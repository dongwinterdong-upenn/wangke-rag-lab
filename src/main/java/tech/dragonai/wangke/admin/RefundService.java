package tech.dragonai.wangke.admin;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tech.dragonai.wangke.auth.AuthInterceptor;
import tech.dragonai.wangke.web.ApiException;

import java.security.SecureRandom;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 退款单的申请 / 审批 / 打款回执。
 *
 * <h3>不绕过 trg_refund_precheck</h3>
 * 「激活码一旦激活，概不退费」（录音 01:50）在钱这一侧的落点就是那条触发器：
 * 只要订单里有一张 BOUND 的码，整单退款（scope=FULL）就会被拒。这一层的职责<b>不是</b>
 * 把这条规则再实现一遍（那样两处口径迟早分叉，而分叉的那一天不会有任何提示），
 * 而是在被拒时告诉运营「那还能怎么办」——退未激活的码。
 *
 * <h3>状态推进靠带条件的 UPDATE，钱的动作跟在它后面</h3>
 * {@code UPDATE ... WHERE id=? AND status='APPROVED'} 影响 1 行，就是「我抢到了这次状态迁移」，
 * 只有抢到的那个请求才继续往下动订单金额。写成「先查状态再改」的话，
 * 审批按钮被双击就会退两次款，而两条退款单各自都不超过实付，
 * 库里那条 {@code ck_order_amount} 也拦不住（它比的是累计值，两次分别写入时每次都合法）。
 * 真正拦住第二次的是 {@code trg_refund_precheck} 里那句「在途退款也占额度」，
 * 但依赖它兜底意味着运营会看到一个技术性拒绝而不是「已经退过了」。
 */
@Service
public class RefundService {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final AdminAudit audit;
    private final SecureRandom rnd = new SecureRandom();

    public RefundService(JdbcTemplate jdbc, TransactionTemplate tx, AdminAudit audit) {
        this.jdbc = jdbc; this.tx = tx; this.audit = audit;
    }

    // ---------------------------------------------------------------- 申请

    /**
     * 发起退款单（状态 APPLIED）。
     *
     * @param scope UNUSED_CODES = 只退未激活的码（G8）；FULL = 整单；MANUAL = 人工特批金额
     */
    public Map<String, Object> apply(HttpServletRequest req, long orderId, long amountFen,
                                     String scope, String reason) {
        long operator = AuthInterceptor.userId(req);
        if (amountFen <= 0)
            throw ApiException.badRequest("退款金额必须大于 0（单位：分）");
        String sc = AdminQuery.enumOf(scope, "scope", "UNUSED_CODES", "FULL", "MANUAL");
        if (sc == null) throw ApiException.badRequest("scope 必须是 UNUSED_CODES / FULL / MANUAL");
        if (AdminQuery.blank(reason)) throw ApiException.badRequest("请填写退款原因");

        var orders = jdbc.queryForList(
                "SELECT order_no, amount_fen, paid_amount_fen, refunded_amount_fen, status,"
              + " code_qty_snapshot FROM biz_order WHERE id=?", orderId);
        if (orders.isEmpty()) throw ApiException.notFound("订单不存在");
        var o = orders.get(0);

        for (int attempt = 0; attempt < 5; attempt++) {
            String no = nextNo("RF");
            try {
                jdbc.update(
                    "INSERT INTO refund_record (refund_no,order_id,amount_fen,reason,scope,applied_by)"
                  + " VALUES (?,?,?,?,?,?)", no, orderId, amountFen, reason.trim(), sc, operator);
                long id = jdbc.queryForObject("SELECT id FROM refund_record WHERE refund_no=?",
                        Number.class, no).longValue();
                audit.write(req, "REFUND_APPLY", "refund_record", id, null,
                        AdminJson.map("refundNo", no, "orderId", orderId,
                                "orderNo", o.get("order_no"), "amountFen", amountFen,
                                "scope", sc, "reason", reason.trim(), "status", "APPLIED"));
                return AdminJson.ok("refundId", id, "refundNo", no, "status", "APPLIED",
                        "amountFen", amountFen, "amountYuan", AdminQuery.yuan(amountFen),
                        "message", "退款单已创建，待审批");
            } catch (DuplicateKeyException e) {
                // 单号撞了就换一个。连撞 5 次说明随机源坏了，不能无限试下去
                if (attempt == 4) throw e;
            } catch (DataAccessException e) {
                throw DbSignal.explain(e, hintFor(orderId, sc));
            }
        }
        throw new IllegalStateException("unreachable");
    }

