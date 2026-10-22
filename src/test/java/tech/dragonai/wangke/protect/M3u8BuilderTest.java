package tech.dragonai.wangke.protect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 逐用户播放列表。除了"生成得对"，更要证明【投递 → 取证】是一条闭环：
 * 发出去的播放列表能被反读成码字，再喂回抗合谋码后指认得回同一个人。
 * 只测生成、不测反读，等于造了一把没试过能不能开锁的钥匙。
 */
class M3u8BuilderTest {

    static final M3u8Builder B = new M3u8Builder();
    static final M3u8Builder.Lesson LESSON = M3u8Builder.whole(7L, 600, 6.0, 6, 0, "oss/l7");

    /** 播放列表里的分片行（跳过 #EXT-X-KEY 那些同样以 .ts 结尾不了的头）。 */
    static List<String> segLines(String m3u8) {
        return m3u8.lines().filter(l -> !l.startsWith("#") && !l.isBlank()).toList();
    }

    /** 从一行分片 URL 里取全局片号。 */
    static int segOf(String line) {
        String name = line.substring(line.lastIndexOf('/') + 1).replace(".ts", "");
        return Integer.parseInt(name.split("_")[0]);
    }

    static boolean[] bits(String s) {
        boolean[] b = new boolean[s.length()];
        for (int i = 0; i < s.length(); i++) b[i] = s.charAt(i) == '1';
        return b;
    }

    @Test
    @DisplayName("槽位数 = 分片数 / 步长")
    void slotCount() {
        assertEquals(100, LESSON.slotCount());
        assertEquals(600, M3u8Builder.whole(1, 600, 6, 1, 0, "x").slotCount());
        assertEquals(0, M3u8Builder.whole(1, 600, 6, 0, 0, "x").slotCount());
    }

    @Test
    void trailingPartialStrideIsNotWatermarked() {
        var lesson = M3u8Builder.whole(7L, 1060, 10.0, 6, 545, "fde/l7");
        boolean[] codeword = new boolean[721];

        String playlist = B.build(lesson, codeword, "/key", "https://cdn/fde/l7");
        assertTrue(playlist.contains("https://cdn/fde/l7/1059.ts"));
        assertFalse(playlist.contains("1059_a.ts"));
        assertFalse(playlist.contains("1059_b.ts"));

        List<String> objects = B.segmentObjects(lesson);
        assertEquals(1236, objects.size());
        assertTrue(objects.contains("1059.ts"));
    }

    @Test
    @DisplayName("槽位↔分片映射可逆，且取 stride 中点（不与关键帧边界重合）")
    void slotMappingIsInvertible() {
        for (int slot = 0; slot < 100; slot++) {
            int seg = slot * 6 + 3;
            assertTrue(LESSON.isSlot(seg));
            assertEquals(slot, LESSON.bitOf(seg));
            assertNotEquals(0, seg % 6, "槽位落在 stride 起点上，位置可猜");
        }
        assertFalse(LESSON.isSlot(0), "stride 起点上的片不是槽位");
    }

    @Test
    @DisplayName("码字不同 → 播放列表不同；码字相同 → 逐字节相同")
    void playlistIsPerUser() {
        boolean[] u1 = new boolean[100], u2 = new boolean[100];
        u2[37] = true;
        String a = B.build(LESSON, u1, "/key?t=X", "https://cdn/x");
        String b = B.build(LESSON, u2, "/key?t=X", "https://cdn/x");
        assertNotEquals(a, b, "两个学员拿到同一份播放列表，水印就不存在了");
        assertEquals(a, B.build(LESSON, u1.clone(), "/key?t=X", "https://cdn/x"),
                "同一码字两次生成不一致，取证会对不上");
    }

    @Test
    @DisplayName("非槽位分片全员共用同一个 URL（否则 CDN 命中率归零）")
    void nonSlotSegmentsAreShared() {
        boolean[] u1 = new boolean[100];
        boolean[] u2 = new boolean[100];
        for (int i = 0; i < 100; i++) u2[i] = true;          // 完全相反的码字
        List<String> l1 = B.build(LESSON, u1, "/k", "https://cdn/x").lines().toList();
        List<String> l2 = B.build(LESSON, u2, "/k", "https://cdn/x").lines().toList();
        int differing = 0;
        for (int i = 0; i < l1.size(); i++) if (!l1.get(i).equals(l2.get(i))) differing++;
        assertEquals(100, differing,
                "两份完全相反的码字之间，应当只有 100 个槽位行不同；实际 " + differing
                + " 行不同 —— 非槽位分片被个性化了，CDN 会失效");
    }

