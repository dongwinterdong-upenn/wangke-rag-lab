package tech.dragonai.wangke.admin;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tech.dragonai.wangke.auth.AuthInterceptor;
import tech.dragonai.wangke.trade.ManualIssueService;
import tech.dragonai.wangke.web.ApiException;
import tech.dragonai.wangke.web.ClientIp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 超管人工给代理发放激活码（赠送 / 补发）、批量取明文、整单作废。
 *
 * <h3>路径必须落在 /api/admin/ 下</h3>
 * AdminGuard 是按路径前缀挂的拦截器（{@code /api/admin/**}）。挂到别的前缀下的话
 * 那道闸整个不生效，而接口<b>工作得好好的</b> —— 仓库里 InvoiceAdminController 挂在
 * {@code /api/invoice/admin} 就是个反例。每个方法体第一行仍写 requireRole：
 * 两道闸互补不重复，一道是路径级的、一道是方法级的。
 *
 * <h3>这里发出去的是白送的码</h3>
 * 它代建一张 {@code origin='ADMIN_GIFT'} 的订单：应付记目录价、实付恒 0、到账时间恒空、
 * 不产生佣金、不进营收统计、开不出发票。这几条都是库层强制的，不是这一层的承诺 ——
 * 理由见 db/migrations/20260822_超管手工发码.sql 的文件头。
 *
 * <h3>审计挂在 agent_profile 上，不是 biz_order</h3>
 * 运营查「这家代理手上的码是怎么来的」会去代理详情页，而那一页的审计流水是按
 * {@code target_type='agent_profile'} 查的。挂到订单上的话这笔发码在那一页上完全看不到，
 * 而纠纷复盘时问的第一句就是这个。订单侧的可追溯性由
 * {@code biz_order.grant_by / grant_reason} 两列直接承担 —— 它们在业务事务里写，
 * 比事务之外的审计更近一层。<b>照抄这个形态的人要注意：没有第二落点就不能把审计写在事务外。</b>
 */
@RestController
@RequestMapping("/api/admin")
public class AdminCodeGrantController {

    private final JdbcTemplate jdbc;
    private final ManualIssueService issue;
    private final AdminCodeExportService export;
    private final AdminAudit audit;

    public AdminCodeGrantController(JdbcTemplate jdbc, ManualIssueService issue,
                                    AdminCodeExportService export, AdminAudit audit) {
        this.jdbc = jdbc; this.issue = issue; this.export = export; this.audit = audit;
    }

    /**
     * @param grantKey 幂等键。前端在对话框<b>打开时</b>生成一次并存住，关框才换 ——
     *                 写成每次点击现生成的话 uk_grant_key 那道防线等于不存在，
     *                 而「双击一次 = 两批码发出去」在界面上两次都显示成功
     */
    public record GrantReq(Long agentId, Long skuId, Integer copies, String reason, String grantKey) {}
    public record ReasonReq(String reason) {}
    public record ExportReq(String grantKey) {}

    // ---------------------------------------------------------------- 可发放的套餐

    /**
     * 可以拿来发放的套餐档。
     *
     * <p>这份列表原本写死在后台前端里（skuId 与 codeQty 照 db/seed.sql 抄的两行）。
     * skuId 抄错是<b>响的</b> —— ManualIssueService 当场拒掉。真正会安静出事的是
     * {@code codeQty}：发码框里那句「N 份 × 每份 M 个 = X 个码」和确认按钮上的
     * 「确认发放 X 个码」全由它算出来，而实际发多少个由库里的 {@code sku.code_qty} 决定。
     * 哪天有人把 AGENT_10 从 10 调成 12，操作员看到「发放 20 个码」、实际发出去 24 个，
     * 两边都不报错 —— 而这个数正是他按下按钮时唯一的依据。
     *
     * <p>只出 {@code buyer_type='AGENT'} 且 {@code status='ON'} 的档：学员档拿去发码会被
     * ManualIssueService 拒掉（「这个套餐不是代理档」），已下架的档不该还能发出去。
     * 顺带带上 MAX_COPIES / MAX_CODES —— 前端原来也是各抄一份，同样是「两处对不上时
     * 用户看到的是后端那句」，不如只留一处。
     */
    @GetMapping("/grant-skus")
    Map<String, Object> grantSkus(HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        List<Map<String, Object>> out = new ArrayList<>();
        for (var r : jdbc.queryForList(
                "SELECT s.id, s.sku_code, s.name, s.code_qty FROM sku s"
              + " WHERE s.buyer_type='AGENT' AND s.status='ON'"
              + " ORDER BY s.code_qty, s.id")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("skuId", ((Number) r.get("id")).longValue());
            m.put("skuCode", r.get("sku_code"));
            m.put("name", r.get("name"));
            m.put("codeQty", ((Number) r.get("code_qty")).intValue());
            out.add(m);
        }
        return AdminJson.ok("skus", out,
                "maxCopies", ManualIssueService.MAX_COPIES,
                "maxCodes", ManualIssueService.MAX_CODES);
    }

    // ---------------------------------------------------------------- 发码

    @PostMapping("/code-grants")
    Map<String, Object> grant(@RequestBody(required = false) GrantReq r, HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        if (r == null) throw ApiException.badRequest("请求体为空");
        if (r.agentId() == null) throw ApiException.badRequest("请选择要发给哪家代理");
        if (r.skuId() == null) throw ApiException.badRequest("请选择套餐");

        ManualIssueService.GrantResult g;
        try {
            g = issue.grant(new ManualIssueService.GrantCmd(
                    r.agentId(), r.skuId(), r.copies() == null ? 1 : r.copies(),
                    r.reason(), r.grantKey(),
                    AuthInterceptor.userId(req), ClientIp.of(req)));
        } catch (DataAccessException e) {
            // ★ catch 的必须是 DataAccessException 不是 DataIntegrityViolationException：
            // 触发器的 SIGNAL 走自定义 SQLSTATE 45000，Spring 冒上来的是
            // UncategorizedSQLException，catch 错了就是一段永远不执行的守卫。
            throw DbSignal.explain(e, "这批码没有发出去，库里也没有留下半张");
        }

        // 重复提交回 200 + changed:false，绝不回 409：后台按钮会被双击，把第二次点击
        // 变成红色错误框的话，运营的第一反应是「刚才那次没成功」，于是去别处找补 ——
        // 真的又发了一批码出去。这类误判比漏批更贵。
        if (!g.firstTime())
            return AdminJson.ok("changed", false,
                    "orderIds", g.orderIds(), "orderNos", g.orderNos(),
                    "codes", g.codes(), "codesIssued", g.codesIssued(),
                    "grantKey", r.grantKey(),
                    "message", "这一批刚才已经发过了，没有重复发码。"
                            + "共 " + g.codesIssued() + " 个码，明文到订单里查看或导出");

        audit.write(req, "CODE_GRANT", "agent_profile", r.agentId(), null,
                AdminJson.map("orderNos", String.join(",", g.orderNos()),
                        "skuId", r.skuId(), "skuName", g.skuName(),
                        "copies", r.copies() == null ? 1 : r.copies(),
                        "codesIssued", g.codesIssued(),
                        "amountFenTotal", g.amountFenTotal(),
                        "reason", r.reason(), "grantKey", r.grantKey()));

        return AdminJson.ok("changed", true,
                "orderIds", g.orderIds(), "orderNos", g.orderNos(),
                "codes", g.codes(), "codesIssued", g.codesIssued(),
                "amountFenTotal", g.amountFenTotal(),
                "grantKey", r.grantKey(),
                "message", "已给 " + g.agentName() + " 发出 " + g.codesIssued() + " 个激活码。"
                        + "明文请到订单里查看或导出 —— 每一次都会记入审计日志");
    }

    // ---------------------------------------------------------------- 取明文

    /** 一张订单的全部码明文。 */
    @PostMapping("/orders/{id}/codes/export")
    Map<String, Object> exportOrder(@PathVariable long id, HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        if (jdbc.queryForList("SELECT 1 FROM biz_order WHERE id=?", id).isEmpty())
            throw ApiException.notFound("订单不存在");
        return export.exportByOrders(AuthInterceptor.userId(req), List.of(id),
                "ORDER_EXPORT", ClientIp.of(req));
    }

    /**
     * 一次发放（可能是好几张单）的全部码明文。
     *
     * <p>发 20 份就是 20 张单，逐单导出要点 20 次 —— 而这正是最容易「漏导一单」的场景，
     * 漏掉的那些码躺在库里没人发，代理也不知道自己少了几张。
     */
    @PostMapping("/code-grants/export")
    Map<String, Object> exportGrant(@RequestBody(required = false) ExportReq r, HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        if (r == null) throw ApiException.badRequest("请求体为空");
        // 形状收死再拼 LIKE：传个 % 进来就是 LIKE '%#%'，一次导出全库白送码的明文
        ManualIssueService.requireGrantKey(r.grantKey());
        var ids = jdbc.queryForList(
                "SELECT id FROM biz_order WHERE grant_key LIKE ? ORDER BY id",
                Long.class, r.grantKey().trim() + "#%");
        if (ids.isEmpty()) throw ApiException.notFound("找不到这一批发放");
        return export.exportByOrders(AuthInterceptor.userId(req), ids,
                "GRANT_EXPORT", ClientIp.of(req));
    }

    // ---------------------------------------------------------------- 作废

    /**
     * 整单作废还没被激活的码。<b>只对平台手工发放的单开放。</b>
     *
     * <p>为什么限死在白送单上：自助单是收过钱的，作废码而不退钱比不作废更坏 ——
     * 订单实付一分不动、退款表一行不加、佣金不冲正，对账表完全是平的，
     * 只有代理来问「我的 20 个码怎么没了」。收过钱的单必须走退款那条链
     * （按退款比例作废并冲正佣金）。
     *
     * <p>为什么不开「按 codeId 的单张作废」：今天全仓唯一把码置 REVOKED 的语句在
     * RefundService 里，前置是退款真的打款成功。开一颗不带退款前置的单张作废按钮，
     * 就是一条绕过整条退款链的作废后门。
     */
    @PostMapping("/orders/{id}/codes/revoke")
    Map<String, Object> revokeOrderCodes(@PathVariable long id,
                                         @RequestBody(required = false) ReasonReq r,
                                         HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        if (r == null || AdminQuery.blank(r.reason()))
            throw ApiException.badRequest("请填写作废原因：这批码会立刻在代理手上失效，"
                    + "事后他来问「为什么用不了」，只有这一句能答");
        if (r.reason().length() > 255)
            throw ApiException.badRequest("作废原因不要超过 255 个字");

        var rows = jdbc.queryForList(
                "SELECT order_no, origin, grant_reason FROM biz_order WHERE id=?", id);
        if (rows.isEmpty()) throw ApiException.notFound("订单不存在");
        if (!"ADMIN_GIFT".equals(rows.get(0).get("origin")))
            throw ApiException.conflict("这张单是收过钱的，作废码而不退钱会让代理的库存和钱对不上 ——"
                    + "订单实付一分不动、退款表一行不加、佣金也不冲正，对账表完全是平的，"
                    + "只有代理会来问「我的码怎么没了」。请走退款流程（只退未激活的码用 UNUSED_CODES）");

        // 已激活的那几张单独拎出来告诉运营，而不是让库层那句「激活即不退」的 409
        // 直接透给他 —— 他要知道的是「哪几张撤不掉」，不是一句规则原文。
        var bound = jdbc.query(
                "SELECT id, code_masked FROM activation_code "
              + " WHERE order_id=? AND status='BOUND' ORDER BY id",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("codeId", rs.getLong("id"));
                    m.put("masked", rs.getString("code_masked"));
                    return m;
                }, id);

        int n;
        try {
            // 三样一次写全。少写 revoked_by 的话，事后没人知道是谁按的那一下；
            // 少写 revoke_reason 的话，代理来问时答不上来。
            n = jdbc.update(
                    "UPDATE activation_code SET status='REVOKED', revoked_by=?, revoked_at=NOW(3),"
                  + " revoke_reason=? WHERE order_id=? AND status='ISSUED'",
                    AuthInterceptor.userId(req), r.reason().trim(), id);
        } catch (DataAccessException e) {
            throw DbSignal.explain(e, null);
        }

        List<String> notes = new ArrayList<>();
        if (!bound.isEmpty())
            notes.add("其中 " + bound.size() + " 张已经被学员激活，按「激活即不退」作废不了，"
                    + "它们仍然可以正常观看");

        if (n == 0)
            return AdminJson.ok("changed", false, "revoked", 0, "boundCodes", bound,
                    "notes", notes,
                    "message", bound.isEmpty()
                            ? "这一单没有还未激活的码，什么都没有改动"
                            : "这一单的码都已经被激活了，一张都作废不了");

        audit.write(req, "CODE_GRANT_REVOKE", "agent_profile",
                jdbc.queryForObject("SELECT buyer_agent_id FROM biz_order WHERE id=?", Long.class, id),
                AdminJson.map("orderNo", rows.get(0).get("order_no"),
                        "grantReason", rows.get(0).get("grant_reason")),
                AdminJson.map("orderId", id, "revoked", n,
                        "stillBound", bound.size(), "reason", r.reason().trim()));

        return AdminJson.ok("changed", true, "revoked", n, "boundCodes", bound,
                "notes", notes,
                "message", "已作废 " + n + " 张未激活的码"
                        + (bound.isEmpty() ? "" : "；" + notes.get(0)));
    }
}
