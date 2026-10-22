package tech.dragonai.wangke.support;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tech.dragonai.wangke.ItDb;

import javax.sql.DataSource;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 懂妈接进答疑管线之后的行为。
 *
 * <p>只用一个假 sidecar（本地 HTTP，回放固定的 SSE），<b>不连真模型</b>：
 * 这里要钉的不是"她答得好不好"，而是<b>她答不上来的时候这条问题去了哪里</b>。
 *
 * <h3>三条判据，后两条比第一条重要</h3>
 * <ol>
 *   <li>能答就答，工单记 AI_ANSWERED；
 *   <li><b>回答半截（流断了、没收到收尾标记）必须转人工</b> —— 半截答案落进 qa_message
 *       是最坏的一种：它看起来是一条正常回答，学员照着做，而缺掉的恰好是后半段；
 *   <li><b>调不通必须转人工</b> —— 若降级成一句「暂时无法回答」，工单状态就成了
 *       AI_ANSWERED，这条问题<b>再也不会进导师队列</b>，学员的问题无声消失。
 * </ol>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DongmaIT {

    static final String DB = "wangke_dongma_it";
    static final long COURSE = 1L;
    static final long STU = 40L;

    static DataSource ds;
    static JdbcTemplate jdbc;
    static TransactionTemplate tx;
    static HttpServer fake;
    static int fakePort;

    /**
     * 假 sidecar 这一轮怎么回。三种形态<b>是三件不同的事</b>，别合并：
     * <ul>
     *   <li>{@code normal} —— 正常说完，收尾发 done。
     *   <li>{@code cutoff} —— 连接断在半路，<b>什么收尾都没有</b>（网线拔了、上游超时）。
     *   <li>{@code truncated} —— 流<b>正常结束</b>，但上游 finish_reason='length'：
     *       回答在半句话上被切断。sidecar 此时发 error 帧而不是 done。
     *       <p>★ 这一档是补上去的，而它才是<b>真实世界里最常发生</b>的那种半截：
     *       reasoning_effort 产生的思考也吃 max_tokens，思考一涨正文就被挤断。
     *       实测同一个追问连打 5 次撞上 1 次（思考 1482 token，正文只剩 213 字、断在句中）。
     *       <p>而 sidecar 原来<b>无条件</b>发 done:true —— 它表达的是「上游的流结束了」，
     *       不是「答案说完了」。于是半截答案带着 done 落进 qa_message，
     *       DongmaClient 那道「没收到 done 就转人工」的闸整个被绕过去。
     *       这里原先只有 cutoff 一档，量的是网线断，<b>量具的取景框之外没有信号</b>。
     * </ul>
     */
    static final AtomicReference<String> MODE = new AtomicReference<>("normal");

    /** 上一次收到的请求体原文。追问那几条用例要看「懂妈到底被喂了什么」。 */
    static final AtomicReference<String> LAST_BODY = new AtomicReference<>("");

    static final String ANSWER = "CORS 在 L3 00:30:41 讲的，是前后端分离后绕不开的第一个坑。";

    @BeforeAll
    static void bootstrap() throws Exception {
        ds = ItDb.freshDatabase(DB);
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));

        jdbc.update("INSERT INTO sys_user (id,phone,role) VALUES (?,'13300000040','STUDENT')", STU);
        jdbc.update("INSERT INTO enrollment (user_id,course_id,source,valid_from,valid_until,"
                  + "agreement_version,agreed_at) VALUES (?,?, 'GRANT', NOW(3),"
                  + " DATE_ADD(NOW(3), INTERVAL 1 YEAR), 'v1', NOW(3))", STU, COURSE);

        fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakePort = fake.getAddress().getPort();
        fake.createContext("/ask", ex -> {
            LAST_BODY.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            ex.getResponseHeaders().add("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, 0);
            StringBuilder sb = new StringBuilder();
            for (String piece : ANSWER.split("(?<=\\G.{12})"))
                sb.append("data: {\"delta\":").append(quote(piece)).append("}\n\n");
            // 收尾这一下决定了这条回答算不算数，三种形态见 MODE 的注释
            switch (MODE.get()) {
                case "normal" ->
                    sb.append("data: {\"done\":true,\"text\":").append(quote(ANSWER)).append("}\n\n");
                case "truncated" ->
                    // 上游 finish_reason='length'：流是正常结束的，答案却断在半句话上
                    sb.append("data: {\"error\":\"回答被截断（length）\",\"truncated\":true}\n\n");
                default -> { /* cutoff：什么都不发，连接就这么断了 */ }
            }
            byte[] b = sb.toString().getBytes(StandardCharsets.UTF_8);
            ex.getResponseBody().write(b);
            ex.getResponseBody().close();
        });
        fake.start();
    }

    @AfterAll
    static void teardown() { if (fake != null) fake.stop(0); ItDb.close(ds); }

    private static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                default -> b.append(c);
            }
        }
        return b.append('"').toString();
    }

    private QaService svc(String baseUrl, String token) {
        return new QaService(jdbc, tx, new FaqSearch(jdbc),
                new DongmaClient(baseUrl, token, 10), 50);
    }

    private String statusOf(long ticketId) {
        return String.valueOf(jdbc.queryForMap(
                "SELECT status FROM qa_ticket WHERE id=?", ticketId).get("status"));
    }

    @Test @Order(1)
    @DisplayName("问答库没命中时，懂妈接手：工单记 AI_ANSWERED，答案原样落库")
    void dongmaAnswersWhenFaqMisses() {
        var qa = svc("http://127.0.0.1:" + fakePort, "test-token");
        var a = qa.ask(STU, COURSE, null, "CORS", "CORS 是什么？课上哪一讲讲的", false);

        assertTrue(a.answered(), "懂妈答上了，但结果里说没答");
        assertEquals("AI_ANSWERED", a.status());
        assertNull(a.faqId(), "这条不是从问答库来的，faqId 必须为空，否则出处会指到一条无关的 FAQ 上");
        assertEquals(ANSWER, a.answer());

        var msgs = jdbc.queryForList(
                "SELECT sender_type, content, citations FROM qa_message WHERE ticket_id=? ORDER BY id",
                a.ticketId());
        assertEquals(2, msgs.size(), "应当是『学员问 + AI 答』两条");
        assertEquals("AI", msgs.get(1).get("sender_type"));
        assertEquals(ANSWER, msgs.get(1).get("content"));
        assertNull(msgs.get(1).get("citations"),
                "懂妈的出处写在正文的时间码里；塞一个形状不符的 citations，"
              + "按 [{lesson_id,ts_sec}] 渲染的前端会静默什么都不显示");

        assertNull(jdbc.queryForMap("SELECT ai_confidence FROM qa_ticket WHERE id=?", a.ticketId())
                .get("ai_confidence"), "没有真置信度就必须留 NULL，不许硬编一个数");
    }

    @Test @Order(2)
    @DisplayName("★ 连接断在半路（没有收尾标记）：必须转人工，不许把半截当答案")
    void cutoffAnswerEscalates() {
        MODE.set("cutoff");
        try {
            var qa = svc("http://127.0.0.1:" + fakePort, "test-token");
            var a = qa.ask(STU, COURSE, null, "半截", "这一问会拿到一个断掉的回答", false);

            assertFalse(a.answered(), "半截回答被当成了正常作答");
            assertEquals("ESCALATED", a.status());
            assertEquals("LOW_CONFIDENCE",
                    jdbc.queryForMap("SELECT escalate_reason FROM qa_ticket WHERE id=?", a.ticketId())
                            .get("escalate_reason"));
            assertEquals(1, msgCount(a.ticketId()),
                    "只该有学员那一条；半截答案一旦落库，学员照着做而缺的正是后半段");
        } finally {
            MODE.set("normal");
        }
    }

    @Test @Order(2) @DisplayName(
        "★★ 回答被上游切断（finish_reason=length，流却正常结束）：同样必须转人工")
    void lengthTruncatedAnswerEscalates() {
        // 这一档比 cutoff 更要命：那一头看起来一切正常 —— 流走完了、没有异常、
        // 前面的 delta 一段不少。少的只是最后半句话，而半句话读起来和一整句一样通顺。
        MODE.set("truncated");
        try {
            var qa = svc("http://127.0.0.1:" + fakePort, "test-token");
            var a = qa.ask(STU, COURSE, null, "被切断", "这一问会被上游按 length 切断", false);

            assertFalse(a.answered(), "被切断的回答被当成了正常作答");
            assertEquals("ESCALATED", a.status());
            assertEquals(1, msgCount(a.ticketId()),
                    "半截答案落库了 —— 它看起来是一条正常回答，缺的恰好是后半段的注意事项");
        } finally {
            MODE.set("normal");
        }
    }

    @Test @Order(3)
    @DisplayName("★ 懂妈调不通：必须转人工，绝不能降级成一句「暂时无法回答」")
    void unreachableEscalatesInsteadOfFakeAnswer() {
        // 127.0.0.1:1 上不会有人监听 —— 连接直接被拒，是最干脆的一种不可达
        var qa = svc("http://127.0.0.1:1", "test-token");
        var a = qa.ask(STU, COURSE, null, "服务挂了", "这一问打不到懂妈身上", false);

        assertFalse(a.answered());
        assertEquals("ESCALATED", a.status(),
                "降级成 AI_ANSWERED 的话，这条问题再也不会进导师队列，学员的问题就此消失");
        assertNotNull(jdbc.queryForMap("SELECT sla_due_at FROM qa_ticket WHERE id=?", a.ticketId())
                .get("sla_due_at"), "转了人工却没有 SLA，导师队列排不上它");
        assertEquals(1, msgCount(a.ticketId()));
    }

    @Test @Order(4)
    @DisplayName("没配令牌 = 这套环境没有懂妈：行为退回接她之前，问答库没命中就转人工")
    void disabledFallsBackToPreviousBehaviour() {
        var qa = svc("http://127.0.0.1:" + fakePort, "");
        var a = qa.ask(STU, COURSE, null, "未配置", "这套环境没有懂妈", false);
        assertFalse(a.answered());
        assertEquals("ESCALATED", a.status());
    }

    // ================================================================= 追问

    @Test @Order(5)
    @DisplayName("★ 追问也由懂妈接：不能扣了配额只查问答库，没命中就把人推给老师")
    void followUpAlsoGoesToDongma() {
        var qa = svc("http://127.0.0.1:" + fakePort, "test-token");
        var a = qa.ask(STU, COURSE, null, "追问链路", "第一问，由懂妈答", false);
        assertEquals("AI_ANSWERED", a.status());

        qa.reply(STU, a.ticketId(), "那第二步具体怎么做？");

        assertEquals("AI_ANSWERED", statusOf(a.ticketId()),
                "追问被懂妈答上了，工单却进了导师队列 —— 老师会收到一堆她本来答得了的问题");
        var msgs = jdbc.queryForList(
                "SELECT sender_type, content FROM qa_message WHERE ticket_id=? ORDER BY id",
                a.ticketId());
        assertEquals(4, msgs.size(), "应当是 学员问/AI答/学员追问/AI答 四条");
        assertEquals("AI", msgs.get(3).get("sender_type"), "追问之后没有任何答复落库");
        assertEquals(ANSWER, msgs.get(3).get("content"));

        assertNull(jdbc.queryForMap("SELECT sla_due_at FROM qa_ticket WHERE id=?", a.ticketId())
                .get("sla_due_at"), "没转人工却挂上了 SLA，导师队列会排进一条不该排的");
    }

    @Test @Order(6)
    @DisplayName("★ 追问要带上下文，而且当前这句不能在 history 里重复出现一次")
    void followUpCarriesHistoryWithoutDuplicatingTheQuestion() {
        var qa = svc("http://127.0.0.1:" + fakePort, "test-token");
        var a = qa.ask(STU, COURSE, null, "上下文", "开场白：这是第一问", false);

        // 安全拦下的消息不能被喂回模型：它在详情页里已经被换成一句提示，
        // 原文再进模型等于安全拦截只拦住了人眼 —— 而模型的回答是会落库、会被学员看到的
        jdbc.update("INSERT INTO qa_message (ticket_id,sender_type,sender_id,content,safety_verdict)"
                  + " VALUES (?,'STUDENT',?,?, 'BLOCKED')", a.ticketId(), STU, "违规原文不许外流");

        LAST_BODY.set("");
        qa.reply(STU, a.ticketId(), "接着上面那句，第二步呢？");

        String body = LAST_BODY.get();
        assertTrue(body.contains("开场白：这是第一问"), "追问没带上下文，她会把「第二步呢」当成没头没尾的一句");
        assertFalse(body.contains("违规原文不许外流"), "被安全拦下的消息被原样喂回了模型");

        // question 与 history 的最后一条不能是同一句：先写消息再取历史的话，
        // 同一句话会说两遍，模型会把它当成学员在重复强调
        int lastHistory = body.lastIndexOf("接着上面那句，第二步呢？");
        int firstHit = body.indexOf("接着上面那句，第二步呢？");
        assertEquals(firstHit, lastHistory, "当前这句在载荷里出现了两次（history 里也塞了一份）");
    }

    @Test @Order(7)
    @DisplayName("★ 追问时懂妈调不通：照样转人工，且带上 SLA")
    void followUpEscalatesWhenDongmaIsDown() {
        var up = svc("http://127.0.0.1:" + fakePort, "test-token");
        var a = up.ask(STU, COURSE, null, "追问断供", "第一问正常", false);
        assertEquals("AI_ANSWERED", a.status());

        var down = svc("http://127.0.0.1:1", "test-token");
        down.reply(STU, a.ticketId(), "她这会儿不在，这条得有人接");

        assertEquals("ESCALATED", statusOf(a.ticketId()));
        var t = jdbc.queryForMap("SELECT escalate_reason, sla_due_at FROM qa_ticket WHERE id=?",
                a.ticketId());
        assertEquals("LOW_CONFIDENCE", t.get("escalate_reason"));
        assertNotNull(t.get("sla_due_at"), "转了人工却没有 SLA，导师队列排不上它");
        assertEquals(3, msgCount(a.ticketId()), "应当是 学员问/AI答/学员追问 三条，不该有第四条");
    }

    @Test @Order(8)
    @DisplayName("管理端能按状态把已回复的工单找回来 —— 否则关闭与沉淀 FAQ 都没有入口")
    void queueCanFindRepliedTickets() {
        var qa = svc("http://127.0.0.1:1", "test-token");   // 打不通 → 直接落到 ESCALATED
        var a = qa.ask(STU, COURSE, null, "找得回来", "这一单会被人工接手", false);
        assertEquals("ESCALATED", a.status());

        assertTrue(qa.queue(1L, "PLATFORM_ADMIN", null, 100).stream()
                        .anyMatch(m -> Long.valueOf(a.ticketId()).equals(m.get("id"))),
                "默认档就是待处理队列");

        qa.staffReply(1L, "PLATFORM_ADMIN", a.ticketId(), "老师来答一下");
        assertEquals("HUMAN_ANSWERED", statusOf(a.ticketId()));

        assertFalse(qa.queue(1L, "PLATFORM_ADMIN", null, 100).stream()
                        .anyMatch(m -> Long.valueOf(a.ticketId()).equals(m.get("id"))),
                "回复完还留在待处理队列里，导师会重复处理");
        assertTrue(qa.queue(1L, "PLATFORM_ADMIN", "HUMAN_ANSWERED", 100).stream()
                        .anyMatch(m -> Long.valueOf(a.ticketId()).equals(m.get("id"))),
                "★ 换成「人工已答」也找不回来 —— 那么关闭工单、沉淀公开问答就再没有入口了");

        // 认不出来的值退回默认档，不是「查不到」：拼错一个状态返回空表，
        // 与「这一档真的没有工单」在屏幕上分不出来
        assertEquals(qa.queue(1L, "PLATFORM_ADMIN", null, 100).size(),
                qa.queue(1L, "PLATFORM_ADMIN", "NOT_A_STATUS", 100).size());
    }

    @Test @Order(9)
    @DisplayName("★ 追问要如实回报剩余配额：AI 档扣了就报数，人工档没扣就报 null（不是 0）")
    void followUpReportsQuotaHonestly() {
        var qa = svc("http://127.0.0.1:" + fakePort, "test-token");
        var a = qa.ask(STU, COURSE, null, "配额回报", "第一问，由懂妈答", false);
        assertEquals("AI_ANSWERED", a.status());

        Integer afterFollowUp = qa.reply(STU, a.ticketId(), "接着问一句，这条要扣配额");
        assertNotNull(afterFollowUp,
                "AI 档的追问是一次真实的模型调用，配额确实扣了。扣了却不回报的话，"
              + "常驻挂件上那句「今天还可以问 N 次」只会在他提【新问题】时更新 —— "
              + "一个越看越不准的数，比不显示更坏");
        assertEquals(a.quotaLeftToday() - 1, afterFollowUp.intValue());

        // 转人工之后再追问：那是发给导师看的，不消耗配额
        jdbc.update("UPDATE qa_ticket SET status='HUMAN_ANSWERED' WHERE id=?", a.ticketId());
        assertNull(qa.reply(STU, a.ticketId(), "老师，我还有一处不明白"),
                "没扣配额却报了个数。报 0 会让学员以为自己被卡住了，"
              + "而他在人工档下本来可以一直追问下去 —— 「没扣」和「剩 0 次」是两件事");
        assertEquals(afterFollowUp.intValue(), 50 - usedToday(),
                "人工档的那条追问把配额也扣了");
    }

    // ================================================================= 流式

    @Test @Order(10)
    @DisplayName("★ 流式：答案是一段段送出来的，而且拼起来与落库的那一份逐字相同")
    void streamingFeedsDeltasAsTheyCome() {
        var qa = svc("http://127.0.0.1:" + fakePort, "test-token");
        List<String> deltas = new ArrayList<>();
        var a = qa.askStreaming(STU, COURSE, null, "流式", "这一问要一段段送回来", false,
                deltas::add);

        assertTrue(deltas.size() > 1,
                "只来了 " + deltas.size() + " 段 —— 那不是流式，是攒完一次性发。"
              + "真实现场里这一步最容易【看起来对】：后端确实边收边发，"
              + "而 nginx 默认把上游响应攒起来，浏览器仍然是最后一刻一次性收到");
        assertEquals(ANSWER, String.join("", deltas), "送出去的和最终答案对不上");
        assertEquals(ANSWER, a.answer());
        assertEquals("AI_ANSWERED", statusOf(a.ticketId()));
    }

    @Test @Order(11)
    @DisplayName("★★ 流式期间工单必须是 ESCALATED —— 中途挂掉也得有人管")
    void ticketIsEscalatedWhileStillStreaming() {
        // 这一条钉的是「方向朝哪边倒」：先写 AI_ANSWERED 再改，进程一挂就留下
        // 一张【状态写着已解答、一个字答复都没有】的工单 —— 它不在导师队列里，
        // 谁都不会再看它一眼，学员的问题就此无声消失。
        var qa = svc("http://127.0.0.1:" + fakePort, "test-token");
        AtomicReference<String> midway = new AtomicReference<>();
        var a = qa.askStreaming(STU, COURSE, null, "中途状态", "答到一半的时候这单是什么状态", false,
                d -> midway.compareAndSet(null, newestTicketStatus()));

        assertEquals("ESCALATED", midway.get(),
                "流还没走完，工单就已经写成了「已解答」—— 此刻进程一挂，这条问题就再也没人管了");
        assertEquals("AI_ANSWERED", statusOf(a.ticketId()), "答完了却没改回来");
        assertNull(jdbc.queryForMap("SELECT sla_due_at FROM qa_ticket WHERE id=?", a.ticketId())
                .get("sla_due_at"),
                "答完了 SLA 没清 —— 导师队列按 sla_due_at 排，它会以「最快超时」赖在队首");
    }

    @Test @Order(12)
    @DisplayName("★★ 流式 + 被截断：字已经喂出去了，但一个都不许落库，工单留在待人工")
    void truncatedStreamStoresNothing() {
        MODE.set("truncated");
        try {
            var qa = svc("http://127.0.0.1:" + fakePort, "test-token");
            List<String> deltas = new ArrayList<>();
            var a = qa.askStreaming(STU, COURSE, null, "流式截断", "这一问会被上游切断", false,
                    deltas::add);

            assertFalse(deltas.isEmpty(), "前半段本来就该已经送到学员屏幕上了");
            assertFalse(a.answered());
            assertEquals("ESCALATED", statusOf(a.ticketId()));
            assertEquals(1, msgCount(a.ticketId()),
                    "★ 半截答案落库了。喂出去的字只是给人看的，不代表这段话算数 —— "
                  + "落了库它就会以一条正常答复的样子留在工单里，日后谁看都当真");
            assertNotNull(jdbc.queryForMap("SELECT sla_due_at FROM qa_ticket WHERE id=?", a.ticketId())
                    .get("sla_due_at"), "转了人工却没有 SLA，导师队列排不上它");
        } finally {
            MODE.set("normal");
        }
    }

    @Test @Order(13)
    @DisplayName("★ 流式追问：同样一段段送，同样在答成之后才把工单改回 AI_ANSWERED")
    void followUpStreamsToo() {
        var qa = svc("http://127.0.0.1:" + fakePort, "test-token");
        var a = qa.askStreaming(STU, COURSE, null, "流式追问", "第一问，由懂妈答", false, d -> { });
        assertEquals("AI_ANSWERED", statusOf(a.ticketId()));

        List<String> deltas = new ArrayList<>();
        AtomicReference<String> midway = new AtomicReference<>();
        qa.replyStreaming(STU, a.ticketId(), "那第二步呢？", d -> {
            deltas.add(d);
            midway.compareAndSet(null, statusOf(a.ticketId()));
        });

        assertTrue(deltas.size() > 1, "追问没走流式，只来了 " + deltas.size() + " 段");
        assertEquals("ESCALATED", midway.get(), "追问答到一半时工单就写成了已解答");
        assertEquals("AI_ANSWERED", statusOf(a.ticketId()));
        assertEquals(4, msgCount(a.ticketId()), "应当是 学员问/AI答/学员追问/AI答 四条");
    }

    @Test @Order(14)
    @DisplayName("★ 学员中途关掉页面：不再白等模型说完，也不落一份没人看的答案")
    void clientGoneStopsEverything() {
        var qa = svc("http://127.0.0.1:" + fakePort, "test-token");
        var a = qa.askStreaming(STU, COURSE, null, "人走了", "答到一半人就关页面了", false,
                d -> { throw new RuntimeException("客户端断开"); });

        assertFalse(a.answered(), "对面都走了，还把这一单记成已解答");
        assertEquals("ESCALATED", statusOf(a.ticketId()));
        assertEquals(1, msgCount(a.ticketId()), "落了一份没有人会看的答案");
    }

    /** 库里最新那一张工单的状态。流式进行中拿不到 ticketId，只能这么看。 */
    static String newestTicketStatus() {
        return String.valueOf(jdbc.queryForList(
                "SELECT status FROM qa_ticket ORDER BY id DESC LIMIT 1").get(0).get("status"));
    }

    static int usedToday() {
        Integer n = jdbc.queryForObject(
                "SELECT ask_count FROM qa_quota_usage WHERE user_id=? AND stat_date=CURDATE()",
                Integer.class, STU);
        return n == null ? 0 : n;
    }

    static int msgCount(long ticketId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM qa_message WHERE ticket_id=?",
                Integer.class, ticketId);
    }
}
