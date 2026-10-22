package tech.dragonai.wangke.admin;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tech.dragonai.wangke.auth.AuthInterceptor;
import tech.dragonai.wangke.trade.PricingService;
import tech.dragonai.wangke.web.ApiException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 超管自由设置进货价：<b>层级价打底 + 某几家代理单独覆盖</b>。
 *
 * <h3>改价的唯一姿势是「封口旧行 + 插新行」</h3>
 * 两张价目表（agent_price_tier / agent_price_override）都是区间表：
 * {@code effective_from/to} + 生成列 {@code active_flag} + 唯一键保证每个
 * (层级|代理, 套餐) 至多一条当前生效。所以改价<b>绝不是 UPDATE 旧行</b> ——
 * UPDATE 旧行在库层是一次完全合法的写入（唯一键不动、CHECK 满意、行数正确），
 * 而按这个价成交过的每一张老订单，再查「当时是什么价」查到的都是新价：
 * 历史被改写，且没有一行日志说它被改过。专属价那一侧已经把这条做成了物理约束
 * （trg_price_override_update），层级价那一侧靠这里这段代码。
 *
 * <h3>★ 这一层唯一会漏钱的判据：平台净收不得为负</h3>
 * 二级代理下单时平台净收 = 二级成交价 − 返给一级的佣金（commission_rule）。
 * 价目一旦设到佣金以下，净收就是负数：平台每卖一单倒贴钱。而这条错的形态是
 * <b>全链路合法</b>的 —— 订单金额 &gt; 0、佣金按规则算出来是正数且冻结期满自动入账、
 * 对账表与总览每一处单看都平；只有把「本月收到的钱」减去「本月付出的佣金」
 * 才看得见缺口，而那是月底的事，那时佣金已与真佣金不可分辨。
 * 所以这里<b>当场算、当场拒</b>，不是警告。库层两个触发器是同一条判据的物理版本，
 * 两层互补：这一层给运营一句算得出来的话（成交 X、佣金 Y、净收 Z），那一层保证绕不过去。
 *
 * <h3>净收 &gt;= 0 但低于同档层级价：摆出来让人确认，不拦</h3>
 * 那是一次正常的议价，拦掉就是把功能关了。但它必须被<b>看见</b>：
 * {@code /preview} 把成交价、佣金、净收、同档层级价、差额一起返回，
 * 前端把这几个数摆进二次确认框。前端自己按公式算是不行的 —— 那是第二套口径，
 * 与 settleCommission 一旦漂移，运营确认的数字和真实结算的数字就不是同一个。
 *
 * <h3>路径必须落在 /api/admin/ 下</h3>
 * AdminGuard 是按路径前缀挂的拦截器。挂到别的前缀下的话那道闸整个不生效，
 * 而接口<b>工作得好好的</b>。每个方法体第一行仍写 requireRole：两道闸互补不重复。
 *
 * <h3>★ 下单链还没有接上专属价</h3>
 * {@code OrderService.create} 今天仍调两参的 {@code priceOf(level, sku)}，
 * 那个文件归另一路改。接线是三步，缺一不可：
 * <ol>
 *   <li>{@code var q = pricing.priceOf(agentId, level, skuId);} 取代原来的 priceOf(level, skuId)；
 *   <li>把 {@code q.tierId()} 与 {@code q.overrideId()} <b>分别</b>写进 biz_order 的
 *       price_tier_id / price_override_id 两列（ck_order_price_source 保证至多一个非空）——
 *       两张表的主键会撞，塞进同一列的表现是复盘时查到一个与 amount_fen 对不上的数；
 *   <li>学员直购（level=0）没有 agentId，仍走两参重载。
 * </ol>
 * 接上之前，专属价只在这一页上算得出来、看得见、有历史，但不影响成交价。
 */
@RestController
@RequestMapping("/api/admin/pricing")
public class AdminPricingController {

