package tech.dragonai.wangke.admin;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tech.dragonai.wangke.protect.M3u8Builder;
import tech.dragonai.wangke.web.ApiException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 讲次「上架前自检」。
 *
 * <h3>这一层是什么，不是什么</h3>
 * <b>它不是闸门。</b>闸门是 {@code trg_lesson_publish_gate_ins/upd}，在库里，不管谁从哪条路
 * 把 {@code lesson.status} 改成 ON 都得过。这一层做的是另一件事：<b>把闸门会拒绝的理由提前算一遍，
 * 并且一次性把全部问题都列出来</b>。
 *
 * <p>为什么值得单独写：触发器是「遇到第一个不满足就 SIGNAL」，运营改完一项再点一次上架，
 * 又被下一项挡住，改五项要来回八趟，而每一趟他都不知道后面还有几关。自检一次列全，
 * 他能一次改完。
 *
 * <p><b>自检通过不等于上架一定成功</b>，这是有意的、也是必须接受的：
 * 自检与上架之间隔着一次往返，这中间别人可能改了同课程另一讲的位区间，
 * 于是重叠判据的结论就变了。真正的裁决权只能在库里，只有它是在同一把锁下求值的。
 * 所以上架路径绝不会写成「自检过了就直接 UPDATE」——那等于把闸门搬到应用层再宣称库里也有一道。
 *
 * <p>判据逐条对齐 {@code sp_lesson_publish_gate}，<b>包括顺序</b>：顺序不一致时，
 * 自检说的「第一个问题」和触发器拒绝时说的那句会指向不同的东西，运营会以为系统在自相矛盾。
 */
@Service
public class LessonPublishCheck {

    private final JdbcTemplate jdbc;

