package tech.dragonai.wangke.trade;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * 激活码的可逆保护。库里存两样东西，各有各的用途，缺一不可：
 *
 * <ul>
 *   <li><b>code_hash</b>（SHA-256 + 盐）—— 学员输入时用来查行。单向，泄库也换不回码。
 *   <li><b>code_enc</b>（AES-256-GCM 密文）—— 代理要把码发给学员，所以平台必须还能读回明文。
 * </ul>
 *
 * <p>只有 hash 是不够的：代理买了 10 个码，没有明文就没法交付，等于货发不出去。
 * 只有密文也不行：学员输码时无法定位行，除非把全表解密一遍比对。
 *
 * <p><b>AAD 绑定 code_hash</b>：拿到库写权限的人，把 A 行的密文复制到 B 行时，
 * 解密会当场失败（AEADBadTagException），而不是安静地解出另一个码。
 * 没有这层绑定，密文是可以在行之间搬家的 —— 加密本身拦不住"搬家"这种攻击。
 */
public final class CodeVault {

    private static final int IV_LEN = 12;        // GCM 标准 96 位
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final byte[] hashSalt;
    private final SecureRandom rnd = new SecureRandom();

    /**
     * @param key32    32 字节 AES-256 密钥，来自应用配置，<b>不入库</b>
     * @param hashSalt code_hash 的全局盐
     */
    public CodeVault(byte[] key32, byte[] hashSalt) {
        if (key32 == null || key32.length != 32)
            throw new IllegalArgumentException(
                    "AES-256 密钥必须是 32 字节，实际 " + (key32 == null ? "null" : key32.length + " 字节"));
        // 占位密钥（全 0、全同一字节）是部署时最常见的事故：配置没填，
        // 程序照样跑，密文照样写，等发现时全库都是用空密钥加的。宁可起不来。
        boolean allSame = true;
        for (byte b : key32) if (b != key32[0]) { allSame = false; break; }
        if (allSame)
            throw new IllegalArgumentException("密钥每个字节都相同，看起来是没填的占位值，拒绝启动");
        this.key = new SecretKeySpec(key32, "AES");
        this.hashSalt = hashSalt.clone();
    }

    /** 明文 → 密文。每次调用换一个随机 IV，同一个码两次加密结果不同（防止靠密文相等判断码相同）。 */
    public byte[] seal(String plain, String codeHash) {
        try {
            byte[] iv = new byte[IV_LEN];
            rnd.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            c.updateAAD(codeHash.getBytes(StandardCharsets.US_ASCII));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[IV_LEN + ct.length];
            System.arraycopy(iv, 0, out, 0, IV_LEN);
            System.arraycopy(ct, 0, out, IV_LEN, ct.length);
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("激活码加密失败", e);
        }
    }

    /** 密文 → 明文。密文被篡改、被换行、或密钥不对，一律抛异常，绝不返回可疑明文。 */
    public String open(byte[] sealed, String codeHash) {
        if (sealed == null || sealed.length <= IV_LEN)
            throw new IllegalStateException("密文长度不合法：" + (sealed == null ? "null" : sealed.length));
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(TAG_BITS, Arrays.copyOf(sealed, IV_LEN)));
            c.updateAAD(codeHash.getBytes(StandardCharsets.US_ASCII));
            return new String(c.doFinal(sealed, IV_LEN, sealed.length - IV_LEN), StandardCharsets.UTF_8);
        } catch (javax.crypto.AEADBadTagException e) {
            throw new IllegalStateException(
                    "激活码密文校验失败：密钥不匹配，或这行密文不属于这行 code_hash（被搬过家）", e);
        } catch (Exception e) {
            throw new IllegalStateException("激活码解密失败", e);
        }
    }

    /** SHA-256(码 + 全局盐)。学员输码时靠它定位行。 */
    public String hash(String plain) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            md.update(hashSalt);
            byte[] d = md.digest(plain.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                               .append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
