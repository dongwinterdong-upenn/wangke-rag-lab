package tech.dragonai.wangke.trade;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import tech.dragonai.wangke.auth.AuthInterceptor;
import tech.dragonai.wangke.web.ApiException;
import tech.dragonai.wangke.web.ClientIp;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 支付：发起、回调、查单。
 *
 * <h3>这条路径上唯一允许发货的入口是 {@link FulfillService#confirmPaid}</h3>
 * 回调、前端轮询、定时对账三条路都必须汇到它。它的幂等靠
 * {@code payment_record} 的唯一键 {@code (provider, provider_txn_id)} 裁决，
 * 而不是"先查 status 再改"—— 支付宝最多重推 25 次，并且会<b>并发</b>推，
 * 先查后写在压测下必然漏，漏的表现是同一笔钱发两次货（多一个激活码 = 白送一门课，
 * 而且订单金额没变、对账表也平，没有任何异常信号）。
 *
 * <p>所以这个类里<b>没有</b>任何"把订单标成已支付"的 SQL。有的只是：
 * 校验来源、把金额和订单对上、然后交给 confirmPaid。
 */
@Controller
@RequestMapping("/api/pay")
public class PayController {

    private static final Logger log = LoggerFactory.getLogger(PayController.class);

    /**
     * 只用来把 Map&lt;String,String&gt; 的回调原文序列化成 JSON 存证，
     * 不参与出网响应，因此不需要应用级的 Jackson 配置（时区、null 策略都无关）。
     */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final OrderQueryService view;
    private final AlipayService alipay;
    private final FulfillService fulfill;
    private final JdbcTemplate jdbc;

    public PayController(OrderQueryService view, AlipayService alipay,
                         FulfillService fulfill, JdbcTemplate jdbc) {
        this.view = view; this.alipay = alipay; this.fulfill = fulfill; this.jdbc = jdbc;
    }

    // ================================================================ 发起支付

    public record CreateReq(@NotNull @Positive Long orderId, @NotBlank String channel) {}

    /**
     * 创建支付。返回的 form / url 是<b>本地签名生成</b>的，不发生网络调用。
     *
     * <p>订单必须是我的、必须还在待支付、必须没过期，三条都在这里挡住。
     * 尤其是"没过期"：过期订单我们这边会被定时任务置 CLOSED，而 CLOSED 是终态，
     * 到那时支付宝再把钱推过来，货就永远发不出去了，只能人工退款。
     * 把 expire_at 一并传给支付宝的 time_expire，让两边同时关单。
     */
    @PostMapping(value = "/create", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    Map<String, Object> create(@Valid @RequestBody CreateReq r, HttpServletRequest req) {
        long uid = AuthInterceptor.userId(req);
        AlipayService.Channel ch;
        try {
            ch = AlipayService.Channel.valueOf(r.channel().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("未知的支付渠道，只支持 PC 与 WAP");
        }

        var o = view.detail(uid, r.orderId());          // 越权在 SQL 的 WHERE 里，不是这里

        // ★ 代理进货单不走线上收银台。
        //
        // 2026-09-01 起进货价一家一议（agent_price_override），代理端界面与出网 JSON
        // 两侧都不再出现任何进货价。但这个接口原样返回 amountFen / amountYuan，
        // 而 detail() 有意不带 buyer_type（带了代理就付不了款）—— 于是代理拿自己的
        // orderId 打这一条，就把刚刚藏起来的进货价原样问出来了，**整轮改动被这一个接口绕开**。
        //
        // 拒绝而不是"只把金额抹掉"：真实业务口径已经变成线下对公转账（下单 → 平台核价告知
        // → 转账 → 超管确认到账出码），线上收银台对进货单本来就不该存在。留着一个
        // 能发起支付、金额却对不上谈定价的入口，比没有这个入口危险得多。
        if ("AGENT".equals(o.buyerType()))
            throw ApiException.conflict("代理进货请走线下对公转账：平台会核出金额并告知，"
                    + "到账后由平台确认，激活码随即进入「我的激活码」");

        if (!"CREATED".equals(o.status()))
            throw ApiException.conflict(switch (o.status()) {
                case "PAID", "FULFILLED" -> "订单已支付，请勿重复付款";
                case "CLOSED" -> "订单已取消或已过期，请重新下单";
                default -> "订单当前状态不可支付";
            });
        // 过期判据来自数据库（OrderQueryService 里那条 TIMESTAMPDIFF），不在这里比 JVM 时间。
        // 写 expire_at 的是数据库的时钟，拿 JVM 的时钟去比它，等于要求
        // MySQL 服务器时区、连接串声明的 serverTimezone、JVM 默认时区三者同时正确 ——
        // 只要有一处不一致，刚下的单就会立刻显示"已超过支付时限"，且不报任何错。
        if (o.expired())
            throw ApiException.conflict("订单已超过支付时限，请重新下单");

        String subject = o.skuName() == null || o.skuName().isBlank() ? o.skuCode() : o.skuName();
        var pay = alipay.createPay(ch, o.orderNo(), o.amountFen(), subject, o.expireInSeconds());

        // 记下这次选的收银台。<b>PC 与 WAP 的分别只有这一步知道</b> ——
        // 回调报文里没有这个信息，FulfillService 更拿不到。不记的话，
        // "WAP 收银台在某个 App 的 webview 里跳不回来"这类问题就没法按渠道切分去查，
        // 而那正是最需要切分的时候。
        //
        // WHERE 里带 status='CREATED' 与 buyer_user_id：这条 UPDATE 绝不能碰到一个
        // 在这几毫秒里刚刚到账的订单 —— 那时它已经是 PAID，改它的渠道就是改账。
        jdbc.update("UPDATE biz_order SET pay_channel=? "
                  + "WHERE id=? AND buyer_user_id=? AND status='CREATED'",
                "PC".equals(pay.channel()) ? "ALIPAY_PAGE" : "ALIPAY_WAP", o.id(), uid);

        // 留痕。争议处理时要能说清"这笔支付是谁、在什么时候、从哪个 IP 发起的"。
        // 没有这条记录，"我没点过支付"这类纠纷里平台是没有立场的一方。
        audit(uid, "PAY_CREATE", o.orderNo(),
              Map.of("channel", pay.channel(), "amountFen", String.valueOf(o.amountFen())),
              ClientIp.of(req));

        var m = new LinkedHashMap<String, Object>();
        m.put("ok", true);
        m.put("orderNo", o.orderNo());
        m.put("amountFen", o.amountFen());
        m.put("amountYuan", OrderQueryService.yuan(o.amountFen()));
        m.put("channel", pay.channel());
        m.put("form", pay.form());
        m.put("payUrl", pay.payUrl());
        m.put("expireAt", o.expireAt());
        m.put("expireInSeconds", o.expireInSeconds());   // 收银台倒计时用这个，理由见 OrderQueryService.view
        return m;
    }

    // ================================================================ 异步回调

    /**
     * 支付宝异步回调。<b>public：来源可信性由验签保证，不靠登录态。</b>
     * （白名单在 AuthInterceptor 的 {@code /api/pay/notify/} 前缀上。）
     *
     * <p>返回的是<b>纯文本</b> "success" / "failure"，不是 JSON。
     * 支付宝只认这两个字面量；回一个 {"ok":true} 会被判定为失败，
     * 于是它按 25 次的节奏重推同一笔 —— 我们这边货已经发了，日志被刷爆，
     * 而且没有任何一处会报错。
     *
     * <p><b>success 与 failure 的分界不是"处理成功与否"，是"再推一次有没有用"。</b>
     * <ul>
     *   <li>验签 / app_id / seller_id / 金额不符 → failure。重推同样会失败，
     *       但保留失败信号是有意的：这几种要么是攻击，要么是配置错，必须持续可见。
     *   <li>trade_status 不是终态（WAIT_BUYER_PAY、TRADE_CLOSED）→ success。
     *       这是正常通知，忽略即可；回 failure 会招来 25 次无意义重推。
     *   <li>订单不存在 / 订单已终态 → success，但同时写一条审计并打 ERROR。
     *       重推不会让订单长出来，也不会让终态订单变回可发货；
     *       这里需要的是一条<b>不会丢的人工待办</b>，不是无限重试。
     * </ul>
     */
    @PostMapping(value = "/notify/alipay", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    @ResponseBody
    public String notifyAlipay(HttpServletRequest req) {
        Map<String, String> params = flatten(req);
        String outTradeNo = params.get("out_trade_no");
        try {
            AlipayService.Notify n;
            try {
                n = alipay.verifyNotify(params);
            } catch (AlipayService.NotifyRejected e) {
                // 刻意不写 audit_log：验签没过的东西不能当成"一条支付事件"记账，
                // 否则任何人都能往我们的审计表里灌行（还带着他自己编的 out_trade_no）。
                // 日志足够定位，且日志不是业务数据。
                log.warn("支付宝回调被拒 reason={} out_trade_no={} remote={}",
                         e.reason(), outTradeNo, ClientIp.of(req));
                return "failure";
            }

            if (!AlipayService.isPaid(n.tradeStatus())) {
                log.info("支付宝回调非终态，忽略 out_trade_no={} trade_status={}",
                         n.outTradeNo(), n.tradeStatus());
                return "success";
            }

            var o = view.byOrderNoForNotify(n.outTradeNo());
            if (o == null) {
                // 钱到了，单不见了。验签已经过了，所以这确实是打给我们的一笔真钱。
                log.error("支付宝回调找不到对应订单：out_trade_no={} trade_no={} 金额(分)={}",
                          n.outTradeNo(), n.tradeNo(), n.totalAmountFen());
                audit(null, "PAY_NOTIFY_ORPHAN", n.outTradeNo(), params, ClientIp.of(req));
                return "success";
            }

            // 金额必须与订单应付逐笔核对。只验签不比金额，等于允许任何人用一笔
            // 0.01 元的【真实】支付去解锁一个 3490 元的订单 —— 那笔支付的签名是真的，
            // app_id、seller_id 也都是真的，唯一对不上的就是金额。
            if (n.totalAmountFen() != o.amountFen()) {
                log.error("支付宝回调金额与订单不符 out_trade_no={} 回调(分)={} 应付(分)={}",
                          n.outTradeNo(), n.totalAmountFen(), o.amountFen());
                audit(null, "PAY_NOTIFY_AMOUNT_MISMATCH", n.outTradeNo(), params, ClientIp.of(req));
                return "failure";
            }

            // 只有这三个状态下，一笔到账的钱才该被自动发货：
            // 待支付（正常）、已支付 / 已发货（重推，交给 confirmPaid 幂等吞掉）。
            //
            // 这里<b>用白名单而不是"排除 CLOSED 和 REFUNDED"</b>。黑名单的问题在于
            // 它对没想到的状态默认放行：PARTIAL_REFUNDED 就是漏网的那一个 ——
            // trg_order_state 并不禁止 PARTIAL_REFUNDED → FULFILLED，
            // 于是一笔迟到的回调会把一个已经退过款的订单<b>悄悄改回已发货</b>，
            // 退款记录还在、订单状态却翻了，对账两边都说自己没错。
            // 以后再往这个枚举里加值时，白名单会让新状态默认走人工，而不是默认发货。
            if (!"CREATED".equals(o.status()) && !"PAID".equals(o.status())
                    && !"FULFILLED".equals(o.status())) {
                log.error("支付宝回调命中不可发货的订单状态，钱已收但不能发货，需人工处理："
                        + "out_trade_no={} status={} trade_no={}",
                          n.outTradeNo(), o.status(), n.tradeNo());
                audit(null, "PAY_NOTIFY_ORPHAN", n.outTradeNo(), params, ClientIp.of(req));
                return "success";
            }

            // 唯一的发货入口。幂等与金额复核都在它里面，重推 25 次也只发一次货。
            var res = fulfill.confirmPaid(o.id(), "ALIPAY", n.tradeNo(),
                                          n.totalAmountFen(), n.paidAt(), null);
            if (res.firstTime()) {
                log.info("支付到账并已交付 orderNo={} 出码={} 佣金(分)={}",
                         o.orderNo(), res.codesIssued(), res.commissionFen());
                savePayload(n.tradeNo(), params);
            } else {
                log.info("支付宝重复回调，已忽略 orderNo={} trade_no={}", o.orderNo(), n.tradeNo());
            }
            return "success";

        } catch (RuntimeException e) {
            // 走到这里说明是我们这边的故障（库连不上、锁超时、代码 bug）。
            // 这类是【瞬时的】，回 failure 让支付宝重推才是对的 —— 下一次可能就成了。
            // 反过来回 success，这笔钱就永远没有第二次机会被入账。
            log.error("支付宝回调处理异常 out_trade_no={}", outTradeNo, e);
            return "failure";
        }
    }

    /**
     * 把回调原文存进 {@code payment_record.raw_payload}。
     *
     * <p>放在 confirmPaid 之后、且失败不影响返回值：钱已经入账、货已经发了，
     * 为了一份存证把整笔支付判成失败并触发重推，是拿真实交易去换一条日志。
     * 存不下来只是少了份证据，重推却会让整条链重跑。
     */
    private void savePayload(String tradeNo, Map<String, String> params) {
        try {
            jdbc.update("UPDATE payment_record SET raw_payload=? "
                      + "WHERE provider='ALIPAY' AND provider_txn_id=?",
                    JSON.writeValueAsString(params), tradeNo);
        } catch (Exception e) {
            log.warn("回调原文存证失败 trade_no={}", tradeNo, e);
        }
    }

    /**
     * 把 servlet 的参数表拍平成验签要的 Map。
     *
     * <p>同名参数只取第一个，不做 join。支付宝不会发重名参数，出现重名就是有人
     * 在中间加料；把两份值拼起来的话验签当然会失败，但故障会表现成
     * "偶发验签不通过"而不是"参数被污染"，排查方向会整个跑偏。
     */
    static Map<String, String> flatten(HttpServletRequest req) {
        Map<String, String> m = new HashMap<>();
        for (var e : req.getParameterMap().entrySet()) {
            String[] v = e.getValue();
            if (v == null || v.length == 0) continue;
            m.put(e.getKey(), v[0]);
        }
        return m;
    }

    // ================================================================ 前端轮询查单

    /**
     * 前端轮询。<b>要登录，且只能查自己的单</b>（越权条件在 SQL 的 WHERE 里）。
     *
     * <p>本地还是待支付时，顺手主动查一次支付宝。<b>只依赖回调是不够的</b>：
     * 我们这边 502、发版重启、网络抖动，都会让某几笔的回调彻底丢失，
     * 故障形态是"用户付了钱，订单永远显示待支付"，而平台侧毫无信号 ——
     * 账是平的，只有客服工单能发现。
     *
     * <p>主动查单失败<b>不影响本接口返回</b>：对账是尽力而为的补救，
     * 让它把一个正常的轮询接口打成 500，等于用一个补救措施制造了新的故障。
     */
    @GetMapping(value = "/status/{orderNo}", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    Map<String, Object> status(@PathVariable String orderNo, HttpServletRequest req) {
        long uid = AuthInterceptor.userId(req);
        var o = view.byOrderNo(uid, orderNo);
        if ("CREATED".equals(o.status())) {
            reconcile(o);
            o = view.byOrderNo(uid, orderNo);           // 对上账了就应当立刻反映出来
        }
        var m = new LinkedHashMap<String, Object>();
        m.put("ok", true);
        // paid 是给前端停止轮询用的单一判据。让前端自己去 switch 状态字符串的话，
        // 迟早会漏掉 PARTIAL_REFUNDED 这类，表现是收银台一直转圈。
        m.put("paid", !"CREATED".equals(o.status()) && !"CLOSED".equals(o.status()));
        m.put("order", OrderQueryService.view(o));
        return m;
    }

    private void reconcile(OrderQueryService.OrderRow o) {
        try {
            var t = alipay.query(o.orderNo());
            if (t == null || !AlipayService.isPaid(t.tradeStatus())) return;
            if (t.totalAmountFen() != o.amountFen()) {
                // 和回调走同一条判据。对账这条路上放宽金额校验是很自然的疏忽
                // （"回调那边已经比过了"），但这条路根本没经过回调。
                log.error("主动查单金额与订单不符 orderNo={} 查得(分)={} 应付(分)={}",
                          o.orderNo(), t.totalAmountFen(), o.amountFen());
                return;
            }
            var res = fulfill.confirmPaid(o.id(), "ALIPAY", t.tradeNo(),
                                          t.totalAmountFen(), t.paidAt(), null);
            if (res.firstTime())
                log.warn("回调丢失，由主动查单补上 orderNo={} trade_no={}", o.orderNo(), t.tradeNo());
        } catch (RuntimeException e) {
            log.error("主动查单失败，本次轮询按本地状态返回 orderNo={}", o.orderNo(), e);
        }
    }

    // ================================================================ 审计

    /**
     * @param operatorId 回调路径没有登录态，传 null；audit_log.operator_id 可空正是为此
     * @param payload 存进 after_json。回调原文里没有支付密码之类的秘密，
     *        但它有买家账号（脱敏后的手机号/邮箱）—— 所以只进内部审计表，绝不出网
     */
    private void audit(Long operatorId, String action, String targetId,
                       Map<String, String> payload, String ip) {
        try {
            jdbc.update("INSERT INTO audit_log (operator_id,action,target_type,target_id,"
                      + "after_json,client_ip) VALUES (?,?,'biz_order',?,?,?)",
                    operatorId, action, targetId, JSON.writeValueAsString(payload), ip);
        } catch (Exception e) {
            // 审计写失败不能反过来打断业务：支付已经完成的情况下，
            // 让一条日志把整笔交易回滚，是用次要目标伤主要目标。
            log.error("审计写入失败 action={} target={}", action, targetId, e);
        }
    }
}
