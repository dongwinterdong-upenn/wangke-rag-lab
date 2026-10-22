package tech.dragonai.wangke.trade;

import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.domain.AlipayTradePagePayModel;
import com.alipay.api.domain.AlipayTradeQueryModel;
import com.alipay.api.domain.AlipayTradeRefundModel;
import com.alipay.api.domain.AlipayTradeWapPayModel;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.api.request.AlipayTradePagePayRequest;
import com.alipay.api.request.AlipayTradeQueryRequest;
import com.alipay.api.request.AlipayTradeRefundRequest;
import com.alipay.api.request.AlipayTradeWapPayRequest;
import com.alipay.api.response.AlipayTradeQueryResponse;
import com.alipay.api.response.AlipayTradeRefundResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tech.dragonai.wangke.config.WangkeProps;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 支付宝网关的全部出入口：发起支付（PC / WAP）、主动查单、退款、异步回调验签。
 *
 * <p>这一层<b>只跟支付宝说话，不碰订单库</b>。金额与订单的比对留给调用方
 * （{@link PayController}），因为那需要读订单快照，而"要不要放行这笔钱"是业务判断，
 * 不是协议解析。两件事混在一起时，最容易出现的写法是"验完签顺手把订单标成已付"，
 * 于是金额比对就没人做了 —— 那正是拿一笔 0.01 元的真实支付解锁 3490 元订单的口子。
 *
 * <h3>为什么验签必须用官方 AlipaySignature.rsaCheckV1</h3>
 * 待签串的构造有一串细节：按 key 字典序、剔除 sign 与 sign_type、
 * <b>剔除空值</b>、不做 URL 编码、用回调声明的 charset 取字节。
 * 自己拼这一串，任何一处写错的表现都是"回调偶尔验不过" —— 偶尔，
 * 因为只有当那个字段恰好出现（比如买家备注为空、优惠金额字段缺席）时才错。
 * 而验不过的那几笔是真金白银，且会被当成"支付宝抽风"放过去。
 *
 * <h3>为什么还要有主动查单</h3>
 * 回调是支付宝<b>尽力而为</b>的推送：我们这边 502、网络抖动、进程重启，
 * 都会让某几笔的回调彻底丢失。只依赖回调的系统，故障形态是
 * "用户付了钱，订单一直显示待支付"，而且平台侧毫无信号 —— 账是平的，
 * 只有客服工单能发现。所以前端轮询与定时对账都要能自己去问一次。
 */
@Service
public class AlipayService {

    private static final Logger log = LoggerFactory.getLogger(AlipayService.class);

    /**
     * 只有这两个状态代表钱真的到我们账上。
     *
     * <p>TRADE_FINISHED 是"不可退款的完结"（超过退款期或已全额退款后的终态），
     * 对已经发过货的订单它只是一个更晚的通知；漏掉它的后果是：
     * 一笔在退款期内没收到 TRADE_SUCCESS 的交易（极少见但存在），
     * 最终只推 TRADE_FINISHED，于是我们永远不发货。
     */
    private static final Set<String> PAID_STATUS = Set.of("TRADE_SUCCESS", "TRADE_FINISHED");

    /** 支付宝的时间字段格式，全网关统一。 */
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AlipayClient client;
    private final String appId;
    private final String alipayPublicKey;
    private final String sellerId;
    private final String notifyUrl;
    private final String returnUrl;

    /**
     * @param sellerId 我们自己的商户 PID（2088 开头）。<b>没有它就没法做回调的收款方校验。</b>
     *        用 {@code @Value} 而不是 WangkeProps 上的字段，是因为这一项是随支付宝接入
     *        一起补进来的，先让它可配、可校验；配置项本身请见 sharedEdits。
     */
    public AlipayService(AlipayClient client, WangkeProps props,
                         @Value("${wangke.alipay.seller-id:}") String sellerId) {
        this.client = client;
        var a = props.getAlipay();
        // 和 AppConfig 同一条纪律：凭据缺失就别起来。
        // 这里尤其是 seller-id —— 它只在【回调到达时】才被用到，
        // 留到运行期才发现为空，意味着第一笔真实支付的回调会被我们自己拒掉，
        // 而用户那边显示已付款。宁可启动失败。
        this.appId = require("wangke.alipay.app-id", a.getAppId());
        this.alipayPublicKey = require("wangke.alipay.public-key", a.getPublicKey());
        this.notifyUrl = require("wangke.alipay.notify-url", a.getNotifyUrl());
        this.sellerId = require("wangke.alipay.seller-id", sellerId);
        this.returnUrl = a.getReturnUrl() == null ? "" : a.getReturnUrl().trim();
    }

