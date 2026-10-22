package tech.dragonai.wangke.trade;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tech.dragonai.wangke.ItDb;
import tech.dragonai.wangke.learn.ActivationService;
import tech.dragonai.wangke.web.ApiException;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 订单的 HTTP 面：下单、查我的单、查一单、取消未支付。
 *
 * <h3>这个类里绝大多数判据是"越权"与"取消"两件事</h3>
 * 因为这两处的错<b>不会有任何症状</b>。漏写一个 {@code buyer_user_id=?} 的查询工作得很好，
 * 只是任何人换个 id 就能读别人的单；把取消写成"先查状态再更新"，在压测之外
 * 也永远不会红，只在某一笔支付恰好落在两句 SQL 中间时把一个已付款的订单关掉。
 *
 * <p>所以每条负例都钉住<b>原因</b>而不是"抛了异常"：越权与不存在必须给出
 * 完全相同的状态码与文案（否则这是个能枚举全站订单的探针），
 * 拒绝取消必须同时断言订单状态<b>一动没动</b>（否则"先关单再报错"的实现照样绿）。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OrderControllerIT {

    /**
     * 与 {@link AlipayCallbackIT} 用不同的库名。
     * 两个 IT 在同一个 JVM 里顺序跑，共用库名的话后一个的 {@code DROP DATABASE}
     * 会把前一个的数据连根拔掉 —— 而失败信息会是"表不存在"，看不出是撞库名。
     */
    static final String DB = "wangke_pay_order_it";

    static DataSource ds;
    static JdbcTemplate jdbc;
    static OrderQueryService view;
    static FulfillService fulfill;
    static ActivationService activation;
    static OrderController api;
    static PublicSkuController publicSkus;

    /** 学员甲 / 学员乙 / 一级代理 / 平台超管（seed 里的 id=1）。 */
    static final long STUDENT_A = 60, STUDENT_B = 61, AGENT_L1 = 62, ADMIN = 1;
    static final long SKU_STUDENT = 3, SKU_AGENT_1 = 1, SKU_AGENT_10 = 2;

    @BeforeAll
    static void bootstrap() throws Exception {
        ds = ItDb.freshDatabase(DB);
        // 取消 → 支付这条链要走到 confirmPaid，而 payment_record.provider 的枚举里
        // 还没有 ALIPAY（见 migrations/20260811_支付宝支付渠道.sql）。
        AlipayTestKit.applyPayMigration(DB);
        jdbc = new JdbcTemplate(ds);
        var pricing = new PricingService(jdbc);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        var vault = new CodeVault(ItDb.testKey(24), "order-salt".getBytes(StandardCharsets.UTF_8));
        view = new OrderQueryService(jdbc);
        fulfill = new FulfillService(jdbc, pricing, tx, vault);
        activation = new ActivationService(jdbc, tx, vault);
        api = new OrderController(new OrderService(jdbc, pricing), view, new CodeService(jdbc, vault));
        publicSkus = new PublicSkuController(jdbc, pricing);

        jdbc.update("UPDATE course SET status='ON' WHERE id=1");

        jdbc.update("INSERT INTO sys_user (id,phone,role) VALUES "
                  + "(60,'13600000060','STUDENT'),(61,'13600000061','STUDENT'),"
                  + "(62,'13600000062','AGENT')");
        jdbc.update("INSERT INTO agent_profile (id,user_id,agent_code,level,status)"
                  + " VALUES (1,62,'OC1',1,'ACTIVE')");
    }

    @AfterAll
    static void teardown() { ItDb.close(ds); }

    // ---------------------------------------------------------------- 小工具

    private static long orderIdOf(Map<String, Object> body) {
        return ((Number) body.get("orderId")).longValue();
    }

    private static Map<String, Object> place(long uid, String role, long skuId) {
        return api.create(new OrderController.CreateReq(skuId), AlipayTestKit.asUser(uid, role));
    }

    private static String statusOf(long orderId) {
        return jdbc.queryForObject("SELECT status FROM biz_order WHERE id=?", String.class, orderId);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> ordersIn(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("orders");
    }

    // ================================================================ 下单

    @Test @Order(0)
    @DisplayName("A draft course is hidden from ordering even when its SKU is ON")
    void draftCourseCannotBeOrdered() {
        jdbc.update("UPDATE course SET status='DRAFT' WHERE id=1");
        try {
            var e = assertThrows(IllegalStateException.class,
                    () -> place(STUDENT_A, "STUDENT", SKU_STUDENT));
            assertTrue(e.getMessage().contains("\u5c1a\u672a\u4e0a\u67b6"), e.getMessage());
            Integer n = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM biz_order WHERE buyer_user_id=?", Integer.class, STUDENT_A);
            assertEquals(0, n, "a rejected draft-course purchase must not create an order");

            // ★ 橱窗必须与下单闸同口径 —— 这一条以前一个用例都没有：
            // 把 c.status='ON' 从 PublicSkuController 里删掉，273 个测试没有一个会红，
            // 而线上的表现是「橱窗写着能买、点下去 409」，两边各说各话。
            // 判据必须走真接口，不能自己复述一遍 WHERE 子句 —— 那样测的是库里的数据，
            // 不是那段代码，改坏了照样绿。
            assertTrue(skusOf("STUDENT").isEmpty(), "课程未上架时学员橱窗必须是空的");
        } finally {
            jdbc.update("UPDATE course SET status='ON' WHERE id=1");
        }
        // 闸打开之后必须恢复可见 —— 否则上面那条「空」可能只是因为橱窗一直是空的
        assertFalse(skusOf("STUDENT").isEmpty(), "课程上架后学员橱窗不该还是空的");
    }

    /**
     * 公开橱窗不得吐出代理进货价。
     *
     * <p>这一条原来是反着写的 —— 上面那个用例曾经断言
     * {@code assertFalse(skusOf("AGENT").isEmpty())}，把「免登录也能拿到批发价」
     * 钉成了判据。于是这个洞不仅没有测试拦着，还有一条测试守着它不被修好。
     *
     * <p>这里断言 403 而不是「空列表」：空列表读起来像「现在没有可买的档」，
     * 下一个人会把它当成 bug「顺手修好」。
     */
    @Test @Order(0)
    @DisplayName("★ 公开橱窗拒绝 buyerType=AGENT：进货价只在 ACTIVE 代理那条路上出")
    void publicShopNeverServesAgentPricing() {
        var denied = assertThrows(ApiException.class, () -> publicSkus.skus("AGENT"));
        assertEquals(403, denied.status(),
                "免登录接口把代理进货价发出去了；业务口径是「审批通过才能看进货价」");
        assertTrue(denied.getMessage().contains("审批"), denied.getMessage());

        // 大小写与空白不能绕过去
        assertEquals(403, assertThrows(ApiException.class, () -> publicSkus.skus(" agent ")).status());

        // 阴性对照：学员零售档必须照常出，否则上面两条可能只是因为接口整个坏了
        assertFalse(skusOf("STUDENT").isEmpty(), "学员零售档被误伤了");
    }

    /** 走真的 PublicSkuController，不复述它的 SQL。 */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> skusOf(String buyerType) {
        return (List<Map<String, Object>>) publicSkus.skus(buyerType).get("skus");
    }

    @Test @Order(1)
    @DisplayName("学员直购：金额与码数一律由服务端定，接口签名里根本没有金额这个入参")
    void studentCanPlaceOrder() {
        var body = place(STUDENT_A, "STUDENT", SKU_STUDENT);
        assertEquals(true, body.get("ok"));
        assertEquals(79900L, body.get("amountFen"), "学员直购价 799 元");
        assertEquals("799.00", body.get("amountYuan"));
        assertEquals(1, body.get("codeQty"));
        assertTrue(((String) body.get("orderNo")).startsWith("WK"));

        // 结构性判据，不是洁癖：只要下单请求体里出现 amount / price 这类字段，
        // 就一定会有某个"为了兼容"的版本把它用起来，那时前端就能自己定价了。
        // 断言签名本身，能在字段被加回来的那一刻就红，而不是等到有人真的用它。
        var comps = OrderController.CreateReq.class.getRecordComponents();
        assertEquals(1, comps.length, "下单入参多了字段：金额与数量绝不能由前端传");
        assertEquals("skuId", comps[0].getName());
    }

    @Test @Order(2)
    @DisplayName("代理批量单：价与码数取代理层级的快照，不是学员价")
    void agentGetsTierPrice() {
        var body = place(AGENT_L1, "AGENT", SKU_AGENT_10);
        assertEquals(298000L, body.get("amountFen"), "一级代理批量价 2980");
        assertEquals(10, body.get("codeQty"));
    }

    @Test @Order(3)
    @DisplayName("平台管理员不能用自己的账号下单（否则交易流水里混进审批人）")
    void adminCannotPlaceOrder() {
        var e = assertThrows(ApiException.class, () -> place(ADMIN, "PLATFORM_ADMIN", SKU_STUDENT));
        assertEquals(403, e.status());
        assertEquals("没有权限执行该操作", e.getMessage());
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM biz_order WHERE buyer_user_id=?", Integer.class, ADMIN);
        assertEquals(0, n, "被拒了却还是落了单");
    }

    @Test @Order(4)
    @DisplayName("学员买不了代理套餐：拒绝的原因必须是「仅限代理」，而不是价目缺失")
    void studentCannotBuyAgentSku() {
        var e = assertThrows(IllegalStateException.class,
                () -> place(STUDENT_A, "STUDENT", SKU_AGENT_1));
        // 两种拒绝都会让下单失败，但含义完全不同：
        // "仅限代理" 是业务规则生效了；"没有当前生效的价目" 说明它是在取价那一步偶然炸的，
        // 哪天给学员层级配上了 AGENT_1 的价，这个口子就自己开了，而判据仍然是绿的。
        assertTrue(e.getMessage().contains("仅限已生效的代理商"), e.getMessage());
    }

    // ================================================================ 越权

    @Test @Order(5)
    @DisplayName("查别人的单：与查一个不存在的 id 必须给出完全一样的 404，否则这是枚举探针")
    void detailIsOwnerScoped() {
        long mine = orderIdOf(place(STUDENT_B, "STUDENT", SKU_STUDENT));

        var stolen = assertThrows(ApiException.class,
                () -> api.detail(mine, AlipayTestKit.asUser(STUDENT_A, "STUDENT")));
        var absent = assertThrows(ApiException.class,
                () -> api.detail(999_999_999L, AlipayTestKit.asUser(STUDENT_A, "STUDENT")));

        assertEquals(404, stolen.status());
        assertEquals(absent.status(), stolen.status(),
                "两种情况状态码不同 —— 攻击者据此就能数出平台一共有多少单");
        assertEquals(absent.getMessage(), stolen.getMessage(),
                "两种情况文案不同 —— 同上，文案本身就是信息");

        // 本人拿得到，说明上面那个 404 是权限判据在起作用，不是这条查询本来就查不出东西。
        // 少了这半句，一个恒定返回 404 的实现也能让上面全绿。
        var ok = api.detail(mine, AlipayTestKit.asUser(STUDENT_B, "STUDENT"));
        @SuppressWarnings("unchecked")
        var o = (Map<String, Object>) ok.get("order");
        assertEquals(mine, ((Number) o.get("orderId")).longValue());
    }

    @Test @Order(6)
    @DisplayName("我的订单列表里不会出现别人的单，total 计数也不含")
    void listIsOwnerScoped() {
        long theirs = orderIdOf(place(STUDENT_B, "STUDENT", SKU_STUDENT));
        var body = api.mine(null, 100, 0, AlipayTestKit.asUser(STUDENT_A, "STUDENT"));

        var ids = ordersIn(body).stream().map(m -> ((Number) m.get("orderId")).longValue()).toList();
        assertFalse(ids.contains(theirs), "列表里出现了别人的订单");
        assertFalse(ids.isEmpty(), "本人一单都查不到，说明这条查询本身是坏的，上一句断言不作数");

        // total 与列表必须同源。分页那条 SQL 加了归属条件、计数那条忘了加，
        // 是很常见的一处漏 —— 表现是"共 37 单"但只能翻出 5 单，看起来像分页 bug。
        long total = ((Number) body.get("total")).longValue();
        assertEquals(ids.size(), total, "总数与列表不同源，多半是计数那条 SQL 漏了归属条件");
    }

    // ============================================== 跨角色：同一个人的两种身份

    /**
     * 同一个自然人既是学员又是代理时，学员端不得看见他的<b>代理进货单</b>。
     *
     * <p>这一条 2026-08-19 由业务方在真机上报出来（「订单居然是可以互通看到的」），
     * 而 366 条测试全绿 —— 因为<b>一条都没测过这件事</b>。生产库里当时就有 1 个人
     * 同时持有两种单，他打开「我的订单」看到的就是这一幕，连 2980 批发价一起。
     *
     * <p>判据钉的是"以什么身份下的单"，不是"谁下的单"：代理进货单的
     * buyer_user_id 写的就是他本人（{@link OrderService} 建单处），
     * 所以只按人过滤的实现照样能让"别人的单看不见"那几条用例全绿。
     */
    @Test @Order(20)
    @DisplayName("★ 学员端「我的订单」不含本人的代理进货单：列表 / 计数 / 详情 / 单号 / 取消 五处一致")
    void agentPurchaseNeverLeaksIntoStudentOrders() {
        var asStudentSelf = AlipayTestKit.asUser(AGENT_L1, "STUDENT");

        // 同一个人：先以代理身份进一批货，再以学员身份给自己买一份课
        long wholesale = orderIdOf(place(AGENT_L1, "AGENT", SKU_AGENT_10));
        long retail = orderIdOf(place(AGENT_L1, "STUDENT", SKU_STUDENT));
        assertEquals("AGENT", jdbc.queryForObject(
                "SELECT buyer_type FROM biz_order WHERE id=?", String.class, wholesale));
        assertEquals(AGENT_L1, jdbc.queryForObject(
                "SELECT buyer_user_id FROM biz_order WHERE id=?", Long.class, wholesale),
                "前提没成立：进货单的 buyer_user_id 不是本人的话，这条用例测不到东西");

        // ① 列表
        var body = api.mine(null, 100, 0, asStudentSelf);
        var ids = ordersIn(body).stream().map(m -> ((Number) m.get("orderId")).longValue()).toList();
        assertFalse(ids.contains(wholesale), "学员端列出了本人的代理进货单，批发价跟着一起出去了");
        assertTrue(ids.contains(retail), "本人的学员单查不到，说明这条查询整个坏了，上一句不作数");

        // ② 计数与列表同源
        assertEquals(ids.size(), ((Number) body.get("total")).longValue(),
                "总数与列表不同源 —— 多半是计数那条 SQL 漏了 buyer_type");

        // ③ 学员端详情：进货单在这条路上必须"不存在"，且与真不存在的单同一个 404。
        //    走的是 OrderController.detail → studentDetail，不是共用的 detail。
        var hidden = assertThrows(ApiException.class, () -> api.detail(wholesale, asStudentSelf));
        var absent = assertThrows(ApiException.class, () -> api.detail(999_999_998L, asStudentSelf));
        assertEquals(404, hidden.status());
        assertEquals(absent.status(), hidden.status());
        assertEquals(absent.getMessage(), hidden.getMessage(),
                "两种 404 文案不同 —— 那就能靠文案区分「这单存在但不是学员单」");

        // ④ 付款那两条路【必须仍然通】。这是这个用例里最容易被改坏的一半：
        //    detail / byOrderNo 是代理付进货款走的路，把 buyer_type 加到那里，
        //    表现是代理点「继续付款」拿到一句"订单不存在"，而单就在他的进货记录里躺着。
        String no = jdbc.queryForObject(
                "SELECT order_no FROM biz_order WHERE id=?", String.class, wholesale);
        assertEquals(wholesale, view.detail(AGENT_L1, wholesale).id(),
                "代理付不了自己的进货款了");
        assertEquals(wholesale, view.byOrderNo(AGENT_L1, no).id(),
                "付款状态轮询取不到进货单了 —— 付完钱页面会一直停在「待支付」");

        // ⑤ 取消【刻意】仍然放行：它不吐任何价格，而且是代理放弃一张未付款进货单的
        //    唯一入口（代理端没有取消按钮，/api/agent/orders 也只有列表/建单/查单）。
        //    把它一起挡掉是拿一个不相干的功能去抵账，安全收益为零。见 OrderQueryService.cancel 注释。
        api.cancel(wholesale, asStudentSelf);
        assertEquals("CLOSED", statusOf(wholesale), "取消这条路被误伤了：代理再也放弃不了未付款的进货单");

        // 阴性对照：这张进货单**确实存在且仍是 CREATED**。
        // 少了这一句，一个"进货单压根没建出来"的世界也能让上面每一条断言全绿。
        var real = view.byOrderNoForNotify(no);
        assertNotNull(real, "进货单根本不存在 —— 上面那些 404 什么也没证明");
        assertEquals("AGENT", real.buyerType());
        assertEquals(wholesale, real.id());
    }

    // ================================================================ 分页与过滤

    @Test @Order(7)
    @DisplayName("分页参数在服务端钳位：limit=0 不能变成「一单都没有」")
    void pagingIsClamped() {
        var req = AlipayTestKit.asUser(STUDENT_A, "STUDENT");
        place(STUDENT_A, "STUDENT", SKU_STUDENT);
        place(STUDENT_A, "STUDENT", SKU_STUDENT);

        // limit=0 直接透传就是 LIMIT 0 —— 返回空列表、HTTP 200、没有任何报错，
        // 而前端会把它显示成"您还没有订单"。所以下界钳到 1。
        assertEquals(1, ordersIn(api.mine(null, 0, 0, req)).size(), "limit=0 被透传成了空结果");
        // 上界钳到 100：?limit=1000000 是一次全表扫加一个超大响应体，且看起来完全像正常流量
        assertTrue(ordersIn(api.mine(null, 1_000_000, 0, req)).size() <= 100, "limit 上界没有钳位");
        // 负 offset 会让 MySQL 直接报语法错，那是 500 而不是 400
        assertFalse(ordersIn(api.mine(null, 5, -7, req)).isEmpty(), "负 offset 没有钳位");

        // 翻页真的在翻：第二页的首条不等于第一页的首条
        var p0 = ordersIn(api.mine(null, 1, 0, req));
        var p1 = ordersIn(api.mine(null, 1, 1, req));
        assertNotEquals(p0.get(0).get("orderId"), p1.get(0).get("orderId"));
    }

    @Test @Order(8)
    @DisplayName("状态过滤只认枚举值：拼错的状态必须 400，不能返回空列表")
    void unknownStatusIsRejected() {
        var req = AlipayTestKit.asUser(STUDENT_A, "STUDENT");
        var e = assertThrows(ApiException.class, () -> api.mine("created", 20, 0, req));
        assertEquals(400, e.status());
        assertEquals("未知的订单状态", e.getMessage());
        // 不校验也能"工作"（参数化查询挡住了注入），代价是拼错枚举返回空列表，
        // 前端把"我传错了"看成"我没有订单"—— 这类错永远不会被上报。
        assertFalse(ordersIn(api.mine("CREATED", 20, 0, req)).isEmpty(), "正确的枚举值反而查不到");
    }

    @Test @Order(9)
    @DisplayName("出网视图只给买家该看的字段：定价来源、下单 IP、代理关系一概不出网")
    void viewLeaksNoInternals() {
        long id = orderIdOf(place(STUDENT_A, "STUDENT", SKU_STUDENT));
        var body = api.detail(id, AlipayTestKit.asUser(STUDENT_A, "STUDENT"));
        @SuppressWarnings("unchecked")
        var o = (Map<String, Object>) body.get("order");
        for (String forbidden : new String[]{
                "priceTierId", "price_tier_id", "clientIp", "client_ip",
                "buyerAgentId", "buyer_agent_id", "attributedAgentId", "skuId"}) {
            assertFalse(o.containsKey(forbidden),
                    "出网视图里有 " + forbidden + " —— 定价结构与渠道关系是内部信息，"
                  + "买家页面上没有任何用途，泄漏出去却能被拼出整张价目表");
        }
        assertEquals(79900L, o.get("amountFen"), "分必须出网");
        assertEquals("799.00", o.get("amountYuan"), "元只是给人看的那一份，加减一律用分");
    }

    // ================================================================ 取消

    @Test @Order(10)
    @DisplayName("未支付订单可取消；再取消一次是 409 而不是「又成功了一次」")
    void cancelUnpaidOrder() {
        long id = orderIdOf(place(STUDENT_A, "STUDENT", SKU_STUDENT));
        var req = AlipayTestKit.asUser(STUDENT_A, "STUDENT");

        assertEquals(true, api.cancel(id, req).get("ok"));
        assertEquals("CLOSED", statusOf(id));

        var e = assertThrows(ApiException.class, () -> api.cancel(id, req));
        assertEquals(409, e.status());
        assertTrue(e.getMessage().contains("已取消"), e.getMessage());
        // 把重复取消当成功回 200，两个标签页各点一次都会显示"取消成功"；
        // 而如果中间这单其实已经被支付了，用户看到的仍然是"取消成功"。状态要如实回。
    }

    @Test @Order(11)
    @DisplayName("取消后不能再发起支付：CLOSED 是终态，这时收到钱就只能人工退")
    void cancelledOrderCannotBePaid() {
        long id = orderIdOf(place(STUDENT_A, "STUDENT", SKU_STUDENT));
        var req = AlipayTestKit.asUser(STUDENT_A, "STUDENT");
        api.cancel(id, req);

        var pay = new PayController(view, AlipayTestKit.service(), fulfill, jdbc);
        var e = assertThrows(ApiException.class,
                () -> pay.create(new PayController.CreateReq(id, "PC"), req));
        assertEquals(409, e.status());
        assertTrue(e.getMessage().contains("已取消"), e.getMessage());

        // 数据库那一侧也得拦得住：trg_order_state 规定终态不可再变更。
        // 应用层的判断如果哪天被绕过（后台补单、脚本改数），这条才是最后一道。
        var db = assertThrows(org.springframework.dao.DataAccessException.class,
                () -> jdbc.update("UPDATE biz_order SET status='PAID', paid_at=NOW(3),"
                                + " paid_amount_fen=79900 WHERE id=?", id));
        assertTrue(String.valueOf(db.getMessage()).contains("终态"),
                "触发器没有拦住终态回退：" + db.getMessage());
    }

    @Test @Order(12)
    @DisplayName("别人取消不了我的单，而且我的单必须一动没动")
    void cancelIsOwnerScoped() {
        long id = orderIdOf(place(STUDENT_B, "STUDENT", SKU_STUDENT));

        var e = assertThrows(ApiException.class,
                () -> api.cancel(id, AlipayTestKit.asUser(STUDENT_A, "STUDENT")));
        assertEquals(404, e.status());
        assertEquals("订单不存在", e.getMessage());
        // 这半句才是判据的重心：只断言"抛了 404"的话，一个先 UPDATE 再检查归属、
        // 发现不对再报错的实现同样全绿 —— 而它已经把别人的单关掉了。
        assertEquals("CREATED", statusOf(id), "越权取消被拒了，但订单已经被关掉了");
    }

    @Test @Order(13)
    @DisplayName("已支付订单不能取消（判定与写入是同一条 UPDATE，不存在「查完再改」的窗口）")
    void paidOrderCannotBeCancelled() {
        long id = orderIdOf(place(AGENT_L1, "AGENT", SKU_AGENT_10));
        var res = fulfill.confirmPaid(id, "ALIPAY", "2026081122001400090013",
                                      298000L, LocalDateTime.now(), null);
        assertTrue(res.firstTime());
        assertEquals("FULFILLED", statusOf(id));

        var e = assertThrows(ApiException.class,
                () -> api.cancel(id, AlipayTestKit.asUser(AGENT_L1, "AGENT")));
        assertEquals(409, e.status());
        assertTrue(e.getMessage().contains("已支付"), e.getMessage());
        assertEquals("FULFILLED", statusOf(id), "已发货的订单被取消了 —— 货发了、单关了");
        Integer codes = jdbc.queryForObject(
                "SELECT COUNT(*) FROM activation_code WHERE order_id=?", Integer.class, id);
        assertEquals(10, codes, "已经发出去的码不该受取消动作影响");
    }

    @Test @Order(14)
    @DisplayName("并发取消同一单：恰好一个成功，另一个如实拿到 409")
    void concurrentCancelHasExactlyOneWinner() throws Exception {
        long id = orderIdOf(place(STUDENT_A, "STUDENT", SKU_STUDENT));
        var req = AlipayTestKit.asUser(STUDENT_A, "STUDENT");

        // 取消若写成"先 SELECT status 再 UPDATE"，两个线程会都读到 CREATED、
        // 都执行 UPDATE、都返回成功。那种实现在单线程测试里永远是绿的。
        // 判定与写入合成一条 UPDATE（状态在 WHERE 里）之后，只有一个能改到行。
        int n = 8;
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(n);
        var ok = new AtomicInteger();
        var conflict = new AtomicInteger();
        var other = new AtomicInteger();
        for (int i = 0; i < n; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    api.cancel(id, req);
                    ok.incrementAndGet();
                } catch (ApiException e) {
                    if (e.status() == 409) conflict.incrementAndGet(); else other.incrementAndGet();
                } catch (Exception e) {
                    other.incrementAndGet();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        assertTrue(done.await(30, java.util.concurrent.TimeUnit.SECONDS), "并发取消卡住了");

        assertEquals(0, other.get(), "并发下出现了 409 之外的失败（多半是锁超时被当成了 500）");
        assertEquals(1, ok.get(), "有 " + ok.get() + " 个线程都被告知取消成功 —— "
                + "判定与写入不是同一条 UPDATE，中间的窗口足以吞掉一笔刚到账的支付");
        assertEquals(n - 1, conflict.get());
        assertEquals("CLOSED", statusOf(id));
    }

    @Test @Order(15)
    @DisplayName("学员直购付款后本人能取码并激活；别人取不到，订单 GET 不泄露明文")
    void directPurchaseDeliversCodeToBuyer() {
        long id = orderIdOf(place(STUDENT_A, "STUDENT", SKU_STUDENT));
        var paid = fulfill.confirmPaid(id, "ALIPAY", "2026081122001400090015",
                79900L, LocalDateTime.now(), null);
        assertTrue(paid.firstTime());
        assertEquals(1, paid.codesIssued());

        var mine = AlipayTestKit.asUser(STUDENT_A, "STUDENT");
        var revealed = api.revealActivationCode(id, mine);
        String code = (String) revealed.get("code");
        assertNotNull(code);
        assertFalse(code.contains("****"), "交付给本人的是掩码，不可用于激活");

        @SuppressWarnings("unchecked")
        var order = (Map<String, Object>) api.detail(id, mine).get("order");
        assertFalse(order.containsKey("code"), "订单 GET 泄露了可直接兑换的明文码");

        var denied = assertThrows(ApiException.class, () -> api.revealActivationCode(
                id, AlipayTestKit.asUser(STUDENT_B, "STUDENT")));
        assertEquals(404, denied.status());

        var opened = activation.redeem(STUDENT_A, code, "student-service-v1", "127.0.0.1");
        assertTrue(opened.firstTime());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM enrollment WHERE user_id=? AND order_id=?",
                Integer.class, STUDENT_A, id));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE operator_id=? "
              + "AND action='STUDENT_CODE_REVEAL' AND target_type='activation_code'",
                Integer.class, STUDENT_A));

        // ★ 激活【之后】再点那颗按钮 —— 这是刚成功激活的人最可能点的一下。
        // 原来 WHERE 里带 c.status='ISSUED'，于是这一下掉进同一个 404
        // 「订单不存在或激活码尚未生成」：一句假话，而且它唯一的去处是客服工单。
        var used = assertThrows(ApiException.class, () -> api.revealActivationCode(id, mine));
        assertEquals(409, used.status(), "已激活不是「不存在」，不该复用 404");
        assertTrue(used.getMessage().contains("已经用过"),
                "文案要说清是「你用过了」，实测得到：" + used.getMessage());
        assertFalse(used.getMessage().contains("尚未生成"),
                "仍在对已激活的本人说「激活码尚未生成」");
    }

    @Test @Order(16)
    @DisplayName("代理本人按学员价买课，也必须取得到码（他的码 owner_agent_id 是 NULL，代理端列表看不到）")
    void agentBuyingAtStudentPriceCanRevealToo() {
        // OrderService 明确允许代理按学员档下单，此时 buyer_type='STUDENT'、
        // buyer_agent_id 为 null，发出来的码 owner_agent_id 也是 null ——
        // 代理端的 /codes 列表按 owner_agent_id 查，一条都看不到。
        // 取码接口原来写死 requireRole("STUDENT")，于是两条路都取不到：钱收了，货锁死。
        long id = orderIdOf(place(AGENT_L1, "AGENT", SKU_STUDENT));
        var paid = fulfill.confirmPaid(id, "ALIPAY", "2026081322001400090016",
                79900L, LocalDateTime.now(), null);
        assertEquals(1, paid.codesIssued());
        assertNull(jdbc.queryForObject(
                "SELECT owner_agent_id FROM activation_code WHERE order_id=?", Long.class, id),
                "夹具前提：这种码不属于任何代理");

        String code = (String) api.revealActivationCode(
                id, AlipayTestKit.asUser(AGENT_L1, "AGENT")).get("code");
        assertNotNull(code);
        assertFalse(code.contains("****"));

        // 放开角色不等于放开越权：别人的单照旧 404（边界在 buyer_user_id，不在角色）
        var denied = assertThrows(ApiException.class, () -> api.revealActivationCode(
                id, AlipayTestKit.asUser(STUDENT_B, "STUDENT")));
        assertEquals(404, denied.status());
    }
}
