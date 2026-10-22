package tech.dragonai.wangke.trade;

import org.springframework.jdbc.core.JdbcTemplate;
import tech.dragonai.wangke.web.ApiException;

import java.util.ArrayList;
import java.util.List;

/**
 * 把码交到代理手上。出码那一步只写了库，没有任何路径能把明文取出来 ——
 * 那意味着钱收了、货在库里，但发不出去。这一层补的就是这段。
 *
 * <h3>为什么默认只给掩码</h3>
 * 代理后台列表天天开着，投屏、截图、被同事看一眼都是常态。
 * 明文只在<b>显式点"查看"</b>时逐条给出，并且每一次都写审计日志 ——
 * 日后出现"我的码被别人用了"的纠纷，平台要能说清这串码在什么时候被谁看过。
 * 没有这条记录，纠纷里平台是没有立场的一方。
 */
public class CodeService {

    private final JdbcTemplate jdbc;
    private final CodeVault vault;

    public CodeService(JdbcTemplate jdbc, CodeVault vault) {
        this.jdbc = jdbc; this.vault = vault;
    }

    /**
     * @param orderNo 这张码是哪一单出的。<b>不是可选字段</b>：代理端「来源」那一列读的就是它，
     *                后端不返回时前端静默显示成「—」—— 那一列在生产上就这么死了很久
     * @param source  {@code PURCHASE}（自己进的货）/ {@code PLATFORM_GRANT}（平台手工发放）。
     *                手工发的码有真单号，但那笔单从来没有收到过钱，
     *                让它显示成一个看起来正常的订单号，代理会拿去对账，而账上没有这笔
     */
    public record CodeRow(long id, String masked, String status, java.time.LocalDateTime deadline,
                          Long boundUserId, String orderNo, String source) {}

    /** 订单来源枚举 → 给代理看的来源。集中一处，两条查询共用。 */
    public static String sourceOf(String origin) {
        return "ADMIN_GIFT".equals(origin) ? "PLATFORM_GRANT" : "PURCHASE";
    }

    /** 代理的码列表。只出掩码。 */
    public List<CodeRow> list(long agentId, Long orderId) {
        // LEFT JOIN 不是笔误：order_id 虽然是 NOT NULL，但全库零外键，指向不存在订单的
        // 孤儿码理论上存在。写 INNER 的话那些行会从代理的「我的激活码」里整片静默消失 ——
        // 不报错、不空，只是行数变少，而代理只会说「我的码少了几张」。
        StringBuilder sql = new StringBuilder(
                "SELECT c.id, c.code_masked, c.status, c.activate_deadline, c.bound_user_id,"
              + " o.order_no, o.origin"
              + " FROM activation_code c LEFT JOIN biz_order o ON o.id = c.order_id"
              + " WHERE c.owner_agent_id=?");
        List<Object> args = new ArrayList<>();
        args.add(agentId);
        if (orderId != null) { sql.append(" AND c.order_id=?"); args.add(orderId); }
        sql.append(" ORDER BY c.id");
        return jdbc.query(sql.toString(), (rs, i) -> new CodeRow(
                rs.getLong("id"), rs.getString("code_masked"), rs.getString("status"),
                rs.getTimestamp("activate_deadline").toLocalDateTime(),
                rs.getObject("bound_user_id") == null ? null : rs.getLong("bound_user_id"),
                rs.getString("order_no"), sourceOf(rs.getString("origin"))), args.toArray());
    }

    /**
     * 取明文，逐条、留痕。
     *
     * <p>越权检查放在 SQL 的 WHERE 里而不是取出来再比对：写成
     * "先按 id 查行，再判断 owner 是不是他" 的话，只要哪天有人加了一条
     * "查不到就报另一种错" 的分支，就变成了一个可以枚举别人码 id 的探针。
     * 条件在 WHERE 里，查不到与不是你的，对外是同一种结果。
     */
    public String reveal(long operatorUserId, long agentId, long codeId, String ip) {
        var rows = jdbc.queryForList(
                "SELECT code_hash, code_enc FROM activation_code WHERE id=? AND owner_agent_id=?",
                codeId, agentId);
        if (rows.isEmpty())
            throw new IllegalArgumentException("激活码不存在或不属于你");

        String plain = vault.open((byte[]) rows.get(0).get("code_enc"),
                                  (String) rows.get(0).get("code_hash"));

        jdbc.update("INSERT INTO audit_log (operator_id,action,target_type,target_id,client_ip) "
                  + "VALUES (?,'CODE_REVEAL','activation_code',?,?)",
                operatorUserId, String.valueOf(codeId), ip);
        return plain;
    }

    /**
     * 超管按订单列码。<b>只出掩码。</b>
     *
     * <p>与代理那条 {@link #list} 的差别只在越权维度：代理按 {@code owner_agent_id} 圈自己的，
     * 超管按订单圈 —— 后台本来就能看这一单的全部信息，没有"别人的码"这个概念。
     *
     * <p>为什么需要它：在此之前后台订单详情只出 {@code status} 与 {@code COUNT(*)}，
     * 一串码都看不到，<b>连掩码都看不到</b>。学员说"码丢了"、代理说"这个码我没发过"，
     * 客服手上一点材料都没有。
     */
    public List<CodeRow> listByOrder(long orderId) {
        return jdbc.query(
                "SELECT c.id, c.code_masked, c.status, c.activate_deadline, c.bound_user_id,"
              + " o.order_no, o.origin"
              + " FROM activation_code c LEFT JOIN biz_order o ON o.id = c.order_id"
              + " WHERE c.order_id=? ORDER BY c.id",
                (rs, i) -> new CodeRow(
                        rs.getLong("id"), rs.getString("code_masked"), rs.getString("status"),
                        rs.getTimestamp("activate_deadline").toLocalDateTime(),
                        rs.getObject("bound_user_id") == null ? null : rs.getLong("bound_user_id"),
                        rs.getString("order_no"), sourceOf(rs.getString("origin"))),
                orderId);
    }

