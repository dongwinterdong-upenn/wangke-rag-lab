package tech.dragonai.wangke.home;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tech.dragonai.wangke.exam.CertificateService;
import tech.dragonai.wangke.exam.ExamService;
import tech.dragonai.wangke.learn.CourseService;
import tech.dragonai.wangke.support.QaService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 学习中心导览页的数据。
 *
 * <h3>为什么单开一个包</h3>
 * 这一层要同时读课程、测评、答疑、结业证明四片。放进其中任何一片都会让那一片
 * 反过来依赖另外三片；放在这里，依赖方向是单向的 —— {@code home} 依赖它们，
 * 没有任何东西依赖 {@code home}。删掉这个包，其余功能一行都不用改。
 *
 * <h3>它不做判断，只搬运判断</h3>
 * "能不能考"这件事只有 {@link ExamService#papers} 说了算。这里不重算一遍
 * 进度够不够、次数还剩几次 —— 一旦在导览页上重算，两处式子迟早分叉，
 * 而分叉的表现是<b>首页说"可以考了"，点进去被拒</b>。
 *
 * <h3>测评是按卷列的，不是按课（2026-09-03）</h3>
 * 一门课下面有综合卷与四份章节卷，另有一份不挂课程的开放卷，所以这一区块的
 * 一条 = 一份卷。<b>开放卷即使这个学员一门课都没买也要出现</b> ——
 * 它对所有注册学员开放，而"我的测评"是他唯一能找到它的地方。
 * 因此这一区块<b>不再从"我的课程"推出来</b>：从课程推的话，没买课的人这里永远是空的。
 *
 * <h3>取不到 ≠ 是零</h3>
 * 四片里任何一片挂了都不该让整张导览页 500 —— 但也<b>不能把它当成零</b>。
 * 一个"0 条等回复"的角标读起来是"没问题要处理"，学员就不会再点进去了。
 * 所以出错的那一片返回 {@code null}，由前端显示成"暂时取不到"，
 * 并且在服务端留一条 WARN —— 不留日志的降级等于把故障藏起来。
 *
 * <p>注意全局开着 {@code default-property-inclusion: non_null}：null 的那一片
 * 在 JSON 里是<b>整个键消失</b>，不是一个 {@code null}。前端类型因此写成可选字段。
 * 「取不到」与「是零」仍然分得开（一个没有这个键，一个键在值是 0），
 * 分不开的是「取不到」与「这个后端还没有这一片」—— 两者都该显示成"暂时取不到"，
 * 所以不为此再造一个标记；真正防字段名写错的是 HomeIT 里那条键名判据。
 */
@Service
public class HomeService {

    private static final Logger log = LoggerFactory.getLogger(HomeService.class);

    /** 一张导览页最多列几份卷。超出的部分不列，且如实告诉前端"还有几份没列"。 */
    private static final int ASSESS_MAX_PAPERS = 20;

    private final CourseService courses;
    private final ExamService exam;
    private final QaService qa;
    private final CertificateService certs;

    public HomeService(CourseService courses, ExamService exam, QaService qa, CertificateService certs) {
        this.courses = courses;
        this.exam = exam;
        this.qa = qa;
        this.certs = certs;
    }

    /** 导览页要的全部东西，一次请求。 */
    public Map<String, Object> overview(long userId) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> my = courses.myCourses(userId);
        out.put("courses", coursesBlock(my));
        out.put("assess", section(userId, "测评", () -> assessBlock(userId)));
        out.put("qa", section(userId, "答疑", () -> qaBlock(userId)));
        out.put("cert", section(userId, "结业证明", () -> certBlock(userId)));
        return out;
    }

    // ------------------------------------------------------------------ 课程

    /**
     * 课程卡要的汇总 + 明细。
     *
     * <p>合计进度按<b>秒</b>加权，不是把各门课的百分比取平均：两门课一门 5 分钟看完、
     * 一门 10 小时没看，取平均是 50%，按秒算是 1%。学员看的是后者。
     * 已过期的课不进分母 —— 它已经不能看了，把它算进去只会让"已学"永远涨不上去。
     */
    private Map<String, Object> coursesBlock(List<Map<String, Object>> my) {
        long watched = 0, total = 0;
        int active = 0, expired = 0, doneLessons = 0, totalLessons = 0;
        for (var c : my) {
            if (Boolean.TRUE.equals(c.get("expired"))) { expired++; continue; }
            active++;
            watched += i(c.get("watchedSeconds"));
            total += i(c.get("totalSeconds"));
            doneLessons += i(c.get("doneLessons"));
            totalLessons += i(c.get("totalLessons"));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", my.size());
        m.put("active", active);
        m.put("expired", expired);
        m.put("doneLessons", doneLessons);
        m.put("totalLessons", totalLessons);
        m.put("watchedSeconds", watched);
        m.put("totalSeconds", total);
        // 向下取整，与 ProgressService.progressPct 一个口径：看了 99% 显示成 100%，
        // 学员会拿着"我明明学完了"来问为什么不能考试。
        m.put("progressPct", total <= 0 ? 0 : (int) Math.min(100, watched * 100 / total));
        m.put("items", my);
        return m;
    }

    // ------------------------------------------------------------------ 测评

    /**
     * 每份卷一条：现在能不能考、为什么不能。
     *
     * <p>整条都来自 {@link ExamService#papers}，包括那句 blockedReason —— 不在这里另编。
     * 那一层已经做了两件这里不再重复的事：按权益筛掉看不见的卷（判据与开考闸门同一段代码），
     * 以及一份卷取不到状态时只坏那一张卡（不兜的话整个测评区块会降级成"暂时取不到"，
     * 界面上的表现是<b>另外五份本来能考的卷一起消失了</b>）。
     *
     * <p>{@code BLOCKED} 现在有两种来头：门槛没到（进度 / 次数 / 冷却），
     * 以及<b>权益到期或被停用</b>（那一层给的是"可见但不能考"的卡）。
     * 这里不区分它们，也不该区分：学员要做的事已经写在 reason 里，
     * 在导览页上再分出一个状态名，只会逼前端把同一句话写两遍。
     */
    private Map<String, Object> assessBlock(long userId) {
        List<Map<String, Object>> items = new ArrayList<>();
        int ready = 0, passed = 0, inProgress = 0, skipped = 0;

        for (var p : exam.papers(userId)) {
            if (items.size() >= ASSESS_MAX_PAPERS) { skipped++; continue; }
            Map<String, Object> it = new LinkedHashMap<>();
            it.put("paperId", p.paperId());
            it.put("code", p.code());
            it.put("title", p.title());
            it.put("subtitle", p.subtitle());
            // 开放卷的 courseId 是 null（全局 non_null 下这个键会整个消失）——
            // 前端据此把它分到"公开测评"那一组，不必再猜。
            it.put("courseId", p.courseId());
            it.put("courseTitle", p.courseTitle());
            it.put("access", p.access());
            it.put("grantsCertificate", p.grantsCertificate());
            it.put("progressPct", p.progressPct());
            // rules 取不到时这几个数一律留空：空的语义是"不知道"，写 0 会变成一句假话
            // （"还剩 0 次可考"与"没问到还剩几次"在界面上是两回事）。
            it.put("requireProgressPct", p.rules() == null ? null : p.rules().requireProgressPct());
            it.put("questionCount", p.rules() == null ? null : p.rules().questionCount());
            it.put("passScore", p.rules() == null ? null : p.rules().passScore());
            it.put("durationMin", p.rules() == null ? null : p.rules().durationMin());
            it.put("attemptsLeft", p.attemptsLeft());
            it.put("inProgressAttemptId", p.inProgressAttemptId());
            it.put("passedAttemptId", p.passedAttemptId());
            // 顺序是"越终局的越先说"，与 ExamService.gate 里那串 if 一致。
            //
            // ★ 已知的一处不精确，故意没在这里"修"：一份卷若**同时**权益到期且没配规则，
            //   papers() 给出的是 rules=null + reason="观看有效期已结束，续期后可以参加测评"
            //   （权益话优先，那一层是对的），到这里会落成 state=UNAVAILABLE 配一句讲有效期的话。
            //   试过把「有理由就算 BLOCKED」提到前面 —— 那样"没配规则"也变成 BLOCKED，
            //   把 HomeIT 三条真判据（脏数据不该让别的卷消失 / 没配规则如实说 / 导览与接口一致）
            //   一起打红：它们钉的正是"没配规则 = 暂无测评"。
            //   真要分清，得让 PaperCard 自己带上"是权益拦的还是没规则"，那是另一次改动。
            //   当前无人可达：生产六张卷都配了规则，权益到期的卷仍然有规则、走的是 BLOCKED。
            if (p.rules() == null)                      { it.put("state", "UNAVAILABLE"); }
            else if (p.passedAttemptId() != null)       { it.put("state", "PASSED");      passed++; }
            else if (p.inProgressAttemptId() != null)   { it.put("state", "IN_PROGRESS"); inProgress++; }
            else if (p.canStart())                      { it.put("state", "READY");       ready++; }
            else                                        { it.put("state", "BLOCKED"); }
            it.put("reason", p.blockedReason());
            items.add(it);
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("readyCount", ready);
        m.put("inProgressCount", inProgress);
        m.put("passedCount", passed);
        // 没列的那几份要说出来。悄悄截断的话，界面上"0 份可以考"是错的，且看不出来。
        m.put("skippedCount", skipped);
        m.put("items", items);
        return m;
    }

    // ------------------------------------------------------------------ 答疑

    /**
     * 工单计数。
     *
     * <p>字段叫 {@code humanAnswered} 而不是 {@code unread}：库里没有已读标记，
     * 叫 unread 就是在说一件我们并不知道的事 —— 学员看过三遍的回复仍然会被角标说成"未读"。
     */
    private Map<String, Object> qaBlock(long userId) {
        var byStatus = qa.myTicketCounts(userId);
        int total = 0;
        for (int n : byStatus.values()) total += n;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", total);
        m.put("waiting", byStatus.getOrDefault("ESCALATED", 0));
        m.put("humanAnswered", byStatus.getOrDefault("HUMAN_ANSWERED", 0));
        m.put("aiAnswered", byStatus.getOrDefault("AI_ANSWERED", 0));
        return m;
    }

    // ------------------------------------------------------------------ 结业证明

    private Map<String, Object> certBlock(long userId) {
        var all = certs.my(userId);
        int valid = 0;
        for (var c : all) if ("VALID".equals(c.status())) valid++;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", all.size());
        m.put("valid", valid);
        return m;
    }

    // ------------------------------------------------------------------ 降级

    private interface Block { Map<String, Object> get(); }

    /** 单片降级：出错返回 null（前端显示"暂时取不到"），并留一条带用户与片名的 WARN。 */
    private Map<String, Object> section(long userId, String name, Block b) {
        try {
            return b.get();
        } catch (RuntimeException e) {
            log.warn("导览页「{}」取数失败 userId={}", name, userId, e);
            return null;
        }
    }

    private static int i(Object o) { return o == null ? 0 : ((Number) o).intValue(); }
}
