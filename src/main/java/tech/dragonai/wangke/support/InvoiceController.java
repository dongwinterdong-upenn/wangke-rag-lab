package tech.dragonai.wangke.support;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;
import tech.dragonai.wangke.auth.AuthInterceptor;
import tech.dragonai.wangke.web.ApiException;

import java.util.Map;

/**
 * 开票申请（买方侧）。<b>这一侧只服务代理的进货单，学员一律拒绝。</b>
 *
 * <p><b>请求体里没有金额字段，这是有意的、也是这一层最重要的一件事。</b>
 * 只要 DTO 上存在一个 amountFen，它迟早会在某个「先接进来，回头再校验」的分支里被采信，
 * 而那一刻平台开出的就是一张金额由买方指定的发票。
 * 金额的唯一来源是 {@link InvoiceService#apply} 里按订单实付减退款算出来的那个数。
 */
@RestController
@RequestMapping("/api/invoice")
public class InvoiceController {

    private final InvoiceService invoices;

    public InvoiceController(InvoiceService invoices) { this.invoices = invoices; }

    /**
     * 谁还能走买方侧开票。
     *
     * <h3>为什么闸开在服务端而不是「把学员端那块界面删掉」就完事</h3>
     * 2026-09-01 需求方把学员端的开票入口<b>当 bug 报</b>：课程发票由售出方（代理）开，
     * 学员本来就不该自助申请。界面删掉之后 {@code /api/invoice/**} 三条路仍然敞着 ——
     * 存过一份请求、或者会开 devtools 的学员照样提交得进来，
     * 而这种「坏」<b>不会报任何错</b>：申请安静地落库，然后堆在运营那边成为一批
     * 他们既没有流程也没有权限处理的工单。删界面只是把入口藏起来，不是把功能关掉。
     *
     * <h3>为什么写成白名单（只有 AGENT 过）而不是「把 STUDENT 挡掉」</h3>
     * 黑名单在<b>下一个角色出现</b>的那天会默默把它放进来，且没有任何提示 ——
     * 这与 {@link AuthInterceptor} 类注释里「默认拒绝、白名单放行」是同一条理由。
     * 多放一个角色进来的代价是虚开风险面变大，少放一个的代价是一句能看懂的拒绝，
     * 两者不对等。
     *
     * <p>文案不能是「没有权限」：学员看到那四个字只会以为账号出了问题，然后打客服电话，
     * 而客服要说的恰恰就是这句话本身。所以直接把<b>下一步</b>写在拒绝里。
     */
    private static void requireInvoiceableRole(HttpServletRequest req) {
        if (!"AGENT".equals(AuthInterceptor.role(req)))
            throw ApiException.forbidden("课程发票请联系客服开具，学员端不提供自助开票");
    }

    /** taxNo 可空：个人抬头没有纳税人识别号。 */
    public record ApplyReq(@NotNull Long orderId, @NotBlank String title, String taxNo) {}

    @PostMapping("/apply")
    Map<String, Object> apply(@RequestBody ApplyReq r, HttpServletRequest req) {
        requireInvoiceableRole(req);
        var out = invoices.apply(AuthInterceptor.userId(req), r.orderId(), r.title(), r.taxNo());
        return Map.of("ok", true, "invoice", out);
    }

    @GetMapping("/my")
    Map<String, Object> my(@RequestParam(defaultValue = "50") int limit, HttpServletRequest req) {
        // 读也要挡。这条出的是申请人自己的抬头、税号与金额，本身不算越权，
        // 但放着不挡就等于学员端还剩一块「能看见发票列表、只是提交不了」的半截功能：
        // 界面上什么都不显示、接口里却答得好好的，下一个人照着它就把入口又接回去了。
        requireInvoiceableRole(req);
        return Map.of("ok", true, "invoices", invoices.mine(AuthInterceptor.userId(req), limit));
    }

    /**
     * 可以开票的订单。前端不再拿 {@code /api/orders} 来挑单 ——
     * 那条路只出学员单，代理的进货单开不了票。
     * 理由见 {@link InvoiceService#invoiceableOrders}。
     */
    @GetMapping("/eligible-orders")
    Map<String, Object> eligible(@RequestParam(defaultValue = "50") int limit,
                                 HttpServletRequest req) {
        requireInvoiceableRole(req);
        return Map.of("ok", true,
                "orders", invoices.invoiceableOrders(AuthInterceptor.userId(req), limit));
    }
}
