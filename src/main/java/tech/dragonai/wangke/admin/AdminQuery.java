package tech.dragonai.wangke.admin;

import tech.dragonai.wangke.web.ApiException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

/**
 * 后台列表接口的公共参数处理。
 */
final class AdminQuery {

    private AdminQuery() {}

    /** 每页上限 200。不设上限的话 {@code ?size=1000000} 就是一个把库拖死的合法请求。 */
    static final int MAX_SIZE = 200;

    static int page(Integer p) { return p == null || p < 1 ? 1 : Math.min(p, 100_000); }

    static int size(Integer s) { return s == null ? 20 : Math.max(1, Math.min(s, MAX_SIZE)); }

    static long offset(int page, int size) { return (long) (page - 1) * size; }

    /**
     * LIKE 的通配符转义。
     *
     * <p>不转义的话，一个 {@code keyword=%} 就是「返回全库用户」—— 参数化查询防的是注入，
     * 防不了这个：{@code %} 与 {@code _} 在 LIKE 的语义里本来就是通配符，是合法值不是攻击载荷。
     */
    static String like(String keyword) {
        String k = keyword == null ? "" : keyword.trim();
        StringBuilder sb = new StringBuilder("%");
        for (int i = 0; i < k.length(); i++) {
            char c = k.charAt(i);
            if (c == '%' || c == '_' || c == '\\') sb.append('\\');
            sb.append(c);
        }
        return sb.append('%').toString();
    }

    static boolean blank(String s) { return s == null || s.isBlank(); }

    /** 只接受白名单里的枚举值。直接把用户串拼进 SQL 的 IN 是另一回事，但拼进去也会让 ENUM 报错，还不如当场拒。 */
    static String enumOf(String v, String field, String... allowed) {
        if (blank(v)) return null;
        String s = v.trim().toUpperCase();
        for (String a : allowed) if (a.equals(s)) return s;
        throw ApiException.badRequest(field + " 只能是：" + String.join(" / ", allowed));
    }

    /**
     * 日期时间参数。接受 {@code 2026-08-11} 或 {@code 2026-08-11T09:30:00}。
     *
     * @param endOfDay 只给了日期时，是否补到当天 23:59:59.999 —— 区间上界写成 00:00
     *                 会让「查 8 月 11 日的单」一条都查不出来，而且看起来像是那天没有单
     */
    static LocalDateTime time(String v, boolean endOfDay, String field) {
        if (blank(v)) return null;
        String s = v.trim();
        try {
            if (s.length() == 10) {
                LocalDate d = LocalDate.parse(s);
                return endOfDay ? d.atTime(23, 59, 59, 999_000_000) : d.atStartOfDay();
            }
            return LocalDateTime.parse(s);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest(field + " 格式应为 2026-08-11 或 2026-08-11T09:30:00");
        }
    }

    /** 手机号掩码。列表默认只出掩码，完整号码要走单独的「查看」接口并留痕。 */
    static String maskPhone(String phone) {
        if (phone == null) return null;
        String p = phone.trim();
        if (p.length() < 7) return "***";
        return p.substring(0, 3) + "****" + p.substring(p.length() - 4);
    }

    /** 银行账号 / 收款账号掩码。只留后 4 位，够运营核对，不够别人拿去用。 */
    static String maskAccount(String acct) {
        if (acct == null) return null;
        String a = acct.trim();
        if (a.length() <= 4) return "****";
        return "****" + a.substring(a.length() - 4);
    }

    /**
     * 分 → 元的展示串。<b>只用于给人看的文案</b>，任何参与计算的地方一律留在分。
     * BigDecimal 而不是 double：double 拼出来会有 3489.9999999999995 这种东西，
     * 而运营看到这个数只会认为系统算错了钱。
     */
    static String yuan(long fen) {
        return new java.math.BigDecimal(fen).movePointLeft(2).toPlainString();
    }

    /**
     * BIGINT UNSIGNED 安全取值。
     *
     * <p>Connector/J 把 BIGINT UNSIGNED 映射成 <b>BigInteger</b> 而不是 Long，
     * {@code (Long) row.get("id")} 会在运行期抛 ClassCastException。本库每个主键都是无符号，
     * 所以取 id 一律走这里。
     */
    static Long id(Object v) { return v == null ? null : ((Number) v).longValue(); }

    static long longOf(Object v, long dflt) {
        if (v == null) return dflt;
        if (v instanceof Boolean b) return b ? 1 : 0;
        return ((Number) v).longValue();
    }

    /**
     * 取整数列。<b>要能吃下 Boolean。</b>
     *
     * <p>Connector/J 默认开着 {@code tinyInt1isBit}，于是 schema 里所有
     * {@code TINYINT(1)} 列（{@code is_preview}、{@code must_change_pwd}、
     * {@code shuffle_options}…）从 JDBC 出来的是 <b>Boolean 而不是 Number</b>。
     * 直接 {@code ((Number) v).intValue()} 会在运行期抛 ClassCastException ——
     * 而且只在那几列上抛，其余全绿，很容易被当成偶发问题。
     * 这一条和「BIGINT UNSIGNED 是 BigInteger 不是 Long」是同一类坑，一并在这里收口。
     */
    static int intOf(Object v, int dflt) {
        if (v == null) return dflt;
        if (v instanceof Boolean b) return b ? 1 : 0;
        return ((Number) v).intValue();
    }

    /** TINYINT(1) 列的统一出口。对外一律给 true/false，不给 0/1 —— 免得前端两种都要处理。 */
    static Boolean flag(Object v) {
        if (v == null) return null;
        if (v instanceof Boolean b) return b;
        return ((Number) v).intValue() != 0;
    }

    /**
     * 插入并拿回自增主键。
     *
     * <p>不用 {@code SELECT LAST_INSERT_ID()} 单独查一次：那个值是<b>按连接</b>保存的，
     * 而 JdbcTemplate 的每条语句都可能从池里拿到不同的连接 —— 平时（池空闲、恰好复用同一条）
     * 一切正常，并发起来就会拿到别人刚插入的那个 id。这种错不会报错，只会把新建的讲次
     * 挂到另一门课上，或者把审计写到另一条记录上。
     */
    static long insert(org.springframework.jdbc.core.JdbcTemplate jdbc, String sql, Object... args) {
        var kh = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            return ps;
        }, kh);
        Number k = kh.getKey();
        if (k == null) throw new IllegalStateException("插入后没有拿到自增主键");
        return k.longValue();
    }
}
