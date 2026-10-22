package tech.dragonai.wangke.learn;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tech.dragonai.wangke.web.ApiException;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 课程与讲次的取数。
 *
 * <p>三个接口的可见性各不相同，而它们查的是同一批表，所以最容易出的错是
 * <b>把某个接口的字段集合抄到另一个接口上</b>。这里每个方法各自拼自己的字段，
 * 一个都不复用，就是为了让"公开页多了一列 hls_key"这种事必须是有人手写出来的，
 * 而不是改一处默认字段列表就顺带发生的。
 *
 * <p><b>hls_key、segment_count、wm_bit_offset 一律不出现在任何返回体里。</b>
 * 前两个是私有桶的对象定位信息，最后一个是水印布局 —— 知道位区间就能针对性地
 * 只擦那几片，抗合谋直接归零。
 */
@Service
public class CourseService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbc;
    private final Entitlements entitlements;

    public CourseService(JdbcTemplate jdbc, Entitlements entitlements) {
        this.jdbc = jdbc; this.entitlements = entitlements;
    }

    // ------------------------------------------------------------------ 公开课程介绍

    /**
     * 课程介绍。公开，不需要登录。
     *
     * <p>这里返回 404 而不是 403，且措辞承认"课程不存在"—— 与 {@link Entitlements} 那条
     * 「不泄露课程是否存在」不矛盾：这是<b>公开售卖页</b>，课程存不存在本来就是公开信息。
     * 那条规则针对的是需要权益的取数路径。
     */
    public Map<String, Object> publicCourse(long courseId) {
        var rows = jdbc.queryForList(
                "SELECT id,title,subtitle,cover_url,total_lessons,total_seconds,"
              + "activate_months,access_months FROM course WHERE id=? AND status='ON'", courseId);
        if (rows.isEmpty()) throw ApiException.notFound("课程不存在或尚未上架");
        var c = rows.get(0);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("courseId", Entitlements.num(c.get("id")));
        out.put("title", c.get("title"));
        out.put("subtitle", c.get("subtitle"));
        out.put("coverUrl", c.get("cover_url"));
        out.put("activateMonths", c.get("activate_months"));
        out.put("accessMonths", c.get("access_months"));

        // 目录用 ON 的讲次现算，不取 course.total_lessons/total_seconds：
        // 那两列是冗余汇总，下架一讲而忘了回写就会让页面上的讲数与目录对不上，
        // 而对不上不会报错，只会让客户觉得"少了两讲"。
        var lessons = jdbc.queryForList(
                "SELECT ch.id AS chapter_id, ch.title AS chapter_title, ch.sort_no AS chapter_sort, "
              + "       l.id, l.title, l.sort_no, l.duration_sec, l.is_preview "
              + "  FROM chapter ch LEFT JOIN lesson l "
              + "    ON l.chapter_id = ch.id AND l.status='ON' "
              + " WHERE ch.course_id=? ORDER BY ch.sort_no, l.sort_no, l.id", courseId);

        int total = 0, seconds = 0;
        for (var r : lessons) if (r.get("id") != null) { total++; seconds += Entitlements.i(r.get("duration_sec")); }
        out.put("totalLessons", total);
        out.put("totalSeconds", seconds);
        int sectionCount = 0;
        var sections = sectionsOf(courseId);
        for (var v : sections.values()) sectionCount += v.size();
        // 目录深度也报出来：售卖页写「4 讲」和写「4 讲 · 119 节」是两回事，
        // 而前者恰好是这门课上线以来一直在写的那个数。
        out.put("totalSections", sectionCount);
        out.put("chapters", groupChapters(lessons, null, sections));
        return out;
    }

    // ------------------------------------------------------------------ 我的课程

    /**
     * 我的课程。列的是<b>这个人自己的权益行</b>，所以越权判定就是 WHERE user_id=?，
     * 没有第二处可以绕开的地方。
     *
     * <p>过期的权益照样列出来（带 expired 标记），不过滤掉：学员要能看见
     * "我买过、已到期"，客服也要能凭它对话。真正要拦的是取内容，那在
     * {@link Entitlements#requireLesson} 上。
     */
    public List<Map<String, Object>> myCourses(long userId) {
        var rows = jdbc.queryForList(
                "SELECT e.course_id, e.status, e.valid_from, e.valid_until, e.source, "
              + "       c.title, c.subtitle, c.cover_url, "
              + "       e.valid_until <= NOW(3) AS expired, "
              + "       (SELECT COALESCE(SUM(l.duration_sec),0) FROM lesson l "
              + "         WHERE l.course_id=e.course_id AND l.status='ON') AS total_sec, "
              + "       (SELECT COUNT(*) FROM lesson l WHERE l.course_id=e.course_id AND l.status='ON') AS total_lessons, "
              // 逐讲 LEAST 夹取再求和：某一讲的 duration_sec 被下调（重新剪辑）之后，
              // 旧的 watched_sec 会比新时长还大，直接相加会算出超过 100% 的进度。
              + "       (SELECT COALESCE(SUM(LEAST(p.watched_sec, l.duration_sec)),0) "
              + "          FROM learn_progress p JOIN lesson l ON l.id=p.lesson_id AND l.status='ON' "
              + "         WHERE p.user_id=e.user_id AND p.course_id=e.course_id) AS watched_sec, "
              + "       (SELECT COUNT(*) FROM learn_progress p JOIN lesson l ON l.id=p.lesson_id AND l.status='ON' "
              + "         WHERE p.user_id=e.user_id AND p.course_id=e.course_id "
              + "           AND p.completed_at IS NOT NULL) AS done_lessons "
              + "  FROM enrollment e JOIN course c ON c.id=e.course_id "
              + " WHERE e.user_id=? ORDER BY e.created_at DESC", userId);

        List<Map<String, Object>> out = new ArrayList<>();
        for (var r : rows) {
            int totalSec = Entitlements.i(r.get("total_sec"));
            int watched = Entitlements.i(r.get("watched_sec"));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("courseId", Entitlements.num(r.get("course_id")));
            m.put("title", r.get("title"));
            m.put("subtitle", r.get("subtitle"));
            m.put("coverUrl", r.get("cover_url"));
            m.put("status", r.get("status"));
            m.put("source", r.get("source"));
            m.put("validFrom", ts(r.get("valid_from")));
            m.put("validUntil", ts(r.get("valid_until")));
            // 过期与否由数据库同一个 NOW(3) 判，不在 Java 里拿 LocalDateTime.now() 再比一次：
            // 两处时钟不同源时，列表说"有效"而点进去 403，学员会以为系统坏了。
            m.put("expired", asBool(r.get("expired")) || !"ACTIVE".equals(r.get("status")));
            m.put("totalLessons", r.get("total_lessons"));
            m.put("doneLessons", r.get("done_lessons"));
            m.put("totalSeconds", totalSec);
            m.put("watchedSeconds", watched);
            m.put("progressPct", ProgressService.progressPct(watched, totalSec));
            out.add(m);
        }
        return out;
    }

    // ------------------------------------------------------------------ 讲次列表（要权益）

    /** 讲次列表。取数前先过权益闸门，无权益与课程不存在返回同一句 403。 */
    public Map<String, Object> lessonsOf(long userId, long courseId) {
        var access = entitlements.requireCourse(userId, courseId);

        var rows = jdbc.queryForList(
                "SELECT ch.id AS chapter_id, ch.title AS chapter_title, ch.sort_no AS chapter_sort, "
              + "       l.id, l.title, l.sort_no, l.duration_sec, l.is_preview, "
              + "       p.max_position_sec, p.watched_sec, p.completed_at "
              + "  FROM chapter ch "
              + "  LEFT JOIN lesson l ON l.chapter_id = ch.id AND l.status='ON' "
              // 进度按 (本人, 讲次) 关联。user_id 写在 ON 里而不是 WHERE 里是必须的：
              // 放进 WHERE 会把 LEFT JOIN 退化成 INNER JOIN，还没看过的讲次整条消失，
              // 目录里就只剩看过的那几讲 —— 而这不会报错，只会让人以为课程变短了。
              + "  LEFT JOIN learn_progress p ON p.lesson_id = l.id AND p.user_id = ? "
              + " WHERE ch.course_id=? ORDER BY ch.sort_no, l.sort_no, l.id", userId, courseId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("courseId", courseId);
        out.put("validUntil", ts(access.validUntil()));
        out.put("chapters", groupChapters(rows, userId, sectionsOf(courseId)));
        return out;
    }

    /**
     * 一门课所有已上架讲次的内容分节，按 lesson_id 归堆。
     *
     * <p><b>单独一条查询，不跟讲次那条 JOIN 在一起。</b>合进去的话，一讲有 30 节
     * 就会把那一讲的行乘成 30 份，而那条查询同时还 LEFT JOIN 着「空章」与「本人进度」——
     * 三者叠在一起之后，「这一讲他看了多少」会被重复计入 30 次，
     * 而这不会报错，只会让进度百分比莫名其妙地变成几百。
     */
    private Map<Long, List<Map<String, Object>>> sectionsOf(long courseId) {
        var rows = jdbc.queryForList(
                "SELECT s.lesson_id, s.sort_no, s.title, s.summary, s.kind, s.start_sec, s.end_sec "
              + "  FROM lesson_section s JOIN lesson l ON l.id = s.lesson_id "
              + " WHERE l.course_id=? AND l.status='ON' "
              + " ORDER BY s.lesson_id, s.sort_no", courseId);
        Map<Long, List<Map<String, Object>>> out = new LinkedHashMap<>();
        for (var r : rows) out.computeIfAbsent(Entitlements.num(r.get("lesson_id")),
                k -> new ArrayList<>()).add(r);
        return out;
    }

    // ------------------------------------------------------------------ 组装

    /**
     * @param userId   非 null 表示这是带进度的私有视图；null 是公开目录，绝不带进度字段
     * @param sections 逐讲的内容分节，按 lesson_id 归堆；没有分节的讲次给空列表而不是 null ——
     *                 前端拿到 null 要多写一条判断，而漏写的那次表现是整页白屏
     */
    private List<Map<String, Object>> groupChapters(List<Map<String, Object>> rows, Long userId,
                                                    Map<Long, List<Map<String, Object>>> sections) {
        List<Map<String, Object>> chapters = new ArrayList<>();
        Map<Long, List<Map<String, Object>>> byChapter = new LinkedHashMap<>();
        // 用 LinkedHashMap 而不是 Map.of：Map.of 不接受 null 值，
        // 章标题将来若允许为空，这里会变成一个只在生产数据上才复现的 NPE。
        Map<Long, String> titles = new LinkedHashMap<>();

        for (var r : rows) {
            long chId = Entitlements.num(r.get("chapter_id"));
            byChapter.computeIfAbsent(chId, k -> new ArrayList<>());
            titles.putIfAbsent(chId, (String) r.get("chapter_title"));
            if (r.get("id") == null) continue;          // 空章：LEFT JOIN 出来的那一行

            long lessonId = Entitlements.num(r.get("id"));
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("lessonId", lessonId);
            l.put("title", r.get("title"));
            l.put("sortNo", r.get("sort_no"));
            l.put("durationSec", r.get("duration_sec"));
            l.put("isPreview", asBool(r.get("is_preview")));
            int maxPos = r.get("max_position_sec") == null ? 0 : Entitlements.i(r.get("max_position_sec"));
            if (userId != null) {
                int dur = Entitlements.i(r.get("duration_sec"));
                int watched = r.get("watched_sec") == null ? 0 : Entitlements.i(r.get("watched_sec"));
                l.put("maxPositionSec", maxPos);
                l.put("watchedSec", watched);
                l.put("progressPct", ProgressService.progressPct(watched, dur));
                l.put("completed", r.get("completed_at") != null);
            }
            l.put("sections", viewSections(sections.get(lessonId), userId == null ? -1 : maxPos));
            byChapter.get(chId).add(l);
        }

        for (var e : byChapter.entrySet()) {
            Map<String, Object> ch = new LinkedHashMap<>();
            ch.put("chapterId", e.getKey());
            ch.put("title", titles.get(e.getKey()));
            ch.put("lessons", e.getValue());
            chapters.add(ch);
        }
        return chapters;
    }

    /**
     * 分节的出网形状。
     *
     * <h3>为什么标的是「播到过」而不是「学完了」</h3>
     * 手里只有 {@code max_position_sec} 这一个标量 —— 它是<b>最远播放位置</b>，
     * 不是「这一段真的看过」。一个把进度条拖到片尾的人，按它算会让整讲每一节都变成"已完成"。
     *
     * <p>所以这里只出两个事实：{@code passed}（播放位置越过了这一节）与
     * {@code current}（上次停在这一节里）。<b>不出 completed，也不出百分比</b> ——
     * 那两个会被读成「学过了」，而这份数据撑不起那句话。
     * 讲次一级的 completed 仍然由 {@code completed_at} 给，那一条是有心跳记账兜底的。
     *
     * @param maxPos 传 -1 表示公开视图：一个字的进度都不带
     */
    private static List<Map<String, Object>> viewSections(List<Map<String, Object>> rows, int maxPos) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (rows == null) return out;
        for (var r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sortNo", r.get("sort_no"));
            m.put("title", r.get("title"));
            m.put("summary", r.get("summary"));
            m.put("kind", r.get("kind"));
            int start = Entitlements.i(r.get("start_sec")), end = Entitlements.i(r.get("end_sec"));
            m.put("startSec", start);
            m.put("endSec", end);
            if (maxPos >= 0) {
                m.put("passed", maxPos >= end);
                // ★ maxPos > 0 这一半不能省。一个从没打开过这一讲的人 maxPos 是 0，
                // 而 0 落在第一节的区间里 —— 于是「上次看到这」会挂在每一讲的第 1 节上，
                // 前端据此自动展开目录，新学员一进来就是四章一百多行全铺开。
                // 是本地把目录真跑了一遍才看见的：单看这三行代码它是"对"的。
                m.put("current", maxPos > 0 && maxPos >= start && maxPos < end);
            }
            out.add(m);
        }
        return out;
    }

    private static String ts(Object v) {
        return v == null ? null : ((LocalDateTime) v).format(TS);
    }

    /** MySQL 的布尔表达式与 TINYINT(1) 回来的可能是 Boolean 也可能是 Number，两种都得认。 */
    static boolean asBool(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        return ((Number) v).intValue() != 0;
    }
}
