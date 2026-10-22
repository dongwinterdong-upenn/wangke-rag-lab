package tech.dragonai.wangke.admin;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理端测评的取数层：现行规则、题库分布、以及「这张卷现在能不能开考」这一条判据。
 *
 * <h3>为什么单独一个类</h3>
 * 这三样东西在管理端被三个地方要：列卷（摆给运营看）、上架闸（据此拒绝）、
 * 审题（改完之后告诉运营刚才那一下把卷弄坏了没有）。三处各写一遍的话，
 * 迟早出现「列表说够了、上架被拒」或者反过来 —— 而两边都不报错，
 * 运营只会觉得后台抽风。所以判据只写一处，三个入口共用。
 *
 * <h3>★ 现行规则的取法必须与 ExamService 逐字一致</h3>
 * {@code exam_config} 是<b>插新行</b>改规则的（旧行留着，已开考的场次按
 * {@code attempt.config_id} 引用旧规则）。取「现在生效的是哪一行」这件事，
 * 学员那一侧是 {@code ExamService.effectiveRules}，管理端是这里，
 * 两条 SQL 的 WHERE 与 ORDER BY <b>必须一模一样</b>：
 * 少一个 {@code effective_from <= NOW(3)}，后台就会把一条未来生效的规则显示成现行，
 * 运营照着它去核对及格线，而学员开考时用的是另一行 —— 页面上写 80 分、实际按 90 分判，
 * 没有任何一侧会报错。
 *
 * <h3>为什么逐卷查一次而不是一条 SQL 查全部</h3>
 * 「每张卷取最新一行」写成一条 SQL 需要 GROUP BY + 自连接或窗口函数，那就是<b>第二条式子</b>，
 * 与上面那条一旦漂移就是上一段说的那种事故。试卷是六张（量级是十几张，不是几万张），
 * 逐卷查一次的代价可以忽略，换来的是「取现行规则」这件事全仓库只有一种写法。
 */
@Service
public class AdminExamService {

    private final JdbcTemplate jdbc;

    public AdminExamService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /**
     * 一张卷的题库分布。
     *
     * @param approved <b>唯一能被抽到的那一堆</b>（抽题是 {@code WHERE paper_id=? AND
     *                 review_status='APPROVED'}）。卷面 100 题而这个数是 3 时，
     *                 学员点开考会被拒 —— 而在他点之前，平台这边没有任何信号
     * @param total    这张卷名下的全部题目。<b>不是前四项之和</b>：review_status 以后多出
     *                 一个枚举值时，前四项会漏掉它，而 total 仍然是对的 ——
     *                 两个数对不上本身就是「有一类题没被这一页统计到」的信号
     */
    public record Bank(int approved, int pending, int rejected, int draft, int total) {}

    /** 一张卷当前生效的测评规则。{@code configId} 就是新开考的场次会引用的那一行。 */
    public record Rules(long configId, Long paperId, Long courseId, int questionCount,
                        int passScore, int durationMin, boolean shuffleOptions, int maxAttempts,
                        int retryCooldownHours, int requireProgressPct,
                        LocalDateTime effectiveFrom) {}

    // ================================================================ 现行规则

    /**
     * 取<b>现行</b>规则；这张卷还没配过规则时返回 null（不是抛异常 ——
     * 后台列表要能把「还没配规则」这件事显示出来，而不是整页 500）。
     */
    public Rules effectiveRules(long paperId) {
        var rows = jdbc.queryForList(
                "SELECT id, paper_id, course_id, question_count, pass_score, duration_min,"
              + " shuffle_options, max_attempts, retry_cooldown_hours, require_progress_pct,"
              + " effective_from FROM exam_config"
              + " WHERE paper_id=? AND effective_from <= NOW(3)"
              + " ORDER BY effective_from DESC, id DESC LIMIT 1", paperId);
        return rows.isEmpty() ? null : rulesOf(rows.get(0));
    }

    /**
     * shuffle_options 走 {@link AdminQuery#flag}，course_id / paper_id 走 {@link AdminQuery#id}。
     *
     * <p>两条都是这个库的老坑：{@code TINYINT(1)} 在 Connector/J 默认配置下取回来是
     * <b>Boolean</b>，{@code BIGINT UNSIGNED} 取回来是 <b>BigInteger</b>，
     * 直接强转成 Number/Long 会在运行期炸，而且只在这几列上炸。
     */
    private static Rules rulesOf(Map<String, Object> c) {
        return new Rules(AdminQuery.longOf(c.get("id"), 0),
                AdminQuery.id(c.get("paper_id")),
                AdminQuery.id(c.get("course_id")),
                AdminQuery.intOf(c.get("question_count"), 0),
                AdminQuery.intOf(c.get("pass_score"), 0),
                AdminQuery.intOf(c.get("duration_min"), 0),
                Boolean.TRUE.equals(AdminQuery.flag(c.get("shuffle_options"))),
                AdminQuery.intOf(c.get("max_attempts"), 0),
                AdminQuery.intOf(c.get("retry_cooldown_hours"), 0),
                AdminQuery.intOf(c.get("require_progress_pct"), 0),
                (LocalDateTime) c.get("effective_from"));
    }

    /** 这张卷一共存过几版规则。改规则是插新行，所以这个数只会涨 —— 它掉下去说明有人改了旧行。 */
    public Map<Long, Integer> ruleVersions(Collection<Long> paperIds) {
        Map<Long, Integer> out = new LinkedHashMap<>();
        List<Object> args = new ArrayList<>();
        String in = placeholders(paperIds, args);
        for (Long id : paperIds) if (id != null) out.put(id, 0);
        if (args.isEmpty()) return out;
        for (var r : jdbc.queryForList(
                "SELECT paper_id, COUNT(*) AS n FROM exam_config"
              + " WHERE paper_id IN (" + in + ") GROUP BY paper_id", args.toArray()))
            out.put(AdminQuery.id(r.get("paper_id")), AdminQuery.intOf(r.get("n"), 0));
        return out;
    }

