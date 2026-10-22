-- 超管自由设置进货价：层级价打底 + 某几家代理单独覆盖。
--
-- ★ 这份迁移里的【触发器段必须用 root 连接执行】，不能用应用账号：
--   COURSE 的 MySQL 开着 binlog 而 log_bin_trust_function_creators=0，
--   应用账号没有 SUPER/TRIGGER 权限，建触发器会报
--   「ERROR 1419: You do not have the SUPER privilege and binary logging is enabled」，
--   而这个错**只打在触发器那几行上** —— 表、列、唯一键、CHECK 全都建好了，闸门一个没有，
--   而后续插入全部通过。20260822_超管手工发码.sql 与 20260821_讲次内容分节.sql 各记着一次。
--       ssh COURSE 'mysql wangke < 这个文件'
--   跑完不要只看「没报错」：要在库上真查一次
--       SELECT TRIGGER_NAME FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE();
--   确认 trg_price_tier_precheck / trg_price_override_precheck / trg_price_override_update 三样都在。
--
--
-- ## 为什么专属价是一张新表，不是给 agent_price_tier 加一列 agent_id
--
--   ① **唯一键会当场松掉。** 今天 uk_active_price(agent_level, sku_id, active_flag) 是
--      「每个 (层级,套餐) 至多一条当前生效价」这条不变量的全部执行力，PricingService.priceOf
--      查出 >1 行就炸也是靠它兜底。加一列 agent_id 之后唯一键必须跟着改成四列，
--      而那一刻「层级价」这一档（agent_id IS NULL）就退出唯一性判定了 —— 多个 NULL 不冲突，
--      同一个 (层级,套餐) 可以有任意多条当前生效的层级价。表现是取价二义，
--      而它不会在改表那天暴露，是在下一次改层级价时才第一次插出第二行。
--   ② **两种价目的定义域不同。** 层级价按 (1/2/0) 取，专属价按具体某家代理取；
--      混在一张表里之后，每一处查价的 SQL 都要多带一个「agent_id IS NULL」条件，
--      漏掉的那一处不会报错，只会把某家的专属价发给所有同级代理。
--   ③ **撤销专属价要能回落。** 分表之后「撤销」= 给专属行封口，取价自然回落到层级价；
--      同表的话「撤销」是删行或者写一个哨兵值，两条都会把历史弄丢。
--
--
-- ## 「专属价不可能让平台倒贴」为什么必须是库层的
--
-- 二级代理下单时平台净收 = 二级成交价 − 返给一级的佣金（commission_rule，固定每码或按实付比例）。
-- 种子里这条是恒等的：二级付 3490 − 佣金 510 = 2980 = 一级批量价（test_schema.py W 段在核）。
--
-- 专属价一旦设到佣金以下，这个净收就是**负数**：平台每卖一单倒贴钱。而这条错的形态是
-- 全链路合法的 ——
--   · 订单 amount_fen > 0，ck_order_amount 满意；
--   · 佣金按规则算出来是正数，ck_commission_amount 满意，冻结 15 天后自动进 agent_balance；
--   · 对账表、代理台账、总览四项每一处单看都平；
--   · 只有把「本月收到的钱」减去「本月付出的佣金」才看得见那个缺口，而那是月底的事。
-- 到那时佣金早已结算完毕，与真佣金不可分辨，撤回路径只剩「找代理把钱要回来」。
--
-- 应用层（AdminPricingController）也拦一道，两道互补：应用层那道给运营一句能算得出来的话
-- （成交价 X、佣金 Y、净收 Z），这道保证绕不过去 —— 直接 INSERT、以后新写的某个批量导价功能、
-- 或者哪天有人「临时改一下」，都得过。
--
--
-- ## 层级价那张老表也上了同一道闸，而且比专属价更要紧
--
-- 专属价错了只坑一家；**层级价错了坑的是这一层的全部代理**。所以 trg_price_tier_precheck
-- 建在 agent_price_tier 上，判据与专属价那条逐字同源（见两个触发器体里的交叉注释）。
--
-- 它对存量与现有夹具都是零影响：level=1/0 那两档在 commission_rule 里根本没有规则行
-- （规则只配了 buyer_level=2），恒定跳过；level=2 现行两档的净收是 2980 / 349 元，离 0 很远。
-- db/seed.sql 里价目先于规则插入，那一刻规则表是空的，也照样跳过。
--
--
-- ## 为什么还要给 biz_order 加一列 price_override_id
--
-- price_tier_id 的列注释是「取价来源，便于事后复盘」。专属价成交的单如果把 override 的主键
-- 塞进这一列，它就指向了**另一张表的同名主键** —— 17 号既可能是层级价也可能是专属价，
-- 而两行的金额不同。复盘时按 price_tier_id 去 agent_price_tier 一查，查到的是一个
-- 与 amount_fen 对不上的数，看起来像数据损坏。所以另开一列，并用 CHECK 保证
-- **两个指针至多只有一个非空**：取价来源只能有一个，写两个等于没写。
--
-- ★ 这一列今天没有写入方：OrderService 那条链归另一路改（见 PricingService 的三参重载注释）。
--   接上之前，专属价对下单价没有任何影响 —— 这是已知的、写在这里的半成品状态，
--   不是漏掉的一步。

