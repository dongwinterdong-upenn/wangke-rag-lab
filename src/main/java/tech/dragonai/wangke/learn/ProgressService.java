package tech.dragonai.wangke.learn;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tech.dragonai.wangke.web.ApiException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * 学习进度与心跳。
 *
 * <h3>判据：什么算「学过了」</h3>
 * 这里存了两个数，它们的可信度完全不同，混用就会让结业证书失去意义：
 *
 * <ul>
 *   <li><b>max_position_sec（最远播放位置）—— 客户端说了算，允许一次跳到片尾。</b>
 *       它的用途只有一个：断点续播。拖进度条跳过片头是完全正常的操作，
 *       拦它的结果是学员从中间退出后再进来被拽回起点，而这类"安全策略"最后
 *       一定会被产品要求关掉。所以不拦。
 *   <li><b>watched_sec（有效观看时长）—— 服务端说了算，只按墙钟增量发放。</b>
 *       前端心跳可以伪造，位置可以伪造，倍速可以伪造，<b>唯独服务端两次收到心跳
 *       之间真实流逝的时间伪造不了</b>。所以一次心跳最多记
 *       {@code 真实流逝秒数 × 上报倍速} 秒，且不超过 {@link #MAX_CREDIT_PER_BEAT_SEC}。
 * </ul>
 *
 * <p><b>进度百分比、completed_at、以及一切下游（考试的 require_progress_pct、
 * 结业证书）一律以 watched_sec 为准，绝不以 max_position_sec 为准。</b>
 * 这是这个类里最重要的一条：直接把进度条拖到结尾，max_position 到 100%，
 * 而 watched_sec 一秒不涨 —— "已学完"因此不会变成假的。
 *
 * <p>completed_at 要求两条同时成立：<b>看够了</b>（watched ≥ 90% 时长）
 * <b>且看到过尾</b>（max_position ≥ 95% 时长）。缺任何一条都能被单独刷：
 * 只看前五分钟循环一百遍能刷满第一条，一次拖到底能刷满第二条。
 *
 * <h3>为什么每次记账都要行锁</h3>
 * 增量是从"上次记账时刻"算出来的。两个标签页同时发心跳时，先查后写会双双读到
 * 同一个 last_credit_at，各自记一份增量 —— 开 N 个标签页就有 N 倍进度，
 * 而且完全不报错。所以记账走事务 + SELECT ... FOR UPDATE。
 */
@Service
public class ProgressService {

    /** 与 ck_heartbeat_rate 同源。两处写不同的数，就会出现库层拒绝而应用层以为合法的请求。 */
    static final double MIN_RATE = 0.5, MAX_RATE = 3.0;

    /**
     * 单次心跳最多记账 60 秒。
     *
     * <p>这个 60 不是随手取的：它正是 trg_progress_sanity 里
     * {@code watched_sec > heartbeat_count * 60} 用的那个数。应用层的闸门贴着库层的天花板，
     * 于是"库层拒绝"这件事在正常路径上永远不会发生 —— 一旦发生就一定是真出了问题，
     * 而不是又一次正常业务撞到了保护性约束。
     *
     * <p>它挡住的是：客户端静默 10 分钟后补发一次心跳，想一次性领走 600 秒。
     */
    static final int MAX_CREDIT_PER_BEAT_SEC = 60;

    /** 完成判据的两条线。 */
    static final double COMPLETE_WATCHED_RATIO = 0.90, COMPLETE_POSITION_RATIO = 0.95;

    /**
     * 上报位置允许比库里的 duration_sec 多出的秒数。
     *
     * <p>转码出来的实际时长与录入的 duration_sec 差个一两秒是常态。
     * 完全不容忍会让最后一次心跳被拒（学员看到"上报失败"）；
     * 而无脑夹取到 duration 则会把"两边时长口径真的不一致"这件事永久藏起来 ——
     * 表现是进度永远停在 99%，没人查得出为什么。所以：小于容差就夹取，超过就报出来。
     */
    static final int DURATION_TOLERANCE_SEC = 2;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Entitlements entitlements;

    public ProgressService(JdbcTemplate jdbc, TransactionTemplate tx, Entitlements entitlements) {
        this.jdbc = jdbc; this.tx = tx; this.entitlements = entitlements;
    }

    /**
     * @param creditedSec 本次真正记进 watched_sec 的秒数。返回给前端不是为了好看：
     *                    它是"你刚才那一下被记了多少"的唯一可观测量，
     *                    没有它，防刷闸门在生产上是个黑盒，只能靠猜。
     */
    public record Snapshot(long lessonId, long courseId, int durationSec,
                           int maxPositionSec, int watchedSec, int heartbeatCount,
                           int progressPct, boolean completed, int creditedSec) {}

    // ------------------------------------------------------------------ 进度上报

    /**
     * 上报观看位置。只推进断点续播用的 max_position_sec，<b>不发放任何 watched_sec</b>。
     *
     * <p>为什么不顺手记账：这个接口没有任何凭据能证明"这段时间人在看"。
     * 有效观看时长只从心跳来，心跳带着会话、倍速与逐条明细（learn_heartbeat），
     * 事后可复盘。两条路都能记账的话，刷进度的人只会挑不留明细的那条。
     */
    public Snapshot report(long userId, long lessonId, int positionSec) {
        var lesson = entitlements.requireLesson(userId, lessonId);
        int position = clampPosition(positionSec, lesson.durationSec());
        return tx.execute(st -> {
            var cur = lockRow(userId, lesson.courseId(), lessonId);
            int newMax = Math.max(cur.maxPosition, position);
            boolean completed = isCompleted(cur.watched, newMax, lesson.durationSec());
            jdbc.update("UPDATE learn_progress SET max_position_sec=?, last_seen_at=NOW(3), "
                      + "completed_at=IF(? AND completed_at IS NULL, NOW(3), completed_at) "
                      + "WHERE user_id=? AND lesson_id=?",
                    newMax, completed, userId, lessonId);
            return snapshot(lesson, newMax, cur.watched, cur.heartbeats, 0);
        });
    }

    // ------------------------------------------------------------------ 心跳

    /**
     * 播放心跳。落一行 learn_heartbeat 明细，并按墙钟闸门给 watched_sec 记账。
     *
     * @param sessionId 必须是本人、本讲、尚未结束的那次开播。<b>不校验会话的话，
     *                  这个接口就是一个不用打开播放器就能刷满整门课的接口</b> ——
     *                  编一个 session_id 循环 POST 即可，而 learn_heartbeat 里
     *                  会留下一堆指向不存在会话的明细，事后复盘也串不起来。
     */
    public Snapshot heartbeat(long userId, long lessonId, String sessionId, int positionSec,
                              double playbackRate, String ip, String deviceId) {
        var lesson = entitlements.requireLesson(userId, lessonId);
        int position = clampPosition(positionSec, lesson.durationSec());
        double rate = normalizeRate(playbackRate);
        requireOwnSession(userId, lessonId, sessionId);

        return tx.execute(st -> {
            var cur = lockRow(userId, lesson.courseId(), lessonId);

            // 记账闸门。elapsed 由数据库算（TIMESTAMPDIFF），不是应用侧两个 now 相减：
            // 多实例部署下应用机之间时钟能差几秒，而差出来的那几秒会被当成有效观看时长。
            int credit;
            if (cur.elapsedSinceCredit == null) {
                // 起播那一拍：还没有"上次记账时刻"，没有任何区间可以计费，记 0。
                credit = 0;
            } else {
                long byWallClock = (long) Math.floor(Math.max(0, cur.elapsedSinceCredit) * rate);
                credit = (int) Math.min(byWallClock, MAX_CREDIT_PER_BEAT_SEC);
            }
            credit = Math.max(0, Math.min(credit, lesson.durationSec() - cur.watched));

            int newWatched = cur.watched + credit;
            int newMax = Math.max(cur.maxPosition, position);
            int newBeats = cur.heartbeats + 1;
            boolean completed = isCompleted(newWatched, newMax, lesson.durationSec());

            jdbc.update("INSERT INTO learn_heartbeat "
                      + "(user_id,lesson_id,session_id,position_sec,playback_rate,client_ip,device_id) "
                      + "VALUES (?,?,?,?,?,?,?)",
                    userId, lessonId, sessionId, position,
                    BigDecimal.valueOf(rate).setScale(1, RoundingMode.HALF_UP),
                    PlayService.safeIp(ip), deviceId);

            jdbc.update("UPDATE learn_progress SET max_position_sec=?, watched_sec=?, "
                      + "heartbeat_count=?, last_seen_at=NOW(3), last_credit_at=NOW(3), "
                      + "completed_at=IF(? AND completed_at IS NULL, NOW(3), completed_at) "
                      + "WHERE user_id=? AND lesson_id=?",
                    newMax, newWatched, newBeats, completed, userId, lessonId);

            return snapshot(lesson, newMax, newWatched, newBeats, credit);
        });
    }

    // ------------------------------------------------------------------ 内部

    private record Current(int maxPosition, int watched, int heartbeats, Long elapsedSinceCredit) {}

    /**
     * 取当前行并加锁。行不存在时先按唯一键补一行。
     *
     * <p>补行走 INSERT ... ON DUPLICATE KEY UPDATE 而不是"先 SELECT 看看有没有"：
     * 同一讲的两次首播并发时，先查后插会双双查到"没有"，然后一个成功一个撞 uk_user_lesson，
     * 后者拿到的是一句唯一键冲突 —— 学员看到"重复提交"，其实他只是在看课。
     */
    private Current lockRow(long userId, long courseId, long lessonId) {
        jdbc.update("INSERT INTO learn_progress (user_id,course_id,lesson_id) VALUES (?,?,?) "
                  + "ON DUPLICATE KEY UPDATE user_id=user_id", userId, courseId, lessonId);
        Map<String, Object> r = jdbc.queryForMap(
                "SELECT max_position_sec, watched_sec, heartbeat_count, "
              + "       TIMESTAMPDIFF(SECOND, last_credit_at, NOW(3)) AS elapsed_since_credit "
              + "  FROM learn_progress WHERE user_id=? AND lesson_id=? FOR UPDATE",
                userId, lessonId);
        Object elapsed = r.get("elapsed_since_credit");
        return new Current(Entitlements.i(r.get("max_position_sec")),
                Entitlements.i(r.get("watched_sec")),
                Entitlements.i(r.get("heartbeat_count")),
                elapsed == null ? null : ((Number) elapsed).longValue());
    }

    /**
     * 会话归属检查写进 WHERE，不是"查出会话再比 user_id"。
     * 后者查出来的那一行会带着别人的 lesson_id / device_id 停在内存里，
     * 是个现成的越权探针。
     */
    private void requireOwnSession(long userId, long lessonId, String sessionId) {
        if (sessionId == null || sessionId.isBlank())
            throw ApiException.badRequest("心跳必须带上开播时拿到的 sessionId");
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM play_session "
              + "WHERE session_id=? AND user_id=? AND lesson_id=? AND ended_at IS NULL",
                Integer.class, sessionId, userId, lessonId);
        if (n == null || n == 0)
            throw ApiException.forbidden("播放会话无效或已结束，请重新开始播放");
    }

    static int clampPosition(int positionSec, int durationSec) {
        if (positionSec < 0) throw ApiException.badRequest("观看位置不能为负数");
        if (positionSec > durationSec + DURATION_TOLERANCE_SEC)
            throw ApiException.badRequest("上报的观看位置（" + positionSec + " 秒）超过本讲时长（"
                    + durationSec + " 秒），请检查播放器与课程时长是否一致");
        return Math.min(positionSec, durationSec);
    }

    static double normalizeRate(double raw) {
        // 先按 DECIMAL(3,1) 的精度取整再判范围：3.04 这种值入库会被数据库四舍五入成 3.0，
        // 如果这里按 3.04 放行、按 3.0 入库，应用与库对"这次心跳的倍速是多少"的记录就不是一回事。
        double r = BigDecimal.valueOf(raw).setScale(1, RoundingMode.HALF_UP).doubleValue();
        if (r < MIN_RATE || r > MAX_RATE)
            throw ApiException.badRequest("播放倍速只允许 " + MIN_RATE + " ~ " + MAX_RATE + " 倍");
        return r;
    }

    static boolean isCompleted(int watchedSec, int maxPositionSec, int durationSec) {
        if (durationSec <= 0) return false;
        return watchedSec >= durationSec * COMPLETE_WATCHED_RATIO
            && maxPositionSec >= durationSec * COMPLETE_POSITION_RATIO;
    }

    /** 百分比一律按 watched_sec 算。用 max_position 算的话，一次拖到底就是 100%。 */
    static int progressPct(int watchedSec, int durationSec) {
        if (durationSec <= 0) return 0;
        return (int) Math.min(100, (long) watchedSec * 100 / durationSec);
    }

    private static Snapshot snapshot(Entitlements.LessonAccess l, int maxPos, int watched,
                                     int beats, int credited) {
        return new Snapshot(l.lessonId(), l.courseId(), l.durationSec(), maxPos, watched, beats,
                progressPct(watched, l.durationSec()),
                isCompleted(watched, maxPos, l.durationSec()), credited);
    }
}
