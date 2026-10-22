package tech.dragonai.wangke.agent;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tech.dragonai.wangke.auth.AliyunSms;
import tech.dragonai.wangke.trade.ActivationCodes;
import tech.dragonai.wangke.trade.CodeService;
import tech.dragonai.wangke.web.ApiException;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 代理商后台的全部读写。控制器只负责取当前登录用户与 IP，业务在这里。
 *
 * <h3>这一层最大的风险是越权，所以有一条贯穿全类的写法约束</h3>
 * <b>客户端永远不能指定"看哪个代理的数据"。</b>每个方法都只收 {@code userId}
 * （由鉴权拦截器从令牌解出来的），自己去解析代理档案，再把解析出的 {@code agent.id()}
 * 拼进 WHERE。所有过滤条件都在 SQL 的 WHERE 里，绝不"先按 id 查出来再比对 owner"——
 * 后者的问题不是它当下会错，而是它会长成一个探针：
 * 只要哪天有人给"查到了但不是你的"补一条不同的错误分支（比如 403 而不是 404），
 * 别人的码 id / 订单 id 就能被逐个枚举出来，而这个演化过程不会有任何测试变红。
 *
 * <h3>PENDING 代理没有经营能力</h3>
 * 审核中的代理除了看自己那份档案，其他接口一律拒。理由不是洁癖：
 * 申请是<b>用户自助</b>提交的，如果 PENDING 就能查码、能提现，那"平台审批"这一步
 * 拦不住任何东西 —— 谁都能注册一个号、点一下申请，然后拿到一个经营中的代理后台。
 */
@Service
public class AgentService {

    /** 推广码长度（不含前缀）。24 字符表 × 9 位 ≈ 2.6e12，配合唯一键重试足够。 */
    private static final int CODE_BODY = 9;

    /** 分页上限。不设上限的话 size=1000000 就是一次全表导出。 */
    static final int MAX_PAGE_SIZE = 100;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final CodeService codes;
    private final SecureRandom rnd = new SecureRandom();

    public AgentService(JdbcTemplate jdbc, TransactionTemplate tx, CodeService codes) {
        this.jdbc = jdbc; this.tx = tx; this.codes = codes;
    }

    /** 当前登录用户的代理身份。id 是 agent_profile.id，不是 user_id —— 两者到处混用会串数据。 */
    public record Agent(long id, long userId, int level, Long parentAgentId,
                        String agentCode, String status) {
        public boolean operational() { return "ACTIVE".equals(status); }
    }

    public record Page(List<Map<String, Object>> items, int page, int size, long total) {}

    public record ApplyResult(long agentId, String agentCode, int level, String status) {}

    // ================================================================= 身份

    /** 解析代理档案；不是代理返回 null。uk_user 保证至多一行。 */
    public Agent profileOf(long userId) {
        var rows = jdbc.queryForList(
                "SELECT id, user_id, level, parent_agent_id, agent_code, status "
              + "FROM agent_profile WHERE user_id=?", userId);
        if (rows.isEmpty()) return null;
        var r = rows.get(0);
        return new Agent(num(r.get("id")), num(r.get("user_id")),
                ((Number) r.get("level")).intValue(),
                r.get("parent_agent_id") == null ? null : num(r.get("parent_agent_id")),
                (String) r.get("agent_code"), (String) r.get("status"));
    }

    /**
     * 取"可经营"的代理身份，取不到就当场拒。
     *
     * <p>每个经营接口的第一行都调它，而不是各自写一遍 status 判断：
     * 分散写的话，漏判的那个接口不会报错、不会变红，只是对审核中的代理照常放行。
     */
    public Agent requireOperational(long userId) {
        Agent a = profileOf(userId);
        if (a == null)
            throw ApiException.forbidden("你还不是代理商，请先提交代理申请");
        switch (a.status()) {
            case "ACTIVE": return a;
            case "PENDING": throw ApiException.forbidden(
                    "代理资格审核中，通过后才能进货、查看激活码与提现");
            case "SUSPENDED": throw ApiException.forbidden(
                    "代理账号已暂停，暂时不能开展经营，请联系平台");
            case "TERMINATED": throw ApiException.forbidden("代理合作已终止");
            default: throw ApiException.forbidden("代理状态异常，请联系平台");
        }
    }

    // ================================================================= 档案

