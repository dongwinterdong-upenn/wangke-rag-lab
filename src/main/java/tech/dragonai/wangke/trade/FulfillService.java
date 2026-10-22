package tech.dragonai.wangke.trade;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 支付确认 → 自动出码 → 结算上级佣金。整条链上最怕的两件事：
 *
 * <p><b>① 重复入账。</b>微信回调、前端轮询查单、定时对账三条路都调这里，
 * 同一笔交易会到达多次，而且会<b>并发</b>到达。所以幂等靠唯一键裁决，
 * 绝不用「先查 status 再改」—— 那中间有竞态窗口，压测下必然漏。
 *
 * <p><b>② 多发码。</b>一个订单多发一个码就是白送一门课，而且不会有任何异常信号：
 * 订单金额没变、对账表也平，只有课程访问量对不上。所以出码前先按订单已发数收口，
 * 数据库那边还有 trg_activation_code_quota 兜底。
 */
public class FulfillService {

    private final JdbcTemplate jdbc;
    private final PricingService pricing;
    private final TransactionTemplate tx;
    private final CodeVault vault;
    private final SecureRandom rnd = new SecureRandom();

    public FulfillService(JdbcTemplate jdbc, PricingService pricing,
                          TransactionTemplate tx, CodeVault vault) {
        this.jdbc = jdbc; this.pricing = pricing; this.tx = tx; this.vault = vault;
    }

    public record Result(boolean firstTime, int codesIssued, long commissionFen) {}

    /**
     * 确认支付。可安全重复调用与并发调用。
     *
     * @param providerTxnId 微信 transaction_id；对公转账用银行流水号
     */
    public Result confirmPaid(long orderId, String provider, String providerTxnId,
                              long amountFen, LocalDateTime paidAt, Long confirmedBy) {
        // 显式事务，不用 @Transactional 注解。注解靠代理生效，直接 new 出来的对象
        // （测试里就是这么用的）根本不会开事务 —— 于是「中途失败会整体回滚」这条保证
        // 在测试里是假的，而生产里是真的，两边行为不一致，最坏的一种情况。
        // 有限重试。锁竞争在高并发下无法完全消除（幂等键冲突本身就要等锁），
        // 而重试在这里是【安全】的 —— 整个操作幂等，重跑一次不会多发货。
        // 这正是把幂等做扎实之后额外拿到的好处。
        org.springframework.dao.TransientDataAccessException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return tx.execute(st -> doConfirm(orderId, provider, providerTxnId, amountFen,
                                                  paidAt, confirmedBy));
            } catch (org.springframework.dao.TransientDataAccessException e) {
                last = e;
                try { Thread.sleep(20L * (attempt + 1)); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }
        throw last;
    }

    private Result doConfirm(long orderId, String provider, String providerTxnId,
                             long amountFen, LocalDateTime paidAt, Long confirmedBy) {

        // 先【快照读】订单，再进幂等闸门。
        //
        // 上一版把两件事合成一条 INSERT ... SELECT ... FROM biz_order，压测下必然死锁：
        // INSERT...SELECT 会对读到的订单行加共享锁，12 个并发事务全都握着同一行的 S 锁，
        // 而胜出的那个接着要 UPDATE 同一行拿排他锁 —— 锁环闭合。
        // 生产上三条回调路径同时打进来就会复现，且表现为随机 500。
        // 拆成"普通 SELECT（MVCC 快照，不加锁）+ 普通 INSERT（字面值）"即可解开。
        var orders = jdbc.queryForList(
            "SELECT o.order_no, o.amount_fen, o.code_qty_snapshot, o.buyer_agent_id,"
          + " o.buyer_agent_level, o.sku_id, o.status,"
          + " (SELECT course_id FROM sku WHERE id=o.sku_id) AS course_id_hint"
          + " FROM biz_order o WHERE o.id=?", orderId);
        if (orders.isEmpty()) throw new IllegalStateException("订单不存在：" + orderId);
        var order = orders.get(0);

        // 金额必须与订单应付逐笔核对，挡住改价与串单
        long due = ((Number) order.get("amount_fen")).longValue();
        if (amountFen != due)
            throw new IllegalStateException("实付 " + amountFen + " 与应付 " + due
                    + " 不符：拒绝入账，人工核对（改价或串单）");

        // 幂等闸门：唯一键 (provider, provider_txn_id)。并发时只有一个连接能插进去，
        // 其余收到 DuplicateKey —— 这才是裁决点，不是先 SELECT status 再判断。
        try {
            jdbc.update(
                "INSERT INTO payment_record (order_id,order_no,provider,provider_txn_id,"
              + "amount_fen,status,confirmed_by,paid_at) VALUES (?,?,?,?,?,'SUCCESS',?,?)",
                orderId, order.get("order_no"), provider, providerTxnId,
                amountFen, confirmedBy, paidAt);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return new Result(false, 0, 0);          // 这笔交易已经入过账，直接返回
        }

        // 收款渠道。ALIPAY 传 null 是有意的：PC 收银台与 WAP 收银台的区别只有
        // 【发起支付那一步】知道（/api/pay/create 已经把 ALIPAY_PAGE / ALIPAY_WAP 写在订单上），
        // 回调这里拿不到，所以用 COALESCE 保留订单上已有的那个值。
        //
        // 原来这里写死成「WECHAT → WECHAT_NATIVE，其余 → BANK_TRANSFER」，
        // 于是每一笔支付宝订单都被记成对公转账 —— 对账按渠道切分时全错，
        // 而且不报任何错：数字是对的，只是记到了别人头上。
        String channel = switch (provider) {
            case "WECHAT" -> "WECHAT_NATIVE";
            case "ALIPAY" -> null;
            default -> "BANK_TRANSFER";
        };
        jdbc.update("UPDATE biz_order SET status='PAID', paid_amount_fen=?, paid_at=?, "
                  + "pay_channel=COALESCE(?, pay_channel) WHERE id=? AND status='CREATED'",
                amountFen, paidAt, channel, orderId);

        int qty = ((Number) order.get("code_qty_snapshot")).intValue();
        long courseId = ((Number) order.get("course_id_hint")).longValue();
        Number agentId = (Number) order.get("buyer_agent_id");
        int issued = issueCodes(orderId, courseId, agentId == null ? null : agentId.longValue(), qty);

        jdbc.update("UPDATE biz_order SET status='FULFILLED', fulfilled_at=? WHERE id=?",
                paidAt, orderId);

        long comm = settleCommission(orderId, (String) order.get("order_no"),
                ((Number) order.get("sku_id")).longValue(),
                agentId == null ? null : agentId.longValue(),
                order.get("buyer_agent_level") == null ? 0
                        : ((Number) order.get("buyer_agent_level")).intValue(),
                amountFen, qty);

        return new Result(true, issued, comm);
    }

