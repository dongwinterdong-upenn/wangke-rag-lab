package tech.dragonai.wangke.trade;

import com.alipay.api.AlipayClient;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.internal.util.AlipaySignature;
import org.springframework.mock.web.MockHttpServletRequest;
import tech.dragonai.wangke.ItDb;
import tech.dragonai.wangke.auth.AuthInterceptor;
import tech.dragonai.wangke.config.WangkeProps;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 支付宝相关判据的公共夹具。
 *
 * <h3>为什么自己生成密钥对、自己签一份回调</h3>
 * 验签是这条链上最容易写错、也最不容易被发现写错的一段（错了的表现是
 * "回调偶尔验不过"，而漏掉的那几笔是真金白银）。所以它必须被<b>真的验一遍</b>，
 * 不能靠打桩把 verifyNotify 换成 return true —— 那样测的是桩不是代码。
 *
 * <p>用测试自己生成的 RSA 密钥对：私钥签、把公钥当作"支付宝公钥"配给服务，
 * 于是整条验签路径（待签串构造 → SHA256withRSA → base64）跑的都是官方实现，
 * 且<b>不需要外网、不需要真实商户号</b>。
 *
 * <h3>网关地址故意指向一个必然拒绝连接的端口</h3>
 * page.pay / wap.pay 是"页面接口"，本地签名即可，不发生网络调用 ——
 * 把网关指到 127.0.0.1:1 正好可以证明这一点（能生成表单说明确实没联网）。
 * 而 trade.query / trade.refund 是要联网的，指到这里会<b>立刻</b>连接被拒，
 * 于是"对账失败不能把轮询接口打挂"这条判据可以被真的触发，而不是靠等超时。
 */
final class AlipayTestKit {

    private AlipayTestKit() {}

    static final String APP_ID = "2021000000000001";
    static final String SELLER_ID = "2088000000000001";
    /** 端口 1 上不会有服务，connect 立刻被拒（不是超时），测试因此不会挂住。 */
    static final String GATEWAY = "http://127.0.0.1:1/gateway.do";

    static final String PRIVATE_KEY;
    static final String PUBLIC_KEY;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    static {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            KeyPair kp = g.generateKeyPair();
            // 支付宝 SDK 要的就是这两种编码：私钥 PKCS#8、公钥 X.509，都是无 PEM 头的 base64
            PRIVATE_KEY = Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded());
            PUBLIC_KEY = Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    static WangkeProps props() {
        var p = new WangkeProps();
        var a = new WangkeProps.Alipay();
        a.setAppId(APP_ID);
        a.setPrivateKey(PRIVATE_KEY);
        a.setPublicKey(PUBLIC_KEY);
        a.setGateway(GATEWAY);
        a.setNotifyUrl("https://wangke.example.com/api/pay/notify/alipay");
        a.setReturnUrl("https://wangke.example.com/pay/done");
        p.setAlipay(a);
        return p;
    }

    static AlipayClient client() {
        return new DefaultAlipayClient(GATEWAY, APP_ID, PRIVATE_KEY, "json", "UTF-8",
                PUBLIC_KEY, "RSA2");
    }

    static AlipayService service() {
        return new AlipayService(client(), props(), SELLER_ID);
    }

    // ---------------------------------------------------------------- 回调报文

