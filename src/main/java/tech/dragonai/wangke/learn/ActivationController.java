package tech.dragonai.wangke.learn;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;
import tech.dragonai.wangke.auth.AuthInterceptor;
import tech.dragonai.wangke.web.ClientIp;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 激活码兑换。
 *
 * <p>这一层只做三件事：取当前用户、取真实 IP、把结果翻成响应体。
 * 一码一人、first-touch 归属、协议留证、并发裁决全部在 {@link ActivationService} 与触发器里，
 * 这里<b>一条业务判断都不许有</b> —— 有的话就会出现两套规则，
 * 而 HTTP 那套是可以被绕过的（后台工具、脚本、别的入口都不走它）。
 */
@RestController
@RequestMapping("/api/learn")
public class ActivationController {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ActivationService activation;

    public ActivationController(ActivationService activation) { this.activation = activation; }

    /**
     * @param agreementVersion 学员勾选的服务协议版本。<b>必填</b>：
     *        "激活即不退"这条规则将来在纠纷里唯一能立住的依据就是它，
     *        缺了它，平台对"用户当时到底同意了什么"一句话都说不出来。
     */
    public record ActivateReq(@NotBlank(message = "激活码不能为空") String code,
                              @NotBlank(message = "缺少服务协议版本") String agreementVersion) {}

    /**
     * 兑换激活码。
     *
     * <p>注意参数上的 {@code @Valid}：Spring 只有看到它才会真正执行记录里的
     * {@code @NotBlank}。少写这个注解不会有任何报错 —— 校验注解静静地不生效，
     * 空值一路进到 service 层，最后以一句语义不对的错误收场。
     */
    @PostMapping("/activate")
    Map<String, Object> activate(@RequestBody @Valid ActivateReq r, HttpServletRequest req) {
        long userId = AuthInterceptor.userId(req);
        var result = activation.redeem(userId, r.code(), r.agreementVersion(), ClientIp.of(req));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        // firstTime=false 是"你刚才已经激活过了"，不是错误。前端据此决定是弹"激活成功"
        // 还是直接跳进课程 —— 分不清的话，弱网下重复点一次就会看到两遍成功动画。
        out.put("firstTime", result.firstTime());
        out.put("courseId", result.courseId());
        out.put("validUntil", result.validUntil().format(TS));
        out.put("message", result.firstTime()
                ? "激活成功，课程已加入「我的课程」" : "这门课你已经激活过了，可直接开始学习");
        // 刻意不返回 enrollmentId 与 attributedAgentId：
        // 前者是内部主键，后者是分销归属 —— 学员既用不到，也不该知道自己被算给了谁。
        return out;
    }
}
