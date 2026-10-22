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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 代理商后台。跑在真实 MySQL 上，因为这一块一半的规则在触发器里。
 *
 * <p>这里的赔付点是<b>越权</b>与<b>钱出去</b>两件事，所以每个负例都钉住失败原因：
 * 只断言"抛异常"的用例，很容易是因为别的原因红的（比如根本没建到数据），
 * 那样它通过时什么也没证明。
 *
 * <p>另有一条纪律贯穿全文件：<b>凡是应用层拦下来的规则，都要再验一次库里那道闸</b>。
 * 应用层的检查随时可能在重构里被绕过（新写一条 SQL、后台脚本、手工 UPDATE），
 * 只测应用层等于测了一层随时会消失的防线。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AgentIT {

    static final String DB = "wangke_agent_it";

    static DataSource ds;
    static JdbcTemplate jdbc;
    static AgentService agents;
    static WithdrawService withdraws;
    static CodeService codeService;
    static OrderService orders;
    static FulfillService fulfill;
    static ActivationService activation;

    /** 一级 甲（agent 1 / user 10）、二级 乙（agent 2 / user 11） */
    static final long A_L1 = 1, A_L2 = 2, A_PENDING = 3, A_SOLO = 4;
    static final long U_L1 = 10, U_L2 = 11, U_PENDING = 12, U_SOLO = 13;

    static long orderId;

    @BeforeAll
    static void bootstrap() throws Exception {
        ds = ItDb.freshDatabase(DB);
        jdbc = new JdbcTemplate(ds);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        var vault = new CodeVault(ItDb.testKey(7), "agent-salt".getBytes(StandardCharsets.UTF_8));
        var pricing = new PricingService(jdbc);

        codeService = new CodeService(jdbc, vault);
        agents = new AgentService(jdbc, tx, codeService);
        withdraws = new WithdrawService(jdbc, tx, agents);
        orders = new OrderService(jdbc, pricing);
        fulfill = new FulfillService(jdbc, pricing, tx, vault);
        activation = new ActivationService(jdbc, tx, vault);

        jdbc.update("INSERT INTO sys_user (id,phone,role,real_name) VALUES"
                  + " (10,'13100000010','AGENT','甲一级'),(11,'13100000011','AGENT','乙二级'),"
                  + " (12,'13100000012','AGENT','丙待审'),(13,'13100000013','AGENT','丁独立')");
        jdbc.update("INSERT INTO sys_user (id,phone,role) VALUES (20,'13200000020','STUDENT')");
        // 学员 21 是点甲的推广链接注册的：没买过任何东西，但算甲名下的人
        jdbc.update("INSERT INTO sys_user (id,phone,role,attributed_agent_id,attributed_at,"
                  + "attribution_source) VALUES (21,'13200000021','STUDENT',1,NOW(3),'REF_LINK')");
        // 申请用的空白学员号
        for (int i = 30; i <= 36; i++)
            jdbc.update("INSERT INTO sys_user (id,phone,role) VALUES (?,?,'STUDENT')",
                    i, "1330000" + (1000 + i));
        jdbc.update("INSERT INTO sys_user (id,phone,role,status) VALUES (40,'13400000040','MENTOR','ACTIVE')");
        // 37 是个「角色已经是 AGENT、但还没有档案」的号：验层级红线那条用例要用它，
        // 否则触发器会先在「档案只能挂在 AGENT 角色上」那一条上拒绝，
        // 用例照样绿，但绿的是另一条规则 —— 负例钉错了原因等于没测。
        jdbc.update("INSERT INTO sys_user (id,phone,role) VALUES (37,'13300000037','AGENT')");

        jdbc.update("INSERT INTO agent_profile (id,user_id,agent_code,level,status,subject_type,"
                  + "settle_bank_name,settle_bank_acct) "
                  + "VALUES (1,10,'AGL1KEY0001',1,'ACTIVE','INDIVIDUAL','招商银行','6225880000001111')");
        jdbc.update("INSERT INTO agent_profile (id,user_id,agent_code,level,parent_agent_id,status,"
                  + "subject_type,settle_bank_acct) "
                  + "VALUES (2,11,'AGL2KEY0002',2,1,'ACTIVE','INDIVIDUAL','6225880000002222')");
        jdbc.update("INSERT INTO agent_profile (id,user_id,agent_code,level,parent_agent_id,status) "
                  + "VALUES (3,12,'AGP3KEY0003',2,1,'PENDING')");
        jdbc.update("INSERT INTO agent_profile (id,user_id,agent_code,level,status,subject_type,"
                  + "settle_bank_acct) VALUES (4,13,'AGL4KEY0004',1,'ACTIVE','INDIVIDUAL','6225880000004444')");
        // 一个【一级但还在审核中】的代理。要验「推荐人尚未生效」这一条就必须是一级，
        // 拿二级的 PENDING 档案去验，先撞上的是层级那一条 —— 用例照样红/绿，
        // 但它证明的是另一条规则（第一版就是这么错的，failsafe 当场把它抓出来了）。
        jdbc.update("UPDATE sys_user SET role='AGENT' WHERE id=36");
        jdbc.update("INSERT INTO agent_profile (id,user_id,agent_code,level,status) "
                  + "VALUES (5,36,'AGP5KEY0005',1,'PENDING')");

        // 二级进 10 个码：真金白银走一遍下单→支付→出码→结佣，
        // 不手写 INSERT 造数据 —— 手写的数据永远和生产路径写出来的不完全一样，
        // 而统计口径对不对，考的正是那点不一样。
        var o = orders.create(U_L2, 2, "1.2.3.4");
        orderId = o.orderId();
        var paid = fulfill.confirmPaid(orderId, "WECHAT", "WX_AGENT_1", o.amountFen(),
                LocalDateTime.now(), null);
        assertEquals(10, paid.codesIssued(), "夹具没建起来，后面的断言都不作数");
        assertEquals(51000, paid.commissionFen());
    }

    @AfterAll
    static void teardown() { ItDb.close(ds); }

    // ================================================================ 档案

    @Test @Order(1)
    @DisplayName("我的档案：一级看得到推广码，二级看得到上级推广码，手机号与卡号只出掩码")
    void profile() {
        var l1 = agents.me(U_L1);
        assertEquals("AGL1KEY0001", l1.get("agentCode"));
        assertEquals("ref=AGL1KEY0001", l1.get("refQuery"));
        assertEquals(1, l1.get("level"));
        assertEquals(true, l1.get("canOperate"));
        assertNull(l1.get("parentAgentCode"), "一级不该有上级");
        assertEquals("131****0010", l1.get("phone"));
        assertEquals("****1111", l1.get("settleBankAcct"),
                "完整卡号出现在了后台列表里 —— 这个页面天天开着，投屏截图就是一次泄漏");

        var l2 = agents.me(U_L2);
        assertEquals("AGL1KEY0001", l2.get("parentAgentCode"));
        assertEquals(2, l2.get("level"));
    }

    @Test @Order(2)
    @DisplayName("非代理查档案：404 且提示去申请，而不是 500 或空对象")
    void profileOfNonAgent() {
        var e = assertThrows(ApiException.class, () -> agents.me(30));
        assertEquals(404, e.status());
        assertTrue(e.getMessage().contains("还不是代理商"), e.getMessage());
    }

    @Test @Order(3)
    @DisplayName("PENDING 代理：能看自己的档案，但所有经营接口一律 403")
    void pendingAgentHasNoBusinessCapability() {
        // 看得到自己的审核状态
        var me = agents.me(U_PENDING);
        assertEquals("PENDING", me.get("status"));
        assertEquals("审核中", me.get("statusText"));
        assertEquals(false, me.get("canOperate"));

        // 其余一个都不许
        record Call(String name, org.junit.jupiter.api.function.Executable run) {}
        List<Call> calls = List.of(
                new Call("codes", () -> agents.codes(U_PENDING, null, null, 1, 20)),
                new Call("reveal", () -> agents.reveal(U_PENDING, 1, "1.1.1.1")),
                new Call("students", () -> agents.students(U_PENDING, 1, 20)),
                new Call("commissions", () -> agents.commissions(U_PENDING, null, 1, 20)),
                new Call("balance", () -> agents.balance(U_PENDING)),
                new Call("stats", () -> agents.stats(U_PENDING, null)),
                new Call("withdraw", () -> withdraws.apply(U_PENDING, 50_000L, "BANK_TRANSFER", "1.1.1.1")),
                new Call("withdrawList", () -> withdraws.list(U_PENDING, null, 1, 20)));
        for (var c : calls) {
            var e = assertThrows(ApiException.class, c.run(), c.name() + " 对审核中的代理放行了");
            assertEquals(403, e.status(), c.name());
            assertTrue(e.getMessage().contains("审核中"),
                    c.name() + " 的拒绝理由不是「审核中」而是：" + e.getMessage());
        }
    }

    // ================================================================ 申请

    @Test @Order(10)
    @DisplayName("申请一级：落 PENDING、角色改成 AGENT、推广码不可预测")
    void applyLevel1() {
        var r = agents.apply(30, 1, null, null, null, "INDIVIDUAL", null, "张三",
                "工商银行", "6222000000003030", "9.9.9.9");
        assertEquals("PENDING", r.status());
        assertEquals(1, r.level());

        var row = jdbc.queryForMap("SELECT * FROM agent_profile WHERE id=?", r.agentId());
        assertEquals("PENDING", row.get("status"));
        assertNull(row.get("parent_agent_id"), "一级不得有上级");
        assertEquals("AGENT", jdbc.queryForObject(
                "SELECT role FROM sys_user WHERE id=30", String.class),
                "角色没落成 AGENT，档案与角色对不上，这个号在后台是代理、在业务上什么都不是");

        // 不可预测：连着申请几个，码不得连号也不得撞
        var seen = new java.util.HashSet<String>();
        seen.add(r.agentCode());
        for (int u = 31; u <= 33; u++)
            assertTrue(seen.add(agents.apply(u, 1, null, null, null, null, null, null, null, null, "1").agentCode()),
                    "推广码撞车");
        for (String c : seen)
            assertTrue(c.startsWith("AG") && c.length() == 11, c);
    }

    @Test @Order(11)
    @DisplayName("重复申请：靠 uk_user 裁决，不靠先查后写，且回话里带当前状态")
    void applyTwice() {
        var e = assertThrows(ApiException.class,
                () -> agents.apply(30, 1, null, null, null, null, null, null, null, null, "1"));
        assertEquals(409, e.status());
        assertTrue(e.getMessage().contains("审核中"), e.getMessage());
        assertEquals(1, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_profile WHERE user_id=30", Integer.class));
    }

    @Test @Order(12)
    @DisplayName("申请二级：必须带推荐人推广码，且推荐人必须是一级、必须已生效")
    void applyLevel2() {
        // 不带推荐人
        var e1 = assertThrows(ApiException.class,
                () -> agents.apply(34, 2, null, null, null, null, null, null, null, null, "1"));
        assertEquals(400, e1.status());
        assertTrue(e1.getMessage().contains("推荐人"), e1.getMessage());

        // 推荐码不存在
        var e2 = assertThrows(ApiException.class,
                () -> agents.apply(34, 2, "AGNOTEXIST9", null, null, null, null, null, null, null, "1"));
        assertTrue(e2.getMessage().contains("不存在"), e2.getMessage());

        // 挂到二级名下 —— 这是 §7.6 的合规红线
        var e3 = assertThrows(ApiException.class,
                () -> agents.apply(34, 2, "AGL2KEY0002", null, null, null, null, null, null, null, "1"));
        assertEquals(400, e3.status());
        assertTrue(e3.getMessage().contains("二级不能再发展下线")
                        && e3.getMessage().contains("一级代理的推广码"),
                "挂到二级被拒了，但没告诉用户该怎么办：" + e3.getMessage());

        // 挂到审核中的一级代理名下：审核中的代理不该能发展下线，否则"平台审批"这一步
        // 可以被一条自助申请链绕过去 —— 未审的拉未审的，一层套一层
        var e4 = assertThrows(ApiException.class,
                () -> agents.apply(34, 2, "AGP5KEY0005", null, null, null, null, null, null, null, "1"));
        assertTrue(e4.getMessage().contains("尚未生效") || e4.getMessage().contains("暂停"),
                e4.getMessage());

        // 一级不许带推荐人
        var e5 = assertThrows(ApiException.class,
                () -> agents.apply(34, 1, "AGL1KEY0001", null, null, null, null, null, null, null, "1"));
        assertTrue(e5.getMessage().contains("一级代理不挂在任何人名下"), e5.getMessage());

        // 正路：挂到一级甲名下，同时留下推荐痕迹
        var ok = agents.apply(34, 2, "AGL1KEY0001", null, null, null, null, "李四", null, null, "1");
        assertEquals(2, ok.level());
        assertEquals(A_L1, ((Number) jdbc.queryForMap(
                "SELECT parent_agent_id FROM agent_profile WHERE id=?", ok.agentId())
                .get("parent_agent_id")).longValue());
        assertEquals(1, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_invite WHERE inviter_agent_id=? AND created_agent_id=?",
                Integer.class, A_L1, ok.agentId()),
                "没留推荐痕迹：事后「这个二级当初是谁推荐的」查不出来，抢人时谁都说不清");
    }

    @Test @Order(13)
    @DisplayName("应用层拦下的层级红线，库里那道闸也必须真的在（绕过应用层直插）")
    void levelGuardIsAlsoInTheDatabase() {
        var e = assertThrows(Exception.class, () ->
                jdbc.update("INSERT INTO agent_profile (user_id,agent_code,level,parent_agent_id,status)"
                          + " VALUES (37,'AGBYPASS001',2,?,'PENDING')", A_L2));
        assertTrue(rootMessage(e).contains("上级代理必须是存在且为一级"),
                "绕过应用层就能挂出三级链，说明这条红线只写在 Java 里：" + rootMessage(e));

        // 悬空的 parent 同样要被拒（A8 那个坑：NULL <> 1 求值为 NULL，IF 不触发）
        var e2 = assertThrows(Exception.class, () ->
                jdbc.update("INSERT INTO agent_profile (user_id,agent_code,level,parent_agent_id,status)"
                          + " VALUES (37,'AGBYPASS002',2,999999,'PENDING')"));
        assertTrue(rootMessage(e2).contains("上级代理必须是存在且为一级"), rootMessage(e2));
    }

    @Test @Order(14)
    @DisplayName("导师/管理员账号不能申请代理：审批人不能同时是被审批人")
    void mentorCannotApply() {
        var e = assertThrows(ApiException.class,
                () -> agents.apply(40, 1, null, null, null, null, null, null, null, null, "1"));
        assertEquals(403, e.status());
        assertTrue(e.getMessage().contains("不能申请代理"), e.getMessage());
    }

    // ====================================================== 驳回之后还有没有路

    /**
     * ★ 被驳回的申请人不能被永久锁死，而且不能被告知一件没发生过的事。
     *
     * <p>后台「驳回申请」写的是 {@code status='TERMINATED'} —— 和「终止合作」同一个值。
     * 于是一个从没合作过的申请人会读到「你的代理合作已终止 …… 已经产生、且过了冻结期的
     * 佣金仍可提现」，而重新申请拿到的是「无需重复提交」：两句话都说不通，且没有出路。
     *
     * <p>判据是 {@code approved_at}：只有审批通过才写它。没写过 = 那是一份被驳回的申请。
     */
    @Test @Order(15)
    @DisplayName("★ 申请被驳回：me 里分得出「驳回」与「终止」，并且能改完资料再交一次")
    void rejectedApplicationCanReapply() {
        var first = agents.apply(35, 1, null, "王五", null, "INDIVIDUAL", null, "王五",
                "工商银行", "6222020000009999", "1.2.3.4");
        assertEquals("PENDING", first.status());
        long profileId = first.agentId();

        // 后台驳回：写 TERMINATED，理由只进审计日志
        jdbc.update("UPDATE agent_profile SET status='TERMINATED' WHERE id=?", profileId);
        jdbc.update("INSERT INTO audit_log (operator_id,action,target_type,target_id,after_json)"
                  + " VALUES (9,'AGENT_REJECT','agent_profile',?,"
                  + " JSON_OBJECT('status','TERMINATED','reason','结算账户与实名不一致'))",
                String.valueOf(profileId));

        @SuppressWarnings("unchecked")
        var agent = (java.util.Map<String, Object>) agents.me(35).get("agent");
        assertEquals("TERMINATED", agent.get("status"));
        assertEquals(Boolean.FALSE, agent.get("everApproved"));
        assertEquals(Boolean.TRUE, agent.get("rejectedApplication"),
                "分不出「驳回」和「终止」，代理端只能对一个申请人说『你的合作已终止』");
        assertEquals(Boolean.TRUE, agent.get("canReapply"));
        assertEquals("结算账户与实名不一致", agent.get("statusNote"),
                "★ 驳回理由躺在 audit_log 里没有出口，当事人永远看不到自己为什么没过");

        // 改完资料再交一次：同一份档案回到 PENDING，推广码不变（存量二维码还印着它）
        String codeBefore = (String) agent.get("agentCode");
        var again = agents.apply(35, 1, null, "王五", null, "INDIVIDUAL", null, "王五",
                "建设银行", "6217000000008888", "1.2.3.4");
        assertEquals("PENDING", again.status(), "被驳回之后仍然交不上去 —— 这条路是死的");
        assertEquals(profileId, again.agentId(), "重投建出了第二份档案，uk_user 应当只允许一份");
        assertEquals(codeBefore, again.agentCode(), "推广码被换掉了，之前发出去的推广链接会全部失效");
        assertEquals(1, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_profile WHERE user_id=35", Integer.class));
        assertEquals("建设银行", jdbc.queryForMap(
                "SELECT settle_bank_name FROM agent_profile WHERE id=?", profileId)
                .get("settle_bank_name"), "重投没有把新填的资料写进去");
        assertEquals(1, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action='AGENT_REAPPLY' AND target_id=?",
                Integer.class, String.valueOf(profileId)));
    }

    @Test @Order(16)
    @DisplayName("★ 真正合作过再被终止的，不能在前台自助复活 —— 那是平台的决定")
    void terminatedPartnerCannotSelfResurrect() {
        // 自带一个空白号：夹具里 30~36 早被别的用例占了（36 就是那个「待审的一级」，
        // 借它跑这条会在第一步就撞上「已提交过申请」，而那考的是另一条规则
        jdbc.update("INSERT INTO sys_user (id,phone,role) VALUES (41,'13400000041','STUDENT')");
        var r = agents.apply(41, 1, null, "赵六", null, "INDIVIDUAL", null, "赵六",
                null, null, "1.2.3.4");
        long id = r.agentId();
        // 通过过审批（approved_at 有值），之后被终止
        jdbc.update("UPDATE agent_profile SET status='TERMINATED', approved_by=9,"
                  + " approved_at=NOW(3) WHERE id=?", id);

        @SuppressWarnings("unchecked")
        var agent = (java.util.Map<String, Object>) agents.me(41).get("agent");
        assertEquals(Boolean.TRUE, agent.get("everApproved"));
        assertEquals(Boolean.FALSE, agent.get("rejectedApplication"));
        assertEquals(Boolean.FALSE, agent.get("canReapply"));

        var e = assertThrows(ApiException.class, () -> agents.apply(41, 1, null, null, null,
                null, null, null, null, null, "1.2.3.4"));
        assertEquals(409, e.status());
        assertTrue(e.getMessage().contains("已终止"), e.getMessage());
        assertEquals("TERMINATED", jdbc.queryForObject(
                "SELECT status FROM agent_profile WHERE id=?", String.class, id),
                "自助重投把一个被终止的合作改回了待审核");
    }

    // ================================================================ 激活码

    @Test @Order(20)
    @DisplayName("我的码只出掩码；一级看不到二级的货")
    void codesAreMaskedAndScoped() {
        var mine = agents.codes(U_L2, null, null, 1, 100);
        assertEquals(10, mine.total());
        for (var c : mine.items()) {
            String masked = (String) c.get("masked");
            assertTrue(masked.contains("****"), "列表里出现了非掩码的码：" + masked);
            assertFalse(c.containsKey("code"), "列表里带出了明文字段");
            assertFalse(c.containsKey("boundUserId"), "列表里带出了别人的 user_id");
        }

        // 一级也看不到二级进的货。能看就意味着上级可以拿下级的码去卖或直接激活。
        assertEquals(0, agents.codes(U_L1, null, null, 1, 100).total(),
                "一级看到了二级的库存 —— 下级的货在上级后台里是不设防的");
        // 连按订单号定向查也查不到：条件是 owner_agent_id = 我，不是「这单是不是我的」
        assertEquals(0, agents.codes(U_L1, orderId, null, 1, 100).total());
    }

    @Test @Order(21)
    @DisplayName("分页与状态筛选：越界页返回空而不是报错，未知状态当场拒")
    void codePaging() {
        assertEquals(3, agents.codes(U_L2, null, null, 1, 3).items().size());
        assertEquals(1, agents.codes(U_L2, null, null, 4, 3).items().size());
        assertEquals(0, agents.codes(U_L2, null, null, 9, 3).items().size());
        assertEquals(10, agents.codes(U_L2, null, "ISSUED", 1, 100).total());
        assertEquals(0, agents.codes(U_L2, null, "REVOKED", 1, 100).total());

        var e = assertThrows(ApiException.class, () -> agents.codes(U_L2, null, "Issued ok", 1, 20));
        assertEquals(400, e.status());
        assertTrue(e.getMessage().contains("未知的激活码状态"), e.getMessage());
        // 分页参数是攻击面的一部分：size 不封顶就是一次全量导出
        assertEquals(AgentService.MAX_PAGE_SIZE, agents.codes(U_L2, null, null, 1, 100000).size());
        assertThrows(ApiException.class, () -> agents.codes(U_L2, null, null, 0, 20));
    }

    @Test @Order(22)
    @DisplayName("看明文：本人可看且必写审计；越权与不存在给出【同一句话】")
    void revealIsAuditedAndDoesNotLeakExistence() {
        long codeId = (Long) agents.codes(U_L2, null, null, 1, 1).items().get(0).get("id");
        assertTrue(codeId > 0);

        int before = auditCount(codeId);
        String plain = agents.reveal(U_L2, codeId, "8.8.8.8");
        assertEquals(ActivationCodes.normalize(plain), plain, "取出来的明文不是规范形式");
        assertEquals(before + 1, auditCount(codeId),
                "看了明文却没留痕：日后「我的码被别人用了」的纠纷里，平台是没有立场的一方");

        var log = jdbc.queryForMap("SELECT * FROM audit_log WHERE action='CODE_REVEAL' "
                + "AND target_id=? ORDER BY id DESC LIMIT 1", String.valueOf(codeId));
        assertEquals(U_L2, ((Number) log.get("operator_id")).longValue());
        assertEquals("8.8.8.8", log.get("client_ip"));

        // 一级去取二级的码 —— 这是本块最大的风险，必须拒
        var denied = assertThrows(IllegalArgumentException.class,
                () -> agents.reveal(U_L1, codeId, "1.1.1.1"));
        // 不存在的码
        var missing = assertThrows(IllegalArgumentException.class,
                () -> agents.reveal(U_L1, 999_999L, "1.1.1.1"));
        assertEquals(missing.getMessage(), denied.getMessage(),
                "「不是你的」和「不存在」回话不同，等于开放了一个可以逐个枚举码 id 的探针");
        assertFalse(denied.getMessage().contains(String.valueOf(codeId)));

        // 越权那次不许留下"看过明文"的痕迹（审计条数不能变）
        assertEquals(before + 1, auditCount(codeId), "越权失败也记了一次 CODE_REVEAL，审计被污染");
    }

    // ================================================================ 学员

    @Test @Order(30)
    @DisplayName("我名下的学员：归属来的 + 用我的码激活的，两个来源都要算；手机号掩码")
    void students() {
        // 学员 20 拿二级乙的码激活
        String plain = agents.reveal(U_L2, (Long) agents.codes(U_L2, null, "ISSUED", 1, 1)
                .items().get(0).get("id"), "1.1.1.1");
        var r = activation.redeem(20, plain, "v2026-08-11", "5.5.5.5");
        assertTrue(r.firstTime());

        var mine = agents.students(U_L2, 1, 20);
        assertEquals(1, mine.total());
        var s = mine.items().get(0);
        assertEquals(20L, s.get("userId"));
        assertEquals("132****0020", s.get("phone"), "学员完整手机号落到了代理手里");
        assertNotNull(s.get("myCodeMasked"), "看不出这个学员用的是我的哪张码，售后对不上账");
        assertTrue(((String) s.get("myCodeMasked")).contains("****"));
        assertEquals(true, s.get("attributedToMe"));
        assertEquals(1, s.get("courseCount"));

        // 甲名下是那个只点了链接、还没买的学员 21 —— 恰恰是最该跟进的一批，不能漏
        var his = agents.students(U_L1, 1, 20);
        assertEquals(1, his.total());
        assertEquals(21L, his.items().get(0).get("userId"));
        assertEquals(0, his.items().get(0).get("courseCount"));
        assertNull(his.items().get(0).get("myCodeMasked"));
    }

    // ================================================================ 佣金与余额

    @Test @Order(40)
    @DisplayName("佣金流水只读 commission_record：金额、基数、冻结期都来自成交时的快照")
    void commissions() {
        var list = agents.commissions(U_L1, null, 1, 20);
        assertEquals(1, list.total());
        var c = list.items().get(0);
        assertEquals(51000L, c.get("amountFen"), "佣金不是 51/码 × 10，说明有人在展示端又算了一遍");
        assertEquals(349000L, c.get("basisFen"), "计算基数应为订单实付");
        assertEquals("PENDING", c.get("status"));
        assertEquals("冻结中", c.get("statusText"));
        assertEquals("AGL2KEY0002", c.get("sourceAgentCode"));

        // 二级自己没有下级，拿不到佣金
        assertEquals(0, agents.commissions(U_L2, null, 1, 20).total());
        // 越权：甲的流水里不会出现别人的记录，条件在 WHERE 里
        assertEquals(0, agents.commissions(U_SOLO, null, 1, 20).total());
    }

    @Test @Order(41)
    @DisplayName("余额：可提现 = 台账余额 − 在途提现，与提现闸门同一道算式")
    void balanceMatchesWithdrawGate() {
        // 佣金转可提现（冻结期满 → 入台账 → 改余额）这条链目前仓里没有任何代码在跑，
        // 这里手工造出那一步的产物，否则提现永远走不到闸门那一步。
        settleIntoBalance(A_L1, 100_000);

        var b = agents.balance(U_L1);
        assertEquals(true, b.get("accountOpened"));
        assertEquals(100_000L, b.get("availableFen"));
        assertEquals(0L, b.get("inflightWithdrawFen"));
        assertEquals(100_000L, b.get("withdrawableFen"));
        // 还在冻结期里的那 510 元不算可提 —— 混进去就等于退款窗口内的钱可以被提走
        assertEquals(51_000L, b.get("frozenFen"));

        // 没有佣金账户的代理：显示 0 而不是报错，且明说账户还没开
        var b2 = agents.balance(U_L2);
        assertEquals(false, b2.get("accountOpened"));
        assertEquals(0L, b2.get("withdrawableFen"));
    }

    // ================================================================ 统计

    @Test @Order(50)
    @DisplayName("统计口径与 seed 价目一致：进货 10 / 动销 1 / 库存 9 / 成交额 3490")
    void stats() {
        var s = agents.stats(U_L2, null);
        assertEquals(10L, s.get("purchasedCodes"));
        assertEquals(1L, s.get("activatedCodes"), "动销没跟着激活走，渠道健康度就是假的");
        assertEquals(9L, s.get("unactivatedStock"));
        // 进货金额不出网（代理端一律不出价）。同一条规矩换观测点：钉库里那一行。
        assertFalse(s.containsKey("purchasedAmountFen"), "/stats 把累计进货金额出网了");
        assertEquals(349_000L, jdbc.queryForObject(
                "SELECT SUM(amount_fen) FROM biz_order WHERE buyer_agent_id="
              + "(SELECT id FROM agent_profile WHERE user_id=?) AND paid_at IS NOT NULL",
                Long.class, U_L2),
                "成交额与 seed 里的二级批量价对不上");
        assertEquals(1L, s.get("paidOrders"));
        assertFalse(s.containsKey("subAgentPurchasedCodes"),
                "二级不该看到下级汇总 —— 它根本不该有下级");

        var l1 = agents.stats(U_L1, null);
        assertEquals(0L, l1.get("purchasedCodes"), "甲自己没进货");
        assertEquals(51_000L, l1.get("commissionEarnedFen"));
        // 汇总口径是码数不是金额：金额就是下级谈下来的专属价。
        assertEquals(10L, l1.get("subAgentPurchasedCodes"),
                "一级看不到下级的渠道活跃度了 —— 换口径不等于砍功能");
        assertFalse(l1.containsKey("subAgentPurchasedAmountFen"),
                "下级的进货金额出网了：那是他单独谈下来的价");
        assertEquals(1L, l1.get("subAgentActiveCount"));

        // 平台净收与"谁来买"无关，这条恒等式必须落在真实成交上（seed 的价目表就是这么设计的）
        // 恒等式没变，只是「二级付了多少」这个数不再由代理端接口给（它就是进货价），
        // 改从库里取。这条是全平台价目设计的地基：平台净收与「谁来买」无关。
        long l2Paid = jdbc.queryForObject(
                "SELECT SUM(amount_fen) FROM biz_order WHERE buyer_agent_id="
              + "(SELECT id FROM agent_profile WHERE user_id=?) AND paid_at IS NOT NULL",
                Long.class, U_L2);
        assertEquals(298_000L, l2Paid - (Long) l1.get("commissionEarnedFen"),
                "二级付 3490 减佣金 510 应正好等于一级批量价 2980");

        // 按月：本月有数，上个月必须是 0 而不是把全部算进去
        String thisMonth = java.time.YearMonth.now().toString();
        String lastMonth = java.time.YearMonth.now().minusMonths(1).toString();
        // 月度窗口这条规矩不变，观测点换成码数（金额不再出网）：
        // 本月有数、上个月必须是 0 而不是把全部算进去。
        assertEquals(10L, agents.stats(U_L2, thisMonth).get("purchasedCodes"));
        assertEquals(0L, agents.stats(U_L2, lastMonth).get("purchasedCodes"),
                "按月筛没生效，上个月把全部进货一起算进去了");
        assertThrows(ApiException.class, () -> agents.stats(U_L2, "2026-13"));
        assertThrows(ApiException.class, () -> agents.stats(U_L2, "8月"));
    }

    // ================================================================ 提现

    @Test @Order(60)
    @DisplayName("提现：低于门槛、没有佣金账户、没登记结算账户 —— 各自说清是哪一条")
    void withdrawGuards() {
        var low = assertThrows(ApiException.class,
                () -> withdraws.apply(U_L1, 9_999L, "BANK_TRANSFER", "1.1.1.1"));
        assertEquals(400, low.status());
        assertTrue(low.getMessage().contains("不得低于 100.00 元"), low.getMessage());

        var none = assertThrows(ApiException.class,
                () -> withdraws.apply(U_L2, 50_000L, "BANK_TRANSFER", "1.1.1.1"));
        assertTrue(none.getMessage().contains("还没有可提现的佣金"), none.getMessage());

        // 没登记结算账户的代理：要在申请这一步就拦，不能等到打款失败
        jdbc.update("UPDATE agent_profile SET settle_bank_acct=NULL WHERE id=?", A_SOLO);
        settleIntoBalance(A_SOLO, 50_000);
        var noAcct = assertThrows(ApiException.class,
                () -> withdraws.apply(U_SOLO, 20_000L, "BANK_TRANSFER", "1.1.1.1"));
        assertTrue(noAcct.getMessage().contains("结算账户"), noAcct.getMessage());
        jdbc.update("UPDATE agent_profile SET settle_bank_acct='6225880000004444' WHERE id=?", A_SOLO);

        var badChannel = assertThrows(ApiException.class,
                () -> withdraws.apply(U_L1, 20_000L, "ALIPAY", "1.1.1.1"));
        assertTrue(badChannel.getMessage().contains("提现渠道"), badChannel.getMessage());
    }

    @Test @Order(61)
    @DisplayName("提现超过可用余额被拒；在途提现单同样占额度")
    void withdrawCannotExceedBalance() {
        // 余额 1000 元
        var first = withdraws.apply(U_L1, 60_000L, "BANK_TRANSFER", "1.1.1.1");
        assertEquals("APPLIED", first.status());
        assertEquals("甲一级", first.payeeName(), "户名不是实名，等于允许代收");
        assertEquals(40_000L, first.remainingFen());

        // 余额没扣（要等打款成功），但在途的这一笔必须占住额度
        var again = assertThrows(ApiException.class,
                () -> withdraws.apply(U_L1, 60_000L, "BANK_TRANSFER", "1.1.1.1"));
        assertEquals(409, again.status());
        assertTrue(again.getMessage().contains("可提现 400.00 元")
                        && again.getMessage().contains("在途提现单"),
                "只比余额不比在途：1000 元余额能连提两笔 600 —— " + again.getMessage());

        assertEquals(60_000L, agents.balance(U_L1).get("inflightWithdrawFen"));
        assertEquals(40_000L, agents.balance(U_L1).get("withdrawableFen"));

        // 应用层这道预检只是为了出人话，真正的闸门在库里。绕过应用层直插也必须被拒。
        var bypass = assertThrows(Exception.class, () ->
                jdbc.update("INSERT INTO withdraw_order (withdraw_no,agent_id,amount_fen,channel,"
                          + "payee_name,payee_account,status) VALUES (?,?,?,?,?,?,'APPLIED')",
                        "WDBYPASS0001", A_L1, 60_000L, "BANK_TRANSFER", "甲一级", "6225880000001111"));
        assertTrue(rootMessage(bypass).contains("超过可提现佣金余额"),
                "库里那道闸没生效，应用层就是唯一防线：" + rootMessage(bypass));

        // 负余额（退款冲正没抵扣完）时一分都不能提
        jdbc.update("UPDATE agent_balance SET commission_balance_fen=-1 WHERE agent_id=?", A_SOLO);
        var neg = assertThrows(ApiException.class,
                () -> withdraws.apply(U_SOLO, 20_000L, "BANK_TRANSFER", "1.1.1.1"));
        assertTrue(neg.getMessage().contains("余额为负"), neg.getMessage());
        jdbc.update("UPDATE agent_balance SET commission_balance_fen=50000 WHERE agent_id=?", A_SOLO);
    }

    @Test @Order(62)
    @DisplayName("提现单列表：只出自己的，卡号掩码")
    void withdrawList() {
        var mine = withdraws.list(U_L1, null, 1, 20);
        assertEquals(1, mine.total());
        var w = mine.items().get(0);
        assertEquals(60_000L, w.get("amountFen"));
        assertEquals("已申请，待审核", w.get("statusText"));
        assertEquals("****1111", w.get("payeeAccount"));
        assertTrue(((String) w.get("withdrawNo")).startsWith("WD"));

        assertEquals(0, withdraws.list(U_SOLO, null, 1, 20).total(),
                "别人的提现单出现在了我的列表里");
        assertEquals(1, withdraws.list(U_L1, "APPLIED", 1, 20).total());
        assertEquals(0, withdraws.list(U_L1, "PAID", 1, 20).total());
        assertThrows(ApiException.class, () -> withdraws.list(U_L1, "DONE", 1, 20));
    }

    @Test @Order(63)
    @DisplayName("并发提现：8 个线程各提 6 成余额，只能有一笔进得去")
    void concurrentWithdrawIsSerialized() throws Exception {
        // 触发器里那两条 SELECT 是一致性读，并发下彼此看不见对方的在途单，
        // 只靠它的话两笔都能过闸 —— 这里验的是申请前对余额行加的那把排他锁。
        //
        // 阴性对照跑过：把 WithdrawService 里的 FOR UPDATE 去掉，这条用例当场变成
        // 「expected: <1> but was: <7>」——8 笔里 7 笔都过了闸，在途合计 4200 元
        // 对着 1000 元余额。所以这条用例量的确实是那把锁，不是碰巧串行。
        settleIntoBalance(A_SOLO, 100_000);
        jdbc.update("UPDATE agent_balance SET commission_balance_fen=100000 WHERE agent_id=?", A_SOLO);

        int n = 8;
        var pool = Executors.newFixedThreadPool(n);
        var start = new CountDownLatch(1);
        var okCount = new AtomicInteger();
        var futures = new java.util.ArrayList<Future<?>>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                try { withdraws.apply(U_SOLO, 60_000L, "BANK_TRANSFER", "1.1.1.1"); okCount.incrementAndGet(); }
                catch (ApiException ignored) { /* 被额度拦下是预期结果 */ }
                return null;
            }));
        }
        start.countDown();
        for (var f : futures) f.get(60, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(1, okCount.get(), "并发下放行了不止一笔 6 成余额的提现");
        assertEquals(60_000L, (long) jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount_fen),0) FROM withdraw_order WHERE agent_id=?",
                Long.class, A_SOLO),
                "在途提现总额超过了余额");
    }

    @Test @Order(70)
    @DisplayName("已有归属的学员转做代理：first-touch 那道不可变触发器不该把申请挡下来")
    void attributedStudentCanBecomeAgent() {
        // 申请代理会 UPDATE sys_user（改角色），而 sys_user 上挂着 trg_user_attribution_immutable。
        // 这条路只有"本来就有归属的学员"才走得到 —— 空白号一路绿，
        // 而真实世界里想做代理的恰恰是那些先被人带进来、卖得不错的学员。
        jdbc.update("INSERT INTO sys_user (id,phone,role,attributed_agent_id,attributed_at,"
                  + "attribution_source) VALUES (38,'13300000038','STUDENT',1,NOW(3),'REF_LINK')");

        var r = agents.apply(38, 2, "AGL1KEY0001", null, null, null, null, null, null, null, "1");
        assertEquals("PENDING", r.status());
        assertEquals("AGENT", jdbc.queryForObject("SELECT role FROM sys_user WHERE id=38", String.class));
        // 归属一个字都不该被动过：申请代理不是 first-touch 的例外
        assertEquals(A_L1, ((Number) jdbc.queryForMap(
                "SELECT attributed_agent_id FROM sys_user WHERE id=38").get("attributed_agent_id"))
                .longValue());
    }

    // ================================================================ 工具

    /**
     * 造出「冻结期满 → 佣金入台账 → 余额可提」那一步的产物。
     * 仓里目前没有任何代码做这件事（既没有定时任务，也没有写 commission_ledger 的路径），
     * 所以这里手工补上，否则提现这一整块在测试里根本走不到闸门。
     */
    private static void settleIntoBalance(long agentId, long fen) {
        jdbc.update("INSERT INTO agent_balance (agent_id,commission_balance_fen,total_settled_fen) "
                  + "VALUES (?,?,?) ON DUPLICATE KEY UPDATE commission_balance_fen=VALUES(commission_balance_fen)",
                agentId, fen, fen);
    }

    private static int auditCount(long codeId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='CODE_REVEAL' "
                + "AND target_type='activation_code' AND target_id=?", Integer.class,
                String.valueOf(codeId));
    }

    /** 触发器的 SIGNAL 藏在异常链里，逐层找出原文再断言 —— 只断言"抛了异常"钉不住原因。 */
    private static String rootMessage(Throwable e) {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) sb.append(t.getMessage()).append(" | ");
        return sb.toString();
    }
}
