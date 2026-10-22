package tech.dragonai.wangke.trade;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 取价与取佣金规则。
 *
 * <p><b>只取"当前生效"的那一行</b>（effective_to IS NULL）。schema 用生成列 +
 * 唯一键保证了每个 (层级, 套餐) 至多一行当前生效，所以这里查出多行就是数据出了问题，
 * 必须炸而不是"取第一条" —— 取价二义会让不同订单按不同价成交，事后无法解释。
 *
 * <p>取到的价与规则必须由调用方<b>写进订单快照</b>。改价改规则是常态，
 * 而历史订单要能按当时的价复盘、按当时的规则对账。
 */
public class PricingService {

    private final JdbcTemplate jdbc;

    public PricingService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Price(long tierId, long amountFen) {}
    public record Rule(long ruleId, String calcMode, Long fixedPerCodeFen,
                       Integer rateBp, int freezeDays) {

        /** 佣金 = 按码固定 × 码数，或 实付金额 × 基点/10000。 */
        public long computeFen(long paidFen, int codeQty) {
            if ("FIXED_PER_CODE".equals(calcMode)) {
                if (fixedPerCodeFen == null) throw new IllegalStateException("规则声明按码固定却没有金额");
                return fixedPerCodeFen * codeQty;
            }
            if ("RATE_OF_PAID".equals(calcMode)) {
                if (rateBp == null) throw new IllegalStateException("规则声明按比例却没有基点");
                // 按实付而非标价：打折成交时不能按原价返佣，否则平台倒贴
                return paidFen * rateBp / 10_000;
            }
            throw new IllegalStateException("未知的佣金算法：" + calcMode);
        }
    }

    /** agentLevel：1=一级 2=二级 0=学员直购 */
    public Price priceOf(int agentLevel, long skuId) {
        var rows = jdbc.queryForList(
                "SELECT id, total_price_fen FROM agent_price_tier "
              + "WHERE agent_level=? AND sku_id=? AND effective_to IS NULL", agentLevel, skuId);
        if (rows.isEmpty())
            throw new IllegalStateException("没有当前生效的价目：层级 " + agentLevel + " 套餐 " + skuId);
        if (rows.size() > 1)
            throw new IllegalStateException("取价二义：层级 " + agentLevel + " 套餐 " + skuId
                    + " 有 " + rows.size() + " 条当前生效价，唯一键失守");
        return new Price(((Number) rows.get(0).get("id")).longValue(),
                         ((Number) rows.get(0).get("total_price_fen")).longValue());
    }

    /**
     * 某一家代理实际适用的价：<b>专属价优先，没有则回落层级价</b>。
     *
     * <p>专属价（agent_price_override）是给谈过价的那几家单独设的，没设的代理走层级价。
     * 撤销专属价 = 给那一行封口，取价自然回落 —— 所以这里只认 {@code effective_to IS NULL}，
     * 和层级价那一侧是同一条规矩。
     *
     * <p><b>旧的两参 {@link #priceOf(int, long)} 一个字节都没动</b>：OrderService 与
     * ManualIssueService 都在调它，改签名会把那两条链一起弄红。这是一个纯新增的重载。
     *
     * <p>★ <b>调用方要把结果写进订单快照时，不能拿 {@code tierId} 一了百了。</b>
     * 两张表的主键会撞：17 号既可能是层级价也可能是专属价，而两行的金额不同。
     * 所以 biz_order 上是两列（price_tier_id / price_override_id，CHECK 保证至多一个非空），
     * 按 {@link Quote#fromOverride()} 分别写。写错的表现不是报错，是复盘时按
     * price_tier_id 查到一个与 amount_fen 对不上的数，看起来像数据损坏。
     *
     * <p>★ <b>今天生产的下单链还没有调它。</b>OrderService.create 仍走两参重载（那两个文件
     * 归另一路改）。也就是说专属价现在只在后台价目页上算得出来、看得见、有历史，
     * 但还不影响成交价 —— 这是已知的半成品状态，接线方式写在 AdminPricingController 的类注释里。
     *
     * @param agentLevel 代理层级，回落层级价时用。传错层级会取到另一层的价，
     *                   而金额是个合法的数 —— 所以由调用方从 agent_profile 上取，别猜
     */
    public Quote priceOf(long agentId, int agentLevel, long skuId) {
        Price tier = priceOf(agentLevel, skuId);
        var rows = jdbc.queryForList(
                "SELECT id, total_price_fen FROM agent_price_override "
              + "WHERE agent_id=? AND sku_id=? AND effective_to IS NULL", agentId, skuId);
        if (rows.size() > 1)
            throw new IllegalStateException("专属价二义：代理 " + agentId + " 套餐 " + skuId
                    + " 有 " + rows.size() + " 条当前生效价，uk_active_override 失守");
        if (rows.isEmpty())
            return new Quote(tier.amountFen(), tier.tierId(), null, tier.amountFen());
        return new Quote(((Number) rows.get(0).get("total_price_fen")).longValue(),
                         null, ((Number) rows.get(0).get("id")).longValue(), tier.amountFen());
    }

