package tech.dragonai.wangke.trade;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 下单。整条钱链的入口，也是<b>价格快照</b>唯一该被写下来的地方。
 *
 * <p>在这之前，订单是靠测试里手写 INSERT 造出来的 —— 也就是说
 * PricingService 注释里那句"取到的价必须由调用方写进订单快照"，
 * 生产代码里一次都没有人执行过。这一层把它补上。
 *
 * <h3>为什么金额必须快照，不能下单时只存 sku_id 事后再算</h3>
 * 改价是常态。事后按现价复算，会让一张 8 月 1 日下的单在 8 月 12 日显示另一个金额，
 * 对账、退款、佣金三处同时对不上，而且没有任何报错 —— 只有客户来问为什么。
 * 所以 amount_fen 与 price_tier_id 都要落在订单行上：前者是钱，后者是"这个钱哪来的"。
 */
public class OrderService {

    private final JdbcTemplate jdbc;
    private final PricingService pricing;
    private final SecureRandom rnd = new SecureRandom();

    /** 未支付订单的存活时长。到点由定时任务置 CLOSED，释放不了库存但能清干净待办。 */
    private static final int PAY_WINDOW_MINUTES = 120;

    /**
     * 手工发放单的 expire_at 距离现在多久。
     *
     * <p>手工单没有支付窗口这回事，但 expire_at 是 NOT NULL 且 trg_order_insert 要求它
     * 晚于当前时间、又不得超过 10 分钟（留长窗口的话它会混进「待支付订单」的各种
     * 扫描与提醒里，而它永远不会被支付）。2 分钟是这两个约束之间最短的安全值。
     */
    private static final int GRANT_WINDOW_MINUTES = 2;

    public OrderService(JdbcTemplate jdbc, PricingService pricing) {
        this.jdbc = jdbc; this.pricing = pricing;
    }

    public record Created(long orderId, String orderNo, long amountFen, int codeQty,
                          int buyerLevel, Long buyerAgentId) {}

    /**
     * 建单。不涉及钱的移动，只是把"要买什么、按什么价"钉死。
     *
     * @param clientIp 落到订单行上。风控与争议处理都要用，且必须是下单当时的，事后补不回来
     */
    public Created create(long buyerUserId, long skuId, String clientIp) {
        var skus = jdbc.queryForList(
                "SELECT s.sku_code, s.buyer_type, s.code_qty, s.status, "
              + "c.status AS course_status FROM sku s "
              + "JOIN course c ON c.id=s.course_id WHERE s.id=?", skuId);
        if (skus.isEmpty()) throw new IllegalArgumentException("套餐不存在：" + skuId);
        Map<String, Object> sku = skus.get(0);
        if (!"ON".equals(sku.get("status")))
            throw new IllegalStateException("该套餐已下架，无法下单");
        if (!"ON".equals(sku.get("course_status")))
            throw new IllegalStateException("\u8bfe\u7a0b\u5c1a\u672a\u4e0a\u67b6\uff0c\u6682\u65f6\u65e0\u6cd5\u8d2d\u4e70");
        String buyerType = (String) sku.get("buyer_type");
        int codeQty = ((Number) sku.get("code_qty")).intValue();

        var users = jdbc.queryForList(
                "SELECT status, attributed_agent_id FROM sys_user WHERE id=?", buyerUserId);
        if (users.isEmpty()) throw new IllegalArgumentException("下单账号不存在：" + buyerUserId);
        if (!"ACTIVE".equals(users.get(0).get("status")))
            throw new IllegalStateException("账号不在正常状态，无法下单");

        // 代理身份取"当前有效"的那条：PENDING（待审核）与 SUSPENDED（停用）都不算。
        // 停用中的代理还能按代理价进货，是分销系统里很实际的一个口子。
        var agents = jdbc.queryForList(
                "SELECT id, level FROM agent_profile WHERE user_id=? AND status='ACTIVE'", buyerUserId);
        Long agentId = null;
        int level;
        if ("AGENT".equals(buyerType)) {
            if (agents.isEmpty())
                throw new IllegalStateException("该套餐仅限已生效的代理商购买");
            agentId = ((Number) agents.get(0).get("id")).longValue();
            level = ((Number) agents.get(0).get("level")).intValue();
        } else {
            // 学员档。代理本人也可以按学员价买，但那时不该走代理身份 ——
            // 否则 buyer_agent_level 会串到代理档去，佣金也就跟着算错了。
            level = 0;
        }

        // 取价：这家代理有专属价就用专属价，没有才回落层级价。
        //
        // 两个来源的主键**必须分列写**（price_tier_id / price_override_id），不能挤进同一列 ——
        // agent_price_tier 与 agent_price_override 是两张表，17 号既可能是层级价也可能是专属价，
        // 而两者金额不同。塞进一列的表现不是报错：事后复盘「这一单当时是什么价」会查到
        // 一个与 amount_fen 对不上的数，看起来像数据损坏。ck_order_price_source 保证两者至多一个非空。
        //
        // 学员直购（agentId 为 null）没有专属价这回事，走两参重载。
        long amountFen;
        Long tierId = null, overrideId = null;
        if (agentId == null) {
            var price = pricing.priceOf(level, skuId);
            amountFen = price.amountFen();
            tierId = price.tierId();
        } else {
            var quote = pricing.priceOf(agentId, level, skuId);
            amountFen = quote.amountFen();
            tierId = quote.tierId();
            overrideId = quote.overrideId();
        }
        Long attributed = users.get(0).get("attributed_agent_id") == null
                ? null : ((Number) users.get(0).get("attributed_agent_id")).longValue();

        return insert(buyerUserId, buyerType, agentId,
                "AGENT".equals(buyerType) ? level : null,
                skuId, (String) sku.get("sku_code"), codeQty, tierId, overrideId, amountFen,
                "STUDENT".equals(buyerType) ? attributed : null,
                PAY_WINDOW_MINUTES, clientIp,
                "SELF_SERVE", null, null, null, "CREATED");
    }

