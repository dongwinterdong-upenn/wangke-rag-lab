package tech.dragonai.wangke.learn;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tech.dragonai.wangke.web.ApiException;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 权益闸门。学员端每一条取数路径的第一道判定，也是<b>唯一</b>一道。
 *
 * <h3>为什么判定写在 SQL 的 WHERE 里，而不是「先按 id 查出来再比 owner」</h3>
 * 先查后比的写法有两个必然的演化终点，两个都很难在 code review 里看出来：
 * <ul>
 *   <li>查出来的那一行，在比对失败的分支上仍然<b>已经在内存里了</b>。
 *       下一个人加个日志、加个错误提示，把 title 或 lessonId 带进响应体，
 *       接口就变成了一个可枚举全库的探针 —— 而它每一次返回的都是 403，看起来完全正常。
 *   <li>比对那一行代码是可以被忘记的。忘了之后接口工作得很好，只是谁都能调。
 * </ul>
 * 所以这里的每一条 SQL 都把「这一讲属于哪门课」「这个人有没有这门课」「过没过期」
 * 三段条件放进同一次查询的 WHERE，查不到就是查不到，手上从来没有过别人的数据。
 *
 * <h3>为什么无权益和不存在返回同一句话</h3>
 * 分开报（"课程不存在" vs "你没有权限"）等于免费提供一个课程 id 枚举器。
 * 学员端没有任何一个场景需要区分这两者 —— 两种情况下他能做的事完全一样。
 *
 * <h3>为什么用 NOW(3) 而不是应用侧的 LocalDateTime.now()</h3>
 * 有效期是「到期即断」的硬边界。应用机与数据库机的时钟偏几秒，就会出现
 * 「A 机上判过期、B 机上判没过期」的分裂，而分裂不会报错，只会让同一个学员
 * 刷新一下就又能播了。判定与存储用同一个时钟源，这种分裂在结构上不存在。
 */
@Component
public class Entitlements {

    /** 无权益与课程不存在共用这一句。措辞里不含任何能反推课程是否存在的信息。 */
    public static final String DENIED = "你还没有这门课的观看权限，或权限已到期";

    private final JdbcTemplate jdbc;

    public Entitlements(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Access(long enrollmentId, long courseId,
                         LocalDateTime validFrom, LocalDateTime validUntil) {}

    /**
     * 讲次级权益。
     *
     * @param hlsKey OSS 私有桶对象前缀。<b>只允许流到 m3u8 的分片 URL 拼装处，
     *               绝不允许出现在任何 JSON 响应体里</b>（schema：绝不下发直链）。
     *               它出现在这个 record 上是因为播放列表非拼不可，不是因为它可以外传。
     */
    /**
     * <b>两个布尔说的是两件事，不许合并。</b>合并过一次，代价见下。
     *
     * @param preview  <b>素材属性</b>：这一讲是试听（先导课）。试听素材按"公开物料、不带水印"
     *                 制作，磁盘上<b>没有 {@code _a/_b} 那两份</b>，上架闸门也豁免了码本与位区间。
     *                 所以「投递成什么形态」只能看它，<b>与谁在看无关</b>。
     * @param entitled <b>观看者属性</b>：他有这门课的有效权益。它决定的是设备绑定、
     *                 以及下游那些"既然放行了就说明他买了课"的推断。
     *
     * <p>第一版把这两件事合成了一个 {@code preview = 没有权益}，于是：
     * <b>已购学员点开先导课，拿到的是按 A/B 引用的播放列表，而那些分片根本不存在</b> ——
     * 每个槽位 404，hls.js 归成 NETWORK_ERROR 无限重试，画面不动且一句错都不报。
     * 同一讲对付过钱的人反而播不了，对没付钱的人正常。
     */
    public record LessonAccess(long lessonId, long courseId, String title, int durationSec,
                               int segmentCount, int slotStride, int bitOffset, String hlsKey,
                               boolean preview, boolean entitled) {}

    /** 有这门课、状态正常、在有效期内 —— 三条同时成立才返回，否则 403。 */
    public Access requireCourse(long userId, long courseId) {
        var rows = jdbc.queryForList(
                "SELECT id, course_id, valid_from, valid_until FROM enrollment "
              + "WHERE user_id=? AND course_id=? AND status='ACTIVE' "
              + "  AND valid_from <= NOW(3) AND valid_until > NOW(3)", userId, courseId);
        if (rows.isEmpty()) throw ApiException.forbidden(DENIED);
        Map<String, Object> e = rows.get(0);
        return new Access(num(e.get("id")), num(e.get("course_id")),
                (LocalDateTime) e.get("valid_from"), (LocalDateTime) e.get("valid_until"));
    }

    /**
     * 讲次级权益。讲次 → 课程 → 权益三段在同一条 SQL 里判完。
     *
     * <p>{@code l.status='ON'} 也在 WHERE 里而不是查出来再判：草稿 / 已下架的讲次
     * 对学员而言就该等同于不存在，包括「不能通过它确认这一讲存在」。
     */
    public LessonAccess requireLesson(long userId, long lessonId) {
        // 两条放行理由，一条 SQL 判完：① 有这门课的有效权益；② 这一讲是试听。
        // LEFT JOIN + HAVING 式的写法（把权益判定放进 e.id IS NOT NULL）比"查两次"好，
        // 理由和 requireCourse 一样：判定与取数在同一次查询里，
        // 中间没有"权益刚好在这两句之间到期"的缝。
        var rows = jdbc.queryForList(
                "SELECT l.id, l.course_id, l.title, l.duration_sec, l.segment_count, "
              + "       l.wm_slot_stride, l.wm_bit_offset, l.hls_key, l.is_preview, "
              + "       e.id AS enrollment_id "
              + "  FROM lesson l "
              + "  LEFT JOIN enrollment e ON e.course_id = l.course_id "
              + "   AND e.user_id = ? AND e.status = 'ACTIVE' "
              + "   AND e.valid_from <= NOW(3) AND e.valid_until > NOW(3) "
              + " WHERE l.id = ? AND l.status = 'ON' "
              + "   AND (e.id IS NOT NULL OR l.is_preview = 1)", userId, lessonId);
        if (rows.isEmpty()) throw ApiException.forbidden(DENIED);
        Map<String, Object> l = rows.get(0);
        boolean preview = flag(l.get("is_preview"));     // 素材是不是试听
        boolean entitled = l.get("enrollment_id") != null;  // 他有没有权益
        return new LessonAccess(num(l.get("id")), num(l.get("course_id")), (String) l.get("title"),
                i(l.get("duration_sec")), i(l.get("segment_count")),
                i(l.get("wm_slot_stride")), i(l.get("wm_bit_offset")), (String) l.get("hls_key"),
                preview, entitled);
    }

    /**
     * TINYINT(1) 在 Connector/J 里回来的是 <b>Boolean</b>，不是数字
     * （连接串默认 {@code tinyInt1isBit=true}）—— 直接走 {@link #i} 会 ClassCastException，
     * 而且它只在真正读到那一列的那条路上炸，别的用例照样绿。
     * 两种都认，因为同一列在别的驱动/别的连接参数下确实会回数字。
     */
    static boolean flag(Object v) {
        if (v instanceof Boolean b) return b;
        return v instanceof Number n && n.intValue() != 0;
    }

    /** BIGINT UNSIGNED 在 Connector/J 里是 BigInteger 不是 Long，直接强转会 ClassCastException。 */
    static long num(Object v) { return ((Number) v).longValue(); }
    static int i(Object v) { return ((Number) v).intValue(); }
}
