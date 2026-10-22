package tech.dragonai.wangke.agent;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tech.dragonai.wangke.web.ApiException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 「这笔佣金是怎么来的」弹窗（{@code GET /api/agent/commissions/{id}}）。
 *
 * <h3>只读快照，一个数都不重算</h3>
 * 佣金在成交那一刻按<b>当时</b>的规则算好并存了快照（calc_mode_snapshot / calc_basis_fen /
 * amount_fen）。展示端按现行 commission_rule 再算一次的话，改过规则之后两个数字就会对不上，
 * <b>而对不上的那一方永远是页面上显示的那个</b> —— 代理拿着截图来对账，平台拿不出解释。
 *
 * <h3>下级的身份信息只给推广码与"名称"，绝不回退到实名或手机号</h3>
 * company_name / contact_name 都为空时 sourceAgentName 就是 null，前端显示"下级代理"。
 * 回退到 {@code sys_user.real_name} 或 {@code phone} 等于把身份证姓名与联系方式
 * 交到上级手里 —— 上下级私下往来是分销体系里最难查的一类纠纷。
 * {@code AgentService.me} 对<b>上级</b>信息就是这么处理的，这里对<b>下级</b>要对称。
 */
@Service
public class AgentCommissionDetailService {

    private final JdbcTemplate jdbc;
    private final AgentService agents;

    public AgentCommissionDetailService(JdbcTemplate jdbc, AgentService agents) {
        this.jdbc = jdbc; this.agents = agents;
    }

