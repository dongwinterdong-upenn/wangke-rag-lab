package tech.dragonai.wangke.learn;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tech.dragonai.wangke.trade.ActivationCodes;
import tech.dragonai.wangke.trade.CodeVault;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 学员激活：一串码 → 一份课程权益 + 一次归属落定。
 *
 * <p>这是整个平台唯一一处"用户拿着凭据来换东西"的入口，它同时是三条规则的落点：
 *
 * <ul>
 *   <li><b>一码一人，激活即不退</b>（录音 01:50）—— 码一旦绑定就不可改绑、不可退回，
 *       库里由 trg_activation_code_state 强制，这里只负责给出人话的提示。
 *   <li><b>first-touch 归属</b>（§7.5）—— 学员第一次被谁带进来就归谁，之后不再改。
 *   <li><b>协议留证</b>（G6）—— 激活时勾选的协议版本与时间必须落库，
 *       否则事后就"用户当时有没有同意不退费"这件事，平台一句话都说不出来。
 * </ul>
 *
 * <h3>失败原因是接口的一部分</h3>
 * 每种失败都带 {@link Reason}，不是笼统一句"激活失败"。两个理由：
 * 客服要能一眼看出是"码被别人用了"还是"你已经有这门课了"；
 * 测试要能<b>钉住失败原因</b> —— 只断言"抛异常"的负例，很容易是因为别的原因红的，
 * 那种测试通过时什么也没证明。
 */
public class ActivationService {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final CodeVault vault;

    public ActivationService(JdbcTemplate jdbc, TransactionTemplate tx, CodeVault vault) {
        this.jdbc = jdbc; this.tx = tx; this.vault = vault;
    }

    public enum Reason {
        /** 输入根本不可能是一个码（位数不对、含字母表外字符），连库都不用查 */
        MALFORMED,
        /** 格式合法但库里没有 */
        NOT_FOUND,
        /** 已被别人激活 —— 一码一人 */
        ALREADY_BOUND_BY_OTHER,
        /** 已被平台作废 */
        REVOKED,
        /** 超过激活期限（G5） */
        EXPIRED,
        /** 这个学员已经有这门课的权益了；此码未被消耗，可转给他人 */
        ALREADY_ENROLLED,
        /** 账号被冻结/停用 */
        USER_NOT_ACTIVE,
        /** 没带协议版本 */
        AGREEMENT_REQUIRED
    }

    public static class RedeemException extends RuntimeException {
        private final Reason reason;
        public RedeemException(Reason reason, String msg) { super(msg); this.reason = reason; }
        public Reason reason() { return reason; }
    }

    /**
     * @param firstTime          false = 这个码这个人已经激活过，本次是重复提交（幂等返回，不是错误）
     * @param attributionJustSet true = 本次激活把学员的归属代理第一次落定了
     */
    public record Result(boolean firstTime, long enrollmentId, long courseId,
                         LocalDateTime validUntil, Long attributedAgentId,
                         boolean attributionJustSet) {}

    /**
     * 兑换激活码。可安全重复提交：同一个人对同一个码再点一次，返回上次的结果而不是报错
     * （学员在弱网下重复点"激活"是常态，报错会让他以为码坏了）。
     *
     * @param agreementVersion 学员勾选的服务协议版本，必须由前端如实带上（G6 留证）
     */
    public Result redeem(long userId, String input, String agreementVersion, String ip) {
        if (agreementVersion == null || agreementVersion.isBlank())
            throw new RedeemException(Reason.AGREEMENT_REQUIRED,
                    "未带服务协议版本：激活即不退是要靠这条留证的，不能省");

        String code;
        try {
            code = ActivationCodes.normalize(input);
        } catch (IllegalArgumentException e) {
            throw new RedeemException(Reason.MALFORMED, e.getMessage());
        }
        String hash = vault.hash(code);

        return tx.execute(st -> doRedeem(userId, hash, agreementVersion, ip));
    }

