package tech.dragonai.wangke.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;
import tech.dragonai.wangke.web.ClientIp;

import java.util.Map;

/** 注册登录。全部在 {@code /api/auth/} 白名单下，无需登录即可访问。 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;
    private final SmsCodeService sms;

    public AuthController(AuthService auth, SmsCodeService sms) {
        this.auth = auth; this.sms = sms;
    }

    public record SendCodeReq(@NotBlank String phone, @NotBlank String scene) {}
    public record RegisterReq(@NotBlank String phone, @NotBlank String code,
                              String password, String ref) {}
    public record CodeLoginReq(@NotBlank String phone, @NotBlank String code, String ref) {}
    public record PwdLoginReq(@NotBlank String phone, @NotBlank String password) {}
    public record ResetReq(@NotBlank String phone, @NotBlank String code, @NotBlank String password) {}
    public record ChangePwdReq(String oldPassword, @NotBlank String newPassword) {}

    // ★ 五个入参 record 上的 @NotBlank 只有在参数前写了 @Valid 时才会执行。
    // 2026-09-02 之前这里一个 @Valid 都没有：畸形请求（比如 scene 缺席）直接走到
    // r.scene().toUpperCase() 上 NPE，出网是 500「未预期异常」而不是 400 ——
    // 对客户端来说是"服务器坏了"，对日志来说是一条 ERROR 级的堆栈，两边都指错方向。
    // 全局 MethodArgumentNotValidException 处理器早就接好了，只差这一个词。
    @PostMapping("/sms")
    Map<String, Object> sendCode(@Valid @RequestBody SendCodeReq r, HttpServletRequest req) {
        SmsCodeService.Scene scene;
        try {
            scene = SmsCodeService.Scene.valueOf(r.scene().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw tech.dragonai.wangke.web.ApiException.badRequest("未知的验证码场景");
        }
        int cooldown = sms.send(r.phone(), scene, ClientIp.of(req));
        return Map.of("ok", true, "cooldownSeconds", cooldown);
    }

    @PostMapping("/register")
    Map<String, Object> register(@Valid @RequestBody RegisterReq r, HttpServletRequest req) {
        return Map.of("ok", true, "session",
                auth.register(r.phone(), r.code(), r.password(), r.ref(), ClientIp.of(req)));
    }

    @PostMapping("/login/code")
    Map<String, Object> loginByCode(@Valid @RequestBody CodeLoginReq r, HttpServletRequest req) {
        return Map.of("ok", true, "session",
                auth.loginByCode(r.phone(), r.code(), r.ref(), ClientIp.of(req)));
    }

    @PostMapping("/login/password")
    Map<String, Object> loginByPassword(@Valid @RequestBody PwdLoginReq r) {
        return Map.of("ok", true, "session", auth.loginByPassword(r.phone(), r.password()));
    }

    @PostMapping("/password/reset")
    Map<String, Object> reset(@Valid @RequestBody ResetReq r) {
        auth.resetPassword(r.phone(), r.code(), r.password());
        return Map.of("ok", true, "message", "密码已重置，请重新登录");
    }
}
