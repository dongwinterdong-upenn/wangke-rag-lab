package tech.dragonai.wangke.admin;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 审计快照的 JSON 序列化，以及响应体的构造。
 *
 * <p><b>为什么不用全局那只 ObjectMapper：</b>
 * ① {@code spring.jackson.default-property-inclusion=non_null} 把它配成了「丢掉 null 字段」。
 * 在响应体里这是好事，在审计快照里是灾难 —— 「这个字段本来就是空的」和「这个字段根本没被记录」
 * 是两件完全不同的事，而丢掉之后它们在日志里长得一模一样，事后追责时后者什么也证明不了。
 * ② 这里要序列化的是 JdbcTemplate 出来的行 Map，值可能是 BigInteger（BIGINT UNSIGNED）、
 * LocalDateTime、byte[]，裸 ObjectMapper 对它们要么抛异常要么渲染成另一种形状。
 * ③ {@code audit_log.before_json} 是 JSON 列，写进去的字符串不是合法 JSON 会被 MySQL 以
 * 错误 3140 拒绝，而那次拒绝会把整个改动一起回滚 —— 运营看到的是一个莫名其妙的失败，
 * 而真正的原因是审计序列化器吐了一段坏字符串。所以这里宁可自己写死，保证永远合法。
 */
final class AdminJson {

    private AdminJson() {}

    /** 成对的 key/value 拼成有序 Map。有序是为了让同一个动作的两次快照能逐行肉眼对比。 */
    static Map<String, Object> map(Object... kv) {
        if (kv.length % 2 != 0)
            throw new IllegalArgumentException("审计快照的键值个数不成对：" + kv.length);
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    /** 响应体统一 {"ok":true, ...}。允许 null 值（Map.of 不允许，那会逼调用方到处写分支）。 */
    static Map<String, Object> ok(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.putAll(map(kv));
        return m;
    }

    static String obj(Map<String, ?> m) {
        if (m == null) return null;
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, ?> e : m.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            quote(sb, e.getKey());
            sb.append(':');
            value(sb, e.getValue());
        }
        return sb.append('}').toString();
    }

    private static void value(StringBuilder sb, Object v) {
        if (v == null) { sb.append("null"); return; }
        if (v instanceof Boolean b) { sb.append(b ? "true" : "false"); return; }
        if (v instanceof Number n) {
            double d = n.doubleValue();
            // NaN / Infinity 不是合法 JSON。直接拼进去会让整条审计被 MySQL 拒绝，
            // 于是那次业务改动跟着一起回滚 —— 一个数值边界把正常操作打挂。
            if (Double.isNaN(d) || Double.isInfinite(d)) { quote(sb, String.valueOf(n)); return; }
            sb.append(n);
            return;
        }
        // byte[]（如 code_enc）绝不能进审计：那是密文本身，审计日志的可见面比它宽得多
        if (v instanceof byte[] b) { quote(sb, "<" + b.length + " bytes>"); return; }
        quote(sb, String.valueOf(v));
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    // 控制字符必须转义成 6 字符的 uXXXX 形式，直接写进去是非法 JSON
                    // （注释里也不能写出那个转义序列本身 —— Java 的 unicode 转义
                    //   在词法分析之前处理，连注释里的都算数，写了就编译不过）
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }
}