    /**
     * 单笔整单价的上限（分）= 100 万元。
     *
     * <p>不是业务规则，是<b>小数点位置的护栏</b>：元⇄分换算错一位就是十倍价差，
     * 而十倍的价目在库层处处合法（CHECK 只管 &gt; 0），代理进货时才会发现，
     * 那时该收的钱已经按错的数收过了。给一个离真实档位（349～3490 元）三个数量级远的上限，
     * 挡住的是「多打了两个 0」这一类，正常议价一个都碰不到。
     */
    static final long MAX_PRICE_FEN = 100_000_000L;

    private final JdbcTemplate jdbc;
    private final PricingService pricing;
    private final AdminAudit audit;
    private final TransactionTemplate tx;

    public AdminPricingController(JdbcTemplate jdbc, PricingService pricing,
                                  AdminAudit audit, TransactionTemplate tx) {
        this.jdbc = jdbc; this.pricing = pricing; this.audit = audit; this.tx = tx;
    }

    public record TierReq(Integer agentLevel, Long skuId, Long totalPriceFen, String reason) {}
    public record OverrideReq(Long agentId, Long skuId, Long totalPriceFen, String reason) {}
    public record PreviewReq(Integer agentLevel, Long agentId, Long skuId, Long totalPriceFen) {}
    public record ReasonReq(String reason) {}

    // ================================================================ 读