    private static String require(String key, String v) {
        if (v == null || v.isBlank())
            throw new IllegalStateException(
                    "配置项 " + key + " 为空。拒绝以缺失凭据启动 —— "
                  + "它只在真实回调到达时才被用到，那时失败的是一笔已经收到的钱");
        return v.trim();
    }

    // ================================================================ 发起支付

    public enum Channel { PC, WAP }

    /**
     * @param form  自动提交的 HTML 表单，前端直接 document.write 即可跳转
     * @param payUrl 同一笔支付的 GET 跳转地址，给不方便写表单的客户端（小程序 webview、App）
     */
    public record PayForm(String channel, String form, String payUrl) {}

    /**
     * 收银台的时区。支付宝的 {@code time_expire} 收的是<b>北京时间的墙上时间字符串</b>，
     * 报文里没有任何地方能声明时区，所以格式化时必须显式指定，不能用 JVM 默认时区。
     *
     * <p>用默认时区的后果：服务部署在 UTC 机器上（本仓的测试机、绝大多数云主机默认就是）
     * 时，算出来的关单时刻比真实意图早 8 小时，于是用户点开收银台看到的是"交易已关闭"，
     * 而我们这边订单还老老实实是待支付。没有任何一侧报错，日志里也只有一次正常的下单。
     */
    private static final java.time.ZoneId BEIJING = java.time.ZoneId.of("Asia/Shanghai");

    /**
     * 发起支付。<b>本地签名，不发生网络调用</b>（page/wap 两个接口都是"页面接口"），
     * 所以这条路径在没有外网的环境里也能被完整测到。
     *
     * @param expireInSeconds 距关单还有多少秒，由数据库算出（写 expire_at 的就是它的时钟）。
     *        这里收<b>秒数而不是时刻</b>：时刻要跨"数据库时区 → JDBC 声明时区 → JVM 时区
     *        → 北京时间"四道换算才能变成报文里那个字符串，中间任何一道拧错都不会报错，
     *        只会让关单时间整体平移。秒数是差值，只经过一道换算（当下的北京时间 + 秒数）。
     *        <b>必须传</b>：不传 time_expire 的话支付宝按自己的默认时长关单，
     *        于是会出现"我们这边已 CLOSED、支付宝那边还能付"的窗口 ——
     *        用户付了钱，订单却已经关了，钱收了货发不出去，只能人工退。
     */
    public PayForm createPay(Channel channel, String orderNo, long amountFen,
                             String subject, long expireInSeconds) {
        String amountYuan = fenToYuan(amountFen);
        if (expireInSeconds <= 0)
            throw new IllegalStateException("订单已过关单时刻，不应再发起支付");
        String expire = java.time.ZonedDateTime.now(BEIJING).plusSeconds(expireInSeconds).format(TS);
        try {
            if (channel == Channel.PC) {
                var m = new AlipayTradePagePayModel();
                m.setOutTradeNo(orderNo);
                m.setTotalAmount(amountYuan);
                m.setSubject(subject);
                m.setProductCode("FAST_INSTANT_TRADE_PAY");
                m.setTimeExpire(expire);
                var req = new AlipayTradePagePayRequest();
                req.setBizModel(m);
                req.setNotifyUrl(notifyUrl);
                if (!returnUrl.isEmpty()) req.setReturnUrl(returnUrl);
                return new PayForm("PC",
                        client.pageExecute(req).getBody(),
                        client.pageExecute(req, "GET").getBody());
            }
            var m = new AlipayTradeWapPayModel();
            m.setOutTradeNo(orderNo);
            m.setTotalAmount(amountYuan);
            m.setSubject(subject);
            m.setProductCode("QUICK_WAP_WAY");
            m.setTimeExpire(expire);
            // quit_url 是用户在收银台点"返回"时的落点。不设的话手机浏览器会退回空白页，
            // 用户以为下单失败于是再下一单 —— 重复订单大多是这么来的。
            if (!returnUrl.isEmpty()) m.setQuitUrl(returnUrl);
            var req = new AlipayTradeWapPayRequest();
            req.setBizModel(m);
            req.setNotifyUrl(notifyUrl);
            if (!returnUrl.isEmpty()) req.setReturnUrl(returnUrl);
            return new PayForm("WAP",
                    client.pageExecute(req).getBody(),
                    client.pageExecute(req, "GET").getBody());
        } catch (AlipayApiException e) {
            // 这里失败只可能是签名/参数问题（没有网络调用），属于我们自己的配置错。
            // 把支付宝的原文吞进日志，对外只给人话 —— 网关报文里有 appId 与密钥线索。
            log.error("生成支付宝支付参数失败 orderNo={}", orderNo, e);
            throw new IllegalStateException("发起支付失败，请稍后重试");
        }
    }

