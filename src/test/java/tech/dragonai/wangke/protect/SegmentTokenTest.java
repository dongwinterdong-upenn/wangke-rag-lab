package tech.dragonai.wangke.protect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tech.dragonai.wangke.ItDb;

import java.time.Instant;
import java.util.Map;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 取片凭据。这里钉的是一条会「不报错地失效」的性质，所以每个负例都必须钉住失败原因：
 * 只断言"被拒了"的话，一条因为路径不合法而被拒的用例，会让"签名绑不绑用户"
 * 这件事一次都没被验证过。
 */
class SegmentTokenTest {

    private final SegmentToken t = new SegmentToken(ItDb.testKey(31));
    private static final Instant NOW = Instant.parse("2026-08-21T10:00:00Z");
    private static final Instant EXP = NOW.plusSeconds(3600);

    private static Map<String, String> parse(String query) {
        Map<String, String> m = new HashMap<>();
        for (String kv : query.split("&")) {
            int i = kv.indexOf('=');
            m.put(kv.substring(0, i), kv.substring(i + 1));
        }
        return m;
    }

    private SegmentToken.Result verify(String object, Map<String, String> q, Instant now) {
        return t.verify(object, q.get("l"), q.get("u"), q.get("e"), q.get("s"), now);
    }

    @Test
    @DisplayName("自己签的自己认")
    void roundTrip() {
        var q = parse(t.query(42, 7, "fde/l3/627_a.ts", EXP));
        var r = verify("fde/l3/627_a.ts", q, NOW);
        assertInstanceOf(SegmentToken.Ok.class, r, () -> String.valueOf(r));
        assertEquals(42, ((SegmentToken.Ok) r).userId());
        assertEquals(7, ((SegmentToken.Ok) r).lessonId());
    }

    /**
     * ★ 这是整个改动存在的理由。
     *
     * <p>{@code {i}_a.ts / {i}_b.ts} 这个命名规则印在每个人自己的播放列表里。
     * 分片过去在 nginx 上零凭据直出，于是把自己 m3u8 里所有 {@code _b} 改成 {@code _a}
     * 再下载，产出副本的码字恒为全 A —— 拿去 {@code WatermarkService.accuse}
     * 谁都指认不到，<b>而且不报错，只是查不出来</b>。
     * 签名盖住对象名之后，另一个变体的 URL 拼得出来但签不出来。
     */
    @Test
    @DisplayName("★ 把 _b 改成 _a：URL 拼得出来，签名对不上 —— 抹平水印这条路断掉")
    void cannotReuseSignatureForTheOtherVariant() {
        var q = parse(t.query(42, 7, "fde/l3/627_b.ts", EXP));

        var flipped = verify("fde/l3/627_a.ts", q, NOW);
        assertInstanceOf(SegmentToken.Rejected.class, flipped);
        assertEquals("取片签名不匹配", ((SegmentToken.Rejected) flipped).reason());

        // 阴性对照：不改变体的话必须是通的，否则上面那条可能只是"验签整个坏了"
        assertInstanceOf(SegmentToken.Ok.class, verify("fde/l3/627_b.ts", q, NOW));
    }

    @Test
    @DisplayName("★ 签名绑用户：拿别人的票取同一片，验不过")
    void signatureIsBoundToTheUser() {
        var mine = parse(t.query(42, 7, "fde/l3/627_a.ts", EXP));
        mine.put("u", "43");                       // 只改 u，签名不动
        var r = verify("fde/l3/627_a.ts", mine, NOW);
        assertInstanceOf(SegmentToken.Rejected.class, r);
        assertEquals("取片签名不匹配", ((SegmentToken.Rejected) r).reason());
    }

    @Test
    @DisplayName("★ 签名绑讲次：第 7 讲的票取不了第 8 讲的片")
    void signatureIsBoundToTheLesson() {
        var q = parse(t.query(42, 7, "fde/l3/627_a.ts", EXP));
        q.put("l", "8");
        var r = verify("fde/l3/627_a.ts", q, NOW);
        assertEquals("取片签名不匹配", ((SegmentToken.Rejected) r).reason());
    }

