package tech.dragonai.wangke.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import tech.dragonai.wangke.web.ApiException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 已登录用户对自己账号的操作。
 *
 * <h3>为什么不挂在 /api/auth/ 下</h3>
 * {@code AuthInterceptor.PUBLIC_PREFIX} 里有 {@code "/api/auth/"} ——
 * 那是给注册登录这类<b>还没有身份</b>的请求开的门。
 * 把「改自己的密码」「解绑自己的设备」放进去，等于把它们做成了无需登录的接口：
 * 任何人带一个 userId 就能改别人的密码。所以自己人的操作走 {@code /api/account/}。
 *
 * <h3>这几个接口原本是缺的</h3>
 * {@code AuthService.me} 与 {@code changePassword} 早就写好了，但<b>没有任何映射</b>，
 * 于是学员端「我的」页面上三块内容各自显示「没有这个接口」。
 * 前端把它降级成了一句人话而不是一片红，所以看起来不像故障 ——
 * 但那一页实际上有一半是死的。是在浏览器里真点一遍才发现的。
 */
@RestController
@RequestMapping("/api/account")
public class AccountController {

    private final AuthService auth;
    private final JdbcTemplate jdbc;

    public AccountController(AuthService auth, JdbcTemplate jdbc) {
        this.auth = auth;
        this.jdbc = jdbc;
    }

    /**
     * 我是谁。
     *
     * <p>手机号给两份，用途不同：
     * <ul>
     *   <li>{@code phone} 打码，用于界面显示 —— 个人中心、顶栏、截图里都会出现它；
     *   <li>{@code phoneFull} 完整，<b>只给本人</b>，用于播放器水印。
     * </ul>
     * 水印上打码号的溯源精度差一个量级：全国有一万个 138****8888。
     * 而这是用户自己的号、走的是他自己的登录态，给他看不构成泄漏。
     * 分成两个字段而不是只给完整号，是为了让「哪一处在用完整号」在代码里一眼可查。
     */
    @GetMapping("/me")
    Map<String, Object> me(HttpServletRequest req) {
        long uid = AuthInterceptor.userId(req);
        Map<String, Object> out = new LinkedHashMap<>(auth.me(uid));
        out.put("ok", true);
        String full = jdbc.queryForObject("SELECT phone FROM sys_user WHERE id=?", String.class, uid);
        out.put("phoneFull", full);
        return out;
    }

    /**
     * 改密的请求体。
     *
     * <p><b>这里没有 userId，也没有 phone，而且永远不许加。</b>
     * 身份只从登录态取（{@link AuthInterceptor#userId}）。加一个 userId 入参之后，
     * 这个接口就变成了"改任意账号的密码"，而它<b>工作起来一切正常</b> ——
     * 自己改自己的时候完全正确，没有任何测试会变红，没有任何日志会异常。
     * 发现它的方式只有一种：等到有人把别人的号改掉。
     *
     * <p>{@code oldPassword} 允许缺省/null/空串：平台开户但从没设过密码的账号
     * （{@code password_hash IS NULL}，seed 里的超管 id=1 就是）留空即可。
     * 学员端传 {@code oldPwd || null}、代理端传 {@code oldPwd || undefined}
     * （JSON.stringify 会把整个 key 丢掉），到 Java 侧都是 null，等价。
     */
    public record ChangePwdReq(String oldPassword, @NotBlank String newPassword) {}