    /**
     * 出码。已发数达到订单快照即停 —— 重放时不会再多发一个。
     *
     * <p><b>package-private 是给 {@link ManualIssueService} 用的，不是疏忽。</b>
     * 手工发码那条路必须调这一份，一个字节都不许复制：明文 → {@code vault.hash} →
     * {@code vault.seal(plain, hash)} → mask 这个顺序不可交换（AAD 绑的就是 code_hash），
     * 抄反了会得到一行插得进去、事后永远解不开的密文 —— 而它不在插入那一刻炸，
     * 是在几周后有人点「查看明文」或代理点「导出」时才炸。
     */
    int issueCodes(long orderId, long courseId, Long ownerAgentId, int qty) {
        Integer already = jdbc.queryForObject(
                "SELECT COUNT(*) FROM activation_code WHERE order_id=?", Integer.class, orderId);
        int need = qty - (already == null ? 0 : already);
        if (need <= 0) return 0;

        // 激活期限来自课程配置，不再写死在这里。同一个业务参数出现在代码的两处，
        // 迟早会有一处被改漏 —— 而"码还能不能激活"改漏了是不会报错的。
        Integer months = jdbc.queryForObject(
                "SELECT activate_months FROM course WHERE id=?", Integer.class, courseId);
        if (months == null) throw new IllegalStateException("课程不存在：" + courseId);
        LocalDateTime deadline = LocalDateTime.now().plusMonths(months);      // G5

        List<Object[]> batch = new ArrayList<>();
        for (int i = 0; i < need; i++) {
            String plain = ActivationCodes.generate(rnd);
            String hash = vault.hash(plain);
            batch.add(new Object[]{hash, vault.seal(plain, hash), ActivationCodes.mask(plain),
                    courseId, ownerAgentId, orderId, deadline});
        }
        jdbc.batchUpdate(
            "INSERT INTO activation_code (code_hash,code_enc,code_masked,course_id,"
          + "owner_agent_id,order_id,activate_deadline) VALUES (?,?,?,?,?,?,?)", batch);
        return need;
    }

    /**
     * 安全取 id。schema 里所有主键都是 BIGINT UNSIGNED，
     * 而 MySQL Connector/J 把 BIGINT UNSIGNED 映射成 <b>BigInteger</b> 而不是 Long ——
     * 直接 (Long) 强转会在运行期抛 ClassCastException。
     * 这个坑在本库里到处都是（每个 id 都是无符号），所以统一走这里。
     */
    static Long asLong(Object v) {
        return v == null ? null : ((Number) v).longValue();
    }

    /** 结算上级佣金。唯一键 (order_id, beneficiary_agent_id) 是重复结算的物理防线。 */
    private long settleCommission(long orderId, String orderNo, long skuId,
                                  Long buyerAgentId, int buyerLevel, long paidFen, int qty) {
        if (buyerAgentId == null || buyerLevel != 2) return 0;      // 只有二级下单才返给一级
        Long parent = jdbc.query(
                "SELECT parent_agent_id FROM agent_profile WHERE id=?",
                rs -> rs.next() ? asLong(rs.getObject("parent_agent_id")) : null, buyerAgentId);
        if (parent == null) return 0;

        var rule = pricing.ruleOf(buyerLevel, skuId);
        if (rule == null) return 0;
        long amount = rule.computeFen(paidFen, qty);
        if (amount <= 0) return 0;

        try {
            jdbc.update(
                "INSERT INTO commission_record (beneficiary_agent_id,source_agent_id,order_id,order_no,"
              + "rule_id,calc_mode_snapshot,calc_basis_fen,amount_fen,freeze_until) "
              + "VALUES (?,?,?,?,?,?,?,?,DATE_ADD(NOW(3), INTERVAL ? DAY))",
                parent, buyerAgentId, orderId, orderNo, rule.ruleId(), rule.calcMode(),
                paidFen, amount, rule.freezeDays());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return 0;                                   // 已结过，重放不再加一笔
        }
        return amount;
    }

}