-- ================================================================ 锁等待
--
-- 生产的 lock_wait_timeout 是默认值 31536000 秒（一整年）。下面给 biz_order 加列 + 加 CHECK
-- 要拿它的排他元数据锁；只要那一刻有任何长事务握着这张表，ALTER 就会**等上一年**，
-- 而元数据锁是排队的 —— 排在它后面的每一条 biz_order 查询（下单、订单页、总览）
-- 也一起无限期挂住。表现是整站订单相关功能同时卡死，不自愈、日志里一行错都没有。
--
-- 10 秒拿不到就认输：失败是响的、可重试的，而挂死是哑的。
SET SESSION lock_wait_timeout = 10;


-- ================================================================ 权限探针
--
-- ★ 这一段必须排在**任何 DDL 之前**，它的全部作用是「用错账号时先炸，别先拆东西」。
--
-- 用应用账号跑这份文件的话：下面 `DROP TRIGGER IF EXISTS trg_price_tier_precheck`
-- **会成功**（DROP 只要 TRIGGER 权限，而应用账号是 GRANT ALL ON wangke.*），
-- 紧跟的 CREATE TRIGGER 因为 log_bin=ON 且 log_bin_trust_function_creators=OFF 报 1419。
-- **DDL 不回滚，删掉就是删掉。** 已经装好之后再用错账号重跑一遍，
-- 就是把三道闸一起拆掉，而表、列、CHECK 全都还在，看起来只是「触发器那段没成」。
--
-- 文件末尾那段 sp_assert_landed 救不了这个：`mysql db < file` 首错即停，走不到那一行。
--
-- 探针建在 `sku` 上：运行期没有任何代码往 sku 插行，而 CREATE TRIGGER 要的权限与表无关。
-- 触发器体是空的，即便在存在的那一瞬间有人插也什么都不做。
--
--     正确跑法： ssh COURSE 'mysql wangke < 这个文件'      （默认就是 root@localhost）

DROP TRIGGER IF EXISTS `trg_privilege_probe`;

DELIMITER $$
CREATE TRIGGER `trg_privilege_probe` BEFORE INSERT ON `sku` FOR EACH ROW
BEGIN
END$$
DELIMITER ;

DROP TRIGGER `trg_privilege_probe`;