    /**
     * 我的代理档案。<b>这是 PENDING 代理唯一能调的接口</b>——
     * 看不到自己的审核状态，用户只能靠反复点别的接口撞 403 来猜，那是最差的体验。
     */
    public Map<String, Object> me(long userId) {
        var rows = jdbc.queryForList(
                "SELECT a.id, a.agent_code, a.level, a.parent_agent_id, a.status, a.company_name,"
              + " a.subject_type, a.tax_no, a.contact_name, a.settle_bank_name, a.settle_bank_acct,"
              + " a.approved_at, a.created_at, p.agent_code AS parent_agent_code,"
              + " u.id AS user_id, u.real_name, u.phone, u.nickname, u.role"
              + " FROM agent_profile a"
              + " LEFT JOIN agent_profile p ON p.id = a.parent_agent_id"
              + " JOIN sys_user u ON u.id = a.user_id"
              + " WHERE a.user_id=?", userId);
        if (rows.isEmpty())
            throw ApiException.notFound("你还不是代理商，请先提交代理申请");
        var r = rows.get(0);

        var out = new LinkedHashMap<String, Object>();
        out.put("agentId", num(r.get("id")));
        out.put("agentCode", r.get("agent_code"));
        // 推广链接的域名由前端拼：后端并不知道它今天挂在哪个域名下，
        // 写死一个域名的下场是换域名那天所有存量二维码同时失效且没人发现。
        out.put("refQuery", "ref=" + r.get("agent_code"));
        out.put("level", ((Number) r.get("level")).intValue());
        out.put("levelText", ((Number) r.get("level")).intValue() == 1 ? "一级代理" : "二级代理");
        out.put("status", r.get("status"));
        out.put("statusText", statusText((String) r.get("status")));
        out.put("canOperate", "ACTIVE".equals(r.get("status")));
        // 只给上级的推广码，不给上级的手机号/联系人：二级知道自己挂在谁下面是必要的，
        // 拿到上级的联系方式则不是 —— 上下级之间的私下往来是分销体系里最难查的一类纠纷。
        out.put("parentAgentCode", r.get("parent_agent_code"));
        out.put("companyName", r.get("company_name"));
        out.put("subjectType", r.get("subject_type"));
        out.put("taxNo", r.get("tax_no"));
        out.put("contactName", r.get("contact_name"));
        out.put("realName", r.get("real_name"));
        out.put("phone", AliyunSms.maskPhone((String) r.get("phone")));
        out.put("settleBankName", r.get("settle_bank_name"));
        // 自己的卡号也只回掩码：代理后台天天开着，投屏、截图、同事路过都是常态，
        // 而完整卡号在这个页面上没有任何用途 —— 它只在打款那一侧被读。
        out.put("settleBankAcct", maskAcct((String) r.get("settle_bank_acct")));
        out.put("settleReady", notBlank((String) r.get("settle_bank_acct")));
        out.put("approvedAt", r.get("approved_at"));
        out.put("createdAt", r.get("created_at"));

        // ---- 三段式：user / agent / application -------------------------------
        //
        // ★ 代理端整个应用读的是嵌套形状（AgentMe = {user, agent, application}），
        //   而这里一直只出扁平键。后果不是显示错，是**每一个有档案的代理都白屏**：
        //   apps/agent/src/App.tsx 里 `me.user.nickname` 在 render 期抛 TypeError，
        //   全仓没有 ErrorBoundary，React 卸载整棵树 —— 提现、佣金、激活码一页都打不开。
        //
        //   前端 AgentApi.me() 里那个兜底只在 **404**（还不是代理）时才拼出嵌套体，
        //   恰恰是「已经是代理」这条 200 的正常路径完全裸奔。那段兜底的注释里
        //   写着「实测撞到过」，也就是说这个白屏被撞见过，但只补了走不到的那一半。
        //
        //   扁平键**全部保留**，这里只是加三个容器键（与 rows/items 同一手法）：
        //   改名是破坏性的，加键是纯增量。
        //
        //   role 不是猜的：trg_agent_parent_must_be_l1_ins 强制代理档案只能挂在
        //   AGENT 角色的账号上，这里直接取库里那一列，不做任何推断。
        var user = new LinkedHashMap<String, Object>();
        user.put("id", num(r.get("user_id")));
        user.put("phone", AliyunSms.maskPhone((String) r.get("phone")));
        user.put("nickname", r.get("nickname"));
        user.put("realName", r.get("real_name"));
        user.put("role", r.get("role"));
        out.put("user", user);

        var agent = new LinkedHashMap<String, Object>();
        agent.put("id", num(r.get("id")));
        agent.put("agentCode", r.get("agent_code"));
        agent.put("level", ((Number) r.get("level")).intValue());
        agent.put("status", r.get("status"));
        agent.put("statusText", statusText((String) r.get("status")));
        agent.put("parentAgentCode", r.get("parent_agent_code"));
        agent.put("companyName", r.get("company_name"));
        agent.put("subjectType", r.get("subject_type"));
        agent.put("taxNo", r.get("tax_no"));
        agent.put("contactName", r.get("contact_name"));
        agent.put("settleBankName", r.get("settle_bank_name"));
        agent.put("settleBankAcct", maskAcct((String) r.get("settle_bank_acct")));
        agent.put("approvedAt", r.get("approved_at"));
        agent.put("createdAt", r.get("created_at"));

        // ★★ 「申请被驳回」和「合作被终止」在库里是<b>同一个值</b>（TERMINATED）。
        //
        //   后台那两个动作是分开的（AGENT_REJECT / AGENT_SUSPEND→TERMINATED），
        //   AdminAgentController 的注释也写着「两种终态，事后分得出来」——
        //   可 agent_profile.status 只有一列，两条路都写 TERMINATED，事后<b>分不出来</b>。
        //
        //   于是代理端对一个刚被驳回的申请人说的是「你的代理合作已终止…已经产生、
        //   且过了冻结期的佣金仍可提现」：他从来没有过合作，也没有一分钱佣金。
        //   更糟的是这条路是死的 —— Gate 里那个「修改资料，重新申请」的分支挂在
        //   `me.application` 上，而有档案时 application 恒为 null，永远走不到。
        //
        //   区分不需要改表：approved_at 只在审批通过时才写。没通过过的 TERMINATED
        //   就是「申请被驳回」，通过过的才是「合作终止」。
        boolean everApproved = r.get("approved_at") != null;
        agent.put("everApproved", everApproved);
        agent.put("rejectedApplication", !everApproved && "TERMINATED".equals(r.get("status")));
        // 能不能改资料重新提交一次。与 apply() 的放行条件是同一句话，不许两处各写一遍
        agent.put("canReapply", canReapply((String) r.get("status"), everApproved));
        agent.put("statusNote", statusNoteOf(num(r.get("id")), (String) r.get("status")));
        out.put("agent", agent);

        // 有档案就没有「在审的申请单」这一说 —— 档案本身带着 status（PENDING 就是在审）。
        // 显式给 null 而不是省略：省略的话前端 me.application?.status 与
        // 「后端这版还没这个字段」在网线上分不出来。
        // 注意 application 是 null，non_null 会把它删掉 —— 前端读的是可选链，无妨。
        out.put("application", null);
        return out;
    }

    /**
     * 把一份<b>被驳回过</b>的申请改完资料重新提交。
     *
     * <p>裁决点是那条 UPDATE 的影响行数，不是「先查状态再决定」：
     * {@code WHERE id=? AND status='TERMINATED' AND approved_at IS NULL}。
     * 少了后半个条件，一个真正合作过又被终止的代理就能在前台自助复活自己 ——
     * 而那是平台的决定。中间开一道缝去 SELECT 判断，正好够后台在这一瞬间改掉状态。
     *
     * <p>推广码<b>不换</b>：它已经印在存量二维码和推广链接上了，换一个等于把之前发出去的
     * 全部作废，而这次动作在业务上只是「资料重填」。
     *
     * @return 重新提交成功的结果；这份档案不满足重投条件时返回 null（由调用方给 409）
     */
    private ApplyResult reopen(long agentId, long userId, int lv, Long parentId, String subject,
                               String companyName, String taxNo, String contactName,
                               String settleBankName, String settleBankAcct, String ip) {
        int rows = jdbc.update(
                "UPDATE agent_profile SET level=?, parent_agent_id=?, company_name=?,"
              + " subject_type=?, tax_no=?, contact_name=?, settle_bank_name=?, settle_bank_acct=?,"
              + " status='PENDING'"
              + " WHERE id=? AND status='TERMINATED' AND approved_at IS NULL",
                lv, parentId, trimToNull(companyName), subject, trimToNull(taxNo),
                trimToNull(contactName), trimToNull(settleBankName), trimToNull(settleBankAcct),
                agentId);
        if (rows != 1) return null;

        audit(userId, "AGENT_REAPPLY", "agent_profile", agentId, ip,
                "JSON_OBJECT('level',?,'parent_agent_id',?,'subject_type',?)",
                lv, parentId, subject);

        String code = (String) jdbc.queryForMap(
                "SELECT agent_code FROM agent_profile WHERE id=?", agentId).get("agent_code");
        return new ApplyResult(agentId, code, lv, "PENDING");
    }

    /**
     * 「还能不能改资料再交一次」。
     *
     * <p>只有<b>从未通过过审批</b>的 TERMINATED 才行 —— 那是一份被驳回的申请。
     * 真正合作过再被终止的，重新开户是平台的决定，不该由本人在前台自助恢复。
     */
    static boolean canReapply(String status, boolean everApproved) {
        return "TERMINATED".equals(status) && !everApproved;
    }

