package tech.dragonai.wangke.protect;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 水印的服务层：码本 → 位区间 → 签发 → 投递 → 取证。
 *
 * <p>这一层存在的理由是把三件"错了不会报错"的事变成会报错的事：
 *
 * <ol>
 *   <li><b>码本参数没落库。</b>打分公式里的 p_j 必须和签发时的逐位相同。
 *       它以前只活在内存里，进程一重启，已发出去的每一份码字都成了不可验证的字节。
 *   <li><b>码长撑不起声称的抗合谋人数。</b>槽位不够时系统照常发码字、照常打分，
 *       只是谁也抓不到。这里在建码本时就把它变成一次拒绝。
 *   <li><b>位区间在码字发出之后被改动。</b>改一次，之前所有副本的取证全部错位，
 *       而错位的打分不会失败，只会指认到别人身上。
 * </ol>
 */
public class WatermarkService {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final SecureRandom rnd = new SecureRandom();
    private final M3u8Builder m3u8 = new M3u8Builder();

    public WatermarkService(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc; this.tx = tx;
    }

    public record Scheme(long id, long courseId, int maxColluders, int usersPlanned,
                         double eps1, int bitLength, double[] bias) {

        public TardosCode code() { return TardosCode.of(bias, maxColluders); }

        /** 全长样本的指认阈值。 */
        public double threshold() { return TardosCode.threshold(maxColluders, usersPlanned, eps1); }

        /** 残缺样本的指认阈值。泄漏往往只有一讲，这才是常用的那个。 */
        public double threshold(double readableRatio) {
            return TardosCode.thresholdForPartial(maxColluders, usersPlanned, eps1, readableRatio);
        }
    }

    // ------------------------------------------------------------------ 码本

    /**
     * 建立（或取回）一门课的现行码本。
     *
     * <p><b>会因为"槽位装不下"而拒绝。</b>抗合谋所需码长随合谋人数平方增长，
     * 而槽位数是视频时长除出来的硬上限，两边很容易对不上。
     * 对不上时不能默默缩短码长 —— 缩短之后漏判率不会有任何提示，
     * 系统看起来一切正常，只是永远抓不到人。要么调小 c、要么调小步长（拿存储换）。
     */
    public Scheme ensureScheme(long courseId, int maxColluders, int usersPlanned, double eps1) {
        Scheme existing = activeSchemeOrNull(courseId);
        if (existing != null) return existing;

        int capacity = totalSlotCapacity(courseId);
        int need = TardosCode.suggestedLength(maxColluders, usersPlanned, eps1);
        if (capacity <= 0)
            throw new IllegalStateException("课程 " + courseId + " 还没有可用的水印槽位："
                    + "先给每一讲录入 segment_count 与 wm_slot_stride");
        if (need > capacity) {
            int canDo = SlotBudget.maxColluders(capacity, usersPlanned, eps1);
            throw new IllegalStateException(
                    "槽位装不下：抗 " + maxColluders + " 人合谋需要 " + need + " 位，"
                  + "本课程只有 " + capacity + " 个槽位（最多支持 " + canDo + " 人）。"
                  + "拒绝缩短码长 —— 缩短之后漏判率不会有任何提示，系统照常运行但永远抓不到人。"
                  + "要么把 c 降到 " + canDo + "，要么把 wm_slot_stride 调小（拿存储换槽位）");
        }

        // 码长取【槽位容量】而不是 suggestedLength 的最小值。两个理由：
        //
        // ① 用不满就会漏讲。位区间按容量首尾相接分配，若码字只有最小长度，
        //    排在后面的讲次整段落在码字之外 —— 那几讲投递出去的副本一位水印都没有，
        //    而 build() 只在越界时才报错，正好越界的讲次才炸，没越界的安静地少了水印。
        // ② 多出来的位是白赚的。指认阈值 Z = 20c·ln(n/eps1) 与码长无关，
        //    码越长分数分离得越开，误判漏判都只会更低。存储代价已经付过了（槽位就在那儿）。
        TardosCode code = TardosCode.createWithLength(capacity, maxColluders, rnd);
        double[] bias = code.bias();
        try {
            jdbc.update("INSERT INTO wm_scheme (course_id,max_colluders,users_planned,eps1,"
                      + "bit_length,bias) VALUES (?,?,?,?,?,?)",
                    courseId, maxColluders, usersPlanned, eps1, bias.length, packBias(bias));
        } catch (DuplicateKeyException e) {
            return activeScheme(courseId);          // 并发建库，别人先建成了
        }
        return activeScheme(courseId);
    }

