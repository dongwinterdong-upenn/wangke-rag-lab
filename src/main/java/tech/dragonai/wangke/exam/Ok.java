package tech.dragonai.wangke.exam;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 成功响应体 {@code {"ok":true, ...}} 的组装。
 *
 * <p><b>为什么不用 {@code Map.of}</b>：{@code Map.of} 的值为 null 时直接抛 NPE。
 * 而这一片的正常数据里到处是 null —— 没交卷的场次没有分数、没吊销的证书没有吊销时间、
 * 冷却期没到的时候没有剩余秒数。用 {@code Map.of} 的话，最常见的那条路径
 * （学员点开一场刚开始的考试）会以 500 收场，而错误日志里只有一句
 * NullPointerException，看不出是哪个字段 —— 一个纯粹由容器选型造成的故障。
 *
 * <p>顺带保住字段顺序：{@code Map.of} 的迭代顺序是随机的，同一个接口两次响应的
 * 字段顺序不同，会让前端联调时的 diff 与接口文档的示例永远对不上。
 */
final class Ok {

    private Ok() {}

    /** kv 必须成对；奇数个是调用方写错了，当场炸而不是静默丢掉最后一个。 */
    static Map<String, Object> of(Object... kv) {
        if (kv.length % 2 != 0)
            throw new IllegalStateException("响应字段必须成对出现，实际给了 " + kv.length + " 个");
        var m = new LinkedHashMap<String, Object>();
        m.put("ok", true);
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
