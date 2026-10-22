package tech.dragonai.wangke.protect;

import java.util.ArrayList;
import java.util.List;

/**
 * 逐用户 HLS 播放列表。A/B 分片水印的投递端。
 *
 * <p><b>架构上最关键的一条：个性化只落在这份几 KB 的 m3u8 上，分片本身全员共用。</b>
 * 如果给每个学员生成一整套分片，CDN 命中率直接归零、存储乘以学员数，
 * 这套方案在几百人规模上就跑不动了。所以：
 * <ul>
 *   <li>水印槽位的那一片存两份（A/B，视觉无差别），全员共用这两份
 *   <li>某学员的 m3u8 在第 j 个槽位引用 A 还是 B，由他码字的第 j 位决定
 *   <li>非槽位的片只有一份，所有人的 m3u8 都指向它
 * </ul>
 * 于是存储只多 1/stride，CDN 照常缓存，而每个人拿到的播放序列是唯一的。
 *
 * <h3>一讲 = 片库里的一段（或几段）片号区间</h3>
 * 2026-08-21 之前，一讲等于一整场录屏，片号从 0 数到结尾。之后一场录屏被切成
 * 四五讲，讲次拿的是这个片库目录里的一段全局片号 —— 磁盘上一个文件都没改名。
 * 一讲还可以有【几段】：中间跳过的是剪掉的课间与等 AI 跑完的静音。
 *
 * <p>这带来两件必须在这里处理、否则不会报错的事：
 * <ul>
 *   <li><b>IV。</b>AES-128 的 IV 一直取"分片序号"，打包时用的是<b>全局片号</b>
 *       （{@code printf '%032x' $i}）。播放列表若不写 IV，播放器按
 *       EXT-X-MEDIA-SEQUENCE 往下推；而一讲内部允许跳段，跳段之后推出来的号就全错。
 *
 *       <p>要命的是错了<b>几乎看不出来</b>。CBC 只有第一个分组吃 IV，
 *       后面靠密文自同步，所以一片 1.5 MB 里只错开头那 2 个字节，
 *       TS 解复用器按 0x47 同步字节一对齐就过去了。2026-08-21 实测：
 *       把 IV 换成播放序号，ffmpeg 照常 exit 0，解出来的帧序列 md5
 *       与明文<b>逐帧相同</b>。正因为它不报错、不花屏、连帧都不差，
 *       才必须<b>逐片显式写死</b> —— 靠推的话没人会发现它是错的。
 *   <li><b>时间轴。</b>跳段处必须写 {@code #EXT-X-DISCONTINUITY}，
 *       否则播放器按连续时间轴对齐 PTS，拖进度条的位置从跳段之后全错。
 * </ul>
 *
 * <h3>取证要能反着走</h3>
 * 拿到泄漏样本后，必须能从"第几片用的是 A 还是 B"反推出码字。所以槽位映射必须是
 * 确定性的、可复现的，而且<b>只跟片在片库里的全局片号有关</b>：
 * {@code bit(g) = wm_bit_base + g / stride}。
 *
 * <p>上一版是按【讲次顺序】把位区间首尾相接分下去的。那样一重切课，同一片对应的位
 * 就整体挪一格，已发出的码字全部作废 —— 而作废不报错，只会让取证算出一组错分数、
 * 指认到无辜的人。正因如此，上一轮的结论是"课程拆不成更多讲次"。改成全局映射之后
 * 这条限制就没有了：怎么重切，每一片对应的位都不动。
 */
public final class M3u8Builder {

    /**
     * 对象名 → 播放列表里那一行的完整 URL。
     *
     * <p>存在的理由是<b>签名必须逐对象签</b>：整讲一张票的话，一个人拿自己的票
     * 照样能取到另一个变体，于是把 m3u8 里所有 {@code _b} 换成 {@code _a} 就能
     * 抹平自己的水印码字 —— 而抹平之后取证不报错，只是谁都指认不到。
     */
    @FunctionalInterface
    public interface SegmentUrl {
        String of(String objectName);
    }

    /** 一段连续的片号区间，{@code [from, to)}。 */
    public record Block(int from, int to) {
        public Block {
            if (from < 0 || to <= from)
                throw new IllegalArgumentException("片号区间不合法：[" + from + "," + to + ")");
        }
        public int size() { return to - from; }
    }

