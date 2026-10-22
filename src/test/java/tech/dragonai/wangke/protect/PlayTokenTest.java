package tech.dragonai.wangke.protect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 播放令牌。每一条【绕过路径】都必须有一条对应的反例 ——
 * 这套令牌的存在意义就是挡住"复制 key 的 URL 到别处照样能取"，
 * 少挡一样，整套 HLS 加密就等于没有。
 */
class PlayTokenTest {

    static final byte[] SECRET = "01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8);
    static final PlayToken T = new PlayToken(SECRET);
    static final Instant NOW = Instant.parse("2026-08-11T10:00:00Z");
    static final long LESSON = 7L;

    static PlayToken.Claims claims(Instant exp) {
        return new PlayToken.Claims("SESS_A1", 42L, 7L, "DEV_X",
                PlayToken.ipScope("203.0.113.55"), exp);
    }
    static String fresh() { return T.issue(claims(NOW.plusSeconds(60))); }

    @Test
    @DisplayName("正常路径：同会话同设备同网段，未过期 —— 放行")
    void happyPath() {
        var r = T.verify(fresh(), LESSON, "DEV_X", "203.0.113.55", NOW);
        assertInstanceOf(PlayToken.Ok.class, r);
        assertEquals(42L, ((PlayToken.Ok) r).claims().userId());
    }

    @Test
    @DisplayName("换设备 —— 拒（挡住把令牌复制到另一台机器）")
    void wrongDevice() {
        var r = T.verify(fresh(), LESSON, "DEV_OTHER", "203.0.113.55", NOW);
        assertEquals("设备不匹配", ((PlayToken.Rejected) r).reason());
    }

    @Test
    @DisplayName("换网段 —— 拒（挡住把链接丢进群里即时转发）")
    void wrongNetwork() {
        var r = T.verify(fresh(), LESSON, "DEV_X", "198.51.100.9", NOW);
        assertEquals("来源网段不匹配", ((PlayToken.Rejected) r).reason());
    }

    @Test
    @DisplayName("同网段内换 IP —— 放行（移动网络切基站不能让人掉线）")
    void sameSubnetOk() {
        var r = T.verify(fresh(), LESSON, "DEV_X", "203.0.113.200", NOW);
        assertInstanceOf(PlayToken.Ok.class, r,
                "严格绑完整 IP 会让手机端十分钟掉一次线，这是有意的让步");
    }

    @Test
    @DisplayName("过期 —— 拒；且到期瞬间就算过期")
    void expired() {
        String tok = T.issue(claims(NOW.plusSeconds(60)));
        assertInstanceOf(PlayToken.Ok.class, T.verify(tok, LESSON, "DEV_X", "203.0.113.55", NOW.plusSeconds(59)));
        assertEquals("令牌已过期",
                ((PlayToken.Rejected) T.verify(tok, LESSON, "DEV_X", "203.0.113.55", NOW.plusSeconds(60))).reason());
        assertEquals("令牌已过期",
                ((PlayToken.Rejected) T.verify(tok, LESSON, "DEV_X", "203.0.113.55", NOW.plus(Duration.ofHours(1)))).reason());
    }

    @Test
    @DisplayName("篡改载荷（把 userId 改成别人）—— 拒")
    void tamperedPayload() {
        String raw = new String(Base64.getUrlDecoder().decode(fresh()), StandardCharsets.UTF_8);
        String forged = raw.replace("|42|", "|99|");
        String tok = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(forged.getBytes(StandardCharsets.UTF_8));
        assertEquals("签名不匹配", ((PlayToken.Rejected) T.verify(tok, LESSON, "DEV_X", "203.0.113.55", NOW)).reason());
    }

    @Test
    @DisplayName("篡改过期时间（自己续期）—— 拒")
    void tamperedExpiry() {
        String raw = new String(Base64.getUrlDecoder().decode(
                T.issue(claims(NOW.plusSeconds(60)))), StandardCharsets.UTF_8);
        String[] p = raw.split("\\|");
        p[5] = String.valueOf(NOW.plus(Duration.ofDays(365)).getEpochSecond());
        String tok = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(String.join("|", p).getBytes(StandardCharsets.UTF_8));
        assertEquals("签名不匹配", ((PlayToken.Rejected) T.verify(tok, LESSON, "DEV_X", "203.0.113.55", NOW)).reason());
    }

