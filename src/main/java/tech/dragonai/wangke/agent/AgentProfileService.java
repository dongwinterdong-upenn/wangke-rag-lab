package tech.dragonai.wangke.agent;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tech.dragonai.wangke.web.ApiException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 代理自助改联系人与结算信息（{@code PUT /api/agent/profile}）。
 *
 * <p>这一页真正重要的只有<b>结算账户</b>：填错了钱打不出去，
 * 而"打不出去"要等到打款那一刻才知道 —— 那时提现单已经走完了申请、风控、审核三步。
 *
 * <h3>字段语义写死在这里，因为它决定了"清空"到底生不生效</h3>
 * <b>字段缺失或为 null = 不改这一列；给了空字符串 = 把这一列清成 NULL。</b>
 * 前端 Me.tsx 每次把五个字段（trim 过）全发，所以这条规则就是"用户把主体名称删空并保存"
 * 的实际行为。反过来定义（空串也当成不改）的话，用户会发现自己删不掉填错的公司名，
 * 而界面上没有任何提示。
 *
 * <h3>四种状态都允许改，不是 requireOperational</h3>
 * <ul>
 *   <li><b>PENDING</b> 必须能在审批前改掉填错的卡号，否则唯一的出路是"被驳回再重填"；
 *   <li><b>SUSPENDED / TERMINATED</b> 仍可能有已过冻结期的佣金要提现，
 *       收款人填不对钱就出不去 —— Gate 的文案已经这么向用户承诺过了。
 * </ul>
 * 没有档案 → 404，与 {@code /me} 同一句话。
 *
 * <h3>列名是写死的白名单，绝不用请求体的 key 去拼</h3>
 * 拿请求体的 key 拼列名等于把列名交给客户端，下一步就是
 * {@code status='ACTIVE'} 或 {@code level=1} 被人从这个接口写进去。
 */
@Service
public class AgentProfileService {

    /** 五个可改列的长度上限，逐个照 schema 抄。校验必须在应用层做完 —— 理由见 {@link #updateProfile}。 */
    private static final int MAX_CONTACT = 64, MAX_COMPANY = 128, MAX_TAX = 32,
                             MAX_BANK_NAME = 128, MAX_BANK_ACCT = 64;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final AgentService agents;

    public AgentProfileService(JdbcTemplate jdbc, TransactionTemplate tx, AgentService agents) {
        this.jdbc = jdbc; this.tx = tx; this.agents = agents;
    }

    /** 请求体。五个字段全可选，语义见类注释：null=不改，""=清空。 */
    public record UpdateReq(String contactName, String companyName, String taxNo,
                            String settleBankName, String settleBankAcct) {}

    /**
     * 保存资料。
     *
     * <p><b>长度校验必须在这里做完，不能靠 MySQL。</b>超长时 MySQL 抛 1406，
     * 到了 {@code GlobalExceptionHandler} 会变成一句 500「系统繁忙」——
     * 用户看到的是"系统坏了"，而不是"名字太长了"，于是他会重试、会投诉，
     * 就是不会去改那个字段。
     *
     * <p><b>一个字段都没变时不发 UPDATE</b>：空 UPDATE 会白白推进 updated_at，
     * 并在审计表里留下一条什么都没改的记录 —— 日后查"这张卡号是什么时候被换掉的"，
     * 要在一堆没有内容的记录里翻。
     *
     * <p>两个标签页同时保存 = 字段级最后写入者赢，<b>不加乐观锁</b>：
     * 这五个字段之间没有跨字段不变式（它们不是钱），加版本号只会制造
     * "保存失败请刷新"这种用户无法自解的错误。两次改动在 audit_log 里都留痕。
     *
     * @return {@code updated} 是本次真正改动的字段名（驼峰）。它存在的意义是让
     *         "点了保存但什么都没改"和"保存成功"在响应里就能分开
     */
    public Map<String, Object> updateProfile(long userId, UpdateReq r, String ip) {
        // 只要求档案存在，不看状态。理由见类注释。
        AgentService.Agent a = agents.profileOf(userId);
        if (a == null) throw ApiException.notFound("你还不是代理商，请先提交代理申请");

        String contactName   = check(r.contactName(),     MAX_CONTACT,   "联系人姓名");
        String companyName   = check(r.companyName(),     MAX_COMPANY,   "主体名称");
        String taxNo         = check(r.taxNo(),           MAX_TAX,       "税号");
        String bankName      = check(r.settleBankName(),  MAX_BANK_NAME, "开户行");
        String bankAcct      = check(r.settleBankAcct(),  MAX_BANK_ACCT, "银行账号");

        if (bankAcct != null && !bankAcct.isEmpty()) {
            // 去掉空格再判：银行回单上的账号常带四位一组的空格，用户照抄过来是常态。
            bankAcct = bankAcct.replace(" ", "");
            if (!bankAcct.matches("^[0-9]{8,32}$"))
                throw ApiException.badRequest(
                        "银行账号只能是 8–32 位数字。带字母或符号的账号会在打款那一刻被银行退回，"
                      + "而那时这笔钱已经走过一遍申请与审核");
        }
        if (taxNo != null && !taxNo.isEmpty() && taxNo.length() != 15 && taxNo.length() != 18)
            // 只判长度，不算校验位：统一社会信用代码的校验位规则各地实现不一，
            // 卡死会误伤真实主体，而这一列只是开票时抄给财务的。
            throw ApiException.badRequest("税号应为 15 位（老税号）或 18 位（统一社会信用代码）");

        // 取旧值算 diff。这次回查【只】用于算 diff 与写审计，不承担并发裁决 ——
        // 两个标签页同时保存时，谁的 UPDATE 后到谁赢，这正是想要的语义。
        var old = jdbc.queryForMap(
                "SELECT contact_name, company_name, tax_no, settle_bank_name, settle_bank_acct"
              + " FROM agent_profile WHERE user_id=?", userId);

        var cols = new ArrayList<String>();
        var vals = new ArrayList<Object>();
        var changed = new ArrayList<String>();
        plan("contact_name",      "contactName",     contactName, old, cols, vals, changed);
        plan("company_name",      "companyName",     companyName, old, cols, vals, changed);
        plan("tax_no",            "taxNo",           taxNo,       old, cols, vals, changed);
        plan("settle_bank_name",  "settleBankName",  bankName,    old, cols, vals, changed);
        plan("settle_bank_acct",  "settleBankAcct",  bankAcct,    old, cols, vals, changed);

        var out = new LinkedHashMap<String, Object>();
        if (cols.isEmpty()) {
            out.put("updated", List.of());
            out.put("message", "没有需要保存的改动");
            return out;
        }

        boolean acctChanged = changed.contains("settleBankAcct");
        String acctBefore = AgentService.maskAcct((String) old.get("settle_bank_acct"));
        String acctAfter = AgentService.maskAcct(bankAcct);

        StringBuilder sql = new StringBuilder("UPDATE agent_profile SET ");
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append(cols.get(i)).append("=?");
        }
        sql.append(" WHERE user_id=?");
        vals.add(userId);