    @Test
    @DisplayName("密钥 URI 必须写进播放列表，且不得内联明文密钥")
    void keyUriPresent() {
        String m = B.build(LESSON, new boolean[100], "/api/play/key?t=TOKEN", "https://cdn/x");
        assertTrue(m.contains("#EXT-X-KEY:METHOD=AES-128"), "没有加密声明，分片是裸的");
        assertTrue(m.contains("URI=\"/api/play/key?t=TOKEN\""));
        // IV 本身就是 32 个十六进制字符，先把它摘掉再找"疑似密钥"，
        // 否则这条判据会被自己的 IV 顶成常红，而常红的判据下场是被人删掉。
        String noIv = m.replaceAll("IV=0x[0-9a-fA-F]{32}", "");
        assertFalse(noIv.matches("(?s).*[0-9a-fA-F]{32}.*"),
                "播放列表里出现了疑似明文密钥：\n" + noIv);
        // 内联密钥最现实的一条路是 data: URI，单靠上面那条十六进制判据抓不到 base64。
        assertFalse(m.contains("URI=\"data:"), "密钥被内联成 data: URI，等于没有加密");

        // 判据得真会红：把一把 16 字节明文密钥塞进播放列表，上面那条必须变红。
        String leaked = m.replace("#EXT-X-ENDLIST", "#EXT-X-SESSION-KEY:K=0123456789abcdef0123456789abcdef\n#EXT-X-ENDLIST");
        assertTrue(leaked.replaceAll("IV=0x[0-9a-fA-F]{32}", "").matches("(?s).*[0-9a-fA-F]{32}.*"),
                "把明文密钥塞进去之后判据还是绿的 —— 它是个空判据");
    }

    @Test
    @DisplayName("码字短于槽位数 —— 必须报错，不许静默截断")
    void shortCodewordRejected() {
        var e = assertThrows(IllegalArgumentException.class,
                () -> B.build(LESSON, new boolean[50], "/k", "https://cdn/x"));
        assertTrue(e.getMessage().contains("静默截断"),
                "静默截断会让后半程失去水印，而且不会有任何症状");
    }

    @Test
    @DisplayName("投递→取证闭环：反读播放列表得到码字，指认回同一个人")
    void deliveryToForensicsRoundTrip() {
        SecureRandom rnd = new SecureRandom(new byte[]{4, 2, 4, 2});
        int users = 300, c = 3;
        double eps1 = 1e-3;
        TardosCode code = TardosCode.create(c, users, eps1, rnd);
        // 用与码长匹配的讲次参数：每片都做双版本
        M3u8Builder.Lesson big = M3u8Builder.whole(9L, code.length(), 6.0, 1, 0, "oss/l9");

        List<TardosCode.Suspect> pool = new ArrayList<>();
        for (long i = 1; i <= users; i++) pool.add(new TardosCode.Suspect(i, code.issue(rnd)));

        int leaker = 77;
        String m3u8 = B.build(big, pool.get(leaker).codeword(), "/k", "https://cdn/x");

        // 模拟取证：从泄漏副本的分片文件名读出每个槽位用的是 a 还是 b
        char[] variants = new char[big.slotCount()];
        int j = 0;
        for (String line : segLines(m3u8)) {
            if (big.isSlot(segOf(line))) variants[j++] = line.contains("_b") ? 'b' : 'a';
        }
        var rec = M3u8Builder.recover(variants, 0, big.slotCount());
        assertEquals(1.0, rec.readableRatio(), 1e-9, "完整样本应当全部可读");

        var acc = code.accuse(rec.codeword(), pool, TardosCode.threshold(c, users, eps1));
        assertFalse(acc.isEmpty(), "闭环断了：发得出去，读不回来");
        assertEquals(pool.get(leaker).userId(), acc.get(0).userId(), "指认到了别人");
        assertEquals(1, acc.size(), "单人泄漏却牵连了 " + acc.size() + " 人");
    }

