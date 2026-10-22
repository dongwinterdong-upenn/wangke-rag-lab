package tech.dragonai.wangke.admin;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import tech.dragonai.wangke.auth.AuthInterceptor;
import tech.dragonai.wangke.web.ApiException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 代理商审批与层级维护。
 *
 * <h3>状态迁移一律用「带条件的 UPDATE」裁决，不用「先查再改」</h3>
 * 后台按钮会被双击，也会被两个运营同时点。写成「先 SELECT status 判断是不是 PENDING，
 * 再 UPDATE」的话，两个请求会同时读到 PENDING、同时通过判断、同时写入 —— 结果是
 * 两条审批通过的审计记录、两个 approved_by，事后谁也说不清是谁批的。
 * 把状态条件放进 WHERE，InnoDB 的行锁保证只有一个能改到，{@code rows==1} 就是裁决结果。
 * 改动后再读一次库只是为了<b>组织提示文案</b>，不参与判断。
 *
 * <h3>层级只锁不改</h3>
 * 「分销层级硬锁 2 级」是合规红线，库里由 {@code ck_agent_parent} 与
 * {@code trg_agent_parent_must_be_l1_*} 强制。这里不重复实现那套判断，只做两件事：
 * 把明显填错的组合当场挡回去（省一次往返、给一句人话），以及把库层的拒绝翻译成运营能执行的动作。
 */
@RestController
@RequestMapping("/api/admin/agents")
public class AdminAgentController {

    private final JdbcTemplate jdbc;
    private final AdminAudit audit;

    public AdminAgentController(JdbcTemplate jdbc, AdminAudit audit) {
        this.jdbc = jdbc; this.audit = audit;
    }

    public record ReasonReq(String reason) {}
    public record LevelReq(Integer level, Long parentAgentId, String reason) {}

    // ---------------------------------------------------------------- 查询

    @GetMapping
    Map<String, Object> list(@RequestParam(required = false) String status,
                             @RequestParam(required = false) Integer level,
                             @RequestParam(required = false) String keyword,
                             @RequestParam(required = false) Integer page,
                             @RequestParam(required = false) Integer size,
                             HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        String st = AdminQuery.enumOf(status, "status", "PENDING", "ACTIVE", "SUSPENDED", "TERMINATED");
        int p = AdminQuery.page(page), sz = AdminQuery.size(size);

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (st != null) { where.append(" AND a.status=?"); args.add(st); }
        if (level != null) { where.append(" AND a.level=?"); args.add(level); }
        if (!AdminQuery.blank(keyword)) {
            // 手机号 / 代理码 / 公司名 / 联系人 一起搜。用 LIKE 的通配符必须在值里转义
            // （见 AdminQuery.like），否则 keyword=% 就是一条「导出全部代理」的合法请求
            where.append(" AND (u.phone LIKE ? OR a.agent_code LIKE ?"
                       + " OR a.company_name LIKE ? OR a.contact_name LIKE ?)");
            String k = AdminQuery.like(keyword);
            args.add(k); args.add(k); args.add(k); args.add(k);
        }

        Integer total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_profile a JOIN sys_user u ON u.id=a.user_id" + where,
                Integer.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(sz); pageArgs.add(AdminQuery.offset(p, sz));
        var rows = jdbc.queryForList(
                "SELECT a.id, a.user_id, a.agent_code, a.level, a.parent_agent_id, a.company_name,"
              + " a.subject_type, a.contact_name, a.status, a.approved_by, a.approved_at, a.created_at,"
              + " u.phone, u.nickname, u.real_name, u.status AS user_status"
              + " FROM agent_profile a JOIN sys_user u ON u.id=a.user_id" + where
              + " ORDER BY a.id DESC LIMIT ? OFFSET ?", pageArgs.toArray());

        List<Map<String, Object>> out = new ArrayList<>();
        for (var r : rows) out.add(brief(r));
        return AdminJson.ok("total", total == null ? 0 : total, "page", p, "size", sz, "items", out);
    }

