package tech.dragonai.wangke.trade;

import tech.dragonai.wangke.ItDb;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 订单 → 支付 → 出码 → 结佣 的整链集成测试，跑在真实 MySQL 上。
 *
 * <p>这条链最怕的不是"跑不通"，是"跑通了但重复跑一次就多发一份货"。
 * 微信回调、前端轮询查单、定时对账三条路都调 confirmPaid，同一笔交易必然到达多次，
 * 而且会<b>并发</b>到达。所以本测试的重点全在重放与并发上，
 * 单次成功那条只是前置条件。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FulfillServiceIT {

    static final String DB = "wangke_it";
    static DataSource ds;
    static JdbcTemplate jdbc;
    static FulfillService svc;
    static PricingService pricing;

    @BeforeAll
    static void bootstrap() throws Exception {
        ds = ItDb.freshDatabase(DB);
        jdbc = new JdbcTemplate(ds);
        pricing = new PricingService(jdbc);
        svc = new FulfillService(jdbc, pricing,
                new TransactionTemplate(new DataSourceTransactionManager(ds)),
                new CodeVault(ItDb.testKey(1),
                        "test-salt-not-for-production".getBytes(StandardCharsets.UTF_8)));

        // 一级 A1 与其下级二级 B2
        jdbc.update("INSERT INTO sys_user (id,phone,role) VALUES (10,'13100000010','AGENT'),(11,'13100000011','AGENT')");
        jdbc.update("INSERT INTO agent_profile (id,user_id,agent_code,level,status) VALUES (1,10,'A1',1,'ACTIVE')");
        jdbc.update("INSERT INTO agent_profile (id,user_id,agent_code,level,parent_agent_id,status)"
                  + " VALUES (2,11,'B2',2,1,'ACTIVE')");
    }

    @AfterAll
    static void teardown() { ItDb.close(ds); }

    /** 建一张二级代理买 10 个码的订单（AGENT_10，二级价 3490）。 */
    long newOrder(String orderNo) {
        var price = pricing.priceOf(2, 2);
        jdbc.update("INSERT INTO biz_order (order_no,buyer_user_id,buyer_type,buyer_agent_id,"
                  + "buyer_agent_level,sku_id,sku_code_snapshot,code_qty_snapshot,price_tier_id,"
                  + "amount_fen,expire_at) VALUES (?,11,'AGENT',2,2,2,'AGENT_10',10,?,?,"
                  + "DATE_ADD(NOW(3), INTERVAL 2 HOUR))",
                orderNo, price.tierId(), price.amountFen());
        return jdbc.queryForObject("SELECT id FROM biz_order WHERE order_no=?", Number.class, orderNo).longValue();
    }

    int codesOf(long orderId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM activation_code WHERE order_id=?",
                Integer.class, orderId);
    }

    @Test @Order(1)
    @DisplayName("取价取到当前生效的那一条：二级批量 3490，一级批量 2980")
    void pricing() {
        assertEquals(349000, pricing.priceOf(2, 2).amountFen());
        assertEquals(298000, pricing.priceOf(1, 2).amountFen());
        assertEquals(34900, pricing.priceOf(1, 1).amountFen(), "一级单买应为拍板后的 349");
        assertEquals(39900, pricing.priceOf(2, 1).amountFen());
    }

    @Test @Order(2)
    @DisplayName("元→分：三位小数必须报错，不许四舍五入")
    void yuanToFen() {
        assertEquals(39900, PricingService.yuanToFen("399"));
        assertEquals(39900, PricingService.yuanToFen("399.00"));
        assertEquals(1234, PricingService.yuanToFen("12.34"));
        var e = assertThrows(IllegalArgumentException.class, () -> PricingService.yuanToFen("12.345"));
        assertTrue(e.getMessage().contains("拒绝四舍五入"),
                "静默截断成 12 元不会留下任何日志，这是 schema 头部记的那条应用层合同");
    }

    @Test @Order(3)
    @DisplayName("首次确认支付：出 10 个码、结 1 笔佣金 510 元")
    void firstConfirm() {
        long id = newOrder("IT_ORD_1");
        var r = svc.confirmPaid(id, "WECHAT", "WX_IT_1", 349000, LocalDateTime.now(), null);
        assertTrue(r.firstTime());
        assertEquals(10, r.codesIssued());
        assertEquals(51000, r.commissionFen(), "批量档 51 元/码 × 10");
        assertEquals("FULFILLED", jdbc.queryForObject(
                "SELECT status FROM biz_order WHERE id=?", String.class, id));
        assertEquals(10, codesOf(id));
    }

    @Test @Order(4)
    @DisplayName("同一笔交易重复回调 5 次：码仍是 10 个，佣金仍是 1 笔")
    void replayIsIdempotent() {
        long id = jdbc.queryForObject("SELECT id FROM biz_order WHERE order_no='IT_ORD_1'", Number.class).longValue();
        for (int i = 0; i < 5; i++) {
            var r = svc.confirmPaid(id, "WECHAT", "WX_IT_1", 349000, LocalDateTime.now(), null);
            assertFalse(r.firstTime(), "第 " + (i + 2) + " 次回调被当成首次，会重复发货");
        }
        assertEquals(10, codesOf(id), "重放后码数变了 —— 多发一个就是白送一门课");
        assertEquals(1, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM commission_record WHERE order_id=?", Integer.class, id));
        assertEquals(1, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_record WHERE order_id=?", Integer.class, id));
    }

    @Test @Order(5)
    @DisplayName("12 个线程并发确认同一笔交易：恰好 1 次成功，码恰好 10 个")
    void concurrentConfirmIsIdempotent() throws Exception {
        long id = newOrder("IT_ORD_2");
        int n = 12;
        var pool = Executors.newFixedThreadPool(n);
        var start = new CountDownLatch(1);
        var firsts = new AtomicInteger();
        var errors = new CopyOnWriteArrayList<String>();
        List<Future<?>> fs = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            fs.add(pool.submit(() -> {
                try {
                    start.await();
                    var r = svc.confirmPaid(id, "WECHAT", "WX_IT_2", 349000, LocalDateTime.now(), null);
                    if (r.firstTime()) firsts.incrementAndGet();
                } catch (Exception e) {
                    errors.add(e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }));
        }
        start.countDown();
        for (Future<?> f : fs) f.get(60, TimeUnit.SECONDS);
        pool.shutdown();

        // 先看异常。上一版这里只断言 firsts==1，于是所有线程都抛 ClassCastException 时
        // 报出来的是「幂等键没起到裁决作用」—— 一个完全错误的诊断。
        // 断言的顺序决定了失败信息指向哪里，指错方向会浪费掉一整轮排查。
        assertTrue(errors.isEmpty(), "并发过程中抛了异常，先修异常再谈幂等：" + errors);
        assertEquals(1, firsts.get(),
                "并发下有 " + firsts.get() + " 个连接认为自己是首次 —— 幂等键没起到裁决作用");
        assertEquals(10, codesOf(id), "并发下发了 " + codesOf(id) + " 个码");
        assertEquals(1, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM commission_record WHERE order_id=?", Integer.class, id));
        // 死锁/超时属实现缺陷，不能当"正常竞争"放过
        assertTrue(errors.stream().noneMatch(s -> s.contains("Deadlock")),
                "出现死锁：" + errors);
    }

    @Test @Order(6)
    @DisplayName("实付与应付不符：拒绝入账并整体回滚，不留半截数据")
    void amountMismatchRollsBack() {
        long id = newOrder("IT_ORD_3");
        assertThrows(Exception.class, () ->
                svc.confirmPaid(id, "WECHAT", "WX_IT_3", 100, LocalDateTime.now(), null));
        assertEquals(0, codesOf(id), "金额不符却已经发了码");
        assertEquals(0, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_record WHERE provider_txn_id='WX_IT_3'", Integer.class),
                "支付流水没跟着回滚 —— 事务没生效，下次重试会被幂等键挡住而永远付不进来");
        assertEquals("CREATED", jdbc.queryForObject(
                "SELECT status FROM biz_order WHERE id=?", String.class, id));
    }

    @Test @Order(7)
    @DisplayName("一级代理自己下单：没有上级，不产生佣金")
    void level1BuysNoCommission() {
        var price = pricing.priceOf(1, 2);
        jdbc.update("INSERT INTO biz_order (order_no,buyer_user_id,buyer_type,buyer_agent_id,"
                  + "buyer_agent_level,sku_id,sku_code_snapshot,code_qty_snapshot,amount_fen,expire_at)"
                  + " VALUES ('IT_ORD_4',10,'AGENT',1,1,2,'AGENT_10',10,?,DATE_ADD(NOW(3),INTERVAL 2 HOUR))",
                price.amountFen());
        long id = jdbc.queryForObject("SELECT id FROM biz_order WHERE order_no='IT_ORD_4'", Number.class).longValue();
        var r = svc.confirmPaid(id, "WECHAT", "WX_IT_4", 298000, LocalDateTime.now(), null);
        assertEquals(10, r.codesIssued());
        assertEquals(0, r.commissionFen(), "一级没有上级，却算出了佣金");
    }

    @Test @Order(8)
    @DisplayName("JDBC 链路四字节字符往返无损（写 utf8 会静默吞掉，不会报错）")
    void fourByteCharsSurviveJdbc() {
        jdbc.update("INSERT INTO sys_user (id,phone,role,nickname) VALUES (77,'13100000077','STUDENT',?)",
                "学员🎓📚𠮷");
        String back = jdbc.queryForObject("SELECT nickname FROM sys_user WHERE id=77", String.class);
        assertEquals("学员🎓📚𠮷", back, "四字节字符在 JDBC 链路上被截断了");
    }

    @Test @Order(9)
    @DisplayName("发出的码不落明文：库里只有 hash 与掩码")
    void codesAreNotStoredInPlaintext() {
        var row = jdbc.queryForMap("SELECT code_hash, code_masked FROM activation_code LIMIT 1");
        String hash = (String) row.get("code_hash");
        String masked = (String) row.get("code_masked");
        assertEquals(64, hash.length(), "code_hash 不是 SHA-256 十六进制");
        assertTrue(masked.contains("****"), "掩码没有遮住中间段：" + masked);
        assertTrue(masked.length() <= 19);
    }
}
