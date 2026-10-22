package tech.dragonai.wangke.admin;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import tech.dragonai.wangke.auth.AuthInterceptor;
import tech.dragonai.wangke.web.ApiException;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 学员（以及全部账号）的查询与处置。
 *
 * <h3>冻结必须和 token_version+1 在同一条 UPDATE 里</h3>
 * JWT 是无状态的，签出去就收不回来。{@code AuthInterceptor} 每次请求都会查一次用户行，
 * 状态不是 ACTIVE 直接拒 —— 所以「只改 status」看起来也能立刻掉线。但那是假的：
 * 一旦解冻，那张十二小时有效期内的旧令牌立刻又能用了，而冻结期间他做过什么、
 * 令牌是不是已经落在别人手里，平台一无所知。所以必须让版本号跟着 +1，把旧令牌<b>作废</b>而不是<b>屏蔽</b>。
 *
 * <p>而且两件事必须在<b>同一条语句</b>里完成。拆成两条 UPDATE 的话，中间崩一次
 * （连接断、进程被杀、事务没开），留下的是一个「已冻结但令牌还活着」的账号，
 * 而这个状态在任何页面上都看不出来 —— 后台显示已冻结，他照常在看课。
 */
@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private final JdbcTemplate jdbc;
    private final AdminAudit audit;
    private final PasswordEncoder pwd;
    private final SecureRandom rnd = new SecureRandom();

    public AdminUserController(JdbcTemplate jdbc, AdminAudit audit, PasswordEncoder pwd) {
        this.jdbc = jdbc; this.audit = audit; this.pwd = pwd;
    }

    public record ReasonReq(String reason) {}

    // ---------------------------------------------------------------- 查询

    @GetMapping
    Map<String, Object> list(@RequestParam(required = false) String keyword,
                             @RequestParam(required = false) String role,
                             @RequestParam(required = false) String status,
                             @RequestParam(required = false) Long attributedAgentId,
                             @RequestParam(required = false) Integer page,
                             @RequestParam(required = false) Integer size,
                             HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        String r = AdminQuery.enumOf(role, "role", "PLATFORM_ADMIN", "AGENT", "STUDENT", "MENTOR");
        String st = AdminQuery.enumOf(status, "status", "ACTIVE", "FROZEN", "DISABLED");
        int p = AdminQuery.page(page), sz = AdminQuery.size(size);

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (r != null) { where.append(" AND u.role=?"); args.add(r); }
        if (st != null) { where.append(" AND u.status=?"); args.add(st); }
        if (attributedAgentId != null) {
            where.append(" AND u.attributed_agent_id=?"); args.add(attributedAgentId);
        }
        if (!AdminQuery.blank(keyword)) {
            where.append(" AND (u.phone LIKE ? OR u.nickname LIKE ? OR u.real_name LIKE ?)");
            String k = AdminQuery.like(keyword);
            args.add(k); args.add(k); args.add(k);
        }

        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM sys_user u" + where,
                Integer.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(sz); pageArgs.add(AdminQuery.offset(p, sz));
        var rows = jdbc.queryForList(
                "SELECT u.id, u.phone, u.nickname, u.role, u.status, u.real_name,"
              + " u.attributed_agent_id, u.attribution_source, u.must_change_pwd, u.token_version,"
              + " u.last_login_at, u.created_at FROM sys_user u" + where
              + " ORDER BY u.id DESC LIMIT ? OFFSET ?", pageArgs.toArray());

        List<Map<String, Object>> out = new ArrayList<>();
        for (var row : rows) out.add(view(row));
        return AdminJson.ok("total", total == null ? 0 : total, "page", p, "size", sz, "items", out);
    }

    @GetMapping("/{id}")
    Map<String, Object> detail(@PathVariable long id, HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        var rows = jdbc.queryForList("SELECT * FROM sys_user WHERE id=?", id);
        if (rows.isEmpty()) throw ApiException.notFound("账号不存在");
        Map<String, Object> v = view(rows.get(0));
        v.put("idCardMasked", rows.get(0).get("id_card_masked"));
        v.put("attributedAt", rows.get(0).get("attributed_at"));

        v.put("enrollments", jdbc.queryForList(
                "SELECT e.id, e.course_id, c.title AS course_title, e.source, e.status,"
              + " e.valid_from, e.valid_until, e.attributed_agent_id"
              + " FROM enrollment e LEFT JOIN course c ON c.id=e.course_id"
              + " WHERE e.user_id=? ORDER BY e.id DESC", id));
        v.put("devices", jdbc.queryForList(
                "SELECT id, device_label, status, bound_at, removed_at FROM user_device"
              + " WHERE user_id=? ORDER BY id DESC", id));
        v.put("riskScore7d", jdbc.queryForObject(
                "SELECT COALESCE(SUM(score),0) FROM risk_event WHERE user_id=?"
              + " AND created_at > DATE_SUB(NOW(3), INTERVAL 7 DAY)", Integer.class, id));
        return AdminJson.ok("user", v);
    }

    /**
     * 看完整手机号。
     *
     * <p>列表与详情一律只出掩码，完整号码走这个单独入口<b>并且留痕</b>——
     * 和激活码明文那条路是同一套设计。后台页面天天开着、会被投屏与截图，
     * 常驻一整列真实手机号，一次泄漏的是全库用户的联系方式；而客服确实需要打电话，
     * 所以不能没有这条路，只能让每一次使用都留下记录。
     */
    @GetMapping("/{id}/phone")
    Map<String, Object> revealPhone(@PathVariable long id, HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        var rows = jdbc.queryForList("SELECT phone FROM sys_user WHERE id=?", id);
        if (rows.isEmpty()) throw ApiException.notFound("账号不存在");
        // 审计里只记「看了谁的号」，不记号码本身：审计日志的可见面比手机号该有的还宽
        audit.write(req, "USER_PHONE_REVEAL", "sys_user", id, null,
                AdminJson.map("phoneMasked", AdminQuery.maskPhone((String) rows.get(0).get("phone"))));
        return AdminJson.ok("phone", rows.get(0).get("phone"));
    }

    // ---------------------------------------------------------------- 处置

    @PostMapping("/{id}/freeze")
    Map<String, Object> freeze(@PathVariable long id, @RequestBody(required = false) ReasonReq body,
                               HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        String reason = body == null ? null : body.reason();
        if (AdminQuery.blank(reason)) throw ApiException.badRequest("请填写冻结理由");
        long operator = AuthInterceptor.userId(req);
        if (operator == id) throw ApiException.badRequest("不能冻结自己");

        var before = load(id);
        if (before.isEmpty()) throw ApiException.notFound("账号不存在");

        // 「不许冻结平台管理员」写进 WHERE，不写成先查出来再比对：
        // 后者的形态是「查不到就报另一种错」，久而久之会长成一个可枚举账号的探针。
        // 条件在 WHERE 里，不存在 / 不是可冻结对象 / 已冻结，对外都只是「没改到行」
        int rows = jdbc.update(
                "UPDATE sys_user SET status='FROZEN', token_version = token_version + 1"
              + " WHERE id=? AND status<>'FROZEN' AND role<>'PLATFORM_ADMIN'", id);
        if (rows == 0) {
            String now = String.valueOf(before.get("status"));
            if ("PLATFORM_ADMIN".equals(before.get("role")))
                throw ApiException.forbidden("不能冻结平台管理员账号。"
                        + "要收回权限请改角色或走账号注销流程，冻结会把整个后台锁死");
            if ("FROZEN".equals(now))
                return AdminJson.ok("changed", false, "status", "FROZEN", "message", "该账号已处于冻结状态");
            throw ApiException.conflict("冻结失败，当前状态是 " + now + "，请刷新后重试");
        }

        var after = load(id);
        after.put("reason", reason.trim());
        audit.write(req, "USER_FREEZE", "sys_user", id, before, after);
        return AdminJson.ok("changed", true, "status", "FROZEN",
                "tokenVersion", after.get("tokenVersion"),
                "message", "已冻结，其手上的登录令牌当场作废（token_version 已 +1），不必等 12 小时过期");
    }

    @PostMapping("/{id}/unfreeze")
    Map<String, Object> unfreeze(@PathVariable long id, HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        var before = load(id);
        if (before.isEmpty()) throw ApiException.notFound("账号不存在");

        int rows = jdbc.update("UPDATE sys_user SET status='ACTIVE' WHERE id=? AND status='FROZEN'", id);
        if (rows == 0) {
            if ("ACTIVE".equals(before.get("status")))
                return AdminJson.ok("changed", false, "status", "ACTIVE", "message", "该账号本来就是正常状态");
            throw ApiException.conflict("当前状态是 " + before.get("status") + "，不能解冻");
        }
        var after = load(id);
        audit.write(req, "USER_UNFREEZE", "sys_user", id, before, after);
        // 解冻【不】回滚 token_version：那个 +1 是把旧令牌作废，不是暂时屏蔽。
        // 回滚等于让冻结期间可能已经泄漏出去的令牌重新生效
        return AdminJson.ok("changed", true, "status", "ACTIVE",
                "message", "已解冻。冻结时作废的旧令牌不会恢复，用户需要重新登录");
    }

    /**
     * 重置密码。生成一次性初始密码，强制下次登录改密，并作废他手上的全部令牌。
     *
     * <p><b>明文只在这一次响应里出现，绝不写进审计日志。</b>审计要记的是「谁在什么时候
     * 重置了谁的密码」，不是密码本身 —— 把它写进 audit_log 等于给所有能查日志的人
     * 留了一份可登录凭据，而且是永久的。
     */
    @PostMapping("/{id}/reset-password")
    Map<String, Object> resetPassword(@PathVariable long id, HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        var before = load(id);
        if (before.isEmpty()) throw ApiException.notFound("账号不存在");

        String temp = tempPassword();
        int rows = jdbc.update(
                // must_change_pwd=1 与 token_version+1 必须和改密同一条语句：
                // 少了前者，这个由别人知道的密码会一直是他的常用密码；
                // 少了后者，被盗会话在改密后依然有效，而改密的全部意义就是把它踢掉
                "UPDATE sys_user SET password_hash=?, must_change_pwd=1,"
              + " token_version = token_version + 1 WHERE id=?", pwd.encode(temp), id);
        if (rows == 0) throw ApiException.notFound("账号不存在");

        var after = load(id);
        audit.write(req, "USER_RESET_PASSWORD", "sys_user", id, before, after);
        return AdminJson.ok("temporaryPassword", temp, "mustChangePwd", true,
                "message", "初始密码只显示这一次，请通过可信渠道转达。"
                        + "该用户下次登录必须改密，此前签出的令牌已全部作废");
    }

    // ------------------------------------------------------ 开账号 / 送课

    /** 开户请求体。只收这三项 —— 密码由服务端生成，不许由调用方指定。 */
    public record CreateUserReq(String phone, String role, String realName) {}

    /**
     * 平台开户。
     *
     * <h3>会议上那句「超管后台可以开账号是吧」，在此之前的答案是<b>不能</b></h3>
     * {@code sys_user} 的唯一 INSERT 在 {@code AuthService} 注册那条路上，必须本人持短信
     * 验证码走完。也就是说运营想给一个人开号，只能让对方自己去注册。
     * 而 schema 里 {@code must_change_pwd} 这一列的注释写的是「平台开户后首次登录强制改密」——
     * <b>设计时本来就有这条路，只是一直没人把它接出来。</b>
     *
     * <h3>密码为什么由服务端生成、且只回一次</h3>
     * 与 {@link #resetPassword} 同一条纪律：明文只在这一次响应里出现，不写审计日志。
     * 审计要记的是「谁开了谁的号」，不是凭据本身。
     *
     * <p>角色只放 STUDENT 与 MENTOR。<b>不放 AGENT</b>：代理是自助申请 + 平台审批那条路
     * （要填层级、上级推广码、收款信息，还要落 agent_profile 与 agent_invite），
     * 在这里塞一个 role=AGENT 的账号只会得到一个没有档案的空壳 ——
     * 它能登录，但代理端每一页都 404。<b>也不放 PLATFORM_ADMIN</b>：
     * 超管能自助增殖超管的话，一次越权就是永久的。
     *
     * <p>MENTOR 放开是有原因的：答疑工单的「指派导师」在此之前是一条走不通的路 ——
     * 全系统造不出 MENTOR 账号，那个对话框必然 400。
     */
    @PostMapping
    Map<String, Object> create(@RequestBody CreateUserReq r, HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        String phone = r.phone() == null ? "" : r.phone().trim();
        if (!phone.matches("1[3-9]\\d{9}")) throw ApiException.badRequest("手机号格式不正确");
        String role = r.role() == null ? "STUDENT" : r.role().trim().toUpperCase();
        if (!List.of("STUDENT", "MENTOR").contains(role))
            throw ApiException.badRequest("只能开学员或导师账号；代理走自助申请 + 审批那条路");
        String name = r.realName() == null ? null : r.realName().trim();
        if (name != null && name.length() > 64) throw ApiException.badRequest("姓名过长");

        String temp = tempPassword();
        long id;
        try {
            // 手机号唯一由 uk_phone 裁决，不做"先查再插" —— 并发下先查再插必然漏，
            // 而漏掉的表现是 500，不是一句人话。
            jdbc.update("INSERT INTO sys_user (phone,password_hash,role,real_name,must_change_pwd)"
                      + " VALUES (?,?,?,?,1)", phone, pwd.encode(temp), role,
                    name == null || name.isEmpty() ? null : name);
            id = jdbc.queryForObject("SELECT id FROM sys_user WHERE phone=?", Long.class, phone);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw ApiException.conflict("这个手机号已经注册过了");
        }

        audit.write(req, "USER_CREATE", "sys_user", id, null, load(id));
        return AdminJson.ok("userId", id, "role", role, "temporaryPassword", temp,
                "message", "初始密码只显示这一次，请通过可信渠道转达。该用户首次登录必须改密");
    }

    /** 改名请求体。reason 必填：改的是水印上那个名字，要有人为它负责。 */
    public record RealNameReq(String realName, String reason) {}

    /**
     * 更正真实姓名。<b>这是学员那条「写一次」规则唯一的逃生口。</b>
     *
     * <p>学员自助填名（{@code POST /api/account/real-name}）只能从空补成有值，
     * 填错了自己改不了，那里的 409 文案写的是「需要更正请联系平台」。
     * <b>这个接口就是那句话的落点</b> —— 没有它，那句话是空头支票：
     * 学员打错一个字，往后每一节课的防盗录水印上都挂着错的名字，
     * 而水印的全部意义就是"泄漏之后能追回到人"。
     *
     * <p>与自助那条不同，这里<b>允许覆盖</b>（它本来就是用来纠错的），
     * 所以三件事必须齐：原因必填、前后值都进审计、动作名单列。
     *
     * <p>代理账号同样能改 —— 但要知道这一列在代理那侧是<b>提现收款人姓名</b>，
     * 改完之后打款要与它一致。所以响应里把这句话说出来，别让操作的人事后才知道。
     */
    @PostMapping("/{id}/real-name")
    Map<String, Object> setRealName(@PathVariable long id, @RequestBody RealNameReq r,
                                    HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        String name = r.realName() == null ? "" : r.realName().trim();
        if (name.isEmpty()) throw ApiException.badRequest("请填写姓名");
        if (name.length() > 64) throw ApiException.badRequest("姓名过长");
        String reason = r.reason() == null ? "" : r.reason().trim();
        if (reason.isEmpty()) throw ApiException.badRequest("请填写更正原因，它会进审计日志");
        if (reason.length() > 255) throw ApiException.badRequest("原因过长");

        // load() 不带 real_name（它是给冻结/改密那几条用的），这里单独取一次原值：
        // 审计要能回答"改之前叫什么"，只记新值等于把纠错本身变成不可复盘的动作。
        var rows = jdbc.queryForList("SELECT real_name FROM sys_user WHERE id=?", id);
        if (rows.isEmpty()) throw ApiException.notFound("账号不存在");
        Object oldName = rows.get(0).get("real_name");

        int n = jdbc.update("UPDATE sys_user SET real_name=? WHERE id=?", name, id);
        if (n == 0) throw ApiException.notFound("账号不存在");

        audit.write(req, "USER_REAL_NAME", "sys_user", id,
                AdminJson.map("realName", oldName),
                AdminJson.map("realName", name, "reason", reason));

        Integer isAgent = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_profile WHERE user_id=?", Integer.class, id);
        return AdminJson.ok("realName", name, "message",
                isAgent != null && isAgent > 0
                        ? "已更正。注意：这个账号有代理档案，这一列同时是提现打款的收款人姓名"
                        : "已更正。学员看课时水印上显示的就是这个名字");
    }

    /** 赠课请求体。reason 必填 —— 白送一门课要有人为它签字。 */
    public record GrantReq(Long courseId, Integer months, String reason) {}

    /**
     * 直接给一个账号开通课程（赠课 / 内部账号 / 试用）。
     *
     * <h3>为什么这条路必须存在</h3>
     * 在此之前，超管想让某个人能看课，唯一的动作是<b>「订单 → 确认对公转账到账」</b>。
     * 那个接口不校验订单原本是不是走对公转账，事实上就是现成的送课后门 ——
     * 代价是把一次白送记成<b>一笔真实收入</b>，还照着结了佣金。
     * 账面上没有任何异常，只有收入是假的。
     *
     * <p>而库里早就为正规的赠课留好了位置：{@code enrollment.source} 的枚举里有
     * {@code 'GRANT'}，{@code ck_enrollment_source} 也明确放行「GRANT 可以既没有码也没有单」，
     * 后台的中文标签甚至已经写好了「平台赠送」。<b>缺的只是一个写得出这个值的接口。</b>
     *
     * <h3>赠课不产生归属，也不产生佣金</h3>
     * {@code attributed_agent_id} 留空：这份权益不是谁卖出去的，挂到某个代理名下
     * 就等于凭空给他记了一笔业绩，而结算是按业绩走的。
     *
     * <p>「一人一课一份权益」由 {@code uk_user_course} 裁决，不做先查后插。
     */
    @PostMapping("/{id}/enrollments")
    Map<String, Object> grant(@PathVariable long id, @RequestBody GrantReq r,
                              HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        if (r.courseId() == null) throw ApiException.badRequest("请指定课程");
        String reason = r.reason() == null ? "" : r.reason().trim();
        if (reason.isEmpty()) throw ApiException.badRequest("请填写赠课原因，它会进审计日志");
        if (reason.length() > 255) throw ApiException.badRequest("原因过长");
        if (load(id).isEmpty()) throw ApiException.notFound("账号不存在");

        var courses = jdbc.queryForList(
                "SELECT id, title, status, access_months FROM course WHERE id=?", r.courseId());
        if (courses.isEmpty()) throw ApiException.notFound("课程不存在");
        var course = courses.get(0);
        // 草稿 / 已下架的课照样可以赠：内部账号常常要在上架前先看。
        // 但 lesson.status='ON' 那一层仍然管着，看得到的只有已上架的讲。
        // 上限只卡【调用方传进来的】那个数。课程自己配了多长就是多长 ——
        // 把 120 也套在课程默认值上，等于一门配了 10 年期的课永远赠不出去，
        // 而报出来的错是"有效期过长"，看起来像调用方填错了。
        int months;
        if (r.months() != null) {
            months = r.months();
            if (months <= 0) throw ApiException.badRequest("有效期必须是正整数");
            if (months > 120) throw ApiException.badRequest("有效期过长（最多 120 个月）");
        } else {
            months = AdminQuery.intOf(course.get("access_months"), 24);
        }

        try {
            jdbc.update(
                    // 有效期用库侧时钟算：写它的钟和判它的钟必须是同一个，
                    // 否则时区一不一致，赠出去的课要么当场过期要么多送几个月
                    "INSERT INTO enrollment (user_id,course_id,source,activation_code_id,order_id,"
                  + "attributed_agent_id,valid_from,valid_until,status,agreement_version,agreed_at)"
                  + " VALUES (?,?,'GRANT',NULL,NULL,NULL,NOW(3),"
                  + "         DATE_ADD(NOW(3), INTERVAL ? MONTH),'ACTIVE','ADMIN_GRANT',NOW(3))",
                    id, r.courseId(), months);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw ApiException.conflict("这个账号已经有这门课的权益了");
        } catch (org.springframework.dao.DataAccessException e) {
            // trg_enrollment_precheck（冻结/停用的账号不能开权益）抛出来的是
            // **UncategorizedSQLException**，不是 DataIntegrityViolationException ——
            // 触发器的 SIGNAL 走的是自定义 SQLSTATE 45000，Spring 的异常翻译认不出来。
            // 原来这里 catch 的是后者，那是一段永远不会执行的守卫：
            // 运营看到的会是一个 500，而不是「学员账号不在正常状态」。
            throw ApiException.conflict(rootMessage(e));
        }

        var after = jdbc.queryForList(
                "SELECT id, course_id, source, valid_from, valid_until, status"
              + " FROM enrollment WHERE user_id=? AND course_id=?", id, r.courseId());
        // ★ 审计对象写 sys_user 而不是 enrollment。
        //   后台「学员详情」里的改动记录是按 target_type='sys_user' + target_id=用户 id 查的，
        //   挂到 enrollment 上的话，这笔赠课在那一页上【完全看不到】——
        //   而"这门课他是怎么来的"正是查那一页的人要问的第一个问题。
        //   enrollment id 放进 after_json 里，不丢。
        audit.write(req, "ENROLLMENT_GRANT", "sys_user", id,
                AdminJson.map("reason", reason, "courseId", r.courseId(), "months", months),
                after.isEmpty() ? null : after.get(0));
        return AdminJson.ok("courseTitle", course.get("title"), "months", months,
                "message", "已开通，不产生订单、不产生归属、不结佣金");
    }

    /** 触发器抛出来的 MESSAGE_TEXT 藏在异常链最深处，直接 getMessage 只会拿到一堆 SQL。 */
    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) t = t.getCause();
        String m = t.getMessage();
        return m == null || m.isBlank() ? "开通失败" : m;
    }

    // ---------------------------------------------------------------- 内部

    /** 满足 AuthService 的密码规则（≥8 位、含字母与数字），且不含形近字符。 */
    private String tempPassword() {
        // 去掉 0/O/1/l/I：初始密码要靠人念给人听，形近字符会变成一通客服电话
        final String letters = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ";
        final String digits = "23456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) sb.append(letters.charAt(rnd.nextInt(letters.length())));
        for (int i = 0; i < 4; i++) sb.append(digits.charAt(rnd.nextInt(digits.length())));
        return sb.toString();
    }

    private Map<String, Object> view(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", AdminQuery.id(r.get("id")));
        m.put("phoneMasked", AdminQuery.maskPhone((String) r.get("phone")));
        m.put("nickname", r.get("nickname"));
        m.put("realName", r.get("real_name"));
        m.put("role", r.get("role"));
        m.put("status", r.get("status"));
        m.put("attributedAgentId", AdminQuery.id(r.get("attributed_agent_id")));
        m.put("attributionSource", r.get("attribution_source"));
        m.put("mustChangePwd", AdminQuery.flag(r.get("must_change_pwd")));
        m.put("tokenVersion", r.get("token_version"));
        m.put("lastLoginAt", r.get("last_login_at"));
        m.put("createdAt", r.get("created_at"));
        return m;
    }

    private Map<String, Object> load(long id) {
        var rows = jdbc.queryForList(
                "SELECT id, role, status, token_version, must_change_pwd FROM sys_user WHERE id=?", id);
        if (rows.isEmpty()) return AdminJson.map();
        var r = rows.get(0);
        return AdminJson.map("userId", AdminQuery.id(r.get("id")), "role", r.get("role"),
                "status", r.get("status"), "tokenVersion", r.get("token_version"),
                "mustChangePwd", AdminQuery.flag(r.get("must_change_pwd")));
    }
}
