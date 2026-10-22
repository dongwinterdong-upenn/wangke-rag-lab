package tech.dragonai.wangke.exam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionTemplate;
import tech.dragonai.wangke.ItDb;
import tech.dragonai.wangke.auth.AuthInterceptor;
import tech.dragonai.wangke.web.ApiException;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测评与结业证明，跑在真实 MySQL 上。
 *
 * <h3>为什么调的是控制器方法而不是 Service</h3>
 * "题目下发不含答案"这条判据必须守在<b>真正出网的那个对象</b>上。
 * 只测 Service 的返回值，控制器往响应里多塞一个字段（比如为了做"答题卡回显"
 * 顺手把 question 整行带上）判据照样绿。所以这里用 MockHttpServletRequest
 * 直接调控制器方法，再用 Jackson 把返回的 Map 序列化成 JSON —— 与生产里
 * 那条路径同一个对象、同一套序列化。
 *
 * <h3>每个负例都钉住失败原因</h3>
 * 只断言"抛异常"的负例太容易因为别的原因红着（SQL 写错了、id 不存在、
 * 权限先拦下了），那样它通过时什么也没证明。这里一律断言 HTTP 状态 + 关键措辞，
 * 并且在能查的地方补一句"库里确实没多出一行"。
 * 库层那两条（触发器）钉的是 MySQL 的 <b>1644</b>（SIGNAL 专用错误码）——
 * 只断言"写失败"的话，撞唯一键、写错列名、字段超长都能让它绿。
 *
 * <h3>三份卷是这一轮的主角</h3>
 * 综合卷（发证、要买课、80% 进度门槛）、章节卷（不发证、无门槛）、
 * 开放卷（不挂课程、不要权益）。夹具自己造这三份，并且<b>把 seed 里带的卷全部下架</b> ——
 * 不这么做的话，这些用例的绿与红取决于 seed 当天装了几份卷，
 * 而那是另一个人在改的文件。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ExamIT {

    static final String DB = "wangke_exam_it";
    static final long COURSE = 1L;

    /** 综合卷：发结业证明、要买课、80% 进度门槛。全部题目都要被抽中，判分才是确定的。 */
    static final long P_MAIN = 501;
    static final int PAPER = 6, PASS = 5, MAX_ATTEMPTS = 2, COOLDOWN_H = 24, REQUIRE_PCT = 80;

    /** 章节练兵卷：同一门课，不发证、没有进度门槛、不冷却。 */
    static final long P_CH1 = 502;
    static final int CH_PAPER = 2, CH_PASS = 1, CH_MAX = 5;

    /** 开放卷：course_id 为空、access=OPEN —— 任何注册学员都能考。 */
    static final long P_OPEN = 503;
    static final int OPEN_PAPER = 2, OPEN_PASS = 1;

    /** 已下架的卷：既不该出现在列表里，也不该能直接打 paperId 开考。 */
    static final long P_OFF = 504;

    /** 题目解析的哨兵串。判据不能只看字段名 —— 值也不许出现在响应里。 */
    static final String EXPL_SENTINEL = "EXPL_SENTINEL_7Q";
    static final String PHONE_20 = "13200000020";

    /** 一门课都没买的注册学员：开放卷他能考，综合卷他不能。 */
    static final long U_NOCOURSE = 24;
    /** 只考章节卷的学员：用来证明章节卷换不出结业证明。 */
    static final long U_CHAPTER = 25;
    /** 只在库层用例里出现（裸 INSERT 打触发器）。 */
    static final long U_RAW = 26;
    /** 权益还没生效（valid_from 在未来）：卷看得见，但一场都不该能考。 */
    static final long U_FUTURE = 27;
    /** 权益已到期：卷要<b>留在列表里</b>，卡上写的是续期 —— 不是整张消失。 */
    static final long U_EXPIRED = 28;

    static DataSource ds;
    static JdbcTemplate jdbc;
    static ObjectMapper mapper;
    static ExamService exam;
    static CertificateService certs;
    static ExamController examApi;
    static CertificateController certApi;

    static long attempt20;
    static String cert20;
    /** U_CHAPTER 通过的那场章节卷 / 综合卷，库层用例拿它们做对照。 */
    static long chapterPassed, mainPassedOfChapterUser;

    @BeforeAll
    static void bootstrap() throws Exception {
        ds = ItDb.freshDatabase(DB);
        jdbc = new JdbcTemplate(ds);
        // findAndRegisterModules 带进 JavaTimeModule；不注册的话第一次序列化
        // LocalDateTime 就抛 InvalidDefinitionException，而那会被误读成"接口有问题"
        mapper = new ObjectMapper().findAndRegisterModules();
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        // 不带报告服务的那个构造：这套判据的库里没有 exam_report 表，
        // 接上报告只会让一整片与它无关的用例因为"表不存在"红掉。
        // 报告那条链（事实层、抢占、清扫、时间点校验）由 ExamReportIT 单独守。
        exam = new ExamService(jdbc, tx, mapper);
        certs = new CertificateService(jdbc, mapper);
        // 控制器要一个报告服务才装得起来。这里给一个接在同一个库上、
        // 但这套用例一次都不会去调的实例（模型客户端固定失败，绝不出网）。
        examApi = new ExamController(exam, new ExamReportService(jdbc, mapper,
                (sys, user) -> DeepSeekClient.Reply.fail("ExamIT 不生成报告"), Runnable::run));
        certApi = new CertificateController(certs);

        jdbc.update("INSERT INTO sys_user (id,phone,role,real_name) VALUES (20,?,'STUDENT','张三丰')", PHONE_20);
        jdbc.update("INSERT INTO sys_user (id,phone,role) VALUES (21,'13200000021','STUDENT')");
        jdbc.update("INSERT INTO sys_user (id,phone,role) VALUES (22,'13200000022','STUDENT')");
        jdbc.update("INSERT INTO sys_user (id,phone,role) VALUES (23,'13200000023','STUDENT')");
        jdbc.update("INSERT INTO sys_user (id,phone,role) VALUES (?,'13200000024','STUDENT')", U_NOCOURSE);
        jdbc.update("INSERT INTO sys_user (id,phone,role) VALUES (?,'13200000025','STUDENT')", U_CHAPTER);
        jdbc.update("INSERT INTO sys_user (id,phone,role) VALUES (?,'13200000026','STUDENT')", U_RAW);
        jdbc.update("INSERT INTO sys_user (id,phone,role) VALUES (?,'13200000027','STUDENT')", U_FUTURE);
        jdbc.update("INSERT INTO sys_user (id,phone,role) VALUES (?,'13200000028','STUDENT')", U_EXPIRED);

        // U_NOCOURSE 故意不给权益：他是"开放卷对所有注册学员开放"这条的唯一证人。
        for (long u : new long[]{20, 21, 22, 23, U_CHAPTER})
            jdbc.update("INSERT INTO enrollment (user_id,course_id,source,valid_from,valid_until,"
                      + "agreement_version,agreed_at) VALUES (?,?,'GRANT',NOW(3),"
                      + "DATE_ADD(NOW(3), INTERVAL 24 MONTH),'v2026-08-11',NOW(3))", u, COURSE);

        // 权益的两种"有但不能用"。日期一律由 SQL 算：判定那边取的是 NOW(3)，
        // 用应用侧的钟造夹具，差几毫秒就会把边界用例变成偶发红。
        jdbc.update("INSERT INTO enrollment (user_id,course_id,source,valid_from,valid_until,"
                  + "agreement_version,agreed_at) VALUES (?,?,'GRANT',"
                  + "DATE_ADD(NOW(3), INTERVAL 7 DAY), DATE_ADD(NOW(3), INTERVAL 24 MONTH),"
                  + "'v2026-08-11',NOW(3))", U_FUTURE, COURSE);
        jdbc.update("INSERT INTO enrollment (user_id,course_id,source,valid_from,valid_until,"
                  + "agreement_version,agreed_at) VALUES (?,?,'GRANT',"
                  + "DATE_SUB(NOW(3), INTERVAL 24 MONTH), DATE_SUB(NOW(3), INTERVAL 1 DAY),"
                  + "'v2026-08-11',NOW(3))", U_EXPIRED, COURSE);

        // 上架 4 讲。lesson 的上架闸门要求本课程有现行水印码本且位区间不重叠 ——
        // 这里如实满足它，而不是把讲次留在 DRAFT 绕过去：进度分母只算已上架正片，
        // 绕过去的话这套判据测的就不是生产里那条路径。
        jdbc.update("INSERT INTO wm_scheme (course_id,max_colluders,users_planned,eps1,bit_length,bias) "
                  + "VALUES (?,2,100,0.001,64,UNHEX(REPEAT('00',512)))", COURSE);
        for (int k = 1; k <= 4; k++) {
            // 章 = 一场录屏 = 一个片库目录（这里每讲各占一整个目录）。
            // 步长 10、目录 10 片 ⇒ 容量 1 个槽位，落在第 5 片上，码位 = 本章基点。
            jdbc.update("INSERT INTO chapter (id,course_id,title,hls_key,store_segment_count,"
                      + "wm_bit_base,sort_no) VALUES (?,?,?,?,10,?,?)",
                    k, COURSE, "第" + k + "章", "oss/l" + k, k - 1, k);
            jdbc.update("INSERT INTO lesson (id,chapter_id,course_id,title,sort_no,duration_sec,"
                      + "hls_key,segment_count,wm_slot_stride,wm_slot_count,wm_bit_offset,"
                      + "wm_bit_base,status) VALUES (?,?,?,?,?,600,?,10,10,1,?,?,'DRAFT')",
                    k, k, COURSE, "第" + k + "讲", k, "oss/l" + k, k - 1, k - 1);
            jdbc.update("INSERT INTO lesson_segment_block (lesson_id,sort_no,seg_from,seg_to)"
                      + " VALUES (?,1,0,10)", k);
            // 测试不走播放解密，只需满足“上架前已有封存密钥”的勾稽。
            // 密文格式与 AAD 的真实解封由 LearnIT 覆盖。
            jdbc.update("INSERT INTO lesson_content_key "
                      + "(lesson_id,course_id,key_version,key_enc,kek_id,status) "
                      + "VALUES (?,?,1,?,'exam-it','ACTIVE')", k, COURSE, new byte[44]);
        }
        jdbc.update("UPDATE lesson SET status='ON' WHERE course_id=?", COURSE);

        // 20 / 22 / 23 / U_CHAPTER 学满，21 一秒没看
        for (long u : new long[]{20, 22, 23, U_CHAPTER})
            for (int k = 1; k <= 4; k++)
                jdbc.update("INSERT INTO learn_progress (user_id,course_id,lesson_id,"
                          + "max_position_sec,watched_sec,heartbeat_count) VALUES (?,?,?,600,600,40)",
                        u, COURSE, k);

        // seed 带的卷（生产要上的那 6 份）全部下架：这套用例只对自己造的三份卷负责。
        // 不这么做的话，"这门课有几份发证的卷"取决于 seed，而 seed 是别人在改的文件 ——
        // 那条按 courseId 兼容的路径会因此时绿时红，且看不出与自己的改动有没有关系。
        jdbc.update("UPDATE exam_paper SET status='OFF'");

        paper(P_MAIN, "IT_MAIN", "结业综合测评", COURSE, "ENROLLED", 1, "ON", 1);
        paper(P_CH1, "IT_CH1", "第一章 · 章节练兵", COURSE, "ENROLLED", 0, "ON", 2);
        paper(P_OPEN, "IT_OPEN", "开放测评", null, "OPEN", 0, "ON", 3);
        paper(P_OFF, "IT_OFF", "已下架的卷", COURSE, "ENROLLED", 0, "OFF", 4);

        // 综合卷给两行规则：旧的那行（100 题 / 90 分 / 3 次）留着不动，
        // 让"取现行规则"这条逻辑有东西可测 —— 取错行不报任何错，
        // 只会让及格线与触发器校验用的那一行对不上，学员考完交不上卷。
        config(P_MAIN, COURSE, 100, 90, 45, MAX_ATTEMPTS + 1, COOLDOWN_H, REQUIRE_PCT,
                "'2026-01-01 00:00:00'");
        config(P_MAIN, COURSE, PAPER, PASS, 45, MAX_ATTEMPTS, COOLDOWN_H, REQUIRE_PCT, "NOW(3)");
        // 章节卷：不设进度门槛、不冷却、次数放宽
        config(P_CH1, COURSE, CH_PAPER, CH_PASS, 35, CH_MAX, 0, 0, "NOW(3)");
        // 开放卷：course_id 也是空的 —— 它不属于任何课程
        config(P_OPEN, null, OPEN_PAPER, OPEN_PASS, 30, 3, 0, 0, "NOW(3)");

        // 综合卷：6 道已审核（含 1 道多选）+ 2 道待审核
        String[] ans = {"A", "B", "C", "D", "A"};
        for (int i = 0; i < 5; i++)
            question(101 + i, P_MAIN, "SINGLE", "单选第 " + (i + 1) + " 题", ans[i], "APPROVED");
        question(106, P_MAIN, "MULTI", "多选题", "AC", "APPROVED");
        for (int i = 0; i < 2; i++)
            question(201 + i, P_MAIN, "SINGLE", "未审核题 " + i, "A", "PENDING");

        // 章节卷：2 道已审核 + 2 道待审核。综合卷那 6 道是"别的卷的题"，
        // 按 course_id 抽题的写法会把它们一起抽进来。
        question(301, P_CH1, "SINGLE", "章节题一", "B", "APPROVED");
        question(302, P_CH1, "SINGLE", "章节题二", "C", "APPROVED");
        question(311, P_CH1, "SINGLE", "章节待审一", "A", "PENDING");
        question(312, P_CH1, "SINGLE", "章节待审二", "A", "PENDING");

        // 开放卷的题：question.course_id 仍是 NOT NULL，所以这里只能挂一个课程编号 ——
        // 抽题按 paper_id，它不参与判定（见交付说明里的遗留问题）。
        question(401, P_OPEN, "SINGLE", "开放题一", "D", "APPROVED");
        question(402, P_OPEN, "SINGLE", "开放题二", "A", "APPROVED");
    }

    @AfterAll
    static void teardown() { ItDb.close(ds); }

    // ---------------------------------------------------------------- 夹具

    static void paper(long id, String code, String title, Long courseId, String access,
                      int grantsCert, String status, int sortNo) {
        jdbc.update("INSERT INTO exam_paper (id,code,title,subtitle,course_id,access,"
                  + "grants_certificate,status,sort_no) VALUES (?,?,?,?,?,?,?,?,?)",
                id, code, title, title + " · 副标题", courseId, access, grantsCert, status, sortNo);
    }

    /** effectiveFrom 是 SQL 片段（NOW(3) 或字面量），不是参数：两种写法都要用得上。 */
    static void config(long paperId, Long courseId, int count, int pass, int durationMin,
                       int maxAttempts, int cooldownH, int requirePct, String effectiveFrom) {
        jdbc.update("INSERT INTO exam_config (paper_id,course_id,question_count,pass_score,"
                  + "duration_min,shuffle_options,max_attempts,retry_cooldown_hours,"
                  + "require_progress_pct,effective_from) VALUES (?,?,?,?,?,1,?,?,?,"
                  + effectiveFrom + ")",
                paperId, courseId, count, pass, durationMin, maxAttempts, cooldownH, requirePct);
    }

    static void question(long id, long paperId, String type, String stem, String answer, String status) {
        String opts = "[{\"key\":\"A\",\"text\":\"甲\"},{\"key\":\"B\",\"text\":\"乙\"},"
                    + "{\"key\":\"C\",\"text\":\"丙\"},{\"key\":\"D\",\"text\":\"丁\"}]";
        jdbc.update("INSERT INTO question (id,course_id,paper_id,type,stem,options,answer,"
                  + "explanation,origin,review_status) VALUES (?,?,?,?,?,?,?,?,'MANUAL_UPLOAD',?)",
                id, COURSE, paperId, type, stem, opts, answer, EXPL_SENTINEL + id, status);
    }

    // ---------------------------------------------------------------- 工具

    static MockHttpServletRequest as(long userId, String role) {
        var req = new MockHttpServletRequest();
        req.setAttribute(AuthInterceptor.ATTR_USER_ID, userId);
        req.setAttribute(AuthInterceptor.ATTR_ROLE, role);
        req.setRemoteAddr("10.0.0.9");
        return req;
    }

    static MockHttpServletRequest student(long userId) { return as(userId, "STUDENT"); }

    static ExamController.StartReq startOf(long paperId) {
        return new ExamController.StartReq(paperId, null);
    }

    static ApiException expect(int status, String mustContain, Executable body) {
        var e = assertThrows(ApiException.class, body);
        assertEquals(status, e.status(), "HTTP 状态不对（消息：" + e.getMessage() + "）—— "
                + "这个负例可能是因为别的原因红的");
        assertTrue(e.getMessage().contains(mustContain),
                "失败原因不是要测的那条。期望消息里含『" + mustContain + "』，实际：" + e.getMessage());
        return e;
    }

    /**
     * 断言这条写入是被<b>触发器主动拒绝</b>的。
     *
     * <p>只断言"抛异常"太松：撞唯一键、列名写错、字段超长都会抛，那样这条负例
     * 通过时什么也没证明。MySQL 的 SIGNAL 统一走错误码 <b>1644</b>，
     * 钉住它等于钉住"是那道闸门拦的"。文案不钉 —— 文案在库那边，随时可能改一个字。
     */
    static void expectTriggerRefusal(String what, Executable body) {
        var e = assertThrows(RuntimeException.class, body);
        SQLException sql = null;
        for (Throwable t = e; t != null; t = t.getCause())
            if (t instanceof SQLException s) { sql = s; break; }
        assertNotNull(sql, what + "：抛的不是数据库异常，这条负例是别的原因红的 —— " + e);
        assertEquals(1644, sql.getErrorCode(), what + "：不是触发器 SIGNAL 拒的（1644 才是），"
                + "实际 " + sql.getErrorCode() + " / " + sql.getMessage());
    }

    static int attemptCount(long userId, long paperId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM exam_attempt WHERE user_id=? AND paper_id=?",
                Integer.class, userId, paperId);
    }

    /** 把控制器返回的 Map 走一遍真实序列化，判据要跑在出网的那份字节上。 */
    static JsonNode asJson(Map<String, Object> body) {
        return mapper.valueToTree(body);
    }

    static String rawJson(Map<String, Object> body) {
        try {
            return mapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 递归收集所有名字里带 answer/correct/explan/solution 的字段路径。 */
    static void collectAnswerish(JsonNode n, String path, List<String> hits) {
        if (n.isObject()) {
            var it = n.fields();
            while (it.hasNext()) {
                var e = it.next();
                String lower = e.getKey().toLowerCase(Locale.ROOT);
                if (lower.contains("answer") || lower.contains("correct")
                        || lower.contains("explan") || lower.contains("solution"))
                    hits.add(path + "." + e.getKey());
                collectAnswerish(e.getValue(), path + "." + e.getKey(), hits);
            }
        } else if (n.isArray()) {
            for (int i = 0; i < n.size(); i++) collectAnswerish(n.get(i), path + "[" + i + "]", hits);
        }
    }

    /** 本卷已审核通过的题 → 标准答案。抽题按卷，判据也必须按卷。 */
    static Map<Long, String> approvedAnswers(long paperId) {
        var m = new HashMap<Long, String>();
        for (var r : jdbc.queryForList(
                "SELECT id, answer FROM question WHERE paper_id=? AND review_status='APPROVED'", paperId))
            m.put(((Number) r.get("id")).longValue(), (String) r.get("answer"));
        return m;
    }

    /** 全对地交掉一场考试。 */
    static Map<String, Object> submitAllCorrect(long userId, long paperId, long attemptId) {
        var answers = new HashMap<String, String>();
        for (var e : approvedAnswers(paperId).entrySet())
            answers.put(String.valueOf(e.getKey()), e.getValue());
        return examApi.submit(String.valueOf(attemptId),
                new ExamController.SubmitReq(answers), student(userId));
    }

    @SuppressWarnings("unchecked")
    static List<ExamService.PaperCard> papersOf(long userId) {
        return (List<ExamService.PaperCard>) examApi.papers(student(userId)).get("papers");
    }

    static ExamService.PaperCard cardOf(long userId, long paperId) {
        for (var c : papersOf(userId)) if (c.paperId() == paperId) return c;
        return null;
    }

    /** 把一场考试整体往前挪，用来跨过冷却窗口 / 制造超时。 */
    static void shiftAttempt(long attemptId, int hours) {
        jdbc.update("UPDATE exam_attempt SET started_at=DATE_SUB(started_at, INTERVAL ? HOUR),"
                  + " deadline_at=DATE_SUB(deadline_at, INTERVAL ? HOUR),"
                  + " submitted_at=DATE_SUB(submitted_at, INTERVAL ? HOUR) WHERE id=?",
                hours, hours, hours, attemptId);
    }

    // ---------------------------------------------------------------- 试卷列表

    @Test @Order(1)
    @DisplayName("我能考哪几份卷：按权益筛，下架的不出现，开放卷谁都看得见")
    void papersListIsScopedByEntitlement() {
        var mine = papersOf(20);
        Set<Long> ids = new HashSet<>();
        for (var c : mine) ids.add(c.paperId());
        assertEquals(Set.of(P_MAIN, P_CH1, P_OPEN), ids,
                "买了课的学员应当看到本课程的两份卷加开放卷，且不含已下架的 IT_OFF");
        // 排序按 sort_no：综合卷在前、章节卷随后、开放卷最后
        assertEquals(List.of(P_MAIN, P_CH1, P_OPEN),
                mine.stream().map(ExamService.PaperCard::paperId).toList(), "卷序不是 sort_no 排的");

        var main = mine.get(0);
        assertEquals("IT_MAIN", main.code());
        assertEquals("ENROLLED", main.access());
        assertTrue(main.grantsCertificate(), "综合卷是发证的那一张");
        assertEquals(COURSE, main.courseId().longValue());
        assertNotNull(main.courseTitle(), "卡上要有课程名，学员才知道这卷属于哪门课");
        assertEquals(PAPER, main.rules().questionCount());
        assertEquals(PASS, main.rules().passScore());
        assertEquals(100, main.progressPct(), "学满了却不算 100%");
        assertTrue(main.canStart());

        var open = mine.get(2);
        assertNull(open.courseId(), "开放卷不挂课程");
        assertEquals("OPEN", open.access());
        assertFalse(open.grantsCertificate(), "开放卷不发结业证明");
        assertNull(open.progressPct(),
                "开放卷没有课程，写一个『进度 0%』出来是句假话 —— 空的语义是「不适用」");

        // 一门课都没买的注册学员：只看得见开放卷，且能考
        var none = papersOf(U_NOCOURSE);
        assertEquals(1, none.size(), "没买课的学员看到了不该看到的卷：" + none);
        assertEquals(P_OPEN, none.get(0).paperId());
        assertTrue(none.get(0).canStart(), "开放卷对任何注册学员都该能考：" + none.get(0).blockedReason());

        // 下架的卷不只是"从列表上消失"：直接打它的 paperId 也不能开考
        expect(404, "已下架", () -> examApi.start(startOf(P_OFF), student(20)));
    }

    @Test @Order(2)
    @DisplayName("列表说能考的，逐张点下去都真能考（可见性判据与开考闸门是同一段代码）")
    void listAndGateAgreeCardByCard() {
        for (var c : papersOf(20)) {
            var view = examApi.config(c.paperId(), null, student(20));
            assertEquals(c.canStart(), view.get("canStart"),
                    "卷 " + c.paperId() + "：列表说 canStart=" + c.canStart() + "，config 说的不一样");
            assertEquals(c.blockedReason(), view.get("blockedReason"),
                    "卷 " + c.paperId() + " 的拒绝理由两处不一致");
        }
    }

    // ---------------------------------------------------------------- 开放卷

    @Test @Order(3)
    @DisplayName("★ 开放卷：一门课都没买也能考、能交、能判分")
    void openPaperNeedsNoEnrollment() {
        var started = examApi.start(startOf(P_OPEN), student(U_NOCOURSE));
        assertEquals(Boolean.TRUE, started.get("firstTime"));
        long a = (Long) started.get("attemptId");
        assertEquals(OPEN_PAPER, started.get("questionCount"));

        var r = submitAllCorrect(U_NOCOURSE, P_OPEN, a);
        assertEquals(OPEN_PAPER, r.get("score"));
        assertEquals(Boolean.TRUE, r.get("passed"));

        // 开放卷不挂课程：这一场的 course_id 必须是 NULL，不能拿别的课凑一个上去 ——
        // 凑上去的话，trg_certificate_precheck 会认为他通过了那门课的考试。
        assertNull(jdbc.queryForMap("SELECT course_id FROM exam_attempt WHERE id=?", a).get("course_id"),
                "开放卷的场次挂上了课程编号");
        assertEquals(P_OPEN, ((Number) jdbc.queryForMap(
                "SELECT paper_id FROM exam_attempt WHERE id=?", a).get("paper_id")).longValue());
    }

    @Test @Order(4)
    @DisplayName("★ 权益卷：没买课的人看不见，也考不了（403，且没留下考试记录）")
    void enrolledPaperRefusesStudentWithoutEnrollment() {
        assertNull(cardOf(U_NOCOURSE, P_MAIN), "没买课却在列表里看到了权益卷");

        // 例外口子只能由 access 字段开：开放卷放行 ≠ 所有卷放行
        expect(403, "还没有这门课的权益",
                () -> examApi.start(startOf(P_MAIN), student(U_NOCOURSE)));
        expect(403, "还没有这门课的权益",
                () -> examApi.config(P_MAIN, null, student(U_NOCOURSE)));
        assertEquals(0, attemptCount(U_NOCOURSE, P_MAIN), "被拒绝的开考留下了一条考试记录");
    }

    // ---------------------------------------------------------------- 规则

    @Test @Order(5)
    @DisplayName("规则取的是这份卷现行的那一行，不是旧行、也不是别的卷的行")
    void configReadsEffectiveRuleRow() {
        var body = examApi.config(P_MAIN, null, student(20));
        var rules = (ExamService.Rules) body.get("rules");
        // 同一份卷的旧行是 100 题 / 90 分 / 3 次。取错行不会报任何错，
        // 只会让及格线与触发器校验用的那一行对不上，学员考完交不上卷。
        assertEquals(PAPER, rules.questionCount(), "取到的不是现行规则行");
        assertEquals(PASS, rules.passScore());
        assertEquals(MAX_ATTEMPTS, rules.maxAttempts());
        assertEquals(COOLDOWN_H, rules.retryCooldownHours());
        assertEquals(REQUIRE_PCT, rules.requireProgressPct());
        assertEquals(100, body.get("progressPct"), "学满了却不算 100%");
        assertEquals(Boolean.TRUE, body.get("canStart"));

        // 章节卷有它自己的一套规则。按 course_id 取配置的写法在这里必红：
        // 两份卷同属一门课，会一起读到综合卷那行，于是 50 题的卷子谁也考不过。
        var ch = (ExamService.Rules) examApi.config(P_CH1, null, student(20)).get("rules");
        assertEquals(CH_PAPER, ch.questionCount(), "章节卷读到了别的卷的规则");
        assertEquals(CH_PASS, ch.passScore());
        assertEquals(CH_MAX, ch.maxAttempts());
        assertEquals(0, ch.requireProgressPct(), "章节卷不该有进度门槛");
    }

    @Test @Order(6)
    @DisplayName("进度不达标不能开考，且列表、config、start 给的是同一个理由")
    void progressGateBlocksStart() {
        var view = examApi.config(P_MAIN, null, student(21));
        assertEquals(0, view.get("progressPct"));
        assertEquals(Boolean.FALSE, view.get("canStart"));
        String shown = (String) view.get("blockedReason");
        assertNotNull(shown, "界面上不给理由，学员只会反复点");

        var e = expect(409, "学习进度", () -> examApi.start(startOf(P_MAIN), student(21)));
        // 两处各写一遍判定的话迟早分叉：界面写着"可以考"，点下去被拒，
        // 而这种不一致没有任何自动信号
        assertEquals(shown, e.getMessage(), "config 展示的理由与 start 实际拒绝的理由不是同一句");
        assertEquals(shown, cardOf(21, P_MAIN).blockedReason(), "列表上写的理由又是另一句");
        assertEquals(0, attemptCount(21, P_MAIN), "被拒绝的开考留下了一条考试记录");

        // 同一个人、同一门课、同一秒：章节卷没有进度门槛，所以他考得了 ——
        // 门槛必须是按卷的规则说了算，不是"这门课要 80%"。
        assertTrue(cardOf(21, P_CH1).canStart(), "没有进度门槛的章节卷也被拦下了");
    }

    @Test @Order(7)
    @DisplayName("题库不足卷面题量时拒绝开考（否则所有人必然不及格，而且不报错）")
    void poolTooSmallRefusesStart() {
        jdbc.update("UPDATE question SET review_status='REJECTED' WHERE id=101");
        try {
            expect(409, "不足卷面要求", () -> examApi.start(startOf(P_MAIN), student(20)));
            assertEquals(0, attemptCount(20, P_MAIN), "题库不足时仍然开出了一场考试");
        } finally {
            jdbc.update("UPDATE question SET review_status='APPROVED' WHERE id=101");
        }
    }

    // ---------------------------------------------------------------- 出题

    @Test @Order(8)
    @DisplayName("★ 下发的考卷里没有任何答案：字段名、字段值两侧都断")
    void paperNeverCarriesAnswers() {
        var started = examApi.start(startOf(P_MAIN), student(20));
        attempt20 = (Long) started.get("attemptId");
        assertEquals(Boolean.TRUE, started.get("firstTime"));

        var body = examApi.paper(String.valueOf(attempt20), student(20));

        // 阴性对照：先证明确实拉到了题。空响应当然"不含答案"，
        // 少了这一步，接口哪天返回空数组这条判据照样绿。
        @SuppressWarnings("unchecked")
        var qs = (List<ExamService.PaperQuestion>) body.get("questions");
        assertEquals(PAPER, qs.size(), "考卷题数不对");
        for (var q : qs) {
            assertNotNull(q.stem());
            assertFalse(q.stem().isBlank(), "题干是空的");
            assertEquals(4, q.options().size(), "选项没下发，学员没法作答");
        }

        var hits = new ArrayList<String>();
        collectAnswerish(asJson(body), "$", hits);
        assertTrue(hits.isEmpty(), "响应体里出现了答案字段：" + hits
                + " —— 前端判分是假判分，浏览器读得到就等于公开");

        // 字段名之外再断值：有人把 explanation 塞进 stem 或某个自由文本字段时，
        // 只看字段名的判据是抓不到的
        assertFalse(rawJson(body).contains(EXPL_SENTINEL),
                "响应体里出现了题目解析原文");
    }

    @Test @Order(9)
    @DisplayName("只抽已审核通过的题：待审核的一道都不许进考卷")
    void onlyApprovedQuestionsAreDrawn() {
        var body = examApi.paper(String.valueOf(attempt20), student(20));
        @SuppressWarnings("unchecked")
        var qs = (List<ExamService.PaperQuestion>) body.get("questions");
        Set<Long> drawn = new HashSet<>();
        for (var q : qs) drawn.add(q.id());
        assertEquals(approvedAnswers(P_MAIN).keySet(), drawn,
                "考卷里出现了未审核的题，或漏掉了已审核的题");
    }

    @Test @Order(10)
    @DisplayName("★ 抽题只抽本卷的题：同一门课里别的卷那 6 道一道都不许串进来")
    void drawsOnlyFromItsOwnPaper() {
        Set<Long> mine = approvedAnswers(P_CH1).keySet();
        assertEquals(CH_PAPER, mine.size(), "夹具坏了：章节卷的已审核题不是 2 道");
        long courseApproved = jdbc.queryForObject(
                "SELECT COUNT(*) FROM question WHERE course_id=? AND review_status='APPROVED'",
                Long.class, COURSE);
        assertTrue(courseApproved > mine.size(),
                "夹具坏了：本课程的题池必须比本卷大，否则这条判据什么也证明不了");

        // 抽题是随机的：只抽一次的话，按 course_id 抽也有约 1/28 的概率恰好抽中本卷这两道。
        // 抽三次，蒙对的概率降到十万分之四 —— 而正确实现每次都必然相等。
        for (int round = 1; round <= 3; round++) {
            long a = (Long) examApi.start(startOf(P_CH1), student(U_CHAPTER)).get("attemptId");
            @SuppressWarnings("unchecked")
            var qs = (List<ExamService.PaperQuestion>) examApi
                    .paper(String.valueOf(a), student(U_CHAPTER)).get("questions");
            Set<Long> drawn = new HashSet<>();
            for (var q : qs) drawn.add(q.id());
            assertEquals(mine, drawn, "第 " + round + " 轮抽到了不属于本卷的题（或漏了本卷的题）");
            // 下一轮要从头开始：删掉这一场，attempt_no 与冷却都跟着回到起点
            jdbc.update("DELETE FROM exam_answer WHERE attempt_id=?", a);
            jdbc.update("DELETE FROM exam_attempt WHERE id=?", a);
        }
    }

    @Test @Order(11)
    @DisplayName("别人的考卷：404，且不区分『不存在』与『不是你的』")
    void paperOfAnotherStudentIs404() {
        var e = expect(404, "不存在",
                () -> examApi.paper(String.valueOf(attempt20), student(22)));
        // 越权检查写在 WHERE 里而不是"查出来再比对"，两种情况对外必须同一种结果 ——
        // 否则这个接口就是一个可以枚举别人考卷 id 的探针
        var e2 = expect(404, "不存在", () -> examApi.paper("99999999", student(22)));
        assertEquals(e.getMessage(), e2.getMessage(), "『不是你的』与『不存在』给了不同的回答");
    }

    @Test @Order(12)
    @DisplayName("提交了不在本场考卷里的题号：400，且本场没被交掉")
    void submitRejectsQuestionNotOnPaper() {
        var req = new ExamController.SubmitReq(Map.of("999999", "A"));
        expect(400, "不在本场考卷内",
                () -> examApi.submit(String.valueOf(attempt20), req, student(20)));
        assertNull(jdbc.queryForMap("SELECT submitted_at FROM exam_attempt WHERE id=?", attempt20)
                .get("submitted_at"), "一次被拒绝的提交把考试交掉了");
    }

    // ---------------------------------------------------------------- 判分

    @Test @Order(13)
    @DisplayName("判分在服务端；大小写与多选顺序都不影响对错；重复交卷返回原成绩")
    void submitScoresServerSideAndIsIdempotent() {
        var correct = approvedAnswers(P_MAIN);
        var answers = new HashMap<String, String>();
        for (var e : correct.entrySet()) {
            String a = e.getValue();
            // 多选反着提交（AC → CA）、单选小写提交：两条规范化路径一次断完。
            // 不做规范化的话表现是"多选题好像全是错的"，而这不会报任何错。
            answers.put(String.valueOf(e.getKey()),
                    a.length() > 1 ? new StringBuilder(a).reverse().toString()
                                   : a.toLowerCase(Locale.ROOT));
        }

        var r = examApi.submit(String.valueOf(attempt20),
                new ExamController.SubmitReq(answers), student(20));
        assertEquals(Boolean.TRUE, r.get("firstTime"));
        assertEquals(PAPER, r.get("score"), "全对却没拿满分：多选顺序或大小写没规范化");
        assertEquals(Boolean.TRUE, r.get("passed"));

        assertEquals(PAPER, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM exam_answer WHERE attempt_id=?", Integer.class, attempt20),
                "答题明细没有逐题落库，事后无法复盘");
        var row = jdbc.queryForMap("SELECT score, passed, submitted_at FROM exam_attempt WHERE id=?", attempt20);
        assertEquals(PAPER, ((Number) row.get("score")).intValue());
        assertNotNull(row.get("submitted_at"));

        // 幂等：弱网重发不该变成一句"已交卷不得改分"的技术味提示
        var again = examApi.submit(String.valueOf(attempt20),
                new ExamController.SubmitReq(answers), student(20));
        assertEquals(Boolean.FALSE, again.get("firstTime"));
        assertEquals(PAPER, again.get("score"));
        assertEquals(PAPER, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM exam_answer WHERE attempt_id=?", Integer.class, attempt20),
                "重复交卷又写了一遍答题明细");
    }

    @Test @Order(14)
    @DisplayName("交卷之后取题仍然不给答案（题库要复用，交一次卷送一次题库）")
    void paperAfterSubmitStillHidesAnswers() {
        var body = examApi.paper(String.valueOf(attempt20), student(20));
        assertEquals(Boolean.TRUE, body.get("submitted"));
        assertEquals(PAPER, body.get("score"), "交卷后应当能看到自己的成绩");
        assertEquals(P_MAIN, body.get("paperId"), "答题页要知道自己考的是哪份卷");
        var hits = new ArrayList<String>();
        collectAnswerish(asJson(body), "$", hits);
        assertTrue(hits.isEmpty(), "交卷后把答案一起发出去了：" + hits);
        assertFalse(rawJson(body).contains(EXPL_SENTINEL));
    }

    @Test @Order(15)
    @DisplayName("已通过的人不再开考：不浪费次数，也不会把 passed 改掉")
    void passedStudentCannotStartAgain() {
        expect(409, "已通过", () -> examApi.start(startOf(P_MAIN), student(20)));
        assertEquals(1, attemptCount(20, P_MAIN));
        // 通过的是综合卷，不是"这门课的所有卷"：章节卷照样能考
        assertTrue(cardOf(20, P_CH1).canStart(), "通过综合卷把同门课的章节卷一起锁上了");
    }

    // ---------------------------------------------------------------- 次数与冷却

    @Test @Order(16)
    @DisplayName("冷却期内不能重考；跨过冷却可以；次数用完就彻底不能考")
    void cooldownThenAttemptsExhausted() {
        // 第 1 场：交白卷
        long a1 = (Long) examApi.start(startOf(P_MAIN), student(22)).get("attemptId");
        var r1 = examApi.submit(String.valueOf(a1), new ExamController.SubmitReq(Map.of()), student(22));
        assertEquals(0, r1.get("score"));
        assertEquals(Boolean.FALSE, r1.get("passed"));

        // 冷却：从 exam_config 读的 24 小时，不是代码里写死的
        expect(409, "距上一次考试不足 " + COOLDOWN_H + " 小时",
                () -> examApi.start(startOf(P_MAIN), student(22)));
        assertEquals(1, attemptCount(22, P_MAIN), "冷却期内还是开出了一场");

        shiftAttempt(a1, COOLDOWN_H + 1);

        // 第 2 场：还是白卷
        long a2 = (Long) examApi.start(startOf(P_MAIN), student(22)).get("attemptId");
        assertNotEquals(a1, a2);
        examApi.submit(String.valueOf(a2), new ExamController.SubmitReq(Map.of()), student(22));
        shiftAttempt(a2, COOLDOWN_H + 1);

        // 第 3 场：次数上限（max_attempts=2）。这条同时被触发器兜着，
        // 但应用层要先给人话，而不是把触发器文案抛给学员
        expect(409, "次数已用完", () -> examApi.start(startOf(P_MAIN), student(22)));
        assertEquals(MAX_ATTEMPTS, attemptCount(22, P_MAIN));

        // 提示顺序：次数用完的人不该看到"冷却中，24 小时后再来"然后白等一天
        var view = examApi.config(P_MAIN, null, student(22));
        assertEquals(Boolean.FALSE, view.get("canStart"));
        assertTrue(((String) view.get("blockedReason")).contains("次数已用完"),
                "次数已用完却先提示冷却：" + view.get("blockedReason"));
        assertEquals(0, view.get("attemptsLeft"));

        // 次数是按卷算的：综合卷考满了，章节卷还有它自己的 5 次
        var ch = examApi.config(P_CH1, null, student(22));
        assertEquals(CH_MAX, ch.get("attemptsLeft"), "一份卷的次数被另一份卷用掉了");
    }

    @Test @Order(17)
    @DisplayName("开考不交卷：超时后不给交，那一场按用掉一次算")
    void expiredAttemptCannotBeSubmitted() {
        long a = (Long) examApi.start(startOf(P_MAIN), student(23)).get("attemptId");
        // 整场往前挪两小时（started_at 也要挪，否则撞 ck_attempt_time）
        jdbc.update("UPDATE exam_attempt SET started_at=DATE_SUB(NOW(3), INTERVAL 2 HOUR),"
                  + " deadline_at=DATE_SUB(NOW(3), INTERVAL 1 HOUR) WHERE id=?", a);

        expect(409, "考试时间已",
                () -> examApi.submit(String.valueOf(a), new ExamController.SubmitReq(Map.of()), student(23)));
        // 不判 0 分也不落分："没交卷"与"交了白卷"在申诉时是两回事
        var row = jdbc.queryForMap("SELECT score, submitted_at FROM exam_attempt WHERE id=?", a);
        assertNull(row.get("score"), "超时的场次被判了分");
        assertNull(row.get("submitted_at"));

        var list = exam.attempts(23L, P_MAIN, null);
        assertEquals(1, list.size());
        assertEquals("EXPIRED", list.get(0).state());
        assertEquals(P_MAIN, list.get(0).paperId());

        // 而且这一场照样算用掉了一次机会，否则"开一场晾着"就是绕过冷却的免费通道
        expect(409, "距上一次考试不足", () -> examApi.start(startOf(P_MAIN), student(23)));
    }

    // ---------------------------------------------------------------- 章节卷不发证

    @Test @Order(18)
    @DisplayName("★ 章节卷考满分也换不出结业证明（应用层：给人话，不是触发器文案）")
    void chapterPaperPassDoesNotGrantCertificate() {
        chapterPassed = (Long) examApi.start(startOf(P_CH1), student(U_CHAPTER)).get("attemptId");
        var r = submitAllCorrect(U_CHAPTER, P_CH1, chapterPassed);
        assertEquals(CH_PAPER, r.get("score"), "章节卷没判满分，下面那条判据就不成立了");
        assertEquals(Boolean.TRUE, r.get("passed"));

        var e = expect(409, "章节练习卷不发结业证明",
                () -> certApi.issue(new CertificateController.IssueReq(COURSE), student(U_CHAPTER)));
        assertTrue(e.getMessage().contains("尚未通过"), "话要说全：" + e.getMessage());
        assertEquals(0, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM certificate WHERE user_id=?", Integer.class, U_CHAPTER),
                "被拒绝的申请还是发出了一张证");
    }

    @Test @Order(19)
    @DisplayName("★ 库层同一条判据：拿章节卷那场去插 certificate，被触发器拒（对照：综合卷那场能插）")
    void certificateTriggerRefusesNonGrantingPaper() {
        // 应用层那条改错了没人会立刻发现（发证接口只有一个入口），所以库层必须自己拦。
        expectTriggerRefusal("章节卷换证", () -> jdbc.update(
                "INSERT INTO certificate (cert_no,user_id,course_id,attempt_id) VALUES (?,?,?,?)",
                "WK-AAAA-BBBB-CCCC", U_CHAPTER, COURSE, chapterPassed));
        assertEquals(0, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM certificate WHERE user_id=?", Integer.class, U_CHAPTER));

        // 对照组：同一个人、同一门课、同一张表，只把"考的是哪份卷"换成综合卷 ——
        // 它必须能插进去。没有这一步的话，上面那条负例可能只是因为别的列写错了。
        mainPassedOfChapterUser = (Long) examApi.start(startOf(P_MAIN), student(U_CHAPTER)).get("attemptId");
        var r = submitAllCorrect(U_CHAPTER, P_MAIN, mainPassedOfChapterUser);
        assertEquals(Boolean.TRUE, r.get("passed"));
        jdbc.update("INSERT INTO certificate (cert_no,user_id,course_id,attempt_id) VALUES (?,?,?,?)",
                "WK-AAAA-BBBB-CCCD", U_CHAPTER, COURSE, mainPassedOfChapterUser);
        assertEquals(1, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM certificate WHERE user_id=?", Integer.class, U_CHAPTER),
                "对照组没插进去：那这条负例证明不了是 grants_certificate 拦的");
    }

    @Test @Order(20)
    @DisplayName("★ 场次的 paper_id 与它引用的规则不是同一份卷时，被触发器拒")
    void attemptTriggerRefusesPaperConfigMismatch() {
        long mainConfig = jdbc.queryForObject(
                "SELECT id FROM exam_config WHERE paper_id=? ORDER BY effective_from DESC, id DESC LIMIT 1",
                Long.class, P_MAIN);
        long chConfig = jdbc.queryForObject(
                "SELECT id FROM exam_config WHERE paper_id=? ORDER BY effective_from DESC, id DESC LIMIT 1",
                Long.class, P_CH1);

        // 章节卷的场次挂上综合卷的规则：抽题按 A 卷走、判分按 B 卷的及格线走，
        // 两边各按各的算，一个字的错都不会报。
        expectTriggerRefusal("卷与规则错配", () -> insertAttempt(U_RAW, P_CH1, mainConfig));
        assertEquals(0, attemptCount(U_RAW, P_CH1), "错配的场次还是写进去了");

        // 对照组：同一条 INSERT，只把 config 换成本卷的 —— 必须能写进去
        insertAttempt(U_RAW, P_CH1, chConfig);
        assertEquals(1, attemptCount(U_RAW, P_CH1),
                "对照组没写进去：那上面那条负例证明不了是 paper_id 与 config 对不上被拒的");
    }

    /** 裸插一场考试（绕开应用层，专门去打触发器）。 */
    static void insertAttempt(long userId, long paperId, long configId) {
        jdbc.update("INSERT INTO exam_attempt (user_id,course_id,paper_id,config_id,attempt_no,"
                  + "question_ids,started_at,deadline_at) VALUES (?,?,?,?,1,'[]',NOW(3),"
                  + "DATE_ADD(NOW(3), INTERVAL 1 HOUR))", userId, COURSE, paperId, configId);
    }

    // ---------------------------------------------------------------- 结业证明

    @Test @Order(21)
    @DisplayName("没通过不能发证，且库里不许留下半张证")
    void certificateNeedsPassedAttempt() {
        expect(409, "尚未通过",
                () -> certApi.issue(new CertificateController.IssueReq(COURSE), student(23)));
        assertEquals(0, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM certificate WHERE user_id=23", Integer.class));
        // 22 用完了次数、一次都没过，同样发不出
        expect(409, "尚未通过",
                () -> certApi.issue(new CertificateController.IssueReq(COURSE), student(22)));
    }

    @Test @Order(22)
    @DisplayName("发证幂等：连点两下只有一张，编号相同")
    void certificateIssueIsIdempotent() {
        var first = certApi.issue(new CertificateController.IssueReq(COURSE), student(20));
        assertEquals(Boolean.TRUE, first.get("firstTime"));
        cert20 = (String) first.get("certNo");
        assertNotNull(cert20);

        for (int i = 0; i < 3; i++) {
            var again = certApi.issue(new CertificateController.IssueReq(COURSE), student(20));
            assertEquals(Boolean.FALSE, again.get("firstTime"), "重复申请被当成首次签发");
            assertEquals(cert20, again.get("certNo"), "同一个人同一门课发出了第二个编号");
        }
        assertEquals(1, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM certificate WHERE user_id=20 AND course_id=?",
                Integer.class, COURSE));
        // 签发依据必须是那次通过的考试
        assertEquals(attempt20, ((Number) jdbc.queryForMap(
                "SELECT attempt_id FROM certificate WHERE cert_no=?", cert20).get("attempt_id")).longValue());
    }

    @Test @Order(23)
    @DisplayName("公开验真：给状态与课程，不给完整姓名，任何形式都不给手机号")
    void verifyMasksHolderAndNeverLeaksPhone() {
        var body = certApi.verify(cert20.toLowerCase(Locale.ROOT).replace("-", " "));
        assertEquals("VALID", body.get("status"));
        assertEquals(cert20, body.get("certNo"), "大小写/横杠规整没做，用户抄的编号查不到");
        assertNotNull(body.get("courseTitle"));
        assertEquals("张**", body.get("holder"), "姓名脱敏不对");

        String raw = rawJson(body);
        assertFalse(raw.contains(PHONE_20), "验真响应里出现了学员手机号");
        assertFalse(raw.contains("张三丰"), "验真响应里出现了完整姓名");
    }

    @Test @Order(24)
    @DisplayName("吊销：非管理员 403、无理由 400，两种情况都不许改到那一行")
    void revokeNeedsAdminAndReason() {
        expect(403, "没有权限", () -> certApi.revoke(
                new CertificateController.RevokeReq(cert20, "冒名顶替"), student(20)));
        expect(400, "理由", () -> certApi.revoke(
                new CertificateController.RevokeReq(cert20, "  "), as(1, "PLATFORM_ADMIN")));
        assertNull(jdbc.queryForMap("SELECT revoked_at FROM certificate WHERE cert_no=?", cert20)
                .get("revoked_at"), "被拒绝的吊销把证书吊了");
    }

    @Test @Order(25)
    @DisplayName("吊销后验真如实返回 REVOKED；审计恰好一条；不能自助重新申请")
    void revokeThenVerifyReportsRevoked() {
        var r = certApi.revoke(new CertificateController.RevokeReq(cert20, "考试代考，经核实"),
                as(1, "PLATFORM_ADMIN"));
        assertEquals(Boolean.TRUE, r.get("firstTime"));
        assertNotNull(r.get("revokedAt"));

        var v = certApi.verify(cert20);
        // 藏起来的话，核验的人看到的是"查无此证"，会以为自己抄错了再打电话来问 ——
        // 而这张证确实存在过
        assertEquals("REVOKED", v.get("status"));
        assertNotNull(v.get("revokedAt"));

        var audit = jdbc.queryForList(
                "SELECT operator_id, target_id, before_json, after_json FROM audit_log "
              + "WHERE action='CERT_REVOKE'");
        assertEquals(1, audit.size(), "吊销没有留痕，日后复核平台没有立场");
        assertEquals(cert20, audit.get(0).get("target_id"));
        assertEquals(1L, ((Number) audit.get(0).get("operator_id")).longValue());
        assertTrue(String.valueOf(audit.get(0).get("before_json")).contains("null"),
                "before_json 丢了『吊销前 revoked_at 为空』这条事实：" + audit.get(0).get("before_json"));
        assertTrue(String.valueOf(audit.get(0).get("after_json")).contains("代考"), "吊销理由没进审计");

        // 重复吊销幂等，且不再多写一条审计（审计记的是发生过的变更）
        var twice = certApi.revoke(new CertificateController.RevokeReq(cert20, "再点一次"),
                as(1, "PLATFORM_ADMIN"));
        assertEquals(Boolean.FALSE, twice.get("firstTime"));
        assertEquals(1, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action='CERT_REVOKE'", Integer.class),
                "重复点击被记成了两次吊销");

        expect(409, "已被平台吊销",
                () -> certApi.issue(new CertificateController.IssueReq(COURSE), student(20)));
    }

    @Test @Order(26)
    @DisplayName("验真：编号不存在 404、格式不对 400（两者要分得开）")
    void verifyUnknownAndMalformed() {
        expect(404, "未查到", () -> certApi.verify("WK-ACDE-FHJK-LMNP"));
        expect(400, "位", () -> certApi.verify("WK-ACDE"));
        expect(400, "开头", () -> certApi.verify("XX-ACDE-FHJK-LMNP"));
        // 字母表外的字符明确报位置，不做模糊匹配 —— 否则"查无此证"与"你抄错一位"
        // 变成同一个结果，而这两件事对核验人是完全不同的信号
        expect(400, "第 1 位", () -> certApi.verify("WK-OCDE-FHJK-LMNP"));
    }

    @Test @Order(27)
    @DisplayName("证书编号不可预测：不重复、相邻两个几乎全不同（顺序号只差最后一两位）")
    void certNoIsUnpredictable() {
        int n = 200;
        var seen = new ArrayList<String>(n);
        for (int i = 0; i < n; i++) seen.add(CertNo.generate());
        assertEquals(n, new HashSet<>(seen).size(), "编号出现重复");

        for (int i = 1; i < n; i++) {
            String a = seen.get(i - 1).replace("-", "").substring(CertNo.PREFIX.length());
            String b = seen.get(i).replace("-", "").substring(CertNo.PREFIX.length());
            int same = 0;
            for (int k = 0; k < CertNo.BODY; k++) if (a.charAt(k) == b.charAt(k)) same++;
            // 12 位、24 字符表，重合位数期望 0.5；重合 7 位以上的概率小到不可能偶然出现。
            // 顺序编号在这条判据下必红：它相邻两个只差最后一两位。
            assertTrue(same < 7, "相邻两个编号有 " + same + " 位相同（" + a + " / " + b
                    + "）—— 编号带上了顺序或时间结构，发证量会被外人算出来");
        }
        // 规整往返：编号会被抄在纸上、念给客服听
        for (String s : seen.subList(0, 20))
            assertEquals(s, CertNo.normalize(s.toLowerCase(Locale.ROOT).replace("-", "")));
    }

    // ---------------------------------------------------------------- HTTP 层

    /**
     * 前面所有用例都是直接调控制器方法，测不到两件只在真实 HTTP 分发时才发生的事：
     * <ul>
     *   <li>{@code /papers}、{@code /config}、{@code /attempts} 会不会被 {@code /{attemptId}} 吃掉 ——
     *       被吃掉的表现是"取配置"变成"查一场叫 config 的考试"，返回 404 而不是报错。
     *   <li>路径变量填了非数字时是 400 还是 500 —— 走 500 的话，一个打错的 URL
     *       会在错误日志里留一条 ERROR 堆栈，真故障会被这类噪声淹没。
     * </ul>
     * 用 standaloneSetup 挂上真实的 GlobalExceptionHandler，跑的是同一套分发与同一套错误映射。
     */
    @Test @Order(28)
    @DisplayName("HTTP 分发：字面量路径不被 /{attemptId} 吃掉，打错的 URL 是 400 不是 500")
    void httpRoutingAndErrorMapping() throws Exception {
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(examApi, certApi)
                .setControllerAdvice(new tech.dragonai.wangke.web.GlobalExceptionHandler())
                .build();
        org.springframework.test.web.servlet.request.RequestPostProcessor login20 = r -> {
            r.setAttribute(AuthInterceptor.ATTR_USER_ID, 20L);
            r.setAttribute(AuthInterceptor.ATTR_ROLE, "STUDENT");
            return r;
        };

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/exam/papers").with(login20))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.papers[0].code").value("IT_MAIN"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.papers[0].rules.questionCount").value(PAPER));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/exam/config").param("paperId", String.valueOf(P_CH1)).with(login20))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.ok").value(true))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.rules.questionCount").value(CH_PAPER));

        // 向后兼容：老客户端只会传 courseId，要落到这门课发证的那张卷（IT_MAIN）上
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/exam/config").param("courseId", "1").with(login20))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.rules.questionCount").value(PAPER));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/exam/attempts").with(login20))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.attempts[0].state").value("PASSED"));

        // 打错的路径变量：400 + 人话，不是 500 + 一条 ERROR 堆栈
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/exam/abc").with(login20))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.ok").value(false));

        // 两个入口参数都不给同理
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/exam/config").with(login20))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());

        // 公开验真整条链路：此时这张证已被吊销
        var res = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/public/cert/verify/" + cert20))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.status").value("REVOKED"))
                .andReturn();
        String body = res.getResponse().getContentAsString();
        assertFalse(body.contains(PHONE_20), "公开验真的真实响应体里出现了手机号：" + body);
        assertFalse(body.contains("张三丰"), "公开验真的真实响应体里出现了完整姓名：" + body);
    }

    /**
     * 一门课有两张发证卷时，courseId 那条兼容路径必须<b>明说无法判断</b>，
     * 不能静默取第一张 —— 取错卷不报任何错，学员只是考了一份他没打算考的卷。
     */
    @Test @Order(29)
    @DisplayName("向后兼容：courseId 解析不出唯一那张发证卷时 409 说明原因")
    void legacyCourseIdRefusesToGuess() {
        jdbc.update("UPDATE exam_paper SET grants_certificate=1 WHERE id=?", P_CH1);
        try {
            expect(409, "无法判断要考哪一份",
                    () -> examApi.config(null, COURSE, student(20)));
        } finally {
            jdbc.update("UPDATE exam_paper SET grants_certificate=0 WHERE id=?", P_CH1);
        }
        jdbc.update("UPDATE exam_paper SET status='OFF' WHERE id=?", P_MAIN);
        try {
            expect(409, "没有可发结业证明的测评卷",
                    () -> examApi.config(null, COURSE, student(20)));
        } finally {
            jdbc.update("UPDATE exam_paper SET status='ON' WHERE id=?", P_MAIN);
        }
    }

    // ---------------------------------------------------------------- 权益的两档

    /**
     * ★ 权益到期 = <b>可见但不能考</b>；压根没买过 = 不可见。两档必须分得开。
     *
     * <p>合成一档（到期就整张卷藏起来）的后果没有任何报错：到期学员打开测评页发现
     * "我的卷不见了"，而平台想让他做的事是续期 —— 这句话除了这张卡以外没有地方会说。
     */
    @Test @Order(30)
    @DisplayName("★ 权益到期的卷留在列表里、点不动、写着续期；没买过课的卷压根不出现")
    void expiredEntitlementKeepsCardButBlocksStart() {
        Set<Long> ids = new HashSet<>();
        for (var c : papersOf(U_EXPIRED)) ids.add(c.paperId());
        assertEquals(Set.of(P_MAIN, P_CH1, P_OPEN), ids,
                "到期学员的卷从列表上消失了（或多出了不该有的卷）：" + ids);

        var main = cardOf(U_EXPIRED, P_MAIN);
        assertFalse(main.canStart(), "到期了还说能考");
        String shown = main.blockedReason();
        assertTrue(shown.contains("续期"), "学员要做的事是续期：" + shown);
        // 给学员看的句子里不许出现库里的枚举：他读不懂，也不知道该做什么
        assertFalse(shown.contains("EXPIRED") || shown.contains("SUSPENDED"),
                "把库里的枚举原样拼给学员看了：" + shown);
        assertNotNull(main.rules(), "可见但不能考的卡仍然要有卷面信息，不能只剩一句话");

        // 点下去被拒，而且与卡上写的是同一句 —— 两处各写一遍必然分叉
        var e = expect(403, "续期", () -> examApi.start(startOf(P_MAIN), student(U_EXPIRED)));
        assertEquals(shown, e.getMessage(), "卡上写的理由与 start 拒绝的不是同一句");
        assertEquals(0, attemptCount(U_EXPIRED, P_MAIN), "被拒绝的开考留下了一条考试记录");

        // 对照组：这门课压根没买过的人，这张卷根本不出现 —— 两档确实是两档
        assertNull(cardOf(U_NOCOURSE, P_MAIN), "没买过课的人看到了这张卷");

        // 被停用的权益是另一句话（同样不许带枚举）：他要做的事是找客服，不是续期
        jdbc.update("UPDATE enrollment SET status='SUSPENDED',"
                  + " valid_until=DATE_ADD(NOW(3), INTERVAL 24 MONTH) "
                  + "WHERE user_id=? AND course_id=?", U_EXPIRED, COURSE);
        try {
            var suspended = cardOf(U_EXPIRED, P_MAIN);
            assertFalse(suspended.canStart());
            assertTrue(suspended.blockedReason().contains("暂停"), suspended.blockedReason());
            assertFalse(suspended.blockedReason().contains("SUSPENDED"),
                    "把库里的枚举原样拼给学员看了：" + suspended.blockedReason());
        } finally {
            jdbc.update("UPDATE enrollment SET status='ACTIVE',"
                      + " valid_until=DATE_SUB(NOW(3), INTERVAL 1 DAY) "
                      + "WHERE user_id=? AND course_id=?", U_EXPIRED, COURSE);
        }
    }

    /**
     * ★ 还没生效的权益（valid_from 在未来）不能考。
     *
     * <p>这一条守的是<b>两道闸门的口径一致</b>：听课那边是
     * {@code status='ACTIVE' AND valid_from <= NOW(3) AND valid_until > NOW(3)}。
     * 测评这边漏掉 valid_from 的话，同一个人一讲课都点不开，却能考试、
     * 考过还能换出结业证明 —— 两道闸门对同一个人给出相反的答案，且两边都不报错。
     */
    @Test @Order(31)
    @DisplayName("★ 权益还没生效的人考不了（与听课那道闸门同一个口径）")
    void notYetEffectiveEntitlementCannotStart() {
        // 用章节卷：它没有进度门槛、没冷却、没考过 —— 除了权益没有别的东西能拦他，
        // 所以下面这条红了只可能是权益判据的事。
        var card = cardOf(U_FUTURE, P_CH1);
        assertNotNull(card, "买过课的人（哪怕权益还没生效）要看得见这张卷");
        assertFalse(card.canStart(), "还没生效的权益却能考：" + card.blockedReason());
        assertTrue(card.blockedReason().contains("生效"), card.blockedReason());

        expect(403, "生效", () -> examApi.start(startOf(P_CH1), student(U_FUTURE)));
        assertEquals(0, attemptCount(U_FUTURE, P_CH1), "被拒绝的开考留下了一条考试记录");

        // 反证：只把 valid_from 挪到过去，同一个人同一份卷立刻能考。
        // 少了这一步，"这张卡永远 canStart=false" 也能让上面几行绿。
        jdbc.update("UPDATE enrollment SET valid_from=DATE_SUB(NOW(3), INTERVAL 1 DAY) "
                  + "WHERE user_id=? AND course_id=?", U_FUTURE, COURSE);
        try {
            assertTrue(cardOf(U_FUTURE, P_CH1).canStart(),
                    "权益生效了却还是考不了：拦住他的不是 valid_from");
        } finally {
            jdbc.update("UPDATE enrollment SET valid_from=DATE_ADD(NOW(3), INTERVAL 7 DAY) "
                      + "WHERE user_id=? AND course_id=?", U_FUTURE, COURSE);
        }

        // 同一个人在听课那道闸门上也是被拒的 —— 这条判据钉的就是"两边说的是同一件事"
        var ent = new tech.dragonai.wangke.learn.Entitlements(jdbc);
        assertThrows(ApiException.class, () -> ent.requireCourse(U_FUTURE, COURSE),
                "测评这边拒了，听课那边却放行：两道闸门对同一个人给出了相反的答案");
    }

    // ---------------------------------------------------------------- 规则本身配错了

    /**
     * 卷面题数配成 0 或负数时必须拒绝开考。
     *
     * <p>0 的坏法是最安静的一种：题库不足那道门变成 {@code 0 < 0} 恒假，于是开出一场
     * 零题考试，交卷判 0 分，而 {@code passed = 0 >= 0} 为真 —— 一场空考换出结业证明，
     * 库里那三条触发器（分数≤题量、passed 与分数一致、次数上限）逐条满意。
     * 负数则直接拼成 {@code LIMIT -5}，SQL 语法错，被记成一条 500 的系统故障。
     */
    @Test @Order(32)
    @DisplayName("卷面题数配成 0 / 负数时拒绝开考，且是 409 人话不是 500")
    void invalidQuestionCountRefusesStart() {
        long pBad = 505;
        paper(pBad, "IT_BAD", "题数配错的卷", COURSE, "ENROLLED", 0, "ON", 5);
        config(pBad, COURSE, 0, 0, 30, 3, 0, 0, "NOW(3)");
        try {
            expect(409, "卷面题数是 0", () -> examApi.start(startOf(pBad), student(20)));
            assertEquals(0, attemptCount(20, pBad), "零题的卷还是开出了一场考试");

            jdbc.update("UPDATE exam_config SET question_count=-5 WHERE paper_id=?", pBad);
            expect(409, "卷面题数是 -5", () -> examApi.start(startOf(pBad), student(20)));
            assertEquals(0, attemptCount(20, pBad));
        } finally {
            jdbc.update("DELETE FROM exam_config WHERE paper_id=?", pBad);
            jdbc.update("DELETE FROM exam_paper WHERE id=?", pBad);
        }
    }

    // ---------------------------------------------------------------- 成绩页要的两个字段

    /**
     * 取考卷的响应要说清"这一场考的是哪份卷、那份卷发不发证"。
     *
     * <p>成绩页据此决定给不给"去领结业证明"那个入口。让前端按默认值猜的话：
     * 猜发证 → 考过章节卷的人看到一个点下去必然 409 的按钮；
     * 猜不发证 → 真考过综合卷的人拿不到他应得的那张证。两种猜错都不报错。
     */
    @Test @Order(33)
    @DisplayName("取考卷带上 paperTitle 与 grantsCertificate（成绩页的领证入口只认它）")
    void paperCarriesPaperIdentity() {
        var main = examApi.paper(String.valueOf(attempt20), student(20));
        assertEquals("结业综合测评", main.get("paperTitle"));
        assertEquals(Boolean.TRUE, main.get("grantsCertificate"), "综合卷是发证的那一张");

        // 对照组：章节卷那场必须是 false。恒返回 true 的实现在这里必红 ——
        // 而它在生产里的表现只是"章节卷考完也有个领证按钮"，没有任何报错。
        var ch = examApi.paper(String.valueOf(chapterPassed), student(U_CHAPTER));
        assertEquals("第一章 · 章节练兵", ch.get("paperTitle"));
        assertEquals(Boolean.FALSE, ch.get("grantsCertificate"), "章节卷不发结业证明");

        // 新字段不许把答案捎带出去（判据在真实序列化的那份字节上）
        var hits = new ArrayList<String>();
        collectAnswerish(asJson(main), "$", hits);
        assertTrue(hits.isEmpty(), "响应体里出现了答案字段：" + hits);
    }

    /**
     * 把接口文档里那条<b>已知边界</b>钉成判据：{@code attempts?courseId=} 查不到开放卷的场次。
     *
     * <p>开放卷不挂课程，{@code exam_attempt.course_id} 是 NULL，SQL 里 {@code course_id=?}
     * 对 NULL 恒假。这不是缺陷是定义，但它的坏法是静默的 —— 哪天有页面拿这条当
     * "我的全部考试记录"总表，学员考过的开放卷那几场会一声不响地缺席。
     * 钉住它有两个作用：写文档的人改了行为必须同轮改这条，
     * 读代码的人不必再去猜"到底是没实现还是故意的"。
     */
    @Test @Order(34)
    @DisplayName("已知边界：attempts?courseId= 筛不到开放卷的场次（不传筛子才是总表）")
    void attemptsByCourseIdNeverMatchesOpenPaper() {
        // U_NOCOURSE 只考过开放卷那一场（第 3 条用例里考的）
        assertEquals(1, exam.attempts(U_NOCOURSE, null, null).size(),
                "夹具坏了：他那场开放卷的记录不见了，下面两条就什么都证明不了");
        assertEquals(P_OPEN, exam.attempts(U_NOCOURSE, P_OPEN, null).get(0).paperId(),
                "按卷筛是新客户端该走的路，它必须查得到");
        assertTrue(exam.attempts(U_NOCOURSE, null, COURSE).isEmpty(),
                "按课程筛竟然查到了开放卷的场次 —— 行为变了就要同轮改掉接口文档里那条边界");
    }

    interface Executable extends org.junit.jupiter.api.function.Executable {}
}
