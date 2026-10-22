package tech.dragonai.wangke.web;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查。
 *
 * <p><b>它原本不存在，但 {@code AuthInterceptor.PUBLIC_PREFIX} 里一直给它留着位置。</b>
 * 于是形成了一个很难查的组合：白名单放行 → 请求进到 DispatcherServlet →
 * 没有任何 handler → {@code NoHandlerFoundException} → 落到兜底分支 →
 * <b>回 500「系统繁忙」并写一条 ERROR 堆栈</b>。
 * 谁把它配成健康探针，看到的就是「服务一直不健康」，而服务其实是好的。
 *
 * <h3>为什么要真查一次数据库</h3>
 * 只回一句 {@code {"ok":true}} 的健康检查，量的是「Tomcat 还在监听」，
 * 而这恰恰是最不可能坏的那一层。真实的故障是连接池耗尽、数据库主从切换、
 * 密码轮换后没重启 —— 这些情况下进程活得好好的，接口全线 500。
 * 探针必须踩到那条路径上，否则它只会在服务已经死透之后才报警。
 *
 * <p>代价是每次探测一次 {@code SELECT 1}。探针频率是秒级、查询走连接池，
 * 这点开销远小于「探针说健康但其实没法用」的代价。
 *
 * <p>数据库不通时返回 <b>503 而不是 200 带一个 down 字段</b>：
 * 负载均衡器和 systemd 只看状态码，body 里写什么它们都不读。
 */
@RestController
public class HealthController {

    private final JdbcTemplate jdbc;

    public HealthController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping("/api/health")
    ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            out.put("ok", true);
            out.put("db", "up");
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            // 具体的异常写日志，不出网：连接串、库名、用户名都在里面
            org.slf4j.LoggerFactory.getLogger(HealthController.class)
                    .error("健康检查：数据库不可达", e);
            out.put("ok", false);
            out.put("db", "down");
            return ResponseEntity.status(503).body(out);
        }
    }
}