    // ================================================================ 异步回调

    public enum Reject { BAD_SIGN, BAD_APP_ID, BAD_SELLER_ID, MALFORMED }

    /** 回调没通过协议层校验。带上原因，便于把"被攻击"和"配置错"分开看。 */
    public static class NotifyRejected extends RuntimeException {
        private final Reject reason;
        public NotifyRejected(Reject reason, String message) { super(message); this.reason = reason; }
        public Reject reason() { return reason; }
    }

    /**
     * @param totalAmountFen 已按"元→分"换算好，用的是 {@link PricingService#yuanToFen(String)}，
     *        三位小数会被拒而不是四舍五入
     */
    public record Notify(String appId, String sellerId, String outTradeNo, String tradeNo,
                         String tradeStatus, long totalAmountFen, LocalDateTime paidAt) {}

    /**
     * 验签 + 协议层校验。通过返回解析结果，不通过抛 {@link NotifyRejected}。
     *
     * <p><b>app_id 与 seller_id 都要校验，缺一不可。</b>
     * 常见的"只验签就放行"之所以危险，是因为在<b>支付宝公钥模式</b>下，
     * 那把公钥是平台级的、所有商户共用一把 —— 于是任何一笔发给<b>别人</b>的、
     * 真实的支付宝通知，只要被转发到我们的 notify_url，验签都能通过。
     * 校验 app_id 挡住"不是发给我们这个应用的"，校验 seller_id 挡住
     * "收款方不是我们"（同一应用可以配多个收款账户，也可能被服务商代运营）。
     */
    public Notify verifyNotify(Map<String, String> params) {
        if (params == null || params.isEmpty())
            throw new NotifyRejected(Reject.MALFORMED, "回调参数为空");

        // rsaCheckV1 会【就地】从传进去的 map 里删掉 sign 与 sign_type。
        // 直接把原 map 递进去，回调原文就在验签这一步被悄悄改了形状 ——
        // 后面再想把完整报文存进 payment_record.raw_payload 当证据，存下来的
        // 已经是缺了签名的半份，而这不会报任何错。所以传副本。
        Map<String, String> forCheck = new HashMap<>(params);
        boolean ok;
        try {
            ok = AlipaySignature.rsaCheckV1(forCheck, alipayPublicKey, "UTF-8", "RSA2");
        } catch (AlipayApiException e) {
            // 公钥格式不对、签名不是合法 base64 之类，都会走到这里。
            // 与"验签结果为 false"同等对待：都不能放行。
            log.warn("支付宝回调验签过程异常 out_trade_no={}", params.get("out_trade_no"), e);
            throw new NotifyRejected(Reject.BAD_SIGN, "验签失败");
        }
        if (!ok) throw new NotifyRejected(Reject.BAD_SIGN, "验签失败");

        // 报文声明了非 UTF-8（支付宝商户后台可以配成 GBK）时，验签其实已经不可靠了：
        // servlet 容器是按 UTF-8 解出这些参数的，GBK 的字节被 UTF-8 解码后是不可逆的乱码，
        // 到这一步再换 charset 参数也救不回来。真出现时的表现是"带中文的单验不过、
        // 纯数字的单没事"，看起来完全像支付宝抽风。这里能做的是把它变成一条可查的信号，
        // 而不是让它以偶发验签失败的形式存在。
        String charset = params.get("charset");
        if (charset != null && !charset.isBlank() && !charset.trim().equalsIgnoreCase("utf-8"))
            log.warn("支付宝回调声明的字符集是 {}，而本服务按 UTF-8 解参 —— "
                   + "请把商户后台的接口编码改成 UTF-8，否则含中文的报文会偶发验签失败", charset);

        String gotApp = params.get("app_id");
        if (!appId.equals(gotApp))
            throw new NotifyRejected(Reject.BAD_APP_ID, "回调的 app_id 与本应用不符");

        String gotSeller = params.get("seller_id");
        if (!sellerId.equals(gotSeller))
            throw new NotifyRejected(Reject.BAD_SELLER_ID, "回调的收款方与本商户不符");

        String outTradeNo = params.get("out_trade_no");
        String tradeNo = params.get("trade_no");
        String tradeStatus = params.get("trade_status");
        if (outTradeNo == null || outTradeNo.isBlank())
            throw new NotifyRejected(Reject.MALFORMED, "回调缺少商户订单号");
        if (tradeNo == null || tradeNo.isBlank())
            throw new NotifyRejected(Reject.MALFORMED, "回调缺少支付宝交易号");
        if (tradeStatus == null || tradeStatus.isBlank())
            throw new NotifyRejected(Reject.MALFORMED, "回调缺少交易状态");

        // 未到账的通知（WAIT_BUYER_PAY / TRADE_CLOSED）不带 total_amount 的情况是有的，
        // 而这类通知本来就要被忽略，所以金额缺失在这里不算错，交给上层按状态处理。
        long fen = 0;
        String amt = params.get("total_amount");
        if (amt != null && !amt.isBlank()) {
            try {
                // 必须走 yuanToFen：它对三位及以上小数直接报错。
                // 用 double 解析 '0.145' 再乘 100 得到的是 14（少收 0.5 分），
                // 而且不会有任何日志 —— schema 头部专门记了这个盲区。
                fen = PricingService.yuanToFen(amt);
            } catch (IllegalArgumentException e) {
                throw new NotifyRejected(Reject.MALFORMED, "回调金额格式异常");
            }
        } else if (isPaid(tradeStatus)) {
            throw new NotifyRejected(Reject.MALFORMED, "已支付回调却没有金额");
        }

        return new Notify(gotApp, gotSeller, outTradeNo, tradeNo, tradeStatus, fen,
                parseTime(params.get("gmt_payment"), params.get("notify_time")));
    }