        // UPDATE 与审计 INSERT 必须一起成或一起败：留下一条"改了卡号"的审计而实际没改，
        // 或者改了却没有痕迹，两种都会让日后的打款纠纷查不清。
        // 用 TransactionTemplate 而不是 @Transactional：注解靠代理生效，
        // 测试里 new 出来的对象根本不会开事务，于是这条保证在测试里是假的、生产里是真的。
        tx.executeWithoutResult(st -> {
            jdbc.update(sql.toString(), vals.toArray());
            // 审计表是给平台侧看的。完整卡号只该存在 agent_profile 这一列里 ——
            // 复制进第二张表等于把同一份 PII 多存一处，泄漏面翻倍而收益为零：
            // 掩码 + 一个"变没变"的布尔已经足够回答"这张卡是什么时候被换掉的"。
            agents.audit(userId, "AGENT_PROFILE_UPDATE", "agent_profile", a.id(), ip,
                    "JSON_OBJECT('fields',?,'settleAcctChanged',?,'settleAcctBefore',?,'settleAcctAfter',?)",
                    String.join(",", changed), acctChanged, acctBefore, acctAfter);
        });

        out.put("updated", changed);
        // 结算账户改动要单独说一句：前端确认框已经这么向用户承诺过，
        // 而这句承诺的正确性由 AgentBizIT 里那条用例钉住，不靠读代码相信。
        out.put("message", acctChanged
                ? "已保存。已经在审核或打款流程里的提现单仍按申请时的收款账户执行，不受本次修改影响"
                : "已保存");
        return out;
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 把一个字段的意图翻译成"要不要写这一列、写什么值"。
     *
     * @param v null = 请求里没给这个字段，本列保持不动；
     *          ""  = 用户显式清空，写 NULL；
     *          其它 = 写这个值
     */
    private static void plan(String col, String camel, String v, Map<String, Object> old,
                             List<String> cols, List<Object> vals, List<String> changed) {
        if (v == null) return;                       // 没给 = 不改
        String next = v.isEmpty() ? null : v;        // 空串 = 清空
        String prev = (String) old.get(col);
        // 与现值相同就不进 SET：五个字段全没变时整条 UPDATE 都不发。
        if (java.util.Objects.equals(prev, next)) return;
        cols.add(col);
        vals.add(next);
        changed.add(camel);
    }

    /**
     * 长度校验 + 修剪。
     *
     * @return null 表示"这个字段没给"；空串表示"要清空"；其余是修剪后的值
     */
    private static String check(String v, int max, String what) {
        if (v == null) return null;
        String s = v.trim();
        if (s.isEmpty()) return "";
        if (s.length() > max)
            throw ApiException.badRequest(what + "最多 " + max + " 个字，当前 " + s.length() + " 个");
        return s;
    }
}
