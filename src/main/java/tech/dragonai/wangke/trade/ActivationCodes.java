package tech.dragonai.wangke.trade;

import java.security.SecureRandom;

/**
 * 激活码的字母表、生成与输入规整。生成端与输入端<b>必须共用同一份定义</b>：
 * 分开写两份，字母表哪天改了，生成出来的码会当场变成"输入非法"。
 *
 * <h3>字母表怎么挑的</h3>
 * 目标不是"看起来专业"，是<b>让任何字母表外的字符都能被判定为输错</b>，
 * 于是提示能说人话（"第 7 位不是有效字符"），而不是笼统的"激活码不存在"。
 *
 * <p>做法是把每一对易混字符拆开处理：
 * <pre>
 *   {0,O} {1,I} {2,Z} {5,S} {8,B}   两个都不要 → 输进来就是错，无从猜起，直接拒
 *   {6,G} 留 6 不留 G                只有一个在表里 → 输 G 必然想打 6，可以安全纠正
 *   {U,V} 留 U 不留 V                同上
 * </pre>
 *
 * <p>这样得到一条能被断言的性质：<b>同一易混组里最多只有一个成员在字母表内</b>。
 * 上一版把 G 与 6、U 与 V 同时留着，这条性质是假的 —— 用户打 G 时既可能想打 G
 * 也可能想打 6，两条路都通，而错的那条会落到"码不存在"，
 * 客服端看到的现象是"学员说码是对的，系统说查无此码"，最难查的一类工单。
 *
 * <p>24 个字符 × 16 位 ≈ 73 bit，穷举不可行；真正防刷的是接口限流，不是码长。
 */
public final class ActivationCodes {

    /** 24 字符。改这里必须同时想清楚上面那条性质还成不成立（有测试守着）。 */
    public static final String ALPHABET = "ACDEFHJKLMNPQRTUWXY34679";

    /** 易混组。测试用它核对"每组至多一个成员在字母表内"。 */
    public static final String[] CONFUSION_GROUPS = {"0O", "1I", "2Z", "5S", "8B", "6G", "UV"};

    public static final int LENGTH = 16;          // 4 组 × 4 位
    private static final int GROUP = 4;

    private ActivationCodes() {}

    public static String generate(SecureRandom rnd) {
        StringBuilder sb = new StringBuilder(LENGTH + 3);
        for (int i = 0; i < LENGTH; i++) {
            if (i > 0 && i % GROUP == 0) sb.append('-');
            sb.append(ALPHABET.charAt(rnd.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    /** 掩码展示：YG7X-****-****-3K9P。给代理看列表用，不泄露完整码。 */
    public static String mask(String code) {
        String c = strip(code);
        return c.substring(0, GROUP) + "-****-****-" + c.substring(c.length() - GROUP);
    }

    /**
     * 学员输入 → 规范形式。大小写、空格、全角横杠、少打横杠都能救回来；
     * 真正打错的字符则明确报出位置，<b>不做模糊匹配</b>。
     *
     * @throws IllegalArgumentException 输入不可能是一个合法码（连查库都不必）
     */
    public static String normalize(String input) {
        if (input == null || input.isBlank())
            throw new IllegalArgumentException("请输入激活码");

        StringBuilder raw = new StringBuilder();
        for (char ch : input.toUpperCase(java.util.Locale.ROOT).toCharArray()) {
            if (ch >= 'A' && ch <= 'Z' || ch >= '0' && ch <= '9') {
                // 只在"组内仅有一个成员在字母表里"时才敢纠正，见类注释
                if (ch == 'G') ch = '6';
                if (ch == 'V') ch = 'U';
                raw.append(ch);
            }
            // 其余（横杠、空格、全角符号、不可见字符）一律丢掉，不报错：
            // 从微信里复制过来的码常常带零宽字符，为这个拒绝用户毫无意义
        }

        if (raw.length() != LENGTH)
            throw new IllegalArgumentException(
                    "激活码应为 " + LENGTH + " 位，你输入了 " + raw.length() + " 位");

        for (int i = 0; i < LENGTH; i++) {
            char ch = raw.charAt(i);
            if (ALPHABET.indexOf(ch) < 0)
                throw new IllegalArgumentException(
                        "第 " + (i + 1) + " 位『" + ch + "』不是激活码里会出现的字符，请核对");
        }

        StringBuilder out = new StringBuilder(LENGTH + 3);
        for (int i = 0; i < LENGTH; i++) {
            if (i > 0 && i % GROUP == 0) out.append('-');
            out.append(raw.charAt(i));
        }
        return out.toString();
    }

    private static String strip(String code) {
        return code.replace("-", "");
    }
}
