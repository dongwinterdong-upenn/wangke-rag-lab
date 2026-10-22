package tech.dragonai.wangke.protect;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 播放风控。判"这个号是不是在被多个人共用"，以及"是不是在被批量抓取"。
 *
 * <p>设计上的三条取舍：
 *
 * <p><b>① 分级处置，不是一刀封号。</b>共享账号的证据几乎总是概率性的
 * （出差、换 wifi、家里两台设备都会像共享），一发现异常就封号会误伤真学员，
 * 而误伤一个付了 799 的学员的代价远大于漏掉一个蹭课的。
 * 所以是 观察 → 验证码 → 短信验证 → 冻结 24 小时 → 停用 的阶梯。
 *
 * <p><b>② 累计分用 7 天滑动窗口，不用"当次"。</b>只看当次的话，
 * 每天异地登录一次、每次都刚好不触发阈值的账号永远抓不到。
 *
 * <p><b>③ 每一条处置都必须能人工解封。</b>见 {@code risk_action.released_by}。
 * 没有解封通道的风控系统，第一次误伤就会变成客服灾难。
 */
public final class RiskEngine {

    /** 信号与分值。分值是策略，集中在这里，不散落在调用处。 */
    public enum Signal {
        IMPOSSIBLE_TRAVEL(50, "两次播放的地理位置在物理上来不及移动"),
        CONCURRENT_SESSION(35, "同一账号多地并发播放"),
        DEVICE_CHURN(25, "短期内频繁更换设备"),
        SEGMENT_RATE_EXCEED(40, "取片速率远超正常播放"),
        SEQUENTIAL_SCAN(45, "顺序抓取全部分片，播放器不会这样取"),
        KEY_REQUEST_BURST(30, "短时间内大量索取解密密钥"),
        WM_TAMPERED(60, "水印槽位被篡改或缺失");

        public final int score; public final String why;
        Signal(int s, String w) { this.score = s; this.why = w; }
    }

    /** 处置档位。阈值是 7 天窗口内的累计分。 */
    public enum Action {
        NONE(0), WATCH(40), CAPTCHA(80), SMS_VERIFY(130), FREEZE_24H(200), SUSPEND(300);
        public final int atLeast;
        Action(int a) { this.atLeast = a; }
    }

    /** 民航客机巡航速度上界，取 900 km/h。超过这个速度的"移动"是物理上不可能的。 */
    private static final double MAX_KMH = 900.0;
    /** 城市级 IP 定位误差。低于这个距离不做判断，否则同城换网就会误报。 */
    private static final double GEO_NOISE_KM = 120.0;

    public record GeoPoint(double lat, double lon) {}
    public record Play(Instant at, GeoPoint where, String deviceId) {}
    public record Hit(Signal signal, String detail) {}

    /**
     * 不可能旅行。两次播放之间需要的速度超过 900 km/h 即判定。
     *
     * <p>两个必须的护栏，缺一个都会天天误报：
     * ① 距离低于城市级定位误差（120km）时不判 —— 同城换运营商出口就会跳几十公里；
     * ② 时间差过小时不判 —— 秒级间隔下任何定位抖动都会算出超音速。
     */
    public static boolean impossibleTravel(Play a, Play b) {
        double km = haversineKm(a.where(), b.where());
        if (km < GEO_NOISE_KM) return false;
        double hours = Math.abs(Duration.between(a.at(), b.at()).toMillis()) / 3_600_000.0;
        if (hours < 1.0 / 60) return false;          // 小于 1 分钟不判
        return km / hours > MAX_KMH;
    }

    static double haversineKm(GeoPoint p, GeoPoint q) {
        double R = 6371.0;
        double dLat = Math.toRadians(q.lat() - p.lat());
        double dLon = Math.toRadians(q.lon() - p.lon());
        double h = Math.pow(Math.sin(dLat / 2), 2)
                 + Math.cos(Math.toRadians(p.lat())) * Math.cos(Math.toRadians(q.lat()))
                 * Math.pow(Math.sin(dLon / 2), 2);
        return 2 * R * Math.asin(Math.min(1, Math.sqrt(h)));
    }

    /**
     * 取片速率。正常播放取片速度约等于播放速度（每 6 秒的片，6 秒取一次），
     * 允许缓冲预取。远超这个比例就是在下载而不是在看。
     *
     * @param fetched    本会话已取片数
     * @param elapsedSec 会话已持续秒数
     * @param segSec     单片时长
     * @param maxRate    允许的倍率上限（含预取余量），建议 4.0
     */
    public static boolean segmentRateExceeded(int fetched, double elapsedSec,
                                              double segSec, double maxRate) {
        if (elapsedSec < 30) return false;            // 起播缓冲期不判，否则人人中招
        double expected = elapsedSec / segSec;
        return fetched > expected * maxRate;
    }