    /**
     * @param blocks             本讲交付的片号区间，按播放顺序、互不重叠
     * @param wmBitBase          本片库第 0 个槽位对应的码位（chapter.wm_bit_base）
     * @param storeSegmentCount  <b>片库目录里的分片总数</b>，不是本讲交付的数量。
     *        它决定槽位容量上界 {@code slot < 总数 / stride} —— 打包脚本就是按这个
     *        上界生成 _a/_b 的。照"g % stride == stride/2"直接判的话，片库尾部那个
     *        不完整步长里的片会被当成槽位（生产上 fde/l7 的第 1059 片正是如此），
     *        于是播放列表去要一个磁盘上没有的 {@code 1059_a.ts} → 404 →
     *        hls.js 归成 NETWORK_ERROR 无限重试：画面停住，一句错都不报。
     */
    public record Lesson(long lessonId, List<Block> blocks, double segSeconds,
                         int wmSlotStride, int wmBitBase, int storeSegmentCount,
                         String hlsKeyPrefix) {

        public Lesson {
            blocks = List.copyOf(blocks);
        }

        /** 片库的槽位容量上界。与打包脚本的 {@code SLOT_COUNT=SEG_COUNT/STRIDE} 同一个式子。 */
        public int slotCapacity() {
            return wmSlotStride <= 0 ? 0 : storeSegmentCount / wmSlotStride;
        }

        /** 第 g 片是不是槽位片（磁盘上有 {@code g_a.ts} / {@code g_b.ts}）。 */
        public boolean isSlot(int g) {
            return wmSlotStride > 0
                && g % wmSlotStride == wmSlotStride / 2
                && g / wmSlotStride < slotCapacity();
        }

        /** 第 g 片投递的是码字第几位。只对槽位片有意义。 */
        public int bitOf(int g) { return wmBitBase + g / wmSlotStride; }

        public int segmentCount() {
            int n = 0; for (Block b : blocks) n += b.size(); return n;
        }

        /** 本讲实际投递到的槽位片，按播放顺序。 */
        public List<Integer> slotSegments() {
            List<Integer> out = new ArrayList<>();
            for (Block b : blocks)
                for (int g = b.from(); g < b.to(); g++)
                    if (isSlot(g)) out.add(g);
            return out;
        }

        /** 本讲能承载多少位码字。 */
        public int slotCount() { return slotSegments().size(); }

        /** 本讲用到的第一位。没有槽位时返回 -1。 */
        public int bitOffset() {
            var s = slotSegments();
            return s.isEmpty() ? -1 : bitOf(s.get(0));
        }

        /** 本讲用到的最后一位之后一位。没有槽位时返回 -1。 */
        public int bitEnd() {
            var s = slotSegments();
            return s.isEmpty() ? -1 : bitOf(s.get(s.size() - 1)) + 1;
        }
    }

    /** 单块的便捷构造：整段连续交付。 */
    public static Lesson whole(long lessonId, int segmentCount, double segSeconds,
                               int stride, int bitBase, String hlsKeyPrefix) {
        return new Lesson(lessonId, List.of(new Block(0, segmentCount)), segSeconds,
                stride, bitBase, segmentCount, hlsKeyPrefix);
    }

    /** 生成播放列表。分片 URL 只拼前缀、不签名 —— 只给测试与本地调试用。 */
    public String build(Lesson lesson, boolean[] codeword, String keyUri, String segmentUrlBase) {
        return build(lesson, codeword, keyUri, obj -> segmentUrlBase + "/" + obj);
    }

    /**
     * 生成播放列表，分片 URL 交给 {@code url} 拼。
     *
     * @param codeword 该学员的<b>整门课</b>码字。本讲只用其中它自己那些位。
     *                 码字盖不住就直接报错 —— 静默截断会让后半程失去水印，
     *                 而且不会有任何症状，只有真去取证时才发现读回来的位全是 A。
     */
    public String build(Lesson lesson, boolean[] codeword, String keyUri, SegmentUrl url) {
        int end = lesson.bitEnd();
        if (end >= 0 && codeword.length < end)
            throw new IllegalArgumentException(
                    "码字只有 " + codeword.length + " 位，本讲要用到第 " + end
                    + " 位（基点 " + lesson.wmBitBase() + " + 片库内偏移）："
                    + "静默截断会让后半程失去水印，必须先用 SlotBudget 核对码长");

        StringBuilder sb = new StringBuilder();
        sb.append("#EXTM3U\n#EXT-X-VERSION:3\n")
          .append("#EXT-X-TARGETDURATION:").append((int) Math.ceil(lesson.segSeconds())).append('\n')
          .append("#EXT-X-PLAYLIST-TYPE:VOD\n")
          .append("#EXT-X-MEDIA-SEQUENCE:0\n");

        String extinf = "#EXTINF:" + String.format("%.3f", lesson.segSeconds()) + ",\n";
        int prevTo = -1;
        for (Block b : lesson.blocks()) {
            // 跳段：中间那几片是剪掉的内容（课间、等 AI 的静音）。不打这个标记的话，
            // 播放器按连续时间轴对齐 PTS，从跳段之后拖进度条的位置全是错的。
            if (prevTo >= 0 && b.from() != prevTo) sb.append("#EXT-X-DISCONTINUITY\n");
            prevTo = b.to();
            for (int g = b.from(); g < b.to(); g++) {
                String object;
                if (lesson.isSlot(g)) {
                    object = g + "_" + (codeword[lesson.bitOf(g)] ? "b" : "a") + ".ts";
                } else {
                    object = g + ".ts";
                }
                // 逐片显式写 IV。打包时用的就是【全局片号】做 IV，而本讲的第 0 片
                // 可能是全局第 267 片；不写的话播放器按播放序号推，推出来的是 0、1、2…
                sb.append("#EXT-X-KEY:METHOD=AES-128,URI=\"").append(keyUri)
                  .append("\",IV=0x").append(iv(g)).append('\n')
                  .append(extinf).append(url.of(object)).append('\n');
            }
        }
        sb.append("#EXT-X-ENDLIST\n");
        return sb.toString();
    }

