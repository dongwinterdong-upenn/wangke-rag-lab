package tech.dragonai.wangke.learn;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tech.dragonai.wangke.web.ApiException;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 讲次内容密钥（HLS AES-128）的保管与下发。
 *
 * <p>这块在 schema 里原本是<b>空的</b>：#EXT-X-KEY 指着一个要凭令牌才取得到的 URI，
 * 但「取得到什么」没有任何存放处。补法见 migrations/20260811_讲次内容密钥.sql，
 * 三条设计约束在这里落地：
 *
 * <ol>
 *   <li><b>一讲一把。</b>共用一把等于一讲泄漏即全课程泄漏，而且轮换要重转码全部视频，
 *       实际上等于永不轮换。
 *   <li><b>密文存库，KEK 在配置。</b>拖库拿到的是 44 字节密文；没有配置里那把 KEK，
 *       它解不开也用不了。
 *   <li><b>播放路径绝不现场生成密钥。</b>见 {@link #contentKey(long)}。
 * </ol>
 */
@Service
public class LessonKeyService {

    private static final Logger log = LoggerFactory.getLogger(LessonKeyService.class);

    /** HLS EXT-X-KEY METHOD=AES-128：内容密钥固定 16 字节，多一字节少一字节播放器都不认。 */
    static final int CONTENT_KEY_BYTES = 16;
    private static final int IV_LEN = 12;          // GCM 标准 96 位
    private static final int TAG_BITS = 128;
    private static final String KEK_ID = "v1";

    /**
     * 解密后的密钥在进程内缓存 60 秒。
     *
     * <p>为什么要缓存：密钥请求是全站最高频的路径（见 PlayToken 的类注释 ——
     * 它是按「每片一次」设计的），每次都回库 + 一次 GCM 解密会让这条路成为数据库压力最大的地方。
     *
     * <p>为什么<b>不</b>永久缓存：轮换（旧行置 RETIRED、插入新版本）之后，
     * 永久缓存会让老进程继续下发旧密钥，而新切的分片是用新密钥加的 ——
     * 表现是「部分机器上播不了」，服务端零报错。60 秒是「轮换一分钟内自动生效」
     * 与「不打库」之间的取舍点。
     */
    private static final long CACHE_TTL_MS = 60_000L;

    private final JdbcTemplate jdbc;
    private final String kekB64;
    private final SecureRandom rnd = new SecureRandom();
    private final ConcurrentHashMap<Long, Cached> cache = new ConcurrentHashMap<>();
    private volatile SecretKey kek;

    private record Cached(byte[] key, long expiresAtMs) {}

    // 两个构造器 → 必须显式标注哪个是注入用的，否则 Spring 在启动期报「候选构造器有歧义」。
    @org.springframework.beans.factory.annotation.Autowired
    public LessonKeyService(JdbcTemplate jdbc,
                            @Value("${wangke.secrets.content-kek:}") String kekB64) {
        this.jdbc = jdbc;
        this.kekB64 = kekB64;
    }

    /** 测试与内容入库流水线用的直接构造：绕开 Spring 也能拿到同一套封装/解封逻辑。 */
    public LessonKeyService(JdbcTemplate jdbc, byte[] kek32) {
        this.jdbc = jdbc;
        this.kekB64 = Base64.getEncoder().encodeToString(kek32);
    }

    // ------------------------------------------------------------------ 封装

    /**
     * 给一讲生成并封存内容密钥。内容入库流水线（切片 → 加密 → 上传）在<b>加密之前</b>调用，
     * 拿到的明文密钥交给转码器，库里只留密文。
     *
     * <p><b>幂等靠唯一键裁决，不靠「先查有没有」。</b>两个流水线实例同时处理同一讲时，
     * 先查后写会双双查到「没有」，然后各写一把 —— uk_lesson_active 会让后到的那个撞唯一键，
     * 这里把撞键当成「已经有了」正常返回，而不是报错。
     *
     * <p><b>已存在时绝不覆盖。</b>覆盖一把已经用于加密分片的密钥，后果是那一讲从此
     * 谁也播不了，且不可逆（分片已经加密上传了）—— 而服务端不会有任何报错，
     * 症状只出现在播放器那一侧的一句「解密失败」。所以这里没有任何 UPDATE 路径。
     *
     * @return 该讲现行的明文内容密钥（新生成的，或库里已有的那把）
     */
    public byte[] ensureKey(long lessonId) {
        var lesson = jdbc.queryForList("SELECT course_id FROM lesson WHERE id=?", lessonId);
        if (lesson.isEmpty()) throw new IllegalArgumentException("讲次不存在：" + lessonId);
        long courseId = Entitlements.num(lesson.get(0).get("course_id"));

        byte[] fresh = new byte[CONTENT_KEY_BYTES];
        rnd.nextBytes(fresh);
        try {
            jdbc.update("INSERT INTO lesson_content_key "
                      + "(lesson_id,course_id,key_version,algo,key_enc,kek_id) "
                      + "VALUES (?,?,1,'AES-128',?,?)",
                    lessonId, courseId, seal(fresh, lessonId, 1), KEK_ID);
            return fresh;
        } catch (DuplicateKeyException e) {
            return contentKey(lessonId);
        }
    }

    /**
     * 把一行内容密钥<b>改绑</b>到另一讲：按源讲次的 AAD 解封，再按本讲的 AAD 重新封装。
     *
     * <h3>为什么需要这件事</h3>
     * 一场录屏被重切成几讲时（2026-08-21 那次：4 讲 → 16 讲），新讲次共用的是
     * <b>同一批分片</b>，所以必须是同一把内容密钥。而密文是按 (讲次, 版本) 绑定 AAD 的，
     * 直接把 key_enc 复制到新讲次那一行，解封会当场失败 —— 那正是 {@link #aad} 存在的理由。
     * 复制之后必须由持有 KEK 的这一侧重新封一次。
     *
     * <p>2026-08-21 首次发布就撞在这里：SQL 把 key_enc 原样复制给了 16 个新讲次，
     * 库层四道闸门全绿、播放列表与分片都取得到，唯独 /api/play/key 回 409。
     * 前面所有的"绿"都只证明代码自洽，是真的去播了一遍才碰到它。
     *
     * <p><b>幂等。</b>已经绑对了就直接返回 false，不做任何改动 —— 重跑一次发布脚本
     * 不该把一行好的密文重新封一遍（每封一次就多一次出错的机会）。
     *
     * @return true 表示这次真的改绑了；false 表示本来就是对的
     */
    public boolean rebind(long lessonId, long srcLessonId) {
        var rows = jdbc.queryForList(
                "SELECT key_version, key_enc FROM lesson_content_key "
              + "WHERE lesson_id=? AND status='ACTIVE'", lessonId);
        if (rows.isEmpty())
            throw ApiException.notFound("讲次 " + lessonId + " 没有现行内容密钥，无从改绑");
        int version = Entitlements.i(rows.get(0).get("key_version"));
        byte[] sealed = (byte[]) rows.get(0).get("key_enc");

        try {
            open(sealed, lessonId, version);
            return false;                       // 本来就绑对了
        } catch (IllegalStateException ignore) {
            // 继续往下：按源讲次试一次
        }

        byte[] plain;
        try {
            plain = open(sealed, srcLessonId, version);
        } catch (IllegalStateException e) {
            throw ApiException.conflict("讲次 " + lessonId + " 的密文既不属于它自己，"
                    + "也不属于讲次 " + srcLessonId + "：源讲次填错了，或者这行密文是别的 KEK 封的");
        }
        if (plain.length != CONTENT_KEY_BYTES)
            throw new IllegalStateException("解出 " + plain.length + " 字节，AES-128 要求 "
                    + CONTENT_KEY_BYTES + " 字节");
        jdbc.update("UPDATE lesson_content_key SET key_enc=? WHERE lesson_id=? AND status='ACTIVE'",
                seal(plain, lessonId, version), lessonId);
        cache.remove(lessonId);
        Arrays.fill(plain, (byte) 0);
        log.info("讲次 {} 的内容密钥已由讲次 {} 改绑（明文不入日志）", lessonId, srcLessonId);
        return true;
    }

    // ------------------------------------------------------------------ 下发

    /**
     * 取某一讲的明文内容密钥，供 /api/play/key 下发给播放器。
     *
     * <p><b>库里没有时一律拒绝，绝不现场生成。</b>现场生成出来的密钥与当初加密分片用的
     * 不是同一把，播放器只会报一句「解密失败」，服务端这边一切正常 ——
     * 那是最难查的一类故障：所有监控都是绿的，只有用户在说看不了。
     * 拒绝反而会在服务端留下一条明确的 409，指向真正的缺口：这一讲还没做加密封装。
     */
    public byte[] contentKey(long lessonId) {
        Cached hit = cache.get(lessonId);
        long now = System.currentTimeMillis();
        if (hit != null && hit.expiresAtMs() > now) return hit.key().clone();

        var rows = jdbc.queryForList(
                "SELECT key_version, key_enc FROM lesson_content_key "
              + "WHERE lesson_id=? AND status='ACTIVE'", lessonId);
        if (rows.isEmpty()) {
            log.error("讲次 {} 没有现行内容密钥：分片可能是用别处的密钥加的，或者根本没做加密封装", lessonId);
            throw new ApiException(409, "本讲尚未完成加密封装，暂时无法播放，请联系平台");
        }
        int version = Entitlements.i(rows.get(0).get("key_version"));
        byte[] key = open((byte[]) rows.get(0).get("key_enc"), lessonId, version);
        if (key.length != CONTENT_KEY_BYTES)
            throw new IllegalStateException("讲次 " + lessonId + " 的内容密钥解出 " + key.length
                    + " 字节，AES-128 要求 " + CONTENT_KEY_BYTES + " 字节");
        cache.put(lessonId, new Cached(key.clone(), now + CACHE_TTL_MS));
        return key;
    }

    // ------------------------------------------------------------------ 密码学

    /**
     * AAD 绑定 (讲次, 版本)。与 CodeVault 绑 code_hash 是同一个理由：
     * 拿到库写权限的人把 A 讲的密文复制到 B 讲那一行时，解密当场失败，
     * 而不是安静地解出另一讲的密钥。加密本身拦不住「密文搬家」。
     */
    private static byte[] aad(long lessonId, int version) {
        return ("wangke/lesson-key/" + lessonId + "/v" + version).getBytes(StandardCharsets.US_ASCII);
    }

    byte[] seal(byte[] plain, long lessonId, int version) {
        try {
            byte[] iv = new byte[IV_LEN];
            rnd.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, kek(), new GCMParameterSpec(TAG_BITS, iv));
            c.updateAAD(aad(lessonId, version));
            byte[] ct = c.doFinal(plain);
            byte[] out = new byte[IV_LEN + ct.length];
            System.arraycopy(iv, 0, out, 0, IV_LEN);
            System.arraycopy(ct, 0, out, IV_LEN, ct.length);
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("内容密钥封装失败", e);
        }
    }

    byte[] open(byte[] sealed, long lessonId, int version) {
        if (sealed == null || sealed.length <= IV_LEN)
            throw new IllegalStateException("内容密钥密文长度不合法："
                    + (sealed == null ? "null" : sealed.length));
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, kek(),
                    new GCMParameterSpec(TAG_BITS, Arrays.copyOf(sealed, IV_LEN)));
            c.updateAAD(aad(lessonId, version));
            return c.doFinal(sealed, IV_LEN, sealed.length - IV_LEN);
        } catch (javax.crypto.AEADBadTagException e) {
            throw new IllegalStateException("讲次 " + lessonId + " 的内容密钥校验失败："
                    + "KEK 不匹配，或这行密文不属于这一讲（被搬过家）", e);
        } catch (Exception e) {
            throw new IllegalStateException("内容密钥解封失败", e);
        }
    }

    /**
     * KEK 的校验点。
     *
     * <p><b>这套仓库的规矩是「凭据缺失就拒绝启动」（见 AppConfig），而这里是惰性校验 ——
     * 这是一次有意的降级，不是疏忽。</b>原因是我不能改 AppConfig（共享文件），
     * 而把校验放在唯一另一个绕不过去的点上（第一次真正用到 KEK 的时刻），
     * 至少保证了「密钥没配」永远不会被当成「密钥能用」。
     * 正确的最终形态是把它挪进 AppConfig.requireKey32 —— 已写进交付的 sharedEdits。
     */
    private SecretKey kek() {
        SecretKey k = kek;
        if (k != null) return k;
        synchronized (this) {
            if (kek != null) return kek;
            if (kekB64 == null || kekB64.isBlank()) {
                log.error("配置项 wangke.secrets.content-kek 为空：内容密钥无法解封，播放全线不可用");
                throw new ApiException(503, "播放服务尚未完成密钥配置，请联系平台");
            }
            byte[] raw;
            try {
                raw = Base64.getDecoder().decode(kekB64.trim());
            } catch (IllegalArgumentException e) {
                log.error("配置项 wangke.secrets.content-kek 不是合法 base64");
                throw new ApiException(503, "播放服务尚未完成密钥配置，请联系平台");
            }
            if (raw.length != 32) {
                log.error("配置项 wangke.secrets.content-kek 解出 {} 字节，要求 32 字节（AES-256）", raw.length);
                throw new ApiException(503, "播放服务尚未完成密钥配置，请联系平台");
            }
            // 占位密钥（全 0 / 全同一字节）是部署时最常见的事故：配置没填，
            // 程序照样跑，密文照样写，等发现时全库都是用空密钥封的。
            boolean allSame = true;
            for (byte b : raw) if (b != raw[0]) { allSame = false; break; }
            if (allSame) {
                log.error("配置项 wangke.secrets.content-kek 每个字节都相同，看起来是没填的占位值");
                throw new ApiException(503, "播放服务尚未完成密钥配置，请联系平台");
            }
            kek = new SecretKeySpec(raw, "AES");
            return kek;
        }
    }
}
