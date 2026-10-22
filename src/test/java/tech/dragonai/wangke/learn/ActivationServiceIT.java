package tech.dragonai.wangke.learn;

import tech.dragonai.wangke.ItDb;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tech.dragonai.wangke.trade.*;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 激活链：码 → 权益 → 归属。跑在真实 MySQL 上。
 *
 * <p>这条链的赔付点与出码那条不同。出码怕的是"多发一份货"，
 * 激活怕的是两件事：<b>一码两人</b>（同一串码开出两份权益，等于白送）
 * 与<b>码被白白消耗</b>（激活失败了却把码烧掉，学员的钱打了水漂，而且不可逆——
 * 库里那条"激活即不退"的规则会让售后无解）。所以每个负例都要额外断言码还在。
 *
 * <p>每个负例都<b>钉住失败原因</b>。只断言"抛异常"的负例太容易因为别的原因红着，
 * 那样它通过时什么也没证明 —— db/test_schema.py 那边靠这条抓出过两个假通过。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ActivationServiceIT {

    static final String DB = "wangke_act_it";
    static DataSource ds;
    static JdbcTemplate jdbc;
    static FulfillService fulfill;
    static ActivationService act;
    static CodeService codes;
    static CodeVault vault;

    static final long COURSE = 1L;
    static final String AGREEMENT = "v2026-08-11";

    @BeforeAll
    static void bootstrap() throws Exception {
        ds = ItDb.freshDatabase(DB);
        jdbc = new JdbcTemplate(ds);

        vault = new CodeVault(ItDb.testKey(42), "it-salt".getBytes(StandardCharsets.UTF_8));
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        fulfill = new FulfillService(jdbc, new PricingService(jdbc), tx, vault);
        act = new ActivationService(jdbc, tx, vault);
        codes = new CodeService(jdbc, vault);

        // 一级 A1（agent 1 / user 10）、二级 B2（agent 2 / user 11）
        jdbc.update("INSERT INTO sys_user (id,phone,role) VALUES (10,'13100000010','AGENT'),(11,'13100000011','AGENT')");
        jdbc.update("INSERT INTO agent_profile (id,user_id,agent_code,level,status) VALUES (1,10,'A1',1,'ACTIVE')");
        jdbc.update("INSERT INTO agent_profile (id,user_id,agent_code,level,parent_agent_id,status)"
                  + " VALUES (2,11,'B2',2,1,'ACTIVE')");
        // 学员 20..40，另加一个被冻结的 50
        for (int i = 20; i <= 40; i++)
            jdbc.update("INSERT INTO sys_user (id,phone,role) VALUES (?,?,'STUDENT')", i, "1320000" + (1000 + i));
        jdbc.update("INSERT INTO sys_user (id,phone,role,status) VALUES (50,'13200005050','STUDENT','FROZEN')");

        // 二级代理买 10 个码，付款出货
        long orderId = newOrder("ACT_ORD_1", 2, 11);
        fulfill.confirmPaid(orderId, "WECHAT", "WX_ACT_1", 349000, LocalDateTime.now(), null);
    }

    @AfterAll
    static void teardown() { ItDb.close(ds); }

    static long newOrder(String orderNo, long agentId, long userId) {
        var price = new PricingService(jdbc).priceOf(agentId == 1 ? 1 : 2, 2);
        jdbc.update("INSERT INTO biz_order (order_no,buyer_user_id,buyer_type,buyer_agent_id,"
                  + "buyer_agent_level,sku_id,sku_code_snapshot,code_qty_snapshot,price_tier_id,"
                  + "amount_fen,expire_at) VALUES (?,?,'AGENT',?,?,2,'AGENT_10',10,?,?,"
                  + "DATE_ADD(NOW(3), INTERVAL 2 HOUR))",
                orderNo, userId, agentId, agentId == 1 ? 1 : 2, price.tierId(), price.amountFen());
        return jdbc.queryForObject("SELECT id FROM biz_order WHERE order_no=?", Number.class, orderNo).longValue();
    }

    /** 取第 n 个仍未激活的码的明文（走 CodeService，顺便证明交付路径通） */
    static String plainCode(long agentId, int n) {
        var list = codes.list(agentId, null).stream().filter(c -> "ISSUED".equals(c.status())).toList();
        var row = list.get(n);
        return codes.reveal(agentId == 1 ? 10 : 11, agentId, row.id(), "10.0.0.1");
    }

    static String statusOfCode(String plain) {
        return jdbc.queryForObject("SELECT status FROM activation_code WHERE code_hash=?",
                String.class, vault.hash(plain));
    }

    static ActivationService.RedeemException expect(ActivationService.Reason want, Executable body) {
        var e = assertThrows(ActivationService.RedeemException.class, body);
        assertEquals(want, e.reason(),
                "失败原因不是要测的那个（实际：" + e.reason() + " / " + e.getMessage()
              + "）—— 这个负例通过时并没有证明被测的那条规则成立");
        return e;
    }

    // ---------------------------------------------------------------- 正例

    @Test @Order(1)
    @DisplayName("交付路径：代理列表只见掩码，显式查看才给明文并留审计")
    void deliveryPath() {
        var list = codes.list(2, null);
        assertEquals(10, list.size());
        assertTrue(list.stream().allMatch(c -> c.masked().contains("****")), "列表里出现了完整码");

        long id = list.get(0).id();
        String plain = codes.reveal(11, 2, id, "10.0.0.1");
        assertEquals(19, plain.length());
        assertEquals(ActivationCodes.mask(plain), list.get(0).masked(), "明文与掩码对不上");
        assertEquals(1, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action='CODE_REVEAL' AND target_id=?",
                Integer.class, String.valueOf(id)), "查看明文没有留痕，日后纠纷平台没有立场");

        // 越权：一级代理来看二级的码
        assertThrows(IllegalArgumentException.class, () -> codes.reveal(10, 1, id, "10.0.0.2"));
    }

    @Test @Order(2)
    @DisplayName("激活成功：权益 24 个月、码转 BOUND、归属首次落定到售出方")
    void redeemHappyPath() {
        String code = plainCode(2, 0);
        var r = act.redeem(20, code, AGREEMENT, "1.2.3.4");

        assertTrue(r.firstTime());
        assertEquals(COURSE, r.courseId());
        assertEquals(2L, r.attributedAgentId(), "权益归属应当跟码走 —— 这个座位是二级代理卖的");
        assertTrue(r.attributionJustSet(), "学员首次归属没落定");

        var e = jdbc.queryForMap("SELECT * FROM enrollment WHERE id=?", r.enrollmentId());
        assertEquals("ACTIVATION_CODE", e.get("source"));
        assertEquals("ACTIVE", e.get("status"));
        assertEquals(AGREEMENT, e.get("agreement_version"), "协议版本没落库，G6 留证是空的");
        // Connector/J 8.x 默认把 DATETIME 映射成 LocalDateTime 而不是 java.sql.Timestamp
        var from = (LocalDateTime) e.get("valid_from");
        var until = (LocalDateTime) e.get("valid_until");
        assertEquals(from.plusMonths(24), until, "观看期不是课程配置的 access_months");

        assertEquals("BOUND", statusOfCode(code));
        var u = jdbc.queryForMap("SELECT attributed_agent_id, attribution_source FROM sys_user WHERE id=20");
        assertEquals(2L, ((Number) u.get("attributed_agent_id")).longValue());
        assertEquals("ACTIVATION_CODE", u.get("attribution_source"));
    }

    @Test @Order(3)
    @DisplayName("同一人重复提交同一串码：返回既有权益，不报错也不再开一份")
    void redeemIsIdempotentForSamePerson() {
        String code = plainCode(2, 0);              // 已激活的那串已不在 ISSUED 列表里
        var first = act.redeem(21, code, AGREEMENT, "1.2.3.4");
        for (int i = 0; i < 3; i++) {
            var again = act.redeem(21, code, AGREEMENT, "1.2.3.4");
            assertFalse(again.firstTime(), "重复提交被当成首次激活");
            assertEquals(first.enrollmentId(), again.enrollmentId(), "同一个码开出了第二份权益");
        }
        assertEquals(1, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM enrollment WHERE user_id=21", Integer.class));
    }

    // ---------------------------------------------------------------- 负例（每条都钉住原因 + 断言码没被烧掉）

    @Test @Order(4)
    @DisplayName("别人的码：一码一人")
    void codeBoundByOther() {
        String code = jdbc.queryForObject(
                "SELECT code_hash FROM activation_code WHERE bound_user_id=20", String.class);
        assertNotNull(code);
        // 用已绑定那串的明文重放：从 code_enc 取回
        var row = jdbc.queryForMap("SELECT id FROM activation_code WHERE bound_user_id=20");
        String plain = vault.open(jdbc.queryForObject(
                "SELECT code_enc FROM activation_code WHERE bound_user_id=20", byte[].class), code);
        expect(ActivationService.Reason.ALREADY_BOUND_BY_OTHER,
                () -> act.redeem(22, plain, AGREEMENT, "1.2.3.4"));
        assertEquals(20L, ((Number) jdbc.queryForMap(
                "SELECT bound_user_id FROM activation_code WHERE id=?", row.get("id"))
                .get("bound_user_id")).longValue(), "绑定被改掉了");
    }

    @Test @Order(5)
    @DisplayName("已有同课权益：拒绝，且此码必须【原封不动】还在")
    void alreadyEnrolledDoesNotBurnTheCode() {
        String code = plainCode(2, 0);
        expect(ActivationService.Reason.ALREADY_ENROLLED,
                () -> act.redeem(20, code, AGREEMENT, "1.2.3.4"));
        assertEquals("ISSUED", statusOfCode(code),
                "激活失败却把码烧掉了 —— 学员的钱打了水漂，而且『激活即不退』让售后无解");
    }

    @Test @Order(6)
    @DisplayName("超过激活期限：EXPIRED，码不消耗")
    void expiredCode() {
        String code = plainCode(2, 0);
        jdbc.update("UPDATE activation_code SET activate_deadline=DATE_SUB(NOW(3), INTERVAL 1 DAY) "
                  + "WHERE code_hash=?", vault.hash(code));
        expect(ActivationService.Reason.EXPIRED, () -> act.redeem(23, code, AGREEMENT, "1.2.3.4"));
        assertEquals("ISSUED", statusOfCode(code));
        jdbc.update("UPDATE activation_code SET activate_deadline=DATE_ADD(NOW(3), INTERVAL 300 DAY) "
                  + "WHERE code_hash=?", vault.hash(code));
    }

    @Test @Order(7)
    @DisplayName("已作废：REVOKED")
    void revokedCode() {
        String code = plainCode(2, 1);
        jdbc.update("UPDATE activation_code SET status='REVOKED', revoked_at=NOW(3), "
                  + "revoke_reason='测试' WHERE code_hash=?", vault.hash(code));
        expect(ActivationService.Reason.REVOKED, () -> act.redeem(23, code, AGREEMENT, "1.2.3.4"));
    }

    @Test @Order(8)
    @DisplayName("输入不合法：连库都不查")
    void malformedInput() {
        expect(ActivationService.Reason.MALFORMED, () -> act.redeem(23, "ACDE-FHJK", AGREEMENT, "1"));
        expect(ActivationService.Reason.MALFORMED, () -> act.redeem(23, "ACDO-FHJK-LMNP-QRTU", AGREEMENT, "1"));
        expect(ActivationService.Reason.MALFORMED, () -> act.redeem(23, "", AGREEMENT, "1"));
    }

    @Test @Order(9)
    @DisplayName("格式对但不存在：NOT_FOUND")
    void notFound() {
        expect(ActivationService.Reason.NOT_FOUND,
                () -> act.redeem(23, "ACDE-FHJK-LMNP-QRTU", AGREEMENT, "1.2.3.4"));
    }

    @Test @Order(10)
    @DisplayName("冻结账号不能开课，码不消耗")
    void frozenUser() {
        String code = plainCode(2, 0);
        expect(ActivationService.Reason.USER_NOT_ACTIVE, () -> act.redeem(50, code, AGREEMENT, "1.2.3.4"));
        assertEquals("ISSUED", statusOfCode(code), "封了号还把码烧了");
    }

    @Test @Order(11)
    @DisplayName("没带协议版本一律拒：G6 留证不能省")
    void agreementRequired() {
        String code = plainCode(2, 0);
        expect(ActivationService.Reason.AGREEMENT_REQUIRED, () -> act.redeem(23, code, null, "1.2.3.4"));
        expect(ActivationService.Reason.AGREEMENT_REQUIRED, () -> act.redeem(23, code, "  ", "1.2.3.4"));
        assertEquals("ISSUED", statusOfCode(code));
    }

    // ---------------------------------------------------------------- 归属

    @Test @Order(12)
    @DisplayName("first-touch：已归属 A 的学员用 B 的码激活，人还是 A 的，座位算 B 的")
    void firstTouchWins() throws Exception {
        // 一级代理 A1 也进一批货
        long o = newOrder("ACT_ORD_2", 1, 10);
        fulfill.confirmPaid(o, "WECHAT", "WX_ACT_2", 298000, LocalDateTime.now(), null);

        // 学员 24 先由 A1 的推广链接带进来
        jdbc.update("UPDATE sys_user SET attributed_agent_id=1, attributed_at=NOW(3), "
                  + "attribution_source='REF_LINK' WHERE id=24");

        String bCode = plainCode(2, 0);             // 二级 B2 的码
        var r = act.redeem(24, bCode, AGREEMENT, "1.2.3.4");

        assertFalse(r.attributionJustSet(), "first-touch 被后来的激活改掉了");
        assertEquals(1L, ((Number) jdbc.queryForMap(
                "SELECT attributed_agent_id FROM sys_user WHERE id=24").get("attributed_agent_id")).longValue(),
                "人的归属必须还是最早触达的 A1");
        assertEquals(2L, r.attributedAgentId(), "这个座位是 B2 卖的，权益归属应当是 B2");
    }

    @Test @Order(13)
    @DisplayName("代理拿自己的码给自己开课：不自归属（自购返佣的入口）")
    void agentCannotAttributeToSelf() {
        String code = plainCode(2, 0);
        var r = act.redeem(11, code, AGREEMENT, "1.2.3.4");     // 11 就是 B2 本人
        assertFalse(r.attributionJustSet(), "代理把自己设成了自己的归属代理");
        assertNull(jdbc.queryForMap("SELECT attributed_agent_id FROM sys_user WHERE id=11")
                .get("attributed_agent_id"));
        assertEquals(2L, r.attributedAgentId(), "座位仍然记在自己名下（自己买的自己用）");
    }

    // ---------------------------------------------------------------- 并发与不可逆

    @Test @Order(14)
    @DisplayName("12 个学员抢同一串码：恰好 1 人拿到，其余全是 ALREADY_BOUND_BY_OTHER")
    void concurrentRedeemOfSameCode() throws Exception {
        String code = plainCode(2, 0);
        int n = 12;
        var pool = Executors.newFixedThreadPool(n);
        var start = new CountDownLatch(1);
        var wins = new AtomicInteger();
        var wrongReason = new CopyOnWriteArrayList<String>();
        var crashed = new CopyOnWriteArrayList<String>();
        List<Future<?>> fs = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            final long uid = 25 + i;                 // 学员 25..36，互不相同
            fs.add(pool.submit(() -> {
                try {
                    start.await();
                    act.redeem(uid, code, AGREEMENT, "1.2.3.4");
                    wins.incrementAndGet();
                } catch (ActivationService.RedeemException e) {
                    if (e.reason() != ActivationService.Reason.ALREADY_BOUND_BY_OTHER)
                        wrongReason.add(uid + "→" + e.reason() + ": " + e.getMessage());
                } catch (Exception e) {
                    crashed.add(uid + "→" + e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }));
        }
        start.countDown();
        for (Future<?> f : fs) f.get(60, TimeUnit.SECONDS);
        pool.shutdown();

        // 先看崩溃再看语义：断言顺序决定失败信息指向哪儿，指错方向会浪费一整轮排查
        assertTrue(crashed.isEmpty(), "并发过程中抛了非业务异常：" + crashed);
        assertEquals(1, wins.get(), "有 " + wins.get() + " 个人同时激活成功 —— 一码两人");
        assertTrue(wrongReason.isEmpty(), "失败者拿到的不是『已被他人激活』：" + wrongReason);
        assertEquals(1, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM enrollment WHERE activation_code_id="
              + "(SELECT id FROM activation_code WHERE code_hash=?)", Integer.class, vault.hash(code)));
    }

    @Test @Order(15)
    @DisplayName("激活即不退：已绑定的码不可改绑、不可退回未激活（库层强制）")
    void boundCodeIsIrreversible() {
        Long id = jdbc.queryForObject("SELECT id FROM activation_code WHERE status='BOUND' LIMIT 1",
                Number.class).longValue();
        var e1 = assertThrows(Exception.class, () -> jdbc.update(
                "UPDATE activation_code SET bound_user_id=40 WHERE id=?", id));
        assertTrue(e1.getMessage().contains("不可改绑"), e1.getMessage());
        var e2 = assertThrows(Exception.class, () -> jdbc.update(
                "UPDATE activation_code SET status='ISSUED' WHERE id=?", id));
        assertTrue(e2.getMessage().contains("激活即不退"), e2.getMessage());
    }

    @Test @Order(16)
    @DisplayName("整单退款在有码已激活时被库层挡住（钱那一侧的同一条规则）")
    void refundBlockedAfterActivation() {
        Long orderId = jdbc.queryForObject(
                "SELECT order_id FROM activation_code WHERE status='BOUND' LIMIT 1", Number.class).longValue();
        var e = assertThrows(Exception.class, () -> jdbc.update(
                "INSERT INTO refund_record (refund_no,order_id,scope,amount_fen,reason,applied_by) "
              + "VALUES ('RF_IT_1',?,'FULL',349000,'测试整单退',11)", orderId));
        // 上一版这里写了张不存在的列，于是异常来自 SQL 语法而不是那条业务规则 ——
        // 只断言"抛异常"的话它会照样绿，而被测的规则一次都没被执行到
        assertTrue(e.getMessage().contains("激活即不退"),
                "挡住整单退款的不是『激活即不退』那条触发器：" + e.getMessage());
    }

    interface Executable extends org.junit.jupiter.api.function.Executable {}
}