    /** 钱是否真的到账。除这两个状态外一律不发货。 */
    public static boolean isPaid(String tradeStatus) { return PAID_STATUS.contains(tradeStatus); }

    /**
     * 取付款时刻。优先 gmt_payment（真实付款时间），退而求其次用 notify_time。
     *
     * <p>两个都没有时用当前时间：paid_at 是 trg_order_state 强制要求的字段，
     * 缺了整笔支付会被数据库拒掉 —— 为了一个时间戳丢一笔已收到的钱不划算。
     * 差几秒的付款时间影响的是对账精度，不影响钱的归属。
     *
     * <p>兜底那一步取的是<b>北京时间</b>的当下，不是 {@code LocalDateTime.now()}。
     * 回调里那两个字段本来就是北京时间的墙上时间，兜底用 JVM 默认时区的话，
     * 部署在 UTC 机器上时同一列里会同时存在两种含义的时间戳（大部分是北京、
     * 少数兜底的是 UTC），差 8 小时。对账时这批记录会莫名其妙地归到前一天。
     */
    private static LocalDateTime parseTime(String gmtPayment, String notifyTime) {
        for (String s : new String[]{gmtPayment, notifyTime}) {
            if (s == null || s.isBlank()) continue;
            try { return LocalDateTime.parse(s.trim(), TS); }
            catch (RuntimeException ignored) { /* 换下一个候选 */ }
        }
        return LocalDateTime.now(BEIJING);
    }

    // ================================================================ 主动查单 / 退款

    /** 网关够不着（网络、超时、5xx）。与"交易不存在"是两回事，必须分开处理。 */
    public static class GatewayUnavailable extends RuntimeException {
        public GatewayUnavailable(String m, Throwable c) { super(m, c); }
    }

    public record Trade(String outTradeNo, String tradeNo, String tradeStatus,
                        long totalAmountFen, LocalDateTime paidAt) {}

