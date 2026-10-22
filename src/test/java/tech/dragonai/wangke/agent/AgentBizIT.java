package tech.dragonai.wangke.agent;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tech.dragonai.wangke.ItDb;
import tech.dragonai.wangke.learn.ActivationService;
import tech.dragonai.wangke.trade.*;
import tech.dragonai.wangke.web.ApiException;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 代理经营面：工作台 / 商品 / 进货单 / 推广 / 佣金详情 / 资料 / 批量导码。
 *
 * <p>跑在真实 MySQL 上，因为这一块的规则有一半在触发器里（下单的代理状态闸、
 * 提现的余额闸、激活码的状态机），而且<b>数据一律走生产路径造</b>：
 * 下单 → 支付 → 出码 → 结佣。手写 INSERT 造出来的数据永远和真实路径写出来的不完全一样，
 * 而"统计口径对不对"考的正是那点不一样。
 *
 * <p>三条贯穿全文件的纪律：
 * <ol>
 *   <li><b>每个负例都钉住失败原因</b>。只断言"抛了异常"的用例太容易因为别的原因红
 *       （比如夹具根本没建起来），那样它通过时什么也没证明。
 *   <li><b>断言落到每个字段</b>，不是"没抛异常就算过"。字段名对不上时前端拿到的是
 *       undefined，那是白屏而不是报错。
 *   <li><b>应用层拦下来的规则要再验一次库里那道闸</b>。应用层的检查随时可能在重构里
 *       被绕过（新写一条 SQL、后台脚本、手工 UPDATE），只测应用层等于测了一层
 *       随时会消失的防线。
 * </ol>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AgentBizIT {

    static final String DB = "wangke_agentbiz_it";

    static DataSource ds;
    static JdbcTemplate jdbc;
    static AgentService agents;
    static WithdrawService withdraws;
    static FulfillService fulfill;
    static ActivationService activation;
    static OrderService orderService;

    static AgentDashboardService dashboards;
    static AgentShopService shop;
    static AgentProfileService profiles;
    static AgentCommissionDetailService commissionDetail;
    static AgentCodeExportService codeExport;
    /** 配好了学员端地址的那一份 */
    static AgentPromoteService promoteConfigured;
    /** 完全没配的那一份 —— 它必须如实说"我不知道"，而不是猜一个域名 */
    static AgentPromoteService promoteUnconfigured;

    /** 一级 甲（agent 1 / user 10）、二级 乙（agent 2 / user 11）、待审 丙、独立一级 丁 */
    static final long A_L1 = 1, A_L2 = 2, A_PENDING = 3, A_SOLO = 4;
    static final long U_L1 = 10, U_L2 = 11, U_PENDING = 12, U_SOLO = 13;
    /** 一个连代理档案都没有的普通学员号 */
    static final long U_PLAIN = 30;

    static final long SKU_ONE = 1, SKU_TEN = 2, SKU_STUDENT = 3;
    /** 专门用来验"单次导出上限"的大批量套餐 */
    static final long SKU_BULK = 90;
    static final int BULK_QTY = AgentCodeExportService.MAX_EXPORT + 1;

    /** 乙那张 10 个码的进货单 */
    static long bulkOrderId;

    @BeforeAll
    static void bootstrap() throws Exception {
        ds = ItDb.freshDatabase(DB);
        jdbc = new JdbcTemplate(ds);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        var vault = new CodeVault(ItDb.testKey(11), "agentbiz-salt".getBytes(StandardCharsets.UTF_8));
        var pricing = new PricingService(jdbc);
        var codeService = new CodeService(jdbc, vault);

        agents = new AgentService(jdbc, tx, codeService);
        withdraws = new WithdrawService(jdbc, tx, agents);
        orderService = new OrderService(jdbc, pricing);
        fulfill = new FulfillService(jdbc, pricing, tx, vault);
        activation = new ActivationService(jdbc, tx, vault);

        dashboards = new AgentDashboardService(jdbc, agents);
        shop = new AgentShopService(jdbc, agents, pricing, orderService);
        profiles = new AgentProfileService(jdbc, tx, agents);
        commissionDetail = new AgentCommissionDetailService(jdbc, agents);
        codeExport = new AgentCodeExportService(jdbc, tx, agents, vault);
        promoteConfigured = new AgentPromoteService(agents,
                "https://course.dragonai.tech/study/", "https://course.dragonai.tech/landing");
        promoteUnconfigured = new AgentPromoteService(agents, "", "");

        jdbc.update("INSERT INTO sys_user (id,phone,role,real_name) VALUES"
                  + " (10,'13100000010','AGENT','甲一级'),(11,'13100000011','AGENT','乙二级'),"
                  + " (12,'13100000012','AGENT','丙待审'),(13,'13100000013','AGENT','丁独立')");
        jdbc.update("INSERT INTO sys_user (id,phone,role) VALUES (20,'13200000020','STUDENT')");
        // 21 是点甲的推广链接注册的学员：没买过任何东西，但算甲名下的人
        jdbc.update("INSERT INTO sys_user (id,phone,role,attributed_agent_id,attributed_at,"
                  + "attribution_source) VALUES (21,'13200000021','STUDENT',1,NOW(3),'REF_LINK')");
        jdbc.update("INSERT INTO sys_user (id,phone,role) VALUES (30,'13300000030','STUDENT')");

        jdbc.update("INSERT INTO agent_profile (id,user_id,agent_code,level,status,subject_type,"
                  + "settle_bank_name,settle_bank_acct) "
                  + "VALUES (1,10,'AGL1KEY0001',1,'ACTIVE','INDIVIDUAL','招商银行','6225880000001111')");
        // 乙有意不填 company_name / contact_name：佣金详情里"下级叫什么"必须为 null，
        // 而不是回退到 sys_user.real_name（那是身份证姓名）
        jdbc.update("INSERT INTO agent_profile (id,user_id,agent_code,level,parent_agent_id,status,"
                  + "subject_type,settle_bank_acct) "
                  + "VALUES (2,11,'AGL2KEY0002',2,1,'ACTIVE','INDIVIDUAL','6225880000002222')");
        jdbc.update("INSERT INTO agent_profile (id,user_id,agent_code,level,parent_agent_id,status) "
                  + "VALUES (3,12,'AGP3KEY0003',2,1,'PENDING')");
        jdbc.update("INSERT INTO agent_profile (id,user_id,agent_code,level,status,subject_type,"
                  + "settle_bank_acct) VALUES (4,13,'AGL4KEY0004',1,'ACTIVE','INDIVIDUAL','6225880000004444')");

        // 乙真金白银进 10 个码：下单 → 支付 → 出码 → 结佣，一步不跳
        var o = orderService.create(U_L2, SKU_TEN, "1.2.3.4");
        bulkOrderId = o.orderId();
        var paid = fulfill.confirmPaid(bulkOrderId, "WECHAT", "WX_AGENTBIZ_1", o.amountFen(),
                LocalDateTime.now(), null);
        assertEquals(10, paid.codesIssued(), "夹具没建起来，后面的断言都不作数");
        assertEquals(51_000, paid.commissionFen(), "佣金没结出来，佣金详情那一组会假绿");
    }

    @AfterAll
    static void teardown() { ItDb.close(ds); }

    // ============================================================== 工作台

    @Test @Order(1)
    @DisplayName("工作台的每个数都与 /stats、/balance、/students 同源，不是另算一遍")
    void dashboardMirrorsExistingEndpoints() {
        var d = dashboards.dashboard(U_L2);
        var stats = agents.stats(U_L2, null);
        var monthStats = agents.stats(U_L2, java.time.YearMonth.now().toString());
        var bal = agents.balance(U_L2);

        // 同源判据：不是"这个数字等于 10"，而是"这个数字与那个接口给的一模一样"。
        // 只断言字面值的话，两处口径一起漂移时用例照样绿。
        assertEquals(stats.get("unactivatedStock"), d.get("stockUnactivated"),
                "工作台库存与 /stats 对不上：点进列表会看到另一个数，而没有任何报错");
        // 进货金额【两处都不出网】（进货价一家一议之后代理端一律不出价）。
        // 同源判据换到还看得见的那个数上：码数。两处口径一起漂移时它照样抓得到，
        // 而它不泄漏单价。
        // 同源判据靠上面那条 unactivatedStock ↔ stockUnactivated（工作台没有 purchasedCodes 这个键）。
        assertFalse(d.containsKey("purchasedAmountFen"), "工作台把累计进货金额出网了");
        assertFalse(d.containsKey("monthPurchasedAmountFen"), "工作台把本月进货金额出网了");
        assertFalse(stats.containsKey("purchasedAmountFen"), "/stats 把累计进货金额出网了");
        assertFalse(monthStats.containsKey("purchasedAmountFen"), "/stats 月度把进货金额出网了");
        assertEquals(bal.get("withdrawableFen"), d.get("withdrawableFen"));
        assertEquals(bal.get("inflightWithdrawFen"), d.get("inflightWithdrawFen"));
        assertEquals(bal.get("frozenFen"), d.get("frozenCommissionFen"));
        assertEquals(agents.students(U_L2, 1, 20).total(), d.get("studentsTotal"));

        // 再核一遍绝对值，确认同源的那个值本身是对的（两边一起错也算错）
        assertEquals(10L, d.get("stockUnactivated"));
        // 金额不再出网，改钉库里那一行：这一单确实按二级批量价成交了。
        // 只断言"字段不在"是半条判据 —— 把取价整个删掉（金额恒 0）它照样绿。
        assertEquals(349_000L, jdbc.queryForObject(
                "SELECT amount_fen FROM biz_order WHERE id=?", Long.class, bulkOrderId),
                "成交金额与 seed 里的二级批量价对不上");
        assertEquals(0L, d.get("monthActivatedCodes"), "还没有人激活，动销必须是 0");
        assertEquals(0L, d.get("lastMonthActivatedCodes"));
        assertEquals(0L, d.get("studentsTotal"));
        // 还没结过佣的代理没有 agent_balance 行 —— 三个金额返 0 而不是 null。
        // 这里的 0 是真值：一分钱都还没结算过。
        assertEquals(0L, d.get("withdrawableFen"));
        assertEquals(0L, d.get("inflightWithdrawFen"));
        assertEquals(0L, d.get("frozenCommissionFen"));
        assertEquals(0L, d.get("withdrawNeedsAttention"));
        assertEquals(java.time.YearMonth.now().toString(), d.get("month"));

        // 期限：课程 activate_months=12，所以 10 个码都不在 90 天倒计时内，也没有过期的
        assertEquals(0L, d.get("expiringSoonCodes"));
        assertEquals(0L, d.get("expiredNotSweptCodes"));
        assertNotNull(d.get("nearestDeadline"), "有 10 个未激活的码，却算不出最近的截止日");
        assertTrue(d.get("nearestDeadline") instanceof LocalDateTime,
                "截止日必须是 LocalDateTime：走 getTimestamp 会按连接串时区平移几小时且不报错");

        var notes = notes(d);
        assertTrue(notes.containsKey("monthActivatedCodes"), "没说清动销是按 bound_at 归月的");
        assertTrue(notes.containsKey("withdrawNeedsAttention"), "没说清红点为什么有 30 天窗口");
        assertFalse(notes.containsKey("nearestDeadline"), "有截止日却还挂着'算不出来'的说明");
        assertFalse(notes.containsKey("withdrawableFen"), "余额不为负，不该有负余额说明");
        assertFalse(notes.containsKey("stockUnactivated"), "没有过期未扫的码，不该有差额说明");
    }

    @Test @Order(2)
    @DisplayName("一级的工作台：冻结佣金看得见，一个码都没有时最近截止日返 null 并说明为什么")
    void dashboardForUpstream() {
        var d = dashboards.dashboard(U_L1);
        assertEquals(0L, d.get("stockUnactivated"), "甲自己没进货");
        assertFalse(d.containsKey("purchasedAmountFen"), "工作台把累计进货金额出网了");
        assertEquals(51_000L, d.get("frozenCommissionFen"),
                "下级那单的佣金还在冻结期，必须显示在'冻结中'而不是可提现");
        assertEquals(0L, d.get("withdrawableFen"), "冻结期内的钱被算成了可提现");
        assertEquals(1L, d.get("studentsTotal"), "只点了推广链接、还没买的学员漏掉了 —— "
                + "那恰恰是最该跟进的一批");

        // 一个 ISSUED 码都没有 → 键直接消失（non_null），所以必须配一句话，
        // 否则前端看到的是"这个字段没了"，与"后端还没实现"长得一模一样
        assertNull(d.get("nearestDeadline"));
        assertTrue(notes(d).get("nearestDeadline").contains("一个未激活的码都没有"),
                "null 字段没有配说明：" + notes(d));
    }

    @Test @Order(3)
    @DisplayName("工作台是经营接口：审核中 403 说'审核中'，没有档案 403 说'去申请'")
    void dashboardIsAnOperationalEndpoint() {
        var pending = assertThrows(ApiException.class, () -> dashboards.dashboard(U_PENDING));
        assertEquals(403, pending.status());
        assertTrue(pending.getMessage().contains("审核中"),
                "拒绝理由不是'审核中'而是：" + pending.getMessage());

        var plain = assertThrows(ApiException.class, () -> dashboards.dashboard(U_PLAIN));
        assertEquals(403, plain.status());
        assertTrue(plain.getMessage().contains("还不是代理商"), plain.getMessage());
    }

    @Test @Order(4)
    @DisplayName("过了期限还没被扫成 EXPIRED 的码：仍按 ISSUED 计入库存，另出差额并说明")
    void expiredButNotSweptCodesAreExplainedNotHidden() {
        // 丁进 1 个码，然后把它的激活期限挪到昨天 —— 全仓没有任何定时任务会把它扫成 EXPIRED
        var o = orderService.create(U_SOLO, SKU_ONE, "1.2.3.4");
        fulfill.confirmPaid(o.orderId(), "WECHAT", "WX_SOLO_1", o.amountFen(),
                LocalDateTime.now(), null);
        jdbc.update("UPDATE activation_code SET activate_deadline = NOW(3) - INTERVAL 1 DAY "
                  + "WHERE owner_agent_id=?", A_SOLO);

        var d = dashboards.dashboard(U_SOLO);
        assertEquals(1L, d.get("stockUnactivated"),
                "在工作台悄悄把过期的码从库存里减掉，/stats 与 /codes 就会显示另一个数");
        assertEquals(1L, d.get("expiredNotSweptCodes"));
        assertEquals(0L, d.get("expiringSoonCodes"),
                "已过期的码被重复算进了'90 天内到期'，两个数不该重叠");
        assertTrue(notes(d).get("stockUnactivated").contains("1 个"),
                "差额没有解释，代理会把它当成系统算错：" + notes(d));
    }

    @Test @Order(5)
    @DisplayName("台账余额为负时可提现钳到 0，并说清真实余额为负、此刻提现会被库拒")
    void negativeBalanceIsClampedAndExplained() {
        jdbc.update("INSERT INTO agent_balance (agent_id,commission_balance_fen) VALUES (?,?)",
                A_SOLO, -12_345L);
        var d = dashboards.dashboard(U_SOLO);
        assertEquals(0L, d.get("withdrawableFen"),
                "负数会被前端当成输入框上限，算出一个负的上限然后干脆放弃校验");
        assertTrue(notes(d).get("withdrawableFen").contains("为负"), notes(d).toString());

        // 库里那道闸也必须真的在：负余额时一分钱都提不出去
        var denied = assertThrows(Exception.class, () ->
                jdbc.update("INSERT INTO withdraw_order (withdraw_no,agent_id,amount_fen,channel,"
                          + "payee_name,payee_account) VALUES ('WDNEG0000001',?,10000,"
                          + "'BANK_TRANSFER','丁独立','6225880000004444')", A_SOLO));
        assertTrue(rootMessage(denied).contains("余额为负"), rootMessage(denied));

        jdbc.update("DELETE FROM agent_balance WHERE agent_id=?", A_SOLO);
    }

    @Test @Order(6)
    @DisplayName("待办红点只数最近 30 天内被驳回/失败的提现单：不设窗口的红点等于没有红点")
    void withdrawNeedsAttentionHasAWindow() {
        jdbc.update("INSERT INTO agent_balance (agent_id,commission_balance_fen,total_settled_fen)"
                  + " VALUES (?,?,?)", A_L1, 100_000L, 100_000L);

        long old = insertWithdraw("WDATT0000001", A_L1, 60_000L);
        jdbc.update("UPDATE withdraw_order SET status='RISK_REJECTED' WHERE id=?", old);
        long recent = insertWithdraw("WDATT0000002", A_L1, 60_000L);
        jdbc.update("UPDATE withdraw_order SET status='RISK_REJECTED' WHERE id=?", recent);
        jdbc.update("UPDATE withdraw_order SET created_at = NOW(3) - INTERVAL 40 DAY WHERE id=?", old);

        assertEquals(1L, dashboards.dashboard(U_L1).get("withdrawNeedsAttention"),
                "40 天前那张被驳回的单还在点红点。RISK_REJECTED 是终态、库里没有'已知悉'标记，"
              + "不设窗口这个红点就永远不灭");

        // 两张都不在途，所以额度必须完全回吐 —— 否则代理会以为钱被扣住了
        assertEquals(0L, agents.balance(U_L1).get("inflightWithdrawFen"));
    }

    @Test @Order(7)
    @DisplayName("动销按 bound_at 归月：码后来被置成 EXPIRED，上个月的激活数也不该自己变小")
    void activatedCountUsesBoundAtNotStatus() {
        long codeId = (Long) agents.codes(U_L2, null, "ISSUED", 1, 1).items().get(0).get("id");
        String plain = agents.reveal(U_L2, codeId, "1.1.1.1");
        assertTrue(activation.redeem(20, plain, "v2026-08-11", "5.5.5.5").firstTime());

        var afterRedeem = dashboards.dashboard(U_L2);
        assertEquals(1L, afterRedeem.get("monthActivatedCodes"));
        assertEquals(9L, afterRedeem.get("stockUnactivated"));
        assertEquals(1L, afterRedeem.get("studentsTotal"), "激活了我的码的人没算进我的学员数");

        // BOUND → EXPIRED 是合法迁移（学习权限到期）。判据若写成 status='BOUND'，
        // 这一行就会让"本月激活了 1 个"这个已经发生过的事实当场变成 0。
        jdbc.update("UPDATE activation_code SET status='EXPIRED' WHERE id=?", codeId);
        var afterExpire = dashboards.dashboard(U_L2);
        assertEquals(1L, afterExpire.get("monthActivatedCodes"),
                "用 status 当判据了：学习权限一到期，历史激活数就会自己变小");
        assertEquals(9L, afterExpire.get("stockUnactivated"));
    }

    // ============================================================== 橱窗

    @Test @Order(10)
    @DisplayName("橱窗按我的层级出档，字段逐个对齐 SkuRow（其中没有价），且学员直购档一条都不出")
    void skuShopIsScopedToMyLevel() {
        var l2 = shop.skus(U_L2);
        assertEquals(2, l2.get("agentLevel"));
        assertEquals(2, l2.get("total"));
        var rows = rows(l2);
        assertEquals(2, rows.size());
        assertEquals(List.of("AGENT_1", "AGENT_10"),
                rows.stream().map(r -> r.get("skuCode")).toList(),
                "排序不是按码数从少到多，橱窗的档位顺序会跟价目表的插入顺序走");

        var single = rows.get(0);
        assertEquals(SKU_ONE, single.get("skuId"));
        assertEquals("代理单买 · 1 个激活码", single.get("name"));
        assertEquals(1, single.get("codeQty"));
        assertEquals("ON", single.get("status"));
        assertEquals("AI FDE 工程师就业实训营", single.get("courseTitle"));
        assertEquals(12, single.get("activateMonths"));
        assertEquals(24, single.get("accessMonths"));

        // ★ 橱窗不出价（进货价一家一议，代理端不出现任何价目）。
        //   键名与数值两道判据都在 AgentJsonShapeIT.assertNoAgentSideMoney 里 ——
        //   同一条规矩只写一份，两处各写一遍的失败方式是改了一处忘了另一处。
        AgentJsonShapeIT.assertNoAgentSideMoney(jdbc, l2, "AgentShopService.skus");

        // 层级取自令牌，接口根本没有 agentLevel 这个入参 —— 收了它就等于开放
        // "二级代理看一级价目"。价不出网之后，这条闸唯一还看得见的表现是
        // agentLevel 与"哪些档买得到"（后者由 unpricedSkuIsDroppedNotZeroed 钉着）。
        assertEquals(1, shop.skus(U_L1).get("agentLevel"));
        assertEquals(2, rows(shop.skus(U_L1)).size());

        // 学员直购档绝不能出现：代理买到它会建出一张 buyer_agent_id=NULL 的单，
        // 付了钱一个码都不到他名下，而那张单在进货记录里根本不出现
        assertTrue(rows.stream().noneMatch(r -> "STUDENT_DIRECT".equals(r.get("skuCode"))),
                "学员直购档出现在了代理橱窗里");
        assertEquals(List.of(), l2.get("unpricedSkuCodes"));
    }

    @Test @Order(11)
    @DisplayName("★ 代理看不到价了，成交价更要钉住：建单落库的金额必须等于 priceOf 给的那个数")
    void orderPriceComesFromPricingService() {
        // 这条判据原来叫"橱窗价 = 下单价"，靠比对两个响应里的数字。
        // 橱窗与建单响应都不再出价之后，那种比法没有了对象 —— 而"两处各查一遍价目表"
        // 这个病并没有消失，只是<b>症状从'橱窗写 2980、下单扣 3490'变成了完全无症状</b>：
        // 代理看不到任何数字，扣多少他都不知道。所以判据改钉库里那一行。
        long expect = new PricingService(jdbc).priceOf(2, SKU_ONE).amountFen();
        assertTrue(expect > 0, "夹具不成立：二级单买档没有现行价");

        var created = shop.createOrder(U_L2, SKU_ONE, "1.2.3.4");
        assertFalse(created.containsKey("amountFen"),
                "建单响应又开始出金额了 —— 页面不画它，所以只有 F12 里看得见");
        assertEquals(expect, ((Number) jdbc.queryForMap(
                "SELECT amount_fen FROM biz_order WHERE id=?", created.get("orderId"))
                .get("amount_fen")).longValue(),
                "落库的成交金额不是 priceOf 给的那个数。代理端已经不显示金额，"
              + "这个数错了不会有任何人在界面上发现，只会在对账时炸出来");
    }

    @Test @Order(12)
    @DisplayName("没有现行价的档整行不出，并在 unpricedSkuCodes + notes 里说明为什么")
    void unpricedSkuIsDroppedNotZeroed() {
        // 把二级单买那一档的现行价封口（改价的正确姿势就是封口，不是删行）
        jdbc.update("UPDATE agent_price_tier SET effective_to=NOW(3) "
                  + "WHERE agent_level=2 AND sku_id=? AND effective_to IS NULL", SKU_ONE);
        try {
            var body = shop.skus(U_L2);
            var rows = rows(body);
            assertEquals(1, rows.size(), "取不到价的那一行还留在橱窗里");
            assertEquals(1, body.get("total"), "total 必须是实际返回的行数");
            assertEquals("AGENT_10", rows.get(0).get("skuCode"));
            assertEquals(List.of("AGENT_1"), body.get("unpricedSkuCodes"));
            assertTrue(notes(body).get("unpricedSkuCodes").contains("AGENT_1"), notes(body).toString());

            // ★ 配对：同一时刻一级看得到 AGENT_1（他那一层的价没被封）。
            //   价不出网之后，"层级取自令牌、不是随便谁都能看别人那一层"这条闸
            //   就只剩下"哪些档买得到"这一个可观测表现了 —— 少了这半条，
            //   把 priceOf 的层级参数写死成 1 也照样绿。
            assertTrue(rows(shop.skus(U_L1)).stream()
                            .anyMatch(r -> "AGENT_1".equals(r.get("skuCode"))),
                    "封的是二级那一档的价，一级的橱窗却也跟着少了一行");

            // 而且这一档此刻确实买不了：橱窗与下单是同一个取价，拒绝理由要是人话
            var e = assertThrows(ApiException.class, () -> shop.createOrder(U_L2, SKU_ONE, "1.1.1.1"));
            assertEquals(409, e.status());
            assertTrue(e.getMessage().contains("暂时不售"), e.getMessage());
            assertFalse(e.getMessage().contains("层级"),
                    "把 priceOf 给运维看的原话透出去了，内部编号和定价结构一起送出：" + e.getMessage());
        } finally {
            jdbc.update("UPDATE agent_price_tier SET effective_to=NULL "
                      + "WHERE agent_level=2 AND sku_id=? AND effective_to IS NOT NULL", SKU_ONE);
        }
    }

    @Test @Order(13)
    @DisplayName("下架的档不出橱窗；橱窗是经营接口，审核中的代理拿 403")
    void offShelfAndPending() {
        jdbc.update("UPDATE sku SET status='OFF' WHERE id=?", SKU_ONE);
        try {
            var rows = rows(shop.skus(U_L2));
            assertEquals(1, rows.size());
            assertEquals("AGENT_10", rows.get(0).get("skuCode"));
        } finally {
            jdbc.update("UPDATE sku SET status='ON' WHERE id=?", SKU_ONE);
        }

        var e = assertThrows(ApiException.class, () -> shop.skus(U_PENDING));
        assertEquals(403, e.status());
        assertTrue(e.getMessage().contains("审核中"),
                "审核中的代理看得到价目却下不了单，只会以为是系统坏了：" + e.getMessage());
    }

    // ============================================================== 建单

    @Test @Order(20)
    @DisplayName("建单只收 skuId：金额、码数、层级全部来自服务端；响应里有码数没有金额")
    void createOrderTakesOnlySkuId() {
        var c = shop.createOrder(U_L2, SKU_TEN, "9.9.9.9");
        assertTrue((Long) c.get("orderId") > 0);
        assertTrue(((String) c.get("orderNo")).startsWith("WK"), "单号形状不对：" + c.get("orderNo"));
        // 金额不回给代理（进货价一家一议，代理端不出现任何价目），
        // 但码数必须回：那是他这一步唯一能核对的量 —— 提交完连"几个码"都不知道，
        // 他只能靠再提交一次来确认自己点中了哪一档。
        AgentJsonShapeIT.assertNoAgentSideMoney(jdbc, c, "AgentShopService.createOrder");
        assertEquals(10, c.get("codeQty"));
        assertEquals("代理批量 · 10 个激活码", c.get("skuName"));
        // expireAt 缺了，前端的付款倒计时一开就是 0，用户会以为单子当场过期
        assertNotNull(c.get("expireAt"), "没有关单时刻，付款页倒计时开局即 0");
        assertTrue(c.get("expireAt") instanceof LocalDateTime);
        long left = (Long) c.get("expireInSeconds");
        assertTrue(left > 7000 && left <= 7200,
                "关单时刻不是数据库用自己的时钟写的（120 分钟），实得 " + left + " 秒");

        // 快照必须落库：price_tier_id 是"这个钱哪来的"，缺了事后无法复盘
        var row = jdbc.queryForMap("SELECT buyer_agent_id, buyer_agent_level, price_tier_id,"
                + " amount_fen, code_qty_snapshot, status FROM biz_order WHERE id=?", c.get("orderId"));
        assertEquals(A_L2, ((Number) row.get("buyer_agent_id")).longValue());
        assertEquals(2, ((Number) row.get("buyer_agent_level")).intValue());
        assertNotNull(row.get("price_tier_id"), "没记取价来源，改价之后这单的金额无法解释");
        assertEquals("CREATED", row.get("status"));

        // 重复 POST 不做去重：每次一张新的 CREATED 单，120 分钟自动关闭、不占任何库存。
        // 真幂等要靠 client_token 唯一键裁决，schema 里没有这一列，不许用"先查后写"顶替。
        var again = shop.createOrder(U_L2, SKU_TEN, "9.9.9.9");
        assertNotEquals(c.get("orderNo"), again.get("orderNo"));
    }

    @Test @Order(21)
    @DisplayName("学员直购档、不存在的档、已下架的档：三种拒绝各说各的，且都在建单之前")
    void createOrderRejectsWrongSku() {
        long before = orderCount(A_L2);

        var student = assertThrows(ApiException.class,
                () -> shop.createOrder(U_L2, SKU_STUDENT, "1.1.1.1"));
        assertEquals(400, student.status());
        assertTrue(student.getMessage().contains("学员直购套餐"),
                "没拦住学员档：代理会付完 799 却一个码都不到名下，而且这张单在进货记录里查不到");

        var missing = assertThrows(ApiException.class,
                () -> shop.createOrder(U_L2, 99_999L, "1.1.1.1"));
        assertEquals(400, missing.status());
        assertTrue(missing.getMessage().contains("不存在"), missing.getMessage());

        jdbc.update("UPDATE sku SET status='OFF' WHERE id=?", SKU_TEN);
        try {
            var off = assertThrows(ApiException.class,
                    () -> shop.createOrder(U_L2, SKU_TEN, "1.1.1.1"));
            assertEquals(409, off.status());
            assertTrue(off.getMessage().contains("已下架"), off.getMessage());
        } finally {
            jdbc.update("UPDATE sku SET status='ON' WHERE id=?", SKU_TEN);
        }

        assertEquals(before, orderCount(A_L2), "被拒的三次里有人已经建了单");
    }

    @Test @Order(22)
    @DisplayName("非 ACTIVE 代理不能进货：应用层给人话，库里那道闸也必须真的在")
    void createOrderRequiresActiveProfile() {
        var e = assertThrows(ApiException.class, () -> shop.createOrder(U_PENDING, SKU_TEN, "1.1.1.1"));
        assertEquals(403, e.status());
        assertTrue(e.getMessage().contains("审核中"),
                "让触发器兜底的话，用户看到的是'代理商不在有效状态，不能下单'那句面向数据库的文案："
                        + e.getMessage());

        // 绕过应用层直插：裁决权始终在触发器手里
        var bypass = assertThrows(Exception.class, () ->
                jdbc.update("INSERT INTO biz_order (order_no,buyer_user_id,buyer_type,buyer_agent_id,"
                          + "buyer_agent_level,sku_id,sku_code_snapshot,code_qty_snapshot,amount_fen,"
                          + "expire_at) VALUES ('WKBYPASS00001',12,'AGENT',?,2,2,'AGENT_10',10,349000,"
                          + "DATE_ADD(NOW(3), INTERVAL 60 MINUTE))", A_PENDING));
        assertTrue(rootMessage(bypass).contains("代理商不在有效状态"),
                "库里那道闸没生效，应用层就是唯一防线：" + rootMessage(bypass));
    }

    // ============================================================== 进货记录

    @Test @Order(23)
    @DisplayName("进货记录按 buyer_agent_id 过滤：自己以学员身份买的课不算进货，也不该混进来")
    void ordersAreScopedByAgentNotUser() {
        // 乙用同一个账号按学员价给自己买一份课：buyer_agent_id 为 NULL、不出码到他名下
        var selfBuy = orderService.create(U_L2, SKU_STUDENT, "1.1.1.1");
        assertNull(jdbc.queryForMap("SELECT buyer_agent_id FROM biz_order WHERE id=?",
                selfBuy.orderId()).get("buyer_agent_id"), "夹具不成立");

        var page = shop.orders(U_L2, null, 1, 100);
        var rows = rows(page);
        assertEquals(orderCount(A_L2), page.get("total"));
        assertTrue(rows.stream().noneMatch(r -> selfBuy.orderNo().equals(r.get("orderNo"))),
                "学员身份买的那张单混进了进货记录，代理会以为自己进的货丢了");

        // 主键字段名必须是 id：前端 rowKey={r=>r.id}、"继续付款"传 orderId:r.id
        for (var r : rows) {
            assertNotNull(r.get("id"), "行里没有 id：整片列表没有 key，'继续付款'传的是 undefined");
            assertFalse(r.containsKey("orderId"), "字段名是 orderId，与前端 OrderRow 对不上");
            assertNotNull(r.get("createdAt"));
            assertTrue((Long) r.get("expireInSeconds") >= 0, "倒计时秒数出现负值");
        }
        // 降序：最新的单在最前面
        assertTrue((Long) rows.get(0).get("id") > (Long) rows.get(rows.size() - 1).get("id"));

        // 那张走完全链路的单：状态语义要如实给
        var fulfilled = rows.stream()
                .filter(r -> ((Long) r.get("id")) == bulkOrderId).findFirst().orElseThrow();
        assertEquals("FULFILLED", fulfilled.get("status"),
                "PAID 只表示钱到了，FULFILLED 才表示码出完了 —— 界面按它决定说'已出码'还是'正在出码'");
        // 三个金额（应付 / 实付 / 已退）一个都不出：它们都是按进货价算的，
        // 退款金额同样能把进货价倒推出来。这里连整份响应一起扫，
        // 因为下一个人最可能的做法是"只把 amountFen 拿掉、留下 paidAmountFen"。
        AgentJsonShapeIT.assertNoAgentSideMoney(jdbc, page, "AgentShopService.orders");
        assertEquals(10, fulfilled.get("codeQty"));
        assertEquals("AGENT_10", fulfilled.get("skuCode"));
        assertEquals("代理批量 · 10 个激活码", fulfilled.get("skuName"));
        assertNotNull(fulfilled.get("paidAt"));

        // 别人的单一条都不出现
        assertTrue(rows(shop.orders(U_SOLO, null, 1, 100)).stream()
                        .noneMatch(r -> ((Long) r.get("id")) == bulkOrderId),
                "越权条件不在 WHERE 里：别人的进货单出现在了我的列表里");
    }

    @Test @Order(24)
    @DisplayName("状态筛选与分页：未知状态当场 400，翻过尾页返回空但 total 仍是真实总数")
    void ordersFilteringAndPaging() {
        long total = orderCount(A_L2);
        assertEquals(1, rows(shop.orders(U_L2, "FULFILLED", 1, 100)).size());
        assertEquals(0, rows(shop.orders(U_L2, "REFUNDED", 1, 100)).size());

        var bad = assertThrows(ApiException.class, () -> shop.orders(U_L2, "Paid ok", 1, 20));
        assertEquals(400, bad.status());
        assertTrue(bad.getMessage().contains("未知的订单状态"),
                "查空会被前端读成'我没有订单'，这种错能在线上活很久：" + bad.getMessage());

        assertThrows(ApiException.class, () -> shop.orders(U_L2, null, 0, 20));
        assertEquals(AgentService.MAX_PAGE_SIZE, shop.orders(U_L2, null, 1, 1_000_000).get("size"),
                "size 不封顶就是一次全量导出");

        var past = shop.orders(U_L2, null, 99, 20);
        assertEquals(0, rows(past).size());
        assertEquals(total, past.get("total"), "翻过尾页时 total 跟着变 0，分页控件会整个消失");
    }

    @Test @Order(25)
    @DisplayName("按单号查单：扁平形状、越权与不存在同一句 404、倒计时钳到 0")
    void orderByNo() {
        String orderNo = (String) jdbc.queryForMap(
                "SELECT order_no FROM biz_order WHERE id=?", bulkOrderId).get("order_no");

        var o = shop.order(U_L2, orderNo);
        // 扁平：前端 api.get<OrderRow>(...) 之后直接读 o.status。
        // 包成 { order: {...} } 会让轮询永远读到 undefined —— 表现是付款成功后
        // 页面一直停在"待支付"，用户会再付一次。
        assertFalse(o.containsKey("order"), "响应被包了一层，轮询会永远读到 undefined");
        assertEquals(bulkOrderId, o.get("id"));
        assertEquals(orderNo, o.get("orderNo"));
        assertEquals("FULFILLED", o.get("status"));
        AgentJsonShapeIT.assertNoAgentSideMoney(jdbc, o, "AgentShopService.order");
        assertEquals(10, o.get("codeQty"));
        assertNotNull(o.get("expireAt"));
        assertNotNull(o.get("createdAt"));

        var notMine = assertThrows(ApiException.class, () -> shop.order(U_SOLO, orderNo));
        var missing = assertThrows(ApiException.class, () -> shop.order(U_L2, "WK20260811ZZZZZZZZZZ"));
        assertEquals(404, notMine.status());
        assertEquals(404, missing.status());
        assertEquals(missing.getMessage(), notMine.getMessage(),
                "'不是你的'和'不存在'回话不同，等于开放了一个可以枚举全站单号的探针");
        assertFalse(notMine.getMessage().contains(orderNo));

        // 已经过了关单时刻的单：秒数钳到 0，负数会让前端倒计时显示成负值
        var stale = shop.createOrder(U_L2, SKU_ONE, "1.1.1.1");
        jdbc.update("UPDATE biz_order SET expire_at = NOW(3) - INTERVAL 1 HOUR WHERE id=?",
                stale.get("orderId"));
        assertEquals(0L, shop.order(U_L2, (String) stale.get("orderNo")).get("expireInSeconds"));
    }

    // ============================================================== 推广

    @Test @Order(30)
    @DisplayName("推广链接由后端拼：配了就用配置，没配就标明是猜的，都没有就返 null 并说明")
    void promoteLink() {
        var cfg = promoteConfigured.promote(U_L1, "https://ignored.example.com");
        assertEquals("AGL1KEY0001", cfg.get("agentCode"));
        assertEquals("https://course.dragonai.tech/study/register?ref=AGL1KEY0001", cfg.get("refUrl"),
                "配置里带结尾斜杠时拼出了双斜杠，或者 ref 参数名写错");
        assertEquals("CONFIG", cfg.get("refUrlSource"));
        assertEquals("https://course.dragonai.tech/landing", cfg.get("landingUrl"));
        assertEquals(true, cfg.get("attributionActive"));
        assertFalse(notes(cfg).containsKey("refUrl"));

        // 没配置但请求头可信：兜底拼出来，并明确标成"这是猜的"
        var guessed = promoteUnconfigured.promote(U_L1, "https://wk.example.com");
        assertEquals("https://wk.example.com/study/register?ref=AGL1KEY0001", guessed.get("refUrl"));
        assertEquals("REQUEST_ORIGIN", guessed.get("refUrlSource"));
        assertTrue(notes(guessed).get("refUrlSource").contains("确认"), notes(guessed).toString());

        // 什么都没有：绝不编一个看起来像样的域名 —— 它会被印在传单和二维码上发出去
        var blind = promoteUnconfigured.promote(U_L1, null);
        assertNull(blind.get("refUrl"), "算不出来却编了一个域名出来：" + blind.get("refUrl"));
        assertNull(blind.get("refUrlSource"));
        assertNull(blind.get("landingUrl"));
        assertTrue(notes(blind).get("refUrl").contains("传单"), notes(blind).toString());
        assertTrue(notes(blind).containsKey("landingUrl"));
    }

    @Test @Order(31)
    @DisplayName("推广页对审核中的代理放行，但必须说清这段时间发出去的链接一个人都归属不到")
    void promoteIsOpenToPendingButSaysSo() {
        var p = promoteConfigured.promote(U_PENDING, null);
        assertEquals("AGP3KEY0003", p.get("agentCode"),
                "审核期唯一有用的一页也给了 403，而他什么都没做错");
        assertEquals(false, p.get("attributionActive"));
        assertTrue(notes(p).get("attributionActive").contains("归属不到"),
                "没说清审核期的链接白发：resolveRef 对非 ACTIVE 代理是静默忽略的，"
              + "不报错、不落库、什么痕迹都没有。" + notes(p));

        // 没有档案 → 404，且与 /me 同一句话（前端 Gate 靠它画"去申请"的入口）
        var none = assertThrows(ApiException.class, () -> promoteConfigured.promote(U_PLAIN, null));
        assertEquals(404, none.status());
        var me = assertThrows(ApiException.class, () -> agents.me(U_PLAIN));
        assertEquals(me.getMessage(), none.getMessage(),
                "同一件事在两个页面上有两种说法");
    }

    // ============================================================== 资料

    @Test @Order(40)
    @DisplayName("改结算账户：只改动过的列进 SET，审计只落掩码，在途提现单按申请时的账户走")
    void updateSettleAccount() {
        // 先申请一张在途提现单，把当时的收款账户快照下来
        var w = withdraws.apply(U_L1, 20_000L, "BANK_TRANSFER", "1.1.1.1");
        String snapshot = jdbc.queryForObject(
                "SELECT payee_account FROM withdraw_order WHERE id=?", String.class, w.id());
        assertEquals("6225880000001111", snapshot);

        int auditBefore = auditCount("AGENT_PROFILE_UPDATE");
        var r = profiles.updateProfile(U_L1,
                new AgentProfileService.UpdateReq(null, null, null, null, "6225 8800 0000 9999"),
                "7.7.7.7");
        assertEquals(List.of("settleBankAcct"), r.get("updated"),
                "把没改的字段也算成改动了，'点了保存但什么都没改'与'保存成功'就分不开");
        assertTrue(((String) r.get("message")).contains("申请时的收款账户"),
                "改了卡号却没提醒在途提现单仍走原账户，用户会以为新账户立刻生效："
                        + r.get("message"));

        var row = jdbc.queryForMap("SELECT settle_bank_acct, settle_bank_name, contact_name"
                + " FROM agent_profile WHERE id=?", A_L1);
        assertEquals("6225880000009999", row.get("settle_bank_acct"),
                "银行回单上的账号常带空格，用户照抄过来必须能存进去");
        assertEquals("招商银行", row.get("settle_bank_name"), "没改的列被顺手写了");

        // 审计：只落掩码 + 一个布尔。把完整卡号复制进第二张表，等于把同一份 PII 多存一处
        assertEquals(auditBefore + 1, auditCount("AGENT_PROFILE_UPDATE"));
        var log = jdbc.queryForMap("SELECT operator_id, target_id, after_json, client_ip"
                + " FROM audit_log WHERE action='AGENT_PROFILE_UPDATE' ORDER BY id DESC LIMIT 1");
        assertEquals(U_L1, ((Number) log.get("operator_id")).longValue());
        assertEquals(String.valueOf(A_L1), log.get("target_id"));
        assertEquals("7.7.7.7", log.get("client_ip"));
        String json = String.valueOf(log.get("after_json"));
        assertTrue(json.contains("****9999"), json);
        assertTrue(json.contains("****1111"), json);
        assertTrue(json.contains("settleBankAcct"), json);
        assertFalse(json.contains("6225880000009999"), "完整卡号被复制进了审计表：" + json);

        // 改结算账户不影响在途提现单：申请那一刻就快照了收款人，这是前端确认框里
        // 对用户的原话，必须有测试钉住它，而不是靠读代码相信
        assertEquals(snapshot, jdbc.queryForObject(
                "SELECT payee_account FROM withdraw_order WHERE id=?", String.class, w.id()),
                "在途提现单的收款账户被改资料改掉了 —— 钱会打到用户以为还没生效的新账户上");
    }

    @Test @Order(41)
    @DisplayName("null=不改、空串=清空；五个字段都没变时不发 UPDATE，也不留一条空审计")
    void updateSemanticsNullVsEmpty() {
        profiles.updateProfile(U_L1,
                new AgentProfileService.UpdateReq(null, "某某教育科技", null, null, null), "1.1.1.1");
        assertEquals("某某教育科技", jdbc.queryForMap(
                "SELECT company_name FROM agent_profile WHERE id=?", A_L1).get("company_name"));

        // 空串 = 用户在界面上把它删空了，必须真的清成 NULL；
        // 反过来定义（空串也当成不改）的话，用户会发现自己删不掉填错的公司名，而界面上没有任何提示
        var cleared = profiles.updateProfile(U_L1,
                new AgentProfileService.UpdateReq(null, "", null, null, null), "1.1.1.1");
        assertEquals(List.of("companyName"), cleared.get("updated"));
        assertNull(jdbc.queryForMap("SELECT company_name FROM agent_profile WHERE id=?", A_L1)
                .get("company_name"), "空串没有把这一列清成 NULL，'清空'这个动作是无效的");

        // 一个字段都没变：不发 UPDATE（不推进 updated_at）、不写审计
        int auditBefore = auditCount("AGENT_PROFILE_UPDATE");
        Object touchedBefore = jdbc.queryForMap(
                "SELECT updated_at FROM agent_profile WHERE id=?", A_L1).get("updated_at");
        var noop = profiles.updateProfile(U_L1,
                new AgentProfileService.UpdateReq(null, null, null, "招商银行", "6225880000009999"),
                "1.1.1.1");
        assertEquals(List.of(), noop.get("updated"));
        assertEquals("没有需要保存的改动", noop.get("message"));
        assertEquals(auditBefore, auditCount("AGENT_PROFILE_UPDATE"),
                "什么都没改也写了一条审计，日后查'这张卡是什么时候被换掉的'要在噪声里翻");
        assertEquals(touchedBefore, jdbc.queryForMap(
                "SELECT updated_at FROM agent_profile WHERE id=?", A_L1).get("updated_at"));

        // 全空请求同样是 no-op，而不是"把五列全清空"
        assertEquals(List.of(), profiles.updateProfile(U_L1,
                new AgentProfileService.UpdateReq(null, null, null, null, null), "1.1.1.1")
                .get("updated"));
    }

    @Test @Order(42)
    @DisplayName("超长、账号带字母、税号位数不对：各自 400 说人话，不能落到 MySQL 变成 500")
    void updateValidation() {
        var longName = assertThrows(ApiException.class, () -> profiles.updateProfile(U_L1,
                new AgentProfileService.UpdateReq("名".repeat(65), null, null, null, null), "1"));
        assertEquals(400, longName.status());
        assertTrue(longName.getMessage().contains("最多 64"),
                "超长交给 MySQL 拦（1406）会变成一句'系统繁忙'，用户以为系统坏了："
                        + longName.getMessage());

        var letters = assertThrows(ApiException.class, () -> profiles.updateProfile(U_L1,
                new AgentProfileService.UpdateReq(null, null, null, null, "62258800ABC"), "1"));
        assertTrue(letters.getMessage().contains("8–32 位数字"),
                "带字母的账号会在打款那一刻被银行退回，而那时钱已经走过一遍流程："
                        + letters.getMessage());
        assertThrows(ApiException.class, () -> profiles.updateProfile(U_L1,
                new AgentProfileService.UpdateReq(null, null, null, null, "1234567"), "1"));

        var tax = assertThrows(ApiException.class, () -> profiles.updateProfile(U_L1,
                new AgentProfileService.UpdateReq(null, null, "1234567890", null, null), "1"));
        assertTrue(tax.getMessage().contains("15 位"), tax.getMessage());
        // 18 位统一社会信用代码放行：只判长度不算校验位，各地规则不一，卡死会误伤真实主体
        assertEquals(List.of("taxNo"), profiles.updateProfile(U_L1,
                new AgentProfileService.UpdateReq(null, null, "91310000MA1FL0TE9X", null, null), "1")
                .get("updated"));

        // 被拒的那几次一个字节都没写进去
        assertNull(jdbc.queryForMap("SELECT contact_name FROM agent_profile WHERE id=?", A_L1)
                .get("contact_name"));
    }

    @Test @Order(43)
    @DisplayName("资料页对四种状态全放行：PENDING 必须能在审批前改掉填错的卡号")
    void updateProfileIsOpenToEveryStatus() {
        var pending = profiles.updateProfile(U_PENDING,
                new AgentProfileService.UpdateReq("丙的联系人", null, null, null, "6225880000003333"),
                "1.1.1.1");
        assertEquals(List.of("contactName", "settleBankAcct"), pending.get("updated"),
                "审核中的代理改不了资料，唯一的出路就成了'被驳回再重填'");

        jdbc.update("UPDATE agent_profile SET status='SUSPENDED' WHERE id=?", A_SOLO);
        try {
            // 停用的代理仍可能有已过冻结期的佣金要提现，收款人填不对钱就出不去
            assertEquals(List.of("settleBankAcct"), profiles.updateProfile(U_SOLO,
                    new AgentProfileService.UpdateReq(null, null, null, null, "6225880000004455"),
                    "1.1.1.1").get("updated"));
        } finally {
            jdbc.update("UPDATE agent_profile SET status='ACTIVE' WHERE id=?", A_SOLO);
        }

        var none = assertThrows(ApiException.class, () -> profiles.updateProfile(U_PLAIN,
                new AgentProfileService.UpdateReq("张三", null, null, null, null), "1.1.1.1"));
        assertEquals(404, none.status(), "非代理应当是 404（去申请），不是 403（你被拒了）");
        assertTrue(none.getMessage().contains("还不是代理商"), none.getMessage());
    }

    // ============================================================== 佣金详情

    @Test @Order(50)
    @DisplayName("佣金详情逐字段读快照：金额、基数、来源订单都来自成交那一刻")
    void commissionDetailReadsSnapshot() {
        long id = (Long) agents.commissions(U_L1, null, 1, 20).items().get(0).get("id");
        var d = commissionDetail.detail(U_L1, id);

        assertEquals(id, d.get("id"));
        assertEquals(51_000L, d.get("amountFen"), "佣金被展示端又算了一遍");
        assertEquals("PENDING", d.get("status"));
        assertEquals(bulkOrderId, d.get("orderId"));
        assertEquals("AGL2KEY0002", d.get("sourceAgentCode"));
        // 字段名按 TS 抄：calcModeSnapshot / calcBasisFen（列表接口出的是 calcMode / basisFen）
        assertEquals("FIXED_PER_CODE", d.get("calcModeSnapshot"));
        // 基数【不出网】：固定每码那一档，它对核对佣金没有用处，而它恰好等于下级的进货价
        // —— 进货价一家一议之后，出了它就等于把下级谈下来的价端给上级看。
        assertFalse(d.containsKey("calcBasisFen"),
                "固定每码结算居然把计算基数出网了 —— 那就是下级的专属进货价");
        // 规矩没变，只是换了观测点：快照仍在库里，佣金仍按它算。
        assertEquals(349_000L, jdbc.queryForObject(
                "SELECT calc_basis_fen FROM commission_record WHERE id=?", Long.class, id),
                "结佣快照没落库，历史佣金就无法复盘");
        assertNotNull(d.get("freezeUntil"));
        assertNotNull(d.get("createdAt"));
        assertNull(d.get("settledAt"), "还在冻结期就有了结算时间");
        assertNull(d.get("withdrawNo"));
        assertNull(d.get("revokeReason"));

        @SuppressWarnings("unchecked")
        var o = (Map<String, Object>) d.get("order");
        assertNotNull(o, "来源订单查不到，'这笔佣金是怎么来的'就回答不了");
        assertEquals(d.get("orderNo"), o.get("orderNo"));
        assertEquals("代理批量 · 10 个激活码", o.get("skuName"));
        assertEquals(10, o.get("codeQty"), "码数要取订单快照，不是 sku 现值");
        // 下级那张单的金额【不出网】：进货价一家一议之后，它就是下级谈下来的专属价。
        // 上级核对自己那笔佣金靠「每个码固定金额 × 码数」，不需要知道下级花了多少。
        assertFalse(o.containsKey("amountFen"), "下级的进货金额出网了");
        assertFalse(o.containsKey("paidAmountFen"), "下级的实付金额出网了");
        assertNotNull(o.get("paidAt"));
        assertEquals("AGL2KEY0002", o.get("buyerAgentCode"));

        // 下级没填主体名也没填联系人 → null，绝不回退到实名或手机号：
        // 上下级私下往来是分销体系里最难查的一类纠纷
        assertNull(d.get("sourceAgentName"));
        assertNull(o.get("buyerAgentName"));
        assertEquals("乙二级", jdbc.queryForObject("SELECT real_name FROM sys_user WHERE id=?",
                String.class, U_L2), "夹具不成立：乙本来就没有实名，这条断言证明不了什么");

        // 填了主体名就显示主体名（COALESCE 的第一顺位）
        jdbc.update("UPDATE agent_profile SET company_name='乙方教育' WHERE id=?", A_L2);
        try {
            assertEquals("乙方教育", commissionDetail.detail(U_L1, id).get("sourceAgentName"));
        } finally {
            jdbc.update("UPDATE agent_profile SET company_name=NULL WHERE id=?", A_L2);
        }
    }

    @Test @Order(51)
    @DisplayName("越权与不存在同一句 404；基数与现值不一致时两个数都给并说明")
    void commissionDetailGuards() {
        long id = (Long) agents.commissions(U_L1, null, 1, 20).items().get(0).get("id");

        var notMine = assertThrows(ApiException.class, () -> commissionDetail.detail(U_L2, id));
        var missing = assertThrows(ApiException.class, () -> commissionDetail.detail(U_L1, 999_999L));
        assertEquals(404, notMine.status());
        assertEquals(missing.getMessage(), notMine.getMessage(),
                "两种回答不同，就是一个佣金 id 的存在性探针：顺着能数出平台一共发了多少佣金");

        // 那一单后来实付变了（退款/改额）：金额一律读快照，另出一句说明，不按现行规则复算
        jdbc.update("UPDATE biz_order SET paid_amount_fen=300000 WHERE id=?", bulkOrderId);
        try {
            var d = commissionDetail.detail(U_L1, id);
            // 不变量没变：基数是结佣当时的快照，不跟订单现值走。
            // 观测点从接口挪到库里 —— 接口那一侧现在有意不出这个数（见上一条）。
            assertEquals(349_000L, jdbc.queryForObject(
                    "SELECT calc_basis_fen FROM commission_record WHERE id=?", Long.class, id),
                    "基数跟着现值走了，历史佣金无法复盘");
            assertEquals(51_000L, d.get("amountFen"),
                    "订单实付变了，佣金金额跟着变了 —— 佣金必须按结佣快照算");
            @SuppressWarnings("unchecked")
            var o = (Map<String, Object>) d.get("order");
            // 下级那张进货单的金额一个都不许出网：那是他谈下来的专属价
            assertFalse(o.containsKey("paidAmountFen"), "下级的实付金额出网了");
            assertFalse(o.containsKey("amountFen"), "下级的进货金额出网了");
        } finally {
            jdbc.update("UPDATE biz_order SET paid_amount_fen=349000 WHERE id=?", bulkOrderId);
        }
    }

    @Test @Order(52)
    @DisplayName("来源订单查不到时 order 返 null 并说明，绝不编一个空对象出来")
    void commissionDetailWithOrphanOrder() {
        // 库里 biz_order 与 commission_record 之间没有外键，孤儿行在理论上可以出现
        jdbc.update("INSERT INTO commission_record (beneficiary_agent_id,source_agent_id,order_id,"
                  + "order_no,rule_id,calc_mode_snapshot,calc_basis_fen,amount_fen,status,freeze_until)"
                  + " VALUES (?,NULL,999999,'WKORPHAN0001',1,'FIXED_PER_CODE',0,100,'PENDING',"
                  + "DATE_ADD(NOW(3), INTERVAL 15 DAY))", A_L1);
        long id = ((Number) jdbc.queryForMap(
                "SELECT id FROM commission_record WHERE order_no='WKORPHAN0001'").get("id")).longValue();

        var d = commissionDetail.detail(U_L1, id);
        assertNull(d.get("order"),
                "编了一个空对象出来：codeQty:0 / amountFen:0 会被读成'买了 0 个码花了 0 元'");
        assertTrue(notes(d).get("order").contains("核对"), notes(d).toString());
        // 学员直购归属那类佣金没有下级代理，两个字段都为 null，前端显示"下级代理"
        assertNull(d.get("sourceAgentCode"));
        assertNull(d.get("sourceAgentName"));

        jdbc.update("DELETE FROM commission_record WHERE order_no='WKORPHAN0001'");
    }

    // ============================================================== 批量导码

    @Test @Order(60)
    @DisplayName("批量导码：明文逐条出，逐条写与单条查看同名的审计，越权的一条都不出")
    void exportCodes() {
        int before = revealAuditCount();
        var body = codeExport.export(U_L2, new AgentCodeExportService.ExportReq("ISSUED", null, null),
                "8.8.8.8");

        assertEquals(9, body.get("total"), "默认只导未激活的码（10 个里已激活 1 个）");
        var rows = rows(body);
        assertEquals(9, rows.size());
        assertEquals(AgentCodeExportService.NOTICE, body.get("notice"));
        for (var r : rows) {
            String plain = (String) r.get("code");
            assertEquals(ActivationCodes.normalize(plain), plain, "导出的明文不是规范形式");
            assertTrue(((String) r.get("masked")).contains("****"));
            assertEquals("ISSUED", r.get("status"));
            assertNotNull(r.get("id"));
            assertNotNull(r.get("activateDeadline"));
            assertNotNull(r.get("orderNo"), "看不出这批码是哪一单进的，对账时对不上");
        }

        // 每一条都留痕，且 action 与单条查看同名：起个新名字的话，
        // "这串码被谁看过"要查两个 action，而第二个迟早有人忘了查
        assertEquals(before + 9, revealAuditCount(),
                "导出没有逐条写审计：一次泄漏 9 串可直接激活的码，而平台没有任何记录");
        var log = jdbc.queryForMap("SELECT client_ip, after_json FROM audit_log"
                + " WHERE action='CODE_REVEAL' ORDER BY id DESC LIMIT 1");
        assertEquals("8.8.8.8", log.get("client_ip"));
        assertTrue(String.valueOf(log.get("after_json")).contains("EXPORT"),
                "分不出这次是单条查看还是批量导出：" + log.get("after_json"));

        // 一级导不到二级的货：能取下级的码明文就能拿去自己卖或直接激活
        int guard = revealAuditCount();
        var stolen = codeExport.export(U_L1,
                new AgentCodeExportService.ExportReq("ISSUED", null,
                        rows.stream().map(r -> (Long) r.get("id")).toList()), "1.1.1.1");
        assertEquals(0, stolen.get("total"), "上级导出了下级的码明文");
        assertEquals(0, rows(stolen).size());
        assertEquals(guard, revealAuditCount(), "越权那次也写了审计，日志被污染");
    }

    @Test @Order(61)
    @DisplayName("勾选优先且忽略筛选、不属于我的 id 静默跳过、未知状态当场 400、0 命中是 200")
    void exportSelectionAndGuards() {
        var all = rows(codeExport.export(U_L2,
                new AgentCodeExportService.ExportReq("ISSUED", null, null), "1.1.1.1"));
        long mine = (Long) all.get(0).get("id");

        // 勾了 2 个，其中一个不是我的 → 静默跳过，不报"这个码不是你的"（那是存在性探针）
        var picked = codeExport.export(U_L2,
                new AgentCodeExportService.ExportReq("BOUND", "对不上的搜索词", List.of(mine, 999_999L)),
                "1.1.1.1");
        assertEquals(1, picked.get("total"),
                "勾选没有优先于筛选：会出现'我勾了 20 个只导出 3 个'而界面上无法解释");
        assertEquals(mine, rows(picked).get(0).get("id"));
        assertTrue(notes(picked).containsKey("ids"), "忽略了 status/keyword 却没说一声");

        var tooMany = assertThrows(ApiException.class, () -> codeExport.export(U_L2,
                new AgentCodeExportService.ExportReq(null, null,
                        java.util.stream.LongStream.range(0, AgentCodeExportService.MAX_IDS + 1)
                                .boxed().toList()), "1.1.1.1"));
        assertEquals(400, tooMany.status());
        assertTrue(tooMany.getMessage().contains(String.valueOf(AgentCodeExportService.MAX_IDS)),
                tooMany.getMessage());

        // 逐个 token 校验：集合里只要有一个不认识的值就整条拒，不是"认识几个算几个"
        var badStatus = assertThrows(ApiException.class, () -> codeExport.export(U_L2,
                new AgentCodeExportService.ExportReq("ISSUED,DONE", null, null), "1.1.1.1"));
        assertEquals(400, badStatus.status());
        assertTrue(badStatus.getMessage().contains("未知的激活码状态"), badStatus.getMessage());
        // 大小写不敏感是有意的（与 /codes 同一个 normalizeEnum），不是漏判
        assertEquals(9, codeExport.export(U_L2,
                new AgentCodeExportService.ExportReq("issued", null, null), "1.1.1.1").get("total"));

        // 搜索：掩码与订单号上模糊匹配
        String masked = (String) all.get(0).get("masked");
        var hit = codeExport.export(U_L2,
                new AgentCodeExportService.ExportReq("ISSUED", masked.substring(0, 4), null), "1.1.1.1");
        assertTrue((Integer) hit.get("total") >= 1);
        assertTrue(notes(hit).containsKey("keyword"), "没说清搜索的匹配范围");

        // 0 命中是 200 + 空列表，不是 404：404 会被 shell 的统一出口变成一张错误页
        var empty = codeExport.export(U_SOLO,
                new AgentCodeExportService.ExportReq("BOUND", null, null), "1.1.1.1");
        assertEquals(0, empty.get("total"));
        assertEquals(List.of(), empty.get("rows"));
        assertEquals(AgentCodeExportService.NOTICE, empty.get("notice"));

        // 经营接口：审核中的代理一律拒
        var pending = assertThrows(ApiException.class, () -> codeExport.export(U_PENDING,
                new AgentCodeExportService.ExportReq(null, null, null), "1.1.1.1"));
        assertEquals(403, pending.status());
        assertTrue(pending.getMessage().contains("审核中"), pending.getMessage());
    }

    @Test @Order(62)
    @DisplayName("命中超过单次上限当场 400，绝不静默截断，也不留下任何'看过明文'的痕迹")
    void exportRefusesToTruncate() {
        // 造一档 2001 个码的批量套餐，让丁真金白银走一遍进货
        jdbc.update("INSERT INTO sku (id,sku_code,name,buyer_type,course_id,code_qty,status)"
                  + " VALUES (?,'AGENT_BULK','代理批量 · 超大包','AGENT',1,?,'ON')", SKU_BULK, BULK_QTY);
        jdbc.update("INSERT INTO agent_price_tier (agent_level,sku_id,total_price_fen,"
                  + "effective_from,created_by) VALUES (1,?,100000,'2026-01-01 00:00:00',1)", SKU_BULK);

        var o = orderService.create(U_SOLO, SKU_BULK, "1.1.1.1");
        var paid = fulfill.confirmPaid(o.orderId(), "WECHAT", "WX_BULK_1", o.amountFen(),
                LocalDateTime.now(), null);
        assertEquals(BULK_QTY, paid.codesIssued(), "夹具没出满码，上限用例证明不了什么");

        // 丁名下此刻的未激活码总数（含 Order(4) 那个过期未扫的），命中数要如实报出来
        Long hit = jdbc.queryForObject("SELECT COUNT(*) FROM activation_code"
                + " WHERE owner_agent_id=? AND status='ISSUED'", Long.class, A_SOLO);
        assertTrue(hit != null && hit > AgentCodeExportService.MAX_EXPORT, "夹具没超过上限");

        int before = revealAuditCount();
        var e = assertThrows(ApiException.class, () -> codeExport.export(U_SOLO,
                new AgentCodeExportService.ExportReq("ISSUED", null, null), "1.1.1.1"));
        assertEquals(400, e.status());
        assertTrue(e.getMessage().contains(String.valueOf(AgentCodeExportService.MAX_EXPORT)),
                e.getMessage());
        assertTrue(e.getMessage().contains(String.valueOf(hit)),
                "没告诉他这次命中了多少个，他不知道该缩到多小：" + e.getMessage());
        assertEquals(before, revealAuditCount(),
                "拒绝的那次也写了审计：没人看到明文，却在日志里留下了'看过'");
    }

    // ============================================================== 工具

    @SuppressWarnings("unchecked")
    private static Map<String, String> notes(Map<String, Object> body) {
        return (Map<String, String>) body.get("notes");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("rows");
    }

    private static long orderCount(long agentId) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM biz_order WHERE buyer_agent_id=?",
                Long.class, agentId);
        return n == null ? 0 : n;
    }

    private static int auditCount(String action) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action=?",
                Integer.class, action);
        return n == null ? 0 : n;
    }

    /** 单条查看与批量导出必须落在同一个 action 上，所以数的就是这一个。 */
    private static int revealAuditCount() {
        return auditCount("CODE_REVEAL");
    }

    private static long insertWithdraw(String no, long agentId, long amountFen) {
        jdbc.update("INSERT INTO withdraw_order (withdraw_no,agent_id,amount_fen,channel,"
                  + "payee_name,payee_account) VALUES (?,?,?,'BANK_TRANSFER','甲一级','6225880000001111')",
                no, agentId, amountFen);
        return ((Number) jdbc.queryForMap(
                "SELECT id FROM withdraw_order WHERE withdraw_no=?", no).get("id")).longValue();
    }

    /** 触发器的 SIGNAL 藏在异常链里，逐层找出原文再断言 —— 只断言"抛了异常"钉不住原因。 */
    private static String rootMessage(Throwable e) {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) sb.append(t.getMessage()).append(" | ");
        return sb.toString();
    }

    // ================================================== 放弃一张未付款的进货单

    /**
     * ★ 这条路是 2026-08-19 那轮改动<b>补回来的</b>。
     *
     * <p>在那之前，代理放弃进货单靠的是<b>学员端</b>那颗「取消订单」按钮 ——
     * 因为学员端的「我的订单」当时会把他自己的进货单一并列出来（那正是被报的问题）。
     * 收紧之后进货单不再出现在那一页，按钮跟着没了，而库里<b>没有任何定时任务</b>
     * 会替他关掉这张单（全仓唯一的 @Scheduled 是佣金结算）——
     * 它会永久停在进货记录顶部显示「待支付」。
     */
    @Test @Order(70)
    @DisplayName("★ 代理能取消自己未付款的进货单；别人的、已付款的都不行")
    void agentCanCancelUnpaidPurchase() {
        var created = shop.createOrder(U_L1, SKU_TEN, "1.1.1.1");
        String orderNo = String.valueOf(created.get("orderNo"));
        assertEquals("CREATED", jdbc.queryForObject(
                "SELECT status FROM biz_order WHERE order_no=?", String.class, orderNo));

        // 别人的单：不存在与不是你的收敛到同一个 404，不构成单号枚举探针
        var stolen = assertThrows(ApiException.class, () -> shop.cancelOrder(U_L2, orderNo));
        var absent = assertThrows(ApiException.class,
                () -> shop.cancelOrder(U_L2, "WK_NO_SUCH_ORDER_1"));
        assertEquals(404, stolen.status());
        assertEquals(absent.status(), stolen.status());
        assertEquals(absent.getMessage(), stolen.getMessage());
        assertEquals("CREATED", jdbc.queryForObject(
                "SELECT status FROM biz_order WHERE order_no=?", String.class, orderNo),
                "越权取消被拒了，但单子已经被关掉了");

        // 本人：能取消
        shop.cancelOrder(U_L1, orderNo);
        assertEquals("CLOSED", jdbc.queryForObject(
                "SELECT status FROM biz_order WHERE order_no=?", String.class, orderNo));

        // 重复取消：如实回 409，不装作成功
        var again = assertThrows(ApiException.class, () -> shop.cancelOrder(U_L1, orderNo));
        assertEquals(409, again.status());
        assertTrue(again.getMessage().contains("取消过"), again.getMessage());
    }

    @Test @Order(71)
    @DisplayName("已付款的进货单取消不掉，且状态一动没动")
    void paidPurchaseCannotBeCancelled() {
        var created = shop.createOrder(U_L1, SKU_TEN, "1.1.1.1");
        String orderNo = String.valueOf(created.get("orderNo"));
        jdbc.update("UPDATE biz_order SET status='PAID', paid_at=NOW(3), paid_amount_fen=amount_fen"
                  + " WHERE order_no=?", orderNo);

        var e = assertThrows(ApiException.class, () -> shop.cancelOrder(U_L1, orderNo));
        assertEquals(409, e.status());
        assertTrue(e.getMessage().contains("付过款"), e.getMessage());
        // 「先关单再报错」的实现同样会抛 409，所以必须连状态一起断言
        assertEquals("PAID", jdbc.queryForObject(
                "SELECT status FROM biz_order WHERE order_no=?", String.class, orderNo),
                "已付款的单被取消了 —— 钱收了、单关了、码再也发不出去");
    }
}