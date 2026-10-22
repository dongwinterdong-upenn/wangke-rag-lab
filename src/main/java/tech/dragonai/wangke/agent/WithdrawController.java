package tech.dragonai.wangke.agent;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import tech.dragonai.wangke.auth.AuthInterceptor;
import tech.dragonai.wangke.web.ClientIp;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 提现申请、查询与自助撤销。
 *
 * <p>请求体里<b>只有金额和渠道</b>：收款户名与账户由服务端从库里取，
 * 客户端说了不算。理由见 {@link WithdrawService} 的类注释。
 *
 * <h3>为什么挂两个路径</h3>
 * 判据是「哪一边改了不会把已经上线的东西弄坏」。
 * 已构建并分发的 {@code web/apps/agent/dist/assets/index-*.js} 里字面量就是
 * {@code api/agent/withdrawals}：改前端要重新构建 + 发版，而用户浏览器里那份缓存的旧
 * bundle 会继续打 {@code /withdrawals}，拿到 404 —— 整个提现页当场变错误页，
 * 且这种事没法热修。反过来，全仓 grep（三个 app 的 src 与 dist、shared、src/main、
 * src/test、docs）里调用 {@code /api/agent/withdraw} 的调用方<b>一个都没有</b>，
 * 唯一出现的地方就是这个 {@code @RequestMapping} 自己。
 * 所以新路径 {@code /withdrawals} 是正路，旧路径原样存活：
 * <b>这次改动对任何今天能跑通的请求都是纯增量</b>。
 *
 * <p>同理，列表响应体<b>保留</b> {@code items} 旧键、<b>新增</b> {@code rows}，
 * 而不是把 items 改名。改名会让潜在的旧调用方拿到一个 200 + 空指针，比 404 还难查。
 *
 * <p>旧路径别名保留多久？建议在确认无调用方后删除，删除前先加一条 access log 统计 ——
 * "没 grep 到"不等于"没有"（有人用 curl 对着老路径写脚本是常态）。
 * <b>新调用方一律用 /api/agent/withdrawals。</b>
 */
@RestController
@RequestMapping({"/api/agent/withdrawals", "/api/agent/withdraw"})
public class WithdrawController {

    private final WithdrawService withdraws;

    public WithdrawController(WithdrawService withdraws) { this.withdraws = withdraws; }

    /**
     * @param amountFen 分。不接受"元"字符串：换算放在这一层的话，'100.005'
     *                  会被静默截断成 100.00，少提的那半分钱不会有任何日志
     */
    public record WithdrawReq(Long amountFen, String channel) {}

    @PostMapping
    Map<String, Object> apply(@RequestBody WithdrawReq r, HttpServletRequest req) {
        var w = withdraws.apply(AuthInterceptor.userId(req), r.amountFen(), r.channel(),
                ClientIp.of(req));

        var out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        out.put("id", w.id());
        out.put("withdrawNo", w.withdrawNo());
        out.put("amountFen", w.amountFen());
        out.put("status", w.status());
        out.put("statusText", WithdrawService.statusText(w.status()));
        // 户名回显：让代理在提交后立刻看见钱要打给谁。
        // 不回显的话，"实名填错 → 打款失败 → 重提" 这个循环要绕到打款失败才被发现。
        out.put("payeeName", w.payeeName());
        out.put("remainingWithdrawableFen", w.remainingFen());
        out.put("message", "提现申请已提交，待平台审核与打款");
        return out;
    }

    /**
     * 提现记录分页。
     *
     * <p>除了"历史列表"外还兼一个隐藏职责：前端 Withdraw.tsx 的 Ledger 用
     * {@code rows.filter(status ∈ APPLIED/REVIEWING/APPROVED/PAYING)} 把"在途的是这几笔"
     * <b>逐单</b>列出来，用来回答那句最常被问的"我余额明明够为什么提不出来"。
     * 所以 status 与 amountFen 必须逐单可见，只给一个在途合计不够。
     *
     * <p>{@code rows} 与 {@code items} 是<b>同一个 List 实例</b>：前者是前端
     * {@code Page<T> = { total; rows }} 要的键，后者是现役调用方在用的旧键。
     */
    @GetMapping
    Map<String, Object> list(@RequestParam(required = false) String status,
                             @RequestParam(defaultValue = "1") int page,
                             @RequestParam(defaultValue = "20") int size,
                             HttpServletRequest req) {
        var p = withdraws.list(AuthInterceptor.userId(req), status, page, size);
        var out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        out.put("rows", p.items());
        out.put("items", p.items());
        out.put("total", p.total());
        out.put("page", p.page());
        out.put("size", p.size());
        return out;
    }

    /**
     * 撤销一张还没被平台受理的提现单。
     *
     * <h3>方法签名上没有 @RequestBody，这是有意的</h3>
     * 前端 {@code api.post(path)} 不带 body 但带 {@code Content-Type: application/json}。
     * 声明了 {@code @RequestBody}（哪怕 {@code required=false}）就会走
     * {@code HttpMessageNotReadableException} → 400"请求体不是合法的 JSON"，
     * 而用户看到的是"撤销失败，请重试"，重试一万次都一样。
     * 既有的 {@code POST /api/agent/codes/{id}/reveal} 就是这个写法。
     *
     * <h3>裁决在 {@link WithdrawService#cancel} 那条带三个条件的 UPDATE 里</h3>
     * {@code id} 定位、{@code agent_id} 管归属（少写它任何人都能撤别人的单，
     * 而且撤成功不会有任何异常和报警）、{@code status IN ('APPLIED')} 是应用层
     * 唯一的那道状态闸（{@code trg_withdraw_state} 对 NEW='CANCELLED' 一个字都没写，
     * PAYING→CANCELLED 库里是放行的，而那意味着钱可能已经在路上、额度却被回吐，
     * 同一笔钱能被再提一次）。
     *
     * <h3>为什么要 withdrawableKnown 这个布尔</h3>
     * {@code spring.jackson.default-property-inclusion=non_null} 会把值为 null 的键
     * <b>整个删掉</b>（连 Map 的值也删）。所以"算不出来所以给 null"在网线上和
     * "后端太旧没这个字段"长得一模一样。于是约定：算得出来 → 出
     * {@code withdrawableFen} + {@code withdrawableKnown=true}；算不出来 →
     * 不出 {@code withdrawableFen}、{@code withdrawableKnown=false}，
     * 并用 {@code withdrawableUnknownReason} 写清为什么。
     *
     * @param id withdraw_order.id。别人的单与不存在的单给同一句 404 ——
     *           给不同回答就等于开了一个"这个 id 存在吗"的问答接口
     */
    @PostMapping("/{id}/cancel")
    Map<String, Object> cancel(@PathVariable long id, HttpServletRequest req) {
        var c = withdraws.cancel(AuthInterceptor.userId(req), id, ClientIp.of(req));

        var out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        // changed=false 是幂等重放（这单本来就已经撤过了），不是失败
        out.put("changed", c.changed());
        out.put("id", c.id());
        out.put("withdrawNo", c.withdrawNo());
        out.put("amountFen", c.amountFen());
        out.put("status", "CANCELLED");
        out.put("statusText", WithdrawService.statusText("CANCELLED"));
        out.put("withdrawableKnown", c.withdrawableFen() != null);
        if (c.withdrawableFen() != null) out.put("withdrawableFen", c.withdrawableFen());
        else out.put("withdrawableUnknownReason", c.withdrawableUnknownReason());
        out.put("message", "已撤销，这笔钱回到可申请额度里了");
        return out;
    }
}