    /**
     * 停用 / 驳回时后台填的那句理由。
     *
     * <p>它落在 audit_log 的 after_json 里（AdminAgentController 的 reject/suspend 都
     * {@code after.put("reason", …)}），此前<b>没有任何出口</b>：代理端的 gate.tsx 读的是
     * {@code agent.statusNote}，而这个字段全仓从来没有人写过 —— 于是被停用的代理看到的
     * 永远是那句兜底文案，真实理由躺在审计表里，只有 DBA 看得见。
     *
     * <p>不为此加一列：理由本来就是「某个人在某一刻做的决定」，审计表才是它的家。
     * 这里只是把它读出来给当事人看。查不到就返回 null，让前端用兜底文案 ——
     * 绝不编一句「因违规被停用」。
     */
    private String statusNoteOf(long agentId, String status) {
        if (!"SUSPENDED".equals(status) && !"TERMINATED".equals(status)) return null;
        try {
            var rows = jdbc.queryForList(
                    "SELECT JSON_UNQUOTE(JSON_EXTRACT(after_json,'$.reason')) AS reason"
                  + "  FROM audit_log"
                  + " WHERE target_type='agent_profile' AND target_id=?"
                  + "   AND action IN ('AGENT_REJECT','AGENT_SUSPEND')"
                  + " ORDER BY id DESC LIMIT 1", String.valueOf(agentId));
            if (rows.isEmpty()) return null;
            String reason = (String) rows.get(0).get("reason");
            return notBlank(reason) ? reason : null;
        } catch (RuntimeException e) {
            // 读不出理由不该让整个「我的代理档案」挂掉 —— 少一句说明，不是少一个页面
            return null;
        }
    }

    // ================================================================= 申请

    /**
     * 申请成为代理。一级直接申请，二级必须带推荐人的推广码。
     *
     * <p><b>为什么申请要顺手把账号角色改成 AGENT：</b>
     * 库里 trg_agent_parent_must_be_l1_ins 强制"代理档案只能挂在 AGENT 角色的账号上"，
     * 所以档案与角色必须一起落。两件事放在<b>同一个事务</b>里，
     * 是因为如果先改角色再插档案失败，账号就停在一个"角色是代理、却没有代理档案"的
     * 中间态上 —— 这种账号在后台看起来是代理、在业务上什么都不是，
     * 而且没有任何一条报警会提到它。
     *
     * <p>角色变了不需要顶掉登录态：拦截器每次请求都按<b>库里当前的角色</b>判权，
     * 令牌里那个只是签发时的快照。
     *
     * <p><b>经营能力仍然为零</b>：档案落库时是 PENDING，平台审批通过才 ACTIVE。
     */
    public ApplyResult apply(long userId, Integer level, String refCode, String realName,
                             String companyName,
                             String subjectType, String taxNo, String contactName,
                             String settleBankName, String settleBankAcct, String ip) {

        int lv = level == null ? 0 : level;
        if (lv != 1 && lv != 2)
            throw ApiException.badRequest("请选择代理层级：1=一级代理，2=二级代理（体系只有两级）");

        String subject = subjectType == null || subjectType.isBlank()
                ? "INDIVIDUAL" : subjectType.trim().toUpperCase(Locale.ROOT);
        if (!List.of("INDIVIDUAL", "SOLE_PROPRIETOR", "COMPANY").contains(subject))
            throw ApiException.badRequest("主体类型只能是 个人 / 个体工商户 / 企业");

        Long parentId = lv == 2 ? resolveInviter(userId, refCode) : null;
        if (lv == 1 && notBlank(refCode))
            throw ApiException.badRequest(
                    "一级代理不挂在任何人名下。填了推荐人推广码请改为申请二级代理");

        var u = jdbc.queryForList("SELECT role, status, phone FROM sys_user WHERE id=?", userId);
        if (u.isEmpty()) throw ApiException.unauthorized("账号不存在");
        String role = (String) u.get(0).get("role");
        if (!"ACTIVE".equals(u.get(0).get("status")))
            throw ApiException.forbidden("账号不在正常状态，不能申请代理");
        // 平台管理员/导师不能兼任代理：审批人自己就是被审批人时，这道审批就不存在了。
        if (!"STUDENT".equals(role) && !"AGENT".equals(role))
            throw ApiException.forbidden("该账号类型不能申请代理，请另用手机号注册后申请");
        String phone = (String) u.get(0).get("phone");

        return tx.execute(st -> {
            // 角色必须先落，档案插入时触发器会检查它
            jdbc.update("UPDATE sys_user SET role='AGENT' WHERE id=? AND role IN ('STUDENT','AGENT')",
                    userId);

            // ★ 实名。这是 sys_user.real_name 在全仓【唯一】的写入路径。
            //   个人主体的打款户名取的就是它（payeeOf），没有它，自助注册的代理
            //   永远过不了提现那道实名闸，而界面上没有任何地方能补 —— 钱出不去。
            //   COALESCE 保证只在还没有实名时才写：已有实名不许被一次申请覆盖掉
            //   （改实名是风控动作，不该混在自助申请里）。
            if (notBlank(realName))
                jdbc.update("UPDATE sys_user SET real_name = COALESCE(real_name, ?) WHERE id=?",
                        realName.trim(), userId);

            // 推广码撞车走重试，不做"先查再插"：并发下先查再插必然漏。
            // 而 uk_user（一人一档）与 uk_agent_code（码唯一）撞的是同一个异常类型，
            // 所以撞了之后再回查一次档案来区分是哪一个 —— 注意这次回查只用于
            // 【分类】，裁决权始终在唯一键手里。
            for (int attempt = 0; ; attempt++) {
                String code = newAgentCode();
                try {
                    jdbc.update(
                        "INSERT INTO agent_profile (user_id,agent_code,level,parent_agent_id,"
                      + "company_name,subject_type,tax_no,contact_name,settle_bank_name,"
                      + "settle_bank_acct,status) VALUES (?,?,?,?,?,?,?,?,?,?,'PENDING')",
                        userId, code, lv, parentId, trimToNull(companyName), subject,
                        trimToNull(taxNo), trimToNull(contactName), trimToNull(settleBankName),
                        trimToNull(settleBankAcct));

                    long agentId = num(jdbc.queryForObject(
                            "SELECT id FROM agent_profile WHERE agent_code=?", Number.class, code));

                    // 二级的推荐关系单独留痕（G17）。档案里只有 parent_agent_id 这一个结果值，
                    // 事后"这个二级当初是谁推荐的、平台是谁批的"就无从查起，
                    // 而这恰恰是代理之间抢人时唯一能拿出来的证据。
                    if (parentId != null) {
                        jdbc.update("INSERT INTO agent_invite (inviter_agent_id,phone,contact_name,"
                                  + "remark,status,created_agent_id) VALUES (?,?,?,?,'SUBMITTED',?)",
                                parentId, phone, trimToNull(contactName),
                                "代理端自助申请", agentId);
                    }

                    audit(userId, "AGENT_APPLY", "agent_profile", agentId, ip,
                            "JSON_OBJECT('level',?,'parent_agent_id',?,'subject_type',?)",
                            lv, parentId, subject);

                    return new ApplyResult(agentId, code, lv, "PENDING");
                } catch (DuplicateKeyException e) {
                    Agent existing = profileOf(userId);
                    if (existing != null) {
                        // ★ 被驳回的申请可以改完资料再交一次，不是死路。
                        //
                        //   原来这里对任何已有档案一律 409。而后台「驳回」写的是
                        //   status='TERMINATED'，于是被驳回的申请人：Gate 里看到的是
                        //   「你的代理合作已终止」（他从没合作过），点重新申请拿到的是
                        //   「无需重复提交」——两句话都说不通，而且没有任何一条出路。
                        //   代理端 gate.tsx 里那个「修改资料，重新申请」的按钮从设计上就在，
                        //   只是永远走不到；缺的是后端这一侧。
                        var re = reopen(existing.id(), userId, lv, parentId, subject,
                                companyName, taxNo, contactName, settleBankName, settleBankAcct, ip);
                        if (re != null) return re;
                        throw ApiException.conflict("你已经提交过代理申请（当前状态："
                                + statusText(existing.status()) + "），无需重复提交");
                    }
                    if (attempt >= 4) throw e;      // 连撞 5 次说明随机源坏了，不能无限试
                }
            }
        });
    }

