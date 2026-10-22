package tech.dragonai.wangke.exam;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tech.dragonai.wangke.auth.AuthInterceptor;
import tech.dragonai.wangke.web.ApiException;

import java.util.Map;

/**
 * 测评接口。全部需要登录（AuthInterceptor 默认拒绝，这些路径不在白名单里）。
 *
 * <h3>为什么路径参数声明成 String 再自己解析</h3>
 * 写成 {@code @PathVariable long attemptId} 的话，{@code GET /api/exam/abc}
 * 会抛 MethodArgumentTypeMismatchException；GlobalExceptionHandler 没有针对它的分支，
 * 于是落到"未预期异常"→ <b>500 并写一条 ERROR 日志</b>。
 * 一个手抖打错的 URL 不该被记成系统故障 —— 真故障会被这类噪声淹没。
 * 同理 {@code @RequestParam long} 缺参数、{@code @RequestBody} 空 body 都会走到 500。
 * 这里统一自己收口成 400。（更彻底的做法是在全局处理器里补这三个分支，
 * 那是共享文件，已在交付说明里提给主控。）
 *
 * <h3>路由顺序</h3>
 * {@code /papers}、{@code /config} 与 {@code /attempts} 都是字面量路径，Spring 的匹配优先级
 * 高于 {@code /{attemptId}}，不会被后者吃掉。这条依赖的是框架行为，所以写在这里 ——
 * 哪天有人把 {@code /{attemptId}} 改成更宽的模式，这条注释是唯一的提醒。
 *
 * <h3>入口参数从 courseId 换成 paperId（2026-09-03）</h3>
 * 一门课现在挂着一份综合卷加四份章节卷，另有一份不挂课程的开放卷，
 * "课程"已经不足以指认要考哪一份。{@code courseId} 仍然收，解析成<b>这门课发结业证明的
 * 那一张卷</b>（见 {@link ExamService#certificatePaperOf}）—— 解析不出来就 409 说明白，
 * <b>不静默取第一张</b>：取错卷不会报任何错，学员只是考了一份他没打算考的卷。
 *
 * <p>两个参数都不给是 400。都给了以 {@code paperId} 为准 —— 老客户端不会同时带两个，
 * 同时出现说明是新客户端在传 paperId，而 courseId 只是它顺手带上的上下文。
 */
@RestController
@RequestMapping("/api/exam")
public class ExamController {

    private final ExamService exam;
    private final ExamReportService reports;

    public ExamController(ExamService exam, ExamReportService reports) {
        this.exam = exam;
        this.reports = reports;
    }

    public record StartReq(Long paperId, Long courseId) {}

    /** 作答：题号 → 选项。没答的题可以不出现。 */
    public record SubmitReq(Map<String, String> answers) {}

    /**
     * 我能考的全部卷。
     *
     * <p>开放卷（access='OPEN'）对任何注册学员都在这张列表里，<b>哪怕他一门课都没买</b>；
     * 反过来，权益不在的卷根本不进列表 —— 判据与开考闸门是同一段代码，
     * 所以不会出现"列表里有、点进去 403"。
     */
    @GetMapping("/papers")
    Map<String, Object> papers(HttpServletRequest req) {
        return Ok.of("papers", exam.papers(AuthInterceptor.userId(req)));
    }

    /** 这份卷的测评规则 + 我当前能不能考。规则全部来自 exam_config，没有一个数字写死在代码里。 */
    @GetMapping("/config")
    Map<String, Object> config(@RequestParam(name = "paperId", required = false) Long paperId,
                               @RequestParam(name = "courseId", required = false) Long courseId,
                               HttpServletRequest req) {
        var s = exam.status(AuthInterceptor.userId(req), requirePaper(paperId, courseId));
        return Ok.of("rules", s.rules(),
                "progressPct", s.progressPct(),
                "attemptsUsed", s.attemptsUsed(),
                "attemptsLeft", s.attemptsLeft(),
                "inProgressAttemptId", s.inProgressAttemptId(),
                "passedAttemptId", s.passedAttemptId(),
                "cooldownSecondsLeft", s.cooldownSecondsLeft(),
                "canStart", s.canStart(),
                "blockedReason", s.blockedReason());
    }

    /** 开考。已有进行中的场次时返回那一场（firstTime=false），不新开、不烧次数。 */
    @PostMapping("/start")
    Map<String, Object> start(@RequestBody(required = false) StartReq r, HttpServletRequest req) {
        if (r == null) throw ApiException.badRequest("请求体为空");
        var s = exam.start(AuthInterceptor.userId(req), requirePaper(r.paperId(), r.courseId()));
        return Ok.of("firstTime", s.firstTime(), "attemptId", s.attemptId(),
                "attemptNo", s.attemptNo(), "deadlineAt", s.deadlineAt(),
                "questionCount", s.questionCount());
    }

    /**
     * 我的考试记录。两个筛子都可选：{@code paperId} 精确到一份卷，
     * {@code courseId} 给的是这门课所有卷的场次（老客户端要的就是这份）。
     *
     * <p><b>接口契约里的一条硬边界：{@code ?courseId=} 查不到开放卷的场次。</b>
     * 开放卷不属于任何课程（{@code exam_attempt.course_id} 为 NULL），
     * 按课程筛在 SQL 里对 NULL 恒假。这不是缺陷是定义 —— 但它的坏法是<b>静默的</b>：
     * 哪天有页面拿这条当"我的全部考试记录"总表，学员考过的 AIGC 开放卷那几场
     * 会一声不响地不在列表里。要总表就<b>两个参数都不传</b>。
     */
    @GetMapping("/attempts")
    Map<String, Object> attempts(@RequestParam(name = "paperId", required = false) Long paperId,
                                 @RequestParam(name = "courseId", required = false) Long courseId,
                                 HttpServletRequest req) {
        return Ok.of("attempts", exam.attempts(AuthInterceptor.userId(req), paperId, courseId));
    }

