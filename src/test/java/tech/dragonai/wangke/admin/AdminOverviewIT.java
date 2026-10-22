package tech.dragonai.wangke.admin;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.function.Executable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionTemplate;
import tech.dragonai.wangke.ItDb;
import tech.dragonai.wangke.auth.AuthInterceptor;
import tech.dragonai.wangke.support.DongmaClient;
import tech.dragonai.wangke.support.FaqSearch;
import tech.dragonai.wangke.support.InvoiceService;
import tech.dragonai.wangke.support.QaService;
import tech.dragonai.wangke.trade.CodeService;
import tech.dragonai.wangke.trade.CodeVault;
import tech.dragonai.wangke.trade.FulfillService;
import tech.dragonai.wangke.trade.PricingService;
import tech.dragonai.wangke.web.ApiException;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 平台总览汇总接口。跑在真实 MySQL 上（库名 {@code wangke_overview_it}）。
 *
 * <p>这一屏的赔付点只有一个：<b>运营会拿上面的数字做决定</b>。于是要守的东西是三样，
 * 每一样都对应一组用例：
 * <ol>
 *   <li><b>0 与「没有这个数」必须可区分。</b>空库上必须是 0（查到了、确实没有），
 *       取数失败必须是 null 并附一句说明（没查到，请自己去列表核）。这两个数字在运营那里
 *       通向完全不同的决定，写反任何一边都是在编造事实或在丢失事实。
 *   <li><b>口径必须是「支付时间」。</b>前端那份拼盘按下单时间算，跨月那几天必然和账对不上，
 *       而两套口径都不会报错。用例 ③ 造的就是「上月下单今天付」「今天下单昨天付」这两笔。
 *   <li><b>数字点进去必须对得上。</b>待办五项都是链接。总览 4、点进去 3 的话，
 *       运营会以为列表漏了记录，然后去别处找补 —— 那比没有数字更坏。
 *       用例 ⑦ 把五项逐一和它们各自的列表接口对齐。
 * </ol>
 *
 * <p><b>断言从同一个时钟推导，不写死日期。</b>每月 1 号 {@code monthFrom == todayFrom}，
 * 本月窗口就等于今日窗口，四个数两两相等 —— 那是对的，不是 bug。写死日期的用例每月 1 号红一次，
 * 而红一次之后大家学会的是「这个用例不用管」。
 *
 * <p><b>每个负例都钉住失败原因。</b>只断言「抛异常」的用例太容易因为别的原因绿/红
 * （jdbc 没连上也会抛），那样它通过时什么也没证明。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AdminOverviewIT {

    static final String DB = "wangke_overview_it";
    static final long ADMIN = 1L;                 // seed.sql 里的平台超管
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 窗口在 @BeforeAll 算一次，接口每次调用自己再算一次；两者不一致就是跨零点跑了，{@link #call} 会当场说清。 */
    static LocalDate TODAY;
    static LocalDateTime TODAY_FROM, WINDOW_END, MONTH_FROM;

    static DataSource ds;
    static JdbcTemplate jdbc;
    static AdminOverviewController overview;

    // 待办五项要和它们各自的列表接口逐一对齐，所以那五个接口本身也得在场
    static AdminAgentController agents;
    static AdminWithdrawController withdraws;
    static AdminOrderController orders;
    static QaService qa;
    static InvoiceService invoices;

    static final List<String> ALL_FIELDS = List.of(
            "todayPaidFen", "monthPaidFen", "todayOrders", "monthOrders",
            "todayNewStudents", "monthNewStudents", "todayActivations", "monthActivations",
            "todo.pendingAgents", "todo.pendingWithdraws", "todo.pendingRefunds",
            "todo.openTickets", "todo.pendingInvoices");

    static long ordPaidToday;      // 用例 ③ 的那单：上月下单、今天付
    static long ordCodes;          // 用例 ⑤ 挂激活码用的壳单（未支付，不进任何成交口径）

    @BeforeAll
    static void bootstrap() throws Exception {
        ds = ItDb.freshDatabase(DB);
        jdbc = new JdbcTemplate(ds);

        // 发票的两列走 migrations，还没并进 schema.sql。不套上去的话 InvoiceService.queue
        // 会以「Unknown column」收场 —— 那是个看起来像代码 bug、其实是环境缺一块的报错。
        // 先探 information_schema 再决定套不套：这份迁移迟早会被并进 schema.sql，
        // 到那天无条件 ALTER 会以「Duplicate column name」炸在【建库】那一步。
        Integer patched = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS"
              + " WHERE TABLE_SCHEMA=? AND TABLE_NAME='invoice_request' AND COLUMN_NAME='reject_reason'",
                Integer.class, DB);
        if (patched != null && patched == 0) {
            String ddl = Files.readString(
                    Paths.get("db/migrations/20260811_发票号唯一与驳回原因.sql"), StandardCharsets.UTF_8);
            ItDb.runScript(ddl.replace("`wangke`", "`" + DB + "`"));
        }

        TODAY = LocalDate.now(ZONE);
        TODAY_FROM = TODAY.atStartOfDay();
        WINDOW_END = TODAY.plusDays(1).atStartOfDay();
        MONTH_FROM = TODAY.withDayOfMonth(1).atStartOfDay();

        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        var audit = new AdminAudit(jdbc);
        var vault = new CodeVault(ItDb.testKey(31), "overview-salt".getBytes(StandardCharsets.UTF_8));

        // 被测对象：构造只注入 JdbcTemplate。不注 AdminAudit 是有意的 ——
        // 这个接口不写审计，注进来只会让下一个人以为该写。
        overview = new AdminOverviewController(jdbc);

        agents = new AdminAgentController(jdbc, audit);
        withdraws = new AdminWithdrawController(jdbc, audit, new WithdrawService(jdbc, tx, audit));
        orders = new AdminOrderController(jdbc, audit,
                new FulfillService(jdbc, new PricingService(jdbc), tx, vault),
                new RefundService(jdbc, tx, audit),
                new CodeService(jdbc, vault));
        qa = new QaService(jdbc, tx, new FaqSearch(jdbc), new DongmaClient("http://127.0.0.1:1", "", 1), 20);
        invoices = new InvoiceService(jdbc, tx);
    }

    @AfterAll
    static void teardown() { ItDb.close(ds); }

    // ---------------------------------------------------------------- 夹具

    static MockHttpServletRequest as(String role, Long userId) {
        var r = new MockHttpServletRequest();
        r.setRequestURI("/api/admin/overview");
        if (userId != null) r.setAttribute(AuthInterceptor.ATTR_USER_ID, userId);
        if (role != null) r.setAttribute(AuthInterceptor.ATTR_ROLE, role);
        r.setRemoteAddr("10.9.9.9");
        return r;
    }

    static MockHttpServletRequest admin() { return as("PLATFORM_ADMIN", ADMIN); }

    /** 403 的判据要连状态码和理由一起钉住，否则任何一种失败都能让它绿。 */
    static void assertForbidden(String what, Executable body) {
        var e = assertThrows(ApiException.class, body, what + "：非管理员居然调通了，后台在裸奔");
        assertEquals(403, e.status(), what + "：拒绝了，但状态码是 " + e.status()
                + "（" + e.getMessage() + "）—— 不是角色闸拦下的，这个负例什么也没证明");
        assertTrue(e.getMessage().contains("没有权限"),
                what + "：拒绝理由不是权限问题 —— " + e.getMessage());
    }

    /**
     * 以管理员身份取一次数，并顺手把「口径自证」那一段钉住。
     *
     * <p>window 每次都验，是因为它是这一屏唯一能自证口径的东西：一旦服务端的窗口和用例算的窗口
     * 不是同一个（跨零点跑、部署机时区不对），后面每一条数字断言都会以一种看不出原因的方式红。
     * 在这里失败至少能直接读出是哪一边错了。
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> call() {
        Map<String, Object> r = overview.overview(admin());
        assertEquals(Boolean.TRUE, r.get("ok"), "house style 的 ok 字段没了");

        Object w0 = r.get("window");
        assertNotNull(w0, "window 缺席 —— 这一屏再没有任何东西能说明它是按哪个区间出的");
        Map<String, Object> w = (Map<String, Object>) w0;
        assertEquals("Asia/Shanghai", w.get("zone"), "业务时区不是 schema 顶部声明的那个");
        assertEquals(TODAY_FROM.toString(), w.get("todayFrom"),
                "服务端算的今日下界和用例算的不一样（跨零点跑的？部署机时区不是 Asia/Shanghai？）");
        assertEquals(WINDOW_END.toString(), w.get("todayTo"),
                "今日上界必须是明天零点（半开区间），不是 23:59:59");
        assertEquals(MONTH_FROM.toString(), w.get("monthFrom"), "本月下界不是月初零点");
        assertEquals(WINDOW_END.toString(), w.get("monthTo"),
                "本月上界必须与今日上界同值，否则「今日 ⊆ 本月」就不是构造保证了");

        Object g = r.get("generatedAt");
        assertTrue(g instanceof String && !((String) g).isBlank(),
                "generatedAt 不是一个非空字符串：" + g);

        assertNotNull(r.get("unavailable"), "unavailable 必须恒存在（哪怕空数组）"
                + "—— non_null 会把 null 字段整个丢掉，说明只能挂在它身上");
        assertNotNull(r.get("caveats"), "caveats 必须恒存在（哪怕空数组）");
        return r;
    }

    /** 取一个指标。{@code todo.} 前缀的进 todo 子对象。<b>「键不存在」和「值为 null」要分开报</b>。 */
    @SuppressWarnings("unchecked")
    static Long numOf(Map<String, Object> r, String field) {
        Map<String, Object> holder = r;
        String key = field;
        if (field.startsWith("todo.")) {
            Object t = r.get("todo");
            assertNotNull(t, "todo 子对象整个缺席 —— 前端 todoFrom() 会把五项全读成「取不到」");
            holder = (Map<String, Object>) t;
            key = field.substring("todo.".length());
        }
        assertTrue(holder.containsKey(key),
                field + " 这个键根本不在返回体里。前端读到 undefined 会当成「取不到」，"
                      + "而 non_null 让「缺席」与「为 null」在线上长得一模一样");
        Object v = holder.get(key);
        if (v == null) return null;
        assertTrue(v instanceof Number, field + " 不是数字：" + v.getClass() + " = " + v);
        return ((Number) v).longValue();
    }

    /** 断言某项是数字（不是 null），并返回它。用在「这一项应该算得出来」的地方。 */
    static long number(Map<String, Object> r, String field) {
        Long v = numOf(r, field);
        assertNotNull(v, field + " 是 null。null 的意思是「没查到」，运营会去列表页自己核；"
                + "这里查询是跑成了的，必须给数");
        return v;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> unavailable(Map<String, Object> r) {
        return (List<Map<String, Object>>) r.get("unavailable");
    }

    @SuppressWarnings("unchecked")
    static List<String> caveats(Map<String, Object> r) {
        return (List<String>) r.get("caveats");
    }

    /**
     * 由<b>数据</b>触发的口径提示。
     *
     * <p>caveats 里有两类东西，判据必须分开：
     * <ul>
     *   <li><b>结构性</b>的 —— 只要接口还长这样就恒定存在：成交按 paid_at 而列表按
     *       created_at（口径本身不同）、以及服务端与库的时钟对不上（部署环境决定）。
     *       它们与本轮造的数据无关。
     *   <li><b>数据触发</b>的 —— 「有 N 笔记了实付却没有支付时间」「工单 103 条列表只列 50」，
     *       只有造出那种数据才会出现。下面每条用例真正要断的是这一类。
     * </ul>
     *
     * <p>所以「此刻没有口径提示」这句话要断在这个过滤后的列表上。直接断 caveats 全空
     * 会把结构性那两条也算进去，用例会在一台时区没配对的机器上变红 —— 而那恰恰是
     * 那条提示<b>正确工作</b>的时候。反过来，结构性那两条不能就此没人看着，
     * 由 {@link #structuralCaveatsAlwaysPresent} 单独钉住。
     */
    static List<String> dataCaveats(Map<String, Object> r) {
        return caveats(r).stream()
                .filter(c -> !c.startsWith("成交四项按【支付时间】归属"))
                .filter(c -> !c.startsWith("日界线可能不准"))
                .filter(c -> !c.startsWith("这次没能核对服务端与数据库的时区"))
                .toList();
    }

    // ---------------------------------------------------------------- 造数

    /**
     * 直接插一笔订单。<b>不走 OrderService</b>：这里要造的是「上月下单今天付」这类
     * 正常流程造不出来的时间组合，那正是本接口口径判据的核心。
     *
     * <p>{@code buyer_user_id} 用的是不存在的号：库里没有外键，而给每笔测试订单配一个真账号
     * 会把「新增学员」那组用例的基数搅浑 —— 那一组数的正是 sys_user 的行。
     */
    static long newOrder(String orderNo, long amountFen, long paidFen, long refundedFen,
                         String status, LocalDateTime createdAt, LocalDateTime paidAt, int qty) {
        jdbc.update("INSERT INTO biz_order (order_no,buyer_user_id,buyer_type,sku_id,"
                  + "sku_code_snapshot,code_qty_snapshot,amount_fen,paid_amount_fen,"
                  + "refunded_amount_fen,status,pay_channel,paid_at,expire_at,created_at)"
                  + " VALUES (?,900,'STUDENT',3,'STUDENT_DIRECT',?,?,?,?,?,'WECHAT_NATIVE',?,"
                  + " DATE_ADD(NOW(3), INTERVAL 2 HOUR),?)",
                orderNo, qty, amountFen, paidFen, refundedFen, status, paidAt, createdAt);
        return jdbc.queryForObject("SELECT id FROM biz_order WHERE order_no=?",
                Number.class, orderNo).longValue();
    }

    static long newCode(long orderId, String tag) {
        String hash = (tag + "0".repeat(64)).substring(0, 64);
        jdbc.update("INSERT INTO activation_code (code_hash,code_enc,code_masked,course_id,"
                  + "order_id,status,activate_deadline)"
                  + " VALUES (?,?,?,1,?,'ISSUED',DATE_ADD(NOW(3), INTERVAL 30 DAY))",
                hash, tag.getBytes(StandardCharsets.UTF_8), "MASK-" + tag, orderId);
        return jdbc.queryForObject("SELECT id FROM activation_code WHERE code_hash=?",
                Number.class, hash).longValue();
    }

    /** 走真正的 ISSUED→BOUND 迁移（触发器要求同时写 bound_user_id 与 bound_at）。 */
    static void bind(long codeId, long userId, LocalDateTime at) {
        int n = jdbc.update("UPDATE activation_code SET status='BOUND', bound_user_id=?, bound_at=?"
                          + " WHERE id=?", userId, at, codeId);
        assertEquals(1, n, "激活迁移没写进去");
    }

    static void newUser(long id, String phone, String role, String status, LocalDateTime createdAt) {
        jdbc.update("INSERT INTO sys_user (id,phone,role,status,created_at) VALUES (?,?,?,?,?)",
                id, phone, role, status, createdAt);
    }

    /** 半开区间 [MONTH_FROM, WINDOW_END) —— 用例的期望值只能按这条推，不能按日期硬写。 */
    static boolean inMonth(LocalDateTime t) {
        return !t.isBefore(MONTH_FROM) && t.isBefore(WINDOW_END);
    }

    // ================================================================ 1. 空库

    @Test @Order(1)
    @DisplayName("空库：13 项全是 0 而不是 null，unavailable 与 caveats 都是空数组")
    void emptyDatabaseIsZeroNotNull() {
        Map<String, Object> r = call();

        for (String f : ALL_FIELDS) {
            Long v = numOf(r, f);
            assertNotNull(v, f + " 在空库上是 null。null 的意思是「这个数没取到，你得自己去列表核」，"
                    + "而这里查询跑成了、结果确实是空的 —— 那是 0。"
                    + "把 0 说成 null，运营每天都要为一屏假警报去核对；反过来更糟。");
            assertEquals(0L, v.longValue(), f + " 在空库上不是 0：" + v);
        }

        assertEquals(List.of(), unavailable(r),
                "什么都没坏，unavailable 却不是空的 —— 那 13 个 0 会被运营当成假数字");
        assertEquals(List.of(), dataCaveats(r), "空库上不该有任何由数据触发的口径提示");

        // 0 不是因为「表是空的所以随便什么过滤都返回 0」：seed 的超管确实在库里，
        // 它没被算进新增学员，是 role 过滤起了作用。
        assertEquals(1, (int) jdbc.queryForObject(
                        "SELECT COUNT(*) FROM sys_user WHERE role='PLATFORM_ADMIN'", Integer.class),
                "seed 的超管不在库里，那「今日新增学员=0」这条断言什么也没证明");
        assertEquals(0L, number(r, "todayNewStudents"),
                "超管被算进了新增学员 —— role 过滤没起作用");
    }

    // ================================================================ 2. 角色闸

    @Test @Order(2)
    @DisplayName("越权：学员 / 代理 / 导师 / 匿名一律 403「没有权限」，且一个数字都不许先算")
    void nonPlatformAdminIsForbidden() {
        assertForbidden("学员看总览", () -> overview.overview(as("STUDENT", 20L)));
        assertForbidden("代理看总览", () -> overview.overview(as("AGENT", 10L)));
        // 导师能看答疑队列，但成交额 / 提现 / 退款是财务面，和答疑不是一个可见面
        assertForbidden("导师看总览", () -> overview.overview(as("MENTOR", 30L)));
        assertForbidden("匿名看总览", () -> overview.overview(as(null, null)));

        // requireRole 必须在方法第一行：把 biz_order 藏起来之后，越权者拿到的仍然必须是 403。
        // 如果角色判断排在取数后面，这里会变成「先算了一遍、算失败了、然后才拒绝」——
        // 拒绝是拒绝了，可数据面已经被一个无权的人踩过一遍了。
        jdbc.execute("RENAME TABLE biz_order TO biz_order_hidden");
        try {
            assertForbidden("学员在库表缺失时看总览",
                    () -> overview.overview(as("STUDENT", 20L)));
        } finally {
            jdbc.execute("RENAME TABLE biz_order_hidden TO biz_order");
        }
        assertEquals(0L, number(call(), "monthOrders"), "表没还回来，后面的用例会全红");
    }

    // ================================================================ 3. 成交口径

    @Test @Order(3)
    @DisplayName("成交按 paid_at 归属，不按 created_at：上月下单今天付要算今天，今天下单昨天付不算今天")
    void paidAtNotCreatedAt() {
        Map<String, Object> before = call();
        long t0Fen = number(before, "todayPaidFen"), m0Fen = number(before, "monthPaidFen");
        long t0Cnt = number(before, "todayOrders"),  m0Cnt = number(before, "monthOrders");

        // A：上月下单、今天付。按 created_at 算的话它整个不在本月里
        LocalDateTime paidA = TODAY_FROM.plusHours(9);
        ordPaidToday = newOrder("OVW_A", 120000, 120000, 0, "FULFILLED",
                MONTH_FROM.minusDays(3), paidA, 1);
        // B：今天下单、昨天付（对公转账那条路上 created_at 与 paid_at 差几天是常态）
        LocalDateTime paidB = TODAY_FROM.minusHours(2);
        newOrder("OVW_B", 50000, 50000, 0, "FULFILLED", TODAY_FROM.plusHours(1), paidB, 1);
        // C：今天下单、没付。钱没到过账，任何口径下都不该出现在成交里
        newOrder("OVW_C", 79900, 0, 0, "CREATED", TODAY_FROM.plusHours(2), null, 1);

        Map<String, Object> r = call();

        assertEquals(t0Fen + 120000, number(r, "todayPaidFen"),
                "今日成交额不对。按 created_at 归属的话这里会是 50000（B 是今天下的单）"
              + "或 129900（把没付款的 C 也算上），三个数都不会报错，只有这个是对的");
        assertEquals(t0Cnt + 1, number(r, "todayOrders"),
                "今日订单数不对：只有 A 是今天付的");

        // 每月 1 号 monthFrom == todayFrom，B 的「昨天」就落在上个月 —— 那是对的，不是 bug
        boolean bInMonth = inMonth(paidB);
        assertEquals(m0Fen + 120000 + (bInMonth ? 50000 : 0), number(r, "monthPaidFen"),
                "本月成交额不对（B 的支付时间 " + paidB + " 是否落在本月：" + bInMonth + "）");
        assertEquals(m0Cnt + 1 + (bInMonth ? 1 : 0), number(r, "monthOrders"),
                "本月订单数不对");

        assertEquals(List.of(), dataCaveats(r),
                "C 的实付是 0，不该被口径探针当成「有钱没归属」：" + dataCaveats(r));
    }

    // ================================================================ 4. 已退款仍算成交

    @Test @Order(4)
    @DisplayName("已退款的单仍计入成交额：钱确实到过账，退款发生在别的日子")
    void refundedOrderStillCounts() {
        Map<String, Object> before = call();
        long t0 = number(before, "todayPaidFen"), c0 = number(before, "todayOrders");

        newOrder("OVW_REFUNDED", 99900, 99900, 99900, "REFUNDED",
                MONTH_FROM.minusDays(1), TODAY_FROM.plusHours(3), 1);

        Map<String, Object> r = call();
        assertEquals(t0 + 99900, number(r, "todayPaidFen"),
                "已退款的 999 元没算进今日成交额。写成 status IN ('PAID','FULFILLED') 会让这笔钱"
              + "静默消失 —— 数字变小、没有任何报错，而运营会以为那天生意差");
        assertEquals(c0 + 1, number(r, "todayOrders"), "已退款的单没算进今日订单数");

        // 也不许拿 refunded_amount_fen 去冲减：卡片写的是「实际收到的钱（已支付部分）」，
        // 退款只有 refund_record.finished_at 能定日期，揉进同一个日窗口算出来的数谁也解释不了
        assertNotEquals(t0, number(r, "todayPaidFen"),
                "成交额被退款冲掉了 —— 那是两个口径拼成的一个数");
    }

    // ================================================================ 5. 激活数

    @Test @Order(5)
    @DisplayName("激活数按 bound_at：BOUND 后转 EXPIRED 的仍计入，ISSUED→REVOKED 的不计入")
    void activationsCountedByBoundAt() {
        Map<String, Object> before = call();
        long t0 = number(before, "todayActivations"), m0 = number(before, "monthActivations");

        // 壳单：未支付、实付 0，所以既不进成交口径，也不会被「有钱没归属」的探针捞到
        ordCodes = newOrder("OVW_CODES", 79900, 0, 0, "CREATED",
                TODAY_FROM.plusHours(1), null, 10);

        long c1 = newCode(ordCodes, "OVWCODE1");
        bind(c1, 901, TODAY_FROM.plusHours(10));

        long c2 = newCode(ordCodes, "OVWCODE2");
        bind(c2, 902, TODAY_FROM.plusHours(11));
        jdbc.update("UPDATE activation_code SET status='EXPIRED' WHERE id=?", c2);
        assertEquals("EXPIRED", jdbc.queryForObject(
                        "SELECT status FROM activation_code WHERE id=?", String.class, c2),
                "这个用例要验的就是「已过期但激活过」，可它根本没变成 EXPIRED");

        long c3 = newCode(ordCodes, "OVWCODE3");
        jdbc.update("UPDATE activation_code SET status='REVOKED', revoked_by=1, revoked_at=NOW(3)"
                  + " WHERE id=?", c3);

        newCode(ordCodes, "OVWCODE4");                        // 一直 ISSUED，没人激活

        long c5 = newCode(ordCodes, "OVWCODE5");
        bind(c5, 905, WINDOW_END);                            // 明天零点：半开上界，不含

        Map<String, Object> r = call();
        assertEquals(t0 + 2, number(r, "todayActivations"),
                "今日激活数不对。判据必须是 bound_at 而不是 status='BOUND'：BOUND→EXPIRED 是合法迁移，"
              + "拿 status 过滤的话「今天激活了 30 个」会在几个月后自己变成 12 个，且没有任何提示");
        assertEquals(m0 + 2, number(r, "monthActivations"), "本月激活数不对");

        // 反向钉一下：如果实现拿的是 status='BOUND'，只会数出 c1 一个
        assertNotEquals(t0 + 1, number(r, "todayActivations"),
                "只数出一个，说明判据是 status 不是 bound_at");
    }

    // ================================================================ 6. 新增学员

    @Test @Order(6)
    @DisplayName("新增学员含 FROZEN，不含代理 / 导师 / 超管；明天零点注册的不算今天")
    void newStudentsIncludeFrozenExcludeOtherRoles() {
        Map<String, Object> before = call();
        long t0 = number(before, "todayNewStudents"), m0 = number(before, "monthNewStudents");

        newUser(201, "13500000201", "STUDENT", "ACTIVE", TODAY_FROM.plusHours(4));
        // 冻结不改变「今天有个人注册了」这件事实。按 status='ACTIVE' 过滤会让历史数字
        // 随着封号自己缩水，而且缩的时候没有任何提示。
        newUser(202, "13500000202", "STUDENT", "FROZEN", TODAY_FROM.plusHours(5));
        newUser(203, "13500000203", "AGENT", "ACTIVE", TODAY_FROM.plusHours(6));
        newUser(204, "13500000204", "MENTOR", "ACTIVE", TODAY_FROM.plusHours(7));
        newUser(205, "13500000205", "STUDENT", "ACTIVE", WINDOW_END);   // 半开上界，不含

        Map<String, Object> r = call();
        assertEquals(t0 + 2, number(r, "todayNewStudents"),
                "今日新增学员不对：被冻结的 202 要算（他确实注册了），代理 203 / 导师 204 / "
              + "明天零点的 205 都不该算");
        assertEquals(m0 + 2, number(r, "monthNewStudents"), "本月新增学员不对");
    }

    // ================================================================ 7. 待办与列表对齐

    @Test @Order(7)
    @DisplayName("待办五项与各自列表接口的 total 逐一相等：数字点进去必须对得上")
    void todoMatchesTheListsItLinksTo() {
        // ---- 待审代理：一条正常的，一条挂在已删账号上的（列表是 INNER JOIN，两边都必须排除它）
        newUser(301, "13500000301", "AGENT", "ACTIVE", TODAY_FROM.plusHours(3));
        newUser(302, "13500000302", "AGENT", "ACTIVE", TODAY_FROM.plusHours(3));
        jdbc.update("INSERT INTO agent_profile (user_id,agent_code,level,status)"
                  + " VALUES (301,'OVW301',1,'PENDING')");
        jdbc.update("INSERT INTO agent_profile (user_id,agent_code,level,status)"
                  + " VALUES (302,'OVW302',1,'PENDING')");
        jdbc.update("DELETE FROM sys_user WHERE id=302");    // 档案还在，账号没了

        // ---- 待审提现：一条 APPLIED，一条已进 REVIEWING（链接是 ?status=APPLIED，不含它）
        newUser(303, "13500000303", "AGENT", "ACTIVE", TODAY_FROM.plusHours(3));
        jdbc.update("INSERT INTO agent_profile (id,user_id,agent_code,level,status)"
                  + " VALUES (90,303,'OVW303',1,'ACTIVE')");
        jdbc.update("INSERT INTO agent_balance (agent_id,commission_balance_fen) VALUES (90,1000000)");
        jdbc.update("INSERT INTO withdraw_order (withdraw_no,agent_id,amount_fen,channel,"
                  + "payee_name,payee_account) VALUES ('OVW_W1',90,20000,'BANK_TRANSFER','张三','621')");
        jdbc.update("INSERT INTO withdraw_order (withdraw_no,agent_id,amount_fen,channel,"
                  + "payee_name,payee_account) VALUES ('OVW_W2',90,30000,'BANK_TRANSFER','张三','621')");
        jdbc.update("UPDATE withdraw_order SET status='REVIEWING' WHERE withdraw_no='OVW_W2'");

        // ---- 待审退款：一条 APPLIED，一条已 APPROVED
        jdbc.update("INSERT INTO refund_record (refund_no,order_id,amount_fen,scope,applied_by)"
                  + " VALUES ('OVW_R1',?,10000,'MANUAL',1)", ordPaidToday);
        jdbc.update("INSERT INTO refund_record (refund_no,order_id,amount_fen,scope,applied_by)"
                  + " VALUES ('OVW_R2',?,10000,'MANUAL',1)", ordPaidToday);
        jdbc.update("UPDATE refund_record SET status='APPROVED', approved_by=1"
                  + " WHERE refund_no='OVW_R2'");

        // ---- 待处理工单：两条 ESCALATED，另外两条别的状态
        jdbc.update("INSERT INTO qa_ticket (user_id,course_id,title,status,sla_due_at) VALUES"
                  + " (201,1,'工单一','ESCALATED',DATE_ADD(NOW(3), INTERVAL 24 HOUR)),"
                  + " (201,1,'工单二','ESCALATED',DATE_ADD(NOW(3), INTERVAL 12 HOUR)),"
                  + " (201,1,'工单三','AI_ANSWERED',NULL),"
                  + " (201,1,'工单四','CLOSED',NULL)");

        // ---- 待开发票：两条 APPLIED，一条已开
        jdbc.update("INSERT INTO invoice_request (applicant_user_id,scope,ref_id,title,amount_fen)"
                  + " VALUES (201,'ORDER',9001,'某某公司',79900),(201,'ORDER',9002,'某某公司',79900)");
        jdbc.update("INSERT INTO invoice_request (applicant_user_id,scope,ref_id,title,amount_fen,"
                  + "status,invoice_no,issued_at) VALUES (201,'ORDER',9003,'某某公司',79900,"
                  + "'ISSUED','INV-OVW-1',NOW(3))");

        Map<String, Object> r = call();
        var req = admin();

        // 两头都断言：只对齐不写绝对值的话，两边用同一条错 SQL 也会「相等」
        long listAgents = ((Number) agents.list("PENDING", null, null, null, 1, req).get("total")).longValue();
        assertEquals(1L, listAgents, "列表侧的待审代理不是 1：挂在已删账号上的那条应当被 INNER JOIN 排除");
        assertEquals(listAgents, number(r, "todo.pendingAgents"),
                "待审代理：总览与 /agents?status=PENDING 对不上。JOIN 少写一次就是"
              + "「总览 2、点进去 1」，运营会以为列表漏了记录");

        long listWithdraws = ((Number) withdraws.list("APPLIED", null, null, null, 1, 1, req)
                .get("total")).longValue();
        assertEquals(1L, listWithdraws, "列表侧的待审提现不是 1");
        assertEquals(listWithdraws, number(r, "todo.pendingWithdraws"),
                "待审提现：链接是 ?status=APPLIED，把 REVIEWING 也算进来就和列表对不上了");

        long listRefunds = ((Number) orders.refundList("APPLIED", null, 1, 1, req)
                .get("total")).longValue();
        assertEquals(1L, listRefunds, "列表侧的待审退款不是 1");
        assertEquals(listRefunds, number(r, "todo.pendingRefunds"), "待审退款与退款列表对不上");

        long queueTickets = qa.queue(ADMIN, "PLATFORM_ADMIN", null, 100).size();
        assertEquals(2L, queueTickets, "队列侧的待处理工单不是 2");
        assertEquals(queueTickets, number(r, "todo.openTickets"),
                "待处理工单与 QaService.queue（管理员视角，不加 assignee 条件）对不上");

        long queueInvoices = invoices.queue("APPLIED", 100).size();
        assertEquals(2L, queueInvoices, "队列侧的待开发票不是 2");
        assertEquals(queueInvoices, number(r, "todo.pendingInvoices"), "待开发票与开票队列对不上");

        assertEquals(List.of(), unavailable(r), "什么都没坏，unavailable 却不空：" + unavailable(r));
    }

    // ================================================================ 8. 时间边界

    @Test @Order(8)
    @DisplayName("半开区间 [from, end)：00:00:00.000 算今天，23:59:59.999 算今天，明天 00:00:00.000 不算")
    void halfOpenWindowBoundaries() {
        Map<String, Object> before = call();
        long t0Fen = number(before, "todayPaidFen"), t0Cnt = number(before, "todayOrders");
        long m0Cnt = number(before, "monthOrders");

        newOrder("OVW_EDGE_LO", 10000, 10000, 0, "PAID",
                MONTH_FROM, TODAY_FROM, 1);                                   // 00:00:00.000
        newOrder("OVW_EDGE_HI", 20000, 20000, 0, "PAID",
                MONTH_FROM, WINDOW_END.minusNanos(1_000_000L), 1);            // 23:59:59.999
        newOrder("OVW_EDGE_NEXT", 30000, 30000, 0, "PAID",
                MONTH_FROM, WINDOW_END, 1);                                   // 明天 00:00:00.000

        Map<String, Object> r = call();
        assertEquals(t0Cnt + 2, number(r, "todayOrders"),
                "边界算错了。写成 BETWEEN ... '23:59:59' 会丢掉 23:59:59.999 那一笔（少 1）；"
              + "上界写成闭区间会把明天零点那笔算进今天（多 1）");
        assertEquals(t0Fen + 30000, number(r, "todayPaidFen"), "今日成交额的边界不对");
        assertEquals(m0Cnt + 2, number(r, "monthOrders"),
                "本月上界与今日上界必须是同一个值，明天零点那笔在本月里也不该出现");
    }

    // ================================================================ 9. 取数失败

    @Test @Order(9)
    @DisplayName("一项取数失败：该项为 null 并在 unavailable 里说清，其余 12 项照出，接口不抛异常")
    void oneBrokenQueryDoesNotSinkThePage() {
        jdbc.execute("RENAME TABLE qa_ticket TO qa_ticket_moved");
        try {
            Map<String, Object> r = assertDoesNotThrow(AdminOverviewIT::call,
                    "一张表出问题就整页 500 —— 运营连还能看的那 11 个数也一起失去了");

            assertNull(numOf(r, "todo.openTickets"),
                    "取数失败却给了个数。如果这里是 0，运营会当成「今天没有待处理工单」下班走人");

            var un = unavailable(r);
            assertEquals(1, un.size(), "unavailable 里不是恰好一条：" + un);
            Map<String, Object> u = un.get(0);
            assertEquals("todo.openTickets", u.get("field"),
                    "field 必须是前端能对上的路径字面量");
            assertEquals("待处理工单", u.get("label"),
                    "label 必须逐字等于页面上那张卡片的标题，否则运营对不上是哪一项");

            String reason = String.valueOf(u.get("reason"));
            assertFalse(reason.isBlank(), "reason 是空的 —— 那这条 unavailable 什么也没说");
            for (String leak : List.of("qa_ticket", "SELECT", "select", "Exception",
                                       "SQL", "org.spring")) {
                assertFalse(reason.contains(leak),
                        "reason 里漏了内部细节「" + leak + "」：内部细节不出网（"
                                + "GlobalExceptionHandler 的第一条纪律），技术细节只进日志。原文：" + reason);
            }

            for (String f : ALL_FIELDS) {
                if ("todo.openTickets".equals(f)) continue;
                assertNotNull(numOf(r, f),
                        f + " 也变成 null 了 —— 一项失败把别的项一起拖下水，"
                          + "失败隔离没做到（多半是把六条语句包进了同一个事务）");
            }
        } finally {
            jdbc.execute("RENAME TABLE qa_ticket_moved TO qa_ticket");
        }

        // 表还回来之后必须自己恢复，不需要重启
        Map<String, Object> back = call();
        assertEquals(2L, number(back, "todo.openTickets"), "表还回来了，数字没跟着回来");
        assertEquals(List.of(), unavailable(back), "故障过去了，unavailable 却没清空");
    }

    // ================================================================ 10. 口径偏差

    @Test @Order(10)
    @DisplayName("有钱却没有支付时间：成交额不变（那笔进不了任何日窗口），但 caveats 要说清有几笔")
    void moneyWithoutPaidAtBecomesACaveat() {
        Map<String, Object> before = call();
        assertEquals(List.of(), dataCaveats(before), "这条用例的前提是此刻还没有数据触发的口径提示");
        long t0 = number(before, "todayPaidFen"), m0 = number(before, "monthPaidFen");
        long tc0 = number(before, "todayOrders"), mc0 = number(before, "monthOrders");

        // 库层拦得住 UPDATE 路径（trg_order_state 要求标记已支付必须写 paid_at），
        // 拦不住直接 INSERT 一行 status='PAID' 且 paid_at 为空 —— 那笔钱从此不出现在
        // 任何日 / 月统计里，而且不会有任何信号。这条 caveat 就是那个信号。
        newOrder("OVW_GHOST", 88800, 88800, 0, "PAID", TODAY_FROM.plusHours(6), null, 1);

        Map<String, Object> r = call();
        assertEquals(t0, number(r, "todayPaidFen"), "没有支付时间的钱不该被归到今天");
        assertEquals(m0, number(r, "monthPaidFen"), "没有支付时间的钱不该被归到本月");
        assertEquals(tc0, number(r, "todayOrders"), "今日订单数被这笔无归属的单撑大了");
        assertEquals(mc0, number(r, "monthOrders"), "本月订单数被这笔无归属的单撑大了");

        // 断在 dataCaveats 上：结构性那两条（成交口径 / 时钟）与这笔造出来的数据无关，
        // 由 structuralCaveatsAlwaysPresent 单独钉住。
        var cav = dataCaveats(r);
        assertEquals(1, cav.size(), "由数据触发的口径提示不是恰好一条：" + cav);
        assertTrue(cav.get(0).contains("1 笔"),
                "提示里没有具体笔数。「成交额可能偏低」这种话运营没法用，"
                        + "他需要知道到底有几笔要去捞。原文：" + cav.get(0));
        assertTrue(cav.get(0).contains("偏低"),
                "提示里没说清偏的方向，运营不知道该往上还是往下修正：" + cav.get(0));

        // 偏差落 caveats 而不是 null：把成交额整个变成 null，
        // 会连「今天确实收到了这些钱」这条真信息一起丢掉
        assertEquals(List.of(), unavailable(r),
                "已知口径偏差被当成了取数失败 —— 数字还在，不该进 unavailable：" + unavailable(r));
    }

    // ================================================================ 11. 队列硬顶

    @Test @Order(11)
    @DisplayName("待处理工单超过队列硬顶：数字仍给全量，但要说清点进去为什么只有 100 条")
    void ticketCountAboveQueueCapIsExplained() {
        // 这条排在最后：它要往 qa_ticket 里灌 101 行，放前面会把用例 ⑦ 的对齐断言搅浑
        StringBuilder sb = new StringBuilder(
                "INSERT INTO qa_ticket (user_id,course_id,title,status,sla_due_at) VALUES");
        for (int i = 0; i < 101; i++)
            sb.append(i == 0 ? " " : ",")
              .append("(201,1,'压队列").append(i).append("','ESCALATED',DATE_ADD(NOW(3), INTERVAL 6 HOUR))");
        jdbc.update(sb.toString());

        Map<String, Object> r = call();
        long n = number(r, "todo.openTickets");
        assertEquals(103L, n, "总览给的必须是全量 COUNT（之前 2 条 + 新灌 101 条）");
        assertEquals(100, qa.queue(ADMIN, "PLATFORM_ADMIN", null, 100).size(),
                "队列侧的硬顶不是 100，那这条 caveat 里的数字就是假的");

        String hit = caveats(r).stream().filter(c -> c.contains("待处理工单")).findFirst()
                .orElseThrow(() -> new AssertionError(
                        "总览 103、点进去只有 100 条，却没有任何说明。运营对这种差异的第一反应是"
                      + "「列表漏了记录」，然后去别处找补 —— 那比没有数字更坏。现有提示：" + caveats(r)));
        // 三个数都要在：总览的真数、这一屏默认列多少（运营真正看到的那个）、
        // 以及每页调到最大也只有多少。少了中间那个，51~100 这一段差异无从解释；
        // 少了最后那个，运营会以为「翻到第二页就全了」。
        assertTrue(hit.contains("103") && hit.contains("50") && hit.contains("100"),
                "提示里没有把三个数都写出来，运营对不上是哪几个数在打架：" + hit);

        // 说明归说明，数字不许被砍成 100 去迎合列表 —— 那是编造
        assertNotEquals(100L, n, "为了和列表一致把真数砍成了硬顶值");
    }

    // ================================================================ 12. 结构性提示

    /**
     * 结构性的两条口径提示必须<b>恒定在场</b>。
     *
     * <p>上面那些用例把「此刻没有口径提示」断在 {@link #dataCaveats} 上，
     * 也就是刻意把这两条滤掉了。滤掉就得有人单独看着它们，
     * 否则哪天有人把它们删了，全套用例照样全绿 —— 而那正是本轮要治的那种病：
     * 一个不会报错、只会安静给出错数字的改动。
     */
    @Test @Order(12)
    @DisplayName("★ 成交口径与时钟这两条结构性提示恒在：它们被滤出了别的判据，必须单独钉住")
    void structuralCaveatsAlwaysPresent() {
        List<String> cs = caveats(call());

        assertTrue(cs.stream().anyMatch(c -> c.startsWith("成交四项按【支付时间】归属")),
                "成交四项按 paid_at 统计，而卡片点过去的订单列表按 created_at 过滤，"
              + "这条差异没有任何地方说得出来。同一个标签、同一个金额，背后是两笔不相干的订单，"
              + "两边都不报错。现有提示：" + cs);

        // 时钟这条是【条件性】的：三者一致时本来就不该说话。所以判据分两支写，
        // 而不是无脑断它在场 —— 那样在一台配置正确的机器上会红，且红得莫名其妙。
        LocalDateTime dbNow = jdbc.queryForObject("SELECT NOW(3)", LocalDateTime.class);
        long offMin = Math.abs(java.time.temporal.ChronoUnit.MINUTES.between(
                dbNow, LocalDateTime.now(ZONE)));
        boolean jvmOff = !ZoneId.systemDefault().getRules().equals(ZONE.getRules());
        boolean skewed = offMin >= 2 || jvmOff;

        assertEquals(skewed, cs.stream().anyMatch(c -> c.startsWith("日界线可能不准")),
                skewed
                    ? "这台机器上库/JVM 与 Asia/Shanghai 对不上（差 " + offMin + " 分钟，JVM 是 "
                      + ZoneId.systemDefault() + "），「今日/本月」的日界线就是偏的，"
                      + "而 window.zone 仍然回显 Asia/Shanghai —— 这件事必须说出来。现有提示：" + cs
                    : "三者时区一致，却还在报日界线不准 —— 这是一句恒定的噪声，"
                      + "运营看几次之后就再也不看 caveats 了。现有提示：" + cs);
    }
}
