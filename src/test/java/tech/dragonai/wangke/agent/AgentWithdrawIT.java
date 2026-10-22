package tech.dragonai.wangke.agent;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionTemplate;
import tech.dragonai.wangke.ItDb;
import tech.dragonai.wangke.admin.AdminAudit;
import tech.dragonai.wangke.auth.AuthInterceptor;
import tech.dragonai.wangke.trade.CodeService;
import tech.dragonai.wangke.trade.CodeVault;
import tech.dragonai.wangke.web.ApiException;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 代理自助撤销提现单 + 提现列表返回形状。跑在真实 MySQL 上，因为这一块最要命的一条规则
 * <b>不在</b>库里：{@code trg_withdraw_state} 对 NEW='CANCELLED' 一个字都没写，
 * APPROVED→CANCELLED、PAYING→CANCELLED 库里全放行。也就是说
 * "只有 APPLIED 能撤" 这道闸<b>只在应用层</b>，它塌了不会有任何数据库报错。
 * 所以这里有一条用例直接对库 UPDATE，证明库确实放行 —— 用来钉住"这是唯一防线"。
 *
 * <p>纪律同 AgentIT：每个负例都要钉住失败原因（状态码 + 文案），并且<b>断言库里那行没被改</b>。
 * 只断言"抛了异常"的用例太容易因为别的原因红/绿，绿的时候什么也没证明。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AgentWithdrawIT {

    static final String DB = "wangke_agent_withdraw_it";

    static DataSource ds;
    static JdbcTemplate jdbc;
    static TransactionTemplate tx;
    static AgentService agents;
    static WithdrawService withdraws;
    static WithdrawController api;                              // 返回体的键要在控制器这一层验
    static tech.dragonai.wangke.admin.WithdrawService adminWithdraws;

    /** 甲（我）、乙（别人）、丙（有单但没有佣金账户） */
    static final long A_ME = 1, A_OTHER = 2, A_NOBAL = 3;
    static final long U_ME = 10, U_OTHER = 11, U_NOBAL = 12, U_ADMIN = 9;

    /** 起始佣金余额：1 万元。单笔申请 200 元，够全部用例各自开单不互相饿死。 */
    static final long START_BALANCE = 1_000_000L;
    static final long AMOUNT = 20_000L;

    @BeforeAll
    static void bootstrap() throws Exception {
        ds = ItDb.freshDatabase(DB);
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        var vault = new CodeVault(ItDb.testKey(11), "wd-salt".getBytes(StandardCharsets.UTF_8));

        agents = new AgentService(jdbc, tx, new CodeService(jdbc, vault));
        withdraws = new WithdrawService(jdbc, tx, agents);
        api = new WithdrawController(withdraws);
        adminWithdraws = new tech.dragonai.wangke.admin.WithdrawService(jdbc, tx, new AdminAudit(jdbc));

        jdbc.update("INSERT INTO sys_user (id,phone,role,real_name) VALUES (9,'13900000009','PLATFORM_ADMIN','平台管理员')");
        jdbc.update("INSERT INTO sys_user (id,phone,role,real_name) VALUES"
                  + " (10,'13100000010','AGENT','甲一级'),(11,'13100000011','AGENT','乙一级'),"
                  + " (12,'13100000012','AGENT','丙一级')");
        jdbc.update("INSERT INTO agent_profile (id,user_id,agent_code,level,status,subject_type,"
                  + "settle_bank_name,settle_bank_acct) VALUES"
                  + " (1,10,'AGWD00000001',1,'ACTIVE','INDIVIDUAL','招商银行','6225880000001111'),"
                  + " (2,11,'AGWD00000002',1,'ACTIVE','INDIVIDUAL','招商银行','6225880000002222'),"
                  + " (3,12,'AGWD00000003',1,'ACTIVE','INDIVIDUAL','招商银行','6225880000003333')");
        for (long a : new long[]{A_ME, A_OTHER, A_NOBAL})
            jdbc.update("INSERT INTO agent_balance (agent_id,commission_balance_fen,total_settled_fen)"
                      + " VALUES (?,?,?)", a, START_BALANCE, START_BALANCE);
    }

    @AfterAll
    static void teardown() { ItDb.close(ds); }

    // ================================================================ 撤销

    @Test @Order(1)
    @DisplayName("撤销成功：单子进 CANCELLED、余额三列逐列未变、在途归零、可提额度复原")
    void cancelReleasesQuotaWithoutTouchingBalance() {
        long id = withdraws.apply(U_ME, AMOUNT, "BANK_TRANSFER", "1.1.1.1").id();

        var before = balanceRow(A_ME);
        assertEquals(AMOUNT, agents.inflightWithdrawFen(A_ME), "申请后这一单必须占住额度");
        assertEquals(START_BALANCE - AMOUNT, agents.balance(U_ME).get("withdrawableFen"));

        var out = api.cancel(id, as(U_ME));

        assertEquals(true, out.get("ok"));
        assertEquals(true, out.get("changed"), "第一次撤销必须是真的改了状态");
        assertEquals(id, out.get("id"));
        assertEquals("CANCELLED", out.get("status"));
        assertEquals("已取消", out.get("statusText"));
        assertEquals(AMOUNT, out.get("amountFen"));
        assertTrue(String.valueOf(out.get("withdrawNo")).startsWith("WD"), "单号没回显，用户没法对账");
        assertEquals(true, out.get("withdrawableKnown"));
        assertEquals(START_BALANCE, out.get("withdrawableFen"),
                "撤销后可提额度没复原 —— 前端那句「这笔钱立刻回到可申请额度」就是假的");
        assertFalse(out.containsKey("withdrawableUnknownReason"), "算得出来就不该有「算不出来的理由」");
        assertEquals("已撤销，这笔钱回到可申请额度里了", out.get("message"));

        assertEquals("CANCELLED", statusOf(id));

        // 钱的不变量：撤销全程不碰余额。余额本来就只在打款成功时才扣，
        // 谁在这里"把钱退回余额"，就是凭空多造一笔可提金额。
        var after = balanceRow(A_ME);
        assertEquals(before.get("commission_balance_fen"), after.get("commission_balance_fen"),
                "撤销动了佣金余额 —— 这是凭空多造钱");
        assertEquals(before.get("total_settled_fen"), after.get("total_settled_fen"));
        assertEquals(before.get("total_withdrawn_fen"), after.get("total_withdrawn_fen"));
        assertEquals(0, countLedger(A_ME), "撤销写了佣金台账 —— 没有钱在动，不该有流水");

        assertEquals(0L, agents.inflightWithdrawFen(A_ME), "撤销后这单还在在途集合里");
        assertEquals(START_BALANCE, agents.balance(U_ME).get("withdrawableFen"),
                "/balance 与撤销返回的可提额度对不上，就是两本账");

        assertEquals(1, cancelAudits(id), "撤销没留痕：「我撤了」与「平台把我的单弄没了」在库里就长得一样了");
    }

    @Test @Order(2)
    @DisplayName("撤销别人的单：404「提现单不存在」，且那行状态一个字节都没动")
    void cannotCancelSomeoneElsesOrder() {
        long victim = withdraws.apply(U_OTHER, AMOUNT, "BANK_TRANSFER", "2.2.2.2").id();

        var e = assertThrows(ApiException.class, () -> api.cancel(victim, as(U_ME)));
        assertEquals(404, e.status(), "别人的单给了 404 之外的状态码 —— 这就是一个存在性探针："
                + e.status() + " / " + e.getMessage());
        assertEquals("提现单不存在", e.getMessage(),
                "别人的单与不存在的单必须给同一句话，否则顺着 id 枚举一遍就知道平台发了多少单");

        // 只断言抛异常不够：要钉住"没被改"。少写 WHERE 里那个 agent_id 的话，
        // 这条 UPDATE 会成功、不会有任何异常与报警，受害人只看到单子莫名其妙没了。
        assertEquals("APPLIED", statusOf(victim), "别人的提现单被撤掉了");
        assertEquals(0, cancelAudits(victim));
        assertEquals(AMOUNT, agents.inflightWithdrawFen(A_OTHER), "受害人的额度被放了出去");
    }

    @Test @Order(3)
    @DisplayName("非 APPLIED 不能撤：409 且带当前状态；同时验库里那道闸【不拦】，应用层是唯一防线")
    void onlyAppliedCanBeCancelled() {
        long id = firstAppliedOf(A_OTHER);                 // 上一条用例申请的那张
        jdbc.update("UPDATE withdraw_order SET status='REVIEWING' WHERE id=? AND status='APPLIED'", id);

        var e = assertThrows(ApiException.class, () -> api.cancel(id, as(U_OTHER)));
        assertEquals(409, e.status(), "平台已受理的单被拒时给的不是 409：" + e.getMessage());
        assertTrue(e.getMessage().contains("审核中"),
                "没把当前状态说清，用户只会反复点：" + e.getMessage());
        assertTrue(e.getMessage().contains("联系平台"), e.getMessage());
        assertEquals("REVIEWING", statusOf(id), "被拒了，但状态还是被改掉了");
        assertEquals(0, cancelAudits(id), "没撤成却写了撤销审计");

        // APPROVED 同样撤不动（PAYING 在库里也放行，撤了钱可能已经在路上，额度却回吐）
        jdbc.update("UPDATE withdraw_order SET status='APPROVED' WHERE id=?", id);
        var e2 = assertThrows(ApiException.class, () -> api.cancel(id, as(U_OTHER)));
        assertEquals(409, e2.status());
        assertTrue(e2.getMessage().contains("审核通过，待打款"), e2.getMessage());
        assertEquals("APPROVED", statusOf(id));

        // ★ 阴性对照：直接对库 UPDATE，绕过应用层。这一条【必须成功】——
        // 它证明 trg_withdraw_state 对 NEW='CANCELLED' 根本没有意见，
        // 所以上面那道 status IN ('APPLIED') 是唯一挡得住 APPROVED/PAYING→CANCELLED 的东西。
        // 哪天有人把它删掉，库不会报错、不会有任何信号，只有这条用例会红。
        int raw = jdbc.update("UPDATE withdraw_order SET status='CANCELLED' WHERE id=?", id);
        assertEquals(1, raw, "库里如果拦住了 APPROVED→CANCELLED，这条注释与整段判断就该重写");
        assertEquals("CANCELLED", statusOf(id));
    }

    @Test @Order(4)
    @DisplayName("重复撤销：第二次 changed=false 不报错，审计只留一条")
    void repeatedCancelIsIdempotent() {
        long id = withdraws.apply(U_ME, AMOUNT, "BANK_TRANSFER", "1.1.1.1").id();

        var first = api.cancel(id, as(U_ME));
        assertEquals(true, first.get("changed"));

        // 两个标签页各点一次、或前端去重没兜住时，第二次不该报错吓人
        var second = api.cancel(id, as(U_ME));
        assertEquals(true, second.get("ok"));
        assertEquals(false, second.get("changed"), "幂等重放被当成了一次新的撤销");
        assertEquals("CANCELLED", second.get("status"));
        assertEquals(AMOUNT, second.get("amountFen"), "重放也要把单据信息给全，不能只回一个 ok");
        assertEquals(first.get("withdrawNo"), second.get("withdrawNo"));
        assertEquals(true, second.get("withdrawableKnown"));

        assertEquals(1, cancelAudits(id), "重放写了第二条审计 —— 审计里会出现一次不存在的撤销动作");
    }

    @Test @Order(5)
    @DisplayName("并发撤同一张单：N 个线程里恰好 1 个 changed=true，其余 200 + changed=false")
    void concurrentCancelHasExactlyOneWinner() throws Exception {
        long id = withdraws.apply(U_ME, AMOUNT, "BANK_TRANSFER", "1.1.1.1").id();

        int n = 8;
        var pool = Executors.newFixedThreadPool(n);
        var start = new CountDownLatch(1);
        var changed = new AtomicInteger();
        var replayed = new AtomicInteger();
        var failed = new ArrayList<String>();
        var futures = new ArrayList<Future<?>>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    var out = api.cancel(id, as(U_ME));
                    if (Boolean.TRUE.equals(out.get("changed"))) changed.incrementAndGet();
                    else replayed.incrementAndGet();
                } catch (Exception ex) {
                    synchronized (failed) { failed.add(ex.getClass().getSimpleName() + ": " + ex.getMessage()); }
                }
                return null;
            }));
        }
        start.countDown();
        for (var f : futures) f.get(60, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(List.of(), failed, "并发撤销里有请求直接失败了");
        assertEquals(1, changed.get(), "不止一个线程认为自己撤销成功 —— 审计里会出现两次撤销同一张单");
        assertEquals(n - 1, replayed.get());
        assertEquals("CANCELLED", statusOf(id));
        assertEquals(1, cancelAudits(id), "并发下写了多条撤销审计");
    }

    @Test @Order(6)
    @DisplayName("撤销 vs 平台受理并发：谁先到谁赢，绝不出现「已撤销又被推进审核」")
    void cancelRacesPlatformApprove() throws Exception {
        int rounds = 12;
        int cancelWins = 0, adminWins = 0;

        for (int r = 0; r < rounds; r++) {
            long id = withdraws.apply(U_ME, AMOUNT, "BANK_TRANSFER", "1.1.1.1").id();

            var pool = Executors.newFixedThreadPool(2);
            var start = new CountDownLatch(1);
            Future<Boolean> cancel = pool.submit(() -> {
                start.await();
                try { return Boolean.TRUE.equals(api.cancel(id, as(U_ME)).get("changed")); }
                catch (ApiException e) { return false; }
            });
            Future<Boolean> approve = pool.submit(() -> {
                start.await();
                // 平台侧 approve 在同一事务里连推 APPLIED→REVIEWING→APPROVED（见 admin/WithdrawService）
                try { return Boolean.TRUE.equals(adminWithdraws.approve(admin(), id, "抽检").get("changed")); }
                catch (ApiException e) { return false; }
            });
            start.countDown();
            boolean cancelOk = cancel.get(60, TimeUnit.SECONDS);
            boolean approveOk = approve.get(60, TimeUnit.SECONDS);
            pool.shutdown();

            String finalStatus = statusOf(id);
            assertTrue(cancelOk ^ approveOk,
                    "两边都认为自己赢了（或都输了）：cancel=" + cancelOk + " approve=" + approveOk
                            + " 最终状态=" + finalStatus);
            assertTrue(List.of("CANCELLED", "APPROVED").contains(finalStatus),
                    "最终状态既不是撤销也不是受理后的状态：" + finalStatus);
            assertEquals(cancelOk, "CANCELLED".equals(finalStatus),
                    "赢的那一方与库里的最终状态对不上 —— 有一侧覆盖了另一侧");
            // 「已 CANCELLED 又被推进 REVIEWING」：这一步在库里被 trg_withdraw_state 挡着
            // （OLD='CANCELLED' 是终态），但真正让它压根不会发生的是两边 UPDATE 上的状态谓词。
            assertEquals(cancelOk ? 1 : 0, cancelAudits(id));

            if (cancelOk) cancelWins++; else adminWins++;
            // 受理成功的那些单会一直占着在途额度，跑完把它们推到终态释放掉
            if (!cancelOk) jdbc.update("UPDATE withdraw_order SET status='RISK_REJECTED',"
                    + " risk_note='用例收尾' WHERE id=? AND status='APPROVED'", id);
        }
        assertEquals(rounds, cancelWins + adminWins);
    }

    @Test @Order(7)
    @DisplayName("没有佣金账户时：withdrawableKnown=false，body 里【没有】withdrawableFen 这个键")
    void withdrawableIsUnknownWhenNoBalanceRow() {
        long id = withdraws.apply(U_NOBAL, AMOUNT, "BANK_TRANSFER", "3.3.3.3").id();
        // 账户被销户/尚未开户：申请时还在（触发器要求有行），撤销时已经没了
        jdbc.update("DELETE FROM agent_balance WHERE agent_id=?", A_NOBAL);

        var out = api.cancel(id, as(U_NOBAL));

        assertEquals(true, out.get("ok"));
        assertEquals(true, out.get("changed"));
        assertEquals("CANCELLED", out.get("status"));
        assertEquals(false, out.get("withdrawableKnown"));
        // ★ 用 containsKey 断言：non_null 会把值为 null 的键整个删掉，
        // 于是"算不出来所以给 null"在网线上和"后端太旧没这个字段"长得一模一样。
        // 这个键必须压根不出现，由 withdrawableKnown 来承担"知不知道"这件事。
        assertFalse(out.containsKey("withdrawableFen"),
                "没有佣金账户却给出了一个可提额度 —— 0 会被运营读成「额度被在途单占满了」，"
                        + "而真实情况是这个账户还没开过");
        String reason = (String) out.get("withdrawableUnknownReason");
        assertNotNull(reason, "说不出为什么算不出来，等于什么也没说");
        assertTrue(reason.contains("agent_balance"), reason);
        assertTrue(reason.contains("不返回 0"), reason);

        assertEquals("CANCELLED", statusOf(id));
        jdbc.update("INSERT INTO agent_balance (agent_id,commission_balance_fen) VALUES (?,?)",
                A_NOBAL, START_BALANCE);
    }

    // ================================================================ 列表

    @Test @Order(10)
    @DisplayName("列表返回体：rows 与 items 同长同实例，WithdrawRow 的 14 个键逐字段对得上")
    void listCarriesRowsAndItems() {
        long id = withdraws.apply(U_ME, AMOUNT, "WECHAT_TRANSFER", "1.1.1.1").id();

        var out = api.list(null, 1, 10, as(U_ME));
        assertEquals(true, out.get("ok"));
        @SuppressWarnings("unchecked")
        var rows = (List<Map<String, Object>>) out.get("rows");
        @SuppressWarnings("unchecked")
        var items = (List<Map<String, Object>>) out.get("items");
        assertNotNull(rows, "前端 Page<T> 要的就是 rows 这个键，没有它整页渲染不出来");
        assertSame(rows, items, "rows 与 items 必须是同一个 List：改名会让旧调用方拿到 200 + 空指针");
        assertEquals(rows.size(), items.size());
        assertEquals(1, out.get("page"));
        assertEquals(10, out.get("size"));
        assertTrue((Long) out.get("total") >= 1, "total 是 Pager 唯一的依据");
        assertEquals((long) rows.size(), Math.min((Long) out.get("total"), 10L));

        var w = rows.get(0);
        assertEquals(id, w.get("id"), "ORDER BY id DESC：最新一笔必须在最上面");
        assertTrue(w.get("id") instanceof Long, "BIGINT UNSIGNED 直接强转 Long 会 CCE，这里必须已经是 Long");
        assertTrue(String.valueOf(w.get("withdrawNo")).startsWith("WD"));
        assertEquals(AMOUNT, w.get("amountFen"));
        // 未打款时这两列是 DEFAULT 0，语义是"打款时才确定"而不是"零税"；
        // 前端只在 status==='PAID' 时才渲染它们。见 open_questions。
        assertEquals(0L, w.get("taxWithheldFen"));
        assertEquals(0L, w.get("actualPaidFen"));
        assertEquals("WECHAT_TRANSFER", w.get("channel"));
        assertEquals("甲一级", w.get("payeeName"), "户名不是实名，等于允许代收");
        assertEquals("****1111", w.get("payeeAccount"), "完整卡号进了列表 —— 投屏截图就是一次泄漏");
        assertEquals("APPLIED", w.get("status"));
        assertEquals("已申请，待审核", w.get("statusText"), "旧键 statusText 去掉即回归");
        assertNotNull(w.get("createdAt"));
        // non_null 下这四个为空时是【整个键消失】，不是 null。TS 那边都是可选，接得住。
        assertNull(w.get("riskNote"));
        assertNull(w.get("failReason"));
        assertNull(w.get("paidAt"));
        assertNull(w.get("invoiceNo"));
        assertTrue(w.containsKey("invoiceNo"), "invoiceNo 是现役返回体里的旧键，去掉即回归");
        // 13 个 = TS 里 WithdrawRow 逐字段照抄的那些；另外 2 个 = 前端不认识但必须继续给的
        // 旧键 statusText / invoiceNo（现役实现已有，去掉即回归）。
        assertEquals(List.of("id", "withdrawNo", "amountFen", "taxWithheldFen", "actualPaidFen",
                        "channel", "payeeName", "payeeAccount", "status", "statusText",
                        "riskNote", "failReason", "invoiceNo", "createdAt", "paidAt"),
                List.copyOf(w.keySet()),
                "WithdrawRow 的键集变了 —— 前端是逐字段照抄的，少一个键那一列就恒空");

        // 撤销过的单继续留在列表里：撤了就查不到，等于"我撤了"这件事没有任何记录
        long cancelled = withdraws.apply(U_ME, AMOUNT, "BANK_TRANSFER", "1.1.1.1").id();
        api.cancel(cancelled, as(U_ME));
        var only = api.list("cancelled", 1, 10, as(U_ME));       // normalizeEnum 会 trim+大写
        @SuppressWarnings("unchecked")
        var cancelledRows = (List<Map<String, Object>>) only.get("rows");
        assertTrue(cancelledRows.stream().anyMatch(r -> cancelled == (Long) r.get("id")),
                "撤销后这张单从列表里消失了");
        assertTrue(cancelledRows.stream().allMatch(r -> "CANCELLED".equals(r.get("status"))));
    }

    @Test @Order(11)
    @DisplayName("列表分页与参数：size>100 静默钳到 100 并回真实值；翻过头给空 rows 不报错；未知 status 报错不查空")
    void listPagingAndParamGuards() {
        var clamped = api.list(null, 1, 1000, as(U_ME));
        assertEquals(100, clamped.get("size"), "size 没被钳住：size=1000000 就是一次全表导出");

        var overflow = api.list(null, 999, 10, as(U_ME));
        @SuppressWarnings("unchecked")
        var rows = (List<Map<String, Object>>) overflow.get("rows");
        assertEquals(List.of(), rows, "翻过末页应给空 rows（不是 null，non_null 会把 null 删掉）");
        assertTrue((Long) overflow.get("total") > 0, "翻过头也要给真实总数，Pager 靠它回退");

        var badPage = assertThrows(ApiException.class, () -> api.list(null, 0, 10, as(U_ME)));
        assertEquals(400, badPage.status());
        assertTrue(badPage.getMessage().contains("页码从 1 开始"), badPage.getMessage());
        var badSize = assertThrows(ApiException.class, () -> api.list(null, 1, 0, as(U_ME)));
        assertTrue(badSize.getMessage().contains("每页条数至少 1"), badSize.getMessage());

        // 未知状态报错而不是查空：查空和"确实没有"长得一模一样，这种 bug 能在线上活很久
        var badStatus = assertThrows(ApiException.class, () -> api.list("DONE", 1, 10, as(U_ME)));
        assertEquals(400, badStatus.status());
        assertTrue(badStatus.getMessage().contains("未知的提现单状态"), badStatus.getMessage());
    }

    @Test @Order(12)
    @DisplayName("空数据：total=0、rows=[] 且不是 null，绝不因为「没提过现」而 404")
    void emptyListIsNotAnError() {
        var out = api.list(null, 1, 10, as(U_NOBAL));
        @SuppressWarnings("unchecked")
        var rows = (List<Map<String, Object>>) out.get("rows");
        // 丙只有一张已撤销的单，按 APPLIED 过滤就是真空
        var applied = api.list("APPLIED", 1, 10, as(U_NOBAL));
        assertEquals(0L, applied.get("total"));
        @SuppressWarnings("unchecked")
        var appliedRows = (List<Map<String, Object>>) applied.get("rows");
        assertNotNull(appliedRows, "空列表必须是 []，null 会被 non_null 整键删掉，前端拿到 undefined 就崩");
        assertEquals(0, appliedRows.size());
        assertNotNull(rows);
    }

    // ================================================================ 越权 / 身份

    @Test @Order(20)
    @DisplayName("未审代理（PENDING）撤不了、也看不了提现台账 —— 判据是档案状态不是 role")
    void pendingAgentIsRejected() {
        jdbc.update("INSERT INTO sys_user (id,phone,role,real_name) VALUES (13,'13100000013','AGENT','丁待审')");
        jdbc.update("INSERT INTO agent_profile (id,user_id,agent_code,level,status) "
                  + "VALUES (4,13,'AGWD00000004',1,'PENDING')");
        // ★ role 已经是 AGENT 了（自助申请时就改了），只判 role 的实现会在这里放行
        assertEquals("AGENT", jdbc.queryForObject("SELECT role FROM sys_user WHERE id=13", String.class));

        long someone = firstAnyOf(A_ME);
        var e = assertThrows(ApiException.class, () -> api.cancel(someone, as(13)));
        assertEquals(403, e.status(), "PENDING 代理被拒的状态码不是 403：" + e.getMessage());
        assertTrue(e.getMessage().contains("审核中"), e.getMessage());

        var e2 = assertThrows(ApiException.class, () -> api.list(null, 1, 10, as(13)));
        assertEquals(403, e2.status());

        var e3 = assertThrows(ApiException.class, () -> api.cancel(someone, as(30)));
        assertEquals(403, e3.status(), "非代理调撤销：" + e3.getMessage());
        assertTrue(e3.getMessage().contains("还不是代理商"), e3.getMessage());
    }

    @Test @Order(21)
    @DisplayName("不存在的单：与别人的单同一句话、同一状态码")
    void missingOrderLooksExactlyLikeSomeoneElses() {
        var missing = assertThrows(ApiException.class, () -> api.cancel(999_999L, as(U_ME)));
        assertEquals(404, missing.status());
        assertEquals("提现单不存在", missing.getMessage(),
                "不存在与不属于你给了不同回答，就等于开放了一个「这个 id 存在吗」的问答接口");
    }

    // ---------------------------------------------------------------- 夹具

    // ============================================ 打款失败之后这笔钱去哪了

    /**
     * ★ 同一笔佣金余额不能被提出去两次。
     *
     * <p>这条路上的每一步单看都合法，所以它不会报错，只会少钱：
     * <ol>
     *   <li>提 1000 → 银行退票 → 平台标记打款失败。钱没出去、余额没扣。
     *   <li>如果 FAILED 不占额度，代理照着界面上那句「钱还在你的余额里，可以重新申请」
     *       再提一张 —— 闸门看不见那张 FAILED，放行。
     *   <li>平台把第一张 FAILED 重新审批打款（{@code trg_withdraw_state} 明文允许），
     *       再把第二张也打了。两笔都真的出账，余额被扣成负数。
     * </ol>
     *
     * <p>所以这里量的是<b>第 2 步就必须被拦住</b>：FAILED 期间可提额度不回吐。
     */
    @Test @Order(40)
    @DisplayName("★ 打款失败期间额度不回吐 —— 否则同一笔余额能被提两次")
    void failedWithdrawKeepsHoldingQuota() {
        long id = withdraws.apply(U_OTHER, AMOUNT, "BANK_TRANSFER", "1.1.1.1").id();
        adminWithdraws.approve(admin(), id, "核过了");
        adminWithdraws.markPaid(admin(), id, "TXN-FAIL-1", 0L, AMOUNT, null);
        // 上一句把它推到了 PAID，这条用例要的是 FAILED，所以另起一单走到 PAYING 再失败
        long f = withdraws.apply(U_OTHER, AMOUNT, "BANK_TRANSFER", "1.1.1.1").id();
        adminWithdraws.approve(admin(), f, "核过了");
        jdbc.update("UPDATE withdraw_order SET status='PAYING' WHERE id=?", f);
        adminWithdraws.markFailed(admin(), f, "账号名与开户名不符，银行退回");
        assertEquals("FAILED", statusOf(f));

        var bal = balanceRow(A_OTHER);
        long onBook = ((Number) bal.get("commission_balance_fen")).longValue();
        assertEquals(AMOUNT, agents.inflightWithdrawFen(A_OTHER),
                "打款失败的单子把额度放了出去 —— 它还能被重新打款，放出去就是允许提第二次");
        assertEquals(onBook - AMOUNT, agents.balance(U_OTHER).get("withdrawableFen"),
                "界面上的可提额度必须与闸门口径一致，否则「页面说能提、库说不能」");

        // 闸门这一侧同样要拦住：把余额减到只够一笔，再提一笔必须被拒
        long room = onBook - AMOUNT;
        var e = assertThrows(ApiException.class,
                () -> withdraws.apply(U_OTHER, room + 1, "BANK_TRANSFER", "1.1.1.1"));
        assertEquals(409, e.status());
        assertTrue(e.getMessage().contains("超出可提额度"), "拒是拒了，但没说清是被在途占着：" + e.getMessage());
    }

    @Test @Order(41)
    @DisplayName("★ 打款失败的单子必须有出路：重新审核通过、或驳回退回额度")
    void failedWithdrawHasTwoWaysOut() {
        long f = jdbc.queryForObject(
                "SELECT id FROM withdraw_order WHERE agent_id=? AND status='FAILED'"
              + " ORDER BY id DESC LIMIT 1", Long.class, A_OTHER);

        // ① 后台此前唯一显示的按钮：直接标记已打款。它必定被拒 —— 而拒的那句话
        //    要能告诉运营下一步该点哪里，否则一条走不通的路配一句看不懂的错
        var dead = assertThrows(ApiException.class,
                () -> adminWithdraws.markPaid(admin(), f, "TXN-X", 0L, AMOUNT, null));
        assertEquals(409, dead.status());
        assertTrue(dead.getMessage().contains("重新审核通过"),
                "对 FAILED 只说了「不能跳步」，没说该怎么办：" + dead.getMessage());

        // ② 出路一：重新审核通过 → 打款成功。全程只扣一次余额
        long before = ((Number) balanceRow(A_OTHER).get("commission_balance_fen")).longValue();
        adminWithdraws.approve(admin(), f, "收款账号已改对");
        assertEquals("APPROVED", statusOf(f));
        adminWithdraws.markPaid(admin(), f, "TXN-RETRY-OK", 0L, AMOUNT, null);
        assertEquals("PAID", statusOf(f));
        assertEquals(before - AMOUNT,
                ((Number) balanceRow(A_OTHER).get("commission_balance_fen")).longValue(),
                "重试打款扣了不止一次，或者一次都没扣");
        assertEquals(0L, agents.inflightWithdrawFen(A_OTHER), "打完款还占着额度");

        // ③ 出路二：驳回。终态，额度当场释放，钱一分没动
        long g = withdraws.apply(U_OTHER, AMOUNT, "BANK_TRANSFER", "1.1.1.1").id();
        adminWithdraws.approve(admin(), g, "核过了");
        jdbc.update("UPDATE withdraw_order SET status='PAYING' WHERE id=?", g);
        adminWithdraws.markFailed(admin(), g, "对方账户已销户");
        long held = ((Number) balanceRow(A_OTHER).get("commission_balance_fen")).longValue();

        var out = adminWithdraws.reject(admin(), g, "账户已销户，本单终结");
        assertEquals(true, out.get("changed"));
        assertEquals("RISK_REJECTED", statusOf(g));
        assertEquals(0L, agents.inflightWithdrawFen(A_OTHER), "驳回之后额度必须当场退回");
        assertEquals(held, ((Number) balanceRow(A_OTHER).get("commission_balance_fen")).longValue(),
                "驳回动了余额 —— 这笔钱从来没有出过账，账上不该有任何变化");
    }

    static MockHttpServletRequest as(long userId) {
        var r = new MockHttpServletRequest();
        r.setRequestURI("/api/agent/withdrawals");
        r.setAttribute(AuthInterceptor.ATTR_USER_ID, userId);
        r.setRemoteAddr("10.9.9.9");
        return r;
    }

    static MockHttpServletRequest admin() {
        var r = as(U_ADMIN);
        r.setAttribute(AuthInterceptor.ATTR_ROLE, "PLATFORM_ADMIN");
        return r;
    }

    static String statusOf(long id) {
        return jdbc.queryForObject("SELECT status FROM withdraw_order WHERE id=?", String.class, id);
    }

    static Map<String, Object> balanceRow(long agentId) {
        return jdbc.queryForMap("SELECT commission_balance_fen, total_settled_fen,"
                + " total_withdrawn_fen FROM agent_balance WHERE agent_id=?", agentId);
    }

    static int countLedger(long agentId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM commission_ledger WHERE agent_id=?",
                Integer.class, agentId);
    }

    static int cancelAudits(long withdrawId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='WITHDRAW_CANCEL'"
                + " AND target_type='withdraw_order' AND target_id=?", Integer.class,
                String.valueOf(withdrawId));
    }

    static long firstAppliedOf(long agentId) {
        return jdbc.queryForObject("SELECT id FROM withdraw_order WHERE agent_id=? AND status='APPLIED'"
                + " ORDER BY id DESC LIMIT 1", Long.class, agentId);
    }

    static long firstAnyOf(long agentId) {
        return jdbc.queryForObject("SELECT id FROM withdraw_order WHERE agent_id=?"
                + " ORDER BY id DESC LIMIT 1", Long.class, agentId);
    }
}
