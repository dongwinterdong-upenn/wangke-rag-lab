package tech.dragonai.wangke.trade;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionTemplate;
import tech.dragonai.wangke.ItDb;
import tech.dragonai.wangke.admin.AdminAudit;
import tech.dragonai.wangke.admin.AdminCodeExportService;
import tech.dragonai.wangke.admin.AdminCodeGrantController;
import tech.dragonai.wangke.admin.AdminOrderController;
import tech.dragonai.wangke.admin.AdminOverviewController;
import tech.dragonai.wangke.admin.RefundService;
import tech.dragonai.wangke.agent.AgentCodeExportService;
import tech.dragonai.wangke.agent.AgentService;
import tech.dragonai.wangke.auth.AuthInterceptor;
import tech.dragonai.wangke.learn.ActivationService;
import tech.dragonai.wangke.support.InvoiceService;
import tech.dragonai.wangke.web.ApiException;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 超管人工给代理发码（赠送 / 补发）。跑在真实 MySQL 上（库名 {@code wangke_gift_it}）。
 *
 * <p>这条路径的形状是「合成订单」：发码时代建一张 {@code origin='ADMIN_GIFT'} 的
 * biz_order，码照常挂在它上面。好处是出码逻辑一个字节都不复制；代价是<b>系统里从此
 * 多了一种长得像订单、却从来没有收到过钱的东西</b>，而全仓有十几处按「订单」取数。
 * 于是本测试要守的不是「发码能不能成功」，是那十几处会不会把它当成一笔收入：
 *
 * <ol>
 *   <li><b>它不能变成钱。</b>不进 payment_record、不结佣金、不进总览成交四项、
 *       开不出发票、确认不了到账。这几条坏掉时<b>一条错都不会报</b> ——
 *       财务对账时多出来的那几千块钱，没有任何一行日志能解释它从哪来。
 *   <li><b>它必须是真码。</b>白送的码和买来的码走同一条 issueCodes。这里唯一的出口是
 *       真取一次明文、真激活一次：hash / seal 顺序抄反的表现是插得进、掩码对、状态对，
 *       只有有人点「导出」时才炸，而那可能是几周之后。
 *   <li><b>重复提交不能多发一批。</b>后台按钮会被双击，幂等键是唯一的裁决点。
 * </ol>
 *
 * <p>每个负例都<b>钉住失败原因</b>：只断言「抛了异常」的用例太容易因为别的原因红着，
 * 那样它绿的时候什么也没证明。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ManualIssueIT {

    /**
     * 库名必须是全新的一个。
     *
     * <p>与别的 IT 撞名的后果不是「数据串了」这么温和：{@link ItDb#freshDatabase} 第一句是
     * {@code DROP DATABASE IF EXISTS}，两个类并行（或先后）跑同一个库名时，后启动的那个
     * 会把前一个正在用的库连根拔掉，前一个报出来的是「表不存在」——
     * 一句完全指不到根因、且换台机器就复现不了的错。
     */
    static final String DB = "wangke_gift_it";

    static final long ADMIN = 1L;              // seed.sql 里的平台超管
    static final long U_A1 = 10L, U_B2 = 11L, U_SUSPENDED = 12L;
    static final long AG_A1 = 1L, AG_B2 = 2L, AG_SUSPENDED = 3L;
    static final long STU_G6 = 20L, STU_G20 = 21L;
    static final long SKU_1 = 1L;              // AGENT_1：1 个码
    static final long SKU_10 = 2L;             // AGENT_10：10 个码
    static final long SKU_50 = 4L;             // 本测试自建：50 个码，只为压 MAX_CODES 那条闸
    static final String AGREEMENT = "v2026-08-11";

    static DataSource ds;
    static JdbcTemplate jdbc;
    static ManualIssueService issue;
    static AdminCodeGrantController grants;
    static AdminOrderController adminOrders;
    static AdminOverviewController overviewApi;
    static CodeService codeApi;
    static ActivationService activation;
    static AgentService agentSvc;
    static AgentCodeExportService agentExport;
    static InvoiceService invoices;
    static RefundService refunds;
    static OrderService orderSvc;

    /** G1 那一批：agent B2 的 2 张白送单、20 个码。后面好几条判据都拿它当素材。 */
    static List<Long> k1OrderIds;
    static long k1CodeIdBound;                 // G6 真激活掉的那一个码
    static String k1PlainOfBound;              // 它的明文，G7 拿去审计表里搜
    static long realOrderId;                   // G3 真收过钱的那一单，G18 拿它做退款

    @BeforeAll
    static void bootstrap() throws Exception {
        ds = ItDb.freshDatabase(DB);
        jdbc = new JdbcTemplate(ds);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        var pricing = new PricingService(jdbc);
        var vault = new CodeVault(ItDb.testKey(42),
                "gift-salt-not-for-production".getBytes(StandardCharsets.UTF_8));

        var fulfill = new FulfillService(jdbc, pricing, tx, vault);
        orderSvc = new OrderService(jdbc, pricing);
        codeApi = new CodeService(jdbc, vault);
        activation = new ActivationService(jdbc, tx, vault);
        var audit = new AdminAudit(jdbc);

        issue = new ManualIssueService(jdbc, orderSvc, pricing, fulfill, tx);
        grants = new AdminCodeGrantController(jdbc, issue,
                new AdminCodeExportService(jdbc, tx, vault), audit);
        refunds = new RefundService(jdbc, tx, audit);
        adminOrders = new AdminOrderController(jdbc, audit, fulfill, refunds, codeApi);
        overviewApi = new AdminOverviewController(jdbc);
        agentSvc = new AgentService(jdbc, tx, codeApi);
        agentExport = new AgentCodeExportService(jdbc, tx, agentSvc, vault);
        invoices = new InvoiceService(jdbc, tx);

        jdbc.update("INSERT INTO sys_user (id,phone,role) VALUES "
                  + "(10,'13100000010','AGENT'),(11,'13100000011','AGENT'),(12,'13100000012','AGENT')");
        jdbc.update("INSERT INTO sys_user (id,phone,role) VALUES "
                  + "(20,'13200000020','STUDENT'),(21,'13200000021','STUDENT')");
        jdbc.update("INSERT INTO agent_profile (id,user_id,agent_code,level,status)"
                  + " VALUES (1,10,'A1',1,'ACTIVE')");
        // B2 是二级且有上级 —— 这是有意选的：它买东西是会结佣金的，
        // 所以「白送单不结佣金」这条判据在它身上才有牙齿。挂在一级代理下发码的话，
        // commission_record 本来就不会多一行，G1 那一半会变成一条永远绿的空断言。
        jdbc.update("INSERT INTO agent_profile (id,user_id,agent_code,level,parent_agent_id,status)"
                  + " VALUES (2,11,'B2',2,1,'ACTIVE')");
        jdbc.update("INSERT INTO agent_profile (id,user_id,agent_code,level,parent_agent_id,status)"
                  + " VALUES (3,12,'B3',2,1,'SUSPENDED')");

        // 种子里最大的代理档只有 10 个码，而 MAX_COPIES(20) × 10 = 200 正好【不】超过
        // MAX_CODES(200)。也就是说不补一个大档位的话，「copies × code_qty 超上限」那条闸
        // 在整个种子目录上根本触发不到，G21 的后半句会变成一条测不到东西的断言。
        jdbc.update("INSERT INTO sku (id,sku_code,name,buyer_type,course_id,code_qty,status)"
                  + " VALUES (4,'AGENT_50','代理批量 · 50 个激活码','AGENT',1,50,'ON')");
    }

    @AfterAll
    static void teardown() { ItDb.close(ds); }

    // ================================================================ 夹具

    static MockHttpServletRequest as(String role, Long userId) {
        var r = new MockHttpServletRequest();
        r.setRequestURI("/api/admin/probe");
        if (userId != null) r.setAttribute(AuthInterceptor.ATTR_USER_ID, userId);
        if (role != null) r.setAttribute(AuthInterceptor.ATTR_ROLE, role);
        r.setRemoteAddr("10.9.9.9");
        return r;
    }

    static MockHttpServletRequest admin() { return as("PLATFORM_ADMIN", ADMIN); }

    /**
     * 后台那三个控制器方法（overview / confirmTransfer / grant / revokeOrderCodes）都是
     * <b>包内可见</b>的，而这个测试住在 trade 包里 —— 它测的是发码这条链，不是后台。
     *
     * <p>用反射调，而不是把生产方法改成 public：为了让一个测试够得着就放宽生产代码的
     * 可见性，等于把「谁能调它」这条边界让给测试来定，下一个人会顺着这个口子从别的包
     * 直接调进来，而 AdminGuard 是按 <b>URL 前缀</b>挂的、对进程内直调完全不生效。
     *
     * <p>签名改了会抛 NoSuchMethodException 当场红，不会静默跳过。
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> callAdmin(Object target, String method,
                                         Class<?>[] types, Object... args) {
        try {
            var m = target.getClass().getDeclaredMethod(method, types);
            m.setAccessible(true);
            return (Map<String, Object>) m.invoke(target, args);
        } catch (InvocationTargetException e) {
            // 业务异常必须原样抛出去。包成 InvocationTargetException 的话，
            // assertThrows(ApiException.class, ...) 一个都抓不到，
            // 所有负例都会以「抛了别的异常」红着 —— 而红的原因和被测的东西毫无关系。
            Throwable c = e.getCause();
            if (c instanceof RuntimeException re) throw re;
            if (c instanceof Error err) throw err;
            throw new IllegalStateException(c);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("后台接口的签名变了：" + method, e);
        }
    }

    static Map<String, Object> overview() {
        return callAdmin(overviewApi, "overview",
                new Class<?>[]{HttpServletRequest.class}, admin());
    }

    static Map<String, Object> grantApi(long agentId, long skuId, int copies,
                                        String reason, String key) {
        return callAdmin(grants, "grant",
                new Class<?>[]{AdminCodeGrantController.GrantReq.class, HttpServletRequest.class},
                new AdminCodeGrantController.GrantReq(agentId, skuId, copies, reason, key), admin());
    }

    static Map<String, Object> exportGrantApi(String key) {
        return callAdmin(grants, "exportGrant",
                new Class<?>[]{AdminCodeGrantController.ExportReq.class, HttpServletRequest.class},
                new AdminCodeGrantController.ExportReq(key), admin());
    }

    static Map<String, Object> revokeApi(long orderId, String reason) {
        return callAdmin(grants, "revokeOrderCodes",
                new Class<?>[]{long.class, AdminCodeGrantController.ReasonReq.class,
                               HttpServletRequest.class},
                orderId, new AdminCodeGrantController.ReasonReq(reason), admin());
    }

    static Map<String, Object> confirmTransferApi(long orderId, long amountFen, String txnNo) {
        return callAdmin(adminOrders, "confirmTransfer",
                new Class<?>[]{long.class, AdminOrderController.ConfirmReq.class,
                               HttpServletRequest.class},
                orderId, new AdminOrderController.ConfirmReq(txnNo, amountFen, null, null), admin());
    }

    static long count(String sql, Object... args) {
        Number n = jdbc.queryForObject(sql, Number.class, args);
        return n == null ? 0 : n.longValue();
    }

    static long num(Object v) { return ((Number) v).longValue(); }

    static List<Long> orderIdsOf(Map<String, Object> res) {
        @SuppressWarnings("unchecked")
        List<Number> raw = (List<Number>) res.get("orderIds");
        List<Long> out = new ArrayList<>();
        for (Number n : raw) out.add(n.longValue());
        return out;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> codesOf(Map<String, Object> res) {
        return (List<Map<String, Object>>) res.get("codes");
    }

    static List<Long> codeIdsOfOrder(long orderId) {
        return jdbc.queryForList("SELECT id FROM activation_code WHERE order_id=? ORDER BY id",
                Long.class, orderId);
    }

    /** 一次取全 agent 手上所有白送码的状态。G18 拿它做「一张都没被串到」的判据。 */
    static Map<Long, String> giftCodeStates(long agentId) {
        var out = new LinkedHashMap<Long, String>();
        jdbc.queryForList(
                "SELECT c.id, c.status FROM activation_code c JOIN biz_order o ON o.id=c.order_id"
              + " WHERE c.owner_agent_id=? AND o.origin='ADMIN_GIFT' ORDER BY c.id", agentId)
            .forEach(r -> out.put(num(r.get("id")), (String) r.get("status")));
        return out;
    }

    // ================================================================ G1 头号判据

    @Test @Order(1)
    @DisplayName("G1 白送不是钱：payment_record 与 commission_record 总行数一行都不许动")
    void giftMovesNoMoney() {
        long payBefore = count("SELECT COUNT(*) FROM payment_record");
        long comBefore = count("SELECT COUNT(*) FROM commission_record");
        long ordBefore = count("SELECT COUNT(*) FROM biz_order");
        long codeBefore = count("SELECT COUNT(*) FROM activation_code");

        var res = grantApi(AG_B2, SKU_10, 2, "首批赠送：门店开业物料", "GIFTKEY-G1");
        assertEquals(Boolean.TRUE, res.get("changed"));
        k1OrderIds = orderIdsOf(res);
        assertEquals(2, k1OrderIds.size(), "发 2 份就该是 2 张单");
        assertEquals(20, num(res.get("codesIssued")));

        // 数【总行数】而不是「这几张单上有没有」：按 order_id 圈的话，
        // 哪天有人把佣金记到别的单上（比如上级那一单）就查不出来了，
        // 而那正是最难发现的一种串台 —— 钱确实多出来了，只是挂在别处。
        assertEquals(payBefore, count("SELECT COUNT(*) FROM payment_record"),
                "白送单上挂出了支付流水：这张单从来没有收到过钱，"
                + "多出来的这一笔在银行对账时找不到对应的入账");
        assertEquals(comBefore, count("SELECT COUNT(*) FROM commission_record"),
                "白送单结出了佣金：平台一分钱没收，却要给一级代理付 510 元，"
                + "而这笔支出在营收报表里没有任何对应的收入");

        assertEquals(ordBefore + 2, count("SELECT COUNT(*) FROM biz_order"));
        assertEquals(codeBefore + 20, count("SELECT COUNT(*) FROM activation_code"));

        for (long id : k1OrderIds) {
            var o = jdbc.queryForMap("SELECT origin,status,paid_at,paid_amount_fen,grant_by,"
                    + "grant_reason,grant_key FROM biz_order WHERE id=?", id);
            assertEquals("ADMIN_GIFT", o.get("origin"));
            // 允许它以 CREATED 建出来的话，代理界面上它是一张待支付订单 ——
            // 代理会看见「去付款」按钮，付完就成了一笔真实收入，而它本来是白送的。
            assertEquals("FULFILLED", o.get("status"));
            assertNull(o.get("paid_at"), "白送单有了到账时间，它会立刻被总览的成交四项圈进窗口");
            assertEquals(0L, num(o.get("paid_amount_fen")));
            assertEquals(ADMIN, num(o.get("grant_by")), "没记下是谁发的，事后追不到人");
            assertEquals("首批赠送：门店开业物料", o.get("grant_reason"));
            assertTrue(String.valueOf(o.get("grant_key")).startsWith("GIFTKEY-G1#"),
                    "幂等键没落库，重复提交时没有任何东西能裁决");
        }
    }

    // ================================================================ G3 → G2

    @Test @Order(2)
    @DisplayName("G3 阴性对照：真收一笔钱，monthPaidFen 必须正好涨这一单的应付额")
    void realPaymentMovesMonthPaid() {
        var before = overview();
        long monthBefore = num(before.get("monthPaidFen"));
        long ordersBefore = num(before.get("monthOrders"));

        var created = orderSvc.create(U_B2, SKU_10, "10.0.0.7");
        realOrderId = created.orderId();
        var res = confirmTransferApi(realOrderId, created.amountFen(), "BANK_G3");
        assertEquals(Boolean.TRUE, res.get("changed"));

        var after = overview();
        // 没有这一条，G2 就是一条空断言：接口整个坏掉（比如成交那条 SQL 恒返回 0）
        // 也能让「发码前后逐字相等」绿得毫无破绽。
        assertEquals(monthBefore + created.amountFen(), num(after.get("monthPaidFen")),
                "真收了一笔钱而本月成交额没跟着涨 —— 总览的成交口径本身就是坏的，"
                + "接下来那条「白送不影响成交额」什么也证明不了");
        assertEquals(ordersBefore + 1, num(after.get("monthOrders")));
        assertTrue(num(after.get("monthPaidFen")) > 0,
                "基线必须非零：全零的基线下，两处口径一起漂移也是「逐字相等」");
    }

    @Test @Order(3)
    @DisplayName("G2 同源判据：发一批白送码，总览成交四项与发码前逐字相等")
    void giftDoesNotMoveOverview() {
        var before = overview();

        var res = grantApi(AG_B2, SKU_1, 1, "补发：上一批漏了一个码", "GIFTKEY-G2");
        assertEquals(Boolean.TRUE, res.get("changed"));

        var after = overview();
        // 逐字比对，不是断言等于 0：只钉字面值的话，成交口径和发码口径一起漂移
        // （比如两边都开始把 ADMIN_GIFT 算进来）用例照样绿。
        // 上一条已经保证了这四个数是活的、且不为零。
        for (String k : List.of("todayPaidFen", "monthPaidFen", "todayOrders", "monthOrders"))
            assertEquals(before.get(k), after.get(k),
                    "白送一批码之后总览的 " + k + " 变了：" + before.get(k) + " → " + after.get(k)
                    + "。平台一分钱没收，营收报表却涨了 —— 这笔差额在财务那边无法解释");
    }

    // ================================================================ G4 / G5 幂等

    @Test @Order(4)
    @DisplayName("G4 幂等（单线程）：同一个 grantKey 连提两次，第二次 200 changed:false 且一个码不多")
    void repeatedSubmitIssuesNothingMore() {
        long ordBefore = count("SELECT COUNT(*) FROM biz_order");
        long codeBefore = count("SELECT COUNT(*) FROM activation_code");

        var first = grantApi(AG_B2, SKU_1, 1, "赠送：客服补偿", "GIFTKEY-G4");
        assertEquals(Boolean.TRUE, first.get("changed"));

        // 第二次必须是一个【正常返回】。回 409 的话运营看到的是红色错误框，
        // 他的第一反应是「刚才那次没成功」，于是换个地方再发一批 —— 真的又发出去一批码。
        var second = assertDoesNotThrow(() -> grantApi(AG_B2, SKU_1, 1, "赠送：客服补偿", "GIFTKEY-G4"),
                "重复提交抛了异常。这不是「拦住了重复发货」，是把一次成功的发放显示成失败，"
                + "而运营会去别处找补");
        assertEquals(Boolean.FALSE, second.get("changed"));
        assertEquals(first.get("orderIds"), second.get("orderIds"),
                "重放没有把上一次的单查回来，运营拿不到那批码的去处");

        assertEquals(ordBefore + 1, count("SELECT COUNT(*) FROM biz_order"),
                "重复提交多建了一张单");
        assertEquals(codeBefore + 1, count("SELECT COUNT(*) FROM activation_code"),
                "重复提交多发了码 —— 多一个就是白送一门课");
    }

    @Test @Order(5)
    @DisplayName("G5 幂等（并发）：12 个线程同 grantKey，恰好 1 次是首次，码恰好 2 个")
    void concurrentGrantIsIdempotent() throws Exception {
        long codeBefore = count("SELECT COUNT(*) FROM activation_code");
        // copies 取 2 不是 1：一份的话，批内那个 "#" + i 后缀写成固定串（等于去掉批内区分）
        // 这条改动在本用例上没有任何表现 —— 而它正是这条路上最容易被"简化"掉的一处。
        int copies = 2;
        int n = 12;
        var pool = Executors.newFixedThreadPool(n);
        var start = new CountDownLatch(1);
        var firsts = new AtomicInteger();
        var errors = new CopyOnWriteArrayList<String>();
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            fs.add(pool.submit(() -> {
                try {
                    start.await();
                    var r = issue.grant(new ManualIssueService.GrantCmd(
                            AG_B2, SKU_1, copies, "赠送：并发双击", "GIFTKEY-G5", ADMIN, "10.9.9.9"));
                    if (r.firstTime()) firsts.incrementAndGet();
                } catch (Exception e) {
                    errors.add(e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }));
        }
        start.countDown();
        for (Future<?> f : fs) f.get(90, TimeUnit.SECONDS);
        pool.shutdown();

        // 断言的顺序是判据的一部分。FulfillServiceIT 里记着一次反例：先断言 firsts==1 的话，
        // 所有线程都抛异常（一个码都没发出去）时，报出来的是「幂等键没起到裁决作用」——
        // 一个完全错误的诊断，会把排查方向整个带偏。
        assertTrue(errors.isEmpty(), "并发过程中抛了异常，先修异常再谈幂等：" + errors);
        assertEquals(1, firsts.get(),
                "并发下有 " + firsts.get() + " 个连接认为自己是首次 —— uk_grant_key 没起到裁决作用");
        assertEquals(codeBefore + copies, count("SELECT COUNT(*) FROM activation_code"),
                "并发下发了 " + (count("SELECT COUNT(*) FROM activation_code") - codeBefore) + " 个码");
        assertEquals(copies, count("SELECT COUNT(*) FROM biz_order WHERE grant_key LIKE ?", "GIFTKEY-G5#%"),
                "一次发放的 " + copies + " 份没有各自落一张单：批内的 grant_key 后缀丢了，"
                + "那这一批里第二份往后全都撞在同一个幂等键上");
    }

    // ================================================================ G6 / G7 / G8 明文

    @Test @Order(6)
    @DisplayName("G6 白送的是真码：取明文 → 真激活 → 权益开出来、归属落到那个代理")
    void giftedCodeIsARealCode() {
        long orderId = k1OrderIds.get(0);
        long codeId = codeIdsOfOrder(orderId).get(0);

        // 走 revealForAdmin 而不是直接读库解密：hash 与 seal 的配对关系只有在
        // 「按 hash 存、按 hash 开」这条完整路径上走一遍才验得到。
        String plain = codeApi.revealForAdmin(ADMIN, codeId, "10.9.9.9");
        assertNotNull(plain);
        k1CodeIdBound = codeId;
        k1PlainOfBound = plain;

        var r = activation.redeem(STU_G6, plain, AGREEMENT, "10.9.9.9");
        assertTrue(r.firstTime());
        assertEquals(1L, r.courseId(), "开出来的权益不是这个码对应的课");

        var e = jdbc.queryForMap(
                "SELECT user_id, course_id, attributed_agent_id FROM enrollment WHERE activation_code_id=?",
                codeId);
        assertEquals(STU_G6, num(e.get("user_id")));
        assertEquals(1L, num(e.get("course_id")));
        assertEquals(AG_B2, num(e.get("attributed_agent_id")),
                "这个座位没算到发码给他的那家代理头上");
        assertEquals(AG_B2, num(jdbc.queryForMap(
                "SELECT attributed_agent_id FROM sys_user WHERE id=?", STU_G6)
                .get("attributed_agent_id")),
                "白送码带进来的人没有落归属：first-touch 是止争依据，事后补不回来");
        assertEquals("BOUND", jdbc.queryForObject(
                "SELECT status FROM activation_code WHERE id=?", String.class, codeId));
    }

    @Test @Order(7)
    @DisplayName("G7 明文不进审计：审计快照里搜不到那串码")
    void plaintextNeverLandsInAudit() {
        assertNotNull(k1PlainOfBound, "G6 没跑成，这条判据没有素材可搜");
        // AdminAuditController 会把 before_json / after_json 原样返回。明文一旦被顺手写进
        // 快照，等于给每一个能查审计的人留了一份永久可兑换的凭据 —— 而且是合规地留下的，
        // 事后没人会觉得那里有问题。
        assertEquals(0, count(
                "SELECT COUNT(*) FROM audit_log WHERE after_json LIKE ? OR before_json LIKE ?",
                "%" + k1PlainOfBound + "%", "%" + k1PlainOfBound + "%"),
                "审计日志里出现了激活码明文：" + k1PlainOfBound);
    }

    @Test @Order(8)
    @DisplayName("G8 明文不进响应：GrantResult 结构上没有可放明文的位置，响应里只有掩码")
    void grantResultHasNoPlaintextSlot() {
        // 结构性判据。只断言「这次的响应里没有明文」是拦不住的：
        // 下一个人加一个 List<String> plainCodes 字段，那条断言照样绿（它搜的是别的键）。
        for (var c : ManualIssueService.GrantResult.class.getRecordComponents()) {
            String n = c.getName().toLowerCase();
            boolean risky = (n.contains("plain") || n.contains("code")) && c.getType() == String.class;
            assertFalse(risky, "GrantResult 多了一个像是装明文的字段：" + c.getName()
                    + "。明文进了响应体，下一步就是有人把它顺手写进审计快照");
        }

        var res = grantApi(AG_B2, SKU_10, 1, "赠送：掩码自检", "GIFTKEY-G8");
        var codes = codesOf(res);
        assertEquals(10, codes.size());
        for (var c : codes)
            assertTrue(String.valueOf(c.get("masked")).contains("****"),
                    "响应里的码没有遮住中间段：" + c.get("masked"));
    }

    // ================================================================ G9 / G10 代理端

    @Test @Order(9)
    @DisplayName("G9 代理端看得见：白送的码标成 PLATFORM_GRANT，且带得出单号")
    void agentSeesGrantedCodes() {
        long orderId = k1OrderIds.get(1);
        var page = agentSvc.codes(U_B2, orderId, null, 1, 100);
        assertEquals(10, page.items().size());
        for (var m : page.items()) {
            assertEquals("PLATFORM_GRANT", m.get("source"),
                    "白送的码在代理端显示成 PURCHASE：它确实有一个真单号，"
                    + "但那笔单从来没有收到过钱，代理会拿它去对账而账上没有这笔");
            assertNotNull(m.get("orderNo"),
                    "「来源」那一列拿不到单号会静默显示成「—」，代理无从判断这批码是哪来的");
        }
    }

    @Test @Order(10)
    @DisplayName("G10 代理端导得出：明文能开出来，按发放单号搜也命中")
    void agentExportsGrantedCodes() {
        long orderId = k1OrderIds.get(1);
        var ids = codeIdsOfOrder(orderId);
        var body = agentExport.export(U_B2,
                new AgentCodeExportService.ExportReq(null, null, ids), "10.9.9.9");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) body.get("rows");
        assertEquals(ids.size(), rows.size());
        for (var r : rows) {
            // 这里才是 seal/hash 配对写反的真出口：库里插得进、掩码对、状态对，
            // 只有走到这一步才会炸，而那可能是发码几周之后。
            assertNotNull(r.get("code"), "导出里没有明文，代理拿不到能发出去的码");
            assertEquals("PLATFORM_GRANT", r.get("source"));
        }

        // 按订单号搜。钉的是 activation_code LEFT JOIN biz_order 那一处：
        // 改成 INNER 之后，孤儿码会整片从导出里静默消失 —— 不报错、不空，只是行数变少。
        String orderNo = jdbc.queryForObject(
                "SELECT order_no FROM biz_order WHERE id=?", String.class, orderId);
        var hit = agentExport.export(U_B2,
                new AgentCodeExportService.ExportReq(null, orderNo.substring(10), null), "10.9.9.9");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hitRows = (List<Map<String, Object>>) hit.get("rows");
        var hitIds = new ArrayList<Long>();
        for (var r : hitRows) hitIds.add(num(r.get("id")));
        assertTrue(hitIds.containsAll(ids),
                "按发放单号搜不到这批白送的码：代理在导出页上没有任何别的办法圈出它们");
    }

    // ================================================================ G11 / G12 前置校验

    @Test @Order(11)
    @DisplayName("G11 停用中的代理发不了码：409，且理由指向状态而不是别的什么")
    void suspendedAgentCannotBeGranted() {
        long codeBefore = count("SELECT COUNT(*) FROM activation_code");
        var e = assertThrows(ApiException.class,
                () -> grantApi(AG_SUSPENDED, SKU_1, 1, "赠送：给停用中的代理", "GIFTKEY-G11"));
        assertEquals(409, e.status(), "拒绝了，但状态码是 " + e.status() + "（" + e.getMessage()
                + "）—— 不是状态闸拦下的，这个负例什么也没证明");
        assertTrue(e.getMessage().contains("不是有效状态"),
                "拒绝理由不是代理状态问题 —— " + e.getMessage());
        assertEquals(codeBefore, count("SELECT COUNT(*) FROM activation_code"));
    }

    @Test @Order(12)
    @DisplayName("G12 课程还在 DRAFT 也照样能发码：与赠课那条路给同一个答案")
    void draftCourseStillGrantable() {
        jdbc.update("UPDATE course SET status='DRAFT' WHERE id=1");
        try {
            // 赠课那条路（AdminUserController.grant）刻意允许给未上架的课开权益，
            // 理由是「内部账号常常要在上架前先看」。哪天有人顺手给发码路径补一条
            // course.status='ON' 校验，同一件事在两条路上就给出相反的答案，
            // 而报出来的错看起来像调用方填错了参数。
            var res = grantApi(AG_B2, SKU_1, 1, "赠送：内部账号上架前预览", "GIFTKEY-G12");
            assertEquals(Boolean.TRUE, res.get("changed"));
            assertEquals(1, num(res.get("codesIssued")));
        } finally {
            jdbc.update("UPDATE course SET status='ON' WHERE id=1");
        }
    }

    // ================================================================ G13 / G14 钱的入口

    @Test @Order(13)
    @DisplayName("G13 白送单确认不了到账：409，而且 payment_record 一行都没多")
    void confirmTransferRejectsGiftOrder() {
        long orderId = k1OrderIds.get(0);
        long payBefore = count("SELECT COUNT(*) FROM payment_record");

        var e = assertThrows(ApiException.class,
                () -> confirmTransferApi(orderId, 349000L, "BANK_G13"));
        assertEquals(409, e.status(), "拒绝了，但状态码是 " + e.status() + "：" + e.getMessage());
        assertTrue(e.getMessage().contains("不能确认到账"),
                "拒绝理由不是「这单不收钱」—— " + e.getMessage());

        // 这后半句才是重点。挡在 FulfillService 之后的话，真跑一遍是：payment_record 先插成功
        //（一笔假流水就此落库，还吃掉一个 uk_provider_txn 名额），再 UPDATE ... WHERE
        // status='CREATED' 影响 0 行 —— 结果是一张 paid_at 仍为 NULL 的白送单上挂着一条
        // SUCCESS 的假流水，总览四项纹丝不动，运营看到的是「已确认到账，本单已出 0 个激活码」。
        assertEquals(payBefore, count("SELECT COUNT(*) FROM payment_record"),
                "被拒之后仍然留下了一条支付流水：这是一笔银行那边找不到对应入账的假记录");
    }

    @Test @Order(14)
    @DisplayName("G14 白送单开不出发票：不出现在可开票列表里，硬点它 400")
    void giftOrderIsNotInvoiceable() {
        long giftOrderId = k1OrderIds.get(1);
        var list = invoices.invoiceableOrders(U_B2, 50);
        for (var row : list)
            assertNotEquals(giftOrderId, num(row.get("orderId")),
                    "白送单出现在可开票列表里：平台从未收到过这笔钱，"
                    + "给它开票是这条链的类注释自己定的刑事风险红线");
        // 列表里挡住了不等于 apply 挡住了：前端拿到 orderId 直接 POST 是完全可行的一条路。
        var e = assertThrows(ApiException.class,
                () -> invoices.apply(U_B2, giftOrderId, "某某科技有限公司", null));
        assertEquals(400, e.status(), "拒绝了，但状态码是 " + e.status() + "：" + e.getMessage());
        assertTrue(e.getMessage().contains("从来没有收到过钱"),
                "拒绝理由不是「这单没收过钱」—— " + e.getMessage());
        // 列表里至少要有一张真单，否则上面那个 for 循环是在一个空列表上转圈
        assertFalse(list.isEmpty(), "可开票列表整个是空的 —— 上面那条断言什么也没检查");
    }

    // ================================================================ G15 - G17 作废

    @Test @Order(15)
    @DisplayName("G15 作废：未激活的转 REVOKED 且三样都落库；已激活的那张库层直接拒")
    void revokeIssuedButNotBound() {
        long orderId = k1OrderIds.get(0);
        var res = revokeApi(orderId, "赠送对象已终止合作");
        assertEquals(Boolean.TRUE, res.get("changed"));
        assertEquals(9, num(res.get("revoked")), "这一单 10 个码里有 1 个已激活，该作废 9 个");

        var rows = jdbc.queryForList(
                "SELECT id,status,revoked_by,revoked_at,revoke_reason FROM activation_code"
              + " WHERE order_id=? AND id<>? ORDER BY id", orderId, k1CodeIdBound);
        for (var r : rows) {
            assertEquals("REVOKED", r.get("status"));
            // 三样一次写全。少写 revoked_by，事后没人知道是谁按的那一下；
            // 少写 revoke_reason，代理来问「我的码怎么用不了了」时答不上来。
            assertEquals(ADMIN, num(r.get("revoked_by")), "没记下是谁作废的");
            assertNotNull(r.get("revoked_at"), "没记下什么时候作废的");
            assertEquals("赠送对象已终止合作", r.get("revoke_reason"));
        }

        // 已激活的那张仍然可用 —— 学员已经在看课了。
        assertEquals("BOUND", jdbc.queryForObject(
                "SELECT status FROM activation_code WHERE id=?", String.class, k1CodeIdBound));

        // 绕开应用层那句 WHERE status='ISSUED' 直接改。验的是 trg_activation_code_state
        // 真的在，而不是「应用层答应过不会这么写」——
        // 那句 WHERE 是一个承诺，触发器才是执行。
        var e = assertThrows(DataAccessException.class, () -> jdbc.update(
                "UPDATE activation_code SET status='REVOKED', revoked_by=?, revoked_at=NOW(3),"
              + " revoke_reason=? WHERE id=?", ADMIN, "硬撤", k1CodeIdBound));
        assertTrue(String.valueOf(e.getMessage()).contains("激活即不退"),
                "拒绝的不是那条「激活即不退」的触发器 —— " + e.getMessage());
    }

    @Test @Order(16)
    @DisplayName("G16 作废只对白送单开放：收过钱的单 409，且文案把人指向退款")
    void revokeRejectsSelfServeOrder() {
        var e = assertThrows(ApiException.class, () -> revokeApi(realOrderId, "想直接撤掉"));
        assertEquals(409, e.status(), "拒绝了，但状态码是 " + e.status() + "：" + e.getMessage());
        // 作废收过钱的单上的码而不退钱，对账表是完全平的：实付一分不动、退款表不加行、
        // 佣金不冲正 —— 只有代理会来问「我的 20 个码怎么没了」。所以拒绝时必须给出去处。
        assertTrue(e.getMessage().contains("退款"),
                "拒绝了但没告诉运营该走哪条路，他的下一步是找研发直接改库 —— " + e.getMessage());
        assertEquals(0, count(
                "SELECT COUNT(*) FROM activation_code WHERE order_id=? AND status='REVOKED'",
                realOrderId), "被拒之后仍然作废掉了收过钱的那一单上的码");
    }

    @Test @Order(17)
    @DisplayName("G17 作废幂等：连按两次，第二次 200 changed:false")
    void revokeIsIdempotent() {
        var res = grantApi(AG_B2, SKU_10, 1, "赠送：作废幂等自检", "GIFTKEY-G17");
        long orderId = orderIdsOf(res).get(0);

        var first = revokeApi(orderId, "发错了对象");
        assertEquals(Boolean.TRUE, first.get("changed"));
        assertEquals(10, num(first.get("revoked")));

        // 第二次回 409 的话，运营会以为第一次没生效 —— 而那批码其实已经失效了。
        var second = assertDoesNotThrow(() -> revokeApi(orderId, "发错了对象"),
                "重复作废抛了异常，运营会以为第一次没生效");
        assertEquals(Boolean.FALSE, second.get("changed"));
        assertEquals(0, num(second.get("revoked")));
    }

    // ================================================================ G18 退款不串台

    @Test @Order(18)
    @DisplayName("G18 退款只动它自己那一单：白送的码逐张状态不变")
    void refundDoesNotTouchGiftCodes() {
        var giftBefore = giftCodeStates(AG_B2);
        assertFalse(giftBefore.isEmpty(), "手上一张白送码都没有 —— 这条判据没有素材");

        long paid = count("SELECT paid_amount_fen FROM biz_order WHERE id=?", realOrderId);
        long qty = count("SELECT code_qty_snapshot FROM biz_order WHERE id=?", realOrderId);
        long amount = paid / qty;                       // 只退一个码的钱

        var applied = refunds.apply(admin(), realOrderId, amount, "UNUSED_CODES", "学员退了一个名额");
        long refundId = num(applied.get("refundId"));
        refunds.approve(admin(), refundId);
        refunds.settle(admin(), refundId, "PRF_G18");

        // 先确认退款【真的作废了东西】。它一张都没作废的话，
        // 下面「白送码没变」就是一条在什么都没发生的系统上做的空断言。
        assertEquals(1, count(
                "SELECT COUNT(*) FROM activation_code WHERE order_id=? AND status='REVOKED'",
                realOrderId), "退款没有作废任何一张码，这条对照失去意义");

        assertEquals(giftBefore, giftCodeStates(AG_B2),
                "一次自助单的退款把白送的码也带走了：那批码是平台送出去的、与这笔退款无关，"
                + "代理只会看到「我的赠品凭空没了」而找不到任何原因");
    }

    // ================================================================ G19 代理统计

    @Test @Order(19)
    @DisplayName("G19 代理统计同源：白送只涨 granted 与库存，成交额与订单数逐字不变")
    void agentStatsCountsGiftSeparately() {
        var before = agentSvc.stats(U_B2, null);
        int copies = 3;

        var res = grantApi(AG_B2, SKU_1, copies, "赠送：统计口径自检", "GIFTKEY-G19");
        assertEquals(Boolean.TRUE, res.get("changed"));

        var after = agentSvc.stats(U_B2, null);
        assertEquals(num(before.get("grantedCodes")) + copies, num(after.get("grantedCodes")),
                "白送的码没有单独计数：代理看不出手上哪些货是自己进的、哪些是平台送的");
        assertEquals(num(before.get("unactivatedStock")) + copies, num(after.get("unactivatedStock")));
        assertEquals(num(before.get("purchasedCodes")) + copies, num(after.get("purchasedCodes")));

        // 这四项是钱。白送单 paid_at 恒 NULL，它们必须逐字不变。
        for (String k : List.of("paidOrders", "purchasedAmountFen",
                                "refundedAmountFen", "netPurchasedAmountFen"))
            assertEquals(before.get(k), after.get(k),
                    "白送一批码之后代理的 " + k + " 变了：" + before.get(k) + " → " + after.get(k)
                    + "。代理会以为自己多进了一批货，然后拿这个数来对账");

        // 恒等式：把 granted 从 purchased 里切出去（而不是当成额外一维）会当场破了它，
        // 两个数字各自都对，而代理会当成系统算错来投诉。
        assertEquals(num(after.get("purchasedCodes")),
                num(after.get("activatedCodes")) + num(after.get("unactivatedStock"))
              + num(after.get("expiredCodes")) + num(after.get("revokedCodes")),
                "已激活 + 未激活 + 过期 + 作废 ≠ 进货数");
    }

    // ================================================================ G20 总览提示

    @Test @Order(20)
    @DisplayName("G20 总览提示：白送码被激活时说明「激活涨了成交额没涨」，而成交额确实没涨")
    void overviewExplainsGiftActivations() {
        var res = grantApi(AG_B2, SKU_1, 1, "赠送：总览提示自检", "GIFTKEY-G20");
        long codeId = codeIdsOfOrder(orderIdsOf(res).get(0)).get(0);

        var before = overview();
        activation.redeem(STU_G20, codeApi.revealForAdmin(ADMIN, codeId, "10.9.9.9"),
                AGREEMENT, "10.9.9.9");
        var after = overview();

        @SuppressWarnings("unchecked")
        List<String> caveats = (List<String>) after.get("caveats");
        assertTrue(caveats.stream().anyMatch(s -> s.contains("平台手工发放")),
                "激活数涨了、成交额没涨，而总览一句话都不说 —— "
                + "运营对这种差异的第一反应是「统计漏了记录」，然后去别处找补。caveats=" + caveats);
        assertEquals(before.get("monthPaidFen"), after.get("monthPaidFen"),
                "白送码被激活之后本月成交额动了");
        assertEquals(before.get("monthOrders"), after.get("monthOrders"));
    }

    // ================================================================ G21 上限

    @Test @Order(21)
    @DisplayName("G21 两条上限都拦得住，且拦下时库里一个码都没多")
    void grantRespectsBothCaps() {
        long codeBefore = count("SELECT COUNT(*) FROM activation_code");

        var tooManyCopies = assertThrows(ApiException.class,
                () -> grantApi(AG_B2, SKU_1, ManualIssueService.MAX_COPIES + 1,
                        "赠送：份数超限", "GIFTKEY-G21-A"));
        assertEquals(400, tooManyCopies.status(), tooManyCopies.getMessage());
        assertTrue(tooManyCopies.getMessage().contains("最多发 " + ManualIssueService.MAX_COPIES + " 份"),
                "拒绝理由不是份数上限 —— " + tooManyCopies.getMessage());

        // 份数没超、总码数超。上限的依据在下游：代理端激活码列表是全量读回再内存分页，
        // 导出侧超过硬顶直接 400 让人「缩小范围」，而缩小范围的手段在代理端界面上够不到。
        int copies = ManualIssueService.MAX_CODES / 50 + 1;      // 5 × 50 = 250 > 200
        assertTrue(copies <= ManualIssueService.MAX_COPIES,
                "这个用例本想只压总码数那条闸，结果先撞上了份数闸，什么也没测到");
        var tooManyCodes = assertThrows(ApiException.class,
                () -> grantApi(AG_B2, SKU_50, copies, "赠送：总码数超限", "GIFTKEY-G21-B"));
        assertEquals(400, tooManyCodes.status(), tooManyCodes.getMessage());
        assertTrue(tooManyCodes.getMessage().contains("最多发 " + ManualIssueService.MAX_CODES + " 个码"),
                "拒绝理由不是总码数上限 —— " + tooManyCodes.getMessage());

        // 被拒之后库里必须干净。校验在建单之前就该做完；放到循环里逐份检查的话，
        // 前几份已经发出去了，剩下的报错 —— 运营看到的是一条错误信息，
        // 而代理手上已经多了一批他不知道从哪来的码。
        assertEquals(codeBefore, count("SELECT COUNT(*) FROM activation_code"),
                "上限拦下之后仍然发出了码");
        assertEquals(0, count("SELECT COUNT(*) FROM biz_order WHERE grant_key LIKE ?", "GIFTKEY-G21-%"),
                "上限拦下之后仍然留下了单");
    }

    // ================================================================ G22 幂等键的形状

    /**
     * 幂等键会被拼进 {@code LIKE key + "#%"}（replay、批量明文导出、订单筛选三处）。
     *
     * <p>不收形状的话，传一个 {@code %} 进来就是 {@code LIKE '%#%'} ——
     * <b>一次把全库白送码的明文导出来</b>。它是超管接口、有 500 上限、每个码逐条写审计，
     * 所以这不是「谁都能拿」，而是「一次误触/一次越权就是全量泄露，而且长得像一次正常导出」。
     *
     * <p>这条用例钉的是「形状在进 SQL 之前就被拒」。删掉 requireGrantKey 的话，
     * 下面第一条会变成一次成功的全量导出 —— 而返回体看起来完全正常。
     */
    @Test @Order(22)
    @DisplayName("G22 幂等键收形状：通配符进不了 LIKE")
    void grantKeyShapeIsConstrained() {
        // 先确认库里真有白送单，否则「导不出来」可能只是因为没东西可导，
        // 这条用例就退化成一个永远绿的空断言
        assertTrue(count("SELECT COUNT(*) FROM biz_order WHERE origin='ADMIN_GIFT'") > 0,
                "库里没有白送单，这条用例测不到全量泄露");

        var wild = assertThrows(ApiException.class, () -> exportGrantApi("%"),
                "grantKey='%' 被放行了 —— 那是一次把全库白送码明文导出来");
        assertEquals(400, wild.status(), wild.getMessage());

        // 下划线也是 LIKE 通配符，只是一次只吃一个字符 —— 同样能跨批命中
        assertEquals(400, assertThrows(ApiException.class,
                () -> exportGrantApi("________________________________________")).status());

        // `#` 是我们拼批内序号用的分隔符。键里再出现一个，「k#0」到底是第 0 份
        // 还是键本身就叫「k#0」就分不清了 —— 而分不清的表现是重放查回别人的批次
        assertEquals(400, assertThrows(ApiException.class,
                () -> exportGrantApi("abcdefgh#0")).status());

        // 发码那一侧同一道闸：不收的话 `%#0` 会成为一个真实存在的键，
        // 第二次提交就以 LIKE '%#%' 把全部白送单当成「这一批」查回来
        var badGrant = assertThrows(ApiException.class,
                () -> grantApi(AG_B2, SKU_1, 1, "赠送：键里带通配符", "%"));
        assertEquals(400, badGrant.status(), badGrant.getMessage());

        // 正常的 UUID 必须照常放行 —— 否则这道闸就是把功能关掉，不是把洞堵上
        var ok = grantApi(AG_B2, SKU_1, 1, "赠送：正常 UUID 键",
                "d290f1ee-6c54-4b01-90e6-d701748f0851");
        assertEquals(Boolean.TRUE, ok.get("ok"));
        var exported = exportGrantApi("d290f1ee-6c54-4b01-90e6-d701748f0851");
        assertEquals(1, ((List<?>) exported.get("rows")).size(),
                "正常键导出的行数不对 —— 它只该拿到自己这一批");
    }
}