    @Test
    @DisplayName("换一把密钥签发的令牌 —— 拒（防伪造）")
    void foreignSecret() {
        PlayToken other = new PlayToken("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX".getBytes(StandardCharsets.UTF_8));
        String tok = other.issue(claims(NOW.plusSeconds(60)));
        assertEquals("签名不匹配", ((PlayToken.Rejected) T.verify(tok, LESSON, "DEV_X", "203.0.113.55", NOW)).reason());
    }

    @Test
    @DisplayName("垃圾输入不得抛异常 —— 只能返回拒绝")
    void garbageInput() {
        for (String bad : new String[]{"", "!!!!", "YWJj", "a|b|c"}) {
            var r = assertDoesNotThrow(() -> T.verify(bad, LESSON, "DEV_X", "203.0.113.55", NOW));
            assertInstanceOf(PlayToken.Rejected.class, r, "输入 " + bad + " 竟然通过了");
        }
    }

    @Test
    @DisplayName("先验签后解析：签名不对时不许先信任里面的字段")
    void verifyBeforeParse() {
        String forged = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("SESS|不是数字|7|DEV_X|203.0.113|999|sig".getBytes(StandardCharsets.UTF_8));
        var r = T.verify(forged, LESSON, "DEV_X", "203.0.113.55", NOW);
        assertEquals("签名不匹配", ((PlayToken.Rejected) r).reason(),
                "先解析后验签的话，非法字段会在验签之前就被当成数据处理");
    }

    @Test
    @DisplayName("HMAC 密钥过短必须拒绝构造（弱密钥等于没签名）")
    void weakSecretRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new PlayToken("short".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("定时比较：长度不同、逐位不同都要判不等")
    void constantTime() {
        assertTrue(PlayToken.constantTimeEquals("abc", "abc"));
        assertFalse(PlayToken.constantTimeEquals("abc", "abd"));
        assertFalse(PlayToken.constantTimeEquals("abc", "abcd"));
        assertFalse(PlayToken.constantTimeEquals("", "a"));
        assertTrue(PlayToken.constantTimeEquals("", ""));
    }

    @Test
    @DisplayName("拿这一讲的令牌去开另一讲 —— 拒（试听令牌不能解正片）")
    void tokenIsBoundToItsLesson() {
        var r = T.verify(fresh(), 8L, "DEV_X", "203.0.113.55", NOW);
        assertInstanceOf(PlayToken.Rejected.class, r);
        assertEquals("令牌不是这一讲的", ((PlayToken.Rejected) r).reason(),
                "令牌里带着 lessonId，但没在这里比对的话，"
              + "试听那一讲发出的令牌能解开正片每一讲的密钥，而且一切正常、没有任何报错");
    }

    @Test
    @DisplayName("字段含分隔符必须在签发时就拒，而不是等到校验时报「字段数不对」")
    void separatorInFieldsRejectedAtIssue() {
        var bad = new PlayToken.Claims("SESS|X", 42L, 7L, "DEV_X",
                PlayToken.ipScope("203.0.113.55"), NOW.plusSeconds(60));
        var e = assertThrows(IllegalArgumentException.class, () -> T.issue(bad));
        assertTrue(e.getMessage().contains("分隔符"), e.getMessage());
        var bad2 = new PlayToken.Claims("SESS_A1", 42L, 7L, "DEV|Y",
                PlayToken.ipScope("203.0.113.55"), NOW.plusSeconds(60));
        assertThrows(IllegalArgumentException.class, () -> T.issue(bad2));
    }

    @Test
    @DisplayName("IPv4 取 /24、IPv6 取 /48")
    void ipScoping() {
        assertEquals("203.0.113", PlayToken.ipScope("203.0.113.55"));
        assertEquals("2001:db8:85a3", PlayToken.ipScope("2001:db8:85a3:0:0:8a2e:370:7334"));
        assertEquals("", PlayToken.ipScope(null));
    }
}