    /** 触发器拒绝时补上的「那还能怎么办」。 */
    private String hintFor(long orderId, String scope) {
        if (!"FULL".equals(scope)) return null;
        Integer bound = jdbc.queryForObject(
                "SELECT COUNT(*) FROM activation_code WHERE order_id=? AND status='BOUND'",
                Integer.class, orderId);
        Integer issued = jdbc.queryForObject(
                "SELECT COUNT(*) FROM activation_code WHERE order_id=? AND status='ISSUED'",
                Integer.class, orderId);
        if (bound == null || bound == 0) return null;
        return "本单已有 " + bound + " 张码被激活、" + (issued == null ? 0 : issued)
             + " 张仍未激活。可以改用 scope=UNUSED_CODES 只退未激活的那部分；"
             + "已激活的按「激活即不退」不退费 —— 这是业务铁律，不是这里能放行的";
    }

    // ---------------------------------------------------------------- 审批

    public Map<String, Object> approve(HttpServletRequest req, long refundId) {
        long operator = AuthInterceptor.userId(req);
        var before = load(refundId);
        int rows;
        try {
            rows = jdbc.update("UPDATE refund_record SET status='APPROVED', approved_by=?"
                             + " WHERE id=? AND status='APPLIED'", operator, refundId);
        } catch (DataAccessException e) {
            throw DbSignal.explain(e, null);
        }
        if (rows == 0) return notChanged(before, "APPLIED", "APPROVED", "审批通过");
        var after = load(refundId);
        audit.write(req, "REFUND_APPROVE", "refund_record", refundId, before, after);
        return AdminJson.ok("changed", true, "status", "APPROVED",
                "message", "已通过审批。钱还没退出去 —— 实际打款后要回来标记打款结果");
    }

    public Map<String, Object> reject(HttpServletRequest req, long refundId, String reason) {
        if (AdminQuery.blank(reason)) throw ApiException.badRequest("请填写驳回理由");
        long operator = AuthInterceptor.userId(req);
        var before = load(refundId);
        int rows;
        try {
            rows = jdbc.update("UPDATE refund_record SET status='REJECTED', approved_by=?"
                             + " WHERE id=? AND status='APPLIED'", operator, refundId);
        } catch (DataAccessException e) {
            throw DbSignal.explain(e, null);
        }
        if (rows == 0) return notChanged(before, "APPLIED", "REJECTED", "驳回");
        var after = load(refundId);
        after.put("reason", reason.trim());
        audit.write(req, "REFUND_REJECT", "refund_record", refundId, before, after);
        return AdminJson.ok("changed", true, "status", "REJECTED", "message", "已驳回");
    }

    // ---------------------------------------------------------------- 打款回执

