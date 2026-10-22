package tech.dragonai.wangke.exam;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tech.dragonai.wangke.web.ApiException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 交卷之后的测评报告：事实层（代码算）+ 叙述层（模型写）。
 *
 * <h3>一、两层是分开的，这是整件事的地基</h3>
 * <b>事实层</b>（{@link #build}）全部由 SQL + Java 算：得分、按讲次/板块/题型/难度的分子分母、
 * 错题清单。它<b>永远算得出来</b>，模型挂了、超时了、写错了都不影响它。
 * <b>叙述层</b>才走模型。合成一段让模型一起写的话，模型一挂整页就空了 ——
 * 而学员的分数与错题是客观的，凭什么因为一次外部调用失败就让他连自己错在哪都看不到。
 * 所以 {@link #report} 在 QUEUED / RUNNING / FAILED 三种状态下<b>都照常返回 stats</b>。
 *
 * <h3>二、19 秒不能挂在交卷那条请求上</h3>
 * 实测一次生成 19.3 秒。所以 {@link #enqueue} 只做两件极快的事：算事实层、插一行 QUEUED；
 * 真正的生成扔进后台线程池。它必须在<b>交卷事务提交之后</b>调用 ——
 * 外部调用进事务的后果是行锁被一条 HTTP 连接持有 19 秒，而那张表上还压着别人的交卷。
 *
 * <h3>三、排队在库里，抢占靠带条件的 UPDATE</h3>
 * 线程池只是"谁来跑"，队列在 {@code exam_report} 表里。抢占是
 * {@code UPDATE … SET status='RUNNING' WHERE id=? AND status='QUEUED'}，<b>受影响行数 = 1 才算抢到</b>。
 * 先查后改在多实例下必然重复生成：两台机器都读到 QUEUED，各写各的，
 * 学员那份报告被写两遍（费用翻倍，且两份内容不一样，谁也说不清哪份是他的）。
 *
 * <h3>四、卡住的行会自愈</h3>
 * 进程在 RUNNING 中途被 kill，那一行会永远停在 RUNNING —— 没有清扫的话，
 * 表现是"这个学员的报告一直转圈"，而没有任何日志会提到它。
 * {@link #sweep} 每分钟把 started_at 超过 {@value #STUCK_MINUTES} 分钟的 RUNNING 行
 * {@code tries+1} 后退回 QUEUED，{@code tries} 满 {@value #MAX_TRIES} 置 FAILED 并写明理由。
 *
 * <h3>五、半截与编造一律不落库</h3>
 * {@code finish_reason != "stop"} 或正文为空由 {@link DeepSeekClient} 判掉；
 * 正文里的时间点由 {@link ExamReportPrompt#unknownTimePoints} 逐个比对事实清单，
 * 对不上就重生成一次，再对不上置 FAILED。理由见规格第二节：试跑时它真的把
 * 事实里的 {@code 18:47} 写成了"18:47 到 26:51 这一段"。<b>宁可没有报告，
 * 也不能让学员按一个不存在的时间点去翻录像</b> —— 前者他知道自己没有报告，后者他以为自己找错了。
 */
@Service
public class ExamReportService {

    private static final Logger log = LoggerFactory.getLogger(ExamReportService.class);

    /** 生成中途被打断的重试上限。到顶置 FAILED，学员可以自己点重新生成。 */
    static final int MAX_TRIES = 3;
    /** RUNNING 超过这么久就认定那个进程已经不在了。真实一次生成 19 秒，10 分钟是三十倍余量。 */
    static final int STUCK_MINUTES = 10;
    /** 同一场考试的手动重试间隔。挡的是连点，不是恶意 —— 一次生成要钱也要 19 秒。 */
    static final int RETRY_COOLDOWN_MINUTES = 5;
    /** fail_reason 是 VARCHAR(255)。超长会被 MySQL 严格模式判成数据截断，一条写失败的报告状态。 */
    private static final int FAIL_REASON_MAX = 250;
    /** 错题题干在报告里的摘要长度。情境题题干 80~350 字，整条塞进去等于把卷子抄一遍。 */
    private static final int STEM_EXCERPT = 120;

    /**
     * 题被<b>物理删除</b>之后，那一行答题明细的归属（四组分桶与错题清单都用它）。
     *
     * <p>不给它一个位置，分母就会跟着 JOIN 一起塌：见 {@link #build} 里那段注释。
     * 措辞是"已下线"而不是"题目不存在"——学员看到的必须是一句他读得懂、
     * 且不会让他以为自己的卷子出了问题的话。
     */
    static final String GONE_LABEL = "题目已下线";

    /** 已下线那道题的"题干"。查不到原题，就明说查不到，不留一行空白让人以为是渲染坏了。 */
    private static final String GONE_STEM = "这道题已从题库下线，看不到原题，也没有可回看的位置";

    /** 越权与不存在<b>对外同一种结果</b>，所以只有这一句话。 */
    private static final String NOT_FOUND = "考试记录不存在";

    /**
     * 报告连队都没排上时给学员的那句话。
     *
     * <p>要说清三件事：不是他的问题、这不是"还在生成"、他可以再点一次。
     * 少了最后一句，页面上就只剩一段他做不了任何事的红字。
     */
    private static final String ENQUEUE_FAILED =
            "这份报告没能排上队（平台侧的问题，不是你的作答），请点重新生成再试一次";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final DeepSeekClient model;
    private final Executor pool;
    /** 自己建的池才由自己关；注入进来的（测试用的同步执行器）不许动。 */
    private final ExecutorService ownPool;

    @Autowired
    public ExamReportService(JdbcTemplate jdbc, ObjectMapper json, DeepSeekClient model) {
        this.jdbc = jdbc;
        this.json = json;
        this.model = model;
        this.ownPool = defaultPool();
        this.pool = this.ownPool;
    }

    /**
     * 判据专用：把"谁来跑"换成同步执行器，生成就变成确定的一步，
     * 不用在测试里睡几秒等一个后台线程（那种等待迟早变成偶发红）。
     * 生产走上面那个构造，固定 2 个线程。
     */
    ExamReportService(JdbcTemplate jdbc, ObjectMapper json, DeepSeekClient model, Executor pool) {
        this.jdbc = jdbc;
        this.json = json;
        this.model = model;
        this.pool = pool;
        this.ownPool = null;
    }

    /**
     * 固定 2 个线程 + 有界队列。
     *
     * <p><b>队列满了就丢（DiscardPolicy），这是安全的</b>：库里那一行还是 QUEUED，
     * {@link #sweep} 下一轮会把它捡回来。换成无界队列才是危险的 ——
     * 一次大批量交卷会把几千个任务堆在堆里，进程一重启全没了，
     * 而库里那些行还停在 QUEUED，永远没人再看它们一眼。
     *
     * <p>守护线程：这些任务丢了不影响任何已落库的数据（最坏是报告晚一分钟生成），
     * 不该拖着 JVM 不让它退出。
     */
    private static ExecutorService defaultPool() {
        var seq = new AtomicInteger();
        return new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(256),
                r -> {
                    Thread t = new Thread(r, "exam-report-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.DiscardPolicy());
    }

    @PreDestroy
    void shutdown() {
        if (ownPool != null) ownPool.shutdownNow();
    }

    // ================================================================= 数据形状

    /** 一段"分子 / 分母"。label 是给人看的那句话，correct/total 是判据钉得住的数。 */
    public record Bucket(String label, int correct, int total) {}

    /**
     * 一道错题。<b>没有 answer、没有 explanation，是判据不是疏忽</b>（取数那一层连查都不查）：
     * 报告是学员能反复看的页面，把正确答案写进去等于每考一次就往外送一份题库。
     *
     * <p>{@code sourceAt} 是 {@code m:ss} 的讲内秒，前端据此跳到播放器那一秒；
     * 它同时是模型<b>唯一允许引用的时间点来源</b>。
     */
    public record Wrong(long questionId, String type, String typeLabel, String stem,
                        String myAnswer, String topic, String knowledgePoint,
                        Integer difficulty, String difficultyLabel,
                        Long lessonId, String lessonTitle, Integer sourceTsSec, String sourceAt) {}

    /** 报告里能推荐的另一份卷。只列真实存在且已上架的，见 {@link #otherPapers}。 */
    public record PaperRef(long paperId, String title) {}

    /**
     * 事实层。<b>这一层永远算得出来</b>，模型不可用时它照常显示。
     *
     * <p>{@code questionCount} 取的是<b>答题明细的行数</b>（他真正被判分的题数），
     * 不是 exam_config 的卷面题数：题目被物理删除时两者会不一致，
     * 而学员看到的分母必须是他实际考的那些题，否则"38/50"里的 50 他自己数不出来。
     * 它<b>单独数一次 {@code exam_answer}</b>，不靠明细那条 JOIN 的行数 —— 理由见 {@link #build}。
     *
     * <p>{@code courseId} 是给报告页拼出处链接用的（{@code ?course=}）：
     * 错题的出处要能点到播放器那一讲的那一秒，而播放地址需要课程编号。
     * 它在 {@link Head} 里本来就查出来了，前端不必为它再发一次请求。
     */
    public record Stats(long attemptId, Long paperId, String paperTitle, Long courseId, int attemptNo,
                        int score, int questionCount, int passScore, boolean passed,
                        LocalDateTime submittedAt,
                        List<Bucket> byTopic, List<Bucket> byLesson,
                        List<Bucket> byType, List<Bucket> byDifficulty,
                        List<Wrong> wrong, List<PaperRef> otherPapers) {}

    /**
     * 报告页要的全部东西。
     *
     * <p>{@code canRetry} 只看状态是不是 FAILED，<b>不看限频窗口</b>：
     * 窗口没到就把"重新生成"按钮藏起来的话，学员看到的是一份失败的报告加一片空白，
     * 他唯一能做的事从界面上消失了。按早了由 {@link #retry} 给一句"还要等几分钟"，
     * 那是他能读懂的答复。
     */
    public record View(String status, Stats stats, String content, String model,
                       String failReason, boolean canRetry) {}

    // ================================================================= 事实层

    /**
     * 算这一场的事实层。纯 SQL + Java，不碰模型。
     *
     * <p><b>一条 SQL 取完全部明细再在内存里分组</b>，不是四条 GROUP BY：
     * 四条各自 JOIN 一遍的话，"按讲次的分母"与"按题型的分母"来自两次不同的读，
     * 中间只要有人改了题（换讲次、改难度），两组数字就对不上 ——
     * 而报告上两处加起来不等于总题数这件事，学员一眼就看见，我们却查不出原因。
     *
     * <h4>★ 明细那条必须是 LEFT JOIN，分母必须单独数</h4>
     * {@code exam_answer} 在交卷时对卷面<b>每一道题</b>都写了一行（题库里已经消失的那些也写，
     * 见 {@code ExamService.submit} 里 {@code standard == null} 那一支），所以"答题明细的行数"
     * 本来是稳定的。用 {@code JOIN question} 的话，题被物理删掉之后那几行整条丢失，
     * 分母跟着变小 —— 而 {@link #report} 每次都现算 stats，于是<b>一份已经生成好的报告
     * 会在题库变动之后自己变数</b>：分母从 6 变 5、四组分桶之和也变成 5、被删那道题从错题清单里消失。
     * 整卷重导（先 DELETE 再 INSERT，回填脚本的常见写法）是最坏的一档：连接结果 0 行 →
     * 报告页显示"得分 38 / 0"、四组全空、错题清单空，接口照样 200，日志一个字都没有。
     *
     * <p>所以两件事一起做：<b>①</b> LEFT JOIN 保住每一行答题明细，接不上的落到
     * {@link #GONE_LABEL} 这个明确的桶（分母不塌，且学员看得见"这里有一道题，但它下线了"）；
     * <b>②</b> {@code questionCount} 单独 {@code COUNT(*) FROM exam_answer}，
     * 不拿明细的行数顶替 —— 分母是学员对着报告数得出来的那个数，
     * 它绝不能取决于另一张表此刻还剩几行。两者对不上就 {@code log.error} 留痕
     * （与 {@code ExamService.submit} 里那条"题目下线请改 review_status"同源）。
     */
    public Stats build(long attemptId) {
        Head h = head(attemptId);

        // ★ 分母的权威来源：他被判分的题数 = 答题明细的行数。单独数一次，
        //   不受下面那条 JOIN 的成败影响（题被物理删掉时，两者正是从这里开始分叉的）。
        Integer answered = jdbc.queryForObject(
                "SELECT COUNT(*) FROM exam_answer WHERE attempt_id=?", Integer.class, attemptId);
        int questionCount = answered == null ? 0 : answered;

        // ★ 不查 q.answer / q.explanation。查出来就有机会漏出去（进 stats JSON、进提示词、
        //   进日志），而不查是结构上的不可能。与 ExamService.paper 那条纪律同源。
        //
        // qid 取的是 ea.question_id 而不是 q.id：题被删掉时 q.id 是 NULL，
        // 而那一行的题号答题明细里一直有，错题清单要能说出"是哪一道题"。
        var rows = jdbc.queryForList(
                "SELECT ea.question_id AS qid, q.id AS q_found, q.type, q.stem, q.topic, "
              + "q.knowledge_point, q.difficulty, q.lesson_id, q.source_ts_sec, "
              + "l.title AS lesson_title, l.sort_no AS lesson_sort, "
              + "ea.user_answer, ea.is_correct "
              + "FROM exam_answer ea LEFT JOIN question q ON q.id = ea.question_id "
              + "LEFT JOIN lesson l ON l.id = q.lesson_id "
              + "WHERE ea.attempt_id=? "
              // 讲次序是学员理解报告的顺序（他是按讲学的）。没挂讲次的题排最后：
              // COALESCE 给一个大数，而不是让 NULL 排在最前面顶掉第 1 讲。
              + "ORDER BY COALESCE(l.sort_no, 999999), qid", attemptId);

        // LEFT JOIN 之下这两个数必然相等（question 与 lesson 都是按主键接的，不会放大行数）。
        // 不等就说明这条 SQL 又被改回了会丢行的形状 —— 那正是要留痕的那件事。
        if (rows.size() != questionCount)
            log.error("场次 {} 的答题明细有 {} 行，取明细的连接只取回 {} 行：报告的分母与分桶将对不上，"
                    + "请查这条 SQL 是不是又丢行了", attemptId, questionCount, rows.size());

        var byTopic = new LinkedHashMap<String, int[]>();
        var byLesson = new LinkedHashMap<String, int[]>();
        var byType = new LinkedHashMap<String, int[]>();
        var byDiff = new LinkedHashMap<String, int[]>();
        var wrong = new ArrayList<Wrong>();
        var gone = new ArrayList<Long>();
        int score = 0;

        for (var r : rows) {
            boolean ok = Boolean.TRUE.equals(boolOrNull(r.get("is_correct")));
            if (ok) score++;
            long qid = num(r.get("qid"));
            String myAnswer = blankToNull(str(r.get("user_answer")));

            // LEFT JOIN 没接上 = 这道题已经被物理删除了。它与"题没标板块"是两回事，
            // 不能混进"未标注板块"那一桶：前者是我们这边的事故（该改 review_status 却删了行），
            // 后者只是数据没填全，而两者的处理方式完全不同。
            if (r.get("q_found") == null) {
                gone.add(qid);
                for (var m : List.of(byTopic, byLesson, byType, byDiff)) tally(m, GONE_LABEL, ok);
                if (ok) continue;
                // 出处一栏必须是 null：查不到题就查不到讲次与讲内秒，
                // 凭空给一个时间点等于让学员按一个不存在的位置去翻录像。
                wrong.add(new Wrong(qid, null, GONE_LABEL, GONE_STEM, myAnswer,
                        GONE_LABEL, null, null, GONE_LABEL, null, GONE_LABEL, null, null));
                continue;
            }

            String topic = str(r.get("topic"));
            String lessonTitle = str(r.get("lesson_title"));
            String type = str(r.get("type"));
            Integer diff = intOrNull(r.get("difficulty"));

            tally(byTopic, topic == null ? "未标注板块" : topic, ok);
            tally(byLesson, lessonTitle == null ? "未标注出处" : lessonTitle, ok);
            tally(byType, typeLabel(type), ok);
            tally(byDiff, difficultyLabel(diff), ok);

            if (ok) continue;
            Integer ts = intOrNull(r.get("source_ts_sec"));
            wrong.add(new Wrong(qid, type, typeLabel(type),
                    excerpt(str(r.get("stem"))),
                    // 没作答与答错是两回事：前者多半是时间不够，后者才是不会。
                    // 空串在这里必须变回 null，否则报告上写着"我的作答："后面什么都没有。
                    myAnswer,
                    topic, str(r.get("knowledge_point")), diff, difficultyLabel(diff),
                    longOrNull(r.get("lesson_id")), lessonTitle, ts,
                    ts == null ? null : ExamReportPrompt.mmss(ts)));
        }

        // 一次 build 只报一条：报告页 3 秒轮询一次，逐题报会把日志刷满，
        // 而刷满的日志与没有日志在排查时是同一件事。
        if (!gone.isEmpty())
            log.error("场次 {} 里有 {} 道题已不在题库中（题号 {}），报告按「{}」计入分母；"
                    + "题目下线请改 review_status，不要物理删除",
                    attemptId, gone.size(), gone, GONE_LABEL);

        return new Stats(attemptId, h.paperId(), h.paperTitle(), h.courseId(), h.attemptNo(),
                // 分数以库里那一列为准（判分是交卷那一刻做的），明细算出来的只用来对账：
                // 两者不一致说明有人动过 exam_answer，报告不该替它圆场。
                h.score() == null ? score : h.score(),
                questionCount, h.passScore(), Boolean.TRUE.equals(h.passed()), h.submittedAt(),
                buckets(byTopic), buckets(byLesson), buckets(byType), buckets(byDiff),
                wrong, otherPapers(h.paperId(), h.courseId()));
    }

    private record Head(long userId, Long paperId, Long courseId, String paperTitle, int attemptNo,
                        Integer score, Boolean passed, LocalDateTime submittedAt, int passScore) {}

    private Head head(long attemptId) {
        // 不 SELECT a.id / c.id / p.id：列标签会在这张大小写不敏感的 map 里互相静默覆盖
        // （ExamService.paper 里那条注释记的就是这个坑）。
        var rows = jdbc.queryForList(
                "SELECT a.user_id, a.paper_id, a.course_id, a.attempt_no, a.score, a.passed, "
              + "a.submitted_at, p.title AS paper_title, c.pass_score "
              + "FROM exam_attempt a JOIN exam_config c ON c.id = a.config_id "
              + "LEFT JOIN exam_paper p ON p.id = a.paper_id WHERE a.id=?", attemptId);
        if (rows.isEmpty()) throw ApiException.notFound(NOT_FOUND);
        var a = rows.get(0);
        return new Head(num(a.get("user_id")), longOrNull(a.get("paper_id")),
                longOrNull(a.get("course_id")), str(a.get("paper_title")),
                ((Number) a.get("attempt_no")).intValue(), intOrNull(a.get("score")),
                boolOrNull(a.get("passed")), (LocalDateTime) a.get("submitted_at"),
                ((Number) a.get("pass_score")).intValue());
    }

    /**
     * "去考另一份卷"能给出的卷名。<b>只列真实存在且已上架的</b> ——
     * 提示词把可选动作枚举死，就是为了堵住试跑里那句"拿一套新的模拟卷"（平台没有这种东西）。
     *
     * <p><b>这里不重算权益判据</b>：那条只写在 {@code ExamService.access} 一处，
     * 抄一份过来迟早分叉。取的是"同一门课的其他上架卷 + 全部开放卷" ——
     * 他刚考完这门课的卷，说明权益当时是在的。最坏的情况是推荐了一份他权益刚好到期的卷，
     * 而他点过去看到的是卡片上那句"续期后可以参加测评"，是<b>他能照着做的一句话</b>；
     * 反过来（为了严谨去查权益、查出来是空的就一份都不推荐）才是真的坏：
     * 报告里那条"接下来做什么"会变成空的。
     */
    private List<PaperRef> otherPapers(Long paperId, Long courseId) {
        var out = new ArrayList<PaperRef>();
        for (var r : jdbc.queryForList(
                "SELECT id, title FROM exam_paper WHERE status='ON' "
              + "AND (access='OPEN' OR (course_id IS NOT NULL AND course_id=?)) "
              + "ORDER BY sort_no, id LIMIT 8", courseId)) {
            long id = num(r.get("id"));
            if (paperId != null && id == paperId) continue;   // 本卷单独写"可以重考"
            out.add(new PaperRef(id, (String) r.get("title")));
        }
        return out;
    }

    private static void tally(Map<String, int[]> into, String label, boolean ok) {
        int[] cell = into.computeIfAbsent(label, k -> new int[2]);
        if (ok) cell[0]++;
        cell[1]++;
    }

    private static List<Bucket> buckets(Map<String, int[]> m) {
        var out = new ArrayList<Bucket>(m.size());
        for (var e : m.entrySet()) out.add(new Bucket(e.getKey(), e.getValue()[0], e.getValue()[1]));
        return out;
    }

    private static String typeLabel(String type) {
        if (type == null) return "其他题型";
        return switch (type) {
            case "SINGLE" -> "单选题";
            case "MULTI" -> "多选题";
            case "JUDGE" -> "判断题";
            default -> type;
        };
    }

    /** 难度的三档措辞来自规格第三节，前端与提示词共用这一处，两边不会各说一套。 */
    private static String difficultyLabel(Integer d) {
        if (d == null) return "未标难度";
        return switch (d) {
            case 1 -> "扎实理解";
            case 2 -> "需要实操";
            case 3 -> "踩过坑才会";
            default -> "难度 " + d;
        };
    }

    private static String excerpt(String stem) {
        if (stem == null) return "";
        String t = stem.replaceAll("\\s+", " ").trim();
        return t.length() <= STEM_EXCERPT ? t : t.substring(0, STEM_EXCERPT) + "…";
    }

    // ================================================================= 入队与生成

    /**
     * 交卷成功、<b>事务提交之后</b>调用：算事实层、插一行 QUEUED、把生成扔给后台。
     *
     * <p>整个方法都在 try 里，因为<b>它绝不能让交卷失败</b>：学员的卷已经判完落库了，
     * 报告只是附加物。这里抛出去的后果是前端收到 500、学员以为没交上、
     * 再点一次得到"已交卷"——一次成功的交卷被一个附加功能变成了两条投诉。
     */
    public void enqueue(long attemptId) {
        try {
            Stats stats = build(attemptId);
            Head h = head(attemptId);
            jdbc.update("INSERT INTO exam_report (attempt_id,user_id,paper_id,status,stats,requested_at) "
                      + "VALUES (?,?,?,'QUEUED',?,NOW(3))",
                    attemptId, h.userId(), h.paperId(), writeStats(stats));
        } catch (DuplicateKeyException e) {
            // 一场考试一份报告（uk_report_attempt）。重复入队不是错误：
            // 弱网重发、或者取报告那一下补的队，都会走到这里。
            log.debug("场次 {} 已经有报告行了，不重复入队", attemptId);
        } catch (RuntimeException e) {
            // ★ error 不是 warn：这一条的后果是<b>学员那一页永远转圈</b>
            //（没有行 → report 每次都补队、每次都失败 → 状态永远给不出来），
            // 而 warn 在生产日志里会被淹掉。异常类型也要打出来：触发器拒绝、
            // paper_id 撞 NOT NULL、stats 序列化不了，看着一样，修法完全不同。
            log.error("场次 {} 的报告入队失败（{}），交卷本身不受影响，但报告页会没有这一行",
                    attemptId, e.getClass().getName(), e);
            return;
        }
        Long id = reportIdOf(attemptId);
        if (id != null) dispatch(id);
    }

    private Long reportIdOf(long attemptId) {
        var rows = jdbc.queryForList("SELECT id FROM exam_report WHERE attempt_id=?", attemptId);
        return rows.isEmpty() ? null : num(rows.get(0).get("id"));
    }

    /**
     * 丢给后台跑。<b>失败只记日志</b>：库里那一行还是 QUEUED，
     * {@link #sweep} 下一轮会捡回来 —— 这正是"队列在库里"的意义。
     */
    private void dispatch(long reportId) {
        try {
            pool.execute(() -> claimAndRun(reportId));
        } catch (RuntimeException e) {
            log.warn("报告 {} 没能进后台队列，等清扫捡回", reportId, e);
        }
    }

    /**
     * 抢占并生成。
     *
     * @return 是否抢到。<b>受影响行数 = 1 才算抢到</b>，多实例部署时另一台机器拿到的是 0，
     *         直接退出 —— 先查后改在这里必然重复生成，而重复生成不报任何错，
     *         只是同一份报告被写了两遍、内容还不一样。
     */
    boolean claimAndRun(long reportId) {
        int n = jdbc.update("UPDATE exam_report SET status='RUNNING', started_at=NOW(3), "
                          + "finished_at=NULL, fail_reason=NULL WHERE id=? AND status='QUEUED'",
                reportId);
        if (n != 1) return false;
        try {
            generate(reportId);
        } catch (RuntimeException e) {
            // 兜住一切：不兜的话这一行永远停在 RUNNING，学员那边一直转圈，
            // 要等十分钟后的清扫才有人管它。
            log.warn("报告 {} 生成时异常", reportId, e);
            fail(reportId, "报告生成时出错了，请稍后重新生成");
        }
        return true;
    }

    /**
     * 生成一份（已经抢到 RUNNING 才会进来）。
     *
     * <p>时间点对不上时<b>重生成一次</b>，第二次仍然对不上就置 FAILED。
     * 不无限重试：模型编造时间点通常是这份事实清单本身的问题（比如错题全都没挂讲次），
     * 重试到第十次也一样，只是把钱和 19 秒重复烧掉。
     */
    private void generate(long reportId) {
        var rows = jdbc.queryForList("SELECT attempt_id FROM exam_report WHERE id=?", reportId);
        if (rows.isEmpty()) return;
        long attemptId = num(rows.get(0).get("attempt_id"));

        Stats stats = build(attemptId);
        String facts = ExamReportPrompt.factSheet(stats);
        Set<Integer> allowed = ExamReportPrompt.timePoints(facts);
        String statsJson = writeStats(stats);

        List<String> bad = List.of();
        for (int round = 1; round <= 2; round++) {
            var reply = model.complete(ExamReportPrompt.SYSTEM,
                    round == 1 ? facts : facts + ExamReportPrompt.RETRY_HINT);
            if (!reply.ok()) {
                // 模型不可用不重试第二轮：调不通就是调不通，重来一次只是再等一个超时。
                fail(reportId, reply.failReason());
                return;
            }
            bad = ExamReportPrompt.unknownTimePoints(reply.content(), allowed);
            if (bad.isEmpty()) {
                int n = jdbc.update("UPDATE exam_report SET status='READY', content=?, model=?, "
                                  + "stats=?, fail_reason=NULL, finished_at=NOW(3) "
                                  + "WHERE id=? AND status='RUNNING'",
                        reply.content(), reply.model(), statsJson, reportId);
                // 状态不是 RUNNING 了（被清扫退回队列、或被人手动改过）：这次的正文就此作废。
                // 硬写回去的话，一份被判定为"中途中断"的报告会突然变成 READY，
                // 而它的 tries 与 started_at 说的是另一个故事。
                if (n != 1) log.warn("报告 {} 落库时状态已变（{} 行受影响），本次正文丢弃", reportId, n);
                return;
            }
            log.warn("报告 {} 第 {} 轮引用了事实清单里没有的时间点 {}，重生成", reportId, round, bad);
        }
        fail(reportId, "模型引用了不存在的时间点（" + String.join("、", bad)
                + "），这份报告已作废。宁可没有报告，也不能让你按一个不存在的时间点去翻录像");
    }

    /** 置 FAILED。理由必须非空且不超长：库层触发器要求 FAILED 必须有 fail_reason。 */
    private void fail(long reportId, String why) {
        String reason = why == null || why.isBlank() ? "报告生成失败，请稍后重新生成" : why.trim();
        if (reason.length() > FAIL_REASON_MAX) reason = reason.substring(0, FAIL_REASON_MAX);
        jdbc.update("UPDATE exam_report SET status='FAILED', fail_reason=?, finished_at=NOW(3) "
                  + "WHERE id=? AND status='RUNNING'", reason, reportId);
    }

    // ================================================================= 清扫

    /**
     * 一分钟一轮：先自愈卡住的行，再把队列里积压的捡起来跑。
     *
     * <p>{@code fixedDelay} 而不是 {@code fixedRate}：上一轮没跑完不该起下一轮。
     * 捡队列这一步不只是补漏 —— <b>它是多实例与重启之后唯一的兜底</b>：
     * 入队那一下丢给的是<b>本进程</b>的线程池，进程一重启，那些任务就没了，
     * 而库里的行还老老实实停在 QUEUED。
     */
    @Scheduled(fixedDelayString = "${wangke.report.sweep-interval-ms:60000}",
               initialDelayString = "${wangke.report.sweep-initial-delay-ms:30000}")
    public void sweep() {
        try {
            int healed = requeueStuck();
            int picked = dispatchQueued();
            if (healed > 0 || picked > 0)
                log.info("报告清扫：自愈 {} 行，捡起 {} 行排队中的", healed, picked);
        } catch (RuntimeException e) {
            // 定时任务抛出去会被框架吞掉（下一轮照跑），但不留痕的话
            // "报告全都不生成了"这件事没有任何线索。
            log.warn("报告清扫失败", e);
        }
    }

    /**
     * 卡在 RUNNING 的行退回队列；重试到顶的置 FAILED。
     *
     * <p>两条 UPDATE 按 {@code tries+1} 是否到顶分开写，条件互斥、顺序无关。
     * 合成一条 {@code IF()} 也能写，但 FAILED 那一条必须同时写 fail_reason
     * （触发器要求），拆开之后这件事一眼看得见。
     */
    int requeueStuck() {
        int dead = jdbc.update(
                "UPDATE exam_report SET status='FAILED', tries=tries+1, finished_at=NOW(3), "
              + "fail_reason=? WHERE status='RUNNING' AND started_at < DATE_SUB(NOW(3), INTERVAL ? MINUTE) "
              + "AND tries+1 >= ?",
                "报告生成连续 " + MAX_TRIES + " 次中途中断（多半是服务重启），请点重新生成再试一次",
                STUCK_MINUTES, MAX_TRIES);
        int back = jdbc.update(
                "UPDATE exam_report SET status='QUEUED', tries=tries+1, started_at=NULL "
              + "WHERE status='RUNNING' AND started_at < DATE_SUB(NOW(3), INTERVAL ? MINUTE) "
              + "AND tries+1 < ?", STUCK_MINUTES, MAX_TRIES);
        return dead + back;
    }

    /** 把库里排队的捡起来丢给线程池。一次只捡一批，队列满了丢掉也无所谓（下一轮还在）。 */
    int dispatchQueued() {
        var ids = jdbc.query("SELECT id FROM exam_report WHERE status='QUEUED' "
                           + "ORDER BY requested_at, id LIMIT 20", (rs, i) -> rs.getLong(1));
        for (long id : ids) dispatch(id);
        return ids.size();
    }

    // ================================================================= 对外

    /**
     * 报告页要的全部内容。
     *
     * <p><b>越权判据写在 SQL 的 WHERE 里</b>（{@code AND a.user_id=?}），
     * 不是"查出来再比对"：后者只要有人加一条"查不到报另一种错"的分支，
     * 这个接口就变成了一个可以枚举别人考试编号的探针。"不是你的"与"不存在"对外同一种结果。
     *
     * <p><b>QUEUED / RUNNING / FAILED 都照常返回 stats</b>：分数与错题是客观的，
     * 学员打开这一页的第一秒就该看到它们，而不是等一个 19 秒的外部调用。
     *
     * <p>stats <b>每次现算</b>，不读库里那一列：那一列存的是<b>喂给模型的那一份快照</b>
     * （日后要能对着它复盘模型为什么这么写），而学员看的应该是现在的事实。
     * 更要紧的是，报告行还不存在时（老场次、入队失败）现算仍然给得出来 ——
     * 读库的写法在那种情况下只能返回空，而空的事实层正是这套设计要防的头号故障。
     */
    public View report(long userId, long attemptId) {
        var rows = jdbc.queryForList(
                "SELECT a.submitted_at, r.status, r.content, r.model, r.fail_reason "
              + "FROM exam_attempt a LEFT JOIN exam_report r ON r.attempt_id = a.id "
              + "WHERE a.id=? AND a.user_id=?", attemptId, userId);
        if (rows.isEmpty()) throw ApiException.notFound(NOT_FOUND);
        var r = rows.get(0);
        if (r.get("submitted_at") == null)
            throw ApiException.conflict("这一场还没有交卷，交卷之后才会生成测评报告");

        Stats stats = build(attemptId);
        String status = str(r.get("status"));
        if (status == null) {
            // 还没有报告行：交卷那一下入队失败了，或者这是上线之前考的老场次。
            // 这里顺手补一次队 —— 学员刷新一下就有了，比让他去找客服强。
            enqueue(attemptId);
            // 补队之后重新读一次状态：同步执行器（或极快的失败，例如没配 key）
            // 会让这一行在 enqueue 返回时已经是 READY / FAILED 了，
            // 硬写 QUEUED 的话，学员看到的是"生成中"，而它其实已经失败并写好了理由。
            var after = jdbc.queryForList(
                    "SELECT status, content, model, fail_reason FROM exam_report WHERE attempt_id=?",
                    attemptId);
            // ★ 补了队还是没有行 = enqueue 真的失败了（它自己吞异常，见那条 log.error）。
            // 这里再返回 QUEUED 的话，学员按规格第五节轮询 3 秒 × 40 次，
            // 每一次都重走一遍"补队 → 失败"，两分钟之后看到的仍然是"还在生成"：
            // failReason 是 null、canRetry 是 false —— 界面上再没有任何出口。
            // 所以给 FAILED + 一句人话 + 把重试按钮打开，让他至少还有一件能做的事。
            if (after.isEmpty()) {
                log.error("场次 {} 补队之后仍然没有报告行，报告页按 FAILED 显示", attemptId);
                return new View("FAILED", stats, null, null, ENQUEUE_FAILED, true);
            }
            var a = after.get(0);
            String now = str(a.get("status"));
            return new View(now, stats, str(a.get("content")), str(a.get("model")),
                    str(a.get("fail_reason")), "FAILED".equals(now));
        }
        return new View(status, stats, str(r.get("content")), str(r.get("model")),
                str(r.get("fail_reason")), "FAILED".equals(status));
    }

    /**
     * 重新生成。<b>只有 FAILED 可以</b>，且同一场 {@value #RETRY_COOLDOWN_MINUTES} 分钟一次。
     *
     * <p>限频不是防攻击，是防连点：一次生成要 19 秒也要钱，
     * 而失败页上那个按钮恰恰是学员最想连按的东西。
     */
    public void retry(long userId, long attemptId) {
        var rows = jdbc.queryForList(
                "SELECT r.id, r.status, TIMESTAMPDIFF(SECOND, r.updated_at, NOW(3)) AS since "
              + "FROM exam_attempt a LEFT JOIN exam_report r ON r.attempt_id = a.id "
              + "WHERE a.id=? AND a.user_id=?", attemptId, userId);
        if (rows.isEmpty()) throw ApiException.notFound(NOT_FOUND);
        var r = rows.get(0);
        if (r.get("status") == null) {
            enqueue(attemptId);
            // 补队之后还是没有行：与 report() 那一处同一件事（enqueue 自己吞了异常）。
            // 这里再回 200 的话，按钮按下去什么都不会发生，而学员看不出为什么。
            if (reportIdOf(attemptId) == null) throw new ApiException(500, ENQUEUE_FAILED);
            return;
        }

        String status = str(r.get("status"));
        if (!"FAILED".equals(status))
            throw ApiException.conflict("READY".equals(status)
                    ? "这份报告已经生成好了，不需要重新生成"
                    : "报告正在生成中，请稍候");

        int since = (int) Math.max(0, ((Number) r.get("since")).longValue());
        int wait = RETRY_COOLDOWN_MINUTES * 60 - since;
        if (wait > 0)
            throw new ApiException(429, "上一次生成刚刚结束，请 " + ExamService.humanize(wait) + "后再试");

        long id = num(r.get("id"));
        // tries 清零：这是学员主动要的一次重来，不该继承上一轮崩溃留下的计数
        // （否则一个 tries=3 的行刚回到队列，下一轮清扫就把它判死，按了等于没按）。
        int n = jdbc.update("UPDATE exam_report SET status='QUEUED', tries=0, fail_reason=NULL, "
                          + "started_at=NULL, finished_at=NULL, requested_at=NOW(3) "
                          + "WHERE id=? AND status='FAILED'", id);
        // 条件更新是并发双击的唯一裁决点：抢输的那一条看到 0 行。
        if (n != 1) throw ApiException.conflict("报告状态刚刚变了，请刷新后再看");
        dispatch(id);
    }

    // ================================================================= 杂项

    private String writeStats(Stats s) {
        try {
            return json.writeValueAsString(s);
        } catch (Exception e) {
            // stats 是 NOT NULL 列，序列化不了就没法入队。这不是"降级成空对象"能糊过去的事：
            // 空的 stats 会让报告页显示一场 0 分 0 题的考试。
            throw new IllegalStateException("事实层无法序列化", e);
        }
    }

    private static String str(Object v) { return v == null ? null : String.valueOf(v); }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }

    /** BIGINT UNSIGNED 在 Connector/J 里是 BigInteger，不是 Long，直接强转会 ClassCastException。 */
    private static long num(Object v) { return ((Number) v).longValue(); }

    private static Long longOrNull(Object v) { return v == null ? null : ((Number) v).longValue(); }

    private static Integer intOrNull(Object v) { return v == null ? null : ((Number) v).intValue(); }

    /** TINYINT(1) 取回来可能是 Boolean 也可能是 Integer/Byte，取决于驱动配置。 */
    private static Boolean boolOrNull(Object v) {
        if (v == null) return null;
        if (v instanceof Boolean b) return b;
        return ((Number) v).intValue() != 0;
    }
}
