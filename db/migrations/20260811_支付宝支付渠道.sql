-- =============================================================================
--  2026-08-11 · 接入支付宝：支付流水与订单渠道两个枚举加值
--
--  背景：schema 起草时只画了微信与对公转账两条收款路，实际接入的是支付宝。
--  没有这两个值，第一笔真实回调会在 INSERT payment_record 时以
--  "Data truncated for column 'provider'" 收场 —— 钱到了，货发不出去，
--  而报错文案完全看不出是"枚举里没有支付宝"。
--
--  ⚠ 新值一律【追加在末尾】，不许插在中间。
--     ENUM 在行里存的是下标不是字符串。把 'ALIPAY' 插到 'BANK_TRANSFER' 前面，
--     MySQL 的某些 ALTER 路径会按下标重解释存量行，于是所有历史的对公转账流水
--     一夜之间变成支付宝流水。它不报错、不告警，只有对账时数字对不上。
--     追加在末尾时下标不动，且能走 ALGORITHM=INSTANT。
--
--  幂等：MODIFY COLUMN 给的是目标定义，重复执行是空操作，已在临时库验证过
--  （存量 BANK_TRANSFER 行在 ALTER 前后逐值不变）。
-- =============================================================================

USE `wangke`;

-- 支付流水的渠道。幂等键 uk_provider_txn 是 (provider, provider_txn_id)，
-- 所以这一列的取值直接参与"同一笔交易只能入账一次"的判定。
ALTER TABLE `payment_record`
  MODIFY COLUMN `provider` ENUM('WECHAT','BANK_TRANSFER','ALIPAY') NOT NULL;

-- 订单上记的是"用哪个收银台付的"。PC 与 WAP 分开记而不是合成一个 ALIPAY：
-- 出问题时（比如 WAP 收银台在某个 App 的 webview 里跳不回来）要能按渠道切分看，
-- 合成一个值之后这个切分就再也做不出来了，而那正是最需要它的时候。
ALTER TABLE `biz_order`
  MODIFY COLUMN `pay_channel`
  ENUM('WECHAT_NATIVE','WECHAT_JSAPI','BANK_TRANSFER','ALIPAY_PAGE','ALIPAY_WAP') NULL;