    @Test
    @DisplayName("样本残缺时按可读比例缩放阈值，仍指认得回且不误伤")
    void partialSampleRoundTrip() {
        SecureRandom rnd = new SecureRandom(new byte[]{8, 8, 8, 8});
        int users = 300, c = 3;
        double eps1 = 1e-3;
        TardosCode code = TardosCode.create(c, users, eps1, rnd);
        List<TardosCode.Suspect> pool = new ArrayList<>();
        for (long i = 1; i <= users; i++) pool.add(new TardosCode.Suspect(i, code.issue(rnd)));

        int leaker = 12;
        char[] variants = new char[code.length()];
        boolean[] cw = pool.get(leaker).codeword();
        for (int j = 0; j < variants.length; j++)
            variants[j] = (j < variants.length * 0.4) ? (cw[j] ? 'b' : 'a') : '?';  // 只放出前 40%

        var rec = M3u8Builder.recover(variants, 0, code.length());
        assertEquals(0.4, rec.readableRatio(), 0.01);
        double z = TardosCode.thresholdForPartial(c, users, eps1, rec.readableRatio());

        long hit = pool.stream().filter(s ->
                code.score(rec.codeword(), s.codeword(), rec.readable()) > z).count();
        double leakerScore = code.score(rec.codeword(), cw, rec.readable());
        assertTrue(leakerScore > z, "只放出 40% 就指认不出，取证在最常见的情形下失效");
        assertEquals(1, hit, "残缺样本牵连了 " + hit + " 人");
    }

    @Test
    @DisplayName("整门课的位被用满且互不重叠 —— 不是每讲都从第 0 位重来")
    void lessonsCoverDisjointBitRanges() {
        // 六讲，各自长度不同，按 wm_bit_offset 首尾相接
        int[] segs = {1520, 1400, 1600, 1300, 1450, 1550};
        List<M3u8Builder.Lesson> course = new ArrayList<>();
        int off = 0;
        for (int k = 0; k < segs.length; k++) {
            course.add(M3u8Builder.whole(k + 1, segs[k], 6.0, 1, off, "oss/l" + k));
            off += segs[k];                       // stride=1 → slotCount == segmentCount
        }
        int total = off;

        // 码字必须是【伪随机】的，不能用 j%2 这种规律串。
        // 第一版就写成了奇偶模式，而所有讲的偏移恰好都是偶数 —— 奇偶模式在偶数平移下不变，
        // 于是"忽略偏移"这个 bug 投递出的变体与正确实现逐位相同，判据照样绿。
        // 判据用的数据本身在故障模式下不变，那它就测不到这个故障。
        SecureRandom fixed = new SecureRandom(new byte[]{9, 9, 9});
        boolean[] cw = new boolean[total];
        for (int j = 0; j < total; j++) cw[j] = fixed.nextBoolean();

        var delivered = new java.util.TreeSet<Integer>();
        for (var l : course) {
            for (String line : segLines(B.build(l, cw, "/k", "https://cdn/x"))) {
                int seg = segOf(line);
                if (!l.isSlot(seg)) continue;
                int bit = l.bitOf(seg);
                assertTrue(delivered.add(bit),
                        "第 " + bit + " 位被两讲同时投递 —— 位区间重叠，等于白费一段码长");
                assertEquals(cw[bit], line.contains("_b"), "第 " + bit + " 位投递的变体与码字不符");
            }
        }
        assertEquals(total, delivered.size(),
                "整门课 " + total + " 位里只有 " + delivered.size() + " 位真被投递出去。"
              + "每讲都从第 0 位开始用时这里会掉到最长一讲那么多，"
              + "有效码长静默变成 1/讲数，抗合谋能力跟着掉，而各组件自己的测试全是绿的");
        assertEquals(0, delivered.first());
        assertEquals(total - 1, (int) delivered.last());
    }

    @Test
    @DisplayName("只泄漏一讲：按偏移放回整条码字，残缺样本仍指认得回且不误伤")
    void singleLessonLeakIsAPartialSample() {
        SecureRandom rnd = new SecureRandom(new byte[]{7, 7, 7});
        int users = 300, c = 2;
        double eps1 = 1e-3;
        TardosCode code = TardosCode.create(c, users, eps1, rnd);

        // 把整条码字切成 6 讲，只泄漏第 3 讲
        int per = code.length() / 6;
        int leakOffset = per * 2;
        var lesson = M3u8Builder.whole(3L, per, 6.0, 1, leakOffset, "oss/l3");

        List<TardosCode.Suspect> pool = new ArrayList<>();
        for (long i = 1; i <= users; i++) pool.add(new TardosCode.Suspect(i, code.issue(rnd)));
        int leaker = 199;

        String m3u8 = B.build(lesson, pool.get(leaker).codeword(), "/k", "https://cdn/x");
        char[] variants = new char[per];
        int k2 = 0;
        for (String line : segLines(m3u8)) {
            if (lesson.isSlot(segOf(line))) variants[k2++] = line.contains("_b") ? 'b' : 'a';
        }

        var rec = M3u8Builder.recover(variants, leakOffset, code.length());
        assertEquals(per / (double) code.length(), rec.readableRatio(), 1e-9,
                "可读比例应当正好是这一讲占整条码字的比例");

        double z = TardosCode.thresholdForPartial(c, users, eps1, rec.readableRatio());
        var acc = code.accuse(rec.codeword(), pool, z, rec.readable());
        assertFalse(acc.isEmpty(), "只泄漏一讲就指认不出 —— 而这正是最常见的情形");
        assertEquals(pool.get(leaker).userId(), acc.get(0).userId(), "指认到了别人");
        assertEquals(1, acc.size(), "单人泄漏牵连了 " + acc.size() + " 人");
    }