    @GetMapping("/{id}")
    Map<String, Object> detail(@PathVariable long id, HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        var rows = jdbc.queryForList(
                "SELECT a.*, u.phone, u.nickname, u.real_name, u.status AS user_status,"
              + " u.id_card_masked, u.last_login_at"
              + " FROM agent_profile a JOIN sys_user u ON u.id=a.user_id WHERE a.id=?", id);
        if (rows.isEmpty()) throw ApiException.notFound("代理商不存在");
        var r = rows.get(0);

        Map<String, Object> d = brief(r);
        d.put("taxNo", r.get("tax_no"));
        d.put("settleBankName", r.get("settle_bank_name"));
        // 结算账号在这里只出掩码：核对身份用得着后 4 位，而完整账号只有真正打款那一步需要，
        // 那一步在提现单上有自己的快照与留痕入口。后台详情页天天开着，不该常驻一个完整账号
        d.put("settleBankAcctMasked", AdminQuery.maskAccount((String) r.get("settle_bank_acct")));
        d.put("idCardMasked", r.get("id_card_masked"));
        d.put("lastLoginAt", r.get("last_login_at"));

        // 业务体检：这几项直接决定他能不能进货、能不能收到钱
        Integer subs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_profile WHERE parent_agent_id=?", Integer.class, id);
        Integer orders = jdbc.queryForObject(
                "SELECT COUNT(*) FROM biz_order WHERE buyer_agent_id=?", Integer.class, id);
        var bal = jdbc.queryForList(
                "SELECT commission_balance_fen, total_settled_fen, total_withdrawn_fen"
              + " FROM agent_balance WHERE agent_id=?", id);
        d.put("subAgentCount", subs);
        d.put("orderCount", orders);
        d.put("commissionBalanceFen", bal.isEmpty() ? 0L
                : AdminQuery.longOf(bal.get(0).get("commission_balance_fen"), 0));
        return AdminJson.ok("agent", d);
    }

    // ---------------------------------------------------------------- 审批

    @PostMapping("/{id}/approve")
    Map<String, Object> approve(@PathVariable long id, HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        long operator = AuthInterceptor.userId(req);

        var before = load(id);
        int rows = jdbc.update(
                "UPDATE agent_profile SET status='ACTIVE', approved_by=?, approved_at=NOW(3)"
              + " WHERE id=? AND status='PENDING'", operator, id);
        if (rows == 0) return notChanged(id, before, "PENDING", "ACTIVE", "审批通过");

        var after = load(id);
        audit.write(req, "AGENT_APPROVE", "agent_profile", id, before, after);
        return AdminJson.ok("changed", true, "status", after.get("status"),
                "message", "已通过审批，该代理即刻可以按层级价进货");
    }

    /**
     * 驳回。落到 TERMINATED（枚举里没有 REJECTED 这一档）。
     *
     * <p>驳回与停用刻意分成两个动作、两种终态：驳回的是「还没开始的合作」，
     * 停用的是「已经在跑、要暂停」的合作，两者在对账与结算上的处理完全不同，
     * 混成一个状态之后事后分不出来。
     */
    @PostMapping("/{id}/reject")
    Map<String, Object> reject(@PathVariable long id, @RequestBody(required = false) ReasonReq body,
                               HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        String reason = reasonOf(body, "驳回");

        var before = load(id);
        int rows = jdbc.update(
                "UPDATE agent_profile SET status='TERMINATED' WHERE id=? AND status='PENDING'", id);
        if (rows == 0) return notChanged(id, before, "PENDING", "TERMINATED", "驳回");

        var after = load(id);
        after.put("reason", reason);
        audit.write(req, "AGENT_REJECT", "agent_profile", id, before, after);
        return AdminJson.ok("changed", true, "status", "TERMINATED", "message", "已驳回");
    }

