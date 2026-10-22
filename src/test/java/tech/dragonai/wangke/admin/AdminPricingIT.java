package tech.dragonai.wangke.admin;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionTemplate;
import tech.dragonai.wangke.ItDb;
import tech.dragonai.wangke.auth.AuthInterceptor;
import tech.dragonai.wangke.trade.CodeVault;
import tech.dragonai.wangke.trade.FulfillService;
import tech.dragonai.wangke.trade.OrderService;
import tech.dragonai.wangke.trade.PricingService;
import tech.dragonai.wangke.web.ApiException;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 超管自由设置进货价：层级价打底 + 某几家单独覆盖。跑在真实 MySQL 上（库名 {@code wangke_pricing_it}）。
 *
 * <p>这条路径要守的是三件事，每一件坏掉都<b>不会报错</b>：
 *
 * <ol>
 *   <li><b>取价不能二义。</b>「专属价优先、没有则回落层级价」必须是一个确定的结果。
 *       两侧任一出现两条当前生效，同一家代理的两次下单就可能按两个价成交，事后无法解释。
 *   <li><b>改价不能改掉历史。</b>改价 = 封口旧行 + 插新行。UPDATE 旧行在库层是一次合法写入，
 *       而按老价成交过的订单再查「当时是什么价」查到的是新价 —— 历史被静默改写。
 *   <li><b>★ 平台净收不能为负。</b>二级成交价 − 返给一级的佣金 &lt; 0 时，平台每卖一单倒贴钱，
 *       而订单、佣金、对账表每一处看都合法，只有月底汇总才发现。
 * </ol>
 *
 * <p>每个负例都<b>钉住失败原因</b>：只断言「抛了异常」的用例太容易因为别的原因红着，
 * 那样它绿的时候什么也没证明。每个「拒绝」都配一条<b>阴性对照</b>：
 * 只验拒绝的话，一条「什么都拒」的闸门同样全绿，而它等于把功能关掉。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AdminPricingIT {

    /**
     * 库名必须是全新的一个。
     *
     * <p>{@link ItDb#freshDatabase} 第一句是 {@code DROP DATABASE IF EXISTS}：与别的 IT 撞名时，
     * 后启动的那个会把前一个正在用的库连根拔掉，前一个报出来的是「表不存在」——
     * 一句完全指不到根因、换台机器就复现不了的错。
     */
    static final String DB = "wangke_pricing_it";

    static final long ADMIN = 1L;               // seed.sql 里的平台超管
    static final long U_A1 = 10L, U_B2 = 11L;
    static final long AG_A1 = 1L, AG_B2 = 2L;
    static final long SKU_1 = 1L;               // AGENT_1：1 个码，二级 399 / 一级 349，佣金 50/码
    static final long SKU_10 = 2L;              // AGENT_10：10 个码，二级 3490 / 一级 2980，佣金 51/码
    static final long SKU_STUDENT = 3L;         // STUDENT_DIRECT：学员直购 799

    /** seed 的口径：二级单买 399 元、佣金 50 元/码 ⇒ 平台净收 349 元 = 一级价。 */
    static final long TIER2_SKU1 = 39_900L, COMM_SKU1 = 5_000L;
    static final long TIER1_SKU1 = 34_900L;
    static final long TIER2_SKU10 = 349_000L, COMM_SKU10 = 51_000L;

    static DataSource ds;
    static JdbcTemplate jdbc;
    static PricingService pricing;
    static AdminPricingController api;
    static OrderService orderSvc;
    static FulfillService fulfill;

    /** P4 那张「改价之前下的」订单。它的金额快照是后面几条判据的锚。 */
    static long oldOrderId;
    static long oldOrderAmountFen;

    @BeforeAll
    static void bootstrap() throws Exception {
        ds = ItDb.freshDatabase(DB);
        jdbc = new JdbcTemplate(ds);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        pricing = new PricingService(jdbc);
        var vault = new CodeVault(ItDb.testKey(77),
                "pricing-salt-not-for-production".getBytes(StandardCharsets.UTF_8));
        fulfill = new FulfillService(jdbc, pricing, tx, vault);
        orderSvc = new OrderService(jdbc, pricing);
        api = new AdminPricingController(jdbc, pricing, new AdminAudit(jdbc), tx);

        jdbc.update("INSERT INTO sys_user (id,phone,role) VALUES "
                  + "(10,'13100000010','AGENT'),(11,'13100000011','AGENT')");
        jdbc.update("INSERT INTO agent_profile (id,user_id,agent_code,level,status)"
                  + " VALUES (1,10,'A1',1,'ACTIVE')");
        // B2 是二级且有上级 —— 有意选的：只有它下单才会真的返佣金，
        // 「净收为负」那条闸在它身上才有牙齿。挂在一级下的话佣金恒为 0，
        // 任何正价的净收都 >= 0，闸门整个不会被求值而用例全绿。
        jdbc.update("INSERT INTO agent_profile (id,user_id,agent_code,level,parent_agent_id,status)"
                  + " VALUES (2,11,'B2',2,1,'ACTIVE')");

        // 夹具自检：下面所有数字都建立在 seed 的价目与佣金上，seed 改了这些用例会
        // 以「拒绝理由不对」之类的方式红着，而根因是种子变了。这里先把它钉死。
        assertEquals(TIER2_SKU1, pricing.priceOf(2, SKU_1).amountFen(), "seed 的二级单买价变了");
        assertEquals(TIER1_SKU1, pricing.priceOf(1, SKU_1).amountFen(), "seed 的一级单买价变了");
        assertEquals(COMM_SKU1, pricing.ruleOf(2, SKU_1).computeFen(TIER2_SKU1, 1),
                "seed 的单买佣金变了");
    }

    @AfterAll
    static void teardown() { ItDb.close(ds); }

    // ================================================================ 夹具

    static MockHttpServletRequest admin() {
        var r = new MockHttpServletRequest();
        r.setRequestURI("/api/admin/pricing");
        r.setAttribute(AuthInterceptor.ATTR_USER_ID, ADMIN);
        r.setAttribute(AuthInterceptor.ATTR_ROLE, "PLATFORM_ADMIN");
        r.setRemoteAddr("10.9.9.9");
        return r;
    }

    static Map<String, Object> setOverride(long agentId, long skuId, long fen, String reason) {
        return api.setOverride(new AdminPricingController.OverrideReq(agentId, skuId, fen, reason),
                admin());
    }

    static Map<String, Object> setTier(int level, long skuId, long fen, String reason) {
        return api.setTier(new AdminPricingController.TierReq(level, skuId, fen, reason), admin());
    }

    static Map<String, Object> preview(Long agentId, Integer level, long skuId, long fen) {
        return api.preview(new AdminPricingController.PreviewReq(level, agentId, skuId, fen), admin());
    }

    static long num(Object v) { return ((Number) v).longValue(); }

    static long count(String sql, Object... args) {
        Number n = jdbc.queryForObject(sql, Number.class, args);
        return n == null ? 0 : n.longValue();
    }

    static long activeOverrides(long agentId, long skuId) {
        return count("SELECT COUNT(*) FROM agent_price_override"
                   + " WHERE agent_id=? AND sku_id=? AND effective_to IS NULL", agentId, skuId);
    }

    // ================================================================ P1 - P3 取价

    @Test @Order(1)
    @DisplayName("P1 设专属价 → 取价专属优先，且来源指到 override 那一行")
    void overrideWins() {
        var res = setOverride(AG_B2, SKU_1, 30_000L, "谈过价：区域独家，走量");
        assertEquals(Boolean.TRUE, res.get("changed"));
        assertEquals(30_000L, num(res.get("totalPriceFen")));
        // 净收必须跟着出：没有这个数，运营按下确认时唯一的依据就是自己心算的差价
        assertEquals(30_000L - COMM_SKU1, num(res.get("netFen")));

        var q = pricing.priceOf(AG_B2, 2, SKU_1);
        assertEquals(30_000L, q.amountFen(), "设了专属价却还是按层级价取 —— 这家谈的价没有生效");
        assertTrue(q.fromOverride());
        assertEquals(num(res.get("overrideId")), q.overrideId().longValue());
        assertNull(q.tierId(),
                "走专属价却带回了 tierId：两张表的主键会撞，写进订单就是一个与金额对不上的来源");
        assertEquals(TIER2_SKU1, q.tierAmountFen(),
                "同档层级价没带出来 —— 界面上那个专属价就是个孤零零的数字，改错一位没人看得出来");
    }

    @Test @Order(2)
    @DisplayName("P2 阴性对照：没设专属价的那一家（和没设的那个套餐）仍然回落层级价")
    void fallbackToTier() {
        // 同一家代理、另一个套餐：专属价是按 (代理,套餐) 设的，不是按代理整体。
        // 这条挡的是「一条专属价把这家所有套餐都盖住了」——
        // 表现是批量档也按单买的专属价成交，金额是个合法的数，没有任何报错。
        var other = pricing.priceOf(AG_B2, 2, SKU_10);
        assertEquals(TIER2_SKU10, other.amountFen(),
                "另一个套餐也被专属价盖住了：专属价是按 (代理,套餐) 设的");
        assertFalse(other.fromOverride());
        assertNotNull(other.tierId());

        // 另一家代理：一级 A1 从没设过专属价，必须原样走层级价
        var another = pricing.priceOf(AG_A1, 1, SKU_1);
        assertEquals(TIER1_SKU1, another.amountFen(),
                "一家代理的专属价漏给了另一家 —— 查询少了 agent_id 这个条件");
        assertFalse(another.fromOverride());
    }

    @Test @Order(3)
    @DisplayName("P3 改价 = 封口旧行 + 插新行：历史留着，当前生效只剩一条，取价是新价")
    void repriceSealsAndInserts() {
        long firstId = jdbc.queryForObject(
                "SELECT id FROM agent_price_override WHERE agent_id=? AND sku_id=? AND effective_to IS NULL",
                Long.class, AG_B2, SKU_1);

        var res = setOverride(AG_B2, SKU_1, 28_000L, "二次议价：加了两个城市");
        assertEquals(Boolean.TRUE, res.get("changed"));
        long secondId = num(res.get("overrideId"));
        assertNotEquals(firstId, secondId, "改价复用了同一行 —— 那是 UPDATE 旧行，历史被改写了");

        assertEquals(1, activeOverrides(AG_B2, SKU_1),
                "当前生效不止一条：同一家代理的两次下单可能按两个价成交，事后无法解释");
        assertEquals(28_000L, pricing.priceOf(AG_B2, 2, SKU_1).amountFen());

        // 老那一行必须原封不动地留着，而且金额还是老金额 —— 这就是「老订单查得回原价」。
        var old = jdbc.queryForMap("SELECT total_price_fen, effective_to FROM agent_price_override WHERE id=?",
                firstId);
        assertEquals(30_000L, num(old.get("total_price_fen")),
                "旧价目行的金额被改掉了：按这个价成交过的订单再查「当时是什么价」会查到新价");
        assertNotNull(old.get("effective_to"), "旧行没有封口");
    }

    // ================================================================ P4 老订单

    @Test @Order(4)
    @DisplayName("P4 老订单只认自己的快照：改完价，按新价付款被拒，按快照付款照常发码结佣")
    void oldOrdersSettleOnSnapshot() {
        // 下单走的是层级价（OrderService 今天还调两参重载，见 AdminPricingController 类注释）
        var created = orderSvc.create(U_B2, SKU_10, "1.2.3.4");
        oldOrderId = created.orderId();
        oldOrderAmountFen = created.amountFen();
        assertEquals(TIER2_SKU10, oldOrderAmountFen, "下单没有按当时的层级价成交");

        long newTier = 300_000L;                       // 3000 元，净收 2490 元，离 0 很远
        var res = setTier(2, SKU_10, newTier, "季度调价：批量档降到 3000");
        assertEquals(Boolean.TRUE, res.get("changed"));
        assertEquals(newTier - COMM_SKU10, num(res.get("netFen")));
        assertEquals(newTier, pricing.priceOf(2, SKU_10).amountFen(), "新层级价没生效");

        // 老单的快照一个字节都不许动
        var o = jdbc.queryForMap("SELECT amount_fen, price_tier_id FROM biz_order WHERE id=?", oldOrderId);
        assertEquals(oldOrderAmountFen, num(o.get("amount_fen")),
                "改价改动了已经下过的订单：客户看到的应付金额在下单之后变了，"
                + "而对账、退款、佣金三处会同时对不上");

        // 按新价付款必须被拒 —— 收款金额与应付不符时不能入账
        assertThrows(Exception.class,
                () -> fulfill.confirmPaid(oldOrderId, "WECHAT", "WX_NEWPRICE", newTier,
                        LocalDateTime.now(), null),
                "按改价后的新价也能把老单支付掉：那笔钱和这张单的应付额对不上");

        var ok = fulfill.confirmPaid(oldOrderId, "WECHAT", "WX_SNAPSHOT", oldOrderAmountFen,
                LocalDateTime.now(), null);
        assertTrue(ok.firstTime());
        assertEquals(10, ok.codesIssued());
        // 佣金的计算基数必须是快照实付，不是现价
        assertEquals(oldOrderAmountFen, count(
                "SELECT calc_basis_fen FROM commission_record WHERE order_id=?", oldOrderId),
                "佣金按改价后的新价算了：老单的返佣和它实际收到的钱不是一套账");
    }

    @Test @Order(5)
    @DisplayName("P5 专属价也不碰老订单：给这家设完专属价，那张老单的金额与来源逐字不变")
    void overrideDoesNotTouchOldOrders() {
        var before = jdbc.queryForMap(
                "SELECT amount_fen, price_tier_id, price_override_id FROM biz_order WHERE id=?", oldOrderId);
        setOverride(AG_B2, SKU_10, 320_000L, "批量档也谈了价");
        var after = jdbc.queryForMap(
                "SELECT amount_fen, price_tier_id, price_override_id FROM biz_order WHERE id=?", oldOrderId);
        assertEquals(before, after,
                "设一份专属价改动了已经下过的订单 —— 订单存的是快照，任何价目改动都不该碰它");
    }

    // ================================================================ P6 - P9 净收闸

    @Test @Order(6)
    @DisplayName("P6 ★ 净收为负当场拒：40 元的专属价扛不住 50 元的佣金，且库里一行都没多")
    void negativeNetIsRefused() {
        long rowsBefore = count("SELECT COUNT(*) FROM agent_price_override");
        long activeBefore = activeOverrides(AG_B2, SKU_1);
        long priceBefore = pricing.priceOf(AG_B2, 2, SKU_1).amountFen();

        var e = assertThrows(ApiException.class,
                () -> setOverride(AG_B2, SKU_1, 4_000L, "老板说给个友情价"));
        assertEquals(409, e.status(), "拒绝了，但状态码是 " + e.status() + "：" + e.getMessage());
        assertTrue(e.getMessage().contains("倒贴"),
                "拒绝理由不是「平台会倒贴」—— 这个负例可能是被别的东西拦下的：" + e.getMessage());
        // 报出去的必须是能对账的三个数，不是一句「不合法」：运营要拿它去找佣金规则
        assertTrue(e.getMessage().contains("50.00"),
                "拒绝时没有把佣金数摆出来，运营不知道该把哪个数调下来：" + e.getMessage());
        // ★ 这一句只有【应用层】那道闸说得出来（库层触发器的 SIGNAL 里没有它）。
        // 没有它的话，把 refuseIfNegative 整个删掉这条用例照样绿 —— 触发器会接住，
        // 报出来的还是一句带「倒贴」的话。而少了应用层那道，/preview 的 blocker 与
        // 确认按钮的禁用条件就全是空的：运营要一路点到提交才知道这个价存不下去。
        assertTrue(e.getMessage().contains("先把佣金规则调下来"),
                "拒绝它的不是接口这一层（这句话只有应用层会说）—— " + e.getMessage());

        // ★ 后半句才是重点。校验放在写库之后的话，旧价已经被封口 ——
        // 收场是「这家代理当前没有任何生效的专属价」，而接口报的是一句拒绝，
        // 运营以为什么都没发生。
        assertEquals(rowsBefore, count("SELECT COUNT(*) FROM agent_price_override"),
                "被拒之后仍然往价目表里写了行");
        assertEquals(activeBefore, activeOverrides(AG_B2, SKU_1),
                "被拒之后旧价被封口了：这家代理当前一条生效的专属价都没有，而接口只报了一句拒绝");
        assertEquals(priceBefore, pricing.priceOf(AG_B2, 2, SKU_1).amountFen(),
                "被拒之后取到的价变了");
    }

    @Test @Order(7)
    @DisplayName("P7 P6 的阴性对照：同样 40 元给一级代理 —— 应成功（一级没有上级，不返佣金）")
    void sameLowPriceIsFineForLevelOne() {
        // 没有这条，P6 绿着只能证明「便宜的价会被拒」—— 那样的闸门会把一级代理的正常降价
        // 也一起拒掉，而没有任何用例看得出来。
        var res = setOverride(AG_A1, SKU_1, 4_000L, "一级清库存价");
        assertEquals(Boolean.TRUE, res.get("changed"));
        assertEquals(0L, num(res.get("commissionFen")), "一级下单不返佣金，佣金应为 0");
        assertEquals(4_000L, num(res.get("netFen")));
        assertEquals(4_000L, pricing.priceOf(AG_A1, 1, SKU_1).amountFen());
    }

    @Test @Order(8)
    @DisplayName("P8 层级价上同一道闸：二级层级价低于佣金也当场拒，且这一档的现价没被封口")
    void negativeNetIsRefusedOnTierToo() {
        // 层级价错了坑的是整整一层代理，比专属价更贵。只在专属价那一侧设闸的话，
        // 表现是「专属价改不了、但层级价能改成同样的亏本数字」。
        long activeBefore = count("SELECT COUNT(*) FROM agent_price_tier"
                                + " WHERE agent_level=2 AND sku_id=? AND effective_to IS NULL", SKU_1);
        var e = assertThrows(ApiException.class, () -> setTier(2, SKU_1, 4_000L, "全线降价"));
        assertEquals(409, e.status(), e.getMessage());
        assertTrue(e.getMessage().contains("倒贴"), "拒绝理由不是净收为负：" + e.getMessage());
        assertTrue(e.getMessage().contains("先把佣金规则调下来"),
                "拒绝它的不是接口这一层（见 P6 里那段说明）—— " + e.getMessage());
        assertEquals(activeBefore, count("SELECT COUNT(*) FROM agent_price_tier"
                                       + " WHERE agent_level=2 AND sku_id=? AND effective_to IS NULL", SKU_1),
                "被拒之后这一档的现价被封口了：整层代理当场下不了单（priceOf 查到 0 行直接抛）");
        assertEquals(TIER2_SKU1, pricing.priceOf(2, SKU_1).amountFen());
    }

    @Test @Order(9)
    @DisplayName("P9 库层兜底：绕过接口直接 INSERT 亏本专属价，触发器照样拒")
    void databaseRefusesNegativeNetToo() {
        // 应用层那道是一句承诺，触发器才是执行。以后的批量导价、某次「临时改一下」
        // 都不会经过上面那个方法，而它们同样能把亏本价放进库里。
        var e = assertThrows(DataAccessException.class, () -> jdbc.update(
                "INSERT INTO agent_price_override (agent_id,sku_id,total_price_fen,"
              + "effective_from,reason,created_by) VALUES (?,?,?,NOW(3),?,?)",
                AG_B2, SKU_1, 100L, "绕过接口直接写", ADMIN));
        assertTrue(String.valueOf(e.getMessage()).contains("倒贴"),
                "拦下它的不是净收那条触发器 —— " + e.getMessage());
    }

    // ================================================================ P10 - P12 其余

    @Test @Order(10)
    @DisplayName("P10 试算与落库同源：preview 报的三个数，就是真存下去之后的三个数")
    void previewMatchesWhatGetsStored() {
        long fen = 33_000L;
        var p = preview(AG_B2, null, SKU_1, fen);
        @SuppressWarnings("unchecked")
        Map<String, Object> pv = (Map<String, Object>) p.get("preview");
        assertEquals(Boolean.FALSE, pv.get("negative"));
        // 净收 >= 0 但低于同档层级价：不拦，但必须被看见 —— 界面靠这两个字段把差额摆出来
        assertEquals(Boolean.TRUE, pv.get("belowTier"),
                "33000 分低于二级层级价 39900 分，preview 却说它不低于标准价 —— "
                + "界面上那句「比标准价便宜多少」就不会出现，运营是在没有参照的情况下按的确认");
        assertEquals(TIER2_SKU1, num(pv.get("tierPriceFen")));

        var res = setOverride(AG_B2, SKU_1, fen, "确认过差额之后的定价");
        // 试算和落库两套口径漂移的表现：运营确认的是一个数，真正结算的是另一个数，
        // 两边都不报错。所以这里逐字比对。
        assertEquals(pv.get("commissionFen"), res.get("commissionFen"),
                "试算的佣金和落库时算的佣金不是同一个数");
        assertEquals(pv.get("netFen"), res.get("netFen"),
                "试算的净收和落库时算的净收不是同一个数");
        assertEquals(fen - COMM_SKU1, num(res.get("netFen")));
    }

    @Test @Order(11)
    @DisplayName("P11 双击保护：同一个价再提交一次，changed:false 且不多一行改价史")
    void resubmittingSamePriceChangesNothing() {
        long rowsBefore = count("SELECT COUNT(*) FROM agent_price_override WHERE agent_id=? AND sku_id=?",
                AG_B2, SKU_1);
        long idBefore = jdbc.queryForObject(
                "SELECT id FROM agent_price_override WHERE agent_id=? AND sku_id=? AND effective_to IS NULL",
                Long.class, AG_B2, SKU_1);

        var again = assertDoesNotThrow(() -> setOverride(AG_B2, SKU_1, 33_000L, "手抖点了两下"),
                "重复提交抛了异常。这不是「拦住了重复改价」，是把一次成功的改价显示成失败");
        assertEquals(Boolean.FALSE, again.get("changed"));
        assertEquals(rowsBefore, count(
                "SELECT COUNT(*) FROM agent_price_override WHERE agent_id=? AND sku_id=?", AG_B2, SKU_1),
                "同价重提多写了一行改价史：事后翻历史时那一行看起来像一次真的改动");
        assertEquals(idBefore, jdbc.queryForObject(
                "SELECT id FROM agent_price_override WHERE agent_id=? AND sku_id=? AND effective_to IS NULL",
                Long.class, AG_B2, SKU_1));
    }

    @Test @Order(12)
    @DisplayName("P12 撤销专属价 = 封口不是删行：取价回落层级价，而历史行还查得到")
    void closingOverrideFallsBackToTier() {
        long id = jdbc.queryForObject(
                "SELECT id FROM agent_price_override WHERE agent_id=? AND sku_id=? AND effective_to IS NULL",
                Long.class, AG_B2, SKU_1);

        var res = api.closeOverride(id, new AdminPricingController.ReasonReq("合作到期，回标准价"), admin());
        assertEquals(Boolean.TRUE, res.get("changed"));
        assertEquals(TIER2_SKU1, num(res.get("fallbackTierPriceFen")));

        var q = pricing.priceOf(AG_B2, 2, SKU_1);
        assertFalse(q.fromOverride(), "撤销之后仍然取到专属价");
        assertEquals(TIER2_SKU1, q.amountFen(), "撤销之后没有回落到层级价");

        // 删行的话，老订单上的 price_override_id 会指向一个不存在的行 ——
        // 而那正是复盘时要查的东西。
        assertEquals(1, count("SELECT COUNT(*) FROM agent_price_override WHERE id=?", id),
                "撤销把那一行删掉了：按这个价成交过的订单再也查不回当时的价");
        assertNotNull(jdbc.queryForMap("SELECT effective_to FROM agent_price_override WHERE id=?", id)
                .get("effective_to"));

        // 重复点撤销回 200 changed:false，不是 409：运营看到红框会以为第一次没生效
        var twice = assertDoesNotThrow(() -> api.closeOverride(id,
                new AdminPricingController.ReasonReq("再点一次"), admin()));
        assertEquals(Boolean.FALSE, twice.get("changed"));
    }

    @Test @Order(13)
    @DisplayName("P13 层级与套餐必须对上：给学员直购档配代理价 400，理由指向「取价永远查不到」")
    void levelMustMatchSkuBuyerType() {
        // 这个组合在库层完全合法，而取价那一侧永远查不到它（学员档恒用 level=0）。
        // 表现是运营改完价、页面显示已生效，而买家那边一分钱没变 —— 没有任何报错。
        var e = assertThrows(ApiException.class, () -> setTier(1, SKU_STUDENT, 70_000L, "零售也降点"));
        assertEquals(400, e.status(), e.getMessage());
        assertTrue(e.getMessage().contains("学员直购档"), e.getMessage());

        var e2 = assertThrows(ApiException.class, () -> setTier(0, SKU_1, 30_000L, "代理档配零售价"));
        assertEquals(400, e2.status(), e2.getMessage());

        // 阴性对照：对上的组合照常改得动，否则这道闸就是把功能关掉
        var ok = setTier(0, SKU_STUDENT, 69_900L, "零售价下调");
        assertEquals(Boolean.TRUE, ok.get("changed"));
        assertEquals(69_900L, pricing.priceOf(0, SKU_STUDENT).amountFen());
    }

    @Test @Order(14)
    @DisplayName("P14 审计挂在代理身上：代理详情页那条流水里查得到这次定价")
    void auditIsFiledUnderTheAgent() {
        // 运营查「这家凭什么是这个价」会去代理详情页，而那一页的审计流水按
        // target_type='agent_profile' 查。挂到套餐上的话，这次定价在那一页上完全看不到，
        // 而纠纷复盘时问的第一句就是这个。
        assertTrue(count("SELECT COUNT(*) FROM audit_log WHERE action='AGENT_PRICE_OVERRIDE_SET'"
                       + " AND target_type='agent_profile' AND target_id=?", String.valueOf(AG_B2)) > 0,
                "专属价的审计没有挂在代理身上，代理详情页上看不到这次定价");
        assertTrue(count("SELECT COUNT(*) FROM audit_log WHERE action='AGENT_PRICE_OVERRIDE_CLOSE'"
                       + " AND target_type='agent_profile' AND target_id=?", String.valueOf(AG_B2)) > 0,
                "撤销专属价没有留下审计");
        assertTrue(count("SELECT COUNT(*) FROM audit_log WHERE action='PRICE_TIER_SET'") > 0,
                "改层级价没有留下审计");
        // 被拒的那两次不该留下审计：审计里出现一条「改成 40 元」而库里没有这个价，
        // 事后复盘会以为改动被人回滚了
        assertEquals(0, count("SELECT COUNT(*) FROM audit_log"
                            + " WHERE after_json LIKE '%\"totalPriceFen\":4000%'"
                            + " AND action='PRICE_TIER_SET'"),
                "一次被拒的改价也写了审计：库里没有这个价，而审计说改过");
    }

    @Test @Order(15)
    @DisplayName("P15 整张价目表出网时带着算好的净收，而且当前生效那一档每个组合只有一行")
    void listingCarriesNetAndIsUnambiguous() {
        var body = api.pricing(admin());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tiers = (List<Map<String, Object>>) body.get("tiers");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> overrides = (List<Map<String, Object>>) body.get("overrides");
        assertFalse(tiers.isEmpty(), "价目表是空的，下面的断言什么也没检查");

        for (var t : tiers) {
            assertNotNull(t.get("netFen"), "层级价没带净收：前端只能自己再算一遍，那是第二套口径");
            assertTrue(num(t.get("netFen")) >= 0,
                    "库里存着一档净收为负的层级价：" + t.get("skuCode") + " 层级 " + t.get("agentLevel"));
        }
        for (var o : overrides) {
            assertNotNull(o.get("tierPriceFen"),
                    "专属价没带同档层级价：界面上它就是个孤零零的数字，改错一位没人看得出来");
            assertTrue(num(o.get("netFen")) >= 0, "库里存着一条净收为负的专属价：" + o);
        }

        // 唯一键失守时 priceOf 会抛「取价二义」。这里对每个出现过的组合真取一次，
        // 让那条自检在测试里也被执行到。
        for (var o : overrides)
            assertDoesNotThrow(() -> pricing.priceOf(num(o.get("agentId")),
                    (int) num(o.get("level")), num(o.get("skuId"))));
    }
}
