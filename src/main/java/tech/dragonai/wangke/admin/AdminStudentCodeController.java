package tech.dragonai.wangke.admin;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tech.dragonai.wangke.auth.AuthInterceptor;
import tech.dragonai.wangke.trade.CodeService;
import tech.dragonai.wangke.trade.ManualIssueService;
import tech.dragonai.wangke.trade.StudentCodeBatchService;
import tech.dragonai.wangke.web.ApiException;
import tech.dragonai.wangke.web.ClientIp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 超管批量生成学员激活码：一次一批，归属可选（不填 = 平台直营）。
 *
 * <h3>路径必须落在 /api/admin/ 下</h3>
 * {@link AdminGuard} 是按路径前缀挂的拦截器（{@code /api/admin/**}）。挂到别的前缀下的话
 * 那道闸整个不生效，而接口<b>工作得好好的</b> —— 仓库里 InvoiceAdminController 挂在
 * {@code /api/invoice/admin} 就是个反例。每个方法体第一行仍写 requireRole：
 * 两道闸互补不重复，一道是路径级的、一道是方法级的。
 *
 * <h3>审计挂在 biz_order 上，不是 agent_profile</h3>
 * 手工给代理发码那条路把审计挂在 {@code agent_profile}，理由是运营会去代理详情页查。
 * <b>这条路照抄那个形态是错的</b>：这里一半的批次根本没有代理（平台直营），
 * 挂到 agent_profile 上意味着那一半在任何页面上都<b>查不到</b> ——
 * 不报错、不空白，就是不存在。而一批一张单、单号永远存在，
 * 订单详情页的审计流水（{@code AuditTrail targetType="biz_order"}）与本页的批次列表
 * 都按 {@code biz_order + orderId} 查得到它。
 *
 * <h3>取明文不在这里实现</h3>
 * 一批就是一张单，所以导出直接复用 {@code POST /api/admin/orders/{id}/codes/export}
 * （{@link AdminCodeExportService}：解密与逐条审计在同一个事务里落库之后才返回）。
 * 在这里再写一个导出入口，就是第二条解密路径 —— 两条路里迟早有一条漏掉审计，
 * 而漏掉的那次<b>就是无痕的明文泄漏</b>：码已经在对方屏幕上了，平台这边一条记录都没有。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminStudentCodeController {

    private final JdbcTemplate jdbc;
    private final StudentCodeBatchService batches;
    private final CodeService codes;
    private final AdminAudit audit;

    public AdminStudentCodeController(JdbcTemplate jdbc, StudentCodeBatchService batches,
                                      CodeService codes, AdminAudit audit) {
        this.jdbc = jdbc; this.batches = batches; this.codes = codes; this.audit = audit;
    }

    /**
     * @param ownerAgentId 归属代理；<b>null = 不归属（平台直营）</b>。
     *                     这一个字段就是本轮需求的全部：它为空时学员激活后不计入任何代理业绩、
     *                     不结佣金
     * @param grantKey     幂等键。前端在对话框<b>打开时</b>生成一次并存住，关框才换 ——
     *                     写成每次点击现生成的话 uk_grant_key 那道防线等于不存在，
     *                     而「双击一次 = 两批码发出去」在界面上两次都显示成功
     */
    public record BatchReq(Integer qty, Long ownerAgentId, String reason, String grantKey) {}

    // ---------------------------------------------------------------- 生成前要知道的数

    /**
     * 生成对话框要用的常量与目录价。
     *
     * <p>上限与单价<b>都不许前端各抄一份</b>：抄的那份对不上时，用户先看到一个
     * 「可以提交」的界面，点下去才吃到后端那句话；而单价抄错更哑 ——
     * 对话框上那句「本批记 X 元的货」会和订单上的金额不一致，
     * 而运营正是拿这个数去判断「这一批送得起送不起」的。
     *
     * <p>为什么单价可以出网：它是<b>学员零售价</b>（{@code /api/public/skus} 对
     * buyerType=STUDENT 本来就公开），不是代理进货价。代理进货价不出网这条不受影响。
     */
    @GetMapping("/student-code-batches/meta")
    Map<String, Object> meta(HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        var rows = jdbc.queryForList(
                "SELECT s.id, s.name, s.status, t.total_price_fen"
              + "  FROM sku s LEFT JOIN agent_price_tier t"
              + "    ON t.sku_id = s.id AND t.agent_level = 0 AND t.effective_to IS NULL"
              + " WHERE s.sku_code = ?", StudentCodeBatchService.SKU_CODE);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("maxBatch", StudentCodeBatchService.MAX_BATCH);
        m.put("skuCode", StudentCodeBatchService.SKU_CODE);
        // 目录里没有这一档 / 没配价时给 null 而不是 0：前端拿 0 会算出「本批记 0 元的货」，
        // 那正好是这张单在库层建不出来的那个金额（ck_order_amount 要求 > 0），
        // 而界面会一路让他点到确认，最后吃一个看不懂的 409
        m.put("skuName", rows.isEmpty() ? null : rows.get(0).get("name"));
        m.put("skuOn", !rows.isEmpty() && "ON".equals(rows.get(0).get("status")));
        m.put("unitPriceFen", rows.isEmpty() ? null : rows.get(0).get("total_price_fen"));
        return AdminJson.ok("meta", m);
    }

    // ---------------------------------------------------------------- 生成

    @PostMapping("/student-code-batches")
    Map<String, Object> create(@RequestBody(required = false) BatchReq r, HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        if (r == null) throw ApiException.badRequest("请求体为空");
        if (r.qty() == null) throw ApiException.badRequest("请填写这一批生成几个码");

        StudentCodeBatchService.BatchResult g;
        try {
            g = batches.issue(new StudentCodeBatchService.BatchCmd(
                    r.qty(), r.ownerAgentId(), r.reason(), r.grantKey(),
                    AuthInterceptor.userId(req), ClientIp.of(req)));
        } catch (DataAccessException e) {
            // ★ catch 的必须是 DataAccessException 不是 DataIntegrityViolationException：
            // 触发器的 SIGNAL 走自定义 SQLSTATE 45000，Spring 冒上来的是
            // UncategorizedSQLException，catch 错了就是一段永远不执行的守卫。
            throw DbSignal.explain(e, "这批码没有生成，库里也没有留下半张");
        }

        // 重复提交回 200 + changed:false，绝不回 409：后台按钮会被双击，把第二次点击
        // 变成红色错误框的话，运营的第一反应是「刚才那次没成功」，于是再点一次 ——
        // 真的又生成了一批没人知道的码。
        if (!g.firstTime())
            return AdminJson.ok("changed", false,
                    "orderId", g.orderId(), "orderNo", g.orderNo(),
                    "codesIssued", g.codesIssued(), "codes", g.codes(),
                    "ownerAgentId", g.ownerAgentId(), "grantKey", r.grantKey(),
                    "message", "这一批刚才已经生成过了，没有重复生成。共 " + g.codesIssued()
                            + " 个码，明文在这一批里导出");

        audit.write(req, "STUDENT_CODE_BATCH", "biz_order", g.orderId(), null,
                AdminJson.map("orderNo", g.orderNo(),
                        "qty", r.qty(),
                        // 归属为空要显式写 null，不能省这个键：AdminJson 刻意不丢 null，
                        // 因为「这批码本来就不归属谁」和「这条审计没记归属」在事后是两件事，
                        // 而后者什么也证明不了
                        "ownerAgentId", g.ownerAgentId(),
                        "ownerName", g.ownerName(),
                        "codesIssued", g.codesIssued(),
                        "amountFenTotal", g.amountFenTotal(),
                        "reason", r.reason(), "grantKey", r.grantKey()));

        String who = g.ownerAgentId() == null
                ? "不归属任何代理（平台直营）" : "归属 " + g.ownerName();
        return AdminJson.ok("changed", true,
                "orderId", g.orderId(), "orderNo", g.orderNo(),
                "codesIssued", g.codesIssued(), "codes", g.codes(),
                "amountFenTotal", g.amountFenTotal(),
                "ownerAgentId", g.ownerAgentId(), "grantKey", r.grantKey(),
                "message", "已生成 " + g.codesIssued() + " 个学员激活码，" + who
                        + "。明文请在这一批里导出 —— 每一次都会记入审计日志");
    }

    // ---------------------------------------------------------------- 批次列表

    /**
     * 已经生成过的批次。
     *
     * <p>筛的是 {@code origin='ADMIN_GIFT' AND buyer_type='STUDENT'}，<b>不是 sku_id</b>：
     * 学员档哪天多出一个（活动短期档），按 sku_id 钉死的话这一页会整个变空，
     * 而空列表和「还没生成过」长得一模一样 —— 运营会当成没发过，然后再发一批。
     *
     * @param owner 空 = 全部；{@code platform} = 只看不归属的那些；数字 = 只看归属这家代理的
     */
    @GetMapping("/student-code-batches")
    Map<String, Object> list(@RequestParam(required = false) String owner,
                             @RequestParam(required = false) String grantKey,
                             @RequestParam(required = false) Integer page,
                             @RequestParam(required = false) Integer size,
                             HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        int p = AdminQuery.page(page), sz = AdminQuery.size(size);

        StringBuilder where = new StringBuilder(
                " WHERE o.origin='ADMIN_GIFT' AND o.buyer_type='STUDENT'");
        List<Object> args = new ArrayList<>();
        if (!AdminQuery.blank(owner)) {
            String o = owner.trim();
            if ("platform".equalsIgnoreCase(o)) where.append(" AND o.attributed_agent_id IS NULL");
            else {
                long id;
                try { id = Long.parseLong(o); }
                catch (NumberFormatException e) {
                    throw ApiException.badRequest("归属筛选只接受 platform 或代理 ID");
                }
                where.append(" AND o.attributed_agent_id=?");
                args.add(id);
            }
        }
        if (!AdminQuery.blank(grantKey)) {
            // 形状收死再拼 LIKE。不收的话传一个 `%` 进来就是 LIKE '%#%'，
            // 「看这一批」当场变成「看全部白送单」，而横幅上那句「共 N 张」照样理直气壮
            ManualIssueService.requireGrantKey(grantKey);
            where.append(" AND o.grant_key LIKE ?");
            args.add(grantKey.trim() + "#%");
        }

        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM biz_order o" + where,
                Integer.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(sz); pageArgs.add(AdminQuery.offset(p, sz));
        var rows = jdbc.queryForList(
                "SELECT o.id, o.order_no, o.code_qty_snapshot, o.amount_fen, o.attributed_agent_id,"
              + " o.grant_by, o.grant_reason, o.grant_key, o.created_at,"
              + " a.agent_code, COALESCE(au.real_name, au.nickname, au.phone) AS agent_name,"
              + " COALESCE(gu.real_name, gu.nickname, gu.phone) AS operator_name,"
              // 三个计数走子查询而不是 JOIN + GROUP BY：JOIN 之后 o.amount_fen 会被
              // 每一个码乘一遍，而那个数看起来完全正常（就是大了 N 倍），没有任何报错
              + " (SELECT COUNT(*) FROM activation_code c WHERE c.order_id=o.id) AS issued,"
              + " (SELECT COUNT(*) FROM activation_code c WHERE c.order_id=o.id"
              + "    AND c.status='BOUND') AS bound,"
              + " (SELECT COUNT(*) FROM activation_code c WHERE c.order_id=o.id"
              + "    AND c.status='REVOKED') AS revoked"
              + " FROM biz_order o"
              // 三个 LEFT JOIN 都不能写 INNER：归属为空是这一轮的<b>正常</b>形态，
              // 写 INNER 的话「平台直营」那半边会整片静默消失 —— 不报错、不空，只是行数变少
              + " LEFT JOIN agent_profile a ON a.id = o.attributed_agent_id"
              + " LEFT JOIN sys_user au ON au.id = a.user_id"
              + " LEFT JOIN sys_user gu ON gu.id = o.grant_by"
              + where + " ORDER BY o.id DESC LIMIT ? OFFSET ?", pageArgs.toArray());

        List<Map<String, Object>> out = new ArrayList<>();
        for (var r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("orderId", AdminQuery.id(r.get("id")));
            m.put("orderNo", r.get("order_no"));
            m.put("qty", r.get("code_qty_snapshot"));
            m.put("amountFen", r.get("amount_fen"));
            m.put("ownerAgentId", AdminQuery.id(r.get("attributed_agent_id")));
            m.put("ownerAgentCode", r.get("agent_code"));
            m.put("ownerName", r.get("agent_name"));
            m.put("grantBy", AdminQuery.id(r.get("grant_by")));
            m.put("operatorName", r.get("operator_name"));
            m.put("reason", r.get("grant_reason"));
            m.put("grantKey", r.get("grant_key"));
            m.put("issued", r.get("issued"));
            m.put("bound", r.get("bound"));
            m.put("revoked", r.get("revoked"));
            m.put("createdAt", r.get("created_at"));
            out.add(m);
        }
        return AdminJson.ok("total", total == null ? 0 : total, "page", p, "size", sz, "items", out);
    }

    /** 一批里的码。<b>只出掩码</b> —— 明文走导出那一条，它对每个码逐条写审计。 */
    @GetMapping("/student-code-batches/{orderId}/codes")
    Map<String, Object> codesOf(@PathVariable long orderId, HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        requireStudentBatch(orderId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (CodeService.CodeRow c : codes.listByOrder(orderId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("codeId", c.id());
            m.put("masked", c.masked());
            m.put("status", c.status());
            m.put("activateDeadline", c.deadline());
            m.put("boundUserId", c.boundUserId());
            out.add(m);
        }
        return AdminJson.ok("orderId", orderId, "items", out);
    }

    /**
     * 这一批必须真的是一批学员码。
     *
     * <p>不校验的话这个接口就是「按任意订单 ID 列码」的第二条入口。今天它只出掩码，
     * 所以看起来无害；但下一个人给它加一列「明文」时不会想到它从来没有做过范围校验 ——
     * 而那时它已经是一个可以枚举全库订单的接口了。
     */
    private void requireStudentBatch(long orderId) {
        var rows = jdbc.queryForList(
                "SELECT origin, buyer_type FROM biz_order WHERE id=?", orderId);
        if (rows.isEmpty()) throw ApiException.notFound("这一批不存在");
        if (!"ADMIN_GIFT".equals(rows.get(0).get("origin"))
                || !"STUDENT".equals(rows.get(0).get("buyer_type")))
            throw ApiException.badRequest("这张单不是一批学员码。收过钱的单请到订单页去看");
    }
}