    /**
     * 超管取明文，逐条、留痕。
     *
     * <p>动作名与代理那条<b>刻意不同</b>（{@code ADMIN_CODE_REVEAL} vs {@code CODE_REVEAL}）：
     * 事后复盘要分得清这串码是代理自己看过，还是平台的人看过。
     * 两者混成同一个动作名的话，纠纷里最关键的那一问 ——「谁看过它」—— 就答不出来。
     *
     * <p>这里没有归属条件，因为超管本来就跨越归属。<b>正因如此它必须留痕</b>：
     * 一个不受归属约束的读操作，唯一的约束就是"看过这件事被记下来了"。
     */
    public String revealForAdmin(long operatorUserId, long codeId, String ip) {
        var rows = jdbc.queryForList(
                "SELECT code_hash, code_enc FROM activation_code WHERE id=?", codeId);
        if (rows.isEmpty()) throw new IllegalArgumentException("激活码不存在");

        String plain = vault.open((byte[]) rows.get(0).get("code_enc"),
                                  (String) rows.get(0).get("code_hash"));

        jdbc.update("INSERT INTO audit_log (operator_id,action,target_type,target_id,client_ip) "
                  + "VALUES (?,'ADMIN_CODE_REVEAL','activation_code',?,?)",
                operatorUserId, String.valueOf(codeId), ip);
        return plain;
    }

    /**
     * 把平台直售订单生成的激活码交给付款学员本人。
     *
     * <p>平台直售的码没有 owner_agent_id，不能复用代理取码接口；但也绝不能把明文塞进
     * 订单 GET 响应。这里用订单买家作为授权边界，并保持“显式 POST 查看 + 每次留审计”的
     * 规则。学员拿到码后仍需在激活页勾选协议，激活时才触发“激活即不退”。
     *
     * <p>不存在、不是本人的订单、代理订单统一返回同一个 404，避免成为订单存在性探针。
     * 但「确实是你的订单、码也确实发过」之后的各种状态要如实分型回告（409）——
     * 那时候已经不存在探针问题了，含糊只会坑到本人。
     */
    public String revealForBuyer(long operatorUserId, long orderId, String ip) {
        // 越权边界（o.buyer_user_id=?）必须留在 WHERE 里；码的【状态】则不能写进 WHERE ——
        // 写进去之后「这单不是你的」和「你已经把码激活了」会塌成同一个 404，
        // 而后者是刚刚才成功激活的人最可能点的那一下：系统告诉他「激活码尚未生成」。
        // 那是一句假话，且它唯一的去处是客服工单。
        var rows = jdbc.queryForList(
                "SELECT c.id, c.code_hash, c.code_enc, c.status "
              + "FROM activation_code c JOIN biz_order o ON o.id=c.order_id "
              // origin='SELF_SERVE'：不加的话，超管能从**学员端**把批量生成的那批码
              // 取出第一个明文，而那次审计记的动作名是 STUDENT_CODE_REVEAL 不是 ADMIN_CODE_REVEAL ——
              // 「这串码被谁看过」从此要查两个 action，纠纷时漏掉的那一半正是平台拿不出来的那一半。
              + "WHERE o.id=? AND o.buyer_user_id=? AND o.buyer_type='STUDENT' "
              + "AND o.origin='SELF_SERVE' "
              + "  AND c.owner_agent_id IS NULL ORDER BY c.id",
                orderId, operatorUserId);
        if (rows.isEmpty())
            throw ApiException.notFound("订单不存在或激活码尚未生成");

        // 取第一张还没被激活的。原来写的是 rows.size() != 1 —— 本意是「不存在」，
        // 实际含义却是「不恰好一张」：哪天上一个 code_qty>1 的学员档，
        // 那种订单的码会全部发出来、然后永远取不出去，货在库里发不掉。
        var issued = rows.stream().filter(r -> "ISSUED".equals(r.get("status"))).findFirst();
        if (issued.isEmpty()) {
            String st = String.valueOf(rows.get(0).get("status"));
            throw ApiException.conflict(switch (st) {
                case "BOUND"   -> "这张激活码你已经用过了，课程就在「我的课程」里";
                case "REVOKED" -> "这张激活码已因退款作废";
                case "EXPIRED" -> "这张激活码已超过激活期限，请联系平台";
                default        -> "这张激活码当前不可查看（状态 " + st + "），请联系平台";
            });
        }

        var row = issued.get();
        long codeId = ((Number) row.get("id")).longValue();
        String plain = vault.open((byte[]) row.get("code_enc"), (String) row.get("code_hash"));
        jdbc.update("INSERT INTO audit_log (operator_id,action,target_type,target_id,client_ip) "
                  + "VALUES (?,'STUDENT_CODE_REVEAL','activation_code',?,?)",
                operatorUserId, String.valueOf(codeId), ip);
        return plain;
    }
}
