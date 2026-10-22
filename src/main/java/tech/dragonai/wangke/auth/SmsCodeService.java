package tech.dragonai.wangke.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tech.dragonai.wangke.config.WangkeProps;
import tech.dragonai.wangke.web.ApiException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;

/**
 * 验证码的签发与核销。
 *
 * <p>这是账号体系里唯一一处「凭一条短信就能拿到账号」的入口，三种攻击都要挡：
 *
 * <ul>
 *   <li><b>暴力猜码</b> —— 6 位只有 100 万种。撞满 {@code max-attempts} 即<b>整条码作废</b>，
 *       不是"这次不算"：只把本次判错而留着码继续猜，等于没有上限。
 *   <li><b>短信轰炸</b> —— 冷却 / 同号日限 / 全局日限三层。少一层就有绕法：
 *       只有冷却挡不住换号刷，只有日限挡不住一分钟内连点。
 *   <li><b>回放</b> —— 用过即销，且<b>靠更新行数裁决</b>而不是"先查再改"。
 * </ul>
 *
 * <p>码存 SHA-256(码+盐)，不落明文。库被读走时，明文码就是一把把可用的账号钥匙。
 */
@Service
public class SmsCodeService {

    public enum Scene { REGISTER, LOGIN, RESET, BIND }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final AliyunSms sms;
    private final WangkeProps.Sms cfg;
    private final byte[] salt;
    private final SecureRandom rnd = new SecureRandom();

    public SmsCodeService(JdbcTemplate jdbc, TransactionTemplate tx, AliyunSms sms, WangkeProps props) {
        this.jdbc = jdbc; this.tx = tx; this.sms = sms;
        this.cfg = props.getSms();
        this.salt = props.getSecrets().getCodeHashSalt().getBytes(StandardCharsets.UTF_8);
    }

    /** 发送验证码。返回距离下次可发的秒数（前端用来置灰按钮）。 */
    public int send(String phone, Scene scene, String clientIp) {
        requirePhone(phone);

        Integer sinceLast = jdbc.queryForObject(
                "SELECT COALESCE(TIMESTAMPDIFF(SECOND, MAX(created_at), NOW(3)), 999999) "
              + "FROM sms_code WHERE phone=? AND scene=?", Integer.class, phone, scene.name());
        if (sinceLast != null && sinceLast < cfg.getCooldownSeconds())
            throw new ApiException(429, "请求过于频繁，请 "
                    + (cfg.getCooldownSeconds() - sinceLast) + " 秒后再试");

        Integer today = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sms_code WHERE phone=? AND created_at >= CURDATE()",
                Integer.class, phone);
        if (today != null && today >= cfg.getPerPhoneDaily())
            throw new ApiException(429, "该手机号今日验证码次数已达上限");

        // 全局水位用 INSERT ... ON DUPLICATE KEY UPDATE 原子递增，
        // 不用"先查再加" —— 并发下先查再加必然超发，而超发的是真金白银的短信费。
        int global = jdbc.update(
                "INSERT INTO sms_daily_quota (stat_date, sent_count) VALUES (CURDATE(), 1) "
              + "ON DUPLICATE KEY UPDATE sent_count = IF(sent_count < ?, sent_count + 1, sent_count)",
                cfg.getGlobalDaily());
        Integer sent = jdbc.queryForObject(
                "SELECT sent_count FROM sms_daily_quota WHERE stat_date=CURDATE()", Integer.class);
        if (sent != null && sent >= cfg.getGlobalDaily() && global == 0)
            throw new ApiException(429, "今日短信发送量已达平台上限，请稍后再试");

        String code = String.format("%06d", rnd.nextInt(1_000_000));
        String bizId;
        try {
            bizId = sms.send(phone, templateOf(scene), java.util.Map.of("code", code));
        } catch (AliyunSms.SmsException e) {
            // 发失败就把刚占的全局水位还回去，否则网关抖一次就白扣一次配额
            jdbc.update("UPDATE sms_daily_quota SET sent_count = GREATEST(sent_count - 1, 0) "
                      + "WHERE stat_date = CURDATE()");
            throw new ApiException(502, "验证码发送失败：" + e.getMessage());
        }

        jdbc.update("INSERT INTO sms_code (phone,scene,code_hash,expires_at,client_ip,provider_biz_id) "
                  + "VALUES (?,?,?,DATE_ADD(NOW(3), INTERVAL ? SECOND),?,?)",
                phone, scene.name(), hash(code), cfg.getCodeTtlSeconds(), clientIp, bizId);
        return cfg.getCooldownSeconds();
    }

    /**
     * 核销验证码。成功返回，失败抛 {@link ApiException}。
     *
     * <p>只认<b>最近一条未消费</b>的码。允许旧码继续有效的话，
     * 攻击者可以让受害者不断重发、自己拿着一堆并行有效的码慢慢试。
     */
    public void verify(String phone, Scene scene, String code) {
        requirePhone(phone);
        if (code == null || !code.matches("\\d{6}"))
            throw new ApiException(400, "验证码格式不对");

        tx.executeWithoutResult(st -> {
            var rows = jdbc.queryForList(
                    "SELECT id, code_hash, attempts, expires_at FROM sms_code "
                  + "WHERE phone=? AND scene=? AND consumed_at IS NULL "
                  + "ORDER BY id DESC LIMIT 1 FOR UPDATE", phone, scene.name());
            if (rows.isEmpty()) throw new ApiException(400, "请先获取验证码");
            var r = rows.get(0);
            long id = ((Number) r.get("id")).longValue();
            int attempts = ((Number) r.get("attempts")).intValue();
            LocalDateTime exp = (LocalDateTime) r.get("expires_at");

            if (exp.isBefore(LocalDateTime.now())) throw new ApiException(400, "验证码已过期，请重新获取");
            if (attempts >= cfg.getMaxAttempts())
                throw new ApiException(429, "验证码错误次数过多，请重新获取");

            if (!MessageDigest.isEqual(hash(code).getBytes(StandardCharsets.US_ASCII),
                                       ((String) r.get("code_hash")).getBytes(StandardCharsets.US_ASCII))) {
                jdbc.update("UPDATE sms_code SET attempts = attempts + 1 WHERE id=?", id);
                int left = cfg.getMaxAttempts() - attempts - 1;
                throw new ApiException(400, left > 0
                        ? "验证码不正确，还可尝试 " + left + " 次"
                        : "验证码错误次数过多，请重新获取");
            }

            // 消费靠更新行数裁决：并发下只有一个连接能把 consumed_at 从 NULL 改掉
            int n = jdbc.update(
                    "UPDATE sms_code SET consumed_at = NOW(3) WHERE id=? AND consumed_at IS NULL", id);
            if (n != 1) throw new ApiException(400, "验证码已被使用，请重新获取");
        });
    }

    private String templateOf(Scene s) {
        return switch (s) {
            case LOGIN -> cfg.getTemplateLogin();
            case REGISTER -> cfg.getTemplateRegister();
            case RESET -> cfg.getTemplateReset();
            case BIND -> cfg.getTemplateBind();
        };
    }

    static void requirePhone(String phone) {
        if (phone == null || !phone.matches("1[3-9]\\d{9}"))
            throw new ApiException(400, "手机号格式不正确");
    }

    private String hash(String code) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt);
            byte[] d = md.digest(code.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                               .append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
