package tech.dragonai.wangke.admin;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tech.dragonai.wangke.auth.AuthInterceptor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 平台总览页（{@code web/apps/admin/src/pages/Overview.tsx}）的唯一取数口。
 *
 * <h3>它替掉的是什么，以及为什么非替不可</h3>
 * 在它上线之前，总览页用 7 个列表接口在前端 {@code compose()} 现拼一份。那份拼盘有两个病：
 * <ul>
 *   <li><b>口径打架。</b>成交额与订单数是按 {@code biz_order.created_at}（下单时间）算的，
 *       而运营心里的「今天收了多少钱」是按支付时间算的。对公转账那条路上两者能差好几天，
 *       跨月那几天差得最明显 —— 而两套口径算出来的数<b>都不会报错</b>，只是不一样。
 *   <li><b>四项恒为 null。</b>「新增学员」「激活数」在拼盘里根本没有数据源，页面上写「暂无接口」。
 *       可库里 {@code sys_user.created_at} 与 {@code activation_code.bound_at} 本来就能算。
 * </ul>
 * 本接口按 {@code paid_at} 归属成交，并把那四项算出来。
 *
 * <h3>唯一的一条铁律：0 与「没有这个数」必须可区分</h3>
 * 查询跑成了、结果是空集 → <b>0</b>（每条 SUM 外面都套 COALESCE）。
 * 查询本身没跑成 → 该字段 <b>null</b>，并在同一个返回体的 {@code unavailable} 里写清是哪一项、为什么。
 * <p>写反了任何一边都是灾难：catch 完塞 0，运营会拿一个编出来的「今天一单没有」去催代理；
 * 空集返回 null，运营会以为系统坏了而不敢相信「今天确实没单」。
 * 这两个数字在运营那里通向<b>完全不同的决定</b>，所以它们在代码里必须是两条不同的路径。
 *
 * <h3>为什么一项失败不能整页 500</h3>
 * 每组查询各自 try/catch，捕到就把该组字段置 null 并<b>继续算其余组</b>。
 * 抛 500 的话，一张表出问题会让运营连还能看的那 11 个数一起失去 —— 而那 11 个数是好的。
 * 反过来，也绝不能把内部故障伪装成 200 空壳：前端 {@code load()} 只在 404/405/501 时降级，
 * 500 会照常报错、401 会照常去踢登录，这是有意的。故障要能被看见。
 *
 * <h3>⚠ 解释为什么不能挂在 null 字段自己身上</h3>
 * {@code application.yml} 配了 {@code spring.jackson.default-property-inclusion: non_null}，
 * 值为 null 的字段<b>在 JSON 里根本不出现</b>。于是「字段缺席」与「字段为 null」在线上不可区分，
 * 任何挂在那个字段上的说明都发不出去。所以说明一律由恒非空的 {@code unavailable} 数组承载，
 * {@code window / caveats / unavailable} 三者哪怕是空的也显式给出 —— 空数组不会被 non_null 丢掉。
 *
 * <h3>为什么不收 from/to/date/zone</h3>
 * 「今日」「本月」的定义只能有一处。开成参数就会出现前端传一套、后端默认另一套，
 * 而两套口径算出来的数都不会报错。窗口由服务端时钟算，并在 {@code window} 里原样回显，
 * 任何人都能核对这一屏数字是按哪个区间出的。多余的未知参数按 Spring 默认忽略、不报 400 ——
 * 后台链接会被人手工加参数，为此报 400 只是制造噪声。
 *
 * <h3>半开区间 [from, end)</h3>
 * 写成 {@code BETWEEN ... '23:59:59'} 会丢掉最后一秒内的单；写成 {@code 23:59:59.999} 又要赌
 * DATETIME(3) 的毫秒进位。半开区间两个坑都没有，而且「今日 ⊆ 本月」是<b>构造保证</b>不是巧合。
 * 窗口一律在 Java 侧算好当参数传下去，<b>不用 MySQL 的 NOW()/CURDATE()</b>：那取决于 DB 会话时区，
 * 与应用写 {@code paid_at} 时用的 JVM 时区是两套，对不上时不报错，只会让日界线偏几个小时。
 *
 * <h3>不开事务，不写审计</h3>
 * 全只读。包进一个事务后，一条语句报错会让事务状态不干净，「一项挂了其余照出」这条更值钱的性质
 * 就没法做了。代价是跨组毫秒级错位，收益远大于代价；组内一致性由「每组一条语句」保证
 * （今日与本月是同一行里的两个 CASE，永远不会出现今日 &gt; 本月这种看起来像 bug 的东西）。
 * <p>构造只注入 {@link JdbcTemplate}，<b>刻意不注入 {@link AdminAudit}</b>：这个接口不写审计
 * （只读、无个人信息、无争议动作，总览页会被挂在屏幕上反复刷新，每次写一条只会把 audit_log 冲成噪声，
 * 真正要查的六个高争议动作反而被淹掉）。注进来只会让下一个人以为该写。
 */