-- ================================================================ 可重复执行的脚手架
--
-- 存在性判断在过程里、**完整 DDL 字面量在调用点**。反过来写（在过程体里 CONCAT 出 DDL）
-- 的话 SchemaDriftTest 的 ADDED 正则一个都抓不到 —— 实测 20260821_讲次重切四十分钟.sql
-- 用那种写法加的 4 列 0 命中。后果是最坏的一种：新装的库缺这些东西，而门禁全绿。
--
-- CREATE TABLE 不需要过程：它自带 IF NOT EXISTS，字面量本来就在文件里。

DELIMITER $$

DROP PROCEDURE IF EXISTS `sp_run_if_col_missing`$$
CREATE PROCEDURE `sp_run_if_col_missing`(IN p_table VARCHAR(64), IN p_col VARCHAR(64), IN p_ddl TEXT)
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table
                    AND COLUMN_NAME = p_col) THEN
    SET @ddl = p_ddl; PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
  END IF;
END$$

-- CHECK 走【总是重建】，不是「不存在才建」。按名字判存在判不出内容：约束在、
-- 但定义是上一版的，那样会安静跳过并报成功（20260822 那轮的 ck_order_grant_by 真踩过）。
DROP PROCEDURE IF EXISTS `sp_replace_check`$$
CREATE PROCEDURE `sp_replace_check`(IN p_table VARCHAR(64), IN p_name VARCHAR(64), IN p_ddl TEXT)
BEGIN
  -- 顺序是「先拿临时名验一遍数据，确认加得上，再拆掉旧的换正名」。
  -- 直接 DROP 再 ADD 的话，只要存量里有一行顶回来，ADD 失败、脚本中止，
  -- 而旧约束**已经被删掉了** —— 那条不变量永久消失，且看起来只是「迁移报了个错」。
  SET @probe = REPLACE(p_ddl, CONCAT('`', p_name, '`'), CONCAT('`', p_name, '_probe`'));
  PREPARE pr FROM @probe; EXECUTE pr; DEALLOCATE PREPARE pr;
  SET @dropprobe = CONCAT('ALTER TABLE `', p_table, '` DROP CHECK `', p_name, '_probe`');
  PREPARE dp FROM @dropprobe; EXECUTE dp; DEALLOCATE PREPARE dp;

  IF EXISTS (SELECT 1 FROM information_schema.TABLE_CONSTRAINTS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table
                AND CONSTRAINT_NAME = p_name) THEN
    SET @drop = CONCAT('ALTER TABLE `', p_table, '` DROP CHECK `', p_name, '`');
    PREPARE d FROM @drop; EXECUTE d; DEALLOCATE PREPARE d;
  END IF;
  SET @ddl = p_ddl; PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
END$$

DELIMITER ;


-- ---------------------------------------------------------------- 新表：专属价
--
-- 历史模式逐条照抄 agent_price_tier，不自创：
--   · effective_from / effective_to，改价 = 给旧行封口 + 插新行，**绝不 UPDATE 旧行**。
--     UPDATE 旧行的话，那张按这个价成交过的老订单再去查「当时是什么价」，查到的是新价 ——
--     不报错，只是历史被改写了。trg_price_override_update 把这条做成物理的。
--   · active_flag 生成列 + uk_active_override：多个 NULL 不参与唯一性判定，
--     所以封口后的历史行想留多少留多少，而每个 (代理,套餐) 至多一条当前生效 —— 取价不二义。
--   · ck_override_positive：0 元或负数价目在库层造不出来。
-- 另加两列：created_by（是谁定的这个价）与 reason（为什么给这家单独定价）。
-- reason 是 NOT NULL：事后有人问「这家凭什么比别人便宜 50 块」，答案得在这一行上，
-- 不能只在审计里（审计是业务事务之外写的，写失败时价目行还在）。