    /**
     * 已登录用户改自己的密码。<b>全站唯一的改密入口，两个前端共用它。</b>
     *
     * <h3>为什么是 POST 不是 PUT</h3>
     * 这个操作<b>不幂等</b>：每调一次 {@code token_version} 就 +1，把该账号所有设备
     * 踢一次。PUT 的语义会让调用方以为重放无害，于是在超时重试里放心地重发 ——
     * 而每一次重发都会把刚刚重新登录进来的那些设备再踢一次。
     *
     * <h3>为什么代理端和学员端必须共用这一个入口</h3>
     * 曾经代理端调的是 {@code PUT /api/me/password}（整仓没有这个映射，纯粹是笔误级
     * 的不一致），学员端调的是这里。两条路并存的代价不在今天，在下一次改动：
     * 谁给改密加短信验证码、加审计、加风控，都只会改到其中一条，
     * <b>另一条静默留成后门，而且不会有任何测试变红</b> ——
     * 因为两条路各自都还"能用"。所以对齐的方向是把前端挪过来，不是再开一个映射。
     *
     * <h3>为什么不能挂到 /api/auth/ 下</h3>
     * {@code AuthInterceptor.PUBLIC_PREFIX} 里的 {@code /api/auth/} 是免登录前缀。
     * 挂进去等于：任何人只要能构造出一个 userId 就能改别人的密码。
     * 这里留在 {@code /api/account/}（默认要登录）不是风格问题，是那道闸本身。
     *
     * <h3>reloginRequired 是信号，不是装饰</h3>
     * 改完密码，{@code token_version} +1 —— <b>发起这次请求的这台设备，手里那张令牌
     * 也当场作废</b>。所以返回体里的 {@code reloginRequired=true} 是给前端用来
     * 主动清令牌 + 跳登录页的。前端不读它的后果不是报错，而是：用户带着一张死令牌
     * 继续点，直到某一次请求 401，被 AppShell 的统一出口莫名其妙弹去登录页 ——
     * 他会以为是平台把他踢了。
     *
     * <p><b>响应里故意不返回新令牌。</b>返回新令牌会让"改密即踢下线"对当前设备失效，
     * 而当前设备恰好可能就是被盗的那一台：攻击者改完密码继续用，真正的机主反而被踢。
     * 宁可让本人重新登录一次。
     *
     * <p>校验规则（长度 8~72、必须同时含字母和数字）在 {@code AuthService.encode} 里落地。
     * 72 是 bcrypt 的硬边界：它只取前 72 字节，超长部分被静默丢弃 ——
     * 用户以为设了 80 位，其实只有 72 位生效。所以后端明确拒绝，而不是截断。
     */
    @PostMapping("/password")
    Map<String, Object> changePassword(@RequestBody @Valid ChangePwdReq r, HttpServletRequest req) {
        auth.changePassword(AuthInterceptor.userId(req), r.oldPassword(), r.newPassword());
        return Map.of("ok", true,
                "message", "密码已修改，所有设备需要重新登录",
                "reloginRequired", true);
    }

    /** 实名请求体。只收姓名一项 —— 这条路不许顺带改任何别的字段。 */
    public record RealNameReq(
            @NotBlank(message = "请填写真实姓名") String realName) {}

    /**
     * 补一次真实姓名。<b>只能从空补成有值，补完不能自己再改。</b>
     *
     * <h3>为什么要有这条路</h3>
     * 播放器的可见水印按业务口径要写「姓名 + 手机号」（2026-08-19：「直接就手机号、
     * 姓名写上」，理由是"带上他个人信息他就不敢传出去了"）。而在此之前，
     * <b>学员的姓名在库里根本不存在</b>：{@code sys_user.real_name} 全平台唯一的写入口
     * 是代理申请，注册和激活都不问姓名，超管也没有改名接口。
     * 也就是说那条口径当时是落不了地的 —— 不是没画，是没有可画的东西。
     *
     * <h3>为什么是写一次</h3>
     * 水印是溯源证据。姓名能随时改的话，一份泄漏样本对应的"当时那个名字"就不可复原，
     * 而且改名这个动作本身会变成一种反取证手段。
     * 真要改（打错字了）走人工：{@code POST /api/admin/users/{id}/real-name}，
     * 超管改 + 留审计，那条路上有人为它负责。
     * <b>这条逃生口必须真的存在</b> —— 一开始它只写在这段注释和 409 文案里，
     * 而全仓没有任何管理端改名接口：那等于告诉用户"联系平台"，然后平台也无能为力，
     * 他往后每一节课的水印上都挂着错的名字。
     *
     * <h3>不再按"有没有代理档案"一刀切</h3>
     * 曾经这里拒绝所有有 {@code agent_profile} 的账号，理由是那一列在代理侧是
     * <b>提现收款人姓名</b>，不该开自助改口。但这个判断是多余且误伤的：
     * <ul>
     *   <li><b>多余</b>：下面那条 UPDATE 只在 real_name 为空时才写得进去，
     *       而在职代理申请时就填了这一列（{@code AgentService} 那条 COALESCE），
     *       所以对他们这条路本来就是空转。
     *   <li><b>误伤</b>：申请被驳回的人留着一行 {@code status='TERMINATED'} 的档案
     *       （驳回不删行），PENDING 的同理。这批人 real_name 恒为空，
     *       代理端资料页对他们又是只读的 —— 一刀切之后他们<b>两条路都走不通</b>，
     *       水印上永远只有手机号。
     * </ul>
     *
     * <h3>必须改密期间不许走这条路</h3>
     * 平台开户 / 重置密码之后 {@code must_change_pwd=1}，而那个初始密码是<b>由人转达</b>的 ——
     * 在本人改掉之前，这个号等于两个人共用。放行 me/password/devices 都是只读或可逆的，
     * 而实名是<b>一次性且不可逆</b>的写入：转达者抢先填一个名字，机主就再也改不了，
     * 只能去找平台。所以这一档明确拒绝。
     */
    @PostMapping("/real-name")
    Map<String, Object> setRealName(@RequestBody @Valid RealNameReq r, HttpServletRequest req) {
        long uid = AuthInterceptor.userId(req);
        String name = r.realName().trim();
        if (name.length() > 64) throw ApiException.badRequest("姓名过长");

        Boolean forced = jdbc.queryForObject(
                "SELECT must_change_pwd FROM sys_user WHERE id=?", Boolean.class, uid);
        if (Boolean.TRUE.equals(forced))
            throw ApiException.conflict("请先修改初始密码，再填写真实姓名");

        // 判定与写入是同一条 UPDATE：条件全在 WHERE 里。
        // 写成"先查空不空再更新"的话，两次之间的另一次提交会被这次覆盖掉。
        int n = jdbc.update(
                "UPDATE sys_user SET real_name=? "
              + "WHERE id=? AND (real_name IS NULL OR real_name='')", name, uid);
        if (n == 1) return Map.of("ok", true, "realName", name);

        String cur = jdbc.queryForObject("SELECT real_name FROM sys_user WHERE id=?",
                String.class, uid);
        throw ApiException.conflict("姓名已经填过了（" + cur + "），需要更正请联系平台");
    }

