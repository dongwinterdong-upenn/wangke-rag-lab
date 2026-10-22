package tech.dragonai.wangke.exam;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.function.Executable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionTemplate;
import tech.dragonai.wangke.ItDb;
import tech.dragonai.wangke.auth.AuthInterceptor;
import tech.dragonai.wangke.web.ApiException;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测评报告：事实层 + 叙述层，跑在真实 MySQL 上。
 *
 * <h3>为什么不打真实 DeepSeek</h3>
 * 真打一次 19 秒、要花钱，而且<b>它每次写的话都不一样</b> ——
 * 这套判据里最要紧的几条恰恰是负例（半截回包、编造的时间点、模型不可用），
 * 而"让模型编一个不存在的时间点"这件事根本没法稳定复现。
 * 所以调用面抽成了 {@link DeepSeekClient} 接口，这里装一个<b>脚本化</b>的假客户端：
 * 每个用例先排好这次要回什么，再去看代码有没有按规格处理。
 * 真实回包的形状则由 {@link DeepSeekHttpClient#parse} 那几条直接判（不出网）。
 *
 * <h3>后台执行在这里是同步的</h3>
 * 除了"交卷不被拖住"那一条，其余用例都把线程池换成 {@code Runnable::run}：
 * 生成于是变成确定的一步，不用在测试里睡几秒等一个后台线程 ——
 * 那种等待迟早变成偶发红，而偶发红会被当成"再跑一次就好了"。
 * 抢占（多实例不重复生成）单独用真线程去撞。
 *
 * <h3>库结构</h3>
 * {@code exam_report} 与 {@code question} 那三列由<b>另一条并行</b>落进 {@code db/}。
 * 这里按规格第三节自己建一份（缺了才建），所以这套判据在那条合进来之前之后都跑得起来。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ExamReportIT {

    static final String DB = "wangke_report_it";
    static final long COURSE = 1L;

    /** 本卷：6 道已审核题全部会被抽中，判分与聚合都是确定的。 */
    static final long P_MAIN = 601;
    static final int PAPER = 6, PASS = 4;
    /** 另外两份真实存在的卷：报告里"去考另一份卷"只能从它们里挑。 */
    static final long P_CH = 602, P_OPEN = 603;
    /** 已下架的卷：不许出现在推荐里（报告不能把学员指向一个他打不开的东西）。 */
    static final long P_OFF = 604;

    static final String CH_TITLE = "第一章 · 前端与 skill（练兵卷）";
    static final String OPEN_TITLE = "AIGC 内容生产能力测试";
    static final String OFF_TITLE = "已下架的卷";

    /** 题目解析的哨兵：它一个字都不许进事实层，也不许进提示词。 */
    static final String EXPL_SENTINEL = "EXPL_SENTINEL_9Z";

    static final long U = 40;          // 主用户，绝大多数场次是他的
    static final long U_OTHER = 41;    // 越权用例的旁观者
    static final long U_ASYNC = 42;    // 真线程池那一条

    static DataSource ds;
    static JdbcTemplate jdbc;
    static ObjectMapper mapper;
    static ScriptedClient client;
    static ExamReportService reports;
    static ExamService exam;
    static ExamController examApi;

    /** 真线程池的那一套（只给"交卷不被拖住"用）。 */
    static ScriptedClient slowClient;
    static ExamReportService asyncReports;
    static ExamService asyncExam;

    @BeforeAll
    static void bootstrap() throws Exception {
        ds = ItDb.freshDatabase(DB);
        jdbc = new JdbcTemplate(ds);
        mapper = new ObjectMapper().findAndRegisterModules();
        ensureReportSchema();

        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        client = new ScriptedClient();
        // Runnable::run = 同步执行：入队之后立刻在当前线程里生成完，用例不用等
        reports = new ExamReportService(jdbc, mapper, client, Runnable::run);
        exam = new ExamService(jdbc, tx, mapper, reports);
        examApi = new ExamController(exam, reports);

        slowClient = new ScriptedClient();
        asyncReports = new ExamReportService(jdbc, mapper, slowClient);   // 真的 2 线程池
        asyncExam = new ExamService(jdbc, tx, mapper, asyncReports);

        for (long u : new long[]{U, U_OTHER, U_ASYNC})
            jdbc.update("INSERT INTO sys_user (id,phone,role) VALUES (?,?,'STUDENT')",
                    u, "132000000" + u);
        for (long u : new long[]{U, U_OTHER, U_ASYNC})
            jdbc.update("INSERT INTO enrollment (user_id,course_id,source,valid_from,valid_until,"
                      + "agreement_version,agreed_at) VALUES (?,?,'GRANT',NOW(3),"
                      + "DATE_ADD(NOW(3), INTERVAL 24 MONTH),'v2026-08-11',NOW(3))", u, COURSE);

        // 三讲。留在 DRAFT：报告只用讲次的标题与顺序，上架闸门（水印码本、内容密钥）
        // 与这套判据无关，为了它把整条上架链复制一遍只会让这个夹具的坏法变多。
        for (int k = 1; k <= 3; k++) {
            jdbc.update("INSERT INTO chapter (id,course_id,title,hls_key,store_segment_count,"
                      + "wm_bit_base,sort_no) VALUES (?,?,?,?,10,?,?)",
                    k, COURSE, "第" + k + "章", "oss/l" + k, k - 1, k);
            jdbc.update("INSERT INTO lesson (id,chapter_id,course_id,title,sort_no,duration_sec,"
                      + "hls_key,segment_count,wm_slot_stride,wm_slot_count,wm_bit_offset,"
                      + "wm_bit_base,status) VALUES (?,?,?,?,?,9000,?,10,10,1,?,?,'DRAFT')",
                    k, k, COURSE, "第 " + k + " 讲 · 正片", k, "oss/l" + k, k - 1, k - 1);
        }

        // seed 自带的卷全部下架：这套用例只对自己造的四份负责，
        // 否则"报告能推荐哪几份卷"取决于 seed，而那是别人在改的文件。
        jdbc.update("UPDATE exam_paper SET status='OFF'");
        paper(P_MAIN, "RPT_MAIN", "结业综合测评", COURSE, "ENROLLED", 1, "ON", 1);
        paper(P_CH, "RPT_CH1", CH_TITLE, COURSE, "ENROLLED", 0, "ON", 2);
        paper(P_OPEN, "RPT_OPEN", OPEN_TITLE, null, "OPEN", 0, "ON", 3);
        paper(P_OFF, "RPT_OFF", OFF_TITLE, COURSE, "ENROLLED", 0, "OFF", 4);

        // 次数放宽、不冷却、无进度门槛：这套判据要反复开考，
        // 而次数与冷却是 ExamIT 的题目，不是这里的。
        config(P_MAIN, COURSE, PAPER, PASS, 45, 40, 0, 0);
        config(P_CH, COURSE, 2, 1, 35, 5, 0, 0);
        config(P_OPEN, null, 2, 1, 30, 3, 0, 0);

        // ─── 本卷的 6 道题。每一列都是某条聚合判据的输入，改动前先看下面那张期望表。
        // id   题型     板块      考点         难度  讲次  讲内秒   答案
        q(701, "SINGLE", "第一章 前端与 skill", "skill 的触发时机", 1, 1L, 1127, "A", "短题干一");
        q(702, "SINGLE", "第一章 前端与 skill", "组件之间怎么通信", 2, 1L, 65, "B", "短题干二");
        q(703, "MULTI", "第二章 后端与多模态", "多模态入参怎么拼", 3, 2L, 300, "AC", "短题干三");
        q(704, "JUDGE", "第二章 后端与多模态", "幂等要不要自己做", 1, 2L, null, "A", "短题干四");
        // 三无题：没板块、没考点、没难度、没挂讲次 —— 报告里必须有它的位置，
        // 否则"分块加起来不等于总题数"，而学员一眼就看得出来。
        q(705, "SINGLE", null, null, null, null, null, "D",
                "这是一道很长的情境题题干，用来验证摘要会被截断。".repeat(8));
        // 讲内秒 7212 = 120:12：最长的一讲三个多小时，分钟数是三位的。
        q(706, "SINGLE", "第三章 AI 原理与 RAG", "召回为什么会漏", 2, 3L, 7212, "C", "短题干六");
        // 未审核的两道：一道都不许进考卷，也就不该出现在报告里
        q(711, "SINGLE", "第一章 前端与 skill", "不该出现的考点", 1, 1L, 10, "A", "待审核一");
        jdbc.update("UPDATE question SET review_status='PENDING' WHERE id=711");
        q(712, "SINGLE", null, null, null, null, null, "A", "待审核二");
        jdbc.update("UPDATE question SET review_status='PENDING' WHERE id=712");

        // 另外两份卷各配一道题，免得它们"没题"这件事影响别的判据
        q2(721, P_CH, "SINGLE", "A");
        q2(722, P_CH, "SINGLE", "A");
        q2(731, P_OPEN, "SINGLE", "A");
        q2(732, P_OPEN, "SINGLE", "A");
    }

    @AfterAll
    static void teardown() { ItDb.close(ds); }

    // ---------------------------------------------------------------- 库结构

    /**
     * 报告那张表与 question 的三列。<b>缺了才建</b> ——
     * 权威 DDL 在 {@code db/}（由另一条并行落），这里只保证这套判据能独立跑起来。
     * 都用 root（{@link ItDb#runScript}）执行：应用账号在 binlog 下建触发器会报 1419。
     */
    static void ensureReportSchema() throws Exception {
        if (!hasColumn("question", "topic"))
            ddl("ALTER TABLE `question` "
              + "ADD COLUMN `topic` VARCHAR(64) NULL COMMENT '聚合用的粗粒度分组：课程卷=章名，AIGC 卷=板块名', "
              + "ADD COLUMN `knowledge_point` VARCHAR(160) NULL COMMENT '细粒度考点，错题清单里逐题显示', "
              + "ADD COLUMN `difficulty` TINYINT NULL COMMENT '1 扎实理解 / 2 需要实操 / 3 踩过坑才会', "
              + "ADD CONSTRAINT `ck_question_difficulty` "
              + "CHECK (`difficulty` IS NULL OR `difficulty` BETWEEN 1 AND 3);");

        ddl("""
            CREATE TABLE IF NOT EXISTS `exam_report` (
              `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
              `attempt_id`   BIGINT UNSIGNED NOT NULL,
              `user_id`      BIGINT UNSIGNED NOT NULL,
              `paper_id`     BIGINT UNSIGNED NULL,
              `status`       ENUM('QUEUED','RUNNING','READY','FAILED') NOT NULL DEFAULT 'QUEUED',
              `model`        VARCHAR(64)  NULL,
              `content`      MEDIUMTEXT   NULL,
              `stats`        JSON         NOT NULL,
              `fail_reason`  VARCHAR(255) NULL,
              `tries`        INT          NOT NULL DEFAULT 0,
              `requested_at` DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
              `started_at`   DATETIME(3)  NULL,
              `finished_at`  DATETIME(3)  NULL,
              `updated_at`   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                             ON UPDATE CURRENT_TIMESTAMP(3),
              PRIMARY KEY (`id`),
              UNIQUE KEY `uk_report_attempt` (`attempt_id`),
              KEY `idx_report_queue` (`status`,`requested_at`)
            ) ENGINE=InnoDB COMMENT='测评报告';
            """);

        if (!hasTrigger("trg_exam_report_precheck"))
            // 插入侧与更新侧判据同源，所以放进一个过程里：两处各抄一遍的坏法是
            // 「新建的报告建不成，而把一行 UPDATE 成同样的形状能改成」（schema.sql 里
            // exam_paper 那两条触发器的注释记的就是这个）。
            ddl("""
                DROP PROCEDURE IF EXISTS `sp_exam_report_check`;
                DELIMITER $$
                CREATE PROCEDURE `sp_exam_report_check`(
                  IN p_attempt BIGINT UNSIGNED, IN p_user BIGINT UNSIGNED,
                  IN p_paper BIGINT UNSIGNED, IN p_status VARCHAR(16),
                  IN p_content MEDIUMTEXT, IN p_reason VARCHAR(255))
                BEGIN
                  DECLARE a_cnt INT DEFAULT 0;
                  DECLARE a_user BIGINT UNSIGNED DEFAULT NULL;
                  DECLARE a_paper BIGINT UNSIGNED DEFAULT NULL;
                  DECLARE a_sub DATETIME(3) DEFAULT NULL;
                  SELECT COUNT(*), MAX(`user_id`), MAX(`paper_id`), MAX(`submitted_at`)
                    INTO a_cnt, a_user, a_paper, a_sub
                    FROM `exam_attempt` WHERE `id` = p_attempt;
                  IF a_cnt = 0 THEN
                    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '这份报告挂的考试记录不存在';
                  END IF;
                  IF a_sub IS NULL THEN
                    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '没有交卷的考试不能有测评报告';
                  END IF;
                  IF NOT (p_user <=> a_user) THEN
                    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '报告的学员与那场考试的学员不是同一个';
                  END IF;
                  IF NOT (p_paper <=> a_paper) THEN
                    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '报告的试卷与那场考试的试卷不是同一份';
                  END IF;
                  IF p_status = 'READY' AND (p_content IS NULL OR p_content = '') THEN
                    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'READY 的报告必须有正文';
                  END IF;
                  IF p_status = 'FAILED' AND (p_reason IS NULL OR p_reason = '') THEN
                    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'FAILED 的报告必须写明失败理由';
                  END IF;
                END$$
                CREATE TRIGGER `trg_exam_report_precheck`
                BEFORE INSERT ON `exam_report` FOR EACH ROW
                BEGIN
                  CALL `sp_exam_report_check`(NEW.attempt_id, NEW.user_id, NEW.paper_id,
                                              NEW.status, NEW.content, NEW.fail_reason);
                END$$
                CREATE TRIGGER `trg_exam_report_upd`
                BEFORE UPDATE ON `exam_report` FOR EACH ROW
                BEGIN
                  CALL `sp_exam_report_check`(NEW.attempt_id, NEW.user_id, NEW.paper_id,
                                              NEW.status, NEW.content, NEW.fail_reason);
                END$$
                DELIMITER ;
                """);
    }

    static boolean hasColumn(String table, String col) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME=?",
                Integer.class, table, col) > 0;
    }

    static boolean hasTrigger(String name) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TRIGGERS "
                + "WHERE TRIGGER_SCHEMA=DATABASE() AND TRIGGER_NAME=?", Integer.class, name) > 0;
    }

    static void ddl(String sql) throws Exception { ItDb.runScript("USE `" + DB + "`;\n" + sql); }

    // ---------------------------------------------------------------- 夹具

    static void paper(long id, String code, String title, Long courseId, String access,
                      int grantsCert, String status, int sortNo) {
        jdbc.update("INSERT INTO exam_paper (id,code,title,course_id,access,"
                  + "grants_certificate,status,sort_no) VALUES (?,?,?,?,?,?,?,?)",
                id, code, title, courseId, access, grantsCert, status, sortNo);
    }

    static void config(long paperId, Long courseId, int count, int pass, int durationMin,
                       int maxAttempts, int cooldownH, int requirePct) {
        jdbc.update("INSERT INTO exam_config (paper_id,course_id,question_count,pass_score,"
                  + "duration_min,shuffle_options,max_attempts,retry_cooldown_hours,"
                  + "require_progress_pct,effective_from) VALUES (?,?,?,?,?,1,?,?,?,NOW(3))",
                paperId, courseId, count, pass, durationMin, maxAttempts, cooldownH, requirePct);
    }

    /** 本卷的题。topic / knowledge_point / difficulty / lesson_id / source_ts_sec 都是报告的输入。 */
    static void q(long id, String type, String topic, String kp, Integer diff,
                  Long lessonId, Integer ts, String answer, String stem) {
        String opts = "[{\"key\":\"A\",\"text\":\"甲\"},{\"key\":\"B\",\"text\":\"乙\"},"
                    + "{\"key\":\"C\",\"text\":\"丙\"},{\"key\":\"D\",\"text\":\"丁\"}]";
        jdbc.update("INSERT INTO question (id,course_id,paper_id,lesson_id,source_ts_sec,type,stem,"
                  + "options,answer,explanation,origin,review_status,topic,knowledge_point,difficulty) "
                  + "VALUES (?,?,?,?,?,?,?,?,?,?,'MANUAL_UPLOAD','APPROVED',?,?,?)",
                id, COURSE, P_MAIN, lessonId, ts, type, stem, opts, answer,
                EXPL_SENTINEL + id, topic, kp, diff);
    }

    /** 别的卷的题（只为让那两份卷不是空卷）。 */
    static void q2(long id, long paperId, String type, String answer) {
        jdbc.update("INSERT INTO question (id,course_id,paper_id,type,stem,options,answer,"
                  + "origin,review_status) VALUES (?,?,?,?,?,'[]',?,'MANUAL_UPLOAD','APPROVED')",
                id, COURSE, paperId, type, "别的卷的题 " + id, answer);
    }

    // ---------------------------------------------------------------- 工具

    static MockHttpServletRequest student(long userId) {
        var req = new MockHttpServletRequest();
        req.setAttribute(AuthInterceptor.ATTR_USER_ID, userId);
        req.setAttribute(AuthInterceptor.ATTR_ROLE, "STUDENT");
        req.setRemoteAddr("10.0.0.9");
        return req;
    }

    /**
     * 固定的一份作答：701 对、703 对（多选反着提交）、702 与 706 答错、704 与 705 不作答。
     * 下面每一条聚合判据的期望值都是从这份作答手算出来的。
     */
    static Map<String, String> standardAnswers() {
        var m = new HashMap<String, String>();
        m.put("701", "A");    // 正确
        m.put("702", "C");    // 错（正解 B）
        m.put("703", "CA");   // 正确（正解 AC，顺序无关）
        m.put("706", "A");    // 错（正解 C）
        return m;             // 704 / 705 缺席 = 未作答
    }

    /** 开一场、按标准作答交卷，返回场次编号。交卷会顺带入队并（同步执行器下）当场生成。 */
    static long submitStandard(long userId) {
        long a = exam.start(userId, P_MAIN).attemptId();
        var r = exam.submit(userId, a, standardAnswers());
        assertTrue(r.firstTime(), "夹具坏了：这一场没有被真正交上");
        assertEquals(2, r.score(), "夹具坏了：标准作答应当是 2 分");
        return a;
    }

    static Map<String, Object> reportRow(long attemptId) {
        var rows = jdbc.queryForList("SELECT * FROM exam_report WHERE attempt_id=?", attemptId);
        assertEquals(1, rows.size(), "场次 " + attemptId + " 的报告行不是恰好一行");
        return rows.get(0);
    }

    static String status(long attemptId) { return (String) reportRow(attemptId).get("status"); }

    static void forceStatus(long attemptId, String sql, Object... args) {
        var all = new ArrayList<>(List.of(args));
        all.add(attemptId);
        jdbc.update("UPDATE exam_report SET " + sql + " WHERE attempt_id=?", all.toArray());
    }

    static ApiException expect(int status, String mustContain, Executable body) {
        var e = assertThrows(ApiException.class, body);
        assertEquals(status, e.status(), "HTTP 状态不对（消息：" + e.getMessage() + "）—— "
                + "这个负例可能是因为别的原因红的");
        assertTrue(e.getMessage().contains(mustContain),
                "失败原因不是要测的那条。期望消息里含『" + mustContain + "』，实际：" + e.getMessage());
        return e;
    }

    static ExamReportService.Bucket bucket(List<ExamReportService.Bucket> rows, String label) {
        for (var b : rows) if (b.label().equals(label)) return b;
        return fail("没有这一组：" + label + "，实际有 " + rows);
    }

    static void assertBucket(List<ExamReportService.Bucket> rows, String label, int correct, int total) {
        var b = bucket(rows, label);
        assertEquals(correct + "/" + total, b.correct() + "/" + b.total(),
                "『" + label + "』这一组的分子分母不对");
    }

    /** 一份看起来像真报告、且只引用事实里有的时间点的正文。 */
    static String goodContent() {
        return """
                ## 这次考得怎么样
                6 道题答对 2 道，没有到 4 分的及格线。

                ## 主要问题
                第一章那道组件通信的题答成了 C，第三章召回那道也错了。

                ## 接下来做什么
                1. 回看《第 1 讲 · 正片》的 1:05。
                2. 回看《第 3 讲 · 正片》的 120:12。
                3. 重考本卷。
                """;
    }

    // ================================================================ 事实层

    @Test @Order(1)
    @DisplayName("★ 事实层：按讲次/板块/题型/难度的分子分母，以及错题清单，全部由 SQL 算")
    void factsAreComputedBySql() {
        long a = submitStandard(U);
        var s = reports.build(a);

        assertEquals(2, s.score(), "得分要以库里那一列为准");
        assertEquals(PAPER, s.questionCount(), "分母是他真正被判分的题数");
        assertEquals(PASS, s.passScore());
        assertFalse(s.passed());
        assertEquals("结业综合测评", s.paperTitle());
        assertEquals(Long.valueOf(COURSE), s.courseId(),
                "报告页要用它拼错题出处那条链接的 ?course=，缺了就得为它再发一次请求");
        assertNotNull(s.submittedAt(), "报告要能说出这一场是什么时候交的");

        // 板块：三无题也必须有它的位置，否则各组加起来不等于总题数
        assertEquals(4, s.byTopic().size(), "板块分组数不对：" + s.byTopic());
        assertBucket(s.byTopic(), "第一章 前端与 skill", 1, 2);
        assertBucket(s.byTopic(), "第二章 后端与多模态", 1, 2);
        assertBucket(s.byTopic(), "第三章 AI 原理与 RAG", 0, 1);
        assertBucket(s.byTopic(), "未标注板块", 0, 1);

        // 讲次：顺序按 sort_no（学员是按讲学的），没挂讲次的排最后
        assertEquals(List.of("第 1 讲 · 正片", "第 2 讲 · 正片", "第 3 讲 · 正片", "未标注出处"),
                s.byLesson().stream().map(ExamReportService.Bucket::label).toList(),
                "讲次顺序不是按 sort_no 排的（没挂讲次的那组必须排最后）");
        assertBucket(s.byLesson(), "第 1 讲 · 正片", 1, 2);
        assertBucket(s.byLesson(), "第 2 讲 · 正片", 1, 2);
        assertBucket(s.byLesson(), "第 3 讲 · 正片", 0, 1);
        assertBucket(s.byLesson(), "未标注出处", 0, 1);

        assertBucket(s.byType(), "单选题", 1, 4);
        assertBucket(s.byType(), "多选题", 1, 1);   // 反着提交的多选算对
        assertBucket(s.byType(), "判断题", 0, 1);

        assertBucket(s.byDifficulty(), "扎实理解", 1, 2);
        assertBucket(s.byDifficulty(), "需要实操", 0, 2);
        assertBucket(s.byDifficulty(), "踩过坑才会", 1, 1);
        assertBucket(s.byDifficulty(), "未标难度", 0, 1);

        // 各组的分母都必须等于卷面题数：任何一组漏了题，学员一眼就看得出来对不上
        for (var group : List.of(s.byTopic(), s.byLesson(), s.byType(), s.byDifficulty())) {
            int sum = 0, ok = 0;
            for (var b : group) { sum += b.total(); ok += b.correct(); }
            assertEquals(PAPER, sum, "这一组的分母加起来不等于卷面题数：" + group);
            assertEquals(s.score(), ok, "这一组的分子加起来不等于得分：" + group);
        }

        // 错题清单：顺序、作答、出处逐条钉
        assertEquals(List.of(702L, 704L, 706L, 705L),
                s.wrong().stream().map(ExamReportService.Wrong::questionId).toList(),
                "错题顺序不对（应按讲次顺序，没挂讲次的最后）");
        var w702 = s.wrong().get(0);
        assertEquals("C", w702.myAnswer(), "他的作答要如实带出来");
        assertEquals("组件之间怎么通信", w702.knowledgePoint());
        assertEquals("需要实操", w702.difficultyLabel());
        assertEquals("单选题", w702.typeLabel());
        assertEquals(1L, w702.lessonId().longValue());
        assertEquals("1:05", w702.sourceAt(), "讲内秒要换成播放器上那个 m:ss");

        var w704 = s.wrong().get(1);
        assertNull(w704.myAnswer(), "没作答与答错是两回事：前者多半是时间不够");
        assertNull(w704.sourceAt(), "这道题没标出处，不能凭空造一个时间点");

        assertEquals("120:12", s.wrong().get(2).sourceAt(),
                "三位分钟被截断了 —— 最长的一讲三个多小时，出处会是 120:12 这种");

        var w705 = s.wrong().get(3);
        assertNull(w705.topic());
        assertNull(w705.knowledgePoint());
        assertNull(w705.difficulty());
        assertTrue(w705.stem().endsWith("…"), "长题干没有截断成摘要：" + w705.stem());

        // 未审核的题一道都没进来
        for (var w : s.wrong())
            assertNotEquals(711L, w.questionId(), "未审核的题进了报告");

        // 推荐的卷只能是真实存在且已上架的
        var titles = s.otherPapers().stream().map(ExamReportService.PaperRef::title).toList();
        assertEquals(List.of(CH_TITLE, OPEN_TITLE), titles,
                "『去考另一份卷』给出的卷名不对（本卷不该在里面，下架的卷更不该）");

        // ★ 正确答案与解析一个字都不许进事实层：报告是他能反复看的页面，题库要复用
        String raw = assertDoesNotThrow(() -> mapper.writeValueAsString(s));
        assertFalse(raw.contains(EXPL_SENTINEL), "事实层里出现了题目解析原文");
    }

    @Test @Order(2)
    @DisplayName("交卷只入队一次：重复交卷不再排队，也不会再调一次模型")
    void enqueueHappensOncePerAttempt() {
        client.reset();
        client.push(DeepSeekClient.Reply.of(goodContent(), "deepseek-v4-flash"));
        long a = submitStandard(U);
        assertEquals("READY", status(a));
        assertEquals(1, client.calls.get(), "一场考试应当只生成一次");

        // 弱网重发：交卷本身是幂等的，报告也不该跟着再排一次队
        var again = exam.submit(U, a, standardAnswers());
        assertFalse(again.firstTime());
        assertEquals(1, client.calls.get(), "重复交卷又调了一次模型");
        assertEquals(1, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM exam_report WHERE attempt_id=?", Integer.class, a),
                "一场考试出现了第二份报告");
    }

    // ================================================================ 模型不可用

    @Test @Order(3)
    @DisplayName("★ 模型不可用：状态 FAILED、正文不落库，而 stats 照常返回")
    void modelOutageStillShowsFacts() {
        client.reset();
        client.push(DeepSeekClient.Reply.fail("模型服务暂时调不通（超时或网络不通），请稍后重新生成"));
        long a = submitStandard(U);

        var row = reportRow(a);
        assertEquals("FAILED", row.get("status"));
        assertNull(row.get("content"), "失败的报告不许留下正文");
        assertNotNull(row.get("fail_reason"), "FAILED 必须写明理由");

        var body = examApi.report(String.valueOf(a), student(U));
        assertEquals("FAILED", body.get("status"));
        var s = (ExamReportService.Stats) body.get("stats");
        assertNotNull(s, "★ 模型挂了就把学员的分数与错题一起藏起来，是这套设计要防的头号故障");
        assertEquals(2, s.score());
        assertEquals(4, s.wrong().size());
        assertNull(body.get("content"));
        assertTrue(((String) body.get("failReason")).contains("调不通"));
        assertEquals(Boolean.TRUE, body.get("canRetry"), "失败之后必须给得出重新生成这条路");
    }

    @Test @Order(4)
    @DisplayName("★ 没配 key = 这套环境没有报告功能：明说理由，绝不降级成模板文案")
    void missingApiKeyFailsLoudly() {
        // 真的用生产那个客户端，只是不给 key —— 它必须在出网之前就返回失败
        var noKey = new DeepSeekHttpClient("", "https://api.deepseek.com", "deepseek-v4-flash", 3000, 120);
        assertFalse(noKey.enabled());
        var reply = noKey.complete("sys", "user");
        assertFalse(reply.ok());
        assertEquals(DeepSeekHttpClient.NO_KEY, reply.failReason());
        assertNull(reply.content(), "没配 key 却给出了正文 —— 那就是模板文案");

        var svc = new ExamReportService(jdbc, mapper, noKey, Runnable::run);
        var examNoKey = new ExamService(jdbc, new TransactionTemplate(
                new DataSourceTransactionManager(ds)), mapper, svc);
        long a = examNoKey.start(U, P_MAIN).attemptId();
        examNoKey.submit(U, a, standardAnswers());

        var row = reportRow(a);
        assertEquals("FAILED", row.get("status"));
        assertNull(row.get("content"));
        assertTrue(((String) row.get("fail_reason")).contains("未配置"),
                "理由要说清是平台没配，不是学员的问题：" + row.get("fail_reason"));
        // 事实层照常
        assertEquals(2, ((ExamReportService.Stats) examApi.report(String.valueOf(a), student(U))
                .get("stats")).score());
    }

    // ================================================================ 半截回包

    @Test @Order(5)
    @DisplayName("★ 半截回包一律当失败：finish_reason 不是 stop、正文空、只有思考，三种都不落库")
    void truncatedReplyIsNeverStored() {
        // ① length 截断（额度被思考吃光的那种，content 常常也是空的）
        var cut = DeepSeekHttpClient.parse(200, """
                {"model":"deepseek-v4-flash","choices":[{"finish_reason":"length",
                 "message":{"role":"assistant","content":"## 这次考得怎么样\\n答对 2 道，"}}]}
                """, "deepseek-v4-flash");
        assertFalse(cut.ok(), "半截被当成了成品 —— 它读起来和完整的一样通顺");
        assertTrue(cut.failReason().contains("半截"), cut.failReason());

        // ② 正文空、只有思考：只读 content，绝不拿 reasoning_content 顶替
        var thinkOnly = DeepSeekHttpClient.parse(200, """
                {"model":"deepseek-v4-flash","choices":[{"finish_reason":"stop",
                 "message":{"role":"assistant","content":"",
                 "reasoning_content":"先假设他第 1 讲没看完，那么……"}}]}
                """, "deepseek-v4-flash");
        assertFalse(thinkOnly.ok(), "把模型的草稿当成了正文");
        assertNull(thinkOnly.content());

        // ③ 非 200
        var http429 = DeepSeekHttpClient.parse(429, "{\"error\":\"rate limit\"}", "deepseek-v4-flash");
        assertFalse(http429.ok());
        assertTrue(http429.failReason().contains("429"), http429.failReason());

        // 阴性对照：正常回包必须过，否则上面三条可能只是"什么都过不了"
        var ok = DeepSeekHttpClient.parse(200, """
                {"model":"deepseek-v4-flash-0903","choices":[{"finish_reason":"stop",
                 "message":{"role":"assistant","content":"  ## 这次考得怎么样\\n正文  ",
                 "reasoning_content":"草稿"}}]}
                """, "deepseek-v4-flash");
        assertTrue(ok.ok(), "正常回包也被判成失败了：" + ok.failReason());
        assertTrue(ok.content().startsWith("## 这次考得怎么样"));
        assertEquals("deepseek-v4-flash-0903", ok.model(), "落库的模型 id 要以回包为准");

        // 端到端：半截进来，库里不许留下任何正文
        client.reset();
        client.push(cut);
        long a = submitStandard(U);
        var row = reportRow(a);
        assertEquals("FAILED", row.get("status"));
        assertNull(row.get("content"), "★ 半截正文落库了");
        assertNull(row.get("model"), "失败的那一次不该留下模型 id");
    }

    // ================================================================ 时间点校验

    @Test @Order(6)
    @DisplayName("★ 正文引用了事实里没有的时间点：重生成一次，再犯就 FAILED（宁可没有报告）")
    void inventedTimePointIsRefused() {
        client.reset();
        // 试跑里真犯过的那一种：事实里只有 1:05，它写成"18:47 到 26:51 这一段"
        String invented = "## 这次考得怎么样\n答对 2 道。\n\n## 接下来做什么\n1. 回看第 1 讲 18:47 到 26:51 这一段。\n";
        client.push(DeepSeekClient.Reply.of(invented, "deepseek-v4-flash"));
        client.push(DeepSeekClient.Reply.of(invented, "deepseek-v4-flash"));

        long a = submitStandard(U);
        assertEquals(2, client.calls.get(), "编造之后必须重生成一次（只一次）");
        var row = reportRow(a);
        assertEquals("FAILED", row.get("status"));
        assertNull(row.get("content"), "带着编造时间点的正文落库了");
        String why = (String) row.get("fail_reason");
        assertTrue(why.contains("不存在的时间点"), why);
        assertTrue(why.contains("18:47") || why.contains("26:51"),
                "理由里要写出它编了什么，否则没人查得出来：" + why);

        // 重生成那一次要把上一次的错告诉它，否则两次是同一个提示词、同一件事再犯一遍
        assertTrue(client.prompts.get(1).contains("上一次生成因为引用了"),
                "第二轮没有追加那句纠正");

        // 阴性对照：只引用事实里有的时间点（含三位分钟的 120:12）必须过
        client.reset();
        client.push(DeepSeekClient.Reply.of(goodContent(), "deepseek-v4-flash"));
        long b = submitStandard(U);
        var okRow = reportRow(b);
        assertEquals("READY", okRow.get("status"), "对照组也被判成编造了 —— 判据太严与模型真编了，"
                + "结果都是 FAILED，没人分得清是哪一种");
        assertEquals(1, client.calls.get(), "对照组不该触发重生成");
        assertTrue(((String) okRow.get("content")).contains("120:12"));
        assertEquals("deepseek-v4-flash", okRow.get("model"));
        assertNull(okRow.get("fail_reason"));
    }

    @Test @Order(7)
    @DisplayName("时间点判据本身：全角冒号照抓，08:07 与 8:07 是同一处，190:12 不许被切成 90:12")
    void timePointMatcherHandlesTheTrickyCases() {
        var allowed = ExamReportPrompt.timePoints("出处：第 1 讲 8:07；第 2 讲 190:12");
        assertEquals(2, allowed.size());

        assertTrue(ExamReportPrompt.unknownTimePoints("回看 08:07 那里", allowed).isEmpty(),
                "补零的写法被判成编造了");
        assertTrue(ExamReportPrompt.unknownTimePoints("回看 190:12", allowed).isEmpty(),
                "三位分钟被切成了 90:12，一份正确的报告被判成编造");
        // 全角冒号先被归一成半角再抓，所以报出来的是半角那一形（理由里写给人看的是位置，
        // 不是它敲的哪个冒号）。抓不到才是要命的：抓不到就等于放行。
        assertEquals(List.of("18:47"),
                ExamReportPrompt.unknownTimePoints("回看 18：47", allowed),
                "全角冒号写的时间点没被抓到 —— 抓不到就等于放行");
        assertEquals(List.of("26:51"),
                ExamReportPrompt.unknownTimePoints("从 8:07 到 26:51", allowed),
                "自己拼出来的区间尾巴没被抓到");

        // ★ 负例：拿一个真时间点当前缀往后编。右边界只挡数字不挡冒号的话这一种整个漏掉 ——
        // 判据只看见前面那个 8:07、认定它在清单里、直接放行，而学员点过去的是 8:07:30，
        // 一个不存在的位置。这正是规格第二节要堵的「不得推算、不得自己拼」。
        assertEquals(List.of("8:07:30"),
                ExamReportPrompt.unknownTimePoints("回看 8:07:30 那一段", allowed),
                "★ 合法时间点后面再接一段被放行了 —— 学员点过去是一个不存在的位置");
        assertEquals(List.of("190:12:00"),
                ExamReportPrompt.unknownTimePoints("回看 190:12:00", allowed),
                "事实清单里只会有 m:ss，正文里的 h:mm:ss 一律算编造");
        assertEquals(List.of("8:07:30"),
                ExamReportPrompt.unknownTimePoints("回看 8：07：30", allowed),
                "全角写的那一种也要按同一条判");

        // ★ 事实清单那一侧也要归一全角冒号，否则方向正好反过来：清单里写着全角的「12：30」时
        // 它进不了 allowed 集合，模型照着清单原样引用反被判成编造，连判两轮之后置 FAILED，
        // 而理由把人指到了模型身上（判据太严与模型真编了，结果都是 FAILED，没人分得清）。
        var fullwidth = ExamReportPrompt.timePoints("出处：第 3 讲 12：30 开讲");
        assertEquals(1, fullwidth.size(),
                "★ 事实清单里的全角时间点没进 allowed 集合：" + fullwidth);
        assertTrue(ExamReportPrompt.unknownTimePoints("回看 12:30", fullwidth).isEmpty(),
                "★ 模型照着清单原样引用，反被判成编造了");
        assertTrue(ExamReportPrompt.unknownTimePoints("回看 12：30", fullwidth).isEmpty(),
                "两侧同一套归一：敲哪一种冒号都该是同一个位置");
    }

    // ================================================================ 提示词

    @Test @Order(8)
    @DisplayName("★ 提示词把可选动作枚举死，且不含正确答案")
    void promptEnumeratesOnlyRealActions() {
        client.reset();
        client.push(DeepSeekClient.Reply.of(goodContent(), "deepseek-v4-flash"));
        submitStandard(U);

        String facts = client.prompts.get(0);
        String sys = client.systems.get(0);

        assertFalse(facts.contains(EXPL_SENTINEL), "★ 题目解析被喂给了模型");
        assertTrue(facts.contains("不含正确答案"), "事实清单要自己说清楚它没有答案");
        assertTrue(facts.contains("【可以回看的位置】"));
        assertTrue(facts.contains("1:05") && facts.contains("120:12"),
                "错题的出处没有进事实清单，模型就只能自己编时间点");
        assertFalse(facts.contains("18:47"),
                "答对那道题的时间点不该出现在清单里（它不是他要回看的地方）");

        // 可选动作：只有平台真有的那四类，卷名必须是真实卷名
        assertTrue(facts.contains(CH_TITLE) && facts.contains(OPEN_TITLE), "真实卷名没给全");
        assertFalse(facts.contains(OFF_TITLE), "把一份已下架的卷推荐给了学员");
        assertTrue(sys.contains("重考本卷") && sys.contains("答疑页"), "四类动作没枚举全");
        assertTrue(sys.contains("没有模拟卷"),
                "没把『模拟卷』堵死 —— 试跑里它真的写过『拿一套新的模拟卷』");
        assertTrue(sys.contains("不得自己拼区间"), "没把拼区间那条写进提示词");
        assertTrue(sys.contains("不得写出任何题目的正确答案"));
    }

    // ================================================================ 抢占与自愈

    @Test @Order(9)
    @DisplayName("★ 抢占：八个线程同时抢一份报告，只有一个抢到，模型只被调一次")
    void claimIsExclusiveAcrossThreads() throws Exception {
        client.reset();
        client.push(DeepSeekClient.Reply.of(goodContent(), "deepseek-v4-flash"));
        long a = submitStandard(U);
        assertEquals("READY", status(a));

        // 退回队列，模拟"这一行还没人跑"，然后八个线程一起冲
        forceStatus(a, "status='QUEUED', content=NULL, model=NULL, started_at=NULL, finished_at=NULL");
        client.reset();
        for (int i = 0; i < 8; i++) client.push(DeepSeekClient.Reply.of(goodContent(), "m" + i));

        long reportId = ((Number) reportRow(a).get("id")).longValue();
        int n = 8;
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(n);
        var claimed = new AtomicInteger();
        var errors = Collections.synchronizedList(new ArrayList<Throwable>());
        for (int i = 0; i < n; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    if (reports.claimAndRun(reportId)) claimed.incrementAndGet();
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    done.countDown();
                }
            }, "claim-" + i).start();
        }
        start.countDown();
        assertTrue(done.await(30, java.util.concurrent.TimeUnit.SECONDS), "抢占用例超时");
        assertTrue(errors.isEmpty(), "抢占过程中抛了异常：" + errors);

        assertEquals(1, claimed.get(), "★ 抢到的不是恰好一个：多实例下同一份报告会被生成两遍");
        assertEquals(1, client.calls.get(), "模型被调了不止一次");
        assertEquals("READY", status(a));
        assertEquals("m0", reportRow(a).get("model"));
    }

    @Test @Order(10)
    @DisplayName("★ 卡在 RUNNING 的行会被清扫回队；重试到顶置 FAILED；刚开始跑的不许动")
    void stuckRunningIsSweptBack() {
        client.reset();
        client.push(DeepSeekClient.Reply.fail("先让它失败，下面手工摆状态"));
        long a = submitStandard(U);

        // ① 卡了 30 分钟：退回队列，tries+1
        forceStatus(a, "status='RUNNING', tries=0, content=NULL, fail_reason=NULL, "
                     + "started_at=DATE_SUB(NOW(3), INTERVAL 30 MINUTE)");
        assertTrue(reports.requeueStuck() >= 1);
        var row = reportRow(a);
        assertEquals("QUEUED", row.get("status"), "卡住的行没有回到队列 —— 学员那边会一直转圈");
        assertEquals(1, ((Number) row.get("tries")).intValue());
        assertNull(row.get("started_at"), "退回队列时要把开始时间清掉，否则下一轮清扫立刻又判它卡住");

        // ② 已经重试满：置 FAILED，且必须写明理由（库层触发器也要求）
        forceStatus(a, "status='RUNNING', tries=2, started_at=DATE_SUB(NOW(3), INTERVAL 30 MINUTE)");
        assertTrue(reports.requeueStuck() >= 1);
        row = reportRow(a);
        assertEquals("FAILED", row.get("status"));
        assertEquals(3, ((Number) row.get("tries")).intValue());
        assertTrue(((String) row.get("fail_reason")).contains("中途中断"), "" + row.get("fail_reason"));

        // ③ 阴性对照：刚开始跑的那一行不许被扫走（否则一份正在生成的报告会被判死）
        forceStatus(a, "status='RUNNING', tries=0, fail_reason=NULL, started_at=NOW(3)");
        reports.requeueStuck();
        assertEquals("RUNNING", status(a), "清扫把一份正在生成的报告扫掉了");
        forceStatus(a, "status='FAILED', fail_reason='用例收尾'");
    }

    @Test @Order(11)
    @DisplayName("库里排队的行会被清扫捡起来跑（进程重启之后唯一的兜底）")
    void queuedRowsArePickedUpBySweep() {
        client.reset();
        client.push(DeepSeekClient.Reply.fail("交卷这一次先失败"));
        long a = submitStandard(U);
        forceStatus(a, "status='QUEUED', content=NULL, fail_reason=NULL, tries=0, started_at=NULL");

        // 这一轮会把库里所有排队的行都捡起来，所以先确认排队的只有我们这一行 ——
        // 不确认的话，那份"好正文"可能被喂给了别的用例留下的行，而本条判据会红得莫名其妙。
        assertEquals(1, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM exam_report WHERE status='QUEUED'", Integer.class),
                "夹具坏了：库里还有别的排队行");

        client.reset();
        client.push(DeepSeekClient.Reply.of(goodContent(), "deepseek-v4-flash"));
        assertTrue(reports.dispatchQueued() >= 1, "队列里的行一个都没被捡起来");
        assertEquals("READY", status(a), "捡起来之后没有真的生成");
    }

    // ================================================================ 接口

    @Test @Order(12)
    @DisplayName("★ 生成中（QUEUED / RUNNING）也要把 stats 返回，学员立刻看得到分数与错题")
    void statsAreReturnedWhileGenerating() {
        client.reset();
        client.push(DeepSeekClient.Reply.of(goodContent(), "deepseek-v4-flash"));
        long a = submitStandard(U);

        for (String st : List.of("QUEUED", "RUNNING")) {
            forceStatus(a, "status=?, content=NULL, model=NULL, fail_reason=NULL, started_at=NOW(3)", st);
            var body = examApi.report(String.valueOf(a), student(U));
            assertEquals(st, body.get("status"));
            var s = (ExamReportService.Stats) body.get("stats");
            assertNotNull(s, "★ " + st + " 状态下没有返回 stats：学员打开报告页看到的是一片空白");
            assertEquals(2, s.score());
            assertEquals(4, s.wrong().size());
            assertNull(body.get("content"));
            assertEquals(Boolean.FALSE, body.get("canRetry"), "还在生成就给了重试入口");
        }
    }

    @Test @Order(13)
    @DisplayName("★ 别人的报告：404，且与『不存在』是同一句话")
    void othersReportIsIndistinguishableFromMissing() {
        client.reset();
        client.push(DeepSeekClient.Reply.of(goodContent(), "deepseek-v4-flash"));
        long a = submitStandard(U);

        var mine = expect(404, "不存在", () -> examApi.report(String.valueOf(a), student(U_OTHER)));
        var ghost = expect(404, "不存在", () -> examApi.report("99999999", student(U_OTHER)));
        assertEquals(mine.getMessage(), ghost.getMessage(),
                "『不是你的』与『不存在』给了不同的回答 —— 这就是一个能枚举别人考试编号的探针");

        // 重试那一路同样不许漏
        expect(404, "不存在", () -> examApi.retryReport(String.valueOf(a), student(U_OTHER)));
        assertEquals("READY", status(a), "越权的重试把别人的报告退回队列了");

        // 没交卷的场次没有报告，但这是他自己的场次，所以给的是另一句话（不是 404）
        long open = exam.start(U, P_MAIN).attemptId();
        expect(409, "还没有交卷", () -> examApi.report(String.valueOf(open), student(U)));
    }

    @Test @Order(14)
    @DisplayName("★ 重新生成：只有 FAILED 能重试，同一场 5 分钟一次")
    void retryIsLimitedToFailedAndRateLimited() {
        client.reset();
        client.push(DeepSeekClient.Reply.fail("模型服务暂时调不通（超时或网络不通），请稍后重新生成"));
        long a = submitStandard(U);
        assertEquals("FAILED", status(a));
        client.reset();     // 计数归零：下面要断言"被限频的那一次一个调用都没发出去"

        // 刚失败就连点：限频拦下，且不许把状态弄乱
        expect(429, "后再试", () -> examApi.retryReport(String.valueOf(a), student(U)));
        assertEquals("FAILED", status(a), "被限频的重试把状态改掉了");
        assertEquals(0, client.calls.get(), "被限频的重试还是去调了模型");

        // 窗口过了：接受，并真的重生成一份
        forceStatus(a, "updated_at=DATE_SUB(NOW(3), INTERVAL 6 MINUTE)");
        client.push(DeepSeekClient.Reply.of(goodContent(), "deepseek-v4-flash"));
        examApi.retryReport(String.valueOf(a), student(U));
        assertEquals("READY", status(a), "重试之后没有真的生成");
        assertEquals(1, client.calls.get());
        assertEquals(0, ((Number) reportRow(a).get("tries")).intValue(),
                "手动重试要把 tries 清零，否则刚回队就被清扫判死，按了等于没按");

        // 已经生成好的不许再点（那只是白烧一次调用）
        forceStatus(a, "updated_at=DATE_SUB(NOW(3), INTERVAL 6 MINUTE)");
        expect(409, "已经生成好了", () -> examApi.retryReport(String.valueOf(a), student(U)));

        // 生成中的也不许点
        forceStatus(a, "status='RUNNING', updated_at=DATE_SUB(NOW(3), INTERVAL 6 MINUTE)");
        expect(409, "正在生成", () -> examApi.retryReport(String.valueOf(a), student(U)));
        forceStatus(a, "status='READY'");
    }

    // ================================================================ 不拖住交卷

    @Test @Order(15)
    @DisplayName("★ 交卷这条请求不被模型拖住：19 秒的外部调用在后台跑")
    void submitIsNotBlockedByTheModel() throws Exception {
        slowClient.reset();
        slowClient.sleepMs = 1500;      // 真实是 19.3 秒，这里 1.5 秒足够把差别拉开
        slowClient.push(DeepSeekClient.Reply.of(goodContent(), "deepseek-v4-flash"));

        long a = asyncExam.start(U_ASYNC, P_MAIN).attemptId();
        long t0 = System.nanoTime();
        var r = asyncExam.submit(U_ASYNC, a, standardAnswers());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(r.firstTime());
        assertTrue(ms < 800, "交卷被模型调用拖住了：" + ms + " ms —— "
                + "生产里那是 19 秒，学员会以为交卷失败并重复提交");

        // 后台照样把它做完
        String st = null;
        for (int i = 0; i < 100 && !"READY".equals(st); i++) {
            Thread.sleep(100);
            st = status(a);
        }
        assertEquals("READY", st, "后台线程池没有把报告生成出来");
        assertTrue(((String) reportRow(a).get("content")).contains("120:12"));
    }

    // ================================================================ 题被物理删掉之后

    /**
     * 整表原样存一份再删。<b>不按夹具重新拼一遍</b>：question 上以后再加一列，
     * 拼的那份就会漏掉它，而这条用例还是绿的 —— 直到后面某条判据莫名其妙地红。
     */
    static void backupQuestions() {
        jdbc.execute("DROP TABLE IF EXISTS `bak_question_rpt`");
        jdbc.execute("CREATE TABLE `bak_question_rpt` AS SELECT * FROM `question`");
    }

    static void restoreQuestions() {
        jdbc.execute("DELETE FROM `question`");
        jdbc.execute("INSERT INTO `question` SELECT * FROM `bak_question_rpt`");
        jdbc.execute("DROP TABLE IF EXISTS `bak_question_rpt`");
    }

    @Test @Order(16)
    @DisplayName("★ 题目被物理删除：分母不塌，那一行落到「题目已下线」，整卷重导也不会变成「2 / 0」")
    void deletedQuestionKeepsTheDenominator() {
        client.reset();
        client.push(DeepSeekClient.Reply.of(goodContent(), "deepseek-v4-flash"));
        long a = submitStandard(U);
        assertEquals(PAPER, reports.build(a).questionCount(), "夹具坏了：删之前分母就不是 6");

        backupQuestions();
        try {
            // ① 删掉一道他答错的题（706：第 3 讲 120:12）。
            // exam_answer 那 6 行一行没少，所以他看到的分母也一行都不许少。
            jdbc.update("DELETE FROM question WHERE id=706");
            var s = reports.build(a);
            assertEquals(PAPER, s.questionCount(),
                    "★ 分母跟着连接一起塌了 —— 一份已经生成好的报告会在题库变动之后自己变数");
            assertEquals(2, s.score(), "分数以 exam_attempt 那一列为准，不该跟着题库变");

            for (var group : List.of(s.byTopic(), s.byLesson(), s.byType(), s.byDifficulty())) {
                int sum = 0;
                for (var b : group) sum += b.total();
                assertEquals(PAPER, sum, "这一组的分母加起来不等于他实际考的题数：" + group);
                assertBucket(group, "题目已下线", 0, 1);
            }

            var gone = s.wrong().stream().filter(w -> w.questionId() == 706L).findFirst()
                    .orElseGet(() -> fail("被删那道题从错题清单里消失了 —— 他确实答错了它"));
            assertNull(gone.sourceAt(),
                    "★ 查不到题就查不到出处，凭空给一个时间点等于让他按一个不存在的位置去翻录像");
            assertNull(gone.sourceTsSec());
            assertEquals("A", gone.myAnswer(), "他的作答在答题明细里，不该跟着题一起没");
            assertTrue(gone.stem().contains("已从题库下线"),
                    "错题清单里那一行要明说它为什么没有题干：" + gone.stem());

            // ② 整卷重导（先 DELETE 再 INSERT，回填脚本的常见写法）：最坏的一档。
            // 旧写法在这里给出的是「得分 2 / 0」+ 四组全空 + 错题清单空，而接口照样 200。
            jdbc.update("DELETE FROM question WHERE paper_id=?", P_MAIN);
            var wiped = reports.build(a);
            assertEquals(PAPER, wiped.questionCount(),
                    "★ 整卷重导之后报告页显示的是「2 / 0」，接口还是 200、日志一个字都没有");
            assertEquals(1, wiped.byTopic().size(), "题全没了就只该剩这一组：" + wiped.byTopic());
            assertBucket(wiped.byTopic(), "题目已下线", 2, PAPER);
            assertEquals(4, wiped.wrong().size(), "错题清单整条空了 —— 他连自己错了几道都看不到");

            // 事实清单也要给得出来（喂给模型的那一份不能是一段空壳）
            String facts = ExamReportPrompt.factSheet(wiped);
            assertTrue(facts.contains("得分：2 / " + PAPER), facts);
            assertTrue(facts.contains("题目已下线"), facts);
        } finally {
            restoreQuestions();
        }

        var back = reports.build(a);
        assertEquals(PAPER, back.questionCount(), "夹具没还原干净");
        assertEquals("120:12", back.wrong().get(2).sourceAt(), "夹具没还原干净");
    }

    // ================================================================ 入队失败

    @Test @Order(17)
    @DisplayName("★ 入队失败：报告页给 FAILED + 人话理由 + 重试入口，而不是永远转圈")
    void failedEnqueueDoesNotSpinForever() {
        // 造一个必然失败的入队：stats 序列化不了 → writeStats 抛 IllegalStateException。
        // 生产里同一条路上还有：库层触发器拒绝、以及 exam_attempt.paper_id 可空而
        // exam_report.paper_id 是 NOT NULL（旧场次那一支）。共同点是全都不响。
        var brokenJson = new ObjectMapper() {
            @Override
            public String writeValueAsString(Object value) {
                throw new IllegalStateException("造出来的序列化故障");
            }
        };
        var broken = new ExamReportService(jdbc, brokenJson, client, Runnable::run);
        var brokenExam = new ExamService(jdbc, new TransactionTemplate(
                new DataSourceTransactionManager(ds)), mapper, broken);

        client.reset();
        long a = brokenExam.start(U, P_MAIN).attemptId();
        var sub = brokenExam.submit(U, a, standardAnswers());
        assertTrue(sub.firstTime(), "★ 报告入队失败把一次成功的交卷弄坏了");
        assertEquals(2, sub.score());
        assertEquals(0, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM exam_report WHERE attempt_id=?", Integer.class, a),
                "夹具坏了：这一条要的正是『一行都没插进去』");

        var v = broken.report(U, a);
        assertEquals("FAILED", v.status(), "★ 补队还是失败却回了 " + v.status()
                + "：学员按规格第五节轮询 3 秒 × 40 次之后，看到的仍然是『还在生成』");
        assertNotNull(v.failReason(), "永远转圈那一条最坏的地方就是它什么都不说");
        assertTrue(v.failReason().contains("没能排上队"), v.failReason());
        assertTrue(v.canRetry(), "界面上必须还剩一件他能做的事");
        assertNotNull(v.stats(), "事实层照常：分数与错题是客观的");
        assertEquals(2, v.stats().score());
        assertEquals(PAPER, v.stats().questionCount());
        assertEquals(0, client.calls.get(), "队都没排上，不该有任何模型调用");

        // 重试那一路同样不许静默：按钮按下去什么都不发生，学员看不出为什么
        var e = assertThrows(ApiException.class, () -> broken.retry(U, a));
        assertTrue(e.getMessage().contains("没能排上队"),
                "重试失败给的不是那句人话：" + e.getMessage());

        // 阴性对照：换回能用的序列化，同一场立刻排得上队并真的生成出来 ——
        // 少了它，上面几条也可能只是"这条路什么都跑不通"
        client.reset();
        client.push(DeepSeekClient.Reply.of(goodContent(), "deepseek-v4-flash"));
        var ok = reports.report(U, a);
        assertEquals("READY", ok.status(), "换回正常序列化之后仍然没有生成，这条用例证明不了任何事");
        assertEquals("READY", status(a));
    }

    // ================================================================ 假客户端

    /**
     * 脚本化的模型客户端。
     *
     * <p><b>脚本排空之后一律失败</b>，不是返回一段"随便什么正文"：
     * 后者会让"少排了一次回包"这种夹具错误静默地变成绿灯，
     * 而那正是这套判据最不该发生的事。
     */
    static final class ScriptedClient implements DeepSeekClient {
        final ConcurrentLinkedDeque<Reply> script = new ConcurrentLinkedDeque<>();
        final List<String> prompts = Collections.synchronizedList(new ArrayList<>());
        final List<String> systems = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger calls = new AtomicInteger();
        volatile long sleepMs = 0;

        void push(Reply r) { script.addLast(r); }

        void reset() {
            script.clear();
            prompts.clear();
            systems.clear();
            calls.set(0);
            sleepMs = 0;
        }

        @Override
        public Reply complete(String systemPrompt, String userPrompt) {
            calls.incrementAndGet();
            systems.add(systemPrompt);
            prompts.add(userPrompt);
            if (sleepMs > 0) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            Reply r = script.pollFirst();
            return r != null ? r : Reply.fail("用例没有为这一次调用排回包（脚本已排空）");
        }
    }
}
