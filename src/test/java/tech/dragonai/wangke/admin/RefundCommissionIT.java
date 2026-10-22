package tech.dragonai.wangke.admin;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionTemplate;
import tech.dragonai.wangke.ItDb;
import tech.dragonai.wangke.agent.CommissionSettleService;
import tech.dragonai.wangke.auth.AuthInterceptor;
import tech.dragonai.wangke.trade.*;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/** 退款、激活码作废、佣金净额结算与已结算冲正必须是一条自洽的钱链。 */
class RefundCommissionIT {
    static DataSource ds;
    static JdbcTemplate jdbc;
    static TransactionTemplate tx;
    static OrderService orders;
    static FulfillService fulfill;
    static RefundService refunds;
    static CommissionSettleService commissions;

    @BeforeAll
    static void bootstrap() throws Exception {
        ds = ItDb.freshDatabase("wangke_refund_commission_it");
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        var pricing = new PricingService(jdbc);
        var vault = new CodeVault(ItDb.testKey(63), "refund-offset-salt".getBytes(StandardCharsets.UTF_8));
        orders = new OrderService(jdbc, pricing);
        fulfill = new FulfillService(jdbc, pricing, tx, vault);
        refunds = new RefundService(jdbc, tx, new AdminAudit(jdbc));
        commissions = new CommissionSettleService(jdbc, tx);

        jdbc.update("INSERT INTO sys_user (id,phone,role) VALUES "
                  + "(90,'13500000090','AGENT'),(91,'13500000091','AGENT')");
        jdbc.update("INSERT INTO agent_profile (id,user_id,agent_code,level,status)"
                  + " VALUES (90,90,'RC90',1,'ACTIVE')");
        jdbc.update("INSERT INTO agent_profile (id,user_id,agent_code,level,parent_agent_id,status)"
                  + " VALUES (91,91,'RC91',2,90,'ACTIVE')");
        jdbc.update("INSERT INTO agent_balance (agent_id) VALUES (90)");
    }

    @AfterAll
    static void teardown() { ItDb.close(ds); }

    private static MockHttpServletRequest admin() {
        var r = new MockHttpServletRequest();
        r.setAttribute(AuthInterceptor.ATTR_USER_ID, 1L);
        r.setAttribute(AuthInterceptor.ATTR_ROLE, "PLATFORM_ADMIN");
        r.setRemoteAddr("127.0.0.1");
        return r;
    }

    private static long paidOrder(String txn) {
        var o = orders.create(91L, 2L, "127.0.0.1");
        fulfill.confirmPaid(o.orderId(), "WECHAT", txn, 349000L, LocalDateTime.now(), 1L);
        return o.orderId();
    }

    @Test
    void partialRefundSettlesNetAndFullRefundOffsetsSettledBalance() {
        long partialOrder = paidOrder("WX_RC_PARTIAL_PAY");
        var partial = refunds.apply(admin(), partialOrder, 100000L,
                "UNUSED_CODES", "退未激活席位");
        long partialId = ((Number) partial.get("refundId")).longValue();
        refunds.approve(admin(), partialId);
        var partialDone = refunds.settle(admin(), partialId, "WX_RC_PARTIAL_OUT");

        assertEquals(3, ((Number) partialDone.get("codesRevoked")).intValue());
        assertEquals(14613L, jdbc.queryForObject(
                "SELECT reversed_fen FROM commission_record WHERE order_id=?",
                Long.class, partialOrder));

        jdbc.update("UPDATE commission_record SET freeze_until=DATE_SUB(NOW(3),INTERVAL 1 DAY)"
                  + " WHERE order_id=?", partialOrder);
        commissions.settleDue();
        assertEquals(36387L, jdbc.queryForObject(
                "SELECT commission_balance_fen FROM agent_balance WHERE agent_id=90", Long.class),
                "部分退款后的佣金只应按净额入账");

        long fullOrder = paidOrder("WX_RC_FULL_PAY");
        jdbc.update("UPDATE commission_record SET freeze_until=DATE_SUB(NOW(3),INTERVAL 1 DAY)"
                  + " WHERE order_id=?", fullOrder);
        commissions.settleDue();
        assertEquals(87387L, jdbc.queryForObject(
                "SELECT commission_balance_fen FROM agent_balance WHERE agent_id=90", Long.class));

        var full = refunds.apply(admin(), fullOrder, 349000L, "FULL", "整单退款");
        long fullId = ((Number) full.get("refundId")).longValue();
        refunds.approve(admin(), fullId);
        var fullDone = refunds.settle(admin(), fullId, "WX_RC_FULL_OUT");

        assertEquals(10, ((Number) fullDone.get("codesRevoked")).intValue());
        assertEquals(36387L, jdbc.queryForObject(
                "SELECT commission_balance_fen FROM agent_balance WHERE agent_id=90", Long.class),
                "已结算佣金没有随整单退款自动扣回");
        assertEquals("REVOKED", jdbc.queryForObject(
                "SELECT status FROM commission_record WHERE order_id=?", String.class, fullOrder));
        assertEquals(51000L, jdbc.queryForObject(
                "SELECT reversed_fen FROM commission_record WHERE order_id=?", Long.class, fullOrder));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM commission_ledger WHERE agent_id=90"
              + " AND biz_type='REFUND_OFFSET'", Integer.class));
    }
}
