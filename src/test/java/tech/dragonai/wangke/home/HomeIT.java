package tech.dragonai.wangke.home;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.support.TransactionTemplate;
import tech.dragonai.wangke.ItDb;
import tech.dragonai.wangke.auth.AuthInterceptor;
import tech.dragonai.wangke.exam.CertificateService;
import tech.dragonai.wangke.exam.ExamService;
import tech.dragonai.wangke.learn.CourseService;
import tech.dragonai.wangke.learn.Entitlements;
import tech.dragonai.wangke.support.DongmaClient;
import tech.dragonai.wangke.support.FaqSearch;
import tech.dragonai.wangke.support.QaService;
import tech.dragonai.wangke.web.ApiException;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 学习中心导览页，跑在真实 MySQL 上。
 *
 * <h3>这一页的失败方式是「安静地说错话」</h3>
 * 导览页不产生任何写操作，所以它坏掉的时候不会报错、不会留下脏数据 ——
 * 它只是把一句错话摆在学员打开 App 的第一屏上：说"没有等回复的问题"（其实有 101 条）、
 * 说"已学 50%"（其实是 1%）、说"可以参加测评"（点进去被拒）。
 * 所以这里的判据几乎全是<b>两处说法必须一致</b>，而不是"接口返回了 200"。
 *
 * <h3>取不到与是零，必须分得开</h3>
 * 单独有一条用例把答疑那一片打成故障，断言它是 {@code null} 而不是 0 ——
 * 一个"0 条待回复"的角标读起来是"没事要办"，而那正是故障时最不该说的话。
 *
 * <h3>测评区块是按卷列的（2026-09-03）</h3>
 * 一条 = 一份卷，不是一门课。夹具因此自己造卷 + 造规则，并且<b>先把 seed 带的卷全部下架</b>
 * （做法照抄 ExamIT）：不这么做的话，这些用例的绿与红取决于 seed 当天装了几份卷、
 * 其中有没有对全员开放的那张，而 seed 是另一个人在改的文件。
 *
 * <p>判据一律按 <b>paperId</b> 找条目、按 paperId 问测评接口。按 courseId 找是这一轮最容易
 * 踩的坑：两者同为 long，传错编译零警告，比的是另一份卷的状态，<b>而且大概率照样绿</b>。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HomeIT {

    static final String DB = "wangke_home_it";

    /** 有测评规则的那门（seed 里就有：需 80% 进度、最多 3 次）。 */
    static final long C_MAIN = 1L;
    /** 5 分钟的短课，没有配测评规则。 */
    static final long C_SHORT = 2L;
    /** 10 小时的长课。 */
    static final long C_LONG = 3L;
    /** 已到期的长课。 */
    static final long C_GONE = 4L;

    static final int SHORT_SEC = 300, LONG_SEC = 36000, GONE_SEC = 72000;

    /**
     * 这一轮的主角：卷。编号从 601 起，避开 seed 里那六份（1..6）——
     * 撞上的话夹具会插不进去，而失败信息只是一句"主键重复"，看不出是谁跟谁撞了。
     */
    /** 主课的综合卷：要 80% 进度、最多 3 次。U_MIX 一秒没看，所以它是 BLOCKED。 */
    static final long P_MAIN = 601;
    /** 短课的卷：<b>故意不配规则</b> —— 导览页要如实说"暂时没有测评"，而不是把它藏起来。 */
    static final long P_SHORT = 602;
    /** 长课的卷：同样要 80% 进度，U_MIX 一秒没看。 */
    static final long P_LONG = 603;
    /** 已到期那门课的卷：可见但不能考，卡上写的是续期。 */
    static final long P_GONE = 604;
    /** 开放卷：不挂课程，任何注册学员（含一门课都没买的 U_NEW）都该看见并能考。 */
    static final long P_OPEN = 605;
    /** U_MANY 那 21 门课各一份卷，编号 700 + 序号。 */
    static final long P_MANY_BASE = 700;

    static final int MAIN_ATTEMPTS = 3, MAIN_REQUIRE_PCT = 80;

    static final long U_NEW = 30, U_MIX = 31, U_MANY = 32, U_QA = 33, U_OTHER = 34;

    static DataSource ds;
    static JdbcTemplate jdbc;
    static ObjectMapper mapper;
    static ExamService exam;
    static QaService qa;
    static CertificateService certs;
    static HomeService home;
    static HomeController api;

    @BeforeAll
    static void bootstrap() throws Exception {
        ds = ItDb.freshDatabase(DB);
        jdbc = new JdbcTemplate(ds);
        mapper = new ObjectMapper().findAndRegisterModules();
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));

        exam = new ExamService(jdbc, tx, mapper);
        certs = new CertificateService(jdbc, mapper);
        qa = new QaService(jdbc, tx, new FaqSearch(jdbc), new DongmaClient("http://127.0.0.1:1", "", 1), 20);
        home = new HomeService(new CourseService(jdbc, new Entitlements(jdbc)), exam, qa, certs);
        api = new HomeController(home);

        for (long u : new long[]{U_NEW, U_MIX, U_MANY, U_QA, U_OTHER})
            jdbc.update("INSERT INTO sys_user (id,phone,role) VALUES (?,?,'STUDENT')",
                    u, "134000000" + u);

        // seed 带的卷（生产要上的那 6 份）全部下架，理由见类注释：这套用例只对自己造的卷负责，
        // 不该随 seed.sql 的改动时绿时红。表还空着时这一句是空转，所以补不补 seed 都跑得通。
        // 位置必须在自己造卷【之前】：放在后面会把自己刚插的卷一起下架 ——
        // 而那不会报错，只表现为"列表里一份卷都没有"。
        jdbc.update("UPDATE exam_paper SET status='OFF'");

        jdbc.update("INSERT INTO course (id,title,status) VALUES (?,'五分钟短课','ON')", C_SHORT);
        jdbc.update("INSERT INTO course (id,title,status) VALUES (?,'十小时长课','ON')", C_LONG);
        jdbc.update("INSERT INTO course (id,title,status) VALUES (?,'已到期的课','ON')", C_GONE);

        onLesson(C_MAIN, 11, SHORT_SEC);
        onLesson(C_SHORT, 12, SHORT_SEC);
        onLesson(C_LONG, 13, LONG_SEC);
        onLesson(C_GONE, 14, GONE_SEC);

        // U_MIX：主课与短课学完，长课一秒没看，另有一门已到期的长课。
        enroll(U_MIX, C_MAIN, true);
        enroll(U_MIX, C_SHORT, true);
        enroll(U_MIX, C_LONG, true);
        enroll(U_MIX, C_GONE, false);
        watched(U_MIX, C_SHORT, 12, SHORT_SEC);
        watched(U_MIX, C_GONE, 14, GONE_SEC);

        // U_MANY：21 门课各一份卷，用来看上限截断说不说实话。
        for (long c = 100; c <= 120; c++) {
            jdbc.update("INSERT INTO course (id,title,status) VALUES (?,?,'ON')", c, "第 " + c + " 门");
            enroll(U_MANY, c, true);
            paper(P_MANY_BASE + (c - 100), "HOME_MANY_" + c, "第 " + c + " 门的卷", c, "ENROLLED", 0, 1);
        }

        // U_QA：101 张工单 —— 比 myTickets 的 LIMIT 100 多一张。
        for (int k = 0; k < 101; k++)
            jdbc.update("INSERT INTO qa_ticket (user_id,course_id,title,status) VALUES (?,?,?,'ESCALATED')",
                    U_QA, C_MAIN, "问题 " + k);
        enroll(U_QA, C_MAIN, true);

        // U_OTHER 的工单不该出现在任何人的导览里。
        enroll(U_OTHER, C_MAIN, true);
        for (int k = 0; k < 7; k++)
            jdbc.update("INSERT INTO qa_ticket (user_id,course_id,title,status) VALUES (?,?,?,'HUMAN_ANSWERED')",
                    U_OTHER, C_MAIN, "别人的问题 " + k);

        // 四门课各一份卷 + 一份开放卷。四张卡刚好覆盖导览页要说的四种话：
        //   BLOCKED（进度不够）/ UNAVAILABLE（没配规则）/ BLOCKED（权益到期）/ READY。
        paper(P_MAIN, "HOME_MAIN", "结业综合测评", C_MAIN, "ENROLLED", 1, 1);
        paper(P_SHORT, "HOME_SHORT", "短课测评", C_SHORT, "ENROLLED", 0, 2);
        paper(P_LONG, "HOME_LONG", "长课测评", C_LONG, "ENROLLED", 0, 3);
        paper(P_GONE, "HOME_GONE", "已到期那门课的测评", C_GONE, "ENROLLED", 0, 4);
        paper(P_OPEN, "HOME_OPEN", "AIGC 开放测评", null, "OPEN", 0, 9);

        config(P_MAIN, C_MAIN, 10, 8, 45, MAIN_ATTEMPTS, 24, MAIN_REQUIRE_PCT);
        // P_SHORT 故意不配规则 —— 那是一条要测的状态，不是漏了。
        config(P_LONG, C_LONG, 10, 8, 45, 3, 24, MAIN_REQUIRE_PCT);
        config(P_GONE, C_GONE, 10, 8, 45, 3, 24, 0);
        // 开放卷不挂课程，规则里的 course_id 也是空的；没有进度门槛，所以谁都能考
        config(P_OPEN, null, 5, 4, 30, 3, 0, 0);
    }

    @AfterAll
    static void teardown() { ItDb.close(ds); }

    /* ================================================================ 空态 */

    @Test @Order(1)
    void 一门课都没买的新学员看得见开放卷看不见别人的卷() {
        var o = overview(U_NEW);
        assertEquals(0, num(sub(o, "courses"), "total"));
        assertEquals(0, num(sub(o, "courses"), "progressPct"));
        // 测评区块不再是从"我的课程"推出来的：开放卷对所有注册学员开放，
        // 而"我的测评"是他唯一能找到它的地方 —— 从课程推的话这里永远是空的。
        var mine = items(o, "assess");
        assertEquals(1, mine.size(), "没买课的学员看到了不该看到的卷：" + mine);
        assertEquals(P_OPEN, ((Number) mine.get(0).get("paperId")).longValue());
        assertEquals("READY", mine.get(0).get("state"), "开放卷对他就该是能考的");
        assertNull(mine.get(0).get("courseId"), "开放卷不挂课程");
        assertEquals(1, num(sub(o, "assess"), "readyCount"));
        // 三片都必须是"有数据且是零"，不是 null —— null 的语义是"取不到"，
        // 新学员的空不是取不到。
        assertNotNull(o.get("qa"));
        assertNotNull(o.get("cert"));
        assertEquals(0, num(sub(o, "qa"), "total"));
        assertEquals(0, num(sub(o, "cert"), "total"));
    }

    /* ================================================================ 课程合计 */

    @Test @Order(2)
    void 合计进度按秒加权而不是把各门课的百分比取平均() {
        // 第一个数据点：短课 300 秒看完、主课 300 秒没看、长课 36000 秒没看。
        //   按秒 = 300/36600 = 0%   按门取平均 = (100+0+0)/3 = 33%
        var c = sub(overview(U_MIX), "courses");
        assertEquals(0, num(c, "progressPct"), "按秒算是 0%，取平均会是 33%");
        assertEquals(300, num(c, "watchedSeconds"));
        assertEquals(SHORT_SEC + SHORT_SEC + LONG_SEC, num(c, "totalSeconds"));

        // 第二个数据点。只测第一个的话，一个恒返回 0 的实现照样绿 ——
        // 而"已学永远是 0%"正是这一屏最容易出、最不容易被发现的坏法。
        //   按秒 = 21900/36600 = 59%   按门取平均 = (100+60+0)/3 = 53%
        watched(U_MIX, C_LONG, 13, 21600);
        try {
            assertEquals(59, num(sub(overview(U_MIX), "courses"), "progressPct"),
                    "按秒算是 59%，取平均会是 53%，恒零的实现会是 0%");
        } finally {
            jdbc.update("DELETE FROM learn_progress WHERE user_id=? AND course_id=?", U_MIX, C_LONG);
        }
    }

    @Test @Order(3)
    void 已过期的课不进合计进度的分母() {
        var c = sub(overview(U_MIX), "courses");
        // C_GONE 有 72000 秒且全看完了。它一旦进了分母（或分子），上一条那个 0% 会变成 66%。
        assertEquals(SHORT_SEC + SHORT_SEC + LONG_SEC, num(c, "totalSeconds"),
                "已到期的 72000 秒不该进分母");
        assertEquals(4, num(c, "total"), "但它仍然要被列出来，学员要能看见自己买过");
        assertEquals(3, num(c, "active"));
        assertEquals(1, num(c, "expired"));
    }

    /* ================================================================ 测评一致性 */

    /**
     * 导览页逐份卷说的测评状态，必须与测评接口对<b>同一份卷</b>说的一致。
     *
     * <p>问的是 {@code exam.status(userId, paperId)} —— 第二个参数是卷号不是课程号。
     * 两者同为 long，传错编译零警告：拿课程号当卷号去查，比的是另一份卷的状态，
     * 而且大概率照样绿。这条判据要是那么写，它就以"绿"的形态废掉了。
     */
    @Test @Order(4)
    void 导览页逐份卷说的测评状态与测评接口一致() {
        boolean sawOpenPaper = false, sawEntitlementBlock = false;
        var mine = items(overview(U_MIX), "assess");
        assertFalse(mine.isEmpty(), "夹具坏了：一份卷都没有，这条判据什么都证明不了");
        for (var it : mine) {
            long paperId = ((Number) it.get("paperId")).longValue();
            // 开放卷没有 courseId（全局 non_null 下这个键整个消失）：先判空再取，
            // 否则这一屏——学员打开 App 的第一屏——会 NPE。
            Long courseId = it.get("courseId") == null
                    ? null : ((Number) it.get("courseId")).longValue();
            if (courseId == null) {
                sawOpenPaper = true;
                assertEquals("OPEN", it.get("access"), "不挂课程的卷只能是开放卷");
            }
            ExamService.Status s;
            try {
                s = exam.status(U_MIX, paperId);
            } catch (ApiException e) {
                // 403 = 权益不在（卡可见但不能考）；其余 = 这份卷本身不可用（例如没配规则）
                if (e.status() == 403) sawEntitlementBlock = true;
                assertEquals(e.status() == 403 ? "BLOCKED" : "UNAVAILABLE", it.get("state"),
                        "卷 " + paperId + "：测评接口拒绝，导览页却说 " + it.get("state"));
                assertEquals(e.getMessage(), it.get("reason"), "原因必须原句转述，不许另编");
                continue;
            }
            String expect = s.passedAttemptId() != null ? "PASSED"
                    : s.inProgressAttemptId() != null ? "IN_PROGRESS"
                    : s.canStart() ? "READY" : "BLOCKED";
            assertEquals(expect, it.get("state"), "卷 " + paperId + " 两处说法不一致");
            assertEquals(s.blockedReason(), it.get("reason"), "卷 " + paperId + " 的原因被改写了");
        }
        // 两条阴性对照：夹具里必须真的有"没有 courseId 的卡"与"被权益拦下的卡"，
        // 否则上面那两条最容易错的分支一次都没被走到，这条用例通过时什么也没证明。
        assertTrue(sawOpenPaper, "夹具里没有开放卷：courseId 为空那条路径没被走到");
        assertTrue(sawEntitlementBlock, "夹具里没有权益到期的卷：403 那条路径没被走到");
    }

    @Test @Order(5)
    void 进度不够时导览页给的是测评接口那一整句而不是自己编的话() {
        var it = assessOf(U_MIX, P_MAIN);
        assertEquals("BLOCKED", it.get("state"));
        String reason = (String) it.get("reason");
        assertEquals(exam.status(U_MIX, P_MAIN).blockedReason(), reason);
        // 钉住这句话真的含着两个数：只断言"非空"的话，改成一句笼统的
        // "暂不能参加测评"照样绿，而学员就再也不知道自己差多少。
        assertTrue(reason.contains("80%"), "要说清门槛是多少：" + reason);
        assertTrue(reason.contains("0%"), "也要说清他现在多少：" + reason);
        assertEquals(0, num(it, "progressPct"));
        assertEquals(MAIN_REQUIRE_PCT, num(it, "requireProgressPct"));
    }

    /**
     * 权益到期的卷<b>留在列表里</b>，卡上写的是续期。
     *
     * <p>整张卷从界面上消失是这一轮改坏过一次的地方：到期学员看到的是"我的卷不见了"，
     * 而平台想让他做的事（续期）从此没有任何地方会说 —— 而且这件事不报错、没有日志。
     */
    @Test @Order(6)
    void 权益到期的卷留在列表里并且说的是续期() {
        var it = assessOf(U_MIX, P_GONE);
        assertEquals("BLOCKED", it.get("state"), "到期的卷要留着（可见但不能考），不是整张消失");
        String reason = (String) it.get("reason");
        assertTrue(reason.contains("续期"), "学员要做的事是续期，不是继续学：" + reason);
        // 给学员看的句子里不许出现库里的枚举：他读不懂，也不知道自己该做什么
        assertFalse(reason.contains("EXPIRED") || reason.contains("SUSPENDED"),
                "把库里的枚举原样拼给学员看了：" + reason);
        // 卡还要有用：题数时长这些照常带出来，否则它就是一张只剩一句话的废卡
        assertNotNull(it.get("questionCount"), "可见但不能考的卡仍然要有卷面信息");
        // 反证：这份卷直接问测评接口确实是被拒的 —— 上面那条不是因为恰好能考才绿的
        var e = assertThrows(ApiException.class, () -> exam.status(U_MIX, P_GONE));
        assertEquals(403, e.status());
        assertEquals(reason, e.getMessage(), "卡上写的与接口拒绝的不是同一句话");
    }

    @Test @Order(7)
    void 没配测评规则的卷如实说暂时没有测评() {
        var it = assessOf(U_MIX, P_SHORT);
        assertEquals("UNAVAILABLE", it.get("state"));
        assertTrue(((String) it.get("reason")).contains("尚未配置测评规则"), (String) it.get("reason"));
        assertNull(it.get("progressPct"), "问不到状态就不该编出一个进度来");
    }

    @Test @Order(8)
    void 可考份数只数真的能考的那几份() {
        var a = sub(overview(U_MIX), "assess");
        // 此刻只有开放卷能考：综合卷与长课卷卡在进度上、短课卷没配规则、到期那门课的卷不能考
        assertEquals(1, num(a, "readyCount"));
        assertEquals("BLOCKED", assessOf(U_MIX, P_MAIN).get("state"));
        // 让主课学满，它应该从 BLOCKED 变成 READY，计数跟着 +1。
        // 不做这一步的话，一个恒返回同一个数的实现也能让上一行绿。
        watched(U_MIX, C_MAIN, 11, SHORT_SEC);
        try {
            var a2 = sub(overview(U_MIX), "assess");
            assertEquals(2, num(a2, "readyCount"));
            assertEquals("READY", assessOf(U_MIX, P_MAIN).get("state"));
            assertEquals(MAIN_ATTEMPTS, num(assessOf(U_MIX, P_MAIN), "attemptsLeft"));
        } finally {
            jdbc.update("DELETE FROM learn_progress WHERE user_id=? AND course_id=?", U_MIX, C_MAIN);
        }
    }

    @Test @Order(9)
    void 试卷超过上限时如实报出没列的份数() {
        var a = sub(overview(U_MANY), "assess");
        assertEquals(20, items(overview(U_MANY), "assess").size());
        // 他看得见 22 份：自己那 21 门课各一份，加上对所有人开放的那一份
        assertEquals(2, num(a, "skippedCount"),
                "没列的两份要说出来 —— 悄悄截断的界面上，'0 份可以考'是错的且看不出来");
    }

    /**
     * 一份卷的脏数据只该影响那一张卡。
     *
     * <p>不这么兜的话它会一路抛到 section()，整个测评模块降级成"暂时取不到" ——
     * 界面上的表现是<b>另外四份本来看得见的卷一起消失了</b>，而学员唯一能看到的
     * 只有一句"暂时取不到"。
     *
     * <p>故障是<b>在取数那一层</b>造的：给 ExamService 一个只在查某一份卷的场次时抛异常的
     * JdbcTemplate。改成"覆写 ExamService 的某个方法"是不行的 —— 导览页现在只调
     * {@code papers()} 一个入口，而逐卡兜底恰恰写在它里面，覆写它等于把被测的东西换掉。
     * 这里的注入点依赖那句 SQL 的写法，所以下面必须断言"那张卡真的坏了"：
     * 哪天 SQL 改了、注入不再命中，这条用例会当场红，而不是变成一条什么都不测的绿判据。
     */
    @Test @Order(10)
    void 一份卷的脏数据不该让别的卷从界面上消失() {
        // 先记下好的时候每份卷各是什么状态，等下逐张比。
        Map<Long, Object> before = new LinkedHashMap<>();
        for (var it : items(overview(U_MIX), "assess"))
            before.put(((Number) it.get("paperId")).longValue(), it.get("state"));
        assertTrue(before.containsKey(P_LONG), "夹具坏了：要弄坏的那份卷本来就不在列表里");

        var brokenJdbc = new JdbcTemplate(ds) {
            @Override
            public List<Map<String, Object>> queryForList(String sql, Object... args) {
                // 只让"长课那份卷"的场次查询炸，别的照常 —— 模拟单份卷的数据/查询出问题
                if (sql.contains("FROM exam_attempt") && args.length > 1
                        && Long.valueOf(P_LONG).equals(args[1]))
                    throw new IllegalStateException("这份卷的数据坏了");
                return super.queryForList(sql, args);
            }
        };
        var broken = new ExamService(brokenJdbc,
                new TransactionTemplate(new DataSourceTransactionManager(ds)), mapper);
        var h = new HomeService(new CourseService(jdbc, new Entitlements(jdbc)), broken, qa, certs);
        var o = h.overview(U_MIX);

        assertNotNull(o.get("assess"), "一份卷坏了不该把整个测评模块打没");
        @SuppressWarnings("unchecked")
        var items = (List<Map<String, Object>>) sub(o, "assess").get("items");
        assertEquals(before.size(), items.size(), "试卷一份都不许少");
        for (var it : items) {
            long id = ((Number) it.get("paperId")).longValue();
            if (id == P_LONG) {
                assertEquals("UNAVAILABLE", it.get("state"),
                        "注入的故障没打中：这条用例已经什么都不测了");
                assertTrue(((String) it.get("reason")).contains("暂时取不到"), (String) it.get("reason"));
            } else {
                assertEquals(before.get(id), it.get("state"), "试卷 " + id + " 被别人的故障带坏了");
            }
        }
    }

    /* ================================================================ 答疑与证书 */

    @Test @Order(11)
    void 工单计数不受列表上限影响() {
        var q = sub(overview(U_QA), "qa");
        assertEquals(101, num(q, "waiting"),
                "拿 myTickets(LIMIT 100) 数出来会是 100 —— 差的那一条没有任何症状");
        assertEquals(101, num(q, "total"));
        assertEquals(0, num(q, "humanAnswered"));
    }

    @Test @Order(12)
    void 别人的工单不进我的导览() {
        assertEquals(0, num(sub(overview(U_MIX), "qa"), "total"));
        // 反证：U_OTHER 自己看得见那 7 条，所以上一行不是因为查询恒空才绿的。
        assertEquals(7, num(sub(overview(U_OTHER), "qa"), "humanAnswered"));
    }

    /* ================================================================ 降级 */

    @Test @Order(13)
    void 某一片取不到时那一片是null而不是零() {
        var broken = new QaService(jdbc, new TransactionTemplate(new DataSourceTransactionManager(ds)),
                new FaqSearch(jdbc), new DongmaClient("http://127.0.0.1:1", "", 1), 20) {
            @Override public Map<String, Integer> myTicketCounts(long userId) {
                throw new IllegalStateException("答疑库炸了");
            }
        };
        var degraded = new HomeService(new CourseService(jdbc, new Entitlements(jdbc)), exam, broken, certs);
        var o = degraded.overview(U_QA);

        assertNull(o.get("qa"), "取不到必须是 null；返回 0 会被读成「没有待回复的问题」");
        // 其余三片照常 —— 一片坏掉不该把整页拖黑。
        assertNotNull(o.get("courses"));
        assertNotNull(o.get("assess"));
        assertNotNull(o.get("cert"));
        assertEquals(1, num(sub(o, "courses"), "total"));
    }

    /**
     * 顶层这五个键是前端逐字读的。
     *
     * <p>改名会以最难看的方式失败：后端把 assess 改叫 assessment 之后接口照常 200，
     * 前端读到 undefined，于是测评那一格<b>永远</b>显示"状态暂时取不到" ——
     * 一个不报错、不留日志、看起来像"后端偶尔抽风"的常驻故障。
     */
    @Test @Order(14)
    void 顶层键名就是前端读的那几个() throws Exception {
        var req = new MockHttpServletRequest();
        req.setAttribute(AuthInterceptor.ATTR_USER_ID, U_MIX);
        req.setAttribute(AuthInterceptor.ATTR_ROLE, "STUDENT");
        JsonNode tree = mapper.readTree(mapper.writeValueAsString(api.overview(req)));

        List<String> keys = new ArrayList<>();
        tree.fieldNames().forEachRemaining(keys::add);
        assertEquals(List.of("ok", "courses", "assess", "qa", "cert"), keys);
    }

    /* ================================================================ 鉴权 */

    /**
     * 导览页必须要登录。
     *
     * <p>这条判据看着多余，但它守的是一个只要手滑一次就成立的洞：响应体里带着
     * 这个人有哪几门课、学到哪、考没考过、有几张证书 —— 路径一旦被写进
     * {@code /api/public/} 或者被加进 PUBLIC_PREFIX，它就变成一个谁都能拉的
     * 用户画像接口，而<b>接口本身工作得好好的</b>，没有任何症状。
     */
    @Test @Order(15)
    void 导览页要登录才给() {
        var interceptor = new AuthInterceptor(null, null);
        var e = assertThrows(ApiException.class, () -> interceptor.preHandle(
                new MockHttpServletRequest("GET", "/api/home/overview"),
                new MockHttpServletResponse(), null));
        assertEquals(401, e.status());
        // 反证：真正公开的那条走得通，所以上面不是因为拦截器恒抛才绿的。
        assertTrue(interceptor.preHandle(new MockHttpServletRequest("GET", "/api/public/course/1"),
                new MockHttpServletResponse(), null));
    }

    /* ================================================================ 出网内容 */

    @Test @Order(16)
    void 导览页不带任何内容凭证() throws Exception {
        var req = new MockHttpServletRequest();
        req.setAttribute(AuthInterceptor.ATTR_USER_ID, U_MIX);
        req.setAttribute(AuthInterceptor.ATTR_ROLE, "STUDENT");
        JsonNode tree = mapper.readTree(mapper.writeValueAsString(api.overview(req)));

        List<String> bad = new ArrayList<>();
        walk(tree, "", bad);
        assertTrue(bad.isEmpty(), "导览页里出现了内容凭证字段：" + bad);
        // 反证：判据确实会咬人 —— 换一个真实存在的字段名进去，它必须红。
        List<String> proof = new ArrayList<>();
        walk(mapper.readTree("{\"a\":{\"hlsKey\":\"oss/l1\"}}"), "", proof);
        assertEquals(1, proof.size(), "这条判据自己失效了");
    }

    private static void walk(JsonNode n, String path, List<String> out) {
        if (n.isObject()) {
            var it = n.fields();
            while (it.hasNext()) {
                var e = it.next();
                String k = e.getKey().toLowerCase();
                if (k.contains("hlskey") || k.contains("hls_key") || k.contains("keyenc")
                        || k.contains("segment") || k.contains("wm_") || k.contains("wmbit"))
                    out.add(path + "/" + e.getKey());
                walk(e.getValue(), path + "/" + e.getKey(), out);
            }
        } else if (n.isArray()) {
            for (int i = 0; i < n.size(); i++) walk(n.get(i), path + "[" + i + "]", out);
        }
    }

    /* ================================================================ 夹具 */

    private static Map<String, Object> overview(long userId) {
        return home.overview(userId);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sub(Map<String, Object> o, String key) {
        var m = (Map<String, Object>) o.get(key);
        assertNotNull(m, "「" + key + "」这一片取不到");
        return m;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> o, String key) {
        return (List<Map<String, Object>>) sub(o, key).get("items");
    }

    /**
     * 按 <b>paperId</b> 取那一条，不是按 courseId。
     *
     * <p>一门课下面现在挂着好几份卷，按课程取只能取到"第一份碰上的" ——
     * 而那不会报错，只会让判据去比另一份卷的状态。
     * 另外开放卷根本没有 courseId（全局 non_null 下这个键整个消失），按它取会 NPE。
     */
    private static Map<String, Object> assessOf(long userId, long paperId) {
        for (var it : items(overview(userId), "assess"))
            if (((Number) it.get("paperId")).longValue() == paperId) return it;
        return fail("导览页里没有试卷 " + paperId + " 的测评条目");
    }

    /** 插一份卷。exam_paper 只放身份与准入，规则在 exam_config（schema 里那条注释）。 */
    private static void paper(long id, String code, String title, Long courseId,
                              String access, int grantsCert, int sortNo) {
        jdbc.update("INSERT INTO exam_paper (id,code,title,subtitle,course_id,access,"
                  + "grants_certificate,status,sort_no) VALUES (?,?,?,?,?,?,?,'ON',?)",
                id, code, title, title + " · 副标题", courseId, access, grantsCert, sortNo);
    }

    /** 这份卷的现行规则。不配规则的卷在导览页上是 UNAVAILABLE —— 那也是一条要测的状态。 */
    private static void config(long paperId, Long courseId, int count, int pass, int durationMin,
                               int maxAttempts, int cooldownH, int requirePct) {
        jdbc.update("INSERT INTO exam_config (paper_id,course_id,question_count,pass_score,"
                  + "duration_min,shuffle_options,max_attempts,retry_cooldown_hours,"
                  + "require_progress_pct,effective_from) VALUES (?,?,?,?,?,1,?,?,?,NOW(3))",
                paperId, courseId, count, pass, durationMin, maxAttempts, cooldownH, requirePct);
    }

    private static int num(Map<String, Object> m, String key) {
        Object v = m.get(key);
        assertNotNull(v, "字段 " + key + " 是 null");
        return ((Number) v).intValue();
    }

    private static void enroll(long userId, long courseId, boolean live) {
        jdbc.update("INSERT INTO enrollment (user_id,course_id,source,valid_from,valid_until,"
                  + "agreement_version,agreed_at) VALUES (?,?,'GRANT',"
                  + (live ? "NOW(3), DATE_ADD(NOW(3), INTERVAL 24 MONTH)"
                          : "DATE_SUB(NOW(3), INTERVAL 24 MONTH), DATE_SUB(NOW(3), INTERVAL 1 DAY)")
                  + ",'v2026-08-11',NOW(3))", userId, courseId);
    }

    /** heartbeat_count 要够：trg_progress_sanity 挡的是 watched_sec > 心跳数 × 60。 */
    private static void watched(long userId, long courseId, long lessonId, int sec) {
        jdbc.update("INSERT INTO learn_progress (user_id,course_id,lesson_id,max_position_sec,"
                  + "watched_sec,heartbeat_count) VALUES (?,?,?,?,?,?)",
                userId, courseId, lessonId, sec, sec, sec / 60 + 1);
    }

    /** 上架一讲。步骤照抄 ExamIT：走真实闸门，不靠把讲次留在 DRAFT 绕过去。 */
    private static void onLesson(long courseId, long lessonId, int durationSec) {
        jdbc.update("INSERT IGNORE INTO wm_scheme (course_id,max_colluders,users_planned,eps1,"
                  + "bit_length,bias) VALUES (?,2,100,0.001,64,UNHEX(REPEAT('00',512)))", courseId);
        jdbc.update("INSERT INTO chapter (id,course_id,title,hls_key,store_segment_count,"
                  + "wm_bit_base,sort_no) VALUES (?,?,'第一章',?,10,0,1)",
                lessonId, courseId, "oss/c" + courseId);
        jdbc.update("INSERT INTO lesson (id,chapter_id,course_id,title,sort_no,duration_sec,"
                  + "hls_key,segment_count,wm_slot_stride,wm_slot_count,wm_bit_offset,"
                  + "wm_bit_base,status) VALUES (?,?,?,'第一讲',1,?,?,10,10,1,0,0,'DRAFT')",
                lessonId, lessonId, courseId, durationSec, "oss/c" + courseId);
        jdbc.update("INSERT INTO lesson_segment_block (lesson_id,sort_no,seg_from,seg_to)"
                  + " VALUES (?,1,0,10)", lessonId);
        jdbc.update("INSERT INTO lesson_content_key (lesson_id,course_id,key_version,key_enc,"
                  + "kek_id,status) VALUES (?,?,1,?,'home-it','ACTIVE')", lessonId, courseId, new byte[44]);
        jdbc.update("UPDATE lesson SET status='ON' WHERE id=?", lessonId);
    }
}
