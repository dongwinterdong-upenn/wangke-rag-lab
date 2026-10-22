package tech.dragonai.wangke.learn;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tech.dragonai.wangke.ItDb;
import tech.dragonai.wangke.config.WangkeProps;
import tech.dragonai.wangke.protect.PlayToken;
import tech.dragonai.wangke.protect.SegmentToken;
import tech.dragonai.wangke.protect.WatermarkService;
import tech.dragonai.wangke.web.ApiException;

import org.springframework.dao.DataAccessException;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 学员端的学习与播放。跑在真实 MySQL 上。
 *
 * <p>这条链的赔付点是<b>片源</b>：一次成功的越权取密钥不像多发一个激活码，它没有上限 ——
 * 拿到密钥就等于拿到整讲的明文视频，而且事后无法收回。所以这里的每个负例都要
 * 钉住两件事：<b>被拒的原因确实是要测的那条规则</b>，以及<b>被拒之后没有留下任何
 * 半成品</b>（没有会话、没有占掉设备位、没有发出密钥）。
 *
 * <p>每个负例都<b>钉住失败原因</b>。只断言"抛异常"的负例太容易因为别的原因红着 ——
 * 比如令牌串讲那条，如果因为设备指纹不匹配而被拒，它照样是绿的，
 * 而"令牌绑不绑讲次"这件事一次都没被验证过。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LearnIT {

    static final String DB = "wangke_learn_it";
    static final long COURSE = 1L;

    /** 每讲 1800 片 × 步长 6 = 300 个槽位；三讲共 900 位，够 c=1/n=20/eps=1e-2 的 760 位下界。 */
    static final int SEGS = 1800, STRIDE = 6, SEG_SEC = 6, DURATION = SEGS * SEG_SEC;

    /**
     * U_OTHER 也有这门课的权益。它的存在是为了让「会话是别人的」那条负例真的在测归属：
     * 用 U_NONE 去试的话，挡住它的是权益闸门而不是会话归属检查 ——
     * 那条用例会绿，而「心跳能不能挂到别人的会话上」一次都没被验证过。
     */
    static final long U_OK = 201, U_NONE = 202, U_EXPIRED = 203, U_OTHER = 204;
    static final String DEV_A = "devA0000000000001", DEV_B = "devB0000000000002", DEV_C = "devC0000000000003";
    static final String IP = "203.0.113.9";

    static DataSource ds;
    static JdbcTemplate jdbc;
    static TransactionTemplate tx;
    static WatermarkService wm;
    static PlayToken playToken;
    static SegmentToken segmentToken;
    static WangkeProps props;
    static LessonKeyService keys;
    static PlayService play;
    static ProgressService progress;
    static CourseService courses;
    static Entitlements entitlements;

    static long L1, L2, L3;

    @BeforeAll
    static void bootstrap() throws Exception {
        ds = ItDb.freshDatabase(DB);
        // 这份迁移还没并进 schema.sql（DDL 已随交付给主控）。用 IF NOT EXISTS + 动态 ALTER
        // 写成可重复执行，合并之后这一行照样跑得过，不需要回来改测试。
        ItDb.runScript(rewrite(Files.readString(
                Paths.get("db/migrations/20260811_讲次内容密钥.sql"), StandardCharsets.UTF_8)));

        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        wm = new WatermarkService(jdbc, tx);
        playToken = new PlayToken(ItDb.testKey(7));
        keys = new LessonKeyService(jdbc, ItDb.testKey(9));
        entitlements = new Entitlements(jdbc);
        progress = new ProgressService(jdbc, tx, entitlements);
        courses = new CourseService(jdbc, entitlements);

        props = new WangkeProps();
        props.getPlay().setTokenTtlSeconds(90);
        segmentToken = new SegmentToken(ItDb.testKey(11));
        play = new PlayService(jdbc, entitlements, wm, playToken, segmentToken, keys, props,
                "https://cdn.example.test/hls");

        jdbc.update("UPDATE course SET status='ON', title='网课平台测试课' WHERE id=?", COURSE);
        L1 = newLesson(1, "第 1 讲");
        L2 = newLesson(2, "第 2 讲");
        L3 = newLesson(3, "第 3 讲");

        // 上架前的真实顺序：先建码本（按当时槽位容量定死码长），再分位区间，最后才能置 ON。
        // 反过来做的话 trg_lesson_publish_gate 会拒 —— 那条闸门就是为这个顺序存在的。
        wm.ensureScheme(COURSE, 1, 20, 1e-2);
        wm.assignBitOffsets(COURSE);
        // 内容入库流水线那一步：切片加密之前先封存每讲的密钥
        for (long l : new long[]{L1, L2, L3}) keys.ensureKey(l);
        jdbc.update("UPDATE lesson SET status='ON' WHERE course_id=?", COURSE);

        jdbc.update("INSERT INTO sys_user (id,phone,role) VALUES (?,'13300000201','STUDENT')", U_OK);
        jdbc.update("INSERT INTO sys_user (id,phone,role) VALUES (?,'13300000202','STUDENT')", U_NONE);
        jdbc.update("INSERT INTO sys_user (id,phone,role) VALUES (?,'13300000203','STUDENT')", U_EXPIRED);
        jdbc.update("INSERT INTO sys_user (id,phone,role) VALUES (?,'13300000204','STUDENT')", U_OTHER);

        grant(U_OK, "DATE_SUB(NOW(3), INTERVAL 1 DAY)", "DATE_ADD(NOW(3), INTERVAL 365 DAY)");
        grant(U_OTHER, "DATE_SUB(NOW(3), INTERVAL 1 DAY)", "DATE_ADD(NOW(3), INTERVAL 365 DAY)");
        // 过期权益：status 仍是 ACTIVE，只是 valid_until 过了。
        // 这是生产上真实的样子 —— 到期扫描是异步的，判定必须靠时间而不是靠 status。
        grant(U_EXPIRED, "DATE_SUB(NOW(3), INTERVAL 400 DAY)", "DATE_SUB(NOW(3), INTERVAL 1 DAY)");
    }

    @AfterAll
    static void teardown() { ItDb.close(ds); }

    /**
     * 播放列表里的分片行。
     *
     * <p>★ 原来这里是 {@code filter(l -> l.endsWith(".ts"))}。取片凭据挂上去之后
     * 每一行都以签名结尾，那个过滤器会<b>一条都匹配不到</b> —— 于是
     * {@code assertEquals(SEGS, ...count())} 会红，而 "分片地址必须落在分发域名下"
     * 那条会<b>静默变成空断言</b>：forEach 在空流上什么都不做，测试照常绿。
     * 按"不是注释行、不是空行"来认，才与 m3u8 的语法一致。
     */
    static java.util.stream.Stream<String> segmentLines(String m3u8) {
        return m3u8.lines().filter(l -> !l.isBlank() && !l.startsWith("#"));
    }

    static String rewrite(String sql) {
        return sql.replace("`wangke`", "`" + DB + "`").replace("USE `wangke`", "USE `" + DB + "`");
    }

    /**
     * 建一讲，连同它自己的一章。
     *
     * <p>章 = 一场录屏 = 一个片库目录，讲次拿的是这个目录里的一段片号区间。
     * 这里每讲各占一整个目录（区间 [0, SEGS)），是最简单的那种形态；
     * 「一个目录切给好几讲」那种由 {@link #midStoreLessonPlaysFromItsOwnRange} 覆盖。
     */
    static long newLesson(int sortNo, String title) {
        String key = "oss-private/course1/l" + sortNo;
        jdbc.update("INSERT INTO chapter (id,course_id,title,hls_key,store_segment_count,sort_no)"
                  + " VALUES (?,?,?,?,?,?)", sortNo, COURSE, "第 " + sortNo + " 章", key, SEGS, sortNo);
        jdbc.update("INSERT INTO lesson (chapter_id,course_id,title,sort_no,duration_sec,hls_key,"
                  + "segment_count,wm_slot_stride,status) VALUES (?,?,?,?,?,?,?,?,'DRAFT')",
                sortNo, COURSE, title, sortNo, DURATION, key, SEGS, STRIDE);
        long id = jdbc.queryForObject("SELECT id FROM lesson WHERE course_id=? AND sort_no=?",
                Number.class, COURSE, sortNo).longValue();
        jdbc.update("INSERT INTO lesson_segment_block (lesson_id,sort_no,seg_from,seg_to)"
                  + " VALUES (?,1,0,?)", id, SEGS);
        return id;
    }

    static void grant(long userId, String fromExpr, String untilExpr) {
        jdbc.update("INSERT INTO enrollment (user_id,course_id,source,valid_from,valid_until,"
                  + "agreement_version,agreed_at) VALUES (?,?,'GRANT'," + fromExpr + "," + untilExpr
                  + ",'v-it',NOW(3))", userId, COURSE);
    }

    static ApiException expect(int status, String mustContain, Executable body) {
        var e = assertThrows(ApiException.class, body);
        assertEquals(status, e.status(),
                "状态码不对（实际 " + e.status() + "：" + e.getMessage() + "）");
        assertTrue(e.getMessage().contains(mustContain),
                "失败原因不是要测的那条规则。期望包含「" + mustContain + "」，实际：" + e.getMessage());
        return e;
    }

    interface Executable extends org.junit.jupiter.api.function.Executable {}

    // ================================================================== 权益

    @Test @Order(1)
    @DisplayName("有权益才能开播；开播写下一行 play_session，含设备、IP、ip_region 与水印码字")
    void openHappyPath() {
        var opened = play.open(U_OK, L1, DEV_A, "Chrome / Windows", IP, "it-agent");
        assertNotNull(opened.token());
        assertEquals(DURATION, opened.durationSec());

        var s = jdbc.queryForMap("SELECT * FROM play_session WHERE session_id=?", opened.sessionId());
        assertEquals(U_OK, ((Number) s.get("user_id")).longValue());
        assertEquals(L1, ((Number) s.get("lesson_id")).longValue());
        assertEquals(DEV_A, s.get("device_id"));
        assertEquals(IP, s.get("client_ip"));
        assertNotNull(s.get("wm_codeword_id"),
                "会话没记下用的是哪份水印码字 —— 真出泄漏时，这一次投递查不到是谁的码字");
        // ip_region 目前没有接 GeoIP。写 UNRESOLVED 前缀而不是编一个地名：
        // 编出来的地名会让不可能旅行检测算出一堆基于假坐标的结论，且永远不报错。
        assertTrue(String.valueOf(s.get("ip_region")).startsWith("UNRESOLVED/"),
                "ip_region 写成了一个看起来像地名、其实是编的值：" + s.get("ip_region"));

        String m3u8 = play.playlist(opened.token(), L1, DEV_A, IP, U_OK);
        assertTrue(m3u8.startsWith("#EXTM3U"), m3u8.substring(0, Math.min(40, m3u8.length())));
        assertEquals(SEGS, segmentLines(m3u8).count());
        assertTrue(m3u8.contains("#EXT-X-KEY:METHOD=AES-128,URI=\"/api/play/key?lessonId=" + L1),
                "密钥 URI 没带 lessonId —— PlayToken.verify 就没有东西可比，串讲检查形同虚设");

        // 令牌续签必须留在同一个会话，并且真的刷新了 last_seen_at。
        //
        // ★ 原来这里是 assertNotNull(last_seen_at)，那是一条恒真的断言：
        // 这一列是 NOT NULL DEFAULT CURRENT_TIMESTAMP(3)，INSERT 那一刻就已经非空，
        // 把 renew 里那句 UPDATE 整句删掉它照样绿 —— 而它想钉的恰恰是那句 UPDATE。
        // 先把时间拨回一小时，让"有没有被写过"这件事变得可观测。
        jdbc.update("UPDATE play_session SET last_seen_at=DATE_SUB(NOW(3), INTERVAL 1 HOUR)"
                  + " WHERE session_id=?", opened.sessionId());
        var renewed = play.renew(U_OK, opened.sessionId(), DEV_A, IP);
        assertEquals(opened.sessionId(), renewed.sessionId(), "续签不该新建播放会话");
        assertEquals(1, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM play_session WHERE session_id=?", Integer.class, opened.sessionId()));
        assertEquals(1, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM play_session WHERE session_id=?"
              + " AND last_seen_at > DATE_SUB(NOW(3), INTERVAL 1 MINUTE)",
                Integer.class, opened.sessionId()),
                "续签没有刷新 last_seen_at —— 长视频播到约 90 秒就会因为这一列停在开播时刻而断");
    }

    @Test @Order(2)
    @DisplayName("★ 无权益：拿得到合法令牌也取不到 m3u8，且提示不泄露课程/讲次是否存在")
    void noEntitlementCannotFetchM3u8() {
        // 开播那条路先被挡住
        var e1 = expect(403, Entitlements.DENIED, () -> play.open(U_NONE, L1, DEV_A, null, IP, null));

        // 更关键的一条：令牌本身是我们亲手签的、完全合法的，权益复查仍然必须挡住它。
        // 少了这一层，任何一张过去签发过的令牌在 90 秒内都是一张免检通行证。
        String forged = playToken.issue(new PlayToken.Claims(
                "sess-forged-0001", U_NONE, L1, DEV_A, PlayToken.ipScope(IP),
                Instant.now().plusSeconds(60)));
        var e2 = expect(403, Entitlements.DENIED,
                () -> play.playlist(forged, L1, DEV_A, IP, U_NONE));

        // 不存在的讲次必须给出【逐字相同】的一句话。不同的话（哪怕只是 404 vs 403）
        // 就是一个课程/讲次 id 枚举器，而它每次返回的都是错误码，看起来完全正常。
        var e3 = assertThrows(ApiException.class,
                () -> entitlements.requireLesson(U_NONE, 999_999L));
        assertEquals(e1.getMessage(), e3.getMessage(),
                "「无权益」与「讲次不存在」的提示不一样，等于免费提供一个 id 枚举器");
        assertEquals(e2.getMessage(), e3.getMessage());
        assertEquals(403, e3.status());

        assertEquals(0, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM play_session WHERE user_id=?", Integer.class, U_NONE),
                "被拒的开播留下了一行播放会话");
        assertEquals(0, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_device WHERE user_id=?", Integer.class, U_NONE),
                "没有权益的人占掉了一个设备位 —— 说明先绑设备后验权益，顺序反了");
    }

    @Test @Order(3)
    @DisplayName("★ 权益过期不能播：status 还是 ACTIVE，判定必须落在 valid_until 上")
    void expiredEntitlementCannotPlay() {
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT status FROM enrollment WHERE user_id=?", String.class, U_EXPIRED),
                "这个用例要测的是「时间过了」，如果 status 已经不是 ACTIVE，"
              + "它就退化成在测「状态不对」—— 通过时什么也没证明");

        expect(403, Entitlements.DENIED, () -> play.open(U_EXPIRED, L1, DEV_A, null, IP, null));
        expect(403, Entitlements.DENIED, () -> progress.report(U_EXPIRED, L1, 10));
        expect(403, Entitlements.DENIED, () -> courses.lessonsOf(U_EXPIRED, COURSE));

        // 「我的课程」照样列得出来，但标着已过期：学员要看得见自己买过什么。
        var mine = courses.myCourses(U_EXPIRED);
        assertEquals(1, mine.size());
        assertEquals(true, mine.get(0).get("expired"), "过期的权益没有被标记为已过期");
    }

    // ================================================================== 设备上限

    @Test @Order(4)
    @DisplayName("★ 第 3 台设备被拒，且给的是人话不是触发器原文；被拒时不留会话")
    void thirdDeviceIsRejectedWithHumanMessage() {
        play.open(U_OK, L1, DEV_A, "Chrome / Windows", IP, null);      // 第 1 台（已绑过，幂等）
        play.open(U_OK, L2, DEV_B, "Safari / iPhone", IP, null);       // 第 2 台
        assertEquals(2, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_device WHERE user_id=? AND status='ACTIVE'",
                Integer.class, U_OK));

        int sessionsBefore = (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM play_session WHERE user_id=?", Integer.class, U_OK);

        var e = expect(403, "设备管理", () -> play.open(U_OK, L1, DEV_C, "Edge / Windows", IP, null));

        // 触发器那句话是写给 DBA 的，不能原样怼到学员脸上。这里钉的是「它没有出现」，
        // 而不是笼统地"有个提示" —— 否则把 catch 去掉、让 45000 直接透出来，这条也是绿的。
        assertFalse(e.getMessage().contains("请先解绑再换设备"),
                "触发器原文直接透给了用户：" + e.getMessage());
        assertTrue(e.getMessage().contains("Chrome / Windows") && e.getMessage().contains("Safari / iPhone"),
                "没有告诉学员已经绑了哪两台，他不知道该解绑谁：" + e.getMessage());

        assertEquals(2, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_device WHERE user_id=? AND status='ACTIVE'",
                Integer.class, U_OK), "第 3 台设备被拒了，却还是绑上了");
        assertEquals(sessionsBefore, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM play_session WHERE user_id=?", Integer.class, U_OK),
                "设备被拒之后仍然开出了一次播放会话 —— 令牌已经签发，上限就是摆设");

        // 解绑一台之后第 3 台就能进：证明拦住它的是上限本身，不是这台设备被拉黑了
        jdbc.update("UPDATE user_device SET status='REMOVED', removed_at=NOW(3) "
                  + "WHERE user_id=? AND device_id=?", U_OK, DEV_B);
        assertNotNull(play.open(U_OK, L1, DEV_C, "Edge / Windows", IP, null).token());
        jdbc.update("UPDATE user_device SET status='REMOVED', removed_at=NOW(3) "
                  + "WHERE user_id=? AND device_id=?", U_OK, DEV_C);
        jdbc.update("UPDATE user_device SET status='ACTIVE', removed_at=NULL "
                  + "WHERE user_id=? AND device_id=?", U_OK, DEV_B);
    }

    // ================================================================== 令牌绑讲次

    @Test @Order(5)
    @DisplayName("★ 令牌串讲：第 1 讲的令牌取不到第 2 讲的密钥（含阴性对照）")
    void tokenIsBoundToItsLesson() {
        var opened = play.open(U_OK, L1, DEV_A, null, IP, null);

        // 阴性对照：同一张令牌取【本讲】密钥必须成功，而且拿到的就是库里封存的那一把。
        // 没有这条，下面那个负例可能只是因为"密钥这条路整个不通"而绿。
        byte[] k1 = play.contentKey(opened.token(), L1, DEV_A, IP, U_OK);
        assertEquals(16, k1.length, "AES-128 内容密钥必须是 16 字节");
        assertArrayEquals(keys.contentKey(L1), k1);

        var e = expect(403, "令牌不是这一讲的",
                () -> play.contentKey(opened.token(), L2, DEV_A, IP, U_OK));
        assertFalse(e.getMessage().contains("设备不匹配"), "拒绝理由跑偏到设备上了：" + e.getMessage());

        // m3u8 那条路同样绑讲次
        expect(403, "令牌不是这一讲的", () -> play.playlist(opened.token(), L2, DEV_A, IP, U_OK));

        // 顺带钉住另外两条绑定，它们和讲次是同一个机制的三条腿
        expect(403, "设备不匹配", () -> play.contentKey(opened.token(), L1, DEV_B, IP, U_OK));
        expect(403, "来源网段不匹配", () -> play.contentKey(opened.token(), L1, DEV_A, "198.51.100.7", U_OK));
        expect(403, "不属于当前登录账号",
                () -> play.contentKey(opened.token(), L1, DEV_A, IP, U_NONE));

        // 三讲的密钥必须两两不同：一把密钥打全课程时，一讲泄漏 = 全课程泄漏
        byte[] k2 = keys.contentKey(L2), k3 = keys.contentKey(L3);
        assertFalse(java.util.Arrays.equals(k1, k2), "第 1、2 讲共用了同一把内容密钥");
        assertFalse(java.util.Arrays.equals(k2, k3), "第 2、3 讲共用了同一把内容密钥");
    }

    @Test @Order(6)
    @DisplayName("内容密钥密文存库：库里那 44 字节既不是明文密钥，也不能搬到别的讲上用")
    void contentKeyIsSealedAndBoundToItsLesson() {
        byte[] enc1 = jdbc.queryForObject(
                "SELECT key_enc FROM lesson_content_key WHERE lesson_id=? AND status='ACTIVE'",
                byte[].class, L1);
        byte[] plain1 = keys.contentKey(L1);
        assertEquals(44, enc1.length, "12 字节 IV + 16 字节密文 + 16 字节 tag = 44");
        assertFalse(java.util.Arrays.equals(java.util.Arrays.copyOf(enc1, 16), plain1),
                "库里存的就是明文密钥 —— 这张表存在的全部意义在这一刻被取消了");

        // 把第 1 讲的密文搬到第 2 讲那一行：AAD 绑着 (讲次, 版本)，解密必须当场失败，
        // 而不是安静地解出第 1 讲的密钥。加密本身拦不住「密文搬家」。
        byte[] enc2 = jdbc.queryForObject(
                "SELECT key_enc FROM lesson_content_key WHERE lesson_id=? AND status='ACTIVE'",
                byte[].class, L2);
        var e = assertThrows(IllegalStateException.class, () -> keys.open(enc1, L2, 1));
        assertTrue(e.getMessage().contains("被搬过家"), e.getMessage());
        assertArrayEquals(enc2, jdbc.queryForObject(
                "SELECT key_enc FROM lesson_content_key WHERE lesson_id=? AND status='ACTIVE'",
                byte[].class, L2), "这个用例改动了库里的密文");

        // ensureKey 幂等且绝不覆盖：覆盖一把已经用于加密分片的密钥 = 那一讲从此没人播得了
        assertArrayEquals(plain1, keys.ensureKey(L1),
                "重复调用 ensureKey 换掉了已有的密钥，分片将永远解不开");
        assertEquals(1, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM lesson_content_key WHERE lesson_id=?", Integer.class, L1));
    }

    // ================================================================== 进度防刷

    @Test @Order(7)
    @DisplayName("★ 倒退式刷满：一次拖到片尾，max_position 到顶而进度与「已学完」纹丝不动")
    void draggingToTheEndDoesNotCompleteTheLesson() {
        var opened = play.open(U_OK, L3, DEV_A, null, IP, null);
        String sess = opened.sessionId();

        // 起播那一拍：还没有"上次记账时刻"，没有任何区间可以计费
        var first = progress.heartbeat(U_OK, L3, sess, 3, 1.0, IP, DEV_A);
        assertEquals(0, first.creditedSec(), "起播第一拍就发了有效观看时长，那一段时间没有任何凭据");

        // 直接把进度条拖到片尾
        var dragged = progress.report(U_OK, L3, DURATION);
        assertEquals(DURATION, dragged.maxPositionSec(), "断点续播位置应当跟着拖动走");
        assertEquals(0, dragged.progressPct(),
                "进度百分比跟着拖动涨了 —— 说明它是按 max_position 算的，"
              + "那么『已学完』就是假的，而结业证书依赖它");
        assertFalse(dragged.completed(), "拖一下就学完了");

        // 连打 20 次心跳，墙钟几乎没走。次数撑不起时长，闸门必须按墙钟发放而不是按次数。
        for (int i = 0; i < 20; i++) progress.heartbeat(U_OK, L3, sess, DURATION, 3.0, IP, DEV_A);
        var spam = progress.report(U_OK, L3, DURATION);
        assertTrue(spam.watchedSec() <= 10,
                "20 次心跳在几乎零墙钟里刷出了 " + spam.watchedSec() + " 秒有效观看时长："
              + "记账是按心跳次数发的，而心跳是客户端说了算的");
        assertFalse(spam.completed(), "刷心跳就学完了");

        // 进度只进不退：报一个更小的位置，最远位置不许被回冲（多端合并取最大值）
        assertEquals(DURATION, progress.report(U_OK, L3, 100).maxPositionSec(),
                "旧端的落后进度把最远位置冲掉了");

        // 明细必须逐条落库，事后可复盘
        assertEquals(21, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM learn_heartbeat WHERE user_id=? AND lesson_id=? AND session_id=?",
                Integer.class, U_OK, L3, sess), "心跳明细没有逐条落 learn_heartbeat");
    }

    @Test @Order(8)
    @DisplayName("闸门确实在放行，只是有上限：静默一小时后补一次心跳，最多领 60 秒")
    void wallClockGateStillCreditsButIsCapped() {
        var opened = play.open(U_OK, L3, DEV_A, null, IP, null);
        // 把记账时钟拨回一小时，模拟"客户端断线一小时后补发一次心跳"
        jdbc.update("UPDATE learn_progress SET last_credit_at=DATE_SUB(NOW(3), INTERVAL 1 HOUR) "
                  + "WHERE user_id=? AND lesson_id=?", U_OK, L3);
        var s = progress.heartbeat(U_OK, L3, opened.sessionId(), DURATION, 1.0, IP, DEV_A);
        assertEquals(ProgressService.MAX_CREDIT_PER_BEAT_SEC, s.creditedSec(),
                "一次心跳领走了 " + s.creditedSec() + " 秒：静默越久领得越多，"
              + "那么断线重连就是最省事的刷进度手法");
        // 这个 60 与 trg_progress_sanity 的 heartbeat_count*60 是同一个数：
        // 应用层闸门贴着库层天花板，正常路径上库层永远不会拒 —— 拒了就一定是真出问题。
        assertTrue(s.watchedSec() <= s.heartbeatCount() * 60,
                "watched_sec 超过了 heartbeat_count×60，库层的勾稽约束下一次写入就会炸");
    }

    @Test @Order(9)
    @DisplayName("完成判据是「看够了」且「看到过尾」，两条都真时才置 completed")
    void completionNeedsBothWatchedAndPosition() {
        // 只看够、没看到尾：把 watched 拉满但最远位置只到 10%。
        // 必须先删再插，不能 UPDATE 把 max_position 压回去 —— trg_progress_sanity_upd
        // 那条「进度只能前进」会拒，而拒绝信息看起来像是被测代码的问题。
        jdbc.update("DELETE FROM learn_progress WHERE user_id=? AND lesson_id=?", U_OK, L3);
        jdbc.update("INSERT INTO learn_progress (user_id,course_id,lesson_id,max_position_sec,"
                  + "watched_sec,heartbeat_count) VALUES (?,?,?,?,?,?)",
                U_OK, COURSE, L3, (int) (DURATION * 0.10), (int) (DURATION * 0.95), 400);
        var onlyWatched = progress.report(U_OK, L3, (int) (DURATION * 0.10));
        assertFalse(onlyWatched.completed(),
                "只靠 watched_sec 就判完成 —— 把开头五分钟循环一百遍即可刷满");

        // 补上"看到过尾"，两条同时成立
        var both = progress.report(U_OK, L3, DURATION);
        assertTrue(both.completed(), "两条判据都满足了却没判完成");
        assertTrue(both.progressPct() >= 95);
        assertNotNull(jdbc.queryForObject(
                "SELECT completed_at FROM learn_progress WHERE user_id=? AND lesson_id=?",
                java.time.LocalDateTime.class, U_OK, L3), "completed_at 没有落库");
    }

    @Test @Order(10)
    @DisplayName("心跳必须挂在自己的、这一讲的、未结束的会话上")
    void heartbeatRequiresOwnLiveSession() {
        var mine = play.open(U_OK, L1, DEV_A, null, IP, null);

        expect(403, "播放会话无效",
                () -> progress.heartbeat(U_OK, L1, "sess-made-up-0001", 5, 1.0, IP, DEV_A));
        // 会话是别人的：归属条件写在 SQL 的 WHERE 里，查不到就是查不到。
        // 这里用的是【同样有权益】的另一个学员 —— 用没权益的人试，挡住他的会是权益闸门，
        // 用例照样绿，而「会话归属」这条规则一次都没被执行到。
        expect(403, "播放会话无效",
                () -> progress.heartbeat(U_OTHER, L1, mine.sessionId(), 5, 1.0, IP, DEV_A));
        // 会话是本人的、但属于另一讲
        expect(403, "播放会话无效",
                () -> progress.heartbeat(U_OK, L2, mine.sessionId(), 5, 1.0, IP, DEV_A));

        jdbc.update("UPDATE play_session SET ended_at=NOW(3) WHERE session_id=?", mine.sessionId());
        expect(403, "播放会话无效",
                () -> progress.heartbeat(U_OK, L1, mine.sessionId(), 5, 1.0, IP, DEV_A));
    }

    @Test @Order(11)
    @DisplayName("倍速与位置的物理上界在进库前就挡掉，报的是人话不是约束名")
    void rateAndPositionAreBoundedBeforeTheDatabase() {
        var opened = play.open(U_OK, L2, DEV_A, null, IP, null);
        String sess = opened.sessionId();

        var fast = expect(400, "播放倍速",
                () -> progress.heartbeat(U_OK, L2, sess, 10, 9.9, IP, DEV_A));
        assertFalse(fast.getMessage().contains("ck_heartbeat_rate"),
                "把数据库约束名怼给了用户：" + fast.getMessage());
        expect(400, "播放倍速", () -> progress.heartbeat(U_OK, L2, sess, 10, 0.1, IP, DEV_A));

        expect(400, "超过本讲时长", () -> progress.report(U_OK, L2, DURATION + 600));
        expect(400, "不能为负数", () -> progress.report(U_OK, L2, -1));

        // 差一两秒是转码时长与录入时长的正常出入，夹取而不是报错
        assertEquals(DURATION, progress.report(U_OK, L2, DURATION + 1).maxPositionSec());

        assertEquals(0, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM learn_heartbeat WHERE session_id=? AND playback_rate > 3.0",
                Integer.class, sess), "越界的倍速被写进了心跳明细");
    }

    // ================================================================== 不下发直链

    @Test @Order(12)
    @DisplayName("★ hls_key 不出现在任何 JSON 响应里；m3u8 也不指向私有桶")
    void hlsKeyNeverLeavesTheServerAsJson() {
        String secret = jdbc.queryForObject("SELECT hls_key FROM lesson WHERE id=?", String.class, L1);
        assertNotNull(secret);

        assertFalse(courses.publicCourse(COURSE).toString().contains(secret),
                "公开课程介绍里带出了私有桶对象前缀");
        assertFalse(courses.lessonsOf(U_OK, COURSE).toString().contains(secret),
                "讲次列表里带出了私有桶对象前缀");
        assertFalse(courses.myCourses(U_OK).toString().contains(secret));

        // 分片地址只能落在配置的分发域名下。m3u8 里必须出现对象路径（播放器要靠它取片），
        // 但它前面必须是 CDN 域名 —— 直接给 OSS 私有桶签名直链才是 schema 说的「下发直链」。
        var opened = play.open(U_OK, L1, DEV_A, null, IP, null);
        String m3u8 = play.playlist(opened.token(), L1, DEV_A, IP, U_OK);
        segmentLines(m3u8).forEach(l ->
                assertTrue(l.startsWith("https://cdn.example.test/hls/"),
                        "分片地址没走分发域名：" + l));

        // 公开介绍页只列已上架讲次，且不带任何进度字段（未登录的人不该有进度）
        var pub = courses.publicCourse(COURSE);
        assertEquals(3, pub.get("totalLessons"));
        assertFalse(pub.toString().contains("watchedSec"), "公开页带出了学习进度字段");
    }

    @Test @Order(13)
    @DisplayName("我的课程：有效期与进度百分比按 watched_sec 算，且逐讲夹取不会超过 100%")
    void myCoursesReportsWatchedBasedProgress() {
        // 把某一讲的 watched_sec 造成"比重新剪辑后的时长还长"，检验 LEAST 夹取
        jdbc.update("UPDATE learn_progress SET watched_sec=?, heartbeat_count=? "
                  + "WHERE user_id=? AND lesson_id=?", DURATION, 400, U_OK, L3);
        jdbc.update("UPDATE lesson SET duration_sec=? WHERE id=?", DURATION / 2, L3);
        try {
            var mine = courses.myCourses(U_OK);
            assertEquals(1, mine.size());
            var c = mine.get(0);
            assertEquals(false, c.get("expired"));
            assertNotNull(c.get("validUntil"), "没给有效期，学员看不到什么时候到期");
            assertTrue((int) c.get("progressPct") <= 100,
                    "进度超过 100%：某一讲被重新剪短之后，旧的 watched_sec 直接相加会溢出百分比");
        } finally {
            jdbc.update("UPDATE lesson SET duration_sec=? WHERE id=?", DURATION, L3);
        }
    }

    @Test @Order(14)
    @DisplayName("讲次列表：没看过的讲次也必须在目录里（LEFT JOIN 不能退化成 INNER JOIN）")
    void lessonListKeepsUnwatchedLessons() {
        var out = courses.lessonsOf(U_OK, COURSE);
        @SuppressWarnings("unchecked")
        var chapters = (List<java.util.Map<String, Object>>) out.get("chapters");
        assertEquals(3, chapters.size(), "夹具里每讲各占一章（见 newLesson）");
        // 讲次数按【全课程】数，不按某一章数：章下有几讲是夹具的形状，
        // 而这条用例钉的是「没看过的讲次不许消失」，跟分章方式无关。
        var lessons = allLessons(chapters);
        assertEquals(3, lessons.size(),
                "目录里只剩 " + lessons.size() + " 讲：进度表的过滤条件写进了 WHERE，"
              + "把 LEFT JOIN 退化成 INNER JOIN，没看过的讲次整条消失了");
        assertTrue(lessons.stream().allMatch(l -> l.containsKey("progressPct")));
    }

    // ================================================================== 设备指纹

    @Test @Order(15)
    @DisplayName("设备标识在开播这一端就校验：含 '|' 的话会把令牌载荷切错段")
    void deviceIdIsValidatedAtTheDoor() {
        var e = expect(400, "非法字符", () -> play.open(U_OK, L1, "dev|A0000000001", null, IP, null));
        assertFalse(e.getMessage().contains("令牌字段数"),
                "错误发生在签令牌那一步了，报的是「令牌字段数不对」，"
              + "而根因在设备指纹上 —— 查起来毫无线索：" + e.getMessage());
        expect(400, "设备标识不合法", () -> play.open(U_OK, L1, "abc", null, IP, null));
        expect(400, "设备标识不合法", () -> play.open(U_OK, L1, "  ", null, IP, null));
    }

    @Test @Order(16)
    @DisplayName("会话 id 与水印码字：同一人两次开播共用一份码字，但每次会话各自留痕")
    void sessionsShareOneCodewordPerCourse() {
        var a = play.open(U_OK, L1, DEV_A, null, IP, null);
        var b = play.open(U_OK, L2, DEV_A, null, IP, null);
        assertNotEquals(a.sessionId(), b.sessionId());
        assertEquals(1, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM wm_codeword WHERE user_id=? AND course_id=?",
                Integer.class, U_OK, COURSE),
                "同一个人拿到了第二份码字：取证时两份都不完全匹配，分数被摊薄到阈值以下");
        assertEquals(1, (int) jdbc.queryForObject(
                "SELECT COUNT(DISTINCT wm_codeword_id) FROM play_session WHERE user_id=?",
                Integer.class, U_OK));
        assertEquals(HexFormat.of().parseHex(a.sessionId()).length, 16, "会话 id 不是 16 字节随机数");
    }

    // ============================================================ 先导课（试听）
    //
    // 会议原话：「课程列表……还能看到一个先导课」「你这边到时候拍个视频放上去」。
    // 在这之前 is_preview 只是一个标记位：三条播放路径全部要 enrollment，
    // 也就是说【没买课的人根本点不开它】，那个「试听」标签是装饰。

    @Test @Order(60)
    @DisplayName("★ 没买课的人能看试听讲，但正片仍然一秒都看不到")
    void previewIsWatchableWithoutEnrollment() {
        long preview = newLesson(91, "先导课");
        jdbc.update("UPDATE lesson SET is_preview=1, status='ON' WHERE id=?", preview);

        // U_NONE 没有任何权益
        assertEquals(0, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM enrollment WHERE user_id=?", Integer.class, U_NONE),
                "前提没成立：这个账号有权益的话，这条用例测不到东西");

        var opened = play.open(U_NONE, preview, DEV_A, "试听设备", IP, "UA");
        assertNotNull(opened.token());
        assertEquals("先导课", opened.title());

        // ★ 阴性对照：同一个人对正片仍然是 403。少了这一句，
        //   一个"把权益闸门整个拆掉"的实现也会让上面全绿。
        expect(403, Entitlements.DENIED, () -> play.open(U_NONE, L1, DEV_A, "x", IP, "UA"));
    }

    @Test @Order(61)
    @DisplayName("★ 试听不占设备位、不发码字 —— 两者都会在他真的买课时反咬一口")
    void previewCostsNeitherDeviceSlotNorCodeword() {
        long preview = newLesson(92, "先导课二");
        jdbc.update("UPDATE lesson SET is_preview=1, status='ON' WHERE id=?", preview);

        int devBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_device WHERE user_id=?", Integer.class, U_NONE);
        int cwBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM wm_codeword WHERE user_id=?", Integer.class, U_NONE);

        // 三台不同设备各看一次试听：设备上限是【已购课程】的防共享手段，
        // 还没买课就先被扣额度的话，他买完课在第三台机器上会打不开，
        // 而原因是"他之前试听过"—— 这种因果没有任何人能自己想明白。
        play.open(U_NONE, preview, DEV_A, "甲", IP, "UA");
        play.open(U_NONE, preview, DEV_B, "乙", IP, "UA");
        play.open(U_NONE, preview, DEV_C, "丙", IP, "UA");

        assertEquals(devBefore, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_device WHERE user_id=?", Integer.class, U_NONE),
                "试听把设备位占了");
        assertEquals(cwBefore, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM wm_codeword WHERE user_id=?", Integer.class, U_NONE),
                "试听发了码字 —— 码本容量有限，会被没买课的人填满");
        assertNull(jdbc.queryForObject(
                "SELECT wm_codeword_id FROM play_session WHERE user_id=? AND lesson_id=? ORDER BY id DESC LIMIT 1",
                Long.class, U_NONE, preview), "会话上挂了码字");
    }

    @Test @Order(62)
    @DisplayName("★ 试听的播放列表不引用 _a/_b：素材里根本没有那两份，引用了就是每个槽位 404")
    void previewPlaylistHasNoVariants() {
        long preview = newLesson(93, "先导课三");
        jdbc.update("UPDATE lesson SET is_preview=1, status='ON' WHERE id=?", preview);

        var opened = play.open(U_NONE, preview, DEV_A, "甲", IP, "UA");
        String m3u8 = play.playlist(opened.token(), preview, DEV_A, IP, U_NONE);

        assertFalse(m3u8.contains("_a.ts"), "试听播放列表引用了 A 变体");
        assertFalse(m3u8.contains("_b.ts"), "试听播放列表引用了 B 变体");
        assertTrue(m3u8.contains("/0.ts"), "连普通分片都没有，这份播放列表是空的");
        assertTrue(m3u8.contains("#EXT-X-KEY"), "试听没加密 —— 加密与水印是两件事，不该一起豁免");

        // ★ 阴性对照：正片那一份必须仍然带 A/B，否则上面三句可能只是"投递整个坏了"
        var paid = play.open(U_OK, L1, DEV_A, "甲", IP, "UA");
        String full = play.playlist(paid.token(), L1, DEV_A, IP, U_OK);
        assertTrue(full.contains("_a.ts") || full.contains("_b.ts"),
                "正片的播放列表也不带变体了 —— 取证水印被一起关掉了");
    }

    /**
     * ★ 投递形态跟【素材】走，不跟【观看者】走。
     *
     * <p>第一版把这两件事合成了一个布尔（preview = 观看者没有权益），于是
     * 已购学员点开先导课会拿到按 A/B 引用的播放列表 —— 而试听素材磁盘上
     * 根本没有 _a/_b 两份，每个槽位 404，hls.js 无限重试，画面不动且一句错都不报。
     * 同一讲对付过钱的人反而播不了，对没付钱的人正常。
     */
    @Test @Order(63)
    @DisplayName("★ 已购学员点开先导课，拿到的也必须是不带 A/B 的那一份（素材里没有变体）")
    void previewDeliveryFollowsMaterialNotViewer() {
        long preview = newLesson(94, "先导课四");
        jdbc.update("UPDATE lesson SET is_preview=1, status='ON' WHERE id=?", preview);

        var opened = play.open(U_OK, preview, DEV_A, "甲", IP, "UA");   // U_OK 权益有效
        String m3u8 = play.playlist(opened.token(), preview, DEV_A, IP, U_OK);
        assertFalse(m3u8.contains("_a.ts") || m3u8.contains("_b.ts"),
                "已购学员的先导课播放列表引用了 A/B —— 那些分片不存在，每个槽位都会 404");

        // 试听讲对谁都没有槽位可用，码字发了也无处投递
        assertNull(jdbc.queryForObject(
                "SELECT wm_codeword_id FROM play_session WHERE session_id=?",
                Long.class, opened.sessionId()),
                "先导课给已购学员发了码字 —— 白占码本容量，而且它一位都投不出去");

        // 但设备位照绑：他是【已购学员】，防共享那条规则对他照常成立
        assertTrue(jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_device WHERE user_id=? AND device_id=?",
                Integer.class, U_OK, DEV_A) > 0,
                "已购学员看先导课没绑设备 —— 防共享对他是该生效的");
    }

    /**
     * ★ 课程还没建码本时，先导课对所有人都必须能播。
     *
     * <p>真实顺序就是这样：会议说「先拍个先导课放上去」，正片还没做完，
     * 于是课程没有 wm_scheme。这时候如果发码字那一步看的是"观看者有没有权益"，
     * <b>被赠课的内部同事反而 409，路人却能看</b>。
     */
    @Test @Order(64)
    @DisplayName("★ 课程还没有水印码本时，先导课对已购与未购都能开播")
    void previewPlaysBeforeAnyCodebookExists() {
        long courseId = 2L;
        jdbc.update("INSERT INTO course (id,title,status,activate_months,access_months)"
                  + " VALUES (?,'只有先导课的新课','ON',1,12)"
                  + " ON DUPLICATE KEY UPDATE status='ON'", courseId);
        jdbc.update("INSERT INTO chapter (id,course_id,title,hls_key,store_segment_count,sort_no)"
                  + " VALUES (900,?,'第一章','oss-private/course2/l1',?,1)"
                  + " ON DUPLICATE KEY UPDATE course_id=VALUES(course_id)", courseId, SEGS);
        jdbc.update("INSERT INTO lesson (chapter_id,course_id,title,sort_no,duration_sec,hls_key,"
                  + "segment_count,wm_slot_stride,is_preview,status)"
                  + " VALUES (900,?,'新课先导',1,?,?,?,?,1,'ON')",
                courseId, DURATION, "oss-private/course2/l1", SEGS, STRIDE);
        long lesson = jdbc.queryForObject(
                "SELECT id FROM lesson WHERE course_id=? AND sort_no=1", Long.class, courseId);
        jdbc.update("INSERT IGNORE INTO lesson_segment_block (lesson_id,sort_no,seg_from,seg_to)"
                  + " VALUES (?,1,0,?)", lesson, SEGS);
        assertEquals(0, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM wm_scheme WHERE course_id=?", Integer.class, courseId),
                "前提没成立：这门课已经有码本了，测不到要测的东西");

        // 未购：能看
        assertNotNull(play.open(U_NONE, lesson, DEV_A, "甲", IP, "UA").token());

        // 已购（赠课）：同样能看 —— 这一条才是原来会 409 的那一半
        grantTo(U_OK, courseId);
        var opened = play.open(U_OK, lesson, DEV_B, "乙", IP, "UA");
        assertNotNull(opened.token(),
                "被赠课的人打不开先导课，而路人打得开 —— 发码字那一步看错了对象");
    }

    // ================================================================== 内容分节

    /**
     * ★ 分节是「挂在讲次上的时间区间」，不是更多讲次。
     *
     * <p>拆讲次那条路走不通，理由写在 {@code db/migrations/20260821_讲次内容分节.sql} 里：
     * 水印位区间在码字发出之后不许重排、分片文件按讲次目录从 0 编号、
     * 进度按 (人, 讲次) 唯一。这一组钉的是<b>库里那三条不变量</b>与<b>出网形状</b>。
     */
    @Test @Order(80)
    @DisplayName("★ 分节出现在目录里，且 passed/current 按最远播放位置算")
    void sectionsShowUpInTheOutline() {
        jdbc.update("DELETE FROM lesson_section WHERE lesson_id=?", L1);
        insertSection(L1, 1, "开场：今天讲什么", 0, 600, "LECTURE");
        insertSection(L1, 2, "现场演示：从空目录起一个项目", 600, 1800, "DEMO");
        insertSection(L1, 3, "答疑", 1800, DURATION, "QA");

        // 把他的最远播放位置放在第 2 节里
        jdbc.update("INSERT INTO learn_progress (user_id,course_id,lesson_id,max_position_sec,"
                  + "watched_sec,heartbeat_count) VALUES (?,?,?,?,?,?) "
                  + "ON DUPLICATE KEY UPDATE "
                  + "max_position_sec=GREATEST(VALUES(max_position_sec), max_position_sec), "
                  + "watched_sec=GREATEST(VALUES(watched_sec), watched_sec), "
                  + "heartbeat_count=GREATEST(VALUES(heartbeat_count), heartbeat_count)",
                U_OK, COURSE, L1, 1200, 900, 100);

        var chapters = cast(courses.lessonsOf(U_OK, COURSE).get("chapters"));
        var lesson = lessonIn(chapters, L1);
        var secs = cast(lesson.get("sections"));
        assertEquals(3, secs.size(), "目录里没带分节");
        assertEquals("开场：今天讲什么", secs.get(0).get("title"));
        assertEquals(600, secs.get(1).get("startSec"));
        assertEquals("DEMO", secs.get(1).get("kind"));

        assertEquals(true,  secs.get(0).get("passed"),  "播放位置已经越过第 1 节，却没标 passed");
        assertEquals(false, secs.get(0).get("current"));
        assertEquals(false, secs.get(1).get("passed"));
        assertEquals(true,  secs.get(1).get("current"), "上次停在第 2 节里，却没标 current");
        assertEquals(false, secs.get(2).get("passed"));

        // ★ 不许出 completed / 百分比这类"学过了"的字眼：手里只有一个最远播放位置，
        // 一个把进度条拖到片尾的人会让整讲每一节都变成"已完成"。
        assertFalse(secs.get(0).containsKey("completed"), "分节上出现了 completed —— 这份数据撑不起那句话");
        assertFalse(secs.get(0).containsKey("progressPct"));
    }

    /**
     * ★ 从没打开过这一讲的人，一节都不许是 current。
     *
     * <p>他的 max_position_sec 是 0，而 0 落在第一节的区间里 ——
     * 判据写成 {@code start <= maxPos < end} 的话，「上次看到这」会挂在每一讲的第 1 节上，
     * 前端据此自动展开目录，<b>新学员一进来就是四章一百多行全铺开</b>。
     * 单看那三行代码它是"对"的，是把目录在本地真跑了一遍才看见的。
     */
    @Test @Order(80)
    @DisplayName("★ 没看过的讲次：一节都不是「上次看到这」")
    void untouchedLessonHasNoCurrentSection() {
        jdbc.update("DELETE FROM lesson_section WHERE lesson_id=?", L3);
        jdbc.update("DELETE FROM learn_progress WHERE user_id=? AND lesson_id=?", U_OTHER, L3);
        insertSection(L3, 1, "第一节", 0, 600, "LECTURE");
        insertSection(L3, 2, "第二节", 600, DURATION, "LECTURE");

        var secs = cast(lessonIn(cast(courses.lessonsOf(U_OTHER, COURSE).get("chapters")), L3)
                .get("sections"));
        assertEquals(false, secs.get(0).get("current"),
                "一次都没打开过，第 1 节却标成了「上次看到这」—— 目录会对每个新学员整个铺开");
        assertEquals(false, secs.get(0).get("passed"));
        assertEquals(false, secs.get(1).get("current"));
    }

    /**
     * ★ 分节【不能】把讲次那一行乘出来。
     *
     * <p>这条钉的是一个很容易在重构里被引入、且完全不报错的缺陷：
     * 把分节 JOIN 进讲次那条查询里。那条查询同时还 LEFT JOIN 着空章与本人进度，
     * 一讲 30 节就会把该讲的行乘成 30 份 —— 目录里同一讲出现 30 遍、
     * 课程总时长跟着乘 30 倍，而没有任何一处会报错。
     *
     * <p>★ 我第一版把断言写成「进度百分比不许变」，那是<b>钉错了地方</b>：
     * 行被乘出来之后，每一行的 watched_sec 还是原来那个值（是复制不是求和），
     * 百分比反而不会变 —— 那条断言在缺陷真的存在时照样绿。
     * 真会变的是<b>行数</b>。
     */
    @Test @Order(81)
    @DisplayName("★ 加了 30 节之后，目录里这一讲仍然只出现一次")
    void sectionsNeverMultiplyTheLessonRow() {
        jdbc.update("DELETE FROM lesson_section WHERE lesson_id=?", L2);
        jdbc.update("INSERT INTO learn_progress (user_id,course_id,lesson_id,max_position_sec,"
                  + "watched_sec,heartbeat_count) VALUES (?,?,?,?,?,?) "
                  + "ON DUPLICATE KEY UPDATE "
                  + "max_position_sec=GREATEST(VALUES(max_position_sec), max_position_sec), "
                  + "watched_sec=GREATEST(VALUES(watched_sec), watched_sec), "
                  + "heartbeat_count=GREATEST(VALUES(heartbeat_count), heartbeat_count)",
                U_OK, COURSE, L2, 3000, 3000, 300);

        int before = Entitlements.i(lessonIn(cast(courses.lessonsOf(U_OK, COURSE).get("chapters")), L2)
                .get("progressPct"));
        assertTrue(before > 0, "前提没成立：这一讲的进度是 0，乘 30 倍也还是 0，测不到东西");

        int lessonsBefore = allLessons(cast(courses.lessonsOf(U_OK, COURSE).get("chapters"))).size();

        int step = DURATION / 30;
        for (int i = 0; i < 30; i++)
            insertSection(L2, i + 1, "第 " + (i + 1) + " 节", i * step,
                    i == 29 ? DURATION : (i + 1) * step, "LECTURE");

        var chapters = cast(courses.lessonsOf(U_OK, COURSE).get("chapters"));
        var all = allLessons(chapters);
        assertEquals(lessonsBefore, all.size(),
                "目录里的讲次行数变了 —— 分节那条查询被 JOIN 进主查询，把讲次行乘出来了");
        assertEquals(1, all.stream()
                .filter(l -> Entitlements.num(l.get("lessonId")) == L2).count(),
                "同一讲在目录里出现了不止一次");
        assertEquals(30, cast(lessonIn(chapters, L2).get("sections")).size());

        int after = Entitlements.i(lessonIn(chapters, L2).get("progressPct"));
        assertEquals(before, after, "讲次进度被分节改动了");

        // 公开目录那条是另一句 SQL，同样要盯着
        var pub = courses.publicCourse(COURSE);
        assertEquals(lessonsBefore, allLessons(cast(pub.get("chapters"))).size(),
                "公开目录里的讲次被分节乘出来了");
        assertEquals(lessonsBefore, Entitlements.i(pub.get("totalLessons")),
                "公开页的「共几讲」被分节乘大了");
    }

    @Test @Order(82)
    @DisplayName("★ 公开目录带分节（那是卖点），但一个进度字段都不许带")
    void publicCatalogHasSectionsButNoProgress() {
        jdbc.update("DELETE FROM lesson_section WHERE lesson_id=?", L3);
        insertSection(L3, 1, "公开可见的一节", 0, DURATION, "LECTURE");

        var pub = courses.publicCourse(COURSE);
        assertEquals(1, Entitlements.i(pub.get("totalSections")) >= 1 ? 1 : 0,
                "公开页没报分节总数");
        var lesson = lessonIn(cast(pub.get("chapters")), L3);
        var secs = cast(lesson.get("sections"));
        assertEquals(1, secs.size(), "公开目录里没有分节 —— 那是这门课最像样的卖点");
        assertFalse(secs.get(0).containsKey("passed"), "公开目录带出了播放位置");
        assertFalse(secs.get(0).containsKey("current"), "公开目录带出了播放位置");
        assertFalse(lesson.containsKey("watchedSec"), "公开目录带出了学习进度");
    }

    @Test @Order(83)
    @DisplayName("★ 区间超出讲次时长：库里就拒，不许留到学员点进去 seek 到视频之外")
    void sectionCannotExceedLessonDuration() {
        jdbc.update("DELETE FROM lesson_section WHERE lesson_id=?", L1);
        var e = assertThrows(DataAccessException.class,
                () -> insertSection(L1, 1, "越界", 0, DURATION + 1, "LECTURE"));
        assertTrue(String.valueOf(e.getMessage()).contains("超出讲次时长"),
                "被拒的原因不是要测的那条：" + e.getMessage());

        // 阴性对照：正好卡在时长上是合法的，否则最后一节永远没法收口
        assertDoesNotThrow(() -> insertSection(L1, 1, "刚好到底", 0, DURATION, "LECTURE"));
    }

    @Test @Order(84)
    @DisplayName("★ 区间重叠：库里就拒 —— 同一时刻属于两节，进度会算错且不报错")
    void sectionsCannotOverlap() {
        jdbc.update("DELETE FROM lesson_section WHERE lesson_id=?", L1);
        insertSection(L1, 1, "第一节", 0, 1000, "LECTURE");

        var e = assertThrows(DataAccessException.class,
                () -> insertSection(L1, 2, "压上来的一节", 900, 1500, "LECTURE"));
        assertTrue(String.valueOf(e.getMessage()).contains("重叠"),
                "被拒的原因不是要测的那条：" + e.getMessage());

        // 阴性对照：首尾相接不算重叠，否则整讲根本铺不满
        assertDoesNotThrow(() -> insertSection(L1, 2, "接上去的一节", 1000, 1500, "LECTURE"));

        // 改动也要拦，不只是插入 —— 只在 INSERT 上设闸的话，
        // 先插一个不重叠的再 UPDATE 成重叠，就整条绕过去了
        var e2 = assertThrows(DataAccessException.class,
                () -> jdbc.update("UPDATE lesson_section SET start_sec=500 "
                                + "WHERE lesson_id=? AND sort_no=2", L1));
        assertTrue(String.valueOf(e2.getMessage()).contains("重叠"), e2.getMessage());
    }

    @Test @Order(85)
    @DisplayName("分节挂到不存在的讲次上，当场拒")
    void sectionNeedsARealLesson() {
        var e = assertThrows(DataAccessException.class,
                () -> insertSection(999_999L, 1, "野节", 0, 100, "LECTURE"));
        assertTrue(String.valueOf(e.getMessage()).contains("讲次不存在"), e.getMessage());
    }

    static void insertSection(long lessonId, int sortNo, String title,
                              int start, int end, String kind) {
        jdbc.update("INSERT INTO lesson_section (lesson_id,sort_no,title,summary,kind,start_sec,end_sec) "
                  + "VALUES (?,?,?,?,?,?,?)",
                lessonId, sortNo, title, title + "（用例造的说明）", kind, start, end);
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> cast(Object o) { return (List<Map<String, Object>>) o; }

    static List<Map<String, Object>> allLessons(List<Map<String, Object>> chapters) {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (var ch : chapters) out.addAll(cast(ch.get("lessons")));
        return out;
    }

    /** 从章节树里挑出某一讲。找不到就直接失败，别让后面的断言在 null 上报 NPE。 */
    static Map<String, Object> lessonIn(List<Map<String, Object>> chapters, long lessonId) {
        for (var ch : chapters)
            for (var l : cast(ch.get("lessons")))
                if (Entitlements.num(l.get("lessonId")) == lessonId) return l;
        throw new AssertionError("目录里没有讲次 " + lessonId);
    }

    // ================================================================== 分片防下载

    /**
     * ★ 分片过去在 nginx 的 {@code /vod/} 上 alias 直出：<b>零凭据、零日志</b>。
     * 2026-08-19 对生产只读实测过 —— {@code 627_a.ts} 和 {@code 627_b.ts} 都是 206，
     * 而播放列表 404、密钥 400，唯独分片本身是裸的。
     *
     * <p>这一组钉的是三件事：每一行都带凭据、凭据绑对象（改变体就签不出来）、
     * 凭据绑用户（别人的票取不到）。第二条是重点：抹平水印之后取证<b>不报错</b>，
     * 只是谁都指认不到 —— 没有断言钉着的话，这种失效不会有任何症状。
     */
    @Test @Order(70)
    @DisplayName("★ 播放列表每一行都带一片一签的取片凭据，且确实验得过")
    void everySegmentUrlCarriesItsOwnSignedTicket() {
        var opened = play.open(U_OK, L1, DEV_A, null, IP, null);
        String m3u8 = play.playlist(opened.token(), L1, DEV_A, IP, U_OK);

        var lines = segmentLines(m3u8).toList();
        assertEquals(SEGS, lines.size());
        for (String line : lines) {
            int q = line.indexOf('?');
            assertTrue(q > 0, "分片行没带取片凭据：" + line);
            String object = line.substring("https://cdn.example.test/hls/".length(), q);
            var p = queryOf(line);
            var r = segmentToken.verify(object, p.get("l"), p.get("u"), p.get("e"), p.get("s"),
                    Instant.now());
            assertInstanceOf(SegmentToken.Ok.class, r, () -> line + " → " + r);
            assertEquals(U_OK, ((SegmentToken.Ok) r).userId());
            assertEquals(L1, ((SegmentToken.Ok) r).lessonId());
        }
    }

    @Test @Order(71)
    @DisplayName("★ 把自己 m3u8 里的 _b 改成 _a：另一个变体签不出来 —— 抹平水印这条路断掉")
    void theOtherVariantCannotBeFetchedWithYourOwnTicket() {
        var opened = play.open(U_OK, L1, DEV_A, null, IP, null);
        String m3u8 = play.playlist(opened.token(), L1, DEV_A, IP, U_OK);

        String bVariant = segmentLines(m3u8).filter(l -> l.contains("_b.ts?")).findFirst()
                .orElseGet(() -> segmentLines(m3u8).filter(l -> l.contains("_a.ts?")).findFirst()
                        .orElseThrow(() -> new AssertionError("播放列表里一个变体都没有")));
        boolean isB = bVariant.contains("_b.ts?");
        String flippedObject = objectOf(bVariant).replace(isB ? "_b.ts" : "_a.ts",
                                                          isB ? "_a.ts" : "_b.ts");
        var p = queryOf(bVariant);

        var r = segmentToken.verify(flippedObject, p.get("l"), p.get("u"), p.get("e"), p.get("s"),
                Instant.now());
        assertInstanceOf(SegmentToken.Rejected.class, r, "另一个变体也能取到 —— 水印可以被抹平");
        assertEquals("取片签名不匹配", ((SegmentToken.Rejected) r).reason());

        // 阴性对照：不改变体必须是通的，否则上面那条可能只是"验签整个坏了"
        assertInstanceOf(SegmentToken.Ok.class, segmentToken.verify(objectOf(bVariant),
                p.get("l"), p.get("u"), p.get("e"), p.get("s"), Instant.now()));
    }

    @Test @Order(72)
    @DisplayName("★ 别人的播放列表取不到：把 u 改成自己，签名当场对不上")
    void anotherStudentsPlaylistIsUseless() {
        var opened = play.open(U_OK, L1, DEV_A, null, IP, null);
        String line = segmentLines(play.playlist(opened.token(), L1, DEV_A, IP, U_OK))
                .findFirst().orElseThrow();
        var p = queryOf(line);
        p.put("u", String.valueOf(U_OTHER));
        var r = segmentToken.verify(objectOf(line), p.get("l"), p.get("u"), p.get("e"), p.get("s"),
                Instant.now());
        assertEquals("取片签名不匹配", ((SegmentToken.Rejected) r).reason());
    }

    /**
     * ★ 取片这条路的服务端行为：验签通过 → 交给 nginx 的 internal location 发文件。
     * Java 一个字节都不读盘 —— 一讲 1800 片全部穿过 JVM 堆是白烧的 CPU 与 GC，
     * 而且 Range 要自己实现（iOS 原生 HLS 是会发 Range 的）。
     */
    @Test @Order(73)
    @DisplayName("★ 取片：合法凭据交给 X-Accel，伪造的当场 403")
    void segmentDeliveryUsesXAccelAndRejectsForgeries() {
        var svc = new SegmentService(jdbc, segmentToken, props);
        var opened = play.open(U_OK, L1, DEV_A, null, IP, null);
        String line = segmentLines(play.playlist(opened.token(), L1, DEV_A, IP, U_OK))
                .findFirst().orElseThrow();
        var p = queryOf(line);
        String object = objectOf(line);

        var out = svc.deliver(object, p.get("l"), p.get("u"), p.get("e"), p.get("s"),
                IP, Instant.now());
        assertEquals("/vod/" + object, out.internalUri(),
                "没走 X-Accel —— 文件会被 Java 读进堆里再吐出去");
        assertEquals(U_OK, out.userId());

        var forged = expect(403, "取片签名不匹配", () -> svc.deliver(object,
                p.get("l"), p.get("u"), p.get("e"), "AAAAAAAAAAAAAAAAAAAAAA", IP, Instant.now()));
        assertEquals(403, forged.status());
    }

    /**
     * ★ 速率闸。这里最容易写出的假绿是"造一个疯狂下载的序列，断言它被拒" ——
     * 那条用例在一个把所有人都拒掉的实现上同样是绿的。所以先跑一段正常观看做阴性对照。
     */
    @Test @Order(74)
    @DisplayName("★ 全速抓取被限速并落一条风控事件；正常速度的观看一次都不被拦")
    void bulkFetchIsThrottledAndRecorded() {
        var svc = new SegmentService(jdbc, segmentToken, props);
        var opened = play.open(U_OTHER, L1, DEV_B, null, IP, null);
        var lines = segmentLines(play.playlist(opened.token(), L1, DEV_B, IP, U_OTHER)).toList();

        // 阴性对照：按 6 秒一片的节奏取 200 片，一次都不该被拦。
        // 时间用合成时刻推进，不真的 sleep —— 也正因为如此，这里绝不能给
        // 200 次调用传同一个时刻：那是"一毫秒内取了 200 片"，闸门本来就该拦。
        long base = 1_755_000_000_000L;
        for (int i = 0; i < 200; i++) {
            var p = queryOf(lines.get(i));
            int idx = i;
            assertDoesNotThrow(() -> svc.deliver(objectOf(lines.get(idx)), p.get("l"), p.get("u"),
                    p.get("e"), p.get("s"), IP, Instant.ofEpochMilli(base + idx * 6000L)),
                    "正常速度的观看被限速了（第 " + i + " 片）");
        }

        // 抓取从一小时后开始，让上面那段的窗口彻底老化 ——
        // 不隔开的话，被拦的可能是"接着上一段算出来的速率"，测的就不是抓取本身了。
        long t = base + 3_600_000L;

        int before = jdbc.queryForObject("SELECT COUNT(*) FROM risk_event WHERE user_id=?",
                Integer.class, U_OTHER);
        int blocked = 0;
        for (int i = 0; i < 400; i++) {
            var p = queryOf(lines.get(i));
            try {
                svc.deliver(objectOf(lines.get(i)), p.get("l"), p.get("u"), p.get("e"), p.get("s"),
                        IP, Instant.ofEpochMilli(t + i * 50L));
            } catch (ApiException e) {
                assertEquals(429, e.status(), "限速用了错误的状态码：" + e.getMessage());
                blocked++;
            }
        }
        assertTrue(blocked > 250, "全速抓取几乎没被拦：只拒了 " + blocked + " 次");

        int after = jdbc.queryForObject("SELECT COUNT(*) FROM risk_event WHERE user_id=?",
                Integer.class, U_OTHER);
        assertEquals(before + 1, after,
                "风控事件要么没落库、要么没节流（400 次抓取该只落一条）");
        var ev = jdbc.queryForMap("SELECT `signal`, score, detail FROM risk_event "
                               + "WHERE user_id=? ORDER BY id DESC LIMIT 1", U_OTHER);
        assertEquals("SEGMENT_RATE_EXCEED", ev.get("signal"));
        assertTrue(((Number) ev.get("score")).intValue() > 0);
        assertTrue(String.valueOf(ev.get("detail")).contains("\"ratio\""),
                "风控明细里没记越界倍数，事后没法判断严重程度：" + ev.get("detail"));
    }

    @Test @Order(72)
    @DisplayName("★ 一个片库归好几讲之后，越界的片取不到 —— 哪怕票是服务端真签的")
    void segmentOutsideThisLessonsRangeIsRejected() {
        var seg = new SegmentService(jdbc, segmentToken, props);
        String prefix = jdbc.queryForObject("SELECT hls_key FROM lesson WHERE id=?", String.class, L1);
        var exp = Instant.now().plusSeconds(600);

        // 阴性对照：区间【之内】的一片，同样一张票，必须是通的。
        // 没有这一句的话，下面那条红不红都说明不了什么 —— 可能是整条路都坏了。
        String inside = prefix + "/0.ts";
        var okQ = parseQuery(segmentToken.query(U_OK, L1, inside, exp));
        assertDoesNotThrow(() -> seg.deliver(inside, okQ.get("l"), okQ.get("u"), okQ.get("e"),
                okQ.get("s"), IP, Instant.now()));

        // 区间【之外】的一片。签名是服务端用同一把密钥真签的 —— 所以这条拦的不是伪造，
        // 而是配置漂移：区间改过而缓存没换、灌库把目录灌串了。两种都会让学员看到
        // 另一讲的内容，而画面照常在动，谁也不会报错。
        String outside = prefix + "/" + (SEGS + 5) + ".ts";
        var badQ = parseQuery(segmentToken.query(U_OK, L1, outside, exp));
        var e = expect(403, "不属于当前讲次", () -> seg.deliver(outside, badQ.get("l"),
                badQ.get("u"), badQ.get("e"), badQ.get("s"), IP, Instant.now()));
        assertNotNull(e);

        // 换一个片库目录同样不行：那里同名的片是别的内容，播出来是另一节课
        String otherStore = "oss-private/course1/l2/0.ts";
        var otherQ = parseQuery(segmentToken.query(U_OK, L1, otherStore, exp));
        expect(403, "不属于当前讲次", () -> seg.deliver(otherStore, otherQ.get("l"),
                otherQ.get("u"), otherQ.get("e"), otherQ.get("s"), IP, Instant.now()));
    }

    @Test @Order(73)
    @DisplayName("★ 重切课程后把密文复制到新讲次：解不开，必须改绑（2026-08-21 生产上就撞了这条）")
    void contentKeyMustBeReboundAfterRecut() {
        // 一场录屏切成几讲之后，新讲次共用【同一批分片】，所以必须是同一把内容密钥。
        // 而密文按 (讲次, 版本) 绑 AAD，SQL 里原样复制过去解封会当场失败 ——
        // 那正是 AAD 存在的理由。首次发布时四道库层闸门全绿、播放列表与分片都取得到，
        // 唯独取密钥回 409，是真的去播了一遍才碰到。
        byte[] want = keys.contentKey(L1);
        long fresh = newLesson(95, "重切出来的一讲");
        jdbc.update("INSERT INTO lesson_content_key (lesson_id,course_id,key_version,algo,"
                  + "key_enc,kek_id,status) SELECT ?,course_id,key_version,algo,key_enc,kek_id,"
                  + "'ACTIVE' FROM lesson_content_key WHERE lesson_id=? AND status='ACTIVE'",
                fresh, L1);

        var e = assertThrows(IllegalStateException.class, () -> keys.contentKey(fresh));
        assertTrue(e.getMessage().contains("被搬过家"), e.getMessage());

        assertTrue(keys.rebind(fresh, L1), "改绑说没改动，但这行密文本来就是解不开的");
        assertArrayEquals(want, keys.contentKey(fresh),
                "改绑之后解出来的不是同一把密钥 —— 新讲次用的是旧讲次那批分片，"
              + "密钥不同就是整讲播不出来，而服务端一句错都不报");

        // 幂等：再来一次不该动它
        assertFalse(keys.rebind(fresh, L1), "已经绑对了还重新封了一遍");

        // 源讲次填错时必须拒绝，不许把一行好密文封成谁也解不开的
        long other = newLesson(96, "另一讲");
        jdbc.update("INSERT INTO lesson_content_key (lesson_id,course_id,key_version,algo,"
                  + "key_enc,kek_id,status) SELECT ?,course_id,key_version,algo,key_enc,kek_id,"
                  + "'ACTIVE' FROM lesson_content_key WHERE lesson_id=? AND status='ACTIVE'",
                other, L1);
        expect(409, "源讲次填错了", () -> keys.rebind(other, L2));
        assertDoesNotThrow(() -> keys.rebind(other, L1), "填错一次之后，正确的源也改不动了");
    }

    /** {@code l=1&u=2&e=3&s=xx} → map。 */
    static java.util.Map<String, String> parseQuery(String q) {
        var m = new java.util.HashMap<String, String>();
        for (String kv : q.split("&")) {
            int i = kv.indexOf('=');
            m.put(kv.substring(0, i), kv.substring(i + 1));
        }
        return m;
    }

    /** {@code https://cdn.example.test/hls/oss-private/.../7_a.ts?l=..} → {@code oss-private/.../7_a.ts} */
    static String objectOf(String line) {
        return line.substring("https://cdn.example.test/hls/".length(), line.indexOf('?'));
    }

    static java.util.Map<String, String> queryOf(String line) {
        var m = new java.util.HashMap<String, String>();
        for (String kv : line.substring(line.indexOf('?') + 1).split("&")) {
            int i = kv.indexOf('=');
            m.put(kv.substring(0, i), kv.substring(i + 1));
        }
        return m;
    }

    static void grantTo(long userId, long courseId) {
        jdbc.update("INSERT INTO enrollment (user_id,course_id,source,valid_from,valid_until,"
                  + "agreement_version,agreed_at) VALUES (?,?,'GRANT',NOW(3),"
                  + "DATE_ADD(NOW(3), INTERVAL 12 MONTH),'v-it',NOW(3))", userId, courseId);
    }
}