    /**
     * 佣金详情。与 {@code /api/agent/commissions} 列表同一道门（详情是从列表点进来的），
     * 所以同样要求 status=ACTIVE。
     *
     * <p>越权条件 {@code c.beneficiary_agent_id = 我} 写在 WHERE 里，
     * "不是我的"与"不存在"回同一句 404：给两种不同的回答，这个接口就变成了
     * 一个佣金 id 的存在性探针，顺着枚举能数出平台一共发了多少笔佣金、金额分布在哪一段。
     *
     * @param id commission_record.id
     */
    public Map<String, Object> detail(long userId, long id) {
        AgentService.Agent a = agents.requireOperational(userId);

        var rows = jdbc.query(
                "SELECT c.id, c.amount_fen, c.reversed_fen, c.status, c.order_id, c.order_no,"
              + " c.calc_mode_snapshot, c.calc_basis_fen, c.freeze_until, c.settled_at,"
              + " c.created_at, c.revoke_reason,"
              + " src.agent_code AS source_agent_code,"
              + " COALESCE(src.company_name, src.contact_name) AS source_agent_name,"
              + " w.withdraw_no"
              + " FROM commission_record c"
              + " LEFT JOIN agent_profile src ON src.id = c.source_agent_id"
              + " LEFT JOIN withdraw_order w ON w.id = c.withdraw_order_id"
              + " WHERE c.id=? AND c.beneficiary_agent_id=?",
                (rs, i) -> {
                    var m = new LinkedHashMap<String, Object>();
                    m.put("id", rs.getLong("id"));
                    m.put("amountFen", rs.getLong("amount_fen"));
                    m.put("reversedFen", rs.getLong("reversed_fen"));
                    m.put("netAmountFen", rs.getLong("amount_fen") - rs.getLong("reversed_fen"));
                    m.put("status", rs.getString("status"));
                    m.put("orderId", rs.getLong("order_id"));
                    m.put("orderNo", rs.getString("order_no"));
                    // source_agent_id 为 NULL（学员直购归属那类佣金）时两个字段都消失，
                    // 前端显示"下级代理"。
                    m.put("sourceAgentCode", rs.getString("source_agent_code"));
                    m.put("sourceAgentName", rs.getString("source_agent_name"));
                    // 字段名按 TS 抄：calcModeSnapshot / calcBasisFen。
                    // 既有列表接口出的是 calcMode / basisFen，两套名并存会让人以为是两个数
                    // —— 已作为待拍板事项上报，不在这里擅自改列表。
                    String mode = rs.getString("calc_mode_snapshot");
                    m.put("calcModeSnapshot", mode);
                    // 基数只在【按实付比例】结算时才是核对佣金所必需的。固定每码那一档
                    // 拿它没有用处，而它恰好就等于下级的进货价 —— 出了它，上面那几行
                    // 收掉 amountFen/paidAmountFen 就白做了。
                    if ("RATE_OF_PAID".equals(mode)) m.put("calcBasisFen", rs.getLong("calc_basis_fen"));
                    // freeze_until / created_at 是 NOT NULL，可直接取；settled_at 可空。
                    m.put("freezeUntil", rs.getObject("freeze_until", LocalDateTime.class));
                    m.put("settledAt", rs.getObject("settled_at", LocalDateTime.class));
                    m.put("withdrawNo", rs.getString("withdraw_no"));
                    m.put("revokeReason", rs.getString("revoke_reason"));
                    m.put("createdAt", rs.getObject("created_at", LocalDateTime.class));
                    return m;
                }, id, a.id());

        if (rows.isEmpty()) throw ApiException.notFound("佣金记录不存在");
        var out = rows.get(0);
        var notes = new LinkedHashMap<String, String>();

        long orderId = (Long) out.get("orderId");
        // 来源订单只出 8 个字段。这里不复用订单详情的出网形状：那一份是给买家看的，
        // 带着 client_ip、price_tier_id 之类的内部字段，而这张单的买家是【别人】。
        var ord = jdbc.query(
                "SELECT o.order_no, s.name AS sku_name, o.code_qty_snapshot, o.amount_fen,"
              + " o.paid_amount_fen, o.paid_at,"
              + " b.agent_code AS buyer_agent_code,"
              + " COALESCE(b.company_name, b.contact_name) AS buyer_agent_name"
              + " FROM biz_order o"
              + " LEFT JOIN sku s ON s.id = o.sku_id"
              + " LEFT JOIN agent_profile b ON b.id = o.buyer_agent_id"
              + " WHERE o.id=?",
                (rs, i) -> {
                    var m = new LinkedHashMap<String, Object>();
                    m.put("orderNo", rs.getString("order_no"));
                    m.put("skuName", rs.getString("sku_name"));
                    // 码数取订单快照 code_qty_snapshot，不是 sku.code_qty（现值）：
                    // 套餐改过码数之后，历史佣金对应的"当时买了几个码"就变了。
                    m.put("codeQty", rs.getInt("code_qty_snapshot"));
                    // ★ 下级那张进货单的金额【不出网】。
                    //
                    // 2026-09-01 起进货价一家一议（agent_price_override），这两个字段等于
                    // 把下级谈下来的专属价原样端给上级看。而上级要核对的是自己那笔佣金，
                    // 不是下级花了多少钱 —— 现行规则全是 FIXED_PER_CODE（固定每码 × 码数），
                    // 用 codeQty 和每码金额就能自己验算，一分钱都不少给。
                    // 真到了 RATE_OF_PAID 那一档，基数由下面的 calcBasisFen 按需给出。
                    m.put("paidAt", rs.getObject("paid_at", LocalDateTime.class));
                    m.put("buyerAgentCode", rs.getString("buyer_agent_code"));
                    m.put("buyerAgentName", rs.getString("buyer_agent_name"));
                    return m;
                }, orderId);

        if (ord.isEmpty()) {
            // 库里 biz_order 与 commission_record 之间没有外键，理论上可以出现孤儿行。
            // 这时给 null + 一句说明，绝不编一个空对象 ——
            // codeQty:0 / amountFen:0 会被读成"买了 0 个码花了 0 元"，
            // 而那正是最容易被当成"平台算错了"来投诉的一种显示。
            out.put("order", null);
            notes.put("order", "来源订单已查不到（订单号 " + out.get("orderNo")
                    + "），请联系平台核对。佣金金额本身来自成交时的快照，不受影响。");
        } else {
            out.put("order", ord.get(0));
            // 原来这里比对「结佣快照的基数」与「订单现在的实付」，两个数都摆出来。
            // 现在下级的实付不出网了，这条比对没有第二个数可比 —— 留着它就是
            // 把刚收掉的那个数从提示语里还回去。退款导致基数与现值不一致这件事，
            // 由退款侧自己的记录负责说明。
        }

        out.put("notes", notes);
        return out;
    }
}
