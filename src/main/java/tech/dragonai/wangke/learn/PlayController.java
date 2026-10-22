package tech.dragonai.wangke.learn;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tech.dragonai.wangke.auth.AuthInterceptor;
import tech.dragonai.wangke.web.ClientIp;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 播放三件套：开播、播放列表、密钥。
 *
 * <h3>为什么后两个接口不从登录态取用户</h3>
 * 它们是<b>播放器</b>发起的请求，不是页面 JS 发起的。iOS 的原生 HLS 没有任何办法
 * 给 m3u8 与 key 请求加上 Authorization 头。所以身份取自 PlayToken ——
 * 它本来就绑死了会话、设备、网段、讲次和过期时间，比一张 12 小时的 JWT 严格得多。
 *
 * <p>登录态若恰好也在（拦截器放行前已解析），会再做一次一致性比对：
 * 已登录的 A 拿着 B 的令牌来取密钥，这一条会当场堵死。
 */
@RestController
@RequestMapping("/api/play")
public class PlayController {

    private final PlayService play;

    public PlayController(PlayService play) { this.play = play; }

    public record SessionReq(@NotNull(message = "缺少 lessonId") Long lessonId,
                             @NotBlank(message = "缺少 deviceId") String deviceId,
                             String deviceLabel) {}

    /**
     * 开播。校验权益有效期 → 绑设备（触发器硬限 2 台）→ 取水印码字 → 写 play_session → 签令牌。
     *
     * <p>这是三条路里唯一一次重校验，也是唯一一次写 play_session。
     * 令牌有效期很短（默认 90 秒），播放器要靠反复调这个接口续签 ——
     * 于是"权益到期、账号被冻、设备被解绑"最迟在一个令牌周期内生效。
     */
    @PostMapping("/session")
    Map<String, Object> session(@RequestBody @Valid SessionReq r, HttpServletRequest req) {
        return view(play.open(AuthInterceptor.userId(req), r.lessonId(),
                r.deviceId(), r.deviceLabel(), ClientIp.of(req), req.getHeader("User-Agent")));
    }

    /**
     * 续签：给<b>同一次播放</b>换一张新令牌。
     *
     * <p>令牌 90 秒，而它被烧进 m3u8 里的 key URI，播放列表只生成一次 ——
     * 不续签的话，学员看到两分钟就会卡住，且播放器一句错都不报
     * （hls.js 把密钥 403 归成 NETWORK_ERROR 然后无限重试）。
     *
     * <p>为什么不复用 {@code /session}：那条路每次都新建 play_session、
     * 重绑一次设备、给风控加一条噪声，而心跳是挂在会话上的 ——
     * 一节两小时的课会开出上百条会话，学习时长的记账当场散架。
     *
     * <p>返回形状与 {@code /session} <b>逐字段相同</b>，前端只有一条解析路径。
     */
    @PostMapping("/renew")
    Map<String, Object> renew(@RequestBody @Valid RenewReq r, HttpServletRequest req) {
        return view(play.renew(AuthInterceptor.userId(req), r.sessionId(),
                r.deviceId(), ClientIp.of(req)));
    }

    public record RenewReq(@jakarta.validation.constraints.NotBlank String sessionId,
                           @jakarta.validation.constraints.NotBlank String deviceId) {}

    /** 出网形状只在这一处定义 —— 两处各拼一遍，迟早有一处少个字段而前端只看到 undefined。 */
    private static Map<String, Object> view(PlayService.Opened opened) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("sessionId", opened.sessionId());
        out.put("token", opened.token());
        out.put("expiresInSec", opened.expiresInSec());
        out.put("lessonId", opened.lessonId());
        out.put("title", opened.title());
        out.put("durationSec", opened.durationSec());
        out.put("resumePositionSec", opened.resumePositionSec());
        out.put("m3u8Url", opened.m3u8Url());
        return out;
    }

    /**
     * 逐用户播放列表。
     *
     * <p>{@code no-store}：这份 m3u8 里嵌着这个人的水印码字（哪几片取 A、哪几片取 B）。
     * 被任何中间层缓存并发给第二个人，两件事同时发生 —— 第二个人拿到的是别人的水印，
     * 而取证时会指认到那个无辜的人身上。这比"没有水印"更糟。
     */
    @GetMapping("/m3u8/{lessonId}")
    ResponseEntity<String> m3u8(@PathVariable long lessonId,
                                @RequestParam String token,
                                @RequestParam String deviceId,
                                HttpServletRequest req) {
        // 校验只做一次。分成"先验一次拿 userId、再验一次取内容"的话，
        // 两次之间用的参数将来会被改得不一样（很典型：一处忘了传 lessonId），
        // 而那种偏差不会报错 —— 只会让其中一次校验形同虚设。
        String body = play.playlist(token, lessonId, deviceId, ClientIp.of(req), loggedInOrNull(req));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.apple.mpegurl"))
                .cacheControl(CacheControl.noStore())
                .body(body);
    }

    /**
     * 下发 AES-128 内容密钥（16 字节裸二进制，HLS 规定的形状）。
     *
     * <p><b>lessonId 必须由请求带上，并原样传给 PlayToken.verify。</b>
     * 从令牌自己的 claims 里取出来再传进去是个恒真比较：拿第 1 讲的令牌去取第 2 讲的密钥
     * 会一路通过，试听那一讲发的令牌能解开正片每一讲 —— 且全程不会有任何报错。
     */
    @GetMapping("/key")
    ResponseEntity<byte[]> key(@RequestParam long lessonId,
                               @RequestParam String token,
                               @RequestParam String deviceId,
                               HttpServletRequest req) {
        byte[] k = play.contentKey(token, lessonId, deviceId, ClientIp.of(req), loggedInOrNull(req));
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .cacheControl(CacheControl.noStore())
                // 密钥不进任何缓存层。CDN 或浏览器缓存住它，等于把"短时效令牌"这件事作废：
                // 令牌过期了，缓存里的密钥还在，而且不需要任何凭据就能取。
                .header("Pragma", "no-cache")
                .contentLength(k.length)
                .body(k);
    }

    /**
     * 取登录态里的 userId，没有就返回 null。
     *
     * <p>这里<b>不能</b>直接调 AuthInterceptor.userId(req) —— 那个方法在没有登录态时抛 401。
     * 两条播放路径要在"拦截器拦着"和"拦截器放行"两种部署下行为一致，
     * 否则一旦有人把 /api/play/key 加进白名单，这两个接口会全线 401，
     * 而症状只出现在 iOS 上（只有它绕不开登录态这件事）。
     */
    private static Long loggedInOrNull(HttpServletRequest req) {
        Object v = req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return v == null ? null : (Long) v;
    }
}
