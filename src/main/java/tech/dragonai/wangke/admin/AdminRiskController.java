package tech.dragonai.wangke.admin;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import tech.dragonai.wangke.auth.AuthInterceptor;
import tech.dragonai.wangke.protect.RiskEngine;
import tech.dragonai.wangke.web.ApiException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 风控事件与处置的查询，以及人工解封。
 *
 * <h3>为什么解封通道必须存在</h3>
 * 共享账号的证据几乎总是概率性的：出差、换 wifi、家里两台设备，看起来都像共享。
 * {@link RiskEngine} 因此设计成阶梯处置而不是一刀封号 —— 但阶梯的最后两档
 * （冻结 24 小时 / 停用）一旦落到一个真学员头上，如果没有人工解封通道，
 * 第一次误伤就会变成客服灾难。{@code risk_action.released_by} 那一列就是为此留的，
 * 这个接口是它唯一的写入点。
 *
 * <h3>解封处置 ≠ 解冻账号</h3>
 * 两者刻意分开：解除风控处置说的是「这条判定作废」，解冻账号说的是「这个人可以继续用」。
 * 合成一个动作之后，一次误判的撤销会顺手把一个因为别的原因（欠费、投诉、实名问题）
 * 被冻结的账号也放出来，而那不是风控该做的决定。
 */
@RestController
@RequestMapping("/api/admin/risk")
public class AdminRiskController {

    private final JdbcTemplate jdbc;
    private final AdminAudit audit;

    public AdminRiskController(JdbcTemplate jdbc, AdminAudit audit) {
        this.jdbc = jdbc; this.audit = audit;
    }

    public record ReasonReq(String reason) {}

    // ---------------------------------------------------------------- 事件

    @GetMapping("/events")
    Map<String, Object> events(@RequestParam(required = false) Long userId,
                               @RequestParam(required = false) String signal,
                               @RequestParam(required = false) String sessionId,
                               @RequestParam(required = false) Integer minScore,
                               @RequestParam(required = false) String from,
                               @RequestParam(required = false) String to,
                               @RequestParam(required = false) Integer page,
                               @RequestParam(required = false) Integer size,
                               HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        int p = AdminQuery.page(page), sz = AdminQuery.size(size);

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (userId != null) { where.append(" AND e.user_id=?"); args.add(userId); }
        // 反引号：signal 是 MySQL 8 的保留字（详见 summary() 里的说明）
        if (!AdminQuery.blank(signal)) { where.append(" AND e.`signal`=?"); args.add(signal.trim()); }
        if (!AdminQuery.blank(sessionId)) {
            where.append(" AND e.session_id=?"); args.add(sessionId.trim());
        }
        if (minScore != null) { where.append(" AND e.score>=?"); args.add(minScore); }
        LocalDateTime f = AdminQuery.time(from, false, "from");
        LocalDateTime t = AdminQuery.time(to, true, "to");
        if (f != null) { where.append(" AND e.created_at>=?"); args.add(f); }
        if (t != null) { where.append(" AND e.created_at<=?"); args.add(t); }

        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM risk_event e" + where,
                Integer.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(sz); pageArgs.add(AdminQuery.offset(p, sz));
        var rows = jdbc.queryForList("SELECT e.* FROM risk_event e" + where
                + " ORDER BY e.id DESC LIMIT ? OFFSET ?", pageArgs.toArray());

        List<Map<String, Object>> out = new ArrayList<>();
        for (var r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", AdminQuery.id(r.get("id")));
            m.put("userId", AdminQuery.id(r.get("user_id")));
            m.put("signal", r.get("signal"));
            m.put("score", r.get("score"));
            m.put("why", whyOf((String) r.get("signal")));
            m.put("detail", r.get("detail"));
            m.put("sessionId", r.get("session_id"));
            m.put("createdAt", r.get("created_at"));
            out.add(m);
        }
        return AdminJson.ok("total", total == null ? 0 : total, "page", p, "size", sz, "items", out);
    }

    // ---------------------------------------------------------------- 处置

    @GetMapping("/actions")
    Map<String, Object> actions(@RequestParam(required = false) Long userId,
                                @RequestParam(required = false) String action,
                                @RequestParam(required = false) Boolean onlyOpen,
                                @RequestParam(required = false) Integer page,
                                @RequestParam(required = false) Integer size,
                                HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        String act = AdminQuery.enumOf(action, "action", "WATCH", "CAPTCHA", "SMS_VERIFY",
                "FREEZE_24H", "SUSPEND");
        int p = AdminQuery.page(page), sz = AdminQuery.size(size);

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (userId != null) { where.append(" AND a.user_id=?"); args.add(userId); }
        if (act != null) { where.append(" AND a.action=?"); args.add(act); }
        if (Boolean.TRUE.equals(onlyOpen)) where.append(" AND a.released_at IS NULL");

        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM risk_action a" + where,
                Integer.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(sz); pageArgs.add(AdminQuery.offset(p, sz));
        var rows = jdbc.queryForList(
                "SELECT a.*, u.status AS user_status FROM risk_action a"
              + " LEFT JOIN sys_user u ON u.id=a.user_id" + where
              + " ORDER BY a.id DESC LIMIT ? OFFSET ?", pageArgs.toArray());

        List<Map<String, Object>> out = new ArrayList<>();
        for (var r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", AdminQuery.id(r.get("id")));
            m.put("userId", AdminQuery.id(r.get("user_id")));
            m.put("userStatus", r.get("user_status"));
            m.put("action", r.get("action"));
            m.put("scoreAtTrigger", r.get("score_at_trigger"));
            m.put("releasedAt", r.get("released_at"));
            m.put("releasedBy", AdminQuery.id(r.get("released_by")));
            m.put("createdAt", r.get("created_at"));
            out.add(m);
        }
        return AdminJson.ok("total", total == null ? 0 : total, "page", p, "size", sz, "items", out);
    }

