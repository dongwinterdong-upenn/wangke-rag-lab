package tech.dragonai.wangke.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

/**
 * 佣金冻结期满 → 转可提现。
 *
 * <h3>为什么这个类必须存在</h3>
 * 合并六域时发现分销这条钱链<b>断在中间</b>，而且断得毫无声息：
 * <ul>
 *   <li>{@code FulfillService} 在订单履约时写 {@code commission_record}
 *       （status=PENDING，带一个 {@code freeze_until}）—— 这一步有人做；
 *   <li>冻结期满后转 SETTLED、记 {@code commission_ledger}、加 {@code agent_balance}
 *       —— <b>全仓零行代码</b>；
 *   <li>提现闸门 {@code trg_withdraw_precheck} 读的是 {@code agent_balance}
 *       —— 这一步也有人做。
 * </ul>
 * 于是生产上的表现是：代理卖了课、佣金记录也躺在库里，但
 * {@code /api/agent/balance} 恒为「未开通佣金账户」，提现永远走不到闸门。
 * 一个分销平台的全部意义就是给代理发钱，而这条路是不通的。
 *
 * <p>它当初没人写，是因为它<b>不在任何一个域里</b> —— 它在交易域和代理域中间，
 * 六域切分正好从这里切开。schema 其实一直在等它：
 * {@code commission_record} 上那条 {@code idx_freeze_scan (status, freeze_until)}
 * 的列注释写着「定时任务扫 PENDING 到期转 SETTLED」。索引建好了，任务没写。
 *
 * <h3>三条不能松的实现纪律</h3>
 * <ol>
 *   <li><b>幂等靠唯一键，不靠「先查后写」。</b>{@code commission_ledger} 上有
 *       {@code uk_biz_ref (biz_type, ref_no)}，ref_no 取佣金记录的 id。
 *       重复跑一次只会撞唯一键，不会凭空多一笔进账。
 *   <li><b>抢占靠带条件的 UPDATE，不靠「先查后改」。</b>
 *       {@code WHERE id=? AND status='PENDING'} 影响行数为 0 就说明别人先拿到了，
 *       直接跳过。两个实例同时跑不会双发。
 *   <li><b>余额算术必须在行锁里做。</b>{@code ck_comm_ledger_arith} 要求
 *       {@code balance_after = balance_before ± amount}，而 balance_before 取自
 *       {@code agent_balance} 那一行。不加 {@code FOR UPDATE} 的话，两笔并发结算
 *       会读到同一个 before，各写各的 after —— 约束照样满足（每行自洽），
 *       但快照少加了一笔。这种错不会报任何异常，只会让代理少拿钱。
 * </ol>
 *
 * <p>一次只处理一批、每笔一个独立事务：一笔算错不该拖累其余，
 * 而「整批一个大事务」在几千笔时会长时间持有行锁，把提现申请堵在门外。
 */
@Service
public class CommissionSettleService {

    private static final Logger log = LoggerFactory.getLogger(CommissionSettleService.class);