    /**
     * ★ 过期判断读的是 {@code e}，而 {@code e} 是对方递进来的。
     * 先看过期再验签的话，一张把 e 写到 2099 年的伪票会先通过过期检查 ——
     * 那一步不会出错，只是它检验的是攻击者自己填的数字。
     */
    @Test
    @DisplayName("★ 把有效期改长：先验签，所以改了就是签名不匹配，而不是「还没过期」")
    void expiryCannotBeExtendedByEditingTheUrl() {
        var q = parse(t.query(42, 7, "fde/l3/627_a.ts", EXP));
        q.put("e", String.valueOf(EXP.plusSeconds(86400 * 365).getEpochSecond()));
        var r = verify("fde/l3/627_a.ts", q, NOW);
        assertInstanceOf(SegmentToken.Rejected.class, r);
        assertEquals("取片签名不匹配", ((SegmentToken.Rejected) r).reason());
    }

    @Test
    @DisplayName("到期就是到期，边界取闭区间的右端")
    void expires() {
        var q = parse(t.query(42, 7, "fde/l3/627_a.ts", EXP));
        assertInstanceOf(SegmentToken.Ok.class, verify("fde/l3/627_a.ts", q, EXP.minusSeconds(1)));
        var r = verify("fde/l3/627_a.ts", q, EXP);
        assertEquals("取片凭据已过期", ((SegmentToken.Rejected) r).reason());
    }

    @Test
    @DisplayName("★ 越界路径：编码形态也拒 —— 校验的字符集里根本没有 %")
    void rejectsTraversalAndEncodedForms() {
        for (String bad : new String[]{
                "fde/../../etc/passwd.ts", "/etc/passwd.ts", "fde//l3/1.ts",
                "fde/l3/%2e%2e/1.ts", "fde/l3/1.ts\n", "fde/l3/1.mp4", "fde/l3/1.ts?x=1"}) {
            var q = parse(t.query(42, 7, "fde/l3/627_a.ts", EXP));
            var r = t.verify(bad, q.get("l"), q.get("u"), q.get("e"), q.get("s"), NOW);
            assertInstanceOf(SegmentToken.Rejected.class, r, bad);
            assertEquals("分片路径不合法", ((SegmentToken.Rejected) r).reason(), bad);
        }
    }

    /**
     * ★ 签名端也要查路径。取片端查的是"别人递进来的"，签名端查的是"我们自己拼出来的" ——
     * 后者出问题的方式是 hls_key 被写成带 {@code ../} 的值，那样签出来的票据是
     * <b>带合法签名的</b>越界路径，取片端验签会通过，拦不住。
     */
    @Test
    @DisplayName("★ 签名端就拒越界路径，不把它变成一个带合法签名的越界路径")
    void signingRefusesBadObjectPaths() {
        var e = assertThrows(IllegalArgumentException.class,
                () -> t.query(42, 7, "fde/../../etc/shadow.ts", EXP));
        assertTrue(e.getMessage().contains("不合法"), e.getMessage());
    }

    @Test
    @DisplayName("换一把根密钥就认不出来 —— 子钥确实是从根密钥派生的")
    void differentSecretDifferentSignature() {
        var q = parse(t.query(42, 7, "fde/l3/627_a.ts", EXP));
        var other = new SegmentToken(ItDb.testKey(32));
        var r = other.verify("fde/l3/627_a.ts", q.get("l"), q.get("u"), q.get("e"), q.get("s"), NOW);
        assertEquals("取片签名不匹配", ((SegmentToken.Rejected) r).reason());
    }

    @Test
    @DisplayName("缺参数与格式错分别报自己的原因，不混成一句")
    void missingAndMalformed() {
        assertEquals("取片凭据缺失",
                ((SegmentToken.Rejected) t.verify("fde/l3/1.ts", "7", "42", null, "x", NOW)).reason());
        assertEquals("取片凭据格式错",
                ((SegmentToken.Rejected) t.verify("fde/l3/1.ts", "七", "42", "1", "x", NOW)).reason());
    }
}