    /**
     * 二级申请时解析推荐人。
     *
     * <p>这里的三条拒绝，每一条 schema 都有对应的闸（trg_agent_parent_must_be_l1_ins
     * 与 ck_agent_parent）。<b>不重复实现规则，只是把它们提前到申请这一刻说人话</b>——
     * 让触发器兜底当然拦得住，但用户看到的会是一句"上级代理必须是存在且为一级的代理"，
     * 他既不知道自己填的码是谁的，也不知道该去找谁要一个能用的码。
     */
    private Long resolveInviter(long userId, String refCode) {
        if (!notBlank(refCode))
            throw ApiException.badRequest("二级代理必须填写推荐人的推广码（向推荐你的一级代理索取）");
        var rows = jdbc.queryForList(
                "SELECT id, user_id, level, status FROM agent_profile WHERE agent_code=?",
                refCode.trim());
        if (rows.isEmpty())
            throw ApiException.badRequest("推荐人推广码不存在，请向推荐人核对后重填");
        var p = rows.get(0);
        int plv = ((Number) p.get("level")).intValue();
        if (plv != 1)
            throw ApiException.badRequest(
                    "这张推广码属于一位二级代理，二级不能再发展下线：分销层级只有两级，"
                  + "二级代理必须挂在一级代理名下。请改用一位一级代理的推广码");
        if (!"ACTIVE".equals(p.get("status")))
            throw ApiException.badRequest("推荐人的代理资格尚未生效或已暂停，暂时不能推荐新代理");
        // 显式 longValue()：num() 返回的是 Long，写成 == 会让读的人得停下来想一秒
        // 到底比的是值还是引用（这里确实是值，但不值得让人想）
        if (num(p.get("user_id")).longValue() == userId)
            throw ApiException.badRequest("不能把自己填成自己的推荐人");
        return num(p.get("id"));
    }

    // ================================================================= 激活码

    /**
     * 我的激活码。<b>只出掩码</b>，明文要单独点"查看"并留痕（见 {@link #reveal}）。
     *
     * <p>一级代理在这里<b>看不到</b>下级的码：过滤条件是 owner_agent_id = 我自己，
     * 而下级进的货 owner 是下级。这不是"暂未实现"，是有意的 ——
     * 上级能取下级的码明文，就能拿去自己卖或直接激活，下级的货在自己后台里是不设防的。
     */
    public Page codes(long userId, Long orderId, String status, int page, int size) {
        Agent a = requireOperational(userId);
        int p = checkPage(page), s = checkSize(size);
        String st = normalizeEnum(status, List.of("ISSUED", "BOUND", "EXPIRED", "REVOKED"),
                "激活码状态");

        // 复用 CodeService.list：那里的 WHERE owner_agent_id=? 是同一条越权条件。
        // 自己在这里再写一条 SQL 更快（能把 LIMIT 下推），但那样同一张表的越权条件
        // 就有了两处实现 —— 改漏一处不会有任何信号，测试也照样绿。
        // 代价是分页在内存里切：码量到万级时这是一次全量读，
        // 已在交付说明里请主控给 CodeService 补一个带 limit/offset 的重载。
        List<CodeService.CodeRow> all = codes.list(a.id(), orderId);
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (var c : all) {
            if (st != null && !st.equals(c.status())) continue;
            var m = new LinkedHashMap<String, Object>();
            m.put("id", c.id());
            m.put("masked", c.masked());
            m.put("status", c.status());
            m.put("statusText", codeStatusText(c.status()));
            m.put("activateDeadline", c.deadline());
            // 只给"是否已被激活"，不给激活人的 user_id：
            // 代理要对账"我的哪张码给了谁"，走 /students（那里有掩码手机号 + 对应的掩码码），
            // 这里多出一个别人的主键 id 只会变成一个可以拿去别处试的句柄。
            m.put("bound", c.boundUserId() != null);
            // 「来源」这一列前端一直在读，而后端从来没有返回过 —— 于是它在生产上
            // 恒显示「—」，很久没人发现。手工发放的码必须标成 PLATFORM_GRANT：
            // 它确实有一个真单号，但那笔单从来没有收到过钱，
            // 显示成一个看起来正常的订单号的话，代理会拿它去对账。
            m.put("orderNo", c.orderNo());
            m.put("source", c.source());
            filtered.add(m);
        }
        // (long) 不能省，理由同 commissions()：int 相乘回绕成负数后，
        // subList(负数, ...) 抛 IndexOutOfBounds → 500 而不是一页空结果。
        int from = (int) Math.min((long) (p - 1) * s, filtered.size());
        int to = Math.min(from + s, filtered.size());
        return new Page(filtered.subList(from, to), p, s, filtered.size());
    }

    /**
     * 查看某张码的明文。越权与审计都在 {@link CodeService#reveal} 里，这里只补代理身份。
     *
     * <p>它对"不是你的码"与"码不存在"给出<b>同一句话</b>，这是有意的：
     * 两者给不同的回答，就等于开放了一个"这个 id 存在吗"的问答接口，
     * 顺着枚举一遍就知道平台一共发了多少码、别人的码 id 在哪一段。
     */
    public String reveal(long userId, long codeId, String ip) {
        Agent a = requireOperational(userId);
        return codes.reveal(userId, a.id(), codeId, ip);
    }

    // ================================================================= 学员

