package tech.dragonai.wangke.exam;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tech.dragonai.wangke.web.ApiException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 结业证明：签发、查看、公开验真、吊销。
 *
 * <h3>措辞红线</h3>
 * 对外一律叫「课程结业证明」，不得出现「认证」「资格」（G14）。
 * 这不是文案偏好：后两个词在教育培训语境下有明确的行政含义，
 * 用了就等于宣称一份我们并不具备的资质，是合规问题不是措辞问题。
 *
 * <h3>签发依据只有一条</h3>
 * exam_attempt.passed。trg_certificate_precheck 已经在库层强制了
 * "必须是这个人这门课的一次已通过考试、且考的那份卷是发证的卷"，这里不重复实现，只负责：
 * 挑出是哪一次、给出人话、以及触发器管不到的幂等与吊销留痕。
 *
 * <h3>不是每份卷都发证（2026-09-03）</h3>
 * 一门课下面挂着一份综合卷和四份 50 题的章节练兵卷。发证判据是
 * {@code exam_paper.grants_certificate=1}，<b>库层与应用层各写一遍是有意的</b>：
 * 库层那条（trg_certificate_precheck）是兜底，任何路径都绕不过；应用层这条负责
 * 在学员点"申请结业证明"时给一句人话，而不是把一句触发器文案抛到他脸上。
 * 两边判的是同一件事，所以改的时候必须一起改。<b>少了库层那条</b>，
 * 考过一份 50 题的章节卷就能换出结业证明 —— 而结业证明是对外物料。
 *
 * <h3>验真接口是公开的，所以它只回答一个问题</h3>
 * 「这个编号对应的结业证明是不是真的、还有效吗」。
 * 它<b>不</b>回答"这是谁的"。给出完整姓名等于让任何拿到编号的人反查到学员身份，
 * 而编号会印在证书上、发在朋友圈、贴进简历 —— 泄露面比想象中大得多。
 */
@Service
public class CertificateService {

    private static final Logger log = LoggerFactory.getLogger(CertificateService.class);

