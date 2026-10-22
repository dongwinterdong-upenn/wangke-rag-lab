package tech.dragonai.wangke.learn;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tech.dragonai.wangke.config.WangkeProps;
import tech.dragonai.wangke.web.ApiException;
import tech.dragonai.wangke.web.ClientIp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * 取一片密文：{@code GET /api/play/seg/{对象路径}?l=&u=&e=&s=}。
 *
 * <h3>为什么分片要走应用，而不是让 nginx 直接发</h3>
 * 直接发就是它原来的样子 —— {@code /vod/} alias 直出，谁都能取、还不记日志。
 * 要在 nginx 那一层验签，需要 {@code secure_link} 模块，而这台机器上的 nginx
 * <b>没有编进那个模块</b>（{@code auth_request} 有，但它是一次子请求，
 * 等于同样要回到应用，还多一趟）。所以由这条路验签，再用
 * {@code X-Accel-Redirect} 把文件交回给 nginx 发 —— 校验在 Java，
 * I/O 在 nginx 的 sendfile 上，两边都做自己擅长的那一件事。
 *
 * <h3>身份不取自登录态</h3>
 * 和 {@code /m3u8}、{@code /key} 同一个理由：这是<b>播放器</b>发起的请求。
 * iOS 的原生 HLS 没有任何办法给分片请求加 Authorization 头，
 * hls.js 也只在密钥那一路上挂了 {@code xhrSetup}。
 * 身份取自 URL 上那张一片一签的凭据（{@link tech.dragonai.wangke.protect.SegmentToken}），
 * 它绑死了「谁 + 哪一讲 + 哪一个对象」。
 */
@RestController
@RequestMapping("/api/play")
public class SegmentController {

    /** 与 {@code @RequestMapping} 拼出来的完整前缀。截路径时用它，别写字面量两遍。 */
    static final String PREFIX = "/api/play/seg/";

    private final SegmentService segments;
    private final WangkeProps props;

    public SegmentController(SegmentService segments, WangkeProps props) {
        this.segments = segments;
        this.props = props;
    }

    @GetMapping("/seg/**")
    ResponseEntity<Resource> segment(@RequestParam(name = "l", required = false) String lessonId,
                                     @RequestParam(name = "u", required = false) String userId,
                                     @RequestParam(name = "e", required = false) String expires,
                                     @RequestParam(name = "s", required = false) String sig,
                                     HttpServletRequest req) {
        String d = segmentPath(req);
        var out = segments.deliver(d, lessonId, userId, expires, sig,
                ClientIp.of(req), Instant.now());

        if (out.internalUri() != null)
            // 体是空的：nginx 读到这个头之后会丢掉这次上游响应的 body，
            // 转到 internal 的 /vod/ 去发真正的文件（含 Range 处理）。
            return ResponseEntity.ok()
                    .header("X-Accel-Redirect", out.internalUri())
                    .contentType(MediaType.parseMediaType("video/mp2t"))
                    .build();

        return fromDisk(out.objectPath());
    }

    /**
     * 从请求行里截出对象路径。
     *
     * <p>用 {@code getRequestURI()} 的<b>原始形态</b>，不做任何解码 ——
     * 解码之后 {@code %2e%2e%2f} 会变成 {@code ../}，而那时它看起来完全合法。
     * 校验（{@link tech.dragonai.wangke.protect.SegmentToken#verify}）认的字符集里
     * 没有 {@code %}，所以任何编码形态在验签之前就被拒了；
     * 而真正的分片名只有数字、字母、{@code . _ - /}，本来就不需要解码。
     */
    private static String segmentPath(HttpServletRequest req) {
        String uri = req.getRequestURI();
        int at = uri.indexOf(PREFIX);
        if (at < 0) throw ApiException.badRequest("分片路径不合法");
        return uri.substring(at + PREFIX.length());
    }

    /**
     * 读盘回吐。<b>只给本地调试用</b>，生产必须走 X-Accel（见 {@code wangke.play.use-x-accel}）。
     *
     * <p>它不支持 Range。iOS 的原生 HLS 会发 Range，所以这条路在真机上是残的 ——
     * 写在这里免得有人把它当成"也能用"的部署形态。
     */
    private ResponseEntity<Resource> fromDisk(String objectPath) {
        String root = props.getPlay().getVodRoot();
        if (root == null || root.isBlank())
            throw new ApiException(503, "分片分发未配置：既没开 X-Accel，也没有本地片库根目录");
        Path p = Path.of(root).toAbsolutePath().normalize().resolve(objectPath).normalize();
        // resolve 之后再比一次前缀：objectPath 已经过字符集校验，这一句是纵深，
        // 挡的是"将来有人放宽了那个正则"的情况 —— 那种放宽不会有任何症状。
        if (!p.startsWith(Path.of(root).toAbsolutePath().normalize()))
            throw ApiException.badRequest("分片路径不合法");
        if (!Files.isReadable(p)) throw ApiException.notFound("分片不存在");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("video/mp2t"))
                .cacheControl(org.springframework.http.CacheControl.maxAge(
                        java.time.Duration.ofHours(1)).cachePrivate())
                .body(new FileSystemResource(p));
    }
}
