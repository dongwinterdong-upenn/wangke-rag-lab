package tech.dragonai.wangke.protect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 取片速率闸。
 *
 * <p>这里最容易写出的假绿是"造一个疯狂下载的序列，断言它被拒" ——
 * 那条用例在一个<b>把所有人都拒掉</b>的实现上同样是绿的。
 * 所以每条正例都配一条阴性对照：正常播放必须一路放行。
 */
class SegmentPaceTest {

    private static final double SEG = 6.0;                 // 6 秒一片
    private static final long T0 = 1_755_000_000_000L;

    private SegmentPace pace() { return new SegmentPace(180, 8.0, 60, 20_000); }

    @Test
    @DisplayName("★ 阴性对照：老老实实按 6 秒一片看两小时，一次都不该被拦")
    void normalPlaybackIsNeverThrottled() {
        var p = pace();
        for (int i = 0; i < 1200; i++) {
            var v = p.admit(1, 1, i, SEG, T0 + i * 6000L);
            assertTrue(v.allowed(), "第 " + i + " 片被限速了：" + v);
        }
    }

    @Test
    @DisplayName("★ 阴性对照：一路拖进度条（跳着取、每次取两三片）也不该被拦")
    void seekingIsNeverThrottled() {
        var p = pace();
        long t = T0;
        for (int seek = 0; seek < 60; seek++) {
            int base = (seek * 137) % 1200;                // 乱跳
            for (int k = 0; k < 3; k++) {
                var v = p.admit(1, 1, base + k, SEG, t);
                assertTrue(v.allowed(), "拖动第 " + seek + " 次被限速：" + v);
                t += 400;
            }
            t += 8000;                                     // 看几秒再拖
        }
    }

    @Test
    @DisplayName("★ 全速抓取被拦下来，且拦的时候确实越了 8 倍那条线")
    void bulkDownloadIsThrottled() {
        var p = pace();
        int blocked = 0, allowed = 0;
        SegmentPace.Verdict first = null;
        for (int i = 0; i < 400; i++) {
            var v = p.admit(1, 1, i, SEG, T0 + i * 50L);   // 每 50ms 一片 = 120 倍速
            if (v.allowed()) allowed++;
            else { blocked++; if (first == null) first = v; }
        }
        assertTrue(blocked > 250, "抓取几乎没被拦：放行 " + allowed + " 拒 " + blocked);
        assertNotNull(first);
        assertTrue(first.ratio() > 8.0, "拦的时候还没越线：" + first);
        assertTrue(first.sequential(), "严格递增没被记成佐证信号：" + first);
    }

    /**
     * ★ 被拒的请求不计入窗口。计入的话窗口永远填满、再也出不来 ——
     * 那是封禁不是限速，而误伤一个付了钱的学员的代价远大于放慢一个下载的人。
     */
    @Test
    @DisplayName("★ 限速之后能恢复：窗口会老化，不是一踩线就永久拒绝")
    void throttlingRecoversAfterTheWindowAges() {
        var p = pace();
        for (int i = 0; i < 400; i++) p.admit(1, 1, i, SEG, T0 + i * 50L);
        assertFalse(p.admit(1, 1, 400, SEG, T0 + 20_000).allowed(), "刚抓完就放行了");

        // 停 4 分钟（窗口 180 秒）之后回来正常看
        long t = T0 + 240_000;
        for (int i = 0; i < 50; i++)
            assertTrue(p.admit(1, 1, 400 + i, SEG, t + i * 6000L).allowed(),
                    "窗口没有老化，限速变成了封禁");
    }

    @Test
    @DisplayName("★ 起播那几秒不判：没有最小样本数的话，每一次开播都会被判成下载")
    void startupBufferingIsNotAccused() {
        var p = pace();
        for (int i = 0; i < 20; i++)
            assertTrue(p.admit(1, 1, i, SEG, T0 + i * 30L).allowed(), "起播缓冲被判成下载");
    }

    @Test
    @DisplayName("★ 两个人互不影响：一个人被限速，不能把另一个人一起限住")
    void windowsAreKeyedByUserAndLesson() {
        var p = pace();
        for (int i = 0; i < 400; i++) p.admit(1, 1, i, SEG, T0 + i * 50L);
        assertFalse(p.admit(1, 1, 400, SEG, T0 + 20_000).allowed());
        assertTrue(p.admit(2, 1, 0, SEG, T0 + 20_000).allowed(), "别人的抓取把这个人限住了");
        assertTrue(p.admit(1, 2, 0, SEG, T0 + 20_000).allowed(), "另一讲被上一讲的抓取限住了");
    }

    @Test
    @DisplayName("拿不到片长就放行 —— 宁可漏判，也不用一个猜出来的阈值")
    void unknownSegmentLengthNeverBlocks() {
        var p = pace();
        for (int i = 0; i < 500; i++)
            assertTrue(p.admit(1, 1, i, 0, T0 + i).allowed());
    }

    @Test
    @DisplayName("跟踪表有上界：不会被刷 user_id 撑成 OOM")
    void trackedKeysAreBounded() {
        var p = new SegmentPace(180, 8.0, 60, 256);
        for (int u = 0; u < 5000; u++) p.admit(u, 1, 0, SEG, T0 + u);
        assertTrue(p.trackedKeys() <= 256, "跟踪表没有上界：" + p.trackedKeys());
    }
}
