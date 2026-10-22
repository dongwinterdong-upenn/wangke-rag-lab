package tech.dragonai.wangke.support;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;
import tech.dragonai.wangke.auth.AuthInterceptor;
import tech.dragonai.wangke.web.ClientIp;

import java.util.Map;

/**
 * 答疑（导师 / 平台侧）。
 *
 * <p><b>角色闸只是第一道，不是唯一一道。</b>{@code requireRole} 拦掉的是「学员来调管理接口」；
 * 拦不掉「导师 A 去回复分配给导师 B 的工单」—— 那是同一个角色内部的越权。
 * 后者一律靠 {@link QaService#scopeOf} 把 {@code assignee_id} 压进 WHERE 来守，
 * 所以这个 Controller 里没有任何一处「先取出工单再判断能不能动」。
 */
@RestController
@RequestMapping("/api/qa/admin")
public class QaAdminController {

    private final QaService qa;

    public QaAdminController(QaService qa) { this.qa = qa; }

    public record ReplyReq(@NotBlank String content) {}
    public record AssignReq(@NotNull Long mentorUserId) {}
    public record PublishReq(@NotBlank String question, @NotBlank String answer) {}

    /**
     * 待处理队列。导师只出分配给自己的，管理员出全部（条件在 SQL 里，见 QaService.queue）。
     *
     * @param status 不传 = 只出待处理的（ESCALATED），这是这个接口的默认语义。
     *               <p>★ 这个参数是补上去的，补之前后台是这样的：接口<b>恒定</b>只回 ESCALATED，
     *               而界面上给了四个状态的下拉。另外三档永远筛出 0 条，运营会读成
     *               「没有这类工单」而不是「这个接口根本不给这类工单」——
     *               两者在屏幕上长得一模一样，都是一张空表。
     *               <p>更实际的后果是：导师回复完，工单当场变成 HUMAN_ANSWERED 离开队列，
     *               于是「回复完记得关闭、好答案沉淀进公开问答库」这两步<b>再也没有入口</b>
     *               （后台没有按 id 找工单的地方）。
     */
    @GetMapping("/queue")
    Map<String, Object> queue(@RequestParam(defaultValue = "50") int limit,
                              @RequestParam(required = false) String status,
                              HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "MENTOR", "PLATFORM_ADMIN");
        return Map.of("ok", true, "tickets",
                qa.queue(AuthInterceptor.userId(req), AuthInterceptor.role(req), status, limit));
    }

    @GetMapping("/{id:\\d+}")
    Map<String, Object> detail(@PathVariable long id, HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "MENTOR", "PLATFORM_ADMIN");
        return Map.of("ok", true, "ticket",
                qa.detail(AuthInterceptor.userId(req), AuthInterceptor.role(req), id));
    }

    @PostMapping("/{id:\\d+}/reply")
    Map<String, Object> reply(@PathVariable long id, @RequestBody ReplyReq r, HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "MENTOR", "PLATFORM_ADMIN");
        qa.staffReply(AuthInterceptor.userId(req), AuthInterceptor.role(req), id, r.content());
        return Map.of("ok", true);
    }

    @PostMapping("/{id:\\d+}/close")
    Map<String, Object> close(@PathVariable long id, HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "MENTOR", "PLATFORM_ADMIN");
        qa.close(AuthInterceptor.userId(req), AuthInterceptor.role(req), id);
        return Map.of("ok", true);
    }

    /**
     * 指派导师。<b>只有平台管理员</b> —— 理由见 {@link QaService#assign}：
     * 让导师自己认领的话，「导师只能看分配给自己的」这条规则就只是多了一步而已。
     */
    @PostMapping("/{id:\\d+}/assign")
    Map<String, Object> assign(@PathVariable long id, @RequestBody AssignReq r, HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        qa.assign(AuthInterceptor.userId(req), id, r.mentorUserId(), ClientIp.of(req));
        return Map.of("ok", true);
    }

    /**
     * 沉淀进公开问答库。
     *
     * <p>question / answer 要求重新填写而不是直接照搬工单原文：工单正文里常带着
     * 学员自己的姓名、手机号、订单号，原样搬进公开库就是把个人信息发到公网上。
     * 让操作者重写一遍是目前唯一可靠的脱敏手段 —— 自动脱敏漏一条不会有任何提示。
     */
    @PostMapping("/{id:\\d+}/publish")
    Map<String, Object> publish(@PathVariable long id, @RequestBody PublishReq r, HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "MENTOR", "PLATFORM_ADMIN");
        long faqId = qa.publishFaq(AuthInterceptor.userId(req), AuthInterceptor.role(req),
                id, r.question(), r.answer(), ClientIp.of(req));
        return Map.of("ok", true, "faqId", faqId);
    }
}
