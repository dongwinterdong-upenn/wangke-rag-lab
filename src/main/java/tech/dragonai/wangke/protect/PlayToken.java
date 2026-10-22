package tech.dragonai.wangke.protect;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;

/**
 * 播放令牌：下发 HLS 解密密钥与分片时的凭据。
 *
 * <p>要挡的是这件事：学员打开控制台，把 key 的 URL 复制出来，
 * 贴到别的机器上照样取到密钥，于是整套加密等于没有。
 * 所以令牌必须<b>同时</b>绑住四样东西，缺一样就有绕过路径：
 *
 * <ul>
 *   <li><b>会话</b> —— 换一次播放就失效，不能长期持有
 *   <li><b>设备</b> —— 复制到别的机器上用不了
 *   <li><b>IP</b> —— 挡住把链接丢进群里的即时转发
 *   <li><b>过期时间</b> —— 短时效，即使泄漏窗口也只有几十秒
 * </ul>
 *
 * <p>服务端无状态校验（HMAC 签名自证），不查库 —— 密钥请求是最高频的路径，
 * 每片一次，查库会把数据库打垮。
 *
 * <p><b>IP 绑定的取舍：</b>移动网络切基站会换 IP，严格绑定会让手机端频繁掉线。
 * 所以绑的是 IP 的网段而非完整地址，见 {@link #ipScope}。
 * 这是「安全」与「能用」之间必须做的让步，写在这里免得下一轮有人当 bug 修掉。
 */
public final class PlayToken {

    private final byte[] secret;

    public PlayToken(byte[] hmacSecret) {
        if (hmacSecret == null || hmacSecret.length < 32)
            throw new IllegalArgumentException("HMAC 密钥至少 32 字节");
        this.secret = hmacSecret.clone();
    }

    public record Claims(String sessionId, long userId, long lessonId,
                         String deviceId, String ipScope, Instant expiresAt) {}

    /**
     * 取 IP 的网段：IPv4 到 /24，IPv6 到 /48。
     * 完整 IP 绑定在移动网络下会因切基站而频繁失效；
     * 网段绑定挡得住跨地域转发，又不会让正常用户十分钟掉一次线。
     */
    public static String ipScope(String ip) {
        if (ip == null || ip.isBlank()) return "";
        if (ip.contains(":")) {                       // IPv6 → 前三组
            String[] g = ip.split(":");
            return String.join(":", java.util.Arrays.copyOf(g, Math.min(3, g.length)));
        }
        String[] o = ip.split("\\.");                 // IPv4 → 前三段
        return o.length == 4 ? o[0] + "." + o[1] + "." + o[2] : ip;
    }

    public String issue(Claims c) {
        // 载荷用 | 分隔，所以字段里不能含 |。含了的话拼出来的串会多切出几段，
        // verify 那边字段数对不上直接拒 —— 失败方向是安全的，但表现为
        // "这台设备永远播不了，而且报的是令牌字段数不对"，查起来毫无线索。
        // 在签发这一端拒掉，错误就发生在能看懂它的地方。
        rejectSeparator("sessionId", c.sessionId());
        rejectSeparator("deviceId", c.deviceId());
        rejectSeparator("ipScope", c.ipScope());
        String payload = String.join("|", c.sessionId(), String.valueOf(c.userId()),
                String.valueOf(c.lessonId()), c.deviceId(), c.ipScope(),
                String.valueOf(c.expiresAt().getEpochSecond()));
        String sig = sign(payload);
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((payload + "|" + sig).getBytes(StandardCharsets.UTF_8));
    }

    /** 校验结果。失败时带明确原因，便于风控记录到底是哪种绕过尝试。 */
    public sealed interface Result permits Ok, Rejected {}
    public record Ok(Claims claims) implements Result {}
    public record Rejected(String reason) implements Result {}

    /**
     * 校验令牌。
     *
     * <p><b>{@code lessonId} 是必填的，不是可选的。</b>令牌里本来就带着它，
     * 但上一版没在这里比对，而是留给调用方自己从 {@link Claims} 里取出来判断 ——
     * 那等于把一条越权检查做成了「记得做就做」。忘了的后果很具体：
     * 试听那一讲发出的令牌能解开正片每一讲的密钥，
     * 而且一切正常，没有任何报错。所以把它做成签名里必须对上的一项。
     */
    public Result verify(String token, long lessonId, String deviceId, String ip, Instant now) {
        String raw;
        try {
            raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return new Rejected("令牌格式非法");
        }
        int cut = raw.lastIndexOf('|');
        if (cut < 0) return new Rejected("令牌格式非法");
        String payload = raw.substring(0, cut), sig = raw.substring(cut + 1);

        // 先验签再解析：未经验签的内容一律不信任
        if (!constantTimeEquals(sig, sign(payload))) return new Rejected("签名不匹配");

        String[] p = payload.split("\\|", -1);
        if (p.length != 6) return new Rejected("令牌字段数不对");
        Claims c;
        try {
            c = new Claims(p[0], Long.parseLong(p[1]), Long.parseLong(p[2]), p[3], p[4],
                    Instant.ofEpochSecond(Long.parseLong(p[5])));
        } catch (NumberFormatException e) {
            return new Rejected("令牌字段格式错");
        }

        if (!now.isBefore(c.expiresAt())) return new Rejected("令牌已过期");
        if (c.lessonId() != lessonId) return new Rejected("令牌不是这一讲的");
        if (!Objects.equals(c.deviceId(), deviceId)) return new Rejected("设备不匹配");
        if (!Objects.equals(c.ipScope(), ipScope(ip))) return new Rejected("来源网段不匹配");
        return new Ok(c);
    }

    private static void rejectSeparator(String field, String value) {
        if (value != null && value.indexOf('|') >= 0)
            throw new IllegalArgumentException(
                    field + " 含分隔符 '|'，会把令牌载荷切错段：" + value);
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 计算失败", e);
        }
    }

    /**
     * 定时比较。用 String.equals 的话，攻击者可以按"第几个字符开始不匹配"
     * 逐位试出签名 —— 这条不是洁癖，是签名校验的基本要求。
     */
    static boolean constantTimeEquals(String a, String b) {
        byte[] x = a.getBytes(StandardCharsets.UTF_8), y = b.getBytes(StandardCharsets.UTF_8);
        int diff = x.length ^ y.length;
        for (int i = 0; i < Math.max(x.length, y.length); i++)
            diff |= (i < x.length ? x[i] : 0) ^ (i < y.length ? y[i] : 0);
        return diff == 0;
    }
}