    /** 每批上限。取小值是为了让单次事务短、锁持有时间短，宁可多跑几轮。 */
    private static final int BATCH = 200;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public CommissionSettleService(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    /** 一轮结算的结果。settled 是真正记账的笔数，skipped 是被别人抢先或已记过的。 */
    public record Result(int settled, int skipped, long amountFen, int revoked) {}

    /**
     * 十分钟一轮。冻结期是以天计的，扫描频率不影响钱数，只影响「到点后多久可提」。
     *
     * <p>{@code fixedDelay} 而不是 {@code fixedRate}：上一轮没跑完就不该起下一轮，
     * 否则积压时两轮会互相抢同一批行的锁，越堵越慢。
     */
    @Scheduled(fixedDelayString = "${wangke.commission.settle-interval-ms:600000}",
               initialDelayString = "${wangke.commission.settle-initial-delay-ms:60000}")
    public void scheduled() {
        try {
            Result r = settleDue();
            if (r.settled() > 0 || r.skipped() > 0 || r.revoked() > 0)
                log.info("佣金结算：转可提现 {} 笔 / {} 分，跳过 {} 笔，全额冲正终结 {} 笔",
                        r.settled(), r.amountFen(), r.skipped(), r.revoked());
        } catch (Exception e) {
            // 定时任务抛异常会被 Spring 吞掉且默认不再重试同一实例的后续调度，
            // 所以这里必须自己接住并留痕 —— 静默停摆的结算任务等于没有结算任务。
            log.error("佣金结算轮次失败（下一轮会重试）", e);
        }
    }

    /**
     * 把所有冻结期已满的 PENDING 佣金转成 SETTLED 并入账。
     *
     * <p>时间判定走 {@code NOW(3)}（数据库时钟）而不是应用进程时钟：
     * 多实例部署时机器差几秒是常态，用本机钟会出现「A 机认为到期、B 机认为没到」，
     * 而这种不一致在单实例上永远重现不出来。
     */
    public Result settleDue() {
        int settled = 0, skipped = 0, revoked = 0;
        long total = 0;
        while (true) {
            List<Map<String, Object>> batch = jdbc.queryForList(
                    "SELECT id, beneficiary_agent_id, order_no, amount_fen, reversed_fen "
                  + "FROM commission_record "
                  + "WHERE status='PENDING' AND freeze_until <= NOW(3) "
                  + "ORDER BY id LIMIT " + BATCH);
            if (batch.isEmpty()) break;

            for (Map<String, Object> row : batch) {
                long id = ((Number) row.get("id")).longValue();
                long agentId = ((Number) row.get("beneficiary_agent_id")).longValue();
                long amount = ((Number) row.get("amount_fen")).longValue()
                        - ((Number) row.get("reversed_fen")).longValue();
                String orderNo = (String) row.get("order_no");

                // 净额被退款冲到 0（或更少）的这一笔，绝不能交给 settleOne。
                //
                // commission_ledger 上有 ck_comm_ledger_amount CHECK (amount_fen > 0)，
                // 插 0 会抛 DataIntegrityViolationException —— 而 settleOne 只接住并发类异常，
                // 这一条会一路穿出 settleDue，把整轮扫描连同后面所有到期佣金一起掀掉。
                // 更糟的是它会复发：批查询是 ORDER BY id LIMIT，下一轮 10 分钟后
                // 又从同一行开始，于是这个代理后面所有佣金永久结算不了。
                // 对外零症状 —— 只有 scheduled() 里那一行 log.error。
                //
                // 全额冲正的佣金本来就没有任何净额可入账，直接终结成 REVOKED：
                // 与 RefundService 整单退款时的落点一致，且 trg_commission_state 允许
                // PENDING → REVOKED（它只禁止从 REVOKED / WITHDRAWN 复活）。
                if (amount <= 0) {
                    int n = jdbc.update(
                            "UPDATE commission_record SET status='REVOKED', revoked_at=NOW(3),"
                          + " revoke_reason=COALESCE(revoke_reason,'退款已全额冲正，无净额可结算')"
                          + " WHERE id=? AND status='PENDING' AND amount_fen - reversed_fen <= 0",
                            id);
                    if (n == 1) { revoked++; log.info("佣金 {}（单 {}）已被退款全额冲正，终结为 REVOKED", id, orderNo); }
                    else skipped++;
                    continue;
                }

                if (settleOne(id, agentId, amount, orderNo)) {
                    settled++; total += amount;
                } else {
                    skipped++;
                }
            }
            if (batch.size() < BATCH) break;
        }
        return new Result(settled, skipped, total, revoked);
    }

    /**
     * @return true 表示这一笔由本次调用记的账；false 表示已被处理过或被别人抢走
     *
     * <p><b>死锁重试不是保险，是这条路径的正常一环。</b>同一个代理的多笔佣金
     * 天然会在同一行余额上排队，而 InnoDB 的锁等待图一旦成环就会挑牺牲者回滚。
     * 牺牲者被静默丢掉的表现是「代理少拿了几笔钱，日志里一个字都没有」。
     */
    private boolean settleOne(long commissionId, long agentId, long amountFen, String orderNo) {
        // ★ 余额行的创建必须在事务【外面】。
        //
        // 原来它在事务里，紧挨着下面那句 SELECT ... FOR UPDATE，于是踩了一个
        // 经典陷阱：INSERT IGNORE 撞到已存在的行时会取一把 S 锁，紧接着
        // FOR UPDATE 要把它升成 X 锁 —— 多个事务同时持 S 再抢 X，就是标准的死锁环。
        // 实测 12 笔并发只有 5 笔落账，另外 7 笔是被 MySQL 选中的死锁牺牲者。
        //
        // 放在外面（自动提交）之后，锁瞬间就还回去了；事务里对这一行的第一次触碰
        // 直接就是 X 锁，没有升级这一步，环就不存在。
        jdbc.update("INSERT IGNORE INTO agent_balance (agent_id) VALUES (?)", agentId);

        for (int attempt = 1; ; attempt++) {
            try {
                return settleOnce(commissionId, agentId, amountFen, orderNo);
            } catch (org.springframework.dao.ConcurrencyFailureException e) {
                // 死锁 / 锁等待超时。整个事务已被回滚（佣金状态也退回 PENDING），
                // 重来一次是安全的；重试仍失败就抛出去，让调度那一层留痕。
                if (attempt >= 3) {
                    log.error("佣金 {} 结算连续 {} 次因并发冲突失败", commissionId, attempt, e);
                    throw e;
                }
                log.debug("佣金 {} 第 {} 次遇到并发冲突，重试", commissionId, attempt);
            }
        }
    }

    private boolean settleOnce(long commissionId, long agentId, long amountFen, String orderNo) {
        return Boolean.TRUE.equals(tx.execute(st -> {
            // ① 抢占。条件写在 WHERE 里，判定与写入是同一条语句 ——
            //    「先 SELECT 看是不是 PENDING，再 UPDATE」中间那道缝隙足够
            //    另一个实例把同一笔也认领走，然后两边各记一次账。
            int claimed = jdbc.update(
                    "UPDATE commission_record SET status='SETTLED', settled_at=NOW(3) "
                  + "WHERE id=? AND status='PENDING' AND freeze_until <= NOW(3)",
                    commissionId);
            if (claimed != 1) return false;

            // ② 行锁。ck_comm_ledger_arith 要求 after = before + amount，
            //    而 before 取自这一行 —— 不锁的话两笔并发会读到同一个 before，
            //    每行都自洽、约束全过，但快照少加了一笔，且不报任何错。
            long before = jdbc.queryForObject(
                    "SELECT commission_balance_fen FROM agent_balance "
                  + "WHERE agent_id=? FOR UPDATE", Long.class, agentId);
            long after = before + amountFen;

            try {
                jdbc.update(
                        "INSERT INTO commission_ledger "
                      + "(agent_id,biz_type,ref_no,direction,amount_fen,"
                      + " balance_before,balance_after,remark) "
                      + "VALUES (?,'SETTLE',?,'IN',?,?,?,?)",
                        agentId, String.valueOf(commissionId), amountFen, before, after,
                        "订单 " + orderNo + " 佣金冻结期满");
            } catch (DuplicateKeyException e) {
                // uk_biz_ref 撞了：这笔的进账在之前某一轮已经记过，
                // 只是当时记完账、改状态之前进程挂了（或那一轮被回滚得不彻底）。
                // 钱不能再加一次，但状态得往前推 —— 上面 ① 已经推了，直接提交。
                log.warn("佣金 {} 的台账已存在，只补状态不重复入账", commissionId);
                return false;
            }

            jdbc.update("UPDATE agent_balance SET commission_balance_fen=?, "
                      + "total_settled_fen=total_settled_fen+? WHERE agent_id=?",
                    after, amountFen, agentId);
            return true;
        }));
    }
}
