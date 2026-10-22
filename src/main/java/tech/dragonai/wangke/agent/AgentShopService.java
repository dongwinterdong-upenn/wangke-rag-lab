package tech.dragonai.wangke.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import tech.dragonai.wangke.trade.OrderQueryService;
import tech.dragonai.wangke.trade.OrderService;
import tech.dragonai.wangke.trade.PricingService;
import tech.dragonai.wangke.web.ApiException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 代理进货：橱窗（{@code /api/agent/skus}）与进货单（{@code /api/agent/orders} 三个）。
 *
 * <h3>★ 代理这一侧一个金额字段都不出网</h3>
 * 进货价由平台按代理逐家单独定，所以代理端摆一张统一价目表本身就是错的信息：
 * 金额只在超管后台与对公转账那一侧出现。
 *
 * <p>这条规矩<b>必须在这里兑现，不能只在界面上兑现</b>。把 totalPriceFen / amountFen
 * 留在 JSON 里而页面上不画，代理按 F12 就看得见 —— 而"页面上不写"会让所有人
 * （包括下一个改这里的人）以为它已经保密了，比明写更糟。
 * 所以 {@link #skus}、{@link #createOrder}、{@link #view} 三处出网体里
 * 没有 totalPriceFen / amountFen / amountYuan / paidAmountFen / refundedAmountFen。
 *
 * <p><b>服务端照常算钱、照常写订单</b>：取价一律 {@link PricingService#priceOf}，
 * 写快照一律 {@link OrderService#create}，biz_order.amount_fen 与 price_tier_id
 * 一个字段都没少。少的只是"讲给代理听"的那一份。
 *
 * <h3>价格只有一条路：PricingService.priceOf</h3>
 * 橱窗能不能买与下单时扣的价必须来自<b>同一次调用路径</b>。
 * 各查一遍价目表的表现是：橱窗挂着一档、下单被拒（或按另一个价成交）——
 * 两个结论各自都对，只是来源不同，而用户看到的是"平台乱收费"。
 * 所以本类一行金额计算都没有。
 *
 * <h3>层级取自令牌，不收参数</h3>
 * {@code /skus} <b>有意不接受 agentLevel</b>：收了就等于开放"二级代理看一级价目"，
 * 而加上这个参数的那次改动看起来只是"支持平台侧复用"，不会有人当成安全变更来审。
 * （{@code PublicSkuController} 正好相反：它是公开橱窗，本来就该收 agentLevel。）
 *
 * <h3>越权维度是 buyer_agent_id，且写在 WHERE 里</h3>
 * 列表、详情、下单回读三处用<b>同一个</b>过滤维度。列表按 buyer_agent_id 过滤而详情
 * 按 buyer_user_id 过滤的话，会出现"详情能打开、列表里却找不到"的单 ——
 * 代理会以为自己进的货丢了。同时"不是我的"与"不存在"必须收敛到同一句 404，
 * 否则这个接口就是一个可以枚举全站单号的探针。
 */
@Service
public class AgentShopService {

    private static final Logger log = LoggerFactory.getLogger(AgentShopService.class);

    /** 进货单的状态全集。传枚举外的值要当场拒，不能查空 —— 理由见 {@link AgentService#normalizeEnum}。 */
    private static final List<String> ORDER_STATUS = List.of(
            "CREATED", "PAID", "FULFILLED", "CLOSED", "REFUNDED", "PARTIAL_REFUNDED");

    /**
     * 代理侧订单的 SELECT。
     *
     * <p><b>这一份与 {@link OrderQueryService} 里那条是同一段 SQL，包括
     * {@code TIMESTAMPDIFF(SECOND, NOW(3), o.expire_at)} 这个关键列</b>：
     * 过期与否必须由数据库自己算，不能在 Java 里拿 {@code LocalDateTime.now()} 去比 ——
     * 这条链上有三个独立的时区旋钮（MySQL 服务器时区、连接串 serverTimezone、JVM 默认时区），
     * 本仓测试机上前两者就已经不一致，表现是刚下的单立刻显示"已超过支付时限"，且毫无报错。
     *
     * <p>它<b>本该</b>作为 {@code OrderQueryService.mineAsAgent/countAsAgent/byOrderNoForAgent}
     * 挂在那个类里（那里的 SELECT 与 MAPPER 都是 private，外部够不到），
     * 本轮因文件归属限制没有改那个文件，已作为偏差上报。合并时：删掉这里的两个常量，
     * 三个方法改调 OrderQueryService，行为不变。在合并之前，改动这三个旋钮的处理方式
     * <b>必须两处一起改</b>。
     */
    private static final String SELECT =
            "SELECT o.id, o.order_no, o.buyer_type, o.sku_code_snapshot, s.name AS sku_name,"
          + " o.code_qty_snapshot, o.amount_fen, o.paid_amount_fen, o.refunded_amount_fen,"
          + " o.status, o.pay_channel, o.paid_at, o.expire_at,"
          + " TIMESTAMPDIFF(SECOND, NOW(3), o.expire_at) AS expire_in_seconds, o.created_at, o.origin"
          + " FROM biz_order o LEFT JOIN sku s ON s.id = o.sku_id ";

    /** DATETIME 列一律 {@code getObject(col, LocalDateTime.class)}，见 {@link OrderQueryService}。 */
    private static final RowMapper<OrderQueryService.OrderRow> MAPPER = (rs, i) ->
            new OrderQueryService.OrderRow(rs.getLong("id"), rs.getString("order_no"),
                    rs.getString("buyer_type"), rs.getString("sku_code_snapshot"),
                    rs.getString("sku_name"), rs.getInt("code_qty_snapshot"),
                    rs.getLong("amount_fen"), rs.getLong("paid_amount_fen"),
                    rs.getLong("refunded_amount_fen"), rs.getString("status"),
                    rs.getString("pay_channel"), ldt(rs, "paid_at"), ldt(rs, "expire_at"),
                    rs.getLong("expire_in_seconds"), ldt(rs, "created_at"),
                    rs.getString("origin"));

    private static LocalDateTime ldt(ResultSet rs, String col) throws SQLException {
        return rs.getObject(col, LocalDateTime.class);
    }

    private final JdbcTemplate jdbc;
    private final AgentService agents;
    private final PricingService pricing;
    private final OrderService orders;

    public AgentShopService(JdbcTemplate jdbc, AgentService agents,
                            PricingService pricing, OrderService orders) {
        this.jdbc = jdbc; this.agents = agents; this.pricing = pricing; this.orders = orders;
    }

    // ================================================================= 橱窗

    /**
     * 我这个层级现在能买什么。<b>不出价</b>（理由见类注释）。
     *
     * <p><b>取价仍然要做，只是结果不出网</b>：它在这里的角色是"这一档此刻买不买得到"的判据。
     * 删掉这次调用（既然不出价了，看起来白算一遍）的后果是橱窗开始列出下单必被拒的档 ——
     * 而代理看不到价，只会看到"选了就报错"，连"是不是价没配好"都猜不到。
     *
     * <p><b>取不到现行价的档整行不出</b>，只把 sku_code 记进 {@code unpricedSkuCodes} 并说明原因。
     * 挂一个买不了的档，比不挂更坏。
     *
     * <p>取价<b>二义</b>（uk_active_price 失守，同一层级同一套餐有两条现行价）是数据事故，
     * 不能只当成"一行不显示"：这时不同的订单会按不同的价成交，而且事后无法解释。
     * 所以那种情况额外打 ERROR 日志。
     */
    public Map<String, Object> skus(long userId) {
        AgentService.Agent a = agents.requireOperational(userId);

        // 只出代理档、只出在售的。学员直购档绝不能出现在这里：
        // 代理买到学员档会建出一张 buyer_agent_id=NULL 的单，付了钱一个码都不到他名下，
        // 而且那张单在 /api/agent/orders 里根本不出现 —— 钱花了，界面上什么都没发生。
        // c.status='ON' 与学员橱窗（PublicSkuController）必须同口径：
        // 只给学员侧加闸的话，课程没上架时代理端照样列出档位、标价、按钮可点，
        // 点下去被 OrderService 409 顶回来 —— 同一条业务规则两边给出相反的答案。
        // 同时 LEFT JOIN 改 INNER：course 行缺失的 SKU 本来就买不了，挂出来比不挂更坏。
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT s.id, s.sku_code, s.name, s.code_qty, s.status,"
              + " c.title AS course_title, c.activate_months, c.access_months"
              + " FROM sku s JOIN course c ON c.id = s.course_id"
              + " WHERE s.buyer_type='AGENT' AND s.status='ON' AND c.status='ON'"
              + " ORDER BY s.code_qty, s.id");

        var out = new ArrayList<Map<String, Object>>();
        var unpriced = new ArrayList<String>();
        var notes = new LinkedHashMap<String, String>();

        for (var r : rows) {
            long skuId = AgentService.num(r.get("id"));
            String skuCode = (String) r.get("sku_code");
            try {
                // 取到的价一个字都不用（见方法注释）：这一句是"买得到吗"的判据，不是取数。
                pricing.priceOf(a.level(), skuId);
            } catch (RuntimeException e) {
                unpriced.add(skuCode);
                String msg = String.valueOf(e.getMessage());
                if (msg.contains("二义")) {
                    log.error("取价二义（uk_active_price 失守）：层级 {} 套餐 {}（{}）"
                            + " —— 这是数据事故，不同订单会按不同的价成交且事后无法解释",
                            a.level(), skuId, skuCode, e);
                } else {
                    log.warn("套餐 {}（{}）在层级 {} 上没有现行价目，橱窗跳过这一行",
                            skuCode, skuId, a.level());
                }
                continue;
            }

            var m = new LinkedHashMap<String, Object>();
            m.put("skuId", skuId);
            m.put("skuCode", skuCode);
            m.put("name", r.get("name"));
            m.put("codeQty", ((Number) r.get("code_qty")).intValue());
            // ★ 这里<b>没有</b> totalPriceFen，也不许换个名字再放回来（unitFen / priceYuan / 折后价…）：
            //   代理端界面已经按"这一页没有任何价目"重画，字段还在的话它就只是从屏幕上
            //   挪进了 F12，而所有人都会以为它保密了。真要给某一家看价，改的是超管后台。
            // 权益月数为空时键直接消失，前端那两句文案按 falsy 自动不写。
            // 绝不补 0 —— "激活后 0 个月内可观看" 比不写这句话糟得多。
            m.put("courseTitle", r.get("course_title"));
            m.put("activateMonths", intOrNull(r.get("activate_months")));
            m.put("accessMonths", intOrNull(r.get("access_months")));
            // status 取库里的真值，不再写死字面量 "ON"。
            // 当前查询已经把 s.status 限死成 ON，所以两者取值恰好相同 ——
            // 但写死意味着哪天放宽了查询条件，这里仍然会对每一行报「在售」，
            // 而前端 Buy.tsx 正是靠 `s.status !== 'OFF'` 过滤的：下架档位会照常可点。
            m.put("status", r.get("status"));
            out.add(m);
        }

        if (!unpriced.isEmpty())
            notes.put("unpricedSkuCodes", "这些档当前不对你开售，"
                    + "所以整行没有出现在列表里：" + String.join("、", unpriced)
                    + "。挂一个买不了的档出来，比不挂更坏 —— 下单时会被同一条判据拒掉。");

        var body = new LinkedHashMap<String, Object>();
        body.put("agentLevel", a.level());
        // total 是【实际返回的行数】，不是过滤前的行数：前端拿 total 画"共 N 个套餐"，
        // 而它看得见的只有 rows 那几行。
        body.put("total", out.size());
        body.put("rows", out);
        body.put("unpricedSkuCodes", unpriced);
        body.put("notes", notes);
        return body;
    }

    // ================================================================= 进货单

    /**
     * 我的进货记录。同时是"这一单到哪一步了"的入口：CREATED 的单要能被认出来
     * （代理端据此把"等平台确认金额 → 对公转账"那段说明再讲一遍）。
     *
     * <p>口径是 {@code buyer_agent_id = 我}，不是 buyer_user_id：同一个人可以既按代理价进货、
     * 又按学员价给自己买一份课，后者 buyer_agent_id 为 NULL、不出码到他名下。
     * 混进"进货记录"会让代理以为自己进的货丢了。
     *
     * @param status 可选，必须是订单状态枚举中的一个。传别的值当场 400 而不是查空 ——
     *               查空会被前端读成"我没有订单"，这种错能在线上活很久
     */
    public Map<String, Object> orders(long userId, String status, int page, int size) {
        AgentService.Agent a = agents.requireOperational(userId);
        int p = AgentService.checkPage(page), s = AgentService.checkSize(size);
        String st = AgentService.normalizeEnum(status, ORDER_STATUS, "订单状态");

        String where = "WHERE o.buyer_agent_id=?" + (st == null ? "" : " AND o.status=?");
        Object[] args = st == null ? new Object[]{a.id()} : new Object[]{a.id(), st};

        Long total = AgentService.num(jdbc.queryForObject(
                "SELECT COUNT(*) FROM biz_order WHERE buyer_agent_id=?"
              + (st == null ? "" : " AND status=?"), Number.class, args));

        Object[] pageArgs = new Object[args.length + 2];
        System.arraycopy(args, 0, pageArgs, 0, args.length);
        pageArgs[args.length] = s;
        pageArgs[args.length + 1] = (long) (p - 1) * s;

        List<OrderQueryService.OrderRow> rows = jdbc.query(
                SELECT + where + " ORDER BY o.id DESC LIMIT ? OFFSET ?", MAPPER, pageArgs);

        var body = new LinkedHashMap<String, Object>();
        // 翻过尾页时 rows 为空但 total 仍是真实总数：前端 Pager 靠它算页数，
        // 这里跟着返回 0 的话，最后一页会把分页控件整个抹掉。
        body.put("total", total == null ? 0L : total);
        body.put("page", p);
        body.put("size", s);
        body.put("rows", rows.stream().map(AgentShopService::view).toList());
        return body;
    }

    /**
     * 单据状态。<b>用业务单号而不是自增 id</b>：
     * 下单响应给出去的就是单号，而顺序 id 对外可见等于把当日成交量一起送出去。
     *
     * <p>它曾经是付款页每 3 秒轮询的那条路（线上收银台已从代理端撤掉，付款走线下对公转账，
     * 所以现在只在打开某一单时读一次）。仍然<b>只做单行读</b>，绝不在这里补写任何状态
     * （比如"顺手把过期单置 CLOSED"）——轮询频率会把它变成一次写风暴。
     *
     * <p>orderNo 走参数化查询，不做格式白名单：格式不对自然落到 404。
     * 补一条"单号格式不对"的 400 反而多送一个可以拿来试探单号构造的信号。
     */
    public Map<String, Object> order(long userId, String orderNo) {
        AgentService.Agent a = agents.requireOperational(userId);
        var rows = jdbc.query(SELECT + "WHERE o.order_no=? AND o.buyer_agent_id=?",
                MAPPER, orderNo, a.id());
        // 不存在 与 不是我的 → 同一句话。给两种不同的回答就等于开放了一个
        // "这个单号存在吗"的问答接口，顺着枚举能数出平台一共有多少单。
        if (rows.isEmpty()) throw ApiException.notFound("订单不存在");
        return view(rows.get(0));
    }

    /**
     * 放弃一张还没付款的进货单。
     *
     * <h3>为什么必须有这条路</h3>
     * 在这之前，代理放弃进货单走的是<b>学员端</b>那颗「取消订单」按钮 ——
     * 因为学员端的「我的订单」原来会把他自己的进货单一并列出来
     * （那正是 2026-08-19 业务方报的问题）。收紧之后进货单不再出现在那一页，
     * 于是那颗按钮对进货单不再被渲染：<b>后端的 cancel 还在，能点到它的界面没了。</b>
     * 而 {@code biz_order} 的自动关单是靠"定时任务"这句注释撑着的，
     * 全仓唯一的 {@code @Scheduled} 是佣金结算 —— 也就是说没人会替他收拾这张单，
     * 它会永久停在进货记录顶部（按 id 倒序）显示「待平台确认」。
     * 付款改成线下对公转账之后这条路更要紧了：单子等的是人，而人可能忘了。
     *
     * <h3>判定与写入是同一条 UPDATE</h3>
     * 归属（{@code buyer_agent_id}）、状态（{@code CREATED}）一起判。
     * 写成"先查再改"的话，两次之间正好到账的那笔支付会被这次取消覆盖成 CLOSED ——
     * 而 CLOSED 是终态：钱收了、单关了、码再也发不出去。
     */
    public Map<String, Object> cancelOrder(long userId, String orderNo) {
        AgentService.Agent a = agents.requireOperational(userId);
        int n = jdbc.update(
                "UPDATE biz_order SET status='CLOSED'"
              + " WHERE order_no=? AND buyer_agent_id=? AND status='CREATED'", orderNo, a.id());
        if (n == 1) return Map.of("ok", true, "orderNo", orderNo, "status", "CLOSED");

        // 没改到行，给一个能自解释的原因。两次查询都带 buyer_agent_id，
        // 所以"不是你的"与"不存在"仍然收敛到同一个 404，不构成单号枚举探针。
        var rows = jdbc.queryForList(
                "SELECT status FROM biz_order WHERE order_no=? AND buyer_agent_id=?",
                orderNo, a.id());
        if (rows.isEmpty()) throw ApiException.notFound("订单不存在");
        String st = String.valueOf(rows.get(0).get("status"));
        throw ApiException.conflict(switch (st) {
            case "CLOSED" -> "这张单已经取消过了";
            case "PAID", "FULFILLED" -> "这张单已经付过款，不能取消。要退款请联系平台";
            case "REFUNDED", "PARTIAL_REFUNDED" -> "这张单已经在退款流程里，不能取消";
            default -> "这张单当前状态（" + st + "）不允许取消";
        });
    }

    /**
     * 代理进货建单。只钉"买什么、按什么价"，不动钱；<b>算出来的价不回给他</b>。
     *
     * <p><b>签名里只有 skuId。</b>不收 amount / codeQty / price / agentLevel 任何一个 ——
     * 签名里只要出现 amount，就一定会有某个版本"为了兼容"把它用起来，
     * 然后前端就能自己定价。价格由 {@link OrderService#create} 经 {@code priceOf} 取，
     * 并连同 price_tier_id 一起写进订单快照，那是全链唯一该写价格快照的地方。
     *
     * <p>重复 POST <b>不做去重</b>：每次一张新的 CREATED 单，120 分钟自动关闭、不占任何库存。
     * 真幂等要靠 client_token 唯一键裁决，schema 里没有这一列，不许在这里用
     * "先查有没有在途的单再决定建不建"顶替 —— 那在并发下必然漏。
     *
     * @param ip 落到订单行上。风控与争议处理都要用，且必须是下单当时的，事后补不回来
     */
    public Map<String, Object> createOrder(long userId, long skuId, String ip) {
        AgentService.Agent a = agents.requireOperational(userId);

        // ① 先自查一次 SKU。这一步存在的意义只有一个：把"代理误点了学员直购档"
        //    这件事在建单【之前】拦住。不拦的话 OrderService 会照建一张
        //    buyer_type=STUDENT、buyer_agent_id=NULL 的单，代理付完 799 一个码都不到他名下，
        //    而且这张单在 GET /api/agent/orders 里根本查不到 —— 钱花了，界面上什么都没发生。
        var skus = jdbc.queryForList("SELECT buyer_type, status, name FROM sku WHERE id=?", skuId);
        if (skus.isEmpty())
            throw ApiException.badRequest("这个套餐不存在，请刷新橱窗后重新选择");
        var sku = skus.get(0);
        if (!"AGENT".equals(sku.get("buyer_type")))
            throw ApiException.badRequest("这是学员直购套餐，代理进货请选代理档");
        if (!"ON".equals(sku.get("status")))
            throw ApiException.conflict("该套餐已下架，无法下单");

        // ② 取价先探一次，只为把运维文案换成人话。
        //    priceOf 的原话是"没有当前生效的价目：层级 1 套餐 2"——那是给运维看的，
        //    直接透给代理等于把内部编号和定价结构一起送出去。
        //    真正的取价仍然发生在 OrderService.create 里，这里探到的值一个字都不用。
        try {
            pricing.priceOf(a.level(), skuId);
        } catch (RuntimeException e) {
            log.warn("代理 {} 层级 {} 下单套餐 {} 时取不到现行价：{}",
                    a.id(), a.level(), skuId, e.getMessage());
            throw ApiException.conflict("这一档暂时不售，请换一个套餐或联系平台");
        }

        // ③ 建单。库里还有第二道闸（trg_order_insert 拒绝 buyer_agent_id 指向非 ACTIVE 档案），
        //    裁决权始终在触发器手里，应用层这一道只是把它翻译成人话。
        OrderService.Created c = orders.create(userId, skuId, ip);

        // ④ 回读。OrderService.Created 里没有 expireAt，而 expire_at 是数据库
        //    用【自己的时钟】写下的（DATE_ADD(NOW(3), INTERVAL 120 MINUTE)）。
        //    在 Java 里拿 now()+120min 自己推，两个时钟一旦不一致，
        //    倒计时就会一开就归零或者虚高几小时，且没有任何报错。
        var back = jdbc.query(SELECT + "WHERE o.order_no=? AND o.buyer_agent_id=?",
                MAPPER, c.orderNo(), a.id());
        if (back.isEmpty())
            throw new IllegalStateException("建单后回读不到订单 " + c.orderNo() + "，请联系平台核对");
        var o = back.get(0);

        var body = new LinkedHashMap<String, Object>();
        body.put("orderId", c.orderId());
        body.put("orderNo", c.orderNo());
        // ★ 建单响应里<b>没有</b> amountFen / amountYuan。金额已经按 priceOf 算好并写进
        //   biz_order.amount_fen（连同 price_tier_id），只是不讲给代理听 ——
        //   他看到的是"提交了几份、接下来等平台确认金额"。
        //   把它加回来的后果不是界面变化（前端根本不读），而是 F12 里又能看见进货价，
        //   且没有任何人会发现 —— 所以这里有一条判据钉着（AgentJsonShapeIT）。
        body.put("codeQty", c.codeQty());
        body.put("skuName", sku.get("name"));
        // expireAt 缺了，前端的付款倒计时一开就是 0，用户会以为单子当场过期。
        body.put("expireAt", o.expireAt());
        body.put("expireInSeconds", Math.max(0, o.expireInSeconds()));
        return body;
    }

    // ================================================================= 出网形状

    /**
     * 订单的出网形状。<b>主键字段名必须是 {@code id}</b>：
     * 前端 {@code rowKey={r => r.id}}、"继续付款"也传 {@code orderId: r.id}。
     * 直接复用 {@code OrderQueryService.view()} 出的是 {@code orderId}，
     * 那会让列表整片没有 key（React 只在控制台警告）、"继续付款"传 undefined。
     *
     * <p>不出 price_tier_id / client_ip / buyer_agent_id：那些是内部复盘用的，
     * 出网没有用途，却会把定价结构与渠道关系一起送出去。
     *
     * <p><b>★ 三个金额也不出</b>（amountFen / paidAmountFen / refundedAmountFen）。
     * 进货记录这一页现在只回答"哪一单、几个码、到哪一步了"。
     * 退款金额同理：它是按进货价退的，写出来等于把进货价倒推给他。
     * 代理自己挣的钱（佣金 / 余额 / 提现）不在这条路上，那几页照常出金额。
     */
    private static Map<String, Object> view(OrderQueryService.OrderRow o) {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", o.id());
        m.put("orderNo", o.orderNo());
        m.put("skuCode", o.skuCode());
        m.put("skuName", o.skuName());
        m.put("codeQty", o.codeQty());
        // 状态语义要如实给：PAID 只表示钱到了，FULFILLED 才表示码出完了。
        // 前端在 FULFILLED/CLOSED 停轮询，把 PAID 提前当成 FULFILLED 回，
        // 会让页面在码还没出完时就说"已出码"。
        m.put("status", o.status());
        m.put("expireAt", o.expireAt());
        m.put("paidAt", o.paidAt());
        m.put("createdAt", o.createdAt());
        // 倒计时给秒数：差值在数据库同一个时钟里算完，与任何一侧的时区设置无关。
        // ★ 代理端已经<b>不再</b>拿它当任何按钮的闸：付款改成线下对公转账，一张 CREATED 单
        //   等平台确认可能要几天，而 expire_at 只有 120 分钟。库里也没有任何定时任务会关它，
        //   FulfillService.confirmPaid 认的是 status='CREATED'、不看 expire_at ——
        //   也就是说过了这个点的单照样能被超管确认到账并出码。
        //   谁再把界面的可见性挂回这个数，代理会在提交两小时后看到入口整片消失，
        //   而单子其实还活着。这个键留着是给运维与后台看的。
        m.put("expireInSeconds", Math.max(0, o.expireInSeconds()));
        // 「来源」出网。平台手工发放的白送单要在界面上写「平台发放 · 无需付款」：
        // 这一句不是价格，是在解释"这批码你没付过钱"—— 别的单在这一页什么钱都不写，
        // 不点破的话，代理会拿这张单去问平台"这笔我是不是还欠着"。
        m.put("origin", o.origin());
        m.put("gift", o.gift());
        return m;
    }

    private static Integer intOrNull(Object v) {
        return v == null ? null : ((Number) v).intValue();
    }
}
