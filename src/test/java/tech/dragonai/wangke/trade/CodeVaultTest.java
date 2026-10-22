package tech.dragonai.wangke.trade;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.*;

class CodeVaultTest {

    static byte[] key(int seed) {
        byte[] k = new byte[32];
        new SecureRandom(new byte[]{(byte) seed}).nextBytes(k);
        return k;
    }

    static final byte[] SALT = "salt".getBytes(StandardCharsets.UTF_8);
    static final CodeVault V = new CodeVault(key(7), SALT);

    @Test
    @DisplayName("往返无损")
    void roundTrip() {
        String code = "ACDE-FHJK-LMNP-QRTU";
        String h = V.hash(code);
        assertEquals(code, V.open(V.seal(code, h), h));
    }

    @Test
    @DisplayName("同一个码两次加密结果不同（否则密文相等就泄露了码相等）")
    void ivIsRandom() {
        String h = V.hash("ACDE-FHJK-LMNP-QRTU");
        assertNotEquals(java.util.Arrays.toString(V.seal("ACDE-FHJK-LMNP-QRTU", h)),
                        java.util.Arrays.toString(V.seal("ACDE-FHJK-LMNP-QRTU", h)));
    }

    @Test
    @DisplayName("密文搬到别的行会当场失败，而不是解出另一个码")
    void aadBindsRowIdentity() {
        byte[] sealed = V.seal("ACDE-FHJK-LMNP-QRTU", V.hash("ACDE-FHJK-LMNP-QRTU"));
        var e = assertThrows(IllegalStateException.class,
                () -> V.open(sealed, V.hash("34679-OTHER-CODE")));
        assertTrue(e.getMessage().contains("搬过家"), e.getMessage());
    }

    @Test
    @DisplayName("改一个字节就失败：密文不是可以随便编辑的")
    void tamperingIsDetected() {
        String h = V.hash("ACDE-FHJK-LMNP-QRTU");
        byte[] sealed = V.seal("ACDE-FHJK-LMNP-QRTU", h);
        sealed[sealed.length - 1] ^= 0x01;
        assertThrows(IllegalStateException.class, () -> V.open(sealed, h));
    }

    @Test
    @DisplayName("换密钥解不开")
    void wrongKeyFails() {
        String h = V.hash("ACDE-FHJK-LMNP-QRTU");
        byte[] sealed = V.seal("ACDE-FHJK-LMNP-QRTU", h);
        var other = new CodeVault(key(9), SALT);
        assertThrows(IllegalStateException.class, () -> other.open(sealed, h));
    }

    @Test
    @DisplayName("占位密钥拒绝启动：配置没填时宁可起不来，也不要写一库空密钥密文")
    void placeholderKeyRejected() {
        assertThrows(IllegalArgumentException.class, () -> new CodeVault(new byte[32], SALT));
        var e = assertThrows(IllegalArgumentException.class, () -> new CodeVault(new byte[16], SALT));
        assertTrue(e.getMessage().contains("32 字节"), e.getMessage());
    }

    @Test
    @DisplayName("hash 是加盐的：换盐同码不同 hash")
    void hashIsSalted() {
        var other = new CodeVault(key(7), "other-salt".getBytes(StandardCharsets.UTF_8));
        assertNotEquals(V.hash("ACDE-FHJK-LMNP-QRTU"), other.hash("ACDE-FHJK-LMNP-QRTU"));
        assertEquals(64, V.hash("ACDE-FHJK-LMNP-QRTU").length());
    }

    @Test
    @DisplayName("截断的密文报错而不是返回半截明文")
    void truncatedCiphertextRejected() {
        assertThrows(IllegalStateException.class, () -> V.open(new byte[8], V.hash("x")));
        assertThrows(IllegalStateException.class, () -> V.open(null, V.hash("x")));
    }
}
