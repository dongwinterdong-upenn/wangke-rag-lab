package tech.dragonai.wangke.protect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 抗合谋码的合谋仿真。
 *
 * <p>这套测试的存在理由：水印算法写错了【不会有任何信号】—— 它照常生成码字、
 * 照常打分、照常给出一个"嫌疑人"，只是那个人可能是无辜的。
 * 所以判据不能是"跑通了"，必须是：
 *   ① 合谋者用各种拼接策略拼出的副本，仍然至少指认出一名真合谋者
 *   ② 没参与的人一个都不被指认（这条比 ① 更重要）
 */
class TardosCodeTest {

    /** 合谋策略：给定各合谋者在某一位上的取值，产出盗版副本这一位的取值。 */
    interface Strategy { boolean pick(boolean[] colluderBits, SecureRandom rnd); }

    /**
     * 标记假设（marking assumption）：合谋者在某一位上【只能输出他们手里见得到的取值】。
     * 在 A/B 分片的落地形态下这不是理论洁癖而是物理事实 —— 三个人手里都是 A 变体时，
     * 他们没有 B，输出不了 B。
     *
     * <p>所有可实现的策略都必须先过这道包装。第一版测试没包，
     * 于是 MINORITY 在三人一致时照样翻转、ALL_ONE 全程无视手里有什么，
     * 跑出来"0/20 抓不到" —— 那不是算法失效，是我在仿真一个做不到的对手。
     */
    static Strategy realizable(Strategy inner) {
        return (bits, rnd) -> {
            boolean first = bits[0];
            for (boolean b : bits) if (b != first) return inner.pick(bits, rnd);
            return first;                       // 一致位：只能原样输出
        };
    }

    /** 多数表决：最常见的朴素拼法。 */
    static final Strategy MAJORITY = realizable((bits, rnd) -> {
        int ones = 0; for (boolean b : bits) if (b) ones++;
        return ones * 2 > bits.length;
    });
    /** 少数表决：故意反着来，试图躲开检测。 */
    static final Strategy MINORITY = realizable((bits, rnd) -> {
        int ones = 0; for (boolean b : bits) if (b) ones++;
        return ones * 2 < bits.length;
    });
    /** 逐片随机取一个合谋者的：最接近实际拼接行为。 */
    static final Strategy RANDOM_PICK = realizable((bits, rnd) -> bits[rnd.nextInt(bits.length)]);
    /** 分歧位一律取 1 / 取 0：在可实现范围内最极端的两种。 */
    static final Strategy PREFER_ONE  = realizable((bits, rnd) -> true);
    static final Strategy PREFER_ZERO = realizable((bits, rnd) -> false);

    /** 违反标记假设的策略：物理上做不到，只用来确认它【不会把无辜者顶上去】。 */
    static final Strategy IMPOSSIBLE_ALL_ONE = (bits, rnd) -> true;

    private record Setup(TardosCode code, List<TardosCode.Suspect> pool, double z) {}

    private Setup build(int users, int c, double eps1, SecureRandom rnd) {
        TardosCode code = TardosCode.create(c, users, eps1, rnd);
        List<TardosCode.Suspect> pool = new ArrayList<>();
        for (long i = 1; i <= users; i++) pool.add(new TardosCode.Suspect(i, code.issue(rnd)));
        return new Setup(code, pool, TardosCode.threshold(c, users, eps1));
    }

    private boolean[] collude(Setup s, List<Integer> idx, Strategy st, SecureRandom rnd) {
        int m = s.code().length();
        boolean[] pirate = new boolean[m];
        boolean[] bits = new boolean[idx.size()];
        for (int j = 0; j < m; j++) {
            for (int k = 0; k < idx.size(); k++) bits[k] = s.pool().get(idx.get(k)).codeword()[j];
            pirate[j] = st.pick(bits, rnd);
        }
        return pirate;
    }

