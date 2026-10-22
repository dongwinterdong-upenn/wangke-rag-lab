package tech.dragonai.wangke.support;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;
import tech.dragonai.wangke.auth.AuthInterceptor;
import tech.dragonai.wangke.web.ClientIp;

import java.util.Map;

/**
 * 开票处理（平台侧）。
 *
 * <p>只有 PLATFORM_ADMIN。导师不在此列 —— 开票是财务动作，
 * 把它和答疑放进同一个角色白名单，只是因为两者在同一个 Controller 目录下，那不是理由。
 *
 * <p>开票与驳回都写 audit_log。这两个动作事后一定会被问到
 * （「这张票是谁开的」「凭什么驳我」），而没有留痕的那一方在争议里是没有立场的。
 */
@RestController
@RequestMapping("/api/invoice/admin")
public class InvoiceAdminController {

    private final InvoiceService invoices;

    public InvoiceAdminController(InvoiceService invoices) { this.invoices = invoices; }

    public record IssueReq(@NotBlank String invoiceNo) {}
    public record RejectReq(@NotBlank String reason) {}

    @GetMapping("/list")
    Map<String, Object> list(@RequestParam(required = false) String status,
                             @RequestParam(defaultValue = "50") int limit,
                             HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        return Map.of("ok", true, "invoices", invoices.queue(status, limit));
    }

    /**
     * 登记发票号并标记已开。金额在这一刻会被<b>重新核一遍</b>
     * （申请到开票之间可能已经退款），见 {@link InvoiceService#issue}。
     */
    @PostMapping("/{id:\\d+}/issue")
    Map<String, Object> issue(@PathVariable long id, @RequestBody IssueReq r, HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        invoices.issue(AuthInterceptor.userId(req), id, r.invoiceNo(), ClientIp.of(req));
        return Map.of("ok", true);
    }

    /** 驳回必须带原因：原因要回给申请人看，否则同一张错抬头会被反复提交、反复驳回。 */
    @PostMapping("/{id:\\d+}/reject")
    Map<String, Object> reject(@PathVariable long id, @RequestBody RejectReq r, HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        invoices.reject(AuthInterceptor.userId(req), id, r.reason(), ClientIp.of(req));
        return Map.of("ok", true);
    }
}
