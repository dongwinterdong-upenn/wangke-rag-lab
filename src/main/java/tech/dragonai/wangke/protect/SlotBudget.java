package tech.dragonai.wangke.protect;

/**
 * 水印槽位预算。回答一个必须先算清楚、否则整套取证形同虚设的问题：
 * <b>这门课的视频里，到底放得下多长的码字？</b>
 *
 * <p>A/B 分片水印的每一位需要一个"槽位"—— 一片被编码成视觉无差别的两份。
 * 槽位数由 总时长 / 单片时长 / 取样步长 决定，是个硬上限。
 * 而抗合谋所需的码长随合谋人数【平方】增长。两边一对，很容易出现
 * "码长要一万位、槽位只有一千五"的情况 —— 此时系统照常运行、照常发码字、
 * 照常打分，只是【谁也抓不到】，而且不会有任何报错。
 *
 * <p>代价的另一头是存储：步长 N 意味着 1/N 的分片要存两份。
 * 步长 1 = 2 倍存储，步长 6 = 1.17 倍。
 */
public final class SlotBudget {

    public record Budget(int slots, double storageMultiplier, int maxColludersSupported) {}

    /**
     * @param totalSeconds 课程总时长（秒）
     * @param segSeconds   HLS 单片时长（秒），常见 4~10
     * @param stride       每隔几片取一片做双版本
     * @param users        用户规模 n
     * @param eps1         允许的漏判概率
     */
    public static Budget of(double totalSeconds, double segSeconds, int stride,
                            int users, double eps1) {
        int segments = (int) Math.floor(totalSeconds / segSeconds);
        int slots = segments / stride;
        double storage = 1.0 + 1.0 / stride;
        return new Budget(slots, storage, maxColluders(slots, users, eps1));
    }

    /** 给定槽位数，反解还撑得住几人合谋。不足 2 意味着只能抓单人泄漏。 */
    public static int maxColluders(int slots, int users, double eps1) {
        double ln = Math.log(users / eps1);
        double c = Math.sqrt(slots / (100.0 * ln));     // m = 100c²ln(n/eps1)
        return (int) Math.floor(c);
    }

    /** 给定目标抗合谋人数，反解需要的取样步长。返回 0 表示步长 1 也不够。 */
    public static int requiredStride(double totalSeconds, double segSeconds,
                                     int users, double eps1, int wantColluders) {
        int need = TardosCode.suggestedLength(wantColluders, users, eps1);
        int segments = (int) Math.floor(totalSeconds / segSeconds);
        if (need > segments) return 0;                   // 每片都做双版本也放不下
        return segments / need;
    }

    private SlotBudget() {}
}
