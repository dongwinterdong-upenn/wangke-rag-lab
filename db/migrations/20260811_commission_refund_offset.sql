USE `wangke`;

-- 退款后的佣金冲正必须可累计、可复算。只把状态改成 REVOKED 会让已经进入
-- agent_balance 的钱留在余额里，代理仍能提现；部分退款则完全没有落点。
ALTER TABLE `commission_record`
  ADD COLUMN `reversed_fen` BIGINT NOT NULL DEFAULT 0
    COMMENT '退款累计冲正佣金，结算只入净额' AFTER `amount_fen`,
  ADD CONSTRAINT `ck_commission_reversed`
    CHECK (`reversed_fen` >= 0 AND `reversed_fen` <= `amount_fen`);
