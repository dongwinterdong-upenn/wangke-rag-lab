-- =============================================================================
--  2026-08-11 · 一级单买 399 → 349（需求方拍板）
--
--  背景：原表里一级单买与二级单买同为 399，差价为 0，一级代理在下级的单买上
--  一分钱拿不到，结果必然是一级只推批量档、二级只买单买档。
--  （seed.sql 里那条 8% 比例返佣是我当时补的占位，不是拍板值，本次一并替换。）
--
--  改后整张表变规整：一级在每一档都比二级便宜 50，佣金恒等于差价，只有一套口径。
--
--      层级 \ 档位      批量 10 个        单买 1 个
--      一级             2980 (298/码)     349
--      二级             3490 (349/码)     399
--      学员零售         —                 799
--      差价/佣金        51/码             50/码
--
--  由此得到一条可断言的恒等式 —— 平台净收与「谁来买」无关：
--      二级付 3490 − 佣金 510 = 2980 = 一级批量价
--      二级付  399 − 佣金  50 =  349 = 一级单买价
--  这条写进了 test_schema.py 的 W 段，改价时会被自动核对。
--
--  ⚠ 改价姿势：旧行封口 + 插新行，禁止 UPDATE 旧行。
--     历史订单存的是价格快照，但对账、复盘要能查回「当时挂的是哪一条」。
--     UPDATE 掉旧行等于把那段历史抹了。
-- =============================================================================

USE `wangke`;

SET @T = '2026-08-11 00:00:00';

-- ---------- 价目：一级单买 399 → 349 ----------
UPDATE `agent_price_tier`
   SET `effective_to` = @T
 WHERE `agent_level` = 1 AND `sku_id` = 1 AND `effective_to` IS NULL;

INSERT INTO `agent_price_tier`
  (`agent_level`,`sku_id`,`total_price_fen`,`effective_from`,`created_by`)
VALUES (1, 1, 34900, @T, 1);

-- ---------- 佣金：单买档由「8% 占位」改为「按码固定 50 元」----------
UPDATE `commission_rule`
   SET `effective_to` = @T
 WHERE `buyer_level` = 2 AND `sku_id` = 1 AND `effective_to` IS NULL;

INSERT INTO `commission_rule`
  (`buyer_level`,`sku_id`,`calc_mode`,`fixed_per_code_fen`,`freeze_days`,`effective_from`,`created_by`)
VALUES (2, 1, 'FIXED_PER_CODE', 5000, 15, @T, 1);