    /**
     * 标记退款已到账。这一步才真正把钱记到订单上。
     *
     * @param providerRefundId 渠道退款单号。它上面有唯一键，同一笔渠道退款不可能被记两次
     */
    public Map<String, Object> settle(HttpServletRequest req, long refundId, String providerRefundId) {
        if (AdminQuery.blank(providerRefundId))
            throw ApiException.badRequest("请填写渠道退款单号：没有它这笔退款事后无法与渠道对账");

        long operator = AuthInterceptor.userId(req);
        return tx.execute(st -> {
            var before = load(refundId);
            int rows;
            try {
                // 这一条就是裁决点：抢到 1 行的那个请求才有资格动订单上的钱。
                // FAILED 也能重试，与 trg_refund_state 的 SUCCESS 前置状态一致
                rows = jdbc.update(
                        "UPDATE refund_record SET status='SUCCESS', provider_refund_id=?,"
                      + " finished_at=NOW(3) WHERE id=? AND status IN ('APPROVED','FAILED')",
                        providerRefundId.trim(), refundId);
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("这个渠道退款单号已经被另一张退款单登记过了，请核对后重填");
            } catch (DataAccessException e) {
                throw DbSignal.explain(e, null);
            }
            if (rows == 0) return notChanged(before, "APPROVED", "SUCCESS", "标记打款成功");

            var r = jdbc.queryForMap(
                    "SELECT order_id, amount_fen, scope FROM refund_record WHERE id=?", refundId);
            long orderId = AdminQuery.longOf(r.get("order_id"), 0);
            long amount = AdminQuery.longOf(r.get("amount_fen"), 0);

            // 订单行加排他锁再算。用一条 UPDATE 里的 IF(...) 表达式是错的：
            // MySQL 的 SET 是从左到右求值的，后面的 status 会看到【已经改过】的
            // refunded_amount_fen，于是判据里那个加号被算了两次
            var o = jdbc.queryForMap(
                    "SELECT paid_amount_fen, refunded_amount_fen, code_qty_snapshot, status FROM biz_order"
                  + " WHERE id=? FOR UPDATE", orderId);
            long paid = AdminQuery.longOf(o.get("paid_amount_fen"), 0);
            long already = AdminQuery.longOf(o.get("refunded_amount_fen"), 0);
            long newRefunded = already + amount;
            int orderCodeQty = ((Number) o.get("code_qty_snapshot")).intValue();
            String scope = String.valueOf(r.get("scope"));

            // 申请退款到实际打款之间可能隔几天。FULL 在申请时没有激活码，不代表
            // 打款这一刻仍然没有；不在最终出钱点重查，就存在“先申请退款、再激活、
            // 最后照常整单退”的竞态。
            Map<String, Object> entitlement = revokeRefundedEntitlements(
                    req, operator, orderId, refundId, scope, paid, newRefunded, orderCodeQty);
            String newStatus = newRefunded >= paid ? "REFUNDED" : "PARTIAL_REFUNDED";
            try {
                jdbc.update("UPDATE biz_order SET refunded_amount_fen=?, status=? WHERE id=?",
                        newRefunded, newStatus, orderId);
            } catch (DataAccessException e) {
                throw DbSignal.explain(e, "订单上的退款累计与实付对不上，请人工核对后再操作");
            }

            Map<String, Object> comm = reverseCommission(
                    req, orderId, newStatus, refundId, paid, newRefunded);

            var after = load(refundId);
            after.put("orderStatus", newStatus);
            after.put("orderRefundedFen", newRefunded);
            audit.write(req, "REFUND_SETTLE", "refund_record", refundId, before, after);

            Map<String, Object> out = new LinkedHashMap<>(AdminJson.ok(
                    "changed", true, "status", "SUCCESS",
                    "orderStatus", newStatus,
                    "orderRefundedFen", newRefunded,
                    "orderRefundedYuan", AdminQuery.yuan(newRefunded)));
            out.putAll(entitlement);
            out.putAll(comm);
            return out;
        });
    }

