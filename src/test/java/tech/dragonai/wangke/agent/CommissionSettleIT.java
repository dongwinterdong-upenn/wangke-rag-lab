package tech.dragonai.wangke.agent;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tech.dragonai.wangke.ItDb;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 佣金冻结期满 → 转可提现，这条链的判据。
 *
 * <p>它测的是一条<b>合并前根本不存在</b>的路径：{@code commission_record} 有人写、
 * {@code agent_balance} 没人加、提现闸门读的却是后者。所以这里的每一条都不是
 * 「防止将来改坏」，而是「先证明它现在真的通了」。
 *
 * <p>最重要的一条是并发那个：结算的余额算术要求
 * {@code balance_after = balance_before + amount}，而 before 取自余额快照那一行。
 * 不加行锁的话两笔并发会读到同一个 before —— <b>每一行台账都自洽、库层约束全过、
 * 一个异常都不抛</b>，只是快照少加了一笔。这是这段代码唯一会「安静地算错钱」的地方，
 * 所以它必须有一条能红的判据，而不只是有一段注释。
 */
class CommissionSettleIT {

    static DataSource ds;
    static JdbcTemplate jdbc;
    static TransactionTemplate tx;
    static CommissionSettleService svc;

    @BeforeAll
    static void boot() throws Exception {
        ds = ItDb.freshDatabase("wangke_settle_it");
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        svc = new CommissionSettleService(jdbc, tx);
    }