    public LessonPublishCheck(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /**
     * @param fix 差什么、该怎么补。null 表示这项已通过
     */
    public record Item(String item, boolean pass, String detail, String fix) {}

    public record Result(long lessonId, String title, boolean previewExempt,
                         boolean publishable, List<Item> items) {

        /** 未通过项拼成一句话，用来贴在触发器拒绝的后面。 */
        public String failureHint() {
            List<String> bad = new ArrayList<>();
            for (Item i : items) if (!i.pass()) bad.add(i.item() + "：" + i.fix());
            return bad.isEmpty()
                    ? "自检当前全部通过 —— 说明是刚刚有人改了同课程另一讲的水印布局，请刷新后重试"
                    : "还差 " + bad.size() + " 项：" + String.join("；", bad);
        }
    }

    /** 区间列表的一行人话，如 {@code [0,267) [300,410)}。 */
    private static String describe(List<Map<String, Object>> rows) {
        List<String> out = new ArrayList<>();
        for (var b : rows) out.add("[" + b.get("seg_from") + "," + b.get("seg_to") + ")");
        return String.join(" ", out);
    }

    public Result of(long lessonId) {
        var rows = jdbc.queryForList(
                "SELECT l.id, l.course_id, l.chapter_id, l.title, l.status, l.is_preview,"
              + " l.segment_count, l.wm_slot_stride, l.wm_slot_count, l.wm_bit_offset,"
              + " l.wm_bit_base, l.hls_key, ch.hls_key AS ch_key, ch.wm_bit_base AS ch_base,"
              + " ch.store_segment_count FROM lesson l"
              + " LEFT JOIN chapter ch ON ch.id = l.chapter_id WHERE l.id=?", lessonId);
        if (rows.isEmpty()) throw ApiException.notFound("讲次不存在");
        Map<String, Object> l = rows.get(0);

        long courseId = AdminQuery.longOf(l.get("course_id"), 0);
        int segs = AdminQuery.intOf(l.get("segment_count"), 0);
        int stride = AdminQuery.intOf(l.get("wm_slot_stride"), 0);
        int slots = AdminQuery.intOf(l.get("wm_slot_count"), 0);
        int offset = AdminQuery.intOf(l.get("wm_bit_offset"), 0);
        boolean preview = AdminQuery.intOf(l.get("is_preview"), 0) == 1;
        String title = (String) l.get("title");

        List<Item> items = new ArrayList<>();
        if (preview) {
            // 试听片段是公开物料，本来就不带水印，【水印那几条】对它豁免。
            //
            // 但原来这里是 return —— 把"能不能播"的三条也一起豁免掉了：
            // 已切片、有内容密钥、以及本讲到底存不存在可投递的分片。
            // 于是一条 0 切片、无密钥的试听讲能顺利上架，自检还打绿灯，
            // 而学员点开只拿到一句 409。豁免的是水印，不是可播性。
            items.add(new Item("试听片段豁免水印", true,
                    "本讲是试听片段（is_preview=1），是公开物料，按设计不带水印："
                            + "不要求码本、不要求位区间，投递走不带 A/B 的播放列表", null));
            items.add(new Item("已切片", segs > 0,
                    "segment_count = " + segs,
                    segs > 0 ? null : "试听讲同样要先切片：0 片上架之后目录里看得见、点开是空的"));
            Integer pk = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM lesson_content_key WHERE lesson_id=? AND status='ACTIVE'",
                    Integer.class, lessonId);
            boolean pKey = pk != null && pk == 1;
            items.add(new Item("内容密钥封装", pKey,
                    pKey ? "已有且仅有 1 把现行 AES-128 内容密钥"
                         : "没有 status=ACTIVE 的 lesson_content_key",
                    pKey ? null : "试听同样是加密投递的（加密与水印是两件事，不一起豁免）。"
                            + "缺密钥时目录能打开，播放器到取密钥那一步才失败"));
            boolean ok = items.stream().allMatch(Item::pass);
            return new Result(lessonId, title, ok, ok, items);
        }

        items.add(new Item("已切片", segs > 0,
                "segment_count = " + segs,
                segs > 0 ? null : "讲次还没切片；先把视频切成 HLS 分片并回填 segment_count。"
                        + "0 片上架等于从这一刻起投递出去的每一份副本都没有水印，而且补不回来"));

        Integer keyCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM lesson_content_key WHERE lesson_id=? AND status='ACTIVE'",
                Integer.class, lessonId);
        boolean hasContentKey = keyCount != null && keyCount == 1;
        items.add(new Item("内容密钥封装", hasContentKey,
                hasContentKey ? "已有且仅有 1 把现行 AES-128 内容密钥"
                              : "没有 status=ACTIVE 的 lesson_content_key",
                hasContentKey ? null : "先让内容入库流水线生成并封存本讲密钥，"
                        + "再用同一把密钥加密 HLS 分片。缺密钥时目录仍能打开，"
                        + "但播放器到取密钥时才会失败"));

        items.add(new Item("取样步长", stride > 0,
                "wm_slot_stride = " + stride,
                stride > 0 ? null : "把 wm_slot_stride 设为正数（默认 6，即每 6 片取 1 片做 A/B 双版本）；"
                        + "步长为 0 时本讲一个槽位都没有"));

        // ── 章即片库 ────────────────────────────────────────────────────
        // 一场录屏切成四五讲之后，片库目录与码位基点归【章】。讲次上那两列是冗余，
        // 对不上就是"去另一个目录取到同名但内容不同的片"或者"整段码字错位"。
        String chKey = (String) l.get("ch_key");
        int store = AdminQuery.intOf(l.get("store_segment_count"), 0);
        int chBase = AdminQuery.intOf(l.get("ch_base"), 0);
        int base = AdminQuery.intOf(l.get("wm_bit_base"), 0);
        String myKey = (String) l.get("hls_key");
        boolean chOk = chKey != null && store > 0;
        items.add(new Item("所在章已登记片库", chOk,
                chOk ? "片库 " + chKey + "，共 " + store + " 片，码位基点 " + chBase
                     : "本讲没有挂在章下，或该章还没登记 hls_key / store_segment_count",
                chOk ? null : "先给章补上 hls_key 与 store_segment_count（片库目录里的分片总数）。"
                        + "槽位容量按它算，缺了就无从判断本讲的码位对不对"));

