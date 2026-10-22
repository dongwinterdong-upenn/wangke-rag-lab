package tech.dragonai.wangke.admin;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import tech.dragonai.wangke.auth.AuthInterceptor;
import tech.dragonai.wangke.learn.LessonKeyService;
import tech.dragonai.wangke.web.ApiException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 课程 / 章 / 讲的维护，以及讲次上架。
 *
 * <h3>为什么没有删除接口</h3>
 * 课程和讲次上挂着已售出的权益、学习进度、水印位区间、考卷抽题快照。删掉一条 lesson，
 * 表面上只是列表少一行，实际上是：已发出的码字里那一段位区间永远不会被投递（抗合谋的账白付了）、
 * 学员的断点续播指向一条不存在的记录、已出的卷子里那道题的出处消失。
 * 所以对外只提供「下架」（status=OFF），不提供删除。要真删得走一次带审计的数据订正。
 *
 * <h3>为什么新建讲次不接受 status 参数</h3>
 * {@code trg_lesson_publish_gate_ins} 明确拦了「直接以 ON 插入」这条路 —— 它是最容易绕过闸门的写法。
 * 应用层这边干脆不给这个参数：新建一律 DRAFT，上架必须走 {@code /publish}，
 * 那条路上有自检、有审计、有闸门的人话翻译。少一个入口就少一处要维护的等价逻辑。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminCourseController {

    private final JdbcTemplate jdbc;
    private final AdminAudit audit;
    private final LessonPublishCheck check;
    private final LessonKeyService keys;

    public AdminCourseController(JdbcTemplate jdbc, AdminAudit audit, LessonPublishCheck check,
                                 LessonKeyService keys) {
        this.jdbc = jdbc; this.audit = audit; this.check = check; this.keys = keys;
    }

    public record CourseReq(String title, String subtitle, String coverUrl,
                            Integer activateMonths, Integer accessMonths) {}
    public record StatusReq(String status) {}
    /**
     * @param hlsKey             本章那一场录屏的片库目录。章 = 片库，章下各讲共用这批分片
     * @param storeSegmentCount  片库目录里的分片总数（剪掉的片也算）。水印槽位容量按它算
     */
    public record ChapterReq(String title, Integer sortNo, String hlsKey,
                             Integer storeSegmentCount) {}

    /**
     * @param hlsKey  <b>已不再接受</b>：讲次的片库目录必须与所在章一致，一律取自章。
     *                留着这个字段只为老调用方不炸；给了与章不同的值会被拒。
     * @param segFrom 本讲在片库里占的片号区间起点（含）
     * @param segTo   区间终点（不含）。与 segFrom 一起给时，segment_count 由区间算出，
     *                请求里的 segmentCount 会被忽略 —— 两个来源迟早对不上，而对不上时
     *                取片限速的阈值是错的、闸门看起来却在工作
     */
    public record LessonReq(Long chapterId, String title, Integer sortNo, Integer durationSec,
                            String hlsKey, Integer segmentCount, Integer wmSlotStride,
                            Integer wmSlotCount, Integer wmBitOffset, Integer isPreview,
                            Integer segFrom, Integer segTo) {}

    // ---------------------------------------------------------------- 课程

    @GetMapping("/courses")
    Map<String, Object> courses(@RequestParam(required = false) String status,
                                HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        String st = AdminQuery.enumOf(status, "status", "DRAFT", "ON", "OFF");
        var rows = st == null
                ? jdbc.queryForList("SELECT * FROM course ORDER BY id")
                : jdbc.queryForList("SELECT * FROM course WHERE status=? ORDER BY id", st);
        List<Map<String, Object>> out = new ArrayList<>();
        for (var r : rows) out.add(courseView(r));
        return AdminJson.ok("items", out);
    }

    @GetMapping("/courses/{id}")
    Map<String, Object> course(@PathVariable long id, HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        var rows = jdbc.queryForList("SELECT * FROM course WHERE id=?", id);
        if (rows.isEmpty()) throw ApiException.notFound("课程不存在");
        Map<String, Object> v = courseView(rows.get(0));
        v.put("lessonsByStatus", jdbc.queryForList(
                "SELECT status, COUNT(*) AS n FROM lesson WHERE course_id=? GROUP BY status", id));
        return AdminJson.ok("course", v);
    }

    @PostMapping("/courses")
    Map<String, Object> createCourse(@RequestBody CourseReq r, HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        if (AdminQuery.blank(r == null ? null : r.title()))
            throw ApiException.badRequest("课程标题不能为空");
        int activate = r.activateMonths() == null ? 12 : r.activateMonths();
        int access = r.accessMonths() == null ? 24 : r.accessMonths();
        if (activate <= 0 || access <= 0)
            throw ApiException.badRequest("可激活月数与可观看月数都必须大于 0");

        long id;
        try {
            id = AdminQuery.insert(jdbc,
                    "INSERT INTO course (title,subtitle,cover_url,activate_months,access_months,status)"
                  + " VALUES (?,?,?,?,?,'DRAFT')",
                    r.title().trim(), r.subtitle(), r.coverUrl(), activate, access);
        } catch (DataAccessException e) {
            throw DbSignal.explain(e, null);
        }
        var after = loadCourse(id);
        audit.write(req, "COURSE_CREATE", "course", id, null, after);
        return AdminJson.ok("courseId", id, "status", "DRAFT",
                "message", "已创建，状态 DRAFT。录完章与讲、逐讲上架之后再把课程置 ON");
    }

    @PatchMapping("/courses/{id}")
    Map<String, Object> editCourse(@PathVariable long id, @RequestBody CourseReq r,
                                   HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        var before = loadCourse(id);
        if (before.isEmpty()) throw ApiException.notFound("课程不存在");

        var set = new ArrayList<String>();
        var args = new ArrayList<>();
        if (!AdminQuery.blank(r.title())) { set.add("title=?"); args.add(r.title().trim()); }
        if (r.subtitle() != null) { set.add("subtitle=?"); args.add(r.subtitle()); }
        if (r.coverUrl() != null) { set.add("cover_url=?"); args.add(r.coverUrl()); }
        if (r.activateMonths() != null) { set.add("activate_months=?"); args.add(r.activateMonths()); }
        if (r.accessMonths() != null) { set.add("access_months=?"); args.add(r.accessMonths()); }
        if (set.isEmpty()) throw ApiException.badRequest("没有要修改的字段");

        args.add(id);
        try {
            jdbc.update("UPDATE course SET " + String.join(",", set) + " WHERE id=?", args.toArray());
        } catch (DataAccessException e) {
            throw DbSignal.explain(e, null);
        }
        var after = loadCourse(id);
        audit.write(req, "COURSE_EDIT", "course", id, before, after);

        // 期限参数改了要说清影响面：这两个数只在「发码时」和「激活时」各读一次，
        // 改它不会追溯已发出的码与已开通的权益。不说的话运营会以为改了就全库生效
        boolean months = r.activateMonths() != null || r.accessMonths() != null;
        return AdminJson.ok("changed", true, "message", months
                ? "已保存。注意：可激活/可观看月数只在发码与激活那一刻各读一次，"
                + "已发出的码与已开通的权益都不会跟着变"
                : "已保存");
    }

    @PostMapping("/courses/{id}/status")
    Map<String, Object> courseStatus(@PathVariable long id, @RequestBody StatusReq r,
                                     HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        String to = AdminQuery.enumOf(r == null ? null : r.status(), "status", "DRAFT", "ON", "OFF");
        if (to == null) throw ApiException.badRequest("status 必须是 DRAFT / ON / OFF");

        var before = loadCourse(id);
        if (before.isEmpty()) throw ApiException.notFound("课程不存在");

        if ("ON".equals(to)) {
            // 应用层的一条软闸（库里没有对应约束，所以这里说清它只是软的）：
            // 一门课上架却一讲都没上架，学员付了钱进来看到的是空目录 —— 而这条链上
            // 没有任何一处会报错，只有客服会收到投诉
            Integer onLessons = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM lesson WHERE course_id=? AND status='ON'", Integer.class, id);
            if (onLessons == null || onLessons == 0)
                throw ApiException.conflict("这门课一讲都还没上架，上架课程后学员进来看到的是空目录。"
                        + "请先逐讲走 /publish 上架");
        }

        int rows = jdbc.update("UPDATE course SET status=? WHERE id=? AND status<>?", to, id, to);
        if (rows == 0)
            return AdminJson.ok("changed", false, "status", to, "message", "课程已经是这个状态");
        var after = loadCourse(id);
        audit.write(req, "COURSE_STATUS", "course", id, before, after);
        return AdminJson.ok("changed", true, "status", to);
    }

    // ---------------------------------------------------------------- 章

    @GetMapping("/courses/{id}/chapters")
    Map<String, Object> chapters(@PathVariable long id, HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        return AdminJson.ok("items", jdbc.queryForList(
                "SELECT id, course_id, title, sort_no FROM chapter WHERE course_id=? ORDER BY sort_no, id", id));
    }

    @PostMapping("/courses/{id}/chapters")
    Map<String, Object> createChapter(@PathVariable long id, @RequestBody ChapterReq r,
                                      HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        requireCourse(id);
        if (AdminQuery.blank(r == null ? null : r.title()))
            throw ApiException.badRequest("章标题不能为空");
        int sortNo = r.sortNo() == null ? nextSort("chapter", "course_id", id) : r.sortNo();
        int store = r.storeSegmentCount() == null ? 0 : r.storeSegmentCount();
        if (store < 0) throw ApiException.badRequest("片库分片总数不能为负");
        long chapterId = AdminQuery.insert(jdbc,
                "INSERT INTO chapter (course_id,title,hls_key,store_segment_count,sort_no)"
              + " VALUES (?,?,?,?,?)",
                id, r.title().trim(), AdminQuery.blank(r.hlsKey()) ? null : r.hlsKey().trim(),
                store, sortNo);
        audit.write(req, "CHAPTER_CREATE", "chapter", chapterId, null,
                AdminJson.map("courseId", id, "title", r.title().trim(), "sortNo", sortNo,
                        "hlsKey", r.hlsKey(), "storeSegmentCount", store));
        return AdminJson.ok("chapterId", chapterId, "sortNo", sortNo);
    }

    @PatchMapping("/chapters/{id}")
    Map<String, Object> editChapter(@PathVariable long id, @RequestBody ChapterReq r,
                                    HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        var rows = jdbc.queryForList("SELECT id, course_id, title, sort_no, hls_key,"
                + " store_segment_count FROM chapter WHERE id=?", id);
        if (rows.isEmpty()) throw ApiException.notFound("章不存在");
        var before = AdminJson.map("title", rows.get(0).get("title"), "sortNo", rows.get(0).get("sort_no"),
                "hlsKey", rows.get(0).get("hls_key"),
                "storeSegmentCount", rows.get(0).get("store_segment_count"));

        var set = new ArrayList<String>();
        var args = new ArrayList<>();
        if (!AdminQuery.blank(r.title())) { set.add("title=?"); args.add(r.title().trim()); }
        if (r.sortNo() != null) { set.add("sort_no=?"); args.add(r.sortNo()); }
        if (!AdminQuery.blank(r.hlsKey())) { set.add("hls_key=?"); args.add(r.hlsKey().trim()); }
        if (r.storeSegmentCount() != null) {
            // 片库分片总数一改，槽位容量就变，全课程的码位基点跟着挪 ——
            // 已发出的码字当场作废。所以这里只放行"还没发过码字"的情形，
            // 真要改就得重开码本（WatermarkService.assignBitOffsets 上还有一道同样的判据）。
            Integer issued = jdbc.queryForObject("SELECT COUNT(*) FROM wm_codeword WHERE course_id=?",
                    Integer.class, AdminQuery.longOf(rows.get(0).get("course_id"), 0));
            if (issued != null && issued > 0
                    && !r.storeSegmentCount().equals(AdminQuery.intOf(rows.get(0).get("store_segment_count"), -1)))
                throw ApiException.conflict("本课程已经发出 " + issued + " 份水印码字，"
                        + "片库分片总数不可再变更：容量一变，各章的码位基点整体挪位，"
                        + "已发出的副本在取证时会指认到别人");
            set.add("store_segment_count=?"); args.add(r.storeSegmentCount());
        }
        if (set.isEmpty()) throw ApiException.badRequest("没有要修改的字段");
        args.add(id);
        jdbc.update("UPDATE chapter SET " + String.join(",", set) + " WHERE id=?", args.toArray());
        audit.write(req, "CHAPTER_EDIT", "chapter", id, before,
                AdminJson.map("title", r.title(), "sortNo", r.sortNo(),
                        "hlsKey", r.hlsKey(), "storeSegmentCount", r.storeSegmentCount()));
        return AdminJson.ok("changed", true);
    }

    public record RebindKeyReq(Long fromLessonId) {}

    /**
     * 把一讲的内容密钥<b>改绑</b>到它自己（按源讲次的 AAD 解封、按本讲的 AAD 重封）。
     *
     * <p>只在<b>重切课程</b>之后需要：新讲次共用旧讲次那一批分片，所以必须是同一把密钥，
     * 而密文是按讲次绑 AAD 的，复制过来解不开。见 {@link LessonKeyService#rebind}。
     *
     * <p>这不削弱 AAD 想防的那件事。AAD 防的是<b>只拿到库写权限</b>的人把 A 讲的密文
     * 搬到 B 讲 —— 那种人没有 KEK，搬完解不开。这条路要 PLATFORM_ADMIN，
     * 而能当超管的人本来就能重置任何人的密码、导出任何数据。
     *
     * <p>明文密钥不出现在响应里，也不写审计 —— 审计只记「从哪一讲改绑过来的」。
     */
    @PostMapping("/lessons/{id}/content-key/rebind")
    Map<String, Object> rebindContentKey(@PathVariable long id, @RequestBody RebindKeyReq r,
                                         HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        if (r == null || r.fromLessonId() == null)
            throw ApiException.badRequest("请指定 fromLessonId（这行密文原本绑在哪一讲上）");
        boolean changed = keys.rebind(id, r.fromLessonId());
        if (changed)
            audit.write(req, "LESSON_KEY_REBIND", "lesson", id, null,
                    AdminJson.map("fromLessonId", r.fromLessonId()));
        return AdminJson.ok("changed", changed,
                "message", changed ? "已按本讲重新封装内容密钥"
                                   : "这行密文本来就绑在本讲上，没有改动");
    }

    /**
     * 请求里的片号区间。两个都没给返回 null（表示这次不动区间）。
     *
     * <p>后台只支持"一讲一段连续区间"。生产上有的讲中间跳掉了课间与等 AI 的静音，
     * 那种多段区间由内容入库流水线（ops/deploy_lessons.sh）灌，不从这里改 ——
     * 后台能改就等于同一件事有了第二个真相来源，而两边迟早对不上。
     */
    private static int[] blockRange(LessonReq r, int store) {
        if (r.segFrom() == null && r.segTo() == null) return null;
        if (r.segFrom() == null || r.segTo() == null)
            throw ApiException.badRequest("segFrom 与 segTo 要一起给");
        int a = r.segFrom(), b = r.segTo();
        if (a < 0 || b <= a) throw ApiException.badRequest("片号区间不合法：[" + a + "," + b + ")");
        if (store > 0 && b > store)
            throw ApiException.badRequest("片号区间超出片库（本章只有 " + store + " 片）："
                    + "播放列表会引用不存在的对象，而 404 在播放器里表现为画面不动、不报错");
        return new int[]{a, b};
    }

    /** 用一段区间整体替换某讲的分片区间。 */
    private void writeSingleBlock(long lessonId, int[] range) {
        jdbc.update("DELETE FROM lesson_segment_block WHERE lesson_id=?", lessonId);
        jdbc.update("INSERT INTO lesson_segment_block (lesson_id,sort_no,seg_from,seg_to)"
                  + " VALUES (?,1,?,?)", lessonId, range[0], range[1]);
    }

    // ---------------------------------------------------------------- 讲

    @GetMapping("/courses/{id}/lessons")
    Map<String, Object> lessons(@PathVariable long id, HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        // 分节数走标量子查询，不 JOIN lesson_section：JOIN 会把一讲的行乘成 N 份，
        // 而这张表下面接着的是「共几讲 / 已上架几讲」两个计数，会跟着一起变成几十。
        var rows = jdbc.queryForList(
                "SELECT l.*, c.title AS chapter_title, "
              + "       (SELECT COUNT(*) FROM lesson_section s WHERE s.lesson_id=l.id) AS section_count, "
              + "       (SELECT COUNT(*) FROM lesson_segment_block b WHERE b.lesson_id=l.id) AS block_count, "
              + "       (SELECT MIN(b.seg_from) FROM lesson_segment_block b WHERE b.lesson_id=l.id) AS seg_from, "
              + "       (SELECT MAX(b.seg_to) FROM lesson_segment_block b WHERE b.lesson_id=l.id) AS seg_to "
              + "  FROM lesson l"
              + " LEFT JOIN chapter c ON c.id=l.chapter_id"
              + " WHERE l.course_id=? ORDER BY l.sort_no, l.id", id);
        List<Map<String, Object>> out = new ArrayList<>();
        for (var r : rows) {
            var v = lessonView(r);
            // 目录（分节）是内容产物，权威在 db/content/ 的 JSON 里，由 ops/deploy_sections.sh 灌。
            // 这里只报个数，刻意【不给编辑入口】—— 后台能改就等于有了第二个真相来源，
            // 而它和那份 JSON 迟早会对不上，且对不上的时候两边看起来都对。
            v.put("sectionCount", r.get("section_count"));
            // 区间是【几段】也要报出来。只报首尾的话，一个中间跳掉课间的多段讲次
            // 看起来跟一段连续的一模一样，而在后台按"一段"存回去会把中间那些段整段抹掉。
            v.put("blockCount", r.get("block_count"));
            v.put("segFrom", r.get("seg_from"));
            v.put("segTo", r.get("seg_to"));
            out.add(v);
        }
        return AdminJson.ok("items", out);
    }

    @PostMapping("/courses/{id}/lessons")
    Map<String, Object> createLesson(@PathVariable long id, @RequestBody LessonReq r,
                                     HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        requireCourse(id);
        if (r == null || r.chapterId() == null) throw ApiException.badRequest("请指定所属章");
        if (AdminQuery.blank(r.title())) throw ApiException.badRequest("讲次标题不能为空");
        if (r.durationSec() == null || r.durationSec() <= 0)
            throw ApiException.badRequest("时长必须大于 0 —— 进度反作弊的三条物理下限全靠它，"
                    + "时长为 0 时任何观看进度都会被判为超长");

        // 章必须属于这门课。写成「先查出章再比对 course_id」也能防住，
        // 但条件放进 WHERE 之后，「章不存在」与「章不是这门课的」对外是同一种结果，
        // 不会变成一个可以枚举别的课程有几章的探针
        var chapters = jdbc.queryForList(
                "SELECT hls_key, store_segment_count, wm_bit_base FROM chapter"
              + " WHERE id=? AND course_id=?", r.chapterId(), id);
        if (chapters.isEmpty())
            throw ApiException.badRequest("所属章不存在或不属于这门课");
        var ch = chapters.get(0);
        // 片库目录一律取自章 —— 讲次上那一列是冗余。两边各填各的话，这一讲会去另一个
        // 目录取到同名但内容不同的片，播出来是另一节课，而这不会报错。
        String chKey = (String) ch.get("hls_key");
        if (AdminQuery.blank(chKey))
            throw ApiException.badRequest("所属章还没登记片库目录：先给章填上 hlsKey 与 storeSegmentCount"
                    + "（片库目录里的分片总数），讲次拿的是这个目录里的一段片号区间");
        if (!AdminQuery.blank(r.hlsKey()) && !chKey.equals(r.hlsKey().trim()))
            throw ApiException.badRequest("讲次的片库目录必须与所在章一致（章是 " + chKey + "）");

        int sortNo = r.sortNo() == null ? nextSort("lesson", "course_id", id) : r.sortNo();
        int stride = r.wmSlotStride() == null ? 6 : r.wmSlotStride();
        int store = AdminQuery.intOf(ch.get("store_segment_count"), 0);
        int base = AdminQuery.intOf(ch.get("wm_bit_base"), 0);
        int[] range = blockRange(r, store);
        int segs = range == null ? (r.segmentCount() == null ? 0 : r.segmentCount())
                                 : range[1] - range[0];
        int preview = r.isPreview() != null && r.isPreview() == 1 ? 1 : 0;

        long lessonId = AdminQuery.insert(jdbc,
                // status 恒为 DRAFT：不给「直接以 ON 插入」留入口，那条路会绕过上架自检
                "INSERT INTO lesson (chapter_id,course_id,title,sort_no,duration_sec,hls_key,"
              + "segment_count,wm_slot_stride,wm_slot_count,wm_bit_offset,wm_bit_base,is_preview,status)"
              + " VALUES (?,?,?,?,?,?,?,?,0,0,?,?,'DRAFT')",
                r.chapterId(), id, r.title().trim(), sortNo, r.durationSec(), chKey,
                segs, stride, base, preview);
        if (range != null) writeSingleBlock(lessonId, range);

        audit.write(req, "LESSON_CREATE", "lesson", lessonId, null,
                AdminJson.map("courseId", id, "chapterId", r.chapterId(), "title", r.title().trim(),
                        "sortNo", sortNo, "durationSec", r.durationSec(), "segmentCount", segs,
                        "wmSlotStride", stride, "isPreview", preview, "status", "DRAFT",
                        "segFrom", range == null ? null : range[0],
                        "segTo", range == null ? null : range[1]));
        refreshCourseTotals(id);
        return AdminJson.ok("lessonId", lessonId, "status", "DRAFT",
                "message", "已创建。位区间（wm_bit_offset / wm_slot_count）由全课程统一分配，"
                        + "不在这里单独填；分配完再走 /publish 上架");
    }

    @PatchMapping("/lessons/{id}")
    Map<String, Object> editLesson(@PathVariable long id, @RequestBody LessonReq r,
                                   HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        var rows = jdbc.queryForList("SELECT * FROM lesson WHERE id=?", id);
        if (rows.isEmpty()) throw ApiException.notFound("讲次不存在");
        var l = rows.get(0);
        long courseId = AdminQuery.longOf(l.get("course_id"), 0);
        var before = lessonSnapshot(l);

        boolean touchesLayout = r.segmentCount() != null || r.wmSlotStride() != null
                || r.wmSlotCount() != null || r.wmBitOffset() != null
                || r.segFrom() != null || r.segTo() != null;
        if (touchesLayout) {
            // 已经发出码字之后再动布局，之前所有副本的取证会整体错位 —— 而错位的打分
            // 不报错，它会安静地算出一组错的分数然后指认到无辜的人身上。
            // WatermarkService.assignBitOffsets 守着同一条规则；后台这个入口如果不守，
            // 它就是那条规则的一个绕过口
            Integer issued = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM wm_codeword WHERE course_id=?", Integer.class, courseId);
            if (issued != null && issued > 0)
                throw ApiException.conflict("本课程已经发出 " + issued + " 份水印码字，"
                        + "切片数与位区间不可再变更：改动会让已发出的所有副本在取证时整体错位，"
                        + "而错位的打分不报错，只会指认到别人");
        }
        if (r.isPreview() != null && "ON".equals(l.get("status"))
                && r.isPreview() != AdminQuery.intOf(l.get("is_preview"), 0))
            // 已上架的正片被改成试听，就等于把一门在售课程的水印保护当场关掉，而且不过任何闸门
            throw ApiException.conflict("已上架的讲次不能改试听标记。请先下架（/unpublish）再改");

        var set = new ArrayList<String>();
        var args = new ArrayList<>();
        if (!AdminQuery.blank(r.title())) { set.add("title=?"); args.add(r.title().trim()); }
        if (r.sortNo() != null) { set.add("sort_no=?"); args.add(r.sortNo()); }
        if (r.durationSec() != null) {
            if (r.durationSec() <= 0) throw ApiException.badRequest("时长必须大于 0");
            set.add("duration_sec=?"); args.add(r.durationSec());
        }
        // hls_key 不再从这里改：它必须与所在章一致，改章的片库目录才是正路
        int[] range = null;
        if (r.segFrom() != null || r.segTo() != null) {
            int store = AdminQuery.intOf(jdbc.queryForObject(
                    "SELECT store_segment_count FROM chapter WHERE id=?", Integer.class,
                    AdminQuery.longOf(l.get("chapter_id"), 0)), 0);
            range = blockRange(r, store);
            if (range == null) throw ApiException.badRequest("segFrom 与 segTo 要一起给");
            set.add("segment_count=?"); args.add(range[1] - range[0]);
        } else if (r.segmentCount() != null) {
            set.add("segment_count=?"); args.add(r.segmentCount());
        }
        if (r.wmSlotStride() != null) { set.add("wm_slot_stride=?"); args.add(r.wmSlotStride()); }
        if (r.wmSlotCount() != null) { set.add("wm_slot_count=?"); args.add(r.wmSlotCount()); }
        if (r.wmBitOffset() != null) { set.add("wm_bit_offset=?"); args.add(r.wmBitOffset()); }
        if (r.isPreview() != null) { set.add("is_preview=?"); args.add(r.isPreview() == 1 ? 1 : 0); }
        if (r.chapterId() != null) {
            Integer okChapter = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM chapter WHERE id=? AND course_id=?",
                    Integer.class, r.chapterId(), courseId);
            if (okChapter == null || okChapter == 0)
                throw ApiException.badRequest("所属章不存在或不属于这门课");
            set.add("chapter_id=?"); args.add(r.chapterId());
        }
        if (set.isEmpty()) throw ApiException.badRequest("没有要修改的字段");

        args.add(id);
        try {
            if (range != null) writeSingleBlock(id, range);
            jdbc.update("UPDATE lesson SET " + String.join(",", set) + " WHERE id=?", args.toArray());
        } catch (DataAccessException e) {
            // 改到已上架的讲次时，水印闸门会在 UPDATE 上再判一次
            throw DbSignal.explain(e, "可先下架（/unpublish）改完再走 /publish 上架");
        }
        var after = lessonSnapshot(jdbc.queryForList("SELECT * FROM lesson WHERE id=?", id).get(0));
        audit.write(req, "LESSON_EDIT", "lesson", id, before, after);
        refreshCourseTotals(courseId);
        return AdminJson.ok("changed", true);
    }

    // ---------------------------------------------------------------- 上架

    /** 上架前自检：一次列全所有拦路项，而不是被闸门一条一条挡回来。 */
    @GetMapping("/lessons/{id}/publish-check")
    Map<String, Object> publishCheck(@PathVariable long id, HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        var res = check.of(id);
        return AdminJson.ok("lessonId", res.lessonId(), "title", res.title(),
                "publishable", res.publishable(), "previewExempt", res.previewExempt(),
                "items", res.items(),
                "note", "自检通过不代表上架一定成功：自检与上架之间别人可能改了同课程另一讲的位区间。"
                      + "最终裁决在库里的 trg_lesson_publish_gate，它和上架是在同一把锁下求值的");
    }

    @PostMapping("/lessons/{id}/publish")
    Map<String, Object> publish(@PathVariable long id, HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        var rows = jdbc.queryForList("SELECT id, course_id, status FROM lesson WHERE id=?", id);
        if (rows.isEmpty()) throw ApiException.notFound("讲次不存在");
        String from = String.valueOf(rows.get(0).get("status"));
        long courseId = AdminQuery.longOf(rows.get(0).get("course_id"), 0);
        if ("ON".equals(from))
            return AdminJson.ok("changed", false, "status", "ON", "message", "这一讲已经上架了");

        // 内容密钥表是后续迁移加入的，早期的库层水印触发器看不到它。
        // 上架前先跑完整自检，避免出现“目录已上架，学员到取密钥时才知道播不了”。
        var preflight = check.of(id);
        if (!preflight.publishable())
            throw ApiException.conflict("上架前自检未通过；" + preflight.failureHint());

        int changed;
        try {
            // 闸门挂在 BEFORE UPDATE 上，所以拒绝会从这条语句抛上来
            changed = jdbc.update("UPDATE lesson SET status='ON' WHERE id=? AND status<>'ON'", id);
        } catch (DataAccessException e) {
            // 触发器只说「不行，因为……」。运营还需要知道「那我该改什么」，
            // 所以把自检结果拼在后面 —— 否则他能做的只有找研发
            String hint = check.of(id).failureHint();
            throw DbSignal.explain(e, hint);
        }
        if (changed == 0)
            return AdminJson.ok("changed", false, "status", "ON", "message", "这一讲已经上架了");

        audit.write(req, "LESSON_PUBLISH", "lesson", id,
                AdminJson.map("status", from), AdminJson.map("status", "ON"));
        refreshCourseTotals(courseId);
        return AdminJson.ok("changed", true, "status", "ON");
    }

    @PostMapping("/lessons/{id}/unpublish")
    Map<String, Object> unpublish(@PathVariable long id, HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        var rows = jdbc.queryForList("SELECT id, course_id, status FROM lesson WHERE id=?", id);
        if (rows.isEmpty()) throw ApiException.notFound("讲次不存在");
        long courseId = AdminQuery.longOf(rows.get(0).get("course_id"), 0);
        String from = String.valueOf(rows.get(0).get("status"));

        int changed = jdbc.update("UPDATE lesson SET status='OFF' WHERE id=? AND status<>'OFF'", id);
        if (changed == 0)
            return AdminJson.ok("changed", false, "status", "OFF", "message", "这一讲已经是下架状态");

        audit.write(req, "LESSON_UNPUBLISH", "lesson", id,
                AdminJson.map("status", from), AdminJson.map("status", "OFF"));
        refreshCourseTotals(courseId);
        return AdminJson.ok("changed", true, "status", "OFF",
                // 下架不退钱、也不收回已发出的码字，这件事必须说出来
                "message", "已下架。已购学员将看不到这一讲；已发出的水印码字与位区间不受影响，"
                        + "位区间仍被本讲占用（这是对的：改它会让已投递的副本取证错位）");
    }

    // ---------------------------------------------------------------- 内部

    private void requireCourse(long id) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM course WHERE id=?", Integer.class, id);
        if (n == null || n == 0) throw ApiException.notFound("课程不存在");
    }

    /**
     * 课程卡片上的「共 N 讲 / 时长」按<b>已上架</b>的讲统计。
     *
     * <p>把 DRAFT 的讲也算进去，等于对外承诺了学员看不到的内容 —— 那是退款纠纷里
     * 最难辩的一种：宣传页上白纸黑字写着 40 讲，进去只有 12 讲。
     */
    private void refreshCourseTotals(long courseId) {
        jdbc.update("UPDATE course SET total_lessons=(SELECT COUNT(*) FROM lesson"
                  + " WHERE course_id=? AND status='ON'),"
                  + " total_seconds=(SELECT COALESCE(SUM(duration_sec),0) FROM lesson"
                  + " WHERE course_id=? AND status='ON') WHERE id=?", courseId, courseId, courseId);
    }

    private int nextSort(String table, String fkColumn, long fkValue) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(sort_no),0) FROM " + table + " WHERE " + fkColumn + "=?",
                Integer.class, fkValue);
        return (max == null ? 0 : max) + 10;      // 留 10 的间隔，插队不用整体重排
    }

    private Map<String, Object> courseView(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", AdminQuery.id(r.get("id")));
        m.put("title", r.get("title"));
        m.put("subtitle", r.get("subtitle"));
        m.put("coverUrl", r.get("cover_url"));
        m.put("totalLessons", r.get("total_lessons"));
        m.put("totalSeconds", r.get("total_seconds"));
        m.put("status", r.get("status"));
        m.put("activateMonths", r.get("activate_months"));
        m.put("accessMonths", r.get("access_months"));
        m.put("createdAt", r.get("created_at"));
        return m;
    }

    private Map<String, Object> lessonView(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", AdminQuery.id(r.get("id")));
        m.put("courseId", AdminQuery.id(r.get("course_id")));
        m.put("chapterId", AdminQuery.id(r.get("chapter_id")));
        m.put("chapterTitle", r.get("chapter_title"));
        m.put("title", r.get("title"));
        m.put("sortNo", r.get("sort_no"));
        m.put("durationSec", r.get("duration_sec"));
        m.put("segmentCount", r.get("segment_count"));
        m.put("wmSlotStride", r.get("wm_slot_stride"));
        m.put("wmSlotCount", r.get("wm_slot_count"));
        m.put("wmBitOffset", r.get("wm_bit_offset"));
        m.put("isPreview", AdminQuery.flag(r.get("is_preview")));
        m.put("status", r.get("status"));
        // hls_key 是私有桶的对象前缀。它本身不是直链，但它是拼直链的那一半，
        // 没有理由让它出现在列表里被截图带走
        m.put("hlsKeyMasked", mask((String) r.get("hls_key")));
        return m;
    }

    private static String mask(String s) {
        if (s == null) return null;
        return s.length() <= 6 ? "****" : s.substring(0, 4) + "****" + s.substring(s.length() - 2);
    }

    private Map<String, Object> loadCourse(long id) {
        var rows = jdbc.queryForList(
                "SELECT title, subtitle, cover_url, status, activate_months, access_months"
              + " FROM course WHERE id=?", id);
        if (rows.isEmpty()) return AdminJson.map();
        var r = rows.get(0);
        return AdminJson.map("title", r.get("title"), "subtitle", r.get("subtitle"),
                "coverUrl", r.get("cover_url"), "status", r.get("status"),
                "activateMonths", r.get("activate_months"), "accessMonths", r.get("access_months"));
    }

    private Map<String, Object> lessonSnapshot(Map<String, Object> r) {
        return AdminJson.map("title", r.get("title"), "sortNo", r.get("sort_no"),
                "chapterId", AdminQuery.id(r.get("chapter_id")),
                "durationSec", r.get("duration_sec"), "segmentCount", r.get("segment_count"),
                "wmSlotStride", r.get("wm_slot_stride"), "wmSlotCount", r.get("wm_slot_count"),
                "wmBitOffset", r.get("wm_bit_offset"), "isPreview", AdminQuery.flag(r.get("is_preview")),
                "status", r.get("status"), "hlsKeyMasked", mask((String) r.get("hls_key")));
    }
}
