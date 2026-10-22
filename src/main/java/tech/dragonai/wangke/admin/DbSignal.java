package tech.dragonai.wangke.admin;

import tech.dragonai.wangke.web.ApiException;

import java.sql.SQLException;
import java.util.Map;

/**
 * 把数据库的拒绝翻成运营看得懂的话，并且<b>只翻那些确实是业务拒绝的</b>。
 *
 * <p>这个库把大量业务规则放在触发器里（激活即不退、分销层级硬锁 2 级、上架前水印闸门……），
 * 那是对的 —— 应用层的检查总有绕过去的路径。代价是运营在后台点一下按钮，收到的是一句
 * 从 JDBC 里冒上来的异常。{@code GlobalExceptionHandler} 已经会把 SIGNAL 原文透出成 409，
 * 这里再往前走一步：<b>补上「那接下来该做什么」</b>。触发器只说「不行，因为……」，
 * 运营还需要知道「那我该改哪个字段」，否则他能做的只有找研发。
 *
 * <h3>为什么未知异常必须原样抛回去</h3>
 * 最省事的写法是 catch 住所有 DataAccessException 都回一句「操作不合规」。那是错的：
 * 一个真 bug（写错列名、连接池耗尽、死锁）会被包装成一句业务话术，运营以为是自己填错了，
 * 于是不上报，于是这个 bug 永远没人知道。所以拿不到 SIGNAL 文本时，把原异常抛回去，
 * 让它走 {@code GlobalExceptionHandler} 的 500 分支并留完整堆栈。
 */
final class DbSignal {

    private DbSignal() {}

    /** MySQL 的 {@code SIGNAL SQLSTATE '45000'} 到 JDBC 这一侧是错误码 1644。 */
    private static final int SIGNAL = 1644;
    /** CHECK 约束被违反是 3819，报文形如 {@code Check constraint 'ck_xxx' is violated.}。 */
    private static final int CHECK_VIOLATED = 3819;

    /**
     * 后台能碰到的 CHECK 约束 → 人话。
     *
     * <p>只列<b>本模块真的可能触发</b>的那几条。不做通配是有意的：把任何 3819 都翻成
     * 「参数不合法」会让一条我没预料到的约束伪装成用户输入问题，而那正是需要被看见的东西。
     */
    private static final Map<String, String> CHECKS = Map.of(
        "ck_agent_parent",
            "一级代理不能有上级，二级代理必须有上级 —— 分销层级硬锁 2 级，这是合规红线不是配置项",
        "ck_agent_level",
            "代理层级只能是 1（一级）或 2（二级）",
        "ck_course_months",
            "可激活月数与可观看月数都必须大于 0",
        "ck_withdraw_amount",
            "提现金额低于门槛（100 元）",
        "ck_refund_amount",
            "退款金额必须大于 0",
        "ck_order_amount",
            "订单的钱对不上：实付不得超过应付，退款不得超过实付",
        "ck_order_gift_unpaid",
            "平台白送的订单不能记实付金额或到账时间 —— 那会把一次白送记成一笔真实收入，"
          + "而它进了营收、结了佣金之后撤不回来",
        "ck_order_grant_by",
            "手工开的订单必须同时记下操作人、发放原因和幂等键，一样都不能少"
    );

    /**
     * 把一个数据库异常翻成对外异常。
     *
     * @param hint 补在触发器原文后面的「接下来该做什么」。可为 null
     * @return 触发器/已知 CHECK 拒绝 → 409 ApiException；其余 → 原异常（走 500 并留堆栈）
     */
    static RuntimeException explain(RuntimeException e, String hint) {
        String signal = textOf(e);
        if (signal != null)
            return ApiException.conflict(hint == null || hint.isBlank() ? signal : signal + "。" + hint);

        String check = checkName(e);
        if (check != null) {
            String human = CHECKS.get(check);
            if (human != null)
                return ApiException.conflict(hint == null || hint.isBlank() ? human : human + "。" + hint);
        }
        return e;
    }

    /** 取触发器 SIGNAL 的原文。这些文案是我们自己写的、面向人的，可以原样出网。 */
    static String textOf(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause())
            if (t instanceof SQLException se && se.getErrorCode() == SIGNAL) {
                String m = se.getMessage();
                return m == null || m.isBlank() ? null : m.trim();
            }
        return null;
    }

    private static String checkName(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause())
            if (t instanceof SQLException se && se.getErrorCode() == CHECK_VIOLATED) {
                String m = se.getMessage();
                if (m == null) return null;
                int a = m.indexOf('\''), b = a < 0 ? -1 : m.indexOf('\'', a + 1);
                return (a < 0 || b < 0) ? null : m.substring(a + 1, b);
            }
        return null;
    }
}
