package tech.dragonai.wangke.agent;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tech.dragonai.wangke.web.ApiException;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static tech.dragonai.wangke.agent.AgentService.num;

/**
 * 代理提现申请。钱唯一一条<b>往外走</b>的路径，所以这一层的每条检查都要说清为什么在。
 *
 * <h3>收款人不由请求参数决定</h3>
 * 户名与收款账户一律取库里登记的（实名 / 主体名 + 结算账户），不取客户端传来的值。
 * 允许请求指定收款人的话，"把佣金提到别人卡上"只需要改一个 JSON 字段，
 * 而这种挪用在事后审计里<b>看起来完全合法</b>——单子确实是本人申请的、金额也在余额内，
 * 只有打款流水上的名字不一样，而那时钱已经出去了。
 *
 * <h3>余额检查在库里，不在这里</h3>
 * 真正裁决的是 trg_withdraw_precheck：它和 INSERT 在同一条语句里求值，
 * 绕不过去。这里的预检只为了出人话（"可提 800.00 元，本次申请 1000.00 元"），
 * 不作数 —— 触发器的文案是给数据库看的，用户看到"已扣除在途提现单"会一头雾水。
 *
 * <h3>并发靠余额行的排他锁，不靠触发器</h3>
 * 触发器里那两条 SELECT 是<b>一致性读</b>：两笔申请同时打进来，各自都看得见足额余额，
 * 都不会看见对方那条还没提交的在途单，于是<b>两笔都能过闸</b>。
 * 10 万余额连提两笔 6 万就是这么发生的。所以申请前先对 agent_balance 那一行
 * {@code SELECT ... FOR UPDATE}，把同一个代理的申请串行化 —— 后到的那笔在锁上等，
 * 拿到锁时已经能看见前一笔的在途金额。
 *
 * <h3>撤销不碰余额，一分钱都不碰</h3>
 * {@link #cancel} 全程不 UPDATE agent_balance、不写 commission_ledger。
 * 理由是这套账的基本口径：<b>余额只在打款成功时才扣</b>（admin/WithdrawService.markPaid
 * 里连同 commission_ledger 一起做），申请阶段占住的是"在途"这个集合
 * （APPLIED/REVIEWING/APPROVED/PAYING），而不是余额本身。所以单子一进 CANCELLED
 * 就自动退出这个集合，额度当场回吐 —— 这正是前端那句"撤销之后这笔钱会立刻回到
 * 可申请额度里，钱不会少"成立的机制。谁要在这里"把钱退回余额"，
 * 就是凭空多造一笔可提金额：余额本来就没被扣过。
 *
 * <p><b>FAILED 也在那个在途集合里</b>（2026-08-14 补）：打款失败的单子钱没出去、余额也没扣，
 * 但它并未终结 —— {@code trg_withdraw_state} 允许 FAILED → APPROVED 重新打款。
 * 不占额度的话，代理照着界面提示再申请一张、平台又把那张 FAILED 重新打了，
 * 同一笔余额出账两次。它的两条出路都在平台侧：重新打款成功，或驳回后额度释放。
 *
 * <h3>锁序：先 withdraw_order，后 agent_balance，且后者不加 FOR UPDATE</h3>
 * admin/WithdrawService.markPaid 的顺序正是 withdraw_order(FOR UPDATE) →
 * agent_balance(FOR UPDATE)。撤销若反过来先锁余额行再改提现单，两条路径就构成锁环，
 * 撞上时 InnoDB 会随机回滚一方 —— 而那一方可能是<b>打款</b>。所以 cancel 里
 * agent_balance 那次读是普通读，只用来算返回值，不参与任何裁决。
 */
// 必须给显式 bean 名：admin 包里也有一个 WithdrawService（代理申请 vs 平台审批，
// 是两件事、两个类）。@Service 默认按类名首字母小写取名，两边都叫 withdrawService，
// Spring 启动时直接 ConflictingBeanDefinitionException 拒绝起来。
// 注意这条不会被任何单测或 IT 抓到 —— 它们都是 new 出来直接测的，
// 没有一个启动 Spring 上下文，所以全绿的测试对"能不能起来"一个字都没说。
@Service("agentWithdrawService")
public class WithdrawService {

    /** ck_withdraw_amount 的门槛（100 元）。写在这里是为了在库拒之前先说人话。 */
    public static final long MIN_WITHDRAW_FEN = 10_000L;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final AgentService agents;
    private final SecureRandom rnd = new SecureRandom();