    /**
     * 停用。停用后 {@code trg_order_insert} 会挡住他继续下单（那条触发器只认 ACTIVE），
     * 已发出去的码不受影响 —— 学员已经付过钱，停代理不该连带停学员的课。
     */
    @PostMapping("/{id}/suspend")
    Map<String, Object> suspend(@PathVariable long id, @RequestBody(required = false) ReasonReq body,
                                HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        String reason = reasonOf(body, "停用");

        var before = load(id);
        int rows = jdbc.update(
                "UPDATE agent_profile SET status='SUSPENDED' WHERE id=? AND status='ACTIVE'", id);
        if (rows == 0) return notChanged(id, before, "ACTIVE", "SUSPENDED", "停用");

        var after = load(id);
        after.put("reason", reason);
        audit.write(req, "AGENT_SUSPEND", "agent_profile", id, before, after);

        Integer pendingWithdraw = jdbc.queryForObject(
                "SELECT COUNT(*) FROM withdraw_order WHERE agent_id=? AND status IN"
              + " ('APPLIED','REVIEWING','APPROVED','PAYING','FAILED')", Integer.class, id);
        return AdminJson.ok("changed", true, "status", "SUSPENDED",
                "message", "已停用，该代理不能再进货",
                // 停用不会自动拦下在途提现单，这件事必须让运营看见而不是自己去猜
                "inflightWithdrawCount", pendingWithdraw,
                "warn", pendingWithdraw != null && pendingWithdraw > 0
                        ? "该代理还有 " + pendingWithdraw + " 笔提现在途，停用不会自动拦下它们，需要逐笔处理"
                        : null);
    }

    @PostMapping("/{id}/resume")
    Map<String, Object> resume(@PathVariable long id, HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        var before = load(id);
        int rows = jdbc.update(
                "UPDATE agent_profile SET status='ACTIVE' WHERE id=? AND status='SUSPENDED'", id);
        if (rows == 0) return notChanged(id, before, "SUSPENDED", "ACTIVE", "恢复");
        var after = load(id);
        audit.write(req, "AGENT_RESUME", "agent_profile", id, before, after);
        return AdminJson.ok("changed", true, "status", "ACTIVE", "message", "已恢复");
    }

    // ---------------------------------------------------------------- 层级

    /**
     * 调整层级。
     *
     * <p>库层已经守住了三条：上级必须存在且是一级、一级不得有上级、有下级的一级不得降级。
     * 这里只把「明显填错」的两种组合提前挡掉并给一句能照着做的提示 —— 因为这两种组合触发的是
     * {@code CHECK} 约束而不是触发器，报上来是 3819 而不是我们自己写的那句话，
     * 不翻译的话运营看到的会是一句「系统繁忙」。
     */
    @PostMapping("/{id}/level")
    Map<String, Object> changeLevel(@PathVariable long id, @RequestBody LevelReq body,
                                    HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        if (body == null || body.level() == null)
            throw ApiException.badRequest("请指定目标层级（1=一级 2=二级）");
        int level = body.level();
        if (level != 1 && level != 2)
            throw ApiException.badRequest("代理层级只能是 1（一级）或 2（二级）");

        Long parent = body.parentAgentId();
        if (level == 1 && parent != null)
            throw ApiException.badRequest("一级代理不能有上级。要把他挂到别人名下，目标层级应当填 2");
        if (level == 2 && parent == null)
            throw ApiException.badRequest("二级代理必须指定上级（且上级必须是一级代理）");
        if (level == 2 && parent == id)
            throw ApiException.badRequest("代理不能挂在自己名下");

        var before = load(id);
        if (before.isEmpty()) throw ApiException.notFound("代理商不存在");

        int rows;
        try {
            // 条件里带上「当前不是目标形态」：重复点同一个按钮不会产生第二条审计
            rows = jdbc.update(
                    "UPDATE agent_profile SET level=?, parent_agent_id=? WHERE id=?"
                  + " AND NOT (level=? AND parent_agent_id <=> ?)",
                    level, level == 1 ? null : parent, id, level, level == 1 ? null : parent);
        } catch (DataAccessException e) {
            throw DbSignal.explain(e,
                    "分销层级硬锁 2 级：上级必须是一个已存在的一级代理；"
                  + "若要把一个已有下级的一级代理降为二级，须先把它的下级转挂到别的一级名下");
        }
        if (rows == 0)
            return AdminJson.ok("changed", false, "level", before.get("level"),
                    "message", "该代理已经是这个层级与上级，无需调整");

        var after = load(id);
        if (!AdminQuery.blank(body.reason())) after.put("reason", body.reason());
        audit.write(req, "AGENT_LEVEL_CHANGE", "agent_profile", id, before, after);
        return AdminJson.ok("changed", true, "level", level, "parentAgentId", parent,
                "message", "层级已调整。注意：已成交订单的价与佣金存的是下单当时的快照，不会跟着变");
    }