    /**
     * 某家代理某个套餐的取价结果。
     *
     * @param amountFen     实际适用的价（专属价或层级价）
     * @param tierId        走层级价时的来源行；走专属价时为 null
     * @param overrideId    走专属价时的来源行；走层级价时为 null
     * @param tierAmountFen 同档层级价，<b>永远有值</b>。界面要拿它和 amountFen 摆在一起，
     *                      让人看见「这家比标准价便宜/贵了多少」；没有这个数的话，
     *                      一个专属价单看是个孤零零的数字，改错一位没人看得出来
     */
    public record Quote(long amountFen, Long tierId, Long overrideId, long tierAmountFen) {
        public boolean fromOverride() { return overrideId != null; }
    }

    /**
     * 平台净收 = 成交价 − 返给上级的佣金。<b>设价接口的硬闸就是它 &lt; 0。</b>
     *
     * <p>口径逐字对齐 {@link FulfillService} 里的 settleCommission：只有「买家是二级、
     * 有上级、且规则表配了」才真的返佣金；算出来 &lt;= 0 时那边直接不插行，
     * 所以这里也把负数钳到 0 —— 否则一条负佣金的规则会被算成「平台还多赚了」，
     * 而实际一分钱都不会返出去。
     *
     * <p>库层 trg_price_tier_precheck / trg_price_override_precheck 是同一条判据的物理版本。
     * 两层都要有：这一层给运营一句算得出来的话（成交 X、佣金 Y、净收 Z），
     * 那一层保证绕不过去。
     */
    public Net netOf(int buyerLevel, boolean hasParent, long skuId, long priceFen, int codeQty) {
        Rule rule = hasParent ? ruleOf(buyerLevel, skuId) : null;
        long comm = rule == null ? 0 : Math.max(0, rule.computeFen(priceFen, codeQty));
        return new Net(priceFen, comm, priceFen - comm, rule);
    }

    /** @param rule 算这笔佣金用的规则；不返佣金时为 null（界面要据此说「这一档不返佣金」） */
    public record Net(long priceFen, long commissionFen, long netFen, Rule rule) {}

    /** 下级层级 buyerLevel 下单时，其上级适用的返佣规则。没有规则返回 null（该档不返佣）。 */
    public Rule ruleOf(int buyerLevel, long skuId) {
        var rows = jdbc.queryForList(
                "SELECT id, calc_mode, fixed_per_code_fen, rate_bp, freeze_days FROM commission_rule "
              + "WHERE buyer_level=? AND sku_id=? AND effective_to IS NULL", buyerLevel, skuId);
        if (rows.isEmpty()) return null;
        if (rows.size() > 1)
            throw new IllegalStateException("佣金规则二义：下单层级 " + buyerLevel + " 套餐 " + skuId);
        var r = rows.get(0);
        return new Rule(((Number) r.get("id")).longValue(),
                (String) r.get("calc_mode"),
                r.get("fixed_per_code_fen") == null ? null : ((Number) r.get("fixed_per_code_fen")).longValue(),
                r.get("rate_bp") == null ? null : ((Number) r.get("rate_bp")).intValue(),
                ((Number) r.get("freeze_days")).intValue());
    }

    /**
     * 元 → 分。<b>遇到三位及以上小数必须报错，不得四舍五入。</b>
     *
     * <p>这是 schema 头部记的那条"数据库拦不住、必须由应用层守"的合同：
     * '12.345' 写进 BIGINT 列会被 MySQL 静默截断成 12，STRICT_TRANS_TABLES 也不报错，
     * 且类型转换发生在 CHECK 与触发器求值【之前】，DB 层没有任何拦截点。
     * 少收的钱不会留下任何日志。
     */
    public static long yuanToFen(String yuan) {
        if (yuan == null || yuan.isBlank()) throw new IllegalArgumentException("金额为空");
        var bd = new java.math.BigDecimal(yuan.trim());
        if (bd.scale() > 2)
            throw new IllegalArgumentException(
                    "金额 " + yuan + " 精确到分以下，拒绝四舍五入：请上游给出确切的分值");
        return bd.movePointRight(2).longValueExact();
    }
}