    /**
     * 整张价目表：标准价（按层级）+ 专属价（按代理）+ 两者的改价历史。
     *
     * <p>每一行都带上算好的<b>佣金与净收</b>。不带的话前端只能自己按 commission_rule 再算一遍，
     * 那是第二套口径 —— 与 settleCommission 漂移之后，页面上写着「净收 349」而实际结算是别的数，
     * 没有任何东西会报错。
     *
     * <p>历史只出封口过的行，按封口时间倒序。它就是需求方那句「后台有记录就行」的落点：
     * 谁、什么时候、把哪一档从多少改成了多少。
     */
    @GetMapping
    Map<String, Object> pricing(HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");

        List<Map<String, Object>> skus = new ArrayList<>();
        for (var r : jdbc.queryForList(
                "SELECT id, sku_code, name, buyer_type, code_qty, status FROM sku ORDER BY buyer_type, code_qty, id")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("skuId", AdminQuery.id(r.get("id")));
            m.put("skuCode", r.get("sku_code"));
            m.put("name", r.get("name"));
            m.put("buyerType", r.get("buyer_type"));
            m.put("codeQty", AdminQuery.intOf(r.get("code_qty"), 1));
            m.put("status", r.get("status"));
            skus.add(m);
        }

        List<Map<String, Object>> tiers = new ArrayList<>();
        for (var r : jdbc.queryForList(
                "SELECT t.id, t.agent_level, t.sku_id, t.total_price_fen, t.effective_from,"
              + " t.created_by, t.created_at, s.code_qty, s.name AS sku_name, s.sku_code"
              + "  FROM agent_price_tier t JOIN sku s ON s.id=t.sku_id"
              + " WHERE t.effective_to IS NULL ORDER BY t.agent_level, s.code_qty, t.sku_id")) {
            int level = AdminQuery.intOf(r.get("agent_level"), 0);
            long price = AdminQuery.longOf(r.get("total_price_fen"), 0);
            var net = pricing.netOf(level, level == 2, AdminQuery.longOf(r.get("sku_id"), 0),
                    price, AdminQuery.intOf(r.get("code_qty"), 1));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", AdminQuery.id(r.get("id")));
            m.put("agentLevel", level);
            m.put("skuId", AdminQuery.id(r.get("sku_id")));
            m.put("skuCode", r.get("sku_code"));
            m.put("skuName", r.get("sku_name"));
            m.put("codeQty", AdminQuery.intOf(r.get("code_qty"), 1));
            m.put("totalPriceFen", price);
            putNet(m, net);
            m.put("effectiveFrom", r.get("effective_from"));
            m.put("createdAt", r.get("created_at"));
            m.put("createdBy", AdminQuery.id(r.get("created_by")));
            tiers.add(m);
        }

        List<Map<String, Object>> overrides = new ArrayList<>();
        for (var r : jdbc.queryForList(overrideSelect() + " WHERE o.effective_to IS NULL"
              + " ORDER BY o.agent_id, s.code_qty, o.sku_id")) {
            overrides.add(overrideRow(r, true));
        }

        List<Map<String, Object>> history = new ArrayList<>();
        for (var r : jdbc.queryForList(overrideSelect() + " WHERE o.effective_to IS NOT NULL"
              + " ORDER BY o.effective_to DESC, o.id DESC LIMIT 200")) {
            history.add(overrideRow(r, false));
        }

        List<Map<String, Object>> tierHistory = new ArrayList<>();
        for (var r : jdbc.queryForList(
                "SELECT t.id, t.agent_level, t.sku_id, t.total_price_fen, t.effective_from,"
              + " t.effective_to, t.created_by, t.created_at, s.name AS sku_name, s.sku_code"
              + "  FROM agent_price_tier t JOIN sku s ON s.id=t.sku_id"
              + " WHERE t.effective_to IS NOT NULL"
              + " ORDER BY t.effective_to DESC, t.id DESC LIMIT 200")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", AdminQuery.id(r.get("id")));
            m.put("agentLevel", AdminQuery.intOf(r.get("agent_level"), 0));
            m.put("skuId", AdminQuery.id(r.get("sku_id")));
            m.put("skuCode", r.get("sku_code"));
            m.put("skuName", r.get("sku_name"));
            m.put("totalPriceFen", AdminQuery.longOf(r.get("total_price_fen"), 0));
            m.put("effectiveFrom", r.get("effective_from"));
            m.put("effectiveTo", r.get("effective_to"));
            m.put("createdBy", AdminQuery.id(r.get("created_by")));
            tierHistory.add(m);
        }

        // 只出还在合作的代理：已终止的代理挑出来定价，定完取不到（下单那一侧先被状态闸拦下），
        // 而这一页会把它显示成「已生效」。
        List<Map<String, Object>> agents = new ArrayList<>();
        for (var r : jdbc.queryForList(
                "SELECT p.id, p.agent_code, p.level, p.status,"
              + "       COALESCE(p.company_name, u.real_name, u.nickname, u.phone) AS name"
              + "  FROM agent_profile p JOIN sys_user u ON u.id=p.user_id"
              + " WHERE p.status='ACTIVE' ORDER BY p.level, p.id")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("agentId", AdminQuery.id(r.get("id")));
            m.put("agentCode", r.get("agent_code"));
            m.put("name", r.get("name"));
            m.put("level", AdminQuery.intOf(r.get("level"), 0));
            agents.add(m);
        }

        return AdminJson.ok("skus", skus, "tiers", tiers, "overrides", overrides,
                "overrideHistory", history, "tierHistory", tierHistory, "agents", agents);
    }

    private static String overrideSelect() {
        return "SELECT o.id, o.agent_id, o.sku_id, o.total_price_fen, o.effective_from,"
             + " o.effective_to, o.reason, o.created_by, o.created_at,"
             + " s.code_qty, s.name AS sku_name, s.sku_code,"
             + " p.agent_code, p.level, p.parent_agent_id, p.status AS agent_status,"
             + " COALESCE(p.company_name, u.real_name, u.nickname, u.phone) AS agent_name"
             + "  FROM agent_price_override o"
             + "  JOIN sku s ON s.id=o.sku_id"
             + "  JOIN agent_profile p ON p.id=o.agent_id"
             + "  JOIN sys_user u ON u.id=p.user_id";
    }

