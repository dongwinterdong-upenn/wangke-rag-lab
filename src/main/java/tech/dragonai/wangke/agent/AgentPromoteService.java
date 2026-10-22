package tech.dragonai.wangke.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tech.dragonai.wangke.web.ApiException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 推广页（{@code GET /api/agent/promote}）：推广链接 + 我的推广码。
 *
 * <h3>链接为什么必须由后端给</h3>
 * 前端拼不出学员端的域名（代理端和学员端是两个应用、两个部署位置），
 * 而<b>拼错的链接会被印在传单上、生成成二维码发出去，收不回来</b>。
 * 所以这一条的纪律格外硬：<b>算不出来就返回 null 并说明为什么，绝不猜一个像样的域名。</b>
 *
 * <h3>四种状态全放行，不是 requireOperational</h3>
 * 推广码在建档那一刻就分配了。审核期（PENDING）唯一有用的一页就是推广页 ——
 * 代理要提前把链接印出来、发出去。这里照抄 requireOperational 的话，
 * 审核中的代理点进推广页只会拿到一句 403，而他什么也没做错。
 *
 * <p>但<b>必须同时说清楚：审核期发出去的链接一个人都归属不到</b>。
 * {@code AuthService.resolveRef} 对指向非 ACTIVE 代理的推广码是<b>静默忽略</b>的 ——
 * 不报错、不落库、什么痕迹都没有。前端已有这句警告文案，
 * 但接口自己要说得出来，否则换个客户端（小程序、SDK）就没人说了。
 */
@Service
public class AgentPromoteService {

    /** 学员端注册页的路由。学员端 Register.tsx 会把 ?ref= 存进 sessionStorage 并在注册时回传。 */
    private static final String REGISTER_PATH = "/register";

    /**
     * 配置缺失时的兜底子路径。两份 vite.config 的注释都写着学员端挂在 /study 下，
     * 但那是<b>部署约定</b>不是事实来源 —— 所以用它拼出来的链接会被明确标成
     * REQUEST_ORIGIN，让调用方知道这个值是猜的。
     */
    private static final String FALLBACK_SUBPATH = "/study";

    private final AgentService agents;
    private final String studentBase;
    private final String landingUrl;

    /**
     * 两项配置都是<b>可选</b>的，缺失不拒绝启动 —— 这与 {@code AppConfig} 里那几个
     * {@code require*}（密钥、支付凭据）的语义完全不同。
     *
     * <p>那几项缺失时服务会以一把假密钥、一个空 appId 在真实流量上工作，
     * 故障要等用户投诉才暴露，所以宁可起不来。而这两项缺失只影响一个页面上的一条链接，
     * 且这个接口会<b>如实说明自己没有这个值</b> —— 为它拒绝启动，等于让整个平台
     * （登录、学习、支付）因为一条推广链接没配而全部不可用。
     *
     * <p>用 {@code @Value} 直接读而不是往 {@code WangkeProps} 里加一段：
     * 那个类是全仓共用的，而这两项只有这一个接口在用。缺省值写成空串，
     * 所以 application.yml 里<b>没有</b> wangke.web 段时也照常装配。
     */
    public AgentPromoteService(AgentService agents,
                               @Value("${wangke.web.student-base:}") String studentBase,
                               @Value("${wangke.web.landing-url:}") String landingUrl) {
        this.agents = agents;
        this.studentBase = trimBase(studentBase);
        this.landingUrl = AgentService.trimToNull(landingUrl);
    }

    /**
     * @param userId        当前登录用户
     * @param requestOrigin 本次请求自身的 scheme://host[:port]（由控制器从
     *                      {@code X-Forwarded-Proto/Host} 解出，
     *                      {@code server.forward-headers-strategy=native} 已开）。
     *                      拿不到就传 null
     */
    public Map<String, Object> promote(long userId, String requestOrigin) {
        AgentService.Agent a = requireProfile(userId);

        var notes = new LinkedHashMap<String, String>();
        String base = studentBase;
        String source = "CONFIG";
        if (base == null) {
            String origin = trimBase(requestOrigin);
            if (origin != null) {
                base = origin + FALLBACK_SUBPATH;
                source = "REQUEST_ORIGIN";
                notes.put("refUrlSource", "平台还没配置学员端地址（wangke.web.student-base），"
                        + "这条链接是用你这次请求的域名 + 部署约定的子路径 " + FALLBACK_SUBPATH
                        + " 拼出来的。印传单、做二维码之前请先找平台确认。");
            }
        }

        // 现在的字母表是 [A-Z0-9]，编码后与原串相同；仍然照做编码 ——
        // 改字母表那天（比如加上 '+' 或 '/'）不会有人记得回来补这一行。
        String refUrl = base == null ? null
                : base + REGISTER_PATH + "?ref=" + URLEncoder.encode(a.agentCode(), StandardCharsets.UTF_8);

        if (refUrl == null)
            notes.put("refUrl", "平台还没配置学员端地址（wangke.web.student-base），"
                    + "这次请求也拿不到可信的域名，所以这里不猜：猜出来的域名会被印在传单与二维码上、"
                    + "发出去就收不回来。请联系平台补上配置后刷新。");

        if (landingUrl == null)
            notes.put("landingUrl", "平台还没配置独立落地页地址（wangke.web.landing-url）。"
                    + "没有这一项时返回 null 而不是回退到别的地址：落地页与注册页不是一回事，"
                    + "拿注册页顶替会让推广数据全部记到注册这一步上。");

        boolean active = a.operational();
        if (!active)
            notes.put("attributionActive", "你的代理资格当前是「" + AgentService.statusText(a.status())
                    + "」。这段时间发出去的推广链接【一个人都归属不到你】——"
                    + "系统对指向非生效代理的推广码是静默忽略的，学员照样能注册，"
                    + "但不会记成你带来的，事后也补不回来。等资格生效后再大规模发放。");

        var out = new LinkedHashMap<String, Object>();
        out.put("agentCode", a.agentCode());
        out.put("refUrl", refUrl);
        out.put("refUrlSource", refUrl == null ? null : source);
        out.put("landingUrl", landingUrl);
        out.put("attributionActive", active);
        out.put("notes", notes);
        return out;
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 取档案：<b>只要求档案存在</b>，四种状态全放行。
     * 没有档案 → 404，且文案与 {@code /me} 完全一致 —— 前端 Gate 靠这句话画"去申请"的入口，
     * 两处措辞不同的话，用户在两个页面上会看到对同一件事的两种说法。
     */
    private AgentService.Agent requireProfile(long userId) {
        AgentService.Agent a = agents.profileOf(userId);
        if (a == null) throw ApiException.notFound("你还不是代理商，请先提交代理申请");
        return a;
    }

    /** 去掉结尾斜杠：配置里带不带 "/" 都不该拼出 "https://x//register"。 */
    private static String trimBase(String v) {
        String s = AgentService.trimToNull(v);
        if (s == null) return null;
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s.isEmpty() ? null : s;
    }
}
