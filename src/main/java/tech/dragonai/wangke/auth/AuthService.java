package tech.dragonai.wangke.auth;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tech.dragonai.wangke.web.ApiException;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 注册 / 登录 / 改密 / 归属落地。
 *
 * <p><b>注册必须是个闭环。</b>这条在优谷那边栽过一次：注册时生成随机密码、
 * 又没有验证码登录入口，于是用户注册完就再也进不来了 —— 注册即失联，
 * 而后台看到的是一堆"注册成功"的账号。所以这里的注册返回令牌，当场就是登录态。
 *
 * <p><b>归属只在注册这一刻落定</b>（§7.5 first-touch）。推广链接带来的 ref
 * 只在这一次有效；之后再点别人的链接不改归属。库里由 trg_user_attribution_immutable 兜底，
 * 这里只负责把它写对一次。
 */
@Service
public class AuthService {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final SmsCodeService sms;
    private final JwtService jwt;
    private final PasswordEncoder pwd;

    public AuthService(JdbcTemplate jdbc, TransactionTemplate tx, SmsCodeService sms,
                       JwtService jwt, PasswordEncoder pwd) {
        this.jdbc = jdbc; this.tx = tx; this.sms = sms; this.jwt = jwt; this.pwd = pwd;
    }

    public record Session(String token, long userId, String role, String phoneMasked,
                          boolean mustChangePwd, long expiresInSeconds) {}

    /**
     * 手机号 + 验证码注册。密码可选：不填就是纯验证码登录的账号。
     *
     * @param refCode 推广码（?ref=）。找不到或指向未生效代理时<b>静默忽略</b>——
     *                归属不成立不该拦住注册，那是把商务问题变成用户故障。
     */
    public Session register(String phone, String code, String password, String refCode, String ip) {
        SmsCodeService.requirePhone(phone);
        sms.verify(phone, SmsCodeService.Scene.REGISTER, code);

        Long attributed = resolveRef(refCode);

        return tx.execute(st -> {
            Long existing = findUserIdByPhone(phone);
            if (existing != null)
                throw ApiException.conflict("该手机号已注册，请直接登录");

            String hash = (password == null || password.isBlank()) ? null : encode(password);
            try {
                if (attributed != null) {
                    jdbc.update("INSERT INTO sys_user (phone,password_hash,role,attributed_agent_id,"
                              + "attributed_at,attribution_source) VALUES (?,?,'STUDENT',?,NOW(3),'REF_LINK')",
                            phone, hash, attributed);
                } else {
                    jdbc.update("INSERT INTO sys_user (phone,password_hash,role) VALUES (?,?,'STUDENT')",
                            phone, hash);
                }
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("该手机号已注册，请直接登录");
            }
            long id = findUserIdByPhone(phone);
            return session(id, "STUDENT", phone, false);
        });
    }

    /** 验证码登录。号码不存在时<b>自动注册</b>——多一步"请先注册"只会流失用户。 */
    public Session loginByCode(String phone, String code, String refCode, String ip) {
        SmsCodeService.requirePhone(phone);
        sms.verify(phone, SmsCodeService.Scene.LOGIN, code);
        Long id = findUserIdByPhone(phone);
        if (id == null) {
            Long attributed = resolveRef(refCode);
            if (attributed != null)
                jdbc.update("INSERT INTO sys_user (phone,role,attributed_agent_id,attributed_at,"
                          + "attribution_source) VALUES (?,'STUDENT',?,NOW(3),'REF_LINK')", phone, attributed);
            else
                jdbc.update("INSERT INTO sys_user (phone,role) VALUES (?,'STUDENT')", phone);
            id = findUserIdByPhone(phone);
        }
        return loadSession(id, phone);
    }

    /**
     * 密码登录。
     *
     * <p>失败提示统一为"手机号或密码不正确"，不区分"号码不存在"与"密码错" ——
     * 区分开等于送给攻击者一个手机号枚举接口。
     */
    public Session loginByPassword(String phone, String password) {
        SmsCodeService.requirePhone(phone);
        var rows = jdbc.queryForList(
                "SELECT id, password_hash, role, status, token_version, must_change_pwd "
              + "FROM sys_user WHERE phone=?", phone);
        if (rows.isEmpty()) {
            // 号码不存在也走一遍哈希计算，避免用响应时间把存在性问出来
            pwd.matches(password == null ? "" : password,
                    "$2a$12$abcdefghijklmnopqrstuv0123456789012345678901234567890");
            throw ApiException.unauthorized("手机号或密码不正确");
        }
        var u = rows.get(0);
        String hash = (String) u.get("password_hash");
        if (hash == null || password == null || !pwd.matches(password, hash))
            throw ApiException.unauthorized("手机号或密码不正确");
        if (!"ACTIVE".equals(u.get("status")))
            throw ApiException.forbidden("账号已被冻结，请联系平台");

        long id = ((Number) u.get("id")).longValue();
        jdbc.update("UPDATE sys_user SET last_login_at=NOW(3) WHERE id=?", id);
        return session(id, (String) u.get("role"), phone,
                mustChangePwd(u.get("must_change_pwd")));
    }

