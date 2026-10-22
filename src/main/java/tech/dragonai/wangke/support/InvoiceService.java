package tech.dragonai.wangke.support;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tech.dragonai.wangke.web.ApiException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 开票。
 *
 * <h3>这一层只有一条真正的红线：金额不许来自前端</h3>
 * 开票金额如果由前端传，等于让买方自己决定要一张多大金额的发票 ——
 * 那不是「参数校验没做」，那是虚开增值税专用发票，是刑事风险。
 * 所以 {@code amount_fen} 在整条链上<b>只有一个来源</b>：服务端按订单实付减去退款算出来。
 * apply 接口连一个 amount 字段都不接，接了就迟早有人在某个分支里把它用上。
 *
 * <h3>可开票额 = 实付 − 已退 − 已开</h3>
 * 三个减项各有一个坑：
 * <ul>
 *   <li><b>「已退」不能只看 {@code biz_order.refunded_amount_fen}</b>。全仓 grep 过：
 *       这一列<b>没有任何代码在写</b>，今天恒等于 0。只读它的话，一笔已经退了 799 的订单
 *       照样能开出 799 的发票，而且账面上完全自洽、不会报错。所以真正的口径取
 *       {@code refund_record} 里 APPLIED/APPROVED/SUCCESS 三态之和 —— 这与
 *       trg_refund_precheck 判断「退款是否超额」用的是<b>同一个集合</b>，
 *       两处口径必须一致，否则会出现「退款那边算超了、开票这边算没退」的对不上。
 *       两个来源取 GREATEST：宁可少开，多开是不可逆的。
 *   <li><b>「已开」要含在途申请</b>（APPLIED），不能只算已开出的（ISSUED）。
 *       只算 ISSUED 的话，两笔各自不超实付的在途申请合起来可以开出两倍。
 *       这跟退款那条「在途退款也占额度」是同一类错。
 *   <li><b>开票那一刻要重算一次</b>。申请是 T0 算的，票是 T1 开的，中间发生退款的话，
 *       T0 那个数就成了虚开的金额。真正会形成一张票的动作是 issue，所以闸必须也在 issue 上。
 * </ul>
 *
 * <h3>为什么这里只处理 scope='ORDER'</h3>
 * {@code invoice_request.scope} 还有一个 WITHDRAW，但那条的方向是反的：
 * {@code withdraw_order.invoice_no} 的注释写着「代理向平台开的票（G11）」——
 * 是代理给平台开票，不是平台给代理开票。把两个方向塞进同一个接口，
 * 迟早会出现平台给自己收到的票再开一张票。
 */
@Service
public class InvoiceService {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public InvoiceService(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc; this.tx = tx;
    }

    /** 单位一律「分」。金额绝不用 double —— 0.1+0.2 那种误差落在钱上就是对不上账。 */
    record Base(long paidFen, long refundedFen, long invoicedFen) {
        long invoiceableFen() { return paidFen - refundedFen - invoicedFen; }
    }

    // ================================================================= 申请