    /**
     * 超管人工给代理发码时代建的那张单。
     *
     * <p><b>它是一张不收钱的单</b>：origin='ADMIN_GIFT'、直接落在 FULFILLED、
     * paid_amount_fen 保持 0、paid_at 保持 NULL。amount_fen 仍然写目录价 ——
     * 不是因为要收这笔钱，是因为 ck_order_amount 第一句就是 {@code amount_fen > 0}，
     * 0 元单在库层造不出来；而写目录价正好让这张单同时是一份可读的
     * 「平台送出去多少钱的货」的账。库层那两条 CHECK 保证它永远变不成收入。
     *
     * <p><b>为什么不复用 {@link #create} 里那条 {@code course.status='ON'} 校验</b>：
     * 赠课那条路（AdminUserController.grant）刻意允许给还没上架的课开权益，理由是
     * 「内部账号常常要在上架前先看」。同一件事在两条路上给相反答案时，
     * 报出来的错看起来像调用方填错了参数。
     *
     * <p>支付窗口写 2 分钟：手工单没有支付窗口这回事，但 expire_at 是 NOT NULL 且
     * 必须晚于当前时间，所以给一个最短的、又不会被 trg_order_insert 那条
     * 「不得超过 10 分钟」拦下的值。
     *
     * @param grantKey 一次发放意图的幂等键，落在 uk_grant_key 上。重复提交靠它裁决
     */
    public Created createForGrant(long buyerUserId, long agentId, int agentLevel,
                                  long skuId, String skuCode, int codeQty, long amountFen,
                                  Long priceTierId, long grantBy, String grantReason,
                                  String grantKey, String clientIp) {
        return insert(buyerUserId, "AGENT", agentId, agentLevel, skuId, skuCode, codeQty,
                priceTierId, null, amountFen, null, GRANT_WINDOW_MINUTES, clientIp,
                "ADMIN_GIFT", grantBy, grantReason, grantKey, "FULFILLED");
    }