    // ================================================================ 题库分布

    /** 单张卷的题库分布。与 {@link #banks} 同一条 SQL —— 单卷页与列表页不许出现两个数。 */
    public Bank bank(long paperId) {
        return banks(List.of(paperId)).getOrDefault(paperId, new Bank(0, 0, 0, 0, 0));
    }

    /**
     * 一次查完若干张卷的题库分布。
     *
     * <p>列表页<b>不能每张卷查一次</b>：这里是按 review_status 分组的聚合，
     * 六张卷就是六次全表聚合，而它和「取现行规则」不一样 —— 那一条是 LIMIT 1 的索引查找。
     *
     * <p>{@code paper_id IS NULL} 的题（迁移之前入库的老题）不属于任何一张卷，
     * 永远抽不到，所以不进任何一张卷的统计。它们在题目列表里用 {@code paperId=none} 才看得到 ——
     * 不给那个入口的话，那批题在后台<b>整片消失</b>，看起来像是迁移把它们删了。
     */
    public Map<Long, Bank> banks(Collection<Long> paperIds) {
        Map<Long, Bank> out = new LinkedHashMap<>();
        List<Object> args = new ArrayList<>();
        String in = placeholders(paperIds, args);
        // 每张卷先垫一个全 0：一张还没有任何题的卷必须出现在结果里，
        // 缺席会让调用方 getOrDefault 到别的东西，或者干脆把这张卷漏出列表
        Map<Long, int[]> acc = new LinkedHashMap<>();
        for (Long id : paperIds) if (id != null) acc.put(id, new int[5]);
        if (args.isEmpty()) return out;

        for (var r : jdbc.queryForList(
                "SELECT paper_id, review_status, COUNT(*) AS n FROM question"
              + " WHERE paper_id IN (" + in + ") GROUP BY paper_id, review_status",
                args.toArray())) {
            int[] a = acc.computeIfAbsent(AdminQuery.id(r.get("paper_id")), k -> new int[5]);
            int n = AdminQuery.intOf(r.get("n"), 0);
            switch (String.valueOf(r.get("review_status"))) {
                case "APPROVED" -> a[0] = n;
                case "PENDING"  -> a[1] = n;
                case "REJECTED" -> a[2] = n;
                case "DRAFT"    -> a[3] = n;
                // ★ 兜底桶不是摆设。review_status 以后多一个枚举值时，这里不接的话
                // 那些题会从「总数」里静默蒸发，后台显示的总数比库里少，没有任何提示。
                default -> a[4] = n;
            }
        }
        for (var e : acc.entrySet()) {
            int[] a = e.getValue();
            out.put(e.getKey(), new Bank(a[0], a[1], a[2], a[3], a[0] + a[1] + a[2] + a[3] + a[4]));
        }
        return out;
    }

    // ================================================================ 能不能开考

    /**
     * ★ 「这张卷现在开得了考吗」——<b>整个管理端只有这一份判据</b>。
     *
     * <p>列卷时它是摆给运营看的那句话，上架时它是拒绝的理由，审题改完之后它是那句警告。
     * 返回 null 表示能开考。
     *
     * <p>四条里每一条坏掉都<b>不报错</b>，而且都要等学员点下「开始考试」才暴露：
     * <ol>
     *   <li><b>没有规则</b>：ExamService 取不到现行规则，学员看到「尚未配置测评规则」。
     *   <li><b>时长 &lt;= 0</b>：deadline 不晚于 started，{@code ck_attempt_time} 直接拒。
     *   <li><b>及格线高于卷面题量</b>：满分也不及格。这一条最阴 —— 考试全程正常，
     *       每个人都考完、都判分、都不过，而且每人烧掉一次补考次数。
     *       库层 {@code trg_exam_attempt_ins} 只校验「分数不超过卷面题量」，
     *       拦不住及格线本身设得比卷面还高。
     *   <li><b>已审核的题不够卷面题数</b>：ExamService 明确拒绝「有几道出几道」，
     *       所以学员点开考直接吃一句 409 —— 而在他点之前，后台没有任何地方会提这件事。
     * </ol>
     */
    public String readyBlocker(Rules r, Bank b) {
        if (r == null)
            return "这张卷还没有测评规则（exam_config 里没有它的行），学员点开考会被拒";
        if (r.durationMin() <= 0)
            return "规则里的考试时长是 " + r.durationMin() + " 分钟，开不了考";
        if (r.passScore() > r.questionCount())
            return "及格线 " + r.passScore() + " 分高于卷面 " + r.questionCount()
                 + " 题，满分也不及格：所有人都会考完、判分、不过，而且每人烧掉一次补考次数";
        if (b.approved() < r.questionCount())
            return "已审核通过的题只有 " + b.approved() + " 道，不足卷面要求的 "
                 + r.questionCount() + " 道（还差 " + (r.questionCount() - b.approved())
                 + " 道），学员点开考会被拒";
        return null;
    }

    // ================================================================ 工具

    /** 拼 {@code IN (?,?,?)} 并把参数收进 args；空集合返回空串（调用方据此短路）。 */
    private static String placeholders(Collection<Long> ids, List<Object> args) {
        StringBuilder sb = new StringBuilder();
        if (ids == null) return "";
        for (Long id : ids) {
            if (id == null) continue;
            if (!args.isEmpty()) sb.append(',');
            sb.append('?');
            args.add(id);
        }
        return sb.toString();
    }
}