    /**
     * 「你打的旧密码不对」的机器可读标识。
     *
     * <p>它与「登录状态失效」共用 401，但对前端是两件完全不同的事：
     * 后者要清令牌把人送回登录页，前者<b>绝不能清</b> —— 清了之后用户第二次
     * 输对密码也只会拿到「请先登录」，再也改不成。
     */
    public static final String REASON_BAD_CREDENTIALS = "BAD_CREDENTIALS";

    /** 忘记密码：验证码 + 新密码。改完令牌版本 +1，此前签出的每一张当场作废。 */
    public void resetPassword(String phone, String code, String newPassword) {
        SmsCodeService.requirePhone(phone);
        sms.verify(phone, SmsCodeService.Scene.RESET, code);
        Long id = findUserIdByPhone(phone);
        if (id == null) throw ApiException.notFound("该手机号尚未注册");
        jdbc.update("UPDATE sys_user SET password_hash=?, must_change_pwd=0, "
                  + "token_version = token_version + 1 WHERE id=?", encode(newPassword), id);
    }

    /** 已登录改密。旧密码必须对——否则一个被盗的会话就能把号彻底夺走。 */
    public void changePassword(long userId, String oldPassword, String newPassword) {
        var rows = jdbc.queryForList("SELECT password_hash, must_change_pwd FROM sys_user WHERE id=?", userId);
        if (rows.isEmpty()) throw ApiException.notFound("账号不存在");
        String hash = (String) rows.get(0).get("password_hash");
        boolean forced = mustChangePwd(rows.get(0).get("must_change_pwd"));
        // 平台开户的初始密码那一档（must_change_pwd=1）仍要校验旧密码，
        // 只是允许 hash 为空的历史账号跳过
        // ★ 带 reason=BAD_CREDENTIALS。这里的 401 说的是「你刚打的那串旧密码不对」，
        //   **不是**「你的登录状态失效了」—— 两件事都用 401，前端就分不开：
        //   shared/api.ts 的 401 分支会无条件 auth.clear()，于是初始密码打错一个字符，
        //   令牌当场被删，之后再输对也只会拿到「请先登录」，强制改密屏把自己锁死。
        //   前端据这个标识跳过清令牌，文案照旧。
        if (hash != null && (oldPassword == null || !pwd.matches(oldPassword, hash)))
            throw ApiException.unauthorized("原密码不正确").withReason(REASON_BAD_CREDENTIALS);
        if (hash == null && !forced && oldPassword != null && !oldPassword.isBlank())
            throw ApiException.unauthorized("原密码不正确").withReason(REASON_BAD_CREDENTIALS);
        jdbc.update("UPDATE sys_user SET password_hash=?, must_change_pwd=0, "
                  + "token_version = token_version + 1 WHERE id=?", encode(newPassword), userId);
    }

    /**
     * 当前账号。<b>逐个字段显式出网，不用 map 透传。</b>
     *
     * <h3>为什么不再 {@code new HashMap<>(rows.get(0))}</h3>
     * 透传的口径是"这条 SELECT 选了什么就出什么"，于是它有两个毛病，而且都发生过：
     * <ul>
     *   <li><b>出得太多。</b>原来跟着出去的有 {@code attributed_agent_id} ——
     *       而 {@code ActivationController} 里白纸黑字写着"学员不该知道自己被算给了哪个代理"
     *       （知道了就会去找那个人要返点，代理之间也会拿它互相挖客）。
     *       给 SELECT 加一列是个看起来毫无风险的动作，它却能把一条产品规则直接作废。
     *   <li><b>键名跟着数据库走。</b>出的是 {@code real_name}（下划线），
     *       而三端的类型声明写的是 {@code realName} —— 于是姓名<b>从来没到过前端</b>，
     *       前端也不会报错，只是那一格永远空着。代理端后来在 api.ts 里打了个
     *       {@code u?.real_name ?? u?.realName} 的补丁，学员端没打，于是学员端一直是空的。
     * </ul>
     * 显式列出来之后，加一列不会顺带出网，改列名也不会静默改掉契约。
     */
    public Map<String, Object> me(long userId) {
        var rows = jdbc.queryForList(
                "SELECT u.id, u.phone, u.nickname, u.role, u.status, u.real_name, u.must_change_pwd,"
              + " a.id AS agent_id, a.level AS agent_level,"
              + " a.status AS agent_status, a.agent_code"
              + " FROM sys_user u LEFT JOIN agent_profile a ON a.user_id = u.id"
              + " WHERE u.id=?", userId);
        if (rows.isEmpty()) throw ApiException.notFound("账号不存在");
        var r = rows.get(0);
        var u = new java.util.LinkedHashMap<String, Object>();
        u.put("id", r.get("id"));
        u.put("phone", AliyunSms.maskPhone((String) r.get("phone")));
        u.put("nickname", r.get("nickname"));
        u.put("realName", r.get("real_name"));
        u.put("role", r.get("role"));
        u.put("status", r.get("status"));
        u.put("mustChangePwd", r.get("must_change_pwd"));
        // 代理档案那几项是他自己的档案，给本人看没有问题；同样逐个列出。
        u.put("agentId", r.get("agent_id"));
        u.put("agentLevel", r.get("agent_level"));
        u.put("agentStatus", r.get("agent_status"));
        u.put("agentCode", r.get("agent_code"));
        // 兼容：代理端 api.ts 与学员端旧包读的是下划线/旧键名，多带一份别名，
        // 等三端都切到 camelCase 之后再删。删的时候要连着 web/ 一起 grep。
        u.put("real_name", r.get("real_name"));
        u.put("must_change_pwd", r.get("must_change_pwd"));
        return u;
    }

