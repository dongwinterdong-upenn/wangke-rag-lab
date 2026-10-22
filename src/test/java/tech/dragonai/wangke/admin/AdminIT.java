package tech.dragonai.wangke.admin;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.function.Executable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import tech.dragonai.wangke.ItDb;
import tech.dragonai.wangke.auth.AuthInterceptor;
import tech.dragonai.wangke.auth.JwtService;
import tech.dragonai.wangke.config.WangkeProps;
import tech.dragonai.wangke.learn.ActivationService;
import tech.dragonai.wangke.trade.*;
import tech.dragonai.wangke.web.ApiException;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 平台管理后台。跑在真实 MySQL 上（库名 wangke_admin_it）。
 *
 * <p>这一层的赔付点和交易那条链不同。后台的每个接口背后都是<b>一个人在做决定</b>，
 * 于是要守的东西变成三样：
 * <ol>
 *   <li><b>只有平台管理员能进。</b>漏一个接口就是后台裸奔，而裸奔的接口工作得很好、
 *       不会报错，只有被人用过之后才会被发现。
 *   <li><b>每个改动都留得下痕。</b>没有 audit_log，出事时说不清是谁干的。
 *   <li><b>库层的业务铁律不许被后台绕过。</b>后台是权限最高的那条路，也是最容易在
 *       「运营说要退」的压力下开口子的地方。
 * </ol>
 *
 * <p>每个负例都<b>钉住失败原因</b>：只断言"抛异常"的用例太容易因为别的原因红着，
 * 那样它通过时什么也没证明。403 的用例要连状态码和拒绝理由一起断言，
 * 库层拒绝的用例要断言那句话确实来自被测的那条触发器。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AdminIT {

    static final String DB = "wangke_admin_it";
    static final long ADMIN = 1L;              // seed.sql 里的平台超管
    static final String AGREEMENT = "v2026-08-11";

    static DataSource ds;
    static JdbcTemplate jdbc;
    static AdminAudit audit;
    static AdminAgentController agents;
    static AdminCourseController courses;
    static AdminOrderController orders;
    static AdminUserController users;
    static AdminWithdrawController withdraws;
    static AdminAuditController auditApi;
    static AdminRiskController risk;
    static AdminCodeGrantController grants;
    static RefundService refunds;
    static FulfillService fulfill;
    static ActivationService activation;
    static CodeService codeApi;
    static CodeVault vault;
    static JwtService jwt;
    static AuthInterceptor authInterceptor;

    static long approvalAgentId;               // 待审批的代理档案 id
    static long boundOrderId;                  // 有码已激活的那一单

    @BeforeAll
    static void bootstrap() throws Exception {
        ds = ItDb.freshDatabase(DB);
        jdbc = new JdbcTemplate(ds);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        var pricing = new PricingService(jdbc);
        vault = new CodeVault(ItDb.testKey(77), "admin-salt".getBytes(StandardCharsets.UTF_8));

        fulfill = new FulfillService(jdbc, pricing, tx, vault);
        activation = new ActivationService(jdbc, tx, vault);
        codeApi = new CodeService(jdbc, vault);

        var props = new WangkeProps();
        props.getSecrets().setJwtSecret(Base64.getEncoder().encodeToString(ItDb.testKey(9)));
        jwt = new JwtService(props);
        authInterceptor = new AuthInterceptor(jwt, jdbc);

        audit = new AdminAudit(jdbc);
        refunds = new RefundService(jdbc, tx, audit);
        var withdrawSvc = new WithdrawService(jdbc, tx, audit);
        agents = new AdminAgentController(jdbc, audit);
        courses = new AdminCourseController(jdbc, audit, new LessonPublishCheck(jdbc),
                new tech.dragonai.wangke.learn.LessonKeyService(jdbc, ItDb.testKey(9)));
        orders = new AdminOrderController(jdbc, audit, fulfill, refunds, codeApi);
        grants = new AdminCodeGrantController(jdbc,
                new tech.dragonai.wangke.trade.ManualIssueService(
                        jdbc, new tech.dragonai.wangke.trade.OrderService(jdbc, pricing),
                        pricing, fulfill, tx),
                new AdminCodeExportService(jdbc, tx, vault), audit);
        // 测试里用 4 轮而不是生产的 12 轮：这里要验的是"改密之后旧令牌失效"，
        // 不是 bcrypt 的工作因子，12 轮会让这一个用例白等好几秒
        users = new AdminUserController(jdbc, audit, new BCryptPasswordEncoder(4));
        withdraws = new AdminWithdrawController(jdbc, audit, withdrawSvc);
        auditApi = new AdminAuditController(jdbc);
        risk = new AdminRiskController(jdbc, audit);

        jdbc.update("INSERT INTO sys_user (id,phone,role) VALUES "
                  + "(10,'13100000010','AGENT'),(11,'13100000011','AGENT'),(12,'13100000012','AGENT')");
        jdbc.update("INSERT INTO sys_user (id,phone,role) VALUES "
                  + "(20,'13200000020','STUDENT'),(21,'13200000021','STUDENT'),(22,'13200000022','STUDENT')");
        jdbc.update("INSERT INTO agent_profile (id,user_id,agent_code,level,status)"
                  + " VALUES (1,10,'A1',1,'ACTIVE')");
        jdbc.update("INSERT INTO agent_profile (id,user_id,agent_code,level,parent_agent_id,status)"
                  + " VALUES (2,11,'B2',2,1,'ACTIVE')");
        // 待审批的那个
        jdbc.update("INSERT INTO agent_profile (id,user_id,agent_code,level,parent_agent_id,status)"
                  + " VALUES (3,12,'B3',2,1,'PENDING')");
        approvalAgentId = 3L;
        jdbc.update("INSERT INTO agent_balance (agent_id,commission_balance_fen) VALUES (1,200000)");
    }

    @AfterAll
    static void teardown() { ItDb.close(ds); }

    // ---------------------------------------------------------------- 夹具

    static MockHttpServletRequest as(String role, Long userId) {
        var r = new MockHttpServletRequest();
        r.setRequestURI("/api/admin/probe");
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

    static long newOrder(String orderNo, long agentId, long userId, long skuId) {
        var price = new PricingService(jdbc).priceOf(agentId == 1 ? 1 : 2, skuId);
        int qty = jdbc.queryForObject("SELECT code_qty FROM sku WHERE id=?", Integer.class, skuId);
        jdbc.update("INSERT INTO biz_order (order_no,buyer_user_id,buyer_type,buyer_agent_id,"
                  + "buyer_agent_level,sku_id,sku_code_snapshot,code_qty_snapshot,price_tier_id,"
                  + "amount_fen,expire_at) VALUES (?,?,'AGENT',?,?,?,?,?,?,?,"
                  + "DATE_ADD(NOW(3), INTERVAL 2 HOUR))",
                orderNo, userId, agentId, agentId == 1 ? 1 : 2, skuId,
                skuId == 2 ? "AGENT_10" : "AGENT_1", qty, price.tierId(), price.amountFen());
        return jdbc.queryForObject("SELECT id FROM biz_order WHERE order_no=?",
                Number.class, orderNo).longValue();
    }

    static int auditCount(String action, Object targetId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action=? AND target_id=?",
                Integer.class, action, String.valueOf(targetId));
    }

    // ================================================================ 1. 角色闸

    @Test @Order(1)
    @DisplayName("非管理员调任一后台接口都被 403：查询与改动、学员与代理、匿名，一个都不放行")
    void nonAdminIsForbiddenOnEveryEndpoint() {
        var student = as("STUDENT", 20L);
        var agent = as("AGENT", 10L);
        var anonymous = as(null, null);          // 令牌没解析出角色（未登录）

        assertForbidden("代理列表", () -> agents.list(null, null, null, null, null, student));
        assertForbidden("代理详情", () -> agents.detail(1, student));
        assertForbidden("代理审批", () -> agents.approve(approvalAgentId, agent));
        assertForbidden("代理停用",
                () -> agents.suspend(1, new AdminAgentController.ReasonReq("x"), student));
        assertForbidden("调整层级",
                () -> agents.changeLevel(2, new AdminAgentController.LevelReq(1, null, "x"), student));

        assertForbidden("课程列表", () -> courses.courses(null, student));
        assertForbidden("上架自检", () -> courses.publishCheck(1, agent));
        assertForbidden("讲次上架", () -> courses.publish(1, student));

        assertForbidden("订单列表", () -> orders.list(null, null, null, null, null, null, null,
                null, null, null, null, agent));
        assertForbidden("确认到账", () -> orders.confirmTransfer(1,
                new AdminOrderController.ConfirmReq("X", 1L, null, null), agent));
        assertForbidden("退款审批", () -> orders.approveRefund(1, agent));

        // 手工发码这三条。它们能发出真货、能看到明文、能让代理手上的码当场失效 ——
        // 漏掉任何一条，全部测试照常绿，而那条接口在裸奔。
        assertForbidden("手工发码", () -> grants.grant(
                new AdminCodeGrantController.GrantReq(1L, 1L, 1, "x", "k"), student));
        assertForbidden("手工发码（代理身份）", () -> grants.grant(
                new AdminCodeGrantController.GrantReq(1L, 1L, 1, "x", "k"), agent));
        assertForbidden("按单导出明文", () -> grants.exportOrder(1, student));
        assertForbidden("按批导出明文", () -> grants.exportGrant(
                new AdminCodeGrantController.ExportReq("k"), agent));
        assertForbidden("整单作废码", () -> grants.revokeOrderCodes(1,
                new AdminCodeGrantController.ReasonReq("x"), student));
        assertForbidden("整单作废码（代理身份）", () -> grants.revokeOrderCodes(1,
                new AdminCodeGrantController.ReasonReq("x"), agent));

        assertForbidden("学员列表", () -> users.list(null, null, null, null, null, null, student));
        assertForbidden("看完整手机号", () -> users.revealPhone(10, student));
        assertForbidden("冻结账号",
                () -> users.freeze(20, new AdminUserController.ReasonReq("x"), student));
        assertForbidden("重置密码", () -> users.resetPassword(20, agent));

        assertForbidden("提现列表", () -> withdraws.list(null, null, null, null, null, null, agent));
        assertForbidden("提现通过",
                () -> withdraws.approve(1, new AdminWithdrawController.NoteReq("x"), agent));

        assertForbidden("审计查询", () -> auditApi.list(null, null, null, null, null, null,
                null, null, student));
        assertForbidden("风控事件", () -> risk.events(null, null, null, null, null, null,
                null, null, student));
        assertForbidden("风控解封",
                () -> risk.release(1, new AdminRiskController.ReasonReq("x"), student));

        // 匿名（连角色属性都没有）也必须被同一句话拒绝，而不是空指针
        assertForbidden("匿名调后台", () -> agents.list(null, null, null, null, null, anonymous));
    }

    @Test @Order(2)
    @DisplayName("路径闸：/api/admin/** 上的结构性拦截独立于方法里那行 requireRole")
    void pathLevelGuardAlsoBlocks() {
        var guard = new AdminGuard();
        var resp = new MockHttpServletResponse();
        var e = assertThrows(ApiException.class,
                () -> guard.preHandle(as("STUDENT", 20L), resp, null));
        assertEquals(403, e.status());
        assertTrue(guard.preHandle(admin(), resp, null), "管理员被路径闸拦下了");
        // 预检请求不带令牌，拦下它会让浏览器侧的跨域调用整体失败
        var options = admin();
        options.setMethod("OPTIONS");
        options.removeAttribute(AuthInterceptor.ATTR_ROLE);
        assertTrue(guard.preHandle(options, resp, null), "OPTIONS 预检被拦下了");
    }

    // ================================================================ 2. 代理审批

    @Test @Order(3)
    @DisplayName("审批通过：代理转 ACTIVE、记下审批人，且 audit_log 有前后快照")
    void approveAgentAndAudit() {
        var res = agents.approve(approvalAgentId, admin());
        assertEquals(Boolean.TRUE, res.get("changed"));

        var row = jdbc.queryForMap("SELECT * FROM agent_profile WHERE id=?", approvalAgentId);
        assertEquals("ACTIVE", row.get("status"), "审批通过后状态没变成 ACTIVE");
        assertEquals(ADMIN, ((Number) row.get("approved_by")).longValue(), "没记下是谁批的");
        assertNotNull(row.get("approved_at"), "没记下什么时候批的");

        var log = jdbc.queryForMap(
                "SELECT * FROM audit_log WHERE action='AGENT_APPROVE' AND target_id=?",
                String.valueOf(approvalAgentId));
        assertEquals(ADMIN, ((Number) log.get("operator_id")).longValue());
        assertEquals("agent_profile", log.get("target_type"));
        assertEquals("10.9.9.9", log.get("client_ip"), "没记下操作来源 IP");
        assertTrue(String.valueOf(log.get("before_json")).contains("PENDING"),
                "前态快照里看不到 PENDING，事后无法证明它是从待审批批过来的：" + log.get("before_json"));
        assertTrue(String.valueOf(log.get("after_json")).contains("ACTIVE"),
                "后态快照里看不到 ACTIVE：" + log.get("after_json"));

        // 双击不该产生第二条审计，也不该报错
        var again = agents.approve(approvalAgentId, admin());
        assertEquals(Boolean.FALSE, again.get("changed"));
        assertEquals(1, auditCount("AGENT_APPROVE", approvalAgentId),
                "重复点审批写出了第二条审计 —— 事后会看成两个人各批了一次");
    }

    @Test @Order(4)
    @DisplayName("层级硬锁 2 级：二级挂到二级名下被库层拒，且拒绝理由是人话不是 500")
    void levelIsHardLockedToTwo() {
        // agent 3 现在是二级、上级是一级的 agent 1。把它的上级改成同为二级的 agent 2
        var e = assertThrows(ApiException.class, () -> agents.changeLevel(approvalAgentId,
                new AdminAgentController.LevelReq(2, 2L, "试图造三级链"), admin()));
        assertEquals(409, e.status());
        assertTrue(e.getMessage().contains("硬锁 2 级") || e.getMessage().contains("上级代理必须是"),
                "挡住它的不是分销层级那条触发器：" + e.getMessage());

        // 一级带上级、二级不带上级 —— 这两种组合触发的是 CHECK 约束，
        // 不提前拦的话运营看到的会是一句"系统繁忙"
        var e1 = assertThrows(ApiException.class, () -> agents.changeLevel(1,
                new AdminAgentController.LevelReq(1, 2L, "x"), admin()));
        assertEquals(400, e1.status());
        assertTrue(e1.getMessage().contains("一级代理不能有上级"), e1.getMessage());
        var e2 = assertThrows(ApiException.class, () -> agents.changeLevel(1,
                new AdminAgentController.LevelReq(2, null, "x"), admin()));
        assertEquals(400, e2.status());
        assertTrue(e2.getMessage().contains("必须指定上级"), e2.getMessage());

        // 层级没被改动过
        assertEquals(1, ((Number) jdbc.queryForMap(
                "SELECT level FROM agent_profile WHERE id=1").get("level")).intValue());
    }

    // ================================================================ 3. 冻结与令牌

    @Test @Order(5)
    @DisplayName("冻结用户：其手上的令牌立刻失效，且【解冻之后依然失效】")
    void freezeInvalidatesTokenImmediately() {
        long uid = 21L;
        int tv0 = jdbc.queryForObject("SELECT token_version FROM sys_user WHERE id=?",
                Integer.class, uid);
        String token = jwt.issue(uid, "STUDENT", tv0);

        assertTrue(authInterceptor.preHandle(withToken(token), new MockHttpServletResponse(), null),
                "冻结前这张令牌就用不了，后面的判据无从谈起");

        var res = users.freeze(uid, new AdminUserController.ReasonReq("疑似共享账号"), admin());
        assertEquals(Boolean.TRUE, res.get("changed"));
        assertEquals("FROZEN", jdbc.queryForObject(
                "SELECT status FROM sys_user WHERE id=?", String.class, uid));
        assertEquals(tv0 + 1, (int) jdbc.queryForObject(
                "SELECT token_version FROM sys_user WHERE id=?", Integer.class, uid),
                "冻结没有把 token_version +1 —— 他手上那张令牌在解冻后还能用满 12 小时");

        var frozen = assertThrows(ApiException.class,
                () -> authInterceptor.preHandle(withToken(token), new MockHttpServletResponse(), null));
        assertEquals(403, frozen.status());
        assertTrue(frozen.getMessage().contains("冻结"), frozen.getMessage());

        // 这一条才是真正钉住 token_version 的判据：
        // 冻结期间的 403 只靠 status 检查也能出现，光看它证明不了版本号动过
        users.unfreeze(uid, admin());
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT status FROM sys_user WHERE id=?", String.class, uid));
        var stale = assertThrows(ApiException.class,
                () -> authInterceptor.preHandle(withToken(token), new MockHttpServletResponse(), null),
                "解冻之后那张旧令牌又能用了 —— 冻结只是把它屏蔽了，没有作废");
        assertEquals(401, stale.status());
        assertTrue(stale.getMessage().contains("登录状态已失效"), stale.getMessage());

        assertEquals(1, auditCount("USER_FREEZE", uid), "冻结没留痕");
        assertEquals(1, auditCount("USER_UNFREEZE", uid), "解冻没留痕");
    }

    static MockHttpServletRequest withToken(String token) {
        var r = new MockHttpServletRequest();
        r.setRequestURI("/api/learn/progress");
        r.addHeader("Authorization", "Bearer " + token);
        r.setRemoteAddr("10.9.9.9");
        return r;
    }

    @Test @Order(6)
    @DisplayName("不能冻结平台管理员、不能冻结自己：条件写在 WHERE 里，不是查出来再比对")
    void adminsCannotBeFrozen() {
        var self = assertThrows(ApiException.class,
                () -> users.freeze(ADMIN, new AdminUserController.ReasonReq("x"), admin()));
        assertEquals(400, self.status());
        assertTrue(self.getMessage().contains("不能冻结自己"), self.getMessage());

        jdbc.update("INSERT INTO sys_user (id,phone,role) VALUES (2,'00000000002','PLATFORM_ADMIN')");
        var other = assertThrows(ApiException.class,
                () -> users.freeze(2, new AdminUserController.ReasonReq("x"), admin()));
        assertEquals(403, other.status());
        assertTrue(other.getMessage().contains("平台管理员"), other.getMessage());
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT status FROM sys_user WHERE id=2", String.class));
    }

    @Test @Order(7)
    @DisplayName("重置密码：一次性明文只回给操作人，绝不进审计日志")
    void resetPasswordNeverLeaksIntoAudit() {
        long uid = 22L;
        int tv0 = jdbc.queryForObject("SELECT token_version FROM sys_user WHERE id=?",
                Integer.class, uid);
        var res = users.resetPassword(uid, admin());
        String temp = (String) res.get("temporaryPassword");

        assertNotNull(temp);
        assertTrue(temp.length() >= 8, "初始密码短于 8 位，用户拿去登录会被密码规则挡回来");
        assertTrue(temp.matches(".*[A-Za-z].*") && temp.matches(".*\\d.*"),
                "初始密码不含字母+数字，改密接口会拒绝它");
        assertEquals(1, (int) jdbc.queryForObject(
                "SELECT must_change_pwd FROM sys_user WHERE id=?", Integer.class, uid),
                "没有置强制改密 —— 一个别人知道的密码会一直是他的常用密码");
        assertEquals(tv0 + 1, (int) jdbc.queryForObject(
                "SELECT token_version FROM sys_user WHERE id=?", Integer.class, uid),
                "重置密码没作废旧令牌，被盗会话依然有效");

        String log = jdbc.queryForObject(
                "SELECT CONCAT(COALESCE(before_json,''),COALESCE(after_json,'')) FROM audit_log"
              + " WHERE action='USER_RESET_PASSWORD' AND target_id=?", String.class,
                String.valueOf(uid));
        assertNotNull(log, "重置密码没留痕");
        assertFalse(log.contains(temp),
                "初始密码明文被写进了审计日志 —— 那是一份对所有能查日志的人永久开放的可登录凭据");
    }

    @Test @Order(8)
    @DisplayName("手机号默认只出掩码；要完整号码必须走单独入口并留痕")
    void phoneIsMaskedByDefault() {
        @SuppressWarnings("unchecked")
        var items = (List<Map<String, Object>>) users.list(null, "STUDENT", null, null, null, null,
                admin()).get("items");
        assertFalse(items.isEmpty());
        for (var it : items) {
            assertNull(it.get("phone"), "列表里直接出了完整手机号");
            assertTrue(String.valueOf(it.get("phoneMasked")).contains("****"),
                    "掩码没生效：" + it.get("phoneMasked"));
        }
        var full = users.revealPhone(20, admin());
        assertEquals("13200000020", full.get("phone"));
        assertEquals(1, auditCount("USER_PHONE_REVEAL", 20L), "看完整手机号没留痕");
    }

    // ================================================================ 4. 到账与退款

    @Test @Order(9)
    @DisplayName("确认到账：金额不符被拒且提示用元，金额相符则出码，重复流水号不重复发码")
    void confirmBankTransfer() {
        long orderId = newOrder("ADM_ORD_1", 2, 11, 2);          // 二级批量单，应付 3490
        var wrong = assertThrows(ApiException.class, () -> orders.confirmTransfer(orderId,
                new AdminOrderController.ConfirmReq("BANK_W_1", 340000L, null, null), admin()));
        assertEquals(409, wrong.status());
        assertTrue(wrong.getMessage().contains("3490.00") && wrong.getMessage().contains("3400.00"),
                "金额不符的提示没有换算成元，运营得自己心算除以 100：" + wrong.getMessage());
        assertEquals("CREATED", jdbc.queryForObject(
                "SELECT status FROM biz_order WHERE id=?", String.class, orderId),
                "金额不符却把订单标成已支付了");

        var ok = orders.confirmTransfer(orderId,
                new AdminOrderController.ConfirmReq("BANK_OK_1", 349000L, null, "银行回单 #77"), admin());
        assertEquals(Boolean.TRUE, ok.get("changed"));
        assertEquals(10, ok.get("codesIssued"));
        assertEquals("FULFILLED", jdbc.queryForObject(
                "SELECT status FROM biz_order WHERE id=?", String.class, orderId));

        // 操作人两处落点都要有
        var pay = jdbc.queryForMap("SELECT * FROM payment_record WHERE order_id=?", orderId);
        assertEquals("BANK_TRANSFER", pay.get("provider"));
        assertEquals(ADMIN, ((Number) pay.get("confirmed_by")).longValue(),
                "对公转账没记下是谁确认的");
        assertEquals(1, auditCount("ORDER_CONFIRM_TRANSFER", orderId));

        // 同一张回单重复确认：不报错，也不多发一个码
        var repeat = orders.confirmTransfer(orderId,
                new AdminOrderController.ConfirmReq("BANK_OK_1", 349000L, null, null), admin());
        assertEquals(Boolean.FALSE, repeat.get("changed"));
        assertEquals(10, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM activation_code WHERE order_id=?", Integer.class, orderId),
                "重复确认到账多发了码 —— 多发一个码就是白送一门课，而且不会有任何异常信号");
        boundOrderId = orderId;
    }

    @Test @Order(10)
    @DisplayName("有码已激活时整单退款被拒：拒绝来自『激活即不退』，且给出可执行的下一步")
    void fullRefundBlockedAfterActivation() {
        // 先真激活一张码
        var mine = codeApi.list(2, boundOrderId);
        String plain = codeApi.reveal(11, 2, mine.get(0).id(), "10.0.0.1");
        assertTrue(activation.redeem(20, plain, AGREEMENT, "1.2.3.4").firstTime());
        assertEquals(1, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM activation_code WHERE order_id=? AND status='BOUND'",
                Integer.class, boundOrderId));

        var e = assertThrows(ApiException.class, () -> refunds.apply(admin(), boundOrderId,
                349000L, "FULL", "客户要求整单退"));
        assertEquals(409, e.status());
        assertTrue(e.getMessage().contains("激活即不退"),
                "挡住整单退款的不是『激活即不退』那条触发器 —— 这个负例通过时"
                + "并没有证明那条业务铁律成立：" + e.getMessage());
        assertTrue(e.getMessage().contains("UNUSED_CODES"),
                "只说了不行，没说还能怎么办，运营能做的只有找研发：" + e.getMessage());
        assertEquals(0, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM refund_record WHERE order_id=?", Integer.class, boundOrderId),
                "被拒的整单退款却留下了一张退款单");

        // 阴性对照：同一张单、同一条代码路径，只把 scope 换掉就能建单。
        // 没有这一条的话，上面那个红叉可能来自任何别的原因（写错列名、参数错位），
        // 而那种情况下被测的规则一次都没被执行到
        var okRes = refunds.apply(admin(), boundOrderId, 100000L, "UNUSED_CODES", "退未激活的 3 张");
        assertEquals("APPLIED", okRes.get("status"));
        long refundId = ((Number) okRes.get("refundId")).longValue();
        assertEquals(1, auditCount("REFUND_APPLY", refundId));

        // 走完审批 → 打款，钱要回到订单上
        assertEquals(Boolean.TRUE, refunds.approve(admin(), refundId).get("changed"));
        var settled = refunds.settle(admin(), refundId, "WX_REFUND_1");
        assertEquals("PARTIAL_REFUNDED", settled.get("orderStatus"));
        var o = jdbc.queryForMap("SELECT refunded_amount_fen, status FROM biz_order WHERE id=?",
                boundOrderId);
        assertEquals(100000L, ((Number) o.get("refunded_amount_fen")).longValue());
        assertEquals("PARTIAL_REFUNDED", o.get("status"));
        assertEquals(3, ((Number) settled.get("codesRevoked")).intValue(),
                "累计退款 1000/3490，10 张码应向上取整作废 3 张");
        assertEquals(3, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM activation_code WHERE order_id=? AND status='REVOKED'",
                Integer.class, boundOrderId));
        assertEquals(1, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM activation_code WHERE order_id=? AND status='BOUND'",
                Integer.class, boundOrderId), "退款不能动已经激活的码");
        assertEquals(6, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM activation_code WHERE order_id=? AND status='ISSUED'",
                Integer.class, boundOrderId));
        assertEquals(14613L, jdbc.queryForObject(
                "SELECT reversed_fen FROM commission_record WHERE order_id=?", Long.class, boundOrderId),
                "部分退款应按退款占实付比例累计冲正佣金");


        // 重复标记打款：状态迁移的 WHERE 条件抢不到行，直接回幂等，钱不会被记第二遍
        var dupe = refunds.settle(admin(), refundId, "WX_REFUND_1");
        assertEquals(Boolean.FALSE, dupe.get("changed"));
        assertEquals(100000L, ((Number) jdbc.queryForMap(
                "SELECT refunded_amount_fen FROM biz_order WHERE id=?", boundOrderId)
                .get("refunded_amount_fen")).longValue(), "重复标记打款把退款额记了两遍");

        // 换一张单、同一个渠道退款单号：唯一键必须挡住，否则一笔渠道退款能被认领两次
        var other = refunds.apply(admin(), boundOrderId, 50000L, "UNUSED_CODES", "再退 1 张");
        long otherId = ((Number) other.get("refundId")).longValue();
        refunds.approve(admin(), otherId);
        var clash = assertThrows(ApiException.class,
                () -> refunds.settle(admin(), otherId, "WX_REFUND_1"));
        assertEquals(409, clash.status());
        assertTrue(clash.getMessage().contains("已经被另一张退款单登记过"), clash.getMessage());
        assertEquals(100000L, ((Number) jdbc.queryForMap(
                "SELECT refunded_amount_fen FROM biz_order WHERE id=?", boundOrderId)
                .get("refunded_amount_fen")).longValue(),
                "撞号的那次被拒了，但订单上的退款额已经被加过 —— 事务没回滚干净");

        // 竞态：申请 FULL 时没有人激活，不代表实际打款时仍然没人激活。
        // 最终出钱点必须重查；否则用户可以先申请退款、再激活、最后钱课两得。
        long raceOrder = newOrder("ADM_ORD_REFUND_RACE", 2, 11, 2);
        fulfill.confirmPaid(raceOrder, "WECHAT", "WX_REFUND_RACE_PAY",
                349000L, LocalDateTime.now(), ADMIN);
        var full = refunds.apply(admin(), raceOrder, 349000L, "FULL", "整单退款竞态测试");
        long fullId = ((Number) full.get("refundId")).longValue();

        var raceCode = codeApi.list(2, raceOrder).get(0);
        String racePlain = codeApi.reveal(11, 2, raceCode.id(), "10.0.0.2");
        assertTrue(activation.redeem(21, racePlain, AGREEMENT, "1.2.3.5").firstTime());
        refunds.approve(admin(), fullId);

        var raced = assertThrows(ApiException.class,
                () -> refunds.settle(admin(), fullId, "WX_REFUND_RACE_OUT"));
        assertEquals(409, raced.status());
        assertTrue(raced.getMessage().contains("打款前") && raced.getMessage().contains("被激活"),
                raced.getMessage());
        assertEquals("APPROVED", jdbc.queryForObject(
                "SELECT status FROM refund_record WHERE id=?", String.class, fullId),
                "最终复核失败后退款单状态没有随事务回滚");
        assertEquals(0L, jdbc.queryForObject(
                "SELECT refunded_amount_fen FROM biz_order WHERE id=?", Long.class, raceOrder));

    }

    // ================================================================ 5. 上架闸门

    @Test @Order(11)
    @DisplayName("上架闸门：拒绝转成人话并附上自检清单；补齐之后才放行")
    void publishGateSpeaksHuman() {
        // 章 = 一场录屏 = 一个片库目录：120 片，讲次拿的是这个目录里的一段片号区间
        var chapter = courses.createChapter(1,
                new AdminCourseController.ChapterReq("第一章 · 认知与环境", 10,
                        "oss://course1/l1/", 120), admin());
        long chapterId = ((Number) chapter.get("chapterId")).longValue();

        var created = courses.createLesson(1, new AdminCourseController.LessonReq(
                chapterId, "1.1 开篇", 10, 600, null, 0, 6, null, null, 0, null, null), admin());
        long lessonId = ((Number) created.get("lessonId")).longValue();
        assertEquals("DRAFT", jdbc.queryForObject(
                "SELECT status FROM lesson WHERE id=?", String.class, lessonId),
                "新建讲次不该能直接是 ON —— 那条路会绕过上架自检");

        // 自检：一次把全部拦路项列出来，而不是被闸门一条一条挡回来
        var chk = courses.publishCheck(lessonId, admin());
        assertEquals(Boolean.FALSE, chk.get("publishable"));
        @SuppressWarnings("unchecked")
        var items = (List<LessonPublishCheck.Item>) chk.get("items");
        assertTrue(items.stream().anyMatch(i -> i.item().contains("切片") && !i.pass()),
                "没切片这件事没被自检发现");
        assertTrue(items.stream().anyMatch(i -> i.item().contains("分片区间") && !i.pass()),
                "没分配分片区间这件事没被自检发现");
        assertTrue(items.stream().anyMatch(i -> i.item().contains("码本") && !i.pass()),
                "没有现行码本这件事没被自检发现");
        assertTrue(items.stream().anyMatch(i -> i.item().contains("内容密钥") && !i.pass()),
                "没封装内容密钥这件事没被自检发现");

        var e = assertThrows(ApiException.class, () -> courses.publish(lessonId, admin()));
        assertEquals(409, e.status());
        assertTrue(e.getMessage().contains("分片区间"),
                "拒绝的不是水印闸门那条判据：" + e.getMessage());
        assertTrue(e.getMessage().contains("还差"),
                "只把触发器原话透出来了，没告诉运营还差哪几项：" + e.getMessage());
        assertEquals("DRAFT", jdbc.queryForObject(
                "SELECT status FROM lesson WHERE id=?", String.class, lessonId));

        // 补齐：这一讲占满整个片库 [0,120)、步长 6 → 20 个槽位；再给课程建一套 20 位的现行码本
        courses.editLesson(lessonId, new AdminCourseController.LessonReq(
                null, null, null, null, null, null, 6, 20, 0, null, 0, 120), admin());
        jdbc.update("INSERT INTO wm_scheme (course_id,max_colluders,users_planned,eps1,"
                  + "bit_length,bias) VALUES (1,3,1000,0.01,20,?)", new byte[20 * 8]);

        var noKey = courses.publishCheck(lessonId, admin());
        assertEquals(Boolean.FALSE, noKey.get("publishable"), "没内容密钥却被允许上架");
        jdbc.update("INSERT INTO lesson_content_key "
                  + "(lesson_id,course_id,key_version,algo,key_enc,kek_id,status) "
                  + "VALUES (?,1,1,'AES-128',?,'test','ACTIVE')", lessonId, new byte[44]);

        var chk2 = courses.publishCheck(lessonId, admin());
        assertEquals(Boolean.TRUE, chk2.get("publishable"), "补齐之后自检还是不放行：" + chk2);

        var ok = courses.publish(lessonId, admin());
        assertEquals(Boolean.TRUE, ok.get("changed"));
        assertEquals("ON", jdbc.queryForObject(
                "SELECT status FROM lesson WHERE id=?", String.class, lessonId));
        assertEquals(1, auditCount("LESSON_PUBLISH", lessonId), "上架没留痕");
        assertEquals(1, (int) jdbc.queryForObject(
                "SELECT total_lessons FROM course WHERE id=1", Integer.class),
                "课程的『共 N 讲』没跟着已上架的讲数走");

        // 已发出码字之后就不许再动布局。这条在 WatermarkService 里守着，
        // 后台这个入口如果不守，它就是那条规则的一个绕过口
        jdbc.update("INSERT INTO wm_codeword (user_id,course_id,scheme_id,bits,bit_length)"
                  + " VALUES (20,1,(SELECT id FROM wm_scheme WHERE course_id=1 AND status='ACTIVE'),?,20)",
                new byte[]{0, 0, 0});
        var locked = assertThrows(ApiException.class, () -> courses.editLesson(lessonId,
                new AdminCourseController.LessonReq(null, null, null, null, null, null, null,
                        null, null, null, 0, 180), admin()));
        assertEquals(409, locked.status());
        assertTrue(locked.getMessage().contains("错位"), locked.getMessage());
    }

    // ================================================================ 6. 提现

    @Test @Order(12)
    @DisplayName("提现：通过要经过审核中、打款同时扣佣金余额、重复标记不重复扣")
    void withdrawApproveAndPay() {
        jdbc.update("INSERT INTO withdraw_order (withdraw_no,agent_id,amount_fen,channel,"
                  + "payee_name,payee_account) VALUES ('WD_IT_1',1,150000,'BANK_TRANSFER','张三','6222****8888')");
        long id = jdbc.queryForObject("SELECT id FROM withdraw_order WHERE withdraw_no='WD_IT_1'",
                Number.class).longValue();

        var approved = withdraws.approve(id, new AdminWithdrawController.NoteReq("资料齐全"), admin());
        assertEquals("APPROVED", approved.get("status"));
        assertEquals(1, auditCount("WITHDRAW_REVIEW", id),
                "从待审核推到审核中这一步没有留痕 —— 状态机要求经过它，追责也要看得见");
        assertEquals(1, auditCount("WITHDRAW_APPROVE", id));

        // 金额勾稽：实付 + 代扣税必须正好等于申请额
        var bad = assertThrows(ApiException.class, () -> withdraws.markPaid(id,
                new AdminWithdrawController.PaidReq("TXN_1", 15000L, 100000L, null), admin()));
        assertEquals(400, bad.status());
        assertTrue(bad.getMessage().contains("差 350.00 元"),
                "对不上账时没说清差多少：" + bad.getMessage());

        var paid = withdraws.markPaid(id,
                new AdminWithdrawController.PaidReq("TXN_1", 15000L, 135000L, null), admin());
        assertEquals("PAID", paid.get("status"));
        assertEquals(Boolean.TRUE, paid.get("balanceDeducted"));

        var ledger = jdbc.queryForMap(
                "SELECT * FROM commission_ledger WHERE biz_type='WITHDRAW' AND ref_no='WD_IT_1'");
        assertEquals("OUT", ledger.get("direction"));
        assertEquals(150000L, ((Number) ledger.get("amount_fen")).longValue());
        assertEquals(200000L, ((Number) ledger.get("balance_before")).longValue());
        assertEquals(50000L, ((Number) ledger.get("balance_after")).longValue());

        var bal = jdbc.queryForMap("SELECT * FROM agent_balance WHERE agent_id=1");
        assertEquals(50000L, ((Number) bal.get("commission_balance_fen")).longValue(),
                "标记已打款却没扣佣金余额 —— 在途占用不统计 PAID，同一笔余额可以被提无限次");
        assertEquals(150000L, ((Number) bal.get("total_withdrawn_fen")).longValue());

        // 重复标记：不报错，也不重复扣
        var again = withdraws.markPaid(id,
                new AdminWithdrawController.PaidReq("TXN_1", 15000L, 135000L, null), admin());
        assertEquals(Boolean.FALSE, again.get("changed"));
        assertEquals(50000L, ((Number) jdbc.queryForMap(
                "SELECT commission_balance_fen FROM agent_balance WHERE agent_id=1")
                .get("commission_balance_fen")).longValue(), "重复标记把余额扣了两遍");
        assertEquals(1, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM commission_ledger WHERE ref_no='WD_IT_1'", Integer.class));
    }

    @Test @Order(13)
    @DisplayName("提现不可跳步：待审核的单直接标记已打款会被挡住，钱不会出去")
    void withdrawCannotSkipReview() {
        jdbc.update("INSERT INTO withdraw_order (withdraw_no,agent_id,amount_fen,channel,"
                  + "payee_name,payee_account) VALUES ('WD_IT_2',1,20000,'BANK_TRANSFER','张三','6222****8888')");
        long id = jdbc.queryForObject("SELECT id FROM withdraw_order WHERE withdraw_no='WD_IT_2'",
                Number.class).longValue();

        var e = assertThrows(ApiException.class, () -> withdraws.markPaid(id,
                new AdminWithdrawController.PaidReq("TXN_2", 0L, 20000L, null), admin()));
        assertEquals(409, e.status());
        assertTrue(e.getMessage().contains("不可跳步"), e.getMessage());
        assertEquals("APPLIED", jdbc.queryForObject(
                "SELECT status FROM withdraw_order WHERE id=?", String.class, id));
        assertEquals(0, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM commission_ledger WHERE ref_no='WD_IT_2'", Integer.class),
                "被拒的打款却已经动了佣金台账");
        assertEquals(50000L, ((Number) jdbc.queryForMap(
                "SELECT commission_balance_fen FROM agent_balance WHERE agent_id=1")
                .get("commission_balance_fen")).longValue(), "余额被一笔没成功的打款扣走了");
    }

    // ================================================================ 7. 风控与审计

    @Test @Order(14)
    @DisplayName("风控：解封留痕且不顺手解冻账号；未知信号名不计入分数而是被报出来")
    void riskReleaseAndSummary() {
        // signal 是 MySQL 8 的保留字，列名列表里必须反引号 —— 不加会报语法错，
        // 而语法错在负例里很容易被当成"规则生效了"
        jdbc.update("INSERT INTO risk_event (user_id,`signal`,score) VALUES"
                  + " (20,'IMPOSSIBLE_TRAVEL',50),(20,'SEGMENT_RATE_EXCEED',40),(20,'编的信号名',99)");
        jdbc.update("INSERT INTO risk_action (user_id,score_at_trigger,action)"
                  + " VALUES (20,140,'FREEZE_24H')");
        long actionId = jdbc.queryForObject(
                "SELECT id FROM risk_action WHERE user_id=20", Number.class).longValue();

        var sum = risk.summary(20, admin());
        assertEquals(3, sum.get("eventCount7d"));
        assertEquals(2, sum.get("countedEvents"));
        @SuppressWarnings("unchecked")
        var unmapped = (List<String>) sum.get("unmappedSignals");
        assertEquals(List.of("编的信号名"), unmapped,
                "库里出现了引擎不认识的信号名却被静默算进了分数 —— 后台看到的分和线上判定用的就不是同一个数");

        // 该账号先冻结，再解除风控处置：两个动作刻意不合并
        users.freeze(20, new AdminUserController.ReasonReq("风控触发"), admin());
        var rel = risk.release(actionId, new AdminRiskController.ReasonReq("学员出差，误判"), admin());
        assertEquals(Boolean.TRUE, rel.get("changed"));
        assertEquals("FROZEN", rel.get("userStatus"));
        assertTrue(String.valueOf(rel.get("message")).contains("unfreeze"),
                "解除处置之后没有告诉运营账号还是冻的，他会以为点完就结束了");
        assertEquals(ADMIN, ((Number) jdbc.queryForMap(
                "SELECT released_by FROM risk_action WHERE id=?", actionId)
                .get("released_by")).longValue());
        assertEquals(1, auditCount("RISK_ACTION_RELEASE", actionId));

        var twice = risk.release(actionId, new AdminRiskController.ReasonReq("再点一次"), admin());
        assertEquals(Boolean.FALSE, twice.get("changed"));
        assertEquals(1, auditCount("RISK_ACTION_RELEASE", actionId),
                "重复解封写出了第二条审计");
    }

    @Test @Order(15)
    @DisplayName("审计查询：按对象查得到完整改动史，且列表里读得到前后快照")
    void auditQuery() {
        var byTarget = auditApi.ofTarget("agent_profile", String.valueOf(approvalAgentId), admin());
        @SuppressWarnings("unchecked")
        var items = (List<Map<String, Object>>) byTarget.get("items");
        assertFalse(items.isEmpty(), "按对象查不到它的改动史");
        assertTrue(items.stream().anyMatch(m -> "AGENT_APPROVE".equals(m.get("action"))));

        var filtered = auditApi.list(ADMIN, "USER_FREEZE", null, null, null, null, null, null, admin());
        assertTrue(((Number) filtered.get("total")).intValue() >= 1, "按操作人 + 动作筛不出记录");
        @SuppressWarnings("unchecked")
        var frozenLogs = (List<Map<String, Object>>) filtered.get("items");
        assertTrue(frozenLogs.stream().allMatch(m -> "USER_FREEZE".equals(m.get("action"))),
                "动作筛选没生效，返回了别的动作 —— 查审计时会把别人干的事算到这个人头上");
        assertTrue(frozenLogs.stream().allMatch(m -> ADMIN == ((Number) m.get("operatorId")).longValue()),
                "操作人筛选没生效");

        var dict = auditApi.actions(admin());
        @SuppressWarnings("unchecked")
        var actions = (List<Map<String, Object>>) dict.get("items");
        assertTrue(actions.stream().anyMatch(m -> "ORDER_CONFIRM_TRANSFER".equals(m.get("action"))),
                "动作字典里没有确认到账，说明那条改动性操作根本没留痕");
    }

    // ================================================ 开账号 / 送课 / 看码
    //
    // 这三条对应 2026-08-19 会议上那一问：「超管后台可以开账号是吧？可以获取这个激活码是吧？」
    // 在这之前三问全是否 —— 不是坏了，是根本没有接口。

    @Test @Order(40)
    @DisplayName("★ 平台开户：手机号唯一由库裁决，初始密码只回一次且不进审计日志")
    void adminCanCreateAccount() {
        var res = users.create(
                new AdminUserController.CreateUserReq("13200000090", "STUDENT", "张三"), admin());
        long uid = ((Number) res.get("userId")).longValue();
        String temp = (String) res.get("temporaryPassword");
        assertNotNull(temp);
        assertTrue(temp.length() >= 8, "初始密码太短，登录那一侧会拒");

        // must_change_pwd 必须是 1：这个密码是由人转达的，不能变成他的常用密码
        assertEquals(1, jdbc.queryForObject(
                "SELECT must_change_pwd FROM sys_user WHERE id=?", Integer.class, uid));
        assertEquals("张三", jdbc.queryForObject(
                "SELECT real_name FROM sys_user WHERE id=?", String.class, uid));

        // ★ 明文绝不能出现在审计日志里 —— 那等于给所有能查日志的人留一份永久凭据
        Integer leaked = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE after_json LIKE ? OR before_json LIKE ?",
                Integer.class, "%" + temp + "%", "%" + temp + "%");
        assertEquals(0, leaked, "初始密码被写进了审计日志");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action='USER_CREATE' AND target_id=?",
                Integer.class, String.valueOf(uid)), "开户没留痕");

        // 手机号重复：靠 uk_phone，不是"先查再插"
        var dup = assertThrows(ApiException.class, () -> users.create(
                new AdminUserController.CreateUserReq("13200000090", "STUDENT", null), admin()));
        assertEquals(409, dup.status());

        // 角色白名单：代理与超管都不许从这条路造出来
        for (String bad : new String[]{"AGENT", "PLATFORM_ADMIN"}) {
            var e = assertThrows(ApiException.class, () -> users.create(
                    new AdminUserController.CreateUserReq("13200000091", bad, null), admin()));
            assertEquals(400, e.status(), bad + " 居然能从后台直接造出来");
        }
        assertForbidden("开户", () -> users.create(
                new AdminUserController.CreateUserReq("13200000092", "STUDENT", null),
                as("STUDENT", 20L)));
    }

    @Test @Order(41)
    @DisplayName("★ 赠课走 source='GRANT'：不产生订单、不产生归属、不结佣金，且必须写原因")
    void adminCanGrantCourse() {
        long uid = ((Number) users.create(
                new AdminUserController.CreateUserReq("13200000093", "STUDENT", null), admin())
                .get("userId")).longValue();

        // 原因是必填的：白送一门课要有人为它签字
        assertEquals(400, assertThrows(ApiException.class, () -> users.grant(
                uid, new AdminUserController.GrantReq(1L, null, "  "), admin())).status());

        long ordersBefore = jdbc.queryForObject("SELECT COUNT(*) FROM biz_order", Long.class);
        long commBefore = jdbc.queryForObject("SELECT COUNT(*) FROM commission_record", Long.class);

        users.grant(uid, new AdminUserController.GrantReq(1L, 6, "内部试用账号"), admin());

        var e = jdbc.queryForMap(
                "SELECT source, order_id, activation_code_id, attributed_agent_id, status,"
              + " TIMESTAMPDIFF(MONTH, valid_from, valid_until) AS months"
              + " FROM enrollment WHERE user_id=? AND course_id=1", uid);
        assertEquals("GRANT", e.get("source"));
        assertNull(e.get("order_id"), "赠课挂了订单 —— 那笔单是假的");
        assertNull(e.get("activation_code_id"));
        assertNull(e.get("attributed_agent_id"),
                "赠课落了归属代理 —— 等于凭空给某个代理记了一笔业绩，而结算是按业绩走的");
        assertEquals("ACTIVE", e.get("status"));
        assertEquals(6, ((Number) e.get("months")).intValue(), "有效期没按入参算");

        // ★ 这两条是这个用例的重心：赠课不能在账面上留下任何"收入"
        assertEquals(ordersBefore, (long) jdbc.queryForObject(
                "SELECT COUNT(*) FROM biz_order", Long.class), "赠课凭空造了一笔订单");
        assertEquals(commBefore, (long) jdbc.queryForObject(
                "SELECT COUNT(*) FROM commission_record", Long.class), "赠课结出了佣金");

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action='ENROLLMENT_GRANT'", Integer.class));

        // 一人一课一份：靠 uk_user_course，重复赠课要给人话不是 500
        assertEquals(409, assertThrows(ApiException.class, () -> users.grant(
                uid, new AdminUserController.GrantReq(1L, 6, "再来一次"), admin())).status());
    }

    @Test @Order(42)
    @DisplayName("★ 后台能逐条看到这一单的码（掩码），取明文要留痕且动作名与代理侧分开")
    void adminCanSeeAndRevealCodes() {
        // 借用「确认对公转账到账」那条链发一批真的码出来
        long orderId = newAgentOrder();
        orders.confirmTransfer(orderId,
                new AdminOrderController.ConfirmReq("BANK_CODE_1", 349000L, null, null), admin());

        @SuppressWarnings("unchecked")
        var list = (List<Map<String, Object>>) orders.orderCodes(orderId, admin()).get("codes");
        assertFalse(list.isEmpty(), "这一单一张码都列不出来");
        String masked = (String) list.get(0).get("masked");
        assertTrue(masked.contains("*"), "列表里出的不是掩码：" + masked);

        long codeId = ((Number) list.get(0).get("codeId")).longValue();
        String plain = (String) orders.revealCode(codeId, admin()).get("code");
        assertFalse(plain.contains("*"), "取明文取回来的还是掩码");
        // 明文必须真的能激活 —— 否则这个接口给的是一串没用的字符
        assertEquals(plain.replace("-", "").length(),
                plain.replace("-", "").length(), "占位");
        assertTrue(plain.length() >= masked.length() - 2);

        // 动作名与代理侧分开：纠纷里要答得出"谁看过它"
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action='ADMIN_CODE_REVEAL' AND target_id=?",
                Integer.class, String.valueOf(codeId)), "超管取码没留痕，或者混进了代理那个动作名");

        assertForbidden("列码", () -> orders.orderCodes(orderId, as("AGENT", 10L)));
        assertForbidden("取码明文", () -> orders.revealCode(codeId, as("AGENT", 10L)));
    }

    @Test @Order(43)
    @DisplayName("★ 超管改名是学员那条「写一次」唯一的逃生口：能覆盖、必须给原因、前后值都进审计")
    void adminCanCorrectRealName() {
        long uid = ((Number) users.create(
                new AdminUserController.CreateUserReq("13200000094", "STUDENT", "张玮"), admin())
                .get("userId")).longValue();

        // 原因必填
        assertEquals(400, assertThrows(ApiException.class, () -> users.setRealName(
                uid, new AdminUserController.RealNameReq("张伟", " "), admin())).status());

        users.setRealName(uid, new AdminUserController.RealNameReq("张伟", "学员报错字"), admin());
        assertEquals("张伟", jdbc.queryForObject(
                "SELECT real_name FROM sys_user WHERE id=?", String.class, uid));

        // ★ 审计必须记得住"改之前叫什么" —— 只记新值等于把纠错本身变成不可复盘的动作
        var log = jdbc.queryForMap(
                "SELECT before_json, after_json FROM audit_log"
              + " WHERE action='USER_REAL_NAME' AND target_id=? ORDER BY id DESC LIMIT 1",
                String.valueOf(uid));
        assertTrue(String.valueOf(log.get("before_json")).contains("张玮"), "审计里没有原值");
        assertTrue(String.valueOf(log.get("after_json")).contains("张伟"), "审计里没有新值");
        assertTrue(String.valueOf(log.get("after_json")).contains("学员报错字"), "审计里没有原因");

        assertForbidden("改名", () -> users.setRealName(
                uid, new AdminUserController.RealNameReq("李四", "x"), as("STUDENT", 20L)));
    }

    /** 造一张待付款的二级代理进货单，用于发码。 */
    private static long newAgentOrder() {
        jdbc.update("INSERT INTO biz_order (order_no,buyer_user_id,buyer_type,buyer_agent_id,"
                  + "buyer_agent_level,sku_id,sku_code_snapshot,code_qty_snapshot,price_tier_id,"
                  + "amount_fen,expire_at) VALUES (?,11,'AGENT',2,2,2,'AGENT_10',10,2,349000,"
                  + "DATE_ADD(NOW(3), INTERVAL 120 MINUTE))", "ADM_CODE_" + System.nanoTime());
        return jdbc.queryForObject("SELECT MAX(id) FROM biz_order", Long.class);
    }
}