package tech.dragonai.wangke.protect;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * 分片取用凭据：一片一签，绑死「谁 + 哪一讲 + 哪一个对象」。
 *
 * <h3>它补的是哪个洞</h3>
 * 分片过去挂在 nginx 的 {@code /vod/} 上 alias 直出，<b>不需要任何凭据</b>，
 * 而且那个 location 写着 {@code access_log off} —— 取片这件事既不授权也不记录。
 * 实测（2026-08-19，对生产只读跑过）：
 * <pre>
 *   curl -r 0-2047 https://course.dragonai.tech/vod/fde/l3/627_a.ts  → 206
 *   curl -r 0-2047 https://course.dragonai.tech/vod/fde/l3/627_b.ts  → 206   ← 另一个变体也拿得到
 * </pre>
 * 播放列表（404）与密钥（400）都是拦着的，唯独分片本身是裸的。
 *
 * <p>于是有两条后果，第二条比第一条严重：
 * <ol>
 *   <li>任何人 {@code curl} 就能把整讲密文拉走，换机器换 IP 都行，日志里一行都没有。
 *       配上一次合法开播拿到的那把<b>一讲一把、永不轮换</b>的 AES 密钥，
 *       离线重组出来的是原画质母版。
 *   <li><b>指纹可以被抹平。</b>{@code {i}_a.ts / {i}_b.ts} 这个命名规则印在每个人
 *       自己的播放列表里。把自己 m3u8 里所有 {@code _b} 改成 {@code _a} 再下载，
 *       产出副本的码字恒为全 A，拿去 {@link WatermarkService#accuse} 谁都指认不到 ——
 *       而且<b>不报错，只是查不出来</b>。Tardos 抗合谋的前提（marking assumption：
 *       合谋者拿不到自己没有的那一位）在那种投递形态下根本不成立，一个人就能把它推翻。
 * </ol>
 *
 * <h3>为什么签名下到「每一个对象」，而不是签一次整讲</h3>
 * 整讲一张票的话，第 2 条一个字都没补上：票是他自己的，他照样能拿这张票去取
 * {@code _b}。签名把<b>对象名</b>也盖进去之后，他手里只有自己那一份变体的签名，
 * 另一个变体的 URL 拼得出来、但签不出来 ⇒ 抹平指纹这条路断了，
 * 泄漏出去的副本必然带着他自己的码字。
 *
 * <p>这条也顺带取代了「把 {@code _a/_b} 改名成 HMAC 摘要」那个方案：
 * 改名要动磁盘上 5054 个文件，做错就是全员播放 404，而收益与这里等价。
 *
 * <h3>没有解决的那一半，写在这里免得被当成已解决</h3>
 * 浏览器必须能取到密文、必须能拿到密钥，所以<b>一个正常学员依然能把自己那份下载下来</b>。
 * 挡住这件事只有硬件级 DRM（Widevine L1 / FairPlay），这个播放器连 EME 都没接，
 * 那条路投入大回报低。这里换来的是三件真东西：
 * <b>不再匿名</b>（每一次取片都归属到一个 user_id）、
 * <b>不可抵赖</b>（副本必然带他自己的码字）、
 * <b>有速率闸</b>（见 {@link SegmentPace}）。
 */
public final class SegmentToken {

    /** 载荷版本。将来换绑定字段时靠它区分，老票据不会被新算法误判成"签名不匹配"。 */
    private static final String V = "v1";

    /**
     * 对象名的合法形状。分片名是内容入库流水线生成的（{@code {i}.ts / {i}_a.ts / {i}_b.ts}），
     * 前缀是 {@code lesson.hls_key}。
     *
     * <p><b>签名端也要查这一条，不能只在取片端查。</b>取片端查的是"别人递进来的路径"，
     * 而签名端查的是"我们自己拼出来的路径" —— 后者出问题的方式是 hls_key 被写进了
     * 带 {@code ../} 或者带查询串的值，那样签出来的票据会把一个越界路径变成
     * <b>合法签名的</b>越界路径，取片端再怎么校验都拦不住（它验签会通过）。
     */
    private static final java.util.regex.Pattern OBJECT =
            java.util.regex.Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._/-]{0,190}\\.ts$");

    private final byte[] key;

    /**
     * @param playHmacSecret 与 {@link PlayToken} 同一把根密钥，但<b>不直接使用</b> ——
     *        这里先派生出一把独立子钥。同一把密钥同时签两种载荷格式，
     *        是典型的跨协议混淆入口：只要将来某一侧的字段分隔或顺序变得能被另一侧
     *        解析，一张票就能当另一张用，而这种缺陷不会有任何症状。
     *        派生一次的成本是零，省下的是一整类不会报警的漏洞。
     */
    public SegmentToken(byte[] playHmacSecret) {
        if (playHmacSecret == null || playHmacSecret.length < 32)
            throw new IllegalArgumentException("HMAC 密钥至少 32 字节");
        this.key = hmac(playHmacSecret, "wangke/vod-segment/" + V);
    }

    // ------------------------------------------------------------------ 签发

    /**
     * 生成挂在分片 URL 上的查询串（不含前导 {@code ?}）。
     *
     * @param objectPath 相对片库根的对象路径，如 {@code fde/l3/627_a.ts}。
     *                   <b>必须与取片端拿到的那一份逐字相同</b>，否则验签必不过。
     */
    public String query(long userId, long lessonId, String objectPath, Instant expiresAt) {
        String obj = requireObject(objectPath);
        long exp = expiresAt.getEpochSecond();
        return "l=" + lessonId + "&u=" + userId + "&e=" + exp
             + "&s=" + sign(payload(userId, lessonId, obj, exp));
    }

    // ------------------------------------------------------------------ 校验

    public sealed interface Result permits Ok, Rejected {}

    /** @param userId 取片人；写日志、算速率、记风控事件都用它 */
    public record Ok(long userId, long lessonId, String objectPath) implements Result {}

    /** 失败原因要具体：风控要能分清是"过期了"还是"在伪造签名"，那是两种完全不同的人。 */
    public record Rejected(String reason) implements Result {}

    /**
     * 校验一次取片。<b>全程无状态、不查库</b> —— 这是全站最高频的路径
     * （每片一次，一讲 1800 片），任何一次数据库往返都会在这里被放大成灾难。
     *
     * @param objectPath 从请求路径里截出来的对象路径，未经解码之外的任何加工
     */
    public Result verify(String objectPath, String l, String u, String e, String s, Instant now) {
        if (objectPath == null || !OBJECT.matcher(objectPath).matches()
                || objectPath.contains("..") || objectPath.contains("//"))
            return new Rejected("分片路径不合法");
        if (l == null || u == null || e == null || s == null)
            return new Rejected("取片凭据缺失");

        long lessonId, userId, exp;
        try {
            lessonId = Long.parseLong(l);
            userId = Long.parseLong(u);
            exp = Long.parseLong(e);
        } catch (NumberFormatException nfe) {
            return new Rejected("取片凭据格式错");
        }

        // 先验签再看过期：过期判断读的是 e，而 e 是对方递进来的。
        // 反过来做的话，一张伪造的、把 e 写到 2099 年的票据会先通过过期检查，
        // 那一步本身不会出错，只是它检验的是攻击者自己填的数字。
        if (!PlayToken.constantTimeEquals(s, sign(payload(userId, lessonId, objectPath, exp))))
            return new Rejected("取片签名不匹配");
        if (!now.isBefore(Instant.ofEpochSecond(exp)))
            return new Rejected("取片凭据已过期");
        return new Ok(userId, lessonId, objectPath);
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 载荷。字段以 {@code |} 分隔，而每个字段的取值域都排除了 {@code |}
     * （两个是十进制整数，一个受 {@link #OBJECT} 约束）——
     * 分隔符能出现在字段里的话，"a|b" 与 "a" + "|b" 会签出同一个值。
     */
    private static String payload(long userId, long lessonId, String objectPath, long exp) {
        return V + "|" + userId + "|" + lessonId + "|" + objectPath + "|" + exp;
    }

    private String sign(String payload) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(hmac(key, payload))
                // 截断到 128 位。全长 43 字符 × 一讲 1800 行 = 每份播放列表多 50KB，
                // 而 128 位对"猜一个签名"来说远超需要（暴力空间 2^128，票据 6 小时就死）。
                .substring(0, 22);
    }

    static String requireObject(String objectPath) {
        if (objectPath == null || !OBJECT.matcher(objectPath).matches()
                || objectPath.contains("..") || objectPath.contains("//"))
            throw new IllegalArgumentException(
                    "分片对象路径不合法：" + objectPath + "。签名端放行一个越界路径，"
                  + "会把它变成一个【带合法签名的】越界路径 —— 取片端验签会通过，拦不住");
        return objectPath;
    }

    private static byte[] hmac(byte[] k, String msg) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(k, "HmacSHA256"));
            return mac.doFinal(msg.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("HMAC 计算失败", ex);
        }
    }
}
