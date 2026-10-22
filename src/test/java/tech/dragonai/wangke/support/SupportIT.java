package tech.dragonai.wangke.support;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionTemplate;
import tech.dragonai.wangke.ItDb;
import tech.dragonai.wangke.auth.AuthInterceptor;
import tech.dragonai.wangke.web.ApiException;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 答疑 + 发票，跑在真实 MySQL 上。
 *
 * <p>这两块的赔付点不一样，判据也就不一样：
 * <ul>
 *   <li>答疑怕的是<b>看到别人的东西</b>（工单正文是学员的私信）和<b>配额形同虚设</b>
 *       （AI 是课程内容的泄漏面）。
 *   <li>发票怕的只有一件事：<b>开出比实付更大的票</b>。那不是数据错误，是虚开。
 * </ul>
 *
 * <p><b>每个负例都钉住失败原因</b>（状态码 + 那句人话里的关键词）。
 * 只断言「抛异常」的负例太容易因为别的原因红着 —— SQL 写错列名也会抛异常，
 * 那种用例绿的时候什么都没证明。{@link #expect} 就是干这个的。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SupportIT {

    static final String DB = "wangke_support_it";

    static DataSource ds;
    static JdbcTemplate jdbc;
    static QaService qa;
    static FaqSearch faq;
    static InvoiceService invoices;
    /** 角色闸只存在于 HTTP 面上，所以这一层必须走真控制器，不能只调 service。 */
    static InvoiceController invoiceApi;

    static final long COURSE = 1L;
    /** 配额上限调小到 3，只是为了让「撞闸」这个用例跑得快；判定逻辑与线上那条完全相同。 */
    static final int QUOTA = 3;

    static final long STU_A = 20L;      // 提问、开票的主角
    static final long STU_B = 21L;      // 专门用来撞配额（配额是按人计的，不能和别人共用）
    static final long STU_C = 22L;      // 越权者：拿别人的工单 id 去读
    static final long MENTOR_1 = 30L;   // 被指派的导师
    static final long MENTOR_2 = 31L;   // 没被指派的导师
    static final long ADMIN = 1L;       // seed.sql 里的平台超管

    static long ticketOfA;
    static long orderOfA;

    @BeforeAll
    static void bootstrap() throws Exception {
        ds = ItDb.freshDatabase(DB);
        jdbc = new JdbcTemplate(ds);

        // 这三样（reject_reason / processed_by / uk_invoice_no）**已经并进 schema.sql**，
        // 所以下面这段探测正常情况下什么都不做。留着它是为了让这份用例在
        // 「schema.sql 里有」和「只有 migrations 里有」两种库上都能跑起来。
        //
        // ★ 这段补丁曾经是唯一让发票用例绿的原因，而它顺手掩盖了一个真缺陷：
        //   schema.sql 里没有那三样 —— 于是【新装的库】上 InvoiceService.mine()
        //   一查 reject_reason 就 500，学员点开「我的 → 发票」看到的是系统繁忙，
        //   而 uk_invoice_no 不在，同一个发票号能填进两条申请（一张票两笔收入，不报错）。
        //   360 条用例全绿照不出来，因为用例自己先把缺的补上了。
        //   实测是在真浏览器里打开「我的」那一页才撞出来的。
        //   → 教训：夹具替被测对象补齐环境时，补的那一块就此退出量程。
        //
        // 先探再套：无条件 ALTER 会在已经并好的库上以「Duplicate column name」当场炸，
        // 而炸的是【建库】这一步，18 条用例会一起变红，看起来像这一批代码全坏了。
        // 判据取 information_schema，不取「试着 ALTER 一下看报不报错」——
        // 后者要靠异常做流程控制，且分不清「已经有了」和「真的坏了」。
        Integer patched = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS "
              + " WHERE TABLE_SCHEMA=? AND TABLE_NAME='invoice_request' AND COLUMN_NAME='reject_reason'",
                Integer.class, DB);
        if (patched != null && patched == 0) {
            String ddl = Files.readString(
                    Paths.get("db/migrations/20260811_发票号唯一与驳回原因.sql"), StandardCharsets.UTF_8);
            ItDb.runScript(ddl.replace("`wangke`", "`" + DB + "`"));
        }

        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        faq = new FaqSearch(jdbc);
        qa = new QaService(jdbc, tx, faq, new DongmaClient("http://127.0.0.1:1", "", 1), QUOTA);
        invoices = new InvoiceService(jdbc, tx);
        invoiceApi = new InvoiceController(invoices);

        jdbc.update("INSERT INTO sys_user (id,phone,role) VALUES "
                  + "(?,'13300000020','STUDENT'),(?,'13300000021','STUDENT'),(?,'13300000022','STUDENT'),"
                  + "(?,'13300000030','MENTOR'),(?,'13300000031','MENTOR')",
                STU_A, STU_B, STU_C, MENTOR_1, MENTOR_2);

        // 权益：只有买了课的人才能提问，所以 A/B 都得先有 enrollment。
        // source='GRANT' 是 ck_enrollment_source 允许的三条之一里唯一不需要码 / 订单的。
        for (long u : new long[]{STU_A, STU_B})
            jdbc.update("INSERT INTO enrollment (user_id,course_id,source,valid_from,valid_until,"
                      + "agreement_version,agreed_at) VALUES (?,?,'GRANT',NOW(3),"
                      + "DATE_ADD(NOW(3), INTERVAL 24 MONTH),'v2026-08-11',NOW(3))", u, COURSE);

        jdbc.update("INSERT INTO faq_entry (course_id,question,answer,created_by) VALUES "
                  + "(?,'激活码激活后有效期是多久？','激活后可观看 24 个月，从激活时刻起算。',?),"
                  + "(?,'激活码可以转给别人吗？','一码一人，激活后不可转移。',?),"
                  + "(?,'课程可以在几台设备上观看？','同一账号最多绑定 3 台设备。',?)",
                COURSE, ADMIN, COURSE, ADMIN, COURSE, ADMIN);

        orderOfA = newPaidOrder("SUP_ORD_A", STU_A, 79900);
    }

    @AfterAll
    static void teardown() { ItDb.close(ds); }

    // ---------------------------------------------------------------- 夹具

    static long newPaidOrder(String orderNo, long buyer, long paidFen) {
        jdbc.update("INSERT INTO biz_order (order_no,buyer_user_id,buyer_type,sku_id,"
                  + "sku_code_snapshot,code_qty_snapshot,amount_fen,expire_at) "
                  + "VALUES (?,?,'STUDENT',3,'STUDENT_DIRECT',1,?,DATE_ADD(NOW(3), INTERVAL 2 HOUR))",
                orderNo, buyer, paidFen);
        long id = ((Number) jdbc.queryForMap("SELECT id FROM biz_order WHERE order_no=?", orderNo)
                .get("id")).longValue();
        // trg_order_state 要求转 PAID 时必须同时写 paid_at 与实付金额，否则拒绝
        jdbc.update("UPDATE biz_order SET status='PAID', paid_at=NOW(3), paid_amount_fen=? WHERE id=?",
                paidFen, id);
        return id;
    }

    /** 钉住失败原因：状态码要对，那句人话里的关键词也要对。 */
    static ApiException expect(int status, String messagePart, org.junit.jupiter.api.function.Executable body) {
        ApiException e = assertThrows(ApiException.class, body,
                "根本没抛 ApiException —— 这条规则可能压根没被执行到");
        assertEquals(status, e.status(),
                "状态码不是要测的那个（实际 " + e.status() + "：" + e.getMessage() + "）");
        assertTrue(e.getMessage().contains(messagePart),
                "失败原因不是要测的那条（实际：" + e.getMessage() + "）—— "
              + "这个负例通过时并没有证明被测的规则成立");
        return e;
    }

    // ================================================================ FAQ 全文检索

    @Test @Order(1)
    @DisplayName("FAQ 检索命中中文分词，且 EXPLAIN 证明走的是 ngram 全文索引而不是全表扫")
    void faqSearchUsesFulltextIndex() {
        var hits = faq.search(COURSE, "激活码", 10);
        assertEquals(2, hits.size(), "『激活码』应当命中两条问答；ngram 分词没生效的话这里是 0");
        assertTrue(hits.get(0).question().contains("激活码"));
        // 相关度只在同一次检索内部可比：命中更多次的那条要排前面
        assertTrue(hits.get(0).score() >= hits.get(1).score(), "结果没有按相关度降序");

        // 跨词命中：'有效期' 这三个字只出现在第一条里
        var one = faq.search(COURSE, "有效期", 10);
        assertEquals(1, one.size());

        // 走的是不是索引 —— 这才是「不要用 LIKE」那条要求的判据
        var plan = jdbc.queryForMap(
                "EXPLAIN SELECT id FROM faq_entry "
              + " WHERE MATCH(question) AGAINST(? IN NATURAL LANGUAGE MODE) AND status='ON'", "激活码");
        System.out.println("[EXPLAIN MATCH..AGAINST] " + plan);
        assertEquals("fulltext", plan.get("type"),
                "MATCH 查询没有走全文索引，access type = " + plan.get("type"));
        assertEquals("ft_question", plan.get("key"),
                "用的不是 schema 里那条 ngram 索引 ft_question");

        // 反面对照：同样的意图写成 LIKE '%x%' 是全表扫。
        // 没有这条对照的话，上面那两个断言只证明「有个索引」，证明不了「换个写法就没了」。
        var likePlan = jdbc.queryForMap(
                "EXPLAIN SELECT id FROM faq_entry WHERE question LIKE '%激活码%' AND status='ON'");
        System.out.println("[EXPLAIN LIKE '%x%'] " + likePlan);
        assertNotEquals("fulltext", likePlan.get("type"),
                "LIKE 竟然也走了全文索引？那说明这条对照没意义，要重新设计判据");
    }

    @Test @Order(2)
    @DisplayName("单字检索被显式拦下：ngram_token_size=2，不拦的话是个不会报错的假空结果")
    void singleCharQueryIsRefusedNotSilentlyEmpty() {
        // 先证明「不拦就是假空」：直接对库发单字全文检索，命中 0 条
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM faq_entry WHERE MATCH(question) AGAINST('码' IN NATURAL LANGUAGE MODE)",
                Integer.class);
        assertEquals(0, n, "单字竟然能命中？那 ngram_token_size 不是 2，这条拦截的前提就变了");

        expect(400, "至少要 2 个字", () -> faq.search(COURSE, "码", 10));
        expect(400, "至少要 2 个字", () -> faq.search(COURSE, "  ", 10));
    }

    // ================================================================ 提问

    @Test @Order(3)
    @DisplayName("提问命中问答库时当场作答，并把 hit_count 记在『真的用它答过』上")
    void askAnsweredFromFaq() {
        var a = qa.ask(STU_A, COURSE, null, "激活码有效期", "我的激活码激活之后能看多久？", false);
        ticketOfA = a.ticketId();

        assertTrue(a.answered(), "命中了问答库却没有作答");
        assertEquals("AI_ANSWERED", a.status());
        assertNotNull(a.faqId());
        assertEquals(QUOTA - 1, a.quotaLeftToday());

        var msgs = jdbc.queryForList("SELECT sender_type, citations FROM qa_message "
                                   + "WHERE ticket_id=? ORDER BY id", ticketOfA);
        assertEquals(2, msgs.size(), "应当是『学员问 + AI 答』两条消息");
        assertEquals("STUDENT", msgs.get(0).get("sender_type"));
        assertEquals("AI", msgs.get(1).get("sender_type"));
        assertTrue(String.valueOf(msgs.get(1).get("citations")).contains("faqId"),
                "自动作答没有带出处，事后无法回溯这句话是从哪条问答来的");

        Integer hits = jdbc.queryForObject("SELECT hit_count FROM faq_entry WHERE id=?",
                Integer.class, a.faqId());
        assertEquals(1, hits);

        // ai_confidence 必须是 NULL：本仓没有模型，硬填一个数会让「低于阈值升级人工」
        // 那条闸的阈值变成一个没有含义的常数
        assertNull(jdbc.queryForMap("SELECT ai_confidence FROM qa_ticket WHERE id=?", ticketOfA)
                .get("ai_confidence"));
    }

    @Test @Order(4)
    @DisplayName("没有这门课权益的人不能提问：AI 是内容泄漏面，不是免费试用通道")
    void askRequiresEnrollment() {
        expect(403, "还没有这门课的有效权益",
                () -> qa.ask(STU_C, COURSE, null, "标题", "没买课也想问", false));
    }

    @Test @Order(5)
    @DisplayName("超配额提问：给人话不给数据库报错，且 blocked_count 必须留下来")
    void quotaExceededIsHumanReadableAndLeavesTrace() {
        for (int i = 0; i < QUOTA; i++)
            qa.ask(STU_B, COURSE, null, "问题" + i, "这是第 " + i + " 个问题，随便问点什么", false);

        var e = expect(403, "答疑次数已用完",
                () -> qa.ask(STU_B, COURSE, null, "第四问", "这一条应该被闸挡住", false));
        // 不能是把数据库的 CHECK / 唯一键报错直接抛出去
        assertFalse(e.getMessage().toLowerCase().contains("sql")
                 || e.getMessage().contains("qa_quota_usage")
                 || e.getMessage().contains("Check constraint"),
                "错误信息里漏了内部细节：" + e.getMessage());

        var row = jdbc.queryForMap(
                "SELECT ask_count, blocked_count FROM qa_quota_usage WHERE user_id=? AND stat_date=CURDATE()",
                STU_B);
        assertEquals(QUOTA, ((Number) row.get("ask_count")).intValue(),
                "被拒的那一次把配额也扣掉了 —— 事务回滚没盖住配额自增");
        assertEquals(1, ((Number) row.get("blocked_count")).intValue(),
                "撞闸没有留下 blocked_count：这条统计跟着事务一起回滚了，"
              + "风控那边永远看不到有人在撞闸");

        // 被拒的提问不能留下半张工单
        Integer tickets = jdbc.queryForObject(
                "SELECT COUNT(*) FROM qa_ticket WHERE user_id=?", Integer.class, STU_B);
        assertEquals(QUOTA, tickets, "被配额挡下的那次仍然建出了工单");
    }

    @Test @Order(6)
    @DisplayName("配额判定在并发下不漏：16 个线程同时提问，通过数恰好等于配额，且不许有人死在死锁上")
    void quotaHoldsUnderConcurrency() throws Exception {
        // 换一个当天还没用过配额的人。先给他权益。
        long uid = 23L;
        jdbc.update("INSERT INTO sys_user (id,phone,role) VALUES (?,'13300000023','STUDENT')", uid);
        jdbc.update("INSERT INTO enrollment (user_id,course_id,source,valid_from,valid_until,"
                  + "agreement_version,agreed_at) VALUES (?,?,'GRANT',NOW(3),"
                  + "DATE_ADD(NOW(3), INTERVAL 24 MONTH),'v2026-08-11',NOW(3))", uid, COURSE);

        // 这些线程全都是「这个人当天的第一条」，所以它们同时去抢建同一行配额。
        //
        // ⚠ 这条用例能钉住的只有【配额数目】，钉不住死锁：
        // 曾经有一次跑批在这里以 "Deadlock found when trying to get lock" 收场，
        // 但把修复关掉做阴性对照，16 线程连跑 3 次全是绿的 —— 真实调度下那个交错要碰运气。
        // 死锁的机理是另外用一个能按住交错的小程序钉死的（先 UPDATE 后 INSERT，
        // 4 线程 20 轮必得 60 次死锁；改成先 INSERT 后 0 次），结论写在
        // QaService.ensureTodayRow 的注释里。
        // 所以别把这条用例当死锁的判据：它绿不代表那条路是好的。
        int n = 16;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(n);
        var start = new java.util.concurrent.CountDownLatch(1);
        var wins = new java.util.concurrent.atomic.AtomicInteger();
        var crashed = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
        for (int i = 0; i < n; i++) {
            final int k = i;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    qa.ask(uid, COURSE, null, "并发问" + k, "并发提问正文 " + k, false);
                    wins.incrementAndGet();
                } catch (ApiException ex) {
                    if (ex.status() != 403 || !ex.getMessage().contains("答疑次数已用完"))
                        crashed.add("非预期业务错：" + ex.status() + " " + ex.getMessage());
                } catch (Exception ex) {
                    crashed.add(ex.getClass().getSimpleName() + ": " + ex.getMessage());
                }
            }));
        }
        start.countDown();
        for (var f : futures) f.get(60, java.util.concurrent.TimeUnit.SECONDS);
        pool.shutdown();

        // 先看崩溃再看语义：断言顺序决定失败信息指向哪儿
        assertTrue(crashed.isEmpty(), "并发过程中抛了非预期异常：" + crashed);
        assertEquals(QUOTA, wins.get(),
                "并发下放过了 " + wins.get() + " 次（上限 " + QUOTA + "）—— "
              + "配额判定退化成了先查后写");
        Integer used = jdbc.queryForObject(
                "SELECT ask_count FROM qa_quota_usage WHERE user_id=? AND stat_date=CURDATE()",
                Integer.class, uid);
        assertEquals(QUOTA, used);
    }

    // ================================================================ 越权

    @Test @Order(7)
    @DisplayName("越权看别人的工单被拒，且『不存在』与『不是你的』对外是同一种结果")
    void cannotReadOthersTicket() {
        // 学员：别人的工单
        expect(404, "工单不存在", () -> qa.detail(STU_C, "STUDENT", ticketOfA));
        // 同一句话、同一个状态码，也是不存在的 id 得到的结果 ——
        // 两者可区分的话，这个接口就是一台能枚举「哪些工单存在」的探针
        expect(404, "工单不存在", () -> qa.detail(STU_C, "STUDENT", 99999L));

        // 追问同理：越权改不动
        expect(404, "工单不存在", () -> qa.reply(STU_C, ticketOfA, "我想插一句"));
        assertEquals(2, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM qa_message WHERE ticket_id=?", Integer.class, ticketOfA),
                "越权的追问竟然写进去了");

        // 本人可以看
        var mine = qa.detail(STU_A, "STUDENT", ticketOfA);
        assertEquals(ticketOfA, mine.get("id"));
        @SuppressWarnings("unchecked")
        var msgs = (List<Map<String, Object>>) mine.get("messages");
        assertEquals(2, msgs.size());
        assertFalse(msgs.get(0).containsKey("senderId"), "详情里带出了 sender_id");
    }

    @Test @Order(8)
    @DisplayName("导师只能看分配给自己的：没派给他之前，导师和陌生人一样是 404")
    void mentorSeesOnlyAssigned() {
        expect(404, "工单不存在", () -> qa.detail(MENTOR_1, "MENTOR", ticketOfA));
        expect(404, "工单不存在", () -> qa.staffReply(MENTOR_1, "MENTOR", ticketOfA, "我来答"));

        qa.assign(ADMIN, ticketOfA, MENTOR_1, "10.0.0.1");

        var seen = qa.detail(MENTOR_1, "MENTOR", ticketOfA);
        assertEquals(ticketOfA, seen.get("id"));
        // 另一位导师仍然看不见 —— 角色对了不等于范围对了
        expect(404, "工单不存在", () -> qa.detail(MENTOR_2, "MENTOR", ticketOfA));

        qa.staffReply(MENTOR_1, "MENTOR", ticketOfA, "有效期是激活后 24 个月。");
        assertEquals("HUMAN_ANSWERED",
                jdbc.queryForMap("SELECT status FROM qa_ticket WHERE id=?", ticketOfA).get("status"));

        assertEquals(1, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action='QA_ASSIGN' AND target_id=?",
                Integer.class, String.valueOf(ticketOfA)), "指派没有留痕");
    }

    @Test @Order(9)
    @DisplayName("已回复工单下的追问要把工单推回队列，否则这条追问谁都不会再看见")
    void followUpReopensTheTicket() {
        qa.reply(STU_A, ticketOfA, "那到期之后还能再续吗？");
        var t = jdbc.queryForMap("SELECT status, sla_due_at FROM qa_ticket WHERE id=?", ticketOfA);
        assertEquals("ESCALATED", t.get("status"),
                "追问之后工单停在 HUMAN_ANSWERED：导师队列按 ESCALATED 取，这条追问不会出现在任何人面前");
        assertNotNull(t.get("sla_due_at"), "回到队列却没有 SLA 时限，等于没有人被催");

        var queue = qa.queue(MENTOR_1, "MENTOR", null, 10);
        assertEquals(1, queue.size());
        assertEquals(ticketOfA, queue.get(0).get("id"));
        // 没派给 MENTOR_2 的工单不该出现在他的队列里
        assertTrue(qa.queue(MENTOR_2, "MENTOR", null, 10).isEmpty());
    }

    @Test @Order(10)
    @DisplayName("沉淀进公开问答库只能有一次：第二次靠条件 UPDATE 裁决，不留孤儿 faq 行")
    void publishFaqIsIdempotent() {
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM faq_entry", Long.class);
        long faqId = qa.publishFaq(ADMIN, "PLATFORM_ADMIN", ticketOfA,
                "激活码激活后能看多久？", "激活后 24 个月。", "10.0.0.1");
        assertTrue(faqId > 0);
        assertEquals(faqId, ((Number) jdbc.queryForMap(
                "SELECT published_faq_id FROM qa_ticket WHERE id=?", ticketOfA)
                .get("published_faq_id")).longValue());

        expect(409, "已经沉淀过", () -> qa.publishFaq(ADMIN, "PLATFORM_ADMIN", ticketOfA,
                "重复沉淀", "重复沉淀", "10.0.0.1"));
        assertEquals(before + 1, (long) jdbc.queryForObject("SELECT COUNT(*) FROM faq_entry", Long.class),
                "第二次沉淀失败了，但那条 faq_entry 留在了库里 —— 事务没把它带走");

        // 新沉淀的条目立刻可被公开检索到（证明它真的进了倒排表）
        assertTrue(faq.search(COURSE, "激活码", 10).stream().anyMatch(h -> h.faqId() == faqId));
    }

    // ================================================================ 发票

    @Test @Order(11)
    @DisplayName("开票金额由服务端按订单实付算出，前端根本没有可传的字段")
    void invoiceAmountIsServerComputed() {
        var out = invoices.apply(STU_A, orderOfA, "某某科技有限公司", "91330106MA2AB1CD5X");
        assertEquals(79900L, out.get("amountFen"), "开票金额不等于订单实付");

        var row = jdbc.queryForMap("SELECT applicant_user_id, amount_fen, status, scope, ref_id "
                                 + "FROM invoice_request WHERE id=?", out.get("id"));
        assertEquals(79900L, ((Number) row.get("amount_fen")).longValue());
        assertEquals("APPLIED", row.get("status"));
        assertEquals("ORDER", row.get("scope"));
        assertEquals(orderOfA, ((Number) row.get("ref_id")).longValue());
    }

    @Test @Order(12)
    @DisplayName("别人的订单开不出票：条件在 WHERE 里，越权与不存在同一种结果")
    void cannotInvoiceSomeoneElsesOrder() {
        long other = newPaidOrder("SUP_ORD_B", STU_B, 79900);
        expect(404, "订单不存在", () -> invoices.apply(STU_C, other, "抬头", null));
        assertEquals(0, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM invoice_request WHERE ref_id=?", Integer.class, other));
    }

    @Test @Order(13)
    @DisplayName("同一笔订单不得重复申请，且提示要说中真正的原因（『你已经申请过了』而不是『没额度了』）")
    void duplicateApplyIsRefused() {
        // 措辞是判据的一部分：已申请的那一行本身就把可开票额吃成了 0，
        // 所以一个只按金额判断的实现会回一句「可开票金额已全部申请或开出」——
        // 状态码同样是 409，用例照样绿，但用户读完并不知道是自己刚点过，于是继续点。
        expect(409, "正在处理中",
                () -> invoices.apply(STU_A, orderOfA, "换个抬头再来一次", null));
        assertEquals(1, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM invoice_request WHERE scope='ORDER' AND ref_id=?",
                Integer.class, orderOfA));
    }

    @Test @Order(14)
    @DisplayName("★申请之后发生退款：开票时重新核额，开票总额超过实付被拒（虚开红线）")
    void issueIsBlockedWhenRefundEatsThePaidAmount() {
        long invoiceId = ((Number) jdbc.queryForMap(
                "SELECT id FROM invoice_request WHERE scope='ORDER' AND ref_id=?", orderOfA)
                .get("id")).longValue();

        // 申请已经按 79900 立好了。现在整单退款进来（还在途，尚未打款）。
        jdbc.update("INSERT INTO refund_record (refund_no,order_id,scope,amount_fen,reason,applied_by) "
                  + "VALUES ('SUP_RF_1',?,'MANUAL',79900,'测试整单退',?)", orderOfA, ADMIN);

        // biz_order.refunded_amount_fen 仍然是 0（全仓没有代码在写它）——
        // 这正是为什么可开票额不能只读那一列。先把这个前提钉住，
        // 否则哪天有人补上了这一列的维护，下面这条用例会因为「另一条路也拦住了」而假绿。
        assertEquals(0L, ((Number) jdbc.queryForMap(
                "SELECT refunded_amount_fen FROM biz_order WHERE id=?", orderOfA)
                .get("refunded_amount_fen")).longValue());

        expect(409, "超过该订单的实付余额",
                () -> invoices.issue(ADMIN, invoiceId, "INV-2026-0001", "10.0.0.1"));

        var after = jdbc.queryForMap(
                "SELECT status, invoice_no, issued_at FROM invoice_request WHERE id=?", invoiceId);
        assertEquals("APPLIED", after.get("status"), "被拒了却还是把状态改成了已开票");
        assertNull(after.get("invoice_no"), "被拒了却把发票号登记上去了 —— 这张号就此被占用");
        assertNull(after.get("issued_at"));
    }

    @Test @Order(15)
    @DisplayName("全额退款后重新申请：可开票额为 0，申请这一步就被挡住")
    void applyIsBlockedWhenFullyRefunded() {
        long invoiceId = ((Number) jdbc.queryForMap(
                "SELECT id FROM invoice_request WHERE scope='ORDER' AND ref_id=?", orderOfA)
                .get("id")).longValue();
        invoices.reject(ADMIN, invoiceId, "订单已申请退款，无法开票", "10.0.0.1");

        // 驳回原因要回得到申请人手里，否则同一张申请会被反复提交
        var mine = invoices.mine(STU_A, 10);
        assertEquals("REJECTED", mine.get(0).get("status"));
        assertEquals("订单已申请退款，无法开票", mine.get(0).get("rejectReason"));

        expect(409, "全额退款", () -> invoices.apply(STU_A, orderOfA, "再试一次", null));
        assertEquals(1, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action='INVOICE_REJECT' AND target_id=?",
                Integer.class, String.valueOf(invoiceId)), "驳回没有留痕");
    }

    @Test @Order(16)
    @DisplayName("部分退款：可开票额 = 实付 − 已退，且被驳回过的申请可以按新金额重来")
    void partialRefundShrinksTheInvoiceableAmount() {
        long order = newPaidOrder("SUP_ORD_C", STU_A, 79900);
        jdbc.update("INSERT INTO refund_record (refund_no,order_id,scope,amount_fen,reason,applied_by) "
                  + "VALUES ('SUP_RF_2',?,'MANUAL',30000,'部分退',?)", order, ADMIN);

        var out = invoices.apply(STU_A, order, "某某科技有限公司", null);
        assertEquals(49900L, out.get("amountFen"),
                "可开票额没有把在途退款减掉 —— 多开出来的这 30000 就是虚开的部分");

        long invoiceId = ((Number) out.get("id")).longValue();
        invoices.reject(ADMIN, invoiceId, "抬头写错了", "10.0.0.1");
        var again = invoices.apply(STU_A, order, "某某科技有限公司（正确抬头）", null);
        assertEquals(invoiceId, ((Number) again.get("id")).longValue(),
                "被驳回后重新申请开出了第二行，uk_scope_ref 本该让它复用同一行");
        assertEquals(49900L, again.get("amountFen"), "重来时金额没有重算");
        assertNull(jdbc.queryForMap("SELECT reject_reason FROM invoice_request WHERE id=?", invoiceId)
                .get("reject_reason"), "重新申请后仍挂着上一次的驳回原因");

        invoices.issue(ADMIN, invoiceId, "INV-2026-0002", "10.0.0.1");
        var row = jdbc.queryForMap("SELECT status, invoice_no, processed_by FROM invoice_request WHERE id=?",
                invoiceId);
        assertEquals("ISSUED", row.get("status"));
        assertEquals("INV-2026-0002", row.get("invoice_no"));
        assertEquals(ADMIN, ((Number) row.get("processed_by")).longValue());
        assertEquals(1, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action='INVOICE_ISSUE' AND target_id=?",
                Integer.class, String.valueOf(invoiceId)));

        // 已开出的申请不能再被处理一次（并发双击 / 两个客服同时点）
        expect(409, "已被处理过", () -> invoices.issue(ADMIN, invoiceId, "INV-2026-0003", "10.0.0.1"));
    }

    @Test @Order(17)
    @DisplayName("同一个发票号不能登记到两笔申请上（uk_invoice_no），且回的是人话不是唯一键报错")
    void invoiceNoIsGloballyUnique() {
        long order = newPaidOrder("SUP_ORD_D", STU_A, 79900);
        var out = invoices.apply(STU_A, order, "某某科技有限公司", null);
        long invoiceId = ((Number) out.get("id")).longValue();

        // INV-2026-0002 已经被上一条用例登记过了。
        // 钉住的是「这句话说清了该做什么」：裸的 DuplicateKeyException 走到统一出口
        // 会变成一句「该记录已存在，请勿重复提交」，财务读完不会想到去核对发票号填错了哪一位。
        expect(409, "该发票号已登记在另一笔开票申请上",
                () -> invoices.issue(ADMIN, invoiceId, "INV-2026-0002", "10.0.0.1"));

        var after = jdbc.queryForMap(
                "SELECT status, invoice_no FROM invoice_request WHERE id=?", invoiceId);
        assertEquals("APPLIED", after.get("status"), "发票号撞车了，状态却已经改成已开票");
        assertNull(after.get("invoice_no"), "撞车的号仍然被写进了这一行");

        // 阴性对照：换一个没被占用的号就能开出来。
        // 没有这一条的话，上面那个 409 也可能是「这条申请本身就开不了」造成的，
        // 用例绿着，却没证明被拒的原因是号重复。
        invoices.issue(ADMIN, invoiceId, "INV-2026-0004", "10.0.0.1");
        assertEquals("ISSUED",
                jdbc.queryForMap("SELECT status FROM invoice_request WHERE id=?", invoiceId).get("status"));
    }

    // ================================================================ 已关闭工单

    @Test @Order(18)
    @DisplayName("已关闭工单：学员追问与导师回复都当场拒绝，不能『消息落库、状态不动』")
    void closedTicketRefusesBothSides() {
        var a = qa.ask(STU_A, COURSE, null, "设备台数", "这门课能在几台设备上观看？", false);
        long tid = a.ticketId();
        qa.close(ADMIN, "PLATFORM_ADMIN", tid);

        int before = msgCount(tid);
        expect(409, "该工单已关闭", () -> qa.reply(STU_A, tid, "再补一句"));
        // 导师侧尤其要拒：放行的话回复真的落进库里、学员也看得见，但工单仍算已结案，
        // 于是导师以为自己回了、系统以为这单结了，这条回复掉在中间没有人跟进。
        expect(409, "该工单已关闭", () -> qa.staffReply(ADMIN, "PLATFORM_ADMIN", tid, "补充一句"));

        assertEquals(before, msgCount(tid), "被拒的回复仍然写进了工单");
        assertEquals("CLOSED",
                jdbc.queryForMap("SELECT status FROM qa_ticket WHERE id=?", tid).get("status"),
                "被拒的回复把已关闭的工单又拉回了未结案状态");
    }

    static int msgCount(long ticketId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM qa_message WHERE ticket_id=?",
                Integer.class, ticketId);
    }

    // ================================================ 开票的挑单来源（跨身份）

    /**
     * 代理必须开得了自己进货单的票。
     *
     * <p>这条是 2026-08-19 那轮改动<b>差点砸出来的坑</b>：学员端「我的订单」收窄成
     * 只出 buyer_type='STUDENT' 之后，开票页原来正是拿那条接口来挑单的，
     * 于是代理的进货单从下拉里消失 —— 而代理端<b>根本没有开票入口</b>（全仓为零）。
     * B2B 那一半的开票比 C 端还刚性，砍掉它不会有任何报错，只会有客服工单。
     *
     * <p>所以挑单换成了 {@code /api/invoice/eligible-orders}，按<b>付过这笔钱的人</b>圈，
     * 不按身份分。
     */
    @Test @Order(80)
    @DisplayName("★ 可开票订单跨身份：代理的进货单也在里面，且可开票额由服务端算")
    void invoiceableOrdersSpanBothRoles() {
        // 代理档案只能挂在 role='AGENT' 的账号上（trg_agent_profile 守着），
        // 所以这里单开一个代理账号，而不是把学员改成代理 —— 改角色会串到别的用例。
        long agentUser = 990L;
        jdbc.update("INSERT INTO sys_user (id,phone,role) VALUES (?,'13300000990','AGENT')", agentUser);
        long agentOrder = newPaidAgentOrder("SUP_AGT_1", agentUser, 298000);

        var rows = invoices.invoiceableOrders(agentUser, 50);
        var ids = rows.stream().map(r -> ((Number) r.get("orderId")).longValue()).toList();
        assertTrue(ids.contains(agentOrder),
                "代理进货单不在可开票列表里 —— 代理端没有开票入口，这一单就永远开不了票了");

        var agentRow = rows.stream()
                .filter(r -> ((Number) r.get("orderId")).longValue() == agentOrder)
                .findFirst().orElseThrow();
        assertEquals("AGENT", agentRow.get("buyerType"));
        assertEquals(298000L, ((Number) agentRow.get("invoiceableFen")).longValue(),
                "可开票额不等于实付 —— 前端不再自己算，这个数错了就是开错票");

        // 申请一次之后就不该再出现在挑单里（避免重复提交）
        invoices.apply(agentUser, agentOrder, "某某科技有限公司", "91310000MA1FL0000X");
        var after = invoices.invoiceableOrders(agentUser, 50).stream()
                .map(r -> ((Number) r.get("orderId")).longValue()).toList();
        assertFalse(after.contains(agentOrder), "已经在处理中的单还留在挑单列表里");
    }

    // ============================================ 买方侧开票的角色闸（学员端已删）

    /**
     * 带身份的假请求。角色<b>必须由外面传</b>，不能在这里按 user id 猜 ——
     * 线上这一位是 {@link AuthInterceptor} 每次请求从 sys_user 现查的，
     * 夹具自己推导的话，测的就是夹具的推导而不是那道闸。
     */
    static MockHttpServletRequest as(long userId, String role) {
        var req = new MockHttpServletRequest();
        req.setRemoteAddr("203.0.113.9");
        req.setAttribute(AuthInterceptor.ATTR_USER_ID, userId);
        req.setAttribute(AuthInterceptor.ATTR_ROLE, role);
        return req;
    }

    /**
     * 学员调买方侧开票的三条接口一律 403。
     *
     * <h3>删掉界面不等于关掉功能</h3>
     * 2026-09-01 学员端「我的 → 发票」整块删除。如果只删界面，
     * {@code /api/invoice/**} 仍然收得进学员的申请 —— 存过一份请求或者会开 devtools 的人
     * 照样提交，而这条错<b>不报任何错</b>：申请安静地落库，堆在运营那边成为一批
     * 他们既没有流程也没有权限处理的工单。所以判据必须钉在 HTTP 面上。
     *
     * <p>三条都测：只测 apply 的话，留着 my / eligible-orders 敞开等于学员端还剩
     * 一块「看得见发票、只是提交不了」的半截功能，下一个人照着它就把入口接回去了。
     *
     * <p><b>前提先钉住</b>：这一单在<b>没有</b>角色闸时是能开出票的（下面那句
     * invoiceableOrders 断言），否则这个 403 也可能是「这单本来就开不了」造成的，
     * 用例绿着却什么都没证明。
     */
    @Test @Order(85)
    @DisplayName("★ 学员调 apply / my / eligible-orders 一律 403，且那句话告诉他下一步去哪")
    void studentInvoicingIsClosedAtTheHttpLayer() {
        long order = newPaidOrder("SUP_ORD_STU_GATE", STU_A, 79900);
        // 阴性对照：闸之外的每一个条件都是通的 —— 这一单此刻实实在在可开票。
        assertTrue(invoices.invoiceableOrders(STU_A, 50).stream()
                        .anyMatch(r -> ((Number) r.get("orderId")).longValue() == order),
                "这一单本来就开不了票，那下面那三个 403 证明不了角色闸存在");

        var stu = as(STU_A, "STUDENT");
        var e = expect(403, "联系客服",
                () -> invoiceApi.apply(new InvoiceController.ApplyReq(order, "某某公司", null), stu));
        expect(403, "联系客服", () -> invoiceApi.my(50, as(STU_A, "STUDENT")));
        expect(403, "联系客服", () -> invoiceApi.eligible(50, as(STU_A, "STUDENT")));

        // 文案：一句「没有权限」会让学员以为是账号出了问题，然后打客服电话，
        // 而客服要说的恰恰就是这句话本身。所以拒绝里必须带着下一步。
        assertFalse(e.getMessage().contains("没有权限"),
                "拒绝的措辞退回成了通用的『没有权限』：" + e.getMessage());
        assertTrue(e.getMessage().contains("开票") || e.getMessage().contains("发票"),
                "拒绝里没提这是发票的事，学员读完不知道被拒的是什么：" + e.getMessage());

        // 被拒的申请一行都不许落库 —— 否则运营侧照样会看到它
        assertEquals(0, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM invoice_request WHERE ref_id=?", Integer.class, order),
                "被 403 拒掉的申请仍然写进了 invoice_request");
    }

    /**
     * 正例：代理调同样三条接口照常返回 200。
     *
     * <p>这一条是防「把整个功能关死」的那一半。代理进货开票是正当业务，
     * 而它<b>没有别的入口</b>（代理端界面全仓 grep 为零，只有这三条 HTTP 路），
     * 所以把闸写成「登录就拒」或者不小心把白名单写错角色名的话，
     * B2B 那一半会静静地全灭：没有报错、没有告警，只有客服工单。
     */
    @Test @Order(86)
    @DisplayName("★ 代理调 apply / my / eligible-orders 照常 200 —— 进货开票是正当业务，不许被一起关掉")
    void agentInvoicingStillWorks() {
        long agentUser = 991L;
        jdbc.update("INSERT INTO sys_user (id,phone,role) VALUES (?,'13300000991','AGENT')", agentUser);
        long agentOrder = newPaidAgentOrder("SUP_AGT_2", agentUser, 298000);
        var agent = as(agentUser, "AGENT");

        @SuppressWarnings("unchecked")
        var eligible = (List<Map<String, Object>>) invoiceApi.eligible(50, agent).get("orders");
        assertTrue(eligible.stream()
                        .anyMatch(r -> ((Number) r.get("orderId")).longValue() == agentOrder),
                "代理的进货单不在可开票列表里 —— 角色闸把正当业务一起关掉了");

        var applied = invoiceApi.apply(
                new InvoiceController.ApplyReq(agentOrder, "某某科技有限公司", "91310000MA1FL0001X"), agent);
        assertEquals(Boolean.TRUE, applied.get("ok"));
        @SuppressWarnings("unchecked")
        var invoice = (Map<String, Object>) applied.get("invoice");
        assertEquals(298000L, invoice.get("amountFen"), "代理开出的金额不等于进货单实付");

        @SuppressWarnings("unchecked")
        var mine = (List<Map<String, Object>>) invoiceApi.my(50, agent).get("invoices");
        assertEquals(1, mine.size(), "代理查自己的开票记录查不到刚提交的那一笔");
        assertEquals(agentOrder, ((Number) mine.get(0).get("orderId")).longValue());
    }

    /** 造一张【已支付的代理进货单】，买家仍是同一个自然人。 */
    static long newPaidAgentOrder(String orderNo, long buyer, long paidFen) {
        jdbc.update("INSERT INTO agent_profile (user_id,agent_code,level,status)"
                  + " SELECT ?,?,1,'ACTIVE' FROM DUAL"
                  + " WHERE NOT EXISTS (SELECT 1 FROM agent_profile WHERE user_id=?)",
                buyer, "SUPA" + buyer, buyer);
        long agentId = ((Number) jdbc.queryForMap(
                "SELECT id FROM agent_profile WHERE user_id=?", buyer).get("id")).longValue();
        jdbc.update("INSERT INTO biz_order (order_no,buyer_user_id,buyer_type,buyer_agent_id,"
                  + "buyer_agent_level,sku_id,sku_code_snapshot,code_qty_snapshot,amount_fen,expire_at)"
                  + " VALUES (?,?,'AGENT',?,1,2,'AGENT_10',10,?,DATE_ADD(NOW(3), INTERVAL 2 HOUR))",
                orderNo, buyer, agentId, paidFen);
        long id = ((Number) jdbc.queryForMap("SELECT id FROM biz_order WHERE order_no=?", orderNo)
                .get("id")).longValue();
        jdbc.update("UPDATE biz_order SET status='PAID', paid_at=NOW(3), paid_amount_fen=? WHERE id=?",
                paidFen, id);
        return id;
    }
}