    /**
     * 退款与课程席位必须在同一个事务里一起减少。
     *
     * <p>{@code UNUSED_CODES} 没有单独的“退几张码”字段，因此按累计退款占整单实付的
     * 比例确定累计应作废数，并向上取整。例：10 张码实付 3490 元，累计退 1000 元，
     * {@code ceil(1000/3490*10)=3}，至少作废 3 张。向下取整会让客户保留的席位价值
     * 高于剩余付款。已经 BOUND 的码绝不动；若未激活码不够，整笔退款事务回滚。
     *
     * <p>{@code MANUAL} 是独立的人工补偿，不自动改变课程权益。
     */
    private Map<String, Object> revokeRefundedEntitlements(
            HttpServletRequest req, long operator, long orderId, long refundId, String scope,
            long paidFen, long cumulativeRefundFen, int orderCodeQty) {
        if ("MANUAL".equals(scope)) return Map.of();
        if (paidFen <= 0 || orderCodeQty <= 0)
            throw ApiException.conflict("订单实付金额或出码数量异常，不能自动核减课程席位");

        Integer bound = jdbc.queryForObject(
                "SELECT COUNT(*) FROM activation_code WHERE order_id=? AND status='BOUND'",
                Integer.class, orderId);
        if ("FULL".equals(scope)) {
            if (bound != null && bound > 0)
                throw ApiException.conflict("退款打款前发现本单已有 " + bound
                        + " 张码被激活：整单退款已不再成立。请改为只退未激活部分");
            if (cumulativeRefundFen != paidFen)
                throw ApiException.conflict("FULL 必须退清订单剩余实付金额；当前累计退款 "
                        + AdminQuery.yuan(cumulativeRefundFen) + " 元，实付 "
                        + AdminQuery.yuan(paidFen) + " 元");
        }

        BigInteger numerator = BigInteger.valueOf(cumulativeRefundFen)
                .multiply(BigInteger.valueOf(orderCodeQty));
        BigInteger denominator = BigInteger.valueOf(paidFen);
        int targetRevoked = numerator.add(denominator).subtract(BigInteger.ONE)
                .divide(denominator).intValueExact();
        targetRevoked = Math.min(orderCodeQty, targetRevoked);

        Integer revoked = jdbc.queryForObject(
                "SELECT COUNT(*) FROM activation_code WHERE order_id=? AND status='REVOKED'",
                Integer.class, orderId);
        int need = targetRevoked - (revoked == null ? 0 : revoked);
        if (need <= 0)
            return AdminJson.map("codesRevoked", 0, "totalCodesRevoked", targetRevoked);

        var ids = jdbc.queryForList(
                "SELECT id FROM activation_code WHERE order_id=? AND status='ISSUED'"
              + " ORDER BY id DESC LIMIT ? FOR UPDATE", Number.class, orderId, need);
        if (ids.size() != need)
            throw ApiException.conflict("退款需要累计作废 " + targetRevoked + " 张码，但当前只有 "
                    + ids.size() + " 张可作废；可能有学员在退款审批后完成了激活，请重新核对退款范围");

        for (Number id : ids) {
            jdbc.update("UPDATE activation_code SET status='REVOKED', revoked_by=?, revoked_at=NOW(3),"
                      + " revoke_reason=? WHERE id=? AND status='ISSUED'",
                    operator, "退款单 #" + refundId + " 已成功退款", id.longValue());
        }
        audit.write(req, "REFUND_CODES_REVOKE", "refund_record", refundId,
                AdminJson.map("orderId", orderId, "previousRevoked", revoked == null ? 0 : revoked),
                AdminJson.map("newlyRevoked", need, "totalRevoked", targetRevoked,
                        "scope", scope, "cumulativeRefundFen", cumulativeRefundFen));
        return AdminJson.map("codesRevoked", need, "totalCodesRevoked", targetRevoked);
    }

    public Map<String, Object> fail(HttpServletRequest req, long refundId, String reason) {
        var before = load(refundId);
        int rows;
        try {
            rows = jdbc.update("UPDATE refund_record SET status='FAILED', finished_at=NOW(3)"
                             + " WHERE id=? AND status='APPROVED'", refundId);
        } catch (DataAccessException e) {
            throw DbSignal.explain(e, null);
        }
        if (rows == 0) return notChanged(before, "APPROVED", "FAILED", "标记打款失败");
        var after = load(refundId);
        after.put("reason", reason);
        audit.write(req, "REFUND_FAIL", "refund_record", refundId, before, after);
        return AdminJson.ok("changed", true, "status", "FAILED",
                "message", "已标记打款失败，可修正后重新标记成功");
    }

    // ---------------------------------------------------------------- 佣金