    /**
     * 我绑过哪些设备。
     *
     * <p>播放侧撞到设备上限时给的文案是「如需更换设备，请联系客服解绑」，
     * 而这个页面存在的意义就是让他<b>不用联系客服</b>。
     * 只回自己的：归属条件写在 SQL 的 WHERE 里，不在 Java 里过滤。
     */
    @GetMapping("/devices")
    Map<String, Object> devices(HttpServletRequest req) {
        long uid = AuthInterceptor.userId(req);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, device_id, device_label, status, bound_at, removed_at "
              + "FROM user_device WHERE user_id=? ORDER BY status='ACTIVE' DESC, bound_at DESC", uid);
        List<Map<String, Object>> out = rows.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.get("id"));
            // 设备指纹本身不出网：它是取密钥时要带的凭据之一，
            // 出网等于把「换个设备号就能接着播」这条路写在页面上。只给一个可辨认的尾巴。
            String did = (String) r.get("device_id");
            m.put("deviceTail", did == null || did.length() < 6 ? "——" : "…" + did.substring(did.length() - 6));
            m.put("deviceLabel", r.get("device_label"));
            m.put("status", r.get("status"));
            m.put("boundAt", r.get("bound_at"));
            m.put("removedAt", r.get("removed_at"));
            return m;
        }).toList();
        return Map.of("ok", true, "devices", out,
                "activeCount", out.stream().filter(m -> "ACTIVE".equals(m.get("status"))).count());
    }

    /**
     * 解绑一台设备。
     *
     * <p>判定与写入是同一条 UPDATE：{@code WHERE id=? AND user_id=? AND status='ACTIVE'}。
     * 「先查是不是自己的、再改」中间那道缝隙足够另一个请求把状态改掉，
     * 而更要命的是<b>少写一个 user_id 就能解绑别人的设备</b>。
     * 影响行数为 0 时，「不是你的」与「已经解绑过」回同一句话，不泄露存在性。
     */
    @PostMapping("/devices/{id}/unbind")
    Map<String, Object> unbind(@PathVariable long id, HttpServletRequest req) {
        int n = jdbc.update("UPDATE user_device SET status='REMOVED', removed_at=NOW(3) "
                          + "WHERE id=? AND user_id=? AND status='ACTIVE'",
                id, AuthInterceptor.userId(req));
        if (n != 1) throw ApiException.notFound("这台设备不在你的已绑列表里");
        return Map.of("ok", true, "message", "已解绑。那台设备正在播放的话会中断，重新登录即可");
    }
}
