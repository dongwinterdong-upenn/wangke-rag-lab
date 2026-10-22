package tech.dragonai.wangke.agent;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tech.dragonai.wangke.auth.AuthInterceptor;
import tech.dragonai.wangke.web.ClientIp;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 申请成为代理。
 *
 * <p>这条路径<b>不在</b> AuthInterceptor 的白名单里，所以必须先登录 ——
 * 代理档案要挂在一个已实名手机号的账号上，匿名提交的申请既联系不到人，
 * 也没法在审批通过时把角色落到某个账号上。
 *
 * <p>不做 requireRole 校验：申请人此刻通常还是 STUDENT，
 * 能不能申请由 {@link AgentService#apply} 按账号状态与角色判定。
 */
@RestController
public class AgentApplyController {

    private final AgentService agents;

    public AgentApplyController(AgentService agents) { this.agents = agents; }

    /**
     * @param level  1=一级 2=二级
     * @param refCode 推荐人的推广码（agent_code）。二级必填，一级必须不填
     */
    /**
     * ★ {@code parentAgentCode} 与 {@code refCode} 是同一个东西的两个名字，两个都收。
     *
     * <p>前端发的一直是 {@code parentAgentCode}（Apply.tsx），而这里原来只收
     * {@code refCode} —— Spring Boot 默认 {@code FAIL_ON_UNKNOWN_PROPERTIES=false}，
     * 那个键被 Jackson <b>静默丢弃</b>，于是二级代理申请恒定 400
     * 「二级代理必须填写推荐人的推广码」，报的正是他刚填好的那一栏。
     * <b>自助路径下二级代理一个都建不出来</b>，而佣金的唯一来源是下级进货，
     * 所以整条佣金链在自助路径上从源头就没通。
     *
     * <p>{@code realName} 同理被丢了，后果更远：{@code sys_user.real_name} 全仓
     * 没有别的写入路径，而个人主体的打款户名取的正是它 —— 于是自助注册的代理
     * 永远过不了提现那道实名闸，界面上也没有任何地方能补。
     *
     * <p>两个名字都留：改名是破坏性的（可能有存量调用方发 refCode），加一个是纯增量。
     */
    public record ApplyReq(Integer level, String refCode, String parentAgentCode,
                           String realName, String companyName, String subjectType,
                           String taxNo, String contactName,
                           String settleBankName, String settleBankAcct) {

        /** 前端那个名字优先；两个都没填就是 null（一级代理的正常情况）。 */
        String inviterCode() {
            return AgentService.notBlank(parentAgentCode) ? parentAgentCode : refCode;
        }
    }

    @PostMapping("/api/agent/apply")
    Map<String, Object> apply(@RequestBody ApplyReq r, HttpServletRequest req) {
        var res = agents.apply(AuthInterceptor.userId(req), r.level(), r.inviterCode(),
                r.realName(), r.companyName(), r.subjectType(), r.taxNo(), r.contactName(),
                r.settleBankName(), r.settleBankAcct(), ClientIp.of(req));

        var out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        out.put("agentId", res.agentId());
        out.put("agentCode", res.agentCode());
        out.put("level", res.level());
        out.put("status", res.status());
        // 明说"还不能经营"。不说的话，申请成功的下一秒去点进货、点提现，
        // 拿到的是一串 403，用户会以为是系统坏了而不是在等审批。
        out.put("message", "申请已提交，等待平台审核。审核通过后才能进货、查看激活码与提现");
        return out;
    }
}
