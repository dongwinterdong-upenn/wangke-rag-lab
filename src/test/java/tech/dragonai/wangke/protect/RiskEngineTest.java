package tech.dragonai.wangke.protect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static tech.dragonai.wangke.protect.RiskEngine.*;

/**
 * 风控判据。
 *
 * <p>这套测试里【阴性对照比阳性用例更重要】：漏掉一个蹭课的，损失是一份 799；
 * 误伤一个真学员，损失是这个学员、他的口碑，以及客服为此花掉的时间。
 * 所以每一条信号都必须先证明它不会在正常行为上开火。
 */
class RiskEngineTest {

    static final GeoPoint 北京 = new GeoPoint(39.90, 116.40);
    static final GeoPoint 上海 = new GeoPoint(31.23, 121.47);
    static final GeoPoint 广州 = new GeoPoint(23.13, 113.26);
    static final GeoPoint 纽约 = new GeoPoint(40.71, -74.01);
    /** 同城换运营商出口：北京市内两个点，直线约 20 km */
    static final GeoPoint 北京另一出口 = new GeoPoint(39.98, 116.60);

    static final Instant T0 = Instant.parse("2026-08-11T10:00:00Z");
    static Play at(Instant t, GeoPoint g) { return new Play(t, g, "DEV_A"); }

    // ---------- 不可能旅行 ----------

    @Test
    @DisplayName("北京→纽约 1 小时内播放 —— 应判为不可能旅行")
    void travelPositive() {
        assertTrue(impossibleTravel(at(T0, 北京), at(T0.plus(Duration.ofHours(1)), 纽约)));
    }

    @Test
    @DisplayName("北京→上海 4 小时（坐飞机来得及）—— 不应判定")
    void travelFlightIsFine() {
        assertFalse(impossibleTravel(at(T0, 北京), at(T0.plus(Duration.ofHours(4)), 上海)),
                "正常出差被判成共享账号，这是最典型的误伤");
    }

    @Test
    @DisplayName("同城换网 20km / 间隔 1 分钟 —— 不应判定（低于定位误差）")
    void travelSameCityNoise() {
        assertFalse(impossibleTravel(at(T0, 北京), at(T0.plus(Duration.ofMinutes(1)), 北京另一出口)),
                "家里 wifi 切 4G 就报警，风控会淹没在噪声里");
    }

    @Test
    @DisplayName("间隔 10 秒的两次定位抖动 —— 不应判定（时间差过小）")
    void travelTinyGap() {
        assertFalse(impossibleTravel(at(T0, 北京), at(T0.plusSeconds(10), 上海)),
                "秒级间隔下任何定位抖动都会算出超音速，必须有时间下限");
    }

    @Test
    @DisplayName("北京→广州 30 分钟 —— 应判定（1900km 需 3800km/h）")
    void travelRealSharing() {
        assertTrue(impossibleTravel(at(T0, 北京), at(T0.plus(Duration.ofMinutes(30)), 广州)));
    }

    // ---------- 取片速率 ----------

    @Test
    @DisplayName("正常播放：600 秒取 100 片（6 秒/片）—— 不应判定")
    void rateNormal() {
        assertFalse(segmentRateExceeded(100, 600, 6, 4.0));
    }

    @Test
    @DisplayName("带缓冲预取：600 秒取 300 片（3 倍）—— 仍不应判定")
    void ratePrefetchOk() {
        assertFalse(segmentRateExceeded(300, 600, 6, 4.0), "预取余量不足会误伤弱网用户");
    }

    @Test
    @DisplayName("整片下载：600 秒取 1800 片（18 倍）—— 应判定")
    void rateDownload() {
        assertTrue(segmentRateExceeded(1800, 600, 6, 4.0));
    }

    @Test
    @DisplayName("起播 10 秒内猛取 —— 不应判定（缓冲期豁免）")
    void rateStartupExempt() {
        assertTrue(segmentRateExceeded(50, 31, 6, 4.0), "缓冲期一过就该判");
        assertFalse(segmentRateExceeded(50, 10, 6, 4.0), "起播缓冲人人如此，判了就是人人中招");
    }

