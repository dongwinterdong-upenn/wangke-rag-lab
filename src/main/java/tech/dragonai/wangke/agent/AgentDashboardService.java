package tech.dragonai.wangke.agent;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 代理工作台（{@code GET /api/agent/dashboard}）的聚合层。
 *
 * <h3>为什么这里一条统计 SQL 都不该新写</h3>
 * 工作台回答的三个问题（手里还有多少货能发 / 这个月动了多少 / 有没有事等着我处理），
 * 每一个在别处都已经有权威口径：库存与成交额在 {@link AgentService#stats}，
 * 三个金额在 {@link AgentService#balance}，人数在 {@link AgentService#students}。
 * 在这里"顺手再算一遍"看起来只是省了几次调用，实际后果是两处口径独立漂移 ——
 * 表现是工作台写 30 个库存、点进去列表只有 18 条，<b>而没有任何一处报错</b>：
 * 两个数字各自都"对"，只是来源不同。所以本类的规则是：
 * <b>能复用既有方法就复用，实在没有现成方法的才自己查，且只查那一条。</b>
 *
 * <p>只有三组数没有现成方法，在这里第一次落地：
 * <ol>
 *   <li><b>本月/上月激活数</b> —— 判据是 {@code bound_at} 而不是 {@code status='BOUND'}。
 *       用状态的话，BOUND→EXPIRED（学习权限到期）是一条合法迁移，
 *       于是"上个月激活了多少"这个已经发生过的事实，会在几个月后自己变小。
 *   <li><b>激活期限</b> —— 90 天内到期的库存数、已过期未扫的数、最近的一个截止日。
 *   <li><b>需要处理的提现单</b> —— 被风控驳回或打款失败的。
 * </ol>
 *
 * <h3>算不出来的数返回 null，并在同一个响应体里说明为什么</h3>
 * {@code spring.jackson.default-property-inclusion=non_null} 之下，值为 null 的字段
 * <b>整个键都不会出现</b>。前端看到的不是 null 而是"这个字段没了"，
 * 而"没了"和"后端还没实现"、"我这里就是 0"三件事在界面上长得一模一样。
 * 所以每一个可能为 null 的字段都在 {@code notes} 里配一句人话。
 *
 * <p>本类<b>全部只读</b>，不需要事务。代价是一次请求约 10 条查询（stats 调两次、
 * students 调一次也是两条）。真成了热点再合并，但不要为了省查询把口径抄第二份 ——
 * 省下的是几毫秒，赔进去的是"两个页面显示不同的数字"这种查不出来的 bug。
 */
@Service
public class AgentDashboardService {

    /**
     * 提现红点的回看窗口。RISK_REJECTED 是终态，库里也没有"已知悉"这一列，
     * 不设窗口的话这个红点点亮之后永远不灭 —— 而永不灭的红点等于没有红点，
     * 三天之后没有人会再看它一眼。30 天是个待运营拍板的值（见交付说明）。
     */
    static final int ATTENTION_DAYS = 30;

    /** 库存告警的提前量：激活期限进入 90 天倒计时的码，代理该开始催着发出去了。 */
    static final int EXPIRING_SOON_DAYS = 90;

    private final JdbcTemplate jdbc;
    private final AgentService agents;

    public AgentDashboardService(JdbcTemplate jdbc, AgentService agents) {
        this.jdbc = jdbc;
        this.agents = agents;
    }

    /**
     * 工作台的四张 KPI 卡 + 待办条。
     *
     * <p>身份只从 {@code userId} 来（控制器从令牌解出），不收 agentId：
     * 一旦这个接口开始收 agentId，它就变成了"任意代理经营看板查询接口"，
     * 而加参数的那次改动看起来只是"支持平台侧复用"。
     *
     * @param userId 当前登录用户；必须是 status=ACTIVE 的代理，否则 403
     */
    public Map<String, Object> dashboard(long userId) {
        AgentService.Agent a = agents.requireOperational(userId);

        // 月份边界在入口算一次，下面所有口径共用同一组值。
        // 每条 SQL 各写一次 NOW() 的话，跨月零点那一刻两条 SQL 会落在不同的月里，
        // 于是"本月激活 0 个、上月激活 0 个、但库存少了 3 个"，对不上而且查不出来。
        YearMonth thisMonth = YearMonth.now();
        String month = thisMonth.toString();
        String[] cur = AgentService.monthRange(month);                       // [本月初, 下月初)
        String[] prev = AgentService.monthRange(thisMonth.minusMonths(1).toString());

        // ---- 复用既有口径：库存与成交额 ----
        Map<String, Object> all = agents.stats(userId, null);
        Map<String, Object> mon = agents.stats(userId, month);

        // ---- 复用既有口径：三个金额 ----
        Map<String, Object> bal = agents.balance(userId);

        // ---- 复用既有口径：学员人数 ----
        // 不复制 students() 里那段 UNION（归属来的 + 用我的码激活的），而是直接问它要 total：
        // 抄一份 SQL 过来，两处的人数就会在任何一次口径调整后分叉，
        // 而"工作台 12 人、学员列表 9 条"是没有任何报错的那类 bug。
        // 代价是多一次 size=1 的分页查询，换来的是构造上的同源。
        long studentsTotal = agents.students(userId, 1, 1).total();

        var notes = new LinkedHashMap<String, String>();

        // ---- 激活数：判据是 bound_at，不是 status ----
        Map<String, Object> act = jdbc.queryForMap(
                "SELECT COALESCE(SUM(bound_at >= ? AND bound_at < ?),0) AS cur,"
              + " COALESCE(SUM(bound_at >= ? AND bound_at < ?),0) AS prev"
              + " FROM activation_code WHERE owner_agent_id=? AND bound_at IS NOT NULL",
                cur[0], cur[1], prev[0], prev[1], a.id());

        // ---- 期限：90 天内到期 / 已过期未扫 / 最近的截止日 ----
        // 用 RowMapper 而不是 queryForMap 取 nearest：DATETIME 要走
        // getObject(col, LocalDateTime.class)，getTimestamp 会按连接串时区换算一遍，
        // 于是页面上的截止日整体平移几小时且没有任何报错。
        Deadlines dl = jdbc.query(
                "SELECT COALESCE(SUM(activate_deadline > NOW(3)"
              + "        AND activate_deadline < NOW(3) + INTERVAL " + EXPIRING_SOON_DAYS + " DAY),0) AS soon,"
              + " COALESCE(SUM(activate_deadline <= NOW(3)),0) AS dead,"
              // nearest 必须与 soon 用同一个下界（> NOW(3)），否则两个字段口径不同
              // 却被前端拼进同一句话：待办条写的是「有 {soon} 个码快到激活期限，
              // 最早的一个 {nearest}」——代理手上有 3 个已过期未扫的码 + 5 个 60 天后到期的码时，
              // 那句话会变成「有 5 个码快到激活期限，最早的一个已过期」：
              // 主语是那 5 个，取的值却来自全部 8 个。
              // （全仓没有扫描过期码的定时任务，所以"已过期但还是 ISSUED"是常态不是边角。）
              + " MIN(CASE WHEN activate_deadline > NOW(3) THEN activate_deadline END) AS nearest"
              + " FROM activation_code WHERE owner_agent_id=? AND status='ISSUED'",
                (rs, i) -> new Deadlines(rs.getLong("soon"), rs.getLong("dead"),
                        rs.getObject("nearest", LocalDateTime.class)),
                a.id()).get(0);

        // ---- 待办：被驳回 / 打款失败的提现单 ----
        Long attention = AgentService.num(jdbc.queryForObject(
                "SELECT COUNT(*) FROM withdraw_order WHERE agent_id=?"
              + " AND status IN ('RISK_REJECTED','FAILED')"
              + " AND created_at >= NOW(3) - INTERVAL " + ATTENTION_DAYS + " DAY",
                Number.class, a.id()));

        long stock = (Long) all.get("unactivatedStock");
        long available = (Long) bal.get("availableFen");

        var out = new LinkedHashMap<String, Object>();
        out.put("month", month);
        out.put("stockUnactivated", stock);
        out.put("expiringSoonCodes", dl.soon());
        out.put("nearestDeadline", dl.nearest());
        out.put("expiredNotSweptCodes", dl.dead());
        out.put("monthActivatedCodes", AgentService.num(act.get("cur")));
        out.put("lastMonthActivatedCodes", AgentService.num(act.get("prev")));
        out.put("studentsTotal", studentsTotal);
        out.put("withdrawableFen", bal.get("withdrawableFen"));
        out.put("inflightWithdrawFen", bal.get("inflightWithdrawFen"));
        out.put("frozenCommissionFen", bal.get("frozenFen"));
        // 进货金额不再出网（2026-09-01：进货价一家一议，代理端两侧都不出价）。
        // 留着的话工作台那一格虽然删了，F12 里照样看得见 —— 而累计进货金额除以累计码数
        // 就是他的单价，等于把这一轮藏起来的东西从另一个字段里还回去。
        // 服务端仍照常统计，只是不讲给代理听；超管后台那一侧一个字段都没动。
        out.put("withdrawNeedsAttention", attention == null ? 0L : attention);

        // ---- 每一个 null、每一个"看起来不对"的数，都要自己说得出原因 ----

        if (dl.nearest() == null)
            notes.put("nearestDeadline", "现在一个未激活的码都没有，所以没有最近的激活截止日。"
                    + "（non_null 策略下这个字段的键不会出现在响应里，不是接口漏了）");

        if (available < 0)
            notes.put("withdrawableFen", "台账余额当前为负（退款冲正尚未被后续佣金抵扣完），"
                    + "这里按 0 显示：负数会让前端把它当成输入框上限，算出一个负的上限然后干脆放弃校验。"
                    + "此刻发起提现会被库里的 trg_withdraw_precheck 直接拒掉。");

        if (dl.dead() > 0)
            notes.put("stockUnactivated", "库存里混着 " + dl.dead() + " 个已经过了激活期限、"
                    + "但还没有被扫成 EXPIRED 的码（全仓目前没有这个定时任务）。"
                    + "这里仍按 status='ISSUED' 计数，是为了与 /stats、/codes 列表同一口径 —— "
                    + "在这里偷偷减掉，三个页面就会显示三个不同的库存数。差额见 expiredNotSweptCodes。");

        notes.put("monthActivatedCodes", "按激活时间 bound_at 归月，不按 status='BOUND' 统计："
                + "BOUND→EXPIRED（学习权限到期）是合法迁移，用状态算的话上个月的激活数过几个月会自己变小。");

        notes.put("withdrawNeedsAttention", "只数最近 " + ATTENTION_DAYS
                + " 天内被风控驳回或打款失败的提现单。风控驳回是终态、库里没有『已知悉』标记，"
                + "不设窗口这个红点就永远不灭，而永不灭的红点等于没有红点。");

        out.put("notes", notes);
        return out;
    }

    /** 期限那一条 SQL 的三个产物。写成 record 只是为了让 RowMapper 一次取完三列。 */
    private record Deadlines(long soon, long dead, LocalDateTime nearest) {}
}