    private Result doRedeem(long userId, String hash, String agreementVersion, String ip) {
        // 行锁在这里是必要的，不是保险：两个人拿同一串码同时点激活，
        // 「先查 status 再改」会双双查到 ISSUED，然后一个改绑另一个 —— 一码两人。
        // 加锁的顺序全流程统一为 激活码 → 学员 → 权益，避免与其它路径成环。
        var rows = jdbc.queryForList(
                "SELECT id, course_id, owner_agent_id, order_id, status, activate_deadline,"
              + " bound_user_id FROM activation_code WHERE code_hash=? FOR UPDATE", hash);
        if (rows.isEmpty())
            throw new RedeemException(Reason.NOT_FOUND, "激活码不存在，请核对后重新输入");
        Map<String, Object> c = rows.get(0);

        long codeId = num(c.get("id"));
        long courseId = num(c.get("course_id"));
        Object ownerObj = c.get("owner_agent_id");
        Long ownerAgentId = ownerObj == null ? null : num(ownerObj);
        String status = (String) c.get("status");
        Long boundUser = c.get("bound_user_id") == null ? null : num(c.get("bound_user_id"));

        // 幂等：同一个人重复提交同一个码，返回既有权益，而不是「已被激活」
        if ("BOUND".equals(status) && boundUser != null && boundUser == userId) {
            var e = jdbc.queryForMap(
                    "SELECT id, valid_until, attributed_agent_id FROM enrollment WHERE activation_code_id=?",
                    codeId);
            return new Result(false, num(e.get("id")), courseId,
                    (LocalDateTime) e.get("valid_until"),
                    e.get("attributed_agent_id") == null ? null : num(e.get("attributed_agent_id")),
                    false);
        }
        if ("BOUND".equals(status))
            throw new RedeemException(Reason.ALREADY_BOUND_BY_OTHER,
                    "该激活码已被其他账号激活。一码一人，不可转移");
        if ("REVOKED".equals(status))
            throw new RedeemException(Reason.REVOKED, "该激活码已被平台作废，请联系售出方");
        if ("EXPIRED".equals(status))
            throw new RedeemException(Reason.EXPIRED, "该激活码已过激活期限");

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime deadline = (LocalDateTime) c.get("activate_deadline");
        if (deadline != null && now.isAfter(deadline))
            throw new RedeemException(Reason.EXPIRED,
                    "该激活码已于 " + deadline.toLocalDate() + " 超过激活期限（G5）");

        var u = jdbc.queryForList(
                "SELECT status, attributed_agent_id FROM sys_user WHERE id=?", userId);
        if (u.isEmpty() || !"ACTIVE".equals(u.get(0).get("status")))
            throw new RedeemException(Reason.USER_NOT_ACTIVE, "账号不在正常状态，无法激活课程");
        Long userAttributed = u.get(0).get("attributed_agent_id") == null
                ? null : num(u.get(0).get("attributed_agent_id"));

        // 已有同课权益就不许再激活 —— 关键是【此码不得被消耗】。
        // 靠 uk_user_course 兜底也能挡住，但那时码已经在同一事务里被改成 BOUND 了，
        // 依赖回滚来"没消耗"是对的，可提示会变成一句数据库唯一键报错。
        Integer dup = jdbc.queryForObject(
                "SELECT COUNT(*) FROM enrollment WHERE user_id=? AND course_id=?",
                Integer.class, userId, courseId);
        if (dup != null && dup > 0)
            throw new RedeemException(Reason.ALREADY_ENROLLED,
                    "你已拥有该课程的权益，无需重复激活；此码未被消耗，可转给他人使用");

        Integer accessMonths = jdbc.queryForObject(
                "SELECT access_months FROM course WHERE id=?", Integer.class, courseId);
        if (accessMonths == null) throw new IllegalStateException("课程不存在：" + courseId);
        LocalDateTime validUntil = now.plusMonths(accessMonths);

        // WHERE 里带 status='ISSUED' 只是第二道闸，而且它是【安静】的：抢输的连接
        // 影响 0 行、不报错，然后若无其事地继续往下开权益。
        // 实测（把上面的 FOR UPDATE 去掉再跑并发用例）：12 个连接里有 9 个走到了这一步，
        // 全靠 trg_enrollment_precheck 那条「权益必须开给激活该码的学员本人」才没出事 ——
        // 也就是说没有行锁的话，应用层是一路放行的，兜底的是数据库。
        // 这里把 0 行显式变成业务错误，别再让它安静。
        int bound = jdbc.update("UPDATE activation_code SET status='BOUND', bound_user_id=?, bound_at=? "
                  + "WHERE id=? AND status='ISSUED'", userId, now, codeId);
        if (bound != 1)
            throw new RedeemException(Reason.ALREADY_BOUND_BY_OTHER,
                    "该激活码已被其他账号激活。一码一人，不可转移");

        // 归属有两笔，落在两个地方，含义不同，不要合并：
        //   enrollment.attributed_agent_id —— 这个【座位】是谁卖出去的，跟码走
        //   sys_user.attributed_agent_id   —— 这个【人】是谁带进来的，first-touch，永不改
        // 学员先点了 A 的推广链接注册，后来从 B 手里买了码，两者就会不同，
        // 而且都必须留痕：前者是这笔权益的结算依据，后者是止争的依据。
        Long enrollAttribution = ownerAgentId != null ? ownerAgentId : userAttributed;

        boolean attributionJustSet = false;
        if (ownerAgentId != null && userAttributed == null) {
            // WHERE 里带 attributed_agent_id IS NULL：两个不同代理的码被并发激活时，
            // 后到的那条影响 0 行，安静地保持 first-touch，而不是撞上触发器报错。
            // 代理拿自己的码给自己开课时，触发器会拒绝自归属（自购返佣），所以这里先排掉。
            int n = jdbc.update(
                    "UPDATE sys_user SET attributed_agent_id=?, attributed_at=?, "
                  + "attribution_source='ACTIVATION_CODE' "
                  + "WHERE id=? AND attributed_agent_id IS NULL "
                  + "  AND ? NOT IN (SELECT id FROM agent_profile WHERE user_id=?)",
                    ownerAgentId, now, userId, ownerAgentId, userId);
            attributionJustSet = n == 1;
        }

        long enrollmentId;
        try {
            jdbc.update(
                "INSERT INTO enrollment (user_id,course_id,source,activation_code_id,order_id,"
              + "attributed_agent_id,valid_from,valid_until,agreement_version,agreed_at,agreed_ip) "
              + "VALUES (?,?,'ACTIVATION_CODE',?,?,?,?,?,?,?,?)",
                userId, courseId, codeId, num(c.get("order_id")), enrollAttribution,
                now, validUntil, agreementVersion, now, ip);
            enrollmentId = num(jdbc.queryForObject(
                    "SELECT id FROM enrollment WHERE activation_code_id=?", Number.class, codeId));
        } catch (DuplicateKeyException e) {
            // 同一个人同时激活两串同课程的码：上面的计数检查两边都会通过，
            // 唯一键才是真正的裁决点。整个事务回滚，这一串码没被消耗。
            throw new RedeemException(Reason.ALREADY_ENROLLED,
                    "你已拥有该课程的权益（并发提交）；此码未被消耗，可转给他人使用");
        }

        return new Result(true, enrollmentId, courseId, validUntil,
                enrollAttribution, attributionJustSet);
    }

    /** BIGINT UNSIGNED 在 Connector/J 里是 BigInteger，不是 Long，直接强转会炸。 */
    private static long num(Object v) { return ((Number) v).longValue(); }
}