    /**
     * 我名下的学员：归属到我的（推广链接带来的）+ 用我的码激活的。
     *
     * <p>两个来源必须都算，且不能只取其一：
     * 只看归属会漏掉"先自己注册、后来买了我的码"的人（他们的 first-touch 归属是别人或空）；
     * 只看码会漏掉"点我链接注册但还没买"的人 —— 而后者正是代理最需要跟进的那批。
     *
     * <p>手机号一律掩码。代理要联系学员走平台工单，不给完整号码：
     * 完整号码一旦落到代理手里，学员就会被拉进私域，平台之后既管不了服务质量，
     * 也管不了"同一个学员被两个代理同时认领"的纠纷。
     */
    public Page students(long userId, int page, int size) {
        Agent a = requireOperational(userId);
        int p = checkPage(page), s = checkSize(size);

        // 用 UNION 的两条子查询而不是一条 OR：OR 会让 sys_user 走全表扫描，
        // 两条子查询各自吃得到索引（idx_attributed_agent / idx_agent_status）。
        String source =
                "SELECT id AS uid FROM sys_user WHERE attributed_agent_id=? "
              + "UNION "
              + "SELECT e.user_id FROM activation_code c "
              + "  JOIN enrollment e ON e.activation_code_id = c.id "
              + " WHERE c.owner_agent_id=?";

        Long total = num(jdbc.queryForObject(
                "SELECT COUNT(*) FROM (" + source + ") t", Number.class, a.id(), a.id()));

        // 显式写出 List<Map<...>>：用 var 的话 T 被推成 LinkedHashMap，构造 Page 时类型对不上
        List<Map<String, Object>> items = jdbc.query(
                "SELECT u.id, u.phone, u.nickname, u.created_at, u.attributed_agent_id,"
              + " u.attribution_source,"
              + " (SELECT COUNT(*) FROM enrollment e2 WHERE e2.user_id=u.id) AS course_count,"
              + " (SELECT c2.code_masked FROM enrollment e3"
              + "    JOIN activation_code c2 ON c2.id = e3.activation_code_id"
              + "   WHERE e3.user_id = u.id AND c2.owner_agent_id = ?"
              + "   ORDER BY e3.id LIMIT 1) AS my_code_masked,"
              + " (SELECT MAX(e4.created_at) FROM enrollment e4 WHERE e4.user_id=u.id) AS last_enrolled_at"
              + " FROM (" + source + ") t JOIN sys_user u ON u.id = t.uid"
              + " ORDER BY u.id DESC LIMIT ? OFFSET ?",
                (rs, i) -> {
                    var m = new LinkedHashMap<String, Object>();
                    m.put("userId", rs.getLong("id"));
                    m.put("phone", AliyunSms.maskPhone(rs.getString("phone")));
                    m.put("nickname", rs.getString("nickname"));
                    m.put("courseCount", rs.getInt("course_count"));
                    m.put("myCodeMasked", rs.getString("my_code_masked"));
                    // 归属在我 = 这个【人】算我的（first-touch，止争依据）；
                    // 用我的码激活 = 这个【座位】是我卖的（结算依据）。两者可以不同，都要看得见。
                    Object att = rs.getObject("attributed_agent_id");
                    m.put("attributedToMe", att != null && ((Number) att).longValue() == a.id());
                    m.put("attributionSource", rs.getString("attribution_source"));
                    m.put("registeredAt", rs.getTimestamp("created_at").toLocalDateTime());
                    var last = rs.getTimestamp("last_enrolled_at");
                    m.put("lastEnrolledAt", last == null ? null : last.toLocalDateTime());
                    return m;
                },
                a.id(), a.id(), a.id(), s, (long) (p - 1) * s);

        return new Page(items, p, s, total == null ? 0 : total);
    }

    // ================================================================= 佣金

    /**
     * 我的佣金流水。<b>只读 commission_record，不在这里算一遍佣金</b>：
     * 佣金在成交那一刻按当时的规则算好并存了快照，展示端再算一次，
     * 改过规则之后两个数字就会对不上，而对不上的那一方永远是"页面上显示的"。
     */
    public Page commissions(long userId, String status, int page, int size) {
        Agent a = requireOperational(userId);
        int p = checkPage(page), s = checkSize(size);
        String st = normalizeEnum(status,
                List.of("PENDING", "SETTLED", "WITHDRAWING", "WITHDRAWN", "REVOKED"), "佣金状态");

        String where = "WHERE c.beneficiary_agent_id=?" + (st == null ? "" : " AND c.status=?");
        Object[] args = st == null ? new Object[]{a.id()} : new Object[]{a.id(), st};

        Long total = num(jdbc.queryForObject(
                "SELECT COUNT(*) FROM commission_record c " + where, Number.class, args));

        Object[] pageArgs = new Object[args.length + 2];
        System.arraycopy(args, 0, pageArgs, 0, args.length);
        pageArgs[args.length] = s;
        // (long) 不能省：page 无上限，int 相乘会回绕成负 OFFSET，MySQL 直接拒 → 500。
        pageArgs[args.length + 1] = (long) (p - 1) * s;

        List<Map<String, Object>> items = jdbc.query(
                "SELECT c.id, c.order_no, c.calc_mode_snapshot, c.calc_basis_fen, c.amount_fen,"
              + " c.reversed_fen,"
              + " c.status, c.freeze_until, c.settled_at, c.created_at, c.revoke_reason,"
              + " src.agent_code AS source_agent_code"
              + " FROM commission_record c"
              + " LEFT JOIN agent_profile src ON src.id = c.source_agent_id "
              + where + " ORDER BY c.id DESC LIMIT ? OFFSET ?",
                (rs, i) -> {
                    var m = new LinkedHashMap<String, Object>();
                    m.put("id", rs.getLong("id"));
                    m.put("orderNo", rs.getString("order_no"));
                    m.put("sourceAgentCode", rs.getString("source_agent_code"));
                    m.put("calcMode", rs.getString("calc_mode_snapshot"));
                    m.put("basisFen", rs.getLong("calc_basis_fen"));
                    m.put("amountFen", rs.getLong("amount_fen"));
                    m.put("reversedFen", rs.getLong("reversed_fen"));
                    m.put("netAmountFen", rs.getLong("amount_fen") - rs.getLong("reversed_fen"));
                    m.put("status", rs.getString("status"));
                    m.put("statusText", commissionStatusText(rs.getString("status")));
                    m.put("freezeUntil", rs.getTimestamp("freeze_until").toLocalDateTime());
                    var settled = rs.getTimestamp("settled_at");
                    m.put("settledAt", settled == null ? null : settled.toLocalDateTime());
                    m.put("revokeReason", rs.getString("revoke_reason"));
                    m.put("createdAt", rs.getTimestamp("created_at").toLocalDateTime());
                    return m;
                }, pageArgs);

        return new Page(items, p, s, total == null ? 0 : total);
    }