    /** cert_no 随机撞车时的重试上限。撞一次的概率约 2^-55 量级，撞满 5 次意味着别的地方错了。 */
    private static final int NO_RETRY = 5;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public CertificateService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc; this.json = json;
    }

    public record Issued(boolean firstTime, String certNo, long courseId, long attemptId,
                         LocalDateTime issuedAt) {}

    public record MyCert(String certNo, long courseId, String courseTitle,
                         LocalDateTime issuedAt, LocalDateTime revokedAt, String status) {}

    /** 公开验真的返回。字段是刻意选的，见类注释。 */
    public record Verified(String certNo, String status, String courseTitle,
                           String holderMasked, LocalDateTime issuedAt, LocalDateTime revokedAt) {}

    public record Revoked(boolean firstTime, String certNo, LocalDateTime revokedAt) {}

    // ================================================================= 签发

    /**
     * 申请结业证明。
     *
     * <p><b>幂等靠唯一键裁决</b>：直接 INSERT，撞了 uk_user_course 再回查，
     * 而不是"先查有没有、没有就插"。后者在学员连点两下时会双双查到"没有"，
     * 然后一个插成功、另一个撞唯一键抛出一句技术味的 409。
     *
     * <p>这里有个容易写错的地方：certificate 上有<b>两个</b>唯一键
     * （uk_user_course 与 uk_cert_no），撞哪个抛出来都是同一个
     * {@link DuplicateKeyException}。所以捕获后必须<b>回查</b>才能判断走哪条路 ——
     * 撞的是 uk_user_course 就返回既有证书（幂等），撞的是 uk_cert_no
     * 说明随机号重了，换一个号重试。把两者混为一谈的写法有两种失败形状：
     * 要么把随机撞号当成"你已经有证书了"（学员永远拿不到证），
     * 要么把重复申请当成撞号，重试 5 次后抛系统错误。
     */
    public Issued issue(long userId, long courseId) {
        // 用分数最高的那一次做签发依据（同分取最早）。取"最近一次"的写法有个坑：
        // 学员通过之后又考了一次没过，最近一次就是不及格的那场，
        // 于是他明明过了却申请不到证书。
        //
        // JOIN exam_paper 且 grants_certificate=1：只有综合卷算数。不 JOIN 的话，
        // 考过任意一份 50 题的章节练兵卷都会被当成"通过了本课程测评"。
        // INNER JOIN 顺带把 paper_id 为空的历史场次排除在外 —— 那些场次说不清考的是哪份卷，
        // 而"说不清"在发证这条链上只能算不通过。
        var passed = jdbc.queryForList(
                "SELECT a.id FROM exam_attempt a JOIN exam_paper p ON p.id = a.paper_id "
              // grants_certificate 判 <> 0 而不是 = 1：它是 TINYINT(1)，写 2 进来库层完全合法。
              // 与 trg_certificate_precheck 逐字同一条判据 —— 两边判法不同的那天，
              // 应用说"你没通过"而触发器说"可以发"，谁也看不出是哪一边错了。
              + "WHERE a.user_id=? AND a.course_id=? AND a.passed=1 AND p.grants_certificate<>0 "
              + "ORDER BY a.score DESC, a.submitted_at ASC LIMIT 1", userId, courseId);
        if (passed.isEmpty())
            // 措辞要点出"章节卷不发证"：学员刚考完一份 50 题的卷、拿了满分，
            // 只说一句"尚未通过该课程测评"他会以为是系统没记上他的成绩。
            throw ApiException.conflict("尚未通过该课程的结业测评，不能申请结业证明"
                    + "（章节练习卷不发结业证明）");
        long attemptId = num(passed.get(0).get("id"));

        for (int i = 0; i < NO_RETRY; i++) {
            String certNo = CertNo.generate();
            try {
                jdbc.update("INSERT INTO certificate (cert_no,user_id,course_id,attempt_id) "
                          + "VALUES (?,?,?,?)", certNo, userId, courseId, attemptId);
                var row = jdbc.queryForMap(
                        "SELECT issued_at FROM certificate WHERE cert_no=?", certNo);
                return new Issued(true, certNo, courseId, attemptId,
                        (LocalDateTime) row.get("issued_at"));
            } catch (DuplicateKeyException e) {
                var mine = jdbc.queryForList(
                        "SELECT cert_no, attempt_id, issued_at, revoked_at FROM certificate "
                      + "WHERE user_id=? AND course_id=?", userId, courseId);
                if (mine.isEmpty()) {
                    log.warn("结业证明编号随机撞车，重试第 {} 次", i + 1);
                    continue;                       // 撞的是 uk_cert_no，换个号
                }
                var c = mine.get(0);
                // 已吊销的不能自助重开：uk_user_course 决定一人一课只有一行，
                // 重开就必须先动那一行，而"吊销之后又自己变回有效"这件事
                // 一旦能自助完成，吊销就形同虚设。走人工，且必然留审计。
                if (c.get("revoked_at") != null)
                    throw ApiException.conflict(
                            "你的该课程结业证明已被平台吊销，不能自助重新申请，请联系平台");
                return new Issued(false, (String) c.get("cert_no"), courseId,
                        num(c.get("attempt_id")), (LocalDateTime) c.get("issued_at"));
            }
        }
        // 走到这里说明随机源出了问题（比如被换成了固定种子），必须当场炸而不是继续发证：
        // 可预测的编号等于把伪造成本降到零。
        throw new IllegalStateException("连续 " + NO_RETRY + " 次生成的结业证明编号都已存在，"
                + "随机源可能异常，拒绝继续签发");
    }

    /** 我的证书。user_id 写在 WHERE 里，不做"查出来再比对"。 */
    public List<MyCert> my(long userId) {
        return jdbc.query(
                "SELECT c.cert_no, c.course_id, co.title, c.issued_at, c.revoked_at "
              + "FROM certificate c JOIN course co ON co.id = c.course_id "
              + "WHERE c.user_id=? ORDER BY c.id DESC",
                (rs, i) -> {
                    LocalDateTime revoked = rs.getObject("revoked_at", LocalDateTime.class);
                    return new MyCert(rs.getString("cert_no"), rs.getLong("course_id"),
                            rs.getString("title"),
                            rs.getObject("issued_at", LocalDateTime.class), revoked,
                            revoked == null ? "VALID" : "REVOKED");
                }, userId);
    }

    // ================================================================= 验真

    /**
     * 公开验真。<b>无需登录</b>（路径在 /api/public/ 白名单下）。
     *
     * <p>已吊销的证书必须<b>如实返回 REVOKED 而不是当作不存在</b>：
     * 验真的全部价值就在于能分清"这张是伪造的"与"这张曾经真实但已被撤销"。
     * 把吊销的藏起来，用人（HR、合作方）看到的是"查无此证"，
     * 于是他会以为是自己输错了，再抄一遍、打电话来问 —— 而这张证确实存在过。
     */
    public Verified verify(String rawNo) {
        String certNo;
        try {
            certNo = CertNo.normalize(rawNo);
        } catch (IllegalArgumentException e) {
            // 格式错与查无此证要分开：编号格式是公开的（就印在证书上），
            // 说清楚"你少抄了一位"不泄露任何东西，却省掉一通客服电话。
            throw ApiException.badRequest(e.getMessage());
        }

        var rows = jdbc.queryForList(
                "SELECT c.cert_no, c.issued_at, c.revoked_at, co.title, u.real_name, u.nickname "
              + "FROM certificate c JOIN course co ON co.id = c.course_id "
              + "JOIN sys_user u ON u.id = c.user_id WHERE c.cert_no=?", certNo);
        if (rows.isEmpty())
            throw ApiException.notFound("未查到该编号对应的结业证明，请核对编号");
        var c = rows.get(0);

        LocalDateTime revoked = (LocalDateTime) c.get("revoked_at");
        return new Verified(certNo, revoked == null ? "VALID" : "REVOKED",
                (String) c.get("title"),
                maskName((String) c.get("real_name"), (String) c.get("nickname")),
                (LocalDateTime) c.get("issued_at"), revoked);
    }

    /**
     * 持有人脱敏：张三 → 张*，欧阳修 → 欧**。
     *
     * <p>留首字不留全名，是"能对得上"与"不可反查"之间的取舍：
     * 拿着证书来核验的人本来就知道姓名，首字足够确认这张证是不是他的；
     * 而只拿到一个编号的人推不出是谁。
     *
     * <p><b>任何情况下都不出手机号</b>，掩码的也不行 —— 138****8888 配合
     * 一个已知的证书编号，足以确认"某人在这个平台上学过某门课"。
     */
    static String maskName(String realName, String nickname) {
        String n = realName != null && !realName.isBlank() ? realName
                : (nickname != null && !nickname.isBlank() ? nickname : null);
        if (n == null) return "学员";
        n = n.trim();
        // 按码点数而不是 char 数：姓名里出现罕用字（增补平面）时，
        // 按 char 截会把一个字劈成两半，输出一个乱码方框。
        int len = n.codePointCount(0, n.length());
        if (len <= 1) return n;
        int second = n.offsetByCodePoints(0, 1);
        return n.substring(0, second) + "*".repeat(len - 1);
    }

    // ================================================================= 吊销

    /**
     * 吊销（管理员）。
     *
     * <p>条件更新（{@code AND revoked_at IS NULL}）本身就是幂等的裁决：
     * 影响 0 行意味着"不存在"或"已经吊销过了"，两者回查一次即可分开。
     * 重复点击返回成功而<b>不再写一条审计</b> —— 审计记的是发生过的变更，
     * 记一条没发生的变更，日后复核时会把"点了两次按钮"读成"吊销了两次"。
     *
     * <p>{@code revoked_at} 取 GREATEST(NOW(3), issued_at) 是为了 ck_cert_revoke
     * （revoked_at >= issued_at）。正常情况下 NOW 一定更大，但发证与吊销跨了
     * 数据库主从切换或时钟回拨时可能不是 —— 那时撞上的是一句 CHECK 违约，
     * 经全局处理器变成"系统繁忙"，管理员只会看到吊销莫名其妙失败了。
     *
     * @param reason 必填。没有理由的吊销事后无法复核，而吊销是对学员不利的动作。
     */
    public Revoked revoke(long operatorId, String rawNo, String reason, String ip) {
        if (reason == null || reason.isBlank())
            throw ApiException.badRequest("吊销必须填写理由：这是对学员不利的动作，事后要能复核");
        String certNo;
        try {
            certNo = CertNo.normalize(rawNo);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }

        int n = jdbc.update("UPDATE certificate SET revoked_at=GREATEST(NOW(3), issued_at) "
                          + "WHERE cert_no=? AND revoked_at IS NULL", certNo);
        var rows = jdbc.queryForList(
                "SELECT id, revoked_at FROM certificate WHERE cert_no=?", certNo);
        if (rows.isEmpty()) throw ApiException.notFound("未查到该编号对应的结业证明");
        LocalDateTime revokedAt = (LocalDateTime) rows.get(0).get("revoked_at");
        if (n != 1) return new Revoked(false, certNo, revokedAt);

        // before_json 是手写的字面量，不是 jsonOf(单键 map)。原因：全局 ObjectMapper 配了
        // default-property-inclusion: non_null（application.yml），序列化 Map 时会把
        // 值为 null 的键整条丢掉 —— 于是"吊销前 revoked_at 为空"这条本次审计
        // 唯一要记的事实会变成一个空对象 {}，而且不报错。
        jdbc.update("INSERT INTO audit_log (operator_id,action,target_type,target_id,"
                  + "before_json,after_json,client_ip) VALUES (?,'CERT_REVOKE','certificate',?,?,?,?)",
                operatorId, certNo, "{\"revoked_at\":null}",
                jsonOf(Map.of("revoked_at", String.valueOf(revokedAt), "reason", reason)), ip);
        return new Revoked(true, certNo, revokedAt);
    }

    /** before_json / after_json 是 JSON 列：塞非法 JSON 进去 MySQL 会当场拒绝整条审计写入。 */
    private String jsonOf(Map<String, ?> m) {
        try {
            return json.writeValueAsString(m);
        } catch (Exception e) {
            throw new IllegalStateException("审计内容无法序列化", e);
        }
    }

    /** BIGINT UNSIGNED 在 Connector/J 里是 BigInteger，不是 Long，直接强转会 ClassCastException。 */
    private static long num(Object v) { return ((Number) v).longValue(); }
}