    private Map<String, Object> overrideRow(Map<String, Object> r, boolean withNet) {
        Map<String, Object> m = new LinkedHashMap<>();
        long skuId = AdminQuery.longOf(r.get("sku_id"), 0);
        long price = AdminQuery.longOf(r.get("total_price_fen"), 0);
        int level = AdminQuery.intOf(r.get("level"), 0);
        m.put("id", AdminQuery.id(r.get("id")));
        m.put("agentId", AdminQuery.id(r.get("agent_id")));
        m.put("agentCode", r.get("agent_code"));
        m.put("agentName", r.get("agent_name"));
        m.put("agentStatus", r.get("agent_status"));
        m.put("level", level);
        m.put("skuId", skuId);
        m.put("skuCode", r.get("sku_code"));
        m.put("skuName", r.get("sku_name"));
        m.put("codeQty", AdminQuery.intOf(r.get("code_qty"), 1));
        m.put("totalPriceFen", price);
        if (withNet) {
            var net = pricing.netOf(level, r.get("parent_agent_id") != null, skuId, price,
                    AdminQuery.intOf(r.get("code_qty"), 1));
            putNet(m, net);
            // 同档层级价摆在旁边。一个专属价单看是个孤零零的数字，改错一位没人看得出来；
            // 摆上标准价，「便宜了 50」和「便宜了 500」一眼就分得开。
            long tierFen = tierPriceFen(level, skuId);
            m.put("tierPriceFen", tierFen < 0 ? null : tierFen);
            m.put("belowTier", tierFen >= 0 && price < tierFen);
        }
        m.put("reason", r.get("reason"));
        m.put("effectiveFrom", r.get("effective_from"));
        m.put("effectiveTo", r.get("effective_to"));
        m.put("createdAt", r.get("created_at"));
        m.put("createdBy", AdminQuery.id(r.get("created_by")));
        return m;
    }

    private static void putNet(Map<String, Object> m, PricingService.Net net) {
        m.put("commissionFen", net.commissionFen());
        m.put("netFen", net.netFen());
        m.put("calcMode", net.rule() == null ? null : net.rule().calcMode());
    }

    /** 同档层级价；这一档还没配价目时返回 -1（新套餐上线那一刻就是这个状态，不是错）。 */
    private long tierPriceFen(int agentLevel, long skuId) {
        var rows = jdbc.queryForList(
                "SELECT total_price_fen FROM agent_price_tier"
              + " WHERE agent_level=? AND sku_id=? AND effective_to IS NULL", agentLevel, skuId);
        return rows.isEmpty() ? -1 : AdminQuery.longOf(rows.get(0).get("total_price_fen"), 0);
    }

    // ================================================================ 试算

    /**
     * 试算：这个价定下去，平台每单净收多少。
     *
     * <p>前端在输入框里边打边调它，二次确认框里摆的就是这里返回的数。
     * <b>不让前端自己按公式算</b>：那是第二套口径，与 settleCommission 一旦漂移，
     * 运营确认的数字和真实结算的数字就不是同一个，而两边都不会报错。
     */
    @PostMapping("/preview")
    Map<String, Object> preview(@RequestBody(required = false) PreviewReq r, HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        if (r == null) throw ApiException.badRequest("请求体为空");
        long priceFen = requirePrice(r.totalPriceFen());
        Sku sku = requireSku(r.skuId());

        int level;
        boolean hasParent;
        if (r.agentId() != null) {
            Agent a = requireAgent(r.agentId());
            level = a.level(); hasParent = a.hasParent();
        } else {
            level = requireLevel(r.agentLevel(), sku);
            hasParent = level == 2;
        }

        var net = pricing.netOf(level, hasParent, sku.id(), priceFen, sku.codeQty());
        long tierFen = tierPriceFen(level, sku.id());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalPriceFen", priceFen);
        putNet(m, net);
        m.put("tierPriceFen", tierFen < 0 ? null : tierFen);
        m.put("belowTier", tierFen >= 0 && priceFen < tierFen);
        m.put("negative", net.netFen() < 0);
        m.put("codeQty", sku.codeQty());
        m.put("blocker", net.netFen() < 0 ? negativeMessage(net, "这个价") : null);
        return AdminJson.ok("preview", m);
    }

    // ================================================================ 写

