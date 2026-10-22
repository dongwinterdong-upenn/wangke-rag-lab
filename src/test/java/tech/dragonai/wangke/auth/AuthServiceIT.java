package tech.dragonai.wangke.auth;

import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import tech.dragonai.wangke.ItDb;
import tech.dragonai.wangke.config.WangkeProps;
import tech.dragonai.wangke.web.ApiException;

import javax.sql.DataSource;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 登录这条路的判据。
 *
 * <h3>为什么它是补写的</h3>
 * {@code AuthService} 原本<b>一个测试都没有</b>，而全仓 244 条用例里
 * 没有任何一条<b>用正确的密码登录成功过</b>——错密码那条路被间接覆盖到了，
 * 成功那条路没有。
 *
 * <p>代价是一个生产阻断级 bug 一路走到了线上：{@code sys_user.must_change_pwd}
 * 是 TINYINT(1)，MySQL Connector/J 默认 {@code tinyInt1isBit=true} 会把它读成
 * <b>Boolean</b>，而代码强转 {@code Number} → ClassCastException。
 * 于是<b>密码错误返回 401（正确），密码正确返回 500</b>——
 * 因为异常发生在密码校验之后。是在生产机上真登一次才发现的。
 *
 * <p>所以这个文件里最要紧的不是覆盖率，是<b>那条"用对的密码登进去"的用例</b>。
 * 它必须断言到返回值里的每个字段，而不只是"没抛异常"——
 * 只断言不抛的话，把 mustChangePwd 读错成恒 false 一样能过。
 */
class AuthServiceIT {

    static DataSource ds;
    static JdbcTemplate jdbc;
    static AuthService auth;
    static BCryptPasswordEncoder pwd;
    /**
     * ★ 必须复用同一个 JwtService，不能在用例里再 new 一个。
     *
     * ItDb.testKey(seed) 用的是 {@code new SecureRandom(byte[])}，而它在本 JDK 的默认
     * provider 下<b>并不确定</b> —— 同一个 seed 两次调用给出的是两把不同的钥匙。
     * 各 new 一个的后果是令牌签不验：报出来的是「登录已失效，请重新登录」，
     * 看起来像 token_version 对不上，实际是签名验不过，方向完全错。
     */
    static JwtService jwt;