    @Test
    @DisplayName("位区间越界必须报错：错位打分会指认到无辜的人")
    void offsetOutOfRangeRejected() {
        var e = assertThrows(IllegalArgumentException.class,
                () -> M3u8Builder.recover(new char[100], 950, 1000));
        assertTrue(e.getMessage().contains("指认到别人身上"), e.getMessage());
        var lesson = M3u8Builder.whole(1L, 600, 6.0, 6, 90, "oss/x");   // 需要 90+100 位
        var e2 = assertThrows(IllegalArgumentException.class,
                () -> B.build(lesson, new boolean[100], "/k", "https://cdn/x"));
        assertTrue(e2.getMessage().contains("静默截断"), e2.getMessage());
    }

    @Test
    @DisplayName("槽位片存两份、非槽位片存一份 —— 存储只多 1/stride")
    void storageOverhead() {
        List<String> objs = B.segmentObjects(LESSON);
        assertEquals(600 + 100, objs.size());
        assertEquals(1.0 + 1.0 / 6, objs.size() / 600.0, 1e-9);
    }

    // ---------------------------------------------------------------- 分段交付

    @Test
    @DisplayName("★ 逐片显式写 IV，且 IV 就是【全局片号】而不是播放序号")
    void explicitIvIsTheGlobalSegmentIndex() {
        // 一讲从片库中段开始：它的第 0 片是全局第 267 片。
        var lesson = new M3u8Builder.Lesson(1L, List.of(new M3u8Builder.Block(267, 273)),
                10.0, 6, 0, 1098, "fde/l2");
        String m = B.build(lesson, new boolean[721], "/key", "https://cdn/x");

        assertTrue(m.contains("IV=0x" + "0".repeat(29) + "10b"),
                "第 267 片的 IV 应当是 0x10b（267）：\n" + m);
        assertFalse(m.contains("IV=0x" + "0".repeat(32)),
                "出现了 IV=0 —— 那是按播放序号推出来的那一组，不是打包时用的那一组");
        assertEquals(6, m.lines().filter(l -> l.startsWith("#EXT-X-KEY")).count(),
                "IV 逐片不同，所以 EXT-X-KEY 必须逐片写一行");

        // 错了几乎看不出来（CBC 只有第一个分组吃 IV，一片 1.5MB 里只错 2 字节，
        // 解复用器一对齐就过去了），所以这条判据必须钉在【字面上】，
        // 不能靠"能不能播出来"来验 —— 能播不代表 IV 是对的。
        var lines = m.lines().filter(l -> l.startsWith("#EXT-X-KEY")).toList();
        for (int i = 0; i < lines.size(); i++)
            assertTrue(lines.get(i).endsWith(String.format("IV=0x%032x", 267 + i)),
                    "第 " + i + " 行的 IV 不是全局片号 " + (267 + i) + "：" + lines.get(i));
    }

    @Test
    @DisplayName("跳段处必须有 EXT-X-DISCONTINUITY，不跳段处必须没有")
    void gapsAreMarkedDiscontinuous() {
        var gapped = new M3u8Builder.Lesson(1L,
                List.of(new M3u8Builder.Block(0, 10), new M3u8Builder.Block(40, 50)),
                10.0, 6, 0, 1098, "fde/l2");
        String m = B.build(gapped, new boolean[721], "/k", "https://cdn/x");
        assertEquals(1, m.lines().filter(l -> l.equals("#EXT-X-DISCONTINUITY")).count());
        // 标记必须正好在跳段那一片【之前】
        var lines = m.lines().toList();
        int at = lines.indexOf("#EXT-X-DISCONTINUITY");
        assertTrue(lines.get(at + 3).endsWith("/40.ts"), "标记没落在跳段的那一片前面");

        var contiguous = new M3u8Builder.Lesson(1L,
                List.of(new M3u8Builder.Block(0, 10), new M3u8Builder.Block(10, 20)),
                10.0, 6, 0, 1098, "fde/l2");
        assertFalse(B.build(contiguous, new boolean[721], "/k", "https://cdn/x")
                        .contains("DISCONTINUITY"),
                "首尾相接的两段之间打了跳段标记：播放器会白白重建一次时间轴");
    }