    /**
     * 退款按累计退款占实付的比例冲正佣金，每次只处理相比上次新增的差额。
     *
     * <p>PENDING 佣金尚未进余额，只增加 reversed_fen；已结算或进入提现流程的佣金
     * 同时写 REFUND_OFFSET 台账并扣减代理余额。整单退完时状态转 REVOKED。
     * 冲正后允许出现负余额：这表示代理已提走的佣金尚未偿还，后续佣金入账会自然抵扣。
     */
    private Map<String, Object> reverseCommission(HttpServletRequest req, long orderId,
                                                  String orderStatus, long refundId,
                                                  long paidFen, long cumulativeRefundFen) {
        var rows = jdbc.queryForList(
                "SELECT id, beneficiary_agent_id, amount_fen, reversed_fen, status"
              + " FROM commission_record WHERE order_id=? FOR UPDATE", orderId);
        if (rows.isEmpty()) return Map.of();
        var c = rows.get(0);
        long commissionId = AdminQuery.longOf(c.get("id"), 0);
        long agentId = AdminQuery.longOf(c.get("beneficiary_agent_id"), 0);
        long original = AdminQuery.longOf(c.get("amount_fen"), 0);
        long reversed = AdminQuery.longOf(c.get("reversed_fen"), 0);
        String was = String.valueOf(c.get("status"));

        long target = BigInteger.valueOf(original)
                .multiply(BigInteger.valueOf(cumulativeRefundFen))
                .add(BigInteger.valueOf(paidFen / 2))
                .divide(BigInteger.valueOf(paidFen)).longValueExact();
        target = Math.min(original, target);
        long delta = target - reversed;
        if (delta <= 0) return AdminJson.map("commissionReversedFen", reversed);

        boolean full = "REFUNDED".equals(orderStatus);
        boolean credited = "SETTLED".equals(was) || "WITHDRAWING".equals(was)
                || "WITHDRAWN".equals(was);

        long balanceAfter = Long.MIN_VALUE;
        if (credited) {
            var balances = jdbc.queryForList(
                    "SELECT commission_balance_fen FROM agent_balance WHERE agent_id=? FOR UPDATE",
                    agentId);
            if (balances.isEmpty())
                throw ApiException.conflict("佣金已结算但代理余额账户不存在，不能安全冲正");
            long before = AdminQuery.longOf(balances.get(0).get("commission_balance_fen"), 0);
            balanceAfter = before - delta;
            String ref = refundId + ":" + commissionId;
            jdbc.update("INSERT INTO commission_ledger (agent_id,biz_type,ref_no,direction,"
                      + "amount_fen,balance_before,balance_after,remark) "
                      + "VALUES (?,'REFUND_OFFSET',?,'OUT',?,?,?,?)",
                    agentId, ref, delta, before, balanceAfter,
                    "订单退款冲正佣金；退款单 #" + refundId);
            jdbc.update("UPDATE agent_balance SET commission_balance_fen=? WHERE agent_id=?",
                    balanceAfter, agentId);
        }

        jdbc.update("UPDATE commission_record SET reversed_fen=?, status=?,"
                  + " revoked_at=IF(?='REVOKED',NOW(3),revoked_at),"
                  + " revoke_reason=IF(?='REVOKED',?,revoke_reason) WHERE id=?",
                target, full ? "REVOKED" : was,
                full ? "REVOKED" : was, full ? "REVOKED" : was,
                "订单累计退款已全额冲正（退款单 #" + refundId + "）", commissionId);

        audit.write(req, "COMMISSION_REFUND_OFFSET", "commission_record", commissionId,
                AdminJson.map("status", was, "reversedFen", reversed),
                AdminJson.map("status", full ? "REVOKED" : was, "reversedFen", target,
                        "deltaFen", delta, "balanceAfter", credited ? balanceAfter : null,
                        "orderId", orderId, "refundId", refundId));
        return AdminJson.map("commissionReversedFen", target,
                "commissionReversedThisTimeFen", delta,
                "commissionBalanceAfterFen", credited ? balanceAfter : null,
                "commissionRevoked", full);
    }

    // ---------------------------------------------------------------- 内部

    Map<String, Object> load(long refundId) {
        var rows = jdbc.queryForList(
                "SELECT id, refund_no, order_id, amount_fen, scope, status, applied_by,"
              + " approved_by, provider_refund_id FROM refund_record WHERE id=?", refundId);
        if (rows.isEmpty()) return AdminJson.map();
        var r = rows.get(0);
        return AdminJson.map("refundId", AdminQuery.id(r.get("id")),
                "refundNo", r.get("refund_no"), "orderId", AdminQuery.id(r.get("order_id")),
                "amountFen", r.get("amount_fen"), "scope", r.get("scope"),
                "status", r.get("status"), "appliedBy", AdminQuery.id(r.get("applied_by")),
                "approvedBy", AdminQuery.id(r.get("approved_by")),
                "providerRefundId", r.get("provider_refund_id"));
    }

    private Map<String, Object> notChanged(Map<String, Object> before, String from,
                                           String to, String action) {
        if (before.isEmpty()) throw ApiException.notFound("退款单不存在");
        String now = String.valueOf(before.get("status"));
        if (to.equals(now))
            return AdminJson.ok("changed", false, "status", now,
                    "message", "这张退款单已经" + action + "过了，无需重复操作");
        throw ApiException.conflict("当前状态是 " + now + "，不能" + action
                + "（只有 " + from + " 状态的退款单可以" + action + "）");
    }

    private String nextNo(String prefix) {
        StringBuilder sb = new StringBuilder(prefix);
        sb.append(LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE));
        for (int i = 0; i < 10; i++) sb.append((char) ('0' + rnd.nextInt(10)));
        return sb.toString();
    }
}
