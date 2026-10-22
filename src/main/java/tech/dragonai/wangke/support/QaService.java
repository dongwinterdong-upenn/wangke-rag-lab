package tech.dragonai.wangke.support;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tech.dragonai.wangke.web.ApiException;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 答疑（懂妈）。工单 = 一次提问 + 若干来回消息，落在 qa_ticket / qa_message 上。
 *
 * <h3>这一层真正守的三件事</h3>
 * <ol>
 *   <li><b>配额</b>。AI 答疑是课程内容的泄漏面：一个买了一份课的人，可以用提问把课件
 *       一段段问出来。qa_quota_usage 是那道闸，而闸的判定必须是<b>原子的测试并置位</b>，
 *       不能「先查今天用了几次，再决定让不让过」—— 并发下两条请求会同时读到 19、同时放行。
 *   <li><b>越权</b>。工单里有学员自己写的问题正文，越权读到就是读到别人的私信。
 *       条件一律进 WHERE，不是查出来再比对 owner（见 {@link #scopeOf}）。
 *   <li><b>不静默丢掉追问</b>。学员在一条已被人工回复的工单下追问，如果状态不回到队列里，
 *       这条追问谁都不会再看见 —— 没有报错、没有超时、工单看起来是「已回复」。
 * </ol>
 *
 * <h3>关于「AI 回答」这件事的诚实说明</h3>
 * {@code sender_type='AI'} 这一档下面其实有<b>两种</b>来源，它们在库里长得一模一样：
 * 命中公开问答库时给出的是<b>人写好</b>的答案，没命中时才是懂妈现答的
 * （{@link DongmaClient}，只在配了令牌的环境里在场）。记成 AI 说的是「自动答」这条<b>渠道</b>，
 * 不是断言它一定由模型生成 —— 学员端对两者统一署名「懂妈」，也是这个道理：
 * 分别署名只会让人以为平台上有两个不同的人在答，而他分不清该信哪个。
 *
 * <p>两条链路（首问 {@link #ask} 与追问 {@link #reply}）的次序必须<b>完全一致</b>：
 * 先查问答库、再问懂妈、都不行才转人工。任何一侧偷工，表现都不是报错，
 * 而是「同一个问题换个地方问，答案不一样」。
 *
 * <p>ai_confidence 一律留 NULL：全文检索的 relevance 不是概率，把它线性缩放到 0~1
 * 会让「低于阈值升级人工」那条闸的阈值变成一个没有单位的数 —— 谁都调不动，也说不清调了什么。
 * 懂妈这一侧同样不填：她没有给出可用的置信度，凭感觉写一个数比留空更坏。
 * schema 的 ck_qa_confidence 会把 0~1 之外的值挡住。
 */
@Service
public class QaService {

    /**
     * 每人每天可发起的 AI 提问数。<b>★待需求方拍板</b>。
     * 放这里而不是 schema：它是策略，会随成本和滥用情况调；写进 CHECK 就得改表才能调。
     */
    private final int dailyAskQuota;

    /** 升级人工后的应答时限。进 qa_ticket.sla_due_at，导师队列按 idx_queue(status,sla_due_at) 排。 */
    private static final int SLA_HOURS = 24;

    private static final int MAX_TITLE_CHARS = 120;
    private static final int MAX_CONTENT_CHARS = 2000;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final FaqSearch faq;
    private final DongmaClient dongma;

    public QaService(JdbcTemplate jdbc, TransactionTemplate tx, FaqSearch faq, DongmaClient dongma,
                     @Value("${wangke.qa.daily-ask-quota:20}") int dailyAskQuota) {
        this.jdbc = jdbc; this.tx = tx; this.faq = faq; this.dongma = dongma;
        this.dailyAskQuota = dailyAskQuota;
    }

    /** 配额用尽。单独一个类型，为的是能在事务外面捕获它去记 blocked_count（见 {@link #ask}）。 */
    private static class QuotaExceeded extends RuntimeException {
        QuotaExceeded(String m) { super(m); }
    }

    /**
     * @param answered true = 当场从公开问答库答上了；false = 已转人工，等导师
     */
    public record Asked(long ticketId, String status, boolean answered,
                        String answer, Long faqId, int quotaLeftToday) {}

    // ================================================================= 提问

    /** 提问（不流式）。与 {@link #askStreaming} 是同一条链，只是没人接那些字。 */
    public Asked ask(long userId, long courseId, Long lessonId, String title, String content,
                     boolean wantHuman) {
        return askStreaming(userId, courseId, lessonId, title, content, wantHuman, d -> { });
    }

    /**
     * 提问，边答边送。
     *
     * <h3>★ 事务从"一个大的"拆成了"前后两个小的"，这是这一版最要紧的改动</h3>
     * 原来是：配额 + 建单 + 两条消息 + <b>那 4 秒的模型调用</b> 全在一个事务里。
     * 流式做不到这样 —— 边收边往浏览器写的连接不能挂在事务里，而且那样也没法"边写边看"。
     *
     * 拆法：
     * <pre>
     *   事务一（快）：扣配额 + 建单 + 落学员那条消息        ← 建出来的单是 ESCALATED
     *   事务外（慢）：查问答库 → 问懂妈，逐段喂给浏览器
     *   事务二（快）：答全了才落 AI 那条消息并把单改成 AI_ANSWERED
     * </pre>
     *
     * <h3>为什么建单时先写 ESCALATED，答成了再改回来</h3>
     * <b>方向必须朝"失败了也有人管"那边倒。</b>反过来（先写 AI_ANSWERED，答成了不动、
     * 答砸了再改成 ESCALATED）看起来更顺，但进程若在这中间挂掉 —— 部署重启、OOM、
     * 网络卡死 —— 留下的是一张<b>状态写着「已解答」而一个字答复都没有</b>的工单：
     * 它不在导师队列里，谁都不会再看它一眼，学员的问题就此无声消失。
     * 而现在这个方向，最坏情况是导师收到一条懂妈本来答得了的问题，多花一次人工，
     * 仅此而已。<b>代价不对称的时候，默认值要选代价小的那一边。</b>
     *
     * <p>副作用是有 4 秒左右的窗口，这张单在导师待办里露一下脸又消失。
     * 这个代价是知情的：拿"偶尔看见一条一闪而过的待办"换"绝不丢问题"。
     *
     * <p>另一个白捡的好处：那 4 秒不再攥着 {@code qa_quota_usage} 的行锁。
     * 原来的写法要把这一行锁满整个模型调用，现在扣完就放。
     *
     * <h3>onDelta 的合同</h3>
     * 喂出去的字<b>只是给人看的，不代表这段话算数了</b>。懂妈没说完时
     * {@link DongmaClient#answerStreaming} 返回空，这里一个字都不落库、工单留在 ESCALATED，
     * 而<b>调用方必须把已经显示出去的半截撤掉</b>（见 QaController 的 aborted 帧）。
     * 屏幕上留着一段不作数的答复，比不显示更坏 —— 半句话读起来和一整句一样通顺。
     */
    public Asked askStreaming(long userId, long courseId, Long lessonId,
                              String title, String content, boolean wantHuman,
                              java.util.function.Consumer<String> onDelta) {
        String t = requireText(title, MAX_TITLE_CHARS, "标题");
        String c = requireText(content, MAX_CONTENT_CHARS, "问题正文");

        // 权益闸提到事务外面：它是只读判断，放里放外结果一样，
        // 但放外面就不会为一个根本没买课的人建出配额行来。
        requireEnrollment(userId, courseId);
        // 「今天」与「今天那一行」都在事务【之前】备好，理由见 ensureTodayRow：
        // 那条可能失败的 INSERT 必须待在自己的事务里当场结束，不能带进业务事务。
        LocalDate today = dbToday();
        ensureTodayRow(userId, today);

        Reserved r;
        try {
            r = inTx(st -> reserve(userId, courseId, lessonId, t, c, wantHuman, today));
        } catch (QuotaExceeded e) {
            // 事务已回滚，这条自增走的是一条新的自动提交连接
            bumpBlocked(userId, today);
            throw ApiException.forbidden(e.getMessage());
        }

        if (wantHuman)
            return new Asked(r.ticketId(), "ESCALATED", false, null, null, r.quotaLeft());

        Answer a = produce(courseId, title + " " + content, c, List.of(), onDelta);
        if (a == null)
            return new Asked(r.ticketId(), "ESCALATED", false, null, null, r.quotaLeft());

        settle(r.ticketId(), a);
        return new Asked(r.ticketId(), "AI_ANSWERED", true, a.text(), a.faqId(), r.quotaLeft());
    }

    private record Reserved(long ticketId, int quotaLeft) {}

    /** 一段答复，以及它是不是从问答库来的（决定 citations 与 hit_count）。 */
    private record Answer(String text, Long faqId) {}

    /**
     * 事务一：扣配额、建单、落学员那条消息。<b>建出来的单一律是 ESCALATED</b>，理由见
     * {@link #askStreaming} 的「方向必须朝失败了也有人管那边倒」。
     */
    private Reserved reserve(long userId, long courseId, Long lessonId, String title,
                             String content, boolean wantHuman, LocalDate today) {
        if (lessonId != null) {
            // 讲次必须属于这门课。不校验的话，带着别的课的 lesson_id 提问，
            // 答案里的出处就会指向一门他没买的课的讲次 —— 泄漏是从 citations 里漏的。
            Integer okLesson = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM lesson WHERE id=? AND course_id=?",
                    Integer.class, lessonId, courseId);
            if (okLesson == null || okLesson == 0)
                throw ApiException.badRequest("所选讲次不属于这门课程");
        }
        int used = consumeQuota(userId, today);
        long ticketId = insertTicket(userId, courseId, lessonId, title, "ESCALATED",
                wantHuman ? "USER_REQUEST" : "LOW_CONFIDENCE");
        insertMessage(ticketId, "STUDENT", userId, content, null);
        return new Reserved(ticketId, Math.max(0, dailyAskQuota - used));
    }

    /**
     * 拿到一段答复：<b>先查人写好的问答库，没命中才问懂妈</b>。
     *
     * <p>次序不能反。反过来的话，运营辛苦沉淀的 FAQ 永远不会被用到，
     * 而且同一个问题每次的答案都不一样。首问与追问共用这一个方法，
     * 就是为了让这条次序<b>只有一处</b> —— 两处写，迟早只在一边成立。
     *
     * @return 答不出来（或这套环境没有懂妈）返回 null
     */
    private Answer produce(long courseId, String searchText, String question,
                           List<Map<String, String>> history,
                           java.util.function.Consumer<String> onDelta) {
        // 这里【不设相关度阈值】：MATCH 的分数没有量纲、跨语料不可比，
        // 拿一个拍脑袋的常数去卡它，只会得到一个谁都不敢动、也说不清含义的魔数。
        List<FaqSearch.Hit> hits = safeSearch(courseId, searchText);
        if (!hits.isEmpty()) {
            // 问答库命中是瞬时的，没有"流"可言。但仍然走一次 onDelta：
            // 前端只有一条渲染路径，两种来源走同一条，才不会出现
            // 「命中 FAQ 时那一屏长得不一样」。
            onDelta.accept(hits.get(0).answer());
            return new Answer(hits.get(0).answer(), hits.get(0).faqId());
        }
        if (!dongma.enabled()) return null;
        return dongma.answerStreaming(question, history, onDelta)
                .map(txt -> new Answer(txt, null)).orElse(null);
    }

    /**
     * 事务二：把答复落库，并把工单从 ESCALATED 改回 AI_ANSWERED。
     *
     * <p>{@code sla_due_at} 必须一起清掉。留着的话，一张已经答完的单会带着一个应答期限
     * 躺在库里，而导师队列正是按 {@code sla_due_at} 排的 —— 它会以"最快超时"的姿态
     * 排在队首，直到有人手工去动它。
     */
    private void settle(long ticketId, Answer a) {
        tx.executeWithoutResult(st -> {
            if (a.faqId() != null) {
                insertMessage(ticketId, "AI", null, a.text(), citationOf(a.faqId()));
                // 命中计数记在「真的拿它答过一次」上，而不是记在检索结果里：
                // 检索一次翻十条，十条全 +1 的话，hit_count 量的是曝光不是有用，
                // 而这一列的唯一用途就是挑出「真在解决问题」的条目去优先维护。
                jdbc.update("UPDATE faq_entry SET hit_count=hit_count+1 WHERE id=?", a.faqId());
            } else {
                // citations 留 NULL：懂妈的出处是写在正文里的「L3 02:01:33」这类时间码，
                // 而 citations 这一列的约定形状是 [{lesson_id, ts_sec, snippet}]。
                // 塞一个形状不符的对象进去，将来按它渲染跳转的前端会静默什么都不显示。
                insertMessage(ticketId, "AI", null, a.text(), null);
            }
            jdbc.update("UPDATE qa_ticket SET status='AI_ANSWERED', escalate_reason=NULL, "
                      + "sla_due_at=NULL WHERE id=?", ticketId);
        });
    }

    /**
     * 跑一个事务，撞上死锁就<b>整个重跑</b>。
     *
     * <h3>为什么是「整个重跑」而不是在事务里重试那条语句</h3>
     * MySQL 判定死锁时回滚的是<b>整个事务</b>，不是那一条语句。被选为牺牲者之后
     * 继续在同一个连接上发下一条语句，等于在一个<b>新事务</b>里接着写，
     * 而这之前写好的那几行早已不在了 —— 最后 commit 还会成功，
     * 落库的是半张工单（ticket 在、消息不在，或者配额扣了、工单没建），
     * 而且<b>没有任何报错</b>。这是这一类 bug 最坏的形态：它以"成功"收场。
     *
     * <h3>它是兜底，不是正解</h3>
     * 这里原本每天第一批提问会成片撞死锁，根因是<b>那条会失败的 INSERT 待在业务事务里</b>，
     * 已经在 {@link #ensureTodayRow} 里挪出去了 —— 那才是正解。
     * 重试治不了必然会撞的锁，只会把每一次都变成"撞几次再跑一次"，
     * 而且重试次数一旦不够（实测 16 线程时 4 次就不够）照样以 500 收场。
     *
     * <p>留着这层的理由是另外两种：锁等待超时（对手的事务慢了，1205），
     * 以及这条链上其它行锁将来可能形成的环。它们是真·偶发，重试正合适。
     *
     * <p>重试次数用完就把原异常抛出去（→ 500 + 完整日志）。
     * 吞掉它换成一句业务提示是错的：那样就再也没人知道这里在撞锁了。
     */
    private <T> T inTx(org.springframework.transaction.support.TransactionCallback<T> action) {
        org.springframework.dao.ConcurrencyFailureException last = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                return tx.execute(action);
            } catch (org.springframework.dao.ConcurrencyFailureException e) {
                last = e;
                // 退避一下再上：两个牺牲者若以同样的节奏立刻重来，会以同样的姿势再撞一次
                try {
                    Thread.sleep(10L << attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw last;
    }


    /**
     * 原子地占用一次配额，返回占用后的今日已用次数。
     *
     * <p><b>为什么不是「先 SELECT ask_count 再决定」</b>：两条并发请求会同时读到 19，
     * 同时判定「还剩 1 次」，然后双双写成 20 —— 配额在并发下恒等于形同虚设，
     * 而且这种漏放没有任何痕迹（计数最终仍然是 20，看起来完全正常）。
     *
     * <p>裁决点是 {@code UPDATE ... WHERE ask_count < 上限} 影响的行数：
     * 由于 SET 一定改变了值，「匹配行数」与「改变行数」在这条语句上恒等，
     * 所以它不受 Connector/J 的 useAffectedRows / CLIENT_FOUND_ROWS 差异影响 ——
     * 换成 {@code INSERT ... ON DUPLICATE KEY UPDATE ask_count=IF(...)} 就会踩这个坑：
     * 「没变化」在两种标志位下分别返回 0 和 1，同一份代码在不同连接参数下判定相反。
     *
     * <p>调用它之前，今天那一行必须已经由 {@link #ensureTodayRow} 在<b>事务外</b>建好。
     * 于是这里影响 0 行就只剩一个含义：<b>到顶了</b> ——
     * 不再有「0 行了再去 INSERT 试试」那层循环，少一个分支，也少一处会说错话的地方。
     */
    private int consumeQuota(long userId, LocalDate today) {
        int n = jdbc.update(
                "UPDATE qa_quota_usage SET ask_count=ask_count+1 "
              + " WHERE user_id=? AND stat_date=? AND ask_count<?",
                userId, today, dailyAskQuota);
        if (n != 1)
            throw new QuotaExceeded("今天的答疑次数已用完（每天 " + dailyAskQuota
                    + " 次），明天再来；着急的话可以在工单里申请转人工");

        Integer used = jdbc.queryForObject(
                "SELECT ask_count FROM qa_quota_usage WHERE user_id=? AND stat_date=?",
                Integer.class, userId, today);
        return used == null ? dailyAskQuota : used;
    }

    /**
     * 「今天」取<b>数据库的</b>日期，一次调用里只取一次，之后每条语句都用这个值。
     *
     * <p>不直接在三条语句里各写一个 {@code CURDATE()}：它们是各自求值的，
     * 跨零点的那一瞬间前一条落在昨天、后一条落在今天，于是建出来的行和要改的行不是同一行，
     * 表现是<b>用户今天一次没问过，却被告知配额已用完</b>。
     *
     * <p>也不用 JVM 的 {@code LocalDate.now()}：容器基本都跑在 UTC，而业务时区是 Asia/Shanghai，
     * 每天有 8 小时窗口两边算出来根本不是同一天。这种偏差不会报错，
     * 只会让配额在一个谁也说不清的时刻重置。日期归谁管必须只有一个答案，这里选数据库。
     */
    private LocalDate dbToday() {
        return jdbc.queryForObject("SELECT CURDATE()", LocalDate.class);
    }

    /**
     * 保证今天这一行在。撞重复键说明别人刚建好，那正是想要的结果。
     *
     * <p><b>必须在业务事务【之外】调用</b>（调用点都在 {@code tx.execute} 之前，
     * 于是它走的是一条自动提交的连接，语句一结束锁就还回去了）。
     * 这不是风格问题，是这个方法唯一的存在理由 —— 见下面的实测。
     *
     * <h3>配额行的抢建是这条链上唯一真会死锁的地方</h3>
     * 当天第一批请求会同时去抢建同一个主键 {@code (user_id, stat_date)}。
     * 三种写法在同一个量具下的表现（20 轮，每轮换一个 user_id，
     * 用栅栏把所有事务按在同一步上，再一起放开 —— 真实调度下这个交错要碰运气，
     * 按住它才能把「偶发」变成「必现」）：
     * <pre>
     *   ① 先 UPDATE(0 行) 后 INSERT，同一事务   8 线程：死锁 140  成功  20
     *   ② 先 INSERT 后 UPDATE，同一事务         8 线程：死锁 120  成功  40
     *   ③ INSERT 自成事务，UPDATE 另起事务      8 线程：死锁   0  成功 160
     *                （现在这样）              16 线程：死锁   0  成功 320
     * </pre>
     *
     * <p>①死在间隙锁上：REPEATABLE READ 下那条<b>一行都没匹配到</b>的 UPDATE 并不是什么都不锁，
     * 它在「这一行本该在的位置」留下间隙锁；几个事务各持一份，又都要往这个间隙里插，
     * 插入意向锁与间隙锁冲突，成环。
     *
     * <p>②死在另一把锁上：INSERT 撞重复键时，InnoDB 为了做重复检查<b>已经在那一行上拿了 S 锁，
     * 并且持到事务结束</b>。于是每个输家都攥着 S 锁、又都要为接下来的 UPDATE 拿 X 锁，互相等。
     * ——「把 INSERT 提到 UPDATE 前面」只是把死锁从第一条语句挪到了第二条。
     *
     * <p>③之所以干净：那把 S 锁随着单语句事务当场结束就还回去了，
     * 业务事务里只剩一条 UPDATE 在一行已存在的记录上排队，单一资源、无环可成。
     *
     * <h3>两条更该记住的</h3>
     * <ol>
     *   <li><b>量具的取景框之外没有信号。</b>第一版量具只量到 INSERT 为止，
     *       于是量出「先 INSERT 就 0 死锁」，据此改完，集成用例当场炸 ——
     *       死锁只是挪到了没进量具的那条 UPDATE 上。<b>看不见不等于没有。</b>
     *   <li><b>写了 catch 不等于那条路被守住了。</b>原先那句
     *       {@code catch (DuplicateKeyException)} 守的是一个根本不会发生的情况：
     *       ①②两组的重复键都是 0 次，输的一方全部被数据库判死锁回滚，异常类型压根不同，
     *       于是它一路飞到统一出口变成 500「系统繁忙」。守之前得先证明抛的真是这个异常。
     * </ol>
     */
    private void ensureTodayRow(long userId, LocalDate today) {
        try {
            jdbc.update("INSERT INTO qa_quota_usage (user_id,stat_date,ask_count,blocked_count) "
                      + "VALUES (?,?,0,0)", userId, today);
        } catch (DuplicateKeyException e) {
            // 行已经在了 —— 正是要的结果。抢输的一方走到这里时，赢的一方已经提交。
        }
    }

    /**
     * 记一次「撞闸」。用 ON DUPLICATE KEY UPDATE 而不是先查后写：
     * 这条统计本身不影响判定，唯一要求是不能因为「今天还没有行」而丢掉。
     */
    private void bumpBlocked(long userId, LocalDate today) {
        try {
            // 用调用方已经定好的那个「今天」，不再自己 CURDATE() 求一次：
            // 跨零点时两者会差一天，于是拒绝记在今天、被拒的那次尝试记在昨天，
            // 风控看那一行会觉得「有人只撞闸不提问」。
            jdbc.update("INSERT INTO qa_quota_usage (user_id,stat_date,blocked_count) "
                      + "VALUES (?,?,1) "
                      + "ON DUPLICATE KEY UPDATE blocked_count=blocked_count+1", userId, today);
        } catch (RuntimeException e) {
            // 统计写失败不该把「配额用尽」这句人话换成 500。原始拒绝理由必须原样送到用户手里。
        }
    }

    // ================================================================= 追问

    /**
     * 学员追问。
     *
     * <p>配额只在工单还处于 AI 档时扣：这时候的每一条追问都是一次模型调用，是同一个泄漏面；
     * 已经转人工的工单，追问是发给导师看的，再扣配额会把学员卡在一条他自己也退不出去的工单里
     * （问题没解决、又不让再说话）。
     *
     * <h3>★ 追问必须和首问走同一条链：问答库 → 懂妈 → 转人工</h3>
     * 这里原来只查问答库，没命中就直接转人工 —— 而首问是「问答库没命中就问懂妈」。
     * 于是同一个人在同一张工单里，第一句由懂妈答，第二句起她<b>根本不在场</b>：
     * 配额照扣、答案没有、工单进导师队列。学员看到的是提问页上写着的
     * 「答不好可以再追问」，追了之后却只等来一句「已转人工」；
     * 导师队列里堆的则是<b>懂妈本来答得了</b>的问题。
     *
     * <p>两条链必须一致，否则「先查人写好的 FAQ，再问模型」这条口径只在首问上成立，
     * 而运营看到的转人工量会把原因指向懂妈答不好 —— 真因是她压根没被问到。
     *
     * @return 今天还剩几次；<b>这一条没扣配额时返回 null</b>（工单已在人工档）。
     *         返回 0 和返回 null 是两件不同的事，不能合并成 0 ——
     *         常驻挂件要拿它写「今天还可以问 N 次」，把「没扣」写成「还剩 0 次」，
     *         学员会看到一个把他吓住的数，然后照样能接着追问。
     *         首问那条链（{@link #ask}）本来就出这个数，追问不出，
     *         挂件上的计数就会停在他上一次<b>提新问题</b>的时刻不动 ——
     *         一个越看越不准的数，比不显示更坏。
     */
    public Integer reply(long userId, long ticketId, String content) {
        return replyStreaming(userId, ticketId, content, d -> { });
    }

    /**
     * 追问，边答边送。事务拆法与 {@link #askStreaming} 完全一致，理由也一样：
     * <b>那 4 秒既不能待在事务里，也不能在失败时把工单留在「已解答」上。</b>
     *
     * <p>所以 AI 档的追问在事务一里就先把工单改成 ESCALATED（并挂上 SLA），
     * 答成了在事务二里改回 AI_ANSWERED。中途进程挂掉，留下的是一张
     * <b>状态与内容一致</b>的工单：状态是待人工，内容是学员刚追的那一句，导师看得见。
     */
    public Integer replyStreaming(long userId, long ticketId, String content,
                                  java.util.function.Consumer<String> onDelta) {
        String c = requireText(content, MAX_CONTENT_CHARS, "追问内容");
        // 与 ask 同一套：行先在事务外备好。这里是无条件备的 ——
        // 要不要扣配额得先读到工单状态才知道，而那一步在事务里。
        // 代价是给「只在人工档追问」的人也留一行 ask_count=0 的记录，
        // 那是一行计数为零的用量统计，不是错数据；换来的是那条 INSERT 不进业务事务。
        LocalDate today = dbToday();
        ensureTodayRow(userId, today);

        FollowUp f;
        try {
            // 同样走带死锁重跑的通道
            f = inTx(st -> {
                // 越权条件进 WHERE：查不到与不是你的，对外是同一种结果（404），
                // 否则这个接口就是一台可以枚举「哪些工单 id 存在」的探针。
                var rows = jdbc.queryForList(
                        "SELECT status, course_id FROM qa_ticket WHERE id=? AND user_id=? FOR UPDATE",
                        ticketId, userId);
                if (rows.isEmpty()) throw ApiException.notFound("工单不存在");
                String status = (String) rows.get(0).get("status");
                long courseId = num(rows.get(0).get("course_id"));
                if ("CLOSED".equals(status))
                    throw ApiException.conflict("该工单已关闭，请新建提问");

                if ("AI_ANSWERED".equals(status)) {
                    int left = Math.max(0, dailyAskQuota - consumeQuota(userId, today));
                    // 上下文要在把这条追问写进去【之前】取：取完再写，懂妈拿到的
                    // history 就会以「用户刚说的这句」结尾，而 question 又是同一句 ——
                    // 同一句话说两遍，模型会把它当成学员在重复强调
                    List<Map<String, String>> history = historyOf(ticketId);
                    insertMessage(ticketId, "STUDENT", userId, c, null);
                    // 先落到"待人工"上。答成了事务二会改回来；中途挂了就停在这儿，
                    // 而停在这儿是有人管的那一边。
                    jdbc.update("UPDATE qa_ticket SET status='ESCALATED', "
                              + "escalate_reason='LOW_CONFIDENCE', "
                              + "sla_due_at=DATE_ADD(NOW(3), INTERVAL ? HOUR) WHERE id=?",
                            SLA_HOURS, ticketId);
                    return new FollowUp(true, courseId, left, history);
                }

                insertMessage(ticketId, "STUDENT", userId, c, null);
                // HUMAN_ANSWERED 下的追问必须把工单推回队列。不推的话它停在「已回复」，
                // 导师的待办列表按 status='ESCALATED' 取，这条追问永远不会出现在任何人面前 ——
                // 不报错、不超时，只是没有人再看它一眼。
                if ("HUMAN_ANSWERED".equals(status)) {
                    jdbc.update("UPDATE qa_ticket SET status='ESCALATED', "
                              + "escalate_reason='USER_REQUEST', "
                              + "sla_due_at=DATE_ADD(NOW(3), INTERVAL ? HOUR) WHERE id=?",
                            SLA_HOURS, ticketId);
                }
                // 人工档：这一条没扣配额，如实返回 null 而不是 0
                return new FollowUp(false, courseId, 0, List.of());
            });
        } catch (QuotaExceeded e) {
            bumpBlocked(userId, today);
            throw ApiException.forbidden(e.getMessage());
        }

        if (!f.ai()) return null;

        // 追问的检索文本就是这一句本身（首问那边还要拼上标题）
        Answer a = produce(f.courseId(), c, c, f.history(), onDelta);
        if (a != null) settle(ticketId, a);
        return f.quotaLeft();
    }

    private record FollowUp(boolean ai, long courseId, int quotaLeft,
                            List<Map<String, String>> history) {}

    // ================================================================= 读

    /**
     * 一张工单此刻的状态（越权条件同样进 WHERE）。
     *
     * <p>流式追问的收尾帧要靠它判「这一轮到底答上了没有」：{@link #replyStreaming}
     * 回的是剩余配额，不是"答没答上"，而<b>状态本身就是权威</b> ——
     * 让服务端再回一个布尔，就等于同一件事有了两个来源，早晚对不上。
     */
    public String statusOf(long viewerId, long ticketId) {
        var rows = jdbc.queryForList(
                "SELECT status FROM qa_ticket WHERE id=? AND user_id=?", ticketId, viewerId);
        return rows.isEmpty() ? null : (String) rows.get(0).get("status");
    }

    /** 我的工单列表。永远按 user_id 过滤，导师看自己提的问题走的也是这条。 */
    public List<Map<String, Object>> myTickets(long userId, int limit) {
        int n = Math.max(1, Math.min(limit, 100));
        return jdbc.query(
                "SELECT t.id, t.course_id, t.lesson_id, t.title, t.status, t.escalate_reason, "
              + "       t.sla_due_at, t.created_at, t.updated_at, "
              + "       (SELECT COUNT(*) FROM qa_message m WHERE m.ticket_id=t.id) AS msg_count "
              + "  FROM qa_ticket t WHERE t.user_id=? ORDER BY t.id DESC LIMIT " + n,
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("courseId", rs.getLong("course_id"));
                    m.put("lessonId", rs.getObject("lesson_id") == null ? null : rs.getLong("lesson_id"));
                    m.put("title", rs.getString("title"));
                    m.put("status", rs.getString("status"));
                    m.put("escalateReason", rs.getString("escalate_reason"));
                    m.put("slaDueAt", rs.getObject("sla_due_at"));
                    m.put("createdAt", rs.getObject("created_at"));
                    m.put("updatedAt", rs.getObject("updated_at"));
                    m.put("messageCount", rs.getInt("msg_count"));
                    return m;
                }, userId);
    }

    /**
     * 我的工单按状态计数。
     *
     * <p><b>为什么不拿 {@link #myTickets} 的返回值数一遍</b>：那个方法带 LIMIT（上限 100）。
     * 用它数出来的"待回复 0 条"在工单超过 100 条的人身上是错的，而且错得没有任何症状 ——
     * 首页安安静静地说"没有等回复的问题"，学员就不会再点进去。计数必须自己走一次聚合。
     *
     * <p>返回的 key 就是 {@code qa_ticket.status} 的原值，不在这里翻译成中文：
     * 文案是前端的事，这里多翻一次就多一处会和前端对不上的地方。
     */
    public Map<String, Integer> myTicketCounts(long userId) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (var r : jdbc.queryForList(
                "SELECT status, COUNT(*) AS n FROM qa_ticket WHERE user_id=? GROUP BY status", userId))
            out.put((String) r.get("status"), ((Number) r.get("n")).intValue());
        return out;
    }

    /**
     * 可见范围。<b>这是这一层唯一的越权闸，所以它只产出 SQL 片段，不产出「能不能看」的布尔值</b> ——
     * 布尔值那种写法迟早会演化成「先按 id 查出来，再判断是不是他的」，
     * 而那一步「查出来」本身已经把行取到内存里了，之后任何一条新加的分支
     * （比如「查不到返回另一种提示」）都会把它变成一台可枚举的探针。
     *
     * <p>导师这一档写成 {@code assignee_id=? OR user_id=?}：导师本人也可能是学员
     * （买了课来提问）。只按 assignee_id 过滤的话，他自己提的工单他自己看不见。
     */
    static Scope scopeOf(String role, long viewerId) {
        if ("PLATFORM_ADMIN".equals(role)) return new Scope("", List.of());
        if ("MENTOR".equals(role))
            return new Scope(" AND (assignee_id=? OR user_id=?)", List.of(viewerId, viewerId));
        return new Scope(" AND user_id=?", List.of(viewerId));
    }

    record Scope(String sql, List<Object> args) {}

    /** 工单详情 + 消息。角色决定可见范围，条件进 WHERE。 */
    public Map<String, Object> detail(long viewerId, String role, long ticketId) {
        Scope scope = scopeOf(role, viewerId);
        List<Object> args = new ArrayList<>();
        args.add(ticketId);
        args.addAll(scope.args());

        var rows = jdbc.queryForList(
                "SELECT id, user_id, course_id, lesson_id, title, status, ai_confidence, "
              + "       escalate_reason, assignee_id, sla_due_at, published_faq_id, "
              + "       created_at, updated_at "
              + "  FROM qa_ticket WHERE id=?" + scope.sql(), args.toArray());
        if (rows.isEmpty()) throw ApiException.notFound("工单不存在");
        var t = rows.get(0);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", num(t.get("id")));
        out.put("courseId", num(t.get("course_id")));
        out.put("lessonId", t.get("lesson_id") == null ? null : num(t.get("lesson_id")));
        // ★ 这里只出讲次【标题】，不出任何「第几讲」的号。
        //   lessonId 是主键（全库自增的行号），前端拿它当序号渲染过「关联第 12 讲」，
        //   而这门课一共只有 4 讲。换成 sort_no 也不对：生产库里它是 20/30/40/70 ——
        //   一个留了间隔的排序键，不是序号，照它渲染会写出「关联第 20 讲」。
        //   这个 schema 里【根本没有】一个能当序号用的列，而标题本身就带着编号
        //   （"L3 后端与多模态"），学员认的也是它。不编号，就不会编错号。
        addLessonRef(out, t.get("lesson_id"));
        out.put("title", t.get("title"));
        out.put("status", t.get("status"));
        out.put("escalateReason", t.get("escalate_reason"));
        out.put("slaDueAt", t.get("sla_due_at"));
        out.put("publishedFaqId",
                t.get("published_faq_id") == null ? null : num(t.get("published_faq_id")));
        out.put("createdAt", t.get("created_at"));
        out.put("updatedAt", t.get("updated_at"));
        out.put("messages", messagesOf(ticketId));
        return out;
    }

    /**
     * 把「关联讲次」补成人能读的形态：只给标题。
     *
     * <p><b>刻意不给序号</b>。这张表里没有一列能当序号用：{@code id} 是全库自增的主键，
     * {@code sort_no} 是留了间隔的排序键（生产库里是 20/30/40/70）。两者渲染成
     * 「第 N 讲」都会写出一个课程目录里根本找不到的号，而学员会照着这个号去翻目录。
     * 标题本身带着编号（"L3 后端与多模态"），够用了。
     *
     * <p>键<b>显式给 null</b> 而不是缺省：缺省时前端的 `?? '—'` 与
     * 「后端这版还没这个字段」在网线上分不出来，而这两件事的处置完全不同。
     */
    private void addLessonRef(Map<String, Object> out, Object lessonId) {
        Long id = lessonId == null ? null : num(lessonId);
        out.put("lessonTitle", null);
        if (id == null) return;
        var rows = jdbc.queryForList("SELECT title FROM lesson WHERE id=?", id);
        if (rows.isEmpty()) return;      // 讲次被删了，工单还在：不编一个出来
        out.put("lessonTitle", rows.get(0).get("title"));
    }

    private List<Map<String, Object>> messagesOf(long ticketId) {
        return jdbc.query(
                "SELECT sender_type, content, citations, safety_verdict, created_at "
              + "  FROM qa_message WHERE ticket_id=? ORDER BY id ASC", (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("senderType", rs.getString("sender_type"));
                    // 只出 sender_type，不出 sender_id：工单详情是导师也会看的页面，
                    // 把学员的 user_id 顺手带出去，等于给了一条把工单和账号对上的线。
                    boolean blocked = "BLOCKED".equals(rs.getString("safety_verdict"));
                    m.put("content", blocked ? "（该条内容未通过安全审核）" : rs.getString("content"));
                    m.put("citations", rs.getString("citations"));
                    m.put("createdAt", rs.getObject("created_at"));
                    return m;
                }, ticketId);
    }

    /**
     * 这张工单已有的来回，喂给懂妈当上下文。
     *
     * <p>三条取舍：
     * <ul>
     *   <li><b>BLOCKED 的消息整条不带。</b>它在详情页里已经被换成「该条内容未通过安全审核」，
     *       把原文再喂回模型，等于安全拦截只拦住了人眼、没拦住模型 ——
     *       而模型的回答是会落库、会被学员看到的。
     *   <li><b>导师的回复算 assistant。</b>对懂妈来说「上一轮答复」是谁写的不重要，
     *       重要的是学员已经收到过这段话；标成 user 会让她把老师的话当成学员的新问题。
     *   <li><b>只取最近 {@value #HISTORY_TURNS} 条。</b>工单可以一直追问下去，
     *       全量带上会让载荷随轮次线性膨胀，而答一句追问用不着半年前的开场白。
     * </ul>
     */
    private static final int HISTORY_TURNS = 10;

    private List<Map<String, String>> historyOf(long ticketId) {
        var rows = jdbc.queryForList(
                "SELECT sender_type, content FROM qa_message"
              + " WHERE ticket_id=? AND safety_verdict='PASS'"
              + " ORDER BY id DESC LIMIT " + HISTORY_TURNS, ticketId);
        List<Map<String, String>> out = new ArrayList<>();
        // 上面按 id DESC 取的是「最近 N 条」，喂给模型时必须再翻回时间顺序
        for (int i = rows.size() - 1; i >= 0; i--) {
            var r = rows.get(i);
            String content = (String) r.get("content");
            if (content == null || content.isBlank()) continue;
            out.add(Map.of(
                    "role", "STUDENT".equals(r.get("sender_type")) ? "user" : "assistant",
                    "content", content));
        }
        return out;
    }

    // ================================================================= 导师 / 管理员

    /**
     * 导师队列：只出分配给自己的；管理员出全部。条件同样进 WHERE。
     *
     * @param status 要看哪一档。<b>不传（或传不认识的值）= ESCALATED</b>，即这个接口原本的语义。
     *               允许指定别的档，是因为工单一旦被回复就<b>立刻离开待处理队列</b>，
     *               而「关闭工单」「沉淀成公开问答」这两个动作恰恰是回复之后才做的 ——
     *               不给一条找回来的路，这两个按钮就只在回复后那一瞬间存在，刷新即失。
     *
     *               <p>白名单硬编码在这里而不是拼进 SQL：这个值来自查询串，
     *               直接拼是注入，转义之后拼则会把一个错拼的状态静默变成「查不到」。
     */
    public List<Map<String, Object>> queue(long viewerId, String role, String status, int limit) {
        int n = Math.max(1, Math.min(limit, 100));
        String st = status == null ? null : status.trim().toUpperCase();
        if (!List.of("AI_ANSWERED", "ESCALATED", "HUMAN_ANSWERED", "CLOSED").contains(
                st == null ? "" : st)) st = "ESCALATED";
        String where = "PLATFORM_ADMIN".equals(role) ? "" : " AND assignee_id=?";
        List<Object> args = new ArrayList<>();
        args.add(st);
        if (!"PLATFORM_ADMIN".equals(role)) args.add(viewerId);
        return jdbc.query(
                "SELECT id, user_id, course_id, title, status, escalate_reason, sla_due_at, created_at "
              + "  FROM qa_ticket WHERE status=?" + where
                // 排序跟着「在看哪一档」走，两种意图不一样：
                //   ESCALATED（待处理）—— sla_due_at 升序 = 最快超时的排最前。按 created_at 排
                //     会让一条被追问过、SLA 已经重置的老工单永远压在队首，真正快超时的反而看不见。
                //   其余档 —— 这些工单没有 SLA（sla_due_at 全是 NULL），照上面那条排就退化成
                //     id 升序 = 最老的排最前；而人来这几档是为了找【刚刚处理过的那一张】，
                //     于是要翻到最后一页才看得见自己五秒前回复的工单。所以按 id 降序。
              + ("ESCALATED".equals(st)
                     ? " ORDER BY sla_due_at IS NULL, sla_due_at ASC, id ASC"
                     : " ORDER BY id DESC")
              + " LIMIT " + n,
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("courseId", rs.getLong("course_id"));
                    m.put("title", rs.getString("title"));
                    m.put("status", rs.getString("status"));
                    m.put("escalateReason", rs.getString("escalate_reason"));
                    m.put("slaDueAt", rs.getObject("sla_due_at"));
                    m.put("createdAt", rs.getObject("created_at"));
                    return m;
                }, args.toArray());
    }

    /**
     * 导师 / 管理员回复。可见范围与详情同一把尺，导师改不动没分给自己的工单。
     *
     * <p>已关闭的工单<b>直接拒绝</b>，而不是「消息照写、状态不动」。后者看起来更宽容，
     * 实际是最坏的一种：回复真的落进了库里，学员在详情页看得见，但工单仍算已结案，
     * 谁都不会再跟进它 —— 导师以为自己回了，系统以为这单结了，两边都没错，事就掉在中间。
     *
     * <p>为什么用「加锁读 + 判断」而不是一条 {@code UPDATE ... WHERE status<>'CLOSED'} 的行数：
     * 工单本来就可能已经是 HUMAN_ANSWERED，此时 SET 的值和原值相同，
     * 「匹配行数」与「改变行数」不再相等 —— 而这两者哪一个会被返回，
     * 取决于 Connector/J 的 useAffectedRows / CLIENT_FOUND_ROWS。
     * 也就是说同一份代码在不同连接参数下判定相反，且不会有任何报错。
     * 行锁把「读状态」和「写状态」串起来，没有这层歧义。
     */
    public void staffReply(long operatorId, String role, long ticketId, String content) {
        String c = requireText(content, MAX_CONTENT_CHARS, "回复内容");
        tx.executeWithoutResult(st -> {
            var t = lockVisibleTicket(operatorId, role, ticketId);
            long id = num(t.get("id"));
            if ("CLOSED".equals(t.get("status")))
                throw ApiException.conflict("该工单已关闭，无法再回复；如需继续请让学员新建提问");
            insertMessage(id, "MENTOR", operatorId, c, null);
            jdbc.update("UPDATE qa_ticket SET status='HUMAN_ANSWERED' WHERE id=?", id);
        });
    }

    public void close(long operatorId, String role, long ticketId) {
        long id = requireVisibleTicket(operatorId, role, ticketId);
        jdbc.update("UPDATE qa_ticket SET status='CLOSED' WHERE id=?", id);
    }

    /**
     * 指派导师。<b>只有平台管理员能派</b>。
     *
     * <p>如果允许导师自己认领，那「导师只能看分配给自己的」这条规则就等于没有：
     * 任何导师都能先把工单派给自己再打开它 —— 越权变成两步，但还是能走通。
     */
    public void assign(long operatorId, long ticketId, long mentorUserId, String ip) {
        Integer ok = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_user WHERE id=? AND role='MENTOR' AND status='ACTIVE'",
                Integer.class, mentorUserId);
        if (ok == null || ok == 0) throw ApiException.badRequest("指定的导师不存在或不在正常状态");

        int n = jdbc.update("UPDATE qa_ticket SET assignee_id=? WHERE id=?", mentorUserId, ticketId);
        if (n != 1) throw ApiException.notFound("工单不存在");
        audit(operatorId, "QA_ASSIGN", "qa_ticket", ticketId,
                "{\"assigneeId\":" + mentorUserId + "}", ip);
    }

    /**
     * 把一条问答沉淀进公开问答库。
     *
     * <p>幂等的裁决点是 {@code UPDATE ... WHERE published_faq_id IS NULL} 影响的行数，
     * 不是「先查有没有沉淀过」：两次点击并发进来，先查后写会双双查到 NULL，
     * 于是同一条问答在公开库里出现两遍（而且第二遍会把第一遍的 id 覆盖掉，成为孤儿行）。
     * 顺序是先插 faq_entry 再抢这条 UPDATE，抢输就整事务回滚，插进去的那行跟着没了。
     */
    public long publishFaq(long operatorId, String role, long ticketId,
                           String question, String answer, String ip) {
        String q = requireText(question, 200, "问题");
        String a = requireText(answer, 4000, "答案");
        Long id = tx.execute(st -> {
            long tid = requireVisibleTicket(operatorId, role, ticketId);
            long courseId = num(jdbc.queryForMap(
                    "SELECT course_id FROM qa_ticket WHERE id=?", tid).get("course_id"));

            long faqId = insertReturningId(
                    "INSERT INTO faq_entry (course_id,question,answer,created_by) VALUES (?,?,?,?)",
                    ps -> {
                        ps.setLong(1, courseId);
                        ps.setString(2, q);
                        ps.setString(3, a);
                        ps.setLong(4, operatorId);
                    });
            int n = jdbc.update("UPDATE qa_ticket SET published_faq_id=? "
                              + " WHERE id=? AND published_faq_id IS NULL", faqId, tid);
            if (n != 1) throw ApiException.conflict("该工单已经沉淀过一条公开问答，不能重复沉淀");
            audit(operatorId, "FAQ_PUBLISH", "faq_entry", faqId,
                    "{\"ticketId\":" + tid + "}", ip);
            return faqId;
        });
        return id == null ? 0 : id;
    }

    // ================================================================= 内部

    /**
     * 取一条当前角色看得见的工单 id，看不见就 404。
     * 返回 id 而不是返回布尔，是为了让调用方后面的 UPDATE 用的一定是这条已经过闸的 id。
     */
    private long requireVisibleTicket(long viewerId, String role, long ticketId) {
        Scope scope = scopeOf(role, viewerId);
        List<Object> args = new ArrayList<>();
        args.add(ticketId);
        args.addAll(scope.args());
        var rows = jdbc.queryForList(
                "SELECT id FROM qa_ticket WHERE id=?" + scope.sql(), args.toArray());
        if (rows.isEmpty()) throw ApiException.notFound("工单不存在");
        return num(rows.get(0).get("id"));
    }

    /**
     * 同上，但把行锁住并带回状态，供「读状态 → 据此决定写什么」的路径使用。
     * <b>必须在事务里调</b>：不在事务里的 FOR UPDATE 会随语句结束立刻释放，
     * 锁看起来加了、其实一点用没有，而且不会有任何报错。
     */
    private Map<String, Object> lockVisibleTicket(long viewerId, String role, long ticketId) {
        Scope scope = scopeOf(role, viewerId);
        List<Object> args = new ArrayList<>();
        args.add(ticketId);
        args.addAll(scope.args());
        var rows = jdbc.queryForList(
                "SELECT id, status, course_id FROM qa_ticket WHERE id=?" + scope.sql()
              + " FOR UPDATE", args.toArray());
        if (rows.isEmpty()) throw ApiException.notFound("工单不存在");
        return rows.get(0);
    }

    /** 提问前必须先有这门课的有效权益：没买过的人能问，AI 就成了一条绕过付费的取课通道。 */
    private void requireEnrollment(long userId, long courseId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM enrollment "
              + " WHERE user_id=? AND course_id=? AND status='ACTIVE' AND valid_until>NOW(3)",
                Integer.class, userId, courseId);
        if (n == null || n == 0)
            throw ApiException.forbidden("你还没有这门课的有效权益，无法就该课程提问");
    }

    /** 检索失败不该把提问打回去：问答库出问题时，退化成转人工比报 500 好。 */
    private List<FaqSearch.Hit> safeSearch(long courseId, String text) {
        try {
            return faq.search(courseId, text, 1);
        } catch (ApiException e) {
            return List.of();       // 词太短之类，等价于没命中
        }
    }

    private long insertTicket(long userId, long courseId, Long lessonId, String title,
                              String status, String escalateReason) {
        return insertReturningId(
                "INSERT INTO qa_ticket (user_id,course_id,lesson_id,title,status,escalate_reason,"
              + "sla_due_at) VALUES (?,?,?,?,?,?,"
              + (escalateReason == null ? "NULL" : "DATE_ADD(NOW(3), INTERVAL " + SLA_HOURS + " HOUR)")
              + ")",
                ps -> {
                    ps.setLong(1, userId);
                    ps.setLong(2, courseId);
                    if (lessonId == null) ps.setNull(3, Types.BIGINT); else ps.setLong(3, lessonId);
                    ps.setString(4, title);
                    ps.setString(5, status);
                    if (escalateReason == null) ps.setNull(6, Types.VARCHAR);
                    else ps.setString(6, escalateReason);
                });
    }

    private void insertMessage(long ticketId, String senderType, Long senderId,
                               String content, String citations) {
        jdbc.update("INSERT INTO qa_message (ticket_id,sender_type,sender_id,content,citations) "
                  + "VALUES (?,?,?,?,?)", ticketId, senderType, senderId, content, citations);
    }

    private static String citationOf(long faqId) { return "{\"faqId\":" + faqId + "}"; }

    /**
     * 插入并取回自增主键。
     *
     * <p><b>不用 {@code SELECT LAST_INSERT_ID()}</b>：它是<b>按连接</b>的会话变量，
     * 而 JdbcTemplate 的每一条语句都可能从池里拿到不同的连接 —— 事务里恰好是同一条，
     * 事务外就不是了。那种错的表现是「偶尔取到别人刚插的那行的 id」，
     * 在低并发下几乎复现不出来，上线后才在数据里显形。KeyHolder 走 JDBC 的
     * getGeneratedKeys，和 INSERT 是同一条语句同一条连接。
     */
    private long insertReturningId(String sql, PsSetter setter) {
        var kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            setter.set(ps);
            return ps;
        }, kh);
        Number k = kh.getKey();
        if (k == null) throw new IllegalStateException("插入后没有拿到自增主键");
        return k.longValue();
    }

    @FunctionalInterface
    interface PsSetter { void set(PreparedStatement ps) throws java.sql.SQLException; }

    void audit(long operatorId, String action, String targetType, long targetId,
               String afterJson, String ip) {
        jdbc.update("INSERT INTO audit_log (operator_id,action,target_type,target_id,after_json,client_ip) "
                  + "VALUES (?,?,?,?,?,?)",
                operatorId, action, targetType, String.valueOf(targetId), afterJson, ip);
    }

    /**
     * 长度在应用层拦，不留给 MySQL。
     * 超长写进 VARCHAR 在 STRICT 模式下抛 1406 Data too long ——
     * 那是一条带表名列名的技术报错，既不该出网，也没告诉用户到底哪儿超了。
     */
    private static String requireText(String v, int max, String what) {
        if (v == null || v.isBlank()) throw ApiException.badRequest(what + "不能为空");
        String s = v.strip();
        if (s.length() > max)
            throw ApiException.badRequest(what + "最多 " + max + " 个字，请精简后重试");
        return s;
    }

    /** BIGINT UNSIGNED 在 Connector/J 里是 BigInteger 不是 Long，直接强转会 ClassCastException。 */
    private static long num(Object v) { return ((Number) v).longValue(); }
}