    /** 改一档层级价（标准价）。这一档下所有没有专属价的代理立刻按新价进货。 */
    @PostMapping("/tiers")
    Map<String, Object> setTier(@RequestBody(required = false) TierReq r, HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        if (r == null) throw ApiException.badRequest("请求体为空");
        long priceFen = requirePrice(r.totalPriceFen());
        String reason = requireReason(r.reason());
        Sku sku = requireSku(r.skuId());
        int level = requireLevel(r.agentLevel(), sku);

        var net = pricing.netOf(level, level == 2, sku.id(), priceFen, sku.codeQty());
        refuseIfNegative(net, "这一档的层级价");

        long operator = AuthInterceptor.userId(req);
        var before = jdbc.queryForList(
                "SELECT id, total_price_fen FROM agent_price_tier"
              + " WHERE agent_level=? AND sku_id=? AND effective_to IS NULL", level, sku.id());
        // 双击保护。同一个价再提交一次，重建一条「新价 = 旧价」的历史行只会把改价史弄脏，
        // 而运营看到的两次都是成功 —— 事后翻历史时那一行看起来像一次真的改动。
        if (!before.isEmpty() && AdminQuery.longOf(before.get(0).get("total_price_fen"), -1) == priceFen)
            return AdminJson.ok("changed", false, "netFen", net.netFen(),
                    "message", "这一档现在就是这个价，没有改动");

        long newId = replacePrice(
                "UPDATE agent_price_tier SET effective_to=NOW(3)"
              + " WHERE agent_level=? AND sku_id=? AND effective_to IS NULL",
                new Object[]{level, sku.id()},
                "INSERT INTO agent_price_tier (agent_level,sku_id,total_price_fen,"
              + "effective_from,created_by) VALUES (?,?,?,NOW(3),?)",
                new Object[]{level, sku.id(), priceFen, operator},
                "这一档同时被另一个人改了");

        audit.write(req, "PRICE_TIER_SET", "sku", sku.id(),
                before.isEmpty() ? null
                        : AdminJson.map("tierId", AdminQuery.id(before.get(0).get("id")),
                                "totalPriceFen", AdminQuery.longOf(before.get(0).get("total_price_fen"), 0)),
                AdminJson.map("tierId", newId, "agentLevel", level, "skuId", sku.id(),
                        "totalPriceFen", priceFen, "commissionFen", net.commissionFen(),
                        "netFen", net.netFen(), "reason", reason));

        return AdminJson.ok("changed", true, "tierId", newId,
                "totalPriceFen", priceFen, "commissionFen", net.commissionFen(),
                "netFen", net.netFen(),
                "message", "标准价已改成 " + AdminQuery.yuan(priceFen) + "，平台每单净收 "
                        + AdminQuery.yuan(net.netFen()) + "。已经下过的订单不受影响");
    }