    public WithdrawService(JdbcTemplate jdbc, TransactionTemplate tx, AgentService agents) {
        this.jdbc = jdbc; this.tx = tx; this.agents = agents;
    }

    public record Applied(long id, String withdrawNo, long amountFen, String status,
                          String payeeName, long remainingFen) {}

    /**
     * 撤销的结果。
     *
     * @param changed              true = 本次真的把状态从 APPLIED 改成了 CANCELLED；
     *                             false = 这单本来就已经是 CANCELLED（幂等重放）
     * @param withdrawableFen      撤销后重算的可提额度；<b>算不出来时是 null</b>
     * @param withdrawableUnknownReason 算不出来的原因；算得出来时是 null。
     *                             两个字段成对出现：0 和"没有这个数"在运营那里是
     *                             完全不同的处理动作，不许用 0 冒充
     */
    public record Cancelled(boolean changed, long id, String withdrawNo, long amountFen,
                            Long withdrawableFen, String withdrawableUnknownReason) {}

    /**
     * 申请提现。
     *
     * @param amountFen 金额一律用分。这里不接受"元"字符串：元→分的换算一旦落到这一层，
     *                  '100.005' 这种输入会被 MySQL 静默截断，少提的那几分钱不会有日志
     */
    public Applied apply(long userId, Long amountFen, String channel, String ip) {
        AgentService.Agent a = agents.requireOperational(userId);

        if (amountFen == null || amountFen <= 0)
            throw ApiException.badRequest("请填写提现金额");
        long amount = amountFen;
        if (amount < MIN_WITHDRAW_FEN)
            throw ApiException.badRequest("单次提现不得低于 " + yuan(MIN_WITHDRAW_FEN)
                    + " 元，本次申请 " + yuan(amount) + " 元");
        String ch = AgentService.normalizeEnum(channel == null ? "BANK_TRANSFER" : channel,
                List.of("WECHAT_TRANSFER", "BANK_TRANSFER"), "提现渠道");

        // 收款人快照：一律取库里的登记信息。
        // 走 agents.payeeOf 而不是在这里再查一遍 —— /balance 用同一处判「填没填」，
        // 各写一遍的话，界面放行而这里拒（或反过来）都不会有任何报错。
        var payee = agents.payeeOf(a.id());
        String payeeName = payee.name();
        String payeeAcct = payee.account();
        if (!AgentService.notBlank(payeeName))
            throw ApiException.badRequest(
                    "提现前请先完成实名：打款户名必须与实名一致，平台不接受代收");
        if (!AgentService.notBlank(payeeAcct))
            throw ApiException.badRequest("提现前请先在代理资料里登记结算账户");

        return tx.execute(st -> {
            // 排他锁，见类注释。锁的是余额行本身 —— 触发器读的也是它。
            //
            // 实测（把 FOR UPDATE 去掉再跑 concurrentWithdrawIsSerialized）：
            // 8 个线程各申请 6 成余额，【7 笔全过】，在途合计 4200 元对着 1000 元的余额。
            // 也就是说触发器那道闸在并发下根本不成立，它只挡得住串行提交的重复申请。
            var bal = jdbc.queryForList(
                    "SELECT commission_balance_fen FROM agent_balance WHERE agent_id=? FOR UPDATE",
                    a.id());
            if (bal.isEmpty())
                throw ApiException.conflict("你还没有可提现的佣金");
            long available = num(bal.get(0).get("commission_balance_fen"));
            if (available < 0)
                throw ApiException.conflict(
                        "佣金余额为负（此前的退款冲正尚未被后续佣金抵扣完），暂不能提现");

            long inflight = agents.inflightWithdrawFen(a.id());
            long can = available - inflight;
            if (amount > can)
                throw ApiException.conflict("可提现 " + yuan(Math.max(0, can)) + " 元"
                        + (inflight > 0 ? "（余额 " + yuan(available) + " 元，已扣除在途提现单 "
                                        + yuan(inflight) + " 元）" : "")
                        + "，本次申请 " + yuan(amount) + " 元，超出可提额度");

            for (int attempt = 0; ; attempt++) {
                String no = nextWithdrawNo();
                try {
                    jdbc.update("INSERT INTO withdraw_order (withdraw_no,agent_id,amount_fen,"
                              + "channel,payee_name,payee_account,status) VALUES (?,?,?,?,?,?,'APPLIED')",
                            no, a.id(), amount, ch, payeeName, payeeAcct);
                    long id = num(jdbc.queryForObject(
                            "SELECT id FROM withdraw_order WHERE withdraw_no=?", Number.class, no));

                    // 提现是六个高争议动作之一，申请这一步也留痕：
                    // 只在打款时留痕的话，"我申请了平台没打"与"我根本没申请过"
                    // 这两种投诉在库里长得一模一样。
                    agents.audit(userId, "WITHDRAW_APPLY", "withdraw_order", id, ip,
                            "JSON_OBJECT('amount_fen',?,'channel',?)", amount, ch);

                    return new Applied(id, no, amount, "APPLIED", payeeName, can - amount);
                } catch (DuplicateKeyException e) {
                    if (attempt >= 4) throw e;      // 连撞 5 次说明随机源坏了，不能无限试
                }
            }
        });
    }