    /**
     * 取考卷。
     *
     * <p><b>响应体里不含任何答案字段</b>，这是接口契约的一部分而不是实现细节：
     * 前端判分是假判分，浏览器能读到的东西等于公开。
     * ExamIT 会把这个方法的真实返回值序列化后遍历整棵 JSON 树来守这条。
     */
    @GetMapping("/{attemptId}")
    Map<String, Object> paper(@PathVariable("attemptId") String attemptId, HttpServletRequest req) {
        var p = exam.paper(AuthInterceptor.userId(req), asId(attemptId, "考试记录编号"));
        // paperId 要带出去：答题页是由 attempt 驱动的，交完卷"返回测评列表"时
        // 前端得知道回到哪一份卷，不然它只能退回到一个笼统的入口。
        //
        // paperTitle / grantsCertificate 同理，而且是成绩页唯一的依据：
        // 通过之后要不要显示"去领结业证明"由 grantsCertificate 说了算，不许前端按默认值猜 ——
        // 猜"发证"的话，考过章节卷的人会看到一个点下去必然 409 的按钮；
        // 猜"不发证"的话，真考过综合卷的人拿不到他应得的那张证，而两种猜错都不报错。
        return Ok.of("attemptId", p.attemptId(), "paperId", p.paperId(),
                "paperTitle", p.paperTitle(), "grantsCertificate", p.grantsCertificate(),
                "attemptNo", p.attemptNo(), "rules", p.rules(),
                "deadlineAt", p.deadlineAt(), "secondsLeft", p.secondsLeft(),
                "submitted", p.submitted(), "score", p.score(), "passed", p.passed(),
                "questions", p.questions());
    }

    /** 交卷判分。判分只在服务端做；重复提交返回上次成绩而不是报错。 */
    @PostMapping("/{attemptId}/submit")
    Map<String, Object> submit(@PathVariable("attemptId") String attemptId,
                               @RequestBody(required = false) SubmitReq r, HttpServletRequest req) {
        var s = exam.submit(AuthInterceptor.userId(req), asId(attemptId, "考试记录编号"),
                r == null ? null : r.answers());
        return Ok.of("firstTime", s.firstTime(), "attemptId", s.attemptId(),
                "score", s.score(), "questionCount", s.questionCount(),
                "passScore", s.passScore(), "passed", s.passed());
    }

    /**
     * 这一场的测评报告。
     *
     * <p><b>QUEUED / RUNNING 时也把 stats 返回</b>：分数、按讲次的正确率、错题清单都是
     * 代码算出来的客观事实，学员打开这一页的第一秒就该看到它们 ——
     * 让整页等一个 19 秒的外部调用，等于把一次可能的模型故障变成"报告页永远空白"。
     *
     * <p>越权判据在 SQL 的 WHERE 里（{@code AND user_id=?}），"不是你的"与"不存在"
     * 对外是同一句话，否则这就是一个能枚举别人考试编号的探针。
     *
     * <p>路由：{@code /{attemptId}/report} 比 {@code /{attemptId}} 多一段，
     * 两者不会互相吃掉（同上：这条依赖框架的匹配优先级，所以写在注释里）。
     */
    @GetMapping("/{attemptId}/report")
    Map<String, Object> report(@PathVariable("attemptId") String attemptId, HttpServletRequest req) {
        var v = reports.report(AuthInterceptor.userId(req), asId(attemptId, "考试记录编号"));
        return Ok.of("status", v.status(), "stats", v.stats(), "content", v.content(),
                "model", v.model(), "failReason", v.failReason(), "canRetry", v.canRetry());
    }

    /**
     * 重新生成。仅 FAILED 可重试，且同一场每 5 分钟一次（限频在 Service 里，
     * 与"能不能重试"是同一段代码 —— 两处各写一遍的话，按钮会亮着而点下去被拒）。
     *
     * <p>不返回报告内容：生成要十几秒，这条请求只负责把它排回队里，
     * 前端照旧轮询 {@link #report}。当场把内容等出来的话，这条 HTTP 请求就又被拖住了。
     */
    @PostMapping("/{attemptId}/report/retry")
    Map<String, Object> retryReport(@PathVariable("attemptId") String attemptId,
                                    HttpServletRequest req) {
        reports.retry(AuthInterceptor.userId(req), asId(attemptId, "考试记录编号"));
        return Ok.of("status", "QUEUED");
    }

    /**
     * 入口参数收口：新客户端给 paperId，老客户端给 courseId。
     *
     * <p>courseId 的解析<b>不在这里做</b>，在 {@link ExamService#certificatePaperOf} ——
     * "哪张卷是这门课的正卷"是业务判据（grants_certificate），不是路由细节，
     * 而且它要查库。控制器只负责"两个都没给就是 400"。
     */
    private long requirePaper(Long paperId, Long courseId) {
        if (paperId != null) return paperId;
        if (courseId == null) throw ApiException.badRequest("缺少 paperId");
        return exam.certificatePaperOf(courseId);
    }

    private static long asId(String raw, String what) {
        try {
            return Long.parseLong(raw.trim());
        } catch (RuntimeException e) {
            throw ApiException.badRequest(what + "不合法");
        }
    }
}
