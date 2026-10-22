package tech.dragonai.wangke.learn;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tech.dragonai.wangke.config.WangkeProps;
import tech.dragonai.wangke.protect.RiskEngine;
import tech.dragonai.wangke.protect.SegmentPace;
import tech.dragonai.wangke.protect.SegmentToken;
import tech.dragonai.wangke.web.ApiException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 取一片密文。这是全站请求量最大的一条路：一讲 1800 片，每片一次。
 *
 * <h3>它替换掉的是什么</h3>
 * 分片过去是 nginx 的 {@code /vod/} alias 直出，<b>零凭据、零日志</b>
 * （那个 location 写着 {@code access_log off}）。见 {@link SegmentToken} 的类注释，
 * 那里记着实测与两条后果。现在 {@code /vod/} 变成 {@code internal}，
 * 只能由这条路用 {@code X-Accel-Redirect} 内部转过去。
 *
 * <h3>三条纪律</h3>
 * <ol>
 *   <li><b>不查库。</b>校验完全由签名自证。这条路一秒钟能有上千次，
 *       任何一次数据库往返都会在这里被放大成全站故障。
 *       唯一的库交互是讲次时长（带缓存、只为算速率阈值）与风控落库（有节流）。
 *   <li><b>不读盘。</b>文件由 nginx 用 sendfile 发出去，Java 只回一个头。
 *       几百 KB × 上千片穿过 JVM 堆是白烧的 CPU 与 GC，而且 Range 要自己实现 ——
 *       iOS 原生 HLS 是会发 Range 的。
 *   <li><b>不因为自己出错而挡住播放。</b>速率闸算不出阈值（拿不到片长）时放行，
 *       风控落库失败时只记日志。防护装置把正常学员挡在门外，
 *       比它漏掉一个下载的人糟得多。
 * </ol>
 */
@Service
public class SegmentService {

    private static final Logger log = LoggerFactory.getLogger(SegmentService.class);

    /** 讲次时长缓存的有效期。讲次上架后 duration/segment_count 不再变，这里只防"改过之后一直不生效"。 */
    private static final long META_TTL_MS = 10 * 60_000L;
    /** 同一个 (用户,讲次) 最多每 5 分钟落一条风控事件。不节流的话，一次下载会写出上万行。 */
    private static final long RISK_THROTTLE_MS = 5 * 60_000L;

    private final JdbcTemplate jdbc;
    private final SegmentToken tokens;
    private final SegmentPace pace;
    private final WangkeProps props;
    private final ObjectMapper json = new ObjectMapper();

    private record Key(long userId, long lessonId) {}

    /**
     * @param prefix 本讲的片库目录（如 {@code fde/l2}）
     * @param blocks 本讲交付的片号区间。<b>一场录屏切成四五讲之后，同一个目录归好几讲共用</b>，
     *               所以"这个对象属不属于这一讲"不再是看目录，而要看片号落不落在区间里。
     */
    private record Meta(double segSeconds, String prefix, int[][] blocks, long atMs) {}

    private final Map<Long, Meta> metaCache = new ConcurrentHashMap<>();
    private final Map<Key, Long> lastRiskMs = new ConcurrentHashMap<>();

    public SegmentService(JdbcTemplate jdbc, SegmentToken tokens, WangkeProps props) {
        this.jdbc = jdbc;
        this.tokens = tokens;
        this.props = props;
        var p = props.getPlay();
        this.pace = new SegmentPace(p.getPaceWindowSeconds(), p.getPaceMaxRate(),
                p.getPaceMinSamples(), p.getPaceMaxKeys());
    }

    /**
     * @param internalUri 交给 nginx 的内部路径；{@code null} 表示走读盘回吐那条调试路径
     */
    public record Delivery(long userId, long lessonId, String objectPath, String internalUri) {}