    public Scheme activeScheme(long courseId) {
        Scheme s = activeSchemeOrNull(courseId);
        if (s == null) throw new IllegalStateException("课程 " + courseId + " 没有现行水印码本");
        return s;
    }

    private Scheme activeSchemeOrNull(long courseId) {
        var rows = jdbc.queryForList(
                "SELECT id,course_id,max_colluders,users_planned,eps1,bit_length,bias "
              + "FROM wm_scheme WHERE course_id=? AND status='ACTIVE'", courseId);
        if (rows.isEmpty()) return null;
        var r = rows.get(0);
        int bitLength = ((Number) r.get("bit_length")).intValue();
        double[] bias = unpackBias((byte[]) r.get("bias"), bitLength);
        return new Scheme(((Number) r.get("id")).longValue(),
                ((Number) r.get("course_id")).longValue(),
                ((Number) r.get("max_colluders")).intValue(),
                ((Number) r.get("users_planned")).intValue(),
                ((Number) r.get("eps1")).doubleValue(), bitLength, bias);
    }

    /**
     * 全课程槽位总数 = Σ 每个<b>片库</b> store_segment_count / stride。
     *
     * <p><b>按片库算，不按讲次交付了多少算。</b>这是 2026-08-21 那次重切的关键：
     * 一场录屏切成四五讲之后，剪掉的课间与静音不属于任何一讲，
     * 按"各讲交付量之和"算容量的话，每重切一次容量就变，位就得整体挪 ——
     * 而挪一次，已发出的所有码字在取证时全部错位，且错位的打分不报错。
     * 按片库算，容量是"磁盘上有多少片"，与怎么切课无关。
     *
     * <p>试听片段不计入，因为 {@link #assignBitOffsets} 也不给它分配位区间
     * （它是公开物料，本来就不带水印）。两处的口径必须一致。
     */
    public int totalSlotCapacity(long courseId) {
        Integer n = jdbc.queryForObject(
                "SELECT COALESCE(SUM(FLOOR(c.store_segment_count / s.stride)),0) FROM ("
              + "  SELECT chapter_id, MIN(wm_slot_stride) AS stride FROM lesson"
              + "   WHERE course_id=? AND is_preview=0 AND wm_slot_stride>0 GROUP BY chapter_id) s"
              + "  JOIN chapter c ON c.id = s.chapter_id AND c.store_segment_count > 0",
                Integer.class, courseId);
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------------ 位区间

    public record Layout(long lessonId, int bitOffset, int slotCount) {}

    /**
     * 分配位区间。
     *
     * <h3>真正不许动的是「片库基点」，不是「每讲的偏移」</h3>
     * 码位是片库内全局片号的纯函数：{@code bit(g) = chapter.wm_bit_base + g / stride}。
     * 所以只要各片库的基点与容量不变，怎么重切课、每讲拿哪几段片，
     * 每一片对应的码位都<b>逐位不动</b>，已发出的码字继续有效。
     *
     * <p>上一版把整件事挂在"讲次顺序"上，于是任何重切都会让位整体挪一格，
     * 已发副本当场作废 —— 而作废不报错，只会让取证算出一组错分数、指认到无辜的人。
     * 因此上一版一旦发出码字就整个拒绝重新分配，课程结构从此冻死。
     * 现在拒绝的判据收窄到<b>基点或容量真的变了</b>那一种。
     */
    public List<Layout> assignBitOffsets(long courseId) {
        return tx.execute(st -> {
            Integer issued = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM wm_codeword WHERE course_id=?", Integer.class, courseId);
            boolean frozen = issued != null && issued > 0;

            var chapters = jdbc.queryForList(
                    "SELECT ch.id, ch.wm_bit_base, ch.store_segment_count, "
                  + "       (SELECT MIN(l.wm_slot_stride) FROM lesson l "
                  + "         WHERE l.chapter_id=ch.id AND l.is_preview=0 AND l.wm_slot_stride>0) AS stride "
                  + "  FROM chapter ch WHERE ch.course_id=? ORDER BY ch.sort_no, ch.id", courseId);

            int base = 0;
            for (var c : chapters) {
                if (c.get("stride") == null) continue;              // 空章：还没有正片
                int stride = ((Number) c.get("stride")).intValue();
                int store = ((Number) c.get("store_segment_count")).intValue();
                int stored = ((Number) c.get("wm_bit_base")).intValue();
                long chId = ((Number) c.get("id")).longValue();
                if (store <= 0)
                    throw new IllegalStateException(
                            "章 " + chId + " 还没登记片库分片总数（store_segment_count）："
                          + "槽位容量无从算起，分出来的位区间会与磁盘上的分片对不上");
                if (frozen && stored != base)
                    throw new IllegalStateException(
                            "课程 " + courseId + " 已经发出 " + issued + " 份码字，"
                          + "而章 " + chId + " 的码位基点要从 " + stored + " 改成 " + base + "："
                          + "基点一动，之前所有副本在取证时整体错位，而错位的打分不报错，只会指认到别人。"
                          + "片库的分片总数变过（重新切片、加了一章）就必须重开码本，不能沿用旧的");
                jdbc.update("UPDATE chapter SET wm_bit_base=? WHERE id=?", base, chId);
                base += store / stride;
            }

            var lessons = jdbc.queryForList(
                    "SELECT l.id, l.wm_slot_stride, ch.wm_bit_base, ch.store_segment_count "
                  + "  FROM lesson l JOIN chapter ch ON ch.id = l.chapter_id "
                  + " WHERE l.course_id=? AND l.is_preview=0 ORDER BY l.sort_no, l.id", courseId);
            List<Layout> out = new ArrayList<>();
            for (var l : lessons) {
                long id = ((Number) l.get("id")).longValue();
                var lesson = new M3u8Builder.Lesson(id, blocksOf(id), 1,
                        ((Number) l.get("wm_slot_stride")).intValue(),
                        ((Number) l.get("wm_bit_base")).intValue(),
                        ((Number) l.get("store_segment_count")).intValue(), "");
                int offset = lesson.bitOffset(), slots = lesson.slotCount();
                if (offset < 0)
                    throw new IllegalStateException(
                            "讲次 " + id + " 一个水印槽位都没有：投出去的副本无法取证");
                jdbc.update("UPDATE lesson SET wm_bit_offset=?, wm_slot_count=?, "
                          + "wm_bit_base=?, segment_count=? WHERE id=?",
                        offset, slots, lesson.wmBitBase(), lesson.segmentCount(), id);
                out.add(new Layout(id, offset, slots));
            }

            // 码本是在建库那一刻按当时的容量定死的。之后重新切过片、加了一章，
            // 容量就和码长对不上了，而这件事本身不会有任何提示。
            // 多出来 → 排在后面的片库整段落在码字之外，那几讲一位水印都没有；
            // 少了   → 有一段码长永远不会被投递，白付了抗合谋的账。
            Scheme scheme = activeScheme(courseId);
            if (base != scheme.bitLength())
                throw new IllegalStateException(
                        "片库槽位容量合计 " + base + " 与码长 " + scheme.bitLength() + " 对不上（差 "
                      + (base - scheme.bitLength()) + " 位）："
                      + (base > scheme.bitLength()
                         ? "排在后面的片库会整段落在码字之外，那几讲一位水印都没有"
                         : "有一段码长永远不会被投递出去，抗合谋的账白付了")
                      + "。片库重新切过片就要重开码本，不能沿用旧的");
            return out;
        });
    }

    /** 某讲交付的片号区间，按播放顺序。 */
    private List<M3u8Builder.Block> blocksOf(long lessonId) {
        var rows = jdbc.queryForList(
                "SELECT seg_from, seg_to FROM lesson_segment_block "
              + "WHERE lesson_id=? ORDER BY sort_no", lessonId);
        if (rows.isEmpty())
            throw new IllegalStateException(
                    "讲次 " + lessonId + " 还没有分片区间（lesson_segment_block 为空），无法投递");
        List<M3u8Builder.Block> out = new ArrayList<>();
        for (var r : rows)
            out.add(new M3u8Builder.Block(((Number) r.get("seg_from")).intValue(),
                                          ((Number) r.get("seg_to")).intValue()));
        return out;
    }

    // ------------------------------------------------------------------ 签发

    /**
     * 取（必要时签发）某学员在某课程的码字。按唯一键幂等：
     * 同一个人重复调用永远拿到同一份，否则同一个人会有两份码字，
     * 取证时两份都不完全匹配，分数被摊薄到阈值以下 —— 抓不到，且查不出为什么。
     */
    public boolean[] codewordFor(long userId, long courseId) {
        Scheme scheme = activeScheme(courseId);
        var existing = readCodeword(userId, courseId, scheme);
        if (existing != null) return existing;

        boolean[] cw = scheme.code().issue(rnd);
        try {
            jdbc.update("INSERT INTO wm_codeword (user_id,course_id,scheme_id,bits,bit_length) "
                      + "VALUES (?,?,?,?,?)",
                    userId, courseId, scheme.id(), packBits(cw), cw.length);
        } catch (DuplicateKeyException e) {
            return readCodeword(userId, courseId, scheme);       // 并发签发，用先落库的那份
        }
        return cw;
    }

    private boolean[] readCodeword(long userId, long courseId, Scheme scheme) {
        var rows = jdbc.queryForList(
                "SELECT scheme_id, bits, bit_length FROM wm_codeword WHERE user_id=? AND course_id=?",
                userId, courseId);
        if (rows.isEmpty()) return null;
        var r = rows.get(0);
        Object sid = r.get("scheme_id");
        if (sid == null || ((Number) sid).longValue() != scheme.id())
            throw new IllegalStateException(
                    "学员 " + userId + " 的码字是按另一套码本发的（" + sid + " ≠ " + scheme.id()
                  + "）。跨码本打分算出来的分数是错的，且不会失败 —— 必须按原码本取证");
        int len = ((Number) r.get("bit_length")).intValue();
        return unpackBits((byte[]) r.get("bits"), len);
    }

    // ------------------------------------------------------------------ 投递

    /** 某学员某讲的逐用户播放列表。分片 URL 只拼前缀、不签名 —— 只给测试与本地调试用。 */
    public String playlist(long userId, long lessonId, String keyUri, String cdnBase) {
        return playlist(userId, lessonId, keyUri, cdnBase, false);
    }

    /** 同上，带试听开关。 */
    public String playlist(long userId, long lessonId, String keyUri, String cdnBase,
                           boolean preview) {
        return playlist(userId, lessonId, keyUri, obj -> cdnBase + "/" + obj, preview);
    }

    /**
     * @param preview 这一次投递是<b>试听</b>（先导课，观看者没有权益）。
     *        走不带 A/B 的直白播放列表：所有分片都引用 {@code {i}.ts}，
     *        既不取码字，也不引用 {@code {i}_a.ts / {i}_b.ts}。
     *
     *        <p>这不是"给试听省一道工序"，而是<b>与素材的实际形态对齐</b>：
     *        上架闸门（{@code sp_lesson_publish_gate}）对 is_preview=1 的讲次
     *        整段豁免，也就是说试听讲次<b>不要求有码本、不要求分配位区间</b>，
     *        素材是按"公开物料、不带水印"制作的 —— 磁盘上根本没有 _a/_b 那两份。
     *        原来的投递侧完全不看 is_preview，照样按 stride 生成 A/B 引用，
     *        于是一条正常制作的试听讲播起来会在每个槽位上 404，
     *        而 hls.js 把它归成 NETWORK_ERROR 无限重试：<b>画面不动，一句错都不报。</b>
     */
    public String playlist(long userId, long lessonId, String keyUri,
                           M3u8Builder.SegmentUrl url, boolean preview) {
        var l = lesson(lessonId);
        if (preview) {
            var lesson = toLesson(l);
            // stride=0 ⇒ isSlot() 恒 false ⇒ 全部走 {g}.ts。
            // 码字传一个空数组即可：build 只在有槽位时才读它。
            var plain = new M3u8Builder.Lesson(lesson.lessonId(), lesson.blocks(),
                    lesson.segSeconds(), 0, 0, lesson.storeSegmentCount(), lesson.hlsKeyPrefix());
            return m3u8.build(plain, new boolean[0], keyUri, url);
        }
        long courseId = ((Number) l.get("course_id")).longValue();
        boolean[] cw = codewordFor(userId, courseId);
        return m3u8.build(toLesson(l), cw, keyUri, url);
    }

    private Map<String, Object> lesson(long lessonId) {
        // 片库的两个数（总片数、码位基点）取自【章】。取自讲次那份冗余列也行，
        // 但投递是唯一真正用到它们的地方，从源头取，冗余列错了也伤不到播放。
        var rows = jdbc.queryForList(
                "SELECT l.id, l.course_id, l.segment_count, l.wm_slot_stride, l.hls_key, "
              + "       l.duration_sec, ch.wm_bit_base, ch.store_segment_count "
              + "  FROM lesson l JOIN chapter ch ON ch.id = l.chapter_id WHERE l.id=?", lessonId);
        if (rows.isEmpty()) throw new IllegalArgumentException("讲次不存在（或没有挂在章下）：" + lessonId);
        return rows.get(0);
    }

    private M3u8Builder.Lesson toLesson(Map<String, Object> l) {
        long id = ((Number) l.get("id")).longValue();
        int segs = ((Number) l.get("segment_count")).intValue();
        int dur = ((Number) l.get("duration_sec")).intValue();
        if (segs <= 0) throw new IllegalStateException(
                "讲次 " + id + " 还没切片（segment_count=0），无法投递");
        var blocks = blocksOf(id);
        int actual = 0; for (var b : blocks) actual += b.size();
        // segment_count 是取片限速阈值的分母，也是"这一讲有多长"的另一个来源。
        // 与区间对不上时限速阈值会悄悄偏掉 —— 闸门看起来在工作，其实拦的是错的线。
        if (actual != segs) throw new IllegalStateException(
                "讲次 " + id + " 的 segment_count=" + segs + " 与分片区间合计 " + actual + " 对不上");
        return new M3u8Builder.Lesson(id, blocks, dur / (double) segs,
                ((Number) l.get("wm_slot_stride")).intValue(),
                ((Number) l.get("wm_bit_base")).intValue(),
                ((Number) l.get("store_segment_count")).intValue(), (String) l.get("hls_key"));
    }

    // ------------------------------------------------------------------ 取证

    public record Forensics(int readableBits, double readableRatio, double threshold,
                            List<TardosCode.Accusation> accused) {}

    /**
     * 从一份泄漏副本指认。
     *
     * @param lessonId       泄漏样本属于哪一讲（决定位偏移）
     * @param variantPerSlot 逐槽位读到的变体：'a'/'b'，读不出用 '?'
     */
    public Forensics accuse(long lessonId, char[] variantPerSlot) {
        var l = lesson(lessonId);
        long courseId = ((Number) l.get("course_id")).longValue();
        Scheme scheme = activeScheme(courseId);

        // ★ 码位不能按"起点 + j"推。一讲内部跳过了剪掉的片段，它占的位就是有洞的，
        // 按连续推的话，跳段之后读到的每一位都被放进别人的位上 —— 而这不报错。
        var lesson = toLesson(l);
        var slots = lesson.slotSegments();
        if (variantPerSlot.length != slots.size())
            throw new IllegalArgumentException(
                    "样本读到 " + variantPerSlot.length + " 个槽位，但这一讲有 " + slots.size()
                  + " 个：数目对不上说明样本不是这一讲，或者这一讲重切过");
        int[] bitIndex = new int[slots.size()];
        for (int j = 0; j < bitIndex.length; j++) bitIndex[j] = lesson.bitOf(slots.get(j));

        var rec = M3u8Builder.recover(variantPerSlot, bitIndex, scheme.bitLength());
        double ratio = rec.readableRatio();
        if (ratio <= 0)
            throw new IllegalStateException("样本里一位都没读出来，无法取证");

        List<TardosCode.Suspect> pool = new ArrayList<>();
        jdbc.query("SELECT user_id, bits, bit_length FROM wm_codeword "
                 + "WHERE course_id=? AND scheme_id=?", rs -> {
            pool.add(new TardosCode.Suspect(rs.getLong("user_id"),
                    unpackBits(rs.getBytes("bits"), rs.getInt("bit_length"))));
        }, courseId, scheme.id());

        double z = scheme.threshold(ratio);
        var accused = scheme.code().accuse(rec.codeword(), pool, z, rec.readable());
        int readable = 0;
        for (boolean b : rec.readable()) if (b) readable++;
        return new Forensics(readable, ratio, z, accused);
    }

    // ------------------------------------------------------------------ 编解码

    /** 码字 → 字节。位 j 落在第 j/8 个字节的第 (j%8) 高位。取证要按同一约定读回来。 */
    static byte[] packBits(boolean[] bits) {
        byte[] out = new byte[(bits.length + 7) / 8];
        for (int j = 0; j < bits.length; j++)
            if (bits[j]) out[j >> 3] |= (byte) (0x80 >>> (j & 7));
        return out;
    }

    static boolean[] unpackBits(byte[] bytes, int bitLength) {
        if ((long) bytes.length * 8 < bitLength)
            throw new IllegalStateException(
                    "声称 " + bitLength + " 位但只有 " + bytes.length + " 字节："
                  + "按位长读会越界，读到的是垃圾位，打分结果指向谁完全是随机的");
        boolean[] bits = new boolean[bitLength];
        for (int j = 0; j < bitLength; j++)
            bits[j] = (bytes[j >> 3] & (0x80 >>> (j & 7))) != 0;
        return bits;
    }

    /** p_j → 字节。不量化：存的必须与签发时用的逐位相同。 */
    static byte[] packBias(double[] p) {
        ByteBuffer bb = ByteBuffer.allocate(p.length * 8);
        for (double v : p) bb.putDouble(v);
        return bb.array();
    }

    static double[] unpackBias(byte[] raw, int bitLength) {
        if (raw.length != bitLength * 8)
            throw new IllegalStateException(
                    "码本长度不符：" + raw.length + " 字节 vs 声称 " + bitLength + " 位。"
                  + "错位读 p 不会报错，只会让每一位的权重都算错，最后指认到无辜的人身上");
        ByteBuffer bb = ByteBuffer.wrap(raw);
        double[] p = new double[bitLength];
        for (int j = 0; j < bitLength; j++) p[j] = bb.getDouble();
        return p;
    }
}