    /** 给某一家代理设专属价。没有专属价的代理仍走层级价。 */
    @PostMapping("/overrides")
    Map<String, Object> setOverride(@RequestBody(required = false) OverrideReq r,
                                    HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        if (r == null) throw ApiException.badRequest("请求体为空");
        long priceFen = requirePrice(r.totalPriceFen());
        String reason = requireReason(r.reason());
        Sku sku = requireSku(r.skuId());
        Agent agent = requireAgent(r.agentId());
        if (!"AGENT".equals(sku.buyerType()))
            throw ApiException.badRequest("「" + sku.name() + "」不是代理档，代理买不了它，"
                    + "给它设专属价不会有任何效果");

        var net = pricing.netOf(agent.level(), agent.hasParent(), sku.id(), priceFen, sku.codeQty());
        refuseIfNegative(net, "这家代理的专属价");

        long operator = AuthInterceptor.userId(req);
        var before = jdbc.queryForList(
                "SELECT id, total_price_fen FROM agent_price_override"
              + " WHERE agent_id=? AND sku_id=? AND effective_to IS NULL", agent.id(), sku.id());
        if (!before.isEmpty() && AdminQuery.longOf(before.get(0).get("total_price_fen"), -1) == priceFen)
            return AdminJson.ok("changed", false, "netFen", net.netFen(),
                    "message", "这家代理的这一档现在就是这个价，没有改动");

        long newId = replacePrice(
                "UPDATE agent_price_override SET effective_to=NOW(3)"
              + " WHERE agent_id=? AND sku_id=? AND effective_to IS NULL",
                new Object[]{agent.id(), sku.id()},
                "INSERT INTO agent_price_override (agent_id,sku_id,total_price_fen,"
              + "effective_from,reason,created_by) VALUES (?,?,?,NOW(3),?,?)",
                new Object[]{agent.id(), sku.id(), priceFen, reason, operator},
                "这家代理的这一档同时被另一个人改了");

        long tierFen = tierPriceFen(agent.level(), sku.id());
        // 审计挂在 agent_profile 上，不是 sku：运营查「这家凭什么是这个价」会去代理详情页，
        // 而那一页的审计流水按 target_type='agent_profile' 查。挂到套餐上的话，
        // 这次定价在那一页上完全看不到，而纠纷复盘时问的第一句就是这个。
        audit.write(req, "AGENT_PRICE_OVERRIDE_SET", "agent_profile", agent.id(),
                before.isEmpty() ? null
                        : AdminJson.map("overrideId", AdminQuery.id(before.get(0).get("id")),
                                "totalPriceFen", AdminQuery.longOf(before.get(0).get("total_price_fen"), 0)),
                AdminJson.map("overrideId", newId, "skuId", sku.id(), "skuName", sku.name(),
                        "totalPriceFen", priceFen, "tierPriceFen", tierFen < 0 ? null : tierFen,
                        "commissionFen", net.commissionFen(), "netFen", net.netFen(),
                        "reason", reason));

        return AdminJson.ok("changed", true, "overrideId", newId,
                "totalPriceFen", priceFen, "commissionFen", net.commissionFen(),
                "netFen", net.netFen(), "tierPriceFen", tierFen < 0 ? null : tierFen,
                "message", "已给 " + agent.name() + " 定价 " + AdminQuery.yuan(priceFen)
                        + "，平台每单净收 " + AdminQuery.yuan(net.netFen())
                        + "。已经下过的订单不受影响");
    }

    /**
     * 撤销专属价：给它封口，这家代理回落到层级价。
     *
     * <p><b>不是 DELETE。</b>删行等于把「这家曾经按 300 一单进过货」这件事从库里抹掉，
     * 而那正是老订单复盘时要查的东西 —— 订单上的 price_override_id 会指向一个不存在的行。
     */
    @PostMapping("/overrides/{id}/close")
    Map<String, Object> closeOverride(@PathVariable long id,
                                      @RequestBody(required = false) ReasonReq r,
                                      HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        String reason = requireReason(r == null ? null : r.reason());

        var rows = jdbc.queryForList(
                "SELECT o.id, o.agent_id, o.sku_id, o.total_price_fen, o.effective_to, p.level"
              + "  FROM agent_price_override o JOIN agent_profile p ON p.id=o.agent_id"
              + " WHERE o.id=?", id);
        if (rows.isEmpty()) throw ApiException.notFound("这条专属价不存在");
        var row = rows.get(0);
        // 已经封口的再封一次回 200 changed:false，不回 409：后台按钮会被双击，
        // 把第二次点击变成红色错误框的话，运营的第一反应是「刚才那次没生效」。
        if (row.get("effective_to") != null)
            return AdminJson.ok("changed", false, "message", "这条专属价已经撤销过了，没有改动");

        int n;
        try {
            n = jdbc.update("UPDATE agent_price_override SET effective_to=NOW(3)"
                          + " WHERE id=? AND effective_to IS NULL", id);
        } catch (DataAccessException e) {
            throw DbSignal.explain(e, "这条专属价没有撤销，库里也没有留下半点改动");
        }
        if (n == 0) return AdminJson.ok("changed", false, "message", "这条专属价已经撤销过了，没有改动");

        long agentId = AdminQuery.longOf(row.get("agent_id"), 0);
        long skuId = AdminQuery.longOf(row.get("sku_id"), 0);
        long tierFen = tierPriceFen(AdminQuery.intOf(row.get("level"), 0), skuId);
        audit.write(req, "AGENT_PRICE_OVERRIDE_CLOSE", "agent_profile", agentId,
                AdminJson.map("overrideId", id, "skuId", skuId,
                        "totalPriceFen", AdminQuery.longOf(row.get("total_price_fen"), 0)),
                AdminJson.map("fallbackTierPriceFen", tierFen < 0 ? null : tierFen,
                        "reason", reason));

        return AdminJson.ok("changed", true,
                "fallbackTierPriceFen", tierFen < 0 ? null : tierFen,
                "message", tierFen < 0
                        ? "已撤销。注意：这一档现在连层级价都没有配，这家代理买不了它"
                        : "已撤销，这家代理回到标准价 " + AdminQuery.yuan(tierFen)
                          + "。已经下过的订单不受影响");
    }