    // ---------------------------------------------------------------- 内部

    /** 推广码 → 代理 id。只认已生效的代理；找不到返回 null（静默忽略，不拦注册）。 */
    Long resolveRef(String refCode) {
        if (refCode == null || refCode.isBlank()) return null;
        var rows = jdbc.queryForList(
                "SELECT id FROM agent_profile WHERE agent_code=? AND status='ACTIVE'", refCode.trim());
        return rows.isEmpty() ? null : ((Number) rows.get(0).get("id")).longValue();
    }

    private Long findUserIdByPhone(String phone) {
        var rows = jdbc.queryForList("SELECT id FROM sys_user WHERE phone=?", phone);
        return rows.isEmpty() ? null : ((Number) rows.get(0).get("id")).longValue();
    }

    private Session loadSession(long id, String phone) {
        var u = jdbc.queryForMap(
                "SELECT role, status, must_change_pwd FROM sys_user WHERE id=?", id);
        if (!"ACTIVE".equals(u.get("status")))
            throw ApiException.forbidden("账号已被冻结，请联系平台");
        jdbc.update("UPDATE sys_user SET last_login_at=NOW(3) WHERE id=?", id);
        return session(id, (String) u.get("role"), phone,
                mustChangePwd(u.get("must_change_pwd")));
    }

    private Session session(long id, String role, String phone, boolean mustChange) {
        int tv = jdbc.queryForObject("SELECT token_version FROM sys_user WHERE id=?", Integer.class, id);
        return new Session(jwt.issue(id, role, tv), id, role,
                AliyunSms.maskPhone(phone), mustChange, jwt.ttlSeconds());
    }

    private String encode(String raw) {
        if (raw == null || raw.length() < 8)
            throw ApiException.badRequest("密码至少 8 位");
        if (raw.length() > 72)      // bcrypt 只取前 72 字节，超长部分被静默丢弃
            throw ApiException.badRequest("密码过长（不超过 72 位）");
        if (!raw.matches(".*[A-Za-z].*") || !raw.matches(".*\\d.*"))
            throw ApiException.badRequest("密码需同时包含字母和数字");
        return pwd.encode(raw);
    }

    LocalDateTime now() { return LocalDateTime.now(); }

    /**
     * {@code sys_user.must_change_pwd} 是 TINYINT(1)。
     *
     * <p>MySQL Connector/J 默认开着 {@code tinyInt1isBit=true}，于是 TINYINT(1)
     * 回来的是 <b>Boolean 而不是 Number</b>。原来这里直接强转 Number，
     * 后果是<b>密码登录一旦成功就 500</b> —— 而密码错误反倒能正确返回 401，
     * 因为异常发生在密码校验【之后】。
     *
     * <p>这条 bug 躲过了全部 244 个测试：没有一条用例真的用正确密码登录成功过。
     * 它是在生产机上真登一次才暴露的。
     *
     * <p>两种类型都认，不假设驱动配置 —— 连接串上加不加 tinyInt1isBit 都不该改变行为。
     * （同一份知识在 learn/CourseService.asBool、exam/ExamService.boolOrNull、
     *   admin/AdminQuery.flag 里各有一份实现，三处都是各自踩到后就地解决的。
     *   本该收口成一个工具方法，但那三处都有测试覆盖、此刻不动它们。）
     */
    // 包级可见（不是 private）：AuthInterceptor 的强制改密闸也要读这一列，
    // 而它就在同一个包里。再抄一份的话，这份知识就有第五个副本了 —— 上面那段
    // 注释正是在说这件事，不该由它自己再添一笔。
    static boolean mustChangePwd(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        return ((Number) v).intValue() != 0;
    }
}