    @Test
    @DisplayName("五种合谋策略下：至少指认出一名真合谋者，且零误伤无辜者")
    void collusionResistance() {
        SecureRandom rnd = new SecureRandom(new byte[]{7, 7, 7, 7});
        int users = 500, c = 3;
        double eps1 = 1e-3;
        Setup s = build(users, c, eps1, rnd);

        record Case(String name, Strategy st, boolean mustCatch) {}
        List<Case> cases = List.of(
                new Case("多数表决", MAJORITY, true),
                new Case("少数表决", MINORITY, true),
                new Case("逐片随机取", RANDOM_PICK, true),
                new Case("分歧位取1", PREFER_ONE, true),
                new Case("分歧位取0", PREFER_ZERO, true),
                // 违反标记假设 —— A/B 分片下做不到，只验它不会顶无辜者上去
                new Case("[做不到]全取1", IMPOSSIBLE_ALL_ONE, false));

        int rounds = 20;
        for (Case cs : cases) {
            int caught = 0, framed = 0;
            for (int r = 0; r < rounds; r++) {
                List<Integer> idx = new ArrayList<>();
                while (idx.size() < c) {
                    int k = rnd.nextInt(users);
                    if (!idx.contains(k)) idx.add(k);
                }
                boolean[] pirate = collude(s, idx, cs.st(), rnd);
                List<TardosCode.Accusation> acc = s.code().accuse(pirate, s.pool(), s.z());

                List<Long> guilty = idx.stream().map(i -> s.pool().get(i).userId()).toList();
                if (acc.stream().anyMatch(a -> guilty.contains(a.userId()))) caught++;
                framed += (int) acc.stream().filter(a -> !guilty.contains(a.userId())).count();
            }
            System.out.printf("  %-12s 抓到合谋者 %2d/%2d 轮，误伤无辜 %d 人次%n",
                    cs.name(), caught, rounds, framed);
            // 零误伤：所有策略都必须满足，包括做不到的那些
            assertEquals(0, framed, cs.name() + "：冤枉了无辜学员，这比抓不到更严重");
            // 抓得到：只对【可实现】的策略要求。
            // 第一版只断言了零误伤，于是 0/20 抓不到时测试照样是绿的 ——
            // 只断言一半的判据，等于给自己发了一张假的通过证。
            if (cs.mustCatch())
                assertEquals(rounds, caught, cs.name() + "：可实现的合谋策略却指认不出任何人");
        }
    }

    @Test
    @DisplayName("单人泄漏必被指认（最基本的情形不能反而失手）")
    void singleLeaker() {
        SecureRandom rnd = new SecureRandom(new byte[]{1, 2, 3, 4});
        int users = 500, c = 3;
        Setup s = build(users, c, 1e-3, rnd);
        for (int r = 0; r < 20; r++) {
            int k = rnd.nextInt(users);
            boolean[] pirate = s.pool().get(k).codeword().clone();
            List<TardosCode.Accusation> acc = s.code().accuse(pirate, s.pool(), s.z());
            assertFalse(acc.isEmpty(), "单人泄漏却指认不出任何人");
            assertEquals(s.pool().get(k).userId(), acc.get(0).userId(), "分数最高的不是真泄漏者");
            assertTrue(acc.stream().allMatch(a -> a.userId() == s.pool().get(k).userId()),
                    "单人泄漏不该牵连他人");
        }
    }

    @Test
    @DisplayName("样本只有一半位可读时：按比例缩放阈值仍能指认，且不误伤")
    void partialSample() {
        SecureRandom rnd = new SecureRandom(new byte[]{9, 9, 9, 9});
        int users = 500, c = 3;
        Setup s = build(users, c, 1e-3, rnd);
        int m = s.code().length();
        boolean[] readable = new boolean[m];
        for (int j = 0; j < m / 2; j++) readable[j] = true;      // 只放出前半程
        double z = TardosCode.thresholdForPartial(c, users, 1e-3, 0.5);

        int caught = 0, framed = 0;
        for (int r = 0; r < 20; r++) {
            int k = rnd.nextInt(users);
            boolean[] pirate = s.pool().get(k).codeword().clone();
            for (TardosCode.Suspect sus : s.pool()) {
                double sc = s.code().score(pirate, sus.codeword(), readable);
                if (sc > z) {
                    if (sus.userId() == s.pool().get(k).userId()) caught++; else framed++;
                }
            }
        }
        System.out.printf("  半程样本：抓到 %d/20，误伤 %d 人次%n", caught, framed);
        assertEquals(0, framed, "半程样本把无辜者算了进去");
        assertTrue(caught >= 18, "半程样本漏判过多：" + caught + "/20");
    }

    @Test
    @DisplayName("读不出的位必须跳过，不能当成 0")
    void unreadableBitsMustNotCountAsZero() {
        SecureRandom rnd = new SecureRandom(new byte[]{5, 5, 5, 5});
        Setup s = build(200, 3, 1e-3, rnd);
        boolean[] pirate = s.pool().get(0).codeword().clone();
        boolean[] none = new boolean[s.code().length()];          // 一位都读不出
        double sc = s.code().score(pirate, s.pool().get(0).codeword(), none);
        assertEquals(0.0, sc, 1e-9, "一位都读不出时分数必须是 0，不能凭空产生证据");
    }

    @Test
    @DisplayName("码长与阈值随合谋人数增长，且码长不足时必须显式暴露")
    void lengthGrowsWithColluders() {
        int n = 10_000; double eps = 1e-4;
        int m2 = TardosCode.suggestedLength(2, n, eps);
        int m5 = TardosCode.suggestedLength(5, n, eps);
        assertTrue(m5 > m2 * 5, "抗更多合谋者却没要求更长的码，说明公式接错了");
        System.out.printf("  n=%d 抗 2 人需 %d 位、抗 5 人需 %d 位%n", n, m2, m5);
    }
}
