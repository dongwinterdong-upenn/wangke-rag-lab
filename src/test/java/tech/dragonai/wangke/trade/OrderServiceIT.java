package tech.dragonai.wangke.trade;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tech.dragonai.wangke.ItDb;
import tech.dragonai.wangke.learn.ActivationService;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 下单，以及<b>从下单一路走到学员看得见课</b>的整链。
 *
 * <p>各段分别绿过不等于整链通：段与段之间靠的是快照字段（价、码数、层级），
 * 而快照恰恰是最容易在某一段被算对、在下一段被读错的东西。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OrderServiceIT {

    static final String DB = "wangke_ord_it";
    static DataSource ds;
    static JdbcTemplate jdbc;
    static OrderService orders;
    static FulfillService fulfill;
    static CodeService codes;
    static ActivationService act;
    static CodeVault vault;

    @BeforeAll
    static void bootstrap() throws Exception {
        ds = ItDb.freshDatabase(DB);
        jdbc = new JdbcTemplate(ds);
        var pricing = new PricingService(jdbc);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        vault = new CodeVault(ItDb.testKey(11), "ord-salt".getBytes(StandardCharsets.UTF_8));
        orders = new OrderService(jdbc, pricing);
        fulfill = new FulfillService(jdbc, pricing, tx, vault);
        codes = new CodeService(jdbc, vault);
        act = new ActivationService(jdbc, tx, vault);

        jdbc.update("INSERT INTO sys_user (id,phone,role) VALUES "
                  + "(10,'13100000010','AGENT'),(11,'13100000011','AGENT'),"
                  + "(12,'13100000012','AGENT'),(13,'13100000013','AGENT'),"
                  + "(20,'13200000020','STUDENT'),(21,'13200000021','STUDENT')");
        jdbc.update("INSERT INTO sys_user (id,phone,role,status) VALUES (14,'13100000014','AGENT','FROZEN')");
        jdbc.update("INSERT INTO agent_profile (id,user_id,agent_code,level,status) VALUES (1,10,'A1',1,'ACTIVE')");
        jdbc.update("INSERT INTO agent_profile (id,user_id,agent_code,level,parent_agent_id,status)"
                  + " VALUES (2,11,'B2',2,1,'ACTIVE')");
        jdbc.update("INSERT INTO agent_profile (id,user_id,agent_code,level,parent_agent_id,status)"
                  + " VALUES (3,12,'B3',2,1,'PENDING')");
        jdbc.update("INSERT INTO agent_profile (id,user_id,agent_code,level,parent_agent_id,status)"
                  + " VALUES (4,13,'B4',2,1,'SUSPENDED')");
    }

    @AfterAll
    static void teardown() { ItDb.close(ds); }

    @Test @Order(1)
    @DisplayName("二级代理下批量单：金额 3490、10 个码、取价来源落在订单上")
    void agentBulkOrder() {
        var o = orders.create(11, 2, "1.2.3.4");
        assertEquals(349000, o.amountFen());
        assertEquals(10, o.codeQty());
        assertEquals(2, o.buyerLevel());
        assertEquals(2L, o.buyerAgentId());

        var row = jdbc.queryForMap("SELECT * FROM biz_order WHERE id=?", o.orderId());
        assertEquals("AGENT_10", row.get("sku_code_snapshot"));
        assertNotNull(row.get("price_tier_id"), "没记下取价来源，事后无法复盘这个金额哪来的");
        assertEquals("CREATED", row.get("status"));
        assertEquals("1.2.3.4", row.get("client_ip"));
    }

    @Test @Order(2)
    @DisplayName("一级代理拿一级价：同一个套餐，价随身份走")
    void level1GetsLevel1Price() {
        assertEquals(298000, orders.create(10, 2, "1.1.1.1").amountFen());
        assertEquals(34900, orders.create(10, 1, "1.1.1.1").amountFen());
        assertEquals(39900, orders.create(11, 1, "1.1.1.1").amountFen());
    }

    @Test @Order(3)
    @DisplayName("待审核 / 停用的代理不能按代理价进货")
    void inactiveAgentsCannotBuyAgentSku() {
        var e1 = assertThrows(IllegalStateException.class, () -> orders.create(12, 2, "1"));
        assertTrue(e1.getMessage().contains("已生效的代理商"), e1.getMessage());
        var e2 = assertThrows(IllegalStateException.class, () -> orders.create(13, 2, "1"));
        assertTrue(e2.getMessage().contains("已生效的代理商"), e2.getMessage());
        // 普通学员更不行
        assertThrows(IllegalStateException.class, () -> orders.create(20, 2, "1"));
    }

    @Test @Order(4)
    @DisplayName("学员直购 799：不带代理身份，归属代理落到订单上")
    void studentDirectOrder() {
        jdbc.update("UPDATE sys_user SET attributed_agent_id=2, attributed_at=NOW(3), "
                  + "attribution_source='REF_LINK' WHERE id=21");
        var o = orders.create(21, 3, "9.9.9.9");
        assertEquals(79900, o.amountFen());
        assertEquals(0, o.buyerLevel());
        assertNull(o.buyerAgentId());

        var row = jdbc.queryForMap("SELECT * FROM biz_order WHERE id=?", o.orderId());
        assertEquals("STUDENT", row.get("buyer_type"));
        assertNull(row.get("buyer_agent_id"));
        assertNull(row.get("buyer_agent_level"));
        assertEquals(2L, ((Number) row.get("attributed_agent_id")).longValue(),
                "学员直购没把归属代理记到订单上，这单事后算不清是谁带来的");
    }

    @Test @Order(5)
    @DisplayName("代理按学员价买：走学员档，不得串到代理层级去")
    void agentBuyingStudentSkuIsNotAnAgentOrder() {
        var o = orders.create(11, 3, "1");
        assertEquals(79900, o.amountFen());
        assertEquals(0, o.buyerLevel(), "按学员价买却记成了代理单，佣金会跟着算错");
        assertNull(jdbc.queryForMap("SELECT buyer_agent_level FROM biz_order WHERE id=?",
                o.orderId()).get("buyer_agent_level"));
    }

    @Test @Order(6)
    @DisplayName("下架套餐、不存在的套餐、冻结账号 —— 一律拒")
    void guards() {
        jdbc.update("UPDATE sku SET status='OFF' WHERE id=1");
        var e = assertThrows(IllegalStateException.class, () -> orders.create(11, 1, "1"));
        assertTrue(e.getMessage().contains("已下架"), e.getMessage());
        jdbc.update("UPDATE sku SET status='ON' WHERE id=1");

        assertThrows(IllegalArgumentException.class, () -> orders.create(11, 999, "1"));
        assertThrows(IllegalStateException.class, () -> orders.create(14, 2, "1"));
        assertThrows(IllegalArgumentException.class, () -> orders.create(9999, 2, "1"));
    }

    @Test @Order(7)
    @DisplayName("单号不可预测：不重复、不连号")
    void orderNumbersAreUnguessable() {
        var seen = new HashSet<String>();
        String prev = null;
        int adjacent = 0;
        for (int i = 0; i < 60; i++) {
            String no = orders.create(11, 1, "1").orderNo();
            assertTrue(no.startsWith("WK"), no);
            assertEquals(20, no.length(), no);
            assertTrue(seen.add(no), "单号重复：" + no);
            if (prev != null && Long.parseLong(no.substring(10))
                    - Long.parseLong(prev.substring(10)) == 1) adjacent++;
            prev = no;
        }
        assertEquals(0, adjacent,
                "出现了连号 —— 顺序单号会把当日成交量直接送给任何一个下过单的人");
    }

    // ---------------------------------------------------------------- 整链

    @Test @Order(8)
    @DisplayName("整链：下单 → 支付 → 出码 → 交付 → 学员激活 → 拿到 24 个月权益")
    void fullChain() {
        var o = orders.create(11, 2, "1.2.3.4");                     // 二级代理进 10 个码
        var paid = fulfill.confirmPaid(o.orderId(), "WECHAT", "WX_CHAIN_1",
                o.amountFen(), LocalDateTime.now(), null);
        assertEquals(10, paid.codesIssued());
        assertEquals(51000, paid.commissionFen(), "一级上级的佣金应为 51/码 × 10");

        var mine = codes.list(2, o.orderId());
        assertEquals(10, mine.size());
        String plain = codes.reveal(11, 2, mine.get(0).id(), "1.2.3.4");

        var r = act.redeem(20, plain, "v2026-08-11", "5.6.7.8");
        assertTrue(r.firstTime());
        assertEquals(2L, r.attributedAgentId());

        var en = jdbc.queryForMap("SELECT * FROM enrollment WHERE id=?", r.enrollmentId());
        assertEquals("ACTIVE", en.get("status"));
        assertEquals(((LocalDateTime) en.get("valid_from")).plusMonths(24), en.get("valid_until"));

        // 平台净收与"谁来买"无关，这条恒等式必须在真实成交上也成立
        long platformNet = o.amountFen() - paid.commissionFen();
        assertEquals(298000, platformNet,
                "二级付 3490 减佣金 510 应当正好等于一级批量价 2980；实际净收 " + platformNet);
    }

    @Test @Order(9)
    @DisplayName("金额来自订单快照而不是现价：改价之后，老订单仍按老价成交")
    void priceChangeDoesNotMoveExistingOrders() {
        var o = orders.create(11, 2, "1.2.3.4");
        long snapshot = o.amountFen();

        // 按 schema 约定的改价方式：关旧行、开新行，不 UPDATE 旧行
        jdbc.update("UPDATE agent_price_tier SET effective_to=NOW(3) "
                  + "WHERE agent_level=2 AND sku_id=2 AND effective_to IS NULL");
        jdbc.update("INSERT INTO agent_price_tier (agent_level,sku_id,total_price_fen,"
                  + "effective_from,created_by) VALUES (2,2,399000,NOW(3),1)");
        try {
            assertEquals(399000, new PricingService(jdbc).priceOf(2, 2).amountFen(), "新价没生效");
            // 老单仍按快照价：按新价付款必须被拒
            assertThrows(Exception.class, () -> fulfill.confirmPaid(o.orderId(), "WECHAT",
                    "WX_CHAIN_2", 399000, LocalDateTime.now(), null));
            var ok = fulfill.confirmPaid(o.orderId(), "WECHAT", "WX_CHAIN_3",
                    snapshot, LocalDateTime.now(), null);
            assertTrue(ok.firstTime());
            assertEquals(10, ok.codesIssued());
        } finally {
            jdbc.update("DELETE FROM agent_price_tier WHERE total_price_fen=399000 AND sku_id=2");
            jdbc.update("UPDATE agent_price_tier SET effective_to=NULL "
                      + "WHERE agent_level=2 AND sku_id=2 AND total_price_fen=349000");
        }
    }
}
