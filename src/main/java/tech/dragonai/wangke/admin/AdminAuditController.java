package tech.dragonai.wangke.admin;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import tech.dragonai.wangke.auth.AuthInterceptor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 审计日志查询。
 *
 * <p><b>只读，而且刻意不提供任何写入、修改、删除入口。</b>一份能被后台改动的追责记录，
 * 在真出事时是没有证明力的 —— 对方第一句就是「记录是你们自己改的」。
 * 需要清理历史时走一次有留痕的数据归档，不走这里。
 *
 * <p>注意这里返回 before_json / after_json 的原文。这是有意的：审计的价值就在于能逐字段
 * 对比改动前后。代价是<b>写入侧必须自律</b>——{@link AdminAudit} 的三条纪律里第三条
 * （快照里不放明文凭据）保护的就是这个出口，密码、码明文、完整账号一旦写进去，
 * 这个只读接口就成了它们的泄漏面。
 */
@RestController
@RequestMapping("/api/admin/audit")
public class AdminAuditController {

    private final JdbcTemplate jdbc;

    public AdminAuditController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping
    Map<String, Object> list(@RequestParam(required = false) Long operatorId,
                             @RequestParam(required = false) String action,
                             @RequestParam(required = false) String targetType,
                             @RequestParam(required = false) String targetId,
                             @RequestParam(required = false) String from,
                             @RequestParam(required = false) String to,
                             @RequestParam(required = false) Integer page,
                             @RequestParam(required = false) Integer size,
                             HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        int p = AdminQuery.page(page), sz = AdminQuery.size(size);

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (operatorId != null) { where.append(" AND a.operator_id=?"); args.add(operatorId); }
        if (!AdminQuery.blank(action)) { where.append(" AND a.action=?"); args.add(action.trim()); }
        if (!AdminQuery.blank(targetType)) {
            where.append(" AND a.target_type=?"); args.add(targetType.trim());
        }
        if (!AdminQuery.blank(targetId)) {
            where.append(" AND a.target_id=?"); args.add(targetId.trim());
        }
        LocalDateTime f = AdminQuery.time(from, false, "from");
        LocalDateTime t = AdminQuery.time(to, true, "to");
        if (f != null) { where.append(" AND a.created_at>=?"); args.add(f); }
        if (t != null) { where.append(" AND a.created_at<=?"); args.add(t); }

        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log a" + where,
                Integer.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(sz); pageArgs.add(AdminQuery.offset(p, sz));
        var rows = jdbc.queryForList(
                "SELECT a.*, u.nickname AS operator_nickname, u.role AS operator_role"
              + " FROM audit_log a LEFT JOIN sys_user u ON u.id=a.operator_id" + where
              + " ORDER BY a.id DESC LIMIT ? OFFSET ?", pageArgs.toArray());

        List<Map<String, Object>> out = new ArrayList<>();
        for (var r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", AdminQuery.id(r.get("id")));
            m.put("operatorId", AdminQuery.id(r.get("operator_id")));
            m.put("operatorNickname", r.get("operator_nickname"));
            m.put("operatorRole", r.get("operator_role"));
            m.put("action", r.get("action"));
            m.put("targetType", r.get("target_type"));
            m.put("targetId", r.get("target_id"));
            m.put("beforeJson", r.get("before_json"));
            m.put("afterJson", r.get("after_json"));
            m.put("clientIp", r.get("client_ip"));
            m.put("createdAt", r.get("created_at"));
            out.add(m);
        }
        return AdminJson.ok("total", total == null ? 0 : total, "page", p, "size", sz, "items", out);
    }

    /** 某个对象的全部改动史。纠纷复盘时最常用的一个视图：这条记录被谁动过。 */
    @GetMapping("/target/{targetType}/{targetId}")
    Map<String, Object> ofTarget(@PathVariable String targetType, @PathVariable String targetId,
                                 HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        var rows = jdbc.queryForList(
                "SELECT a.id, a.operator_id, a.action, a.before_json, a.after_json, a.client_ip,"
              + " a.created_at, u.nickname AS operator_nickname"
              + " FROM audit_log a LEFT JOIN sys_user u ON u.id=a.operator_id"
              + " WHERE a.target_type=? AND a.target_id=? ORDER BY a.id",
                targetType, targetId);
        return AdminJson.ok("targetType", targetType, "targetId", targetId, "items", rows);
    }

    /** 动作字典：前端筛选框要用，也让人一眼看出后台一共有哪些改动性动作被记着。 */
    @GetMapping("/actions")
    Map<String, Object> actions(HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        return AdminJson.ok("items", jdbc.queryForList(
                "SELECT action, COUNT(*) AS n, MAX(created_at) AS last_at"
              + " FROM audit_log GROUP BY action ORDER BY n DESC"));
    }
}
