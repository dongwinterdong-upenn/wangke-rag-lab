package tech.dragonai.wangke.trade;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tech.dragonai.wangke.web.ApiException;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单的<b>读</b>与<b>取消</b>。{@link OrderController} 与 {@link PayController} 共用这一份。
 *
 * <h3>为什么把这几条 SQL 收在一个类里</h3>
 * 每一条都带 {@code buyer_user_id=?}。这个条件散在两个控制器里的话，
 * 迟早有一处漏写，而漏写的那条查询<b>工作得很好</b> —— 只是任何人换个 id 就能看别人的单。
 * 收在一处，加接口时抄的是带条件的那一份。
 *
 * <h3>为什么越权条件写在 WHERE 而不是查出来再比对</h3>
 * "先按 id 查行，再判断 buyer 是不是他"看起来等价，但它给了两种不同的结果：
 * 行不存在 vs 行存在但不是你的。只要哪天有人给这两种情况配了不同的文案或状态码，
 * 这个接口就变成了一个可以枚举全站订单 id 的探针 —— 能数出平台一共有多少单。
 * 条件在 WHERE 里，两种情况对外是同一个 404。
 *
 * <h3>越权维度是<b>两个</b>：谁 + 以什么身份</h3>
 * 只有 {@code buyer_user_id=?} 是不够的。同一个自然人可以既是学员又是代理，
 * 而代理进货单的 buyer_user_id 写的就是他本人（{@link OrderService} 建单处），
 * 于是"只按人过滤"会把他的<b>代理进货单连同批发价一起</b>摆进学员端的「我的订单」——
 * 2026-08-19 业务方看到的正是这一幕，而它同时也是代理进货价泄漏的一条路。
 *
 * <p>所以这里每一条都再带 {@code buyer_type='STUDENT'}。代理侧的对称条件
 * （{@code buyer_agent_id=我}）早就写在 {@link tech.dragonai.wangke.agent.AgentShopService}
 * 里且有测试守着，学员侧整片没做 —— 五条入口里只有
 * {@link CodeService#revealForBuyer} 一条带了这个条件。判别式沿用它的
 * {@code buyer_type}，不用 {@code buyer_agent_id IS NULL}：代理按<b>学员档</b>
 * 给自己买的那种单是 {@code buyer_type='STUDENT'} 且 {@code buyer_agent_id} 为 NULL，
 * 两种写法在今天等价，但 buyer_type 说的是"这一单是以什么身份下的"，
 * 而那正是这里要问的问题。
 *
 * <p><b>唯一不带这个条件的是 {@link #byOrderNoForNotify}</b>，理由见那里。
 */
@Service
public class OrderQueryService {

    private final JdbcTemplate jdbc;

    public OrderQueryService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /**
     * 订单视图。
     *
     * <p>用 RowMapper 逐列 {@code rs.getLong} 取，不用 {@code queryForMap} 再强转：
     * 本库所有主键都是 BIGINT UNSIGNED，Connector/J 把它映射成 <b>BigInteger</b>，
     * {@code (Long) map.get("id")} 会在运行期抛 ClassCastException。
     *
     * @param expireInSeconds 距关单还有多少秒，<b>由数据库自己算</b>（见 {@link #SELECT}）。
     *        负数表示已经过期。有了它，"还能不能付"这个判断就不需要任何一方知道时区。
     */
    public record OrderRow(long id, String orderNo, String buyerType, String skuCode, String skuName,
                           int codeQty, long amountFen, long paidAmountFen, long refundedAmountFen,
                           String status, String payChannel, LocalDateTime paidAt,
                           LocalDateTime expireAt, long expireInSeconds, LocalDateTime createdAt,
                           String origin) {

        /** 已过关单时刻。判据来自数据库的时钟，不是 JVM 的 —— 理由见 {@link #SELECT}。 */
        public boolean expired() { return expireInSeconds <= 0; }

        /**
         * 平台手工发给这家代理的白送单。
         *
         * <p>它的 amount_fen 记着目录价，但那笔钱从来没有收过 —— 界面上必须写
         * 「平台发放 · 无需付款」而不是一个金额，否则代理会以为自己欠这笔钱，
         * 或者以为这是一笔已付款的进货。
         */
        public boolean gift() { return "ADMIN_GIFT".equals(origin); }
    }

    /**
     * <b>过期与否由数据库判，不在 Java 里拿 {@code LocalDateTime.now()} 去比。</b>
     *
     * <p>expire_at 是数据库用自己的时钟写下的（{@code DATE_ADD(NOW(3), INTERVAL 120 MINUTE)}）。
     * 拿它去和 JVM 的当前时间比，等于让两个时钟、两套时区设置同时正确才算对，
     * 而这条链上有三个独立的时区旋钮：MySQL 服务器时区、JDBC 连接串上声明的
     * {@code serverTimezone}、JVM 默认时区。本仓的测试机上前两者就已经不一致
     * （服务器跑 UTC，连接串声明 Asia/Shanghai），于是 {@code rs.getTimestamp} 读回来的
     * expire_at 比真值早 8 小时 —— <b>表现是刚下的单立刻显示"已超过支付时限"</b>，
     * 而没有任何一处报错，看起来只像是"支付按钮坏了"。
     *
     * <p>换成让数据库算差值，减法在同一个时钟里做完，三个旋钮怎么拧都不影响结论。
     */
    private static final String SELECT =
            "SELECT o.id, o.order_no, o.buyer_type, o.sku_code_snapshot, s.name AS sku_name,"
          + " o.code_qty_snapshot, o.amount_fen, o.paid_amount_fen, o.refunded_amount_fen,"
          + " o.status, o.pay_channel, o.paid_at, o.expire_at,"
          + " TIMESTAMPDIFF(SECOND, NOW(3), o.expire_at) AS expire_in_seconds, o.created_at, o.origin"
          + " FROM biz_order o LEFT JOIN sku s ON s.id = o.sku_id ";

    private static final org.springframework.jdbc.core.RowMapper<OrderRow> MAPPER = (rs, i) ->
            new OrderRow(rs.getLong("id"), rs.getString("order_no"), rs.getString("buyer_type"),
                    rs.getString("sku_code_snapshot"), rs.getString("sku_name"),
                    rs.getInt("code_qty_snapshot"), rs.getLong("amount_fen"),
                    rs.getLong("paid_amount_fen"), rs.getLong("refunded_amount_fen"),
                    rs.getString("status"), rs.getString("pay_channel"),
                    localDateTime(rs, "paid_at"), localDateTime(rs, "expire_at"),
                    rs.getLong("expire_in_seconds"), localDateTime(rs, "created_at"),
                    rs.getString("origin"));

    /**
     * 读 DATETIME 列。<b>走 {@code getObject(…, LocalDateTime.class)} 而不是 getTimestamp。</b>
     *
     * <p>DATETIME 存的是墙上时间，本来就不带时区。{@code getTimestamp} 会把它当成
     * "连接串声明的那个时区里的一个瞬间"再换算到 JVM 时区 —— 两处设置只要不一致，
     * 每一个时间字段都会整体平移若干小时。它不会抛异常、不会告警，
     * 只是订单详情里的付款时间和关单时间集体错位，客服看到的时间和用户看到的对不上。
     */
    private static LocalDateTime localDateTime(java.sql.ResultSet rs, String col)
            throws java.sql.SQLException {
        return rs.getObject(col, LocalDateTime.class);
    }

    /** 我的订单列表。分页参数在这里收口钳位，不信调用方传进来的 limit。 */
    public List<OrderRow> mine(long userId, String status, int limit, int offset) {
        // limit 不钳位的话，一个 ?limit=1000000 就是一次全表扫 + 一次超大响应体，
        // 而且它看起来完全像正常流量。offset 同理。
        int lim = Math.max(1, Math.min(limit, 100));
        int off = Math.max(0, offset);
        if (status == null || status.isBlank())
            // origin='SELF_SERVE'：超管批量生成学员码时代建的合成单，buyer_user_id 写的是操作人。
            // 不收窄的话，那些 799×N 元、实付 0 的批次单会出现在**超管本人**学员端的
            // 「我的订单」里 —— 一张他没买过的、金额还很大的单，而没有任何一处会报错。
            // 严格只减不增：今天买家看得见的学员单全部是 SELF_SERVE。
            return jdbc.query(SELECT + "WHERE o.buyer_user_id=? AND o.buyer_type='STUDENT'"
                    + " AND o.origin='SELF_SERVE'"
                            + " ORDER BY o.id DESC LIMIT ? OFFSET ?",
                    MAPPER, userId, lim, off);
        return jdbc.query(SELECT + "WHERE o.buyer_user_id=? AND o.buyer_type='STUDENT'"
                + " AND o.origin='SELF_SERVE' AND o.status=?"
                        + " ORDER BY o.id DESC LIMIT ? OFFSET ?",
                MAPPER, userId, status, lim, off);
    }

    public long countMine(long userId, String status) {
        Long n = (status == null || status.isBlank())
                ? jdbc.queryForObject("SELECT COUNT(*) FROM biz_order"
                        + " WHERE buyer_user_id=? AND buyer_type='STUDENT'"
                        + " AND origin='SELF_SERVE'", Long.class, userId)
                : jdbc.queryForObject("SELECT COUNT(*) FROM biz_order"
                        + " WHERE buyer_user_id=? AND buyer_type='STUDENT'"
                        + " AND origin='SELF_SERVE' AND status=?",
                        Long.class, userId, status);
        return n == null ? 0 : n;
    }

    /**
     * 按 id 取我的订单。不是我的 = 不存在，同一个 404。
     *
     * <p><b>这一条不带 buyer_type</b>：它是<b>付款</b>那条路上的取数
     * （{@link PayController#createPay}），而代理进货单也要从这里付钱。
     * 加上 buyer_type='STUDENT' 会让代理<b>付不了款</b> ——
     * 表现是点「继续付款」拿到一句"订单不存在"，而那张单就在他自己的进货记录里躺着。
     * 学员端那一页要的是下面的 {@link #studentDetail}。
     */
    public OrderRow detail(long userId, long orderId) {
        var rows = jdbc.query(SELECT + "WHERE o.id=? AND o.buyer_user_id=?", MAPPER, orderId, userId);
        if (rows.isEmpty()) throw ApiException.notFound("订单不存在");
        return rows.get(0);
    }

    /**
     * 学员端「我的订单」详情。比 {@link #detail} 多一条 {@code buyer_type='STUDENT'}。
     *
     * <p>两个方法看起来只差一个条件，但问的是<b>两个问题</b>：
     * detail 问"这单是不是他的、能不能付"，studentDetail 问"这单该不该出现在学员那一页上"。
     * 合成一个方法、靠一个 boolean 参数切换的话，调用方会照着最近的一次用法抄，
     * 而抄错的那一次不会报错 —— 要么代理付不了款，要么批发价又回到学员眼前。
     */
    public OrderRow studentDetail(long userId, long orderId) {
        var rows = jdbc.query(SELECT + "WHERE o.id=? AND o.buyer_user_id=? AND o.buyer_type='STUDENT'"
                + " AND o.origin='SELF_SERVE'",
                MAPPER, orderId, userId);
        if (rows.isEmpty()) throw ApiException.notFound("订单不存在");
        return rows.get(0);
    }

    /**
     * 按业务单号取我的订单。前端轮询用单号而不是 id，因为下单响应里给出去的是单号。
     *
     * <p>同 {@link #detail}：这是<b>付款状态轮询</b>那条路，代理与学员共用，
     * 所以不带 buyer_type。
     */
    public OrderRow byOrderNo(long userId, String orderNo) {
        var rows = jdbc.query(SELECT + "WHERE o.order_no=? AND o.buyer_user_id=?",
                MAPPER, orderNo, userId);
        if (rows.isEmpty()) throw ApiException.notFound("订单不存在");
        return rows.get(0);
    }

    /**
     * 回调专用：<b>不带 buyer_user_id</b>，因为回调没有登录态，来源可信性由验签保证。
     *
     * @return 找不到返回 null。这里刻意不抛 404 —— 回调路径上"订单不存在"不是给用户看的错误，
     *         而是一条需要人工处理的异常事件（钱到了，单不见了），由调用方决定怎么记。
     */
    public OrderRow byOrderNoForNotify(String orderNo) {
        var rows = jdbc.query(SELECT + "WHERE o.order_no=?", MAPPER, orderNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 取消未支付订单。
     *
     * <p>判定与写入是<b>同一条 UPDATE</b>：条件全在 WHERE 里，
     * 归属、状态一起判。写成"先查状态再更新"的话，两次之间正好到账的那笔支付
     * 会被这次取消覆盖成 CLOSED —— 而 CLOSED 是终态，钱收了、单关了、货再也发不出去。
     *
     * <h3>这一条<b>不带</b> buyer_type，是有意的</h3>
     * 上面那些读的路都带，因为它们会<b>把批发价摆到学员眼前</b>——那是要挡的东西。
     * 取消不吐任何价格、任何别人的数据，它只是"把我自己一张还没付钱的单作废"。
     *
     * <p><b>这段话曾经写错过，值得留着当例子。</b>原来写的是"这条路是代理放弃未付款
     * 进货单的唯一入口，所以不能加条件"。前半句当时是对的，但推理是错的：
     * 真正的入口不是这个后端方法，而是<b>学员端那颗「取消订单」按钮</b> ——
     * 而它是靠 {@link #mine} 把进货单列出来才画得出来的。同一轮改动给 mine 加了
     * buyer_type 之后，那颗按钮对进货单不再渲染：<b>SQL 层保住了能力，界面层把它删了。</b>
     * 一条"为了保住某个能力而不加条件"的理由，在能力已经从另一层消失之后就不再成立。
     *
     * <p>现在代理有自己的取消入口
     * （{@code POST /api/agent/orders/{orderNo}/cancel}，按 buyer_agent_id 圈），
     * 所以这里不带 buyer_type 的理由回到最朴素的那一条：<b>它不吐任何价格、
     * 不吐别人的数据</b>，而 PayController 那条付款链路也共用它。
     *
     * <p>代价是口径与 {@link #detail} 不成对：同一个 id 在 detail 上 404、在这里 409。
     * 这里可接受，因为两种回答泄漏的都只是<b>他自己</b>那张单的状态，
     * 而他在代理端本来就看得到它 —— 不构成任何新的枚举面。
     */
    public void cancel(long userId, long orderId) {
        int n = jdbc.update(
                "UPDATE biz_order SET status='CLOSED'"
              + " WHERE id=? AND buyer_user_id=? AND status='CREATED'",
                orderId, userId);
        if (n == 1) return;

        // 没改到行，要给出能自解释的原因。两次查询都带 buyer_user_id，
        // 所以"不是你的"和"不存在"仍然收敛到同一个 404，不构成枚举探针。
        var rows = jdbc.queryForList(
                "SELECT status FROM biz_order WHERE id=? AND buyer_user_id=?", orderId, userId);
        if (rows.isEmpty()) throw ApiException.notFound("订单不存在");
        String st = (String) rows.get(0).get("status");
        throw ApiException.conflict(switch (st) {
            case "CLOSED" -> "订单已取消，无需重复操作";
            case "PAID", "FULFILLED" -> "订单已支付，不能取消；如需退款请走退款申请";
            case "REFUNDED", "PARTIAL_REFUNDED" -> "订单已进入退款流程，不能取消";
            default -> "订单当前状态不允许取消";
        });
    }

    // ---------------------------------------------------------------- 出网视图

    /**
     * 分 → 元展示串。
     *
     * <p>库里一律是分，出网也一律带上分（{@code amountFen}）—— 元只是给人看的那一份。
     * 只给元的话，前端拿到 "3490.00" 做加减就又变成浮点了，而那种错在小额上看不出来。
     *
     * <p>不复用 {@code AlipayService.fenToYuan}：那里 {@code <=0} 视为配置错要炸，
     * 而这里 0（未支付的实付额）是正常值。
     */
    public static String yuan(long fen) {
        return java.math.BigDecimal.valueOf(fen).movePointLeft(2)
                .setScale(2, java.math.RoundingMode.UNNECESSARY).toPlainString();
    }

    /**
     * 订单的出网形状。<b>只暴露买家自己需要的字段</b> ——
     * price_tier_id、client_ip、buyer_agent_id 这些是内部复盘用的，出网没有用途，
     * 却会把定价结构和渠道关系送出去。
     */
    public static java.util.Map<String, Object> view(OrderRow o) {
        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("orderId", o.id());
        m.put("orderNo", o.orderNo());
        m.put("buyerType", o.buyerType());
        m.put("skuCode", o.skuCode());
        m.put("skuName", o.skuName());
        m.put("codeQty", o.codeQty());
        m.put("amountFen", o.amountFen());
        m.put("amountYuan", yuan(o.amountFen()));
        m.put("paidAmountFen", o.paidAmountFen());
        m.put("refundedAmountFen", o.refundedAmountFen());
        m.put("status", o.status());
        m.put("payChannel", o.payChannel());
        m.put("paidAt", o.paidAt());
        m.put("expireAt", o.expireAt());
        // 倒计时给秒数，不让前端拿 expireAt 自己算。
        // expireAt 是个不带时区的墙上时间字符串，浏览器按本地时区解释它 ——
        // 用户手机时区不是东八区（出国、或者干脆手动改过），倒计时要么一开就归零、
        // 要么虚高几小时，而两种表现都不会有任何报错。秒数是差值，与时区无关。
        m.put("expireInSeconds", Math.max(0, o.expireInSeconds()));
        m.put("createdAt", o.createdAt());
        // 「来源」出网。平台手工发放的白送单要在界面上写「平台发放 · 无需付款」而不是金额：
        // amount_fen 里那个数是目录价，代理从来没有付过它 —— 摆到他脸上，他会以为
        // 自己欠这笔钱，或者以为这是一笔已经付过款的进货。
        m.put("origin", o.origin());
        m.put("gift", o.gift());
        return m;
    }
}
