package tech.dragonai.wangke.trade;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionTemplate;
import tech.dragonai.wangke.ItDb;
import tech.dragonai.wangke.admin.AdminAudit;
import tech.dragonai.wangke.admin.AdminCodeExportService;
import tech.dragonai.wangke.admin.AdminOverviewController;
import tech.dragonai.wangke.admin.AdminStudentCodeController;
import tech.dragonai.wangke.auth.AuthInterceptor;
import tech.dragonai.wangke.learn.ActivationService;
import tech.dragonai.wangke.web.ApiException;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 超管一次生成一批<b>学员</b>激活码，归属可选。跑在真实 MySQL 上（库名 {@code wangke_stucode_it}）。
 *
 * <p>这条路径的形状与「给代理发码」同源（一张 {@code origin='ADMIN_GIFT'} 的合成订单 +
 * 复用 {@code FulfillService.issueCodes}），但它多了一个别处没有的东西：
 * <b>一批不属于任何代理的码</b>。于是这里要守的不是「码能不能发出来」，
 * 是这三件事 —— 每一件坏掉时都<b>一条错都不报</b>：
 *
 * <ol>
 *   <li><b>不归属就是真的不归属。</b>{@code owner_agent_id} 为 NULL 的码被激活之后，
 *       {@code enrollment.attributed_agent_id} 与 {@code sys_user.attributed_agent_id}
 *       必须仍是 NULL、佣金必须一行不多。写错的表现是几个月后某家代理的结算单上
 *       多出一批他从没卖过的学员，而 first-touch 归属那时已经永久锁死、改不回来。
 *       所以 S4 是<b>真的拿一个学员去 redeem</b>，不是断言这一列插进去是 NULL。
 *   <li><b>它不能变成钱。</b>不进 payment_record、不结佣金、不进总览成交四项。
 *       坏掉时财务对账多出来的那几千块钱，没有任何一行日志能解释它从哪来。
 *   <li><b>它必须是真码。</b>明文 → hash → seal(plain,hash) 顺序抄反的表现是插得进、
 *       掩码对、状态对，只有几周后有人点「导出」时才炸。所以 S4 真取一次明文再真激活。
 * </ol>
 *
 * <p>每个负例都<b>钉住失败原因</b>（HTTP 码 + 关键词）：只断言「抛了异常」的用例
 * 太容易因为别的原因红着，那样它绿的时候什么也没证明。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StudentCodeBatchIT {

    /**
     * 库名必须是全新的一个。
     *
     * <p>与别的 IT 撞名的后果不是「数据串了」这么温和：{@code ItDb.freshDatabase} 第一句是
     * {@code DROP DATABASE IF EXISTS}，两个类先后跑同一个库名时，后启动的那个会把前一个
     * 正在用的库连根拔掉，前一个报出来的是「表不存在」—— 一句完全指不到根因的错。
     */
    static final String DB = "wangke_stucode_it";

    static final long ADMIN = 1L;                       // seed.sql 里的平台超管
    static final long U_A1 = 10L, U_B2 = 11L, U_SUSPENDED = 12L;
    static final long AG_A1 = 1L, AG_B2 = 2L, AG_SUSPENDED = 3L;
    /** 三个全新学员：S4 用不归属的码、S6 用归属代理的码、S9 备用。 */
    static final long STU_PLAIN = 20L, STU_OWNED = 21L, STU_SPARE = 22L;
    static final long SKU_AGENT_10 = 2L;
    static final long STUDENT_UNIT_FEN = 79900L;        // seed.sql：学员直购 799 元
    static final String AGREEMENT = "v2026-09-01";

    static DataSource ds;
    static JdbcTemplate jdbc;
    static StudentCodeBatchService svc;
    static AdminStudentCodeController api;
    static AdminCodeExportService export;
    static ActivationService activation;
    static OrderService orderSvc;
    static FulfillService fulfill;
    static AdminOverviewController overviewApi;

    /** S1 那一批：5 个不归属的码。后面好几条判据拿它当素材。 */
    static long b1OrderId;
    static String b1PlainOfRedeemed;                    // S4 真激活掉的那个码的明文
    /** S5 那一批：3 个归属 B2 的码。 */
    static long b2OrderId;

    @BeforeAll
    static void bootstrap() throws Exception {
        ds = ItDb.freshDatabase(DB);
        jdbc = new JdbcTemplate(ds);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        var pricing = new PricingService(jdbc);
        var vault = new CodeVault(ItDb.testKey(77),
                "student-batch-salt-not-for-production".getBytes(StandardCharsets.UTF_8));

        fulfill = new FulfillService(jdbc, pricing, tx, vault);
        orderSvc = new OrderService(jdbc, pricing);
        activation = new ActivationService(jdbc, tx, vault);
        export = new AdminCodeExportService(jdbc, tx, vault);
        overviewApi = new AdminOverviewController(jdbc);

        svc = new StudentCodeBatchService(jdbc, orderSvc, pricing, fulfill, tx);
        api = new AdminStudentCodeController(jdbc, svc, new CodeService(jdbc, vault),
                new AdminAudit(jdbc));

        jdbc.update("INSERT INTO sys_user (id,phone,role) VALUES "
                  + "(10,'13100000010','AGENT'),(11,'13100000011','AGENT'),(12,'13100000012','AGENT')");
        jdbc.update("INSERT INTO sys_user (id,phone,role) VALUES "
                  + "(20,'13200000020','STUDENT'),(21,'13200000021','STUDENT'),"
                  + "(22,'13200000022','STUDENT')");
        jdbc.update("INSERT INTO agent_profile (id,user_id,agent_code,level,status)"
                  + " VALUES (1,10,'A1',1,'ACTIVE')");
        // B2 是二级且有上级 —— 这是有意选的：它买东西【会】结佣金（seed 里 buyer_level=2
        // 的两条规则），所以 S0 的阳性对照才有牙齿，而「白送不结佣金」才不是一条
        // 本来就恒真的空断言。
        jdbc.update("INSERT INTO agent_profile (id,user_id,agent_code,level,parent_agent_id,status)"
                  + " VALUES (2,11,'B2',2,1,'ACTIVE')");
        jdbc.update("INSERT INTO agent_profile (id,user_id,agent_code,level,parent_agent_id,status)"
                  + " VALUES (3,12,'B3',2,1,'SUSPENDED')");
    }

    @AfterAll
    static void teardown() { ItDb.close(ds); }

    // ================================================================ 夹具

    static MockHttpServletRequest admin() {
        var r = new MockHttpServletRequest();
        r.setRequestURI("/api/admin/probe");
        r.setAttribute(AuthInterceptor.ATTR_USER_ID, ADMIN);
        r.setAttribute(AuthInterceptor.ATTR_ROLE, "PLATFORM_ADMIN");
        r.setRemoteAddr("10.9.9.9");
        return r;
    }

    /**
     * 后台控制器方法是<b>包内可见</b>的，而这个测试住在 trade 包里。
     *
     * <p>用反射调，不为了让测试够得着就把生产方法改成 public：可见性一旦放宽，
     * 下一个人会顺着这个口子从别的包直接调进来，而 AdminGuard 是按 <b>URL 前缀</b>挂的、
     * 对进程内直调完全不生效。签名改了会抛 NoSuchMethodException 当场红，不会静默跳过。
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
            // assertThrows(ApiException.class, …) 一个都抓不到，所有负例都会以
            // 「抛了别的异常」红着 —— 而红的原因和被测的东西毫无关系。
            Throwable c = e.getCause();
            if (c instanceof RuntimeException re) throw re;
            if (c instanceof Error err) throw err;
            throw new IllegalStateException(c);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("后台接口的签名变了：" + method, e);
        }
    }

    static Map<String, Object> createApi(Integer qty, Long ownerAgentId, String reason, String key) {
        return callAdmin(api, "create",
                new Class<?>[]{AdminStudentCodeController.BatchReq.class, HttpServletRequest.class},
                new AdminStudentCodeController.BatchReq(qty, ownerAgentId, reason, key), admin());
    }

    static Map<String, Object> listApi(String owner) {
        return callAdmin(api, "list",
                new Class<?>[]{String.class, String.class, Integer.class, Integer.class,
                               HttpServletRequest.class},
                owner, null, 1, 100, admin());
    }

    static Map<String, Object> overview() {
        return callAdmin(overviewApi, "overview",
                new Class<?>[]{HttpServletRequest.class}, admin());
    }

    static long count(String sql, Object... args) {
        Number n = jdbc.queryForObject(sql, Number.class, args);
        return n == null ? 0 : n.longValue();
    }

    static long num(Object v) { return ((Number) v).longValue(); }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> itemsOf(Map<String, Object> res) {
        return (List<Map<String, Object>>) res.get("items");
    }

    /** 这一批的全部明文。走生产那条导出（解密 + 逐条审计同一个事务），不自己解密。 */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> exportPlain(long orderId) {
        var r = export.exportByOrders(ADMIN, List.of(orderId), "ORDER_EXPORT", "10.9.9.9");
        return (List<Map<String, Object>>) r.get("rows");
    }

    static ApiException expectApi(int status, String keyword, Runnable body) {
        var e = assertThrows(ApiException.class, body::run);
        assertEquals(status, e.status(), "错的是状态码，说明拦住它的不是我们以为的那道闸："
                + e.getMessage());
        assertTrue(e.getMessage().contains(keyword),
                "报错里没有「" + keyword + "」：拦住它的可能是另一条规则，"
                + "这条用例绿着也证明不了什么。实际是：" + e.getMessage());
        return e;
    }

    // ================================================================ S0 阳性对照

    /**
     * 先证明三个计数器都是活的。
     *
     * <p>没有这一条，后面每一句「白送之后 X 一点没动」都可能是因为 X 本来就永远不动 ——
     * 比如佣金那条链整个坏掉，「白送不结佣金」照样绿得毫无破绽。
     */
    @Test @Order(1)
    @DisplayName("S0 阳性对照：真收一笔钱 → 支付流水 +1、佣金 +1、本月成交额涨这一单")
    void baselineCountersAreAlive() {
        var before = overview();
        long payBefore = count("SELECT COUNT(*) FROM payment_record");
        long comBefore = count("SELECT COUNT(*) FROM commission_record");

        var created = orderSvc.create(U_B2, SKU_AGENT_10, "10.0.0.7");
        var r = fulfill.confirmPaid(created.orderId(), "BANK_TRANSFER", "BANK_S0",
                created.amountFen(), LocalDateTime.now(), ADMIN);
        assertTrue(r.firstTime());
        assertEquals(10, r.codesIssued());

        assertEquals(payBefore + 1, count("SELECT COUNT(*) FROM payment_record"));
        assertEquals(comBefore + 1, count("SELECT COUNT(*) FROM commission_record"),
                "二级代理买了一单而上级没结出佣金 —— 佣金这条链本身就是坏的，"
                + "接下来那句「白送不结佣金」什么也证明不了");

        var after = overview();
        assertEquals(num(before.get("monthPaidFen")) + created.amountFen(),
                num(after.get("monthPaidFen")),
                "真收了一笔钱而本月成交额没跟着涨 —— 成交口径本身就是坏的");
        assertTrue(num(after.get("monthPaidFen")) > 0,
                "基线必须非零：全零的基线下，两处口径一起漂移也是「逐字相等」");
    }

    // ================================================================ S1 不归属那一批

    @Test @Order(2)
    @DisplayName("S1 生成 5 个不归属的码：码数对、owner_agent_id 全为 NULL、实付 0、佣金 0 行")
    void unattributedBatch() {
        var beforeOverview = overview();
        long payBefore = count("SELECT COUNT(*) FROM payment_record");
        long comBefore = count("SELECT COUNT(*) FROM commission_record");
        long codeBefore = count("SELECT COUNT(*) FROM activation_code");

        var res = createApi(5, null, "地推活动：现场扫码送课", "STUKEY-S1-AAAA");
        assertEquals(Boolean.TRUE, res.get("changed"));
        assertEquals(5L, num(res.get("codesIssued")));
        assertNull(res.get("ownerAgentId"), "不归属那一档回了一个代理 ID，界面会照它显示归属");
        b1OrderId = num(res.get("orderId"));

        assertEquals(codeBefore + 5, count("SELECT COUNT(*) FROM activation_code"));
        assertEquals(5, count("SELECT COUNT(*) FROM activation_code WHERE order_id=?", b1OrderId));
        // 「一个都不属于任何代理」要按 NOT NULL 数，不能只抽查第一条：
        // 少数几条被写上归属时，抽查是抽不出来的，而那几个学员会被永久算给那家代理
        assertEquals(0, count("SELECT COUNT(*) FROM activation_code"
                + " WHERE order_id=? AND owner_agent_id IS NOT NULL", b1OrderId),
                "有码被写上了归属代理：这批是平台直营的，被写上的那几个学员激活后"
                + "会被永久算成那家代理带来的，而 first-touch 归属改不回来");

        var o = jdbc.queryForMap("SELECT origin,buyer_type,status,paid_at,paid_amount_fen,"
                + "amount_fen,code_qty_snapshot,buyer_agent_id,attributed_agent_id,"
                + "grant_by,grant_reason,grant_key FROM biz_order WHERE id=?", b1OrderId);
        assertEquals("ADMIN_GIFT", o.get("origin"));
        assertEquals("STUDENT", o.get("buyer_type"),
                "买家类型写成了 AGENT：后台按买家类型筛订单时，这批学员码会归到代理订单里");
        // 允许它以 CREATED 建出来的话，界面上它就是一张待支付订单，付完就成了一笔真实收入
        assertEquals("FULFILLED", o.get("status"));
        assertNull(o.get("paid_at"), "白送单有了到账时间，它会立刻被总览的成交四项圈进窗口");
        assertEquals(0L, num(o.get("paid_amount_fen")));
        assertNull(o.get("buyer_agent_id"),
                "归属被塞进了 buyer_agent_id：那是「谁进的货」，这批码会被算成那家代理的进货量，"
                + "而他一分钱没付");
        assertNull(o.get("attributed_agent_id"));
        // 应付记目录价 × N。写 0 的话 ck_order_amount 当场拒（响的）；写单价不乘 N 是哑的：
        // 「平台送出去多少钱的货」这本账从此少记 N-1 份，而没有任何一处会对不上
        assertEquals(STUDENT_UNIT_FEN * 5, num(o.get("amount_fen")));
        assertEquals(5, ((Number) o.get("code_qty_snapshot")).intValue(),
                "code_qty_snapshot 不等于这批的码数：trg_activation_code_quota 按它收口，"
                + "写大了这张单以后永远还能再补码进去");
        assertEquals(ADMIN, num(o.get("grant_by")), "没记下是谁发的，事后追不到人");
        assertEquals("地推活动：现场扫码送课", o.get("grant_reason"));
        assertEquals("STUKEY-S1-AAAA#0", o.get("grant_key"),
                "落库的幂等键没带 #0 后缀：全仓三处按 grant_key LIKE key+'#%' 把一批拢在一起，"
                + "这批单在那三处一条都查不出来，而它们只会显示「共 0 张单」");

        assertEquals(payBefore, count("SELECT COUNT(*) FROM payment_record"),
                "白送单上挂出了支付流水：这张单从来没有收到过钱，"
                + "多出来的这一笔在银行对账时找不到对应的入账");
        assertEquals(comBefore, count("SELECT COUNT(*) FROM commission_record"),
                "白送单结出了佣金：平台一分钱没收，却要付一笔佣金出去，"
                + "而它冻结期满就自动进余额，此后与真佣金不可分辨");

        var afterOverview = overview();
        // 逐字比对，不是断言等于 0：只钉字面值的话，成交口径和发码口径一起漂移
        // （比如两边都开始把 ADMIN_GIFT 算进来）用例照样绿。S0 已保证这四个数是活的且非零。
        for (String k : List.of("todayPaidFen", "monthPaidFen", "todayOrders", "monthOrders"))
            assertEquals(beforeOverview.get(k), afterOverview.get(k),
                    "生成一批白送的学员码之后总览的 " + k + " 变了："
                    + beforeOverview.get(k) + " → " + afterOverview.get(k)
                    + "。平台一分钱没收，营收报表却涨了 —— 这笔差额在财务那边无法解释");
    }

    // ================================================================ S2 明文与审计

    @Test @Order(3)
    @DisplayName("S2 明文导得出来（seal 的 AAD 绑对了），而且明文一个字都没进审计日志")
    void plaintextExportableAndNeverAudited() {
        var rows = exportPlain(b1OrderId);      // 解不开会在这里抛 AEADBadTagException
        assertEquals(5, rows.size());
        b1PlainOfRedeemed = (String) rows.get(0).get("code");
        assertNotNull(b1PlainOfRedeemed);
        for (var r : rows)
            assertEquals("PLATFORM_GRANT", r.get("source"),
                    "来源显示成 PURCHASE：这批码从来没有人付过钱，"
                    + "让它显示成一个看起来正常的成交，事后没人分得清哪些是送出去的");

        // 明文一旦进了审计快照，AdminAuditController 会原样把 before/after_json 返回给
        // 所有能查审计的人 —— 等于留了一份永久可兑换的凭据。这条判据扫的是【全表】，
        // 不是只看这一次的那几行：写进去的可能是任何一条动作的快照。
        for (var r : rows) {
            String plain = (String) r.get("code");
            assertEquals(0, count("SELECT COUNT(*) FROM audit_log"
                    + " WHERE COALESCE(before_json,'') LIKE ? OR COALESCE(after_json,'') LIKE ?"
                    + "    OR COALESCE(target_id,'') LIKE ?",
                    "%" + plain + "%", "%" + plain + "%", "%" + plain + "%"),
                    "激活码明文出现在了审计日志里：任何能查审计的人都能直接拿去激活，"
                    + "而这份记录是永久的");
        }

        // 生成这一批本身要留痕，而且要挂在【订单】上：一半的批次根本没有代理，
        // 挂到 agent_profile 上意味着那一半在任何页面上都查不到 —— 不报错，就是不存在
        var a = jdbc.queryForMap(
                "SELECT action, target_type, target_id, after_json FROM audit_log"
              + " WHERE action='STUDENT_CODE_BATCH' AND target_id=? ORDER BY id DESC LIMIT 1",
                String.valueOf(b1OrderId));
        assertEquals("biz_order", a.get("target_type"));
        // MySQL 的 JSON 列会把写进去的字符串重新序列化（键之间补空格、键序也可能变），
        // 所以判据不能钉在我们写出去的那串字节上 —— 去掉空白再比，钉的是「键在不在、值是不是 null」
        String after = String.valueOf(a.get("after_json")).replace(" ", "");
        assertTrue(after.contains("\"ownerAgentId\":null"),
                "归属为空时审计里没有 ownerAgentId 这个键：「这批码本来就不归属谁」"
                + "和「这条审计没记归属」在事后是两件事，而后者什么也证明不了。实际：" + after);
        assertTrue(after.contains("地推活动"), "发放原因没进审计：事后问「这批码怎么来的」答不上来");
    }

    // ================================================================ S3 上限与形状

    @Test @Order(4)
    @DisplayName("S3 负例：超上限 / 0 个 / 空原因 / grantKey 带 % 都当场 400，一个码都不落")
    void badInputsRejected() {
        long codeBefore = count("SELECT COUNT(*) FROM activation_code");
        long ordBefore = count("SELECT COUNT(*) FROM biz_order");

        expectApi(400, "一批最多生成",
                () -> createApi(StudentCodeBatchService.MAX_BATCH + 1, null, "太多了", "STUKEY-S3-OVER"));
        expectApi(400, "一批最多生成", () -> createApi(0, null, "零个", "STUKEY-S3-ZERO"));
        expectApi(400, "生成原因", () -> createApi(1, null, "  ", "STUKEY-S3-NOREASON"));

        // ★ 这一条是安全判据不是参数校验：grantKey 被三处拼进 LIKE key+"#%"，
        // 放一个 % 进来就是 LIKE '%#%' —— 一次把全库白送码圈出来
        expectApi(400, "幂等键格式", () -> createApi(1, null, "注入", "%"));
        expectApi(400, "幂等键格式", () -> createApi(1, null, "注入", "STUKEY%S3"));
        expectApi(400, "幂等键格式", () -> createApi(1, null, "太短", "abc"));

        assertEquals(codeBefore, count("SELECT COUNT(*) FROM activation_code"),
                "被拒的请求还是落了码：这批码没人知道它存在，也没人会去发它");
        assertEquals(ordBefore, count("SELECT COUNT(*) FROM biz_order"));
    }

    // ================================================================ S4 平台直营的全部含义

    /**
     * 这一条是「平台直营」这四个字的<b>全部含义</b>，测不到它等于这一轮什么都没做。
     */
    @Test @Order(5)
    @DisplayName("S4 学员真激活一个不归属的码：能开课，而两处归属仍为 NULL、佣金一行不多")
    void redeemUnattributedKeepsAttributionNull() {
        long comBefore = count("SELECT COUNT(*) FROM commission_record");
        assertNull(jdbc.queryForObject("SELECT attributed_agent_id FROM sys_user WHERE id=?",
                Long.class, STU_PLAIN), "这个学员一开始就该是没有归属的，否则下面测不到东西");

        var r = activation.redeem(STU_PLAIN, b1PlainOfRedeemed, AGREEMENT, "1.2.3.4");
        assertTrue(r.firstTime());
        assertNull(r.attributedAgentId(),
                "激活结果里带上了归属代理：这批是平台直营的码，谁激活都不算任何人的业绩");
        assertFalse(r.attributionJustSet());

        // ① 能开课 —— 权益真的开出来了，不是「什么都没发生所以两个 NULL 都成立」
        var e = jdbc.queryForMap("SELECT user_id, course_id, source, attributed_agent_id,"
                + " valid_until, status FROM enrollment WHERE id=?", r.enrollmentId());
        assertEquals(STU_PLAIN, num(e.get("user_id")));
        assertEquals("ACTIVATION_CODE", e.get("source"));
        assertEquals("ACTIVE", e.get("status"));
        assertTrue(((LocalDateTime) e.get("valid_until")).isAfter(LocalDateTime.now()),
                "权益开出来就是过期的，学员点进去看不了课");

        // ② 这个【座位】不算任何人卖的
        assertNull(e.get("attributed_agent_id"),
                "不归属的码激活后把权益算给了某家代理：这份权益的结算依据被凭空写上了一个人");
        // ③ 这个【人】也没有被谁带进来。first-touch 一旦落定就永不再改 ——
        //    错了不是「改一下」，是这个学员此后所有的课都算那家代理的
        assertNull(jdbc.queryForObject("SELECT attributed_agent_id FROM sys_user WHERE id=?",
                Long.class, STU_PLAIN),
                "不归属的码把学员的 first-touch 归属写死了：他此后每一单都算那家代理的，改不回来");
        assertNull(jdbc.queryForObject("SELECT attribution_source FROM sys_user WHERE id=?",
                String.class, STU_PLAIN));

        // ④ 一分佣金都不结
        assertEquals(comBefore, count("SELECT COUNT(*) FROM commission_record"),
                "平台直营的码被激活后结出了佣金：平台一分钱没收却付了钱出去");

        assertEquals("BOUND", jdbc.queryForObject(
                "SELECT status FROM activation_code WHERE order_id=? AND bound_user_id=?",
                String.class, b1OrderId, STU_PLAIN));
    }

    // ================================================================ S5 / S6 指定归属那一档

    @Test @Order(6)
    @DisplayName("S5 指定归属代理：owner_agent_id 全写对，佣金口径与手工发码一致（0 行）")
    void attributedBatch() {
        long comBefore = count("SELECT COUNT(*) FROM commission_record");
        long payBefore = count("SELECT COUNT(*) FROM payment_record");

        var res = createApi(3, AG_B2, "渠道支持：给 B2 的活动码", "STUKEY-S5-BBBB");
        assertEquals(Boolean.TRUE, res.get("changed"));
        assertEquals(3L, num(res.get("codesIssued")));
        assertEquals(AG_B2, num(res.get("ownerAgentId")));
        b2OrderId = num(res.get("orderId"));

        assertEquals(3, count("SELECT COUNT(*) FROM activation_code"
                + " WHERE order_id=? AND owner_agent_id=?", b2OrderId, AG_B2),
                "指定了归属却没写到码上：代理端「我的激活码」里一张都看不到，"
                + "而运营这边显示已经发了");
        assertEquals(AG_B2, num(jdbc.queryForObject(
                "SELECT attributed_agent_id FROM biz_order WHERE id=?", Long.class, b2OrderId)));

        // B2 是二级、有上级、seed 里配了返佣规则 —— S0 已经证明它买东西是会结佣金的。
        // 所以这里的 0 行是白送单这条口径挡下来的，不是「本来就不会结」
        assertEquals(comBefore, count("SELECT COUNT(*) FROM commission_record"),
                "白送给代理的学员码结出了佣金 —— 与手工发码那条路的口径分叉了");
        assertEquals(payBefore, count("SELECT COUNT(*) FROM payment_record"));
        assertEquals(0L, num(jdbc.queryForMap(
                "SELECT paid_amount_fen FROM biz_order WHERE id=?", b2OrderId)
                .get("paid_amount_fen")));
    }

    @Test @Order(7)
    @DisplayName("S6 归属代理的码被激活：两处归属都写成那家代理，佣金仍然一行不多")
    void redeemAttributedWritesAttribution() {
        long comBefore = count("SELECT COUNT(*) FROM commission_record");
        String plain = (String) exportPlain(b2OrderId).get(0).get("code");

        var r = activation.redeem(STU_OWNED, plain, AGREEMENT, "1.2.3.5");
        assertTrue(r.firstTime());
        assertEquals(AG_B2, r.attributedAgentId());
        assertTrue(r.attributionJustSet());

        assertEquals(AG_B2, num(jdbc.queryForMap(
                "SELECT attributed_agent_id FROM enrollment WHERE id=?", r.enrollmentId())
                .get("attributed_agent_id")),
                "指定了归属，激活后这份权益却没算给他 —— 那这个选项等于没有");
        assertEquals(AG_B2, jdbc.queryForObject(
                "SELECT attributed_agent_id FROM sys_user WHERE id=?", Long.class, STU_OWNED));
        // 归属写上了，钱还是一分不返：白送的货不产生佣金，这是库层强制的
        assertEquals(comBefore, count("SELECT COUNT(*) FROM commission_record"));
    }

    // ================================================================ S7 幂等

    @Test @Order(8)
    @DisplayName("S7 幂等：同一个 grantKey 提交两次，第二次 changed:false 且一个码都不多")
    void repeatedSubmitIssuesNothingMore() {
        long codeBefore = count("SELECT COUNT(*) FROM activation_code");
        long ordBefore = count("SELECT COUNT(*) FROM biz_order");

        var first = createApi(4, null, "客服补偿：批量", "STUKEY-S7-CCCC");
        assertEquals(Boolean.TRUE, first.get("changed"));
        long orderId = num(first.get("orderId"));

        // 第二次连参数都换掉：幂等键裁决的是「这次意图」，不是「这组参数」。
        // 按参数比对的话，运营改了个数字再点一次就真的又发一批出去了
        var again = createApi(9, AG_B2, "客服补偿：批量（手滑改了数）", "STUKEY-S7-CCCC");
        assertEquals(Boolean.FALSE, again.get("changed"),
                "第二次提交回了 changed:true —— 双击一次就是两批码真的发了出去，"
                + "而界面上两次都显示成功");
        assertEquals(orderId, num(again.get("orderId")), "重放没有把第一次那张单查回来");
        assertEquals(4L, num(again.get("codesIssued")));
        assertNull(again.get("ownerAgentId"),
                "重放回了本次请求填的归属而不是库里那一批的：界面会显示「已归属 B2」，"
                + "而码实际上不属于任何人 —— 运营正是靠这句话决定要不要把码发给 B2");

        assertEquals(codeBefore + 4, count("SELECT COUNT(*) FROM activation_code"));
        assertEquals(ordBefore + 1, count("SELECT COUNT(*) FROM biz_order"));
    }

    /**
     * 两条发码路径共用同一个 {@code uk_grant_key}。
     *
     * <p>不认这一条的话，一次「给代理发码」占住的键会让这里回一句
     * 「这批已经生成过了」，运营看到 changed:false 就不会重试 ——
     * 而那批学员码<b>一个都没发出去</b>，界面上一句错都没有。
     */
    @Test @Order(9)
    @DisplayName("S8 幂等键被另一条发码路径占住时，必须报冲突而不是假装「已经发过了」")
    void grantKeyStolenByAnotherPathIsRejected() {
        String key = "STUKEY-S8-DDDD";
        // 手工发码那条路的形状：同一个键，占住 key#0
        var manual = new ManualIssueService(jdbc, orderSvc, new PricingService(jdbc), fulfill,
                new TransactionTemplate(new DataSourceTransactionManager(ds)));
        manual.grant(new ManualIssueService.GrantCmd(AG_B2, 1L, 1, "占住这个键", key,
                ADMIN, "10.0.0.9"));

        long codeBefore = count("SELECT COUNT(*) FROM activation_code");
        expectApi(409, "另一次发放", () -> createApi(2, null, "撞键", key));
        assertEquals(codeBefore, count("SELECT COUNT(*) FROM activation_code"));
    }

    // ================================================================ S9 归属校验

    @Test @Order(10)
    @DisplayName("S9 归属给不存在 / 已停用的代理都当场拒 —— 库层这次拦不住它")
    void ownerAgentMustBeActive() {
        long codeBefore = count("SELECT COUNT(*) FROM activation_code");
        expectApi(404, "代理不存在", () -> createApi(1, 99999L, "查无此人", "STUKEY-S9-NOAGENT"));
        // trg_order_insert 那条「代理必须 ACTIVE」只看 buyer_agent_id，而这条路的
        // buyer_agent_id 是 NULL —— 这里不拦就没有人拦
        expectApi(409, "有效状态", () -> createApi(1, AG_SUSPENDED, "停用中", "STUKEY-S9-SUSPEND"));
        assertEquals(codeBefore, count("SELECT COUNT(*) FROM activation_code"));
    }

    // ================================================================ S10 运营从哪一页去查

    @Test @Order(11)
    @DisplayName("S10 批次列表：两档都在、归属筛选分得开、码数与已激活数对得上")
    void batchListShowsBothKinds() {
        var all = itemsOf(listApi(null));
        assertTrue(all.size() >= 3, "生成过的批次在列表上少了：运营会当成没发过，然后再发一批");
        // 「给代理发码」那条路建的单不能混进来：它们是代理档的码，口径完全不同
        for (var it : all)
            assertNotNull(it.get("orderId"));
        assertTrue(all.stream().anyMatch(x -> num(x.get("orderId")) == b1OrderId));
        assertTrue(all.stream().anyMatch(x -> num(x.get("orderId")) == b2OrderId));

        var b1 = all.stream().filter(x -> num(x.get("orderId")) == b1OrderId).findFirst()
                .orElseThrow();
        assertNull(b1.get("ownerAgentId"));
        assertEquals(5, ((Number) b1.get("qty")).intValue());
        assertEquals(5L, num(b1.get("issued")));
        assertEquals(1L, num(b1.get("bound")), "已激活数对不上：运营按它判断这批还剩几个能发");
        assertEquals(STUDENT_UNIT_FEN * 5, num(b1.get("amountFen")),
                "金额被 JOIN 乘了一遍：数字看起来完全正常，只是大了 N 倍");

        var platformOnly = itemsOf(listApi("platform"));
        assertTrue(platformOnly.stream().allMatch(x -> x.get("ownerAgentId") == null),
                "「只看不归属」里混进了有归属的批次");
        assertTrue(platformOnly.stream().anyMatch(x -> num(x.get("orderId")) == b1OrderId));

        var b2Only = itemsOf(listApi(String.valueOf(AG_B2)));
        assertTrue(b2Only.stream().allMatch(x -> num(x.get("ownerAgentId")) == AG_B2));
        assertTrue(b2Only.stream().anyMatch(x -> num(x.get("orderId")) == b2OrderId));
        assertEquals("B2", b2Only.get(0).get("ownerAgentCode"));

        // 手工发给代理的那一批（S8 建的）不该出现在这一页：它是代理档的码，
        // 混进来的话运营会拿这一页的数去对「送出去多少学员课」，而那个数是错的
        assertEquals(0, count("SELECT COUNT(*) FROM biz_order o WHERE o.origin='ADMIN_GIFT'"
                + " AND o.buyer_type='STUDENT' AND o.sku_code_snapshot<>?",
                StudentCodeBatchService.SKU_CODE));
    }

    @Test @Order(12)
    @DisplayName("S11 一批里的码只出掩码；不是学员批次的订单 ID 打进来当场 400")
    void codesEndpointMasksAndScopes() {
        var res = callAdmin(api, "codesOf",
                new Class<?>[]{long.class, HttpServletRequest.class}, b1OrderId, admin());
        var items = itemsOf(res);
        assertEquals(5, items.size());
        for (var c : items) {
            String masked = String.valueOf(c.get("masked"));
            assertTrue(masked.contains("*"), "列表里出的不是掩码：后台页面天天开着、会被投屏与截图");
            assertFalse(c.containsKey("code"), "码列表里出了明文字段");
        }

        // 收过钱的那一单（S0 建的）不该能从这个入口列码：今天它只出掩码所以看起来无害，
        // 但下一个人给它加一列明文时，不会想到它从来没做过范围校验
        long paidOrderId = count("SELECT MIN(id) FROM biz_order WHERE origin='SELF_SERVE'");
        expectApi(400, "不是一批学员码", () -> callAdmin(api, "codesOf",
                new Class<?>[]{long.class, HttpServletRequest.class}, paidOrderId, admin()));
    }
}
