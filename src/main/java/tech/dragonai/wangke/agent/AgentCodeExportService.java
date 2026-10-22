package tech.dragonai.wangke.agent;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tech.dragonai.wangke.trade.CodeService;
import tech.dragonai.wangke.trade.CodeVault;
import tech.dragonai.wangke.web.ApiException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 批量取激活码明文导 CSV（{@code POST /api/agent/codes/export}）。
 *
 * <p>这是代理最高频的动作，<b>也是明文出网面最大的一处</b>：一次响应体里可能有上千串
 * 可以直接拿去激活的码。所以两条纪律与单条 {@link CodeService#reveal} 完全一致，
 * 一个字都不许分叉：
 *
 * <ol>
 *   <li><b>越权条件写在 WHERE 里</b>（{@code owner_agent_id = 我的 agent id}），
 *       不做"查出来再比对 owner"。一级代理在这里看不到也导不到下级的码 ——
 *       上级能取下级的码明文，就能拿去自己卖或直接激活，下级的货在自己后台里就是不设防的。
 *   <li><b>审计 action 一律 {@code 'CODE_REVEAL'}</b>，与单条查看同名。
 *       给批量导出起个新名字（比如 CODE_EXPORT）看起来更清楚，实际后果是
 *       "这串码被谁看过"要查两个 action，而第二个迟早有人忘了查 ——
 *       纠纷发生时，漏掉的那一半就是平台拿不出来的那一半。
 * </ol>
 *
 * <p>本类<b>本该</b>是 {@code CodeService.revealBatch}，与单条 reveal 共处一室
 * （越权 WHERE 与审计 action 分居两个文件就一定会分叉）。本轮因文件归属限制
 * 没有改 trade 包，已作为偏差上报。合并时整体搬过去即可，接口形状不变。
 *
 * <p>也<b>不</b>用"循环调用单条 reveal"来实现：那是 N 次 SELECT + N 次 INSERT，
 * 2000 个码就是 4000 次往返。但省往返不能以另写一条越权 WHERE 为代价 ——
 * 所以这里只写<b>一条</b> WHERE，列表与导出共用它。
 */
@Service
public class AgentCodeExportService {

    /**
     * 单次导出上限。<b>命中超过它就 400，绝不静默截断</b>：
     * 截断的表现是代理以为剩下的码已经发过了，而那些码还躺在库里没发出去，
     * 学员那边则是"买了课但没收到码"。2000 是个待运营拍板的值（见交付说明）。
     */
    static final int MAX_EXPORT = 2000;

    /** 一次最多按 id 勾选多少条。前端全选一页也就 100，1000 已经很宽松了。 */
    static final int MAX_IDS = 1000;

    private static final List<String> CODE_STATUS = List.of("ISSUED", "BOUND", "EXPIRED", "REVOKED");

    /** 与单条 reveal 同一句提示。两处措辞不同的话，用户会以为是两种不同的操作。 */
    static final String NOTICE = "该次查看已记入审计日志。请勿在群内转发完整激活码";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final AgentService agents;
    private final CodeVault vault;

    public AgentCodeExportService(JdbcTemplate jdbc, TransactionTemplate tx,
                                  AgentService agents, CodeVault vault) {
        this.jdbc = jdbc; this.tx = tx; this.agents = agents; this.vault = vault;
    }

    /** 请求体。三个字段都可选；ids 给了就只按 ids 走。 */
    public record ExportReq(String status, String keyword, List<Long> ids) {}

    /**
     * 批量取明文。
     *
     * <p><b>默认只导 ISSUED。</b>已激活/已作废的码明文发出去，收到的人照做一遍必然失败，
     * 而失败那一刻他面对的不是平台、是发码给他的这个代理。显式传其它状态可以导
     * （码本来就是他自己的），但每一条同样留痕。
     *
     * <p><b>解密与审计在同一个事务里落库之后才返回响应。</b>
     * 先回响应再补审计的话，进程崩在中间就是一次<b>无痕的明文泄漏</b> ——
     * 码已经在对方屏幕上了，而平台这边一条记录都没有。
     *
     * @param req    status 是逗号分隔的状态集合（默认 ISSUED）；keyword 在掩码与订单号上模糊匹配；
     *               ids 非空时忽略前两者
     * @param ip     客户端 IP，落进每一条审计
     */
    public Map<String, Object> export(long userId, ExportReq req, String ip) {
        AgentService.Agent a = agents.requireOperational(userId);
        ExportReq r = req == null ? new ExportReq(null, null, null) : req;

        List<Long> ids = r.ids() == null ? List.of()
                : r.ids().stream().filter(java.util.Objects::nonNull).toList();
        if (ids.size() > MAX_IDS)
            throw ApiException.badRequest("一次最多按勾选导出 " + MAX_IDS + " 个码，本次勾了 "
                    + ids.size() + " 个，请分批");

        StringBuilder where = new StringBuilder(" WHERE c.owner_agent_id=?");
        var args = new ArrayList<Object>();
        args.add(a.id());
        var notes = new LinkedHashMap<String, String>();

        if (!ids.isEmpty()) {
            // 勾选优先，且【忽略】status/keyword：否则会出现"我勾了 20 个，只导出 3 个"
            // 而界面上没有任何解释。不属于我的 id 静默跳过（WHERE 里那条 owner 条件负责），
            // 不报"这个码不是你的"—— 那是一个码 id 的存在性探针。
            where.append(" AND c.id IN (")
                 .append("?,".repeat(ids.size() - 1)).append("?)");
            args.addAll(ids);
            if (AgentService.notBlank(r.status()) || AgentService.notBlank(r.keyword()))
                notes.put("ids", "本次按勾选的 " + ids.size()
                        + " 个码导出，已忽略 status 与 keyword 两个筛选条件："
                        + "两者同时生效会出现「勾了 20 个只导出 3 个」而界面上无法解释。");
        } else {
            List<String> st = statuses(r.status());
            where.append(" AND c.status IN (").append("?,".repeat(st.size() - 1)).append("?)");
            args.addAll(st);
            if (AgentService.notBlank(r.keyword())) {
                // 掩码里本来就没有完整的码，所以"按整串码搜"搜不到是正常的。
                // 按学员手机号搜要 JOIN sys_user ON c.bound_user_id，而默认的 ISSUED 集合里
                // bound_user_id 恒为 NULL —— 那段只有在 status 含 BOUND 时才有意义，
                // 且必须与 GET /api/agent/codes 的谓词一次定死（那个接口目前根本没实现
                // keyword，而前端一直在传）。在两处统一之前，这里只做掩码与订单号。
                where.append(" AND (c.code_masked LIKE ? OR o.order_no LIKE ?)");
                String kw = "%" + r.keyword().trim() + "%";
                args.add(kw);
                args.add(kw);
                notes.put("keyword", "搜索只在激活码掩码与订单号上匹配。掩码里没有完整的码，"
                        + "所以按整串码搜不到是正常的；按学员手机号搜暂未开放"
                        + "（需要与激活码列表的搜索口径一次定死，否则同一个搜索词在两处会给出不同的集合）。");
            }
        }

        String from = " FROM activation_code c LEFT JOIN biz_order o ON o.id = c.order_id";

        // 先 COUNT 再取。命中过多时当场拒，不截断 —— 理由见 MAX_EXPORT。
        Long total = AgentService.num(jdbc.queryForObject(
                "SELECT COUNT(*)" + from + where, Number.class, args.toArray()));
        long n = total == null ? 0 : total;
        if (n > MAX_EXPORT)
            throw ApiException.badRequest("本次命中 " + n + " 个码，超过单次导出上限 " + MAX_EXPORT
                    + "，请用搜索或按订单缩小范围后再导。"
                    + "（这里不做截断：截断之后你会以为剩下的码已经发过了）");

        List<Sealed> sealed = jdbc.query(
                "SELECT c.id, c.code_enc, c.code_hash, c.code_masked, c.status,"
              + " c.activate_deadline, o.order_no, o.origin" + from + where + " ORDER BY c.id",
                (rs, i) -> new Sealed(rs.getLong("id"), rs.getBytes("code_enc"),
                        rs.getString("code_hash"), rs.getString("code_masked"),
                        rs.getString("status"),
                        rs.getObject("activate_deadline", LocalDateTime.class),
                        rs.getString("order_no"), rs.getString("origin")),
                args.toArray());

        var rows = new ArrayList<Map<String, Object>>(sealed.size());
        tx.executeWithoutResult(st -> {
            var auditArgs = new ArrayList<Object[]>(sealed.size());
            for (Sealed s : sealed) {
                // 解密失败（密钥不对、密文被搬过家）必须整批炸，不能跳过那一条：
                // 少一个码的导出文件和完整的导出文件长得一模一样，
                // 而代理会按文件里的行数去发货。
                String plain = vault.open(s.enc(), s.hash());
                var m = new LinkedHashMap<String, Object>();
                m.put("id", s.id());
                m.put("code", plain);
                m.put("masked", s.masked());
                m.put("status", s.status());
                m.put("activateDeadline", s.deadline());
                m.put("orderNo", s.orderNo());
                // 导出的 CSV 里「来源」那一列读的是这个。平台手工发的码有真单号，
                // 但那笔单从来没有收到过钱 —— 让 CSV 里只出现一个看起来正常的订单号，
                // 代理会拿它去对账，而账上没有这笔。
                m.put("source", CodeService.sourceOf(s.origin()));
                rows.add(m);
                auditArgs.add(new Object[]{userId, String.valueOf(s.id()), ip});
            }
            if (!auditArgs.isEmpty())
                jdbc.batchUpdate(
                        "INSERT INTO audit_log (operator_id,action,target_type,target_id,"
                      + "after_json,client_ip) VALUES (?,'CODE_REVEAL','activation_code',?,"
                      + "JSON_OBJECT('via','EXPORT'),?)", auditArgs);
        });

        var out = new LinkedHashMap<String, Object>();
        // 命中 0 条是 200 + 空列表，不是 404：前端据此 toast「没有可导出的未激活码」，
        // 而 404 会被 shell 的统一出口变成一张错误页。
        out.put("total", rows.size());
        out.put("rows", rows);
        out.put("notice", NOTICE);
        out.put("notes", notes);
        return out;
    }

    /**
     * 逗号分隔的状态集合 → 校验过的列表。默认 {@code ISSUED}。
     *
     * <p>枚举外的值<b>当场 400 而不是查空</b>：查空会被前端读成"我没有码"，
     * 而"没有码"和"状态名拼错了"在界面上长得一模一样，这种 bug 能在线上活很久。
     */
    private static List<String> statuses(String raw) {
        if (!AgentService.notBlank(raw)) return List.of("ISSUED");
        var out = new ArrayList<String>();
        for (String token : raw.split(",")) {
            String t = token.trim();
            if (t.isEmpty()) continue;
            String s = AgentService.normalizeEnum(t, CODE_STATUS, "激活码状态");
            if (!out.contains(s.toUpperCase(Locale.ROOT))) out.add(s);
        }
        if (out.isEmpty()) return List.of("ISSUED");
        return out;
    }

    /** 库里那一行的原样。解密放在事务里做，所以先整行取出来。 */
    private record Sealed(long id, byte[] enc, String hash, String masked, String status,
                          LocalDateTime deadline, String orderNo, String origin) {}
}