    /** 一份字段齐全的 TRADE_SUCCESS 回调（未签名）。字段照支付宝真实回调的样子铺满。 */
    static Map<String, String> notifyOf(String outTradeNo, String amountYuan, String tradeNo) {
        String now = LocalDateTime.now().format(TS);
        var m = new LinkedHashMap<String, String>();
        m.put("notify_time", now);
        m.put("notify_type", "trade_status_sync");
        m.put("notify_id", "ac05099524730d7ee05dbb" + Math.abs(tradeNo.hashCode()));
        m.put("app_id", APP_ID);
        m.put("charset", "utf-8");
        m.put("version", "1.0");
        m.put("auth_app_id", APP_ID);
        m.put("trade_no", tradeNo);
        m.put("out_trade_no", outTradeNo);
        m.put("buyer_logon_id", "138****8888");
        m.put("buyer_id", "2088102114562111");
        m.put("seller_id", SELLER_ID);
        m.put("seller_email", "merchant@example.com");
        m.put("trade_status", "TRADE_SUCCESS");
        m.put("total_amount", amountYuan);
        m.put("receipt_amount", amountYuan);
        m.put("buyer_pay_amount", amountYuan);
        m.put("invoice_amount", amountYuan);
        m.put("point_amount", "0.00");
        m.put("gmt_create", now);
        m.put("gmt_payment", now);
        m.put("fund_bill_list", "[{\"amount\":\"" + amountYuan + "\",\"fundChannel\":\"ALIPAYACCOUNT\"}]");
        m.put("subject", "网课平台 · 测试订单");
        return m;
    }

    /**
     * 用测试私钥签一份合法回调。
     *
     * <p>待签串刻意用官方的 {@code getSignContent}（字典序、剔空值），
     * 而验签侧用的是 {@code getSignCheckContentV1}（同一个函数外加剔除 sign / sign_type）。
     * 两边不共享我们自己写的任何拼串代码 —— 否则拼错了两边会一起错，判据恒绿。
     */
    static Map<String, String> sign(Map<String, String> params) {
        var p = new TreeMap<String, String>(params);
        p.remove("sign");
        p.remove("sign_type");
        try {
            String content = AlipaySignature.getSignContent(p);
            p.put("sign", AlipaySignature.rsaSign(content, PRIVATE_KEY, "UTF-8", "RSA2"));
        } catch (Exception e) {
            throw new IllegalStateException("测试签名失败", e);
        }
        p.put("sign_type", "RSA2");
        return p;
    }

    /** 把参数表变成一个到达 /api/pay/notify/alipay 的 POST 请求。 */
    static MockHttpServletRequest notifyRequest(Map<String, String> params) {
        var req = new MockHttpServletRequest("POST", "/api/pay/notify/alipay");
        req.setRemoteAddr("110.75.0.1");                 // 支付宝回调来源网段的样子
        req.setContentType("application/x-www-form-urlencoded;charset=utf-8");
        params.forEach(req::setParameter);
        return req;
    }

    /** 已登录用户发起的请求。属性名与 AuthInterceptor 写进去的一致。 */
    static MockHttpServletRequest asUser(long userId, String role) {
        var req = new MockHttpServletRequest();
        req.setRemoteAddr("203.0.113.7");
        req.setAttribute(AuthInterceptor.ATTR_USER_ID, userId);
        req.setAttribute(AuthInterceptor.ATTR_ROLE, role);
        return req;
    }

    // ---------------------------------------------------------------- 建库

    /**
     * 把本次交付的 DDL 补到测试库上。
     *
     * <p>{@link ItDb#freshDatabase} 只跑 schema.sql + seed.sql，<b>不跑 db/migrations/</b>。
     * 所以在主控把这两条并进 schema.sql 之前，测试库里的枚举还没有 ALIPAY，
     * 第一条 INSERT payment_record 就会以 "Data truncated" 收场 ——
     * 而那个报错完全看不出是枚举缺值。
     *
     * <p>MODIFY COLUMN 给的是目标定义，因此并进 schema.sql 之后这里重跑一遍是空操作：
     * 合并前合并后测试都绿，不需要有人记得回来删这段。
     */
    static void applyPayMigration(String db) throws Exception {
        ItDb.runScript("USE `" + db + "`;\n"
            + "ALTER TABLE `payment_record` "
            + "MODIFY COLUMN `provider` ENUM('WECHAT','BANK_TRANSFER','ALIPAY') NOT NULL;\n"
            + "ALTER TABLE `biz_order` MODIFY COLUMN `pay_channel` "
            + "ENUM('WECHAT_NATIVE','WECHAT_JSAPI','BANK_TRANSFER','ALIPAY_PAGE','ALIPAY_WAP') NULL;\n");
    }
}
