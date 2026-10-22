package tech.dragonai.wangke.agent;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionTemplate;
import tech.dragonai.wangke.ItDb;
import tech.dragonai.wangke.auth.AuthInterceptor;
import tech.dragonai.wangke.trade.CodeService;
import tech.dragonai.wangke.trade.CodeVault;
import tech.dragonai.wangke.trade.OrderService;
import tech.dragonai.wangke.trade.PricingService;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 代理端接口<b>出网 JSON 的键名</b>是否就是前端读的那些。
 *
 * <h3>为什么这个文件必须存在</h3>
 * 本轮交付时全套 326 个测试<b>全绿</b>，而代理端有四个页面是白屏的、提现按钮是永远点不动的。
 * 原因是判据全落在服务层：{@code AgentIT} 断的是 {@code Page.items()}，那是控制器
 * <b>出网之前</b>的形状；{@code AgentBizIT} 断的是各 Service 返回的 Map。
 * 控制器把 {@code items} 装进响应而前端读 {@code rows} 这件事，
 * 没有任何一条判据看得见。
 *
 * <p>而键名对不上的失败方式恰恰是<b>最难查的那一种</b>：JS 里读不存在的键得到 undefined，
 * 不抛错、不报警、HTTP 200、后端日志干干净净。
 * {@code d.rows.length} 在 render 期抛 TypeError，而全仓没有任何 ErrorBoundary
 * （componentDidCatch / getDerivedStateFromError 零命中），React 直接卸载整棵树 ——
 * 用户看到的是一片白，不是一句错误提示。
 *
 * <h3>所以这里的判据一律照抄前端的类型声明，不照抄后端的实现</h3>
 * 每条断言都注明它对应 web/apps/agent/src 里的哪一行。
 * 抄后端实现的判据是自证的：实现改了它跟着改，永远绿。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AgentJsonShapeIT {

    static final String DB = "wangke_agent_json_it";

    static DataSource ds;
    static JdbcTemplate jdbc;
    static AgentService agents;
    static AgentController api;

    /** 甲：一级代理，ACTIVE，结算账户填齐 */
    static final long U_L1 = 10, A_L1 = 1;

    @BeforeAll
    static void bootstrap() throws Exception {
        ds = ItDb.freshDatabase(DB);
        jdbc = new JdbcTemplate(ds);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        var vault = new CodeVault(ItDb.testKey(12), "json-salt".getBytes(StandardCharsets.UTF_8));
        var pricing = new PricingService(jdbc);

        agents = new AgentService(jdbc, tx, new CodeService(jdbc, vault));
        api = new AgentController(agents,
                new AgentDashboardService(jdbc, agents),
                new AgentShopService(jdbc, agents, pricing, new OrderService(jdbc, pricing)),
                new AgentPromoteService(agents, "https://x/study/", "https://x/"),
                new AgentProfileService(jdbc, tx, agents),
                new AgentCommissionDetailService(jdbc, agents),
                new AgentCodeExportService(jdbc, tx, agents, vault));

        jdbc.update("INSERT INTO sys_user (id,phone,role,real_name) VALUES"
                  + " (10,'13100000010','AGENT','甲一级')");
        jdbc.update("INSERT INTO agent_profile (id,user_id,agent_code,level,status,subject_type,"
                  + "settle_bank_name,settle_bank_acct) VALUES"
                  + " (1,10,'AGT0001',1,'ACTIVE','INDIVIDUAL','招商银行深圳分行','6225880000009999')");
        jdbc.update("INSERT INTO agent_balance (agent_id,commission_balance_fen,"
                  + "total_settled_fen,total_withdrawn_fen) VALUES (1,500000,800000,300000)");
    }

    @AfterAll
    static void teardown() { ItDb.close(ds); }

    static MockHttpServletRequest as(long userId) {
        var r = new MockHttpServletRequest();
        r.setAttribute(AuthInterceptor.ATTR_USER_ID, userId);
        r.setAttribute(AuthInterceptor.ATTR_ROLE, "AGENT");
        r.setRemoteAddr("10.0.0.1");
        return r;
    }

    // ============================================== 0. 出网体里不许有进货价（共用判据）

    /**
     * 键名里带钱味的那些词。命中即判红。
     *
     * <p>只钉 {@code amountFen} 这一个名字是钉不住的：把它改名叫 {@code totalPriceFen}、
     * {@code unitFen}、{@code priceYuan}、{@code costFen} 放回去，页面上照样看不见，
     * 而 F12 里照样看得见 —— 而"页面上不写"会让所有人以为它已经保密了，比明写更糟。
     */
    private static final Pattern MONEY_KEY =
            Pattern.compile("(?i)(amount|price|fen|yuan|money|cost|discount)");

    /**
     * 键名带钱味、但确实不是钱的那几个。<b>只许按整个键名精确豁免</b>，不许放宽正则 ——
     * 放宽一次（比如把 price 从表里删掉）就等于把 totalPriceFen 一起放行了。
     */
    private static final Set<String> MONEY_KEY_EXEMPT = Set.of(
            // 「这几档当前不对你开售」的 sku_code 清单，里面一个数字都没有
            "unpricedSkuCodes");

    /**
     * 代理这一侧的出网体里，<b>一个进货价都不许有</b>。
     *
     * <p>进货价由平台按代理逐家单独定，所以代理端不出现任何价目；
     * 金额只在超管后台与对公转账那一侧出现。这条规矩必须在后端兑现：
     * 只把界面上的 ¥ 抹掉、字段留在 JSON 里，代理按 F12 就看得见。
     *
     * <p>两道一起下，因为它们各自都能被绕过：
     * <ul>
     *   <li><b>键名</b>：递归扫每一层。挡住"换个名字放回去"。
     *   <li><b>值</b>：库里每一条价目的数字（分与元两种写法），在整个响应的文本里
     *       一次都不许出现。挡住"塞进一个看起来无害的键、或者写进 notes 那句说明里"。
     * </ul>
     */
    static void assertNoAgentSideMoney(JdbcTemplate jdbc, Object body, String where) {
        scanMoneyKeys(body, where);

        String text = String.valueOf(body);
        for (Long fen : jdbc.queryForList(
                "SELECT total_price_fen FROM agent_price_tier WHERE effective_to IS NULL",
                Long.class)) {
            assertFalse(text.contains(String.valueOf(fen)),
                    where + " 的响应里出现了现行价目的数字 " + fen + "（分）。"
                  + "键名可以随便叫，但这个数出现在代理这一侧就等于进货价出网了：" + text);
            assertFalse(text.contains(tech.dragonai.wangke.trade.OrderQueryService.yuan(fen)),
                    where + " 的响应里出现了现行价目 " + fen + " 分的元写法（"
                  + tech.dragonai.wangke.trade.OrderQueryService.yuan(fen) + "）：" + text);
        }
    }

    private static void scanMoneyKeys(Object node, String where) {
        if (node instanceof Map<?, ?> m) {
            for (var e : m.entrySet()) {
                String k = String.valueOf(e.getKey());
                if (!MONEY_KEY_EXEMPT.contains(k))
                    assertFalse(MONEY_KEY.matcher(k).find(),
                            where + " 的出网体里有一个带金额语义的键：" + k
                          + "。代理端已经按'这一页没有任何价目'重画，字段还在的话它只是从屏幕上"
                          + "挪进了 F12，而所有人都会以为它保密了。整层的键：" + m.keySet());
                scanMoneyKeys(e.getValue(), where);
            }
        } else if (node instanceof Collection<?> c) {
            for (Object o : c) scanMoneyKeys(o, where);
        }
    }

    // ================================================================ 1. 分页键名

    @Test @Order(1)
    @DisplayName("★ 分页响应必须出 rows —— 前端 Page<T> 读的是它，缺了是白屏不是空列表")
    void pagedResponsesCarryRows() {
        // web/apps/agent/src/api.ts:54  export type Page<T> = { total: number; rows: T[] }
        // 读它的四处：Dashboard.tsx:163 / Students.tsx:107 / Codes.tsx:224 / Commission.tsx:126
        //   —— 全是 d.rows.length，undefined.length 直接在 render 期抛。
        for (String what : List.of("codes", "students", "commissions")) {
            Map<String, Object> r = switch (what) {
                case "codes" -> api.codes(null, null, 1, 20, as(U_L1));
                case "students" -> api.students(1, 20, as(U_L1));
                default -> api.commissions(null, 1, 20, as(U_L1));
            };
            assertTrue(r.containsKey("rows"), "/" + what + " 的响应里没有 rows 键。"
                    + "前端 Page<T> 读的就是它，拿到 undefined 之后 d.rows.length 在 render 期抛 "
                    + "TypeError，而全仓没有 ErrorBoundary —— 整页白，控制台之外没有任何提示。"
                    + "现有的键：" + r.keySet());
            assertNotNull(r.get("rows"), "/" + what + " 的 rows 是 null");
            assertSame(r.get("items"), r.get("rows"),
                    "/" + what + " 的 rows 和 items 不是同一个 List 实例。"
                  + "两个键各装一份的话，将来只改其中一处的那次改动不会有任何测试变红。");
            assertTrue(r.get("total") instanceof Number, "/" + what + " 的 total 不是数字");
        }
    }

    // ================================================================ 1b. /me 三段式

    @Test @Order(6)
    @DisplayName("★ /me 必须出 user / agent 两个容器 —— 缺了是整站白屏，不是某一栏空着")
    void meCarriesNestedContainers() {
        // 前端 AgentMe（api.ts:85-95）是 { user, agent, application }，
        // App.tsx:81 在 render 期读 me.user.nickname。后端只出扁平键的话，
        // 那一行抛 TypeError，全仓没有 ErrorBoundary → 整棵树被卸载 → 白屏。
        //
        // 这一例本该和另外五例一起写：AgentJsonShapeIT 就是为「控制器出网键名
        // 对不上前端」这一类病建的闸，而 /me 恰恰是漏掉的那一个。
        Map<String, Object> r = api.me(as(U_L1));

        @SuppressWarnings("unchecked") Map<String, Object> user = (Map<String, Object>) r.get("user");
        assertNotNull(user, "/me 没有 user 容器。App.tsx:81 读 me.user.nickname，"
                + "拿到 undefined 就是 render 期 TypeError → 代理端整站白屏，"
                + "提现、佣金、激活码一页都打不开。现有的键：" + r.keySet());
        assertTrue(user.containsKey("nickname"), "user 缺 nickname：" + user.keySet());
        assertEquals("AGENT", user.get("role"), "user.role 不对 —— 顶栏按它显示身份");
        assertNotNull(user.get("phone"), "user 缺 phone");
        assertTrue(String.valueOf(user.get("phone")).contains("*"), "手机号没掩码");
        assertEquals("甲一级", user.get("realName"), "user.realName 不对");

        @SuppressWarnings("unchecked") Map<String, Object> ag = (Map<String, Object>) r.get("agent");
        assertNotNull(ag, "/me 没有 agent 容器。App.tsx:78 读 me.agent?.agentCode（可选链，不炸），"
                + "但 gate.tsx / Apply.tsx / Me.tsx 都靠它判「我是不是代理、审到哪一步」");
        assertEquals("AGT0001", ag.get("agentCode"), "agent.agentCode 不对");
        assertEquals("ACTIVE", ag.get("status"), "agent.status 不对");
        assertEquals(1, ((Number) ag.get("level")).intValue(), "agent.level 不对");
        assertTrue(String.valueOf(ag.get("settleBankAcct")).contains("*"),
                "嵌套体里的卡号没掩码 —— 扁平那一份掩了、这一份漏了，等于白掩");

        // 扁平键必须原样保留：加容器是增量，不是改形状
        assertEquals("AGT0001", r.get("agentCode"), "扁平键被容器挤掉了，老调用方会当场 undefined");
    }

    // ================================================================ 1c. 收款人闸同源

    @Test @Order(7)
    @DisplayName("★ /balance 的 payeeReady 必须与 apply 的收款人闸同判 —— 只看账户会点亮必被拒的按钮")
    void payeeReadyMatchesApplyGate() {
        // apply 的闸是两条：户名要有、账户要有（WithdrawService.apply）。
        // 前端原来只看 payeeAccount 一条，于是「账户填了、实名没填」的代理
        // 会看到一个亮着却必被拒的提现按钮，而界面上没有地方能补实名。
        // 这不是边角：sys_user.real_name 长期没有任何写入路径，而个人主体的
        // 打款户名取的正是它 —— 自助注册的代理默认全卡在这一条上。
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        var withdraws = new WithdrawService(jdbc, tx, agents);

        // ① 实名为空、账户齐全 → 界面必须判「不能提」，且理由点名实名
        jdbc.update("UPDATE sys_user SET real_name=NULL WHERE id=?", U_L1);
        Map<String, Object> b = api.balance(as(U_L1));
        assertEquals(Boolean.FALSE, b.get("payeeReady"),
                "实名是空的，apply 必拒，而 /balance 说可以提 —— 界面会点亮一个必被拒的按钮");
        assertTrue(String.valueOf(b.get("payeeMissing")).contains("实名"),
                "没点名缺的是实名，用户不知道该去补什么：" + b.get("payeeMissing"));
        var e = assertThrows(tech.dragonai.wangke.web.ApiException.class,
                () -> withdraws.apply(U_L1, 20000L, "BANK_TRANSFER", "10.0.0.1"),
                "实名为空却让提现申请过了");
        assertTrue(e.getMessage().contains("实名"), "拒绝理由不是实名：" + e.getMessage());

        // ② 配对阳性：实名补上之后两侧同时放行。少了这一条，①可能只是恒拒
        jdbc.update("UPDATE sys_user SET real_name='甲一级' WHERE id=?", U_L1);
        Map<String, Object> b2 = api.balance(as(U_L1));
        assertEquals(Boolean.TRUE, b2.get("payeeReady"), "实名补上了，界面那道闸没打开");
        assertNull(b2.get("payeeMissing"), "已经齐全了却还在说缺东西");
        assertDoesNotThrow(() -> withdraws.apply(U_L1, 20000L, "BANK_TRANSFER", "10.0.0.1"),
                "两侧判据不同源：界面说能提，apply 说不能");
    }

    @Test @Order(8)
    @DisplayName("★ 申请时的 realName 必须落库 —— 这是 sys_user.real_name 全仓唯一的写入路径")
    void applyWritesRealName() {
        jdbc.update("INSERT INTO sys_user (id,phone,role) VALUES (77,'13100000077','STUDENT')");
        agents.apply(77, 1, null, "钱七", null, "INDIVIDUAL", null, "钱七",
                "招商银行", "6225000000007777", "10.0.0.1");
        assertEquals("钱七",
                jdbc.queryForObject("SELECT real_name FROM sys_user WHERE id=77", String.class),
                "申请时填的实名没落库。个人主体的打款户名取的就是这一列，"
              + "不写的话这个代理永远过不了提现的实名闸，而界面上没有地方能补 —— 钱出不去。");

        // 已有实名不许被一次申请覆盖：改实名是风控动作，不该混在自助申请里
        jdbc.update("INSERT INTO sys_user (id,phone,role,real_name) VALUES (78,'13100000078','STUDENT','原名')");
        agents.apply(78, 1, null, "冒名", null, "INDIVIDUAL", null, "冒名",
                "招商银行", "6225000000007878", "10.0.0.1");
        assertEquals("原名",
                jdbc.queryForObject("SELECT real_name FROM sys_user WHERE id=78", String.class),
                "已有实名被一次自助申请覆盖掉了 —— 那等于开放了自助改实名");
    }

    // ================================================================ 2. 佣金 summary

    @Test @Order(2)
    @DisplayName("★ 佣金列表必须带 summary 五个键 —— 顶部那条流水线直接读它")
    void commissionListCarriesSummary() {
        // Commission.tsx:101 <Pipeline s={d.summary}/>，Pipeline 读 s.pendingFen 等五个；
        // api.ts:200-206 CommissionSummary 五个字段都是 number（不是可选）。
        Map<String, Object> r = api.commissions(null, 1, 20, as(U_L1));
        Object s = r.get("summary");
        assertNotNull(s, "佣金列表没有 summary。Commission.tsx:120 读 d.summary.settledFen，"
                + "拿到 undefined 就是 TypeError → 整页白，"
                + "而佣金详情抽屉只能从这张表的行点进去，所以 GET /commissions/{id} 也跟着一起用不了。");
        @SuppressWarnings("unchecked") Map<String, Object> sm = (Map<String, Object>) s;
        for (String k : List.of("pendingFen", "settledFen", "withdrawingFen",
                                "withdrawnFen", "revokedFen")) {
            assertTrue(sm.containsKey(k), "summary 缺 " + k + "：" + sm.keySet());
            assertTrue(sm.get(k) instanceof Number,
                    "summary." + k + " 不是数字（是 " + sm.get(k) + "）。"
                  + "null 会被 non_null 整个删掉，前端 Money fen={undefined} 渲染成「—」，"
                  + "在屏幕上和「这一档确实是 0」分不出来。");
        }
    }

    @Test @Order(9)
    @DisplayName("★ 有佣金但还没有余额行时，佣金页不许 500 —— 每个新代理都要经过这一段")
    void commissionSummarySurvivesMissingBalanceRow() {
        // agent_balance 那一行是首次结算才建的，而佣金在下级付款那一刻就有了。
        // 用 queryForObject 读它，会在这一段抛 EmptyResultDataAccessException → 整页 500：
        // 佣金页对没赚到钱的代理正常，对【刚赚到第一笔钱】的代理崩掉。
        // 这条判据只有在夹具【不】预先插 agent_balance 时才有意义。
        jdbc.update("INSERT INTO sys_user (id,phone,role,real_name) VALUES (88,'13100000088','AGENT','钱八')");
        jdbc.update("INSERT INTO agent_profile (id,user_id,agent_code,level,status,subject_type,"
                  + "settle_bank_name,settle_bank_acct) VALUES"
                  + " (8,88,'AGT0008',1,'ACTIVE','INDIVIDUAL','中国银行','6217000000008888')");
        assertEquals(0, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_balance WHERE agent_id=8", Integer.class),
                "夹具先决条件不成立：这个代理不该有余额行，否则这条用例什么也没测");

        Map<String, Object> r = assertDoesNotThrow(() -> api.commissions(null, 1, 20, as(88)),
                "代理有佣金资格但还没有余额行，佣金列表就崩了");
        @SuppressWarnings("unchecked") Map<String, Object> sm = (Map<String, Object>) r.get("summary");
        assertNotNull(sm, "没有 summary");
        assertEquals(0L, ((Number) sm.get("withdrawableFen")).longValue(),
                "没有余额行时可提金额应当是 0，而不是抛异常或给 null");
    }

    // ================================================================ 3. 余额键名

    @Test @Order(3)
    @DisplayName("★ /balance 必须给前端那三个键名 —— 缺了提现页红框常驻、门槛校验静默失效")
    void balanceCarriesFrontendKeyNames() {
        // api.ts:223-239 Balance：commissionBalanceFen / inflightFen / minWithdrawFen
        Map<String, Object> b = api.balance(as(U_L1));

        assertEquals(500000L, ((Number) b.get("commissionBalanceFen")).longValue(),
                "缺 commissionBalanceFen（或值不对）。Withdraw.tsx:84 算 "
              + "derived = commissionBalanceFen - inflightFen，undefined 相减得 NaN，"
              + "而 NaN !== withdrawableFen 恒真 —— 那个红色「对不上账」警告框会天天挂在页面上，"
              + "告诉代理他的钱算错了。");
        assertEquals(0L, ((Number) b.get("inflightFen")).longValue(), "缺 inflightFen");
        assertEquals(WithdrawService.MIN_WITHDRAW_FEN,
                ((Number) b.get("minWithdrawFen")).longValue(),
                "缺 minWithdrawFen。Withdraw.tsx:221 判 fen < minWithdrawFen，"
              + "和 undefined 比恒为 false → 单笔门槛校验静默失效。");

        // 别名不许把原名挤掉：可能还有别的调用方在读原名
        assertEquals(b.get("commissionBalanceFen"), b.get("availableFen"), "别名与原名不同值");
        assertEquals(b.get("inflightFen"), b.get("inflightWithdrawFen"), "别名与原名不同值");
        assertEquals(b.get("minWithdrawFen"), b.get("withdrawMinFen"), "别名与原名不同值");
    }

    @Test @Order(4)
    @DisplayName("★ /balance 必须给收款人 —— 缺了提现按钮对所有代理永远是灰的")
    void balanceCarriesPayee() {
        Map<String, Object> b = api.balance(as(U_L1));

        // Withdraw.tsx:217  const noAccount = !b.payeeAccount
        // Withdraw.tsx:228  canSubmit = fen !== null && !error && !noAccount
        assertNotNull(b.get("payeeAccount"), "缺 payeeAccount。前端用 !b.payeeAccount 判"
                + "「填没填结算账户」，后端不给这个键就恒为 true —— 一个结算账户明明填好了的代理，"
                + "提现按钮永远点不动，旁边还常驻一句「还没填结算账户」。"
                + "POST /api/agent/withdrawals 这个接口因此从界面上根本调不到。");
        assertEquals("甲一级", b.get("payeeName"),
                "个人主体的打款户名必须是实名（不允许代收）");
        assertEquals("招商银行深圳分行", b.get("payeeBankName"), "缺 payeeBankName");

        // 明文卡号不许出网：代理后台天天开着，投屏截图都是常态，
        // 而完整卡号在这一页没有任何用途 —— 它只在打款那一侧被读。
        String acct = (String) b.get("payeeAccount");
        assertNotEquals("6225880000009999", acct, "整串卡号原样出网了");
        assertTrue(acct.contains("*"), "卡号没有掩码：" + acct);
    }

    @Test @Order(5)
    @DisplayName("★ 界面那道「填没填结算账户」的闸，与 apply 真正的闸必须同源")
    void payeeGateAgreesWithApplyGate() {
        // 这两处历史上是两段各自查库的代码。各写一遍的失败方式是静默的：
        // 界面放行而服务端拒（用户点了没反应），或界面禁用而服务端本可放行（钱提不出来）。
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        var withdraws = new WithdrawService(jdbc, tx, agents);

        // 把结算账户清空 —— 界面那道闸应当关上，apply 也应当拒，且两者理由一致
        jdbc.update("UPDATE agent_profile SET settle_bank_acct=NULL WHERE id=?", A_L1);
        try {
            Map<String, Object> b = api.balance(as(U_L1));
            assertNull(b.get("payeeAccount"),
                    "结算账户已清空，/balance 却仍给出 payeeAccount —— 界面会放行一笔注定被拒的申请");

            var e = assertThrows(tech.dragonai.wangke.web.ApiException.class,
                    () -> withdraws.apply(U_L1, 20000L, "BANK_TRANSFER", "10.0.0.1"),
                    "结算账户是空的，提现申请居然过了");
            assertTrue(e.getMessage().contains("结算账户"),
                    "拒绝理由不是「没登记结算账户」，这个负例什么也没证明：" + e.getMessage());
        } finally {
            jdbc.update("UPDATE agent_profile SET settle_bank_acct=? WHERE id=?",
                    "6225880000009999", A_L1);
        }

        // 配对阳性：账户填回去之后，两侧都要恢复放行 —— 否则上面那条断言可能只是「恒拒」
        assertNotNull(api.balance(as(U_L1)).get("payeeAccount"), "账户填回去了，界面那道闸没打开");
        var applied = withdraws.apply(U_L1, 20000L, "BANK_TRANSFER", "10.0.0.1");
        assertEquals("甲一级", applied.payeeName(), "快照里的户名与 /balance 给的不是同一个");
    }

    // ================================================================ 4. 进货价不出网

    @Test @Order(10)
    @DisplayName("★★ 进货这条路的四个响应里一个价都没有，而单照样建得出来、金额照样在库里")
    void agentShopCarriesNoPurchasePrice() {
        // 需求方拍板：代理端保留下单，但页面上不出现任何价目 —— 进货价由平台按代理
        // 逐家单独定，摆一张统一价目表本身就是错的信息。
        // 这条判据落在【出网 JSON】而不是界面上：只改界面的话，代理按 F12 就看得见，
        // 而"页面上不写"会让所有人（包括下一个改这里的人）以为它已经保密了。

        // ---- ① 橱窗
        Map<String, Object> shop = api.skus(as(U_L1));
        assertEquals(Set.of("ok", "agentLevel", "total", "rows", "unpricedSkuCodes", "notes"),
                shop.keySet(), "/skus 顶层的键集合变了");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> skuRows = (List<Map<String, Object>>) shop.get("rows");
        assertFalse(skuRows.isEmpty(),
                "夹具不成立：橱窗一档都没有，下面每一条断言都会因为'没东西可看'而假绿");
        for (var r : skuRows)
            assertEquals(Set.of("skuId", "skuCode", "name", "codeQty", "courseTitle",
                                "activateMonths", "accessMonths", "status"), r.keySet(),
                    "橱窗行的键集合变了。web/apps/agent/src/api.ts 的 SkuRow 里没有价格字段，"
                  + "多出来的那个键前端不会读，只会出现在 F12 里");
        assertNoAgentSideMoney(jdbc, shop, "GET /api/agent/skus");

        // ---- ② 建单：响应里没有金额
        long skuId = ((Number) skuRows.get(0).get("skuId")).longValue();
        Map<String, Object> created = api.createOrder(
                new AgentController.CreateOrderReq(skuId), as(U_L1));
        assertEquals(Set.of("ok", "orderId", "orderNo", "codeQty", "skuName",
                            "expireAt", "expireInSeconds"), created.keySet(),
                "建单响应的键集合变了");
        assertNoAgentSideMoney(jdbc, created, "POST /api/agent/orders");
        String orderNo = (String) created.get("orderNo");
        assertNotNull(orderNo, "连单号都没有，下面查不了这张单");

        // ---- ③ 停发字段 ≠ 不收钱：金额照常算、照常落库
        //      这一条是这一轮的另一半。少了它，把 OrderService 的取价整个删掉
        //      （订单金额恒 0）也照样绿 —— 而那才是真正的事故。
        var row = jdbc.queryForMap(
                "SELECT amount_fen, price_tier_id, status, buyer_agent_id"
              + " FROM biz_order WHERE order_no=?", orderNo);
        long expect = new PricingService(jdbc).priceOf(1, skuId).amountFen();
        assertEquals(expect, ((Number) row.get("amount_fen")).longValue(),
                "库里的成交金额与 priceOf 给的不是同一个数 —— 代理看不到价，"
              + "这个数错了没有任何人会在界面上发现");
        assertTrue(((Number) row.get("amount_fen")).longValue() > 0, "订单金额是 0");
        assertNotNull(row.get("price_tier_id"), "没记取价来源，改价之后这单的金额无法解释");
        assertEquals("CREATED", row.get("status"));
        assertEquals(A_L1, ((Number) row.get("buyer_agent_id")).longValue());

        // ---- ④ 进货记录与单据详情
        Map<String, Object> list = api.orders(null, 1, 20, as(U_L1));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> orderRows = (List<Map<String, Object>>) list.get("rows");
        assertFalse(orderRows.isEmpty(), "刚建的单没出现在进货记录里，这一段什么也没测");
        for (var r : orderRows)
            assertEquals(Set.of("id", "orderNo", "skuCode", "skuName", "codeQty", "status",
                                "expireAt", "paidAt", "createdAt", "expireInSeconds",
                                "origin", "gift"), r.keySet(),
                    "进货记录行的键集合变了。amountFen / paidAmountFen / refundedAmountFen "
                  + "三个都是按进货价算的，退款金额同样能把进货价倒推出来");
        assertNoAgentSideMoney(jdbc, list, "GET /api/agent/orders");
        assertNoAgentSideMoney(jdbc, api.order(orderNo, as(U_L1)),
                "GET /api/agent/orders/{orderNo}");
    }

    @Test @Order(11)
    @DisplayName("★ 配对阳性：佣金与余额那一侧照常出金额 —— 这一轮拿掉的是进货价，不是代理的钱")
    void commissionSideKeepsItsMoney() {
        // 没有这一条，上面那条判据的最省事的"修法"就是把所有带钱的键一起删光：
        // 代理从此看不到自己挣了多少、能提多少，也就没法对账、没法判断该不该提现，
        // 而所有的形状判据仍然全绿。
        Map<String, Object> b = api.balance(as(U_L1));
        assertTrue(((Number) b.get("commissionBalanceFen")).longValue() > 0,
                "余额里的佣金没了。那是代理自己挣的钱，不是进货价");
        assertNotNull(b.get("withdrawableFen"), "可提金额没了，提现页会拿 undefined 去比门槛");

        Map<String, Object> c = api.commissions(null, 1, 20, as(U_L1));
        @SuppressWarnings("unchecked") Map<String, Object> sm =
                (Map<String, Object>) c.get("summary");
        for (String k : List.of("pendingFen", "settledFen", "withdrawingFen",
                                "withdrawnFen", "revokedFen"))
            assertTrue(sm.get(k) instanceof Number,
                    "佣金 summary 的 " + k + " 被一起删掉了：" + sm.keySet());
    }
}
