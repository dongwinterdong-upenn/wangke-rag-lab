package tech.dragonai.wangke.exam;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 事实清单（喂给模型的那份）与两道机械判据。
 *
 * <h3>为什么提示词与校验住在同一个文件</h3>
 * 规格第二节记了试跑里真犯过的两类编造，每一类都要<b>提示词与机械判据一起堵</b>：
 * 提示词是请求它别那么写，判据是它照写了也进不了库。两者拆开放，改了一边忘了另一边
 * 不会有任何信号 —— 提示词松了而判据还在，表现是"报告老是生成失败"；
 * 判据松了而提示词还在，表现是<b>什么都看不出来</b>，直到有学员按一个不存在的时间点去翻录像。
 *
 * <h3>清单里没有正确答案</h3>
 * 取数那一层连 {@code question.answer} 都不查（见 {@link ExamReportService}），
 * 所以这里想写也写不出来。报告是学员能反复看的页面，题库要复用 ——
 * 把答案写进报告等于每考一次就往外送一份题库。
 *
 * <h3>★ 归一必须两侧同步</h3>
 * 时间点判据是"正文里抓到的" 减去 "事实清单里抓到的"，两边<b>必须用同一套归一</b>
 * （见 {@link #normalize}）。只归一一侧的后果按方向分，两种都坏：
 * <ul>
 *   <li>只归一正文（这个文件里真犯过）：事实清单里写着全角的「12：30」时它进不了 allowed 集合，
 *       模型照抄反被判成编造，连判两轮之后置 FAILED，而理由把人指到了模型身上；
 *   <li>只归一清单：正文里的全角时间点抓不到，<b>抓不到就等于放行</b>，
 *       一个编造的时间点照样进库。
 * </ul>
 * 所以改了一边一定要改另一边 —— 两处都只调 {@link #normalize}，别在任何一侧直接用原文。
 */
final class ExamReportPrompt {

    private ExamReportPrompt() {}

    /**
     * 时间点的形状。规格写的是 {@code \d{1,2}:\d{2}}，这里放宽到三位分钟并加了左右边界：
     * <ul>
     *   <li><b>三位分钟</b>：最长的一讲三个多小时，{@code 190:12} 是合法出处。
     *       只认两位的话，它在正文里出现时判据抓到的是 {@code 90:12} ——
     *       与事实清单里的 {@code 190:12} 对不上，于是一份完全正确的报告被判成编造。
     *   <li><b>左右边界</b>（{@code (?<!\d)} / {@code (?!\d)}）：没有它，
     *       {@code 190:12} 会被切出一个 {@code 90:12} 的"幽灵时间点"，同样是假红。
     *   <li><b>★ 秒段（第三组）也要吃进来</b>，否则右边界只挡数字、不挡冒号，
     *       「拿一个真时间点当前缀往后编」这一种整个漏掉：事实里有 {@code 1:05}，
     *       模型写 {@code 1:05:30} 时判据只看见前面那个 {@code 1:05}、认定它在清单里、直接放行 ——
     *       而学员点过去的是 {@code 1:05:30}，一个不存在的位置。这正是规格第二节要堵的那条。
     *       吃进来之后 {@code 1:05:30} 是一个完整的匹配，第三组非空，一律判为编造（见下）。
     * </ul>
     */
    private static final Pattern TIME = Pattern.compile("(?<!\\d)(\\d{1,3}):(\\d{2})(?::(\\d{2}))?(?!\\d)");

    /** 提示词里给的错题条数上限。100 题的卷子错 60 道时，全塞进去只会把预算烧在复述上。 */
    private static final int WRONG_IN_PROMPT = 30;

    /**
     * 系统提示词。<b>四条硬规则都是试跑里真犯过的</b>（规格第二节），措辞不要随手改：
     * 每一条背后都有一条机械判据或一次真实事故。
     */
    static final String SYSTEM = """
            你是网课平台的测评报告助手。学员刚考完一场，下面会给你一份【事实清单】。
            你的任务是照着这份清单写一段点评，帮他知道自己错在哪、接下来该做什么。

            硬规则（违反任意一条，这份报告会被系统丢弃）：
            1. 只能使用事实清单里的信息。清单里没有的数字、讲次、时间点、卷名，一个都不许出现。
            2. 时间点只能原样引用清单里出现过的（例如 18:47）。不得自己拼区间（不得写"18:47 到 26:51"），
               不得推算、不得取整、不得写"大约 20 分钟处"。
            3. 不得写出任何题目的正确答案，也不得暗示正确选项是哪一个。
            4. 建议的动作只能从这四类里选，且必须使用清单里给出的真实名称：
               ① 回看某一讲的某个时间点（用清单【可以回看的位置】里的原句）
               ② 重考本卷
               ③ 去考另一份卷（只能从清单【可以再考的卷】里挑）
               ④ 去答疑页问懂妈（课程答疑助手）
               平台上没有的东西一律不许出现：没有模拟卷、没有错题本、没有习题册、没有讲义下载、
               没有一对一辅导、没有学习计划表。
            5. 不写鼓励式空话（"继续加油""你很棒""相信你一定可以"），不写泛泛的学习方法论。
               每一句都要落到他这一场的具体表现上。

            格式：简体中文，Markdown，只用这三个二级标题，顺序不变：
            ## 这次考得怎么样
            ## 主要问题
            ## 接下来做什么
            前两段各 2-4 句；第三段是有序列表，最多 5 条，每条一个可以立刻去做的动作。
            全文 500 到 900 字，不要写标题以外的任何加粗。
            """;

    /** 重生成时追加的一句。第一次编造了时间点，第二次要把它按在事实上。 */
    static final String RETRY_HINT = """

            ★ 上一次生成因为引用了事实清单里没有的时间点而被丢弃。
            这一次：要么原样使用【可以回看的位置】里的时间点，要么一个时间点都不写。
            """;

    /**
     * 事实清单。<b>模型能说的话的全集就是这一段文本</b> ——
     * 时间点校验也以它为准（见 {@link #timePoints}）。
     */
    static String factSheet(ExamReportService.Stats s) {
        var b = new StringBuilder();
        b.append("【本场考试】\n");
        b.append("试卷：").append(nz(s.paperTitle(), "本卷"))
                .append("（第 ").append(s.attemptNo()).append(" 次考试）\n");
        b.append("得分：").append(s.score()).append(" / ").append(s.questionCount())
                .append("，及格线 ").append(s.passScore())
                .append("，结果：").append(s.passed() ? "通过" : "未通过").append("\n");

        section(b, "按板块（答对 / 该板块题数）", s.byTopic());
        section(b, "按讲次（答对 / 该讲次题数）", s.byLesson());
        section(b, "按题型", s.byType());
        section(b, "按难度", s.byDifficulty());

        b.append("\n【错题清单】（不含正确答案，只有他的作答）\n");
        if (s.wrong().isEmpty()) {
            b.append("本场全对，没有错题。\n");
        } else {
            int n = Math.min(s.wrong().size(), WRONG_IN_PROMPT);
            for (int i = 0; i < n; i++) {
                var w = s.wrong().get(i);
                b.append(i + 1).append(". [").append(nz(w.topic(), "未标注板块"))
                        .append("｜考点：").append(nz(w.knowledgePoint(), "未标注考点"))
                        .append("｜").append(w.typeLabel())
                        .append("｜").append(w.difficultyLabel()).append("]\n");
                b.append("   题干：").append(w.stem()).append("\n");
                b.append("   我的作答：").append(w.myAnswer() == null ? "未作答" : w.myAnswer()).append("\n");
                if (w.sourceAt() != null)
                    b.append("   出处：").append(nz(w.lessonTitle(), "未标注讲次"))
                            .append(" ").append(w.sourceAt()).append("\n");
            }
            if (s.wrong().size() > n)
                b.append("（错题共 ").append(s.wrong().size())
                        .append(" 道，此处只列前 ").append(n).append(" 道）\n");
        }

        b.append("\n【可以回看的位置】（时间点只能从这里抄，不得自己拼区间）\n");
        var spots = reviewSpots(s);
        if (spots.isEmpty()) b.append("本场没有可定位的回看位置，报告里不要出现任何时间点。\n");
        else for (String spot : spots) b.append("- ").append(spot).append("\n");

        b.append("\n【可以再考的卷】\n");
        b.append("- ").append(nz(s.paperTitle(), "本卷")).append("（本卷，可以重考）\n");
        for (var p : s.otherPapers()) b.append("- ").append(p.title()).append("\n");

        b.append("\n【答疑】看不懂的地方可以去答疑页问懂妈。\n");
        return b.toString();
    }

    /** 一段"分子/分母"。空的段直接不出现 —— 空标题会被模型当成"这块没考"来发挥。 */
    private static void section(StringBuilder b, String title, List<ExamReportService.Bucket> rows) {
        if (rows == null || rows.isEmpty()) return;
        b.append("\n【").append(title).append("】\n");
        for (var r : rows)
            b.append("- ").append(r.label()).append("：").append(r.correct())
                    .append(" / ").append(r.total()).append("\n");
    }

    /** 错题出处去重后的回看位置。顺序按错题清单，出现次数多的讲自然排在前面。 */
    private static List<String> reviewSpots(ExamReportService.Stats s) {
        var out = new LinkedHashSet<String>();
        for (var w : s.wrong())
            if (w.sourceAt() != null)
                out.add(nz(w.lessonTitle(), "未标注讲次") + " " + w.sourceAt());
        return new ArrayList<>(out);
    }

    /**
     * 事实清单里出现过的全部时间点，<b>换算成秒</b>。
     *
     * <p>比的是秒不是字符串：{@code 08:07} 与 {@code 8:07} 是同一个位置，
     * 按字符串比会把一份正确的报告判成编造，而"判据太严"与"模型真编了"
     * 在结果上完全一样（都是 FAILED），没人分得清是哪一种。
     *
     * <p><b>先归一全角冒号</b>，与 {@link #unknownTimePoints} 同一套（见类注释）：
     * 清单里的时间点大多来自 {@link #mmss}（半角，安全），但讲次标题、考点、题干摘要
     * 是库里的自由文本，出现「12：30」这种全角写法是常事。
     *
     * <p><b>带秒段的（{@code h:mm:ss}）不进这个集合</b>：清单里能给学员点的位置只有 {@code m:ss}，
     * 自由文本里冒出来的 {@code 1:05:30} 不是一个可回看的位置，放进来等于替模型开了一道门。
     * 正文那一侧对它是一律判编造的，两边这样才一致。
     */
    static Set<Integer> timePoints(String text) {
        var out = new LinkedHashSet<Integer>();
        Matcher m = TIME.matcher(normalize(text));
        while (m.find())
            if (m.group(3) == null)
                out.add(Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2)));
        return out;
    }

    /**
     * 正文里对不上事实清单的时间点，<b>原样</b>返回（要写进 fail_reason，得让人看得出它编了什么）。
     *
     * <p>先把全角冒号换成半角再抓（{@link #normalize}）：模型写"18：47"是常事，
     * 不换的话这条判据抓不到它，而抓不到就等于放行 —— 一个编造的时间点照样进库。
     *
     * <p><b>带秒段的一律算编造</b>，不管前两段对不对得上：事实清单里只会出现 {@code m:ss}，
     * 所以正文里的 {@code 1:05:30} 必然是它拿清单里的 {@code 1:05} 当前缀往后编的 ——
     * 而学员点过去是一个不存在的位置。只看前两段就会放行，这正是这条判据原先的漏洞。
     */
    static List<String> unknownTimePoints(String content, Set<Integer> allowed) {
        var bad = new ArrayList<String>();
        Matcher m = TIME.matcher(normalize(content));
        while (m.find()) {
            int sec = Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2));
            boolean unknown = m.group(3) != null || !allowed.contains(sec);
            if (unknown && !bad.contains(m.group())) bad.add(m.group());
        }
        return bad;
    }

    /**
     * 两侧共用的归一。<b>只有这一处</b>做全角→半角，两边都调它 ——
     * 各自 replace 一遍的写法，改了一边忘了另一边不会有任何信号（见类注释「归一必须两侧同步」）。
     */
    private static String normalize(String text) {
        return text == null ? "" : text.replace('：', ':');
    }

    /** 秒 → {@code m:ss}。分钟不补零，与播放器显示的一致；秒必须两位。 */
    static String mmss(int seconds) {
        int s = Math.max(0, seconds);
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }

    private static String nz(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v;
    }
}
