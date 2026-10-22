package tech.dragonai.wangke.auth;

import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import tech.dragonai.wangke.ItDb;
import tech.dragonai.wangke.config.WangkeProps;
import tech.dragonai.wangke.web.ApiException;
import tech.dragonai.wangke.web.GlobalExceptionHandler;

import javax.sql.DataSource;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code POST /api/account/password} 的判据 —— 全站唯一的改密入口。
 *
 * <h3>这个文件是为「路径对齐」这件事写的</h3>
 * 代理端原本调的是 {@code PUT /api/me/password}：整仓 22 个 {@code @RequestMapping}
 * 里<b>没有任何一个</b> {@code /api/me/}，那只是同一个 api.ts 文件里的笔误级不一致
 * （同一个文件自己就在 {@code AgentApi.me} 的兜底里调 {@code /api/account/me}）。
 * 对齐的方向是把前端挪到已有的入口，而不是再开一个映射 ——
 * 两条路并存的代价不在今天，在下一次改动：谁给改密加短信验证、加审计、加风控，
 * 都只会改到其中一条，<b>另一条静默留成后门，且不会有任何测试变红</b>。
 *
 * <p>所以这里有一条正面的「没留残根」判据：{@code PUT /api/me/password} 必须 404。
 * 它是本组唯一能证明"旧路径真的没了"的东西 —— 源码 grep 干净不等于路由干净。
 *
 * <h3>两层用例，因为有些事只在真实分发下才发生</h3>
 * <ol>
 *   <li><b>直接 new 控制器</b>：行为矩阵（旧密码、空 hash、强制改密、密码规则、
 *       token_version、触发器、并发）。这一层跑得快、断言能落到库里的每一列。
 *   <li><b>MockMvc standaloneSetup</b>：{@code @Valid} 只在真实分发下生效
 *       （同 {@code @Transactional}，靠代理）—— 直接调方法的话，
 *       {@code newPassword=null} 会绕过 {@code @NotBlank} 落到 encode 里
 *       变成「密码至少 8 位」，而线上用户看到的是「newPassword 不能为空」。
 *       还有：路由到底存不存在、动词写错会怎样，也只有这一层测得到。
 * </ol>
 *
 * <h3>库名不许和 wangke_auth_it 撞</h3>
 * 同一个 JVM 里顺序跑，后一个的 {@code DROP DATABASE} 会把前一个连根拔掉，
 * 而报错长得像「表不存在」，会让人往 schema 上查。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AccountPasswordIT {

    static final String DB = "wangke_account_pwd_it";

    static DataSource ds;
    static JdbcTemplate jdbc;
    static AuthService auth;
    static JwtService jwt;
    static BCryptPasswordEncoder pwd;
    static AccountController api;
    static MockMvc mvc;

    /** 手机号发号器：每个用例自己造账号，互不干扰，因此不需要用例间顺序。 */
    static final AtomicInteger PHONE_SEQ = new AtomicInteger(0);

    @BeforeAll
    static void boot() throws Exception {
        ds = ItDb.freshDatabase(DB);
        jdbc = new JdbcTemplate(ds);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));

        var props = new WangkeProps();
        props.getSecrets().setJwtSecret(Base64.getEncoder().encodeToString(ItDb.testKey(11)));
        props.getSecrets().setCodeHashSalt("it-salt");
        // 强度与生产一致（AppConfig 给的是 12）。调低会让用例跑得快些，
        // 但 72 字节边界那几条是 bcrypt 自己的性质，换强度不换性质，没必要为了快而失真。
        pwd = new BCryptPasswordEncoder(12);
        // 短信这条路与改密无关。AliyunSms 是类不是接口，所以覆写 send：
        // 用真实现的话，跑一次测试就往阿里云打一次真短信、花真钱。
        var noSms = new AliyunSms(props) {
            @Override public String send(String phone, String tpl, Map<String, String> p) {
                return "IT-NO-SEND";
            }
        };
        jwt = new JwtService(props);
        auth = new AuthService(jdbc, tx, new SmsCodeService(jdbc, tx, noSms, props), jwt, pwd);
        api = new AccountController(auth, jdbc);

        // 真实分发 + 真实错误映射。两者都要真的挂上：
        // 少了 GlobalExceptionHandler，400/401 会变成 MockMvc 直接抛异常，
        // 测出来的就不是用户看到的东西。
        mvc = MockMvcBuilders.standaloneSetup(api)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterAll
    static void down() {
        ItDb.close(ds);
    }

    /* ------------------------------------------------------------------ 夹具 */

    private static String nextPhone() {
        return String.format("139%08d", PHONE_SEQ.incrementAndGet());
    }

    /** 建一个有密码的账号。返回 id。 */
    private static long mkUser(String plain, int mustChange, String role) {
        String phone = nextPhone();
        jdbc.update("INSERT INTO sys_user (phone,password_hash,role,status,must_change_pwd) "
                  + "VALUES (?,?,?,'ACTIVE',?)", phone, pwd.encode(plain), role, mustChange);
        return uidOf(phone);
    }

    private static long mkUser(String plain) {
        return mkUser(plain, 0, "STUDENT");
    }

    /** 建一个<b>从来没设过密码</b>的账号（password_hash IS NULL）—— seed 里的超管就是这个状态。 */
    private static long mkUserWithoutPassword(int mustChange) {
        String phone = nextPhone();
        jdbc.update("INSERT INTO sys_user (phone,role,status,must_change_pwd) "
                  + "VALUES (?,'STUDENT','ACTIVE',?)", phone, mustChange);
        return uidOf(phone);
    }

    private static long uidOf(String phone) {
        // 主键是 BIGINT UNSIGNED：驱动回的是 BigInteger，直接强转 Long 就是 ClassCastException。
        // 这里指定 Long.class 由 Spring 转换；从 row map 里取的地方一律 ((Number) v).longValue()。
        return jdbc.queryForObject("SELECT id FROM sys_user WHERE phone=?", Long.class, phone);
    }

    private static int tokenVersion(long uid) {
        return jdbc.queryForObject("SELECT token_version FROM sys_user WHERE id=?", Integer.class, uid);
    }

    private static String hashOf(long uid) {
        return jdbc.queryForObject("SELECT password_hash FROM sys_user WHERE id=?", String.class, uid);
    }

    /**
     * {@code must_change_pwd} 是 TINYINT(1)：Connector/J 默认 {@code tinyInt1isBit=true}，
     * 回来的是 <b>Boolean 而不是 Number</b>。测试自己也不许假设驱动配置 ——
     * 假设了的话，连接串上加一个参数就能让判据自己先炸，而炸点看起来像业务代码的锅。
     */
    private static boolean mustChangePwd(long uid) {
        Object v = jdbc.queryForMap("SELECT must_change_pwd FROM sys_user WHERE id=?", uid)
                .get("must_change_pwd");
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        return ((Number) v).intValue() != 0;
    }

    /** 已登录用户发起的请求。属性名与 AuthInterceptor 真正写进去的一致。 */
    private static MockHttpServletRequest asUser(long uid, String role) {
        var req = new MockHttpServletRequest("POST", "/api/account/password");
        req.setRemoteAddr("203.0.113.7");
        req.setAttribute(AuthInterceptor.ATTR_USER_ID, uid);
        req.setAttribute(AuthInterceptor.ATTR_ROLE, role);
        return req;
    }

    private static Map<String, Object> change(long uid, String role, String oldPwd, String newPwd) {
        return api.changePassword(new AccountController.ChangePwdReq(oldPwd, newPwd), asUser(uid, role));
    }

    private static ApiException changeExpectingFailure(long uid, String oldPwd, String newPwd) {
        return assertThrows(ApiException.class,
                () -> change(uid, "STUDENT", oldPwd, newPwd));
    }

    /* ============================================================ 一、正常路径 */

    @Test @Order(1)
    @DisplayName("★ 改密成功：返回体三个字段都要对，库里三处状态都要变")
    void changeSucceedsAndReturnsAllThreeFields() {
        long uid = mkUser("Old#Pass1", 1, "STUDENT");
        int before = tokenVersion(uid);
        String oldHash = hashOf(uid);

        Map<String, Object> out = change(uid, "STUDENT", "Old#Pass1", "New#Pass2");

        // ---- 返回体：逐字段断值。只断 ok=true 的话，把 message 写空、
        //      把 reloginRequired 写成 false 都能全绿，而前端正是靠这两个字段做事的。
        assertEquals(Boolean.TRUE, out.get("ok"));
        assertEquals("密码已修改，所有设备需要重新登录", out.get("message"),
                "这句人话是给前端直接 toast 的，改了就要同步改两个前端");
        assertEquals(Boolean.TRUE, out.get("reloginRequired"),
                "★ 当前这台设备的令牌也作废了，前端要靠这个字段主动登出");

        // Map.of 不保证 key 顺序，所以断集合不断顺序。
        assertEquals(Set.of("ok", "message", "reloginRequired"), out.keySet());
        // ★ 故意不返回新令牌：返回了就等于"改密不踢当前设备"，
        //   而当前设备恰好可能就是被盗的那一台 —— 攻击者继续用，机主反而被踢。
        assertTrue(out.keySet().stream().noneMatch(k -> k.toLowerCase().contains("token")),
                "响应里出现了令牌字段：" + out.keySet());

        // ---- 库里：新 hash 生效、强制改密清零、令牌版本恰好 +1
        String newHash = hashOf(uid);
        assertNotNull(newHash);
        assertNotEquals(oldHash, newHash, "password_hash 没换");
        assertTrue(pwd.matches("New#Pass2", newHash), "新密码验不过，说明写进去的不是它");
        assertFalse(pwd.matches("Old#Pass1", newHash), "旧密码还能验过");
        assertFalse(mustChangePwd(uid), "must_change_pwd 必须一并清零，这是初始密码那一档的出口");
        assertEquals(before + 1, tokenVersion(uid), "token_version 必须恰好 +1");
    }

    @Test @Order(2)
    @DisplayName("★ 改完密码，发起这次请求的那张令牌当场就作废（reloginRequired 不是装饰）")
    void currentDeviceTokenDiesImmediately() {
        long uid = mkUser("Old#Pass1");
        var interceptor = new AuthInterceptor(jwt, jdbc);
        String token = jwt.issue(uid, "STUDENT", tokenVersion(uid));

        // 改密之前：这张令牌是好的
        assertTrue(interceptor.preHandle(withBearer(token), new MockHttpServletResponse(), null));

        change(uid, "STUDENT", "Old#Pass1", "New#Pass2");

        // 改密之后：同一张令牌 401。这一条就是 reloginRequired=true 的事实依据 ——
        // 代理端原来的 toast 写「其他设备上的登录会被踢下线」，那句话是错的：本机也被踢了。
        // 前端不主动登出的话，用户会带着一张死令牌继续点，直到某次请求 401，
        // 被 AppShell 的统一出口无缘无故弹去登录页。
        var e = assertThrows(ApiException.class,
                () -> interceptor.preHandle(withBearer(token), new MockHttpServletResponse(), null));
        assertEquals(401, e.status());
        assertEquals("登录状态已失效，请重新登录", e.getMessage());
    }

    private static MockHttpServletRequest withBearer(String token) {
        var req = new MockHttpServletRequest("POST", "/api/account/password");
        req.addHeader("Authorization", "Bearer " + token);
        return req;
    }

    @Test @Order(3)
    @DisplayName("四个角色一视同仁：改自己的密码不看 role，不加 requireRole")
    void everyRoleMayChangeOwnPassword() {
        for (String role : List.of("STUDENT", "AGENT", "MENTOR", "PLATFORM_ADMIN")) {
            long uid = mkUser("Old#Pass1", 0, role);
            Map<String, Object> out = change(uid, role, "Old#Pass1", "New#Pass2");
            assertEquals(Boolean.TRUE, out.get("ok"), role + " 改不了自己的密码");
            assertTrue(pwd.matches("New#Pass2", hashOf(uid)), role + " 的新密码没写进去");
        }
        // 「代理能不能经营」那条 agent_profile.status='ACTIVE' 的判据与这里无关：
        // 改密不是经营动作，未审代理也必须能改自己的密码，否则他连初始密码都换不掉。
    }

    /* ==================================================== 二、旧密码那道闸 */

    @Test @Order(10)
    @DisplayName("★ 旧密码不对 → 401「原密码不正确」（逐字断，这是防「被盗会话夺号」的闸）")
    void wrongOldPasswordRejected() {
        long uid = mkUser("Old#Pass1");
        int before = tokenVersion(uid);
        String hashBefore = hashOf(uid);

        var e = changeExpectingFailure(uid, "Wrong#Pass9", "New#Pass2");

        assertEquals(401, e.status());
        // 逐字断文案：实现里少一行 pwd.matches 照样能"全绿"，
        // 只断 401 的话，一个把校验删掉、改成"任何非空旧密码都放行"的重构不会被发现。
        assertEquals("原密码不正确", e.getMessage());
        assertEquals(hashBefore, hashOf(uid), "拒绝了却把密码改了");
        assertEquals(before, tokenVersion(uid), "拒绝了却把别的设备踢了");
    }

    @Test @Order(11)
    @DisplayName("★ 库里有密码而 oldPassword 缺省（null）→ 401「原密码不正确」")
    void missingOldPasswordRejectedWhenHashPresent() {
        long uid = mkUser("Old#Pass1");
        int before = tokenVersion(uid);

        var e = changeExpectingFailure(uid, null, "New#Pass2");

        assertEquals(401, e.status());
        assertEquals("原密码不正确", e.getMessage());
        assertEquals(before, tokenVersion(uid));
        assertTrue(pwd.matches("Old#Pass1", hashOf(uid)), "旧密码必须还生效");
    }

    @Test @Order(12)
    @DisplayName("空串旧密码等同缺省：库里有密码时同样 401（前端两端一个传 null 一个传 undefined）")
    void blankOldPasswordRejectedWhenHashPresent() {
        long uid = mkUser("Old#Pass1");
        var e = changeExpectingFailure(uid, "", "New#Pass2");
        assertEquals(401, e.status());
        assertEquals("原密码不正确", e.getMessage());
    }

    @Test @Order(13)
    @DisplayName("★ 从没设过密码 + 旧密码留空 → 200，这是平台开户账号唯一的出路")
    void nullHashWithBlankOldPasswordAllowed() {
        long uid = mkUserWithoutPassword(0);
        assertNull(hashOf(uid), "前提：这个账号出厂就没有密码（seed 里的超管 id=1 就是）");
        int before = tokenVersion(uid);

        Map<String, Object> out = change(uid, "PLATFORM_ADMIN", null, "First#Pass1");

        assertEquals(Boolean.TRUE, out.get("ok"));
        assertNotNull(hashOf(uid), "password_hash 必须变成非空");
        assertTrue(pwd.matches("First#Pass1", hashOf(uid)));
        assertFalse(mustChangePwd(uid));
        assertEquals(before + 1, tokenVersion(uid), "token_version 必须恰好 +1");
    }

    @Test @Order(14)
    @DisplayName("从没设过密码、must_change_pwd=0，却传了非空旧密码 → 401")
    void nullHashWithNonBlankOldPasswordRejected() {
        long uid = mkUserWithoutPassword(0);
        int before = tokenVersion(uid);

        var e = changeExpectingFailure(uid, "whatever", "First#Pass1");

        assertEquals(401, e.status());
        // 已知别扭点：这句话对"你从来就没有过密码"说不通，用户会反复试。
        // 换成「这个账号还没有设过密码，请把该栏留空」要改 AuthService，
        // 而它是两端共用的 —— 见交付说明里的 open_questions，本轮不改，先把现状钉住。
        assertEquals("原密码不正确", e.getMessage());
        assertNull(hashOf(uid), "拒绝了却把密码设上了");
        assertEquals(before, tokenVersion(uid));
    }

    @Test @Order(15)
    @DisplayName("★ must_change_pwd=1 且已有密码 → 仍要校验旧密码（强制改密 ≠ 免验旧密码）")
    void forcedChangeStillVerifiesOldPassword() {
        long uid = mkUser("Init#Pass1", 1, "AGENT");

        var e = changeExpectingFailure(uid, "Guess#Pass9", "New#Pass2");
        assertEquals(401, e.status());
        assertEquals("原密码不正确", e.getMessage());
        assertTrue(mustChangePwd(uid), "被拒之后强制改密的标记不该被清掉");

        // 拿对旧密码才过得去
        Map<String, Object> out = change(uid, "AGENT", "Init#Pass1", "New#Pass2");
        assertEquals(Boolean.TRUE, out.get("ok"));
        assertFalse(mustChangePwd(uid), "这才是初始密码那一档的出口");
    }

    /* ==================================================== 三、新密码的规则 */

    @Test @Order(20)
    @DisplayName("★ 密码规则三条各断到文案：只断 400 的话，删掉其中两条校验也不会红")
    void newPasswordRulesEachHaveTheirOwnMessage() {
        long uid = mkUser("Old#Pass1");

        var tooShort = changeExpectingFailure(uid, "Old#Pass1", "Ab3456j");   // 7 位
        assertEquals(400, tooShort.status());
        assertEquals("密码至少 8 位", tooShort.getMessage());

        var lettersOnly = changeExpectingFailure(uid, "Old#Pass1", "abcdefghij");
        assertEquals(400, lettersOnly.status());
        assertEquals("密码需同时包含字母和数字", lettersOnly.getMessage());

        var digitsOnly = changeExpectingFailure(uid, "Old#Pass1", "1234567890");
        assertEquals(400, digitsOnly.status());
        assertEquals("密码需同时包含字母和数字", digitsOnly.getMessage());

        // 三次拒绝之后，账号必须一动不动
        assertTrue(pwd.matches("Old#Pass1", hashOf(uid)));
        assertEquals(0, tokenVersion(uid));
    }

    @Test @Order(21)
    @DisplayName("★ bcrypt 的 72 字节硬边界：72 位过、73 位拒（明确拒绝，不静默截断）")
    void bcryptSeventyTwoBoundary() {
        long uid = mkUser("Old#Pass1");
        String p72 = "a1" + "x".repeat(70);
        String p73 = "a1" + "x".repeat(71);
        assertEquals(72, p72.length());
        assertEquals(73, p73.length());

        var tooLong = changeExpectingFailure(uid, "Old#Pass1", p73);
        assertEquals(400, tooLong.status());
        assertEquals("密码过长（不超过 72 位）", tooLong.getMessage());
        // 为什么不截断：bcrypt 只取前 72 字节，超长部分被静默丢弃 ——
        // 用户以为自己设了 80 位，实际只有 72 位生效。宁可当场拒绝。
        assertTrue(pwd.matches("Old#Pass1", hashOf(uid)), "73 位被拒之后密码不该变");

        assertEquals(Boolean.TRUE, change(uid, "STUDENT", "Old#Pass1", p72).get("ok"),
                "72 位正好是边界内，必须收");
        assertTrue(pwd.matches(p72, hashOf(uid)));
    }

    @Test @Order(22)
    @DisplayName("新密码与旧密码相同：当前允许（效果=密码没变但所有设备被踢），已知行为不是 bug")
    void sameAsOldPasswordIsAllowed() {
        long uid = mkUser("Old#Pass1");
        int before = tokenVersion(uid);

        Map<String, Object> out = change(uid, "STUDENT", "Old#Pass1", "Old#Pass1");

        assertEquals(Boolean.TRUE, out.get("ok"));
        assertTrue(pwd.matches("Old#Pass1", hashOf(uid)));
        // 踢下线照样发生：这正是"用一次改密把可疑会话全清掉"的用法，不该拦。
        assertEquals(before + 1, tokenVersion(uid));
    }

    /* ==================================================== 四、身份只从登录态取 */

    @Test @Order(30)
    @DisplayName("★ 请求体里不许有 userId / phone —— 有了就是「改任意账号密码」的接口")
    void requestBodyCarriesNoIdentity() {
        var names = java.util.Arrays.stream(AccountController.ChangePwdReq.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .collect(java.util.stream.Collectors.toSet());
        // 这条判据看着像形式主义，但它挡的东西非常具体：加一个 userId 入参之后，
        // 接口"工作起来一切正常"（自己改自己完全正确），没有任何别的测试会红，
        // 发现它的方式只有一种 —— 等到有人把别人的号改掉。
        assertEquals(Set.of("oldPassword", "newPassword"), names,
                "改密请求体多了字段：身份必须只从 AuthInterceptor.userId(req) 取");
    }

    @Test @Order(31)
    @DisplayName("没有登录态属性时控制器自己也拒绝 → 401「请先登录」")
    void noLoginAttributeRejected() {
        var bare = new MockHttpServletRequest("POST", "/api/account/password");
        var e = assertThrows(ApiException.class, () -> api.changePassword(
                new AccountController.ChangePwdReq(null, "New#Pass2"), bare));
        assertEquals(401, e.status());
        assertEquals("请先登录", e.getMessage());
    }

    @Test @Order(32)
    @DisplayName("★ /api/account/ 不在免登录白名单里（挂到 /api/auth/ 下＝谁都能改别人的密码）")
    void accountPrefixIsNotPublic() {
        var interceptor = new AuthInterceptor(jwt, jdbc);

        // 无 Authorization 头访问本接口 → 直接被拦截器挡下
        var noToken = new MockHttpServletRequest("POST", "/api/account/password");
        var e = assertThrows(ApiException.class,
                () -> interceptor.preHandle(noToken, new MockHttpServletResponse(), null));
        assertEquals(401, e.status());
        assertEquals("请先登录", e.getMessage());

        // 配对阳性：同样不带头，/api/auth/ 底下是放行的。
        // 没有这半条，上面那半条可能只是因为拦截器把所有请求都拒了 —— 判据会恒绿。
        var publicReq = new MockHttpServletRequest("POST", "/api/auth/login");
        assertTrue(interceptor.preHandle(publicReq, new MockHttpServletResponse(), null),
                "/api/auth/ 是免登录前缀，这半条证明上面那半条不是恒真");
        assertNull(publicReq.getAttribute(AuthInterceptor.ATTR_USER_ID),
                "免登录前缀下根本没有身份 —— 所以改密永远不能挂到那底下");
    }

    @Test @Order(33)
    @DisplayName("账号不存在 → 404「账号不存在」（拦截器先拦过，但这条分支不许被改成静默 return）")
    void unknownAccountIsNotFound() {
        long ghost = 9_999_999L;
        assertTrue(jdbc.queryForList("SELECT id FROM sys_user WHERE id=?", ghost).isEmpty());

        var e = changeExpectingFailure(ghost, null, "New#Pass2");

        assertEquals(404, e.status());
        assertEquals("账号不存在", e.getMessage());
        // 静默 return 的后果：前端收到 200「密码已修改」，用户拿新密码登不进去，
        // 而后台一切正常，没有任何日志。
        assertTrue(jdbc.queryForList("SELECT id FROM sys_user WHERE id=?", ghost).isEmpty(),
                "不存在的账号被创建出来了");
    }

    /* ==================================================== 五、库这一侧的性质 */

    @Test @Order(40)
    @DisplayName("★ 归属触发器在这条 UPDATE 上必须放行（将来改成整行 UPDATE 会 409，且报错里没有「改密」二字）")
    void attributionTriggerLetsPasswordChangeThrough() {
        // 造一个「已经归属给某个代理」的学员
        long agentUid = mkUser("Agent#Pass1", 0, "AGENT");
        jdbc.update("INSERT INTO agent_profile (user_id,agent_code,level,status) VALUES (?,?,1,'ACTIVE')",
                agentUid, "IT" + PHONE_SEQ.incrementAndGet());
        long agentId = jdbc.queryForObject(
                "SELECT id FROM agent_profile WHERE user_id=?", Long.class, agentUid);

        String phone = nextPhone();
        jdbc.update("INSERT INTO sys_user (phone,password_hash,role,status,attributed_agent_id,"
                  + "attributed_at,attribution_source) VALUES (?,?,'STUDENT','ACTIVE',?,NOW(3),'REF_LINK')",
                phone, pwd.encode("Old#Pass1"), agentId);
        long uid = uidOf(phone);

        // 改密这条 UPDATE 不碰 attributed_agent_id，NEW.x <=> OLD.x 成立 → 放行
        assertEquals(Boolean.TRUE, change(uid, "STUDENT", "Old#Pass1", "New#Pass2").get("ok"));
        assertEquals(agentId, ((Number) jdbc.queryForMap(
                "SELECT attributed_agent_id FROM sys_user WHERE id=?", uid)
                .get("attributed_agent_id")).longValue(), "改密把归属改掉了");

        // ---- 配对阳性：证明这个触发器确实是活的，上面那条绿灯才有意义。
        // 将来谁把改密写成整行 UPDATE、或顺手带上 attributed_agent_id=NULL，
        // 会被 SIGNAL 45000 拒掉 → GlobalExceptionHandler 变成 409
        // 「归属代理一经落定不可变更或清空」—— 报错文案里一个「改密」的字都没有，
        // 排查会整个跑到归属那边去。所以这条必须留在改密的测试文件里。
        var boom = assertThrows(DataAccessException.class, () -> jdbc.update(
                "UPDATE sys_user SET password_hash=?, attributed_agent_id=NULL WHERE id=?",
                pwd.encode("Other#Pass3"), uid));
        String signal = GlobalExceptionHandler.triggerMessage(boom);
        // triggerMessage 认得出来 → GlobalExceptionHandler 会把它当业务规则回 409 而不是 500。
        // 也就是说：那天用户看到的会是「归属代理一经落定不可变更或清空」这句 409，
        // 而他刚点的按钮叫「修改密码」。
        assertNotNull(signal, "触发器没被触发，说明上面那条『放行』是空的、判据恒绿");
        assertTrue(signal.contains("归属代理一经落定不可变更或清空"), "实际报错：" + signal);
    }

    @Test @Order(41)
    @DisplayName("★ 并发改密：token_version 的增量必须严格等于请求数（先读后写的重构会静默丢自增）")
    void concurrentChangesEachBumpTokenVersion() throws Exception {
        int n = 4;
        long uid = mkUser("Same#Pass1");
        int before = tokenVersion(uid);

        // ★ 新旧密码故意取同一个。四个请求交错到任何顺序，每一次读到的 hash 都还验得过
        // "Same#Pass1"，于是【四次必定全部成功】—— 期望增量是一个确定的 4，
        // 而不是"3 或 4 都算对"。用不同的新密码会让先写完的那个把后面几个的旧密码作废，
        // 成功次数变成竞态的函数，判据就只能写成不等式，而不等式恰好挡不住「丢了一次自增」。
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(n);
        var ok = new AtomicInteger();
        var failures = new ConcurrentLinkedQueue<Throwable>();
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            for (int i = 0; i < n; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        Map<String, Object> out = change(uid, "STUDENT", "Same#Pass1", "Same#Pass1");
                        if (Boolean.TRUE.equals(out.get("ok"))) ok.incrementAndGet();
                        else failures.add(new AssertionError("返回体不是 ok=true：" + out));
                    } catch (Throwable t) {
                        failures.add(t);
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(120, TimeUnit.SECONDS), "并发用例超时");
        } finally {
            pool.shutdownNow();
        }
        assertTrue(failures.isEmpty(), "并发改密出现失败：" + failures);
        assertEquals(n, ok.get(), "四个并发请求应当全部成功");

        // 这里断的是自增在 SQL 里做（token_version = token_version + 1），
        // 不是「先 SELECT 出来、Java 里加一、再写回」。后者在两台设备同时改密时会丢一次自增，
        // 表现是「明明改了密码，另一台设备还能继续用」—— 那是安全性质，不是性能问题。
        assertEquals(before + n, tokenVersion(uid),
                "token_version 增量必须等于请求数 " + n + "，少一次就意味着有一张旧令牌活了下来");
        assertTrue(pwd.matches("Same#Pass1", hashOf(uid)), "最后落库的必须是这批请求写的密码");
    }

    @Test @Order(42)
    @DisplayName("★ 故意不幂等：同一个新密码连调两次 → 两次 200、token_version +2")
    void notIdempotentByDesign() {
        long uid = mkUser("Old#Pass1");

        assertEquals(Boolean.TRUE, change(uid, "STUDENT", "Old#Pass1", "New#Pass2").get("ok"));
        // 第二次要用新密码当旧密码，否则第一次就把旧密码作废了
        assertEquals(Boolean.TRUE, change(uid, "STUDENT", "New#Pass2", "New#Pass2").get("ok"));

        assertEquals(2, tokenVersion(uid),
                "两次调用必须各踢一次全设备 —— 后端不做幂等键，连点由前端 inFlight 去重挡");
    }

    /* ==================================================== 六、只有真实分发才成立的判据 */

    private static RequestPostProcessor login(long uid, String role) {
        return r -> {
            r.setAttribute(AuthInterceptor.ATTR_USER_ID, uid);
            r.setAttribute(AuthInterceptor.ATTR_ROLE, role);
            return r;
        };
    }

    @Test @Order(50)
    @DisplayName("★ 路由对齐：POST /api/account/password → 200")
    void httpPostOnAlignedPathWorks() throws Exception {
        long uid = mkUser("Old#Pass1");

        mvc.perform(MockMvcRequestBuilders.post("/api/account/password")
                        .contentType("application/json")
                        .content("{\"oldPassword\":\"Old#Pass1\",\"newPassword\":\"New#Pass2\"}")
                        .with(login(uid, "AGENT")))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.ok").value(true))
                .andExpect(MockMvcResultMatchers.jsonPath("$.message")
                        .value("密码已修改，所有设备需要重新登录"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.reloginRequired").value(true))
                // 响应里不许出现新令牌（见 AccountController 的 Javadoc）
                .andExpect(MockMvcResultMatchers.jsonPath("$.token").doesNotExist());

        assertTrue(pwd.matches("New#Pass2", hashOf(uid)));
    }

    @Test @Order(51)
    @DisplayName("★★ 没留残根：PUT /api/me/password 必须 404（源码 grep 干净 ≠ 路由干净）")
    void oldAgentPathIsGone() throws Exception {
        mvc.perform(MockMvcRequestBuilders.put("/api/me/password")
                        .contentType("application/json")
                        .content("{\"newPassword\":\"New#Pass2\"}")
                        .with(login(1L, "AGENT")))
                .andExpect(MockMvcResultMatchers.status().isNotFound());

        // 整仓一个 /api/me/ 都没有 —— 这个命名空间从来就不存在，
        // 代理端那行 PUT /api/me/password 是同一个 api.ts 文件里的笔误级不一致，
        // 不是有意的第二套设计（同一个文件自己在 AgentApi.me 的兜底里调 /api/account/me）。
        mvc.perform(MockMvcRequestBuilders.get("/api/me/password").with(login(1L, "AGENT")))
                .andExpect(MockMvcResultMatchers.status().isNotFound());
    }

    @Test @Order(52)
    @DisplayName("★ 动词写错的坑：PUT /api/account/password → 405 且 Allow 头指出该用 POST")
    void wrongVerbOnRightPath() throws Exception {
        long uid = mkUser("Old#Pass1");

        // 这条判据原来写成「405 或 500 都算过」，因为当时 GlobalExceptionHandler 没有
        // HttpRequestMethodNotSupportedException 的分支，实测是 500「系统繁忙」+ 一条 ERROR 堆栈。
        // 那个分支已经补上，所以这里收紧成死钉 405 —— 放着「或 500」不管的话，
        // 哪天有人把分支删了，这条用例还是绿的。
        var res = mvc.perform(MockMvcRequestBuilders.put("/api/account/password")
                        .contentType("application/json")
                        .content("{\"oldPassword\":\"Old#Pass1\",\"newPassword\":\"New#Pass2\"}")
                        .with(login(uid, "AGENT")))
                .andExpect(MockMvcResultMatchers.status().isMethodNotAllowed())
                .andReturn().getResponse();

        // 「路径改了、动词没跟着改」是这类迁移最容易发生的漏改。落到 500 的话，
        // 用户看到的是「平台坏了」，没有人会从那句话联想到动词；
        // 值班的人还要在日志里多收一条根本不是故障的 ERROR 堆栈。
        assertTrue(res.getContentAsString().contains("POST"),
                "405 的消息里没点名该用哪个动词，等于只把「不对」说了一遍：" + res.getContentAsString());
        assertEquals("POST", res.getHeader("Allow"),
                "Allow 头缺失或不对。它是 405 里唯一机器可读的部分，"
              + "调用方（含各种 SDK 生成器）靠它自动纠正：" + res.getHeader("Allow"));

        assertTrue(pwd.matches("Old#Pass1", hashOf(uid)), "动词写错却把密码改了");
    }

    @Test @Order(53)
    @DisplayName("★ @NotBlank 只在真实分发下生效：newPassword 为空 → 400 且消息里要点名 newPassword")
    void notBlankOnlyFiresUnderRealDispatch() throws Exception {
        long uid = mkUser("Old#Pass1");

        for (String body : List.of(
                "{\"oldPassword\":\"Old#Pass1\"}",                              // 整个 key 缺失
                "{\"oldPassword\":\"Old#Pass1\",\"newPassword\":null}",
                "{\"oldPassword\":\"Old#Pass1\",\"newPassword\":\"   \"}")) {
            String msg = mvc.perform(MockMvcRequestBuilders.post("/api/account/password")
                            .contentType("application/json").content(body)
                            .with(login(uid, "AGENT")))
                    .andExpect(MockMvcResultMatchers.status().isBadRequest())
                    .andExpect(MockMvcResultMatchers.jsonPath("$.ok").value(false))
                    .andReturn().getResponse().getContentAsString();
            // 只断包含 "newPassword"，不断全句：校验器的默认文案随 JVM locale 变
            // （must not be blank / 不能为空），钉全句的话换台机器就红。
            assertTrue(msg.contains("newPassword"), "错误里没点名是哪个字段：" + msg);
        }

        // 对照：直接 new 控制器调方法时 @Valid 根本不生效（同 @Transactional，靠代理），
        // 空白新密码会一路走到 encode 里，变成「密码至少 8 位」——
        // 与用户在浏览器里真正看到的不是同一句话。这就是这一层用例存在的理由。
        var bypassed = changeExpectingFailure(uid, "Old#Pass1", "   ");
        assertEquals(400, bypassed.status());
        assertEquals("密码至少 8 位", bypassed.getMessage(),
                "如果这里变了，说明有人给 AuthService 加了校验，上面那三条要重新核");

        assertTrue(pwd.matches("Old#Pass1", hashOf(uid)), "被拒之后密码不该变");
    }

    @Test @Order(54)
    @DisplayName("空请求体 → 400「请求体不是合法的 JSON」，不是 500")
    void emptyBodyIsBadRequest() throws Exception {
        mvc.perform(MockMvcRequestBuilders.post("/api/account/password")
                        .contentType("application/json").content("")
                        .with(login(1L, "PLATFORM_ADMIN")))
                .andExpect(MockMvcResultMatchers.status().isBadRequest())
                .andExpect(MockMvcResultMatchers.jsonPath("$.ok").value(false))
                .andExpect(MockMvcResultMatchers.jsonPath("$.message").value("请求体不是合法的 JSON"));
    }

    // ============================================================ 实名（写一次）

    /**
     * 实名这条路的三条规则，都是踩出来的：
     *  ① 写一次 —— 水印是溯源证据，能随时改名等于给反取证开一个口；
     *  ② 必须改密期间不许走 —— 初始密码由人转达，转达者抢先填一个名字就把这次一次性写入烧掉了，
     *     而机主自己再也改不了；
     *  ③ 有代理档案【不再】一刀切拒绝 —— 申请被驳回的人也留着一行档案，
     *     他们 real_name 恒为空、代理端资料页又是只读的，一刀切之后两条路都堵死。
     */
    @Test
    @DisplayName("★ 实名只能从空补成有值，补完自己改不了")
    void realNameIsWriteOnce() {
        long uid = newUser("13500000801", "STUDENT");
        var r = api.setRealName(new AccountController.RealNameReq(" 张三 "), asUser(uid, "STUDENT"));
        assertEquals("张三", r.get("realName"), "两端空白没去掉");
        assertEquals("张三", jdbc.queryForObject(
                "SELECT real_name FROM sys_user WHERE id=?", String.class, uid));

        var again = assertThrows(ApiException.class, () -> api.setRealName(
                new AccountController.RealNameReq("李四"), asUser(uid, "STUDENT")));
        assertEquals(409, again.status());
        assertTrue(again.getMessage().contains("张三"),
                "没告诉他现在是什么名字，他不知道该不该找平台");
        assertEquals("张三", jdbc.queryForObject(
                "SELECT real_name FROM sys_user WHERE id=?", String.class, uid),
                "第二次提交把名字改掉了 —— 写一次没守住");
    }

    @Test
    @DisplayName("★ 必须改密期间不许填实名：初始密码的转达者会把这次一次性写入烧掉")
    void realNameIsBlockedWhileForcedToChangePassword() {
        long uid = newUser("13500000802", "STUDENT");
        jdbc.update("UPDATE sys_user SET must_change_pwd=1 WHERE id=?", uid);

        var e = assertThrows(ApiException.class, () -> api.setRealName(
                new AccountController.RealNameReq("冒名者"), asUser(uid, "STUDENT")));
        assertEquals(409, e.status());
        assertNull(jdbc.queryForObject("SELECT real_name FROM sys_user WHERE id=?",
                String.class, uid), "必须改密期间把名字写进去了");

        // 改完密码之后就该通
        jdbc.update("UPDATE sys_user SET must_change_pwd=0 WHERE id=?", uid);
        api.setRealName(new AccountController.RealNameReq("本人"), asUser(uid, "STUDENT"));
        assertEquals("本人", jdbc.queryForObject(
                "SELECT real_name FROM sys_user WHERE id=?", String.class, uid),
                "改完密码还是填不了 —— 那这条路等于关死了");
    }

    @Test
    @DisplayName("申请被驳回的人（档案 TERMINATED、姓名为空）照样填得了实名")
    void rejectedAgentApplicantCanStillSetRealName() {
        long uid = newUser("13500000803", "AGENT");
        jdbc.update("INSERT INTO agent_profile (user_id,agent_code,level,status)"
                  + " VALUES (?,?,1,'TERMINATED')", uid, "RJ" + uid);
        assertNull(jdbc.queryForObject("SELECT real_name FROM sys_user WHERE id=?",
                String.class, uid), "前提没成立：这个号已经有名字了");

        api.setRealName(new AccountController.RealNameReq("被驳回的人"), asUser(uid, "AGENT"));
        assertEquals("被驳回的人", jdbc.queryForObject(
                "SELECT real_name FROM sys_user WHERE id=?", String.class, uid),
                "被驳回的申请人填不了实名 —— 他的水印上永远只有手机号，而他没有第二条路");
    }

    static long newUser(String phone, String role) {
        jdbc.update("INSERT INTO sys_user (phone,role) VALUES (?,?)", phone, role);
        return jdbc.queryForObject("SELECT id FROM sys_user WHERE phone=?", Long.class, phone);
    }
}