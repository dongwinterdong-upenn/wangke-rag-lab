package tech.dragonai.wangke.admin;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tech.dragonai.wangke.auth.AuthInterceptor;
import tech.dragonai.wangke.web.ApiException;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 提现审批与打款落账。
 *
 * <h3>为什么「通过」要连推两步</h3>
 * {@code trg_withdraw_state} 不允许 APPLIED 直接变 APPROVED，必须经过 REVIEWING。
 * 那条规则真正防的是 <b>APPLIED 直接改成 PAID</b> —— 绕过风控与财务把钱打出去。
 * 一个审核人点「通过」，语义上就是「我看过了，并且我通过」，所以这里在<b>同一个事务</b>里
 * 推 APPLIED → REVIEWING → APPROVED，两步各写一条审计。打款是另一个接口、另一次调用、
 * 另一条审计 —— 钱离开账户的那一步永远不和审批合并，这才是那条规则要守的东西。
 *
 * <h3>为什么打款必须同时扣佣金余额</h3>
 * {@code trg_withdraw_precheck} 里的在途占用统计 APPLIED/REVIEWING/APPROVED/PAYING/FAILED，
 * <b>不含 PAID</b>。也就是说：只要标记已打款而不扣余额，同一笔佣金余额可以被提现无限次，
 * 每一次都能过闸、每一次都会真的打款出去。这不是「以后补上」的活，是标记已打款这个动作
 * 的另一半。所以两者在同一个事务里，要么一起成立要么一起不成立。
 *
 * <p>幂等由 {@code commission_ledger} 的唯一键 (biz_type, ref_no) 裁决 —— 同一张提现单
 * 只可能扣一次余额，重复点「已打款」不会把余额扣两遍。
 *
 * <h3>打款失败不是终点，所以它继续占着额度</h3>
 * FAILED 的单子钱没出去、余额也没扣，但 {@code trg_withdraw_state} 允许它回到 APPROVED
 * 重新打款。所以 {@code trg_withdraw_precheck} 的在途集合里含 FAILED
 * （见 20260814_提现失败单占用在途额度.sql）：不含它的话，代理照着「打款失败，钱还在你的
 * 余额里，可以重新申请」再提一张，平台又把那张 FAILED 重新打了 —— 同一笔余额出账两次，
 * 余额被扣成负数，而每一步单看都合法、零报错。
 *
 * <p>因此 FAILED 必须有两条出路，缺一条它就是个占死额度的结：
 * <b>重新审核通过 → 再打一次</b>（{@link #approve} 接受 FAILED），
 * 或 <b>驳回</b>（{@link #reject} 也接受 FAILED，终态，额度当场释放）。
 */
// 见 agent/WithdrawService 上的同一条说明：两个包里同名类，bean 名必须显式区分。
@Service("adminWithdrawService")
public class WithdrawService {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final AdminAudit audit;

    public WithdrawService(JdbcTemplate jdbc, TransactionTemplate tx, AdminAudit audit) {
        this.jdbc = jdbc; this.tx = tx; this.audit = audit;
    }

    // ---------------------------------------------------------------- 审批

    public Map<String, Object> approve(HttpServletRequest req, long id, String note) {
        long operator = AuthInterceptor.userId(req);
        return tx.execute(st -> {
            var w = lock(id);
            String status = String.valueOf(w.get("status"));
            if ("APPROVED".equals(status))
                return AdminJson.ok("changed", false, "status", "APPROVED",
                        "message", "这张提现单已经审核通过了");

            try {
                if ("APPLIED".equals(status)) {
                    jdbc.update("UPDATE withdraw_order SET status='REVIEWING', reviewed_by=?,"
                              + " reviewed_at=NOW(3), risk_note=? WHERE id=? AND status='APPLIED'",
                            operator, note, id);
                    audit.write(req, "WITHDRAW_REVIEW", "withdraw_order", id,
                            AdminJson.map("status", "APPLIED"),
                            AdminJson.map("status", "REVIEWING", "reviewedBy", operator, "note", note));
                } else if (!"REVIEWING".equals(status) && !"FAILED".equals(status)) {
                    throw ApiException.conflict("当前状态是 " + status + "，不能审核通过"
                            + "（只有 待审核 / 审核中 / 打款失败 的提现单可以通过）");
                }
                int rows = jdbc.update(
                        "UPDATE withdraw_order SET status='APPROVED', reviewed_by=?, reviewed_at=NOW(3)"
                      + " WHERE id=? AND status IN ('REVIEWING','FAILED')", operator, id);
                if (rows == 0)
                    throw ApiException.conflict("刚刚有另一位操作人在改这张提现单，请刷新后重试");
            } catch (DataAccessException e) {
                throw DbSignal.explain(e, null);
            }

            audit.write(req, "WITHDRAW_APPROVE", "withdraw_order", id,
                    AdminJson.map("status", status),
                    AdminJson.map("status", "APPROVED", "reviewedBy", operator, "note", note));
            return AdminJson.ok("changed", true, "status", "APPROVED",
                    "message", "已审核通过。钱还没出去 —— 实际转账完成后回来标记已打款");
        });
    }

    public Map<String, Object> reject(HttpServletRequest req, long id, String reason) {
        if (AdminQuery.blank(reason)) throw ApiException.badRequest("请填写驳回理由");
        long operator = AuthInterceptor.userId(req);
        var before = load(id);
        if (before.isEmpty()) throw ApiException.notFound("提现单不存在");

        int rows;
        try {
            // FAILED 也能驳回，这是 FAILED 唯一的另一条出路。
            //
            // 打款失败的单子占着代理的可提额度（trg_withdraw_precheck 的在途集合含 FAILED，
            // 见 20260814_提现失败单占用在途额度.sql）—— 因为它随时可能被重新审批打款。
            // 如果只有「重新打款」一条路，一笔永远打不出去的款（账号销户、主体注销）
            // 就会把这个代理的额度永久占死，而他在界面上找不到任何能解开它的动作。
            // 驳回是终态，额度当场释放，他可以改好收款信息重新申请。
            rows = jdbc.update("UPDATE withdraw_order SET status='RISK_REJECTED', risk_note=?,"
                             + " reviewed_by=?, reviewed_at=NOW(3) WHERE id=? AND status IN"
                             + " ('APPLIED','REVIEWING','FAILED')", reason.trim(), operator, id);
        } catch (DataAccessException e) {
            throw DbSignal.explain(e, null);
        }
        if (rows == 0) {
            String now = String.valueOf(before.get("status"));
            if ("RISK_REJECTED".equals(now))
                return AdminJson.ok("changed", false, "status", now, "message", "这张提现单已经被驳回了");
            throw ApiException.conflict("当前状态是 " + now + "，不能驳回"
                    + "（只有 待审核 / 审核中 / 打款失败 的提现单可以驳回）");
        }
        var after = load(id);
        after.put("reason", reason.trim());
        audit.write(req, "WITHDRAW_REJECT", "withdraw_order", id, before, after);
        return AdminJson.ok("changed", true, "status", "RISK_REJECTED",
                "message", "已驳回，占用的可提现额度同时释放。"
                        + "钱没有出过账，代理可以改好收款信息重新申请");
    }

    // ---------------------------------------------------------------- 打款

    public Map<String, Object> markPaid(HttpServletRequest req, long id, String providerTxnId,
                                        Long taxWithheldFen, Long actualPaidFen, String paidAtRaw) {
        if (AdminQuery.blank(providerTxnId))
            throw ApiException.badRequest("请填写打款流水号：没有它这笔钱事后无法与银行/微信对账");
        LocalDateTime paidAt = AdminQuery.blank(paidAtRaw)
                ? LocalDateTime.now() : AdminQuery.time(paidAtRaw, false, "paidAt");
        if (paidAt.isAfter(LocalDateTime.now().plusMinutes(5)))
            throw ApiException.badRequest("打款时间不能晚于当前时间，请按回单填写");

        return tx.execute(st -> {
            var w = lock(id);
            String status = String.valueOf(w.get("status"));
            if ("PAID".equals(status))
                return AdminJson.ok("changed", false, "status", "PAID",
                        "message", "这张提现单已经标记过打款了，余额没有被重复扣减");

            long amount = AdminQuery.longOf(w.get("amount_fen"), 0);
            long agentId = AdminQuery.longOf(w.get("agent_id"), 0);
            String withdrawNo = String.valueOf(w.get("withdraw_no"));

            long tax = taxWithheldFen == null ? AdminQuery.longOf(w.get("tax_withheld_fen"), 0) : taxWithheldFen;
            long actual = actualPaidFen == null ? amount - tax : actualPaidFen;
            if (tax < 0 || actual < 0)
                throw ApiException.badRequest("代扣税与实付金额都不能为负");
            // 这条判据库里也有（trg_withdraw_state），这里先判一次只为了给一句人话：
            // 触发器说的是"必须等于"，运营需要看到差了多少、差在哪
            if (actual + tax != amount)
                throw ApiException.badRequest("对不上账：实付 " + AdminQuery.yuan(actual)
                        + " 元 + 代扣个税 " + AdminQuery.yuan(tax) + " 元 = "
                        + AdminQuery.yuan(actual + tax) + " 元，而提现申请金额是 "
                        + AdminQuery.yuan(amount) + " 元，差 "
                        + AdminQuery.yuan(Math.abs(amount - actual - tax)) + " 元");

            // 这里的「先读状态再分支」不是竞态：上面的 lock() 已经对这一行加了排他锁，
            // 本事务提交前没有第二个人能读到并改动它。无锁的先查后写才是那个经典的错
            if ("APPROVED".equals(status)) {
                jdbc.update("UPDATE withdraw_order SET status='PAYING' WHERE id=? AND status='APPROVED'", id);
            } else if (!"PAYING".equals(status)) {
                // FAILED 单独给一句能照着做的话。后台此前对打款失败的单子恰恰只显示
                // 「标记已打款」这一个按钮，点下去必定撞到这里 —— 一条走不通的路配一句
                // 说不清下一步的提示，运营只能去找研发
                if ("FAILED".equals(status))
                    throw ApiException.conflict("这张单上一次打款失败了，不能直接标记已打款。"
                            + "改好收款信息后先点「重新审核通过」，再登记打款；"
                            + "这笔钱确实打不出去就驳回，额度会退回给代理");
                throw ApiException.conflict("当前状态是 " + status + "，不能标记已打款"
                        + "（打款前必须经过 审核 → 通过，不可跳步）");
            }
            // status='PAYING' 时不再重推一次 PAYING：trg_withdraw_state 要求
            // NEW='PAYING' 时 OLD 必须是 APPROVED，原地重推会被它当场拒绝。
            // 会走到这个分支，说明上一次标记打款在中途断了，续做即可

            boolean deducted = deductBalance(agentId, withdrawNo, amount, id);

            // 佣金记录跟着结清。条件里带 WITHDRAWING：申请侧若还没把佣金挂到这张提现单上，
            // 这里就是一次无害的空操作，不会把不相干的佣金误标成已提现
            int settled = jdbc.update(
                    "UPDATE commission_record SET status='WITHDRAWN' WHERE withdraw_order_id=?"
                  + " AND status='WITHDRAWING'", id);

            try {
                int rows = jdbc.update(
                        "UPDATE withdraw_order SET status='PAID', actual_paid_fen=?, tax_withheld_fen=?,"
                      + " provider_txn_id=?, paid_at=? WHERE id=? AND status='PAYING'",
                        actual, tax, providerTxnId.trim(), paidAt, id);
                if (rows == 0) throw ApiException.conflict("提现单状态刚刚被改动，请刷新后重试");
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("这个打款流水号已经登记在另一张提现单上了，请核对后重填");
            } catch (DataAccessException e) {
                throw DbSignal.explain(e, null);
            }

            audit.write(req, "WITHDRAW_PAID", "withdraw_order", id,
                    AdminJson.map("status", status, "amountFen", amount),
                    AdminJson.map("status", "PAID", "actualPaidFen", actual, "taxWithheldFen", tax,
                            "providerTxnId", providerTxnId.trim(), "paidAt", String.valueOf(paidAt),
                            "balanceDeducted", deducted, "commissionSettled", settled));

            return AdminJson.ok("changed", true, "status", "PAID",
                    "actualPaidFen", actual, "taxWithheldFen", tax,
                    "balanceDeducted", deducted, "commissionRecordsSettled", settled,
                    "message", "已标记打款，佣金余额同时扣减 " + AdminQuery.yuan(amount) + " 元");
        });
    }

    public Map<String, Object> markFailed(HttpServletRequest req, long id, String reason) {
        if (AdminQuery.blank(reason)) throw ApiException.badRequest("请填写失败原因");
        var before = load(id);
        if (before.isEmpty()) throw ApiException.notFound("提现单不存在");
        int rows;
        try {
            rows = jdbc.update("UPDATE withdraw_order SET status='FAILED', fail_reason=?"
                             + " WHERE id=? AND status='PAYING'", reason.trim(), id);
        } catch (DataAccessException e) {
            throw DbSignal.explain(e, null);
        }
        if (rows == 0)
            throw ApiException.conflict("当前状态是 " + before.get("status")
                    + "，不能标记打款失败（只有 打款中 的提现单可以）");
        var after = load(id);
        after.put("reason", reason.trim());
        audit.write(req, "WITHDRAW_FAIL", "withdraw_order", id, before, after);
        return AdminJson.ok("changed", true, "status", "FAILED",
                "message", "已标记打款失败。钱没有出账、余额也没扣，"
                        + "但这笔金额仍占着该代理的可提额度 —— 这张单还没有终结。"
                        + "改好收款信息后「重新审核通过」再打一次；确实打不出去就驳回，额度当场退回");
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 扣佣金余额：先写台账再改余额，余额恒等于最新一行的 balance_after。
     *
     * @return true = 本次真的扣了；false = 之前已经扣过（唯一键裁决出来的幂等）
     */
    private boolean deductBalance(long agentId, String withdrawNo, long amount, long withdrawId) {
        var bal = jdbc.queryForList(
                "SELECT commission_balance_fen, total_withdrawn_fen FROM agent_balance"
              + " WHERE agent_id=? FOR UPDATE", agentId);
        if (bal.isEmpty())
            // 建单时 trg_withdraw_precheck 就会因为没有余额行而拒绝，走到这里说明数据被人手工动过
            throw ApiException.conflict("该代理没有佣金账户，无法扣减余额。这笔提现不能标记为已打款，"
                    + "请先人工核对佣金台账");
        long before = AdminQuery.longOf(bal.get(0).get("commission_balance_fen"), 0);
        long after = before - amount;

        try {
            jdbc.update("INSERT INTO commission_ledger (agent_id,biz_type,ref_no,direction,"
                      + "amount_fen,balance_before,balance_after,remark) "
                      + "VALUES (?,'WITHDRAW',?,'OUT',?,?,?,?)",
                    agentId, withdrawNo, amount, before, after, "提现打款 #" + withdrawId);
        } catch (DuplicateKeyException e) {
            // (biz_type, ref_no) 唯一：这张提现单的余额早就扣过了。
            // 幂等由这个唯一键裁决，不靠"先查有没有台账再决定要不要扣" —— 后者并发下会扣两次
            return false;
        }
        jdbc.update("UPDATE agent_balance SET commission_balance_fen=?,"
                  + " total_withdrawn_fen = total_withdrawn_fen + ? WHERE agent_id=?",
                after, amount, agentId);
        return true;
    }

    private Map<String, Object> lock(long id) {
        var rows = jdbc.queryForList(
                "SELECT id, withdraw_no, agent_id, amount_fen, tax_withheld_fen, actual_paid_fen,"
              + " status FROM withdraw_order WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()) throw ApiException.notFound("提现单不存在");
        return rows.get(0);
    }

    Map<String, Object> load(long id) {
        var rows = jdbc.queryForList(
                "SELECT id, withdraw_no, agent_id, amount_fen, tax_withheld_fen, actual_paid_fen,"
              + " status, reviewed_by, provider_txn_id FROM withdraw_order WHERE id=?", id);
        if (rows.isEmpty()) return AdminJson.map();
        var r = rows.get(0);
        return AdminJson.map("withdrawId", AdminQuery.id(r.get("id")),
                "withdrawNo", r.get("withdraw_no"), "agentId", AdminQuery.id(r.get("agent_id")),
                "amountFen", r.get("amount_fen"), "taxWithheldFen", r.get("tax_withheld_fen"),
                "actualPaidFen", r.get("actual_paid_fen"), "status", r.get("status"),
                "reviewedBy", AdminQuery.id(r.get("reviewed_by")),
                "providerTxnId", r.get("provider_txn_id"));
    }
}
