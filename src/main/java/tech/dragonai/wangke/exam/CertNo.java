package tech.dragonai.wangke.exam;

import tech.dragonai.wangke.trade.ActivationCodes;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * 结业证明编号。
 *
 * <h3>为什么不能是顺序号</h3>
 * 顺序编号（WK000001、WK000002…）泄露两件事，而且都是免费泄露：
 * <ul>
 *   <li><b>发证量</b> —— 任何人拿到自己的编号就知道平台一共发了多少张，
 *       这是对外谈判里不该白送的数字。
 *   <li><b>伪造成本</b> —— 验真接口是公开的，顺序号意味着"编一个相邻的号去查"
 *       几乎必中，于是伪造者能拿一张真实存在的证书去比对版式、姓名格式，
 *       甚至直接冒用。而验真接口不会有任何异常信号：他查的每一个号都是真的。
 * </ul>
 *
 * <p>所以编号是 12 位<b>密码学随机</b>字符。24 字符表 × 12 位 ≈ 2^55，
 * 在线枚举不可行。这不表示可以不限流 —— 真正防刷的仍然是接口限流，
 * 编号长度只是让"枚举"这条路本身不成立。
 *
 * <h3>字母表借用激活码那一份</h3>
 * 不是图省事：验真编号和激活码有完全相同的人因问题 —— 会被抄在纸上、
 * 念给客服听、从截图里认。0/O、1/I、2/Z、5/S、8/B 这几对易混字符在两边都必须排掉，
 * 各写一份字母表迟早只改一边。{@link ActivationCodes#ALPHABET} 那份带着
 * "同一易混组里最多只有一个成员在表内"的性质，且有测试守着。
 */
public final class CertNo {

    /** 编号前缀。人一眼能认出这是什么，且不含任何顺序信息。 */
    public static final String PREFIX = "WK";

    /** 随机段长度。改这个数要同时改 certificate.cert_no 的列宽（VARCHAR(32)）。 */
    public static final int BODY = 12;

    private static final int GROUP = 4;
    private static final SecureRandom RND = new SecureRandom();

    private CertNo() {}

    /** 形如 {@code WK-ACDE-FHJK-LMNP}。 */
    public static String generate() {
        var sb = new StringBuilder(PREFIX);
        for (int i = 0; i < BODY; i++) {
            if (i % GROUP == 0) sb.append('-');
            sb.append(ActivationCodes.ALPHABET.charAt(RND.nextInt(ActivationCodes.ALPHABET.length())));
        }
        return sb.toString();
    }

    /**
     * 把用户抄来的编号规整成库里存的那种形式。大小写、空格、少打或多打横杠、
     * 从微信复制带进来的零宽字符都能救回来。
     *
     * <p>G→6、V→U 的纠正与激活码同源：这两组里只有一个成员在字母表内，
     * 所以纠正是安全的（用户打 G 时不可能是想打一个合法的 G）。
     * 其余易混字符两个成员都不在表内，输进来就是错，明确报出来而不是模糊匹配 ——
     * 模糊匹配会让"查无此证"和"你抄错了一位"变成同一个结果，
     * 而这两件事对拿着证书来核验的人是完全不同的信号。
     *
     * @throws IllegalArgumentException 输入不可能是一个合法编号（连库都不用查）
     */
    public static String normalize(String input) {
        if (input == null || input.isBlank())
            throw new IllegalArgumentException("请输入结业证明编号");

        var raw = new StringBuilder();
        for (char ch : input.toUpperCase(Locale.ROOT).toCharArray())
            if (ch >= 'A' && ch <= 'Z' || ch >= '0' && ch <= '9') raw.append(ch);

        if (!raw.toString().startsWith(PREFIX))
            throw new IllegalArgumentException("结业证明编号应以 " + PREFIX + " 开头，请核对");
        String body = raw.substring(PREFIX.length());
        if (body.length() != BODY)
            throw new IllegalArgumentException(
                    "结业证明编号的编码段应为 " + BODY + " 位，你输入了 " + body.length() + " 位");

        var out = new StringBuilder(PREFIX);
        for (int i = 0; i < BODY; i++) {
            char ch = body.charAt(i);
            if (ch == 'G') ch = '6';
            if (ch == 'V') ch = 'U';
            if (ActivationCodes.ALPHABET.indexOf(ch) < 0)
                throw new IllegalArgumentException(
                        "编码段第 " + (i + 1) + " 位『" + body.charAt(i) + "』不是编号里会出现的字符，请核对");
            if (i % GROUP == 0) out.append('-');
            out.append(ch);
        }
        return out.toString();
    }
}