    // ---------- 顺序抓取 ----------

    @Test
    @DisplayName("严格递增 50 片 —— 应判为脚本抓取")
    void scanPositive() {
        List<Integer> seq = new java.util.ArrayList<>();
        for (int i = 100; i < 150; i++) seq.add(i);
        assertTrue(sequentialScan(seq, 30));
    }

    @Test
    @DisplayName("人在看课：有回放、有跳转 —— 不应判定")
    void scanHumanViewing() {
        List<Integer> seq = List.of(1, 2, 3, 4, 5, 4, 5, 6, 7, 20, 21, 22, 23, 24, 25,
                                    26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40);
        assertFalse(sequentialScan(seq, 30), "回看一遍就被当成抓取，正常学习行为会被封");
    }

    @Test
    @DisplayName("连续但太短 —— 不应判定（正常播放本来就是连续的）")
    void scanTooShort() {
        List<Integer> seq = List.of(1, 2, 3, 4, 5);
        assertFalse(sequentialScan(seq, 30), "顺序播放本身就是连续递增，长度门槛不能低");
    }

    // ---------- 分级处置与滑动窗口 ----------

    @Test
    @DisplayName("累计分按 7 天滑动窗口，窗口外的不计入")
    void slidingWindow() {
        Instant now = T0;
        List<ScoredEvent> ev = List.of(
                new ScoredEvent(now.minus(Duration.ofDays(30)), 200),  // 一个月前，不计
                new ScoredEvent(now.minus(Duration.ofDays(1)), 50));
        assertEquals(Action.WATCH, decide(ev, now), "陈年旧账不该把人送进冻结");
    }

    @Test
    @DisplayName("处置逐级升，不跳档")
    void escalation() {
        Instant now = T0;
        assertEquals(Action.NONE, decide(List.of(new ScoredEvent(now, 10)), now));
        assertEquals(Action.WATCH, decide(List.of(new ScoredEvent(now, 50)), now));
        assertEquals(Action.CAPTCHA, decide(List.of(new ScoredEvent(now, 100)), now));
        assertEquals(Action.SMS_VERIFY, decide(List.of(new ScoredEvent(now, 150)), now));
        assertEquals(Action.FREEZE_24H, decide(List.of(new ScoredEvent(now, 250)), now));
        assertEquals(Action.SUSPEND, decide(List.of(new ScoredEvent(now, 400)), now));
    }

    @Test
    @DisplayName("持续小异常会累积，但同类重复递减 —— 不该越过单次铁证")
    void slowBurnAccumulates() {
        Instant now = T0;
        List<ScoredEvent> ev = new java.util.ArrayList<>();
        for (int d = 1; d <= 6; d++)
            ev.add(new ScoredEvent(now.minus(Duration.ofDays(d)), Signal.DEVICE_CHURN.score,
                                   Signal.DEVICE_CHURN));
        Action a = decide(ev, now);
        assertTrue(a.atLeast > Action.NONE.atLeast, "持续异常一点反应都没有，等于没风控");
        assertTrue(a.atLeast < Action.FREEZE_24H.atLeast,
                "一周换六次设备就冻结，误伤代价太大（换手机 + 换电脑 + 出差就够了）");
    }

    @Test
    @DisplayName("不同种类的持续异常不打折，同一种才递减")
    void differentKindsStillAccumulate() {
        Instant now = T0;
        List<ScoredEvent> ev = List.of(
                new ScoredEvent(now.minusSeconds(100), Signal.IMPOSSIBLE_TRAVEL.score, Signal.IMPOSSIBLE_TRAVEL),
                new ScoredEvent(now.minusSeconds(200), Signal.SEQUENTIAL_SCAN.score, Signal.SEQUENTIAL_SCAN),
                new ScoredEvent(now.minusSeconds(300), Signal.SEGMENT_RATE_EXCEED.score, Signal.SEGMENT_RATE_EXCEED));
        assertTrue(decide(ev, now).atLeast >= Action.SMS_VERIFY.atLeast,
                "三种不同信号各来一次却几乎不计分，递减规则误伤到了跨类累积");
    }

