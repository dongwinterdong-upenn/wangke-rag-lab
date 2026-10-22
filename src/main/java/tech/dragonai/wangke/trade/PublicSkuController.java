package tech.dragonai.wangke.trade;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tech.dragonai.wangke.web.ApiException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 可售商品清单。
 *
 * <h3>为什么价格不来自请求，也不来自这张表本身</h3>
 * 价格一律走 {@link PricingService#priceOf}，也就是下单时用的<b>同一条路</b>。
 * 两处各查一遍的分叉表现是：橱窗上写 2980，下单扣 3490 —— 数字都对，
 * 只是来源不同，而用户看到的是"平台乱收费"。
 *
 * <p>这个接口只读、只出已上架的、且<b>不接受任何金额参数</b>。
 * 下单时服务端会重新定价（{@code OrderService.create} 只收 skuId），
 * 所以就算有人改了这里返回的数字，也一分钱都影响不到。
 *
 * <h3>它原本是缺的</h3>
 * 学员端的直购面板依赖它，取不到时整块隐藏 —— 于是"学员没法在站内买课"
 * 这件事在界面上看不出来，只是那块内容不存在。是在浏览器里点一遍才发现的。
 *
 * <h3>这条路<b>只出学员零售价</b>，代理进货价一个字都不给</h3>
 * 它挂在 {@code /api/public/} 下，也就是 {@link tech.dragonai.wangke.auth.AuthInterceptor}
 * 白名单里 —— <b>免登录，谁都能调</b>。原来它照收 {@code buyerType=AGENT&agentLevel=N}，
 * 于是一条不带任何凭据的 curl 就能拿到 298000 / 349000 两档批发价与折合单价。
 *
 * <p>业务口径是「申请了、超管批过了，才能看到进货价」（2026-08-19）。这条口径在
 * {@code /api/agent/skus} 那一侧是真的守着的：层级从令牌解析、且要求档案 ACTIVE，
 * {@link tech.dragonai.wangke.agent.AgentController} 的类注释还专门写了
 * "收 agentLevel 就等于开放二级看一级价目"。而这里把同一份数字原样发出去，
 * 等于把那一整段自律绕过去 —— <b>把口径守在需要凭据的那条路上，却在不需要凭据的
 * 这条路上开一个同样的口子，等于没守。</b>
 *
 * <p>原注释说"传错层级只会让他看到别人的价目，下单时仍按他真实的层级算"。
 * 这句对<b>计费正确性</b>成立，对<b>信息披露</b>不成立，而业务方问的正是后者：
 * 佣金规则被有意设成恰等于层级差价（见 {@code db/seed.sql}），
 * 所以这几个数字里任意露两个，第三个就能被算出来 —— 披露面上没有"只漏一点"这回事。
 */
@RestController
@RequestMapping("/api/public")
public class PublicSkuController {

    private final JdbcTemplate jdbc;
    private final PricingService pricing;

    public PublicSkuController(JdbcTemplate jdbc, PricingService pricing) {
        this.jdbc = jdbc;
        this.pricing = pricing;
    }

    /**
     * @param buyerType 只接受 STUDENT（学员直购）。传 AGENT 一律 403，见类注释 ——
     *                  代理进货价只在 {@code /api/agent/skus}（要求档案 ACTIVE）那条路上出。
     *                  <b>这里刻意回 403 而不是空列表</b>：空列表读起来像"现在没有可买的档"，
     *                  会让下一个人以为接口坏了、然后"顺手修好"它，把口子再开一遍。
     *                  403 带着理由，改动它的人必须先读懂为什么。
     */
    @GetMapping("/skus")
    Map<String, Object> skus(@RequestParam(required = false) String buyerType) {
        String bt = buyerType == null || buyerType.isBlank()
                ? "STUDENT" : buyerType.trim().toUpperCase();
        if ("AGENT".equals(bt))
            throw ApiException.forbidden(
                    "代理进货价需要先申请代理、且经平台审批通过后，在代理后台查看");
        if (!"STUDENT".equals(bt))
            return Map.of("ok", true, "skus", List.of());
        // 学员零售档恒为层级 0。这里不再接受 agentLevel 入参 ——
        // 一个"当前用不到但收着"的参数，就是下一次把 AGENT 放回来的现成入口。
        int lv = 0;

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT s.id, s.sku_code, s.name, s.course_id, s.code_qty, c.title AS course_title "
              + "FROM sku s JOIN course c ON c.id = s.course_id "
              + "WHERE s.status='ON' AND c.status='ON' AND s.buyer_type=? "
              + "ORDER BY s.code_qty, s.id", bt);

        List<Map<String, Object>> out = rows.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            long skuId = ((Number) r.get("id")).longValue();
            m.put("skuId", skuId);
            m.put("skuCode", r.get("sku_code"));
            m.put("name", r.get("name"));
            m.put("courseId", r.get("course_id"));
            m.put("courseTitle", r.get("course_title"));
            int qty = ((Number) r.get("code_qty")).intValue();
            m.put("codeQty", qty);
            try {
                var p = pricing.priceOf(lv, skuId);
                m.put("priceFen", p.amountFen());
                // 单价单独给出来：批量档位的意义就在"每个便宜多少"，
                // 让前端自己除的话，除不尽时三个页面会显示三个不同的数
                m.put("unitPriceFen", qty > 0 ? p.amountFen() / qty : p.amountFen());
            } catch (RuntimeException e) {
                // 这一档没有现行价 = 现在不卖。给 null 而不是 0 ——
                // 0 会在界面上渲染成"免费"，那是最不该出现的误会。
                m.put("priceFen", null);
                m.put("unitPriceFen", null);
                m.put("unavailableReason", "该档位当前没有现行价目");
            }
            return m;
        }).filter(m -> m.get("priceFen") != null).toList();

        return Map.of("ok", true, "buyerType", bt, "skus", out);
    }
}