CREATE TABLE IF NOT EXISTS `agent_price_override` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `agent_id`        BIGINT UNSIGNED NOT NULL              COMMENT '这份价只对这一家生效',
  `sku_id`          BIGINT UNSIGNED NOT NULL,
  `total_price_fen` BIGINT          NOT NULL              COMMENT '整单价格（分）。349 元 = 34900',
  `effective_from`  DATETIME(3)     NOT NULL,
  `effective_to`    DATETIME(3)     NULL                  COMMENT 'NULL = 当前生效；封口即回落层级价',
  `active_flag`     TINYINT GENERATED ALWAYS AS (IF(`effective_to` IS NULL, 1, NULL)) STORED,
  `reason`          VARCHAR(255)    NOT NULL              COMMENT '为什么给这家单独定价，必填',
  `created_by`      BIGINT UNSIGNED NOT NULL,
  `created_at`      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_active_override` (`agent_id`,`sku_id`,`active_flag`),
  KEY `idx_override_lookup` (`agent_id`,`sku_id`,`effective_from`),
  CONSTRAINT `ck_override_positive` CHECK (`total_price_fen` > 0)
) ENGINE=InnoDB COMMENT='代理专属价（层级价的单家覆盖）';


-- ---------------------------------------------------------------- 订单的取价来源
--
-- 见文件头「为什么还要给 biz_order 加一列」。CHECK 保证两个指针至多一个非空。

CALL `sp_run_if_col_missing`('biz_order', 'price_override_id',
  'ALTER TABLE `biz_order` ADD COLUMN `price_override_id` BIGINT UNSIGNED NULL COMMENT ''按专属价成交时的取价来源（agent_price_override.id）；与 price_tier_id 至多一个非空'' AFTER `price_tier_id`');

CALL `sp_replace_check`('biz_order', 'ck_order_price_source',
  'ALTER TABLE `biz_order` ADD CONSTRAINT `ck_order_price_source` CHECK (`price_tier_id` IS NULL OR `price_override_id` IS NULL)');


DROP PROCEDURE IF EXISTS `sp_run_if_col_missing`;
DROP PROCEDURE IF EXISTS `sp_replace_check`;


-- ---------------------------------------------------------------- 触发器
--
-- ★ 从这里往下必须用 root 连接执行（见文件头）。

DELIMITER $$

-- 层级价：净收不得为负。
--
-- 判据与下面 trg_price_override_precheck 同源，两处必须一起改 —— 只改一处的表现是
-- 「后台的专属价改不了、但改层级价能改成同样的亏本数字」，而层级价坑的是整整一层代理。
--
-- 净收口径逐字对齐 FulfillService.settleCommission：只有「买家是二级且有上级且规则表配了」
-- 才会真的产生佣金，而佣金算出来 <= 0 时那边直接 return 0（不插行）。这里跟着钳到 0，
-- 否则一条负佣金的规则会在这里被算成「平台还赚了」，而实际一分钱都不会返出去。
--
-- 层级不带 agent_id，所以「有没有上级」这一问用层级本身回答：agent_profile 的
-- ck_agent_parent 逼着二级必有上级、一级必无上级，所以 agent_level=2 恒等于「有上级」。
DROP TRIGGER IF EXISTS `trg_price_tier_precheck`$$
CREATE TRIGGER `trg_price_tier_precheck`
BEFORE INSERT ON `agent_price_tier` FOR EACH ROW
BEGIN
  DECLARE v_qty   INT;
  DECLARE v_mode  VARCHAR(16);
  DECLARE v_fixed BIGINT;
  DECLARE v_rate  INT;
  DECLARE v_comm  BIGINT DEFAULT 0;
  DECLARE v_msg   VARCHAR(255);

  SET v_qty = (SELECT `code_qty` FROM `sku` WHERE `id` = NEW.`sku_id`);
  IF v_qty IS NULL THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '价目挂在一个不存在的套餐上';
  END IF;

  IF NEW.`agent_level` = 2 THEN
    SET v_mode  = (SELECT `calc_mode` FROM `commission_rule`
                    WHERE `buyer_level` = NEW.`agent_level` AND `sku_id` = NEW.`sku_id`
                      AND `effective_to` IS NULL);
    SET v_fixed = (SELECT `fixed_per_code_fen` FROM `commission_rule`
                    WHERE `buyer_level` = NEW.`agent_level` AND `sku_id` = NEW.`sku_id`
                      AND `effective_to` IS NULL);
    SET v_rate  = (SELECT `rate_bp` FROM `commission_rule`
                    WHERE `buyer_level` = NEW.`agent_level` AND `sku_id` = NEW.`sku_id`
                      AND `effective_to` IS NULL);
    IF v_mode = 'FIXED_PER_CODE' THEN
      SET v_comm = v_fixed * v_qty;
    ELSEIF v_mode = 'RATE_OF_PAID' THEN
      SET v_comm = NEW.`total_price_fen` * v_rate DIV 10000;
    END IF;
    IF v_comm < 0 THEN SET v_comm = 0; END IF;
  END IF;

  IF NEW.`total_price_fen` - v_comm < 0 THEN
    SET v_msg = CONCAT('这个层级价会让平台每卖一单倒贴 ',
                       FORMAT((v_comm - NEW.`total_price_fen`) / 100, 2),
                       ' 元：成交 ', FORMAT(NEW.`total_price_fen` / 100, 2),
                       ' 元，返上级佣金 ', FORMAT(v_comm / 100, 2), ' 元');
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = v_msg;
  END IF;
END$$


-- 专属价：净收不得为负。判据与上面 trg_price_tier_precheck 同源（两处一起改）。
--
-- 差别只有一处：这里的层级与「有没有上级」要从 agent_profile 上取，
-- 因为专属价是挂在具体某一家代理上的。
DROP TRIGGER IF EXISTS `trg_price_override_precheck`$$
CREATE TRIGGER `trg_price_override_precheck`
BEFORE INSERT ON `agent_price_override` FOR EACH ROW
BEGIN
  DECLARE v_level  TINYINT;
  DECLARE v_parent BIGINT UNSIGNED;
  DECLARE v_qty    INT;
  DECLARE v_mode   VARCHAR(16);
  DECLARE v_fixed  BIGINT;
  DECLARE v_rate   INT;
  DECLARE v_comm   BIGINT DEFAULT 0;
  DECLARE v_msg    VARCHAR(255);

  SET v_level  = (SELECT `level` FROM `agent_profile` WHERE `id` = NEW.`agent_id`);
  SET v_parent = (SELECT `parent_agent_id` FROM `agent_profile` WHERE `id` = NEW.`agent_id`);
  SET v_qty    = (SELECT `code_qty` FROM `sku` WHERE `id` = NEW.`sku_id`);

  -- 悬空引用必须当场炸。挂在一个不存在的代理上的专属价永远取不到，
  -- 而后台会把它显示成「已生效」—— 运营以为价改了，代理那边还是老价。
  IF v_level IS NULL THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '专属价挂在一个不存在的代理上';
  END IF;
  IF v_qty IS NULL THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '专属价挂在一个不存在的套餐上';
  END IF;

  IF v_parent IS NOT NULL THEN
    SET v_mode  = (SELECT `calc_mode` FROM `commission_rule`
                    WHERE `buyer_level` = v_level AND `sku_id` = NEW.`sku_id`
                      AND `effective_to` IS NULL);
    SET v_fixed = (SELECT `fixed_per_code_fen` FROM `commission_rule`
                    WHERE `buyer_level` = v_level AND `sku_id` = NEW.`sku_id`
                      AND `effective_to` IS NULL);
    SET v_rate  = (SELECT `rate_bp` FROM `commission_rule`
                    WHERE `buyer_level` = v_level AND `sku_id` = NEW.`sku_id`
                      AND `effective_to` IS NULL);
    IF v_mode = 'FIXED_PER_CODE' THEN
      SET v_comm = v_fixed * v_qty;
    ELSEIF v_mode = 'RATE_OF_PAID' THEN
      SET v_comm = NEW.`total_price_fen` * v_rate DIV 10000;
    END IF;
    IF v_comm < 0 THEN SET v_comm = 0; END IF;
  END IF;

  IF NEW.`total_price_fen` - v_comm < 0 THEN
    SET v_msg = CONCAT('这个专属价会让平台每卖一单倒贴 ',
                       FORMAT((v_comm - NEW.`total_price_fen`) / 100, 2),
                       ' 元：成交 ', FORMAT(NEW.`total_price_fen` / 100, 2),
                       ' 元，返上级佣金 ', FORMAT(v_comm / 100, 2), ' 元');
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = v_msg;
  END IF;
END$$


-- 专属价不许原地改。
--
-- 这一条守的是「老订单查得回原价」。UPDATE 旧行改价在库层是完全合法的一次写入：
-- 唯一键不动、CHECK 满意、行数正确，而按这个价成交过的每一张老订单，
-- 再去查「当时是什么价」时查到的都是新价 —— 复盘、对账、纠纷全部拿到一个被改写过的历史，
-- 而且没有任何一条日志说它被改过。
--
-- 允许改的只有一件事：给当前生效行封口（effective_to 由 NULL 变成一个时间）。
-- 已封口的行连区间都不许再动 —— 改历史区间等价于改历史价。
DROP TRIGGER IF EXISTS `trg_price_override_update`$$
CREATE TRIGGER `trg_price_override_update`
BEFORE UPDATE ON `agent_price_override` FOR EACH ROW
BEGIN
  IF NEW.`total_price_fen` <> OLD.`total_price_fen`
     OR NEW.`agent_id` <> OLD.`agent_id`
     OR NEW.`sku_id` <> OLD.`sku_id`
     OR NEW.`effective_from` <> OLD.`effective_from` THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '专属价不许原地改：改价要先给旧行封口、再插新行，否则老订单的原价会被改写';
  END IF;
  IF OLD.`effective_to` IS NOT NULL
     AND (NEW.`effective_to` IS NULL OR NEW.`effective_to` <> OLD.`effective_to`) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '已封口的专属价不许再改生效区间：改区间等价于改历史价';
  END IF;
END$$

DELIMITER ;


-- ---------------------------------------------------------------- 收尾自检
--
-- 迁移「跑完没报错」不等于「都落地了」：上面 CALL 是条件执行，触发器段又是另一套权限
-- （binlog 下应用账号建触发器是 ERROR 1419，而错只打在触发器那几行，前面全都成功）。
-- 所以这里自己点一遍名，少一样就当场 SIGNAL —— 让迁移失败，
-- 而不是让上线后第一个亏本的专属价去发现。

SELECT
  (SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='agent_price_override')
+ (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='biz_order'
      AND COLUMN_NAME='price_override_id')
+ (SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='agent_price_override'
      AND INDEX_NAME='uk_active_override')
+ (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE TABLE_SCHEMA=DATABASE()
      AND CONSTRAINT_NAME IN ('ck_override_positive','ck_order_price_source'))
+ (SELECT COUNT(*) FROM information_schema.TRIGGERS
    WHERE TRIGGER_SCHEMA=DATABASE()
      AND TRIGGER_NAME IN ('trg_price_tier_precheck','trg_price_override_precheck',
                           'trg_price_override_update'))
INTO @landed;

-- 1 表 + 1 列 + 1 唯一键 + 2 CHECK + 3 触发器 = 8
DROP PROCEDURE IF EXISTS `sp_assert_landed`;
DELIMITER $$
CREATE PROCEDURE `sp_assert_landed`()
BEGIN
  IF @landed <> 8 THEN
    SET @msg = CONCAT('迁移未完整落地：应有 8 样，实到 ', @landed,
                      ' —— 触发器段是不是没用 root 跑？');
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = @msg;
  END IF;
END$$
DELIMITER ;
CALL `sp_assert_landed`();
DROP PROCEDURE `sp_assert_landed`;