    /**
     * 申请开票。<b>注意签名里没有金额</b>。
     *
     * @param titleText 发票抬头。这个可以信前端 —— 抬头是买方自己的信息，写错了是他自己的事；
     *                  金额不行，金额写错了是平台的刑事风险。两者的性质完全不同，别一起「校验一下」。
     */
    public Map<String, Object> apply(long userId, long orderId, String titleText, String taxNo) {
        String title = requireText(titleText, 128, "发票抬头");
        String tax = normalizeTaxNo(taxNo);

        return tx.execute(st -> {
            Base b = baseOf(orderId, userId, 0L);
            if (b.paidFen() <= 0)
                throw ApiException.badRequest("该订单尚未支付，无法开票");

            // 这一查【只决定措辞，不决定成败】—— 成败仍由下面的唯一键与条件 UPDATE 裁决。
            // 之所以要它：已申请过的那一行本身就把可开票额吃成了 0，
            // 不看一眼状态的话，一次最普通的重复点击会得到「可开票金额已全部申请或开出」，
            // 用户读完仍然不知道是自己刚点过、还是这单出了什么问题，于是继续点。
            // 越权在这一步之前已经由 baseOf 的 WHERE 挡掉了（订单不是你的就 404），
            // 所以这里不必也不该再拼一次 applicant_user_id —— 同一笔订单只有一行申请。
            String existing = existingStatus(orderId);
            if ("APPLIED".equals(existing))
                throw ApiException.conflict("该订单的开票申请正在处理中，请勿重复提交");
            if ("ISSUED".equals(existing))
                throw ApiException.conflict("该订单已开具过发票，如需重开请联系客服");

            long amount = b.invoiceableFen();
            // 走到这里只剩「没有申请过」与「上一次被驳回」两种，归零就只有一个原因：退款吃光了
            if (amount <= 0)
                throw ApiException.conflict("该订单实付已全额退款，没有可开票金额");

            long id;
            try {
                id = insertRequest(userId, orderId, title, tax, amount);
            } catch (DuplicateKeyException e) {
                // uk_scope_ref 决定一笔订单只有一行开票申请。撞上它有两种情况：
                // 之前被驳回过（应当允许改抬头重来），或者正在处理 / 已开出（不允许）。
                // 用一条带 status='REJECTED' 的 UPDATE 去裁决，而不是先查一遍状态再决定 ——
                // 先查后写在两个客服同时点「重新提交」时会双双通过。
                // applicant_user_id 也进 WHERE：别人的订单撞不出别人的申请。
                int n = jdbc.update(
                        "UPDATE invoice_request SET title=?, tax_no=?, amount_fen=?, "
                      + "       status='APPLIED', reject_reason=NULL, processed_by=NULL "
                      + " WHERE scope='ORDER' AND ref_id=? AND applicant_user_id=? AND status='REJECTED'",
                        title, tax, amount, orderId, userId);
                if (n != 1) throw ApiException.conflict("该订单已提交过开票申请，请勿重复提交");
                id = num(jdbc.queryForMap(
                        "SELECT id FROM invoice_request WHERE scope='ORDER' AND ref_id=?", orderId)
                        .get("id"));
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", id);
            out.put("orderId", orderId);
            out.put("amountFen", amount);
            out.put("status", "APPLIED");
            return out;
        });
    }

    /**
     * 可开票的订单。
     *
     * <h3>为什么它不能复用 {@code /api/orders}</h3>
     * <b>2026-09-01：学员端那个入口已经拆掉了</b>（需求方两次点名要求去掉，并称之为 bug），
     * 服务端也按角色关上了 —— STUDENT 调 apply / my / eligible-orders 一律 403，AGENT 照常。
     * 所以现在这个方法只服务代理进货单。留着这段历史是因为下面那条「为什么不拿
     * {@code /api/orders} 的返回来挑单」的理由仍然成立，而它当初就是在学员端那个入口上踩出来的。
     * 而 2026-08-19 之后那条路收窄成了「只出 buyer_type='STUDENT'」——
     * 于是<b>代理再也开不了进货单的票</b>，而代理端根本没有开票入口（全仓 grep 为零）。
     * B2B 那一半的开票需求比 C 端还刚性，这不是可以顺手砍掉的东西。
     *
     * <p>关键区别在于两个问题不一样：「这一单该不该出现在学员那一页上」是身份问题，
     * 而「这一单能不能开票」是<b>他有没有付过这笔钱</b>的问题。后者与身份无关，
     * 所以这里按 {@code buyer_user_id} 圈人，<b>不按 buyer_type 分</b>。
     * 顺带说明：他在这里看到的是<b>自己付过的那一单</b>的金额 ——
     * 那个数他本来就知道（是他自己付的），不构成价格披露。
     *
     * <p>可开票额与是否已申请，这里都由服务端算完再出，前端不再自己筛：
     * 原来那段筛选逻辑散在页面里（状态白名单 + 已申请集合），
     * 而"哪些状态还能开票"这条规则的权威在 {@link #apply} 里，两处迟早会分叉。
     */
    public List<Map<String, Object>> invoiceableOrders(long userId, int limit) {
        int lim = Math.max(1, Math.min(limit, 100));
        return jdbc.queryForList(
                "SELECT o.id AS orderId, o.order_no AS orderNo, o.buyer_type AS buyerType,"
              + "       o.sku_code_snapshot AS skuCode, s.name AS skuName, o.status,"
              + "       o.paid_amount_fen"
              + "       - COALESCE((SELECT SUM(r.amount_fen) FROM refund_record r"
              + "                    WHERE r.order_id=o.id"
              + "                      AND r.status IN ('APPLIED','APPROVED','SUCCESS')),0)"
              + "       - COALESCE((SELECT SUM(i.amount_fen) FROM invoice_request i"
              + "                    WHERE i.scope='ORDER' AND i.ref_id=o.id"
              + "                      AND i.status IN ('APPLIED','ISSUED')),0) AS invoiceableFen"
              + "  FROM biz_order o LEFT JOIN sku s ON s.id=o.sku_id"
              + " WHERE o.buyer_user_id=?"
              // ★ 显式挡住平台白送的单，不靠 HAVING 那个副作用。
              // 白送单 status='FULFILLED'，直接通过下面那条状态白名单；而这条 SQL 是按
              // buyer_user_id 圈人、刻意不按 buyer_type 分（理由见上面的注释），
              // 代理本人就是 buyer_user_id。今天唯一挡住它的是 paid_amount_fen=0 让
              // invoiceableFen 算成 0 —— 那是一条巧合，不是设计。哪天有人把 HAVING 挪到前端、
              // 或者补一条按应付金额开票的需求，买方账号里就凭空多出一张
              // 平台从未收到过钱的可开票单，而这条链的类注释自己把它定为刑事风险红线。
              + "   AND o.origin='SELF_SERVE'"
              + "   AND o.status IN ('PAID','FULFILLED','PARTIAL_REFUNDED')"
              // 已经在处理或已开出的不再列出来。被驳回过的要能改抬头重来，所以不排除。
              + "   AND NOT EXISTS (SELECT 1 FROM invoice_request i2"
              + "                    WHERE i2.scope='ORDER' AND i2.ref_id=o.id"
              + "                      AND i2.status IN ('APPLIED','ISSUED'))"
              + " HAVING invoiceableFen > 0"
              + " ORDER BY o.id DESC LIMIT ?", userId, lim);
    }

    public List<Map<String, Object>> mine(long userId, int limit) {
        int n = Math.max(1, Math.min(limit, 100));
        return jdbc.query(
                "SELECT id, scope, ref_id, title, tax_no, amount_fen, status, invoice_no, "
              + "       issued_at, reject_reason, created_at "
              + "  FROM invoice_request WHERE applicant_user_id=? ORDER BY id DESC LIMIT " + n,
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("scope", rs.getString("scope"));
                    m.put("orderId", rs.getLong("ref_id"));
                    m.put("title", rs.getString("title"));
                    m.put("taxNo", rs.getString("tax_no"));
                    m.put("amountFen", rs.getLong("amount_fen"));
                    m.put("status", rs.getString("status"));
                    m.put("invoiceNo", rs.getString("invoice_no"));
                    m.put("issuedAt", rs.getObject("issued_at"));
                    m.put("rejectReason", rs.getString("reject_reason"));
                    m.put("createdAt", rs.getObject("created_at"));
                    return m;
                }, userId);
    }

