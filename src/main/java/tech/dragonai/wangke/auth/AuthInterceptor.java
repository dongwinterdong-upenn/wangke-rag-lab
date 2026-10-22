package tech.dragonai.wangke.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import tech.dragonai.wangke.web.ApiException;

import java.util.Set;

/**
 * 鉴权。
 *
 * <p><b>默认拒绝，白名单放行。</b>反过来做（默认放行、黑名单拦截）的话，
 * 每加一个接口都要记得去补一条规则，而漏补的那个接口是<b>裸的</b>且没有任何提示 ——
 * 它工作得很好，只是谁都能调。这条在 {@link WebConfig} 里配成 addPathPatterns("/api/**")
 * 加显式 PUBLIC 白名单。
 *
 * <p>每次请求都查一次用户行，为的是三件无状态令牌做不到的事：
 * 账号是否被冻结、令牌版本是否还有效（改密/踢下线）、角色是否变了。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final String ATTR_USER_ID = "wangke.userId";
    public static final String ATTR_ROLE = "wangke.role";

    /** 无需登录即可访问的路径前缀。加接口时如果需要公开，必须显式加进来。 */
    private static final Set<String> PUBLIC_PREFIX = Set.of(
            "/api/auth/",            // 注册登录本身
            "/api/public/",          // 课程介绍、试听
            "/api/pay/notify/",      // 支付网关回调：来源是支付宝，靠验签不靠登录
            "/api/health"
    );

    /**
     * 播放器自己发起的三条路：播放列表、密钥、分片。
     *
     * <p>它们拿不到页面的 JWT —— iOS 的原生 HLS 没有任何办法给这些请求加
     * Authorization 头，hls.js 也只在密钥那一路挂了 {@code xhrSetup}。
     * 三条路各自带着一张自证的凭据：前两条是短时效、绑设备与网段的
     * {@link tech.dragonai.wangke.protect.PlayToken}，
     * 分片是一片一签、绑「谁 + 哪一讲 + 哪一个对象」的
     * {@link tech.dragonai.wangke.protect.SegmentToken}。
     *
     * <p><b>三个前缀都必须带边界</b>（结尾斜杠或全等）。写成 {@code startsWith("/api/play/seg")}
     * 的话，将来任何一个以 seg 开头的新接口都会跟着变成免登录的，
     * 而它工作得很好、没有任何提示 —— 只是谁都能调。
     */
    private static boolean isPlayTokenPath(String path) {
        return path.startsWith("/api/play/m3u8/")
            || path.equals("/api/play/key")
            || path.startsWith("/api/play/seg/");
    }

    /**
     * {@code must_change_pwd=1} 时<b>唯一</b>还能走的已登录路径前缀。
     *
     * <p><b>这是一个前缀，不是一张接口白名单。</b>{@code /api/account/} 下的每一个接口
     * 都在里面 —— 今天是 {@code me}（我是谁，界面得画得出来）、{@code password}（把密码改掉）、
     * {@code devices}（设备列表与解绑）、{@code real-name}（补实名）。
     * 原来这段注释写的是"只有 me 与 password 两个"，那句话在 devices 加进来的时候就不成立了，
     * 而它读起来像一条被守住的规则 —— <b>下一个人会照着这句话判断"往这个包里加接口是安全的"</b>。
     *
     * <p>所以往 {@code auth} 包里加接口时要过一遍这条：新接口在"必须改密"这一档下是不是也该通？
     * 不该通的，放到别的前缀下去，别在这里加例外。
     *
     * <p>注意 {@code /api/auth/} 不在这里 —— 它在 PUBLIC_PREFIX 里更早被放行，
     * 所以走短信验证码那条重置通道同样不受影响。
     */
    private static final String FORCED_CHANGE_ALLOW = "/api/account/";

    /** 前端据此跳去改密页的机器可读标识。改文案不许动它。 */
    public static final String REASON_MUST_CHANGE_PASSWORD = "MUST_CHANGE_PASSWORD";

    private final JwtService jwt;
    private final JdbcTemplate jdbc;

    public AuthInterceptor(JwtService jwt, JdbcTemplate jdbc) {
        this.jwt = jwt; this.jdbc = jdbc;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(req.getMethod())) return true;
        String path = req.getRequestURI();
        for (String p : PUBLIC_PREFIX) if (path.startsWith(p)) return true;

        boolean playTokenPath = isPlayTokenPath(path);

        String auth = req.getHeader("Authorization");
        if (auth == null && playTokenPath) return true;
        if (auth == null || !auth.startsWith("Bearer "))
            throw ApiException.unauthorized("请先登录");

        var principal = jwt.parse(auth.substring(7).trim());

        var rows = jdbc.queryForList(
                "SELECT status, role, token_version, must_change_pwd FROM sys_user WHERE id=?",
                principal.userId());
        if (rows.isEmpty()) throw ApiException.unauthorized("账号不存在");
        var u = rows.get(0);
        if (!"ACTIVE".equals(u.get("status")))
            throw ApiException.forbidden("账号已被冻结，请联系平台");
        int tv = ((Number) u.get("token_version")).intValue();
        if (tv != principal.tokenVersion())
            throw ApiException.unauthorized("登录状态已失效，请重新登录");

        // ---- 强制改密：这道闸只有在这里才成立 ----------------------------------
        //
        // 平台开户 / 管理员重置密码之后 must_change_pwd=1，而那个初始密码是【由人转达】的 ——
        // 转达者同样知道它。在本人改掉之前，这个号等于两个人共用。
        //
        // 原来这道闸完全在前端：Login 组件显示一个改密表单，而那只是组件 state，
        // 按一下 F5 就没了。后端一个字都没查 must_change_pwd，loginByPassword 照常签发
        // 完整可用的令牌 —— 拿临时密码 curl 一下登录接口，所有接口照样能调。
        // 也就是说「强制改密」此前没有任何一处是强制的。
        //
        // 放行面收到最小：/api/auth/**（免登录，短信重置那条路）+ /api/account/**
        // （我是谁 + 改密）。AuthService.changePassword 本来就专门处理了这一档
        // （must_change_pwd=1 仍要校验旧密码），所以这条路是通的。
        if (AuthService.mustChangePwd(u.get("must_change_pwd"))
                && !path.startsWith(FORCED_CHANGE_ALLOW))
            throw ApiException.forbidden("请先修改初始密码，再使用其他功能")
                    .withReason(REASON_MUST_CHANGE_PASSWORD);

        req.setAttribute(ATTR_USER_ID, principal.userId());
        // 角色以【库里当前的】为准，不以令牌里那个为准：
        // 令牌是签发时的快照，降权之后旧令牌还带着高权限，那降权就是假的。
        req.setAttribute(ATTR_ROLE, u.get("role"));
        return true;
    }

    public static long userId(HttpServletRequest req) {
        Object v = req.getAttribute(ATTR_USER_ID);
        if (v == null) throw ApiException.unauthorized("请先登录");
        return (Long) v;
    }

    public static String role(HttpServletRequest req) {
        Object v = req.getAttribute(ATTR_ROLE);
        return v == null ? null : (String) v;
    }

    public static void requireRole(HttpServletRequest req, String... roles) {
        String r = role(req);
        for (String want : roles) if (want.equals(r)) return;
        throw ApiException.forbidden("没有权限执行该操作");
    }
}