    /**
     * 余额：可用 / 冻结 / 在途提现。
     *
     * <p><b>"可提现"这个数字必须与 trg_withdraw_precheck 用的是同一道算式</b>
     * （余额 − 在途提现单），否则就是两本账：页面显示可提 800，点下去被库拒，
     * 用户看到的是"系统有问题"，而两边各自都"对"。
     *
     * <p>可用余额取 agent_balance（台账口径，唯一权威），不由 commission_record 现算：
     * 佣金转可提现要走冻结期与台账入账，现算等于绕过那一步，会把还在冻结期里的钱
     * 也显示成可提。commission_record 的分状态合计另外给，作为"这些钱从哪来"的明细。
     */
    public Map<String, Object> balance(long userId) {
        Agent a = requireOperational(userId);

        var bal = jdbc.queryForList(
                "SELECT commission_balance_fen, total_settled_fen, total_withdrawn_fen "
              + "FROM agent_balance WHERE agent_id=?", a.id());
        boolean opened = !bal.isEmpty();
        long available = opened ? num(bal.get(0).get("commission_balance_fen")) : 0L;
        long totalSettled = opened ? num(bal.get(0).get("total_settled_fen")) : 0L;
        long totalWithdrawn = opened ? num(bal.get(0).get("total_withdrawn_fen")) : 0L;

        long inflight = inflightWithdrawFen(a.id());

        var byStatus = new LinkedHashMap<String, Long>();
        for (var row : jdbc.queryForList(
                "SELECT status, COALESCE(SUM(CASE WHEN status='REVOKED' THEN reversed_fen "
              + "ELSE amount_fen-reversed_fen END),0) AS s FROM commission_record "
              + "WHERE beneficiary_agent_id=? GROUP BY status", a.id()))
            byStatus.put((String) row.get("status"), num(row.get("s")));

        var out = new LinkedHashMap<String, Object>();
        out.put("accountOpened", opened);
        out.put("availableFen", available);
        out.put("inflightWithdrawFen", inflight);
        // 负余额（退款冲正没抵扣完）时这里必须是 0 而不是负数：
        // 前端拿负数去做"最多可提"输入框上限会算出一个负的上限，然后放弃校验。
        out.put("withdrawableFen", Math.max(0, available - inflight));
        out.put("frozenFen", byStatus.getOrDefault("PENDING", 0L));
        out.put("totalSettledFen", totalSettled);
        out.put("totalWithdrawnFen", totalWithdrawn);
        out.put("commissionByStatusFen", byStatus);
        out.put("withdrawMinFen", WithdrawService.MIN_WITHDRAW_FEN);

        // ---- 前端 Balance 要的键名，与上面几个是同值别名 ----------------------
        // 加而不是改名：availableFen / inflightWithdrawFen / withdrawMinFen 这三个
        // 名字可能还有别的调用方，改名是破坏性的，加键是纯增量
        // （与 WithdrawController.list 同时出 rows/items 是同一手法）。
        //
        // 少了这三个键的表现不是"显示成空"，是提现页整页失灵且不报错：
        //   commissionBalanceFen 为 undefined → derived = undefined - undefined = NaN
        //   → mismatch = (NaN !== withdrawableFen) 恒真 → 红色「对不上账」警告框常驻，
        //     天天告诉代理他的钱算错了；
        //   minWithdrawFen 为 undefined → (fen < undefined) 恒假 → 单笔门槛校验静默失效。
        out.put("commissionBalanceFen", available);
        out.put("inflightFen", inflight);
        out.put("minWithdrawFen", WithdrawService.MIN_WITHDRAW_FEN);

        // ---- 收款人 -----------------------------------------------------------
        // 前端用 `noAccount = !b.payeeAccount` 决定提现按钮能不能点。后端不给这个键
        // 就恒为 true —— 一个有 5000 元可提、结算账户也填好了的代理，
        // 提现按钮永远是灰的，旁边常驻一句「还没填结算账户」，而他明明填了。
        //
        // 取值走 payeeOf()，与 WithdrawService.apply 写快照、判「填没填」是同一处：
        // 各写一遍的话，界面放行而申请被拒（或反过来）都不会有任何报错。
        var payee = payeeOf(a.id());
        out.put("payeeName", payee.name());
        out.put("payeeAccount", maskAcct(payee.account()));   // 只回掩码，明文不出网
        out.put("payeeBankName", payee.bankName());

        // ★ 界面那道闸必须与 apply 的闸【同一个判断】，不能拿某个字段去代理它。
        //
        //   apply 的闸是两条：户名要有、账户要有。前端原来只看 payeeAccount 一条，
        //   于是「账户填了、实名没填」的代理会看到一个亮着的提现按钮，点下去必被拒
        //   （400「提现前请先完成实名」），而界面上没有任何地方能补实名。
        //   注意这不是边角：sys_user.real_name 目前<b>全仓没有任何写入路径</b>，
        //   而 subject_type 默认 INDIVIDUAL 取的正是 real_name —— 也就是说
        //   自助注册的代理默认全都卡在这一条上。
        //
        //   payeeReady 恒为布尔（不会被 non_null 删掉），payeeMissing 是该补什么的人话。
        //   前端只认这两个，不再自己拼判据。
        boolean nameOk = notBlank(payee.name());
        boolean acctOk = notBlank(payee.account());
        out.put("payeeReady", nameOk && acctOk);
        out.put("payeeMissing", nameOk && acctOk ? null
                : !nameOk && !acctOk ? "还没登记收款人实名与结算账户"
                : !nameOk ? "还没登记收款人实名（打款户名必须与实名一致，平台不接受代收）"
                : "还没登记结算账户");
        return out;
    }

    /**
     * 收款人：打款户名、结算账号、开户行。
     *
     * <p>户名不是一个列，是一条<b>规则</b>：个人主体打到实名，企业/个体户打到主体名，
     * 主体名空着才回退到实名。提现申请写快照要它、余额接口判「填没填」也要它 ——
     * 两处各实现一遍，等于让「界面说能提」和「服务端说不能提」各自成立。
     */
    record Payee(String name, String account, String bankName) {}

    Payee payeeOf(long agentId) {
        var pr = jdbc.queryForMap(
                "SELECT a.subject_type, a.company_name, a.settle_bank_acct,"
              + " a.settle_bank_name, u.real_name"
              + " FROM agent_profile a JOIN sys_user u ON u.id = a.user_id WHERE a.id=?", agentId);
        String subject = (String) pr.get("subject_type");
        String name = "INDIVIDUAL".equals(subject)
                ? (String) pr.get("real_name")
                : firstNotBlank((String) pr.get("company_name"), (String) pr.get("real_name"));
        return new Payee(name, (String) pr.get("settle_bank_acct"),
                (String) pr.get("settle_bank_name"));
    }

    static String firstNotBlank(String a, String b) {
        return notBlank(a) ? a : b;
    }

    /**
     * 佣金分状态合计。佣金页顶部那条流水线读它。
     *
     * <p>与 {@link #balance} 里的 commissionByStatusFen 同一条 SQL，故意共用 ——
     * 佣金页写「可提现 3200」、提现页写「可提现 2800」这种事，两个数字各自都"对"，
     * 只是来源不同，没有任何报错，对账时最难查。
     */
    public Map<String, Object> commissionSummary(long userId) {
        Agent a = requireOperational(userId);
        var byStatus = new LinkedHashMap<String, Long>();
        for (var row : jdbc.queryForList(
                "SELECT status, COALESCE(SUM(CASE WHEN status='REVOKED' THEN reversed_fen "
              + "ELSE amount_fen-reversed_fen END),0) AS s FROM commission_record "
              + "WHERE beneficiary_agent_id=? GROUP BY status", a.id()))
            byStatus.put((String) row.get("status"), num(row.get("s")));

        var out = new LinkedHashMap<String, Object>();
        // 五个键恒存在且恒为数字：application.yml 的 non_null 会把 null 键整个删掉，
        // 而前端 Money fen={undefined} 渲染成「—」，看起来像"这一档没有钱"，
        // 与"这一档确实是 0"在屏幕上分不出来。
        out.put("pendingFen", byStatus.getOrDefault("PENDING", 0L));
        out.put("settledFen", byStatus.getOrDefault("SETTLED", 0L));
        out.put("withdrawingFen", byStatus.getOrDefault("WITHDRAWING", 0L));
        out.put("withdrawnFen", byStatus.getOrDefault("WITHDRAWN", 0L));
        out.put("revokedFen", byStatus.getOrDefault("REVOKED", 0L));

        // ★ settledFen 是【累计曾经结算过】，不是【现在能提多少】，两者会差得很远。
        //
        //   原因是这套状态机在生产里只走到 SETTLED 就停了：全仓没有任何一处把
        //   commission_record 标成 WITHDRAWING（申请侧不写 withdraw_order_id，
        //   WithdrawService 自己的注释也写明了这一点），所以打款那条
        //   `UPDATE ... WHERE status='WITHDRAWING'` 恒影响 0 行 ——
        //   withdrawingFen / withdrawnFen 永远是 0，而 settledFen 只增不减。
        //
        //   结果：代理提完 5000 之后，佣金页会写「可提现 5,000」，
        //   而提现页写「现在可以申请 0」。两个数字各自都"对"，没有任何报错，
        //   对账时最难查。所以这里把【权威的那个】一并给出来，
        //   并明说这两个数分别是什么意思 —— 前端不许再拿 settledFen 当"可提"。
        // ★ 必须 queryForList，不能 queryForObject。
        //
        //   agent_balance 那一行是【首次结算时】才建的，而佣金记录在下级付款那一刻就有了。
        //   也就是说「有佣金、还没有余额行」是每个新代理都会经历的正常阶段，
        //   queryForObject 在这一段直接抛 EmptyResultDataAccessException → 整页 500。
        //   表现是：佣金页对没赚到钱的代理正常，对<b>刚赚到第一笔钱</b>的代理崩掉。
        //   上面 balance() 用的就是 queryForList + isEmpty()，正是为了躲这个坑。
        var bal = jdbc.queryForList(
                "SELECT commission_balance_fen FROM agent_balance WHERE agent_id=?", a.id());
        long available = bal.isEmpty() ? 0L : num(bal.get(0).get("commission_balance_fen"));
        out.put("withdrawableFen", Math.max(0, available - inflightWithdrawFen(a.id())));
        out.put("settledIsCumulative", true);
        return out;
    }

