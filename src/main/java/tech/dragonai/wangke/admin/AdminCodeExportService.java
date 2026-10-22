package tech.dragonai.wangke.admin;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tech.dragonai.wangke.trade.CodeVault;
import tech.dragonai.wangke.web.ApiException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 超管批量取激活码明文（一次发放 / 一张订单的全部码）。
 *
 * <h3>为什么需要它</h3>
 * 在此之前超管取明文只有逐条 {@code POST /api/admin/codes/{codeId}/reveal} 一条路。
 * 手工给代理发 10 个码，就是点 10 次「查看」+ 复制 10 次 —— 而这正是最容易
 * 「复制漏一个」的场景，漏掉的那一个躺在库里没人发，代理也不知道自己少了一张。
 *
 * <h3>三条纪律逐字照抄代理侧的 AgentCodeExportService，一个字都不许分叉</h3>
 * <ol>
 *   <li><b>超限当场 400，绝不截断。</b>截断之后运营会以为剩下的码已经发过了。
 *   <li><b>解密与审计在同一个事务里落库之后才返回。</b>先回响应再补审计的话，
 *       进程崩在中间就是一次<b>无痕的明文泄漏</b> —— 码已经在对方屏幕上了，
 *       而平台这边一条记录都没有。
 *   <li><b>任何一条解密失败整批抛，不跳过。</b>少一个码的导出文件和完整的导出文件
 *       长得一模一样，而人会按文件里的行数去发货。
 * </ol>
 *
 * <h3>动作名复用 ADMIN_CODE_REVEAL，不另起一个</h3>
 * 起新名字看起来更清楚，实际后果是「这串码被谁看过」要查两个 action，
 * 而第二个迟早有人忘了查 —— 纠纷发生时，漏掉的那一半就是平台拿不出来的那一半。
 * 区分批量与单条靠 after_json 里的 {@code via} 字段。
 */
@Service
public class AdminCodeExportService {

    /** 单次导出上限。与代理侧同一个量级的理由：超限 400 不截断。 */
    static final int MAX_EXPORT = 500;

    /** 与单条 reveal 同一句提示。两处措辞不同的话，人会以为是两种不同的操作。 */
    static final String NOTICE = "该次查看已记入审计日志。请勿在群内转发完整激活码";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final CodeVault vault;

    public AdminCodeExportService(JdbcTemplate jdbc, TransactionTemplate tx, CodeVault vault) {
        this.jdbc = jdbc; this.tx = tx; this.vault = vault;
    }

    private record Sealed(long id, byte[] enc, String hash, String masked, String status,
                          LocalDateTime deadline, String orderNo, String origin) {}

    /**
     * 按订单取全部码的明文。
     *
     * @param via 落进审计的来源标记：{@code ORDER_EXPORT} / {@code GRANT_EXPORT}
     */
    public Map<String, Object> exportByOrders(long operatorUserId, List<Long> orderIds,
                                              String via, String ip) {
        if (orderIds == null || orderIds.isEmpty())
            throw ApiException.badRequest("没有指定要导出的订单");
        String in = "?,".repeat(orderIds.size() - 1) + "?";

        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM activation_code WHERE order_id IN (" + in + ")",
                Long.class, orderIds.toArray());
        long n = total == null ? 0 : total;
        if (n > MAX_EXPORT)
            throw ApiException.badRequest("本次命中 " + n + " 个码，超过单次导出上限 " + MAX_EXPORT
                    + "，请按订单分批导出。（这里不做截断：截断之后你会以为剩下的码已经发过了）");

        // LEFT JOIN 不是笔误：order_id 虽然是 NOT NULL，但全库零外键，
        // 指向不存在订单的孤儿码理论上存在。写 INNER 的话那些行会整片静默消失 ——
        // 不报错、不空，只是行数变少。
        List<Sealed> sealed = jdbc.query(
                "SELECT c.id, c.code_enc, c.code_hash, c.code_masked, c.status,"
              + " c.activate_deadline, o.order_no, o.origin"
              + " FROM activation_code c LEFT JOIN biz_order o ON o.id = c.order_id"
              + " WHERE c.order_id IN (" + in + ") ORDER BY c.id",
                (rs, i) -> new Sealed(rs.getLong("id"), rs.getBytes("code_enc"),
                        rs.getString("code_hash"), rs.getString("code_masked"),
                        rs.getString("status"),
                        rs.getObject("activate_deadline", LocalDateTime.class),
                        rs.getString("order_no"), rs.getString("origin")),
                orderIds.toArray());

        var rows = new ArrayList<Map<String, Object>>(sealed.size());
        tx.executeWithoutResult(st -> {
            var auditArgs = new ArrayList<Object[]>(sealed.size());
            for (Sealed s : sealed) {
                // 解密失败整批炸，不跳过那一条：见类注释第三条
                String plain = vault.open(s.enc(), s.hash());
                var m = new LinkedHashMap<String, Object>();
                m.put("codeId", s.id());
                m.put("code", plain);
                m.put("masked", s.masked());
                m.put("status", s.status());
                m.put("activateDeadline", s.deadline());
                m.put("orderNo", s.orderNo());
                m.put("source", "ADMIN_GIFT".equals(s.origin()) ? "PLATFORM_GRANT" : "PURCHASE");
                rows.add(m);
                auditArgs.add(new Object[]{operatorUserId, String.valueOf(s.id()), via, ip});
            }
            if (!auditArgs.isEmpty())
                jdbc.batchUpdate(
                        "INSERT INTO audit_log (operator_id,action,target_type,target_id,"
                      + "after_json,client_ip) VALUES (?,'ADMIN_CODE_REVEAL','activation_code',?,"
                      + "JSON_OBJECT('via',?),?)", auditArgs);
        });

        var out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        out.put("total", rows.size());
        out.put("rows", rows);
        out.put("notice", NOTICE);
        return out;
    }
}