    /**
     * 主动查单（对账用）。
     *
     * @return 交易不存在（用户从没扫过码）返回 null；网关故障抛 {@link GatewayUnavailable}
     *
     * <p>这两种情况必须分开：把"网关超时"当成"交易不存在"，对账任务就会把
     * 一批已支付订单判成未支付并关单 —— 那是最坏的一种批量事故，钱收了、单关了。
     */
    public Trade query(String orderNo) {
        var m = new AlipayTradeQueryModel();
        m.setOutTradeNo(orderNo);
        var req = new AlipayTradeQueryRequest();
        req.setBizModel(m);
        AlipayTradeQueryResponse resp;
        try {
            resp = client.execute(req);
        } catch (AlipayApiException e) {
            throw new GatewayUnavailable("支付宝查单失败：" + orderNo, e);
        }
        if (!resp.isSuccess()) {
            // ACQ.TRADE_NOT_EXIST 是"这笔单支付宝那边没有"，属于正常结果不是故障。
            if ("ACQ.TRADE_NOT_EXIST".equals(resp.getSubCode())) return null;
            throw new GatewayUnavailable(
                    "支付宝查单返回失败 orderNo=" + orderNo + " code=" + resp.getCode()
                  + " subCode=" + resp.getSubCode(), null);
        }
        long fen = 0;
        if (resp.getTotalAmount() != null && !resp.getTotalAmount().isBlank())
            fen = PricingService.yuanToFen(resp.getTotalAmount());
        // 同 parseTime：付款时刻统一落成北京时间的墙上时间。
        // SDK 把 send_pay_date 解析成 java.util.Date（一个瞬间），换算回墙上时间时
        // 用 systemDefault() 会让 UTC 机器上的这一列比回调那条路少 8 小时 ——
        // 同一个订单，回调补的和查单补的时间不一样，而两条路都不报错。
        LocalDateTime paidAt = resp.getSendPayDate() == null
                ? LocalDateTime.now(BEIJING)
                : LocalDateTime.ofInstant(resp.getSendPayDate().toInstant(), BEIJING);
        return new Trade(resp.getOutTradeNo(), resp.getTradeNo(), resp.getTradeStatus(), fen, paidAt);
    }

    public record Refunded(boolean success, String tradeNo, long refundedFen,
                           String code, String subCode) {}

    /**
     * 退款。
     *
     * @param outRequestNo 退款请求号。<b>同一笔订单多次部分退款时必须每次不同</b>，
     *        重复使用同一个 out_request_no 支付宝会当成同一次退款直接返回成功而
     *        <b>不再打款</b> —— 表现是"系统显示退款成功，用户没收到钱"。
     *        对我们而言这个值就是 refund_record.refund_no，天然唯一。
     *        反过来，重试同一笔退款时<b>必须复用</b>同一个号，否则就退了两次。
     */
    public Refunded refund(String orderNo, long amountFen, String outRequestNo, String reason) {
        var m = new AlipayTradeRefundModel();
        m.setOutTradeNo(orderNo);
        m.setRefundAmount(fenToYuan(amountFen));
        m.setOutRequestNo(outRequestNo);
        if (reason != null && !reason.isBlank()) m.setRefundReason(reason.trim());
        var req = new AlipayTradeRefundRequest();
        req.setBizModel(m);
        AlipayTradeRefundResponse resp;
        try {
            resp = client.execute(req);
        } catch (AlipayApiException e) {
            // 超时不等于没退成功：网关侧可能已经受理。所以这里只能抛"不确定"，
            // 绝不能把它当成失败去改 refund_record 的状态 —— 那会让人再退一次。
            throw new GatewayUnavailable("支付宝退款结果未知：" + orderNo + "/" + outRequestNo, e);
        }
        if (!resp.isSuccess())
            return new Refunded(false, resp.getTradeNo(), 0, resp.getCode(), resp.getSubCode());
        long fen = resp.getRefundFee() == null || resp.getRefundFee().isBlank()
                ? amountFen : PricingService.yuanToFen(resp.getRefundFee());
        return new Refunded(true, resp.getTradeNo(), fen, resp.getCode(), resp.getSubCode());
    }

    // ================================================================ 金额

    /**
     * 分 → 元，两位小数字符串。
     *
     * <p>反向的元→分走 {@link PricingService#yuanToFen(String)}。两个方向都不许出现
     * double：{@code 349000 / 100.0} 在别的金额上会给出 3489.9999999999995，
     * 拼成字符串发给支付宝就是一笔对不上的单，而本地断言全绿。
     */
    static String fenToYuan(long fen) {
        if (fen <= 0) throw new IllegalArgumentException("金额必须为正数（分）：" + fen);
        return BigDecimal.valueOf(fen).movePointLeft(2)
                .setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }
}
