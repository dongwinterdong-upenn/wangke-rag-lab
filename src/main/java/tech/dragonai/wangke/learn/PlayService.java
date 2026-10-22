package tech.dragonai.wangke.learn;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tech.dragonai.wangke.config.WangkeProps;
import tech.dragonai.wangke.protect.M3u8Builder;
import tech.dragonai.wangke.protect.PlayToken;
import tech.dragonai.wangke.protect.SegmentToken;
import tech.dragonai.wangke.protect.WatermarkService;
import tech.dragonai.wangke.web.ApiException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 开播 → 播放列表 → 密钥。学员端和内容保护体系接在一起的那一层。
 *
 * <h3>三条路各自信什么</h3>
 * <ul>
 *   <li><b>/session</b> 信登录态（JWT），查库：权益、设备上限、水印码字，然后签一张短时效令牌。
 *       这是唯一一次「重」的校验。
 *   <li><b>/m3u8</b> 信令牌 + 复查一次权益。播放列表是会被收藏、被贴进群的东西，
 *       它带着密钥 URI，所以多付一次查询。
 *   <li><b>/key</b> <b>只</b>信令牌，不查权益。见 {@link #contentKey}，那里写了为什么。
 * </ul>
 *
 * <h3>为什么 m3u8 与 key 的用户身份取自令牌而不是登录态</h3>
 * 这两条路是播放器发起的，不是页面 JS 发起的。iOS 上的原生 HLS 根本没有办法
 * 给这两个请求加 Authorization 头。取自令牌之后，这两个接口在
 * 「拦截器拦着」与「拦截器放行」两种部署下行为完全一致，
 * 不会出现「上线后 iPhone 一律播不了」这种要等用户投诉才知道的事。
 * 登录态若恰好也在，会额外做一次一致性比对（见 {@link #assertSameUser}）。
 */
@Service
public class PlayService {

    private static final Logger log = LoggerFactory.getLogger(PlayService.class);

    /** 设备指纹的形状约束。太短的 device_id 等于全网共用一个设备位，2 台上限直接失效。 */
    private static final int DEVICE_ID_MIN = 8, DEVICE_ID_MAX = 64;

    private final JdbcTemplate jdbc;
    private final Entitlements entitlements;
    private final WatermarkService watermark;
    private final PlayToken playToken;
    private final SegmentToken segmentToken;
    private final LessonKeyService keys;
    private final WangkeProps props;
    private final String cdnBase;
    private final SecureRandom rnd = new SecureRandom();

    public PlayService(JdbcTemplate jdbc, Entitlements entitlements, WatermarkService watermark,
                       PlayToken playToken, SegmentToken segmentToken, LessonKeyService keys,
                       WangkeProps props,
                       @Value("${wangke.play.cdn-base:}") String cdnBase) {
        this.jdbc = jdbc; this.entitlements = entitlements; this.watermark = watermark;
        this.playToken = playToken; this.segmentToken = segmentToken;
        this.keys = keys; this.props = props;
        this.cdnBase = cdnBase == null ? "" : cdnBase.trim().replaceAll("/+$", "");
    }

    public record Opened(String sessionId, String token, int expiresInSec,
                         long lessonId, String title, int durationSec,
                         int resumePositionSec, String m3u8Url) {}

    // ------------------------------------------------------------------ 开播

    /**
     * 开一次播放。
     *
     * <p>顺序是有讲究的：<b>先验权益，再动设备</b>。反过来的话，一个没买课的人
     * 可以靠反复请求把别人（其实是自己）的两个设备位占满 —— 更糟的是，
     * 他连一句错都收不到，因为设备绑定本身是成功的。
     */
    public Opened open(long userId, long lessonId, String deviceId, String deviceLabel,
                       String ip, String userAgent) {
        var lesson = entitlements.requireLesson(userId, lessonId);
        String device = normalizeDeviceId(deviceId);

        // 这里分叉两次，而且【两次看的不是同一个东西】。
        //
        // ① 绑不绑设备 —— 看**观看者**有没有权益。
        //    设备上限是【已购课程】的防共享手段。给还没买的人看一段先导课就烧掉
        //    两个位之一，等于他还没付钱就先被扣了一次额度：等他真的买了课，
        //    在第三台设备上打不开，而原因是"他之前试听过"。
        //
        // ② 发不发码字 —— 看**素材**是不是试听。
        //    试听素材按"公开物料、不带水印"制作，磁盘上没有 _a/_b 两份，
        //    上架闸门也豁免了码本与位区间。所以试听讲【对谁都没有槽位可用】，
        //    码字发了也无处投递。把它写成"看观看者"会炸两次：
        //      · 已购学员点开先导课 → 走 A/B 播放列表 → 每个槽位 404，且一句错都不报；
        //      · 课程还没建码本时（先导课先上、正片还没做），codewordIdFor 抛
        //        「没有现行水印码本」→ 已购/被赠课的人 409，没买课的人反而能看。
        if (lesson.entitled()) bindDevice(userId, device, deviceLabel);

        Long codewordId = lesson.preview() ? null : codewordIdFor(userId, lesson.courseId());

        String sessionId = HexFormat.of().formatHex(randomBytes(16));
        String ipScope = PlayToken.ipScope(ip);
        jdbc.update("INSERT INTO play_session "
                  + "(session_id,user_id,lesson_id,device_id,client_ip,ip_region,user_agent,wm_codeword_id) "
                  + "VALUES (?,?,?,?,?,?,?,?)",
                sessionId, userId, lessonId, device, safeIp(ip), ipRegion(ip),
                userAgent == null ? null : userAgent.substring(0, Math.min(255, userAgent.length())),
                codewordId);

        int ttl = Math.max(30, props.getPlay().getTokenTtlSeconds());
        String token = playToken.issue(new PlayToken.Claims(
                sessionId, userId, lessonId, device, ipScope, Instant.now().plusSeconds(ttl)));

        int resume = resumePosition(userId, lessonId);
        return new Opened(sessionId, token, ttl, lessonId, lesson.title(), lesson.durationSec(),
                resume, "/api/play/m3u8/" + lessonId + "?token=" + token + "&deviceId=" + enc(device));
    }

    /**
     * 给<b>已有的那一次播放</b>换一张新令牌，不新开会话。
     *
     * <h3>为什么必须有这条路</h3>
     * 令牌只有 90 秒，而它被烧进了 m3u8 里的 key URI（{@code /api/play/key?...&token=...}）。
     * 播放列表只生成一次，于是 90 秒之后每一个密钥请求都带着一张死令牌 → 403。
     * hls.js 把 403 归到 NETWORK_ERROR，然后 {@code startLoad()} 无限重试 ——
     * 学员看到的是<b>看了两分钟突然卡住，而播放器一句错都不报</b>。
     *
     * <p>不能拿「再调一次 /session」当续签：那会新建一条 {@code play_session}、
     * 再绑一次设备、给风控多一条噪声，而心跳是挂在会话上的 ——
     * 一节两小时的课会开出上百条会话，学习时长的记账当场散架。
     *
     * <h3>续签不是免检</h3>
     * 每一次都重新查一遍权益（{@code requireLesson}）。这条路每 60 秒才走一次，
     * 不是 {@code /key} 那种每片一次的热路径，查得起。
     * 后台冻结账号 / 回收权益之后，最多再放行一个续签周期。
     *
     * <p>会话必须是<b>本人的、这一讲的、还没结束的</b>。这三条写在 SQL 的 WHERE 里，
     * 而不是查出来再在 Java 里比 —— 少一个条件就等于别人的令牌能换出自己的令牌。
     */
    public Opened renew(long userId, String sessionId, String deviceId, String ip) {
        var lesson = entitlements.requireLesson(userId, lessonIdOfSession(userId, sessionId));
        String device = normalizeDeviceId(deviceId);

        Integer ok = jdbc.query(
                "SELECT 1 FROM play_session WHERE session_id=? AND user_id=? "
              + "AND device_id=? AND ended_at IS NULL",
                rs -> rs.next() ? 1 : null, sessionId, userId, device);
        if (ok == null)
            // 「不是你的」与「不存在」收敛到同一句话：不泄露会话 id 的存在性
            throw new ApiException(403, "播放会话已失效，请重新打开本讲");

        int ttl = Math.max(30, props.getPlay().getTokenTtlSeconds());
        String token = playToken.issue(new PlayToken.Claims(
                sessionId, userId, lesson.lessonId(), device, PlayToken.ipScope(ip),
                Instant.now().plusSeconds(ttl)));

        jdbc.update("UPDATE play_session SET last_seen_at=NOW(3) WHERE session_id=?", sessionId);

        return new Opened(sessionId, token, ttl, lesson.lessonId(), lesson.title(),
                lesson.durationSec(), resumePosition(userId, lesson.lessonId()),
                "/api/play/m3u8/" + lesson.lessonId()
                        + "?token=" + token + "&deviceId=" + enc(device));
    }

    /** 会话属于哪一讲。归属条件同样写在 WHERE 里。 */
    private long lessonIdOfSession(long userId, String sessionId) {
        Long id = jdbc.query("SELECT lesson_id FROM play_session "
                           + "WHERE session_id=? AND user_id=? AND ended_at IS NULL",
                rs -> rs.next() ? rs.getLong(1) : null, sessionId, userId);
        if (id == null) throw new ApiException(403, "播放会话已失效，请重新打开本讲");
        return id;
    }

    /**
     * 设备绑定。
     *
     * <p><b>上限由 trg_user_device_cap 裁决，这里不做「先数一数再决定」。</b>
     * 先数后写在并发下必然漏：两台设备同时开播，双双数到 1，然后各绑一台，账号上就有 3 台。
     * 这里只负责把触发器那句面向 DBA 的话，翻成一句学员看得懂、且知道下一步该干什么的话。
     *
     * <p><b>为什么先 UPDATE 再 INSERT，而不是 INSERT ... ON DUPLICATE KEY UPDATE。</b>
     * MySQL 的 BEFORE INSERT 触发器在<b>检测到唯一键冲突之前</b>就会执行。
     * 于是 ON DUPLICATE 那种写法在这张表上是错的：一个已经绑满 2 台的账号，
     * 用它<b>自己那两台里的任意一台</b>再开播，都会先撞上 trg_user_device_cap_ins ——
     * 症状是「绑满两台之后谁也播不了」，而报出来的是一句「已绑定 2 台设备」，
     * 看起来完全合理，没有任何信号指向根因。LearnIT 第 5~16 条当场把它抓出来了。
     */
    private void bindDevice(long userId, String deviceId, String deviceLabel) {
        String label = deviceLabel == null || deviceLabel.isBlank()
                ? null : deviceLabel.substring(0, Math.min(64, deviceLabel.length()));
        try {
            // 命中既有行就走 UPDATE。解绑过的设备回来时是 REMOVED → ACTIVE，
            // 那条路上 trg_user_device_cap_upd 会重新查上限 —— 上限没有被绕过。
            if (touchBinding(userId, deviceId, label) > 0) return;
            try {
                jdbc.update("INSERT INTO user_device (user_id,device_id,device_label,status) "
                          + "VALUES (?,?,?,'ACTIVE')", userId, deviceId, label);
            } catch (DuplicateKeyException dup) {
                // 并发：另一个连接刚插进同一台设备。唯一键是裁决点，这里重跑一次 UPDATE 即可。
                touchBinding(userId, deviceId, label);
            }
        } catch (DataAccessException e) {
            if (!isDeviceCapRejection(e)) throw e;
            // 撞上限之后再确认一次「这台是不是本来就绑着」。这是一次纯读，发生在
            // 触发器已经拒绝之后，绝不会放进第 3 台。它兜的是连接参数
            // useAffectedRows=true 那种部署：无改动的 UPDATE 返回 0，
            // 会让上面误以为要新建，然后拿一句「设备满了」怼给一个其实早就绑好的人。
            if (isBoundActive(userId, deviceId)) return;
            throw ApiException.forbidden(deviceCapMessage(userId));
        }
    }

    private int touchBinding(long userId, String deviceId, String label) {
        return jdbc.update("UPDATE user_device SET status='ACTIVE', removed_at=NULL, "
                         + "device_label=COALESCE(?, device_label) "
                         + "WHERE user_id=? AND device_id=?", label, userId, deviceId);
    }

    private boolean isBoundActive(long userId, String deviceId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_device WHERE user_id=? AND device_id=? AND status='ACTIVE'",
                Integer.class, userId, deviceId);
        return n != null && n > 0;
    }

    /**
     * 只认设备上限那一条 SIGNAL，其余 45000 一律原样往上抛。
     *
     * <p>如果这里写成「凡是触发器拒绝都翻成设备上限」，将来任何一条新加的约束
     * 都会被伪装成「设备满了」—— 学员按提示去解绑，解完还是不行，客服查不出原因。
     */
    static boolean isDeviceCapRejection(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause())
            if (t instanceof SQLException se && se.getErrorCode() == 1644) {
                String m = se.getMessage();
                return m != null && m.contains("已绑定 2 台设备");
            }
        return false;
    }

    /** 报出「已经绑了哪两台」，否则学员只知道满了，不知道该解绑谁。这些都是他自己的数据。 */
    private String deviceCapMessage(long userId) {
        var rows = jdbc.queryForList(
                "SELECT device_label, bound_at FROM user_device "
              + "WHERE user_id=? AND status='ACTIVE' ORDER BY bound_at", userId);
        List<String> names = new ArrayList<>();
        for (var r : rows) {
            Object lab = r.get("device_label");
            names.add(lab == null ? "未命名设备" : String.valueOf(lab));
        }
        return "这个账号已经绑定了 " + rows.size() + " 台设备（" + String.join("、", names) + "），"
             + "达到上限。要在当前这台设备上看课，请先到「我的 - 设备管理」里解绑一台。";
    }

    private long codewordIdFor(long userId, long courseId) {
        try {
            watermark.codewordFor(userId, courseId);      // 幂等：没有就签一份，有就用原来那份
        } catch (IllegalStateException e) {
            // 典型是「这门课还没有现行水印码本」。这句话是给运维看的，不是给学员看的，
            // 而且它会泄露课程 id 与内部机制 —— 记日志，对外只说不能播。
            log.error("课程 {} 的水印码字签发失败，拒绝开播", courseId, e);
            throw new ApiException(409, "本课程的播放准备尚未完成，暂时无法观看，请联系平台");
        }
        var rows = jdbc.queryForList(
                "SELECT id FROM wm_codeword WHERE user_id=? AND course_id=?", userId, courseId);
        if (rows.isEmpty())
            throw new IllegalStateException("码字刚签发却查不到：user=" + userId + " course=" + courseId);
        return Entitlements.num(rows.get(0).get("id"));
    }

    private int resumePosition(long userId, long lessonId) {
        var rows = jdbc.queryForList(
                "SELECT max_position_sec FROM learn_progress WHERE user_id=? AND lesson_id=?",
                userId, lessonId);
        return rows.isEmpty() ? 0 : Entitlements.i(rows.get(0).get("max_position_sec"));
    }

    // ------------------------------------------------------------------ 播放列表

    /**
     * 逐用户播放列表。
     *
     * <p>密钥 URI 里带的是<b>同一张令牌</b>，而不是另发一张：多发一张就多一个
     * 有效期与撤销口径要对齐，而对不齐的那天不会有任何提示。
     *
     * <p>分片 URL 走 CDN 域名，不是 OSS 私有桶的签名直链 —— schema 里
     * 「绝不下发直链」说的就是后者。hls_key 只在这里参与拼装，任何 JSON 响应体里都没有它。
     */
    public String playlist(String token, long lessonId, String deviceId, String ip,
                           Long loggedInUserId) {
        var claims = requireToken(token, lessonId, deviceId, ip);
        assertSameUser(loggedInUserId, claims.userId());
        // 复查权益：令牌只有 90 秒，但播放列表是会被存下来反复打开的东西。
        // 这一次查询同时兜住了「权益被后台暂停 / 退款回收」与「讲次被下架」。
        var lesson = entitlements.requireLesson(claims.userId(), lessonId);

        if (cdnBase.isEmpty()) {
            log.error("配置项 wangke.play.cdn-base 为空：分片地址无法拼装");
            throw new ApiException(503, "播放服务尚未完成分发配置，请联系平台");
        }
        String keyUri = "/api/play/key?lessonId=" + lessonId
                + "&token=" + token + "&deviceId=" + enc(claims.deviceId());

        // 每一行分片 URL 单独签一张取片凭据。签名盖住【对象名本身】，所以他手里
        // 只有自己那一份变体的签名 —— 另一个变体的 URL 拼得出来但签不出来。
        // 那正是"把 m3u8 里所有 _b 改成 _a、把自己的水印码字抹平"这条路被切断的地方；
        // 抹平之后取证是【不报错的】，只是谁都指认不到，所以它必须在投递侧就断掉。
        // 见 SegmentToken 的类注释与 2026-08-19 那次对生产的只读实测。
        long uid = claims.userId();
        String hlsKey = trimSlashes(lesson.hlsKey());
        Instant segExp = Instant.now().plusSeconds(
                Math.max(600, props.getPlay().getSegmentTtlSeconds()));
        M3u8Builder.SegmentUrl url = obj -> {
            String object = hlsKey + "/" + obj;
            return cdnBase + "/" + object + "?" + segmentToken.query(uid, lessonId, object, segExp);
        };

        // 投递形态跟【素材】走（lesson.preview()），不跟"他有没有权益"走 ——
        // 理由见 open() 里那段②。
        return watermark.playlist(uid, lessonId, keyUri, url, lesson.preview());
    }

    // ------------------------------------------------------------------ 密钥

    /**
     * 下发 AES-128 内容密钥。
     *
     * <p><b>这条路刻意不查权益。</b>PlayToken 的设计前提是它按「每片一次」被调用
     * （见其类注释），在这里加一次 enrollment 查询会把它变成全站数据库压力最大的地方。
     * 少查这一次的代价是：权益在播放中途失效时，最多还能再解密 90 秒 ——
     * 令牌一到期，下一次续签走的是 /session，那里是查权益的。这个窗口是有意留的。
     *
     * <p>令牌校验必须<b>传入请求里的 lessonId</b>，而不是从令牌自己的 claims 里取。
     * 后者是个恒真比较：拿第 1 讲的令牌去请求第 2 讲的密钥会一路通过，
     * 于是买了试听的人能解开正片每一讲 —— 且全程没有任何报错。
     */
    public byte[] contentKey(String token, long lessonId, String deviceId, String ip,
                             Long loggedInUserId) {
        var claims = requireToken(token, lessonId, deviceId, ip);
        assertSameUser(loggedInUserId, claims.userId());
        return keys.contentKey(lessonId);
    }

    // ------------------------------------------------------------------ 公共

    /** 校验失败时把 PlayToken 给出的具体原因带出去：风控要能分清是哪一种绕过尝试。 */
    private PlayToken.Claims requireToken(String token, long lessonId, String deviceId, String ip) {
        if (token == null || token.isBlank())
            throw ApiException.unauthorized("播放令牌缺失，请重新开始播放");
        var r = playToken.verify(token, lessonId, deviceId == null ? "" : deviceId.trim(),
                ip, Instant.now());
        if (r instanceof PlayToken.Rejected rej) {
            log.warn("播放令牌校验未通过：{}（lesson={} ip={}）", rej.reason(), lessonId, ip);
            throw ApiException.forbidden("播放令牌无效：" + rej.reason() + "，请重新开始播放");
        }
        return ((PlayToken.Ok) r).claims();
    }

    /**
     * 登录态若存在，必须与令牌里的用户一致。
     *
     * <p>这不是多余的：令牌是 HMAC 自证的，服务端不查库就认。如果 A 拿到了 B 的令牌
     * （同网段、同设备指纹的合租场景），仅凭令牌是能通过的。带上这一条之后，
     * 至少「已登录的 A 用 B 的令牌」这条路会被当场堵死。
     */
    static void assertSameUser(Long loggedInUserId, long tokenUserId) {
        // longValue() 是必须的：写成 loggedInUserId != tokenUserId 时，
        // 若右侧将来被改成 Long，这行就悄悄退化成引用比较，超过 127 的 id 恒不相等，
        // 于是所有人都被判成"令牌不属于本账号"—— 或者反过来，恒相等，检查作废。
        if (loggedInUserId != null && loggedInUserId.longValue() != tokenUserId)
            throw ApiException.forbidden("播放令牌不属于当前登录账号，请重新开始播放");
    }

    static String normalizeDeviceId(String raw) {
        String d = raw == null ? "" : raw.trim();
        if (d.length() < DEVICE_ID_MIN || d.length() > DEVICE_ID_MAX)
            throw ApiException.badRequest("设备标识不合法：需要 " + DEVICE_ID_MIN + "~" + DEVICE_ID_MAX + " 个字符");
        for (int i = 0; i < d.length(); i++) {
            char c = d.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                      || (c >= '0' && c <= '9') || c == '-' || c == '_';
            // 放开字符集的代价不是「奇怪的字符串进库」，而是 device_id 里能塞进 '|'，
            // 把播放令牌的载荷切错段。PlayToken.issue 会拒，但那时错误已经离开这里，
            // 报的是「令牌字段数不对」，谁也想不到根因在设备指纹上。
            if (!ok) throw ApiException.badRequest("设备标识含非法字符，只允许字母、数字、- 和 _");
        }
        return d;
    }

    /**
     * ip_region。
     *
     * <p>这里<b>没有</b>真正的 GeoIP 库，所以写进去的是带 UNRESOLVED 前缀的网段，
     * 而不是一个看起来像地名的字符串。理由：不可能旅行检测要拿这一列去算距离，
     * 喂给它一个「看起来对、其实是编的」地名，它会算出一堆基于假坐标的结论，
     * 而且永远不会报错。带前缀的值下游要么显式支持，要么一眼看出没接。
     */
    static String ipRegion(String ip) {
        String scope = PlayToken.ipScope(ip);
        return scope.isEmpty() ? null : "UNRESOLVED/" + scope;
    }

    /** client_ip 是 NOT NULL 且只有 45 字符。拿不到真实 IP 时留个显式标记，不留空串。 */
    static String safeIp(String ip) {
        if (ip == null || ip.isBlank()) return "0.0.0.0";
        return ip.length() > 45 ? ip.substring(0, 45) : ip;
    }

    private static String trimSlashes(String s) {
        return s == null ? "" : s.replaceAll("^/+", "").replaceAll("/+$", "");
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private byte[] randomBytes(int n) { byte[] b = new byte[n]; rnd.nextBytes(b); return b; }
}