    /**
     * 超管批量生成<b>学员</b>激活码时代建的那张单（{@link StudentCodeBatchService}）。
     *
     * <p>与 {@link #createForGrant} 共用同一条 INSERT，差别只有三处，每一处不这样写
     * 都会安静地坏掉：
     * <ol>
     *   <li><b>buyer_type 写 STUDENT。</b>挂的是学员档 sku，写成 AGENT 的话后台订单列表
     *       按买家类型筛选时这批单会归到「代理订单」里，而金额、码数、单号全都是对的 ——
     *       没有任何一处会报错，只是这批码在按代理盘点时凭空多出来。
     *   <li><b>buyer_agent_id 传 null。</b>「不归属」那一档根本没有代理；而
     *       {@code trg_order_insert} 那条「代理必须 ACTIVE」只在 buyer_agent_id 非空时求值。
     *       把归属代理塞进 buyer_agent_id 的话，这批码会被算成那家代理的<b>进货</b>
     *       （AgentService 的 purchasedCodes / 成交额都按 buyer_agent_id 圈），
     *       而他一分钱没付。
     *   <li><b>归属落在 attributed_agent_id 上。</b>它的语义就是「这单算谁的业绩」，
     *       且全仓每一处拿它算钱的 SQL 都带 {@code paid_at IS NOT NULL}，
     *       而白送单 paid_at 恒 NULL —— 归属看得见，钱一分不进任何人的业绩。
     * </ol>
     *
     * @param buyerUserId  平台自己下的单，买家写操作人。挂到某个学员头上的话，
     *                     那名学员的「我的订单」里会凭空多出一张他没买过的单
     * @param ownerAgentId 归属代理；null = 平台直营
     * @param codeQty      这一张单挂几个码。{@code trg_activation_code_quota} 按它收口
     */
    public Created createForStudentGrant(long buyerUserId, Long ownerAgentId,
                                         long skuId, String skuCode, int codeQty, long amountFen,
                                         Long priceTierId, long grantBy, String grantReason,
                                         String grantKey, String clientIp) {
        return insert(buyerUserId, "STUDENT", null, null, skuId, skuCode, codeQty,
                priceTierId, null, amountFen, ownerAgentId, GRANT_WINDOW_MINUTES, clientIp,
                "ADMIN_GIFT", grantBy, grantReason, grantKey, "FULFILLED");
    }

    /**
     * 全库唯一的一条生产 {@code INSERT INTO biz_order}。
     *
     * <p><b>这一条不许有第二份。</b>两条 INSERT 意味着两套快照口径（金额、数量、
     * 归属代理、层级），改漏一处零信号 —— 订单看起来完全正常，只是某一列是另一套算法
     * 算出来的。自助下单与手工发放的差别全部收在参数里。
     */
    private Created insert(long buyerUserId, String buyerType, Long agentId, Integer agentLevel,
                           long skuId, String skuCode, int codeQty,
                           Long priceTierId, Long priceOverrideId, long amountFen,
                           Long attributedAgentId, int expireMinutes, String clientIp,
                           String origin, Long grantBy, String grantReason, String grantKey,
                           String initialStatus) {
        // 单号冲突走重试，不做"先查再插"：并发下先查再插必然漏。
        for (int attempt = 0; attempt < 5; attempt++) {
            String orderNo = nextOrderNo();
            try {
                jdbc.update(
                    "INSERT INTO biz_order (order_no,buyer_user_id,buyer_type,buyer_agent_id,"
                  + "buyer_agent_level,sku_id,sku_code_snapshot,code_qty_snapshot,price_tier_id,price_override_id,"
                  + "amount_fen,attributed_agent_id,expire_at,client_ip,"
                  + "origin,grant_by,grant_reason,grant_key,status,fulfilled_at) "
                  + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,DATE_ADD(NOW(3), INTERVAL ? MINUTE),?,"
                  + "?,?,?,?,?,IF(?='FULFILLED', NOW(3), NULL))",
                    orderNo, buyerUserId, buyerType, agentId, agentLevel,
                    skuId, skuCode, codeQty, priceTierId, priceOverrideId, amountFen, attributedAgentId,
                    expireMinutes, clientIp,
                    origin, grantBy, grantReason, grantKey, initialStatus, initialStatus);
                long id = jdbc.queryForObject("SELECT id FROM biz_order WHERE order_no=?",
                        Number.class, orderNo).longValue();
                return new Created(id, orderNo, amountFen, codeQty,
                        agentLevel == null ? 0 : agentLevel, agentId);
            } catch (DuplicateKeyException e) {
                // 幂等键撞了要原样抛给调用方去裁决重复提交；只有单号撞了才重试。
                String m = e.getMessage() == null ? "" : e.getMessage();
                if (m.contains("uk_grant_key")) throw e;
                if (attempt == 4) throw e;      // 连撞 5 次说明随机源坏了，不能无限试
            }
        }
        throw new IllegalStateException("unreachable");
    }

    /**
     * 单号：WK + 日期 + 10 位随机。
     *
     * <p>不用自增也不用时间戳序列：单号对外可见，可预测的单号等于把当日成交量
     * 和别人的订单直接送出去（顺序单号在同行竞对那里是标准侦察手段）。
     */
    private String nextOrderNo() {
        StringBuilder sb = new StringBuilder("WK");
        sb.append(LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE));
        for (int i = 0; i < 10; i++) sb.append((char) ('0' + rnd.nextInt(10)));
        return sb.toString();
    }
}
