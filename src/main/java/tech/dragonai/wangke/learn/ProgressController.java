package tech.dragonai.wangke.learn;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;
import tech.dragonai.wangke.auth.AuthInterceptor;
import tech.dragonai.wangke.web.ClientIp;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 进度上报与心跳。
 *
 * <p>两条路的分工与它们各自能证明什么严格对应：
 * <ul>
 *   <li><b>/progress</b> 只说"我现在播到第几秒"，没有任何凭据 → 只推进断点续播位置。
 *   <li><b>/heartbeat</b> 带会话与倍速、留逐条明细 → 才有资格给有效观看时长记账。
 * </ul>
 * 合成一个接口会让刷进度的人挑成本最低的那条路走，而两条路记的账是同一笔。
 */
@RestController
@RequestMapping("/api/learn")
public class ProgressController {

    private final ProgressService progress;

    public ProgressController(ProgressService progress) { this.progress = progress; }

    // Integer 而不是 int：漏传 position_sec 时，int 会被静默当成 0，
    // 于是"没带位置"和"播到第 0 秒"变成同一件事，前端的 bug 永远不会暴露。
    public record ProgressReq(@NotNull(message = "缺少 lessonId") Long lessonId,
                              @NotNull(message = "缺少 positionSec") Integer positionSec) {}

    public record HeartbeatReq(@NotNull(message = "缺少 lessonId") Long lessonId,
                               @NotBlank(message = "缺少 sessionId") String sessionId,
                               @NotNull(message = "缺少 positionSec") Integer positionSec,
                               Double playbackRate,
                               String deviceId) {}

    @PostMapping("/progress")
    Map<String, Object> report(@RequestBody @Valid ProgressReq r, HttpServletRequest req) {
        return body(progress.report(AuthInterceptor.userId(req), r.lessonId(), r.positionSec()));
    }

    @PostMapping("/heartbeat")
    Map<String, Object> heartbeat(@RequestBody @Valid HeartbeatReq r, HttpServletRequest req) {
        // 倍速缺省按 1.0，不按 0：0 会撞上 ck_heartbeat_rate（下限 0.5），
        // 学员看到的是一句数据库约束名，而他其实什么都没做错。
        double rate = r.playbackRate() == null ? 1.0 : r.playbackRate();
        return body(progress.heartbeat(AuthInterceptor.userId(req), r.lessonId(), r.sessionId(),
                r.positionSec(), rate, ClientIp.of(req), r.deviceId()));
    }

    private static Map<String, Object> body(ProgressService.Snapshot s) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("lessonId", s.lessonId());
        out.put("maxPositionSec", s.maxPositionSec());
        out.put("watchedSec", s.watchedSec());
        out.put("durationSec", s.durationSec());
        out.put("progressPct", s.progressPct());
        out.put("completed", s.completed());
        // 把本次记账秒数如实返回。前端并不需要它，但没有它，防刷闸门在生产上是个黑盒：
        // 学员报"进度不涨"时，唯一能区分"闸门在起作用"与"记账坏了"的就是这个数。
        out.put("creditedSec", s.creditedSec());
        return out;
    }
}