    // ---------------------------------------------------------------- 内部

    private Map<String, Object> brief(Map<String, Object> r) {
        Map<String, Object> d = AdminJson.map(
                "id", AdminQuery.id(r.get("id")),
                "userId", AdminQuery.id(r.get("user_id")),
                "agentCode", r.get("agent_code"),
                "level", r.get("level"),
                "parentAgentId", AdminQuery.id(r.get("parent_agent_id")),
                "companyName", r.get("company_name"),
                "subjectType", r.get("subject_type"),
                "contactName", r.get("contact_name"),
                "status", r.get("status"),
                "userStatus", r.get("user_status"),
                "nickname", r.get("nickname"),
                "realName", r.get("real_name"),
                // 手机号默认只出掩码：后台列表天天开着、会被投屏与截图，
                // 一次泄漏的是整张代理名单的联系方式
                "phoneMasked", AdminQuery.maskPhone((String) r.get("phone")),
                "approvedBy", AdminQuery.id(r.get("approved_by")),
                "approvedAt", r.get("approved_at"),
                "createdAt", r.get("created_at"));
        return d;
    }

    /** 只读一次、只取审计要用的字段。字段选择要稳定，否则前后两条快照对不上行。 */
    private Map<String, Object> load(long id) {
        var rows = jdbc.queryForList(
                "SELECT id, user_id, agent_code, level, parent_agent_id, status, approved_by,"
              + " approved_at FROM agent_profile WHERE id=?", id);
        if (rows.isEmpty()) return AdminJson.map();
        var r = rows.get(0);
        return AdminJson.map("agentId", AdminQuery.id(r.get("id")),
                "userId", AdminQuery.id(r.get("user_id")),
                "agentCode", r.get("agent_code"),
                "level", r.get("level"),
                "parentAgentId", AdminQuery.id(r.get("parent_agent_id")),
                "status", r.get("status"),
                "approvedBy", AdminQuery.id(r.get("approved_by")),
                "approvedAt", String.valueOf(r.get("approved_at")));
    }

    /**
     * 条件 UPDATE 没改到行时的回话。
     *
     * <p>这里再读一次库<b>只是为了措辞</b> —— 判断已经由 UPDATE 的 WHERE 做完了。
     * 顺序反过来（先读再判）就是竞态。
     *
     * <p>「已经是目标状态」必须回 200 而不是报错：后台按钮会被双击，把第二次点击变成一个
     * 红色错误框，运营的第一反应是「刚才那次没成功」，于是去别处找补 —— 这类误判比漏批更贵。
     */
    private Map<String, Object> notChanged(long id, Map<String, Object> before,
                                           String fromStatus, String toStatus, String action) {
        if (before.isEmpty()) throw ApiException.notFound("代理商不存在");
        String now = String.valueOf(before.get("status"));
        if (toStatus.equals(now))
            return AdminJson.ok("changed", false, "status", now,
                    "message", "这条已经" + action + "过了，无需重复操作");
        if (fromStatus.equals(now))
            throw ApiException.conflict("刚刚有另一位操作人在改这条记录，请刷新后重试");
        throw ApiException.conflict("当前状态是 " + now + "，不能" + action
                + "（只有 " + fromStatus + " 状态的代理可以" + action + "）");
    }

    private String reasonOf(ReasonReq body, String action) {
        String r = body == null ? null : body.reason();
        if (AdminQuery.blank(r))
            // 没有理由的驳回/停用，在代理来质问时平台是没有立场的一方
            throw ApiException.badRequest("请填写" + action + "理由");
        return r.trim();
    }
}
