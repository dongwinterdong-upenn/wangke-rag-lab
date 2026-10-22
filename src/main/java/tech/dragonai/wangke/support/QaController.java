package tech.dragonai.wangke.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tech.dragonai.wangke.auth.AuthInterceptor;
import tech.dragonai.wangke.web.ApiException;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 答疑（学员侧）。
 *
 * <p>路径分两档：{@code /api/qa/**} 要登录（AuthInterceptor 默认拒绝），
 * {@code /api/public/faq} 在白名单里、公开可查 —— 公开问答库本来就是对外物料。
 *
 * <p><b>{@code {id:\d+}} 的正则不是装饰。</b>没有它的话，
 * {@code GET /api/qa/admin} 会先被 {@code /api/qa/{id}} 匹配上，
 * 然后在把 "admin" 转成 long 时抛类型转换异常 —— 用户看到的是 500「系统繁忙」，
 * 日志里是一条看起来像内部故障的堆栈，而真实原因只是路由撞了。
 */
@RestController
@RequestMapping("/api")
public class QaController {

    private static final Logger log = LoggerFactory.getLogger(QaController.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final QaService qa;
    private final FaqSearch faq;

    public QaController(QaService qa, FaqSearch faq) { this.qa = qa; this.faq = faq; }

    /** 注意：没有 amount 之类由前端定夺的字段；wantHuman 只是「我要转人工」，不是提权。 */
    public record AskReq(@NotNull Long courseId, Long lessonId,
                         @NotBlank String title, @NotBlank String content,
                         Boolean wantHuman) {}

    public record ReplyReq(@NotBlank String content) {}

    @PostMapping("/qa/ask")
    Map<String, Object> ask(@RequestBody AskReq r, HttpServletRequest req) {
        var a = qa.ask(AuthInterceptor.userId(req), r.courseId(), r.lessonId(),
                r.title(), r.content(), Boolean.TRUE.equals(r.wantHuman()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("ticketId", a.ticketId());
        out.put("status", a.status());
        out.put("answered", a.answered());
        out.put("answer", a.answer());
        out.put("faqId", a.faqId());
        out.put("quotaLeftToday", a.quotaLeftToday());
        // Map.of 遇到 null 值会 NPE，而这里 answer/faqId 天然可空（转人工时没有答案）。
        // 用 LinkedHashMap 是为了让「没答上」这件事以 null 如实出网，而不是被迫编一个空串。
        return out;
    }

    @GetMapping("/qa/my")
    Map<String, Object> my(@RequestParam(defaultValue = "50") int limit, HttpServletRequest req) {
        return Map.of("ok", true, "tickets", qa.myTickets(AuthInterceptor.userId(req), limit));
    }

    @GetMapping("/qa/{id:\\d+}")
    Map<String, Object> detail(@PathVariable long id, HttpServletRequest req) {
        return Map.of("ok", true, "ticket",
                qa.detail(AuthInterceptor.userId(req), AuthInterceptor.role(req), id));
    }

    /**
     * 追问。返回体里带今天的剩余次数，理由见 {@link QaService#reply}：
     * 常驻挂件上的那个计数只有在两条链都出这个数时才准得住。
     *
     * <p>同样用 LinkedHashMap 而不是 Map.of：这一条没扣配额时 quotaLeftToday 是 null，
     * 而 Map.of 遇到 null 值直接 NPE —— 那会把一次正常的追问变成 500。
     */
    @PostMapping("/qa/{id:\\d+}/reply")
    Map<String, Object> reply(@PathVariable long id, @RequestBody ReplyReq r, HttpServletRequest req) {
        Integer left = qa.reply(AuthInterceptor.userId(req), id, r.content());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("quotaLeftToday", left);
        return out;
    }

    /* ============================================================ 流式 */

    /**
     * 提问 / 追问的流式版：懂妈写一句送一句，学员不用对着一个转圈等四秒。
     *
     * <h3>三件必须一起做对的事</h3>
     * <ol>
     *   <li><b>{@code X-Accel-Buffering: no}。</b>nginx 默认会把上游的响应攒起来再一次性发。
     *       不带这个头，后端确实是流式的、浏览器却在最后一刻一次性收到全部 ——
     *       <b>看起来和没做流式一模一样，而且不报任何错</b>。
     *       （sidecar → 后端那一段已经带了；这里是后端 → nginx → 浏览器那一段。）
     *   <li><b>不能用浏览器的 EventSource。</b>它发不了自定义请求头，也发不了 POST 体，
     *       而这两个接口都要 Bearer 令牌 + 请求体。前端走 fetch + ReadableStream。
     *   <li><b>结尾一定要有一帧。</b>{@code done} 或 {@code aborted} 二选一。
     *       只靠"连接关了"当结束信号，与"网断了"在客户端那里长得一模一样 ——
     *       而这两件事的处置完全不同（一个是答完了，一个是要转人工）。
     * </ol>
     *
     * <h3>aborted 帧：已经显示出去的半截必须撤掉</h3>
     * 懂妈没说完时（{@code finish_reason=length}、连接断在半路、调不通），
     * 服务端一个字都不落库、工单留在 ESCALATED。但那些字<b>已经在学员屏幕上了</b> ——
     * 这是流式相对非流式新增的唯一风险。所以收尾发 {@code aborted}，
     * 前端据此把那一段撤掉并换成「这条没答完，已经转给老师」。
     * <b>留着半截更坏：半句话读起来和一整句一样通顺，而缺的往往正是后半段的注意事项。</b>
     */
    @PostMapping(value = "/qa/ask/stream", produces = "text/event-stream;charset=UTF-8")
    ResponseEntity<StreamingResponseBody> askStream(@RequestBody AskReq r, HttpServletRequest req) {
        long userId = AuthInterceptor.userId(req);
        return sse(out -> {
            var a = qa.askStreaming(userId, r.courseId(), r.lessonId(), r.title(), r.content(),
                    Boolean.TRUE.equals(r.wantHuman()), delta -> frame(out, Map.of("delta", delta)));
            Map<String, Object> tail = new LinkedHashMap<>();
            tail.put(a.answered() ? "done" : "aborted", true);
            tail.put("ticketId", a.ticketId());
            tail.put("status", a.status());
            tail.put("quotaLeftToday", a.quotaLeftToday());
            frame(out, tail);
        });
    }

    @PostMapping(value = "/qa/{id:\\d+}/reply/stream", produces = "text/event-stream;charset=UTF-8")
    ResponseEntity<StreamingResponseBody> replyStream(@PathVariable long id,
                                                      @RequestBody ReplyReq r,
                                                      HttpServletRequest req) {
        long userId = AuthInterceptor.userId(req);
        return sse(out -> {
            // 有没有答上，判据是「这一轮之后工单是不是 AI_ANSWERED」——
            // 追问这条链不像首问那样回一个 answered 布尔，而状态本身就是权威。
            Integer left = qa.replyStreaming(userId, id, r.content(),
                    delta -> frame(out, Map.of("delta", delta)));
            String status = qa.statusOf(userId, id);
            Map<String, Object> tail = new LinkedHashMap<>();
            tail.put("AI_ANSWERED".equals(status) ? "done" : "aborted", true);
            tail.put("ticketId", id);
            tail.put("status", status);
            tail.put("quotaLeftToday", left);
            frame(out, tail);
        });
    }

    /**
     * 把业务异常也送进流里。
     *
     * <p>流一旦开始（响应头已发出、状态码已定死 200），再抛异常是没用的：
     * 全局异常处理器改不了已经发出去的头，客户端拿到的是一条<b>突然断掉的 200</b>。
     * 所以配额用尽、越权、工单已关闭这些，都得作为流里的一帧 {@code error} 送出去。
     *
     * <p>鉴权失败（401）不在此列 —— 那是 AuthInterceptor 在进方法之前就挡掉的，
     * 那时候流还没开始，走的仍然是正常的 401 出口。
     */
    private ResponseEntity<StreamingResponseBody> sse(java.util.function.Consumer<OutputStream> body) {
        StreamingResponseBody s = out -> {
            try {
                body.accept(out);
            } catch (ApiException e) {
                frame(out, Map.of("error", e.getMessage(), "status", e.status()));
            } catch (ClientGone e) {
                // 人已经走了（关页面、切走）。不是错误，也没人可通知，安静收工。
            } catch (RuntimeException e) {
                log.warn("答疑流中断", e);
                frame(out, Map.of("error", "服务暂时不可用，请稍后再试"));
            }
            out.flush();
        };
        return ResponseEntity.ok()
                .header("Content-Type", "text/event-stream;charset=UTF-8")
                .header("Cache-Control", "no-cache, no-transform")
                .header("X-Accel-Buffering", "no")   // ← 少了这一行，nginx 会把整条流攒到最后
                .body(s);
    }

    /** 客户端断开。单独一个类型，为的是能和"真出错了"分开处置（见 {@link #sse}）。 */
    static class ClientGone extends RuntimeException {
        ClientGone(Throwable cause) { super(cause); }
    }

    /**
     * 发一帧并<b>当场 flush</b>。不 flush 的话字会攒在 8KB 的缓冲里，
     * 于是"流式"退化成"分几大坨发" —— 短回答干脆一坨发完，和不做流式没区别。
     */
    private static void frame(OutputStream out, Map<String, ?> payload) {
        try {
            out.write(("data: " + JSON.writeValueAsString(payload) + "\n\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            // 写不出去只有一个原因：对面走了。往上抛，让收流那一侧一起停下来 ——
            // 继续把模型的输出收完再落库，落的是一份没人会看的答案，还白占一个连接。
            throw new ClientGone(e);
        }
    }

    /**
     * 公开 FAQ 检索。走 ngram 全文索引，见 {@link FaqSearch} 里的 EXPLAIN 实测。
     *
     * <p>公开等于任何人可调，所以 limit 有硬上限、检索词有长度下限，
     * 两条都在 FaqSearch 里，不在这儿 —— 免得哪天多一个调用方就少一道闸。
     */
    @GetMapping("/public/faq")
    Map<String, Object> publicFaq(@RequestParam("q") String q,
                                  @RequestParam(required = false) Long courseId,
                                  @RequestParam(defaultValue = "10") int limit) {
        List<FaqSearch.Hit> hits = faq.search(courseId, q, limit);
        List<Map<String, Object>> items = hits.stream().map(h -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", h.faqId());
            m.put("courseId", h.courseId());
            m.put("question", h.question());
            m.put("answer", h.answer());
            // 不出 score：它是 MATCH 的原始相关度，没有量纲、跨语料不可比，
            // 出网只会被前端拿去做「相似度 87%」这种它并不表示的东西。
            return m;
        }).toList();
        return Map.of("ok", true, "total", items.size(), "items", items);
    }
}
