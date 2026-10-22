package tech.dragonai.wangke.trade;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tech.dragonai.wangke.ItDb;
import tech.dragonai.wangke.web.ApiException;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 支付宝回调 —— 钱那条链上最不能出错的一段。
 *
 * <p><b>每一条负例都钉死"为什么被拒"，不满足于"抛了个异常"。</b>
 * 只断言"被拒了"的用例在这里等于没测：验签不过、app_id 不对、金额不对，
 * 三者都会让回调失败，但只有第三种代表"有人在用一笔 0.01 元的真实支付
 * 解锁 3490 元的订单"。分不清这三种，就等于没有任何一条判据在守着钱。
 *
 * <p>验签走的是官方 {@code AlipaySignature.rsaCheckV1} 的<b>真实调用</b>，
 * 不打桩：夹具自己生成 RSA 密钥对，私钥签、公钥当"支付宝公钥"配给服务。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AlipayCallbackIT {

    static final String DB = "wangke_pay_it";
    static DataSource ds;
    static JdbcTemplate jdbc;
    static OrderService orders;
    static OrderQueryService view;
    static FulfillService fulfill;
    static AlipayService alipay;
    static PayController pay;

    @BeforeAll
    static void bootstrap() throws Exception {
        ds = ItDb.freshDatabase(DB);
        AlipayTestKit.applyPayMigration(DB);
        jdbc = new JdbcTemplate(ds);
        var pricing = new PricingService(jdbc);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        var vault = new CodeVault(ItDb.testKey(23), "pay-salt".getBytes(StandardCharsets.UTF_8));
        orders = new OrderService(jdbc, pricing);
        view = new OrderQueryService(jdbc);
        fulfill = new FulfillService(jdbc, pricing, tx, vault);
        alipay = AlipayTestKit.service();
        pay = new PayController(view, alipay, fulfill, jdbc);

        jdbc.update("INSERT INTO sys_user (id,phone,role) VALUES "
                  + "(40,'13400000040','AGENT'),(41,'13400000041','AGENT'),"
                  + "(50,'13500000050','STUDENT'),(51,'13500000051','STUDENT')");
        jdbc.update("INSERT INTO agent_profile (id,user_id,agent_code,level,status)"
                  + " VALUES (1,40,'PA1',1,'ACTIVE')");
        jdbc.update("INSERT INTO agent_profile (id,user_id,agent_code,level,parent_agent_id,status)"
                  + " VALUES (2,41,'PB2',2,1,'ACTIVE')");
    }

    @AfterAll
    static void teardown() { ItDb.close(ds); }

    /** 二级代理的 10 码批量单：3490 元，付款后应出 10 个码、给一级返 510。 */
    private static OrderService.Created bulkOrder() { return orders.create(41, 2, "1.2.3.4"); }

    /**
     * 学员直购单（STUDENT_DIRECT，sku 3）。
     *
     * <p>收银台这条路 2026-09-01 起<b>只服务学员</b>：代理进货改成线下对公转账，
     * PayController 对 buyer_type='AGENT' 的单直接 409。所以凡是验「收银台本身」的用例
     * 都要用学员单当夹具 —— 用代理单会在闸门那一步就退出，那时测到的是闸门、不是收银台。
     */
    private static OrderService.Created studentOrder() { return orders.create(50, 3, "1.2.3.4"); }

    private static int codesOf(long orderId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM activation_code WHERE order_id=?", Integer.class, orderId);
        return n == null ? 0 : n;
    }

    private static String statusOf(long orderId) {
        return jdbc.queryForObject("SELECT status FROM biz_order WHERE id=?", String.class, orderId);
    }

    private static int auditCount(String action, String targetId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action=? AND target_id=?",
                Integer.class, action, targetId);
        return n == null ? 0 : n;
    }

    // ================================================================ 发起支付

    @Test @Order(1)
    @DisplayName("发起支付：表单与跳转地址在本地签出，不需要外网")
    void createPayIsLocal() {
        var o = studentOrder();
        // 网关指向 127.0.0.1:1（必然连接被拒）。这里能拿到表单，就证明了
        // page.pay / wap.pay 确实是本地签名的页面接口 —— 这条路径在断网环境也能跑。
        var req = AlipayTestKit.asUser(50, "STUDENT");
        var body = pay.create(new PayController.CreateReq(o.orderId(), "PC"), req);
        assertEquals(true, body.get("ok"));
        assertEquals("799.00", body.get("amountYuan"));
        assertEquals(79900L, body.get("amountFen"));
        String form = (String) body.get("form");
        assertTrue(form.contains(o.orderNo()),
                "表单里没有本单单号，说明 out_trade_no 没传对，付了也对不上账");
        assertTrue(form.contains("<form"), "返回的不是可提交的表单");
        assertTrue(((String) body.get("payUrl")).startsWith(AlipayTestKit.GATEWAY),
                "跳转地址不是支付宝网关");

        // 选了哪个收银台必须落在订单上。PC/WAP 之分只有这一步知道 ——
        // 回调报文里没有，FulfillService 也拿不到。丢了它，出问题时按渠道切分就做不出来。
        assertEquals("ALIPAY_PAGE", jdbc.queryForObject(
                "SELECT pay_channel FROM biz_order WHERE id=?", String.class, o.orderId()));

        var wap = pay.create(new PayController.CreateReq(o.orderId(), "wap"), req);
        assertEquals("WAP", wap.get("channel"), "渠道大小写不敏感应当被接受");
        assertEquals("ALIPAY_WAP", jdbc.queryForObject(
                "SELECT pay_channel FROM biz_order WHERE id=?", String.class, o.orderId()),
                "改用手机收银台之后订单上记的还是上一次的渠道");

        // 关单倒计时必须是"还有约 120 分钟"，不能是 0 也不能是负数。
        //
        // 这条判据钉的是一个真出现过的故障：expire_at 由数据库写（DATE_ADD(NOW(3),…)），
        // 而判过期的代码原本在 Java 里拿 LocalDateTime.now() 去比。测试机上 MySQL 跑 UTC、
        // JDBC 连接串却声明 serverTimezone=Asia/Shanghai，getTimestamp 读回来的值被平移了
        // 8 小时 —— 于是【刚下的单立刻显示"已超过支付时限"】，支付按钮直接不可用，
        // 而全链路没有任何一处报错、没有任何一条日志指向时区。
        long left = ((Number) body.get("expireInSeconds")).longValue();
        assertTrue(left > 110 * 60 && left <= 120 * 60,
                "关单倒计时是 " + left + " 秒。偏差在小时量级几乎一定是时区平移："
              + "expire_at 由数据库时钟写入，任何一侧用 JVM 时钟或换算过的时间戳去判都会错");

        // 留痕必须真的落库：争议里"我没点过支付"要靠它
        assertEquals(2, auditCount("PAY_CREATE", o.orderNo()));
    }

    @Test @Order(2)
    @DisplayName("下单人之外的人不能替这单发起支付")
    void createPayIsOwnerScoped() {
        var o = bulkOrder();
        var e = assertThrows(ApiException.class,
                () -> pay.create(new PayController.CreateReq(o.orderId(), "PC"),
                                 AlipayTestKit.asUser(50, "STUDENT")));
        assertEquals(404, e.status());
        assertEquals("订单不存在", e.getMessage(),
                "越权与不存在必须收敛到同一种结果，否则这是个可以枚举全站订单的探针");
    }

    // ================================================================ 正常到账

    @Test @Order(3)
    @DisplayName("合法回调：验签通过 → 出 10 个码 → 回纯文本 success；流水记在 ALIPAY 名下")
    void validNotifyFulfills() {
        var o = bulkOrder();
        String txn = "2026081122001400000003";
        var p = AlipayTestKit.sign(AlipayTestKit.notifyOf(o.orderNo(), "3490.00", txn));

        String body = pay.notifyAlipay(AlipayTestKit.notifyRequest(p));
        assertEquals("success", body, "支付宝只认字面量 success，回别的会被判失败并重推 25 次");

        assertEquals("FULFILLED", statusOf(o.orderId()));
        assertEquals(10, codesOf(o.orderId()), "出码数与订单快照不符");

        var rec = jdbc.queryForMap(
                "SELECT provider, provider_txn_id, amount_fen, raw_payload FROM payment_record "
              + "WHERE order_id=?", o.orderId());
        assertEquals("ALIPAY", rec.get("provider"),
                "provider 是幂等键 (provider, provider_txn_id) 的一半，记错渠道等于幂等键失守");
        assertEquals(txn, rec.get("provider_txn_id"));
        assertEquals(349000L, ((Number) rec.get("amount_fen")).longValue());
        assertNotNull(rec.get("raw_payload"), "回调原文没有存证，日后争议里平台没有立场");
        assertTrue(((String) rec.get("raw_payload")).contains(txn));

        // 一级上级的佣金：51/码 × 10
        Long comm = jdbc.queryForObject(
                "SELECT amount_fen FROM commission_record WHERE order_id=?", Long.class, o.orderId());
        assertEquals(51000L, comm);

        // 已知缺口：biz_order.pay_channel 目前由 FulfillService 写死成
        // 「WECHAT → WECHAT_NATIVE，其余 → BANK_TRANSFER」，支付宝单会落成 BANK_TRANSFER。
        // 修法是 FulfillService 的一行映射（见交付说明 sharedEdits），不在本次可改的文件里，
        // 所以这里不断言具体渠道值 —— 断言一个已知错的值会在修好之后反过来变红。
    }

    @Test @Order(4)
    @DisplayName("重复回调：支付宝最多推 25 次，第二次起只回 success，绝不再发一次货")
    void duplicateNotifyShipsOnce() {
        var o = bulkOrder();
        String txn = "2026081122001400000004";
        var p = AlipayTestKit.sign(AlipayTestKit.notifyOf(o.orderNo(), "3490.00", txn));

        assertEquals("success", pay.notifyAlipay(AlipayTestKit.notifyRequest(p)));
        assertEquals(10, codesOf(o.orderId()));

        for (int i = 0; i < 4; i++)
            assertEquals("success", pay.notifyAlipay(AlipayTestKit.notifyRequest(p)),
                    "重推必须继续回 success，否则支付宝会一直推到第 25 次");

        assertEquals(10, codesOf(o.orderId()),
                "重复回调多发了码 —— 多一个码就是白送一门课，而且订单金额没变、对账表也平，没有任何信号");
        Integer recs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_record WHERE order_id=?", Integer.class, o.orderId());
        assertEquals(1, recs, "同一笔交易入了两次账");
        Integer comms = jdbc.queryForObject(
                "SELECT COUNT(*) FROM commission_record WHERE order_id=?", Integer.class, o.orderId());
        assertEquals(1, comms, "佣金结了两次");
    }

    // ================================================================ 负例：每条都钉住原因

    @Test @Order(5)
    @DisplayName("验签失败：签完再改金额 → 拒绝的原因必须是 BAD_SIGN，且订单一动不动")
    void tamperedSignatureRejected() {
        var o = bulkOrder();
        var p = new LinkedHashMap<>(AlipayTestKit.sign(
                AlipayTestKit.notifyOf(o.orderNo(), "3490.00", "2026081122001400000005")));
        p.put("total_amount", "0.01");        // 签名覆盖不到这次修改

        var e = assertThrows(AlipayService.NotifyRejected.class, () -> alipay.verifyNotify(p));
        assertEquals(AlipayService.Reject.BAD_SIGN, e.reason(),
                "被拒的原因不是验签 —— 那说明验签这一步根本没有拦住篡改");

        assertEquals("failure", pay.notifyAlipay(AlipayTestKit.notifyRequest(p)));
        assertEquals("CREATED", statusOf(o.orderId()));
        assertEquals(0, codesOf(o.orderId()));
    }

    @Test @Order(6)
    @DisplayName("app_id 不符：签名是真的、报文是真的，只是不是发给我们这个应用的")
    void wrongAppIdRejected() {
        var o = bulkOrder();
        var base = AlipayTestKit.notifyOf(o.orderNo(), "3490.00", "2026081122001400000006");
        base.put("app_id", "2021999999999999");
        base.put("auth_app_id", "2021999999999999");
        var p = AlipayTestKit.sign(base);      // 用我们的私钥签 —— 模拟"平台公钥模式下签名照样过"

        var e = assertThrows(AlipayService.NotifyRejected.class, () -> alipay.verifyNotify(p));
        assertEquals(AlipayService.Reject.BAD_APP_ID, e.reason(),
                "如果这里报的是 BAD_SIGN，说明 app_id 校验其实从来没跑到过");

        assertEquals("failure", pay.notifyAlipay(AlipayTestKit.notifyRequest(p)));
        assertEquals("CREATED", statusOf(o.orderId()));
        assertEquals(0, codesOf(o.orderId()));
    }

    @Test @Order(7)
    @DisplayName("seller_id 不符：收款方不是我们，钱没进我们的账")
    void wrongSellerIdRejected() {
        var o = bulkOrder();
        var base = AlipayTestKit.notifyOf(o.orderNo(), "3490.00", "2026081122001400000007");
        base.put("seller_id", "2088777777777777");
        var p = AlipayTestKit.sign(base);

        var e = assertThrows(AlipayService.NotifyRejected.class, () -> alipay.verifyNotify(p));
        assertEquals(AlipayService.Reject.BAD_SELLER_ID, e.reason());

        assertEquals("failure", pay.notifyAlipay(AlipayTestKit.notifyRequest(p)));
        assertEquals("CREATED", statusOf(o.orderId()));
    }

    @Test @Order(8)
    @DisplayName("金额不符：一笔【真实的】0.01 元支付不能解锁 3490 元的订单")
    void amountMismatchRejected() {
        var o = bulkOrder();
        String txn = "2026081122001400000008";
        var p = AlipayTestKit.sign(AlipayTestKit.notifyOf(o.orderNo(), "0.01", txn));

        // 这一条与前三条的分界正是本用例的意义：验签过、app_id 过、seller_id 过，
        // 报文从头到尾都是真的，唯一对不上的是金额。
        var n = alipay.verifyNotify(p);
        assertEquals(1L, n.totalAmountFen(), "0.01 元应当解析成 1 分");
        assertEquals(o.orderNo(), n.outTradeNo());
        assertTrue(p.containsKey("sign") && p.containsKey("sign_type"),
                "verifyNotify 把调用方的参数表改了 —— rsaCheckV1 会【就地】删掉 sign 与 sign_type，"
              + "不传副本的话回调原文在验签这一步就被悄悄改了形状，存进 raw_payload 的将是缺签名的半份");

        assertEquals("failure", pay.notifyAlipay(AlipayTestKit.notifyRequest(p)));
        assertEquals("CREATED", statusOf(o.orderId()), "金额不符却把订单标成了已支付");
        assertEquals(0, codesOf(o.orderId()));
        Integer recs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_record WHERE order_id=?", Integer.class, o.orderId());
        assertEquals(0, recs, "金额不符的支付不该留下入账流水");
        assertEquals(1, auditCount("PAY_NOTIFY_AMOUNT_MISMATCH", o.orderNo()),
                "金额不符必须留下可查的审计事件，否则这笔钱只在日志里存在");
    }

    @Test @Order(9)
    @DisplayName("三位小数的金额：拒绝而不是四舍五入（少收的钱不会有任何日志）")
    void subFenAmountRejected() {
        var o = bulkOrder();
        var p = AlipayTestKit.sign(
                AlipayTestKit.notifyOf(o.orderNo(), "3490.005", "2026081122001400000009"));
        var e = assertThrows(AlipayService.NotifyRejected.class, () -> alipay.verifyNotify(p));
        assertEquals(AlipayService.Reject.MALFORMED, e.reason());
        assertEquals("failure", pay.notifyAlipay(AlipayTestKit.notifyRequest(p)));
        assertEquals("CREATED", statusOf(o.orderId()));
    }

    @Test @Order(10)
    @DisplayName("非终态通知（WAIT_BUYER_PAY）：忽略，但必须回 success 让它别再推")
    void nonFinalStatusIgnoredButAcknowledged() {
        var o = bulkOrder();
        var base = AlipayTestKit.notifyOf(o.orderNo(), "3490.00", "2026081122001400000010");
        base.put("trade_status", "WAIT_BUYER_PAY");
        base.remove("gmt_payment");
        var p = AlipayTestKit.sign(base);

        assertEquals("success", pay.notifyAlipay(AlipayTestKit.notifyRequest(p)),
                "回 failure 会招来 25 次无意义重推");
        assertEquals("CREATED", statusOf(o.orderId()), "没付钱却发了货");
        assertEquals(0, codesOf(o.orderId()));
    }

    @Test @Order(11)
    @DisplayName("订单不存在：回 success 止推，但要落一条不会丢的人工待办")
    void orphanNotifyRecorded() {
        var p = AlipayTestKit.sign(
                AlipayTestKit.notifyOf("WK20260811NOSUCHORD", "3490.00", "2026081122001400000011"));
        assertEquals("success", pay.notifyAlipay(AlipayTestKit.notifyRequest(p)),
                "重推不会让订单长出来，回 failure 只是让告警变噪音");
        assertEquals(1, auditCount("PAY_NOTIFY_ORPHAN", "WK20260811NOSUCHORD"),
                "钱到了、单不见了，这件事必须留在库里而不只是日志里");
    }

    @Test @Order(12)
    @DisplayName("已取消订单收到付款：不发货、不重试，落人工退款待办")
    void notifyOnClosedOrder() {
        var o = bulkOrder();
        view.cancel(41, o.orderId());
        assertEquals("CLOSED", statusOf(o.orderId()));

        var p = AlipayTestKit.sign(
                AlipayTestKit.notifyOf(o.orderNo(), "3490.00", "2026081122001400000012"));
        assertEquals("success", pay.notifyAlipay(AlipayTestKit.notifyRequest(p)));
        assertEquals("CLOSED", statusOf(o.orderId()), "终态订单被回调改了状态");
        assertEquals(0, codesOf(o.orderId()), "已取消的订单发了货");
        assertEquals(1, auditCount("PAY_NOTIFY_ORPHAN", o.orderNo()));
    }

    @Test @Order(13)
    @DisplayName("取消之后不能再发起支付")
    void cancelledOrderCannotBePaid() {
        var o = studentOrder();
        view.cancel(50, o.orderId());
        var e = assertThrows(ApiException.class,
                () -> pay.create(new PayController.CreateReq(o.orderId(), "PC"),
                                 AlipayTestKit.asUser(50, "STUDENT")));
        assertEquals(409, e.status());
        assertTrue(e.getMessage().contains("已取消"), e.getMessage());
    }

    @Test @Order(13)
    @DisplayName("代理进货单打不开收银台：进货价一家一议，线上收银台会把它原样摆出来")
    void agentOrderCannotUseCashier() {
        var o = bulkOrder();
        var e = assertThrows(ApiException.class,
                () -> pay.create(new PayController.CreateReq(o.orderId(), "PC"),
                                 AlipayTestKit.asUser(41, "AGENT")));
        assertEquals(409, e.status());
        assertTrue(e.getMessage().contains("对公转账"), e.getMessage());

        // ★ 这一条不是"少一个功能"，是把一个绕道堵上：代理端界面与出网 JSON 两侧
        // 都已经不出进货价了，而这个接口原样返回 amountFen —— 代理只要知道自己的
        // orderId 就能把刚藏起来的价问出来。所以顺带断言：被拒时一个金额都没漏出去。
        assertFalse(e.getMessage().contains("3490"), "拒绝的话里带出了进货价：" + e.getMessage());
        assertFalse(e.getMessage().contains("349000"), "拒绝的话里带出了进货价：" + e.getMessage());

        // 单子本身没被这次尝试改坏（渠道不许被写上）
        assertNull(jdbc.queryForObject("SELECT pay_channel FROM biz_order WHERE id=?",
                String.class, o.orderId()),
                "被拒的支付尝试把收银台渠道写到订单上了");
    }

    @Test @Order(14)
    @DisplayName("回调参数为空 / 缺关键字段：一律拒，且分得清是缺什么")
    void malformedNotifyRejected() {
        var e0 = assertThrows(AlipayService.NotifyRejected.class,
                () -> alipay.verifyNotify(Map.of()));
        assertEquals(AlipayService.Reject.MALFORMED, e0.reason());

        var o = bulkOrder();
        var base = AlipayTestKit.notifyOf(o.orderNo(), "3490.00", "2026081122001400000014");
        base.remove("trade_no");
        var p = AlipayTestKit.sign(base);
        var e1 = assertThrows(AlipayService.NotifyRejected.class, () -> alipay.verifyNotify(p));
        assertEquals(AlipayService.Reject.MALFORMED, e1.reason());
        assertTrue(e1.getMessage().contains("交易号"), e1.getMessage());
        assertEquals("failure", pay.notifyAlipay(AlipayTestKit.notifyRequest(p)));
    }

    // ================================================================ 前端轮询

    @Test @Order(15)
    @DisplayName("轮询查单：只能查自己的单")
    void statusIsOwnerScoped() {
        var o = bulkOrder();
        var e = assertThrows(ApiException.class,
                () -> pay.status(o.orderNo(), AlipayTestKit.asUser(50, "STUDENT")));
        assertEquals(404, e.status());
        assertEquals("订单不存在", e.getMessage());
    }

    @Test @Order(16)
    @DisplayName("主动对账够不着网关时，轮询照常按本地状态返回，不能把接口打成 500")
    void statusSurvivesGatewayOutage() {
        var o = bulkOrder();
        // 网关是 127.0.0.1:1，主动查单必然连接被拒 —— 这正是要断言的场景：
        // 对账是尽力而为的补救，不能让它反过来制造一个新的故障。
        assertThrows(AlipayService.GatewayUnavailable.class, () -> alipay.query(o.orderNo()));

        var body = pay.status(o.orderNo(), AlipayTestKit.asUser(41, "AGENT"));
        assertEquals(true, body.get("ok"));
        assertEquals(false, body.get("paid"));
        @SuppressWarnings("unchecked")
        var order = (Map<String, Object>) body.get("order");
        assertEquals("CREATED", order.get("status"));
        assertEquals(349000L, order.get("amountFen"));
        assertEquals("3490.00", order.get("amountYuan"));
    }

    @Test @Order(17)
    @DisplayName("付过款的单，轮询返回 paid=true，且不再去打网关")
    void statusReportsPaid() {
        var o = bulkOrder();
        var p = AlipayTestKit.sign(
                AlipayTestKit.notifyOf(o.orderNo(), "3490.00", "2026081122001400000017"));
        assertEquals("success", pay.notifyAlipay(AlipayTestKit.notifyRequest(p)));

        // 已支付时不再触发对账。若这里去打了 127.0.0.1:1，异常会被 reconcile 吞掉不易察觉，
        // 所以顺带断言返回体是完整的 —— 走错分支时 paid 会是 false。
        var body = pay.status(o.orderNo(), AlipayTestKit.asUser(41, "AGENT"));
        assertEquals(true, body.get("paid"));
        @SuppressWarnings("unchecked")
        var order = (Map<String, Object>) body.get("order");
        assertEquals("FULFILLED", order.get("status"));
        assertEquals(349000L, order.get("paidAmountFen"));
    }

    @Test @Order(19)
    @DisplayName("已部分退款的订单收到迟到回调：不能被悄悄改回已发货")
    void notifyOnPartiallyRefundedOrder() {
        var o = bulkOrder();
        assertEquals("success", pay.notifyAlipay(AlipayTestKit.notifyRequest(AlipayTestKit.sign(
                AlipayTestKit.notifyOf(o.orderNo(), "3490.00", "2026081122001400000019")))));
        assertEquals("FULFILLED", statusOf(o.orderId()));

        // 退掉其中一部分（这里直接改状态，退款流程本身不在本次交付范围内）
        jdbc.update("UPDATE biz_order SET status='PARTIAL_REFUNDED', refunded_amount_fen=34900 "
                  + "WHERE id=?", o.orderId());

        // 一笔【不同 trade_no】的迟到回调 —— 幂等键拦不住它，因为它确实是另一笔交易号。
        // 挡它的只能是"这个订单状态还该不该发货"这条判断。
        // 而 trg_order_state 并不禁止 PARTIAL_REFUNDED → FULFILLED：
        // 数据库这一层不会报错，状态会被安静地翻回去。
        var late = AlipayTestKit.sign(
                AlipayTestKit.notifyOf(o.orderNo(), "3490.00", "2026081122001400000119"));
        assertEquals("success", pay.notifyAlipay(AlipayTestKit.notifyRequest(late)));

        assertEquals("PARTIAL_REFUNDED", statusOf(o.orderId()),
                "已退过款的订单被回调改回了已发货 —— 退款记录还在、订单状态却翻了，"
              + "对账时两边都会说自己没错");
        assertEquals(34900L, jdbc.queryForObject(
                "SELECT refunded_amount_fen FROM biz_order WHERE id=?", Long.class, o.orderId()));
        assertEquals(10, codesOf(o.orderId()), "又发了一批码");
        assertEquals(1, auditCount("PAY_NOTIFY_ORPHAN", o.orderNo()),
                "这笔钱需要人工处理，必须留在库里而不只是日志里");
    }

    // ================================================================ 金额换算

    @Test @Order(18)
    @DisplayName("分↔元换算：整条链上不许出现 double")
    void moneyConversionIsExact() {
        assertEquals("3490.00", AlipayService.fenToYuan(349000));
        assertEquals("0.01", AlipayService.fenToYuan(1));
        assertEquals("799.00", AlipayService.fenToYuan(79900));
        // 反向由 PricingService 守：三位小数直接报错，不四舍五入
        assertEquals(349000L, PricingService.yuanToFen("3490.00"));
        var e = assertThrows(IllegalArgumentException.class, () -> PricingService.yuanToFen("3490.005"));
        assertTrue(e.getMessage().contains("拒绝四舍五入"), e.getMessage());
        // 0 元与负数是配置错，不是业务值
        assertThrows(IllegalArgumentException.class, () -> AlipayService.fenToYuan(0));
    }
}