    @Test
    @DisplayName("零事件 —— 不处置")
    void quietUser() {
        assertEquals(Action.NONE, decide(List.of(), T0));
    }

    // ---------- 整体体检 ----------

    @Test
    @DisplayName("正常一次播放：所有信号都不开火")
    void inspectCleanSession() {
        List<Integer> seq = List.of(1, 2, 3, 4, 5, 4, 5, 6);
        List<Hit> hits = inspect(at(T0.plus(Duration.ofHours(5)), 上海), at(T0, 北京),
                                 100, 600, 6, seq, 1);
        assertTrue(hits.isEmpty(), "正常播放被打出 " + hits.size() + " 个风控信号：" + hits);
    }

    @Test
    @DisplayName("典型共享账号：异地并发 + 顺序抓取，多信号同时命中")
    void inspectSharedAccount() {
        List<Integer> seq = new java.util.ArrayList<>();
        for (int i = 0; i < 60; i++) seq.add(i);
        List<Hit> hits = inspect(at(T0.plus(Duration.ofMinutes(20)), 广州), at(T0, 北京),
                                 2000, 600, 6, seq, 3);
        List<Signal> got = hits.stream().map(Hit::signal).toList();
        assertTrue(got.contains(Signal.IMPOSSIBLE_TRAVEL), "漏了不可能旅行");
        assertTrue(got.contains(Signal.SEGMENT_RATE_EXCEED), "漏了取片速率");
        assertTrue(got.contains(Signal.SEQUENTIAL_SCAN), "漏了顺序抓取");
        assertTrue(got.contains(Signal.CONCURRENT_SESSION), "漏了并发会话");
        int combined = combinedScore(hits);
        assertTrue(decide(List.of(new ScoredEvent(T0, combined)), T0).atLeast
                        >= Action.FREEZE_24H.atLeast,
                "四个信号齐发却没升到冻结档（综合分 " + combined + "）—— "
                + "只发个短信验证码，对方本来就有手机，等于没拦");
    }

    @Test
    @DisplayName("协同加成只按信号种类给，同种重复不加成")
    void synergyCountsKindsNotOccurrences() {
        assertEquals(0, synergyBonus(1));
        assertEquals(0, synergyBonus(2), "两个信号还不足以互证，不给加成");
        assertEquals(40, synergyBonus(3));
        assertEquals(60, synergyBonus(4));
        List<Hit> sameKind = List.of(
                new Hit(Signal.DEVICE_CHURN, "a"), new Hit(Signal.DEVICE_CHURN, "b"),
                new Hit(Signal.DEVICE_CHURN, "c"));
        assertEquals(Signal.DEVICE_CHURN.score * 3, combinedScore(sameKind),
                "同一种信号重复三次不该拿协同加成 —— 那不是三个独立印证");
    }

    @Test
    @DisplayName("单次铁证必须重于长期小异常（这是第一版标定反了的地方）")
    void singleStrongEvidenceOutranksSlowBurn() {
        Instant now = T0;
        List<Integer> seq = new java.util.ArrayList<>();
        for (int i = 0; i < 60; i++) seq.add(i);
        int blatant = combinedScore(inspect(at(now.plus(Duration.ofMinutes(20)), 广州),
                                           at(now, 北京), 2000, 600, 6, seq, 3));
        List<ScoredEvent> burn = new java.util.ArrayList<>();
        for (int d = 1; d <= 6; d++)
            burn.add(new ScoredEvent(now.minus(Duration.ofDays(d)), Signal.DEVICE_CHURN.score,
                                     Signal.DEVICE_CHURN));
        Action aBlatant = decide(List.of(new ScoredEvent(now, blatant)), now);
        Action aBurn = decide(burn, now);
        assertTrue(aBlatant.atLeast > aBurn.atLeast,
                "四信号齐发(" + aBlatant + ") 处置不重于 一周六次换设备(" + aBurn + ")");
    }
}