        boolean keyMatch = chOk && chKey.equals(myKey);
        items.add(new Item("片库目录与章一致", keyMatch,
                "讲次 hls_key = " + myKey + "，章 hls_key = " + chKey,
                keyMatch ? null : "把讲次的 hls_key 改成 " + chKey + "。对不上会去另一个目录取片，"
                        + "那里同名的片是别的内容 —— 播出来是另一节课，而这不会报错"));

        boolean baseMatch = chOk && base == chBase;
        items.add(new Item("码位基点与章一致", baseMatch,
                "讲次 wm_bit_base = " + base + "，章 wm_bit_base = " + chBase,
                baseMatch ? null : "重新分配位区间（WatermarkService.assignBitOffsets）。"
                        + "基点对不上，投递侧算出的码位会整体错位，而错位的取证不报错"));

        // ── 分片区间 ────────────────────────────────────────────────────
        var blockRows = jdbc.queryForList(
                "SELECT seg_from, seg_to FROM lesson_segment_block WHERE lesson_id=? ORDER BY sort_no",
                lessonId);
        boolean hasBlocks = !blockRows.isEmpty();
        items.add(new Item("已分配分片区间", hasBlocks,
                hasBlocks ? blockRows.size() + " 段：" + describe(blockRows) : "lesson_segment_block 为空",
                hasBlocks ? null : "先给本讲灌 lesson_segment_block（它在片库里占哪几段片号）。"
                        + "区间为空时目录里看得见、点开是空的"));

        int blockSegs = 0;
        List<M3u8Builder.Block> blocks = new ArrayList<>();
        for (var b : blockRows) {
            int f = AdminQuery.intOf(b.get("seg_from"), 0), t = AdminQuery.intOf(b.get("seg_to"), 0);
            if (t > f) { blocks.add(new M3u8Builder.Block(f, t)); blockSegs += t - f; }
        }
        boolean segsMatch = hasBlocks && blockSegs == segs;
        items.add(new Item("切片数与区间对账", segsMatch,
                "segment_count = " + segs + "，区间合计 " + blockSegs + " 片",
                segsMatch ? null : "把 segment_count 改成 " + blockSegs + "。取片限速的阈值按它算，"
                        + "对不上会让闸门看起来在工作，其实拦的是错的线"));

        // 槽位数与首个码位都按【区间实算】，不用 segment_count / stride：
        // 区间长度不是步长整数倍时两者会差 1，而差 1 就是整段码字错位一位。
        // 生产上还有更细的一条：片库尾部那个不完整步长里的片即便落在中点也不是槽位
        // （fde/l7 第 1059 片磁盘上只有 1059.ts），所以要带上片库总数做上界。
        Integer expectSlots = null, expectOffset = null;
        if (chOk && stride > 0 && !blocks.isEmpty()) {
            var probe = new M3u8Builder.Lesson(lessonId, blocks, 1, stride, chBase, store, chKey);
            expectSlots = probe.slotCount();
            expectOffset = probe.bitOffset();
        }
        boolean slotOk = expectSlots != null && slots == expectSlots;
        items.add(new Item("槽位数对账", slotOk,
                "wm_slot_count = " + slots + "，按区间实算应为 "
                        + (expectSlots == null ? "（算不出：先补齐上面几项）" : expectSlots),
                slotOk ? null : "重新分配位区间（WatermarkService.assignBitOffsets）。对不上说明布局是按旧切法算的，"
                        + "按旧布局投递会让码字位与实际分片错位 —— 错位的取证不会失败，只会指认到别人"));