    /**
     * 校验并放行一次取片。
     *
     * @param objectPath 相对片库根的对象路径，如 {@code fde/l3/627_a.ts}
     */
    public Delivery deliver(String objectPath, String l, String u, String e, String s,
                            String ip, Instant now) {
        var r = tokens.verify(objectPath, l, u, e, s, now);
        if (r instanceof SegmentToken.Rejected rej) {
            // 记 warn 不记 error：绝大多数是"票据过期"，那是正常的自愈路径（前端会重开会话）。
            // 真正值得警觉的是"签名不匹配"，那句话会原样出现在这一行里。
            log.warn("取片凭据未通过：{}（object={} ip={}）", rej.reason(), objectPath, ip);
            throw ApiException.forbidden("取片凭据无效：" + rej.reason() + "，请重新开始播放");
        }
        var ok = (SegmentToken.Ok) r;

        int index = segmentIndexOf(objectPath);
        var meta = meta(ok.lessonId());
        // 这一条不是安全闸门 —— 签名已经盖住了对象名，服务端也只给本讲播放列表里的
        // 那些对象签过名，所以外面拼不出一张越界的票。它拦的是【配置漂移】：
        // 讲次区间改了而缓存还是旧的、灌库灌串了片库目录。这两种都会让学员看到
        // 另一讲的内容，而画面照常在动，谁也不会报错。
        if (meta.blocks() != null && !covers(meta, objectPath, index)) {
            log.warn("取片对象不在本讲区间内：object={} lesson={} user={} ip={}",
                    objectPath, ok.lessonId(), ok.userId(), ip);
            throw ApiException.forbidden("这一片不属于当前讲次，请重新开始播放");
        }

        var v = pace.admit(ok.userId(), ok.lessonId(), index,
                meta.segSeconds(), now.toEpochMilli());
        if (!v.allowed()) {
            recordRisk(ok.userId(), ok.lessonId(), v, ip);
            throw new ApiException(429,
                    "取片速度远超正常播放，已被限速。如果你只是在正常观看，"
                  + "请稍等片刻或刷新页面重试");
        }

        var p = props.getPlay();
        String internal = p.isUseXAccel()
                ? trimEnd(p.getVodInternalPrefix()) + "/" + objectPath : null;
        return new Delivery(ok.userId(), ok.lessonId(), objectPath, internal);
    }

    // ------------------------------------------------------------------ 速率阈值

    /**
     * 本讲的取片元数据：单片时长（算速率阈值用）、片库目录与片号区间（判归属用）。
     *
     * <p>单片时长拿不到就留 0 ⇒ {@link SegmentPace} 一律放行。<b>不猜。</b>
     * 用一个默认片长去算阈值的话，阈值会随讲次悄悄漂移，而漂移的方向和幅度谁也说不清 ——
     * 那种闸门比没有闸门更坏，因为它看起来在工作。
     *
     * <p>区间拿不到就留 {@code null} ⇒ 归属检查整个跳过，同理。
     */
    private Meta meta(long lessonId) {
        Meta m = metaCache.get(lessonId);
        long now = System.currentTimeMillis();
        if (m != null && now - m.atMs() < META_TTL_MS) return m;
        double v = 0;
        String prefix = null;
        int[][] blocks = null;
        try {
            var rows = jdbc.queryForList(
                    "SELECT duration_sec, segment_count, hls_key FROM lesson WHERE id=?", lessonId);
            if (!rows.isEmpty()) {
                int dur = Entitlements.i(rows.get(0).get("duration_sec"));
                int segs = Entitlements.i(rows.get(0).get("segment_count"));
                if (dur > 0 && segs > 0) v = dur / (double) segs;
                prefix = trimEnd((String) rows.get(0).get("hls_key"));
                var bs = jdbc.queryForList(
                        "SELECT seg_from, seg_to FROM lesson_segment_block "
                      + "WHERE lesson_id=? ORDER BY sort_no", lessonId);
                if (!bs.isEmpty()) {
                    blocks = new int[bs.size()][2];
                    for (int i = 0; i < bs.size(); i++) {
                        blocks[i][0] = Entitlements.i(bs.get(i).get("seg_from"));
                        blocks[i][1] = Entitlements.i(bs.get(i).get("seg_to"));
                    }
                }
            }
        } catch (DataAccessException ex) {
            // 拿不到就放行：这是旁路检查，数据库抖一下不该把正在看课的人一起带走。
            log.warn("取片元数据拿不到（lesson={}），本次放行", lessonId, ex);
        }
        Meta fresh = new Meta(v, prefix, blocks, now);
        metaCache.put(lessonId, fresh);
        return fresh;
    }