    // ================================================================ 校验与工具

    /**
     * 封口旧行 + 插新行，<b>一个事务</b>。
     *
     * <p>不在一个事务里的话，封口成功、插入失败（比如撞上唯一键或触发器）就会留下一个
     * 「这一档当前没有任何生效价」的空档 —— PricingService.priceOf 查到 0 行直接抛异常，
     * 表现是这一档的代理<b>下单整个报 500</b>，而后台价目页上什么都不显示，
     * 看起来像数据被谁删了。
     */
    private long replacePrice(String sealSql, Object[] sealArgs,
                              String insertSql, Object[] insertArgs, String raceHint) {
        try {
            Long id = tx.execute(st -> {
                jdbc.update(sealSql, sealArgs);
                return AdminQuery.insert(jdbc, insertSql, insertArgs);
            });
            if (id == null) throw new IllegalStateException("改价事务没有返回新价目的 id");
            return id;
        } catch (DuplicateKeyException e) {
            // uk_active_price / uk_active_override 是并发改价的裁决点：两个人同时改，
            // 只有一个人的封口 + 插入能成。另一个人必须看到「有人同时在改」，
            // 而不是一句看不懂的唯一键冲突。
            throw ApiException.conflict(raceHint + "，你这次没有生效。刷新看一下现在的价再决定");
        } catch (DataAccessException e) {
            // ★ catch 的必须是 DataAccessException 不是 DataIntegrityViolationException：
            // 触发器的 SIGNAL 走自定义 SQLSTATE 45000，Spring 冒上来的是
            // UncategorizedSQLException，catch 错了就是一段永远不执行的守卫。
            throw DbSignal.explain(e, "价目没有改动，库里也没有留下半行");
        }
    }

    /**
     * ★ 平台净收为负 = 每卖一单倒贴钱。<b>直接拒，不是警告。</b>
     *
     * <p>库层两个触发器也拦（trg_price_tier_precheck / trg_price_override_precheck），
     * 两道互补：这一道给运营一句算得出来的话，那一道保证绕不过去。
     * 只留库层那道的话，运营看到的是一句触发器原文；只留这一道的话，
     * 任何一条直接 INSERT（以后的批量导价、临时改一下）都能把亏本价放进去。
     */
    private void refuseIfNegative(PricingService.Net net, String what) {
        if (net.netFen() >= 0) return;
        throw ApiException.conflict(negativeMessage(net, what));
    }

    /** @param what 主语，比如「这家代理的专属价」。整句必须能被运营直接拿去找佣金规则。 */
    private static String negativeMessage(PricingService.Net net, String what) {
        return what + "会让平台每卖一单倒贴 " + AdminQuery.yuan(-net.netFen()) + "："
             + "成交 " + AdminQuery.yuan(net.priceFen()) + "，"
             + "要返给上级代理的佣金是 " + AdminQuery.yuan(net.commissionFen()) + "。"
             + "这笔亏空在订单、佣金、对账表上处处合法，只有月底汇总才看得见 —— "
             + "先把佣金规则调下来，再来定这个价";
    }