        boolean offsetOk = expectOffset != null && expectOffset >= 0 && offset == expectOffset;
        items.add(new Item("首个码位对账", offsetOk,
                "wm_bit_offset = " + offset + "，按区间实算应为 "
                        + (expectOffset == null ? "（算不出）" : expectOffset),
                offsetOk ? null : (expectOffset != null && expectOffset < 0
                        ? "本讲一个水印槽位都没有：区间太短或整段落在片库尾部，投出去的副本无法取证"
                        : "重新分配位区间。对不上时取证会从错的位读起，指认到别人身上")));

                var scheme = jdbc.queryForList(
                "SELECT id, bit_length FROM wm_scheme WHERE course_id=? AND status='ACTIVE'", courseId);
        boolean hasScheme = !scheme.isEmpty();
        int bitLength = hasScheme ? AdminQuery.intOf(scheme.get(0).get("bit_length"), 0) : 0;
        items.add(new Item("现行水印码本", hasScheme,
                hasScheme ? "码本 #" + AdminQuery.id(scheme.get(0).get("id")) + "，码长 " + bitLength + " 位"
                          : "本课程没有 status=ACTIVE 的 wm_scheme",
                hasScheme ? null : "先给课程建立现行码本（WatermarkService.ensureScheme），"
                        + "没有码本就没有码字，投递出去的副本不可取证"));

        boolean fits = hasScheme && offset + slots <= bitLength;
        items.add(new Item("位区间在码本内", fits,
                "本讲占 [" + offset + ", " + (offset + slots) + ")，码长 " + bitLength,
                fits ? null : (hasScheme
                        ? "超出码本 " + (offset + slots - bitLength) + " 位。超出的那一段取不到码字位，"
                          + "等于本讲后半程没有水印；重新分配全课程位区间，或按新的槽位容量重开码本"
                        : "码本尚未建立，无法判断")));

        Integer overlap = jdbc.queryForObject(
                // 与 sp_lesson_publish_gate 里那条逐字同构：区间相交的标准判法，
                // 且同样不看对方的 status —— DRAFT 的讲也占着位，改天它一上架就撞上了
                "SELECT COUNT(*) FROM lesson WHERE course_id=? AND id<>? AND is_preview=0"
              + " AND ? < wm_bit_offset + wm_slot_count AND wm_bit_offset < ?",
                Integer.class, courseId, lessonId, offset, offset + slots);
        boolean noOverlap = overlap == null || overlap == 0;
        items.add(new Item("位区间不与同课程其他讲重叠", noOverlap,
                noOverlap ? "无重叠" : "与 " + overlap + " 讲的位区间相交",
                noOverlap ? null : "重新分配全课程位区间。重叠的那一段等于白费的码长，"
                        + "抗合谋能力会低于设计值，而这件事本身不会有任何报错"));

        // 同一片库的各讲共用同一批分片，内容密钥必须逐字节相同。只给一讲换了密钥，
        // 另外几讲会拿错的 key 解出垃圾字节 —— 播放器报解码失败或干脆花屏，不是"密钥错"。
        Integer distinct = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT k.key_enc) FROM lesson l2"
              + " JOIN lesson_content_key k ON k.lesson_id=l2.id AND k.status='ACTIVE'"
              + " WHERE l2.chapter_id=? AND l2.is_preview=0"
              + "   AND (l2.status='ON' OR l2.id=?)",
                Integer.class, AdminQuery.longOf(l.get("chapter_id"), 0), lessonId);
        boolean keysAgree = distinct == null || distinct <= 1;
        items.add(new Item("同片库各讲密钥一致", keysAgree,
                keysAgree ? "本片库现行密钥只有 1 把" : "本片库下有 " + distinct + " 把不同的现行密钥",
                keysAgree ? null : "把同一章各讲的 lesson_content_key 统一成同一把。"
                        + "分片是共用的，密钥不同的那几讲会解出垃圾字节而不是报错"));

        boolean all = items.stream().allMatch(Item::pass);
        return new Result(lessonId, title, false, all, items);
    }
}
