package tech.dragonai.wangke.trade;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.*;
import tech.dragonai.wangke.auth.AuthInterceptor;
import tech.dragonai.wangke.web.ApiException;
import tech.dragonai.wangke.web.ClientIp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 订单的 HTTP 面：下单、查我的单、查一单、取消未支付。
 *
 * <p>钱的计算一行都不在这里 —— 价从 {@link PricingService} 取、快照由
 * {@link OrderService} 写。控制器只做三件事：确认是谁在调、把参数递进去、
 * 把结果整理成出网形状。把金额计算搬进控制器是这类项目最常见的退化：
 * 一旦有第二个入口（后台补单、导入），两处算法就会分叉，而分叉的表现是
 * 两笔同款订单金额不同，没有任何报错。
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orders;
    private final OrderQueryService view;
    private final CodeService codes;

    public OrderController(OrderService orders, OrderQueryService view, CodeService codes) {
        this.orders = orders; this.view = view; this.codes = codes;
    }

    public record CreateReq(@NotNull @Positive Long skuId) {}

    /**
     * 下单。买什么由 skuId 决定，<b>价格与数量一概不收前端的</b>。
     *
     * <p>接口里不出现 amount 参数是有意的：只要签名里有它，就一定会有某个版本
     * "为了兼容"把它用起来，然后就能自己定价。前端拿到的金额来自这个接口的返回，
     * 不是它自己算出来再传回来的。
     */
    @PostMapping
    Map<String, Object> create(@Valid @RequestBody CreateReq r, HttpServletRequest req) {
        long uid = AuthInterceptor.userId(req);
        // 只有学员和代理下单。平台管理员用自己的账号买东西，会让"这单是谁买的"
        // 这个口径失真（管理员既是审批人又是买家），而且管理员账号出现在交易流水里
        // 之后，任何按买家维度做的统计都要先去把它剔掉 —— 迟早有一处忘了剔。
        AuthInterceptor.requireRole(req, "STUDENT", "AGENT");

        var c = orders.create(uid, r.skuId(), ClientIp.of(req));
        var m = new LinkedHashMap<String, Object>();
        m.put("ok", true);
        m.put("orderId", c.orderId());
        m.put("orderNo", c.orderNo());
        m.put("amountFen", c.amountFen());
        m.put("amountYuan", OrderQueryService.yuan(c.amountFen()));
        m.put("codeQty", c.codeQty());
        return m;
    }

    /** 我的订单。分页由服务层钳位，status 只做透传过滤。 */
    @GetMapping
    Map<String, Object> mine(@RequestParam(required = false) String status,
                             @RequestParam(defaultValue = "20") int limit,
                             @RequestParam(defaultValue = "0") int offset,
                             HttpServletRequest req) {
        long uid = AuthInterceptor.userId(req);
        if (status != null && !status.isBlank() && !ALLOWED_STATUS.contains(status))
            throw ApiException.badRequest("未知的订单状态");
        List<Map<String, Object>> rows = view.mine(uid, status, limit, offset)
                .stream().map(OrderQueryService::view).toList();
        var m = new LinkedHashMap<String, Object>();
        m.put("ok", true);
        m.put("total", view.countMine(uid, status));
        m.put("orders", rows);
        return m;
    }

    /**
     * 过滤用的合法状态集合。
     *
     * <p>不校验就直接拼进 SQL 参数也能工作（参数化查询挡住了注入），
     * 但拼错的状态返回的是空列表 —— 前端会把"我传错了枚举"看成"我没有订单"。
     * 这类错永远不会被上报。
     */
    private static final java.util.Set<String> ALLOWED_STATUS = java.util.Set.of(
            "CREATED", "PAID", "FULFILLED", "CLOSED", "REFUNDED", "PARTIAL_REFUNDED");

    @GetMapping("/{orderId}")
    Map<String, Object> detail(@PathVariable long orderId, HttpServletRequest req) {
        // studentDetail 而不是 detail：这一页是学员端的「我的订单」，
        // 本人的代理进货单不该出现在这里（连同批发价）。付款那条路走 detail。
        var o = view.studentDetail(AuthInterceptor.userId(req), orderId);
        var m = new LinkedHashMap<String, Object>();
        m.put("ok", true);
        m.put("order", OrderQueryService.view(o));
        return m;
    }

    /**
     * 取消未支付订单。
     *
     * <p>幂等性说明：重复取消返回 409 而不是 200。这不是洁癖 ——
     * 客户端把"已取消"当成"我这次取消成功了"的话，两个标签页各点一次取消，
     * 第二次其实什么都没做，但界面会显示成功；而如果中间这单已经被支付了，
     * 用户看到的仍然是"取消成功"。状态要如实回。
     */
    @PostMapping("/{orderId}/cancel")
    Map<String, Object> cancel(@PathVariable long orderId, HttpServletRequest req) {
        view.cancel(AuthInterceptor.userId(req), orderId);
        return Map.of("ok", true, "message", "订单已取消");
    }

    /**
     * 查看平台直售订单的激活码。明文只经显式 POST 返回且每次留审计；订单列表和详情
     * 始终只返回交易字段，避免浏览订单、截图或中间层缓存时泄露可直接兑换的凭据。
     *
     * <p>角色白名单放到 AGENT 是必须的，不是放宽：{@code OrderService.create} 明确允许
     * 代理本人按学员价买课，那种单 buyer_type='STUDENT'、buyer_agent_id 为 null，
     * 发出来的码 owner_agent_id 也是 null —— 代理端的码列表按 owner_agent_id 查，
     * 一条都看不到。只留 STUDENT 的话，代理付了钱之后两条路都取不到码，钱收了货锁死。
     * 真正的授权边界从来不在这一行，而在 {@code revealForBuyer} 的 {@code o.buyer_user_id=?} 上。
     */
    @PostMapping("/{orderId}/activation-code/reveal")
    Map<String, Object> revealActivationCode(@PathVariable long orderId, HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "STUDENT", "AGENT");
        long uid = AuthInterceptor.userId(req);
        String plain = codes.revealForBuyer(uid, orderId, ClientIp.of(req));
        return Map.of(
                "ok", true,
                "code", plain,
                "notice", "该次查看已记入审计日志。请前往激活页确认协议并开通课程");
    }
}
