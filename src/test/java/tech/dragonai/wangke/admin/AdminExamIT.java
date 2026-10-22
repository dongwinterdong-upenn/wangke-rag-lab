package tech.dragonai.wangke.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import tech.dragonai.wangke.ItDb;
import tech.dragonai.wangke.auth.AuthInterceptor;
import tech.dragonai.wangke.web.ApiException;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多套试卷的管理端：列卷 / 改规则 / 上下架 / 审题。跑在真实 MySQL 上（库名 {@code wangke_adminexam_it}）。
 *
 * <p>这条路径要守的四件事，每一件坏掉都<b>不会报错</b>：
 * <ol>
 *   <li><b>改规则不能改掉历史。</b>UPDATE 旧行在库层是一次完全合法的写入，后果是
 *       正在考试的人交不上卷、已判过分的老场次再查「当时及格线是多少」查到的是新值。
 *   <li><b>「题不够就开不了考」必须在学员点开考之前被看见。</b>这件事的唯一表现是
 *       学员点下去吃一句 409；后台不主动喊，就没有任何人会知道。
 *   <li><b>审核轨迹要留得住。</b>谁、什么时候、把哪道题从什么改成了什么 ——
 *       题目本身没有历史表，只有 audit_log 那一条。
 *   <li><b>这些接口非超管进不来。</b>题目列表带答案与解析，漏一个角色判定等于把答案公开。
 * </ol>
 *
 * <p>每个负例都<b>钉住失败原因</b>：只断言"抛了异常"的用例太容易因为别的原因红着，
 * 那样它绿的时候什么也没证明。每个「拒绝」都配一条<b>阴性对照</b>：
 * 只验拒绝的话，一条"什么都拒"的闸门同样全绿，而它等于把功能关掉。
 *
 * <h3>关于 exam_paper 这几张表从哪来</h3>
 * {@code exam_paper} 与那两个 {@code paper_id} 列属于 {@code db/migrations/20260903_多套试卷.sql}
 * 和它同步进 {@code db/schema.sql} 的那一份，归另一路改，本轮不动 {@code db/} 下任何东西。
 * 但这一组判据<b>不能等那一路落地</b>，所以 {@link #ensurePaperSchema} 先看库里有没有：
 * 有就直接用（那是权威版本），没有就按规格第一节在<b>自己这个临时库里</b>补出来。
 * 两条路都走完之后再做一次三项自检 —— 少了哪一样，报出来的是一句人话，
 * 而不是几十行"Unknown column 'paper_id'"。
 *
 * <p>兜底那份<b>只建一个触发器</b>（{@code trg_exam_config_paper}）：规格里另外几个
 * （exam_paper 的两个前置校验、attempt 与 certificate 上的新增分支）守的都是<b>这个控制器不写的列</b>
 * （access / grants_certificate / paper_id 与 config 对齐），在兜底里建起来一次也不会被触发，
 * 只会给出"覆盖到了"的假象。而 trg_exam_config_paper 正对着本控制器唯一的那条 INSERT ——
 * 少写 paper_id 它会当场炸。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AdminExamIT {

    /**
     * 库名必须是全新的一个。
     *
     * <p>{@link ItDb#freshDatabase} 第一句是 {@code DROP DATABASE IF EXISTS}：与别的 IT 撞名时，
     * 后启动的那个会把前一个正在用的库连根拔掉，前一个报出来的是「表不存在」——
     * 一句完全指不到根因、换台机器就复现不了的错。
     */
    static final String DB = "wangke_adminexam_it";

    static final long ADMIN = 1L;          // seed.sql 里的平台超管
    static final long STUDENT = 20L, AGENT = 21L;

    static final long P_MAIN = 1L;         // 结业综合卷：ON，题够，能开考
    static final long P_SHORT = 2L;        // 章节卷：OFF，已审核 3 道 < 卷面 5 题
    static final long P_OPEN = 3L;         // 开放卷：course_id 为 NULL，且一条规则都还没配
    static final long P_BULK = 4L;         // 题库分页用：120 道题，DRAFT 但一切就绪

    static DataSource ds;
    static JdbcTemplate jdbc;
    static AdminExamService exams;
    static AdminExamController api;

    /** 老题：paper_id 为 NULL。迁移之前入库的题就是这个形态，它不属于任何一张卷。 */
    static long orphanQuestionId;

    /**
     * 规格第一节的 DDL，抄来在临时库里兜底。只在 {@code db/schema.sql} 还没长出这些东西时跑。
     *
     * <p>{@code exam_config.course_id} 必须跟着改成允许 NULL：开放卷不挂课程，
     * 它的规则行 course_id 就是 NULL，不改这一列的话本文件里"给开放卷配规则"那条
     * 会以一句 not-null 约束错误收场，而根因在库结构不在代码。
     */
    static final String EXTRA_DDL = """
        USE `%s`;
        CREATE TABLE `exam_paper` (
          `id`                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
          `code`               VARCHAR(32)     NOT NULL,
          `title`              VARCHAR(128)    NOT NULL,
          `subtitle`           VARCHAR(255)    NULL,
          `course_id`          BIGINT UNSIGNED NULL   COMMENT '开放卷可以不挂课程',
          `access`             ENUM('ENROLLED','OPEN') NOT NULL,
          `grants_certificate` TINYINT(1)      NOT NULL DEFAULT 0,
          `status`             ENUM('DRAFT','ON','OFF') NOT NULL DEFAULT 'DRAFT',
          `sort_no`            INT             NOT NULL DEFAULT 0,
          `created_at`         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
          PRIMARY KEY (`id`),
          UNIQUE KEY `uk_paper_code` (`code`),
          KEY `idx_paper_list` (`status`,`sort_no`)
        ) ENGINE=InnoDB COMMENT='试卷（身份与准入；规则仍在 exam_config）';

        ALTER TABLE `exam_config`
          ADD COLUMN `paper_id` BIGINT UNSIGNED NULL AFTER `course_id`,
          MODIFY COLUMN `course_id` BIGINT UNSIGNED NULL,
          ADD KEY `idx_paper_rule` (`paper_id`,`effective_from`);

        ALTER TABLE `question`
          ADD COLUMN `paper_id` BIGINT UNSIGNED NULL AFTER `course_id`,
          ADD KEY `idx_paper_pool` (`paper_id`,`review_status`,`type`);

        DELIMITER $$
        CREATE TRIGGER `trg_exam_config_paper`
        BEFORE INSERT ON `exam_config` FOR EACH ROW
        BEGIN
          IF NEW.`paper_id` IS NULL AND NEW.`course_id` IS NULL THEN
            SIGNAL SQLSTATE '45000'
              SET MESSAGE_TEXT = '测评规则必须至少挂在一份试卷或一门课程上，两边都空的规则行谁也查不到';
          END IF;
        END$$
        DELIMITER ;
        """;

    /**
     * 库里已经有 exam_paper 就直接用（那是权威版本），没有就按规格补出来。
     *
     * <p>不能无条件跑兜底 DDL：迁移一落地，{@code CREATE TABLE} 会以
     * 「Table 'exam_paper' already exists」把整组用例炸掉 —— 而那是一句
     * 完全指不到"其实一切正常"的错。也不能无条件跳过：那一路还没落地时，
     * 报出来的会是几十行 Unknown column。
     */
    static void ensurePaperSchema() throws Exception {
        if (count("SELECT COUNT(*) FROM information_schema.tables"
                + " WHERE table_schema=? AND table_name='exam_paper'", DB) == 0)
            ItDb.runScript(EXTRA_DDL.formatted(DB));

        // 三项自检。缺哪一样都要在这里以一句人话停下来，而不是等到某条 SQL 报列不存在
        assertEquals(1, count("SELECT COUNT(*) FROM information_schema.tables"
                            + " WHERE table_schema=? AND table_name='exam_paper'", DB),
                "库里没有 exam_paper：迁移那一路还没落地，而兜底 DDL 也没建起来");
        for (String[] col : new String[][]{{"exam_config", "paper_id"}, {"question", "paper_id"}})
            assertEquals(1, count("SELECT COUNT(*) FROM information_schema.columns"
                                + " WHERE table_schema=? AND table_name=? AND column_name=?",
                        DB, col[0], col[1]),
                    col[0] + " 上没有 " + col[1] + " 列：多套卷的抽题与规则都按它圈范围");
        assertEquals("YES", jdbc.queryForObject(
                "SELECT is_nullable FROM information_schema.columns WHERE table_schema=?"
              + " AND table_name='exam_config' AND column_name='course_id'", String.class, DB),
                "exam_config.course_id 还是 NOT NULL：开放卷的规则行 course_id 就是空的，"
                + "这一列不放宽的话给开放卷配规则会以一句 not-null 错误收场");
    }

    @BeforeAll
    static void bootstrap() throws Exception {
        ds = ItDb.freshDatabase(DB);
        jdbc = new JdbcTemplate(ds);
        ensurePaperSchema();
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        exams = new AdminExamService(jdbc);
        api = new AdminExamController(jdbc, exams, new AdminAudit(jdbc), tx, new ObjectMapper());

        jdbc.update("INSERT INTO sys_user (id,phone,role) VALUES (20,'13100000020','STUDENT'),"
                  + "(21,'13100000021','AGENT')");

        // ★ 先把 seed.sql 里那六张正式卷（id 1..6，code FDE_* / AIGC_OPEN）连同它们的规则清掉。
        // 那六行是给「新装的库能直接跑起来」用的：六张全 ON、题库一道题都没有。
        // 不清的话 ① id 1..4 与 uk_paper_code 当场撞，② 就算换个 id 段，这一组判据
        // 也会全程处在「六张已上架的卷都开不了考」的背景噪声里 —— brokenOnCount、
        // 列卷条数、未挂卷题目总数，没有一个数是干净的。
        // seed.sql 那段注释里写的就是这条路：集成测试要自己造卷，先删掉这六行。
        jdbc.update("DELETE FROM exam_config");
        jdbc.update("DELETE FROM exam_paper");

        jdbc.update("INSERT INTO exam_paper (id,code,title,subtitle,course_id,access,"
                  + "grants_certificate,status,sort_no) VALUES"
                  + " (1,'FDE_MAIN','结业综合测评','全课程',1,'ENROLLED',1,'ON',1),"
                  + " (2,'FDE_CH1','第一章 · 前端与 skill',NULL,1,'ENROLLED',0,'OFF',2),"
                  + " (3,'AIGC_OPEN','AIGC 内容生产能力测试',NULL,NULL,'OPEN',0,'DRAFT',9),"
                  + " (4,'FDE_CH2','第二章 · 后端与多模态',NULL,1,'ENROLLED',0,'DRAFT',3)");

        // 卷面题数刻意取小（5 而不是规格里的 50/100）：判据钉的是「已审核题数与卷面题数的关系」，
        // 不是那个常数。取小之后一次审题就能让旗子翻面，用例读起来是一条线而不是一堆循环。
        config(P_MAIN, 1L, 5, 4, 60, 3, 24, 80);
        config(P_SHORT, 1L, 5, 4, 35, 5, 2, 0);
        config(P_BULK, 1L, 100, 80, 60, 3, 24, 0);
        // P_OPEN 故意一条规则都不配：「还没配过规则」是上线过程中必然经过的状态，
        // 而它在后台的表现必须是一句话，不是一个 500。

        // 一条【按课程配、paper_id 为空】的老规则行 —— 迁移之前的存量就是这个形态。
        // 这里自己插，而不是指望 seed.sql 里恰好有一行长这样：判据钉在别人那个文件的
        // 内容上，那份文件一改（它这一轮就改过）这条断言要么静默失效、要么无端红着。
        jdbc.update("INSERT INTO exam_config (course_id,paper_id,question_count,pass_score,"
                  + "duration_min,shuffle_options,max_attempts,retry_cooldown_hours,"
                  + "require_progress_pct,effective_from)"
                  + " VALUES (1,NULL,100,90,45,1,3,24,80,'2025-01-01 00:00:00')");

        addQuestions(P_MAIN, "APPROVED", 5, "综合卷已审题");
        addQuestions(P_MAIN, "PENDING", 2, "综合卷待审题");
        addQuestions(P_SHORT, "APPROVED", 3, "章节卷已审题");
        addQuestions(P_SHORT, "PENDING", 3, "章节卷待审题");
        addQuestions(P_SHORT, "REJECTED", 1, "章节卷驳回题");
        addQuestions(P_BULK, "APPROVED", 100, "题库分页题");
        addQuestions(P_BULK, "PENDING", 15, "题库分页待审");
        addQuestions(P_BULK, "DRAFT", 5, "题库分页草稿");
        orphanQuestionId = addQuestions(null, "DRAFT", 1, "迁移之前的老题").get(0);

        // 夹具自检：下面每一条判据都建立在这些数上，夹具漂了的话用例会以
        // 「旗子不对」之类的方式红着，而根因是种子变了。这里先把它钉死。
        assertEquals(5, exams.bank(P_MAIN).approved(), "夹具：综合卷已审题数变了");
        assertEquals(3, exams.bank(P_SHORT).approved(), "夹具：章节卷已审题数变了");
        assertEquals(120, exams.bank(P_BULK).total(), "夹具：题库分页那张卷的题量变了");
        assertNull(exams.effectiveRules(P_OPEN), "夹具：开放卷本该一条规则都没配");
        // 上面那条老规则行 paper_id 为 NULL。它必须谁也取不到 ——
        // 取到了就说明 effectiveRules 漏了 paper_id 这个条件，
        // 表现是每张新卷都"继承"了老课程的 100 题 / 90 分。
        assertEquals(1, count("SELECT COUNT(*) FROM exam_config WHERE paper_id IS NULL"),
                "夹具：那条按课程配的老规则行不见了");
        // 六张 seed 卷必须已经清干净：漏了的话它们会挤进列卷响应，
        // 而它们题库全空、状态全 ON，会把 brokenOnCount 与列表条数一起带偏。
        assertEquals(4, count("SELECT COUNT(*) FROM exam_paper"),
                "夹具：seed.sql 那六张正式卷没清干净，这一组判据的每个数都会被它们带偏");
    }

    @AfterAll
    static void teardown() { ItDb.close(ds); }

    // ================================================================ 夹具工具

    static MockHttpServletRequest as(long userId, String role) {
        var r = new MockHttpServletRequest();
        r.setRequestURI("/api/admin/exam/papers");
        r.setAttribute(AuthInterceptor.ATTR_USER_ID, userId);
        r.setAttribute(AuthInterceptor.ATTR_ROLE, role);
        r.setRemoteAddr("10.9.9.9");
        return r;
    }

    static MockHttpServletRequest admin() { return as(ADMIN, "PLATFORM_ADMIN"); }

    static void config(long paperId, Long courseId, int qc, int pass, int dur,
                       int maxAttempts, int cooldown, int progress) {
        jdbc.update("INSERT INTO exam_config (paper_id,course_id,question_count,pass_score,"
                  + "duration_min,shuffle_options,max_attempts,retry_cooldown_hours,"
                  + "require_progress_pct,effective_from) VALUES (?,?,?,?,?,1,?,?,?,'2026-01-01 00:00:00')",
                paperId, courseId, qc, pass, dur, maxAttempts, cooldown, progress);
    }

    /** @return 新插入的题目 id，按插入顺序 */
    static List<Long> addQuestions(Long paperId, String status, int n, String stemPrefix) {
        List<Long> ids = new ArrayList<>();
        for (int i = 1; i <= n; i++)
            ids.add(AdminQuery.insert(jdbc,
                    "INSERT INTO question (course_id,paper_id,type,stem,options,answer,explanation,"
                  + "origin,review_status) VALUES (1,?,'SINGLE',?,"
                  + "'[{\"key\":\"A\",\"text\":\"甲\"},{\"key\":\"B\",\"text\":\"乙\"}]',"
                  + "'A',?,'AI_GENERATED',?)",
                    paperId, stemPrefix + " " + i, "解析：" + stemPrefix + " " + i, status));
        return ids;
    }

    static long count(String sql, Object... args) {
        Number n = jdbc.queryForObject(sql, Number.class, args);
        return n == null ? 0 : n.longValue();
    }

    static long num(Object v) { return ((Number) v).longValue(); }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> items(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("items");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> sub(Map<String, Object> m, String key) {
        return (Map<String, Object>) m.get(key);
    }

    /** 列卷响应里的某一张卷。 */
    static Map<String, Object> paperCard(long paperId) {
        for (var m : items(api.papers(admin())))
            if (num(m.get("paperId")) == paperId) return m;
        throw new AssertionError("列卷响应里没有 paperId=" + paperId + " 这张卷");
    }

    static Map<String, Object> put(long paperId, AdminExamController.PaperReq r) {
        return api.updatePaper(paperId, r, admin());
    }

    /** 嵌套写法（规则收在 rules 这一层里）。 */
    static AdminExamController.PaperReq req(String title, String subtitle, Integer sortNo,
                                            String status, AdminExamController.RulesReq rules,
                                            String reason) {
        return new AdminExamController.PaperReq(title, subtitle, sortNo, status, rules, reason,
                null, null, null, null, null, null, null);
    }

    /** 平铺写法 —— 管理端那一页真正发出去的形状，规则七项直接摆在顶层，没有 rules 这一层。 */
    static AdminExamController.PaperReq flatReq(String status, Integer qc, Integer pass,
                                                Integer dur, Boolean shuffle, Integer maxAttempts,
                                                Integer cooldown, Integer progress, String reason) {
        return new AdminExamController.PaperReq(null, null, null, status, null, reason,
                qc, pass, dur, shuffle, maxAttempts, cooldown, progress);
    }

    static AdminExamController.RulesReq rules(Integer qc, Integer pass, Integer dur,
                                              Boolean shuffle, Integer maxAttempts,
                                              Integer cooldown, Integer progress) {
        return new AdminExamController.RulesReq(qc, pass, dur, shuffle, maxAttempts,
                cooldown, progress);
    }

    static String status(long paperId) {
        return jdbc.queryForObject("SELECT status FROM exam_paper WHERE id=?", String.class, paperId);
    }

    static long audits(String action, long targetId) {
        return count("SELECT COUNT(*) FROM audit_log WHERE action=? AND target_type='exam_paper'"
                   + " AND target_id=?", action, String.valueOf(targetId));
    }

    /**
     * 造一个「读完还没写下去的那一刻，被另一个人插了一脚」的控制器。
     *
     * <p>{@code updatePaper} 是<b>先在事务外读</b>（状态、现行规则、题库数），<b>再进事务写</b>。
     * 要撞的就是这两步之间那个窗口。用两条真线程去撞是不可复现的 —— 时序对不上时用例随机绿，
     * 而一条随机绿的并发判据比没有还坏：它绿的时候什么也没证明。
     * 所以这里把「别人那一下」钉死在窗口正中间：换一只 {@link TransactionTemplate}，
     * 它在把事务开起来之前先跑一遍 interleave。撞的是同一个窗口，而且每次都撞得上。
     */
    static AdminExamController racedBy(Runnable interleave) {
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds)) {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                interleave.run();
                return super.execute(action);
            }
        };
        return new AdminExamController(jdbc, exams, new AdminAudit(jdbc), tx, new ObjectMapper());
    }

    // ================================================================ X0 没配过规则的卷也要改得动状态

    /**
     * 这条必须<b>跑在 X4 之前</b>：X4 会给 P_OPEN 配上规则，配完之后这条判据就什么也验不到了
     * —— 它验的正是「{@code exam_config} 里一行都没有」那个状态。
     */
    @Test @Order(0)
    @DisplayName("X0 ★ 一条规则都没配过的卷，改状态不许 500 —— 下架是止损按钮，任何时候都得点得动")
    void statusChangeWorksWhenPaperHasNoRules() {
        assertNull(exams.effectiveRules(P_OPEN),
                "前提没了：这条判据要的是「一条规则行都没有」的卷，它现在有规则了");
        long auditsBefore = audits("EXAM_PAPER_STATUS", P_OPEN);

        // ★ 上架闸拦不住这条路：闸只在 targetStatus=ON 时才跑，OFF / DRAFT 是直接撞进去的。
        // 而「没有规则的卷」恰恰是最该关得掉的那一种 —— 学员看得到卡片、点开考必被拒。
        var off = assertDoesNotThrow(
                () -> put(P_OPEN, req(null, null, null, "OFF", null, "先把它关掉")),
                "一张没配过规则的卷点下架就炸了。下架是出事时唯一的止损按钮，"
              + "它必须在任何状态下都点得动");
        assertEquals(Boolean.TRUE, off.get("changed"));
        assertEquals("OFF", status(P_OPEN), "接口回了成功，库里的状态却没有改");
        assertEquals(auditsBefore + 1, audits("EXAM_PAPER_STATUS", P_OPEN), "下架没有留下审计");

        // 后态快照里的 questionCount 是「当时那一版规则的卷面题数」。这张卷没有规则，
        // 它就该是 JSON null 而不是 0：0 会被事后读成「卷面 0 题」，那是另一件事。
        // 用 JSON_TYPE 而不是 IS NULL —— JSON 列里的 null 取出来是 JSON null 不是 SQL NULL，
        // 写成 IS NULL 的话这条断言恒不成立（键根本没写进去时反而也是 NULL，两种情况分不开）。
        assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action='EXAM_PAPER_STATUS'"
                            + " AND target_id=? AND JSON_TYPE(JSON_EXTRACT(after_json,"
                            + "'$.questionCount'))='NULL'", String.valueOf(P_OPEN)),
                "审计后态里的 questionCount 不是 null —— 没有规则的卷不该凭空长出一个卷面题数");

        // 改回 DRAFT：一是把夹具还原给后面的用例，二是再验一次另一个非 ON 的目标状态
        var back = assertDoesNotThrow(
                () -> put(P_OPEN, req(null, null, null, "DRAFT", null, "还原成草稿")),
                "改回草稿同样炸了：这条路和下架是同一段代码");
        assertEquals(Boolean.TRUE, back.get("changed"));
        assertEquals("DRAFT", status(P_OPEN));
    }

    // ================================================================ X1 权限

    @Test @Order(1)
    @DisplayName("X1 非超管一律 403，而且【什么都没写下去】——四个入口逐个验")
    void nonAdminIsRefused() {
        // 这里验的是方法体第一行那道 requireRole。路径级的那道是 AdminGuard 的事
        // （它按 /api/admin/** 挂拦截器），两道互补：方法这道保护"这个方法被挂到别的路径下"，
        // 路径那道保护"新方法忘了写这一行"。
        long configsBefore = count("SELECT COUNT(*) FROM exam_config");
        long auditsBefore = count("SELECT COUNT(*) FROM audit_log");
        String qStatusBefore = jdbc.queryForObject(
                "SELECT review_status FROM question WHERE id=?", String.class, orphanQuestionId);

        for (String role : new String[]{"STUDENT", "AGENT"}) {
            long uid = "STUDENT".equals(role) ? STUDENT : AGENT;
            var who = as(uid, role);

            var e1 = assertThrows(ApiException.class, () -> api.papers(who));
            assertEquals(403, e1.status(), role + " 列卷没有被拒：" + e1.getMessage());
            assertTrue(e1.getMessage().contains("没有权限"),
                    "拒绝理由不是权限（可能是被别的东西拦下的）：" + e1.getMessage());

            // ★ 题目列表带答案与解析。这一条漏掉等于把整套题的答案公开，
            // 而接口本身工作得好好的 —— 没有任何报错，只是谁都能调。
            var e2 = assertThrows(ApiException.class,
                    () -> api.questions(null, null, null, null, null, null, who));
            assertEquals(403, e2.status(), role + " 能列题：" + e2.getMessage());

            var e3 = assertThrows(ApiException.class, () -> api.updatePaper(P_MAIN,
                    req(null, null, null, "OFF",
                            rules(3, 1, 10, null, null, null, null), "越权改规则"), who));
            assertEquals(403, e3.status(), role + " 能改规则：" + e3.getMessage());

            var e4 = assertThrows(ApiException.class, () -> api.review(orphanQuestionId,
                    new AdminExamController.ReviewReq("APPROVED", "越权审题"), who));
            assertEquals(403, e4.status(), role + " 能审题：" + e4.getMessage());
        }

        // ★ 后半句才是重点：角色判定写在写库【之后】的话，接口照样回 403，
        // 而那一行已经写进去了 —— 一个"看起来被拒了"的越权成功。
        assertEquals(configsBefore, count("SELECT COUNT(*) FROM exam_config"),
                "被 403 拒了，却往 exam_config 里插了新规则");
        assertEquals("ON", status(P_MAIN), "被 403 拒了，卷的上下架状态却变了");
        assertEquals(qStatusBefore, jdbc.queryForObject(
                "SELECT review_status FROM question WHERE id=?", String.class, orphanQuestionId),
                "被 403 拒了，题目的审核状态却变了");
        assertEquals(auditsBefore, count("SELECT COUNT(*) FROM audit_log"),
                "越权请求写了审计：事后翻日志会看到一条根本没发生过的改动");
    }

    // ================================================================ X2 列卷与那面旗子

    @Test @Order(2)
    @DisplayName("X2 ★ 题量不足的那面旗子是真的：同一份响应里，够的卷 true、不够的卷 false 并写明差几道")
    void bankFlagIsPerPaperAndTruthful() {
        var body = api.papers(admin());
        assertEquals(4, items(body).size(), "列卷少了卷或多了卷");

        // --- 不够的那张 ---
        var shortCard = paperCard(P_SHORT);
        assertEquals(Boolean.FALSE, shortCard.get("enoughApproved"),
                "已审核 3 道、卷面 5 题，旗子却说够了 —— 学员点开考会被拒，而后台一路显示正常");
        assertEquals(2L, num(shortCard.get("shortBy")), "差几道算错了");
        assertEquals(Boolean.FALSE, shortCard.get("startable"));
        String blocker = String.valueOf(shortCard.get("blocker"));
        assertTrue(blocker.contains("3") && blocker.contains("5"),
                "blocker 没把「已审核几道 / 卷面几题」这两个数摆出来，"
                + "运营看不出该去审几道题：" + blocker);
        assertTrue(blocker.contains("开考"), "blocker 没说清后果是开不了考：" + blocker);
        assertEquals(3, num(sub(shortCard, "bank").get("approved")));
        assertEquals(7, num(sub(shortCard, "bank").get("total")));

        // --- 阴性对照：够的那张必须是 true。只验 false 的话，一个恒返回 false 的
        //     实现同样全绿，而它会把每一张卷都标红，运营从此不再看这个标记 ---
        var mainCard = paperCard(P_MAIN);
        assertEquals(Boolean.TRUE, mainCard.get("enoughApproved"),
                "已审核 5 道、卷面 5 题，旗子却说不够");
        assertEquals(0L, num(mainCard.get("shortBy")));
        assertNull(mainCard.get("blocker"), "一张真能开考的卷被报成开不了考");
        assertEquals(Boolean.TRUE, mainCard.get("startable"));

        // --- 每张卷各算各的。统计漏掉 paper_id 这个条件时（比如按 course_id 分组），
        //     两张同课程的卷会拿到同一个数：一张全绿、一张也全绿 ---
        assertNotEquals(sub(mainCard, "bank").get("approved"),
                sub(shortCard, "bank").get("approved"),
                "两张卷的已审核题数一模一样 —— 统计很可能没有按 paper_id 分开算");
        for (long pid : new long[]{P_MAIN, P_SHORT, P_BULK}) {
            var card = paperCard(pid);
            assertEquals(count("SELECT COUNT(*) FROM question WHERE paper_id=? AND review_status='APPROVED'", pid),
                    num(sub(card, "bank").get("approved")), "卷 " + pid + " 的已审核题数与库里对不上");
            assertEquals(count("SELECT COUNT(*) FROM question WHERE paper_id=?", pid),
                    num(sub(card, "bank").get("total")), "卷 " + pid + " 的题目总数与库里对不上");
        }

        // --- 开放卷：course_id 是 NULL。course 用 INNER JOIN 的话它会整张消失，
        //     而它恰恰是本轮唯一一张对所有注册学员开放的卷 ---
        var openCard = paperCard(P_OPEN);
        assertNull(openCard.get("courseId"), "开放卷不该挂课程");
        assertEquals("OPEN", openCard.get("access"));
        assertNull(openCard.get("rules"), "这张卷还没配过规则，却取到了规则 —— "
                + "多半是把 seed 里那条按课程配的老行当成了它的现行规则");
        assertTrue(String.valueOf(openCard.get("blocker")).contains("规则"),
                "没配规则这件事没被说出来：" + openCard.get("blocker"));

        // --- 未上架但一切就绪的卷，blocker 必须是 null ---
        // blocker 是「上架之后能不能开考」，上架闸复用它。把"未上架"也算进 blocker 的话，
        // 上架闸会把自己拒掉：你还没上架，所以不许上架。
        var bulkCard = paperCard(P_BULK);
        assertEquals("DRAFT", bulkCard.get("status"));
        assertEquals(Boolean.FALSE, bulkCard.get("visibleToStudents"));
        assertNull(bulkCard.get("blocker"),
                "一张题已备齐、只差上架的卷被报成开不了考 —— 上架闸复用这条判据，它会拒掉这次上架");

        // --- 已上架且开不了考的卷有几张：这是运营每天要看的那个数 ---
        assertEquals(0L, num(body.get("brokenOnCount")), "现在不该有已上架却开不了考的卷");

        // --- 未挂卷的老题不进任何一张卷的统计（它永远抽不到） ---
        long counted = 0;
        for (var m : items(body)) counted += num(sub(m, "bank").get("total"));
        assertEquals(count("SELECT COUNT(*) FROM question WHERE paper_id IS NOT NULL"), counted,
                "未挂卷的老题被算进了某张卷的题库统计 —— 那些题永远抽不到，"
                + "算进去会让「题够了」这个结论凭空多出几道");
    }

    // ================================================================ X3 改规则 = 插新行

    @Test @Order(3)
    @DisplayName("X3 ★ 改规则插新行、旧行逐列不动，且没给的那几项沿用旧值而不是打回建表默认")
    void rulesChangeInsertsNewRowAndKeepsOld() {
        var before = exams.effectiveRules(P_MAIN);
        long oldId = before.configId();
        var oldRow = jdbc.queryForMap("SELECT * FROM exam_config WHERE id=?", oldId);
        long rowsBefore = count("SELECT COUNT(*) FROM exam_config WHERE paper_id=?", P_MAIN);

        // 只改及格分。其余六项一个都不给 —— 这是运营最常做的那一次改动。
        var res = put(P_MAIN, req(null, null, null, null,
                rules(null, 3, null, null, null, null, null), "及格线按百分比下调"));
        assertEquals(Boolean.TRUE, res.get("changed"));
        long newId = num(res.get("configId"));
        assertNotEquals(oldId, newId,
                "改规则复用了同一行 —— 那是 UPDATE 旧行：正在考的人按 config_id 取到的规则中途变了，"
                + "交卷时会被触发器拒；已判过分的老场次再查「当时及格线」也查到新值");

        // ★ 旧行必须逐列不动。少查一列就等于没验那一列，所以这里比的是整行 Map。
        assertEquals(oldRow, jdbc.queryForMap("SELECT * FROM exam_config WHERE id=?", oldId),
                "旧规则行被改动了：按它开考的场次判分依据被静默改写");
        assertEquals(rowsBefore + 1, count("SELECT COUNT(*) FROM exam_config WHERE paper_id=?", P_MAIN),
                "规则历史没有多出一行");

        // 新行：没给的六项沿用旧值。悄悄补建表默认（100 题 / 90 分 / 45 分钟）的话，
        // 接口返回成功、页面上那几个数也"像个正常的数"，没有一处会说它们被改过。
        var now = exams.effectiveRules(P_MAIN);
        assertEquals(newId, now.configId(), "新插的那一行没有成为现行规则");
        assertEquals(3, now.passScore());
        assertEquals(before.questionCount(), now.questionCount(), "没给的卷面题数被改了");
        assertEquals(60, now.durationMin(),
                "没给的考试时长被打回了建表默认值 45 —— 学员的考试时间凭空少了 15 分钟，"
                + "而没有任何一处会提这件事");
        assertEquals(before.maxAttempts(), now.maxAttempts(), "没给的可考次数被改了");
        assertEquals(before.retryCooldownHours(), now.retryCooldownHours(), "没给的冷却被改了");
        assertEquals(before.requireProgressPct(), now.requireProgressPct(), "没给的进度门槛被改了");
        assertEquals(before.shuffleOptions(), now.shuffleOptions(), "没给的选项乱序被改了");

        // 新行要带上 paper_id 与 course_id：paper_id 空了的话 trg_exam_config_paper 会拒；
        // course_id 空了的话按课程查规则的老路径（兼容期还在用）取不到它。
        var newRow = jdbc.queryForMap("SELECT paper_id, course_id, effective_from FROM exam_config WHERE id=?", newId);
        assertEquals(P_MAIN, num(newRow.get("paper_id")));
        assertEquals(1L, num(newRow.get("course_id")));
        assertNotNull(newRow.get("effective_from"));

        assertEquals(1, audits("EXAM_PAPER_RULES", P_MAIN), "改规则没有留下审计");
        // 用 JSON_EXTRACT 而不是 LIKE '%"passScore":4%'：before_json/after_json 是 JSON 列，
        // MySQL 存进去时会重排并在冒号后补空格，按原文拼出来的 LIKE 恒不匹配 ——
        // 那种断言永远红（或者写成 count==0 时永远绿），两种都不检查任何东西。
        assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action='EXAM_PAPER_RULES'"
                            + " AND target_id=? AND JSON_EXTRACT(before_json,'$.passScore')=4"
                            + " AND JSON_EXTRACT(after_json,'$.passScore')=3",
                String.valueOf(P_MAIN)),
                "审计里没有「从 4 分改成 3 分」这件事 —— 规则表只增不改，"
                + "谁把它改成现在这样只有审计说得出来");
        // 没给的那几项也要进后态快照：只记改动的那一项的话，事后翻审计只知道
        // 「有人动过及格线」，答不上来那次存下去的整套规则长什么样
        assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action='EXAM_PAPER_RULES'"
                            + " AND target_id=? AND JSON_EXTRACT(after_json,'$.durationMin')=60",
                String.valueOf(P_MAIN)),
                "审计的后态只记了改动的那一项，没记这一版规则的全貌");

        // 双击保护：同一套规则再提交一次不该多一行历史。
        // 多出来的那一行事后翻历史时看起来像一次真的改动。
        long rowsNow = count("SELECT COUNT(*) FROM exam_config WHERE paper_id=?", P_MAIN);
        var again = assertDoesNotThrow(() -> put(P_MAIN, req(
                null, null, null, null, rules(null, 3, null, null, null, null, null), "手抖点了两下")),
                "重复提交抛了异常。这不是「拦住了重复改规则」，是把一次成功显示成失败");
        assertEquals(Boolean.FALSE, again.get("changed"));
        assertEquals(rowsNow, count("SELECT COUNT(*) FROM exam_config WHERE paper_id=?", P_MAIN),
                "同一套规则重提多写了一版历史");
    }

    // ================================================================ X4 第一次配规则

    @Test @Order(4)
    @DisplayName("X4 开放卷第一次配规则：七项必须填全；配完 course_id 仍是 NULL，不许抄课程卷的")
    void firstTimeRulesMustBeComplete() {
        // 没有可沿用的旧行时缺项必须当场拒。悄悄补默认值的话，存下去的是一套
        // 没有人拍板过的规则（100 题 / 90 分 / 45 分钟），而它看起来完全正常。
        var e = assertThrows(ApiException.class, () -> put(P_OPEN,
                req(null, null, null, null,
                        rules(100, 80, null, true, 3, 24, 0), "只填了一半")));
        assertEquals(400, e.status(), e.getMessage());
        assertTrue(e.getMessage().contains("考试时长"),
                "拒绝没有指出缺的是哪一项，运营只能一项项试：" + e.getMessage());
        assertEquals(0, count("SELECT COUNT(*) FROM exam_config WHERE paper_id=?", P_OPEN),
                "被拒之后仍然插了一行规则");

        var res = put(P_OPEN, req(null, null, null, null,
                rules(100, 80, 60, true, 3, 24, 0), "开放卷首次配规则"));
        assertEquals(Boolean.TRUE, res.get("changed"));
        long cfg = num(res.get("configId"));
        var row = jdbc.queryForMap("SELECT paper_id, course_id FROM exam_config WHERE id=?", cfg);
        assertEquals(P_OPEN, num(row.get("paper_id")));
        // ★ course_id 取自 exam_paper 而不是抄旧行/抄别的卷：抄来的话这张开放卷的规则
        // 永远挂在一门它并不属于的课程上，而抽题按 paper_id 走，谁也不会报错。
        assertNull(row.get("course_id"),
                "开放卷的规则行被写上了 course_id —— 这张卷不属于任何课程");

        // 及格线的百分比口径（100 题 80 分）要由后端算好带出来，前端自己除一遍就是第二套口径
        assertEquals(80L, num(sub(res, "rules").get("passPct")));

        // 配完规则之后，blocker 从「没有规则」变成「题不够」——
        // 旗子跟着库里的状态走，不是一句写死的话
        var card = paperCard(P_OPEN);
        assertEquals(Boolean.FALSE, card.get("enoughApproved"));
        assertEquals(100L, num(card.get("shortBy")), "这张卷一道题都没有，该差整整 100 道");
        assertTrue(String.valueOf(card.get("blocker")).contains("0 道"),
                "blocker 还停在「没有规则」，没跟着改：" + card.get("blocker"));
    }

    // ================================================================ X5 上架闸

    @Test @Order(5)
    @DisplayName("X5 ★ 题不够不许上架（拒得住且没写库），审够之后同一个请求就成功 —— 旗子真的在管事")
    void publishGateFollowsTheFlag() {
        long auditsBefore = audits("EXAM_PAPER_STATUS", P_SHORT);
        var e = assertThrows(ApiException.class, () -> put(P_SHORT,
                req(null, null, null, "ON", null, "先上了再说")));
        assertEquals(409, e.status(), e.getMessage());
        assertTrue(e.getMessage().contains("还差 2 道"),
                "拒绝没说还差几道，运营不知道该审几道题：" + e.getMessage());
        assertTrue(e.getMessage().contains("点下去"),
                "拒绝没说清后果（学员看得到卡片、点下去被拒）：" + e.getMessage());
        // ★ 这句 409 是管理端那个对话框唯一能照抄的文案：闸在后端，界面上那颗按钮该不该
        // 禁掉、禁掉之后提示什么，都得跟这句对得上。所以它必须自带两条出路 ——
        // 只说「不行」的拒绝，运营能做的只有找研发。
        assertTrue(e.getMessage().contains("先把题审够") && e.getMessage().contains("卷面题数"),
                "拒绝没给出路（把题审够 / 把卷面题数改到已审核的题数以内），"
              + "运营只知道被拒了，不知道下一步该干什么：" + e.getMessage());
        assertEquals("OFF", status(P_SHORT), "被拒之后卷还是上架了");
        assertEquals(auditsBefore, audits("EXAM_PAPER_STATUS", P_SHORT),
                "一次被拒的上架写了审计：库里没上架，而审计说上了");

        // 审够两道 —— 走的是真接口，不是直接 UPDATE：这一步同时验了审题接口能把旗子推翻面
        var pending = jdbc.queryForList(
                "SELECT id FROM question WHERE paper_id=? AND review_status='PENDING' ORDER BY id LIMIT 2",
                P_SHORT);
        for (var q : pending)
            api.review(num(q.get("id")), new AdminExamController.ReviewReq("APPROVED", "审过了"), admin());

        var card = paperCard(P_SHORT);
        assertEquals(Boolean.TRUE, card.get("enoughApproved"), "审够了旗子却没翻面");
        assertEquals(0L, num(card.get("shortBy")));

        // 阴性对照：同一个请求，这次必须成功。只验拒绝的话，一条"什么都拒"的闸门同样全绿，
        // 而它等于把上架这个功能关掉了。
        var ok = assertDoesNotThrow(() -> put(P_SHORT, req(
                null, null, null, "ON", null, "题审够了，上架")));
        assertEquals(Boolean.TRUE, ok.get("changed"));
        assertEquals("ON", status(P_SHORT));
        assertEquals(auditsBefore + 1, audits("EXAM_PAPER_STATUS", P_SHORT), "上架没有留下审计");

        // 下架永远不拦：出事时要能立刻把它关掉，闸门只在"往外放"的方向上生效
        var off = assertDoesNotThrow(() -> put(P_SHORT, req(
                null, null, null, "OFF", null, "先撤下来")));
        assertEquals(Boolean.TRUE, off.get("changed"));
        assertEquals("OFF", status(P_SHORT));

        // ★ 闸的第二个入口：一张【已经上架】的卷把卷面题数往上抬，抬到超过已审核题数。
        // 这一下不改状态，但后果和上架一模一样 —— 卷还挂在学员的列表里，而它从这一刻起
        // 开不了考。管理端那个改规则的对话框要按这句话决定禁不禁用，所以文案也钉住。
        long mainRules = count("SELECT COUNT(*) FROM exam_config WHERE paper_id=?", P_MAIN);
        var raise = assertThrows(ApiException.class, () -> put(P_MAIN,
                req(null, null, null, null,
                        rules(6, null, null, null, null, null, null), "卷面加到 6 题")));
        assertEquals(409, raise.status(), raise.getMessage());
        assertTrue(raise.getMessage().contains("现在是上架状态"),
                "已上架的卷改成一套开不了考的规则，拒绝里却没说它现在是上架的："
              + raise.getMessage());
        assertTrue(raise.getMessage().contains("下架"),
                "没告诉运营「真要先改规则就把它下架」这条出路：" + raise.getMessage());
        assertEquals("ON", status(P_MAIN), "被拒的那次把卷的状态也改了");
        assertEquals(mainRules, count("SELECT COUNT(*) FROM exam_config WHERE paper_id=?", P_MAIN),
                "被 409 拒了，规则历史里却多出了一行");
    }

    // ================================================================ X6 审题与审计

    @Test @Order(6)
    @DisplayName("X6 审核状态流转：写了 reviewed_by/at、留了审计、把已上架的卷弄坏时当场喊出来")
    void reviewFlowWritesTrailAndWarns() {
        long qid = jdbc.queryForObject(
                "SELECT id FROM question WHERE paper_id=? AND review_status='APPROVED' ORDER BY id LIMIT 1",
                Long.class, P_MAIN);

        // --- APPROVED → REJECTED：止损动作，任何时候都得成功 ---
        var res = api.review(qid, new AdminExamController.ReviewReq("REJECTED", "选项 B 也对"), admin());
        assertEquals(Boolean.TRUE, res.get("changed"));
        assertEquals("REJECTED", res.get("status"));
        var row = jdbc.queryForMap("SELECT review_status, reviewed_by, reviewed_at FROM question WHERE id=?", qid);
        assertEquals("REJECTED", row.get("review_status"));
        assertEquals(ADMIN, num(row.get("reviewed_by")), "没记下是谁审的");
        assertNotNull(row.get("reviewed_at"), "没记下什么时候审的");

        // ★ 这一下把一张【已上架】的卷打到题量不足。拦是不能拦的（拦下来错题就留在卷上），
        // 但必须当场喊出来，否则这张卷从此开不了考而没有任何人知道。
        assertEquals(4, num(sub(res, "bank").get("approved")));
        assertEquals(Boolean.FALSE, res.get("enoughApproved"));
        String warning = String.valueOf(res.get("warning"));
        assertTrue(warning.contains("上架") && warning.contains("开不了考"),
                "把已上架的卷弄到题量不足，接口却一声不吭：" + res.get("warning"));
        assertEquals(1L, num(api.papers(admin()).get("brokenOnCount")),
                "列卷没把「已上架却开不了考」这张数出来");

        // 审计：题目本身没有历史表，谁把它改成现在这样只有 audit_log 说得出来
        assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action='EXAM_QUESTION_REVIEW'"
                            + " AND target_type='question' AND target_id=?", String.valueOf(qid)),
                "审题没有留下审计");
        var log = jdbc.queryForMap("SELECT before_json, after_json, operator_id FROM audit_log"
                + " WHERE action='EXAM_QUESTION_REVIEW' AND target_id=?", String.valueOf(qid));
        assertTrue(String.valueOf(log.get("before_json")).contains("APPROVED"),
                "审计的前态没记原来是什么：" + log.get("before_json"));
        assertTrue(String.valueOf(log.get("after_json")).contains("REJECTED"),
                "审计的后态没记改成了什么：" + log.get("after_json"));
        assertEquals(ADMIN, num(log.get("operator_id")));

        // --- 状态白名单：DRAFT 是入库初值，不许人工设回去 ---
        long auditsBefore = count("SELECT COUNT(*) FROM audit_log WHERE action='EXAM_QUESTION_REVIEW'");
        var bad = assertThrows(ApiException.class,
                () -> api.review(qid, new AdminExamController.ReviewReq("DRAFT", "退回重写"), admin()));
        assertEquals(400, bad.status(), bad.getMessage());
        assertTrue(bad.getMessage().contains("APPROVED") && bad.getMessage().contains("PENDING"),
                "拒绝没有把可用的三个状态列出来：" + bad.getMessage());
        assertEquals("REJECTED", jdbc.queryForObject(
                "SELECT review_status FROM question WHERE id=?", String.class, qid),
                "被拒的那次仍然改了题目的状态");
        assertEquals(auditsBefore, count(
                "SELECT COUNT(*) FROM audit_log WHERE action='EXAM_QUESTION_REVIEW'"),
                "一次被拒的审题写了审计");

        var nope = assertThrows(ApiException.class, () -> api.review(999_999L,
                new AdminExamController.ReviewReq("APPROVED", null), admin()));
        assertEquals(404, nope.status(), nope.getMessage());

        // --- 双击：同一个结果再点一次回 changed:false，不回错误框 ---
        var twice = assertDoesNotThrow(() -> api.review(qid,
                new AdminExamController.ReviewReq("REJECTED", "再点一次"), admin()));
        assertEquals(Boolean.FALSE, twice.get("changed"));

        // --- 未挂卷的题：审了也不会出现在任何卷面上，这件事必须说出来 ---
        var orphan = api.review(orphanQuestionId,
                new AdminExamController.ReviewReq("APPROVED", "老题先审着"), admin());
        assertEquals(Boolean.TRUE, orphan.get("changed"));
        assertNull(orphan.get("paperId"));
        assertTrue(String.valueOf(orphan.get("warning")).contains("试卷"),
                "审了一道没挂卷的题，接口没说它不会出现在任何卷面上：" + orphan.get("warning"));

        // --- 修回去：阴性对照，警告要跟着消失（恒定报警等于没有报警） ---
        var fixed = api.review(qid, new AdminExamController.ReviewReq("APPROVED", "复核后仍然成立"), admin());
        assertEquals(5, num(sub(fixed, "bank").get("approved")));
        assertNull(fixed.get("warning"), "题补回去了，警告还在响");
        assertEquals(0L, num(api.papers(admin()).get("brokenOnCount")));
    }

    // ================================================================ X7 题目列表

    @Test @Order(7)
    @DisplayName("X7 题库分页：翻页不重不漏、size 有上限、按卷隔离、%% 不会变成「返回全部」")
    void questionListPagesAndFilters() {
        var p1 = api.questions(String.valueOf(P_BULK), null, null, null, 1, 20, admin());
        assertEquals(120L, num(p1.get("total")), "总数不对");
        assertEquals(20, items(p1).size());

        // 翻页必须是全序。只按 review_status 排（没有 id 兜底）的话，同状态行之间的
        // 相对顺序由存储引擎决定，表现是同一道题在两页里都出现、另一道谁也看不到。
        Set<Long> seen = new HashSet<>();
        for (int page = 1; page <= 6; page++) {
            var body = api.questions(String.valueOf(P_BULK), null, null, null, page, 20, admin());
            for (var q : items(body))
                assertTrue(seen.add(num(q.get("questionId"))),
                        "第 " + page + " 页出现了前面已经出过的题：翻页有重叠，中间必然也有漏掉的");
        }
        assertEquals(120, seen.size(), "六页 20 条没能覆盖全部 120 道题");

        // 上限：不设的话 ?size=1000000 就是一个把库拖死的合法请求。
        // 断言的是生效的 size 而不是返回条数 —— 这张卷只有 120 道题，
        // 按条数断言的话，一个完全不限速的实现同样会返回 120 条，用例照绿。
        var big = api.questions(String.valueOf(P_BULK), null, null, null, 1, 100_000, admin());
        assertEquals(AdminQuery.MAX_SIZE, ((Number) big.get("size")).intValue(),
                "size 没有被压到上限");

        // 按卷隔离：漏了 paper_id 这个条件的话，审 A 卷的人看到的是全库的题
        for (var q : items(p1))
            assertEquals(P_BULK, num(q.get("paperId")), "列表里混进了别的卷的题");

        // 状态与类型筛选
        var pend = api.questions(String.valueOf(P_BULK), "PENDING", null, null, 1, 200, admin());
        assertEquals(15L, num(pend.get("total")));
        for (var q : items(pend)) assertEquals("PENDING", q.get("reviewStatus"));

        // ★ 未挂卷的老题要有入口。没有它，迁移之后那批题在后台整片消失，看起来像被删了
        var none = api.questions("none", null, null, null, 1, 20, admin());
        assertEquals(1L, num(none.get("total")));
        assertNull(items(none).get(0).get("paperId"));
        for (var q : items(p1))
            assertNotEquals(orphanQuestionId, num(q.get("questionId")),
                    "未挂卷的题混进了某张卷的题目列表");

        // ★ LIKE 通配符必须转义。参数化查询防的是注入，防不了这个：
        // % 在 LIKE 的语义里本来就是通配符，是合法值不是攻击载荷。
        var wild = api.questions(null, null, null, "%", 1, 20, admin());
        assertEquals(0L, num(wild.get("total")),
                "关键词填一个 % 就把全库题目搜了出来 —— LIKE 的通配符没有转义");
        var hit = api.questions(String.valueOf(P_BULK), null, null, "题库分页题 7", 1, 20, admin());
        assertTrue(num(hit.get("total")) >= 1, "正常关键词搜不到东西，上一条断言什么也没证明");

        // 超管这条路必须带答案与解析 —— 审题的人看不到答案没法审。
        // （学员那一侧相反：ExamService 下发考卷时连 answer 列都不查，ExamIT 有对应判据）
        var one = items(p1).get(0);
        assertNotNull(one.get("answer"), "题目列表没带答案，审题的人无从审起");
        assertNotNull(one.get("explanation"), "题目列表没带解析");
        assertTrue(one.get("options") instanceof List,
                "选项原样按字符串透出去了，前端还要自己 JSON.parse 一次：" + one.get("options"));

        var badPaper = assertThrows(ApiException.class,
                () -> api.questions("abc", null, null, null, 1, 20, admin()));
        assertEquals(400, badPaper.status(), badPaper.getMessage());
        var badStatus = assertThrows(ApiException.class,
                () -> api.questions(null, "WHATEVER", null, null, 1, 20, admin()));
        assertEquals(400, badStatus.status(), badStatus.getMessage());
    }

    // ================================================================ X8 规则取值闸

    @Test @Order(8)
    @DisplayName("X8 及格线高于卷面题量当场拒（满分也不及格），时长 0 与次数 0 同样拒；合法值照常存得下")
    void ruleValuesAreGuarded() {
        long rowsBefore = count("SELECT COUNT(*) FROM exam_config WHERE paper_id=?", P_MAIN);

        // 这一条最阴：考试全程正常，每个人都考完、判分、不过，而且每人烧掉一次补考次数。
        // 库层 trg_exam_attempt_ins 只管「分数不超过卷面题量」，拦不住及格线本身设得比卷面高。
        var e = assertThrows(ApiException.class, () -> put(P_MAIN,
                req(null, null, null, null,
                        rules(5, 9, null, null, null, null, null), "及格线打错了")));
        assertEquals(400, e.status(), e.getMessage());
        assertTrue(e.getMessage().contains("满分也不及格"),
                "拒绝理由不是「满分也不及格」，这个负例可能是被别的东西拦下的：" + e.getMessage());

        var dur = assertThrows(ApiException.class, () -> put(P_MAIN,
                req(null, null, null, null,
                        rules(null, null, 0, null, null, null, null), "时长填 0")));
        assertEquals(400, dur.status(), dur.getMessage());

        var att = assertThrows(ApiException.class, () -> put(P_MAIN,
                req(null, null, null, null,
                        rules(null, null, null, null, 0, null, null), "次数填 0")));
        assertEquals(400, att.status(), att.getMessage());

        var pct = assertThrows(ApiException.class, () -> put(P_MAIN,
                req(null, null, null, null,
                        rules(null, null, null, null, null, null, 800), "进度门槛填 800")));
        assertEquals(400, pct.status(), pct.getMessage());

        assertEquals(rowsBefore, count("SELECT COUNT(*) FROM exam_config WHERE paper_id=?", P_MAIN),
                "被拒的几次里有一次把规则写进去了");

        // 阴性对照：边界上的合法值（及格线正好等于卷面题量）必须存得下。
        // 只验拒绝的话，一条"凡是改及格线都拒"的闸门同样全绿。
        var ok = assertDoesNotThrow(() -> put(P_MAIN, req(
                null, null, null, null, rules(5, 5, null, null, null, null, null), "满分才算过")));
        assertEquals(Boolean.TRUE, ok.get("changed"));
        assertEquals(5, exams.effectiveRules(P_MAIN).passScore());
        assertEquals(rowsBefore + 1, count("SELECT COUNT(*) FROM exam_config WHERE paper_id=?", P_MAIN));

        // 身份字段改得动，而且不碰规则历史（改标题不该多出一版规则）
        long rulesRows = count("SELECT COUNT(*) FROM exam_config WHERE paper_id=?", P_MAIN);
        var edit = put(P_MAIN, req("结业综合测评（2026 版）", "", null,
                null, null, "改个标题"));
        assertEquals(Boolean.TRUE, edit.get("changed"));
        assertEquals("结业综合测评（2026 版）", jdbc.queryForObject(
                "SELECT title FROM exam_paper WHERE id=?", String.class, P_MAIN));
        assertNull(jdbc.queryForObject("SELECT subtitle FROM exam_paper WHERE id=?", String.class, P_MAIN),
                "副标题传空串是「清空」，它没被清掉就再也删不掉了");
        assertEquals(rulesRows, count("SELECT COUNT(*) FROM exam_config WHERE paper_id=?", P_MAIN),
                "只改了标题，却往规则历史里插了一版");
        assertEquals(1, audits("EXAM_PAPER_EDIT", P_MAIN), "改标题没有留下审计");

        var noop = put(P_MAIN, req("结业综合测评（2026 版）", null, null,
                "ON", null, "什么都没改"));
        assertEquals(Boolean.FALSE, noop.get("changed"));

        var missing = assertThrows(ApiException.class, () -> put(999_999L,
                req(null, null, null, "ON", null, "不存在的卷")));
        assertEquals(404, missing.status(), missing.getMessage());
    }

    // ================================================================ X9 请求体的两种写法

    @Test @Order(9)
    @DisplayName("X9 ★ 规则七项平铺在顶层（管理端那一页真正发的形状）也要真的改到规则，不能被当成「什么都没给」")
    void flatRuleBodyAlsoChangesRules() throws Exception {
        var before = exams.effectiveRules(P_MAIN);
        long rows = count("SELECT COUNT(*) FROM exam_config WHERE paper_id=?", P_MAIN);

        var res = put(P_MAIN, flatReq(null, before.questionCount(), before.passScore(), 30,
                true, before.maxAttempts(), before.retryCooldownHours(),
                before.requireProgressPct(), "把时长改到 30 分钟"));
        // ★ 这条判据守的是一种最安静的失败：请求体的形状对不上时，Jackson 把它反序列化成
        // 一个全 null 的 record（fail-on-unknown-properties 默认关着），接口回 200、
        // 响应里写着「没有任何改动」，而运营看到的是保存成功。
        assertEquals(Boolean.TRUE, res.get("changed"),
                "平铺写法被当成了「什么都没给」：接口回 200、页面显示成功，而规则一个字没改");
        assertEquals(30, exams.effectiveRules(P_MAIN).durationMin(), "时长没有改到 30");
        assertEquals(rows + 1, count("SELECT COUNT(*) FROM exam_config WHERE paper_id=?", P_MAIN),
                "平铺写法没有插出新的一版规则");

        // 阴性对照：同样平铺、同样的值再发一次要认出"没改动"。
        // 少了这条，一个"平铺一律当成有改动"的实现同样绿，而它每保存一次就多一版假历史。
        var again = put(P_MAIN, flatReq(null, before.questionCount(), before.passScore(), 30,
                true, before.maxAttempts(), before.retryCooldownHours(),
                before.requireProgressPct(), "再发一次"));
        assertEquals(Boolean.FALSE, again.get("changed"));

        // 两种写法混着发：嵌套那一层说了算，其余用平铺的补
        var mixed = put(P_MAIN, new AdminExamController.PaperReq(null, null, null, null,
                rules(null, null, 45, null, null, null, null), "混着发",
                null, null, 90, null, null, null, 0));
        assertEquals(Boolean.TRUE, mixed.get("changed"));
        assertEquals(45, exams.effectiveRules(P_MAIN).durationMin(),
                "两种写法都给了时长，取的不是嵌套那一层");
        assertEquals(0, exams.effectiveRules(P_MAIN).requireProgressPct(),
                "只有平铺给了的那一项没被采纳");

        // ★ 连着线的那一段也要验：管理端发的 shuffleOptions 是【数字 0/1】不是布尔。
        // 上面几条走的是 Java 构造函数，绕过了 Jackson —— 而真实请求是一串 JSON，
        // 类型对不上时同样是"安静地变成 null"。这里直接把那串 JSON 喂给反序列化器。
        // （用裸 ObjectMapper：application.yml 里那两项 jackson 配置只管序列化与时区，
        //   不改反序列化行为，所以这里与线上是同一套解析。）
        var mapper = new ObjectMapper();
        var wire = mapper.readValue(
                "{\"questionCount\":5,\"passScore\":4,\"durationMin\":60,\"maxAttempts\":3,"
              + "\"retryCooldownHours\":24,\"requireProgressPct\":80,\"shuffleOptions\":1,"
              + "\"reason\":\"页面上点的保存\"}", AdminExamController.PaperReq.class);
        var wireRules = wire.mergedRules();
        assertNotNull(wireRules, "管理端那一页发出去的请求体解析完是「没给规则」");
        assertEquals(Boolean.TRUE, wireRules.shuffleOptions(),
                "shuffleOptions 送的是数字 1，没被认成 true —— 选项乱序会被静默关掉，"
                + "而关掉之后每个人拿到的选项顺序一样，答案传起来毫无障碍");
        assertEquals(60, wireRules.durationMin());

        // 阴性对照：只发 status 的请求体（下架按钮发的就是这个）必须解析成"不改规则"
        var onlyStatus = mapper.readValue("{\"status\":\"OFF\"}", AdminExamController.PaperReq.class);
        assertNull(onlyStatus.mergedRules(),
                "只发 status 的请求被认成了「要改规则」—— 一次下架会顺手插一版规则出来");
        assertEquals("OFF", onlyStatus.status());
    }

    // ================================================================ X10 改状态的并发裁决

    @Test @Order(10)
    @DisplayName("X10 ★ 改状态是 compare-and-set：读到的旧状态已经不成立时回 409，不静默盖过去")
    void statusWriteIsCompareAndSet() {
        assertEquals("OFF", status(P_SHORT), "夹具：这条判据要的起点是一张 OFF 的卷");
        long auditsBefore = audits("EXAM_PAPER_STATUS", P_SHORT);

        // 「另一个人」在本次请求读完状态、还没写下去的那一刻把它上了架。
        // 无条件 UPDATE 的话我这一下会把他那一下静默盖掉，而两边界面都显示成功；
        // 更麻烦的是审计前态记的仍是我读到的 OFF —— 一次根本没发生过的 OFF→DRAFT。
        var raced = racedBy(() ->
                jdbc.update("UPDATE exam_paper SET status='ON' WHERE id=?", P_SHORT));
        var e = assertThrows(ApiException.class,
                () -> raced.updatePaper(P_SHORT,
                        req(null, null, null, "DRAFT", null, "撤回成草稿"), admin()),
                "读到的旧状态已经不成立了，这次改动却还是写了下去");
        assertEquals(409, e.status(), e.getMessage());
        assertTrue(e.getMessage().contains("刚被另一个人改过"),
                "拒绝理由不是并发（这个负例可能是被别的东西拦下的）：" + e.getMessage());

        assertEquals("ON", status(P_SHORT), "409 之后我这一次的状态还是写进去了");
        assertEquals(auditsBefore, audits("EXAM_PAPER_STATUS", P_SHORT),
                "一次没生效的状态改动写了审计 —— exam_paper 除了 audit_log 没有别的历史，"
              + "事后翻日志会看到一次根本没发生过的流转");

        // 同一个请求里的身份改动必须跟着一起回滚。半成功的话运营看到的是一句
        // 「你这次没有生效」，而标题其实已经改掉了 —— 他不会再去改回来。
        String titleBefore = jdbc.queryForObject(
                "SELECT title FROM exam_paper WHERE id=?", String.class, P_SHORT);
        long editsBefore = audits("EXAM_PAPER_EDIT", P_SHORT);
        var raced2 = racedBy(() ->
                jdbc.update("UPDATE exam_paper SET status='OFF' WHERE id=?", P_SHORT));
        var e2 = assertThrows(ApiException.class, () -> raced2.updatePaper(P_SHORT,
                req("第一章 · 顺手改的名", null, null, "DRAFT", null, "改名 + 撤回"), admin()));
        assertEquals(409, e2.status(), e2.getMessage());
        assertEquals(titleBefore, jdbc.queryForObject(
                "SELECT title FROM exam_paper WHERE id=?", String.class, P_SHORT),
                "状态那一步 409 了，同一个请求里的标题改动却留了下来");
        assertEquals(editsBefore, audits("EXAM_PAPER_EDIT", P_SHORT),
                "整个请求都回滚了，改标题的审计却留了下来");

        // 阴性对照：没人来撞的时候，同一个请求必须成功。
        // 只验 409 的话，一条「改状态一律 409」的实现同样全绿，而它等于把上下架关掉了。
        assertEquals("OFF", status(P_SHORT), "夹具：上一步那个「别人」该把它改成了 OFF");
        var ok = assertDoesNotThrow(() -> put(P_SHORT,
                req(null, null, null, "DRAFT", null, "没人来撞，正常撤回成草稿")));
        assertEquals(Boolean.TRUE, ok.get("changed"));
        assertEquals("DRAFT", status(P_SHORT));
        assertEquals(auditsBefore + 1, audits("EXAM_PAPER_STATUS", P_SHORT),
                "一次正常的状态改动没有留下审计");
    }
}