    @AfterAll
    static void down() { ItDb.close(ds); }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM commission_ledger");
        jdbc.update("DELETE FROM agent_balance");
        jdbc.update("DELETE FROM commission_record");
    }

    /* ------------------------------------------------------------- 夹具 */

    /** 造一笔佣金。freezeHours 为负数表示冻结期已满。 */
    private long commission(long agentId, long amountFen, int freezeHours) {
        String no = "T" + System.nanoTime() % 1_000_000_000L;
        jdbc.update("INSERT INTO commission_record (beneficiary_agent_id,order_id,order_no,"
                  + "rule_id,calc_mode_snapshot,calc_basis_fen,amount_fen,status,freeze_until) "
                  + "VALUES (?,?,?,?,?,?,?,'PENDING',DATE_ADD(NOW(3), INTERVAL ? HOUR))",
                agentId, Math.abs(no.hashCode()), no, 1, "FIXED", amountFen * 10, amountFen,
                freezeHours);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long balance(long agentId) {
        List<Long> v = jdbc.queryForList(
                "SELECT commission_balance_fen FROM agent_balance WHERE agent_id=?",
                Long.class, agentId);
        return v.isEmpty() ? -1 : v.get(0);
    }

    /* ------------------------------------------------------------- 判据 */

    @Test
    @DisplayName("冻结期满的佣金转 SETTLED，同时记台账并加余额（三处必须一致）")
    void settlesDueCommission() {
        long id = commission(1001, 51000, -1);

        var r = svc.settleDue();

        assertEquals(1, r.settled(), "应结算 1 笔");
        assertEquals(51000, r.amountFen());
        assertEquals("SETTLED", jdbc.queryForObject(
                "SELECT status FROM commission_record WHERE id=?", String.class, id));
        assertNotNull(jdbc.queryForObject(
                "SELECT settled_at FROM commission_record WHERE id=?", LocalDateTime.class, id));
        assertEquals(51000, balance(1001), "余额快照");
        assertEquals(51000, (long) jdbc.queryForObject(
                "SELECT total_settled_fen FROM agent_balance WHERE agent_id=?", Long.class, 1001));

        // 台账那一行的算术必须自洽 —— ck_comm_ledger_arith 已经在库层挡了，
        // 这里再断一次是为了钉住方向（IN 不是 OUT）与 ref_no 的取值。
        var led = jdbc.queryForMap("SELECT * FROM commission_ledger WHERE agent_id=?", 1001);
        assertEquals("SETTLE", led.get("biz_type"));
        assertEquals("IN", led.get("direction"));
        assertEquals(String.valueOf(id), led.get("ref_no"));
        assertEquals(0L, ((Number) led.get("balance_before")).longValue());
        assertEquals(51000L, ((Number) led.get("balance_after")).longValue());
    }

    @Test
    @DisplayName("冻结期未满的一分钱都不许动")
    void leavesFrozenAlone() {
        long id = commission(1002, 30000, +72);

        var r = svc.settleDue();

        assertEquals(0, r.settled());
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM commission_record WHERE id=?", String.class, id));
        assertEquals(-1, balance(1002), "余额行都不该被建出来");
    }

    @Test
    @DisplayName("★ 净额被退款冲到 0 的那一笔，不许把整轮结算掀翻")
    void fullyReversedCommissionDoesNotKillTheRound() {
        // 部分退款可以把某一笔佣金冲到净额 0，而 RefundService 只在【整单】退完时
        // 才置 REVOKED —— 所以这一笔会一直以 PENDING + reversed_fen==amount_fen 躺着。
        long zeroed = commission(1010, 40000, -1);
        jdbc.update("UPDATE commission_record SET reversed_fen=amount_fen WHERE id=?", zeroed);

        // 关键在于它的 id 比后面这笔小：批查询是 ORDER BY id LIMIT，
        // 它排在前面。修复之前，它会在 commission_ledger 的
        // ck_comm_ledger_amount CHECK(amount_fen > 0) 上抛 DataIntegrityViolationException，
        // 而 settleOne 只接住并发类异常 —— 整轮连同后面这笔健康佣金一起被掀掉，
        // 下一轮 10 分钟后又从同一行开始，永久卡死。对外零症状。
        long healthy = commission(1010, 51000, -1);
        assertTrue(zeroed < healthy, "夹具前提：被冲平的那笔必须排在前面");

        var r = svc.settleDue();

        // ① 整轮没被掀翻：后面那笔健康佣金照常入账
        assertEquals(1, r.settled(), "健康的那笔必须结算成功");
        assertEquals(51000, r.amountFen());
        assertEquals(51000, balance(1010), "余额只该加健康那笔");

        // ② 被冲平的那笔终结成 REVOKED，不再被下一轮捞起来
        assertEquals(1, r.revoked(), "被冲平的那笔应记为终结");
        assertEquals("REVOKED", jdbc.queryForObject(
                "SELECT status FROM commission_record WHERE id=?", String.class, zeroed));
        assertNotNull(jdbc.queryForObject(
                "SELECT revoke_reason FROM commission_record WHERE id=?", String.class, zeroed),
                "终结必须留原因，否则对账时没人知道这笔为什么没进余额");

        // ③ 不许给它记任何台账 —— 记 0 是违反 CHECK 的，记非 0 是凭空造钱
        assertEquals(0, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM commission_ledger WHERE ref_no=?", Integer.class,
                String.valueOf(zeroed)));

        // ④ 再跑一轮必须是干净的：说明它真的从 PENDING 里出去了
        var r2 = svc.settleDue();
        assertEquals(0, r2.settled());
        assertEquals(0, r2.revoked());
        assertEquals(51000, balance(1010), "重跑不许改余额");
    }

    @Test
    @DisplayName("已撤销（订单退款）的佣金不会被结算掉")
    void skipsRevoked() {
        long id = commission(1003, 20000, -1);
        jdbc.update("UPDATE commission_record SET status='REVOKED', revoked_at=NOW(3), "
                  + "revoke_reason='订单退款' WHERE id=?", id);

        assertEquals(0, svc.settleDue().settled());
        assertEquals(-1, balance(1003));
    }

    @Test
    @DisplayName("重复跑不会重复入账（幂等靠唯一键，不靠先查后写）")
    void idempotent() {
        commission(1004, 12345, -1);

        svc.settleDue();
        long after1 = balance(1004);
        var r2 = svc.settleDue();          // 再跑一轮

        assertEquals(0, r2.settled(), "第二轮不该再结算");
        assertEquals(after1, balance(1004), "余额不许变");
        assertEquals(1, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM commission_ledger WHERE agent_id=?", Integer.class, 1004));
    }

    @Test
    @DisplayName("台账已存在但状态没推进（上一轮记完账就挂了）→ 补状态，绝不重复加钱")
    void recoversHalfDoneWithoutDoublePaying() {
        long id = commission(1005, 60000, -1);
        // 手工模拟「钱记了、状态没改」这个中间态
        jdbc.update("INSERT INTO agent_balance (agent_id,commission_balance_fen,total_settled_fen) "
                  + "VALUES (?,?,?)", 1005, 60000, 60000);
        jdbc.update("INSERT INTO commission_ledger (agent_id,biz_type,ref_no,direction,"
                  + "amount_fen,balance_before,balance_after) VALUES (?,'SETTLE',?,'IN',?,0,?)",
                1005, String.valueOf(id), 60000, 60000);

        svc.settleDue();

        assertEquals("SETTLED", jdbc.queryForObject(
                "SELECT status FROM commission_record WHERE id=?", String.class, id),
                "状态要被补上");
        assertEquals(60000, balance(1005), "钱绝不能加第二遍");
        assertEquals(1, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM commission_ledger WHERE agent_id=?", Integer.class, 1005));
    }

    @Test
    @DisplayName("★ 同一代理的多笔佣金并发结算，余额必须等于总和（量的是那把行锁）")
    void concurrentSettlementKeepsBalanceExact() throws Exception {
        final int N = 12;
        final long each = 10000;
        for (int i = 0; i < N; i++) commission(1006, each, -1);

        // 12 个线程各自只处理一笔：把「读 before → 写 after」的窗口撞在一起。
        // 走 settleDue() 的话单线程顺序处理，这个窗口根本不会重叠，测不到锁。
        List<java.util.Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, amount_fen FROM commission_record WHERE beneficiary_agent_id=1006");
        assertEquals(N, rows.size());

        var pool = Executors.newFixedThreadPool(N);
        var gate = new CountDownLatch(1);
        var done = new CountDownLatch(N);
        // ★ 线程里的异常必须收集起来断言。
        //   第一版这里写的是 catch (Exception ignored) {} —— 而实测正是死锁：
        //   12 笔里 7 笔是被 MySQL 选中的牺牲者，全被这个 catch 吞掉，
        //   于是"钱少了"这件事只能靠余额那条断言间接暴露，根因一个字都看不到。
        //   吞掉异常的并发测试，失败时和"业务逻辑算错"长得一模一样。
        var errors = new CopyOnWriteArrayList<Throwable>();
        for (var row : rows) {
            long id = ((Number) row.get("id")).longValue();
            pool.submit(() -> {
                try {
                    gate.await();
                    // 反射调私有的单笔入口，确保并发点落在余额算术上
                    var m = CommissionSettleService.class.getDeclaredMethod(
                            "settleOne", long.class, long.class, long.class, String.class);
                    m.setAccessible(true);
                    m.invoke(svc, id, 1006L, each, "concurrent");
                } catch (Throwable t) {
                    errors.add(t.getCause() == null ? t : t.getCause());
                } finally {
                    done.countDown();
                }
            });
        }
        gate.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "并发结算超时");
        pool.shutdownNow();

        assertTrue(errors.isEmpty(),
                "并发结算不该有任何一笔抛异常（重试应当把死锁吸收掉），实际 "
              + errors.size() + " 笔失败，第一条：" + (errors.isEmpty() ? "" : errors.get(0)));

        long expect = N * each;
        assertEquals(expect, balance(1006),
                "余额快照必须等于 " + N + " 笔之和 —— 少了就说明 FOR UPDATE 没起作用，"
              + "两笔读到了同一个 balance_before");
        assertEquals(expect, (long) jdbc.queryForObject(
                "SELECT SUM(amount_fen) FROM commission_ledger WHERE agent_id=1006", Long.class));
        assertEquals(0, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM commission_record "
              + "WHERE beneficiary_agent_id=1006 AND status<>'SETTLED'", Integer.class));
    }

    @Test
    @DisplayName("结算完的余额能过提现闸门（这条链的终点）")
    void settledBalanceOpensWithdrawGate() {
        // 提现闸门读的就是 agent_balance，之前它恒为空 → 提现永远走不到闸门
        commission(1007, 5_000_00, -1);
        svc.settleDue();

        assertEquals(500000, balance(1007));
        // 闸门要求 ≥ 100 元且不超可用余额；这里只证明「余额确实到位了」，
        // 提现本身的判据在 AgentIT 里
        assertTrue(balance(1007) >= 10000, "已达提现门槛");
    }
}