    /** 这个对象是不是本讲的：目录要对得上，片号要落在某一段区间里。 */
    private static boolean covers(Meta m, String objectPath, int index) {
        if (index < 0) return false;
        if (m.prefix() != null && !m.prefix().isEmpty()
                && !objectPath.startsWith(m.prefix() + "/")) return false;
        for (int[] b : m.blocks()) if (index >= b[0] && index < b[1]) return true;
        return false;
    }

    /** {@code 627_a.ts} → 627，{@code 1004.ts} → 1004，认不出返回 -1。 */
    static int segmentIndexOf(String objectPath) {
        int slash = objectPath.lastIndexOf('/');
        String name = slash < 0 ? objectPath : objectPath.substring(slash + 1);
        int i = 0;
        while (i < name.length() && Character.isDigit(name.charAt(i))) i++;
        if (i == 0 || i > 9) return -1;
        return Integer.parseInt(name.substring(0, i));
    }

    // ------------------------------------------------------------------ 风控落库

    /**
     * 写一条 {@code risk_event}。
     *
     * <p><b>这是这张表的第一个写入者。</b>在此之前后台的风控页是一张空表的展示器 ——
     * 页面画得很完整，只是永远没有数据，而"没有数据"和"没有风险"在界面上长得一样。
     *
     * <p>落库失败只记日志：风控是旁路，它挂了不该把正在看课的人一起带走。
     */
    private void recordRisk(long userId, long lessonId, SegmentPace.Verdict v, String ip) {
        Key k = new Key(userId, lessonId);
        long now = System.currentTimeMillis();
        Long last = lastRiskMs.get(k);
        if (last != null && now - last < RISK_THROTTLE_MS) return;
        lastRiskMs.put(k, now);
        if (lastRiskMs.size() > 50_000) lastRiskMs.clear();   // 粗暴但有界；这张表只是节流用

        var hits = new java.util.ArrayList<RiskEngine.Hit>();
        hits.add(new RiskEngine.Hit(RiskEngine.Signal.SEGMENT_RATE_EXCEED,
                String.format("窗口内取 %d 片 / 正常应约 %.0f 片", v.fetched(), v.expected())));
        // 顺序只在速率已经越界之后才作为佐证。单独用它会把"从头看到尾不拖进度条"的
        // 学员判成抓取脚本 —— 那种人的取片序号同样是严格递增一片不落。
        if (v.sequential())
            hits.add(new RiskEngine.Hit(RiskEngine.Signal.SEQUENTIAL_SCAN, "窗口内严格递增一片不落"));

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("lessonId", lessonId);
        detail.put("fetched", v.fetched());
        detail.put("expected", Math.round(v.expected() * 10) / 10.0);
        detail.put("ratio", Math.round(v.ratio() * 10) / 10.0);
        detail.put("maxRate", props.getPlay().getPaceMaxRate());
        detail.put("windowSec", props.getPlay().getPaceWindowSeconds());
        detail.put("elapsedSec", Math.round(v.elapsedSec()));
        detail.put("sequential", v.sequential());
        detail.put("ip", ip);
        detail.put("reasons", hits.stream().map(h -> h.signal().name() + "：" + h.detail()).toList());

        try {
            // `signal` 要加反引号：SIGNAL 是 MySQL 8 的保留字（就是触发器里那个
            // SIGNAL SQLSTATE '45000'）。不加的话报的是 BadSqlGrammarException，
            // 而这里是 catch 住只记 warn 的旁路 —— 表现就是风控页永远是空的、
            // 一句错都不报。集成测试当场把它抓出来了。
            jdbc.update("INSERT INTO risk_event (user_id, `signal`, score, detail) VALUES (?,?,?,?)",
                    userId, RiskEngine.Signal.SEGMENT_RATE_EXCEED.name(),
                    RiskEngine.combinedScore(hits), json.writeValueAsString(detail));
        } catch (Exception ex) {
            log.warn("风控事件落库失败（user={} lesson={}）", userId, lessonId, ex);
        }
        log.warn("取片限速：user={} lesson={} 窗口内 {} 片 / 应约 {} 片（{} 倍），ip={}",
                userId, lessonId, v.fetched(), Math.round(v.expected()),
                Math.round(v.ratio() * 10) / 10.0, ip);
    }

    private static String trimEnd(String s) {
        return s == null ? "" : s.replaceAll("/+$", "");
    }
}