    private static long requirePrice(Long fen) {
        if (fen == null) throw ApiException.badRequest("请填价格");
        if (fen <= 0) throw ApiException.badRequest("价格必须大于 0。要白送请走「手工发码」，"
                + "那条路记的是目录价、实付 0，账才是对的");
        if (fen > MAX_PRICE_FEN)
            throw ApiException.badRequest("价格 " + AdminQuery.yuan(fen)
                    + " 看起来多打了几位。元和分换算错一位就是十倍价差，而十倍的价目在库里完全合法，"
                    + "要等代理进货时才发现");
        return fen;
    }

    private static String requireReason(String reason) {
        if (AdminQuery.blank(reason))
            throw ApiException.badRequest("请填写改价原因：事后有人问「这家凭什么是这个价」，"
                    + "只有这一句能答");
        if (reason.trim().length() > 255) throw ApiException.badRequest("改价原因不要超过 255 个字");
        return reason.trim();
    }

    private record Sku(long id, String skuCode, String name, String buyerType, int codeQty) {}
    private record Agent(long id, String name, int level, boolean hasParent) {}

    private Sku requireSku(Long skuId) {
        if (skuId == null) throw ApiException.badRequest("请选择套餐");
        var rows = jdbc.queryForList(
                "SELECT id, sku_code, name, buyer_type, code_qty FROM sku WHERE id=?", skuId);
        if (rows.isEmpty()) throw ApiException.notFound("套餐不存在");
        var s = rows.get(0);
        return new Sku(AdminQuery.longOf(s.get("id"), 0), (String) s.get("sku_code"),
                (String) s.get("name"), (String) s.get("buyer_type"),
                AdminQuery.intOf(s.get("code_qty"), 1));
    }

    private Agent requireAgent(Long agentId) {
        if (agentId == null) throw ApiException.badRequest("请选择代理");
        var rows = jdbc.queryForList(
                "SELECT p.id, p.level, p.parent_agent_id, p.status,"
              + "       COALESCE(p.company_name, u.real_name, u.nickname, u.phone) AS name"
              + "  FROM agent_profile p JOIN sys_user u ON u.id=p.user_id WHERE p.id=?", agentId);
        if (rows.isEmpty()) throw ApiException.notFound("代理不存在");
        var a = rows.get(0);
        if (!"ACTIVE".equals(a.get("status")))
            throw ApiException.conflict("这家代理当前不是有效状态（" + a.get("status") + "），"
                    + "它现在下不了单，定的价也取不到。先恢复它的状态再来");
        return new Agent(AdminQuery.longOf(a.get("id"), 0), String.valueOf(a.get("name")),
                AdminQuery.intOf(a.get("level"), 0), a.get("parent_agent_id") != null);
    }

    /**
     * 层级与套餐的买家类型必须对上。
     *
     * <p>对不上的组合（比如给学员直购档配一条「一级代理价」）在库层完全合法，
     * 而取价那一侧永远查不到它：OrderService 对 STUDENT 档恒用 level=0，对 AGENT 档用代理层级。
     * 表现是运营改完价、页面显示已生效，而代理那边一分钱没变 —— 没有任何报错。
     */
    private static int requireLevel(Integer level, Sku sku) {
        if (level == null) throw ApiException.badRequest("请选择这是哪一层的价");
        if (level != 0 && level != 1 && level != 2)
            throw ApiException.badRequest("层级只有 0（学员直购）、1（一级代理）、2（二级代理）");
        boolean student = "STUDENT".equals(sku.buyerType());
        if (student && level != 0)
            throw ApiException.badRequest("「" + sku.name() + "」是学员直购档，只有零售价这一档（层级 0）。"
                    + "配成代理档的话取价那一侧永远查不到它，改完价代理那边一分钱不变");
        if (!student && level == 0)
            throw ApiException.badRequest("「" + sku.name() + "」是代理档，学员买不了它，"
                    + "给它配零售价不会有任何效果");
        return level;
    }
}
