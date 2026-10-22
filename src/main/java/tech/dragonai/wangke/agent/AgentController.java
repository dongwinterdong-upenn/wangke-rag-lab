package tech.dragonai.wangke.agent;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.*;
import tech.dragonai.wangke.auth.AuthInterceptor;
import tech.dragonai.wangke.web.ClientIp;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 代理商后台的接口面（工作台 / 商品 / 进货单 / 激活码 / 学员 / 佣金 / 推广 / 资料）。
 *
 * <p><b>控制器里没有一个方法接收 agentId。</b>当前身份只从令牌来（{@link AuthInterceptor#userId}），
 * 代理档案由服务层解析，越权条件在 SQL 的 WHERE 里。
 * 一旦哪个接口开始收 agentId 参数，它就变成了"任意代理数据查询接口"——
 * 而且加上这个参数的那次改动看起来只是"支持平台侧复用"，不会有人当成安全变更来审。
 * 同理，{@code /skus} 有意不收 agentLevel（收了就等于开放"二级看一级价目"），
 * {@code POST /orders} 有意不收 amount / codeQty（签名里只要出现 amount，
 * 就一定会有某个版本"为了兼容"把它用起来，然后前端就能自己定价）。
 *
 * <h3>状态门禁分三档，不是一句"除 /me 外全部要求 ACTIVE"</h3>
 * <ul>
 *   <li><b>经营接口</b>（codes / students / commissions / balance / stats / dashboard /
 *       skus / orders / codes/export）要求档案 status=ACTIVE：
 *       申请是用户自助提交的，PENDING 就能进货、能查码、能提现的话，"平台审批"这一步拦不住任何东西。
 *   <li><b>{@code /me}、{@code /promote}、{@code PUT /profile}</b> 只要求档案存在，四种状态全放行。
 *       {@code /promote}：推广码在建档那一刻就分配了，审核期唯一有用的一页就是它
 *       （但接口要如实说明"这段时间发出去的链接一个人都归属不到"）；
 *       {@code PUT /profile}：PENDING 必须能在审批前改掉填错的卡号，
 *       SUSPENDED/TERMINATED 也可能有已过冻结期的佣金要提现，收款人填不对钱就出不去。
 *   <li>没有档案 → 404，且各处文案一致（前端 Gate 靠这句话画"去申请"的入口）。
 * </ul>
 * <b>这段分档是有意的，不是漏改。</b>把它简化回"除 /me 外全部 ACTIVE"，
 * 审核中的代理点进推广页和资料页只会拿到两句 403，而他什么也没做错。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentService agents;
    private final AgentDashboardService dashboard;
    private final AgentShopService shop;
    private final AgentPromoteService promote;
    private final AgentProfileService profiles;
    private final AgentCommissionDetailService commissionDetail;
    private final AgentCodeExportService codeExport;

    public AgentController(AgentService agents, AgentDashboardService dashboard,
                           AgentShopService shop, AgentPromoteService promote,
                           AgentProfileService profiles,
                           AgentCommissionDetailService commissionDetail,
                           AgentCodeExportService codeExport) {
        this.agents = agents;
        this.dashboard = dashboard;
        this.shop = shop;
        this.promote = promote;
        this.profiles = profiles;
        this.commissionDetail = commissionDetail;
        this.codeExport = codeExport;
    }

    /** 我的代理档案。PENDING 也能看 —— 看不到自己的审核状态，用户只能靠撞 403 来猜。 */
    @GetMapping("/me")
    Map<String, Object> me(HttpServletRequest req) {
        return ok(agents.me(AuthInterceptor.userId(req)));
    }

    /**
     * 我的激活码。<b>只出掩码</b>。
     *
     * @param orderId 可选，按进货单筛。不校验这单是不是我的：底层条件是
     *                owner_agent_id = 我，别人的 orderId 传进来只会得到空列表 ——
     *                这正是想要的，比"这单不属于你"更不容易被拿去做存在性探测
     */
    @GetMapping("/codes")
    Map<String, Object> codes(@RequestParam(required = false) Long orderId,
                              @RequestParam(required = false) String status,
                              @RequestParam(defaultValue = "1") int page,
                              @RequestParam(defaultValue = "20") int size,
                              HttpServletRequest req) {
        return page(agents.codes(AuthInterceptor.userId(req), orderId, status, page, size));
    }

    /**
     * 查看某张码的明文。每一次都写审计日志（CodeService.reveal 里做）。
     *
     * <p>用 POST 而不是 GET：GET 会被浏览器与中间层缓存、会进 access log 的 URL 里，
     * 而这个响应体是一串可以直接拿去激活的码。
     */
    @PostMapping("/codes/{id}/reveal")
    Map<String, Object> reveal(@PathVariable long id, HttpServletRequest req) {
        String plain = agents.reveal(AuthInterceptor.userId(req), id, ClientIp.of(req));
        var out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        out.put("code", plain);
        out.put("notice", "该次查看已记入审计日志。请勿在群内转发完整激活码");
        return out;
    }

    /** 我名下的学员：归属到我的 + 用我的码激活的。手机号一律掩码。 */
    @GetMapping("/students")
    Map<String, Object> students(@RequestParam(defaultValue = "1") int page,
                                 @RequestParam(defaultValue = "20") int size,
                                 HttpServletRequest req) {
        return page(agents.students(AuthInterceptor.userId(req), page, size));
    }

    /**
     * 我的佣金流水。
     *
     * <p>响应里<b>必须带 summary</b>：佣金页顶部那条「冻结中 → 可提现 → 提现中 → 已提现」
     * 的流水线（Commission.tsx:101 <code>&lt;Pipeline s={d.summary}/&gt;</code>）直接读它，
     * 缺了就是 <code>d.summary.settledFen</code> 抛 TypeError → 整页白屏。
     *
     * <p>五个分状态合计取 {@link AgentService#commissionSummary}，与 /balance 的
     * commissionByStatusFen 同一条 SQL —— 不在这里另算一遍。两处各算一遍的表现是
     * 佣金页写「可提现 3200」、提现页写「可提现 2800」，而两个数字各自都"对"。
     */
    @GetMapping("/commissions")
    Map<String, Object> commissions(@RequestParam(required = false) String status,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int size,
                                    HttpServletRequest req) {
        long uid = AuthInterceptor.userId(req);
        var out = page(agents.commissions(uid, status, page, size));
        out.put("summary", agents.commissionSummary(uid));
        return out;
    }

    /** 余额：可用 / 冻结 / 在途提现。withdrawableFen 与提现闸门同一道算式。 */
    @GetMapping("/balance")
    Map<String, Object> balance(HttpServletRequest req) {
        return ok(agents.balance(AuthInterceptor.userId(req)));
    }

    /** 我的统计：进货、动销、库存、成交额。 */
    @GetMapping("/stats")
    Map<String, Object> stats(@RequestParam(required = false) String month,
                              HttpServletRequest req) {
        return ok(agents.stats(AuthInterceptor.userId(req), month));
    }

    // ---------------------------------------------------------------- 工作台

    /**
     * 工作台：四张 KPI 卡 + 待办条。
     *
     * <p>这里的每一个数字都<b>复用</b>已有接口的口径（/stats、/balance、/students），
     * 不在这里另算一遍。两处各算一遍的表现是工作台写 30 个库存、点进去列表只有 18 条，
     * <b>而没有任何报错</b> —— 两个数字各自都"对"，只是来源不同。
     *
     * <p>无参数、无请求体：身份只从令牌来。
     */
    @GetMapping("/dashboard")
    Map<String, Object> dashboard(HttpServletRequest req) {
        return ok(dashboard.dashboard(AuthInterceptor.userId(req)));
    }

    // ---------------------------------------------------------------- 进货

    /**
     * 进货橱窗：我这个层级现在能买什么。
     *
     * <p><b>不出价</b>（2026-09-01）：进货价改成一家一议，代理端界面与出网 JSON 两侧
     * 都不再出现任何进货金额。服务端照常取价、照常把金额写进订单 —— 少的只是讲给代理听的那一份。
     *
     * <p><b>不接受 agentLevel</b>：层级取自令牌解出的档案。收这个参数就等于开放
     * "二级代理看一级价目"。价一律走 PricingService.priceOf，与下单时是同一条路 ——
     * 两处各查一遍价目表的表现是橱窗写 2980、下单扣 3490。
     */
    @GetMapping("/skus")
    Map<String, Object> skus(HttpServletRequest req) {
        return ok(shop.skus(AuthInterceptor.userId(req)));
    }

    /**
     * 我的进货记录。同时是"继续付款"的入口：CREATED 且没过期的单要能被认出来。
     *
     * @param status 可选，只接受订单状态枚举中的值。传别的值 400 而不是查空 ——
     *               查空会被前端读成"我没有订单"，这种错能在线上活很久
     */
    @GetMapping("/orders")
    Map<String, Object> orders(@RequestParam(required = false) String status,
                               @RequestParam(defaultValue = "1") int page,
                               @RequestParam(defaultValue = "20") int size,
                               HttpServletRequest req) {
        return ok(shop.orders(AuthInterceptor.userId(req), status, page, size));
    }

    /**
     * 代理进货建单。请求体<b>只有 skuId</b>，一个金额字段都不收。
     *
     * <p>价格与数量一概不信前端的：签名里只要出现 amount，就一定会有某个版本
     * "为了兼容"把它用起来，然后调用方就能自己定价。真正的取价与写快照
     * 都发生在 OrderService.create 里，那是全链唯一该写价格快照的地方。
     */
    @PostMapping("/orders")
    Map<String, Object> createOrder(@Valid @RequestBody CreateOrderReq body,
                                    HttpServletRequest req) {
        return ok(shop.createOrder(AuthInterceptor.userId(req), body.skuId(), ClientIp.of(req)));
    }

    /** 建单请求。@Positive 挡住 0 与负数：那两种值只会在服务层变成一句"套餐不存在"。 */
    public record CreateOrderReq(
            @NotNull(message = "必须指定要买的套餐") @Positive(message = "套餐编号不合法") Long skuId) {}

    /**
     * 付款页每 3 秒轮询一次的单据状态。
     *
     * <p>响应是<b>扁平</b>的订单行，不包一层 {@code order}：前端是
     * {@code api.get<OrderRow>(...)} 之后直接读 {@code o.status}，
     * 包起来会让轮询永远读到 undefined —— 表现是付款成功后页面一直停在"待支付"，
     * 用户会再付一次。
     *
     * <p>用业务单号而不是自增 id：下单响应给出去的就是单号，
     * 而顺序 id 对外可见等于把当日成交量一起送出去。
     */
    @GetMapping("/orders/{orderNo}")
    Map<String, Object> order(@PathVariable String orderNo, HttpServletRequest req) {
        return ok(shop.order(AuthInterceptor.userId(req), orderNo));
    }

    /**
     * 放弃一张还没付款的进货单。
     *
     * <p>用业务单号而不是自增 id：与 {@link #order} 同口径，顺序 id 对外可见
     * 等于把当日成交量一起送出去。
     */
    @PostMapping("/orders/{orderNo}/cancel")
    Map<String, Object> cancelOrder(@PathVariable String orderNo, HttpServletRequest req) {
        return ok(shop.cancelOrder(AuthInterceptor.userId(req), orderNo));
    }

    // ---------------------------------------------------------------- 推广

    /**
     * 推广页：推广码 + 推广链接。
     *
     * <p>链接必须由后端给：前端拼不出学员端的域名，而<b>拼错的链接会被印在传单上、
     * 生成成二维码发出去，收不回来</b>。所以配置缺失时这里返回 null 并说明为什么，
     * 绝不猜一个看起来像样的域名。
     *
     * <p>四种档案状态全放行（见类注释）。
     */
    @GetMapping("/promote")
    Map<String, Object> promote(HttpServletRequest req) {
        return ok(promote.promote(AuthInterceptor.userId(req), originOf(req)));
    }

    // ---------------------------------------------------------------- 佣金详情

    /**
     * 「这笔佣金是怎么来的」。只读成交那一刻的快照，<b>不按现行规则重算</b>：
     * 改过规则之后两个数字就会对不上，而对不上的那一方永远是页面上显示的那个。
     *
     * <p>"不是我的"与"不存在"回同一句 404 —— 否则这是一个佣金 id 的存在性探针。
     */
    @GetMapping("/commissions/{id}")
    Map<String, Object> commission(@PathVariable long id, HttpServletRequest req) {
        return ok(commissionDetail.detail(AuthInterceptor.userId(req), id));
    }

    // ---------------------------------------------------------------- 资料

    /**
     * 改联系人与结算信息。
     *
     * <p>语义写死：<b>字段缺失或为 null = 不改这一列；给了空字符串 = 把这一列清成 NULL。</b>
     * 前端每次把五个字段全发，所以这条规则决定了"用户清空主体名称"到底生不生效。
     *
     * <p>不允许改 level / status / parent_agent_id / agent_code / subject_type：
     * 前四个是审批结论与分销拓扑，subject_type（个人↔企业）会改变开票与代扣个税口径，
     * 必须走平台审批。列名是代码里的白名单，绝不用请求体的 key 去拼。
     *
     * <p>四种档案状态全放行（见类注释）。
     */
    @PutMapping("/profile")
    Map<String, Object> updateProfile(@RequestBody AgentProfileService.UpdateReq body,
                                      HttpServletRequest req) {
        return ok(profiles.updateProfile(AuthInterceptor.userId(req), body, ClientIp.of(req)));
    }

    // ---------------------------------------------------------------- 批量导码

    /**
     * 批量取激活码明文，导 CSV 发给学员。
     *
     * <p>用 POST 而不是 GET，与单条 reveal 同理：GET 会被浏览器和中间层缓存、
     * 会把参数写进 access log，而这个响应体是一串可以直接拿去激活的码。
     *
     * <p>每一条都与单条查看写<b>同一种</b>审计日志（action='CODE_REVEAL'）：
     * 起个新名字的话，"这串码被谁看过"要查两个 action，而第二个迟早有人忘了查。
     */
    @PostMapping("/codes/export")
    Map<String, Object> exportCodes(@RequestBody(required = false)
                                    AgentCodeExportService.ExportReq body,
                                    HttpServletRequest req) {
        return ok(codeExport.export(AuthInterceptor.userId(req), body, ClientIp.of(req)));
    }

    // ---------------------------------------------------------------- 响应

    /**
     * 本次请求自己的 {@code scheme://host[:port]}。
     *
     * <p>{@code server.forward-headers-strategy=native} 已开，所以 nginx 前置时
     * 这里拿到的是 X-Forwarded-Proto/Host 的值而不是 127.0.0.1:8801。
     * 它只在学员端地址没有配置时作为兜底，且响应里会把来源标成 REQUEST_ORIGIN ——
     * 猜出来的域名必须让调用方知道它是猜的。
     */
    private static String originOf(HttpServletRequest req) {
        String scheme = req.getScheme(), host = req.getServerName();
        if (scheme == null || host == null || host.isBlank()) return null;
        int port = req.getServerPort();
        boolean standard = ("http".equals(scheme) && port == 80)
                        || ("https".equals(scheme) && port == 443);
        return scheme + "://" + host + (standard || port <= 0 ? "" : ":" + port);
    }

    private static Map<String, Object> ok(Map<String, Object> body) {
        var out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        out.putAll(body);
        return out;
    }

    /**
     * 分页响应。
     *
     * <p><b>{@code rows} 与 {@code items} 是同一个 List 实例，两个键都要出。</b>
     * 前端的 {@code Page<T>} 是 <code>{ total; rows }</code>（web/apps/agent/src/api.ts:54），
     * 只出 items 的话，{@code d.rows.length} 会在 <b>render 期</b>抛 TypeError ——
     * 而全仓没有任何 ErrorBoundary（componentDidCatch / getDerivedStateFromError 零命中），
     * React 会卸载整棵树，代理看到的是<b>一片白屏</b>，不是一句错误提示。
     * 工作台、我的学员、我的激活码、佣金流水四页全是这个形状。
     *
     * <p>这条不会被服务层测试挡住：AgentIT 断的是 {@code Page.items()}，
     * 那是控制器<b>出网之前</b>的形状。要挡住它，判据必须落在控制器出的 JSON 键上。
     * 同一轮里 WithdrawController.list 与 AgentShopService 都出了 rows，只有这里漏了。
     */
    private static Map<String, Object> page(AgentService.Page p) {
        var out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        out.put("rows", p.items());
        out.put("items", p.items());
        out.put("page", p.page());
        out.put("size", p.size());
        out.put("total", p.total());
        return out;
    }
}