    /** 在途提现：与 trg_withdraw_precheck 完全同一组状态。改这里必须同时改那里。 */
    /**
     * 在途提现合计。<b>口径必须与 {@code trg_withdraw_precheck} 里那条 SUM 逐字一致</b>：
     * 这里算出来的是界面上「现在可以申请多少」，那里算出来的是「让不让过闸」，
     * 两边差一个状态，就会出现「页面写着可提 800、点下去被库拒绝」，而两边各自都"对"。
     *
     * <h3>★ FAILED 也占额度</h3>
     * 打款失败的单子<b>没有</b>把钱付出去，也<b>没有</b>扣余额（扣减只发生在 markPaid），
     * 但它并未终结：{@code trg_withdraw_state} 明文允许 FAILED → APPROVED → PAYING → PAID
     * （后台「修正收款信息后重新打款」走的就是这条）。
     *
     * <p>此前 FAILED 不在这个集合里，于是同一笔佣金余额可以被提<b>两次</b>：
     * 代理端在打款失败时写着「钱还在你的余额里，可以重新申请」，他照做建了第二张单
     * （闸门看不见那张 FAILED，放行）；平台这边把第一张 FAILED 重新审批通过并打款，
     * 再把第二张也打了 —— 两笔都真的出账，{@code agent_balance.commission_balance_fen}
     * 被扣成负数（那一列的注释就写着「可为负」，没有任何约束会拦）。
     * 全程零报错：每一步单看都合法。
     *
     * <p>所以 FAILED 保持占用，直到平台把它推向终点：重新打款成功（PAID），
     * 或者驳回（RISK_REJECTED，额度当场释放，代理再重新申请）。
     */
    long inflightWithdrawFen(long agentId) {
        Long v = num(jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount_fen),0) FROM withdraw_order WHERE agent_id=? "
              + "AND status IN ('APPLIED','REVIEWING','APPROVED','PAYING','FAILED')",
                Number.class, agentId));
        return v == null ? 0L : v;
    }

    // ================================================================= 统计

    /**
     * 我的统计：进货数、已激活数、未激活库存、成交额。
     *
     * <p>三个口径同时出（采购 / 动销 / 库存），因为只看采购会把"压了一堆货卖不动"
     * 的渠道显示成健康渠道 —— 真正反映渠道健康的是动销。
     *
     * <p>库存那一栏把 EXPIRED / REVOKED 单列出来，不并进"未激活"：
     * 并进去的话 已激活 + 未激活 ≠ 进货数，代理会当成系统算错来投诉，
     * 而实际上那些码是过期或被作废的，钱和货都已经有归属了。
     *
     * @param month 可选 YYYY-MM。按支付时间归月（与 agent_monthly_stat 的口径一致）
     */
    public Map<String, Object> stats(long userId, String month) {
        Agent a = requireOperational(userId);
        String[] range = monthRange(month);        // null = 不限时间

        var out = new LinkedHashMap<String, Object>();
        out.put("month", month == null ? "ALL" : month);
        out.put("level", a.level());

        // ---- 码：进货 / 动销 / 库存 ----
        String codeWhere = "WHERE owner_agent_id=?"
                + (range == null ? "" : " AND created_at >= ? AND created_at < ?");
        var codeRow = jdbc.queryForMap(
                "SELECT COUNT(*) AS total,"
              + " COALESCE(SUM(status='BOUND'),0)   AS bound,"
              + " COALESCE(SUM(status='ISSUED'),0)  AS issued,"
              + " COALESCE(SUM(status='EXPIRED'),0) AS expired,"
              + " COALESCE(SUM(status='REVOKED'),0) AS revoked"
              + " FROM activation_code " + codeWhere, argsWithRange(a.id(), range));
        out.put("purchasedCodes", num(codeRow.get("total")));
        out.put("activatedCodes", num(codeRow.get("bound")));
        out.put("unactivatedStock", num(codeRow.get("issued")));
        out.put("expiredCodes", num(codeRow.get("expired")));
        out.put("revokedCodes", num(codeRow.get("revoked")));

        // 平台手工发放的那部分，另起一条独立语句。
        //
        // ★ 绝不给上面那条 SQL 加 JOIN biz_order：它的 WHERE 是拼出来的裸 created_at、
        // SELECT 列表是裸 SUM(status='BOUND')，两个列名在 activation_code 与 biz_order 里
        // 都存在 —— 加 JOIN 当场 ERROR 1052 ambiguous，代理端「我的统计」对每个代理都 500。
        //
        // ★ 也绝不把 purchasedCodes 收窄成「只数非白送」：上面那五个键之间有一条恒等式
        // （已激活 + 未激活 + 过期 + 作废 = 进货数），收窄一个而不收窄其余就当场破了它，
        // 两个数字各自都对，而代理会当成系统算错来投诉。granted 是<b>额外</b>的一维，
        // 不是从 purchased 里切出去的一块。
        String grantWhere = "WHERE c.owner_agent_id=? AND o.origin='ADMIN_GIFT'"
                + (range == null ? "" : " AND c.created_at >= ? AND c.created_at < ?");
        out.put("grantedCodes", num(jdbc.queryForObject(
                "SELECT COUNT(*) FROM activation_code c JOIN biz_order o ON o.id=c.order_id "
              + grantWhere, Long.class, argsWithRange(a.id(), range))));

        // ---- 成交额：实付 − 已退，全部取订单快照，不按现价复算 ----
        String orderWhere = "WHERE buyer_agent_id=? AND paid_at IS NOT NULL"
                + (range == null ? "" : " AND paid_at >= ? AND paid_at < ?");
        var ordRow = jdbc.queryForMap(
                "SELECT COUNT(*) AS cnt,"
              + " COALESCE(SUM(paid_amount_fen),0)     AS paid,"
              + " COALESCE(SUM(refunded_amount_fen),0) AS refunded"
              + " FROM biz_order " + orderWhere, argsWithRange(a.id(), range));
        long paid = num(ordRow.get("paid")), refunded = num(ordRow.get("refunded"));
        out.put("paidOrders", num(ordRow.get("cnt")));
        // 同 AgentDashboardService：进货金额不出网。除以码数就是单价。
        // 变量 paid 仍算着 —— 它是下面几项的中间量，删了会连带改口径。
        // refunded / net 两个也一起收：净额就是进货价，退款额在只有一两张单时同样反推得出。
        // 退了多少钱由平台在线下退款时告知（付款本来也是线下对公转账）。
        // 变量 refunded 仍算着 —— 它是下面几项的中间量。

        // ---- 归属我的学员直购（§7.5，这批单我没进货，但结算算我的） ----
        String directWhere = "WHERE attributed_agent_id=? AND paid_at IS NOT NULL"
                + (range == null ? "" : " AND paid_at >= ? AND paid_at < ?");
        var dirRow = jdbc.queryForMap(
                "SELECT COUNT(*) AS cnt,"
              + " COALESCE(SUM(paid_amount_fen - refunded_amount_fen),0) AS net"
              + " FROM biz_order " + directWhere, argsWithRange(a.id(), range));
        out.put("directPurchaseOrders", num(dirRow.get("cnt")));
        out.put("directPurchaseAmountFen", num(dirRow.get("net")));

        // ---- 佣金：读记录，不重算 ----
        String commWhere = "WHERE beneficiary_agent_id=? AND status <> 'REVOKED'"
                + (range == null ? "" : " AND created_at >= ? AND created_at < ?");
        Long comm = num(jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount_fen-reversed_fen),0) FROM commission_record " + commWhere,
                Number.class, argsWithRange(a.id(), range)));
        out.put("commissionEarnedFen", comm == null ? 0L : comm);

        // ---- 下级汇总：只给合计，不给明细（schema 里就是这么写的：防挖客） ----
        if (a.level() == 1) {
            String subWhere = "WHERE s.parent_agent_id=? AND o.paid_at IS NOT NULL"
                    + (range == null ? "" : " AND o.paid_at >= ? AND o.paid_at < ?");
            // 汇总口径从【金额】换成【码数】。
            //
            // 进货价一家一议之后，下级的进货金额是他和平台单独谈下来的价 ——
            // 原样端给上级看，等于把下级的议价结果公开。而上级要的其实是
            // 「我的渠道活不活」这个信号，码数一样答得了，且不含单价。
            // 只删不换的话，一级会突然失去看渠道业绩的能力，而没有任何提示。
            var sub = jdbc.queryForMap(
                    "SELECT COUNT(DISTINCT o.buyer_agent_id) AS agents,"
                  + " COALESCE(SUM(o.code_qty_snapshot),0) AS codes"
                  + " FROM biz_order o JOIN agent_profile s ON s.id = o.buyer_agent_id "
                  + subWhere, argsWithRange(a.id(), range));
            out.put("subAgentActiveCount", num(sub.get("agents")));
            out.put("subAgentPurchasedCodes", num(sub.get("codes")));
        }
        return out;
    }

    // ================================================================= 工具

    /** 审计留痕。after_json 用 MySQL 的 JSON_OBJECT 拼，不自己拼字符串 —— 手拼迟早会被引号带崩。 */
    void audit(long operatorId, String action, String targetType, long targetId, String ip,
               String jsonExpr, Object... jsonArgs) {
        Object[] args = new Object[4 + jsonArgs.length + 1];
        args[0] = operatorId; args[1] = action; args[2] = targetType;
        args[3] = String.valueOf(targetId);
        System.arraycopy(jsonArgs, 0, args, 4, jsonArgs.length);
        args[args.length - 1] = ip;
        jdbc.update("INSERT INTO audit_log (operator_id,action,target_type,target_id,after_json,client_ip)"
                  + " VALUES (?,?,?,?," + jsonExpr + ",?)", args);
    }

    /** 推广码：随机、不可预测。顺序码等于把"平台一共有几个代理"和别人的码一起送出去。 */
    private String newAgentCode() {
        StringBuilder sb = new StringBuilder("AG");
        for (int i = 0; i < CODE_BODY; i++)
            sb.append(ActivationCodes.ALPHABET.charAt(rnd.nextInt(ActivationCodes.ALPHABET.length())));
        return sb.toString();
    }

    static int checkPage(int page) {
        if (page < 1) throw ApiException.badRequest("页码从 1 开始");
        return page;
    }

    static int checkSize(int size) {
        if (size < 1) throw ApiException.badRequest("每页条数至少 1");
        return Math.min(size, MAX_PAGE_SIZE);
    }

    /**
     * 枚举值过滤参数。传了库里不存在的值时<b>报错而不是查空</b>：
     * 查空的话前端把 status 拼错（"Issued"）会看到一个空列表，
     * 而空列表和"确实没有"长得一模一样，这种 bug 能在线上活很久。
     */
    static String normalizeEnum(String v, List<String> allowed, String what) {
        if (v == null || v.isBlank()) return null;
        String s = v.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(s))
            throw ApiException.badRequest("未知的" + what + "：" + v);
        return s;
    }

    /** YYYY-MM → [月初, 下月初)。用半开区间而不是 BETWEEN：月末 23:59:59.999 的单会被 BETWEEN 漏掉。 */
    static String[] monthRange(String month) {
        if (month == null || month.isBlank()) return null;
        String m = month.trim();
        if (!m.matches("^[0-9]{4}-(0[1-9]|1[0-2])$"))
            throw ApiException.badRequest("月份格式应为 YYYY-MM");
        var from = LocalDateTime.parse(m + "-01T00:00:00");
        return new String[]{from.toString(), from.plusMonths(1).toString()};
    }

    private static Object[] argsWithRange(long agentId, String[] range) {
        return range == null ? new Object[]{agentId}
                             : new Object[]{agentId, range[0], range[1]};
    }

    static String statusText(String s) {
        return switch (s) {
            case "PENDING" -> "审核中";
            case "ACTIVE" -> "已生效";
            case "SUSPENDED" -> "已暂停";
            case "TERMINATED" -> "已终止";
            default -> s;
        };
    }

    private static String codeStatusText(String s) {
        return switch (s) {
            case "ISSUED" -> "未激活";
            case "BOUND" -> "已激活";
            case "EXPIRED" -> "已过激活期限";
            case "REVOKED" -> "已作废";
            default -> s;
        };
    }

    private static String commissionStatusText(String s) {
        return switch (s) {
            case "PENDING" -> "冻结中";
            case "SETTLED" -> "可提现";
            case "WITHDRAWING" -> "提现中";
            case "WITHDRAWN" -> "已提现";
            case "REVOKED" -> "已撤销（订单退款）";
            default -> s;
        };
    }

    /** 卡号只留后 4 位。前面的位数用 * 补，不暴露原长度也不影响用户认出是哪张卡。 */
    static String maskAcct(String acct) {
        if (acct == null || acct.isBlank()) return null;
        String a = acct.trim();
        if (a.length() <= 4) return "****";
        return "****" + a.substring(a.length() - 4);
    }

    static boolean notBlank(String s) { return s != null && !s.isBlank(); }

    static String trimToNull(String s) { return notBlank(s) ? s.trim() : null; }

    /**
     * BIGINT UNSIGNED 在 Connector/J 里映射成 <b>BigInteger</b> 而不是 Long，
     * 直接 (Long) 强转会在运行期抛 ClassCastException。本库每个主键都是无符号，
     * 所以取 id 一律走这里。SUM() 又是 BigDecimal —— 同样被这个方法接住。
     */
    static Long num(Object v) { return v == null ? null : ((Number) v).longValue(); }
}