    /**
     * 代理自助撤销一张<b>还没被平台受理</b>的提现单，把这张单占住的额度放回可申请额度。
     *
     * <h3>裁决只有一条语句：那条带三个条件的 UPDATE</h3>
     * <pre>UPDATE withdraw_order SET status='CANCELLED'
     * WHERE id=? AND agent_id=? AND status IN ('APPLIED')</pre>
     * 三个条件缺一不可，各自挡的是不同的事故：
     * <ul>
     *   <li>{@code id=?} —— 定位。
     *   <li>{@code agent_id=?} —— <b>归属</b>。少写它，任何人都能撤销别人的提现单：
     *       id 是自增小整数，枚举成本为零。更糟的是这条 UPDATE 成功后不会有任何异常、
     *       任何报警，受害人只会看到自己的单子"莫名其妙没了"。
     *   <li>{@code status IN ('APPLIED')} —— <b>应用层唯一的那道状态闸</b>。
     *       {@code trg_withdraw_state} 只拦 OLD ∈ (PAID,CANCELLED,RISK_REJECTED)，
     *       对 NEW='CANCELLED' 一个字都没写：APPROVED→CANCELLED、PAYING→CANCELLED
     *       库里全放行。而 PAYING 意味着钱可能已经在路上，撤成 CANCELLED 之后这张单
     *       退出在途集合、额度立刻回吐，<b>同一笔钱可以被再提一次</b>。
     *       写成 IN 只是为了将来加值时改一个词，不是暗示现在还能加。
     * </ul>
     *
     * <p><b>不许写成"先 SELECT status 再判断再 UPDATE"</b>：中间那道缝隙足够平台侧的
     * approve / markPaid 把状态推走（admin/WithdrawService 就在并发地改同一行），
     * 读到 APPLIED、写下去时已经是 PAYING，而不带 status 谓词的覆盖式 UPDATE
     * 会把它改成 CANCELLED。rows==0 之后的那次回查只用来<b>解释</b>，不用来裁决。
     *
     * <p>并发因此由 InnoDB 的行锁裁决，谁先到谁赢：平台先推到 REVIEWING，
     * 则本次 rows=0 → 409 并把当前状态说清；代理先撤，则平台侧那条
     * {@code WHERE status IN (...)} 的 UPDATE 自己 rows=0 → 409。两侧都不会覆盖对方。
     *
     * @param withdrawId withdraw_order.id。不存在的单与别人的单给<b>同一句话</b>（404）：
     *                   给不同回答就等于开放了一个"这个 id 存在吗"的问答接口，
     *                   顺着枚举一遍就知道平台一共发了多少提现单
     */
    public Cancelled cancel(long userId, long withdrawId, String ip) {
        AgentService.Agent a = agents.requireOperational(userId);

        return tx.execute(st -> {
            // 唯一的裁决语句。三个条件的作用见方法注释。
            int rows = jdbc.update(
                    "UPDATE withdraw_order SET status='CANCELLED'"
                  + " WHERE id=? AND agent_id=? AND status IN ('APPLIED')",
                    withdrawId, a.id());

            boolean changed;
            String withdrawNo;
            long amount;

            if (rows == 1) {
                // 本事务已持有这一行的排他锁，这次读拿到的就是刚写下去的值
                var w = jdbc.queryForMap(
                        "SELECT withdraw_no, amount_fen FROM withdraw_order WHERE id=?", withdrawId);
                withdrawNo = (String) w.get("withdraw_no");
                amount = num(w.get("amount_fen"));
                changed = true;

                // 兜底：这张单上有没有挂着已经被标成"提现中"的佣金。
                //
                // 今天 apply() 从不写 withdraw_order_id，所以这个计数恒为 0，这段是休眠的。
                // 它存在的意义是将来谁给 apply 补上"申请时把佣金标 WITHDRAWING"时，
                // 撤销不会变成一台静默的碎钞机：trg_commission_state 明文禁止
                // WITHDRAWING → SETTLED（"只有冻结中的佣金能转为可提现"），
                // 一旦挂上去就退不回来 —— 单子撤了，那几笔佣金会被永久卡在"提现中"。
                //
                // commission_record 上没有 withdraw_order_id 索引，这是一次全表扫；
                // 只在真正撤销成功时执行一次，撤销是低频动作，可接受。
                Long attached = num(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM commission_record WHERE withdraw_order_id=?"
                      + " AND status='WITHDRAWING'", Number.class, withdrawId));
                if (attached != null && attached > 0)
                    throw ApiException.conflict("这张提现单上已挂了 " + attached
                            + " 笔佣金记录，撤销会把它们永久卡在「提现中」，请联系平台处理");

                agents.audit(userId, "WITHDRAW_CANCEL", "withdraw_order", withdrawId, ip,
                        "JSON_OBJECT('status','CANCELLED','amount_fen',?)", amount);
            } else {
                // rows==0 只说明"没改成"，为什么没改成要再查一次才知道。
                // 这条回查【必须】带 agent_id：不带的话，别人的单会走进下面的
                // 状态分支拿到 409（"当前状态是 打款中"），而这正是一个存在性探针。
                var found = jdbc.queryForList(
                        "SELECT status, withdraw_no, amount_fen FROM withdraw_order"
                      + " WHERE id=? AND agent_id=?", withdrawId, a.id());
                if (found.isEmpty())
                    throw ApiException.notFound("提现单不存在");

                var w = found.get(0);
                String now = (String) w.get("status");
                withdrawNo = (String) w.get("withdraw_no");
                amount = num(w.get("amount_fen"));

                if (!"CANCELLED".equals(now))
                    throw ApiException.conflict("当前状态是 " + statusText(now)
                            + "，不能撤销（只有 待审核 的提现单可以自行撤销；"
                            + "平台已受理的请联系平台）");

                // 已经是 CANCELLED：两个标签页各点一次、或前端去重没兜住时，
                // 第二次不该报错吓人。也不重复写审计 —— 只有第一次是真的动作。
                changed = false;
            }

            // ---- 以下只为算返回值，不参与裁决 ----
            // agent_balance 走普通读、不加 FOR UPDATE：锁序见类注释（先单后余额）。
            var bal = jdbc.queryForList(
                    "SELECT commission_balance_fen FROM agent_balance WHERE agent_id=?", a.id());
            if (bal.isEmpty())
                // 绝不回 0：0 在运营那里意味着"额度为零，去查是不是被在途单占满了"，
                // 而真实情况是"这个账户还没开过，这个数不存在"，两者是完全不同的处理动作。
                return new Cancelled(changed, withdrawId, withdrawNo, amount, null,
                        "该代理还没有佣金账户（agent_balance 无此行），可提额度无从算起；"
                      + "这里不返回 0，因为 0 会被读成额度耗尽");

            long available = num(bal.get(0).get("commission_balance_fen"));
            // 与 /api/agent/balance、与申请闸门用同一道算式（余额 − 在途）。
            // 三处对不上就是三本账：页面显示可提 800、点下去被拒，两边各自都"对"。
            // 此时在途已不含刚撤销的这一单 —— 它已经不在 APPLIED/REVIEWING/APPROVED/PAYING 里。
            long withdrawable = Math.max(0L, available - agents.inflightWithdrawFen(a.id()));
            return new Cancelled(changed, withdrawId, withdrawNo, amount, withdrawable, null);
        });
    }

    /** 我的提现单。条件写在 WHERE 里：客户端没有任何办法指定"看谁的单子"。 */
    public AgentService.Page list(long userId, String status, int page, int size) {
        AgentService.Agent a = agents.requireOperational(userId);
        int p = AgentService.checkPage(page), s = AgentService.checkSize(size);
        String st = AgentService.normalizeEnum(status,
                List.of("APPLIED", "RISK_REJECTED", "REVIEWING", "APPROVED", "PAYING",
                        "PAID", "FAILED", "CANCELLED"), "提现单状态");

        String where = "WHERE agent_id=?" + (st == null ? "" : " AND status=?");
        Object[] args = st == null ? new Object[]{a.id()} : new Object[]{a.id(), st};

        Long total = num(jdbc.queryForObject(
                "SELECT COUNT(*) FROM withdraw_order " + where, Number.class, args));

        Object[] pageArgs = new Object[args.length + 2];
        System.arraycopy(args, 0, pageArgs, 0, args.length);
        pageArgs[args.length] = s;
        // (long) 不能省：checkPage 只拦 page<1、不设上限，checkSize 只把 size 封顶到 100。
        // int 相乘在 page 两千五百万左右回绕成负数，MySQL 拒绝负 OFFSET →
        // 500「系统繁忙」而不是一页空结果 —— COUNT 那条先跑通了，
        // 所以前端拿到的不是「页码越界」而是「平台坏了」。
        pageArgs[args.length + 1] = (long) (p - 1) * s;

        // 显式写出 List<Map<...>> 而不是 var：让 T 从目标类型推成 Map，
        // 否则推出来的是 LinkedHashMap，构造 Page 时类型对不上。
        List<Map<String, Object>> items = jdbc.query(
                "SELECT id, withdraw_no, amount_fen, tax_withheld_fen, actual_paid_fen, channel,"
              + " payee_name, payee_account, status, risk_note, fail_reason, invoice_no,"
              + " created_at, paid_at FROM withdraw_order " + where
              + " ORDER BY id DESC LIMIT ? OFFSET ?",
                (rs, i) -> {
                    var m = new LinkedHashMap<String, Object>();
                    m.put("id", rs.getLong("id"));
                    m.put("withdrawNo", rs.getString("withdraw_no"));
                    m.put("amountFen", rs.getLong("amount_fen"));
                    m.put("taxWithheldFen", rs.getLong("tax_withheld_fen"));
                    m.put("actualPaidFen", rs.getLong("actual_paid_fen"));
                    m.put("channel", rs.getString("channel"));
                    m.put("payeeName", rs.getString("payee_name"));
                    m.put("payeeAccount", AgentService.maskAcct(rs.getString("payee_account")));
                    m.put("status", rs.getString("status"));
                    m.put("statusText", statusText(rs.getString("status")));
                    // 风控备注与失败原因要给：不给的话代理只看到一个"失败"，
                    // 既不知道是户名不符还是账号错，也就永远修不好，只能反复重提。
                    m.put("riskNote", rs.getString("risk_note"));
                    m.put("failReason", rs.getString("fail_reason"));
                    m.put("invoiceNo", rs.getString("invoice_no"));
                    m.put("createdAt", rs.getTimestamp("created_at").toLocalDateTime());
                    var paid = rs.getTimestamp("paid_at");
                    m.put("paidAt", paid == null ? null : paid.toLocalDateTime());
                    return m;
                }, pageArgs);

        return new AgentService.Page(items, p, s, total == null ? 0 : total);
    }

    // ---------------------------------------------------------------- 工具

    /** WD + 日期 + 10 位随机。与订单号同理：可预测的单号会把当日提现量送出去。 */
    private String nextWithdrawNo() {
        StringBuilder sb = new StringBuilder("WD");
        sb.append(LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE));
        for (int i = 0; i < 10; i++) sb.append((char) ('0' + rnd.nextInt(10)));
        return sb.toString();
    }

    /** 分 → 元，只用于给用户看的文案。金额本身全程是 BIGINT 分，绝不落到浮点上。 */
    static String yuan(long fen) {
        return String.format(Locale.ROOT, "%d.%02d", fen / 100, Math.abs(fen % 100));
    }

    static String statusText(String s) {
        return switch (s) {
            case "APPLIED" -> "已申请，待审核";
            case "RISK_REJECTED" -> "风控拒绝";
            case "REVIEWING" -> "审核中";
            case "APPROVED" -> "审核通过，待打款";
            case "PAYING" -> "打款中";
            case "PAID" -> "已打款";
            case "FAILED" -> "打款失败";
            case "CANCELLED" -> "已取消";
            default -> s;
        };
    }
}