    @BeforeAll
    static void boot() throws Exception {
        ds = ItDb.freshDatabase("wangke_auth_it");
        jdbc = new JdbcTemplate(ds);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));

        var props = new WangkeProps();
        props.getSecrets().setJwtSecret(Base64.getEncoder().encodeToString(ItDb.testKey(7)));
        props.getSecrets().setCodeHashSalt("it-salt");
        pwd = new BCryptPasswordEncoder(12);
        // 短信这条路不在本用例范围内。AliyunSms 是类不是接口，所以覆写 send：
        // 用真实现的话，跑一次测试就往阿里云打一次真实短信、花真钱。
        var noSms = new AliyunSms(props) {
            @Override public String send(String phone, String tpl, java.util.Map<String,String> p) {
                return "IT-NO-SEND";
            }
        };
        var sms = new SmsCodeService(jdbc, tx, noSms, props);
        jwt = new JwtService(props);
        auth = new AuthService(jdbc, tx, sms, jwt, pwd);
    }

    @AfterAll
    static void down() { ItDb.close(ds); }

    private long mkUser(String phone, String plain, int mustChange) {
        jdbc.update("DELETE FROM sys_user WHERE phone=?", phone);
        jdbc.update("INSERT INTO sys_user (phone,password_hash,role,status,must_change_pwd) "
                  + "VALUES (?,?,'STUDENT','ACTIVE',?)", phone, pwd.encode(plain), mustChange);
        return jdbc.queryForObject("SELECT id FROM sys_user WHERE phone=?", Long.class, phone);
    }

    @Test
    @DisplayName("★ 用正确的密码登录成功（这条用例缺席过，代价是线上 500）")
    void loginWithCorrectPasswordSucceeds() {
        long id = mkUser("13800138001", "Correct#Horse1", 0);

        var s = auth.loginByPassword("13800138001", "Correct#Horse1");

        assertNotNull(s, "必须拿到会话");
        assertEquals(id, s.userId());
        assertEquals("STUDENT", s.role());
        assertFalse(s.mustChangePwd(), "这个用户不需要强制改密");
        assertTrue(s.expiresInSeconds() > 0, "令牌要有有效期");
        assertNotNull(s.token());
        assertTrue(s.token().split("\\.").length == 3, "应当是一张 JWT");
        // 打码而不是原样回显：日志、前端状态、客服截图都会带上它
        assertTrue(s.phoneMasked().contains("****"), "手机号必须打码，实际 " + s.phoneMasked());
        assertFalse(s.phoneMasked().contains("13800138001"), "不许出现完整号码");
        // 登录要留痕
        assertNotNull(jdbc.queryForObject(
                "SELECT last_login_at FROM sys_user WHERE id=?", java.time.LocalDateTime.class, id));
    }

    @Test
    @DisplayName("★ must_change_pwd=1 必须真的读成 true（TINYINT(1) 会被驱动读成 Boolean）")
    void mustChangePwdIsReadCorrectly() {
        mkUser("13800138002", "Correct#Horse2", 1);

        var s = auth.loginByPassword("13800138002", "Correct#Horse2");

        // 这一条是那个 ClassCastException 的正面判据。
        // 只断言"登录没抛异常"是不够的：把这个字段读成恒 false 照样能过，
        // 而后果是转达过初始密码的人手上那份凭据永远不会被要求换掉。
        assertTrue(s.mustChangePwd(), "初始密码用户必须被要求改密");
    }

    @Test
    @DisplayName("密码错误 → 401，且不透露这个号存不存在")
    void wrongPasswordRejected() {
        mkUser("13800138003", "Correct#Horse3", 0);

        var e1 = assertThrows(ApiException.class,
                () -> auth.loginByPassword("13800138003", "wrong-password"));
        var e2 = assertThrows(ApiException.class,
                () -> auth.loginByPassword("13800138009", "wrong-password"));

        assertEquals(401, e1.status());
        assertEquals(401, e2.status());
        // 两句话必须逐字相同 —— 不同的话，拿一本手机号词典就能把「哪些号注册过」问出来
        assertEquals(e1.getMessage(), e2.getMessage(),
                "「密码错」与「查无此号」必须回同一句话");
    }

    @Test
    @DisplayName("冻结的账号即使密码对也进不来")
    void frozenAccountRejected() {
        mkUser("13800138004", "Correct#Horse4", 0);
        jdbc.update("UPDATE sys_user SET status='FROZEN' WHERE phone=?", "13800138004");

        var e = assertThrows(ApiException.class,
                () -> auth.loginByPassword("13800138004", "Correct#Horse4"));
        assertEquals(403, e.status());
    }

    @Test
    @DisplayName("手机号格式不合法当场拒绝（种子超管那个 00000000000 就是这么登不进去的）")
    void invalidPhoneRejected() {
        var e = assertThrows(ApiException.class,
                () -> auth.loginByPassword("00000000000", "whatever"));
        assertEquals(400, e.status());
        assertTrue(e.getMessage().contains("手机号"), "要说清是手机号的问题：" + e.getMessage());
    }

    @Test
    @DisplayName("没设过密码的账号不能用密码登录（种子超管出厂就是这个状态）")
    void nullPasswordHashCannotLogin() {
        jdbc.update("DELETE FROM sys_user WHERE phone=?", "13800138005");
        jdbc.update("INSERT INTO sys_user (phone,role,status) VALUES (?,'STUDENT','ACTIVE')",
                "13800138005");

        var e = assertThrows(ApiException.class,
                () -> auth.loginByPassword("13800138005", "anything"));
        assertEquals(401, e.status());
    }

    // ================================================================ 强制改密闸

    /**
     * {@code must_change_pwd=1} 时，除 {@code /api/account/**} 外一律 403。
     *
     * <p>这道闸此前<b>完全在前端</b>：登录页显示一个改密表单，而那只是组件 state，
     * 按一下 F5 就没了。后端一个字都没查 must_change_pwd，登录接口照常签发完整可用的令牌 ——
     * 也就是说，拿着别人转达的初始密码 curl 一下登录接口，所有接口照样能调，
     * 「强制改密」没有任何一处是强制的。
     *
     * <p>判据同时钉住<b>放行面</b>：只堵不放的话，用户会被挡在一堵没有门的墙前面 ——
     * 每个接口都说「请先改密」，而改密接口自己也 403。那种"修好了"比坏着更难查。
     */
    @Test
    @DisplayName("★★ must_change_pwd=1：业务接口 403 且带 reason，而改密与「我是谁」必须放行")
    void forcedPasswordChangeGate() {
        long uid = mkUser("13800138009", "Temp#Pass1", 1);
        var s = auth.loginByPassword("13800138009", "Temp#Pass1");
        assertTrue(s.mustChangePwd(), "夹具没造对：这个号应当是 must_change_pwd=1");

        var interceptor = new AuthInterceptor(jwt, jdbc);

        // ① 业务接口：403，且带机器可读的 reason
        for (String path : List.of("/api/agent/dashboard", "/api/admin/overview",
                                   "/api/learn/my/courses", "/api/agent/withdrawals")) {
            var req = new MockHttpServletRequest();
            req.setRequestURI(path);
            req.addHeader("Authorization", "Bearer " + s.token());
            var e = assertThrows(ApiException.class,
                    () -> interceptor.preHandle(req, new MockHttpServletResponse(), new Object()),
                    path + " 在强制改密状态下居然放行了 —— 那这道闸等于不存在");
            assertEquals(403, e.status(), path + " 拒了但状态码不是 403：" + e.getMessage());
            assertEquals(AuthInterceptor.REASON_MUST_CHANGE_PASSWORD, e.reason(),
                    path + " 没带 reason。前端要靠它跳改密页，"
                  + "靠 message 做分支的话，某次纯文案改动就会让整条路静默失效");
        }

        // ② 放行面：改密与「我是谁」必须过。没有这一半，用户就被关在没有门的墙里
        for (String path : List.of("/api/account/password", "/api/account/me")) {
            var req = new MockHttpServletRequest();
            req.setRequestURI(path);
            req.addHeader("Authorization", "Bearer " + s.token());
            assertTrue(interceptor.preHandle(req, new MockHttpServletResponse(), new Object()),
                    path + " 被这道闸拦住了 —— 那用户永远改不掉密码，"
                  + "每个接口都说「请先改密」，而改密接口自己也 403");
        }

        // ③ 配对阳性：把标记清掉之后，业务接口必须恢复放行。
        //    少了这一条，上面那些 403 可能只是因为令牌根本无效（恒拒），什么也没证明。
        jdbc.update("UPDATE sys_user SET must_change_pwd=0 WHERE id=?", uid);
        var req = new MockHttpServletRequest();
        req.setRequestURI("/api/agent/dashboard");
        req.addHeader("Authorization", "Bearer " + s.token());
        assertTrue(interceptor.preHandle(req, new MockHttpServletResponse(), new Object()),
                "标记已清掉，业务接口却还在拒 —— 上面那组 403 不是这道闸拦的");
    }

}
