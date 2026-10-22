package tech.dragonai.wangke.admin;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import tech.dragonai.wangke.auth.AuthInterceptor;
import tech.dragonai.wangke.web.ApiException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 提现审批。业务逻辑在 {@link WithdrawService}，这一层只做鉴权、参数与展示。
 *
 * <p>收款账号在列表里只出掩码，完整账号在详情接口给出<b>并留痕</b>：打款那一刻确实要用它，
 * 但审批列表天天开着，一整列真实银行账号被截图带走一次就够了。
 */
@RestController
@RequestMapping("/api/admin/withdraws")
public class AdminWithdrawController {

    private final JdbcTemplate jdbc;
    private final AdminAudit audit;
    private final WithdrawService withdraws;

    public AdminWithdrawController(JdbcTemplate jdbc, AdminAudit audit, WithdrawService withdraws) {
        this.jdbc = jdbc; this.audit = audit; this.withdraws = withdraws;
    }

    public record NoteReq(String note) {}
    public record ReasonReq(String reason) {}
    public record PaidReq(String providerTxnId, Long taxWithheldFen, Long actualPaidFen,
                          String paidAt) {}

    @GetMapping
    Map<String, Object> list(@RequestParam(required = false) String status,
                             @RequestParam(required = false) Long agentId,
                             @RequestParam(required = false) String from,
                             @RequestParam(required = false) String to,
                             @RequestParam(required = false) Integer page,
                             @RequestParam(required = false) Integer size,
                             HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        String st = AdminQuery.enumOf(status, "status", "APPLIED", "RISK_REJECTED", "REVIEWING",
                "APPROVED", "PAYING", "PAID", "FAILED", "CANCELLED");
        int p = AdminQuery.page(page), sz = AdminQuery.size(size);

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (st != null) { where.append(" AND w.status=?"); args.add(st); }
        if (agentId != null) { where.append(" AND w.agent_id=?"); args.add(agentId); }
        var f = AdminQuery.time(from, false, "from");
        var t = AdminQuery.time(to, true, "to");
        if (f != null) { where.append(" AND w.created_at>=?"); args.add(f); }
        if (t != null) { where.append(" AND w.created_at<=?"); args.add(t); }

        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM withdraw_order w" + where,
                Integer.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(sz); pageArgs.add(AdminQuery.offset(p, sz));
        var rows = jdbc.queryForList(
                "SELECT w.*, a.agent_code, a.company_name, b.commission_balance_fen"
              + " FROM withdraw_order w"
              + " LEFT JOIN agent_profile a ON a.id=w.agent_id"
              + " LEFT JOIN agent_balance b ON b.agent_id=w.agent_id" + where
              + " ORDER BY w.id DESC LIMIT ? OFFSET ?", pageArgs.toArray());

        List<Map<String, Object>> out = new ArrayList<>();
        for (var r : rows) out.add(view(r, false));
        return AdminJson.ok("total", total == null ? 0 : total, "page", p, "size", sz, "items", out);
    }

    @GetMapping("/{id}")
    Map<String, Object> detail(@PathVariable long id, HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        var rows = jdbc.queryForList(
                "SELECT w.*, a.agent_code, a.company_name, a.subject_type, a.settle_bank_name,"
              + " b.commission_balance_fen, u.real_name"
              + " FROM withdraw_order w"
              + " LEFT JOIN agent_profile a ON a.id=w.agent_id"
              + " LEFT JOIN agent_balance b ON b.agent_id=w.agent_id"
              + " LEFT JOIN sys_user u ON u.id=a.user_id WHERE w.id=?", id);
        if (rows.isEmpty()) throw ApiException.notFound("提现单不存在");
        var r = rows.get(0);
        Map<String, Object> v = view(r, true);
        v.put("subjectType", r.get("subject_type"));
        v.put("settleBankName", r.get("settle_bank_name"));
        // §7.4：打款须与实名一致，不允许代收。把两个名字并排放出来，让审核人一眼能比
        v.put("agentRealName", r.get("real_name"));
        v.put("payeeMatchesRealName", r.get("payee_name") != null
                && r.get("payee_name").equals(r.get("real_name")));

        // 在途统计：审核人需要知道「他还有几笔在排队」，否则会出现同一笔余额被批多次
        v.put("inflight", jdbc.queryForList(
                "SELECT status, COUNT(*) AS n, COALESCE(SUM(amount_fen),0) AS amt"
              + " FROM withdraw_order WHERE agent_id=? AND status IN"
              + " ('APPLIED','REVIEWING','APPROVED','PAYING','FAILED') GROUP BY status",
                AdminQuery.longOf(r.get("agent_id"), 0)));

        audit.write(req, "WITHDRAW_ACCOUNT_VIEW", "withdraw_order", id, null,
                AdminJson.map("payeeAccountMasked",
                        AdminQuery.maskAccount((String) r.get("payee_account"))));
        return AdminJson.ok("withdraw", v);
    }

    @PostMapping("/{id}/approve")
    Map<String, Object> approve(@PathVariable long id, @RequestBody(required = false) NoteReq body,
                                HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        return withdraws.approve(req, id, body == null ? null : body.note());
    }

    @PostMapping("/{id}/reject")
    Map<String, Object> reject(@PathVariable long id, @RequestBody(required = false) ReasonReq body,
                               HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        return withdraws.reject(req, id, body == null ? null : body.reason());
    }

    @PostMapping("/{id}/paid")
    Map<String, Object> markPaid(@PathVariable long id, @RequestBody PaidReq body,
                                 HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        if (body == null) throw ApiException.badRequest("请填写打款流水号");
        return withdraws.markPaid(req, id, body.providerTxnId(), body.taxWithheldFen(),
                body.actualPaidFen(), body.paidAt());
    }

    @PostMapping("/{id}/fail")
    Map<String, Object> markFailed(@PathVariable long id, @RequestBody(required = false) ReasonReq body,
                                   HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        return withdraws.markFailed(req, id, body == null ? null : body.reason());
    }

    // ---------------------------------------------------------------- 内部

    private Map<String, Object> view(Map<String, Object> r, boolean full) {
        Map<String, Object> m = new LinkedHashMap<>();
        long amount = AdminQuery.longOf(r.get("amount_fen"), 0);
        m.put("id", AdminQuery.id(r.get("id")));
        m.put("withdrawNo", r.get("withdraw_no"));
        m.put("agentId", AdminQuery.id(r.get("agent_id")));
        m.put("agentCode", r.get("agent_code"));
        m.put("companyName", r.get("company_name"));
        m.put("amountFen", amount);
        m.put("amountYuan", AdminQuery.yuan(amount));
        m.put("taxWithheldFen", r.get("tax_withheld_fen"));
        m.put("actualPaidFen", r.get("actual_paid_fen"));
        m.put("channel", r.get("channel"));
        m.put("payeeName", r.get("payee_name"));
        m.put("payeeAccountMasked", AdminQuery.maskAccount((String) r.get("payee_account")));
        if (full) m.put("payeeAccount", r.get("payee_account"));
        m.put("status", r.get("status"));
        m.put("riskNote", r.get("risk_note"));
        m.put("failReason", r.get("fail_reason"));
        m.put("reviewedBy", AdminQuery.id(r.get("reviewed_by")));
        m.put("reviewedAt", r.get("reviewed_at"));
        m.put("providerTxnId", r.get("provider_txn_id"));
        m.put("invoiceNo", r.get("invoice_no"));
        m.put("paidAt", r.get("paid_at"));
        m.put("createdAt", r.get("created_at"));
        m.put("commissionBalanceFen", r.get("commission_balance_fen"));
        return m;
    }
}