    /**
     * 顺序抓取识别。人看课会跳、会回放、会停；抓取脚本是严格递增一片不落。
     * 单看速率抓不住"慢速但完整"的抓取，这条能。
     *
     * @param seq 按请求先后排列的分片序号
     */
    public static boolean sequentialScan(List<Integer> seq, int minLen) {
        if (seq.size() < minLen) return false;
        for (int i = 1; i < seq.size(); i++)
            if (seq.get(i) != seq.get(i - 1) + 1) return false;
        return true;
    }

    /**
     * 同一次播放里多个【不同】信号齐发的协同加成。
     *
     * <p>为什么需要它：第一版按纯累加算，一次"不可能旅行 + 整片下载 + 脚本顺序抓取
     * + 三地并发"（四信号齐发、几乎不可能是误判）只有 170 分，够不到冻结；
     * 而"一周内换了六次设备"这种单一小异常反复，累到 210 分直接冻结。
     * 方向是反的 —— 四种不同信号同时命中是四个相互独立的印证，
     * 强度远高于同一种行为重复六次。而对前者只发一个短信验证码，
     * 对方本来就持有手机，等于没拦。
     *
     * <p>加成只按【不同种类数】给，同种信号在一次会话里重复多少次都不加成。
     */
    public static int synergyBonus(int distinctSignals) {
        return distinctSignals >= 3 ? (distinctSignals - 1) * 20 : 0;
    }

    /** 一次体检的综合分 = 各信号分之和 + 协同加成。 */
    public static int combinedScore(List<Hit> hits) {
        int sum = 0;
        java.util.EnumSet<Signal> kinds = java.util.EnumSet.noneOf(Signal.class);
        for (Hit h : hits) { sum += h.signal().score; kinds.add(h.signal()); }
        return sum + synergyBonus(kinds.size());
    }

    /**
     * 7 天滑动窗口累计分 → 处置档位。
     *
     * <p>同类信号重复采【递减计入】：第 n 次只按 1/n 权重算。
     * 一周换六次设备是【一个】行为模式，不是六份独立证据；
     * 线性累加会让"持续轻微异常"越过"单次铁证"，那正是上面说的倒置。
     */
    public static Action decide(List<ScoredEvent> events, Instant now) {
        Instant from = now.minus(Duration.ofDays(7));
        java.util.Map<Object, Integer> seen = new java.util.HashMap<>();
        double sum = 0;
        for (ScoredEvent e : events) {
            if (!e.at().isAfter(from)) continue;
            Object key = e.kind() == null ? e : e.kind();
            int n = seen.merge(key, 1, Integer::sum);
            sum += e.score() / (double) n;
        }
        Action out = Action.NONE;
        for (Action a : Action.values())
            if (sum >= a.atLeast && a.atLeast >= out.atLeast) out = a;
        return out;
    }

    /** kind 为 null 表示"不参与同类递减"（如已合成过协同加成的会话级综合分）。 */
    public record ScoredEvent(Instant at, int score, Signal kind) {
        public ScoredEvent(Instant at, int score) { this(at, score, null); }
    }

    /** 一次播放请求的完整体检，返回命中的所有信号。 */
    public static List<Hit> inspect(Play current, Play previous,
                                    int fetched, double elapsedSec, double segSec,
                                    List<Integer> seq, int activeSessions) {
        List<Hit> hits = new ArrayList<>();
        if (previous != null && impossibleTravel(previous, current))
            hits.add(new Hit(Signal.IMPOSSIBLE_TRAVEL,
                    String.format("%.0f km / %.2f h",
                            haversineKm(previous.where(), current.where()),
                            Math.abs(Duration.between(previous.at(), current.at()).toMillis()) / 3.6e6)));
        if (segmentRateExceeded(fetched, elapsedSec, segSec, 4.0))
            hits.add(new Hit(Signal.SEGMENT_RATE_EXCEED,
                    String.format("已取 %d 片 / 应约 %.0f 片", fetched, elapsedSec / segSec)));
        if (sequentialScan(seq, 30))
            hits.add(new Hit(Signal.SEQUENTIAL_SCAN, "连续 " + seq.size() + " 片严格递增"));
        if (activeSessions > 1)
            hits.add(new Hit(Signal.CONCURRENT_SESSION, activeSessions + " 个会话同时在播"));
        return hits;
    }

    private RiskEngine() {}
}
