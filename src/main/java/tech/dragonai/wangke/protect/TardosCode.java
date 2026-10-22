package tech.dragonai.wangke.protect;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Tardos 抗合谋指纹码（对称版，Škorić 等人的 symmetric score）。
 *
 * <p>为什么不能用 user_id 的二进制当水印：几个学员把各自的副本逐片比对，
 * 发现某片不一样就随机取一份拼起来，拼出的副本谁的 id 都对不上，
 * 合谋者反而被洗白，甚至可能拼成一个无辜者的 id —— 那比没有水印更糟。
 * 抗合谋码的作用是：不管他们怎么拼，拼出来的东西仍然会把参与者的分数抬高，
 * 而没参与的人分数不会。
 *
 * <p>落地形态是 A/B 分片：每隔 {@code wm_slot_stride} 片取一片编码成两份
 * （视觉无差别），码字第 j 位决定该学员的 m3u8 引用 A 还是 B。
 * 分片本身对所有人一样，可以进 CDN 缓存；个性化只在那份很小的 m3u8 上。
 *
 * <p><b>这个类的每一处阈值都必须由 {@code TardosCodeTest} 的合谋仿真验证过，
 * 不能凭直觉调。</b>指认无辜者是不可逆的伤害。
 */
public final class TardosCode {

    /** 概率截断，避免 p 太靠近 0/1 导致分数发散。Tardos 原文取 1/(300c)。 */
    private final double cutoff;
    /** 码长（位）。受每讲可用水印槽位数限制。 */
    private final int length;
    /** 每一位的偏置概率 p_j，全局共用，必须与码字一起持久化 —— 丢了就无法取证。 */
    private final double[] p;

    private TardosCode(int length, double cutoff, double[] p) {
        this.length = length;
        this.cutoff = cutoff;
        this.p = p;
    }

    /**
     * 生成一套码本参数。
     *
     * @param maxColluders 设计能抗的合谋人数 c
     * @param users        用户规模 n
     * @param eps1         允许的漏判概率（合谋副本一个人都指认不出）
     */
    public static TardosCode create(int maxColluders, int users, double eps1, SecureRandom rnd) {
        return createWithLength(suggestedLength(maxColluders, users, eps1), maxColluders, rnd);
    }

    /**
     * 按指定码长生成码本。用于<b>把槽位用满</b>：槽位容量通常大于所需码长，
     * 而多出来的槽位不用白不用 —— 指认阈值 Z = 20·c·ln(n/eps1) 与码长无关，
     * 码越长分数分离得越开，误判与漏判都只会更低。
     *
     * <p>反过来，<b>码长短于 suggestedLength 是绝不允许的</b>：短了之后漏判率上升，
     * 而这件事不会有任何提示，系统照常发码字、照常打分，只是抓不到人。
     */
    public static TardosCode createWithLength(int m, int maxColluders, SecureRandom rnd) {
        if (m <= 0) throw new IllegalArgumentException("码长必须为正");
        double t = 1.0 / (300.0 * maxColluders);
        double[] p = new double[m];
        double lo = Math.asin(Math.sqrt(t));
        double hi = Math.asin(Math.sqrt(1 - t));
        for (int j = 0; j < m; j++) {
            // 反正弦分布：p = sin²(r)，r 在 [asin√t, asin√(1-t)] 上均匀
            double r = lo + rnd.nextDouble() * (hi - lo);
            p[j] = Math.pow(Math.sin(r), 2);
        }
        return new TardosCode(m, t, p);
    }

    /** 已有码本参数时复原（取证时必须用当初那一套 p）。 */
    public static TardosCode of(double[] p, int maxColluders) {
        return new TardosCode(p.length, 1.0 / (300.0 * maxColluders), p.clone());
    }

    /**
     * Tardos 原文的码长下界 m = 100·c²·ln(n/eps1)。
     * 实际部署可以更短（对称版常数更好），但缩短必须先跑仿真，
     * 不能因为"槽位不够"就直接砍 —— 砍短了漏判率不会有任何提示。
     */
    public static int suggestedLength(int c, int n, double eps1) {
        return (int) Math.ceil(100.0 * c * c * Math.log(n / eps1));
    }

    public int length() { return length; }
    public double[] bias() { return p.clone(); }

