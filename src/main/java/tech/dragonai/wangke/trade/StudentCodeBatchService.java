package tech.dragonai.wangke.trade;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tech.dragonai.wangke.web.ApiException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 超管一次生成一批<b>学员</b>激活码。归属可选：不填 = 平台直营，填了 = 算某家代理的。
 *
 * <h3>它和「给代理发码」是两件事，不是一个开关</h3>
 * {@link ManualIssueService} 发的是<b>代理档</b>的码：买家是代理、码进代理的库存、
 * 走代理价目、他再转售给学员。这里发的是<b>学员档</b>的码：平台自己拿去做活动、
 * 补偿、内部账号，谁激活就是谁的。两条路的订单形状不同（买家类型、有没有 buyer_agent_id、
 * 按哪一档取价），硬塞进一条会让其中一条的订单行长成另一条的样子 ——
 * 而订单行是事后唯一还能说明「这批码当初是干什么用的」的东西。
 *
 * <h3>「不归属」是这一轮的主语，也是最容易被悄悄改坏的一条</h3>
 * {@code activation_code.owner_agent_id} 为 NULL 才叫平台直营：学员激活它之后
 * {@code enrollment.attributed_agent_id} 与 {@code sys_user.attributed_agent_id} 都不该被写上
 * 任何代理，佣金也不该多一行。这三件事分散在 {@link tech.dragonai.wangke.learn.ActivationService}
 * 里，没有一处会因为写错而报错 —— 表现是几个月后某家代理的结算单上多出一批他从没卖过的学员，
 * 而那时归属已经按 first-touch 永久锁死、改不回来了。所以 StudentCodeBatchIT 是真的
 * 拿一个学员去 redeem 一个 NULL 归属的码，而不是只断言这一列插进去是 NULL。
 *
 * <h3>出码逻辑一个字节都不复制</h3>
 * 照 {@link ManualIssueService} 的做法代建一张 {@code origin='ADMIN_GIFT'} 的订单，
 * 然后直接调 {@link FulfillService#issueCodes}：明文 → {@code vault.hash} →
 * {@code vault.seal(plain, hash)} → mask 这个顺序不可交换（AAD 绑的就是 code_hash），
 * 抄反了会得到一行插得进去、掩码期限状态全对、学员照样能激活的密文，
 * 只有几周后有人点「导出明文」时才炸 AEADBadTagException —— 那时这批码已经发出去了。
 *
 * <h3>这张单不收钱，而且是库层保证的</h3>
 * {@code amount_fen} 记学员直购目录价 × N（{@code ck_order_amount} 逼着 &gt; 0，
 * 也正好当成「平台送出去多少钱的货」的账），实付恒 0、到账时间恒空。
 * {@code ck_order_gift_unpaid} / {@code ck_order_grant_by} 两条 CHECK 与
 * {@code trg_commission_precheck} / {@code trg_payment_precheck} 两个触发器
 * 保证它变不成收入、结不出佣金、也挂不上支付流水。
 */
public class StudentCodeBatchService {

    /**
     * 一批最多几个码。
     *
     * <p><b>上限是被下游钉死的，不是拍的。</b>一批 = 一张订单 = 一次导出：
     * <ul>
     *   <li>取明文走 {@code AdminCodeExportService}，那里 {@code MAX_EXPORT=500} 且
     *       <b>超限整批 400，不截断</b>。发出 600 个码的那一批，明文<b>一次也导不出来</b> ——
     *       码在库里、学员手上什么都没有，而这批码存在的全部意义就是把明文发出去。
     *       500 是硬顶，任何大于它的上限都是在给自己造一批永远取不出来的码。
     *   <li>指定归属代理时这批码会进代理端「我的激活码」，那一页是
     *       {@code CodeService.list} 全量读回再内存分页（{@code AgentService.codes}），
     *       一批灌几千个进去，那一页对这家代理就再也打不开了。
     *   <li>200 与 {@link ManualIssueService#MAX_CODES} <b>同一个数</b>：两条发码路径
     *       给运营两个不同的上限，他只会以为界面算错了，然后去试到底哪个是真的。
     * </ul>
     * 需要更多就分多批 —— 分批是可见的，导不出明文不是。
     */
    public static final int MAX_BATCH = 200;

    /**
     * 学员档的 sku_code。
     *
     * <p>按 {@code sku_code} 取而不是按 {@code buyer_type='STUDENT'} 取第一条：
     * 哪天多出第二个学员档（比如做活动的短期档），「取第一条」会静默换一个价、
     * 换一门课，而这一页上什么都不会变。取不到就当场炸。
     */
    public static final String SKU_CODE = "STUDENT_DIRECT";

    /**
     * 学员直购在价目表里的层级。{@code agent_price_tier.agent_level} 的注释写着
     * 「1=一级 2=二级 <b>0=学员直购</b>」，取错层级会按代理进货价记这批白送货的账。
     */
    private static final int STUDENT_LEVEL = 0;

    private final JdbcTemplate jdbc;
    private final OrderService orders;
    private final PricingService pricing;
    private final FulfillService fulfill;
    private final TransactionTemplate tx;

    public StudentCodeBatchService(JdbcTemplate jdbc, OrderService orders, PricingService pricing,
                                   FulfillService fulfill, TransactionTemplate tx) {
        this.jdbc = jdbc; this.orders = orders; this.pricing = pricing;
        this.fulfill = fulfill; this.tx = tx;
    }

    /**
     * @param qty          这一批出几个码
     * @param ownerAgentId 归属代理；<b>null = 平台直营</b>，学员激活后不计入任何代理、不结佣金
     * @param grantKey     一次生成意图的幂等键，由前端在对话框<b>打开时</b>生成一次
     */
    public record BatchCmd(int qty, Long ownerAgentId, String reason, String grantKey,
                           long operatorId, String clientIp) {}

    /**
     * 生成结果。
     *
     * <p><b>这里没有明文字段，是判据不是疏忽。</b>明文一旦进了响应体，下一步就有人把它
     * 顺手写进审计快照 —— 而 {@code AdminAuditController} 会原样返回 after_json，
     * 等于给所有能查审计的人留一份永久可兑换的凭据。取明文只能走导出那条路，
     * 那条路对每一个码逐条写审计。
     *
     * @param firstTime false = 这个幂等键已经生成过了，本次一个码都没多发
     */
    public record BatchResult(boolean firstTime, long orderId, String orderNo, int codesIssued,
                              long amountFenTotal, Long ownerAgentId, String ownerName,
                              List<Map<String, Object>> codes) {}

    /** 生成一批。可安全重复提交：同一个 grantKey 第二次调用只会把第一次的结果查回来。 */
    public BatchResult issue(BatchCmd cmd) {
        Ctx c = validate(cmd);
        try {
            return tx.execute(st -> doIssue(cmd, c));
        } catch (DuplicateKeyException e) {
            // 幂等键撞了 = 这次意图已经生成过。绝不回 409：后台按钮会被双击，
            // 把第二次点击变成红色错误框的话，运营的第一反应是「刚才那次没成功」，
            // 于是再点一次 —— 真的又发了一批码出去，而且这批码没人知道它存在。
            String m = e.getMessage() == null ? "" : e.getMessage();
            if (!m.contains("uk_grant_key")) throw e;
            return replay(cmd, c);
        }
    }

    // ------------------------------------------------------------------ 校验

    private record Ctx(long skuId, String skuCode, long courseId, long unitFen, Long priceTierId,
                       Long ownerAgentId, String ownerName) {}

    private Ctx validate(BatchCmd cmd) {
        if (cmd.qty() < 1 || cmd.qty() > MAX_BATCH)
            throw ApiException.badRequest("一批最多生成 " + MAX_BATCH + " 个码（本次 " + cmd.qty()
                    + "），请分多批。上限是导出侧定的：一批就是一张单，"
                    + "超过导出上限之后这批码的明文一次也导不出来");
        if (cmd.reason() == null || cmd.reason().isBlank())
            throw ApiException.badRequest("请填写生成原因：事后有人问「这批码是怎么来的」，只有这一句能答");
        if (cmd.reason().length() > 255)
            throw ApiException.badRequest("生成原因不要超过 255 个字");
        // 形状收死再让它进 uk_grant_key 与三处 LIKE 拼接。不收的话传一个 `%` 进来，
        // 批次筛选与批量导出就都变成「全库白送码」——
        // 有 500 上限和逐条审计兜着，但那是事后查得到，不是当时拦得住。
        ManualIssueService.requireGrantKey(cmd.grantKey());

        Long ownerAgentId = cmd.ownerAgentId();
        String ownerName = null;
        if (ownerAgentId != null) {
            var rows = jdbc.queryForList(
                    "SELECT p.status, p.agent_code, COALESCE(u.real_name, u.nickname, u.phone) AS name"
                  + "  FROM agent_profile p JOIN sys_user u ON u.id = p.user_id WHERE p.id=?",
                    ownerAgentId);
            if (rows.isEmpty()) throw ApiException.notFound("代理不存在");
            var a = rows.get(0);
            // 停用/终止的代理拿到一批新码，等于把库存发给一家已经不该再卖货的公司。
            // 库层这次拦不住：这张单的 buyer_agent_id 是 NULL（归属落在 attributed_agent_id 上），
            // 而 trg_order_insert 那条「代理必须 ACTIVE」只看 buyer_agent_id ——
            // 也就是说这里不拦就<b>没有人拦</b>。
            if (!"ACTIVE".equals(a.get("status")))
                throw ApiException.conflict("这家代理当前不是有效状态（" + a.get("status")
                        + "），不能把码归属给它。先恢复它的状态，或者选「不归属（平台直营）」");
            ownerName = a.get("name") + "（" + a.get("agent_code") + "）";
        }

        var skus = jdbc.queryForList(
                "SELECT id, name, buyer_type, course_id, status FROM sku WHERE sku_code=?", SKU_CODE);
        if (skus.isEmpty())
            throw ApiException.conflict("目录里没有 " + SKU_CODE + " 这一档，学员码挂不到订单上");
        var sku = skus.get(0);
        if (!"STUDENT".equals(sku.get("buyer_type")))
            throw ApiException.conflict("目录里的 " + SKU_CODE + " 被改成了非学员档，"
                    + "这批码会按代理档的口径记账。先把目录改回来");
        if (!"ON".equals(sku.get("status")))
            throw ApiException.conflict("学员直购档已下架。它同时是这批白送码的记账依据，"
                    + "下架状态下的价目不该拿来记账 —— 请先上架");

        long skuId = ((Number) sku.get("id")).longValue();
        PricingService.Price price;
        try {
            price = pricing.priceOf(STUDENT_LEVEL, skuId);
        } catch (IllegalStateException e) {
            // PricingService 抛的原文带内部编号，不能直接透给运营
            throw ApiException.conflict("学员直购这一档没有生效价目。白送的单也要记目录价"
                    + "（它是「平台送出去多少钱的货」这个口径的唯一来源），请先在价目表里配好");
        }

        return new Ctx(skuId, SKU_CODE, ((Number) sku.get("course_id")).longValue(),
                price.amountFen(), price.tierId(), ownerAgentId, ownerName);
    }

    // ------------------------------------------------------------------ 生成

    /**
     * 整批一个事务，一张订单挂 N 个码。
     *
     * <p>{@code code_qty_snapshot} 必须写 N：{@code trg_activation_code_quota} 是按
     * 「本单已发数 &lt; 快照数」收口的，写 1 而发 N 的话第二个码就被库层拒了，
     * 整批回滚 —— 这一条是响的。反过来写 N 而只发 1 才是哑的：那张单从此永远还能
     * 再补 N-1 个码进去，而补码那条路（{@code issueCodes} 的 need 计算）不看是谁在补。
     */
    private BatchResult doIssue(BatchCmd cmd, Ctx c) {
        long amount = c.unitFen() * cmd.qty();
        var created = orders.createForStudentGrant(
                cmd.operatorId(), c.ownerAgentId(), c.skuId(), c.skuCode(), cmd.qty(),
                amount, c.priceTierId(), cmd.operatorId(), cmd.reason().trim(),
                batchKey(cmd.grantKey()), cmd.clientIp());
        int issued = fulfill.issueCodes(created.orderId(), c.courseId(), c.ownerAgentId(), cmd.qty());
        return new BatchResult(true, created.orderId(), created.orderNo(), issued,
                amount, c.ownerAgentId(), c.ownerName(), maskedOf(created.orderId()));
    }

    /**
     * 落库的幂等键。
     *
     * <p>带上 {@code #0} 后缀是为了和 {@link ManualIssueService} 那条路<b>逐字同形</b>：
     * 那边一次发 N 份就是 {@code key#0 … key#(N-1)}，而全仓有三处按
     * {@code grant_key LIKE key + "#%"} 把「一次发放」拢在一起（订单列表的批次筛选、
     * 批量明文导出、手工发码的重放）。这里不带后缀的话，这批单在那三处一条都查不出来，
     * 而它们不会报错 —— 只会显示「这一批共 0 张单」。
     */
    private static String batchKey(String grantKey) { return grantKey.trim() + "#0"; }

    /**
     * 幂等重放：把这个 grantKey 上一次生成的单与码原样查回来。
     *
     * <p>{@code uk_grant_key} 是<b>全表</b>唯一的，两条发码路径共用它。所以重放之前
     * 必须确认捡回来的确实是一批学员码 —— 否则一次「给代理发码」占住的键会让这里
     * 理直气壮地回一句「这批已经生成过了，没有重复生成」，运营看到 changed:false
     * 就不会再试，而那批学员码<b>一个都没发出去</b>，界面上一句错都没有。
     */
    private BatchResult replay(BatchCmd cmd, Ctx c) {
        var rows = jdbc.queryForList(
                "SELECT id, order_no, amount_fen, buyer_type, origin, attributed_agent_id"
              + "  FROM biz_order WHERE grant_key=?", batchKey(cmd.grantKey()));
        if (rows.isEmpty())
            // 键被占住了，但不是被 key#0 占的（比如手工发码发了 20 份，占了 key#0…key#19，
            // 而我们撞的是 key#0 之外的某一条 —— 实际撞不到，但这条路不能靠"实际撞不到"活着）
            throw ApiException.conflict("这个幂等键已经被另一次发放占用了，请关掉重开这个对话框再试"
                    + "（重开会换一个新的键）");
        var o = rows.get(0);
        if (!"ADMIN_GIFT".equals(o.get("origin")) || !"STUDENT".equals(o.get("buyer_type")))
            throw ApiException.conflict("这个幂等键已经被另一次发放占用了（订单 "
                    + o.get("order_no") + " 不是一批学员码），请关掉重开这个对话框再试");

        long orderId = ((Number) o.get("id")).longValue();
        var codes = maskedOf(orderId);
        Object owner = o.get("attributed_agent_id");
        Long ownerId = owner == null ? null : ((Number) owner).longValue();
        // 归属取【库里那一批】的，不是本次请求填的。同一个键第二次提交时把归属改了，
        // 回本次填的那个名字就是当面撒谎：界面显示「已归属给 B」，而码实际归属 A，
        // 而运营正是靠这句话去决定要不要把码发给 B 的。
        return new BatchResult(false, orderId, (String) o.get("order_no"), codes.size(),
                ((Number) o.get("amount_fen")).longValue(),
                ownerId, java.util.Objects.equals(ownerId, c.ownerAgentId())
                        ? c.ownerName() : agentLabel(ownerId), codes);
    }

    /** 代理的可读名字。归属为空（平台直营）时返回 null，别编一个「平台」出来当代理名。 */
    private String agentLabel(Long agentId) {
        if (agentId == null) return null;
        var rows = jdbc.queryForList(
                "SELECT p.agent_code, COALESCE(u.real_name, u.nickname, u.phone) AS name"
              + "  FROM agent_profile p JOIN sys_user u ON u.id = p.user_id WHERE p.id=?", agentId);
        if (rows.isEmpty()) return "代理 #" + agentId;
        return rows.get(0).get("name") + "（" + rows.get(0).get("agent_code") + "）";
    }

    /** 只回掩码。理由见 {@link BatchResult}。 */
    private List<Map<String, Object>> maskedOf(long orderId) {
        return jdbc.query(
                "SELECT id, code_masked FROM activation_code WHERE order_id=? ORDER BY id",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("codeId", rs.getLong("id"));
                    m.put("masked", rs.getString("code_masked"));
                    return m;
                }, orderId);
    }
}
