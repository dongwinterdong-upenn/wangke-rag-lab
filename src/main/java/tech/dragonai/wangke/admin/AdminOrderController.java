package tech.dragonai.wangke.admin;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import tech.dragonai.wangke.auth.AuthInterceptor;
import tech.dragonai.wangke.trade.CodeService;
import tech.dragonai.wangke.trade.FulfillService;
import tech.dragonai.wangke.trade.ManualIssueService;
import tech.dragonai.wangke.web.ApiException;
import tech.dragonai.wangke.web.ClientIp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 订单查询、对公转账人工确认到账、退款审批。
 *
 * <h3>确认到账为什么不自己写 UPDATE</h3>
 * 「收到钱 → 出码 → 结上级佣金」是一条有三个不变量的链（这笔交易只能入账一次、
 * 一单不能多发一个码、一单对同一受益人只能结一次佣金），{@link FulfillService} 里
 * 每一条都靠唯一键裁决。后台自己写一遍 UPDATE 只会得到一条<b>没有这些保证</b>的旁路：
 * 表面上钱记上了，码没发、佣金没结，或者双击一下发了两批码 —— 而多发一个码不会有任何
 * 异常信号，订单金额没变、对账表也平，只有课程访问量对不上。
 * 所以这里只做三件事：核参数、调它、把它的拒绝翻成人话。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminOrderController {

    private final JdbcTemplate jdbc;
    private final AdminAudit audit;
    private final FulfillService fulfill;
    private final RefundService refunds;
    private final CodeService codes;

    public AdminOrderController(JdbcTemplate jdbc, AdminAudit audit,
                                FulfillService fulfill, RefundService refunds, CodeService codes) {
        this.jdbc = jdbc; this.audit = audit; this.fulfill = fulfill; this.refunds = refunds;
        this.codes = codes;
    }

    public record ConfirmReq(String txnNo, Long amountFen, String paidAt, String remark) {}
    public record RefundReq(Long amountFen, String scope, String reason) {}
    public record ReasonReq(String reason) {}
    public record SettleReq(String providerRefundId) {}

    // ---------------------------------------------------------------- 订单查询

    @GetMapping("/orders")
    Map<String, Object> list(@RequestParam(required = false) String status,
                             @RequestParam(required = false) String origin,
                             @RequestParam(required = false) String grantKey,
                             @RequestParam(required = false) String orderNo,
                             @RequestParam(required = false) Long buyerUserId,
                             @RequestParam(required = false) Long agentId,
                             @RequestParam(required = false) String payChannel,
                             @RequestParam(required = false) String from,
                             @RequestParam(required = false) String to,
                             @RequestParam(required = false) Integer page,
                             @RequestParam(required = false) Integer size,
                             HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        String st = AdminQuery.enumOf(status, "status", "CREATED", "PAID", "FULFILLED",
                "CLOSED", "REFUNDED", "PARTIAL_REFUNDED");
        // 白名单的职责是挡非法值，不是表达「现在还卖不卖这个渠道」——
        // 微信三值虽然已经没有写入路径，仍然留着，好让旧书签与旧导出链接不至于 400。
        String ch = AdminQuery.enumOf(payChannel, "payChannel",
                "WECHAT_NATIVE", "WECHAT_JSAPI", "BANK_TRANSFER",
                "ALIPAY_PAGE", "ALIPAY_WAP");
        String og = AdminQuery.enumOf(origin, "origin", "SELF_SERVE", "ADMIN_GIFT");
        int p = AdminQuery.page(page), sz = AdminQuery.size(size);

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (st != null) { where.append(" AND o.status=?"); args.add(st); }
        if (ch != null) { where.append(" AND o.pay_channel=?"); args.add(ch); }
        if (og != null) { where.append(" AND o.origin=?"); args.add(og); }
        // 一次发放会建好几张单，它们共用同一个 grantKey 前缀（key#0、key#1……）。
        // 发完码之后界面就是靠它把这一批拢在一起给运营看的。
        if (!AdminQuery.blank(grantKey)) {
            // 同样要先收形状：这里虽然只出订单列表不出明文，但 `%` 会让「看这一批」
            // 变成「看全部白送单」，而横幅上那句「共 N 张单」照样理直气壮地显示
            ManualIssueService.requireGrantKey(grantKey);
            where.append(" AND o.grant_key LIKE ?");
            args.add(grantKey.trim() + "#%");
        }
        if (!AdminQuery.blank(orderNo)) { where.append(" AND o.order_no=?"); args.add(orderNo.trim()); }
        if (buyerUserId != null) { where.append(" AND o.buyer_user_id=?"); args.add(buyerUserId); }
        if (agentId != null) {
            // 「这个代理相关的单」= 他自己进的货 + 归属他的学员直购。少算后者的话，
            // 按代理看销售时学员直购那一档整个消失，而报表不会有任何提示
            where.append(" AND (o.buyer_agent_id=? OR o.attributed_agent_id=?)");
            args.add(agentId); args.add(agentId);
        }
        LocalDateTime f = AdminQuery.time(from, false, "from");
        LocalDateTime t = AdminQuery.time(to, true, "to");
        if (f != null) { where.append(" AND o.created_at>=?"); args.add(f); }
        if (t != null) { where.append(" AND o.created_at<=?"); args.add(t); }

        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM biz_order o" + where,
                Integer.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(sz); pageArgs.add(AdminQuery.offset(p, sz));
        var rows = jdbc.queryForList("SELECT o.* FROM biz_order o" + where
                + " ORDER BY o.id DESC LIMIT ? OFFSET ?", pageArgs.toArray());

        List<Map<String, Object>> out = new ArrayList<>();
        for (var r : rows) out.add(view(r));

        // 汇总跟着筛选条件走。分开算（列表按当前页、汇总按全库）是报表里最经典的一种
        // 「数字对不上」，而且两边都不会报错
        //
        // 白送单的应付金额也进 sumAmountFen —— 三个既有键的口径一个字不改。
        // 单给汇总加一条 origin 过滤而列表不加，就是「列表按一套、汇总按另一套」
        // 那种最经典的数字对不上。改成另给一个可相减的字段，让前端写
        // 「其中平台发放（不收钱）X 元」，两个数能对得上、口径也不含糊。
        var sum = jdbc.queryForMap(
                "SELECT COALESCE(SUM(o.amount_fen),0) AS amt, COALESCE(SUM(o.paid_amount_fen),0) AS paid,"
              + " COALESCE(SUM(o.refunded_amount_fen),0) AS refunded,"
              + " COALESCE(SUM(CASE WHEN o.origin='ADMIN_GIFT' THEN o.amount_fen ELSE 0 END),0) AS gift"
              + " FROM biz_order o" + where,
                args.toArray());
        return AdminJson.ok("total", total == null ? 0 : total, "page", p, "size", sz,
                "items", out,
                "sumAmountFen", AdminQuery.longOf(sum.get("amt"), 0),
                "sumPaidFen", AdminQuery.longOf(sum.get("paid"), 0),
                "sumRefundedFen", AdminQuery.longOf(sum.get("refunded"), 0),
                "sumGiftAmountFen", AdminQuery.longOf(sum.get("gift"), 0));
    }

    @GetMapping("/orders/{id}")
    Map<String, Object> detail(@PathVariable long id, HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        var rows = jdbc.queryForList("SELECT * FROM biz_order WHERE id=?", id);
        if (rows.isEmpty()) throw ApiException.notFound("订单不存在");
        Map<String, Object> v = view(rows.get(0));

        v.put("payments", jdbc.queryForList(
                "SELECT id, provider, provider_txn_id, amount_fen, status, confirmed_by, paid_at"
              + " FROM payment_record WHERE order_id=? ORDER BY id", id));
        v.put("refunds", jdbc.queryForList(
                "SELECT id, refund_no, amount_fen, scope, status, applied_by, approved_by,"
              + " provider_refund_id, finished_at, created_at FROM refund_record"
              + " WHERE order_id=? ORDER BY id", id));
        v.put("codes", jdbc.queryForList(
                // 只出掩码。后台订单详情不是取码的地方，取明文有 CodeService.reveal 那条留痕入口
                "SELECT status, COUNT(*) AS n FROM activation_code WHERE order_id=? GROUP BY status", id));
        v.put("commissions", jdbc.queryForList(
                "SELECT id, beneficiary_agent_id, amount_fen, reversed_fen,"
              + " amount_fen-reversed_fen AS net_amount_fen, status, freeze_until, revoked_at"
              + " FROM commission_record WHERE order_id=?", id));
        return AdminJson.ok("order", v);
    }

    // ---------------------------------------------------------------- 激活码

    /**
     * 这一单发出去的码，逐条列出（掩码）。
     *
     * <p>订单详情里的 {@code codes} 只有「按状态计数」，回答不了客服每天都要回答的问题：
     * 「我这单的码有没有被激活」「被谁激活的」「哪一张还没用」。
     */
    @GetMapping("/orders/{id}/codes")
    Map<String, Object> orderCodes(@PathVariable long id, HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        if (jdbc.queryForList("SELECT 1 FROM biz_order WHERE id=?", id).isEmpty())
            throw ApiException.notFound("订单不存在");
        var rows = codes.listByOrder(id).stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("codeId", c.id());
            m.put("masked", c.masked());
            m.put("status", c.status());
            m.put("activateDeadline", c.deadline());
            m.put("boundUserId", c.boundUserId());
            return m;
        }).toList();
        return AdminJson.ok("codes", rows);
    }

    /**
     * 取一张码的明文。<b>每次都写审计。</b>
     *
     * <p>这是超管唯一能看到码明文的地方，也是会议上那句「可以获取这个激活码是吧」
     * 的落点。在此之前答案是不能 —— 解密函数 {@code CodeVault.open} 的三个调用点
     * 全部是代理或订单买家，超管一个都够不到。
     *
     * <p>动作名是 {@code ADMIN_CODE_REVEAL}，与代理那条 {@code CODE_REVEAL} 分开，
     * 理由见 {@link CodeService#revealForAdmin}。
     */
    @PostMapping("/codes/{codeId}/reveal")
    Map<String, Object> revealCode(@PathVariable long codeId, HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        try {
            String plain = codes.revealForAdmin(
                    AuthInterceptor.userId(req), codeId, ClientIp.of(req));
            return AdminJson.ok("code", plain,
                    "message", "这次查看已记入审计日志");
        } catch (IllegalArgumentException e) {
            throw ApiException.notFound(e.getMessage());
        }
    }

    // ---------------------------------------------------------------- 对公转账到账

    /**
     * 人工确认对公转账到账。
     *
     * <p>金额必须与订单应付<b>逐笔相等</b>，这条判据在 {@link FulfillService} 里，不在这里 ——
     * 那是收款侧唯一的收口点，微信回调、前端查单、定时对账都走它。这里只把它的拒绝
     * 换成运营看得懂的话（分 → 元，并说清为什么不能「差一点也算到账」）。
     *
     * @param txnNo 银行回单上的流水号。它是幂等键：同一笔转账重复确认不会重复发码
     */
    @PostMapping("/orders/{id}/confirm-transfer")
    Map<String, Object> confirmTransfer(@PathVariable long id, @RequestBody ConfirmReq r,
                                        HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        long operator = AuthInterceptor.userId(req);
        if (r == null || AdminQuery.blank(r.txnNo()))
            throw ApiException.badRequest("请填写银行回单上的流水号：它是这笔到账的唯一凭据，"
                    + "也是重复确认时的去重依据");
        if (r.amountFen() == null || r.amountFen() <= 0)
            throw ApiException.badRequest("请填写到账金额（单位：分）");

        LocalDateTime paidAt = r.paidAt() == null || r.paidAt().isBlank()
                ? LocalDateTime.now() : AdminQuery.time(r.paidAt(), false, "paidAt");
        if (paidAt.isAfter(LocalDateTime.now().plusMinutes(5)))
            // 未来的到账时间会让按月统计把这笔算到下个月，而且没人会发现
            throw ApiException.badRequest("到账时间不能晚于当前时间，请按银行回单填写");

        var orders = jdbc.queryForList(
                "SELECT order_no, amount_fen, status, origin FROM biz_order WHERE id=?", id);
        if (orders.isEmpty()) throw ApiException.notFound("订单不存在");
        // ★ 这不是防御性代码，是必须的。不挡的话真跑一遍是这样：FulfillService 先插
        // payment_record（成功，一笔假流水就此落库，还吃掉一个 uk_provider_txn 名额），
        // 再 UPDATE ... WHERE status='CREATED' 影响 0 行（白送单是 FULFILLED），
        // 于是 ck_order_gift_unpaid 根本没被求值 —— 结果是一张 paid_at 仍为 NULL 的
        // 白送单上挂着一条 SUCCESS 的假流水，总览四项纹丝不动（它们按 paid_at 圈窗口），
        // 运营看到的是「已确认到账，本单已出 0 个激活码」。
        // 库层的 trg_payment_precheck 是第二道；这一道负责给人话。
        if (!"SELF_SERVE".equals(orders.get(0).get("origin")))
            throw ApiException.conflict("这张单是平台手工发放的、从来不收钱，不能确认到账。"
                    + "如果代理确实打了款，请让他在代理端正常下单，再对那张单确认到账");
        long due = AdminQuery.longOf(orders.get(0).get("amount_fen"), 0);

        FulfillService.Result result;
        try {
            result = fulfill.confirmPaid(id, "BANK_TRANSFER", r.txnNo().trim(),
                    r.amountFen(), paidAt, operator);
        } catch (IllegalStateException e) {
            // FulfillService 的拒绝本来就是给人看的，但它说的是「分」，而运营手里的回单是「元」。
            // 让他自己在脑子里除以 100 去核对，是这条路上最容易出错的一步
            String m = e.getMessage() == null ? "" : e.getMessage();
            if (m.contains("不符"))
                throw ApiException.conflict("到账金额与订单应付不一致：本单应付 "
                        + AdminQuery.yuan(due) + " 元，填写的到账金额是 "
                        + AdminQuery.yuan(r.amountFen()) + " 元。"
                        + "金额不符一律不入账 —— 少收的钱不会有任何日志，改价与串单都长这样。"
                        + "请核对银行回单后重填，确需特批走人工订正流程");
            throw ApiException.conflict(m);
        } catch (org.springframework.dao.DataAccessException e) {
            throw DbSignal.explain(e, null);
        }

        // 操作人有两处落点：payment_record.confirmed_by 与这条审计。冗余是有意的 ——
        // 审计写失败时前者还在，追责不至于归零
        audit.write(req, "ORDER_CONFIRM_TRANSFER", "biz_order", id,
                AdminJson.map("status", orders.get(0).get("status"), "amountFen", due),
                AdminJson.map("provider", "BANK_TRANSFER", "txnNo", r.txnNo().trim(),
                        "amountFen", r.amountFen(), "paidAt", String.valueOf(paidAt),
                        "firstTime", result.firstTime(), "codesIssued", result.codesIssued(),
                        "commissionFen", result.commissionFen(), "remark", r.remark()));

        if (!result.firstTime())
            return AdminJson.ok("changed", false, "message",
                    "这个流水号已经入过账了，没有重复发码。若确实是另一笔转账，请核对流水号后重填");
        return AdminJson.ok("changed", true, "codesIssued", result.codesIssued(),
                "commissionFen", result.commissionFen(),
                "commissionYuan", AdminQuery.yuan(result.commissionFen()),
                "message", "已确认到账，本单已出 " + result.codesIssued() + " 个激活码");
    }

    // ---------------------------------------------------------------- 退款

    @PostMapping("/orders/{id}/refunds")
    Map<String, Object> applyRefund(@PathVariable long id, @RequestBody RefundReq r,
                                    HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        if (r == null || r.amountFen() == null)
            throw ApiException.badRequest("请填写退款金额（单位：分）");
        return refunds.apply(req, id, r.amountFen(), r.scope(), r.reason());
    }

    @GetMapping("/refunds")
    Map<String, Object> refundList(@RequestParam(required = false) String status,
                                   @RequestParam(required = false) Long orderId,
                                   @RequestParam(required = false) Integer page,
                                   @RequestParam(required = false) Integer size,
                                   HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        String st = AdminQuery.enumOf(status, "status", "APPLIED", "APPROVED", "SUCCESS",
                "FAILED", "REJECTED");
        int p = AdminQuery.page(page), sz = AdminQuery.size(size);

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (st != null) { where.append(" AND r.status=?"); args.add(st); }
        if (orderId != null) { where.append(" AND r.order_id=?"); args.add(orderId); }

        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM refund_record r" + where,
                Integer.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(sz); pageArgs.add(AdminQuery.offset(p, sz));
        var rows = jdbc.queryForList(
                "SELECT r.*, o.order_no, o.paid_amount_fen, o.refunded_amount_fen, o.status AS order_status"
              + " FROM refund_record r JOIN biz_order o ON o.id=r.order_id" + where
              + " ORDER BY r.id DESC LIMIT ? OFFSET ?", pageArgs.toArray());

        List<Map<String, Object>> out = new ArrayList<>();
        for (var r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", AdminQuery.id(r.get("id")));
            m.put("refundNo", r.get("refund_no"));
            m.put("orderId", AdminQuery.id(r.get("order_id")));
            m.put("orderNo", r.get("order_no"));
            m.put("orderStatus", r.get("order_status"));
            m.put("amountFen", r.get("amount_fen"));
            m.put("amountYuan", AdminQuery.yuan(AdminQuery.longOf(r.get("amount_fen"), 0)));
            m.put("scope", r.get("scope"));
            m.put("status", r.get("status"));
            m.put("reason", r.get("reason"));
            m.put("appliedBy", AdminQuery.id(r.get("applied_by")));
            m.put("approvedBy", AdminQuery.id(r.get("approved_by")));
            m.put("providerRefundId", r.get("provider_refund_id"));
            m.put("createdAt", r.get("created_at"));
            m.put("finishedAt", r.get("finished_at"));
            out.add(m);
        }
        return AdminJson.ok("total", total == null ? 0 : total, "page", p, "size", sz, "items", out);
    }

    @PostMapping("/refunds/{id}/approve")
    Map<String, Object> approveRefund(@PathVariable long id, HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        return refunds.approve(req, id);
    }

    @PostMapping("/refunds/{id}/reject")
    Map<String, Object> rejectRefund(@PathVariable long id, @RequestBody(required = false) ReasonReq r,
                                     HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        return refunds.reject(req, id, r == null ? null : r.reason());
    }

    @PostMapping("/refunds/{id}/settle")
    Map<String, Object> settleRefund(@PathVariable long id, @RequestBody SettleReq r,
                                     HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        return refunds.settle(req, id, r == null ? null : r.providerRefundId());
    }

    @PostMapping("/refunds/{id}/fail")
    Map<String, Object> failRefund(@PathVariable long id, @RequestBody(required = false) ReasonReq r,
                                   HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        return refunds.fail(req, id, r == null ? null : r.reason());
    }

    // ---------------------------------------------------------------- 内部

    private Map<String, Object> view(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", AdminQuery.id(r.get("id")));
        m.put("orderNo", r.get("order_no"));
        m.put("buyerUserId", AdminQuery.id(r.get("buyer_user_id")));
        m.put("buyerType", r.get("buyer_type"));
        m.put("buyerAgentId", AdminQuery.id(r.get("buyer_agent_id")));
        m.put("buyerAgentLevel", r.get("buyer_agent_level"));
        m.put("skuCode", r.get("sku_code_snapshot"));
        m.put("codeQty", r.get("code_qty_snapshot"));
        m.put("amountFen", r.get("amount_fen"));
        m.put("amountYuan", AdminQuery.yuan(AdminQuery.longOf(r.get("amount_fen"), 0)));
        m.put("paidAmountFen", r.get("paid_amount_fen"));
        m.put("refundedAmountFen", r.get("refunded_amount_fen"));
        m.put("attributedAgentId", AdminQuery.id(r.get("attributed_agent_id")));
        m.put("status", r.get("status"));
        m.put("payChannel", r.get("pay_channel"));
        m.put("paidAt", r.get("paid_at"));
        m.put("fulfilledAt", r.get("fulfilled_at"));
        m.put("expireAt", r.get("expire_at"));
        m.put("createdAt", r.get("created_at"));
        m.put("clientIp", r.get("client_ip"));
        // ★ 这是一张显式白名单，不是 o.* 直出。不 put 的话后端一切正常、SQL 也确实
        // 查到了这一列，只是前端那一格恒空、来源标签永远不显示，而任何一层都不报错。
        m.put("origin", r.get("origin"));
        m.put("grantBy", AdminQuery.id(r.get("grant_by")));
        m.put("grantReason", r.get("grant_reason"));
        m.put("grantKey", r.get("grant_key"));
        return m;
    }
}
