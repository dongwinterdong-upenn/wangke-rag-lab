package tech.dragonai.wangke.trade;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tech.dragonai.wangke.web.ApiException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 超管人工给代理发放激活码（赠送 / 补发）。
 *
 * <h3>为什么放在 trade 包而不是 admin 包</h3>
 * 它触碰的是这条链上那三条不变量 —— <b>一笔交易只入账一次、一单不多发一个码、
 * 一单对同一受益人只结一次佣金</b> —— 所以它必须和守着这三条的代码住在一起。
 * 放进 admin 包的话，下一个改 FulfillService 的人不会知道还有第二条出码路径。
 *
 * <h3>它发出去的码和买来的码是同一种码</h3>
 * 手工发码不另起一条出码逻辑：它代建一张 {@code origin='ADMIN_GIFT'} 的订单，
 * 然后直接调 {@link FulfillService#issueCodes}。「一个字节都不复制」是判据不是洁癖 ——
 * 明文 → hash → seal(plain, hash) 这个顺序抄反的表现是：码插得进去、掩码期限状态全对、
 * 学员照样能激活，只有有人点「查看明文」或代理点「导出」时才炸 AEADBadTagException。
 *
 * <h3>这张单不收钱，而且这件事是库层保证的</h3>
 * {@code amount_fen} 写目录价（ck_order_amount 逼着 &gt; 0），实付恒 0、到账时间恒空。
 * 两条 CHECK（ck_order_gift_unpaid / ck_order_grant_by）与两个触发器
 * （trg_commission_precheck / trg_payment_precheck）保证它变不成收入、结不出佣金、
 * 也挂不上支付流水。理由见 db/migrations/20260822_超管手工发码.sql 的文件头。
 *
 * <h3>继承下来的一笔债</h3>
 * 复用 issueCodes 也就继承了它那处钟错位：{@code activate_deadline} 用
 * {@code LocalDateTime.now()}（JVM 钟）算，而判它过没过期的是
 * trg_activation_code_state 里的 {@code NOW(3)}（库钟）。两处不同源时，
 * 边界上的那一张码会出现「界面说还能激活、库里说已过期」。本轮不改（不在射程），
 * 但下一个人要知道它在。
 */
public class ManualIssueService {

    /**
     * 一次最多发几份 / 最多几个码。上限的依据见 {@link #validate}。
     *
     * <p><b>public 是有意的</b>：AdminCodeGrantController 的 /grant-skus 把这两个数
     * 一起出网，前端的实时预览与禁用条件直接读它。原来前端各抄了一份常量 ——
     * 抄的那份对不上时，用户先看到一个「可以提交」的界面，点下去才吃到后端那句话，
     * 而两个数字之间那段区间正是他会反复重试的地方。
     */
    public static final int MAX_COPIES = 20;
    public static final int MAX_CODES = 200;

    private final JdbcTemplate jdbc;
    private final OrderService orders;
    private final PricingService pricing;
    private final FulfillService fulfill;
    private final TransactionTemplate tx;

    public ManualIssueService(JdbcTemplate jdbc, OrderService orders, PricingService pricing,
                              FulfillService fulfill, TransactionTemplate tx) {
        this.jdbc = jdbc; this.orders = orders; this.pricing = pricing;
        this.fulfill = fulfill; this.tx = tx;
    }

    /**
     * @param copies   发几份。每份出 {@code sku.code_qty} 个码
     * @param grantKey 一次发放意图的幂等键，由前端在对话框<b>打开时</b>生成一次
     */
    public record GrantCmd(long agentId, long skuId, int copies, String reason,
                           String grantKey, long operatorId, String clientIp) {}

    /**
     * 发放结果。
     *
     * <p><b>这里没有明文字段，是判据不是疏忽。</b>明文一旦进了响应体，下一步就是有人
     * 把它顺手写进审计快照 —— 而 AdminAuditController 会原样返回 before_json/after_json，
     * 等于给所有能查审计的人留一份永久可兑换的凭据。超管要拿明文只能走
     * 逐条 reveal 或批量导出，那两条都逐条写审计。
     *
     * @param firstTime false = 这个幂等键已经发过了，本次一个码都没多发
     */
    public record GrantResult(boolean firstTime, List<Long> orderIds, List<String> orderNos,
                              List<Map<String, Object>> codes, int codesIssued,
                              long amountFenTotal, String agentName, String skuName) {}

    /** 发码。可安全重复提交：同一个 grantKey 第二次调用只会把第一次的结果查回来。 */
    public GrantResult grant(GrantCmd cmd) {
        Ctx c = validate(cmd);
        try {
            return tx.execute(st -> doGrant(cmd, c));
        } catch (DuplicateKeyException e) {
            // 幂等键撞了 = 这次意图已经发过。绝不回 409：后台按钮会被双击，
            // 把第二次点击变成红色错误框的话，运营的第一反应是「刚才那次没成功」，
            // 于是去别处找补 —— 真的又发了一批码出去。
            String m = e.getMessage() == null ? "" : e.getMessage();
            if (!m.contains("uk_grant_key")) throw e;
            return replay(cmd, c);
        }
    }

    // ------------------------------------------------------------------ 校验

    /**
     * 幂等键的形状必须收死。<b>它不是一个自由文本参数，是我们自己生成的键。</b>
     *
     * <p>三处 SQL 把它拼进 {@code LIKE key + "#%"}：本类的 replay、
     * AdminCodeGrantController 的批量明文导出、AdminOrderController 的订单筛选。
     * 不收形状的话，传一个 {@code %} 进来就是 {@code LIKE '%#%'} ——
     * <b>一次把全库白送码的明文导出来</b>（有 500 上限和逐条审计兜着，
     * 但那是事后查得到，不是当时拦得住）。
     *
     * <p>{@code #} 也一并排除：它是我们拼批内序号用的分隔符，键里再出现一个，
     * 「key#0」到底是第 0 份还是键本身就叫「key#0」就分不清了。
     *
     * <p>前端用 {@code crypto.randomUUID()} 生成，天然满足这条。
     */
    public static void requireGrantKey(String key) {
        if (key == null || key.isBlank()) throw ApiException.badRequest("缺少幂等键");
        String k = key.trim();
        if (k.length() < 8 || k.length() > 64 || !k.matches("[A-Za-z0-9-]+"))
            throw ApiException.badRequest("幂等键格式不对（只允许字母、数字与短横线，8～64 位）");
    }


    private record Ctx(long buyerUserId, long agentId, int level, String agentName,
                       String skuCode, String skuName, int codeQty, long courseId,
                       long amountFen, Long priceTierId) {}

    private Ctx validate(GrantCmd cmd) {
        if (cmd.copies() < 1 || cmd.copies() > MAX_COPIES)
            throw ApiException.badRequest("一次最多发 " + MAX_COPIES + " 份，请分多次发放");
        if (cmd.reason() == null || cmd.reason().isBlank())
            throw ApiException.badRequest("请填写发放原因：事后有人问「这批码是怎么来的」，只有这一句能答");
        if (cmd.reason().length() > 255)
            throw ApiException.badRequest("发放原因不要超过 255 个字");
        requireGrantKey(cmd.grantKey());

        // 代理必须在有效状态。库层 trg_order_insert 也拦，但它那句话是给排查用的；
        // 这里先拦一道是为了给运营一句能看懂的话。
        var agents = jdbc.queryForList(
                "SELECT p.id, p.user_id, p.level, p.status, p.agent_code, "
              + "       COALESCE(u.real_name, u.nickname, u.phone) AS name "
              + "  FROM agent_profile p JOIN sys_user u ON u.id = p.user_id WHERE p.id=?",
                cmd.agentId());
        if (agents.isEmpty()) throw ApiException.notFound("代理不存在");
        var a = agents.get(0);
        if (!"ACTIVE".equals(a.get("status")))
            throw ApiException.conflict("这家代理当前不是有效状态（" + a.get("status")
                    + "），不能给它发码。先恢复它的状态再来");

        var skus = jdbc.queryForList(
                "SELECT sku_code, name, buyer_type, code_qty, course_id, status FROM sku WHERE id=?",
                cmd.skuId());
        if (skus.isEmpty()) throw ApiException.notFound("套餐不存在");
        var sku = skus.get(0);
        if (!"AGENT".equals(sku.get("buyer_type")))
            throw ApiException.badRequest("这个套餐不是代理档，不能发给代理");
        if (!"ON".equals(sku.get("status")))
            throw ApiException.conflict("这个套餐已下架，请换一个");

        int codeQty = ((Number) sku.get("code_qty")).intValue();
        // 上限的依据是下游：代理端的激活码列表是全量读回再内存分页，导出侧超过 2000 条
        // 会直接 400 让人「缩小范围」，而缩小范围的两个手段在代理端界面上都够不到。
        if ((long) cmd.copies() * codeQty > MAX_CODES)
            throw ApiException.badRequest("一次最多发 " + MAX_CODES + " 个码，本次是 "
                    + cmd.copies() + " × " + codeQty + " = " + (cmd.copies() * codeQty)
                    + " 个，请分多次发放");

        int level = ((Number) a.get("level")).intValue();
        PricingService.Price price;
        try {
            price = pricing.priceOf(level, cmd.skuId());
        } catch (IllegalStateException e) {
            // PricingService 抛的原文带内部编号，不能直接透给运营
            throw ApiException.conflict("「" + sku.get("name") + "」在 " + level
                    + " 级代理这一档没有生效价目。手工发的单也要记目录价（它是「送出去多少钱的货」"
                    + "这个口径的唯一来源），请先在价目表里配好再发码");
        }

        return new Ctx(((Number) a.get("user_id")).longValue(), cmd.agentId(), level,
                String.valueOf(a.get("name")) + "（" + a.get("agent_code") + "）",
                (String) sku.get("sku_code"), (String) sku.get("name"), codeQty,
                ((Number) sku.get("course_id")).longValue(),
                price.amountFen(), price.tierId());
    }

    // ------------------------------------------------------------------ 发放

    /**
     * 整批一个事务。
     *
     * <p>这样 trg_activation_code_quota 里那条不并发安全的 {@code COUNT(*)} 天然串行 ——
     * 与 FulfillService 用一次 batchUpdate 出码是同一个理由。
     */
    private GrantResult doGrant(GrantCmd cmd, Ctx c) {
        List<Long> orderIds = new ArrayList<>();
        List<String> orderNos = new ArrayList<>();
        int issued = 0;
        for (int i = 0; i < cmd.copies(); i++) {
            var created = orders.createForGrant(c.buyerUserId(), c.agentId(), c.level(),
                    cmd.skuId(), c.skuCode(), c.codeQty(), c.amountFen(), c.priceTierId(),
                    cmd.operatorId(), cmd.reason(), cmd.grantKey() + "#" + i, cmd.clientIp());
            orderIds.add(created.orderId());
            orderNos.add(created.orderNo());
            issued += fulfill.issueCodes(created.orderId(), c.courseId(), c.agentId(), c.codeQty());
        }
        return new GrantResult(true, orderIds, orderNos, maskedOf(orderIds), issued,
                c.amountFen() * cmd.copies(), c.agentName(), c.skuName());
    }

    /** 幂等重放：把这个 grantKey 上一次发出来的单与码原样查回来。 */
    private GrantResult replay(GrantCmd cmd, Ctx c) {
        var rows = jdbc.queryForList(
                "SELECT id, order_no, amount_fen FROM biz_order "
              + " WHERE grant_key LIKE ? ORDER BY id", cmd.grantKey() + "#%");
        List<Long> ids = new ArrayList<>();
        List<String> nos = new ArrayList<>();
        long total = 0;
        for (var r : rows) {
            ids.add(((Number) r.get("id")).longValue());
            nos.add((String) r.get("order_no"));
            total += ((Number) r.get("amount_fen")).longValue();
        }
        var codes = maskedOf(ids);
        return new GrantResult(false, ids, nos, codes, codes.size(), total,
                c.agentName(), c.skuName());
    }

    /** 只回掩码。理由见 {@link GrantResult}。 */
    private List<Map<String, Object>> maskedOf(List<Long> orderIds) {
        if (orderIds.isEmpty()) return List.of();
        String in = "?,".repeat(orderIds.size() - 1) + "?";
        return jdbc.query(
                "SELECT id, code_masked, order_id FROM activation_code "
              + " WHERE order_id IN (" + in + ") ORDER BY id",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("codeId", rs.getLong("id"));
                    m.put("masked", rs.getString("code_masked"));
                    m.put("orderId", rs.getLong("order_id"));
                    return m;
                }, orderIds.toArray());
    }
}
