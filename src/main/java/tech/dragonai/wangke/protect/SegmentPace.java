package tech.dragonai.wangke.protect;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 取片速率闸。判「这个人是在看，还是在下载」。
 *
 * <h3>为什么闸在这里，不在 nginx 的 limit_req 上</h3>
 * {@code limit_req} 认的是 IP。一个宿舍出口后面十个学员共用一个 IP，
 * 按 IP 限速要么松到不起作用，要么把整栋楼一起限死。这里认的是
 * <b>user_id + 讲次</b>，那才是"这一份内容正在被谁以什么速度取走"的正确主体。
 *
 * <h3>判据只用速率，不用「顺序」</h3>
 * {@link RiskEngine#sequentialScan} 的注释说"人看课会跳、会回放、会停；
 * 抓取脚本是严格递增一片不落"。这句话对"抓取脚本"成立，但它的反面不成立 ——
 * <b>一个从头看到尾、中间不拖进度条的学员，取片序号同样是严格递增一片不落。</b>
 * 拿它单独当判据，第一个认真看完一讲的人就会被判成盗版者。
 * 所以这里只用速率闸，顺序只作为速率已经越界之后的<b>佐证信号</b>
 * （见 {@link Verdict#sequential}），它影响的是风控分，不影响放不放行。
 *
 * <h3>越界之后是「限速」，不是「封禁」</h3>
 * 被拒的请求<b>不计入窗口</b>。计入的话窗口永远填满，一旦踩线就再也出不来 ——
 * 那是封号，不是限速，而误伤一个付了 799 的学员的代价远大于放慢一个下载的人。
 * 不计入之后，窗口自然老化，他会被稳定压在 {@code maxRate} 倍实时速度上：
 * 一讲三小时的课，8 倍下限意味着最快也要 22 分钟才能拉完，
 * 而正常观看永远碰不到这条线。
 */
public final class SegmentPace {

    /** 一次取片请求的裁决。 */
    public record Verdict(boolean allowed, int fetched, double expected,
                          double elapsedSec, boolean sequential) {
        /** 超出正常播放速度多少倍。写进风控明细，让人一眼看出越界程度。 */
        public double ratio() { return expected <= 0 ? 0 : fetched / expected; }
    }

    private final int windowSeconds;
    private final double maxRate;
    private final int minSamples;
    private final int maxKeys;

    /** 跟踪键。用 record 而不是把两个 id 位运算拼成一个 long —— 拼接一旦有进位重叠，
     *  两个不同的人会共用一个窗口：一个人被另一个人的取片速度限住，而这件事查不出来。 */
    private record Key(long userId, long lessonId) {}

    private final Map<Key, Window> windows = new ConcurrentHashMap<>();

    /**
     * @param windowSeconds 滑动窗口长度。太短会被起播缓冲期骗（hls.js 一开始会连拉几片），
     *                      太长会让一次拖进度条的痕迹留很久。180 秒是两边都有余量的取值。
     * @param maxRate       允许的倍率上限，含预取与拖动余量。低于 4 会误伤正常拖动。
     * @param minSamples    窗口内不足这么多片时一律放行。没有这条下限的话，
     *                      起播那几秒（elapsed 很小、fetched 有几片）算出来的比值必然爆表，
     *                      于是<b>每一次开播的头几秒都会被判成下载</b>。
     * @param maxKeys       最多同时跟踪多少个 (用户,讲次)。这是内存上界，不是业务参数：
     *                      没有上界的话，一个刷 user_id 的人就能把这张表撑爆成 OOM ——
     *                      而防护装置自己变成拒绝服务的入口，是最难看的一种失败。
     */
    public SegmentPace(int windowSeconds, double maxRate, int minSamples, int maxKeys) {
        this.windowSeconds = Math.max(30, windowSeconds);
        this.maxRate = Math.max(2.0, maxRate);
        this.minSamples = Math.max(10, minSamples);
        this.maxKeys = Math.max(256, maxKeys);
    }

    /**
     * 记一次取片并裁决。
     *
     * @param segSeconds 单片时长（讲次时长 / 片数）。拿不到就传 &le;0，本次一律放行 ——
     *                   宁可漏判，也不要用一个猜出来的片长去算阈值：那会让阈值
     *                   在不同讲次上悄悄漂移，而漂移的方向和幅度谁也说不清。
     */
    public Verdict admit(long userId, long lessonId, int segmentIndex,
                         double segSeconds, long nowMs) {
        if (segSeconds <= 0) return new Verdict(true, 0, 0, 0, false);
        Window w = windows.computeIfAbsent(new Key(userId, lessonId), k -> new Window());
        evictIfCrowded();
        return w.admit(segmentIndex, segSeconds, nowMs, windowSeconds, maxRate, minSamples);
    }

    /** 跟踪表的规模，给自检与监控用。 */
    public int trackedKeys() { return windows.size(); }

    /**
     * 老化清理。按"最后一次活动时间"淘汰，不按插入顺序 ——
     * 按插入顺序会把一个正在看三小时长课的人先踢掉，而他恰恰是最该被跟踪的那一个。
     */
    private void evictIfCrowded() {
        if (windows.size() <= maxKeys) return;
        List<Map.Entry<Key, Window>> all = new ArrayList<>(windows.entrySet());
        all.sort((a, b) -> Long.compare(a.getValue().lastMs, b.getValue().lastMs));
        for (int i = 0; i < all.size() - maxKeys * 3 / 4; i++) windows.remove(all.get(i).getKey());
    }

    private static final class Window {
        private final ArrayDeque<long[]> hits = new ArrayDeque<>();   // [tsMs, segIndex]
        private volatile long lastMs;

        synchronized Verdict admit(int segmentIndex, double segSeconds, long nowMs,
                                   int windowSeconds, double maxRate, int minSamples) {
            lastMs = nowMs;
            long from = nowMs - windowSeconds * 1000L;
            while (!hits.isEmpty() && hits.peekFirst()[0] < from) hits.pollFirst();

            int fetched = hits.size() + 1;                 // 含本次
            double elapsed = hits.isEmpty()
                    ? 0 : (nowMs - hits.peekFirst()[0]) / 1000.0;
            double expected = elapsed / segSeconds;

            boolean over = fetched >= minSamples
                        && elapsed > 0
                        && fetched > expected * maxRate;
            if (over) {
                // 拒的这一次不进窗口。进了的话窗口永远填满、永远越界 —— 那是封禁不是限速。
                return new Verdict(false, fetched - 1, expected, elapsed, strictlySequential());
            }
            hits.addLast(new long[]{nowMs, segmentIndex});
            return new Verdict(true, fetched, expected, elapsed, false);
        }

        /** 窗口内是否严格递增一片不落。只在已越界时才有意义，见类注释。 */
        private boolean strictlySequential() {
            if (hits.size() < 30) return false;
            long prev = Long.MIN_VALUE;
            for (long[] h : hits) {
                if (prev != Long.MIN_VALUE && h[1] != prev + 1) return false;
                prev = h[1];
            }
            return true;
        }
    }
}