    /** 片号 → 16 字节大端 IV 的十六进制。与打包脚本的 {@code printf '%032x'} 同一约定。 */
    static String iv(int segment) {
        return String.format("%032x", segment);
    }

    /**
     * 取证：把某一讲读到的槽位变体放回<b>整门课码字</b>的正确位置上。
     *
     * <p>泄漏样本通常只有一讲。那一讲只覆盖码字的一小段，其余位【读不出】——
     * 必须标成不可读而不是 0。当成 0 会给"这些位取 0"的用户白送一大片加分，
     * 而那是凭空捏造的证据，样本越短捏造得越多，最后指认到的很可能是个无辜的人。
     *
     * @param bitIndex   第 j 个读到的槽位对应码字的第几位。<b>不能假定连续</b>：
     *                   一讲内部跳过了剪掉的片段，它占的位就是有洞的。
     *                   按"起点 + j"去推的话，跳段之后每一位都落在别人的位上。
     * @param codeLength 整门课码字的总位长
     */
    public static Recovered recover(char[] variantPerSlot, int[] bitIndex, int codeLength) {
        if (variantPerSlot.length != bitIndex.length)
            throw new IllegalArgumentException(
                    "读到 " + variantPerSlot.length + " 个槽位但给了 " + bitIndex.length
                  + " 个码位：对不齐就是整段错位，而错位的打分不报错");
        boolean[] bits = new boolean[codeLength];
        boolean[] readable = new boolean[codeLength];
        for (int j = 0; j < variantPerSlot.length; j++) {
            int at = bitIndex[j];
            if (at < 0 || at >= codeLength)
                throw new IllegalArgumentException(
                        "第 " + j + " 个槽位的码位 " + at + " 超出码长 " + codeLength);
            char v = variantPerSlot[j];
            if (v == 'a' || v == 'b') { readable[at] = true; bits[at] = (v == 'b'); }
        }
        return new Recovered(bits, readable);
    }

    /** 连续位区间的便捷重载（整讲一段、没有跳段时用）。 */
    public static Recovered recover(char[] variantPerSlot, int bitOffset, int codeLength) {
        int[] idx = new int[variantPerSlot.length];
        for (int j = 0; j < idx.length; j++) idx[j] = bitOffset + j;
        if (bitOffset < 0 || bitOffset + variantPerSlot.length > codeLength)
            throw new IllegalArgumentException(
                    "槽位区间 [" + bitOffset + "," + (bitOffset + variantPerSlot.length)
                  + ") 超出码字长度 " + codeLength + "：位对不齐会让打分整体错位，指认到别人身上");
        return recover(variantPerSlot, idx, codeLength);
    }

    public record Recovered(boolean[] codeword, boolean[] readable) {
        public double readableRatio() {
            int n = 0; for (boolean b : readable) if (b) n++;
            return readable.length == 0 ? 0 : n / (double) readable.length;
        }
    }

    /** 播放列表里出现的所有分片对象名，供签名下发与预热用。 */
    public List<String> segmentObjects(Lesson lesson) {
        List<String> out = new ArrayList<>();
        for (Block b : lesson.blocks())
            for (int g = b.from(); g < b.to(); g++) {
                if (lesson.isSlot(g)) { out.add(g + "_a.ts"); out.add(g + "_b.ts"); }
                else out.add(g + ".ts");
            }
        return out;
    }
}
