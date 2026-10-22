package tech.dragonai.wangke.protect;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tech.dragonai.wangke.ItDb;

import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 水印服务层。重点全在<b>「错了不会报错」那几件事上</b>：
 * 码本丢失、码长不够、位区间事后被改、跨码本打分。
 * 这四件事共同的形状是——系统照常发码字、照常打分、照常返回一个结果，
 * 只是那个结果是错的，而你只有在真出了泄漏、真去指认时才会发现。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WatermarkServiceIT {

    static final String DB = "wangke_wm_it";
    static final long COURSE = 1L;
    static DataSource ds;
    static JdbcTemplate jdbc;
    static TransactionTemplate tx;
    static WatermarkService wm;

    static final int LESSONS = 6, SEGS = 1520, SEG_SEC = 6;

    @BeforeAll
    static void bootstrap() throws Exception {
        ds = ItDb.freshDatabase(DB);
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        wm = new WatermarkService(jdbc, tx);

        // 章 = 一场录屏 = 一个片库目录。槽位容量按片库的分片总数算，与怎么切讲次无关。
        for (int k = 1; k <= LESSONS; k++) {
            jdbc.update("INSERT INTO chapter (id,course_id,title,hls_key,store_segment_count,sort_no)"
                      + " VALUES (?,?,?,?,?,?)", k, COURSE, "第" + k + "章", "oss/l" + k, SEGS, k);
            jdbc.update("INSERT INTO lesson (id,chapter_id,course_id,title,sort_no,duration_sec,"
                      + "hls_key,segment_count,wm_slot_stride) VALUES (?,?,?,?,?,?,?,?,1)",
                    k, k, COURSE, "第" + k + "讲", k, SEGS * SEG_SEC, "oss/l" + k, SEGS);
            jdbc.update("INSERT INTO lesson_segment_block (lesson_id,sort_no,seg_from,seg_to)"
                      + " VALUES (?,1,0,?)", k, SEGS);
        }
        for (long u = 100; u < 130; u++)
            jdbc.update("INSERT INTO sys_user (id,phone,role) VALUES (?,?,'STUDENT')",
                    u, "139" + (10000000L + u));
    }

    @AfterAll
    static void teardown() { ItDb.close(ds); }

    // ---------------------------------------------------------------- 码本

    @Test @Order(0)
    @DisplayName("试听片段不占码字：容量与位区间分配两处口径必须一致")
    void previewLessonsAreExcludedConsistently() {
        int before = wm.totalSlotCapacity(COURSE);
        // 试听片段是独立编码的一份公开物料 —— 它有自己的片库目录，所以是自己一章。
        jdbc.update("INSERT INTO chapter (id,course_id,title,hls_key,store_segment_count,sort_no)"
                  + " VALUES (90,?,'试听','oss/preview',20,90)", COURSE);
        jdbc.update("INSERT INTO lesson (id,chapter_id,course_id,title,sort_no,duration_sec,"
                  + "hls_key,segment_count,wm_slot_stride,is_preview) "
                  + "VALUES (90,90,?,'试听',90,120,'oss/preview',20,1,1)", COURSE);
        try {
            assertEquals(before, wm.totalSlotCapacity(COURSE),
                    "试听片段被算进了槽位容量，而分配位区间时又跳过它 —— "
                  + "两处口径不一致会让码长比实际分出去的位多一截，"
                  + "多出来的那段码字永远不会被投递，抗合谋的账白付了");
        } finally {
            jdbc.update("DELETE FROM lesson WHERE id=90");
            jdbc.update("DELETE FROM chapter WHERE id=90");
        }
    }

    @Test @Order(1)
    @DisplayName("槽位装不下就拒绝建码本，并说清最多支持几人合谋")
    void refusesWhenSlotsCannotHoldTheCode() {
        assertEquals(LESSONS * SEGS, wm.totalSlotCapacity(COURSE));
        // c=3 需要 11810 位，只有 9120 个槽位
        var e = assertThrows(IllegalStateException.class,
                () -> wm.ensureScheme(COURSE, 3, 500, 1e-3));
        assertTrue(e.getMessage().contains("槽位装不下"), e.getMessage());
        assertTrue(e.getMessage().contains("拒绝缩短码长"),
                "必须说明为什么不能自动缩短：缩短后漏判率不会有任何提示");
        assertEquals(0, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM wm_scheme WHERE course_id=?", Integer.class, COURSE),
                "被拒绝的建库请求留下了半张码本");
    }

    @Test @Order(2)
    @DisplayName("建码本：c=2 装得下，bias 逐位往返无损")
    void schemeRoundTripsExactly() {
        var s = wm.ensureScheme(COURSE, 2, 500, 1e-3);
        // 码长取满槽位容量，而不是 suggestedLength 那个下界。
        // 取下界会让排在后面的讲次整段落在码字之外 —— 那几讲一位水印都没有，
        // 而且不会报错（build 只在越界那一讲才炸，其余安静地少水印）。
        assertEquals(wm.totalSlotCapacity(COURSE), s.bitLength(),
                "码长没有用满槽位：位区间按容量分配，码字短于容量就会有讲次落在码字之外");
        assertTrue(s.bitLength() >= TardosCode.suggestedLength(2, 500, 1e-3),
                "码长短于 c=2 所需的下界：漏判率会上升，而这件事不会有任何提示");

        // 关键：从库里读回来的 p 必须与内存里的【逐位相同】。
        // 量化哪怕一个尾数位，打分就和签发时不是同一套，分数整体漂移，漂过阈值就是误判。
        var again = wm.activeScheme(COURSE);
        assertArrayEquals(s.bias(), again.bias(), 0.0,
                "bias 往返有损 —— 打分公式里的 p 变了，取证结果不再可信");
        assertEquals(s.id(), again.id());
    }

    @Test @Order(3)
    @DisplayName("一门课至多一套现行码本")
    void onlyOneActiveScheme() {
        var s = wm.activeScheme(COURSE);
        assertSame(s.id(), wm.ensureScheme(COURSE, 2, 500, 1e-3).id(), "重复调用建了第二套");
        var e = assertThrows(Exception.class, () -> jdbc.update(
                "INSERT INTO wm_scheme (course_id,max_colluders,users_planned,eps1,bit_length,bias) "
              + "VALUES (?,2,500,0.001,8,REPEAT(0x00,64))", COURSE));
        assertTrue(e.getMessage().contains("uk_course_active"),
                "两套并存时，签发走哪套、取证按哪套算全凭查询顺序：" + e.getMessage());
    }

    @Test @Order(4)
    @DisplayName("bias 字节数与声称位长不符 —— 库层直接拒")
    void biasLengthIsChecked() {
        var e = assertThrows(Exception.class, () -> jdbc.update(
                "INSERT INTO wm_scheme (course_id,max_colluders,users_planned,eps1,bit_length,bias) "
              + "VALUES (999,2,500,0.001,100,REPEAT(0x00,64))"));
        assertTrue(e.getMessage().contains("ck_wm_scheme_bias"), e.getMessage());
    }

    // ---------------------------------------------------------------- 位区间

    @Test @Order(5)
    @DisplayName("各讲位区间首尾相接、互不重叠、用满整条码字前缀")
    void bitRangesAreDisjointAndContiguous() {
        var layout = wm.assignBitOffsets(COURSE);
        assertEquals(LESSONS, layout.size());
        int expect = 0;
        for (var l : layout) {
            assertEquals(expect, l.bitOffset(), "第 " + l.lessonId() + " 讲的偏移不接前一讲");
            assertEquals(SEGS, l.slotCount());
            expect += l.slotCount();
        }
        assertEquals(LESSONS * SEGS, expect);
        assertEquals(wm.activeScheme(COURSE).bitLength(), expect,
                "各讲位区间的总和与码长对不上：多出来的讲次落在码字之外（一位水印都没有），"
              + "少了则有一段码长永远不会被投递出去");
        assertEquals(0, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM lesson a JOIN lesson b ON a.id<b.id AND a.course_id=b.course_id "
              + "AND a.wm_bit_offset < b.wm_bit_offset + b.wm_slot_count "
              + "AND b.wm_bit_offset < a.wm_bit_offset + a.wm_slot_count", Integer.class),
                "有两讲的位区间重叠 —— 重叠的那一段等于白费的码长");

        // 码本是建库那一刻按当时容量定死的。事后加【一整个片库】（一章），容量就和码长
        // 对不上了，多出来的那一章整段落在码字之外 —— 一位水印都没有，且没有任何提示。
        //
        // 注意加的必须是"一章"而不是"一讲"：容量按片库算，在已有章里多切出一讲
        // 不会改变容量（这正是重切课程之所以安全的原因）。
        jdbc.update("INSERT INTO chapter (id,course_id,title,hls_key,store_segment_count,sort_no)"
                  + " VALUES (99,?,'事后加的一章','oss/l99',?,99)", COURSE, SEGS);
        jdbc.update("INSERT INTO lesson (id,chapter_id,course_id,title,sort_no,duration_sec,"
                  + "hls_key,segment_count,wm_slot_stride) VALUES (99,99,?,'事后加的一讲',99,?,?,?,1)",
                COURSE, SEGS * SEG_SEC, "oss/l99", SEGS);
        jdbc.update("INSERT INTO lesson_segment_block (lesson_id,sort_no,seg_from,seg_to)"
                  + " VALUES (99,1,0,?)", SEGS);
        try {
            var e = assertThrows(IllegalStateException.class, () -> wm.assignBitOffsets(COURSE));
            assertTrue(e.getMessage().contains("对不上"), e.getMessage());
            assertTrue(e.getMessage().contains("落在码字之外"),
                    "拒绝的理由要写清后果：" + e.getMessage());
        } finally {
            jdbc.update("DELETE FROM lesson_segment_block WHERE lesson_id=99");
            jdbc.update("DELETE FROM lesson WHERE id=99");
            jdbc.update("DELETE FROM chapter WHERE id=99");
            wm.assignBitOffsets(COURSE);        // 复原布局，后面的用例还要用
        }
    }

    // ---------------------------------------------------------------- 签发

    @Test @Order(6)
    @DisplayName("码字签发幂等：同一人拿到的永远是同一份")
    void codewordIsIdempotent() {
        boolean[] first = wm.codewordFor(100, COURSE);
        assertEquals(wm.activeScheme(COURSE).bitLength(), first.length);
        for (int i = 0; i < 3; i++)
            assertArrayEquals(first, wm.codewordFor(100, COURSE),
                    "同一个人拿到了第二份码字：取证时两份都不完全匹配，分数被摊薄到阈值以下");
        assertEquals(1, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM wm_codeword WHERE user_id=100", Integer.class));
    }

    @Test @Order(7)
    @DisplayName("12 线程并发签发同一人：只落一份，且所有线程拿到的是同一份")
    void concurrentIssueYieldsOneCodeword() throws Exception {
        int n = 12;
        var pool = Executors.newFixedThreadPool(n);
        var start = new CountDownLatch(1);
        var results = new CopyOnWriteArrayList<String>();
        var errors = new CopyOnWriteArrayList<String>();
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++)
            fs.add(pool.submit(() -> {
                try {
                    start.await();
                    results.add(Arrays.toString(wm.codewordFor(101, COURSE)));
                } catch (Exception e) { errors.add(e.getClass().getSimpleName() + ": " + e.getMessage()); }
            }));
        start.countDown();
        for (Future<?> f : fs) f.get(60, TimeUnit.SECONDS);
        pool.shutdown();

        assertTrue(errors.isEmpty(), "并发签发抛异常：" + errors);
        assertEquals(1, new HashSet<>(results).size(),
                "并发下签出了 " + new HashSet<>(results).size() + " 份不同的码字");
        assertEquals(1, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM wm_codeword WHERE user_id=101", Integer.class));
    }

    @Test @Order(8)
    @DisplayName("码字发出之后，片库基点不许再动（但重新分配本身是允许的）")
    void chapterBaseFrozenOnceIssued() {
        // 结构没变时重跑必须成功 —— 上一版在这里是无条件拒绝，于是课程结构一旦
        // 发过码字就被冻死，连"把三小时的一讲切成四讲"都做不了。
        assertDoesNotThrow(() -> wm.assignBitOffsets(COURSE),
                "结构没变的重新分配被拒了：那等于任何课程结构调整都不可能");

        // 真正不许动的是片库基点：它一动，之前所有副本的取证整体错位。
        jdbc.update("UPDATE chapter SET store_segment_count=? WHERE id=1", SEGS - 120);
        try {
            var e = assertThrows(IllegalStateException.class, () -> wm.assignBitOffsets(COURSE));
            assertTrue(e.getMessage().contains("码位基点"), e.getMessage());
            assertTrue(e.getMessage().contains("指认到别人"),
                    "拒绝的理由要写清后果，否则下一个人会以为这只是个保守限制");
        } finally {
            jdbc.update("UPDATE chapter SET store_segment_count=? WHERE id=1", SEGS);
            wm.assignBitOffsets(COURSE);
        }
    }

    @Test @Order(9)
    @DisplayName("★ 把一讲重切成两讲：每一片对应的码位逐片不变，已发出的码字继续有效")
    void recuttingDoesNotMoveAnyBit() {
        var before = deliveredBits(wm.playlist(100, 1, "/k", "https://cdn/l1"));
        assertEquals(SEGS, before.size());

        // 第 1 讲 [0,1520) 拆成 [0,700) 与 [700,1520)
        jdbc.update("UPDATE lesson SET segment_count=700, duration_sec=? WHERE id=1", 700 * SEG_SEC);
        jdbc.update("UPDATE lesson_segment_block SET seg_to=700 WHERE lesson_id=1");
        jdbc.update("INSERT INTO lesson (id,chapter_id,course_id,title,sort_no,duration_sec,"
                  + "hls_key,segment_count,wm_slot_stride) VALUES (7,1,?,'第1讲下半',1,?,?,?,1)",
                COURSE, (SEGS - 700) * SEG_SEC, "oss/l1", SEGS - 700);
        jdbc.update("INSERT INTO lesson_segment_block (lesson_id,sort_no,seg_from,seg_to)"
                  + " VALUES (7,1,700,?)", SEGS);
        try {
            assertDoesNotThrow(() -> wm.assignBitOffsets(COURSE), "重切被拒了");

            var after = new TreeMap<Integer, Character>();
            after.putAll(deliveredBits(wm.playlist(100, 1, "/k", "https://cdn/l1")));
            after.putAll(deliveredBits(wm.playlist(100, 7, "/k", "https://cdn/l1")));
            assertEquals(before, after,
                    "重切之后有片的变体变了 —— 同一片对应的码位挪过位置，"
                  + "已发出的所有副本在取证时会整体错位，而错位的打分不报错，只会指认到别人");
        } finally {
            jdbc.update("DELETE FROM lesson_segment_block WHERE lesson_id=7");
            jdbc.update("DELETE FROM lesson WHERE id=7");
            jdbc.update("UPDATE lesson SET segment_count=?, duration_sec=? WHERE id=1",
                    SEGS, SEGS * SEG_SEC);
            jdbc.update("UPDATE lesson_segment_block SET seg_to=? WHERE lesson_id=1", SEGS);
            wm.assignBitOffsets(COURSE);
        }
    }

    /** 播放列表 → {全局片号: 'a'/'b'}。取证读回来的就是这张表。 */
    static TreeMap<Integer, Character> deliveredBits(String m3u8) {
        var out = new TreeMap<Integer, Character>();
        m3u8.lines().filter(l -> !l.startsWith("#") && l.endsWith(".ts")).forEach(line -> {
            String name = line.substring(line.lastIndexOf('/') + 1).replace(".ts", "");
            out.put(Integer.parseInt(name.split("_")[0]), name.endsWith("_b") ? 'b' : 'a');
        });
        return out;
    }

    @Test @Order(10)
    @DisplayName("跨码本读码字必须炸，不许算出一组看起来正常的分数")
    void crossSchemeIsRejected() {
        jdbc.update("UPDATE wm_codeword SET scheme_id=scheme_id+1000 WHERE user_id=100");
        try {
            var e = assertThrows(IllegalStateException.class, () -> wm.codewordFor(100, COURSE));
            assertTrue(e.getMessage().contains("另一套码本"), e.getMessage());
        } finally {
            jdbc.update("UPDATE wm_codeword SET scheme_id=scheme_id-1000 WHERE user_id=100");
        }
    }

    // ---------------------------------------------------------------- 投递与取证

    @Test @Order(11)
    @DisplayName("逐用户播放列表：人不同则不同，同人两次逐字节相同")
    void playlistIsPerUser() {
        String a = wm.playlist(100, 3, "/key?t=X", "https://cdn/l3");
        String b = wm.playlist(101, 3, "/key?t=X", "https://cdn/l3");
        assertNotEquals(a, b, "两个学员拿到同一份播放列表，水印就不存在了");
        assertEquals(a, wm.playlist(100, 3, "/key?t=X", "https://cdn/l3"),
                "同一人两次生成不一致，取证会对不上");
        assertEquals(SEGS, a.lines().filter(l -> l.endsWith(".ts")).count());
    }

    @Test @Order(12)
    @DisplayName("★ 换一个全新的服务实例仍能取证 —— 码本落库这件事的意义就在这里")
    void forensicsSurvivesRestart() {
        // 30 个学员各拿一份码字
        for (long u = 100; u < 130; u++) wm.codewordFor(u, COURSE);
        long leaker = 117;

        // 泄漏的是第 4 讲：从他的播放列表反读出这一讲的槽位变体
        String m3u8 = wm.playlist(leaker, 4, "/k", "https://cdn/l4");
        // 逐槽位按【播放顺序】读回变体。位是 accuse 自己按区间算的 ——
        // 一讲内部有跳段时，位不连续，这里按顺序给就够了。
        char[] variants = new char[SEGS];
        int j = 0;
        for (var e : deliveredBits(m3u8).entrySet()) variants[j++] = e.getValue();

        // 全新实例：内存里那份 TardosCode 已经不存在了，只能靠库里的 bias
        var reborn = new WatermarkService(jdbc, tx);
        var f = reborn.accuse(4, variants);

        assertEquals(SEGS, f.readableBits());
        assertEquals(SEGS / (double) wm.activeScheme(COURSE).bitLength(), f.readableRatio(), 1e-9);
        assertFalse(f.accused().isEmpty(),
                "重启后指认不出来 —— 码本没落库时就是这个症状，而且没有任何报错");
        assertEquals(leaker, f.accused().get(0).userId(), "指认到了别人");
        assertEquals(1, f.accused().size(), "单人泄漏牵连了 " + f.accused().size() + " 人");
    }

    @Test @Order(13)
    @DisplayName("样本一位都读不出时报错，而不是返回一份空指控")
    void emptySampleIsAnError() {
        char[] blank = new char[SEGS];
        Arrays.fill(blank, '?');
        var e = assertThrows(IllegalStateException.class, () -> wm.accuse(4, blank));
        assertTrue(e.getMessage().contains("一位都没读出来"), e.getMessage());
    }

    @Test @Order(14)
    @DisplayName("位打包往返无损，声称位长超过实际字节数时拒绝")
    void bitPackingRoundTrips() {
        var rnd = new java.security.SecureRandom(new byte[]{3});
        for (int len : new int[]{1, 7, 8, 9, 5249}) {
            boolean[] bits = new boolean[len];
            for (int i = 0; i < len; i++) bits[i] = rnd.nextBoolean();
            assertArrayEquals(bits,
                    WatermarkService.unpackBits(WatermarkService.packBits(bits), len), "len=" + len);
        }
        var e = assertThrows(IllegalStateException.class,
                () -> WatermarkService.unpackBits(new byte[2], 100));
        assertTrue(e.getMessage().contains("越界"), e.getMessage());
    }
}