    @PostMapping("/actions/{id}/release")
    Map<String, Object> release(@PathVariable long id, @RequestBody(required = false) ReasonReq body,
                                HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        String reason = body == null ? null : body.reason();
        if (AdminQuery.blank(reason))
            // 没有理由的解封，在下一次同一个人又被判定时没人知道上次为什么放了他
            throw ApiException.badRequest("请填写解除理由");
        long operator = AuthInterceptor.userId(req);

        var rows = jdbc.queryForList(
                "SELECT id, user_id, action, released_at FROM risk_action WHERE id=?", id);
        if (rows.isEmpty()) throw ApiException.notFound("风控处置记录不存在");
        var r = rows.get(0);

        // released_at IS NULL 放进 WHERE：并发下只有一个请求能解除，第二个拿到 0 行
        int n = jdbc.update("UPDATE risk_action SET released_at=NOW(3), released_by=?"
                          + " WHERE id=? AND released_at IS NULL", operator, id);
        if (n == 0)
            return AdminJson.ok("changed", false, "message", "这条处置已经被解除过了");

        audit.write(req, "RISK_ACTION_RELEASE", "risk_action", id,
                AdminJson.map("action", r.get("action"), "userId", AdminQuery.id(r.get("user_id")),
                        "releasedAt", null),
                AdminJson.map("releasedBy", operator, "reason", reason.trim()));

        long uid = AdminQuery.longOf(r.get("user_id"), 0);
        String userStatus = jdbc.queryForObject(
                "SELECT status FROM sys_user WHERE id=?", String.class, uid);
        return AdminJson.ok("changed", true, "userId", uid, "userStatus", userStatus,
                // 解除处置不会顺手解冻账号，这条必须说出来，否则运营会以为点完就结束了
                "message", "FROZEN".equals(userStatus)
                        ? "处置已解除。但该账号当前仍是冻结状态 —— 解除风控处置与解冻账号是两个决定，"
                        + "确认要放行请另外调用 /api/admin/users/" + uid + "/unfreeze"
                        : "处置已解除");
    }

    // ---------------------------------------------------------------- 体检

    /**
     * 某个学员的 7 天窗口风险体检。分值口径与 {@link RiskEngine#decide} 完全一致 ——
     * 后台看到的分必须和线上判定用的是同一套算法，否则运营会拿着一个 180 分的页面
     * 去问「为什么没有冻结」，而真正的原因是两边根本不是同一个数。
     */
    @GetMapping("/users/{id}/summary")
    Map<String, Object> summary(@PathVariable long id, HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        // signal 是 MySQL 8 的保留字，裸写在选择列表里是语法错。
        // 加了表别名（e.signal）时解析得过去，不加就不行 —— 同一个列名在两处写法不一样，
        // 所以这里一律反引号，免得改动时其中一处忘了
        var rows = jdbc.queryForList(
                "SELECT `signal`, score, created_at FROM risk_event WHERE user_id=?"
              + " AND created_at > DATE_SUB(NOW(3), INTERVAL 7 DAY) ORDER BY created_at", id);

        List<RiskEngine.ScoredEvent> scored = new ArrayList<>();
        List<String> unmapped = new ArrayList<>();
        ZoneId zone = ZoneId.systemDefault();
        for (var r : rows) {
            String sig = (String) r.get("signal");
            RiskEngine.Signal kind;
            try {
                kind = RiskEngine.Signal.valueOf(sig);
            } catch (IllegalArgumentException e) {
                // 库里出现了引擎不认识的信号名。把它按 kind=null 塞进去会让它逃过
                // 「同类递减」而按全额计入，算出来的分比线上判定用的高 —— 宁可不算，
                // 也不能给出一个和线上不一致的分数然后让人拿它做决定
                if (!unmapped.contains(sig)) unmapped.add(sig);
                continue;
            }
            Instant at = ((LocalDateTime) r.get("created_at")).atZone(zone).toInstant();
            scored.add(new RiskEngine.ScoredEvent(at, AdminQuery.intOf(r.get("score"), 0), kind));
        }
        RiskEngine.Action suggest = RiskEngine.decide(scored, Instant.now());

        var latest = jdbc.queryForList(
                "SELECT id, action, score_at_trigger, released_at, created_at FROM risk_action"
              + " WHERE user_id=? ORDER BY id DESC LIMIT 10", id);
        String userStatus = jdbc.queryForObject("SELECT status FROM sys_user WHERE id=?",
                String.class, id);

        return AdminJson.ok("userId", id, "userStatus", userStatus,
                "eventCount7d", rows.size(),
                "countedEvents", scored.size(),
                "suggestedAction", suggest.name(),
                "unmappedSignals", unmapped,
                "unmappedNote", unmapped.isEmpty() ? null
                        : "有 " + unmapped.size() + " 种信号名不在风控引擎的枚举里，未计入分数。"
                        + "这通常意味着某处在写 risk_event 时用了自己编的信号名，需要修",
                "recentActions", latest);
    }

    private static String whyOf(String signal) {
        try {
            return RiskEngine.Signal.valueOf(signal).why;
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }
}
