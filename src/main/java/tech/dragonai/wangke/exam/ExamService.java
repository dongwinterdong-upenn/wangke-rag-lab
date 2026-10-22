package tech.dragonai.wangke.exam;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tech.dragonai.wangke.web.ApiException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 测评：开考 → 取题 → 交卷判分。
 *
 * <h3>这一层守的三条线</h3>
 * <ol>
 *   <li><b>答案只在服务端。</b>下发考卷的那条路径<b>连 answer 列都不查</b> ——
 *       不查出来就不可能漏出去。只断言"响应里没有答案字段"是弱判据，
 *       字段是人加的，哪天有人为了做"答题卡回显"顺手把 question 整行带上，
 *       判据当场失效而且不会有任何报错：前端多了个字段，考试照常进行，
 *       只是每个人都满分。
 *   <li><b>判分依据是快照，不是"当前"。</b>抽到的题号存进 exam_attempt.question_ids，
 *       及格线取 attempt.config_id 那一行而不是"现行规则"。开考后管理员改了及格线，
 *       用现行规则算出的 passed 会与触发器（它按 attempt.config_id 校验）算的不一致，
 *       表现是<b>学员考完了交不上卷</b>，且提示是一句数据库触发器文案。
 *   <li><b>时间只有一个钟。</b>所有时间判定取 {@code SELECT NOW(3)}，不取应用进程的时钟。
 *       多实例部署时各机器差几秒是常态，用本机时钟会出现"在 A 机开考、到 B 机交卷
 *       被判超时"这类无法复现的投诉 —— 而它只发生在负载均衡恰好把两次请求分到
 *       不同机器时，测试环境（单实例）永远重现不出来。
 * </ol>
 *
 * <h3>与触发器的分工</h3>
 * trg_exam_attempt_ins/upd 已经强制了：分数不超过卷面题量、passed 与分数一致、
 * 没有交卷时间不得有分、attempt_no 不超上限、交卷后不得改分、
 * attempt 的 paper_id 必须与它引用的那条 exam_config 的 paper_id 一致。
 * 这里<b>不重复实现</b>这些，只做两件触发器做不了的事：
 * 提前给出人话（"次数已用完"而不是等到 INSERT 才被拒），
 * 以及触发器看不见的前置条件（权益、进度、冷却）。
 *
 * <h3>一份卷才是入口，课程不是（2026-09-03 起）</h3>
 * 原来是"一门课一套卷"，全部入口参数是 courseId。现在一门课下面挂着综合卷与四份
 * 章节卷，另有一份不挂任何课程、对所有注册学员开放的卷，所以入口一律是 <b>paperId</b>：
 * <ul>
 *   <li><b>身份与准入在 exam_paper</b>（挂哪门课、要不要买课、发不发证、上没上架），
 *       <b>规则仍在 exam_config</b> —— 它的 effective_from 版本化机制不能动：
 *       改规则是插新行，已开考的场次按 attempt.config_id 引用旧行，
 *       否则学员考到一半规则变了会交不上卷。
 *   <li><b>可见与能考出自同一段判据</b>（{@link #access}），但分成两档：
 *       <b>不可见</b>（压根没有这门课的权益）与<b>可见但不能考</b>（权益到期 / 被停用）。
 *       后者仍然留在列表里，卡上写着"观看有效期已结束，续期后可以参加测评" ——
 *       整张卷从界面上消失的话，到期学员只会以为"我的卷不见了"，
 *       而他真正要做的事（续期）再也没有任何地方会告诉他。
 *       两档由同一个方法给出，所以"列表里明明有这张卷、点进去 403"仍然不会发生。
 *   <li><b>access='OPEN' 是全平台唯一一个跳过权益校验的口子</b>，
 *       而且必须由 {@code exam_paper.access} 这个字段驱动 ——
 *       任何"在控制器里对某个 paperId 特殊放行"的写法都不许有：
 *       那样一来"哪些卷不用买课"这件事就不在库里，没有任何地方查得到。
 * </ul>
 *
 * <h3>交卷之后还挂着一件事：测评报告（2026-09-03 起）</h3>
 * {@link #submit} 成功之后给 {@link ExamReportService} 排一次队，而且是
 * <b>在事务提交之后</b>、只在真正交上的那一次。理由写在那段代码旁边：
 * 报告要调一次外部模型（实测 19.3 秒），它既不能进事务，也不能挂在交卷这条请求上，
 * 更不能因为自己失败就把一次成功的交卷变成学员眼里的 500。
 */
@Service
public class ExamService {

    private static final Logger log = LoggerFactory.getLogger(ExamService.class);

    /** user_answer 是 VARCHAR(32)。超长不先拦的话，MySQL 严格模式会抛数据截断，
     *  经全局处理器变成一句"系统繁忙"的 500 —— 一个客户端参数问题被记成系统故障。 */
    private static final int MAX_ANSWER_LEN = 32;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    /** 交卷之后给报告排队用。见 {@link #submit} 末尾那段注释；为空 = 这套装配不出报告。 */
    private final ExamReportService reports;

    @Autowired
    public ExamService(JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper json,
                       ExamReportService reports) {
        this.jdbc = jdbc; this.tx = tx; this.json = json; this.reports = reports;
    }

    /**
     * 不带报告的装配。<b>只给不涉及报告的测试夹具用</b>（ExamIT / HomeIT 里那两处
     * {@code new ExamService(jdbc, tx, mapper)}）—— 生产走上面那个 {@code @Autowired} 的构造。
     *
     * <p>留这个口子而不是让那两个夹具一起改，是因为它们的库里根本没有 {@code exam_report} 表：
     * 强行接上报告，那两套判据会因为一个与它们无关的功能整片红掉，
     * 而红的原因看起来像"交卷坏了"。
     */
    public ExamService(JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper json) {
        this(jdbc, tx, json, null);
    }

    // ================================================================= 数据形状

    /** 测评规则。全部来自 exam_config，代码里一个数字都不写死。 */
    public record Rules(long configId, int questionCount, int passScore, int durationMin,
                        boolean shuffleOptions, int maxAttempts, int retryCooldownHours,
                        int requireProgressPct) {}

    public record Option(String key, String text) {}

    /**
     * 下发给考生的一道题。
     *
     * <p><b>这个 record 里没有 answer、没有 explanation，是判据不是疏忽。</b>
     * 前端判分是假判分：JS 能读到的东西等于公开。ExamIT 会把控制器的真实响应
     * 序列化成 JSON 后遍历整棵树，断言不存在任何名字里带 answer/correct/explan 的字段。
     * 加字段前先想清楚这条判据为什么会红。
     */
    public record PaperQuestion(long id, String type, String stem, List<Option> options) {}

    /**
     * 一场考试的全部可见信息。
     *
     * <p>{@code paperTitle} 与 {@code grantsCertificate} 是<b>这一场考的是哪份卷</b>的自述：
     * 成绩页要据此决定给不给"去领结业证明"那个入口。前端按"默认发证"猜的话，
     * 考完 50 题章节卷的人会看到一个领证按钮，点下去被 409 —— 而那是他做对了所有事之后
     * 得到的一句拒绝。paperId 是取不出这两件事的，它只是个数字。
     */
    public record Paper(long attemptId, Long paperId, String paperTitle, boolean grantsCertificate,
                        int attemptNo, Rules rules,
                        LocalDateTime deadlineAt,
                        int secondsLeft, boolean submitted, Integer score, Boolean passed,
                        List<PaperQuestion> questions) {}

    /** courseId 可以为空：开放卷不挂课程。用 long 接的话，NULL 会静默变成课程 0。 */
    public record AttemptRow(long id, Long paperId, Long courseId, int attemptNo,
                             Integer score, Boolean passed,
                             LocalDateTime startedAt, LocalDateTime deadlineAt,
                             LocalDateTime submittedAt, String state) {}

    /**
     * 一份卷的身份与准入。规则不在这里，在 {@link Rules}。
     *
     * <p>{@code courseId} 为空 = 开放卷；{@code access} 是 ENROLLED / OPEN，
     * 前者要买课后者不要，见 {@link #access}。
     */
    public record PaperRow(long id, String code, String title, String subtitle,
                           Long courseId, String courseTitle, String access,
                           boolean grantsCertificate, int sortNo) {}

    /**
     * 学员看到的测评入口状态。canStart / blockedReason 与 {@link #start} 用的是同一段判定。
     *
     * <p>{@code progressPct} 可以为空：开放卷不挂课程，"学到百分之几"这件事对它
     * 根本不存在。填 0 的话界面上会写着"当前学习进度 0%"，而学员并没有落下任何东西。
     */
    public record Status(Rules rules, Integer progressPct, int attemptsUsed, int attemptsLeft,
                         Long inProgressAttemptId, Long passedAttemptId,
                         Integer cooldownSecondsLeft, boolean canStart, String blockedReason) {}

    /**
     * 学员端"我能考哪几份卷"列表里的一张卡。
     *
     * <p>{@code rules} 可以为空：这份卷上架了却没有配规则时，卡片仍然要出现并说明原因，
     * 不能从列表上消失 —— 消失了学员只会以为"我的卷不见了"，而这是平台配错了。
     * 同理 {@code progressPct}/{@code attemptsLeft} 也用包装类型：<b>空的语义是"不适用/取不到"，
     * 不是 0</b>（0 次可考与"不知道还剩几次"在界面上是两句完全不同的话）。
     */
    public record PaperCard(long paperId, String code, String title, String subtitle,
                            Long courseId, String courseTitle, String access,
                            boolean grantsCertificate, int sortNo,
                            Rules rules, Integer progressPct, int attemptsUsed, Integer attemptsLeft,
                            Long inProgressAttemptId, Long passedAttemptId,
                            Integer cooldownSecondsLeft, boolean canStart, String blockedReason) {}

    public record StartResult(boolean firstTime, long attemptId, int attemptNo,
                              LocalDateTime deadlineAt, int questionCount) {}

    public record SubmitResult(boolean firstTime, long attemptId, int score, int questionCount,
                               int passScore, boolean passed) {}

    // ================================================================= 对外方法

    /**
     * 我能考的全部卷（学员端 /exam 那一屏）。
     *
     * <p>过滤用的是 {@link #access} —— 和开考闸门<b>同一段代码</b>。
     * 所以"列表里有、点进去被拒"这件事在结构上就不可能发生：
     * 卡上写着能考的卷，权益判据已经过了。
     *
     * <p><b>权益到期 / 被停用的卷留在列表里，但不能考。</b>只有"压根没有这门课的权益"
     * 才彻底不可见（那门课他没买过，列出来只会让他莫名其妙）。到期的卷一旦整张消失，
     * 学员看到的是"我的卷不见了"，而平台想让他做的事是续期 ——
     * 这句话除了这张卡以外没有任何地方会说。
     *
     * <p><b>一份卷坏了不许把整张列表打没。</b>某份卷没配规则、或者它那门课的数据坏了，
     * 都只影响它自己那张卡；不兜的话学员看到的是一片空白，
     * 而他另外五份能考的卷就此从界面上消失了。
     */
    public List<PaperCard> papers(long userId) {
        LocalDateTime now = dbNow();
        Cache cache = new Cache(userId);
        List<PaperCard> out = new ArrayList<>();
        for (PaperRow p : loadPapers(null)) {
            Access ac = access(p, now, cache);
            if (ac.denial() != null && !ac.visible()) continue;      // 不可见：这门课他没有过权益
            try {
                // 可见但不能考的卷照样把规则算出来（题数、时长、及格线对他仍然有意义），
                // 只是 canStart 一律 false、理由是权益那句话 —— 见 gate 的 preDenial。
                out.add(card(p, gate(p, now, cache, ac.denial())));
            } catch (ApiException e) {
                // 这份卷还没配规则之类：如实把那句话带给学员，不是系统故障，不打 WARN。
                // 权益那句话优先：它比"没配规则"更贴近他能做的事（续期）。
                out.add(card(p, null, ac.denial() != null ? ac.denial() : e.getMessage()));
            } catch (RuntimeException e) {
                log.warn("试卷 {} 的测评状态取不到 userId={}", p.id(), userId, e);
                out.add(card(p, null, "这份试卷的测评状态暂时取不到"));
            }
        }
        return out;
    }

    /** 这份卷的测评规则 + 这个学员当前能不能考。 */
    public Status status(long userId, long paperId) {
        LocalDateTime now = dbNow();
        Cache cache = new Cache(userId);
        Gate g = gate(requirePaper(paperId), now, cache);
        // 剩余次数按 nextAttemptNo 算，与闸门里那个 nextNo > max_attempts 同一个式子。
        // 用 max_attempts - 行数 算的话，历史行被删过之后界面会显示"还剩 1 次"，
        // 点下去被拒 —— 又是一处"两个式子说同一件事"的分叉。
        return new Status(g.rules(), g.progressPct(), g.attemptsUsed(),
                attemptsLeft(g), g.inProgressId(), g.passedAttemptId(), g.cooldownSecondsLeft(),
                g.canStart(), g.blocked());
    }

    private static int attemptsLeft(Gate g) {
        return Math.max(0, g.rules().maxAttempts() - (g.nextAttemptNo() - 1));
    }

    private PaperCard card(PaperRow p, Gate g) {
        return new PaperCard(p.id(), p.code(), p.title(), p.subtitle(), p.courseId(),
                p.courseTitle(), p.access(), p.grantsCertificate(), p.sortNo(),
                g.rules(), g.progressPct(), g.attemptsUsed(), attemptsLeft(g),
                g.inProgressId(), g.passedAttemptId(), g.cooldownSecondsLeft(),
                g.canStart(), g.blocked());
    }

    /** 规则都取不到时的卡：数字一律留空（空 = 不知道，不是 0），只把原因原句带出去。 */
    private PaperCard card(PaperRow p, Rules none, String reason) {
        return new PaperCard(p.id(), p.code(), p.title(), p.subtitle(), p.courseId(),
                p.courseTitle(), p.access(), p.grantsCertificate(), p.sortNo(),
                none, null, 0, null, null, null, null, false, reason);
    }

    /**
     * 开考。
     *
     * <p><b>可安全重复提交</b>：已经有一场进行中的考试时返回那一场，而不是新开一场。
     * 弱网下学员连点两下"开始考试"是常态，新开一场等于白白烧掉一次补考机会，
     * 而这种损失是不可逆的（次数上限由触发器兜底，谁也退不回来）。
     */
    public StartResult start(long userId, long paperId) {
        LocalDateTime now = dbNow();
        PaperRow p = requirePaper(paperId);
        Gate g = gate(p, now, new Cache(userId));

        // 已经在考的那场直接还给他：此时任何"不能开考"的理由都不适用，
        // 他已经在考场里了，把他挡在门外只会让他丢掉这一场。
        if (g.inProgressId() != null) {
            var a = jdbc.queryForMap(
                    "SELECT attempt_no, deadline_at, question_ids FROM exam_attempt WHERE id=?",
                    g.inProgressId());
            return new StartResult(false, g.inProgressId(),
                    ((Number) a.get("attempt_no")).intValue(),
                    (LocalDateTime) a.get("deadline_at"),
                    parseIds((String) a.get("question_ids")).size());
        }
        if (g.blocked() != null) throw ApiException.conflict(g.blocked());

        Rules r = g.rules();
        if (r.durationMin() <= 0)
            throw ApiException.conflict("测评规则里的考试时长为 " + r.durationMin()
                    + " 分钟，无法开考，请联系平台修正规则");
        // 卷面题数 <= 0 必须在抽题之前拦。放过去有两种坏法，都不报错：
        //   · 0 题 —— 下面那道"题库不足"的门变成 0 < 0 恒假，于是开出一场零题考试，
        //     交卷判 0 分，而 pass_score 也是 0 时 passed=true：一场空考换出结业证明，
        //     库那三条触发器（分数≤题量、passed 与分数一致、次数上限）逐条满意。
        //   · 负数 —— 直接拼成 LIMIT -5，SQL 语法错，经全局处理器变成一句"系统繁忙"的 500。
        // 管理端 PUT 已经挡了 1..MAX，但这道门守的是"有人直接改库/导数据"那条路。
        if (r.questionCount() <= 0)
            throw ApiException.conflict("测评规则里的卷面题数是 " + r.questionCount()
                    + " 道，无法开考，请联系平台修正规则");

        // 只抽<b>本卷</b>已审核通过的题（§3.3）。按 course_id 抽的话，
        // 第一章的卷会抽到第四章的题 —— 而这不会报任何错，只是学员考的不是他点的那份卷。
        // ORDER BY RAND() 在题库上是全表打分排序，题库是几百到几千行的量级，
        // 这点开销换来的是"不用维护一张抽题索引表"。
        List<Long> ids = jdbc.query(
                "SELECT id FROM question WHERE paper_id=? AND review_status='APPROVED' "
              + "ORDER BY RAND() LIMIT " + r.questionCount(),
                (rs, i) -> rs.getLong(1), paperId);

        // 题库不足时必须拒绝开考，不能"有几道出几道"。
        // 卷面 40 题、及格线 90 分的考卷，每个人都必然不及格 —— 而这件事不会报任何错，
        // 只会表现为"这份卷所有人都考不过"，等有人投诉时已经烧掉了大家的补考次数。
        if (ids.size() < r.questionCount())
            throw ApiException.conflict("本卷已审核通过的题目只有 " + ids.size()
                    + " 道，不足卷面要求的 " + r.questionCount() + " 道，暂不能开考");

        LocalDateTime deadline = now.plusMinutes(r.durationMin());
        String snapshot = writeIds(ids);
        int nextNo = g.nextAttemptNo();

        try {
            // started_at 显式写 now 而不是让它取默认的 CURRENT_TIMESTAMP(3)：
            // deadline_at 是按 now 算的，两个时间取自不同时刻时 ck_attempt_time
            // （deadline > started）在 duration 为 0 的边界上会翻脸。同一个瞬间算出来的最稳。
            //
            // course_id 跟着卷走（开放卷写 NULL），不是另找一处推出来的：
            // 它是证书那条链上的判据（trg_certificate_precheck 按 user+course 核对），
            // 与卷不一致的话，考的是 A 课的卷、证书发的是 B 课。
            jdbc.update("INSERT INTO exam_attempt (user_id,course_id,paper_id,config_id,attempt_no,"
                      + "question_ids,started_at,deadline_at) VALUES (?,?,?,?,?,?,?,?)",
                    userId, p.courseId(), paperId, r.configId(), nextNo, snapshot, now, deadline);
        } catch (DuplicateKeyException e) {
            // 并发双击开考：两条请求都算出同一个 attempt_no，
            // uk_user_paper_attempt 是唯一的裁决点 —— 先查后写在这里必然漏，
            // 两边都会查到"还没有第 N 场"然后各插一条。
            // 抢输的这条回头把赢家那场读出来当成自己的，学员看到的就是一场考试。
            var again = jdbc.queryForList(
                    "SELECT id, deadline_at FROM exam_attempt "
                  + "WHERE user_id=? AND paper_id=? AND attempt_no=?", userId, paperId, nextNo);
            if (again.isEmpty()) throw e;   // 不是 attempt_no 撞的，别把真错误吃掉
            return new StartResult(false, num(again.get(0).get("id")), nextNo,
                    (LocalDateTime) again.get(0).get("deadline_at"), ids.size());
        }

        long attemptId = num(jdbc.queryForObject(
                "SELECT id FROM exam_attempt WHERE user_id=? AND paper_id=? AND attempt_no=?",
                Number.class, userId, paperId, nextNo));
        return new StartResult(true, attemptId, nextNo, deadline, ids.size());
    }

    /**
     * 取考卷。<b>绝不返回正确答案。</b>
     *
     * <p>越权检查写在 SQL 的 WHERE 里（{@code AND user_id=?}），不是"查出来再比对"：
     * 后者只要哪天有人加一条"查不到报另一种错"的分支，就变成了一个可以枚举
     * 别人考卷 id 的探针。条件在 WHERE 里时，"不存在"与"不是你的"对外是同一种结果。
     */
    public Paper paper(long userId, long attemptId) {
        LocalDateTime now = dbNow();
        // 不 SELECT a.id：它的列标签也叫 id，会和 c.id 撞在同一张大小写不敏感的 map 里，
        // 于是 configId 静默地取到了 attemptId。这种错不会报错，只会让规则读到别的行。
        // p.title 必须起别名：它与 c.* 里没有重名列，但列标签一旦和别的表撞上，
        // 在这张大小写不敏感的 map 里是静默覆盖（见上一段注释里 config_id 那个坑）。
        // LEFT JOIN 而不是 JOIN：paper_id 是可空列，历史行为空时这一场仍然要查得出来，
        // 否则一条老记录会表现成"考试记录不存在"。
        var rows = jdbc.queryForList(
                "SELECT a.attempt_no, a.question_ids, a.deadline_at, a.submitted_at, "
              + "a.score, a.passed, a.paper_id, p.title AS paper_title, p.grants_certificate, "
              + "c.id AS config_id, c.question_count, c.pass_score, "
              + "c.duration_min, c.shuffle_options, c.max_attempts, c.retry_cooldown_hours, "
              + "c.require_progress_pct "
              + "FROM exam_attempt a JOIN exam_config c ON c.id = a.config_id "
              + "LEFT JOIN exam_paper p ON p.id = a.paper_id "
              + "WHERE a.id=? AND a.user_id=?", attemptId, userId);
        if (rows.isEmpty()) throw ApiException.notFound("考试记录不存在");
        var a = rows.get(0);

        Rules r = rulesOf(a);
        List<Long> ids = parseIds((String) a.get("question_ids"));
        LocalDateTime deadline = (LocalDateTime) a.get("deadline_at");
        LocalDateTime submitted = (LocalDateTime) a.get("submitted_at");

        var questions = loadPaperQuestions(attemptId, ids, r.shuffleOptions());

        int left = submitted != null ? 0
                : (int) Math.max(0, Duration.between(now, deadline).toSeconds());
        // 交卷后仍然不给答案：题库是要复用的，交完卷把整卷答案发出去，
        // 等于每考一次就往外送一份题库，下一届考生直接照抄。
        return new Paper(attemptId, longOrNull(a.get("paper_id")),
                (String) a.get("paper_title"),
                // 卷被删/paper_id 为空时按"不发证"算：宁可少给一个领证入口，
                // 不可给一个点下去必然 409 的入口。
                Boolean.TRUE.equals(boolOrNull(a.get("grants_certificate"))),
                ((Number) a.get("attempt_no")).intValue(), r, deadline, left,
                submitted != null, intOrNull(a.get("score")), boolOrNull(a.get("passed")), questions);
    }

    /**
     * 交卷判分。
     *
     * @param answers 题号（字符串）→ 作答。没答的题不必出现在 map 里。
     */
    public SubmitResult submit(long userId, long attemptId, Map<String, String> answers) {
        LocalDateTime now = dbNow();
        var rows = jdbc.queryForList(
                "SELECT a.question_ids, a.deadline_at, a.submitted_at, a.score, a.passed, "
              + "c.question_count, c.pass_score "
              + "FROM exam_attempt a JOIN exam_config c ON c.id = a.config_id "
              + "WHERE a.id=? AND a.user_id=?", attemptId, userId);
        if (rows.isEmpty()) throw ApiException.notFound("考试记录不存在");
        var a = rows.get(0);

        int passScore = ((Number) a.get("pass_score")).intValue();
        int paperSize = ((Number) a.get("question_count")).intValue();

        // 幂等：重复交卷返回上次的成绩，不当错误。
        // 库层（trg_exam_attempt_upd 的"已交卷的考试不得改分"）已经保证改不了分，
        // 这里是为了别让弱网重发变成一句"已交卷不得改分"的技术味提示。
        if (a.get("submitted_at") != null)
            return new SubmitResult(false, attemptId, intOrNull(a.get("score")),
                    paperSize, passScore, Boolean.TRUE.equals(boolOrNull(a.get("passed"))));

        LocalDateTime deadline = (LocalDateTime) a.get("deadline_at");
        // 超时不判分也不落分：判 0 分等于替学员交了一张白卷，
        // 而"没交卷"与"交了 0 分"在申诉时是两回事，库里必须分得清。
        if (now.isAfter(deadline))
            throw ApiException.conflict("考试时间已于 " + deadline + " 结束，本场未能提交");

        List<Long> ids = parseIds((String) a.get("question_ids"));
        Set<Long> onPaper = new HashSet<>(ids);

        // 提交了不在本场考卷里的题号：要么客户端串了场次，要么有人在试探。
        // 静默忽略会让前一种情况变成"分数莫名偏低"的玄学工单，所以显式拒绝。
        Map<Long, String> given = new HashMap<>();
        if (answers != null) for (var e : answers.entrySet()) {
            long qid;
            try {
                qid = Long.parseLong(e.getKey().trim());
            } catch (RuntimeException ex) {
                throw ApiException.badRequest("题号 " + e.getKey() + " 不是合法的题目编号");
            }
            if (!onPaper.contains(qid))
                throw ApiException.badRequest("第 " + qid + " 题不在本场考卷内，请刷新后重新作答");
            if (e.getValue() != null && e.getValue().length() > MAX_ANSWER_LEN)
                throw ApiException.badRequest("第 " + qid + " 题的作答内容过长");
            given.put(qid, e.getValue());
        }

        Map<Long, String> correct = new HashMap<>();
        for (var q : jdbc.query("SELECT id, answer FROM question WHERE id IN ("
                              + placeholders(ids.size()) + ")",
                (rs, i) -> Map.entry(rs.getLong("id"), rs.getString("answer")), ids.toArray()))
            correct.put(q.getKey(), q.getValue());

        int score = 0;
        List<Object[]> batch = new ArrayList<>(ids.size());
        for (Long qid : ids) {
            String standard = correct.get(qid);
            if (standard == null) {
                // 考卷里的题从题库里消失了（被物理删除）。此时这道题任谁都拿不到分，
                // 学员的得分上限被悄悄压低了 —— 触发器只管 score ≤ 题量，看不见这件事。
                // 必须留痕，否则学员申诉时没有任何人知道发生过。
                log.error("考卷 attempt={} 引用的题目 question={} 已不在题库中，该题必然计错；"
                        + "题目下线请改 review_status，不要物理删除", attemptId, qid);
            }
            String ua = normalizeAnswer(given.get(qid));
            boolean ok = standard != null && ua != null && ua.equals(normalizeAnswer(standard));
            if (ok) score++;
            // 没作答的题也写一行（user_answer 为 NULL）：事后复盘要分得清
            // "答错了"和"根本没答"，前者是不会，后者多半是时间不够。
            batch.add(new Object[]{attemptId, qid, ua, ok ? 1 : 0});
        }

        final int finalScore = score;
        final boolean passed = score >= passScore;
        SubmitResult result = tx.execute(st -> {
            // 条件更新（submitted_at IS NULL）是并发重复交卷的唯一裁决点。
            // 抢输的那条 UPDATE 会先阻塞在行锁上，等赢家提交后才返回 0 行，
            // 所以此刻一定读得到已落库的成绩 —— 不会出现"两边都以为自己写成功了"。
            int n = jdbc.update("UPDATE exam_attempt SET score=?, passed=?, submitted_at=? "
                              + "WHERE id=? AND user_id=? AND submitted_at IS NULL",
                    finalScore, passed ? 1 : 0, now, attemptId, userId);
            if (n != 1) {
                var done = jdbc.queryForList(
                        "SELECT score, passed FROM exam_attempt WHERE id=? AND user_id=?",
                        attemptId, userId);
                if (done.isEmpty()) throw ApiException.notFound("考试记录不存在");
                return new SubmitResult(false, attemptId, intOrNull(done.get(0).get("score")),
                        paperSize, passScore, Boolean.TRUE.equals(boolOrNull(done.get(0).get("passed"))));
            }
            jdbc.batchUpdate("INSERT INTO exam_answer (attempt_id,question_id,user_answer,is_correct) "
                           + "VALUES (?,?,?,?)", batch);
            return new SubmitResult(true, attemptId, finalScore, paperSize, passScore, passed);
        });

        // ★ 报告入队<b>在事务提交之后</b>，而且只在真正交上那一次做。
        //
        // 两条都不能松：
        //   · 进事务的话，一次外部调用（实测 19.3 秒）会让 exam_attempt 那一行的锁
        //     被一条 HTTP 连接攥着 19 秒，而这张表上还压着别人的交卷；
        //   · 重复提交（firstTime=false）不该再排一次队 —— 弱网重发是常态，
        //     一场考试只有一份报告（uk_report_attempt 也会拦，但别把撞键当流程）。
        //
        // enqueue 自己吞掉一切异常：卷已经判完落库了，报告只是附加物，
        // 它失败绝不能让学员看到一个 500、以为自己没交上。
        if (reports != null && result != null && result.firstTime()) reports.enqueue(result.attemptId());
        return result;
    }

    /**
     * 我的考试记录。两个筛子都可以为 null（都不给 = 全部）。
     *
     * <p>{@code courseId} 这一路是给老客户端留的：它只知道课程不知道卷，
     * 按课程筛出来的是这门课下面<b>所有卷</b>的场次 —— 这正是它当初想要的那份列表，
     * 只是现在里面多了章节卷。
     *
     * <p><b>已知边界（写死在接口契约里，不是实现细节）：{@code courseId} 永远筛不到开放卷的场次。</b>
     * 开放卷的 {@code exam_attempt.course_id} 是 NULL，而 SQL 里 {@code course_id=?} 对 NULL 恒假 ——
     * 不是"漏了"，是这条路径按定义就只回答"这门课下面的场次"，而开放卷不属于任何课程。
     * 所以：想要"我的全部考试记录"就<b>两个筛子都不传</b>，想要某一份卷就传 paperId；
     * 拿 {@code ?courseId=} 当总表用的页面，开放卷那几场会静默缺席，
     * 而缺席不会有任何报错 —— 学员只是看不到自己考过的 AIGC 卷。
     */
    public List<AttemptRow> attempts(long userId, Long paperId, Long courseId) {
        LocalDateTime now = dbNow();
        StringBuilder sql = new StringBuilder(
                "SELECT id, paper_id, course_id, attempt_no, score, passed, started_at, deadline_at, "
              + "submitted_at FROM exam_attempt WHERE user_id=?");
        List<Object> args = new ArrayList<>();
        args.add(userId);
        if (paperId != null) { sql.append(" AND paper_id=?"); args.add(paperId); }
        if (courseId != null) { sql.append(" AND course_id=?"); args.add(courseId); }
        sql.append(" ORDER BY id DESC");
        return jdbc.query(sql.toString(), (rs, i) -> {
            LocalDateTime submitted = rs.getObject("submitted_at", LocalDateTime.class);
            LocalDateTime deadline = rs.getObject("deadline_at", LocalDateTime.class);
            Boolean passed = boolOrNull(rs.getObject("passed"));
            String state = submitted != null ? (Boolean.TRUE.equals(passed) ? "PASSED" : "FAILED")
                    : (now.isBefore(deadline) ? "IN_PROGRESS" : "EXPIRED");
            return new AttemptRow(rs.getLong("id"), longOrNull(rs.getObject("paper_id")),
                    longOrNull(rs.getObject("course_id")),
                    rs.getInt("attempt_no"), intOrNull(rs.getObject("score")), passed,
                    rs.getObject("started_at", LocalDateTime.class), deadline, submitted, state);
        }, args.toArray());
    }

    /**
     * 老接口的 courseId 落到哪张卷上：<b>这门课发结业证明的那一张</b>。
     *
     * <p>不能"取第一张"：一门课下面现在有五张卷，第一张按 sort_no 排恰好是综合卷
     * 只是今天的巧合 —— 明天运营把章节卷的 sort_no 调到 0，老客户端就开始考章节卷，
     * 而它自己和学员都不会察觉。所以只认 grants_certificate=1 这条<b>有含义</b>的判据，
     * 认不出来就明说，让调用方改传 paperId。
     */
    public long certificatePaperOf(long courseId) {
        var rows = jdbc.queryForList(
                // <> 0 而不是 = 1：TINYINT(1) 里写个 2 在库层是合法的，
                // 库那边的触发器也是按 <> 0 判的（见 schema.sql 里那条注释）。
                "SELECT id, title FROM exam_paper WHERE course_id=? AND grants_certificate<>0 "
              + "AND status='ON' ORDER BY sort_no, id", courseId);
        if (rows.isEmpty())
            throw ApiException.conflict("这门课没有可发结业证明的测评卷，"
                    + "请在测评页选择要考的那一份（接口参数用 paperId）");
        if (rows.size() > 1)
            throw ApiException.conflict("这门课有 " + rows.size() + " 份可发结业证明的测评卷，"
                    + "无法判断要考哪一份，请在测评页选择（接口参数用 paperId）");
        return num(rows.get(0).get("id"));
    }

    // ================================================================= 闸门

    /**
     * 开考闸门的<b>唯一</b>实现。{@link #status}、{@link #start} 与 {@link #papers} 都走这里。
     *
     * <p>两处各写一遍的话迟早分叉，表现是界面写着"可以开始考试"，点下去被拒 ——
     * 而这种不一致没有任何自动信号，只能靠用户报障。
     */
    private Gate gate(PaperRow paper, LocalDateTime now, Cache cache) {
        String denial = access(paper, now, cache).denial();
        // 单卷入口（status / start）一律 403：权益不在的人不该拿到这份卷的任何细节。
        if (denial != null) throw ApiException.forbidden(denial);
        return gate(paper, now, cache, null);
    }

    /**
     * 闸门本体。
     *
     * @param preDenial 只有 {@link #papers} 会传非空：那是"可见但不能考"的卷
     *                  （权益到期 / 被停用）。传进来的这句话<b>压过后面所有理由</b>，
     *                  并且把 canStart 钉死成 false —— 否则一个考到一半权益到期的人，
     *                  卡上会写着"继续考试"，点下去被 {@link #gate} 那道 403 拦住。
     */
    private Gate gate(PaperRow paper, LocalDateTime now, Cache cache, String preDenial) {
        long userId = cache.userId;
        Rules r = effectiveRules(paper.id());
        // 不挂课程的卷没有"学到哪"这件事，连查都不查（也查不出来）。
        Progress p = paper.courseId() == null ? null : cache.progress(paper.courseId());

        var attempts = jdbc.queryForList(
                "SELECT id, attempt_no, passed, deadline_at, submitted_at FROM exam_attempt "
              + "WHERE user_id=? AND paper_id=? ORDER BY attempt_no DESC", userId, paper.id());

        int used = attempts.size();
        // attempt_no 用 max+1 而不是 count+1，且上限判定写成 nextNo > max_attempts ——
        // 与 trg_exam_attempt_ins 里那个式子逐字一致。两边式子不同的话，
        // 要么应用放行而库拒绝（用户看到触发器文案），要么反过来漏一次。
        int nextNo = attempts.isEmpty() ? 1
                : ((Number) attempts.get(0).get("attempt_no")).intValue() + 1;

        Long inProgress = null, passedId = null;
        Integer cooldownLeft = null;

        for (var a : attempts)
            if (Boolean.TRUE.equals(boolOrNull(a.get("passed")))) { passedId = num(a.get("id")); break; }

        if (!attempts.isEmpty()) {
            var last = attempts.get(0);
            LocalDateTime submitted = (LocalDateTime) last.get("submitted_at");
            LocalDateTime deadline = (LocalDateTime) last.get("deadline_at");
            if (submitted == null && now.isBefore(deadline)) inProgress = num(last.get("id"));

            // 冷却从"上一场结束"起算。没交卷的那场按截止时间算结束：
            // 开了考不交卷同样烧掉一次机会，否则"开一场晾着"就是绕过冷却的免费通道。
            LocalDateTime endedAt = submitted != null ? submitted : deadline;
            LocalDateTime coolUntil = endedAt.plusHours(r.retryCooldownHours());
            if (inProgress == null && now.isBefore(coolUntil))
                cooldownLeft = (int) Duration.between(now, coolUntil).toSeconds() + 1;
        }

        // 进度门槛只在"这份卷挂了课程 且 规则要求进度 > 0"时才算。
        // 章节练兵卷与开放卷的 require_progress_pct 是 0：对它们算进度不只是多余 ——
        // 开放卷根本没有课程，"该课程还没有上架正片讲次"这句话会把一份对全员开放的卷
        // 拦得谁也考不了，而这条拦截在测试库（有讲次）里永远不出现。
        boolean progressGate = r.requireProgressPct() > 0 && p != null;

        // 顺序是"越终局的越先说"。次数已经用完的人先看到"冷却中，24 小时后再来"，
        // 会白等一天回来发现根本考不了 —— 提示的顺序本身就是产品体验。
        // 权益那句话（preDenial）比这里所有理由都终局：次数、冷却、进度都不是他要做的事。
        String blocked = preDenial;
        if (blocked == null && inProgress == null) {
            // 规则本身配坏了，也要在这一处说出来。start() 里对这两条各有一道兜底闸，
            // 但闸门在 gate() 之外就意味着：卡片写着"可以考"、点下去吃一个 409 ——
            // 正是这个类反复禁止的那种不一致。判据只在一处，两边才不会分叉。
            // ★ 措辞必须与 start() 里那两句**逐字一致**：同一件事从卡片上读到和从 409 里读到
            //   若是两种说法，学员会以为是两个问题，客服也对不上账。判据钉的是失败原因，
            //   换个说法就等于换了一条判据。
            if (r.durationMin() <= 0) {
                blocked = "测评规则里的考试时长为 " + r.durationMin() + " 分钟，无法开考，请联系平台修正规则";
            } else if (r.questionCount() <= 0) {
                blocked = "测评规则里的卷面题数是 " + r.questionCount() + " 道，无法开考，请联系平台修正规则";
            } else if (passedId != null) {
                blocked = "你已通过本卷测评，无需再考";
            } else if (nextNo > r.maxAttempts()) {
                blocked = "本卷最多可考 " + r.maxAttempts() + " 次，你的次数已用完";
            } else if (progressGate && p.totalSec() <= 0) {
                blocked = "该课程还没有上架正片讲次，暂不能参加测评";
            } else if (progressGate && p.pct() < r.requireProgressPct()) {
                blocked = "当前学习进度 " + p.pct() + "%，需达到 " + r.requireProgressPct()
                        + "% 才能参加测评";
            } else if (cooldownLeft != null) {
                blocked = "距上一次考试不足 " + r.retryCooldownHours() + " 小时，请在 "
                        + humanize(cooldownLeft) + "后再试";
            }
        }
        // canStart 只算这一处：status 与列表卡各算一遍的话，两个式子迟早分叉，
        // 表现还是那句"界面说能考、点下去被拒"。进行中的那一场当然能进（start 会把它还回去），
        // 但权益不在时连它也不算 —— 那一场的入口是 attemptId，不是这张卡。
        boolean canStart = preDenial == null && (inProgress != null || blocked == null);
        return new Gate(r, p == null ? null : p.pct(), used, nextNo, inProgress, passedId,
                cooldownLeft, blocked, canStart);
    }

    private record Gate(Rules rules, Integer progressPct, int attemptsUsed, int nextAttemptNo,
                        Long inProgressId, Long passedAttemptId, Integer cooldownSecondsLeft,
                        String blocked, boolean canStart) {}

    /**
     * 权益判据的<b>唯一</b>实现：{@link #papers} 用它决定这张卷是不是可见、能不能考，
     * {@link #gate} 用它决定这个人能不能开考。
     *
     * <p>不校验的话，"没买课也能考、考过了还能申请结业证明"这条路是通的 ——
     * 而证书是对外物料。
     *
     * <h4>两档，不是一档</h4>
     * <ul>
     *   <li><b>不可见</b>：压根没有这门课的权益（他没买过这门课）。列出来只会让他莫名其妙，
     *       而且等于告诉他平台上还有哪些课有卷。
     *   <li><b>可见但不能考</b>：买过，但权益到期 / 被停用 / 还没生效。<b>这张卡必须留着</b> ——
     *       整张卷消失的话，到期学员看到的是"我的卷不见了"，
     *       而"续期之后就能考"这句话再没有任何地方会说。
     * </ul>
     *
     * <h4>话里不许出现库里的枚举</h4>
     * 早先那句是"课程权益当前不可用（SUSPENDED），暂不能参加测评" —— 括号里那个词
     * 是给运维看的，学员读不懂，也不知道自己该做什么。每一档都换成他能照着做的一句话。
     *
     * <h4>口径与 {@code Entitlements.requireCourse} 逐条对齐</h4>
     * 那边是 {@code status='ACTIVE' AND valid_from <= NOW(3) AND valid_until > NOW(3)}，
     * 这里必须一模一样（尤其是 <b>valid_from</b>：漏了它，一份还没生效的权益能考试、
     * 考过还能换结业证明，而同一个人一讲课都点不开 —— 两道闸门对同一个人给出相反的答案，
     * 且两边都不报错）。判定时刻一律用库里那个 now，不用应用进程的钟。
     *
     * <p><b>access='OPEN' 是唯一的例外口子。</b>它由 {@code exam_paper.access} 这个字段驱动，
     * 不是由 paperId、code 或者调用方的哪个布尔参数驱动：换一种写法（例如在控制器里
     * 判断"这个 id 是开放卷就别调 gate"），"哪些卷不用买课"就从库里搬进了代码里的一处 if，
     * 后台上下架一张开放卷不再有任何效果，而且没有任何地方能查到当前放行了哪几张。
     */
    private Access access(PaperRow paper, LocalDateTime now, Cache cache) {
        if ("OPEN".equals(paper.access())) return Access.OK;
        if (paper.courseId() == null)
            // trg_exam_paper_precheck 不允许 ENROLLED 卷没有课程，走到这里说明有人直接改了库。
            // 放行等于把它当开放卷用，所以一律拒 —— 宁可一张卷考不了，不可全员白考。
            // 这是配置坏了而不是学员的事，所以不可见：他做什么都改变不了它。
            return Access.hidden("该试卷未挂课程，暂不能参加测评");
        var rows = cache.enrollment(paper.courseId());
        if (rows.isEmpty()) return Access.hidden("你还没有这门课的权益");
        String status = (String) rows.get(0).get("status");
        LocalDateTime from = (LocalDateTime) rows.get(0).get("valid_from");
        LocalDateTime until = (LocalDateTime) rows.get(0).get("valid_until");

        // EXPIRED 与"过了 valid_until"是同一件事的两种记法（到期扫描跑没跑过而已），
        // 所以给同一句话：他要做的事都是续期。
        if ("EXPIRED".equals(status) || (until != null && !now.isBefore(until)))
            return Access.blocked("观看有效期已结束，续期后可以参加测评");
        if ("SUSPENDED".equals(status))
            return Access.blocked("课程权益已被暂停，暂不能参加测评，请联系客服");
        if (!"ACTIVE".equals(status))
            // ENUM 以后加值时落到这里。不把那个值拼进句子：学员读不懂，
            // 而"读不懂的括号"会让他以为是自己的问题。
            return Access.blocked("课程权益当前不可用，暂不能参加测评，请联系客服");
        if (from != null && now.isBefore(from))
            return Access.blocked("课程权益将于 " + from.toLocalDate() + " 生效，届时可以参加测评");
        return Access.OK;
    }

    /**
     * 权益判定的结果。{@code denial} 为空 = 放行；
     * {@code visible} 说的是"这张卷还要不要出现在他的列表里"，与能不能考是两件事。
     */
    private record Access(String denial, boolean visible) {
        static final Access OK = new Access(null, true);
        /** 可见但不能考：买过这门课，只是现在考不了。 */
        static Access blocked(String why) { return new Access(why, true); }
        /** 不可见：这张卷对他而言不存在。 */
        static Access hidden(String why) { return new Access(why, false); }
    }

    /**
     * 上架试卷的<b>唯一</b>读取口。{@code status='ON'} 这条只写在这里：
     * 列表按它过滤、闸门按它拒绝，所以下架一份卷之后，学员端看不到它，
     * 拿着旧链接直接打 paperId 也开不了考。两处各写一遍的话，
     * "下架"就只剩"从列表上消失"这一半效果。
     */
    private List<PaperRow> loadPapers(Long paperId) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "SELECT p.id, p.code, p.title, p.subtitle, p.course_id, c.title AS course_title, "
              + "p.access, p.grants_certificate, p.sort_no "
              + "FROM exam_paper p LEFT JOIN course c ON c.id = p.course_id "
              + "WHERE p.status='ON'");
        if (paperId != null) { sql.append(" AND p.id=?"); args.add(paperId); }
        // sort_no 是运营排的展示顺序（综合卷在前、章节卷随后）；同号时按 id 稳定住，
        // 否则同一个人两次刷新看到的卷序不一样。
        sql.append(" ORDER BY p.sort_no, p.id");
        return jdbc.query(sql.toString(), (rs, i) -> new PaperRow(
                rs.getLong("id"), rs.getString("code"), rs.getString("title"),
                rs.getString("subtitle"), longOrNull(rs.getObject("course_id")),
                rs.getString("course_title"), rs.getString("access"),
                Boolean.TRUE.equals(boolOrNull(rs.getObject("grants_certificate"))),
                rs.getInt("sort_no")), args.toArray());
    }

    /** 单张卷。查不到 = 不存在或已下架，对外同一种结果（否则这是个探卷号的探针）。 */
    private PaperRow requirePaper(long paperId) {
        var rows = loadPapers(paperId);
        if (rows.isEmpty()) throw ApiException.notFound("试卷不存在或已下架");
        return rows.get(0);
    }

    /**
     * 一次调用之内的按课程缓存，<b>只服务构造它的那个 userId</b>（userId 存在这里，
     * 就不可能出现"拿别人的权益判自己的卷"这种传参错位）。
     *
     * <p>为什么要有它：一门课下面挂着五份卷，不缓存的话同一条 enrollment、
     * 同一份进度要各查五遍，而这一屏是学员打开 App 的第一屏。
     */
    private final class Cache {
        private final long userId;
        private final Map<Long, List<Map<String, Object>>> enrollments = new HashMap<>();
        private final Map<Long, Progress> progresses = new HashMap<>();

        Cache(long userId) { this.userId = userId; }

        /**
         * 这门课的权益行（uk_user_course 保证最多一行）。
         *
         * <p>三个字段一起取：判据要与 {@code Entitlements.requireCourse} 逐条对齐，
         * 少取一个 valid_from 就等于少判一条，而少判的那条不会报错 ——
         * 它只会让"还没生效的权益"在测评这边通行、在听课那边被拒。
         */
        List<Map<String, Object>> enrollment(long courseId) {
            return enrollments.computeIfAbsent(courseId, cid -> jdbc.queryForList(
                    "SELECT status, valid_from, valid_until FROM enrollment "
                  + "WHERE user_id=? AND course_id=?", userId, cid));
        }

        Progress progress(long courseId) {
            return progresses.computeIfAbsent(courseId, cid -> ExamService.this.progress(userId, cid));
        }
    }

    /**
     * 取<b>现行</b>规则：effective_from 已生效的里面最新的那一条。
     *
     * <p>不能"随便取一行"。exam_config 是靠插新行改规则的（旧行留着，
     * 已开考的场次按 config_id 引用旧规则），取错行的后果是及格线与触发器
     * 校验用的那一行对不上，交卷时被拒。
     *
     * <p>按 <b>paper_id</b> 取而不是 course_id：一门课下面有五份卷，按课程取会让
     * 章节卷读到综合卷的 100 题 / 80 分 —— 于是 50 题的卷子谁也考不过，且不报错。
     * 老的按课程配置的行（paper_id 为空）在这里取不到，这是要的：
     * 一份卷没配自己的规则就该明说，不能悄悄借用别人的。
     */
    private Rules effectiveRules(long paperId) {
        var rows = jdbc.queryForList(
                "SELECT id AS config_id, question_count, pass_score, duration_min, shuffle_options, "
              + "max_attempts, retry_cooldown_hours, require_progress_pct FROM exam_config "
              + "WHERE paper_id=? AND effective_from <= NOW(3) "
              + "ORDER BY effective_from DESC, id DESC LIMIT 1", paperId);
        if (rows.isEmpty())
            throw ApiException.conflict("该试卷尚未配置测评规则，暂不能参加测评");
        return rulesOf(rows.get(0));
    }

    /**
     * 入参必须带 {@code config_id} 这个列标签，见 {@link #paper} 里那条注释。
     *
     * <p>shuffle_options 走 {@link #boolOrNull}：TINYINT(1) 在 Connector/J 默认配置
     * （tinyInt1isBit=true）下取回来是 <b>Boolean</b> 不是 Number，
     * 按 Number 强转当场 ClassCastException。
     */
    private static Rules rulesOf(Map<String, Object> c) {
        return new Rules(num(c.get("config_id")),
                ((Number) c.get("question_count")).intValue(),
                ((Number) c.get("pass_score")).intValue(),
                ((Number) c.get("duration_min")).intValue(),
                Boolean.TRUE.equals(boolOrNull(c.get("shuffle_options"))),
                ((Number) c.get("max_attempts")).intValue(),
                ((Number) c.get("retry_cooldown_hours")).intValue(),
                ((Number) c.get("require_progress_pct")).intValue());
    }

    private record Progress(int pct, long totalSec, long watchedSec) {}

    /**
     * 学习进度。
     *
     * <p>口径是<b>有效观看秒数 / 已上架正片总时长</b>，不是"完成的讲数 / 总讲数"。
     * 按讲数算的话，把最短的几讲刷完就能凑够 80% —— 而长讲恰恰是内容主体，
     * 这个门槛就等于没设。
     *
     * <p>分母只算 status='ON' 且非试听的讲：DRAFT/OFF 的讲学员根本点不开，
     * 算进分母等于永远达不到门槛；试听是公开物料，不属于要学的内容。
     *
     * <p>LEAST 是必要的而不是保险：trg_progress_sanity 只在<b>写入时</b>拦
     * watched_sec > duration_sec，历史行、或者事后把 duration_sec 改短的讲次，
     * 都能让分子超过分母，算出 >100% 的进度。
     */
    private Progress progress(long userId, long courseId) {
        var row = jdbc.queryForMap(
                "SELECT COALESCE(SUM(l.duration_sec),0) AS total, "
              + "COALESCE(SUM(LEAST(COALESCE(p.watched_sec,0), l.duration_sec)),0) AS watched "
              + "FROM lesson l LEFT JOIN learn_progress p "
              + "  ON p.lesson_id = l.id AND p.user_id = ? "
              + "WHERE l.course_id=? AND l.status='ON' AND l.is_preview=0", userId, courseId);
        long total = ((Number) row.get("total")).longValue();
        long watched = ((Number) row.get("watched")).longValue();
        int pct = total <= 0 ? 0 : (int) Math.min(100, watched * 100 / total);
        return new Progress(pct, total, watched);
    }

    // ================================================================= 出题

    private List<PaperQuestion> loadPaperQuestions(long attemptId, List<Long> ids, boolean shuffle) {
        if (ids.isEmpty()) return List.of();
        // 只查 id/type/stem/options —— answer 与 explanation 连查都不查。
        // 也不按 review_status 过滤：快照已经定了，事后把题下线不该改变一张已开的卷。
        var byId = new HashMap<Long, PaperQuestion>();
        for (var q : jdbc.query("SELECT id, type, stem, options FROM question WHERE id IN ("
                              + placeholders(ids.size()) + ")",
                (rs, i) -> new PaperQuestion(rs.getLong("id"), rs.getString("type"),
                        rs.getString("stem"),
                        options(attemptId, rs.getLong("id"), rs.getString("options"), shuffle)),
                ids.toArray()))
            byId.put(q.id(), q);

        var out = new ArrayList<PaperQuestion>(ids.size());
        for (Long qid : ids) {
            var q = byId.get(qid);
            if (q == null) {
                log.error("考卷 attempt={} 引用的题目 question={} 已不在题库中，本卷会少一道题", attemptId, qid);
                continue;
            }
            out.add(q);
        }
        return out;
    }

    /**
     * 选项。乱序只打乱<b>展示顺序</b>，选项 key 与文案的对应关系不动 ——
     * 要防的是"第二个是对的"这种靠位置口口相传的答案，不是让 key 变得不可预测。
     *
     * <p>种子取自 (attemptId, questionId)，所以同一场考试刷新页面顺序不变。
     * 用随机种子的话，学员选到一半刷新，选项全换了位置，而他记住的是"选第二个" ——
     * 这会造成一批看起来像"系统改了我的答案"的申诉。
     */
    private List<Option> options(long attemptId, long questionId, String rawJson, boolean shuffle) {
        if (rawJson == null || rawJson.isBlank()) return List.of();
        List<Map<String, Object>> parsed;
        try {
            parsed = json.readValue(rawJson, new TypeReference<>() {});
        } catch (Exception e) {
            // 题库里的 options 不是合法结构：让这道题变成无选项而不是让整张卷 500。
            // 但必须留痕 —— 一道没有选项的选择题，学员只会以为是自己网络问题。
            log.error("题目 question={} 的 options 无法解析，本题将不带选项下发", questionId, e);
            return List.of();
        }
        var opts = new ArrayList<Option>(parsed.size());
        for (var o : parsed) {
            Object k = o.get("key"), t = o.get("text");
            if (k != null) opts.add(new Option(String.valueOf(k), t == null ? "" : String.valueOf(t)));
        }
        if (shuffle) java.util.Collections.shuffle(opts, new Random(attemptId * 1_000_003L + questionId));
        return opts;
    }

    // ================================================================= 判分

    /**
     * 作答规范化。两边（标准答案与学员作答）走<b>同一个函数</b>，所以只要保证
     * 变换是确定的，比较就仍然成立。
     *
     * <p>排序是为了多选：学员点 B 再点 A 提交上来是 "BA"，题库里存的是 "AB"，
     * 按字符串直接比会把对的判成错的 —— 而这种错判不会报任何错，
     * 只会表现为"多选题好像全是错的"。
     */
    static String normalizeAnswer(String s) {
        if (s == null) return null;
        String t = s.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        if (t.isEmpty()) return null;
        char[] c = t.toCharArray();
        Arrays.sort(c);
        return new String(c);
    }

    // ================================================================= 杂项

    /**
     * 时间基准。所有判定都走库里这一个钟，见类注释第三条。
     * 多一次往返换掉一整类"只在多实例下偶发、测试环境永远重现不出来"的故障。
     */
    private LocalDateTime dbNow() {
        return jdbc.queryForObject("SELECT NOW(3)", LocalDateTime.class);
    }

    private List<Long> parseIds(String snapshot) {
        try {
            return json.readValue(snapshot, new TypeReference<List<Long>>() {});
        } catch (Exception e) {
            // 快照读不出来 = 这场考试无法判分。宁可 500 也不能"当成空卷"，
            // 空卷会被判 0 分并烧掉一次机会。
            throw new IllegalStateException("考卷快照无法解析", e);
        }
    }

    private String writeIds(List<Long> ids) {
        try {
            return json.writeValueAsString(ids);
        } catch (Exception e) {
            throw new IllegalStateException("考卷快照无法序列化", e);
        }
    }

    private static String placeholders(int n) {
        return String.join(",", java.util.Collections.nCopies(n, "?"));
    }

    static String humanize(int seconds) {
        if (seconds >= 3600) return (seconds / 3600) + " 小时 " + (seconds % 3600 / 60) + " 分钟";
        if (seconds >= 60) return (seconds / 60) + " 分钟";
        return seconds + " 秒";
    }

    /** BIGINT UNSIGNED 在 Connector/J 里是 BigInteger，不是 Long，直接强转会 ClassCastException。 */
    private static long num(Object v) { return ((Number) v).longValue(); }

    /** 可空的 BIGINT UNSIGNED（course_id / paper_id 现在都能是 NULL）。 */
    private static Long longOrNull(Object v) { return v == null ? null : ((Number) v).longValue(); }

    private static Integer intOrNull(Object v) { return v == null ? null : ((Number) v).intValue(); }

    /** TINYINT(1) 取回来可能是 Boolean 也可能是 Integer/Byte，取决于驱动配置。 */
    private static Boolean boolOrNull(Object v) {
        if (v == null) return null;
        if (v instanceof Boolean b) return b;
        return ((Number) v).intValue() != 0;
    }
}
