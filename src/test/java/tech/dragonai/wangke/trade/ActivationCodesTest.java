package tech.dragonai.wangke.trade;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

class ActivationCodesTest {

    @Test
    @DisplayName("每个易混组里至多一个成员在字母表内")
    void confusionGroupsAreClosed() {
        for (String g : ActivationCodes.CONFUSION_GROUPS) {
            int in = 0;
            StringBuilder members = new StringBuilder();
            for (char ch : g.toCharArray())
                if (ActivationCodes.ALPHABET.indexOf(ch) >= 0) { in++; members.append(ch); }
            assertTrue(in <= 1,
                    "易混组『" + g + "』里有 " + in + " 个成员（" + members + "）同时在字母表内。"
                  + "用户打错其中一个时两条路都通得过，错的那条会落到「码不存在」，"
                  + "客服看到的就是「学员说码没错、系统说查无此码」");
        }
    }

    @Test
    @DisplayName("字母表本身不含易混对，且生成的码全部可被规整回自身")
    void generatedCodesRoundTrip() {
        SecureRandom rnd = new SecureRandom(new byte[]{1, 2, 3});
        var seen = new HashSet<String>();
        for (int i = 0; i < 2000; i++) {
            String code = ActivationCodes.generate(rnd);
            assertEquals(19, code.length());
            assertEquals(code, ActivationCodes.normalize(code),
                    "生成出来的码自己都过不了输入校验：生成端与输入端的字母表已经不是同一份了");
            seen.add(code);
        }
        assertEquals(2000, seen.size(), "2000 次生成出现重复，随机源有问题");
    }

    @Test
    @DisplayName("规整能救回大小写、空格、缺横杠、全角横杠与零宽字符")
    void normalizeIsForgiving() {
        String canonical = "ACDE-FHJK-LMNP-QRTU";
        assertEquals(canonical, ActivationCodes.normalize("acde-fhjk-lmnp-qrtu"));
        assertEquals(canonical, ActivationCodes.normalize("ACDEFHJKLMNPQRTU"));
        assertEquals(canonical, ActivationCodes.normalize(" ACDE FHJK LMNP QRTU "));
        assertEquals(canonical, ActivationCodes.normalize("ACDE—FHJK—LMNP—QRTU"));
        assertEquals(canonical, ActivationCodes.normalize("ACDE​FHJK-LMNP-QRTU"),
                "从微信复制过来常带零宽字符，为这个拒绝用户毫无意义");
    }

    @Test
    @DisplayName("只在组内仅剩一个成员时才纠正：G→6、V→U")
    void normalizeCorrectsOnlyWhereUnambiguous() {
        assertEquals("6666-UUUU-ACDE-FHJK", ActivationCodes.normalize("GGGG-VVVV-ACDE-FHJK"));
        // O 与 0 都不在表里，无从猜起 —— 必须报位置，不许瞎猜
        var e = assertThrows(IllegalArgumentException.class,
                () -> ActivationCodes.normalize("ACDO-FHJK-LMNP-QRTU"));
        assertTrue(e.getMessage().contains("第 4 位"), "没报出错在第几位：" + e.getMessage());
    }

    @Test
    @DisplayName("位数不对时直接拒，不去查库")
    void lengthIsCheckedBeforeLookup() {
        var e = assertThrows(IllegalArgumentException.class,
                () -> ActivationCodes.normalize("ACDE-FHJK"));
        assertTrue(e.getMessage().contains("8 位"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> ActivationCodes.normalize("   "));
    }

    @Test
    @DisplayName("掩码遮住中间两组")
    void maskHidesMiddle() {
        assertEquals("ACDE-****-****-QRTU", ActivationCodes.mask("ACDE-FHJK-LMNP-QRTU"));
    }
}