    // ================================================================= 开票处理

    public List<Map<String, Object>> queue(String status, int limit) {
        int n = Math.max(1, Math.min(limit, 100));
        String s = status == null || status.isBlank() ? "APPLIED" : status.strip().toUpperCase();
        if (!List.of("APPLIED", "ISSUED", "REJECTED").contains(s))
            throw ApiException.badRequest("未知的开票申请状态");
        return jdbc.query(
                "SELECT id, applicant_user_id, scope, ref_id, title, tax_no, amount_fen, status, "
              + "       invoice_no, issued_at, reject_reason, processed_by, created_at "
              + "  FROM invoice_request WHERE status=? ORDER BY id ASC LIMIT " + n,
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("applicantUserId", rs.getLong("applicant_user_id"));
                    m.put("scope", rs.getString("scope"));
                    m.put("refId", rs.getLong("ref_id"));
                    m.put("title", rs.getString("title"));
                    m.put("taxNo", rs.getString("tax_no"));
                    m.put("amountFen", rs.getLong("amount_fen"));
                    m.put("status", rs.getString("status"));
                    m.put("invoiceNo", rs.getString("invoice_no"));
                    m.put("issuedAt", rs.getObject("issued_at"));
                    m.put("rejectReason", rs.getString("reject_reason"));
                    m.put("createdAt", rs.getObject("created_at"));
                    return m;
                }, s);
    }

    /**
     * 标记已开票并登记发票号。
     *
     * <p>这里是<b>唯一一处真的会让一张发票存在</b>的动作，所以金额闸开在这里而不只是申请时：
     * 申请到开票之间发生退款是常态（学员先申请发票，客服后处理退款）。
     */
    public void issue(long operatorId, long invoiceId, String invoiceNoRaw, String ip) {
        String invoiceNo = requireText(invoiceNoRaw, 64, "发票号");

        tx.executeWithoutResult(st -> {
            var rows = jdbc.queryForList(
                    "SELECT scope, ref_id, amount_fen, status FROM invoice_request WHERE id=? FOR UPDATE",
                    invoiceId);
            if (rows.isEmpty()) throw ApiException.notFound("开票申请不存在");
            var r = rows.get(0);
            if (!"ORDER".equals(r.get("scope")))
                throw ApiException.badRequest("该申请不是订单开票，请走对应流程处理");
            long refId = num(r.get("ref_id"));
            long amount = num(r.get("amount_fen"));

            // 重算。排除自己这一行，否则「已开」里会把待开的这一笔也算进去，永远开不出来。
            Base b = baseOf(refId, null, invoiceId);
            if (amount > b.invoiceableFen())
                throw ApiException.conflict(
                        "开票金额已超过该订单的实付余额（申请之后发生了退款），请驳回后重新申请");

            int n;
            try {
                n = jdbc.update(
                        "UPDATE invoice_request SET status='ISSUED', invoice_no=?, issued_at=NOW(3), "
                      + "       processed_by=?, reject_reason=NULL "
                      + " WHERE id=? AND status='APPLIED'",
                        invoiceNo, operatorId, invoiceId);
            } catch (DuplicateKeyException e) {
                // uk_invoice_no（见 migrations/20260811）。同一个发票号登记到两笔申请上，
                // 账面上就是一张票开了两笔收入 —— 税务口径上的重复入账。
                // 这里必须换成人话再抛：让它裸着走到 GlobalExceptionHandler 的话，
                // 财务看到的是一句「该记录已存在，请勿重复提交」，
                // 而真正该做的动作（回去核对发票号填错了哪一位）一个字都没提。
                throw ApiException.conflict("该发票号已登记在另一笔开票申请上，请核对发票号后重填");
            }
            // 影响 0 行 = 已经被处理过（并发双击、两个客服同时点）。
            // 状态判定放在 WHERE 里而不是先查后写：先查后写会让两次点击开出两张票。
            if (n != 1) throw ApiException.conflict("该申请已被处理过，请刷新后查看当前状态");

            audit(operatorId, "INVOICE_ISSUE", invoiceId,
                    "{\"invoiceNo\":\"" + invoiceNo.replace("\"", "") + "\",\"amountFen\":"
                  + amount + ",\"orderId\":" + refId + "}", ip);
        });
    }

    public void reject(long operatorId, long invoiceId, String reasonRaw, String ip) {
        String reason = requireText(reasonRaw, 255, "驳回原因");
        int n = jdbc.update(
                "UPDATE invoice_request SET status='REJECTED', reject_reason=?, processed_by=? "
              + " WHERE id=? AND status='APPLIED'", reason, operatorId, invoiceId);
        if (n != 1) throw ApiException.conflict("该申请不在待处理状态，无法驳回");
        audit(operatorId, "INVOICE_REJECT", invoiceId,
                "{\"reason\":" + jsonString(reason) + "}", ip);
    }

    // ================================================================= 内部

    /**
     * 算这笔订单此刻的可开票口径。
     *
     * @param ownerUserId       非 null 时把「订单属于谁」压进 WHERE。学员侧必须传，
     *                          否则「先按 id 查订单再比对 buyer」会变成一台枚举别人订单的探针；
     *                          管理员侧传 null 是有意的 —— 他处理的就是别人的订单。
     * @param excludeInvoiceId  「已开」求和时要排除的申请行（开票时排除自己），0 表示不排除
     */
    private Base baseOf(long orderId, Long ownerUserId, long excludeInvoiceId) {
        StringBuilder sql = new StringBuilder(
                "SELECT o.paid_amount_fen AS paid, "
              // GREATEST：两个口径谁大听谁的。少开可以补，多开是不可逆的。
              + "       GREATEST(o.refunded_amount_fen, COALESCE(("
              + "           SELECT SUM(r.amount_fen) FROM refund_record r "
              + "            WHERE r.order_id=o.id AND r.status IN ('APPLIED','APPROVED','SUCCESS')),0)"
              + "       ) AS refunded, "
              + "       COALESCE((SELECT SUM(i.amount_fen) FROM invoice_request i "
              + "                  WHERE i.scope='ORDER' AND i.ref_id=o.id "
              + "                    AND i.status IN ('APPLIED','ISSUED') AND i.id<>?),0) AS invoiced, "
              + "       o.origin AS origin "
              + "  FROM biz_order o WHERE o.id=?");
        List<Object> args = new ArrayList<>(List.of(excludeInvoiceId, orderId));
        if (ownerUserId != null) { sql.append(" AND o.buyer_user_id=?"); args.add(ownerUserId); }
        // 写成 FOR UPDATE OF o 而不是裸 FOR UPDATE：裸写会把子查询扫到的
        // refund_record / invoice_request 行一起锁上，开票复核期间挡住退款审批，
        // 表现是「点退款转圈」而不是任何一条错误信息。这里只需要锁订单行。
        //
        // 老实说：这把锁现在挡不住并发退款 —— 退款那条路径既不写 biz_order 也不锁它
        //（refunded_amount_fen 全仓无人写入）。写在这里是为了把两侧收敛到同一把锁上，
        // 等退款侧补上「改 biz_order.refunded_amount_fen」时，这道闸自动生效。
        sql.append(" FOR UPDATE OF o");

        var rows = jdbc.queryForList(sql.toString(), args.toArray());
        if (rows.isEmpty()) throw ApiException.notFound("订单不存在");
        var m = rows.get(0);
        // 归属条件留在 WHERE 里（那是越权维度，「查不到」与「不是你的」必须是同一种结果）；
        // 来源是业务规则，放到这里判是为了给一句能看懂的话 —— 白送的单被答成
        // 「订单不存在」的话，代理会一直以为是系统出错，然后来问客服。
        if ("ADMIN_GIFT".equals(m.get("origin")))
            throw ApiException.badRequest("这一单是平台手工发放的，从来没有收到过钱，开不出发票");
        return new Base(num(m.get("paid")), num(m.get("refunded")), num(m.get("invoiced")));
    }

    /**
     * 这笔订单已有的那一行开票申请是什么状态；没有就返回 null。
     *
     * <p>调用方必须已经过了归属闸（{@link #baseOf} 带 ownerUserId），
     * 否则这条查询就是一台「拿订单 id 探别人有没有开过票」的探针。
     */
    private String existingStatus(long orderId) {
        var rows = jdbc.queryForList(
                "SELECT status FROM invoice_request WHERE scope='ORDER' AND ref_id=?", orderId);
        return rows.isEmpty() ? null : (String) rows.get(0).get("status");
    }

    private long insertRequest(long userId, long orderId, String title, String tax, long amount) {
        jdbc.update("INSERT INTO invoice_request "
                  + "(applicant_user_id,scope,ref_id,title,tax_no,amount_fen) "
                  + "VALUES (?,'ORDER',?,?,?,?)", userId, orderId, title, tax, amount);
        // 自然键 (scope, ref_id) 上有唯一键，用它取回 id 比 LAST_INSERT_ID() 稳：
        // 后者是按连接的会话变量，池化之后不保证还是刚才那条连接。
        return num(jdbc.queryForMap(
                "SELECT id FROM invoice_request WHERE scope='ORDER' AND ref_id=?", orderId).get("id"));
    }

    private void audit(long operatorId, String action, long invoiceId, String afterJson, String ip) {
        jdbc.update("INSERT INTO audit_log (operator_id,action,target_type,target_id,after_json,client_ip) "
                  + "VALUES (?,?,'invoice_request',?,?,?)",
                operatorId, action, String.valueOf(invoiceId), afterJson, ip);
    }

    private static String normalizeTaxNo(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.strip().toUpperCase();
        // 只做「明显不是税号」的拦截。真正的校验位算法不在这里做：
        // 算错会把合法抬头拒在门外，而这一列本来就允许为空（个人抬头没有税号）。
        if (!s.matches("[0-9A-Z]{15,20}"))
            throw ApiException.badRequest("纳税人识别号格式不正确");
        return s;
    }

    private static String requireText(String v, int max, String what) {
        if (v == null || v.isBlank()) throw ApiException.badRequest(what + "不能为空");
        String s = v.strip();
        if (s.length() > max)
            throw ApiException.badRequest(what + "最多 " + max + " 个字符");
        return s;
    }

    /** 手搓 JSON 只用在这两处审计字段上，且必须转义 —— 驳回原因是人写的自由文本。 */
    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default   -> { if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c); }
            }
        }
        return sb.append('"').toString();
    }

    /** BIGINT UNSIGNED 在 Connector/J 里是 BigInteger 不是 Long，直接强转会 ClassCastException。 */
    private static long num(Object v) { return ((Number) v).longValue(); }
}