    @Test
    @DisplayName("一讲从片库中段开始时，对象名用全局片号（不从 0 重编）")
    void objectNamesStayGlobal() {
        var lesson = new M3u8Builder.Lesson(1L, List.of(new M3u8Builder.Block(267, 270)),
                10.0, 6, 0, 1098, "fde/l2");
        var objs = B.segmentObjects(lesson);
        assertEquals(List.of("267_a.ts", "267_b.ts", "268.ts", "269.ts"), objs,
                "片号从 0 重编的话，磁盘上得给每一讲各存一份 —— 存储乘以讲数，"
              + "而且已发出的副本全部对不上号");
    }

    @Test
    @DisplayName("★★ 重切课程不动任何一位：同一片在两种切法下投递的变体逐片相同")
    void recuttingMovesNoBit() {
        int store = 1098, base = 0;
        SecureRandom fixed = new SecureRandom(new byte[]{5, 5, 5});
        boolean[] cw = new boolean[721];
        for (int i = 0; i < cw.length; i++) cw[i] = fixed.nextBoolean();

        // 切法甲：整场一讲
        var one = new M3u8Builder.Lesson(1L, List.of(new M3u8Builder.Block(0, store)),
                10.0, 6, base, store, "fde/l2");
        // 切法乙：切成四讲，其中还剪掉了 [730,766) 那段课间
        var many = List.of(
                new M3u8Builder.Lesson(1L, List.of(new M3u8Builder.Block(0, 267)), 10, 6, base, store, "fde/l2"),
                new M3u8Builder.Lesson(2L, List.of(new M3u8Builder.Block(267, 554)), 10, 6, base, store, "fde/l2"),
                new M3u8Builder.Lesson(3L, List.of(new M3u8Builder.Block(554, 730),
                                                   new M3u8Builder.Block(766, 829)), 10, 6, base, store, "fde/l2"),
                new M3u8Builder.Lesson(4L, List.of(new M3u8Builder.Block(829, store)), 10, 6, base, store, "fde/l2"));

        var a = variantsBySegment(B.build(one, cw, "/k", "https://cdn/x"));
        var b = new java.util.TreeMap<Integer, Character>();
        for (var l : many) b.putAll(variantsBySegment(B.build(l, cw, "/k", "https://cdn/x")));

        // 剪掉的那 36 片本来就不该出现在乙里
        for (int g = 730; g < 766; g++) assertFalse(b.containsKey(g), "剪掉的第 " + g + " 片被投递了");
        a.keySet().removeIf(g -> g >= 730 && g < 766);
        assertEquals(a, b,
                "重切之后有片的变体变了 —— 同一片对应的码位挪了位置，已发出的所有副本"
              + "在取证时会整体错位，而错位的打分不报错，只会指认到别人");
    }

    /** 播放列表 → {全局片号: 'a'/'b'}；非槽位片不入表。 */
    static java.util.TreeMap<Integer, Character> variantsBySegment(String m3u8) {
        var out = new java.util.TreeMap<Integer, Character>();
        for (String line : segLines(m3u8)) {
            String name = line.substring(line.lastIndexOf('/') + 1).replace(".ts", "");
            if (name.contains("_")) out.put(segOf(line), name.endsWith("_b") ? 'b' : 'a');
        }
        return out;
    }

    @Test
    @DisplayName("片库尾部不完整步长里那片不是槽位（fde/l7 的第 1059 片）")
    void tailBeyondCapacityIsNotASlot() {
        var lesson = M3u8Builder.whole(7L, 1060, 10.0, 6, 545, "fde/l7");
        assertTrue(lesson.isSlot(1053));
        assertFalse(lesson.isSlot(1059),
                "1059 % 6 == 3 看着像槽位，但 1059/6 = 176 已到容量上界，磁盘上没有 1059_a.ts");
        assertEquals(176, lesson.slotCount());
        assertEquals(545 + 176, lesson.bitEnd());
    }
}