@RestController
@RequestMapping("/api/admin/overview")
public class AdminOverviewController {

    private static final Logger log = LoggerFactory.getLogger(AdminOverviewController.class);

    /**
     * 业务时区（schema 顶部的声明）。固定写死并在 {@code window.zone} 里回显，
     * 是为了让「部署机 TZ 不是它」这件事<b>看得见</b> —— 否则表现只是「今天的数字莫名少了一截」。
     */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /**
     * 取数失败时给运营看的话。<b>绝不含 SQL 片段、表名、堆栈</b>（内部细节不出网，
     * 这是 GlobalExceptionHandler 的第一条纪律）。技术细节全部 log.error 进日志。
     */
    private static final String REASON_QUERY_FAILED =
            "这一项的取数没跑成，已经记进服务端日志。上面显示的不是 0，是没取到 —— "
          + "请点进列表页核对后再做决定。";

    /**
     * {@code field → label}。label 逐字抄 Overview.tsx 上那张卡片的中文标题：
     * 运营看到的提示必须能和屏幕上的卡片对上，「todayPaidFen 取不到」对他没有任何意义。
     * field 用前端能对上的路径字面量，待办五项带 {@code todo.} 前缀。
     */
    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("todayPaidFen",         "今日成交额"),
            Map.entry("monthPaidFen",         "本月成交额"),
            Map.entry("todayOrders",          "今日订单数"),
            Map.entry("monthOrders",          "本月订单数"),
            Map.entry("todayNewStudents",     "今日新增学员"),
            Map.entry("monthNewStudents",     "本月新增学员"),
            Map.entry("todayActivations",     "今日激活数"),
            Map.entry("monthActivations",     "本月激活数"),
            Map.entry("todo.pendingAgents",   "待审代理"),
            Map.entry("todo.pendingWithdraws", "待审提现"),
            Map.entry("todo.pendingRefunds",  "待审退款"),
            Map.entry("todo.openTickets",     "待处理工单"),
            Map.entry("todo.pendingInvoices", "待开发票"));

    /**
     * 成交四项。一条语句出 4 个字段 —— 今日与本月是同一行里的两个 CASE，天然同快照。
     *
     * <p>三处「看起来该加、但加了就是错」的条件：
     * <ul>
     *   <li><b>不加 status 过滤。</b>{@code paid_at} 非空即代表这笔钱真的到过账；
     *       写成 {@code status IN ('PAID','FULFILLED')} 会把已退款那部分成交额静默抹掉 ——
     *       数字变小、没有任何报错，而运营会以为那天生意差。
     *   <li><b>不减 refunded_amount_fen。</b>卡片文案写死的是「实际收到的钱（已支付部分）」；
     *       退款发生在别的日子（{@code refund_record.finished_at}），揉进同一个日窗口
     *       就是两个口径拼成一个数，算出来谁也解释不了。
     *   <li><b>不加 paid_amount_fen &gt; 0。</b>{@code trg_order_state} 保证「标记已支付必须带正实付」，
     *       而 CREATED→CLOSED 的单没有 paid_at，本来就进不了这个窗口。
     * </ul>
     */
    private static final String SQL_PAID =
            "SELECT"
          + "  COALESCE(SUM(CASE WHEN o.paid_at >= ? THEN o.paid_amount_fen ELSE 0 END), 0) AS today_paid_fen,"
          + "  COALESCE(SUM(o.paid_amount_fen), 0)                                          AS month_paid_fen,"
          + "  COALESCE(SUM(CASE WHEN o.paid_at >= ? THEN 1 ELSE 0 END), 0)                 AS today_orders,"
          + "  COUNT(*)                                                                     AS month_orders"
          + " FROM biz_order o"
          + " WHERE o.paid_at >= ? AND o.paid_at < ?";

    /**
     * 口径自检探针：本月有没有「记了实付金额、却没有支付时间」的订单。
     *
     * <p>库层拦得住 UPDATE 路径（{@code trg_order_state} 要求标记已支付必须写 paid_at），
     * 但拦不住直接 INSERT 一行 {@code status='PAID'} 且 paid_at 为空 —— 那笔钱从此不出现在
     * 任何日/月统计里，<b>而且不会有任何信号</b>。这条探针就是那个信号。
     * 用 created_at 限定在本月，是为了不让它扫全史。
     */
    private static final String SQL_PAID_WITHOUT_TIME =
            "SELECT COUNT(*) AS n FROM biz_order"
          + " WHERE paid_amount_fen > 0 AND paid_at IS NULL AND created_at >= ? AND created_at < ?";

    /**
     * 本窗口内被激活的码里，有几个来自平台手工发放。
     *
     * <p>白送单 {@code paid_at IS NULL}，所以它天然被成交那几项的 {@code WHERE o.paid_at >= ?}
     * 挡在窗口外 —— 成交额一分钱都不会因为白送而虚高，这是对的。但激活数是按
     * {@code activation_code.bound_at} 圈的，白送的码<b>会</b>进去。
     * 于是「激活涨了、成交额没涨」，而运营看不出这是为什么。
     */
    private static final String SQL_GIFT_ACTIVATIONS =
            "SELECT COUNT(*) AS n FROM activation_code c JOIN biz_order o ON o.id = c.order_id"
          + " WHERE o.origin='ADMIN_GIFT' AND c.bound_at >= ? AND c.bound_at < ?";

    /**
     * 新增学员两项。走 {@code idx_role_status(role,status)} 的 role 前缀。
     *
     * <p><b>不按 status 过滤</b>：冻结/停用不改变「今天有个人注册了」这件事实，
     * 按 ACTIVE 过滤会让历史数字随着封号自己缩水，而且没有任何提示。
     * <p>已知口径缺陷：role 取的是<b>当前值</b>，自助申请代理的人（审批前 role 就已变成 AGENT）
     * 会从历史数字里消失。治本要记「注册时角色」，那是 schema 改动，不在本轮范围。
     */
    private static final String SQL_NEW_STUDENTS =
            "SELECT COALESCE(SUM(CASE WHEN u.created_at >= ? THEN 1 ELSE 0 END), 0) AS today_new,"
          + "       COUNT(*)                                                        AS month_new"
          + " FROM sys_user u"
          + " WHERE u.role = 'STUDENT' AND u.created_at >= ? AND u.created_at < ?";

    /**
     * 激活数两项。判据是 <b>{@code bound_at} 不是 {@code status='BOUND'}</b>。
     *
     * <p>BOUND→EXPIRED 是合法迁移（学习期满，{@code trg_activation_code_state} 明确放行），
     * 拿 status 过滤的话「今天激活了 30 个」会在若干月后自己变成 12 个，而且没有任何提示。
     * bound_at 非空 ⟺ 激活过：ISSUED→REVOKED 不写 bound_at，BOUND 之后不许改绑解绑（同一条触发器）。
     */
    private static final String SQL_ACTIVATIONS =
            "SELECT COALESCE(SUM(CASE WHEN c.bound_at >= ? THEN 1 ELSE 0 END), 0) AS today_act,"
          + "       COUNT(*)                                                      AS month_act"
          + " FROM activation_code c"
          + " WHERE c.bound_at >= ? AND c.bound_at < ?";

    // ---- 待办五项。FROM/WHERE 与卡片点过去的那个列表接口【逐字同源】。
    //      点进去对不上的数字比没有数字更坏：运营会以为列表漏了记录，然后去别处找补。

    /** → {@code AdminAgentController.list?status=PENDING}。JOIN 不能省：列表用的就是 INNER JOIN，
     *  档案挂着一个不存在的 user_id 时裸 COUNT 会多算一条，总览 4、点进去 3。 */
    private static final String SQL_PENDING_AGENTS =
            "SELECT COUNT(*) AS n FROM agent_profile a JOIN sys_user u ON u.id = a.user_id"
          + " WHERE a.status = 'PENDING'";

    /** → {@code AdminWithdrawController.list?status=APPLIED}。只数 APPLIED，不含 REVIEWING ——
     *  链接就是 {@code ?status=APPLIED}，把 REVIEWING 也算进来就和列表对不上了。 */
    private static final String SQL_PENDING_WITHDRAWS =
            "SELECT COUNT(*) AS n FROM withdraw_order WHERE status = 'APPLIED'";

    /** → {@code AdminOrderController.refundList?status=APPLIED}。同样是 INNER JOIN，同样不能省。 */
    private static final String SQL_PENDING_REFUNDS =
            "SELECT COUNT(*) AS n FROM refund_record r JOIN biz_order o ON o.id = r.order_id"
          + " WHERE r.status = 'APPLIED'";

    /** → {@code QaService.queue}（管理员视角）。走 {@code idx_queue(status, sla_due_at)}；
     *  管理员视角没有 assignee_id 条件，与 queue 里 "PLATFORM_ADMIN 不加 where" 那个分支一致。 */
    private static final String SQL_OPEN_TICKETS =
            "SELECT COUNT(*) AS n FROM qa_ticket WHERE status = 'ESCALATED'";

    /** {@code QaService.queue} 里写死的返回条数硬顶。抄常量不推常量：推错了这句提示就成了假话。 */
    private static final int QUEUE_HARD_CAP = 100;

    /**
     * 队列页默认请求的条数（Qa.tsx:23 与 Invoices.tsx:22 都是 50）。
     *
     * <p>抄的是页面实际发出的 limit，不是服务端硬顶 —— 差异发生在两者较小的那个上。
     * 常量只抄不推，但要抄对是哪一个。
     */
    private static final int PAGE_DEFAULT_LIMIT = 50;

    /** → {@code InvoiceService.queue(status='APPLIED')}。 */
    private static final String SQL_PENDING_INVOICES =
            "SELECT COUNT(*) AS n FROM invoice_request WHERE status = 'APPLIED'";

    private final JdbcTemplate jdbc;

    public AdminOverviewController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /**
     * 总览取数。
     *
     * <p>方法体第一行是 {@code requireRole}，理由有两条，都不是形式主义：
     * <ul>
     *   <li>{@link AdminGuard} 是<b>路径闸</b>，只认 {@code /api/admin/**}。集成测试里是直接
     *       {@code new AdminOverviewController(jdbc)} 调方法，它不在链上 —— 少了这行，
     *       403 的负例根本无从写起，而「后台裸奔」这类缺口只有被人用过之后才会被发现。
     *   <li>它还挡住「这个方法哪天被顺手挪到别的路径下」这种情况。
     * </ul>
     * <p>MENTOR 不放行：他能看答疑队列，但成交额、提现、退款是财务面，和答疑不是一个可见面。
     * <p>越权时<b>一个数字都不许算</b>，所以 requireRole 在第一行，不在算完之后。
     */
    @GetMapping
    Map<String, Object> overview(HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");

        LocalDate today = LocalDate.now(ZONE);
        LocalDateTime todayFrom = today.atStartOfDay();
        LocalDateTime windowEnd = today.plusDays(1).atStartOfDay();   // 今日与本月共用的上界
        LocalDateTime monthFrom = today.withDayOfMonth(1).atStartOfDay();

        Map<String, Object> out = AdminJson.ok();
        Map<String, Object> todo = new LinkedHashMap<>();
        List<Map<String, Object>> unavailable = new ArrayList<>();
        List<String> caveats = new ArrayList<>();

        // ---------------------------------------------------------- A 成交四项
        boolean paidOk = true;
        try {
            Map<String, Object> r = jdbc.queryForMap(
                    SQL_PAID, todayFrom, todayFrom, monthFrom, windowEnd);
            // SUM(BIGINT) 回来是 BigDecimal、COUNT(*) 回来可能是 BigInteger，两个都不是 Long。
            // 一律走 AdminQuery.longOf，裸 (Long) 强转会在运行期 ClassCastException。
            out.put("todayPaidFen", AdminQuery.longOf(r.get("today_paid_fen"), 0));
            out.put("monthPaidFen", AdminQuery.longOf(r.get("month_paid_fen"), 0));
            out.put("todayOrders",  AdminQuery.longOf(r.get("today_orders"), 0));
            out.put("monthOrders",  AdminQuery.longOf(r.get("month_orders"), 0));
        } catch (DataAccessException e) {
            paidOk = false;
            log.error("总览：成交四项取数失败，窗口 [{}, {})，SQL={}", monthFrom, windowEnd, SQL_PAID, e);
            markUnavailable(out, todo, unavailable,
                    "todayPaidFen", "monthPaidFen", "todayOrders", "monthOrders");
        }

        // ---------------------------------------------------------- B 新增学员
        try {
            Map<String, Object> r = jdbc.queryForMap(
                    SQL_NEW_STUDENTS, todayFrom, monthFrom, windowEnd);
            out.put("todayNewStudents", AdminQuery.longOf(r.get("today_new"), 0));
            out.put("monthNewStudents", AdminQuery.longOf(r.get("month_new"), 0));
        } catch (DataAccessException e) {
            log.error("总览：新增学员取数失败，窗口 [{}, {})，SQL={}", monthFrom, windowEnd,
                    SQL_NEW_STUDENTS, e);
            markUnavailable(out, todo, unavailable, "todayNewStudents", "monthNewStudents");
        }

        // ---------------------------------------------------------- C 激活数
        try {
            Map<String, Object> r = jdbc.queryForMap(
                    SQL_ACTIVATIONS, todayFrom, monthFrom, windowEnd);
            out.put("todayActivations", AdminQuery.longOf(r.get("today_act"), 0));
            out.put("monthActivations", AdminQuery.longOf(r.get("month_act"), 0));
        } catch (DataAccessException e) {
            log.error("总览：激活数取数失败，窗口 [{}, {})，SQL={}", monthFrom, windowEnd,
                    SQL_ACTIVATIONS, e);
            markUnavailable(out, todo, unavailable, "todayActivations", "monthActivations");
        }

        // ---------------------------------------------------------- D-H 待办五项
        // todo 先挂进 out 再填，是为了让它在 JSON 里稳定地排在 8 个指标之后 —— 后面的
        // put 改的是同一个 Map 引用，插入顺序不受影响。
        out.put("todo", todo);
        count(todo, unavailable, "todo.pendingAgents",    SQL_PENDING_AGENTS);
        count(todo, unavailable, "todo.pendingWithdraws", SQL_PENDING_WITHDRAWS);
        count(todo, unavailable, "todo.pendingRefunds",   SQL_PENDING_REFUNDS);
        count(todo, unavailable, "todo.openTickets",      SQL_OPEN_TICKETS);
        count(todo, unavailable, "todo.pendingInvoices",  SQL_PENDING_INVOICES);

        // ---------------------------------------------------------- A' 口径自证
        // 只在成交额真的算出来了的时候才探：成交额本身是 null 时，
        // 再说一句「它偏低」只是噪声 —— 那个数根本没显示出来。
        if (paidOk) probePaidWithoutTime(monthFrom, windowEnd, caveats);
        probeGiftActivations(monthFrom, windowEnd, caveats);
        notePaidVsCreatedMismatch(paidOk, caveats);
        noteQueueCaps(todo, caveats);
        noteClockSkew(caveats);

        out.put("unavailable", unavailable);
        out.put("caveats", caveats);

        Map<String, Object> window = new LinkedHashMap<>();
        window.put("zone", ZONE.getId());
        window.put("todayFrom", todayFrom.toString());
        window.put("todayTo", windowEnd.toString());       // 半开区间上界，不含
        window.put("monthFrom", monthFrom.toString());
        window.put("monthTo", windowEnd.toString());       // 本月 = 月初到今天结束，与 todayTo 同值
        out.put("window", window);
        // 时刻一律出字符串，不出 LocalDateTime：Jackson 对时间类型的渲染形状取决于全局配置，
        // 而这个字段是给人核对口径用的，不能因为换一个配置就变成一个数组。
        out.put("generatedAt", LocalDateTime.now(ZONE).truncatedTo(ChronoUnit.MILLIS).toString());
        return out;
    }

    // ================================================================ 内部

    /**
     * 已知的口径偏差<b>不落 null，落 caveats</b>：数字仍然给。
     *
     * <p>把成交额整个变成 null 会连「今天确实收了 12 万」这条真信息一起丢掉。
     * 给数 + 说清偏在哪，比给 null 有用得多。
     *
     * <p>探针自己挂了也要说话。静默跳过等于「这次查不出有没有偏差」被伪装成「没有偏差」，
     * 而那正是这条探针存在的理由。
     */
    private void probePaidWithoutTime(LocalDateTime monthFrom, LocalDateTime windowEnd,
                                      List<String> caveats) {
        try {
            Map<String, Object> r = jdbc.queryForMap(SQL_PAID_WITHOUT_TIME, monthFrom, windowEnd);
            long n = AdminQuery.longOf(r.get("n"), 0);
            if (n > 0)
                caveats.add("本月有 " + n + " 笔订单记了实付金额却没有支付时间，"
                          + "这些钱没法按日/按月归属，上面的成交额是偏低的。");
        } catch (DataAccessException e) {
            log.error("总览：成交口径自检（实付非零但无支付时间）没跑成，窗口 [{}, {})",
                    monthFrom, windowEnd, e);
            caveats.add("成交额的口径自检这次没跑成（已记进服务端日志）。"
                      + "如果存在记了实付金额却没有支付时间的订单，上面的成交额会偏低 —— "
                      + "这次查不出有没有这种订单。");
        }
    }

    /**
     * 平台手工发放的码进了激活数，但不进成交额。
     *
     * <p>这两个数从此不是同一批订单算出来的，而界面上它们挨着摆 —— 不说的话，
     * 运营看到的是「这个月激活涨了不少，成交额怎么没动」，然后去查一个根本不存在的问题。
     *
     * <p>探针自己挂了也要说话，理由同 {@link #probePaidWithoutTime}：静默跳过等于
     * 把「这次查不出有没有偏差」伪装成「没有偏差」。
     */
    private void probeGiftActivations(LocalDateTime monthFrom, LocalDateTime windowEnd,
                                      List<String> caveats) {
        try {
            Map<String, Object> r = jdbc.queryForMap(SQL_GIFT_ACTIVATIONS, monthFrom, windowEnd);
            long n = AdminQuery.longOf(r.get("n"), 0);
            if (n > 0)
                caveats.add("本月激活的码里有 " + n + " 个来自平台手工发放，它们不产生成交额 ——"
                          + "「激活涨了而成交额没涨」有一部分是这个原因。");
        } catch (DataAccessException e) {
            log.error("总览：手工发放码的激活占比自检没跑成，窗口 [{}, {})", monthFrom, windowEnd, e);
            caveats.add("手工发放码的口径自检这次没跑成（已记进服务端日志）。"
                      + "如果本月有手工发放的码被激活，激活数会比成交额显得高 —— "
                      + "这次查不出有没有。");
        }
    }

    /**
     * 「待处理工单」这个数点进去会对不上，得先说清楚。
     *
     * <p>这里给的是全量 COUNT，而卡片点过去的 {@code QaService.queue} 一次最多返回 100 条
     * （{@code Math.min(limit, 100)} 是写死的硬顶）。于是超过 100 时必然出现「总览 137、
     * 点进去只有 100 条」—— 而运营对这种差异的第一反应是「列表漏了记录」，然后去别处找补。
     *
     * <p>治本是让 queue 支持分页，那要动 {@code QaService}，不在本轮范围。
     * 退而求其次不是把这个数也砍到 100（那就成了一个假的「137 其实是 100+」），
     * 而是把差异说出来：<b>给真数 + 说清它为什么和列表不一样</b>。
     * 前端 {@code compose()} 那份拼盘为此写过一句 hint（「至少 100 条」），server 模式下
     * 那个补丁没了，所以这句话得从这一侧发出去。
     */
    private static void noteQueueCaps(Map<String, Object> todo, List<String> caveats) {
        noteQueueCap(todo, caveats, "openTickets", "待处理工单", "答疑队列");
        // 待开发票走的是同一个形状：InvoiceService.queue 同样 Math.min(limit,100)，
        // Invoices.tsx 同样默认 limit=50。原来只给工单加了这句，发票一个字都没有。
        noteQueueCap(todo, caveats, "pendingInvoices", "待开发票", "发票队列");
    }

    private static void noteQueueCap(Map<String, Object> todo, List<String> caveats,
                                     String key, String label, String listName) {
        Object v = todo.get(key);
        if (!(v instanceof Number)) return;                 // null（取数失败）时没什么可说的
        long n = ((Number) v).longValue();
        // ★ 比的是【页面实际会请求的条数】，不是服务端硬顶。
        //   原来拿 100 去比，而 Qa.tsx / Invoices.tsx 默认都只请求 50 ——
        //   于是 51~100 这一段（也就是这种差异最常发生的区间）一句话都不会说，
        //   而「总览 60、点进去 50」正是这个方法存在的全部理由。
        long shown = Math.min(PAGE_DEFAULT_LIMIT, QUEUE_HARD_CAP);
        if (n <= shown) return;
        // 两个数都写出来：默认这一屏列多少（运营真正看到的），以及把每页调到最大也只能列多少。
        // 只说硬顶 100 的话，51~100 这一段（差异最常发生的区间）一个字都不会说；
        // 只说 50 的话，运营会以为"翻到第二页就全了"，而超过 100 时翻页也拿不到。
        caveats.add(label + "有 " + n + " 条，而点进去的" + listName + "这一屏默认只列 "
                  + shown + " 条（每页调到最大也只有 " + QUEUE_HARD_CAP
                  + " 条）—— 列表里看到的会比这个数少，那不是漏了记录。");
    }

    /**
     * 成交四项与它们链接过去的订单列表<b>不同口径</b>，必须说出来。
     *
     * <p>这四个数按 {@code paid_at}（支付时间）归属，而卡片点过去的
     * {@code /orders?from=&to=} 用 {@code o.created_at} 过滤（AdminOrderController）。
     * 对公转账那条路上「今天下单、三天后到账」是常态，跨月那几天差得最明显：
     * 同一个标签、同一个金额，背后是两笔毫无关系的订单，两边都不报错。
     *
     * <p>类注释给待办五项立过一条规矩 —— 「FROM/WHERE 与卡片点过去的那个列表接口逐字同源，
     * 点进去对不上的数字比没有数字更坏」。成交四项是唯一违反它的一组。治本是给
     * {@code AdminOrderController.list} 加 paidFrom/paidTo 参数（不在本轮范围），
     * 在那之前至少让它自己说出来。
     */
    private static void notePaidVsCreatedMismatch(boolean paidOk, List<String> caveats) {
        if (!paidOk) return;    // 数都没算出来，再说口径差异只是噪声
        caveats.add("成交四项按【支付时间】归属；点开卡片后的订单列表按【下单时间】筛选。"
                  + "跨日到账的单（对公转账常见）两边会对不上，那不是漏单。");
    }

    /**
     * 窗口时区自证。
     *
     * <p>{@code window.zone} 回的是一个<b>常量</b>。类注释说硬编码是「为了让『部署机 TZ
     * 不是它』这件事看得见」，但回显一个常量什么也看不见 —— 它既不保证也不检测部署机时区。
     *
     * <p>而这三列根本不是同一个时钟写的：
     * <ul>
     *   <li>{@code biz_order.paid_at} 来自支付宝 gmt_payment —— <b>北京时间</b>
     *   <li>{@code activation_code.bound_at} 由 Java 写（{@code LocalDateTime.now()}）—— <b>JVM 默认时区</b>
     *   <li>{@code sys_user.created_at} 由库写（{@code DEFAULT CURRENT_TIMESTAMP(3)}）—— <b>DB 会话时区</b>
     * </ul>
     * 容器里不设 TZ 即 UTC 时，「今日新增学员」「今日激活数」实际统计的是北京时间
     * 昨天 08:00 到今天 08:00，数字静默少一截，没有任何报错。
     *
     * <p>治本要把读写两端钉到同一个时区（JDBC 加
     * {@code connectionTimeZone=Asia/Shanghai&forceConnectionTimeZoneToSession=true}
     * 且 JVM 起 {@code -Duser.timezone=Asia/Shanghai}），但那会让<b>已经写进库的历史行</b>
     * 整体被重新解读、平移 8 小时，是一个需要拍板的迁移动作，不是一次改配置。
     * 在那之前，这里实测一次并如实说出来 —— 把一个静默错误的数字，变成一个说得出口径偏差的数字。
     */
    private void noteClockSkew(List<String> caveats) {
        try {
            LocalDateTime dbNow = jdbc.queryForObject("SELECT NOW(3)", LocalDateTime.class);
            if (dbNow == null) return;
            LocalDateTime bizNow = LocalDateTime.now(ZONE);
            long offMin = Math.abs(ChronoUnit.MINUTES.between(dbNow, bizNow));
            ZoneId jvm = ZoneId.systemDefault();
            boolean jvmOff = !jvm.getRules().equals(ZONE.getRules());
            if (offMin < 2 && !jvmOff) return;              // 三者一致，没什么可说的

            var sb = new StringBuilder("日界线可能不准：本页按 ").append(ZONE.getId())
                    .append(" 切「今天/本月」，但");
            if (offMin >= 2)
                sb.append("数据库时钟与它差 ").append(offMin).append(" 分钟（库里 NOW() 是 ")
                  .append(dbNow).append("）");
            if (offMin >= 2 && jvmOff) sb.append("，且");
            if (jvmOff) sb.append("服务进程的时区是 ").append(jvm.getId());
            sb.append("。「新增学员」「激活数」按写入端的时钟落库，因此这两项的日/月边界会整体平移；"
                    + "成交额按支付网关给的北京时间，与它们不在同一个时钟上。");
            caveats.add(sb.toString());
        } catch (DataAccessException e) {
            log.error("总览：时区自证没跑成", e);
            caveats.add("这次没能核对服务端与数据库的时区，"
                      + "所以查不出「今天/本月」的日界线有没有偏（已记进服务端日志）。");
        }
    }

    /**
     * 一项待办的 COUNT。失败落 null 并登记 unavailable，<b>不落 0</b>，也不往上抛。
     *
     * <p>「0」是一个会被拿来做决定的数字（「今天没有待审提现，可以下班了」），
     * 而真相是「不知道」。这两件事在这里必须走两条路。
     */
    private void count(Map<String, Object> todo, List<Map<String, Object>> unavailable,
                       String field, String sql) {
        String key = field.substring("todo.".length());
        try {
            Map<String, Object> r = jdbc.queryForMap(sql);
            todo.put(key, AdminQuery.longOf(r.get("n"), 0));
        } catch (DataAccessException e) {
            log.error("总览：待办项 {} 取数失败，SQL={}", field, sql, e);
            todo.put(key, null);
            unavailable.add(entry(field));
        }
    }

    /** 把一组字段整体置 null 并逐个登记到 unavailable。字段名带 {@code todo.} 前缀的进 todo 子对象。 */
    private static void markUnavailable(Map<String, Object> out, Map<String, Object> todo,
                                        List<Map<String, Object>> unavailable, String... fields) {
        for (String f : fields) {
            if (f.startsWith("todo.")) todo.put(f.substring("todo.".length()), null);
            else out.put(f, null);
            unavailable.add(entry(f));
        }
    }

    private static Map<String, Object> entry(String field) {
        String label = LABELS.get(field);
        if (label == null)
            // 只可能是有人加了字段忘了配 label。宁可当场炸在开发期，
            // 也不要给运营发一条 label 为 null 的说明 —— 那句话他对不上任何一张卡片。
            throw new IllegalStateException("总览：字段 " + field + " 没有对应的卡片标题");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("field", field);
        m.put("label", label);
        m.put("reason", REASON_QUERY_FAILED);
        return m;
    }
}
