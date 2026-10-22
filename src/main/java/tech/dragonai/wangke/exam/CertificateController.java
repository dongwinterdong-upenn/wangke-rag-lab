package tech.dragonai.wangke.exam;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tech.dragonai.wangke.auth.AuthInterceptor;
import tech.dragonai.wangke.web.ApiException;
import tech.dragonai.wangke.web.ClientIp;

import java.util.Map;

/**
 * 结业证明接口。
 *
 * <p><b>没有类级 @RequestMapping 是有意的</b>：公开验真必须落在 {@code /api/public/} 下，
 * 因为 AuthInterceptor 的白名单是按<b>路径前缀</b>放行的。
 * 把它写成 {@code /api/cert/verify/...} 再去改白名单，等于为了一个接口
 * 把整条鉴权规则撕开一个口子，而白名单每多一条，"哪些接口是裸的"就更难回答一次。
 *
 * <p>对外措辞一律「课程结业证明」，不得出现「认证」「资格」（G14）。
 */
@RestController
public class CertificateController {

    private final CertificateService cert;

    public CertificateController(CertificateService cert) { this.cert = cert; }

    public record IssueReq(Long courseId) {}
    public record RevokeReq(String certNo, String reason) {}

    /** 申请结业证明。只认 exam_attempt.passed；重复申请返回已有的那张，不报错。 */
    @PostMapping("/api/cert/issue")
    Map<String, Object> issue(@RequestBody(required = false) IssueReq r, HttpServletRequest req) {
        if (r == null || r.courseId() == null) throw ApiException.badRequest("缺少 courseId");
        var c = cert.issue(AuthInterceptor.userId(req), r.courseId());
        return Ok.of("firstTime", c.firstTime(), "certNo", c.certNo(),
                "courseId", c.courseId(), "attemptId", c.attemptId(), "issuedAt", c.issuedAt());
    }

    /** 我的结业证明。 */
    @GetMapping("/api/cert/my")
    Map<String, Object> my(HttpServletRequest req) {
        return Ok.of("certificates", cert.my(AuthInterceptor.userId(req)));
    }

    /**
     * 吊销（仅平台管理员），写审计。
     *
     * <p>角色以<b>库里当前的</b>为准（AuthInterceptor 每次请求都重查），
     * 不是令牌里那个快照 —— 否则降权之后旧令牌还能吊销证书。
     */
    @PostMapping("/api/cert/revoke")
    Map<String, Object> revoke(@RequestBody(required = false) RevokeReq r, HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        if (r == null || r.certNo() == null) throw ApiException.badRequest("缺少 certNo");
        var v = cert.revoke(AuthInterceptor.userId(req), r.certNo(), r.reason(), ClientIp.of(req));
        return Ok.of("firstTime", v.firstTime(), "certNo", v.certNo(), "revokedAt", v.revokedAt());
    }

    /**
     * 公开验真。无需登录。
     *
     * <p>已吊销的如实返回 REVOKED，不当作不存在 —— 验真的价值就在于分得清
     * "伪造的"和"真发过但已撤销的"。持有人姓名脱敏，手机号任何形式都不出网。
     *
     * <p><b>已知缺口</b>：这是个公开接口，目前没有限流。编号有 55 bit 随机，
     * 枚举不可行，但暴力刷仍会打库。限流应当由网关统一做，不在这里各写一遍。
     */
    @GetMapping("/api/public/cert/verify/{no}")
    Map<String, Object> verify(@PathVariable("no") String no) {
        var v = cert.verify(no);
        return Ok.of("certNo", v.certNo(), "status", v.status(),
                "courseTitle", v.courseTitle(), "holder", v.holderMasked(),
                "issuedAt", v.issuedAt(), "revokedAt", v.revokedAt());
    }
}
