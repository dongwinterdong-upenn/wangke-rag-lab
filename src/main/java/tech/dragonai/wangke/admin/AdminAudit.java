package tech.dragonai.wangke.admin;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tech.dragonai.wangke.auth.AuthInterceptor;
import tech.dragonai.wangke.web.ClientIp;

import java.util.Map;

/**
 * 平台后台的追责记录。
 *
 * <p><b>这是平台侧唯一的追责依据。</b>后台的每一个改动性动作 —— 审批代理、改价、冻结账号、
 * 确认到账、批退款、放提现 —— 都是「一个人在系统外做的决定」，库里除了结果本身没有任何
 * 痕迹能说明是谁、什么时候、把它从什么改成了什么。漏写一条，出事时说不清是谁干的，
 * 而说不清的后果最终一定落在平台头上。
 *
 * <h3>三条纪律</h3>
 * <ol>
 *   <li><b>写失败就让它炸，绝不 try-catch 吞掉。</b>把审计写包在 try 里「尽力而为」，
 *       表现是平时全绿、真出事时那一条恰好没写上，而没写上的原因永远查不出来。
 *       改动失败可以重来，审计缺失补不回来。
 *   <li><b>能进事务就进同一个事务。</b>调用方在事务里调这里，改动与审计要么一起成立
 *       要么一起不成立；分开写会留下「改了但没记」的窗口。
 *   <li><b>快照里绝不放明文凭据。</b>密码、激活码明文、完整银行账号一律不进 before/after ——
 *       审计日志的可见面（运维、DBA、导出报表）比这些东西该有的可见面宽得多。
 * </ol>
 */
@Component
public class AdminAudit {

    private final JdbcTemplate jdbc;

    public AdminAudit(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /**
     * @param before 改动前快照；新建类动作传 null（不是空 Map —— 「没有前态」与「前态是空的」不同）
     * @param after  改动后快照
     */
    public void write(HttpServletRequest req, String action, String targetType, Object targetId,
                      Map<String, ?> before, Map<String, ?> after) {
        jdbc.update(
            "INSERT INTO audit_log (operator_id,action,target_type,target_id,"
          + "before_json,after_json,client_ip) VALUES (?,?,?,?,?,?,?)",
            AuthInterceptor.userId(req), action, targetType, String.valueOf(targetId),
            AdminJson.obj(before), AdminJson.obj(after), ClientIp.of(req));
    }
}
