-- =============================================================================
--  打款失败的提现单必须继续占用可提额度
--
--  原来 trg_withdraw_precheck 的在途集合是 ('APPLIED','REVIEWING','APPROVED','PAYING')，
--  不含 FAILED。而 trg_withdraw_state 明文允许 FAILED → APPROVED（后台「修正收款信息
--  后重新打款」走的就是这条），也就是说一张 FAILED 的单子随时可能变成真的出账。
--
--  两者放在一起就是一条双重提现的路，且每一步单看都合法：
--
--    1. 代理提 1000 元 → 银行退票 → 平台标记打款失败（FAILED）。
--       这一步【没有】扣余额（扣减只发生在 markPaid 里），
--       同时这张单从在途集合里消失，额度当场全额回吐。
--    2. 代理端在这个状态下明写着「打款失败，钱还在你的余额里，核对账号后可以重新申请」，
--       他照做又提 1000 元 —— 闸门看不见那张 FAILED，放行。
--    3. 平台把第一张 FAILED 重新审核通过并打款（合法路径），再把第二张也打了。
--       两笔都真的出账，commission_balance_fen 被扣成 -1000。
--
--  agent_balance.commission_balance_fen 的注释就写着「可为负」，没有任何约束会拦下第 3 步；
--  audit_log 里两条 WITHDRAW_PAID 也都长得完全正常。这类缺陷不会报错，只会少钱。
--
--  修法是让 FAILED 继续占额度，直到平台把它推向终点：
--    · 重新审核通过 → 打款成功（PAID），额度按实际出账消耗；
--    · 或者驳回（RISK_REJECTED，终态），额度当场释放，代理再重新申请。
--  为此 admin/WithdrawService.reject 同步放开了 FAILED（原来只接受 APPLIED/REVIEWING），
--  否则 FAILED 就成了一个既占额度又无法释放的死结。
--
--  幂等：CREATE OR REPLACE TRIGGER 需要 MySQL 8.0.29+；这里用先 DROP 再建，任何 8.0 都行。
--  这条迁移只改判定集合，不动任何数据行。
-- =============================================================================

DROP TRIGGER IF EXISTS `trg_withdraw_precheck`;

DELIMITER $$
CREATE TRIGGER `trg_withdraw_precheck`
BEFORE INSERT ON `withdraw_order` FOR EACH ROW
BEGIN
  DECLARE bal      BIGINT DEFAULT NULL;
  DECLARE inflight BIGINT DEFAULT 0;
  SELECT `commission_balance_fen` INTO bal FROM `agent_balance` WHERE `agent_id` = NEW.agent_id;
  IF bal IS NULL THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '该代理没有佣金账户，不能提现';
  END IF;
  -- 负余额意味着退款冲正尚未被后续佣金抵扣完，此时放款等于二次损失
  IF bal < 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '佣金余额为负（退款冲正未抵扣完），不能提现';
  END IF;
  -- 余额够，不代表还没被别的提现单占着。余额只在打款成功时才扣，
  -- 只比余额的话，10 万余额连提三笔 6 万，三笔都能过闸。
  -- 这个集合与 AgentService.inflightWithdrawFen 必须逐字一致：一处算「让不让过闸」，
  -- 一处算界面上「现在能提多少」，差一个状态就会出现「页面说能提、库说不能」。
  SELECT COALESCE(SUM(`amount_fen`),0) INTO inflight FROM `withdraw_order`
   WHERE `agent_id` = NEW.agent_id
     AND `status` IN ('APPLIED','REVIEWING','APPROVED','PAYING','FAILED');
  IF NEW.amount_fen > bal - inflight THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '提现金额超过可提现佣金余额（已扣除在途提现单）';
  END IF;
END$$
DELIMITER ;