    /** 给一个用户抽一份码字。第 j 位以概率 p_j 取 1。 */
    public boolean[] issue(SecureRandom rnd) {
        boolean[] x = new boolean[length];
        for (int j = 0; j < length; j++) x[j] = rnd.nextDouble() < p[j];
        return x;
    }

    /**
     * 对称 Tardos 单位得分。y = 泄漏副本这一位的取值，x = 该用户这一位的取值。
     * 一致则加分、不一致则扣分，且【稀有的一致】加得更多 —— 这正是抗合谋的来源：
     * 合谋者拼出来的每一位都来自某个参与者，参与者的分数因此被持续抬高。
     */
    private double unitScore(boolean y, boolean x, double pj) {
        double a = Math.sqrt((1 - pj) / pj);
        double b = Math.sqrt(pj / (1 - pj));
        if (y) return x ? a : -b;
        else   return x ? -a : b;
    }

    /** 用泄漏副本的码字给某个用户打分（所有位都可读）。 */
    public double score(boolean[] pirate, boolean[] user) {
        return score(pirate, user, null);
    }

    /**
     * 带可读性掩码的打分。真实的泄漏样本常常只有一部分位读得出来
     * （录屏被裁掉一段、转码把某些片糊掉、盗录者只放出前半程），
     * 读不出的位必须【跳过】而不是当成 0 —— 当成 0 会给"这一位取 0"的用户
     * 白送一份加分，而那是凭空捏造的证据。
     *
     * @param readable 长度与码字相同；true = 该位可读。传 null 表示全部可读。
     */
    public double score(boolean[] pirate, boolean[] user, boolean[] readable) {
        if (pirate.length != length || user.length != length)
            throw new IllegalArgumentException("码字长度与码本不一致：无法取证");
        if (readable != null && readable.length != length)
            throw new IllegalArgumentException("可读性掩码长度与码字不一致");
        double s = 0;
        for (int j = 0; j < length; j++) {
            if (readable != null && !readable[j]) continue;
            s += unitScore(pirate[j], user[j], p[j]);
        }
        return s;
    }

    /**
     * 位可读比例不足时，阈值必须按比例缩放，否则永远判不出来。
     * 直接拿全长阈值去卡半长样本，等于把取证系统在最需要它的时候关掉。
     */
    public static double thresholdForPartial(int c, int n, double eps1, double readableRatio) {
        if (readableRatio <= 0 || readableRatio > 1)
            throw new IllegalArgumentException("可读比例须在 (0,1]");
        return threshold(c, n, eps1) * Math.sqrt(readableRatio);
    }

    /**
     * 指认阈值。Tardos 原文 Z = 20·c·ln(n/eps1)。
     * 分数超过 Z 才指认；宁可漏判也不能误判 —— 误判的代价是冤枉一个学员。
     */
    public static double threshold(int c, int n, double eps1) {
        return 20.0 * c * Math.log(n / eps1);
    }

    /** 一次取证：返回按分数降序、且超过阈值的嫌疑人。 */
    public List<Accusation> accuse(boolean[] pirate, List<Suspect> pool, double z) {
        return accuse(pirate, pool, z, null);
    }

    /**
     * 带可读性掩码的取证。<b>真实样本几乎总是残缺的</b>——泄漏的往往只是一讲，
     * 而码字是整门课的，于是绝大多数位根本读不出来。
     *
     * <p>这个重载不是可选的便利方法：没有它，调用方只能用全长版本，
     * 而全长版本把读不出的位当成 0 打分，等于凭空造证据。
     * 阈值也必须换成 {@link #thresholdForPartial}，否则残缺样本永远够不着全长阈值，
     * 取证系统就在最需要它的时候静悄悄地什么也不指认。
     */
    public List<Accusation> accuse(boolean[] pirate, List<Suspect> pool, double z, boolean[] readable) {
        List<Accusation> out = new ArrayList<>();
        for (Suspect s : pool) {
            double sc = score(pirate, s.codeword(), readable);
            if (sc > z) out.add(new Accusation(s.userId(), sc));
        }
        out.sort(Comparator.comparingDouble(Accusation::score).reversed());
        return out;
    }

    public record Suspect(long userId, boolean[] codeword) {}
    public record Accusation(long userId, double score) {}
}
