-- =============================================================================
--  网课平台 · 数据库 schema
--  MySQL 8.0 / InnoDB / utf8mb4
--
--  设计依据：《网课平台需求理解与落地设计 v2》
--  金额一律用「分」存 BIGINT，禁止使用 DECIMAL 以外的浮点类型参与计算。
--  时间一律 DATETIME(3)，业务时区 Asia/Shanghai，由应用层统一。
--
--  贯穿全库的三条契约：
--   1. 幂等靠唯一键裁决，不靠「先查后写」（先查后写有竞态）。
--   2. 余额/佣金变动一律「先写台账再改余额」，台账带 before/after，
--      使余额恒等于最新一行的 balance_after。
--   3. 价格与佣金规则可改，但订单/佣金记录存快照，改规则不影响历史。
--
--  ⚠ 一条数据库拦不住、必须由应用层守的合同：
--     元→分的换算函数（yuanToFen）遇到三位及以上小数必须【报错】，不得四舍五入。
--     原因：'12.345' 写进 BIGINT 列会被 MySQL 静默截断为 12，STRICT_TRANS_TABLES
--     也不报错，且类型转换发生在 CHECK 与触发器求值【之前】，DB 层没有任何拦截点。
--     少收的钱不会有任何日志。测试用例 F5 专门把这个盲区钉在台面上。
-- =============================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

CREATE DATABASE IF NOT EXISTS `wangke`
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE `wangke`;


-- =============================================================================
--  A. 账号与角色
-- =============================================================================

-- 统一账号表。四类角色共用一张表，权限差异靠 role + 关联档案表。
CREATE TABLE `sys_user` (
  `id`                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `phone`               VARCHAR(20)     NOT NULL              COMMENT '手机号，全局唯一登录标识（G9 实名要求）',
  `password_hash`       VARCHAR(100)    NULL                  COMMENT 'bcrypt；代理商由平台开户时生成初始密码',
  `nickname`            VARCHAR(64)     NULL,
  `role`                ENUM('PLATFORM_ADMIN','AGENT','STUDENT','MENTOR') NOT NULL,
  `status`              ENUM('ACTIVE','FROZEN','DISABLED') NOT NULL DEFAULT 'ACTIVE',
  `real_name`           VARCHAR(64)     NULL                  COMMENT '提现打款须与此一致（§7.4）',
  `id_card_masked`      VARCHAR(32)     NULL,
  `attributed_agent_id` BIGINT UNSIGNED NULL                  COMMENT '归属代理商（§7.5）。first-touch 锁定后不再变更',
  `attributed_at`       DATETIME(3)     NULL,
  `attribution_source`  ENUM('REF_LINK','ACTIVATION_CODE','MANUAL') NULL,
  `must_change_pwd`     TINYINT(1)      NOT NULL DEFAULT 0    COMMENT '平台开户后首次登录强制改密',
  -- JWT 是无状态的，但「改密后旧令牌立刻失效」「后台踢下线」无状态做不到。
  -- 签发时把版本写进 claim，校验时比对；改密/踢人 +1，旧令牌当场作废。
  `token_version`       INT             NOT NULL DEFAULT 0    COMMENT '令牌版本',
  `last_login_at`       DATETIME(3)     NULL,
  `created_at`          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at`          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_phone` (`phone`),
  KEY `idx_role_status` (`role`,`status`),
  KEY `idx_attributed_agent` (`attributed_agent_id`)
) ENGINE=InnoDB COMMENT='统一账号';

-- 归属一经落定不可变更（§7.5 first-touch）。
-- 这条规则的真正用途不只是技术正确，而是止争：代理之间为「这个学员算谁的」
-- 吵架时，库里的记录必须是不可篡改的第一次触达，否则谁改得动谁就赢。
DELIMITER $$
CREATE TRIGGER `trg_user_attribution_ins`
BEFORE INSERT ON `sys_user` FOR EACH ROW
BEGIN
  -- 自己不能是自己的归属代理：代理拿自己的推广链接下单，等于自购返佣，
  -- 分销系统最经典的薅法。只在 UPDATE 上拦不住，注册时就能带进来。
  IF NEW.attributed_agent_id IS NOT NULL
     AND EXISTS (SELECT 1 FROM `agent_profile`
                 WHERE id = NEW.attributed_agent_id AND user_id = NEW.id) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '代理不能把自己设为自己的归属代理（自购返佣）';
  END IF;
END$$

CREATE TRIGGER `trg_user_attribution_immutable`
BEFORE UPDATE ON `sys_user` FOR EACH ROW
BEGIN
  IF OLD.attributed_agent_id IS NOT NULL
     AND NOT (NEW.attributed_agent_id <=> OLD.attributed_agent_id) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '归属代理一经落定不可变更或清空（first-touch）';
  END IF;

  IF NEW.attributed_agent_id IS NOT NULL AND OLD.attributed_agent_id IS NULL THEN
    IF NEW.attributed_at IS NULL THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '落定归属必须同时写入 attributed_at';
    END IF;
    IF NEW.attribution_source IS NULL THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '落定归属必须写明来源（推广链接 / 激活码 / 人工）';
    END IF;
  END IF;

  IF NEW.attributed_agent_id IS NOT NULL
     AND EXISTS (SELECT 1 FROM `agent_profile`
                 WHERE id = NEW.attributed_agent_id AND user_id = NEW.id) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '代理不能把自己设为自己的归属代理（自购返佣）';
  END IF;
END$$
DELIMITER ;


-- 代理商档案。层级硬锁 2 级（§7.6 合规红线：靠数据库约束，不靠流程约束）。
CREATE TABLE `agent_profile` (
  `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`           BIGINT UNSIGNED NOT NULL,
  `agent_code`        VARCHAR(24)     NOT NULL              COMMENT '推广链接 ?ref= 用；不可预测，禁顺序生成',
  `level`             TINYINT         NOT NULL              COMMENT '1=一级 2=二级',
  `parent_agent_id`   BIGINT UNSIGNED NULL                  COMMENT '仅二级有值，且必须指向 level=1（由触发器强制）',
  `company_name`      VARCHAR(128)    NULL                  COMMENT '§7.4 推荐要求个体工商户/企业主体',
  `subject_type`      ENUM('INDIVIDUAL','SOLE_PROPRIETOR','COMPANY') NOT NULL DEFAULT 'INDIVIDUAL',
  `tax_no`            VARCHAR(32)     NULL,
  `contact_name`      VARCHAR(64)     NULL,
  `settle_bank_name`  VARCHAR(128)    NULL,
  `settle_bank_acct`  VARCHAR(64)     NULL                  COMMENT '打款账户，须与 sys_user.real_name/company_name 一致',
  `status`            ENUM('PENDING','ACTIVE','SUSPENDED','TERMINATED') NOT NULL DEFAULT 'PENDING',
  `approved_by`       BIGINT UNSIGNED NULL,
  `approved_at`       DATETIME(3)     NULL,
  `created_at`        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at`        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user` (`user_id`),
  UNIQUE KEY `uk_agent_code` (`agent_code`),
  KEY `idx_parent` (`parent_agent_id`),
  KEY `idx_level_status` (`level`,`status`),
  CONSTRAINT `ck_agent_level`  CHECK (`level` IN (1,2)),
  -- 一级不得有上级，二级必须有上级。这条挡住「一级挂到别人下面」形成三级链。
  CONSTRAINT `ck_agent_parent` CHECK (
    (`level` = 1 AND `parent_agent_id` IS NULL) OR
    (`level` = 2 AND `parent_agent_id` IS NOT NULL)
  )
) ENGINE=InnoDB COMMENT='代理商档案';

-- CHECK 约束无法跨行校验「上级必须是一级」，用触发器补上。
-- 这是 §7.6「层级在系统里硬锁 2 级」的第二道闸。
DELIMITER $$
-- 用 NOT EXISTS 而不是 (SELECT level ...) <> 1：
-- 上级不存在时子查询返回 NULL，NULL <> 1 求值为 NULL 而非 TRUE，IF 不会触发，
-- 悬空的 parent_agent_id 会被直接放行。测试用例 A8 就是踩的这个坑。
CREATE TRIGGER `trg_agent_parent_must_be_l1_ins`
BEFORE INSERT ON `agent_profile` FOR EACH ROW
BEGIN
  -- 代理档案必须挂在 AGENT 角色的账号上。给学员号建代理档案，等于让一个能
  -- 看课的号同时能进货、能拿佣金、能提现，权限模型从这里开始就是错的。
  IF NOT EXISTS (SELECT 1 FROM `sys_user` WHERE id = NEW.user_id AND `role` = 'AGENT') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '代理商档案只能挂在 AGENT 角色的账号上';
  END IF;
  IF NEW.parent_agent_id IS NOT NULL
     AND NOT EXISTS (SELECT 1 FROM `agent_profile`
                     WHERE id = NEW.parent_agent_id AND `level` = 1) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '上级代理必须是存在且为一级的代理：分销层级硬锁 2 级';
  END IF;
END$$

CREATE TRIGGER `trg_agent_parent_must_be_l1_upd`
BEFORE UPDATE ON `agent_profile` FOR EACH ROW
BEGIN
  IF NEW.parent_agent_id IS NOT NULL
     AND NOT EXISTS (SELECT 1 FROM `agent_profile`
                     WHERE id = NEW.parent_agent_id AND `level` = 1) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '上级代理必须是存在且为一级的代理：分销层级硬锁 2 级';
  END IF;
  -- 自己挂自己也是非法的
  IF NEW.parent_agent_id IS NOT NULL AND NEW.parent_agent_id = NEW.id THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '代理不能挂在自己名下';
  END IF;
  -- 一级代理若被降级为二级，其已有下级会形成三级链，直接拒绝。
  IF NEW.`level` = 2 AND OLD.`level` = 1
     AND EXISTS (SELECT 1 FROM `agent_profile` WHERE parent_agent_id = OLD.id) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '该一级代理已有下级，不能降级为二级';
  END IF;
END$$
DELIMITER ;


-- 二级代理推荐与审批留痕（G17）。开户仍由平台执行（录音 00:06），一级只负责推荐。
CREATE TABLE `agent_invite` (
  `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `inviter_agent_id`  BIGINT UNSIGNED NOT NULL              COMMENT '推荐方，必须是一级',
  `phone`             VARCHAR(20)     NOT NULL,
  `contact_name`      VARCHAR(64)     NULL,
  `remark`            VARCHAR(255)    NULL,
  `status`            ENUM('SUBMITTED','APPROVED','REJECTED') NOT NULL DEFAULT 'SUBMITTED',
  `reject_reason`     VARCHAR(255)    NULL,
  `created_agent_id`  BIGINT UNSIGNED NULL                  COMMENT '审批通过后生成的代理商 id',
  `reviewed_by`       BIGINT UNSIGNED NULL,
  `reviewed_at`       DATETIME(3)     NULL,
  `created_at`        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_inviter` (`inviter_agent_id`,`status`),
  KEY `idx_phone` (`phone`)
) ENGINE=InnoDB COMMENT='二级代理推荐申请';


-- =============================================================================
--  B. 商品、价目与佣金规则
-- =============================================================================

CREATE TABLE `sku` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `sku_code`      VARCHAR(32)     NOT NULL              COMMENT 'AGENT_1 / AGENT_10 / STUDENT_DIRECT',
  `name`          VARCHAR(64)     NOT NULL,
  `buyer_type`    ENUM('AGENT','STUDENT') NOT NULL,
  `course_id`     BIGINT UNSIGNED NOT NULL,
  `code_qty`      INT             NOT NULL              COMMENT '本 SKU 出多少个激活码',
  `status`        ENUM('ON','OFF') NOT NULL DEFAULT 'ON',
  `created_at`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sku_code` (`sku_code`)
) ENGINE=InnoDB COMMENT='售卖套餐';


-- 层级 × 套餐 → 单价。带生效区间，改价不影响历史订单（订单存快照）。
CREATE TABLE `agent_price_tier` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `agent_level`     TINYINT         NOT NULL              COMMENT '1 / 2；学员直购用 0',
  `sku_id`          BIGINT UNSIGNED NOT NULL,
  `total_price_fen` BIGINT          NOT NULL              COMMENT '整单价格（分）。399 元 = 39900',
  `effective_from`  DATETIME(3)     NOT NULL,
  `effective_to`    DATETIME(3)     NULL                  COMMENT 'NULL = 当前生效',
  -- 只在「当前生效」时取值 1，封口后变 NULL。NULL 不参与唯一性判定，
  -- 于是每个 (层级, 套餐) 最多只能有一行当前生效 —— 取价不会二义。
  -- 改价的正确姿势：先把旧行 effective_to 封口，再插新行。
  `active_flag`     TINYINT GENERATED ALWAYS AS (IF(`effective_to` IS NULL, 1, NULL)) STORED,
  `created_by`      BIGINT UNSIGNED NOT NULL,
  `created_at`      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_active_price` (`agent_level`,`sku_id`,`active_flag`),
  KEY `idx_lookup` (`agent_level`,`sku_id`,`effective_from`),
  CONSTRAINT `ck_price_positive` CHECK (`total_price_fen` > 0)
) ENGINE=InnoDB COMMENT='层级价目表';


-- 代理专属价：某一家谈过价的代理单独一份价目。没有专属价的代理用上面的层级价。
--
-- 为什么是一张新表而不是给 agent_price_tier 加一列 agent_id：加列之后
-- uk_active_price 必须扩成四列，而那一刻「层级价」这一档（agent_id IS NULL）就退出
-- 唯一性判定了 —— 多个 NULL 不冲突，同一个 (层级,套餐) 可以有任意多条当前生效的层级价，
-- 取价当场二义。完整理由见 db/migrations/20260901_代理专属价.sql 的文件头。
--
-- 历史模式逐条照抄 agent_price_tier：改价 = 给旧行封口 + 插新行，**绝不 UPDATE 旧行**
-- （trg_price_override_update 把这条做成物理的）；active_flag 生成列让封口后的历史行
-- 想留多少留多少，而每个 (代理,套餐) 至多一条当前生效。
CREATE TABLE `agent_price_override` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `agent_id`        BIGINT UNSIGNED NOT NULL              COMMENT '这份价只对这一家生效',
  `sku_id`          BIGINT UNSIGNED NOT NULL,
  `total_price_fen` BIGINT          NOT NULL              COMMENT '整单价格（分）。349 元 = 34900',
  `effective_from`  DATETIME(3)     NOT NULL,
  `effective_to`    DATETIME(3)     NULL                  COMMENT 'NULL = 当前生效；封口即回落层级价',
  `active_flag`     TINYINT GENERATED ALWAYS AS (IF(`effective_to` IS NULL, 1, NULL)) STORED,
  -- NOT NULL：事后有人问「这家凭什么比别人便宜 50 块」，答案得在这一行上，
  -- 不能只在审计里 —— 审计是业务事务之外写的，写失败时价目行还在。
  `reason`          VARCHAR(255)    NOT NULL              COMMENT '为什么给这家单独定价，必填',
  `created_by`      BIGINT UNSIGNED NOT NULL,
  `created_at`      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_active_override` (`agent_id`,`sku_id`,`active_flag`),
  KEY `idx_override_lookup` (`agent_id`,`sku_id`,`effective_from`),
  CONSTRAINT `ck_override_positive` CHECK (`total_price_fen` > 0)
) ENGINE=InnoDB COMMENT='代理专属价（层级价的单家覆盖）';


-- 返佣规则：二级代理下单时，其一级上级拿多少。
-- 按「实际支付金额」计算，不按标价（§7.2 实现约束 2）。
CREATE TABLE `commission_rule` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `buyer_level`      TINYINT         NOT NULL              COMMENT '下单方层级；2 = 二级代理下单，返给其一级',
  `sku_id`           BIGINT UNSIGNED NOT NULL,
  `calc_mode`        ENUM('FIXED_PER_CODE','RATE_OF_PAID') NOT NULL,
  `fixed_per_code_fen` BIGINT        NULL                  COMMENT 'calc_mode=FIXED_PER_CODE 时用，如 51 元 = 5100',
  `rate_bp`          INT             NULL                  COMMENT 'calc_mode=RATE_OF_PAID 时用，基点。1000 = 10%',
  `freeze_days`      INT             NOT NULL DEFAULT 15   COMMENT '冻结期（§7.3）。必须覆盖退款窗口',
  `effective_from`   DATETIME(3)     NOT NULL,
  `effective_to`     DATETIME(3)     NULL,
  `active_flag`      TINYINT GENERATED ALWAYS AS (IF(`effective_to` IS NULL, 1, NULL)) STORED
                     COMMENT '同 agent_price_tier：保证每个 (下单层级, 套餐) 只有一条当前生效规则',
  `created_by`       BIGINT UNSIGNED NOT NULL,
  `created_at`       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_active_rule` (`buyer_level`,`sku_id`,`active_flag`),
  KEY `idx_lookup` (`buyer_level`,`sku_id`,`effective_from`),
  CONSTRAINT `ck_commission_mode` CHECK (
    (`calc_mode` = 'FIXED_PER_CODE' AND `fixed_per_code_fen` IS NOT NULL) OR
    (`calc_mode` = 'RATE_OF_PAID'   AND `rate_bp` IS NOT NULL)
  )
) ENGINE=InnoDB COMMENT='返佣规则';


-- 定价的那条会漏钱的判据：**平台净收不得为负**。
--
-- 二级下单时平台净收 = 二级成交价 − 返给一级的佣金。价目一旦设到佣金以下，
-- 这个净收就是负数：平台每卖一单倒贴钱。而这条错的形态是全链路合法的 ——
-- 订单金额 > 0、佣金是正数且冻结期满自动进 agent_balance、对账表与总览每一处单看都平，
-- 只有把「本月收到的钱」减去「本月付出的佣金」才看得见那个缺口，而那是月底的事。
-- 到那时佣金已与真佣金不可分辨，撤回路径只剩「找代理把钱要回来」。
--
-- 两个触发器的判据**逐字同源**，要改一起改：只改一处的表现是「专属价改不了、
-- 但层级价能改成同样的亏本数字」，而层级价坑的是整整一层代理，比专属价更贵。
--
-- 净收口径对齐 FulfillService.settleCommission：只有「买家是二级且有上级且规则表配了」
-- 才真的产生佣金；佣金算出来 <= 0 时那边直接不插行，所以这里也把负数钳到 0 ——
-- 否则一条负佣金的规则会在这里被算成「平台还赚了」，而实际一分钱都不会返出去。
DELIMITER $$

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

  -- 层级不带 agent_id，「有没有上级」这一问用层级本身回答：ck_agent_parent 逼着
  -- 二级必有上级、一级必无上级，所以 agent_level=2 恒等于「有上级」。
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


-- 同上，只是层级与「有没有上级」要从 agent_profile 上取（专属价挂在具体某一家上）。
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

  -- 悬空引用必须当场炸：挂在不存在的代理上的专属价永远取不到，
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


-- 专属价不许原地改 —— 这一条守的是「老订单查得回原价」。
--
-- UPDATE 旧行改价在库层是完全合法的一次写入：唯一键不动、CHECK 满意、行数正确，
-- 而按这个价成交过的每一张老订单，再去查「当时是什么价」时查到的都是新价。
-- 复盘、对账、纠纷全部拿到一个被改写过的历史，且没有任何一条日志说它被改过。
--
-- 允许改的只有一件事：给当前生效行封口（effective_to 由 NULL 变成一个时间）。
-- 已封口的行连区间都不许再动 —— 改历史区间等价于改历史价。
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


-- =============================================================================
--  C. 订单与支付
-- =============================================================================

-- order 是保留字，表名用 biz_order。
CREATE TABLE `biz_order` (
  `id`                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `order_no`            VARCHAR(32)     NOT NULL              COMMENT '业务单号，对外可见',
  `buyer_user_id`       BIGINT UNSIGNED NOT NULL,
  `buyer_type`          ENUM('AGENT','STUDENT') NOT NULL,
  `buyer_agent_id`      BIGINT UNSIGNED NULL                  COMMENT 'buyer_type=AGENT 时有值',
  `buyer_agent_level`   TINYINT         NULL,
  `sku_id`              BIGINT UNSIGNED NOT NULL,
  `sku_code_snapshot`   VARCHAR(32)     NOT NULL,
  `code_qty_snapshot`   INT             NOT NULL              COMMENT '快照：本单应出几个码',
  `price_tier_id`       BIGINT UNSIGNED NULL                  COMMENT '取价来源，便于事后复盘',
  -- 按代理专属价成交时的取价来源。**不能塞进 price_tier_id** —— 两张表的主键会撞：
  -- 17 号既可能是层级价也可能是专属价，而两行的金额不同。复盘时按 price_tier_id 去
  -- agent_price_tier 一查，查到的是一个与 amount_fen 对不上的数，看起来像数据损坏。
  `price_override_id`   BIGINT UNSIGNED NULL                  COMMENT '按专属价成交时的取价来源（agent_price_override.id）；与 price_tier_id 至多一个非空',
  `amount_fen`          BIGINT          NOT NULL              COMMENT '应付金额快照',
  `paid_amount_fen`     BIGINT          NOT NULL DEFAULT 0    COMMENT '实付，用于佣金计算',
  `refunded_amount_fen` BIGINT          NOT NULL DEFAULT 0,
  `attributed_agent_id` BIGINT UNSIGNED NULL                  COMMENT '学员直购时的归属代理（§7.5）',
  `status`              ENUM('CREATED','PAID','FULFILLED','CLOSED','REFUNDED','PARTIAL_REFUNDED')
                        NOT NULL DEFAULT 'CREATED',
  -- 新渠道一律【追加在末尾】：ENUM 在行里存的是下标，把 ALIPAY_* 插到 BANK_TRANSFER
  -- 前面会让存量对公转账流水被静默重解释成支付宝 —— 不报错，只有对账时数字对不上。
  `pay_channel`         ENUM('WECHAT_NATIVE','WECHAT_JSAPI','BANK_TRANSFER',
                             'ALIPAY_PAGE','ALIPAY_WAP') NULL,
  `paid_at`             DATETIME(3)     NULL,
  `fulfilled_at`        DATETIME(3)     NULL,
  `expire_at`           DATETIME(3)     NOT NULL              COMMENT '未支付自动 CLOSED',
  `client_ip`           VARCHAR(45)     NULL,
  -- 这张单是怎么来的。ADMIN_GIFT = 超管人工发给代理的赠送/补发码，它不收钱：
  -- amount_fen 记目录价（ck_order_amount 逼着 > 0，也正好当成「送出去多少钱的货」的账），
  -- 而 paid_amount_fen 恒 0、paid_at 恒 NULL、不产生佣金、不进营收统计。
  -- 新增枚举值一律【追加在末尾】，理由同 pay_channel 那一条（存的是下标）。
  `origin`              ENUM('SELF_SERVE','ADMIN_GIFT') NOT NULL DEFAULT 'SELF_SERVE'
                                                              COMMENT '这张单是怎么来的：自助下单 / 平台手工发放',
  `grant_by`            BIGINT UNSIGNED NULL                  COMMENT '手工发放的操作人（sys_user.id）',
  `grant_reason`        VARCHAR(255)    NULL                  COMMENT '手工发放的原因，必填，事后复盘只有这一句',
  -- 一次发放意图的幂等键。后台按钮会被双击，而「双击 = 两批码发出去」在界面上
  -- 两次都显示成功，事后没有任何东西能判定它们是同一次意图。
  `grant_key`           VARCHAR(64)     NULL                  COMMENT '一次发放意图的幂等键；自助单为 NULL',
  `created_at`          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at`          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  -- 多个 NULL 不参与唯一性判定，所以自助单（grant_key IS NULL）完全不受影响。
  -- 做成「自助单只能有一张」的话，全站下单从第二笔起全挂 —— 很吵，但要有正例钉住
  -- （test_schema.py AF11），否则会在上线那一刻才发现。
  UNIQUE KEY `uk_grant_key` (`grant_key`),
  KEY `idx_buyer` (`buyer_user_id`,`created_at`),
  KEY `idx_agent_month` (`buyer_agent_id`,`paid_at`)      COMMENT '按代理按月统计（录音 01:06）走这条',
  KEY `idx_attributed` (`attributed_agent_id`,`paid_at`),
  KEY `idx_status_expire` (`status`,`expire_at`),
  -- 钱的勾稽：实付不得超过应付，退款不得超过实付。挡住「退出比收到还多」。
  CONSTRAINT `ck_order_amount` CHECK (
    `amount_fen` > 0
    AND `paid_amount_fen`     >= 0 AND `paid_amount_fen`     <= `amount_fen`
    AND `refunded_amount_fen` >= 0 AND `refunded_amount_fen` <= `paid_amount_fen`
  ),
  -- 取价来源只能有一个。两个指针都写上的话，复盘按哪一个取都算「有依据」，
  -- 而它们指向的两个金额不同 —— 对账时同一张单会得出两个不同的「当时的价」。
  CONSTRAINT `ck_order_price_source` CHECK (
    `price_tier_id` IS NULL OR `price_override_id` IS NULL
  ),
  -- 白送不可能变成收入。CHECK 在 UPDATE 时对整行求值，所以「先建白送单、
  -- 事后偷偷标已付把它洗成收入」这条路两侧一起堵死。
  CONSTRAINT `ck_order_gift_unpaid` CHECK (
    `origin` <> 'ADMIN_GIFT' OR (`paid_amount_fen` = 0 AND `paid_at` IS NULL)
  ),
  -- 发放签名与 origin 必须一致，【两个方向都要】。
  --
  -- 正向：手工单必须记下操作人、原因、幂等键，一样都不能少 —— 事后有人问「这批码是谁发的、
  -- 为什么发」，答案得落在订单行上。审计是业务事务之外写的，写失败时订单还在。
  --
  -- 反向：自助单不许带发放签名。写成 `origin='SELF_SERVE' OR (...)` 的话，
  -- 只要 origin 是自助，整条约束就短路了 —— 一张真实付款的订单可以带着完整的
  -- 「平台发放」签名落库。钱没错，但台账上的来源是假的；更实在的是 grant_key 上有
  -- 唯一键，被一张自助单占住之后，将来那次手工发码的幂等键就被抢注了，
  -- 表现是发码报 500 而不是幂等返回。
  CONSTRAINT `ck_order_grant_by` CHECK (
    (`origin` =  'SELF_SERVE'
       AND `grant_by` IS NULL AND `grant_reason` IS NULL AND `grant_key` IS NULL)
    OR
    (`origin` <> 'SELF_SERVE'
       AND `grant_by` IS NOT NULL AND `grant_reason` IS NOT NULL AND `grant_key` IS NOT NULL)
  )
) ENGINE=InnoDB COMMENT='订单';

-- 订单状态机。合法迁移：
--   CREATED → PAID → FULFILLED
--   CREATED → CLOSED（超时未付）
--   PAID/FULFILLED → PARTIAL_REFUNDED / REFUNDED
-- CLOSED 与 REFUNDED 是终态。任何形式的回退都拒绝 —— 已发货退回待支付、
-- 关单后又标记已付、已退款又改回已支付，每一条都是对账噩梦。
DELIMITER $$
CREATE TRIGGER `trg_order_insert`
BEFORE INSERT ON `biz_order` FOR EACH ROW
BEGIN
  IF NEW.expire_at <= NOW(3) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '订单过期时间必须晚于当前时间';
  END IF;
  -- 已暂停 / 已终止的代理商不能再进货
  IF NEW.buyer_agent_id IS NOT NULL
     AND NOT EXISTS (SELECT 1 FROM `agent_profile`
                     WHERE id = NEW.buyer_agent_id AND status = 'ACTIVE') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '代理商不在有效状态，不能下单';
  END IF;

  -- 下面两条都以 NEW.origin <> 'SELF_SERVE' 为前提，所以自助下单这条生产路径
  -- 与全部既有测试夹具（不写 origin → 走列默认）零连带。

  -- 白送单必须一步到位落在「已发码」。允许它以 CREATED 建出来的话，界面上它是一张
  -- 待支付订单 —— 代理会看见一个「去付款」按钮，付完就成了一笔真实收入，
  -- 而它本来是白送的。
  IF NEW.origin = 'ADMIN_GIFT' AND NEW.status <> 'FULFILLED' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '平台手工发放的订单必须直接落到已发码状态';
  END IF;

  -- 手工单没有支付窗口这回事。留一个长窗口的话，它会混进「待支付订单」的
  -- 各种扫描与提醒里，而它永远不会被支付。
  IF NEW.origin <> 'SELF_SERVE'
     AND NEW.expire_at > DATE_ADD(NOW(3), INTERVAL 10 MINUTE) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '手工发放的订单不需要支付窗口，expire_at 不得超过 10 分钟';
  END IF;
END$$

CREATE TRIGGER `trg_order_state`
BEFORE UPDATE ON `biz_order` FOR EACH ROW
BEGIN
  IF OLD.status IN ('CLOSED','REFUNDED') AND NEW.status <> OLD.status THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '订单已处于终态（已关闭 / 已全额退款），状态不可再变更';
  END IF;

  IF OLD.status = 'FULFILLED' AND NEW.status IN ('CREATED','PAID') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '已发码的订单不可退回待支付或已支付';
  END IF;

  IF OLD.status = 'PAID' AND NEW.status = 'CREATED' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '已支付订单不可退回待支付';
  END IF;

  IF NEW.status = 'PAID' AND OLD.status NOT IN ('CREATED','PAID') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '只有待支付订单能转为已支付';
  END IF;

  -- 支付必须留痕：没有 paid_at 和实付金额的「已支付」无法对账
  IF NEW.status = 'PAID' AND OLD.status = 'CREATED' THEN
    IF NEW.paid_at IS NULL THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '标记已支付必须同时写入 paid_at';
    END IF;
    IF NEW.paid_amount_fen <= 0 THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '标记已支付必须同时写入实付金额';
    END IF;
  END IF;

  -- 没收到钱的订单不存在退款
  IF NEW.status IN ('REFUNDED','PARTIAL_REFUNDED')
     AND OLD.status NOT IN ('PAID','FULFILLED','PARTIAL_REFUNDED') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '未支付的订单不能退款';
  END IF;
END$$
DELIMITER ;


-- 支付流水。provider_txn_id 唯一键：同一笔微信交易物理上只能入账一次。
-- 回调 / 前端轮询查单 / 定时对账三条路都调同一个 confirmPaid，靠这个唯一键裁决幂等。
CREATE TABLE `payment_record` (
  `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `order_id`          BIGINT UNSIGNED NOT NULL,
  `order_no`          VARCHAR(32)     NOT NULL,
  `provider`          ENUM('WECHAT','BANK_TRANSFER','ALIPAY') NOT NULL,
  `provider_txn_id`   VARCHAR(64)     NOT NULL              COMMENT '支付宝 trade_no；微信 transaction_id；对公转账用「流水号」',
  `amount_fen`        BIGINT          NOT NULL              COMMENT '必须与订单金额逐笔核对，挡改价与串单',
  `status`            ENUM('SUCCESS','FAILED') NOT NULL,
  `raw_payload`       JSON            NULL,
  `confirmed_by`      BIGINT UNSIGNED NULL                  COMMENT '对公转账人工确认人',
  `paid_at`           DATETIME(3)     NOT NULL,
  `created_at`        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_provider_txn` (`provider`,`provider_txn_id`),
  KEY `idx_order` (`order_id`),
  CONSTRAINT `ck_payment_amount` CHECK (`amount_fen` > 0)
) ENGINE=InnoDB COMMENT='支付流水';

-- 白送的订单不能有支付流水。
--
-- ★ 这一条堵的是一条【真通路】，不是防御性代码。真跑一遍是这样：
--   confirmTransfer 打在一张白送单上 → FulfillService.doConfirm 先插 payment_record
--   （成功，一笔假流水就此落库，还吃掉一个 uk_provider_txn 名额）→ 再
--   UPDATE ... WHERE id=? AND status='CREATED' 影响 0 行（白送单是 FULFILLED），
--   于是 ck_order_gift_unpaid 根本没被求值 → issueCodes 算出 need=0 →
--   UPDATE status='FULFILLED' 在 OLD='FULFILLED' 上无规则命中 → settleCommission
--   只有在「买家是二级且有上级且规则表配了」时才会被 trg_commission_precheck 拦下回滚；
--   否则【整个事务提交】。
--
--   结果：一张 paid_at 仍为 NULL 的白送单上挂着一条 SUCCESS 的假流水，
--   总览四项纹丝不动（它们按 paid_at 圈窗口），运营看到的是
--   「已确认到账，本单已出 0 个激活码」。只有拿银行流水逐笔对时才发现。
--
-- 应用层在 confirmTransfer 里也挡了一道（先看 origin 再入账），两道互补：
-- 那道给人话，这道保证绕不过去。
DELIMITER $$
CREATE TRIGGER `trg_payment_precheck`
BEFORE INSERT ON `payment_record` FOR EACH ROW
BEGIN
  IF EXISTS (SELECT 1 FROM `biz_order`
              WHERE id = NEW.order_id AND `origin` = 'ADMIN_GIFT') THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '平台白送的订单不能有支付流水：这张单从来没有收到过钱';
  END IF;
END$$
DELIMITER ;


CREATE TABLE `refund_record` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `refund_no`        VARCHAR(32)     NOT NULL,
  `order_id`         BIGINT UNSIGNED NOT NULL,
  `amount_fen`       BIGINT          NOT NULL,
  `reason`           VARCHAR(255)    NULL,
  `scope`            ENUM('UNUSED_CODES','FULL','MANUAL') NOT NULL
                     COMMENT 'UNUSED_CODES = 只退未激活的码（G8）',
  `provider_refund_id` VARCHAR(64)   NULL,
  `status`           ENUM('APPLIED','APPROVED','SUCCESS','FAILED','REJECTED') NOT NULL DEFAULT 'APPLIED',
  `applied_by`       BIGINT UNSIGNED NOT NULL,
  `approved_by`      BIGINT UNSIGNED NULL,
  `finished_at`      DATETIME(3)     NULL,
  `created_at`       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_refund_no` (`refund_no`),
  UNIQUE KEY `uk_provider_refund` (`provider_refund_id`),
  KEY `idx_order` (`order_id`),
  CONSTRAINT `ck_refund_amount` CHECK (`amount_fen` > 0)
) ENGINE=InnoDB COMMENT='退款记录';

-- 「激活码一旦激活，概不退费，一律不退」（录音 01:50）在钱这一侧的落点。
-- 激活码状态机守住了码不能退回未激活，但那只挡住了货；这里挡住的是钱 ——
-- 一张已激活的码所在的订单，仍然可以被整单退款，货照看、钱退回。
-- 两条一起才算这条业务铁律真的落地了。
DELIMITER $$
CREATE TRIGGER `trg_refund_precheck`
BEFORE INSERT ON `refund_record` FOR EACH ROW
BEGIN
  DECLARE paid   BIGINT DEFAULT NULL;
  DECLARE done   BIGINT DEFAULT 0;
  DECLARE bound  INT    DEFAULT 0;

  SELECT `paid_amount_fen` INTO paid FROM `biz_order` WHERE id = NEW.order_id;
  IF paid IS NULL THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '订单不存在，不能退款';
  END IF;
  IF paid <= 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '订单没有实付金额，不存在退款';
  END IF;

  SELECT COUNT(*) INTO bound FROM `activation_code`
   WHERE order_id = NEW.order_id AND status = 'BOUND';
  IF NEW.scope = 'FULL' AND bound > 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '该订单已有激活码被激活，不得整单退款（激活即不退），只能退未激活的码';
  END IF;

  -- 在途退款也占额度：三张各自不超实付的退款单，合计可以退出两倍。
  -- 与提现的在途占用是同一类错，一并按同样口径守。
  SELECT COALESCE(SUM(`amount_fen`),0) INTO done FROM `refund_record`
   WHERE order_id = NEW.order_id AND status IN ('APPLIED','APPROVED','SUCCESS');
  IF NEW.amount_fen > paid - done THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '退款额超过订单实付余额（含在途退款单）';
  END IF;
END$$

-- 退款单状态机。合法迁移：APPLIED → APPROVED → SUCCESS / FAILED
--                        APPLIED → REJECTED
-- SUCCESS / REJECTED 是终态。已成功的退款退回待申请等于可以再退一次。
CREATE TRIGGER `trg_refund_state`
BEFORE UPDATE ON `refund_record` FOR EACH ROW
BEGIN
  IF OLD.status IN ('SUCCESS','REJECTED') AND NEW.status <> OLD.status THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '退款单已终结，状态不可再变更';
  END IF;
  IF NEW.status = 'APPROVED' AND OLD.status NOT IN ('APPLIED','APPROVED') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '只有待审批的退款单能转为审批通过';
  END IF;
  IF NEW.status = 'SUCCESS' AND OLD.status NOT IN ('APPROVED','FAILED') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '退款打款前必须先经过审批，不可跳步';
  END IF;
  IF NEW.status = 'REJECTED' AND OLD.status <> 'APPLIED' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '只有待审批的退款单能被驳回';
  END IF;
END$$
DELIMITER ;


-- =============================================================================
--  D. 资金台账
--  契约：先写台账再改余额，台账带 before/after，余额恒等于最新一行 balance_after。
--  幂等靠 uk_biz_ref，不靠「先查 status 再改」。
-- =============================================================================

CREATE TABLE `account_ledger` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `owner_type`      ENUM('AGENT','PLATFORM') NOT NULL,
  `owner_id`        BIGINT UNSIGNED NOT NULL,
  `biz_type`        VARCHAR(32)     NOT NULL              COMMENT 'ORDER_PAY / REFUND / ADJUST …',
  `ref_no`          VARCHAR(64)     NOT NULL              COMMENT '业务单号，与 biz_type 组成幂等键',
  `direction`       ENUM('IN','OUT') NOT NULL,
  `amount_fen`      BIGINT          NOT NULL,
  `balance_before`  BIGINT          NOT NULL,
  `balance_after`   BIGINT          NOT NULL,
  `remark`          VARCHAR(255)    NULL,
  `operator_id`     BIGINT UNSIGNED NULL,
  `created_at`      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_biz_ref` (`biz_type`,`ref_no`),
  KEY `idx_owner` (`owner_type`,`owner_id`,`id`),
  CONSTRAINT `ck_ledger_amount` CHECK (`amount_fen` > 0),
  -- 单行自洽：after 必须等于 before ± amount，方向要对得上。
  -- 「余额恒等于最新一行 balance_after」这条契约，前提是每一行自己算得对；
  -- 只保证链条不断、不保证单行正确的话，一行写错就把余额永久带偏。
  CONSTRAINT `ck_ledger_arith` CHECK (
    (`direction` = 'IN'  AND `balance_after` = `balance_before` + `amount_fen`) OR
    (`direction` = 'OUT' AND `balance_after` = `balance_before` - `amount_fen`)
  )
) ENGINE=InnoDB COMMENT='资金台账（余额变动唯一收口点）';


-- =============================================================================
--  E. 分销佣金（§7）
-- =============================================================================

CREATE TABLE `commission_record` (
  `id`                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `beneficiary_agent_id` BIGINT UNSIGNED NOT NULL           COMMENT '拿佣金的代理（一级）',
  `source_agent_id`     BIGINT UNSIGNED NULL                COMMENT '产生佣金的下级代理（二级）',
  `order_id`            BIGINT UNSIGNED NOT NULL,
  `order_no`            VARCHAR(32)     NOT NULL,
  `rule_id`             BIGINT UNSIGNED NOT NULL,
  `calc_mode_snapshot`  VARCHAR(24)     NOT NULL            COMMENT '快照：改规则不影响已产生的佣金',
  `calc_basis_fen`      BIGINT          NOT NULL            COMMENT '计算基数 = 订单实付金额',
  `amount_fen`          BIGINT          NOT NULL,
  `reversed_fen`        BIGINT          NOT NULL DEFAULT 0 COMMENT '退款累计冲正佣金，结算只入净额',
  `status`              ENUM('PENDING','SETTLED','WITHDRAWING','WITHDRAWN','REVOKED')
                        NOT NULL DEFAULT 'PENDING',
  `freeze_until`        DATETIME(3)     NOT NULL            COMMENT '冻结到期时间，期满转 SETTLED',
  `settled_at`          DATETIME(3)     NULL,
  `withdraw_order_id`   BIGINT UNSIGNED NULL,
  `revoked_at`          DATETIME(3)     NULL,
  `revoke_reason`       VARCHAR(255)    NULL,
  `created_at`          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at`          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  -- 一个订单对同一受益人只能产生一条佣金：重复结算的物理防线
  UNIQUE KEY `uk_order_beneficiary` (`order_id`,`beneficiary_agent_id`),
  KEY `idx_beneficiary_status` (`beneficiary_agent_id`,`status`),
  KEY `idx_freeze_scan` (`status`,`freeze_until`)          COMMENT '定时任务扫 PENDING 到期转 SETTLED',
  CONSTRAINT `ck_commission_amount` CHECK (`amount_fen` > 0)
  ,CONSTRAINT `ck_commission_reversed` CHECK (`reversed_fen` >= 0 AND `reversed_fen` <= `amount_fen`)
) ENGINE=InnoDB COMMENT='佣金记录';

-- 白送的订单不产生佣金 —— 库层保证，不是代码纪律。
--
-- 「settleCommission 只有 FulfillService 一个调用点、手工发码那条路不经过它」是今天的
-- 代码事实，下一个人加一行就没了。而这条错一旦发生是【不可逆】的：那笔佣金冻结期满后
-- 由 CommissionSettleService 自动进 agent_balance，此后与真佣金不可分辨；
-- 而白送单 paid_amount_fen=0 连 refund_record 都插不进去（trg_refund_precheck 要求 paid > 0），
-- 撤回路径为零。
DELIMITER $$
CREATE TRIGGER `trg_commission_precheck`
BEFORE INSERT ON `commission_record` FOR EACH ROW
BEGIN
  IF EXISTS (SELECT 1 FROM `biz_order`
              WHERE id = NEW.order_id AND `origin` = 'ADMIN_GIFT') THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '平台白送的订单不产生佣金：没有收到的钱不能返出去';
  END IF;
END$$
DELIMITER ;

-- 佣金状态机（§7.3）。合法迁移：
--   PENDING →(冻结期满) SETTLED → WITHDRAWING → WITHDRAWN
--   任意态 →(订单退款) REVOKED，REVOKED 为终态
-- 两条钱的红线：①冻结期未满不得转可提现（否则退款时钱已被提走）；
--              ②已提现不得退回可提现（等于凭空多出一笔可提金额）。
DELIMITER $$
CREATE TRIGGER `trg_commission_state`
BEFORE UPDATE ON `commission_record` FOR EACH ROW
BEGIN
  IF OLD.status = 'REVOKED' AND NEW.status <> 'REVOKED' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '已撤销的佣金为终态，不可复活';
  END IF;

  IF OLD.status = 'WITHDRAWN' AND NEW.status NOT IN ('WITHDRAWN','REVOKED') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '已提现的佣金不可退回，只能被退款撤销';
  END IF;

  IF NEW.status = 'SETTLED' AND OLD.status = 'PENDING' AND NEW.freeze_until > NOW(3) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '冻结期未满，佣金不能转为可提现';
  END IF;

  IF NEW.status = 'SETTLED' AND OLD.status NOT IN ('PENDING','SETTLED') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '只有冻结中的佣金能转为可提现';
  END IF;

  IF NEW.status = 'WITHDRAWING' AND OLD.status NOT IN ('SETTLED','WITHDRAWING') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '只有可提现的佣金能进入提现中';
  END IF;

  IF NEW.status = 'WITHDRAWN' AND OLD.status NOT IN ('WITHDRAWING','WITHDRAWN') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '只有提现中的佣金能转为已提现';
  END IF;
END$$
DELIMITER ;


-- 佣金余额台账，与 account_ledger 同契约。允许负余额（退款冲正已提现佣金，§7.3）。
CREATE TABLE `commission_ledger` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `agent_id`        BIGINT UNSIGNED NOT NULL,
  `biz_type`        VARCHAR(32)     NOT NULL              COMMENT 'SETTLE / WITHDRAW / REVOKE / OFFSET',
  `ref_no`          VARCHAR(64)     NOT NULL,
  `direction`       ENUM('IN','OUT') NOT NULL,
  `amount_fen`      BIGINT          NOT NULL,
  `balance_before`  BIGINT          NOT NULL,
  `balance_after`   BIGINT          NOT NULL              COMMENT '可为负：已提现佣金被退款冲正时挂负余额',
  `remark`          VARCHAR(255)    NULL,
  `created_at`      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_biz_ref` (`biz_type`,`ref_no`),
  KEY `idx_agent` (`agent_id`,`id`),
  CONSTRAINT `ck_comm_ledger_amount` CHECK (`amount_fen` > 0),
  -- 同 account_ledger。这里 balance_after 允许为负（退款冲正），
  -- 但「负」必须是 before - amount 算出来的负，不是随手写进去的负。
  CONSTRAINT `ck_comm_ledger_arith` CHECK (
    (`direction` = 'IN'  AND `balance_after` = `balance_before` + `amount_fen`) OR
    (`direction` = 'OUT' AND `balance_after` = `balance_before` - `amount_fen`)
  )
) ENGINE=InnoDB COMMENT='佣金台账';


-- 代理商余额快照。恒等于对应台账最新一行的 balance_after，靠行锁串行化。
CREATE TABLE `agent_balance` (
  `agent_id`            BIGINT UNSIGNED NOT NULL,
  `commission_balance_fen` BIGINT       NOT NULL DEFAULT 0 COMMENT '可为负',
  `total_settled_fen`   BIGINT          NOT NULL DEFAULT 0,
  `total_withdrawn_fen` BIGINT          NOT NULL DEFAULT 0,
  `updated_at`          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`agent_id`)
) ENGINE=InnoDB COMMENT='代理商余额快照';


CREATE TABLE `withdraw_order` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `withdraw_no`      VARCHAR(32)     NOT NULL,
  `agent_id`         BIGINT UNSIGNED NOT NULL,
  `amount_fen`       BIGINT          NOT NULL              COMMENT '门槛 ≥ 10000（100 元）',
  `tax_withheld_fen` BIGINT          NOT NULL DEFAULT 0    COMMENT '代扣代缴个税（G21）',
  `actual_paid_fen`  BIGINT          NOT NULL DEFAULT 0,
  `channel`          ENUM('WECHAT_TRANSFER','BANK_TRANSFER') NOT NULL,
  `payee_name`       VARCHAR(64)     NOT NULL              COMMENT '快照：须与实名一致，不允许代收',
  `payee_account`    VARCHAR(64)     NOT NULL,
  `status`           ENUM('APPLIED','RISK_REJECTED','REVIEWING','APPROVED','PAYING','PAID','FAILED','CANCELLED')
                     NOT NULL DEFAULT 'APPLIED',
  `risk_note`        VARCHAR(255)    NULL,
  `reviewed_by`      BIGINT UNSIGNED NULL,
  `reviewed_at`      DATETIME(3)     NULL,
  `provider_txn_id`  VARCHAR(64)     NULL,
  `invoice_no`       VARCHAR(64)     NULL                  COMMENT '代理向平台开的票（G11）',
  `fail_reason`      VARCHAR(255)    NULL,
  `paid_at`          DATETIME(3)     NULL,
  `created_at`       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at`       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_withdraw_no` (`withdraw_no`),
  UNIQUE KEY `uk_provider_txn` (`provider_txn_id`),
  KEY `idx_agent_status` (`agent_id`,`status`,`created_at`),
  CONSTRAINT `ck_withdraw_amount` CHECK (`amount_fen` >= 10000),
  CONSTRAINT `ck_withdraw_parts`  CHECK (`tax_withheld_fen` >= 0 AND `actual_paid_fen` >= 0)
) ENGINE=InnoDB COMMENT='提现单';

-- 提现状态机。必须逐级推进，不能跳步 —— 「APPLIED 直接改成 PAID」等于绕过
-- 风控与财务审核把钱打出去，是这张表上最危险的一个写法。
-- 合法迁移：APPLIED → REVIEWING → APPROVED → PAYING → PAID
--           APPLIED → RISK_REJECTED | CANCELLED
--           PAYING  → FAILED → APPROVED（重试）
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
  --
  -- ★ FAILED 也在这个集合里，虽然那笔钱并没有出账、也没有扣余额。
  --   理由是它【没有终结】：下面 trg_withdraw_state 明文允许 FAILED → APPROVED
  --   （后台「修正收款信息后重新打款」走的就是这条）。把它排除在外，同一笔余额
  --   就能被提两次：代理看着"打款失败、钱还在"再申请一张（这里放行），平台把那张
  --   FAILED 重新审批打款，两笔都真的出账，commission_balance_fen 被扣成负数
  --   （那一列本来就允许为负，没有任何约束会拦），而全程每一步单看都合法、零报错。
  --   FAILED 一直占着额度，直到平台把它推向 PAID 或 RISK_REJECTED。
  --   这个集合与 AgentService.inflightWithdrawFen 必须逐字一致：一处算"让不让过闸"，
  --   一处算界面上"现在能提多少"，差一个状态就会出现"页面说能提、库说不能"。
  SELECT COALESCE(SUM(`amount_fen`),0) INTO inflight FROM `withdraw_order`
   WHERE `agent_id` = NEW.agent_id
     AND `status` IN ('APPLIED','REVIEWING','APPROVED','PAYING','FAILED');
  IF NEW.amount_fen > bal - inflight THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '提现金额超过可提现佣金余额（已扣除在途提现单）';
  END IF;
END$$

CREATE TRIGGER `trg_withdraw_state`
BEFORE UPDATE ON `withdraw_order` FOR EACH ROW
BEGIN
  IF OLD.status IN ('PAID','CANCELLED','RISK_REJECTED') AND NEW.status <> OLD.status THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '提现单已终结，状态不可再变更';
  END IF;

  IF NEW.status = 'REVIEWING' AND OLD.status <> 'APPLIED' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '只有已申请的提现单能进入审核';
  END IF;
  IF NEW.status = 'APPROVED' AND OLD.status NOT IN ('REVIEWING','FAILED') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '只有审核中或打款失败的提现单能转为审核通过';
  END IF;
  IF NEW.status = 'PAYING' AND OLD.status <> 'APPROVED' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '只有审核通过的提现单能进入打款中';
  END IF;
  IF NEW.status = 'PAID' AND OLD.status <> 'PAYING' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '打款前必须经过 审核→通过→打款中，不可跳步';
  END IF;

  -- 打款金额勾稽：到手 + 代扣税 必须正好等于申请额，差一分都要查
  IF NEW.status = 'PAID' AND (NEW.actual_paid_fen + NEW.tax_withheld_fen) <> NEW.amount_fen THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '实付金额 + 代扣代缴个税 必须等于提现申请金额';
  END IF;
  IF NEW.status = 'PAID' AND NEW.paid_at IS NULL THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '标记已打款必须同时写入 paid_at';
  END IF;
END$$
DELIMITER ;


-- =============================================================================
--  F. 激活码与权益
-- =============================================================================

-- 码不存明文：code_hash 唯一索引供 O(1) 激活查询，code_enc 供代理商在自己后台查看，
-- code_masked 供列表展示。DB 泄漏时拿不到可直接激活的明文。
CREATE TABLE `activation_code` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `code_hash`      CHAR(64)        NOT NULL              COMMENT 'SHA-256(code + 全局盐)',
  `code_enc`       VARBINARY(255)  NOT NULL              COMMENT 'AES-256-GCM 密文，密钥在应用配置不入库',
  `code_masked`    VARCHAR(32)     NOT NULL              COMMENT '如 YG7X-****-****-3K9P',
  `course_id`      BIGINT UNSIGNED NOT NULL,
  `owner_agent_id` BIGINT UNSIGNED NULL                  COMMENT '学员直购时为 NULL',
  `order_id`       BIGINT UNSIGNED NOT NULL,
  `status`         ENUM('ISSUED','BOUND','EXPIRED','REVOKED') NOT NULL DEFAULT 'ISSUED',
  `activate_deadline` DATETIME(3)  NOT NULL              COMMENT '发放后须在此前激活，否则 EXPIRED（G5）',
  `bound_user_id`  BIGINT UNSIGNED NULL,
  `bound_at`       DATETIME(3)     NULL,
  `revoked_by`     BIGINT UNSIGNED NULL,
  `revoked_at`     DATETIME(3)     NULL,
  `revoke_reason`  VARCHAR(255)    NULL,
  `created_at`     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at`     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code_hash` (`code_hash`),
  KEY `idx_agent_status` (`owner_agent_id`,`status`),
  KEY `idx_order` (`order_id`),
  KEY `idx_expire_scan` (`status`,`activate_deadline`)
) ENGINE=InnoDB COMMENT='激活码';

-- 激活码状态机。这是「激活码一旦激活，概不退费，一律不退」（录音 01:50）
-- 在数据库层的落点 —— 业务上说得最斩钉截铁的一条规则，不能只靠应用层守。
--
-- 合法迁移：ISSUED → BOUND（学员激活）
--           ISSUED → REVOKED（未激活可作废，对应 G8 未激活可退）
--           ISSUED → EXPIRED（超过激活期限）
--           BOUND  → EXPIRED（学习权限到期）
-- 其余一律拒绝，尤其是 BOUND 之后的改绑、解绑、作废、退回。
DELIMITER $$
-- 发码数不得超过订单快照。多发一个码就是白送一门课，而且这种多发不会有任何
-- 异常信号 —— 订单金额没变、对账表也平，只有课程访问量对不上。
CREATE TRIGGER `trg_activation_code_quota`
BEFORE INSERT ON `activation_code` FOR EACH ROW
BEGIN
  DECLARE qty    INT DEFAULT NULL;
  DECLARE issued INT DEFAULT 0;
  SELECT `code_qty_snapshot` INTO qty FROM `biz_order` WHERE id = NEW.order_id;
  IF qty IS NULL THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '激活码必须挂在一笔存在的订单上';
  END IF;
  SELECT COUNT(*) INTO issued FROM `activation_code` WHERE order_id = NEW.order_id;
  IF issued >= qty THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '本单发码数已达订单快照数量，不能再发（多发一个即白送一门课）';
  END IF;
END$$

CREATE TRIGGER `trg_activation_code_state`
BEFORE UPDATE ON `activation_code` FOR EACH ROW
BEGIN
  IF OLD.status = 'BOUND' THEN
    -- <=> 是 NULL 安全等值比较；用 <> 的话两边为 NULL 时求值为 NULL，判断会失效
    IF NOT (NEW.bound_user_id <=> OLD.bound_user_id) THEN
      SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = '激活码已绑定学员，不可改绑或解绑（一码一人不可转移）';
    END IF;
    IF NEW.status NOT IN ('BOUND','EXPIRED') THEN
      SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = '激活码已激活，不可作废或退回未激活状态（激活即不退）';
    END IF;
  END IF;

  IF OLD.status IN ('REVOKED','EXPIRED') AND NEW.status <> OLD.status THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '激活码已处于终态，状态不可再变更';
  END IF;

  IF NEW.status = 'BOUND' AND OLD.status = 'ISSUED' THEN
    IF NOW(3) > OLD.activate_deadline THEN
      SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = '激活码已超过激活期限，不能再激活（G5）';
    END IF;
    IF NEW.bound_user_id IS NULL THEN
      SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = '激活必须同时写入 bound_user_id';
    END IF;
    IF NEW.bound_at IS NULL THEN
      SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = '激活必须同时写入 bound_at';
    END IF;
  END IF;

  IF NEW.status = 'BOUND' AND OLD.status <> 'ISSUED' THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '只有未激活的码才能被激活';
  END IF;
END$$
DELIMITER ;


-- 学员课程权益。一个账号可持多门课（为多课程留口，G19）。
CREATE TABLE `enrollment` (
  `id`                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`             BIGINT UNSIGNED NOT NULL,
  `course_id`           BIGINT UNSIGNED NOT NULL,
  `source`              ENUM('ACTIVATION_CODE','DIRECT_PURCHASE','GRANT') NOT NULL,
  `activation_code_id`  BIGINT UNSIGNED NULL,
  `order_id`            BIGINT UNSIGNED NULL,
  `attributed_agent_id` BIGINT UNSIGNED NULL              COMMENT '§7.5 归属，结算依据',
  `valid_from`          DATETIME(3)     NOT NULL,
  `valid_until`         DATETIME(3)     NOT NULL          COMMENT '激活后 M 个月（G5）',
  `status`              ENUM('ACTIVE','EXPIRED','SUSPENDED') NOT NULL DEFAULT 'ACTIVE',
  `agreement_version`   VARCHAR(32)     NOT NULL          COMMENT '激活时勾选的服务协议版本（G6 留证）',
  `agreed_at`           DATETIME(3)     NOT NULL,
  `agreed_ip`           VARCHAR(45)     NULL,
  `created_at`          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_course` (`user_id`,`course_id`),
  UNIQUE KEY `uk_code` (`activation_code_id`),
  KEY `idx_attributed` (`attributed_agent_id`),
  KEY `idx_expire_scan` (`status`,`valid_until`),
  CONSTRAINT `ck_enrollment_period` CHECK (`valid_until` > `valid_from`),
  -- 来源必须带得出凭据。source 写着激活码却没有码 id、写着直购却没有订单 id，
  -- 事后既查不出这份权益从哪来，也算不清该给谁结佣。
  CONSTRAINT `ck_enrollment_source` CHECK (
    (`source` = 'ACTIVATION_CODE' AND `activation_code_id` IS NOT NULL) OR
    (`source` = 'DIRECT_PURCHASE' AND `order_id` IS NOT NULL) OR
    (`source` = 'GRANT')
  )
) ENGINE=InnoDB COMMENT='学员课程权益';

DELIMITER $$
CREATE TRIGGER `trg_enrollment_precheck`
BEFORE INSERT ON `enrollment` FOR EACH ROW
BEGIN
  DECLARE code_owner BIGINT UNSIGNED DEFAULT NULL;
  DECLARE code_exists INT DEFAULT 0;

  -- 冻结 / 停用的账号不能开通权益，否则「封了号还能看课」
  IF NOT EXISTS (SELECT 1 FROM `sys_user`
                 WHERE id = NEW.user_id AND status = 'ACTIVE') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '学员账号不在正常状态，不能开通权益';
  END IF;

  -- 「一码一人」的另一半：uk_code 保证一个码只开一份权益，但没保证这份权益
  -- 开给的就是激活它的那个人。码绑给 A、权益开给 B，两条唯一键都拦不住。
  IF NEW.activation_code_id IS NOT NULL THEN
    SELECT COUNT(*), MAX(`bound_user_id`) INTO code_exists, code_owner
      FROM `activation_code` WHERE id = NEW.activation_code_id;
    IF code_exists = 0 THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '激活码不存在，不能开通权益';
    END IF;
    IF code_owner IS NULL OR code_owner <> NEW.user_id THEN
      SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = '权益必须开给激活该码的学员本人（一码一人）';
    END IF;
  END IF;
END$$
DELIMITER ;


-- =============================================================================
--  G. 课程与学习
-- =============================================================================

CREATE TABLE `course` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `title`         VARCHAR(128)    NOT NULL,
  `subtitle`      VARCHAR(255)    NULL,
  `cover_url`     VARCHAR(255)    NULL,
  `total_lessons` INT             NOT NULL DEFAULT 0,
  `total_seconds` INT             NOT NULL DEFAULT 0,
  `status`        ENUM('DRAFT','ON','OFF') NOT NULL DEFAULT 'DRAFT',
  -- G5 的两个期限。放在库里而不是代码里：需求方看得见、改一次不用发版，
  -- 也避免同一个概念在出码处与激活处各写一遍然后慢慢对不上。
  `activate_months` INT           NOT NULL DEFAULT 12   COMMENT '发码后允许激活的月数（G5）',
  `access_months`   INT           NOT NULL DEFAULT 24   COMMENT '激活后可观看的月数（G5 的 M）★待拍板',
  `created_at`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  CONSTRAINT `ck_course_months` CHECK (`activate_months` > 0 AND `access_months` > 0)
) ENGINE=InnoDB COMMENT='课程';


-- 章 = 一场录屏 = 一个片库目录。章以下的各讲共用这批分片，各拿一段片号区间。
--
-- 片库总数与水印基位挂在【章】上而不是【讲】上，是这一版最要紧的一处改动：
-- 码字的第几位 = wm_bit_base + 全局片号 DIV stride，与"这片被排进第几讲"无关。
-- 按讲次顺序分位的话，每重切一次课，同一片对应的位就整体挪一格，
-- 已发出的码字全部作废 —— 而作废不报错，只会让取证指认到无辜的人。
CREATE TABLE `chapter` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `course_id`  BIGINT UNSIGNED NOT NULL,
  `title`      VARCHAR(128)    NOT NULL,
  `hls_key`    VARCHAR(255)    NULL
               COMMENT '本章那一场录屏切出来的片库目录；章下各讲共用它，各拿一段片号区间',
  `store_segment_count` INT    NOT NULL DEFAULT 0
               COMMENT '片库目录里的分片总数（剪掉的片也算在内：水印容量按它算，按交付量算的话每重切一次位就得挪）',
  `wm_bit_base` INT            NOT NULL DEFAULT 0
               COMMENT '本片库第 0 个槽位在整门课码字里的位置：bit(g) = wm_bit_base + g DIV stride',
  `sort_no`    INT             NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_course` (`course_id`,`sort_no`)
) ENGINE=InnoDB COMMENT='章（= 一场录屏 = 一个片库目录）';


CREATE TABLE `lesson` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `chapter_id`      BIGINT UNSIGNED NOT NULL,
  `course_id`       BIGINT UNSIGNED NOT NULL,
  `title`           VARCHAR(128)    NOT NULL,
  `sort_no`         INT             NOT NULL,
  `duration_sec`    INT             NOT NULL,
  `hls_key`         VARCHAR(255)    NOT NULL              COMMENT 'OSS 私有桶对象前缀，绝不下发直链',
  `segment_count`   INT             NOT NULL DEFAULT 0,
  `wm_slot_stride`  INT             NOT NULL DEFAULT 6    COMMENT 'A/B 水印位密度：每 N 片取 1 片做双版本',
  `wm_slot_count`   INT             NOT NULL DEFAULT 0    COMMENT '本讲的水印位数量',
  -- 各讲占码字的一段互不重叠的区间。若每讲都从第 0 位开始用，整门课就只有
  -- 最长那一讲那么多位真正被投递出去，有效码长静默变成 1/讲数，抗合谋能力跟着掉，
  -- 而各组件的单元测试全都照样绿 —— 见 20260811_水印码本持久化.sql 的说明。
  `wm_bit_offset`   INT             NOT NULL DEFAULT 0    COMMENT '本讲【首个】槽位对应的码位',
  -- 冗余自 chapter.wm_bit_base。投递侧每一行分片都要用它算码位，为此多做一次 JOIN
  -- 不值得；上架闸门会校验两边一致（对不上就是整段码字错位，而错位的取证不报错）。
  `wm_bit_base`     INT             NOT NULL DEFAULT 0    COMMENT '所在片库的码位基点，同 chapter.wm_bit_base',
  `is_preview`      TINYINT(1)      NOT NULL DEFAULT 0    COMMENT '试听片段必须是单独编码的一份，不能是正片前几片',
  `status`          ENUM('DRAFT','ON','OFF') NOT NULL DEFAULT 'DRAFT',
  PRIMARY KEY (`id`),
  KEY `idx_course` (`course_id`,`sort_no`),
  KEY `idx_chapter` (`chapter_id`,`sort_no`),
  CONSTRAINT `ck_lesson_wm_offset` CHECK (`wm_bit_offset` >= 0 AND `wm_slot_count` >= 0)
) ENGINE=InnoDB COMMENT='小节（讲）';



-- 讲次内容分节：一段三小时的整讲录屏，按【课上真的在讲什么】切成可点击的目录。
--
-- 分节是讲次【内部】的时间区间，是「我想看的那段在哪」。它与讲次划分是两件事：
-- 讲次的粒度是"一次坐下来能学完的一段"（本课约 40 分钟），分节的粒度是"一个知识点"。
--
-- 2026-08-21 之前这里写着「拆不成更多讲次」，理由是水印位区间在码字发出后不许重排。
-- 那条理由在同日被 migrations/20260821_讲次重切四十分钟.sql 解掉了：码位改成
-- 片库内全局片号的纯函数之后，怎么重切都不动位。现在四场整录已经切成 16 讲。
--
-- 三条不变量都落在库里，因为灌错的表现全是「安静地不对」：
--   · end_sec 超时长 → 点进去 seek 到视频之外，播放器什么都不做也不报错
--   · 区间重叠       → 同一时刻属于两节，进度算错且不报错
--   · sort_no 撞车   → 目录顺序随查询计划变
CREATE TABLE `lesson_section` (
  `id`        BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `lesson_id` BIGINT UNSIGNED NOT NULL,
  `sort_no`   INT             NOT NULL              COMMENT '目录里的序号，从 1 开始',
  `title`     VARCHAR(160)    NOT NULL              COMMENT '节标题，写课上做了什么，不写学员能得到什么',
  `summary`   VARCHAR(512)    NULL                  COMMENT '一句话说明，可空',
  `kind`      ENUM('LECTURE','DEMO','HANDSON','QA','OTHER') NOT NULL DEFAULT 'LECTURE'
                                                    COMMENT '讲解/演示/实操/答疑/其他',
  `start_sec` INT             NOT NULL              COMMENT '相对本讲视频起点的秒数，点这一节就 seek 到这里',
  `end_sec`   INT             NOT NULL              COMMENT '不含。用于判「这一节看完了没有」',
  `created_at` DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_section_sort` (`lesson_id`,`sort_no`),
  KEY `idx_section_start` (`lesson_id`,`start_sec`),
  CONSTRAINT `ck_section_range` CHECK (`start_sec` >= 0 AND `end_sec` > `start_sec`)
) ENGINE=InnoDB COMMENT='讲次内容分节（时间点目录）';

-- 区间不得超出讲次时长，讲次必须存在。
-- 这一条只能在触发器里做：它要跨表读 lesson.duration_sec，CHECK 约束读不了别的表。
DROP TRIGGER IF EXISTS `trg_section_bounds_ins`;
DROP TRIGGER IF EXISTS `trg_section_bounds_upd`;

DELIMITER $$

CREATE TRIGGER `trg_section_bounds_ins`
BEFORE INSERT ON `lesson_section` FOR EACH ROW
BEGIN
  DECLARE dur INT DEFAULT NULL;
  DECLARE n   INT DEFAULT 0;
  SELECT `duration_sec` INTO dur FROM `lesson` WHERE `id` = NEW.lesson_id;
  IF dur IS NULL THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '讲次不存在，无法给它分节';
  END IF;
  IF NEW.end_sec > dur THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '分节区间超出讲次时长：点进去会 seek 到视频之外，而播放器不会报错';
  END IF;
  -- 重叠检查。MySQL 8 允许触发器【读】自己那张表（只是不允许改），已实测。
  SELECT COUNT(*) INTO n FROM `lesson_section`
   WHERE `lesson_id` = NEW.lesson_id
     AND NEW.start_sec < `end_sec` AND `start_sec` < NEW.end_sec;
  IF n > 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '分节区间与同一讲的另一节重叠：同一时刻属于两节，进度会算错且不报错';
  END IF;
END$$

CREATE TRIGGER `trg_section_bounds_upd`
BEFORE UPDATE ON `lesson_section` FOR EACH ROW
BEGIN
  DECLARE dur INT DEFAULT NULL;
  DECLARE n   INT DEFAULT 0;
  SELECT `duration_sec` INTO dur FROM `lesson` WHERE `id` = NEW.lesson_id;
  IF dur IS NULL THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '讲次不存在，无法给它分节';
  END IF;
  IF NEW.end_sec > dur THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '分节区间超出讲次时长：点进去会 seek 到视频之外，而播放器不会报错';
  END IF;
  SELECT COUNT(*) INTO n FROM `lesson_section`
   WHERE `lesson_id` = NEW.lesson_id AND `id` <> NEW.id
     AND NEW.start_sec < `end_sec` AND `start_sec` < NEW.end_sec;
  IF n > 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '分节区间与同一讲的另一节重叠：同一时刻属于两节，进度会算错且不报错';
  END IF;
END$$

DELIMITER ;


-- 讲次交付的分片区间。一讲 = 片库里的一段（或几段）片号区间。
--
-- 为什么要允许「几段」：一场三小时的录屏里夹着课间、等 AI 跑完的静音。把它们剪掉，
-- 就得允许一讲内部跳段。跳段的两个后果都必须在投递侧处理，否则都不报错：
--   · 播放列表要在跳段处写 #EXT-X-DISCONTINUITY，不写的话时间轴对不上，
--     表现是跳段之后拖进度条位置全错；
--   · AES-128 的 IV 一直取"分片序号"（打包时按全局片号），跳段之后推不出来，
--     必须逐片显式写 IV。错了几乎看不出来：CBC 只有第一个分组吃 IV，
--     一片 1.5 MB 里只错 2 个字节，解复用器一对齐就过去了 —— 不报错也不花屏。
-- 见 M3u8Builder。
CREATE TABLE `lesson_segment_block` (
  `id`        BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `lesson_id` BIGINT UNSIGNED NOT NULL,
  `sort_no`   INT             NOT NULL COMMENT '播放顺序，从 1 开始',
  `seg_from`  INT             NOT NULL COMMENT '含。片库目录里的全局片号',
  `seg_to`    INT             NOT NULL COMMENT '不含',
  `created_at` DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_block_sort` (`lesson_id`,`sort_no`),
  KEY `idx_block_range` (`lesson_id`,`seg_from`),
  CONSTRAINT `ck_block_range` CHECK (`seg_from` >= 0 AND `seg_to` > `seg_from`)
) ENGINE=InnoDB COMMENT='讲次交付的分片区间（一讲可有几段，中间跳过的是剪掉的内容）';

DELIMITER $$

DROP PROCEDURE IF EXISTS `sp_block_bounds`$$
CREATE PROCEDURE `sp_block_bounds`(
    IN p_id BIGINT UNSIGNED, IN p_lesson_id BIGINT UNSIGNED,
    IN p_from INT, IN p_to INT)
BEGIN
  DECLARE v_chapter BIGINT UNSIGNED DEFAULT NULL;
  DECLARE v_store INT DEFAULT NULL;
  DECLARE n INT DEFAULT 0;

  SELECT l.chapter_id, c.store_segment_count INTO v_chapter, v_store
    FROM `lesson` l JOIN `chapter` c ON c.id = l.chapter_id
   WHERE l.id = p_lesson_id;
  IF v_chapter IS NULL THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '讲次不存在或没有挂在章下，无法给它分配分片区间';
  END IF;
  IF v_store <= 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '本章还没有登记片库分片总数（chapter.store_segment_count），区间无从校验';
  END IF;
  IF p_to > v_store THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '分片区间超出片库：播放列表会引用不存在的对象，而 404 在 hls.js 里被归成 NETWORK_ERROR 无限重试 —— 画面停住，一句错都不报';
  END IF;

  -- 同一片库内不许两讲抢同一片。抢了就是同一个对象在两个人的码字里对应到不同的位，
  -- 取证会算出一个错的分数，而错的分数不报错。
  SELECT COUNT(*) INTO n
    FROM `lesson_segment_block` b JOIN `lesson` l2 ON l2.id = b.lesson_id
   WHERE l2.chapter_id = v_chapter AND b.id <> IFNULL(p_id, 0)
     AND p_from < b.seg_to AND b.seg_from < p_to;
  IF n > 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '分片区间与同一片库里的另一段重叠：同一片会落进两讲，同一个对象于是对应两个码位';
  END IF;
END$$

DROP TRIGGER IF EXISTS `trg_block_bounds_ins`$$
CREATE TRIGGER `trg_block_bounds_ins`
BEFORE INSERT ON `lesson_segment_block` FOR EACH ROW
BEGIN
  CALL `sp_block_bounds`(NULL, NEW.lesson_id, NEW.seg_from, NEW.seg_to);
END$$

DROP TRIGGER IF EXISTS `trg_block_bounds_upd`$$
CREATE TRIGGER `trg_block_bounds_upd`
BEFORE UPDATE ON `lesson_segment_block` FOR EACH ROW
BEGIN
  CALL `sp_block_bounds`(NEW.id, NEW.lesson_id, NEW.seg_from, NEW.seg_to);
END$$

DELIMITER ;

-- 学习进度。取最大观看位而非最后一次，多端合并靠 max（G12）。
-- 每讲一把 HLS AES-128 内容密钥，密文存库，KEK 只在应用配置中。
-- 表必须在上架触发器之前创建：上架闸门会检查本讲是否有现行密钥。
CREATE TABLE `lesson_content_key` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `lesson_id`   BIGINT UNSIGNED NOT NULL,
  `course_id`   BIGINT UNSIGNED NOT NULL,
  `key_version` INT             NOT NULL DEFAULT 1,
  `algo`        VARCHAR(16)     NOT NULL DEFAULT 'AES-128',
  `key_enc`     VARBINARY(128)  NOT NULL,
  `kek_id`      VARCHAR(32)     NOT NULL DEFAULT 'v1',
  `status`      ENUM('ACTIVE','RETIRED') NOT NULL DEFAULT 'ACTIVE',
  `active_flag` TINYINT GENERATED ALWAYS AS (IF(`status` = 'ACTIVE', 1, NULL)) STORED,
  `created_at`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `retired_at`  DATETIME(3)     NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_lesson_version` (`lesson_id`,`key_version`),
  UNIQUE KEY `uk_lesson_active` (`lesson_id`,`active_flag`),
  KEY `idx_course` (`course_id`,`status`),
  CONSTRAINT `ck_lesson_key_version` CHECK (`key_version` > 0),
  CONSTRAINT `ck_lesson_key_sealed` CHECK (LENGTH(`key_enc`) >= 44 AND LENGTH(`key_enc`) <= 128)
) ENGINE=InnoDB COMMENT='讲次内容密钥（密文存储，KEK 在应用配置）';

CREATE TABLE `learn_progress` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`         BIGINT UNSIGNED NOT NULL,
  `course_id`       BIGINT UNSIGNED NOT NULL,
  `lesson_id`       BIGINT UNSIGNED NOT NULL,
  `max_position_sec` INT            NOT NULL DEFAULT 0    COMMENT '最远观看位置，用于断点续播',
  `watched_sec`     INT             NOT NULL DEFAULT 0    COMMENT '服务端认可的有效观看秒数',
  `heartbeat_count` INT             NOT NULL DEFAULT 0    COMMENT '反作弊：heartbeat_count × 间隔 ≥ watched_sec 才可信',
  -- 与 last_seen_at 分开：进度上报也刷新 last_seen_at，共用一列会把心跳的墙钟增量压小，
  -- 有效观看时长被系统性少记且不报错。定义抄自 migrations/20260811_讲次内容密钥.sql，抄不推。
  `last_credit_at`  DATETIME(3)     NULL                  COMMENT '上次给 watched_sec 记账的时刻',
  `completed_at`    DATETIME(3)     NULL,
  `last_seen_at`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_lesson` (`user_id`,`lesson_id`),
  KEY `idx_user_course` (`user_id`,`course_id`)
) ENGINE=InnoDB COMMENT='学习进度';

-- 进度反作弊的物理下限（G12）。前端心跳可以伪造，但有三条它绕不过去：
--   ①观看位置不可能超过课程时长  ②有效观看时长不可能超过课程时长
--   ③心跳次数撑不起所报观看时长（此处用 60 秒/次 的宽松上界，只挡离谱值；
--     精确阈值随心跳间隔变化，属策略，放应用层，不写死在 schema 里）
-- 代价是每次进度写入多一次 lesson 主键查找。播放心跳约 15 秒一次，
-- 这个开销相对反作弊价值可以接受。
DELIMITER $$
CREATE TRIGGER `trg_progress_sanity_ins`
BEFORE INSERT ON `learn_progress` FOR EACH ROW
BEGIN
  DECLARE dur INT DEFAULT NULL;
  SELECT `duration_sec` INTO dur FROM `lesson` WHERE `id` = NEW.lesson_id;
  IF dur IS NULL THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '小节不存在，无法记录进度';
  END IF;
  IF NEW.max_position_sec > dur THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '观看位置超过课程时长';
  END IF;
  IF NEW.watched_sec > dur THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '有效观看时长超过课程时长';
  END IF;
  IF NEW.watched_sec > NEW.heartbeat_count * 60 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '心跳次数撑不起所报观看时长';
  END IF;
END$$

CREATE TRIGGER `trg_progress_sanity_upd`
BEFORE UPDATE ON `learn_progress` FOR EACH ROW
BEGIN
  DECLARE dur INT DEFAULT NULL;
  SELECT `duration_sec` INTO dur FROM `lesson` WHERE `id` = NEW.lesson_id;
  IF NEW.max_position_sec > dur THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '观看位置超过课程时长';
  END IF;
  IF NEW.watched_sec > dur THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '有效观看时长超过课程时长';
  END IF;
  IF NEW.watched_sec > NEW.heartbeat_count * 60 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '心跳次数撑不起所报观看时长';
  END IF;
  -- 进度只进不退：多端合并取最大值，不能被旧端的落后进度回冲
  IF NEW.max_position_sec < OLD.max_position_sec THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '学习进度只能前进，多端合并须取最大值';
  END IF;
END$$
DELIMITER ;


-- 心跳明细单独存，供服务端反作弊校验与事后复盘。按月分区备选。
CREATE TABLE `learn_heartbeat` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`      BIGINT UNSIGNED NOT NULL,
  `lesson_id`    BIGINT UNSIGNED NOT NULL,
  `session_id`   VARCHAR(48)     NOT NULL,
  `position_sec` INT             NOT NULL,
  `playback_rate` DECIMAL(3,1)   NOT NULL DEFAULT 1.0,
  `client_ip`    VARCHAR(45)     NULL,
  `device_id`    VARCHAR(64)     NULL,
  `created_at`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_user_time` (`user_id`,`created_at`),
  KEY `idx_session` (`session_id`),
  CONSTRAINT `ck_heartbeat_pos` CHECK (`position_sec` >= 0),
  -- 倍速本身合法，9.9 倍速不是在看课是在刷进度。上界取 3.0（播放器常见上限），
  -- 精确策略仍在应用层，这里只挡物理上不可能的值。
  CONSTRAINT `ck_heartbeat_rate` CHECK (`playback_rate` >= 0.5 AND `playback_rate` <= 3.0)
) ENGINE=InnoDB COMMENT='播放心跳明细';


-- =============================================================================
--  H. 播放会话与风控（详见《内容保护与反盗版体系设计》§5）
-- =============================================================================

CREATE TABLE `play_session` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `session_id`    VARCHAR(48)     NOT NULL,
  `user_id`       BIGINT UNSIGNED NOT NULL,
  `lesson_id`     BIGINT UNSIGNED NOT NULL,
  `device_id`     VARCHAR(64)     NOT NULL,
  `client_ip`     VARCHAR(45)     NOT NULL,
  `ip_region`     VARCHAR(64)     NULL                  COMMENT '不可能旅行检测用',
  `user_agent`    VARCHAR(255)    NULL,
  `wm_codeword_id` BIGINT UNSIGNED NULL                 COMMENT '本次下发的 A/B 水印码字',
  `segment_fetched` INT           NOT NULL DEFAULT 0    COMMENT '取片计数，速率配额依据',
  `started_at`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `last_seen_at`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                  COMMENT '最近一次播放令牌续签时间',
  `ended_at`      DATETIME(3)     NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_session_id` (`session_id`),
  KEY `idx_user_time` (`user_id`,`started_at`),
  KEY `idx_device` (`device_id`),
  CONSTRAINT `ck_session_time` CHECK (`ended_at` IS NULL OR `ended_at` >= `started_at`),
  -- 取片计数是速率配额的依据，负数会把配额刷回去
  CONSTRAINT `ck_session_fetched` CHECK (`segment_fetched` >= 0)
) ENGINE=InnoDB COMMENT='播放会话';


CREATE TABLE `user_device` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`      BIGINT UNSIGNED NOT NULL,
  `device_id`    VARCHAR(64)     NOT NULL              COMMENT '指纹归一化后的稳定 hash',
  `device_label` VARCHAR(64)     NULL                  COMMENT '如 Chrome / Windows',
  `status`       ENUM('ACTIVE','REMOVED') NOT NULL DEFAULT 'ACTIVE',
  `bound_at`     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `removed_at`   DATETIME(3)     NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_device` (`user_id`,`device_id`),
  KEY `idx_user_status` (`user_id`,`status`)
) ENGINE=InnoDB COMMENT='设备绑定（上限 2 台，30 天内换 1 次）';

-- 设备上限是防共享账号的地基。上限只写在注释里，等于一个账号可以绑 50 台，
-- 全班共用一个号，L1~L7 后面所有的水印、风控、DRM 都白做。
-- 数字 2 写死在这里是有意的：放宽上限必须走一次 schema 迁移、被人看见，
-- 不能是改一行配置就悄悄发生的事。
DELIMITER $$
CREATE TRIGGER `trg_user_device_cap_ins`
BEFORE INSERT ON `user_device` FOR EACH ROW
BEGIN
  DECLARE cnt INT DEFAULT 0;
  IF NEW.status = 'ACTIVE' THEN
    SELECT COUNT(*) INTO cnt FROM `user_device`
     WHERE user_id = NEW.user_id AND status = 'ACTIVE';
    IF cnt >= 2 THEN
      SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = '该账号已绑定 2 台设备（上限），请先解绑再换设备';
    END IF;
  END IF;
END$$

-- 解绑后再启用同一条记录，同样要过上限
CREATE TRIGGER `trg_user_device_cap_upd`
BEFORE UPDATE ON `user_device` FOR EACH ROW
BEGIN
  DECLARE cnt INT DEFAULT 0;
  IF NEW.status = 'ACTIVE' AND OLD.status = 'REMOVED' THEN
    SELECT COUNT(*) INTO cnt FROM `user_device`
     WHERE user_id = NEW.user_id AND status = 'ACTIVE' AND id <> NEW.id;
    IF cnt >= 2 THEN
      SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = '该账号已绑定 2 台设备（上限），请先解绑再换设备';
    END IF;
  END IF;
END$$
DELIMITER ;


CREATE TABLE `risk_event` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`    BIGINT UNSIGNED NOT NULL,
  `signal`     VARCHAR(48)     NOT NULL              COMMENT 'IMPOSSIBLE_TRAVEL / RATE_EXCEED / WM_TAMPERED …',
  `score`      INT             NOT NULL,
  `detail`     JSON            NULL,
  `session_id` VARCHAR(48)     NULL,
  `created_at` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_user_time` (`user_id`,`created_at`)        COMMENT '7 天滑动窗口聚合走这条'
) ENGINE=InnoDB COMMENT='风控信号';


CREATE TABLE `risk_action` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`    BIGINT UNSIGNED NOT NULL,
  `score_at_trigger` INT       NOT NULL,
  `action`     ENUM('WATCH','CAPTCHA','SMS_VERIFY','FREEZE_24H','SUSPEND') NOT NULL,
  `released_at` DATETIME(3)    NULL,
  `released_by` BIGINT UNSIGNED NULL                 COMMENT '人工解封通道，必须有',
  `created_at` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_user_time` (`user_id`,`created_at`)
) ENGINE=InnoDB COMMENT='风控处置';


-- A/B 盲水印码字分配。用抗合谋码（Tardos）生成，不是 user_id 的二进制。
-- 码本参数。TardosCode 的偏置数组 p_j 是打分公式的一部分：签发用哪一套 p，
-- 取证就必须用同一套。它原先没有任何落脚点 —— 进程一重启，已发出去的每一份码字
-- 都变成不可验证的字节，而且不会有任何报错，只在真出泄漏、真去取证时才发现。
CREATE TABLE `wm_scheme` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `course_id`     BIGINT UNSIGNED NOT NULL,
  `algo`          VARCHAR(32)     NOT NULL DEFAULT 'TARDOS_SYM_V1'
                  COMMENT '换算法必须换 scheme，不能原地改 —— 旧码字要按旧算法验',
  `max_colluders` INT             NOT NULL COMMENT '设计能抗的合谋人数 c',
  `users_planned` INT             NOT NULL COMMENT '设计用户规模 n；超了要新开 scheme',
  `eps1`          DOUBLE          NOT NULL COMMENT '允许的漏判概率',
  `bit_length`    INT             NOT NULL,
  -- 不量化：存的必须与签发时用的逐位相同。差一个尾数位，打分就和当初不是同一套，
  -- 而这种偏差不会报错，只会让分数整体漂移，漂过阈值就是一次误判。
  `bias`          MEDIUMBLOB      NOT NULL COMMENT 'p_j 序列，big-endian float64 × bit_length',
  `status`        ENUM('ACTIVE','RETIRED') NOT NULL DEFAULT 'ACTIVE',
  `active_flag`   TINYINT GENERATED ALWAYS AS (IF(`status` = 'ACTIVE', 1, NULL)) STORED,
  `created_at`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `retired_at`    DATETIME(3)     NULL,
  PRIMARY KEY (`id`),
  -- 一门课至多一套现行。两套并存时，签发走哪套、取证按哪套算全凭查询顺序，
  -- 而两边选得不一样的那一天不会有任何提示。
  UNIQUE KEY `uk_course_active` (`course_id`,`active_flag`),
  KEY `idx_course` (`course_id`,`status`),
  -- 长度对不上 = 取证读到错位的 p。错位不报错，只让每一位的权重都算错，
  -- 最后指认到无辜的人身上 —— 比抓不到更糟。
  CONSTRAINT `ck_wm_scheme_bias` CHECK (`bit_length` > 0 AND LENGTH(`bias`) = `bit_length` * 8),
  CONSTRAINT `ck_wm_scheme_params` CHECK (`max_colluders` > 0 AND `users_planned` > 0
                                          AND `eps1` > 0 AND `eps1` < 1)
) ENGINE=InnoDB COMMENT='水印码本参数（每门课一套现行）';

CREATE TABLE `wm_codeword` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`    BIGINT UNSIGNED NOT NULL,
  `course_id`  BIGINT UNSIGNED NOT NULL,
  `scheme_id`  BIGINT UNSIGNED NULL                  COMMENT '按哪套码本发的；为空 = 不可取证',
  -- 2048 字节 = 16384 位。当前课程 9120 位用得下，但步长调小或课程更长时会顶到上限。
  -- 严格模式下超长是【报错】不是截断，所以顶到了会当场炸而不是安静地少半截码字 ——
  -- 但既然放宽的代价只是几个字节，就没有理由让它逼近上限。
  `bits`       VARBINARY(8192) NOT NULL              COMMENT '按位打包的码字（MSB 在前）',
  `bit_length` INT             NOT NULL,
  `created_at` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_course` (`user_id`,`course_id`),
  KEY `idx_scheme` (`scheme_id`),
  -- 码字是溯源取证的证据。位长为 0 等于没水印却以为有；声称的位长超过实际
  -- 字节数，比对时会读到越界的位，指认到无辜的人身上 —— 后者比没水印更糟。
  CONSTRAINT `ck_wm_bits` CHECK (`bit_length` > 0 AND LENGTH(`bits`) * 8 >= `bit_length`)
) ENGINE=InnoDB COMMENT='盲水印码字';


-- =============================================================================
--  I. 测评
-- =============================================================================

-- 一门课挂多份卷，外加一份不属于任何课程、对全部注册学员开放的卷。
--
-- 这张表只放身份与准入，一条规则都不放 —— 规则在 exam_config，那张表是版本化的
-- （改规则 = 插新行，已开考的场次按 attempt.config_id 引用旧行）。身份跟着版本化的话，
-- 同一份卷会有 N 行各自声明自己发不发证，而「这份卷发不发证」就此没有唯一答案。
--
--   · code 是稳定标识：后端按 code 认「哪张是发证的综合卷」，导题脚本按 code 认卷，
--     运营改标题不影响任何一处。所以它 UNIQUE 且不参与展示。
--   · course_id 可空 —— 这是开放卷存在的全部技术含义。可空 ≠ 随便空，
--     ENROLLED 卷必须挂课程，由 trg_exam_paper_precheck 逼着。
--   · grants_certificate 是证书链的新一环：certificate 那边原来只问「passed 吗、
--     是这个人这门课吗」，问不到「这份卷凭什么发证」。
CREATE TABLE `exam_paper` (
  `id`                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `code`               VARCHAR(32)     NOT NULL              COMMENT '稳定标识，后端与导题脚本按它认卷；改标题不影响',
  `title`              VARCHAR(128)    NOT NULL,
  `subtitle`           VARCHAR(255)    NULL                  COMMENT '卡片上的一句话；不要写题数分数，那些是 exam_config 的',
  `course_id`          BIGINT UNSIGNED NULL                  COMMENT '开放卷可以不挂课程',
  `access`             ENUM('ENROLLED','OPEN') NOT NULL      COMMENT 'ENROLLED=要有该课有效权益；OPEN=任何注册学员都能考',
  `grants_certificate` TINYINT(1)      NOT NULL DEFAULT 0    COMMENT '考过发不发结业证明；trg_certificate_precheck 按它把关',
  `status`             ENUM('DRAFT','ON','OFF') NOT NULL DEFAULT 'DRAFT',
  `sort_no`            INT             NOT NULL DEFAULT 0,
  `created_at`         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_paper_code` (`code`),
  KEY `idx_paper_list` (`status`,`sort_no`)               COMMENT '学员端列卷走这条'
) ENGINE=InnoDB COMMENT='试卷（身份与准入；规则仍在 exam_config）';

-- 卷的三条身份自洽。插入侧与更新侧判据逐字同源，两处必须一起改 ——
-- 只改一处的表现是「新建的卷建不成，而把一张老卷 UPDATE 成同样的形状能改成」。
--
--   ① 开放卷不得发证：不买课不激活就能考的卷，发出去的「课程结业证明」证明不了任何事，
--      而它在库里与真证书一模一样、发出去撤不回。
--   ② ENROLLED 卷必须挂课程：不挂课程的话权益校验无从查起（「查这个人对 NULL 这门课
--      有没有权益」恒为假），整张卷谁都考不了，而学员端会照常把它列出来（列表只看 status）。
--   ③ 发证的卷必须挂课程：certificate.course_id 是 NOT NULL 且 uk_user_course 按它去重。
--      ★ 这一条今天一次都不会触发（access 只有两值，那组合必先被 ① 或 ② 拦下），
--        留着是给 ENUM 追加值的那一天用的。
--
-- grants_certificate 一律用 <> 0 判，不用 = 1：它是 TINYINT(1)，写 2 进来在库层完全合法，
-- 而 = 1 会把它读成「不发证」—— 一张实际发证的卷从这三条闸下面走过去。
DELIMITER $$
CREATE TRIGGER `trg_exam_paper_precheck`
BEFORE INSERT ON `exam_paper` FOR EACH ROW
BEGIN
  IF NEW.`access` = 'OPEN' AND NEW.`grants_certificate` <> 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '开放卷不能发结业证明：不买课不激活就能考的卷，发出去的证明证明不了任何事';
  END IF;
  IF NEW.`access` = 'ENROLLED' AND NEW.`course_id` IS NULL THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '按课程开放的卷必须挂课程，否则权益校验无从查起，这张卷谁都考不了';
  END IF;
  IF NEW.`grants_certificate` <> 0 AND NEW.`course_id` IS NULL THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '发结业证明的卷必须挂课程：不挂课程就答不上来往哪门课发证';
  END IF;
END$$

-- 更新侧。光有插入侧不够，而且缺口正对着最危险的那条路：一张 ENROLLED、发证的正常卷，
-- UPDATE 成 access='OPEN' 之后就是一张「谁都能考、考过发结业证明」的卷 ——
-- 后台改一个下拉框就能做到，插入侧那三条一条都不过问。
CREATE TRIGGER `trg_exam_paper_upd`
BEFORE UPDATE ON `exam_paper` FOR EACH ROW
BEGIN
  IF NEW.`access` = 'OPEN' AND NEW.`grants_certificate` <> 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '开放卷不能发结业证明：不买课不激活就能考的卷，发出去的证明证明不了任何事';
  END IF;
  IF NEW.`access` = 'ENROLLED' AND NEW.`course_id` IS NULL THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '按课程开放的卷必须挂课程，否则权益校验无从查起，这张卷谁都考不了';
  END IF;
  IF NEW.`grants_certificate` <> 0 AND NEW.`course_id` IS NULL THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '发结业证明的卷必须挂课程：不挂课程就答不上来往哪门课发证';
  END IF;
END$$
DELIMITER ;


CREATE TABLE `question` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `course_id`      BIGINT UNSIGNED NULL                  COMMENT '开放卷的题不挂课程；为空时 paper_id 必须非空',
  `paper_id`       BIGINT UNSIGNED NULL                  COMMENT '这道题属于哪份卷；抽题按它圈范围，不再按 course_id',
  `lesson_id`      BIGINT UNSIGNED NULL                  COMMENT '出处，答案解析可跳转',
  `source_ts_sec`  INT             NULL,
  -- 测评报告按这三列聚合与逐题显示（2026-09-03）。三列全可空：抽题与判分一个字都不看它们，
  -- 回填之前照常考试，只是报告的按考点聚合是空的。
  --
  -- ★ topic 与 knowledge_point 分两列，是因为聚合不能用考点：实测 50 题里有 37 个
  --   互不相同的考点，按它分组等于一组一题，报告会变成把卷子再念一遍。
  --   聚合按 topic 与讲次，考点只在错题清单里逐题显示。
  `topic`          VARCHAR(64)     NULL                  COMMENT '报告聚合用的粗粒度分组：课程卷=章名，AIGC 卷=板块名',
  `knowledge_point` VARCHAR(160)   NULL                  COMMENT '细粒度考点，错题清单里逐题显示；不做聚合（实测 50 题 37 个考点，一组一题）',
  `difficulty`     TINYINT         NULL                  COMMENT '1 扎实理解 / 2 需要实操 / 3 踩过坑才会',
  `type`           ENUM('SINGLE','MULTI','JUDGE') NOT NULL COMMENT '录音 02:04 只要选择题和判断题',
  `stem`           TEXT            NOT NULL,
  `options`        JSON            NULL                  COMMENT '[{key,text}]；JUDGE 为空',
  `answer`         VARCHAR(32)     NOT NULL,
  `explanation`    TEXT            NULL,
  `origin`         ENUM('AI_GENERATED','MANUAL_UPLOAD') NOT NULL,
  `review_status`  ENUM('DRAFT','PENDING','APPROVED','REJECTED') NOT NULL DEFAULT 'DRAFT'
                   COMMENT '只有 APPROVED 才能被抽到考卷（§3.3）',
  `reviewed_by`    BIGINT UNSIGNED NULL,
  `reviewed_at`    DATETIME(3)     NULL,
  `created_at`     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  -- 两条索引并存是有意的。多套卷之后抽题走 idx_paper_pool（WHERE paper_id=? AND
  -- review_status='APPROVED'），但旧的 idx_pool 不能删：上线顺序是「先迁移、再导题」，
  -- 中间那一段里全部存量题的 paper_id 还是 NULL，而按 course_id 抽题的老代码还在跑 ——
  -- 删掉它只会表现成「开考那一下慢」，没人会去查是索引没了。
  KEY `idx_pool` (`course_id`,`review_status`,`type`)   COMMENT '随机抽题走这条',
  KEY `idx_paper_pool` (`paper_id`,`review_status`,`type`) COMMENT '多套卷后按卷抽题走这条',
  -- 开放卷的题不挂课程，所以 course_id 放宽为可空；但两头都空的题**谁也抽不到** ——
  -- 按卷抽（新口径）抽不到、按课抽（旧口径）也抽不到，它只是静静躺在库里占着编号，
  -- 而后台题库列表照样把它列出来、审核状态照样能改成 APPROVED。
  CONSTRAINT `ck_question_scope` CHECK (`paper_id` IS NOT NULL OR `course_id` IS NOT NULL),
  -- 难度是 TINYINT，写 0 / 4 / -1 进来在库层全都合法，而报告按难度分组时那些行会落进
  -- 一个谁也没定义过的桶，在页面上显示成一个空标题的分组。回填脚本写错要当场被顶回来。
  CONSTRAINT `ck_question_difficulty` CHECK (`difficulty` IS NULL OR `difficulty` BETWEEN 1 AND 3)
) ENGINE=InnoDB COMMENT='题库';


-- 规则版本化：改规则是插新行，已开考的场次按 attempt.config_id 引用旧行，
-- 否则学员考到一半规则变了会交不上卷。多套卷之后「这行规则归谁」改由 paper_id 回答
-- （旧行 paper_id 为 NULL，仍按 course_id，见 trg_exam_config_paper）。
CREATE TABLE `exam_config` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `course_id`       BIGINT UNSIGNED NULL                  COMMENT '开放卷的规则不挂课程；为空时 paper_id 必须非空',
  `paper_id`        BIGINT UNSIGNED NULL                  COMMENT '这套规则属于哪份卷；旧行为 NULL 时按 course_id 认（trg_exam_config_paper）',
  `question_count`  INT             NOT NULL DEFAULT 100,
  `pass_score`      INT             NOT NULL DEFAULT 90   COMMENT '参考驾照理论 90/100',
  `duration_min`    INT             NOT NULL DEFAULT 45,
  `shuffle_options` TINYINT(1)      NOT NULL DEFAULT 1    COMMENT '选项乱序，防传答案',
  `max_attempts`    INT             NOT NULL DEFAULT 3,
  `retry_cooldown_hours` INT        NOT NULL DEFAULT 24   COMMENT '不限次补考等于没考',
  `require_progress_pct` INT        NOT NULL DEFAULT 80   COMMENT '看完多少才能考',
  `effective_from`  DATETIME(3)     NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_course` (`course_id`,`effective_from`),
  -- effectiveRules 那句查：WHERE paper_id=? AND effective_from<=NOW(3)
  --                        ORDER BY effective_from DESC, id DESC LIMIT 1
  -- 这张表只增不减（每改一次规则加一行），没索引就是全表扫。
  KEY `idx_paper_rule` (`paper_id`,`effective_from`)
) ENGINE=InnoDB COMMENT='测评规则';

-- 一行规则必须归得了属。
--
-- course_id 放宽到可空之后，(paper_id, course_id) 全空的规则行在库层是合法的，
-- 而它**谁也查不到**：按卷查（新口径）查不到、按课查（旧口径）也查不到。
-- 表现是插进去了、后台列表里看得见、开考时报「测评规则不存在，不能开考」，
-- 两边对不上，而没有任何一行日志说这行规则是孤儿。
--
-- 只做插入侧：改规则的正路是插新行（版本化），UPDATE 旧行本来就不该发生。
DELIMITER $$
CREATE TRIGGER `trg_exam_config_paper`
BEFORE INSERT ON `exam_config` FOR EACH ROW
BEGIN
  IF NEW.`paper_id` IS NULL AND NEW.`course_id` IS NULL THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '测评规则必须至少挂在一份试卷或一门课程上，两边都空的规则行谁也查不到';
  END IF;
END$$
DELIMITER ;


-- 唯一键按【卷】而不是按【课】。按课的话，同一个人在同一门课下考第二张卷的第一次考试
-- 与第一张卷的第一次考试撞键，(user, course, 1) 完全相同 —— 第二张卷根本开不出考，
-- 表现是学员点开考报「重复」。
--
-- paper_id 声明成可空，但它在业务上是必填的 —— 逼它的不是 NOT NULL 而是
-- trg_exam_attempt_ins 那条「NEW.paper_id 必须等于该 config 的 paper_id」：
-- 每一行 exam_config 都挂着卷，于是任何一条 paper_id 为空的 attempt 都会被那条判据当场拒掉。
-- 这一点很要紧：新唯一键里 paper_id 为空的行**不参与唯一性判定**，
-- 真让它空进来的话，同一个人同一份卷可以开出任意多个第 1 次考试，而且一声不响。
CREATE TABLE `exam_attempt` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`      BIGINT UNSIGNED NOT NULL,
  `course_id`    BIGINT UNSIGNED NULL                  COMMENT '开放卷的考试不挂课程',
  `paper_id`     BIGINT UNSIGNED NULL                  COMMENT '考的是哪份卷；必须与 exam_config.paper_id 一致（trg_exam_attempt_ins）',
  `config_id`    BIGINT UNSIGNED NOT NULL,
  `attempt_no`   INT             NOT NULL,
  `question_ids` JSON            NOT NULL              COMMENT '抽题快照：事后能复盘他考的是哪 100 题',
  `score`        INT             NULL,
  `passed`       TINYINT(1)      NULL,
  `started_at`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `deadline_at`  DATETIME(3)     NOT NULL,
  `submitted_at` DATETIME(3)     NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_paper_attempt` (`user_id`,`paper_id`,`attempt_no`),
  KEY `idx_user` (`user_id`,`course_id`),
  CONSTRAINT `ck_attempt_no`    CHECK (`attempt_no` >= 1),
  CONSTRAINT `ck_attempt_score` CHECK (`score` IS NULL OR `score` >= 0),
  CONSTRAINT `ck_attempt_time`  CHECK (`deadline_at` > `started_at`)
) ENGINE=InnoDB COMMENT='考试记录';

-- 结业证明的签发依据是 exam_attempt.passed。证书那边把关了「必须是这个人这门课
-- 的一次已通过考试」，却没人把关 passed 本身 —— 分数 10 分标记 passed=1 一样能
-- 换出证书。判据链条上最弱的一环不是最后一环，是没人看的那一环。
--
-- 多套卷之后又多了一条：试卷与规则必须是同一份。两边不一致时说明调用方把 A 卷的题
-- 挂到了 B 卷的规则上 —— 抽题按 attempt.paper_id 走、判分与次数上限按 config 走，
-- **各按各的，且一路不报错**：学员拿到 50 题的章节卷题面，判分却按 100 题综合卷的
-- 80 分及格线，永远考不过；反过来则是 100 题的卷按 40 分及格，随手就过。
--
-- 比较用 <=>（NULL 安全）而不是 =：一边空一边非空时 = 的结果是 NULL，
-- IF NULL 走 false 分支 = **放行**，而「config 有卷、attempt 没写 paper_id」
-- 正是最可能发生的那一种漏写，它还会顺带绕开新唯一键（多个 NULL 不参与唯一性判定）。
--
-- 还有一条：attempt.course_id 必须就是该卷所属的课程。paper / config / attempt 是本轮
-- 引进的三元关系，上面那条只管住 attempt↔config 这条边，course_id 完全自由 ——
-- 一场 paper=综合卷（course 1）的考试若把 course_id 写成 2，库层全程不响，
-- 而 trg_certificate_precheck 是拿 attempt.course_id 去比对 NEW.course_id 的、
-- certificate.course_id 又是 uk_user_course 的去重键，换出来的就是一张挂在第 2 门课
-- 名下的结业证明。开放卷两边同为 NULL，<=> 天然放行。
DELIMITER $$
CREATE TRIGGER `trg_exam_attempt_ins`
BEFORE INSERT ON `exam_attempt` FOR EACH ROW
BEGIN
  DECLARE q_cnt INT DEFAULT NULL;
  DECLARE p_score INT DEFAULT NULL;
  DECLARE max_try INT DEFAULT NULL;
  DECLARE cfg_paper BIGINT UNSIGNED DEFAULT NULL;
  DECLARE p_course BIGINT UNSIGNED DEFAULT NULL;
  DECLARE p_cnt INT DEFAULT 0;
  SELECT `question_count`,`pass_score`,`max_attempts`,`paper_id`
    INTO q_cnt, p_score, max_try, cfg_paper
    FROM `exam_config` WHERE id = NEW.config_id;
  IF q_cnt IS NULL THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '测评规则不存在，不能开考';
  END IF;
  -- 试卷与规则必须是同一份，否则抽题与判分各按各的走，且一路不报错
  IF NOT (NEW.`paper_id` <=> cfg_paper) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '这场考试的试卷与规则不是同一份：抽题按一份卷、判分按另一份';
  END IF;
  -- 这场考试挂的课程必须就是该卷所属的课程（COUNT/MAX 一次取回：卷不在时 p_cnt=0，
  -- 单独报「试卷不存在」而不是让它顺着 p_course 恒为 NULL 报成「课程不一致」）
  IF NEW.`paper_id` IS NOT NULL THEN
    SELECT COUNT(*), MAX(`course_id`) INTO p_cnt, p_course
      FROM `exam_paper` WHERE `id` = NEW.`paper_id`;
    IF p_cnt = 0 THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '这场考试挂的试卷不存在';
    END IF;
    IF NOT (NEW.`course_id` <=> p_course) THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '这场考试挂的课程与试卷所属课程不一致：换出来的会是一张挂错课的结业证明';
    END IF;
  END IF;
  -- 不限次补考等于没考（§3.3）
  IF NEW.attempt_no > max_try THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '已超出该课程的补考次数上限';
  END IF;
  IF NEW.score IS NOT NULL THEN
    IF NEW.score > q_cnt THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '得分不能超过卷面题量';
    END IF;
    IF NEW.submitted_at IS NULL THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '没有交卷时间的考试不能有分数';
    END IF;
    IF NEW.passed IS NOT NULL AND NEW.passed <> (NEW.score >= p_score) THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '通过标记必须与得分和及格线一致';
    END IF;
  END IF;
END$$

-- 更新侧与插入侧同源。缺了下面那三条的话，「章节练兵卷换不出结业证明」这道闸
-- 一条 UPDATE 就绕过去了：拿一条已通过的章节卷记录（paper_id=2、config_id=2、passed=1），
-- 直接发证会被 trg_certificate_precheck 拒；而 `UPDATE exam_attempt SET paper_id=1`
-- 成功之后（此后 paper_id 与 config_id 不再是同一份卷，正是插入侧明令禁止的形状），
-- 同一句 INSERT certificate 一次通过 —— 证书落库，事后与真证书不可分辨。
-- 本文件为 exam_paper 专门写了 trg_exam_paper_upd，理由是「光有插入侧不够，
-- 缺口正对着最危险的那条路」；同一条推理原样适用于这里。
CREATE TRIGGER `trg_exam_attempt_upd`
BEFORE UPDATE ON `exam_attempt` FOR EACH ROW
BEGIN
  DECLARE q_cnt INT DEFAULT NULL;
  DECLARE p_score INT DEFAULT NULL;
  DECLARE cfg_paper BIGINT UNSIGNED DEFAULT NULL;
  DECLARE p_course BIGINT UNSIGNED DEFAULT NULL;
  DECLARE p_cnt INT DEFAULT 0;
  SELECT `question_count`,`pass_score`,`paper_id` INTO q_cnt, p_score, cfg_paper
    FROM `exam_config` WHERE id = NEW.config_id;
  IF NEW.score IS NOT NULL THEN
    IF NEW.score > q_cnt THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '得分不能超过卷面题量';
    END IF;
    IF NEW.submitted_at IS NULL THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '没有交卷时间的考试不能有分数';
    END IF;
    IF NEW.passed IS NOT NULL AND NEW.passed <> (NEW.score >= p_score) THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '通过标记必须与得分和及格线一致';
    END IF;
  END IF;
  -- 交卷后不得改分：改分是发证依据被篡改的唯一入口
  IF OLD.submitted_at IS NOT NULL AND NOT (NEW.score <=> OLD.score) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '已交卷的考试不得改分';
  END IF;
  -- 交卷后也不得改卷：章节卷考完改成综合卷，换出来的就是一张假的结业证明
  IF OLD.submitted_at IS NOT NULL AND NOT (NEW.`paper_id` <=> OLD.`paper_id`) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '已交卷的考试不得改卷';
  END IF;
  -- 与插入侧同源的两条：卷与规则同一份、卷与课程同一门
  IF NOT (NEW.`paper_id` <=> cfg_paper) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '这场考试的试卷与规则不是同一份：抽题按一份卷、判分按另一份';
  END IF;
  IF NEW.`paper_id` IS NOT NULL THEN
    SELECT COUNT(*), MAX(`course_id`) INTO p_cnt, p_course
      FROM `exam_paper` WHERE `id` = NEW.`paper_id`;
    IF p_cnt = 0 THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '这场考试挂的试卷不存在';
    END IF;
    IF NOT (NEW.`course_id` <=> p_course) THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '这场考试挂的课程与试卷所属课程不一致：换出来的会是一张挂错课的结业证明';
    END IF;
  END IF;
END$$
DELIMITER ;


CREATE TABLE `exam_answer` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `attempt_id`  BIGINT UNSIGNED NOT NULL,
  `question_id` BIGINT UNSIGNED NOT NULL,
  `user_answer` VARCHAR(32)     NULL,
  `is_correct`  TINYINT(1)      NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_attempt_question` (`attempt_id`,`question_id`)
) ENGINE=InnoDB COMMENT='答题明细';


-- 测评报告（2026-09-03）。交完卷由 AI 出一份：这次考得怎么样、哪些知识点掌握到什么程度、
-- 接下来回看哪一讲的哪一段。
--
-- 报告分两层，这是整件事的地基：**事实层（stats）由代码算、不经过模型**，
-- 分数、按讲次与考点的正确率、错题清单，永远算得出来；叙述层（content）由模型写。
-- 模型会挂、会超时、会写错，而学员的分数与错题是客观的 —— 合成一段让模型一起写的话，
-- 模型一挂整页就空了，学员连自己错在哪都看不到。
--
-- 为什么是一张新表而不是 exam_attempt 上的几列：两者的生命周期正好相反。
-- exam_attempt 交完卷就是发证依据，那张表上有「已交卷不得改分」「已交卷不得改卷」两条闸；
-- 而报告在一分钟里要改三次状态（QUEUED→RUNNING→READY），失败还要退回 QUEUED 重来。
-- 把报告列挂上去，每一次状态流转都得从那两条闸下面过 —— 为了放它过去就得放宽判据，
-- 等于把「改分」那条闸的边界重新划一遍。
--
--   · attempt_id **唯一**：一场考试一份报告。没有它，重试或两个实例同时入队会插出第二行，
--     而学员端按 attempt 查报告 —— 两行同时存在时页面显示哪一份取决于查询顺序，
--     今天看到成功那份、刷新一下看到失败那份，而两行各自看都合法。
--   · user_id 是**越权判据本身**（报告页的 SQL 里 `AND user_id=?`，「不是你的」与「不存在」
--     对外同一种结果），必须与那场考试一致，由触发器守着。
--   · stats NOT NULL：QUEUED/RUNNING 时页面显示的就是它。允许为空的话，「模型还没跑完」
--     与「连分数都没有」在库里同形，分两层这件事就白做了。
--   · content 用 MEDIUMTEXT 不用 TEXT：TEXT 超长是静默截断，而截断一份报告的表现是
--     「最后一段话说到一半」—— 与模型自己写崩了长得一模一样。
--   · tries 给自愈用：清扫把卡住的 RUNNING 退回 QUEUED 时 +1，到 3 置 FAILED。
--   · 三个时点分开记：「排队排了多久」与「模型跑了多久」是两回事（实测一次 19.3 秒），
--     只留一个 updated_at 的话事后查不出那 19 秒花在哪一段。
CREATE TABLE `exam_report` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `attempt_id`   BIGINT UNSIGNED NOT NULL              COMMENT '一场考试一份报告；重试是改这一行，不是插新行',
  `user_id`      BIGINT UNSIGNED NOT NULL              COMMENT '越权判据就写在这一列上（AND user_id=?）；必须与那场考试一致',
  `paper_id`     BIGINT UNSIGNED NOT NULL              COMMENT '考的是哪份卷；行动清单要按它给「去考另一份卷」',
  `status`       ENUM('QUEUED','RUNNING','READY','FAILED') NOT NULL DEFAULT 'QUEUED'
                 COMMENT '学员端唯一的显示判据：转圈 / 正文 / 失败理由',
  `model`        VARCHAR(64)     NULL                  COMMENT '真正出这份正文的模型 id，不是配置里写的那个',
  `content`      MEDIUMTEXT      NULL                  COMMENT '叙述层正文（Markdown）；TEXT 超长会静默截断，故用 MEDIUMTEXT',
  `stats`        JSON            NOT NULL              COMMENT '事实层：分数、按讲次/考点的正确率、错题清单。模型挂了它照常显示',
  `fail_reason`  VARCHAR(255)    NULL                  COMMENT 'FAILED 时原样显示给学员，所以必须是人话',
  `tries`        INT             NOT NULL DEFAULT 0    COMMENT '清扫退回 QUEUED 时 +1；到 3 置 FAILED，不再无限重试',
  `requested_at` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `started_at`   DATETIME(3)     NULL                  COMMENT '抢到这一行的时刻；清扫按它判「卡住了」',
  `finished_at`  DATETIME(3)     NULL,
  `updated_at`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_report_attempt` (`attempt_id`),
  -- 后台那两句查：取活 WHERE status='QUEUED' ORDER BY requested_at（整条走索引）；
  -- 清扫 WHERE status='RUNNING' AND started_at < NOW()-10min（走 status 前缀再比 started_at，
  -- 扫的是此刻正在跑的那几行，不值得为它单开一条索引）。
  KEY `idx_report_queue` (`status`,`requested_at`)     COMMENT '后台取活与清扫走这条'
) ENGINE=InnoDB COMMENT='测评报告（事实层由代码算，叙述层由模型写）';

-- 报告的四条自洽。插入侧与更新侧判据同源，两处必须一起改
-- （唯一有意不同源的是①：更新侧对「已经孤儿的行」放行，理由见下面那段 ★★★）。
--
--   ① 挂的考试得存在。单独分支报「考试不存在」，不让它顺着 a_user 恒为 NULL 报成
--      「与那场考试对不上」—— 那句话会把人指到错的地方去。
--   ② **只能给已交卷的场次建报告。** 报告里每一个数都来自 exam_answer，没交卷的场次
--      一行明细都没有，那时算出来的事实层是「0 分、全错」，而它与真考了 0 分在库里
--      完全同形。学员看到一份说他全错的报告，而他的卷子还开着。
--   ③ **学员与试卷必须与那场考试一致。** 越权判据就是 exam_report.user_id，写错等于
--      把别人那场考试的错题清单发给这个人，而两行记录各自看都合法、一个字都不报。
--      比较用 <=>（NULL 安全）：exam_attempt.paper_id 是可空列，一边空一边非空时
--      `=` 的结果是 NULL，IF NULL 走 false 分支 = 放行。
--   ④ READY 必须有正文、FAILED 必须写明理由。
--
-- ★ 更新侧才是主战场，这一点与本仓其它表相反：整条状态流转都是 UPDATE
--   （抢占、落正文、置失败、清扫退回）。插入侧守的是「刚入队的那一行」（status 缺省
--   QUEUED、content 必然为空），而真正会写出「READY 却没有正文」的是落正文那一句 ——
--   模型返回空串，或者时间点校验把正文判废之后忘了把 status 一起改回去。
--
-- ★ 故意没有状态机闸门（不像 biz_order / withdraw_order 那样只许前进）：报告的两条
--   自愈路径都要往回走 —— 清扫把卡住的 RUNNING 退回 QUEUED（tries+1）、重试把 FAILED
--   退回 QUEUED。加一条「只能前进」的闸会把这两条一起拒掉。
--
-- ★★★ 「挂的考试不存在」在更新侧【只拦改挂，不拦已经孤儿的行】—— 唯一一处与插入侧不同。
--   全库零外键，删掉一场考试不会连带删它的报告，库里就留下一行孤儿报告；而清扫是
--   **整批** UPDATE（`WHERE status='RUNNING' AND started_at < NOW()-10min`，不带 id），
--   BEFORE UPDATE 对匹配到的每一行各跑一次 —— 孤儿那一行 SIGNAL 一响整条语句回滚，
--   同一批里真正卡住的行一行都退不回队列，表现是「全平台报告一直转圈」，
--   日志里只有一句没有指向的「报告清扫失败」；连运维都补救不了（那行任何 UPDATE 都被
--   同一条判据顶回来，改不成 FAILED，只能整行 DELETE）。已实测复现。
--   所以 a_cnt=0 且 attempt_id 没被改动时放行（孤儿行本来就无一致性可言，
--   a_user/a_paper/a_sub 全是 NULL，②③ 在它身上问不出任何东西）；
--   attempt_id 被改到一场不存在的考试上 = 有人在制造孤儿，照旧拒。
--   ④⑤ 与挂哪一场考试无关，无条件守，孤儿行也不许变成「READY 却一页空白」。
--
-- 判空不用 TRIM：模型返回的空常常是「两个换行」或一个全角空格，而 TRIM 只去半角空格
-- （实测 CHAR_LENGTH(TRIM('  \n  '))=1，判据会认为「有正文」而放行）。
-- REGEXP_REPLACE 去掉全部空白字符再数长度，换行、制表、全角空格三种都归零。
DELIMITER $$
CREATE TRIGGER `trg_exam_report_precheck`
BEFORE INSERT ON `exam_report` FOR EACH ROW
BEGIN
  DECLARE a_cnt   INT DEFAULT 0;
  DECLARE a_user  BIGINT UNSIGNED DEFAULT NULL;
  DECLARE a_paper BIGINT UNSIGNED DEFAULT NULL;
  DECLARE a_sub   DATETIME(3) DEFAULT NULL;
  SELECT COUNT(*), MAX(`user_id`), MAX(`paper_id`), MAX(`submitted_at`)
    INTO a_cnt, a_user, a_paper, a_sub
    FROM `exam_attempt` WHERE `id` = NEW.`attempt_id`;
  IF a_cnt = 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '这份报告挂的考试不存在';
  END IF;
  IF a_sub IS NULL THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '没交卷的场次不能出测评报告：一行答题明细都没有，算出来的「全错」与真考零分同形';
  END IF;
  IF NOT (NEW.`user_id` <=> a_user) OR NOT (NEW.`paper_id` <=> a_paper) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '报告的学员或试卷与那场考试对不上：越权判据就写在这两列上';
  END IF;
  IF NEW.`status` = 'READY'
     AND (NEW.`content` IS NULL OR CHAR_LENGTH(REGEXP_REPLACE(NEW.`content`, '[[:space:]]', '')) = 0) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'READY 的报告必须有正文：状态说已生成而正文是空的，学员看到的是一页空白';
  END IF;
  IF NEW.`status` = 'FAILED'
     AND (NEW.`fail_reason` IS NULL OR CHAR_LENGTH(REGEXP_REPLACE(NEW.`fail_reason`, '[[:space:]]', '')) = 0) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'FAILED 的报告必须写明理由：不写理由的失败，学员和运维都无从下手';
  END IF;
END$$

CREATE TRIGGER `trg_exam_report_upd`
BEFORE UPDATE ON `exam_report` FOR EACH ROW
BEGIN
  DECLARE a_cnt   INT DEFAULT 0;
  DECLARE a_user  BIGINT UNSIGNED DEFAULT NULL;
  DECLARE a_paper BIGINT UNSIGNED DEFAULT NULL;
  DECLARE a_sub   DATETIME(3) DEFAULT NULL;
  SELECT COUNT(*), MAX(`user_id`), MAX(`paper_id`), MAX(`submitted_at`)
    INTO a_cnt, a_user, a_paper, a_sub
    FROM `exam_attempt` WHERE `id` = NEW.`attempt_id`;
  IF a_cnt = 0 THEN
    -- 见上面那段 ★★★：孤儿行放行（否则一行孤儿毒死整批清扫），改挂到不存在的场次照旧拒
    IF NOT (NEW.`attempt_id` <=> OLD.`attempt_id`) THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '这份报告挂的考试不存在';
    END IF;
  ELSE
    IF a_sub IS NULL THEN
      SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = '没交卷的场次不能出测评报告：一行答题明细都没有，算出来的「全错」与真考零分同形';
    END IF;
    IF NOT (NEW.`user_id` <=> a_user) OR NOT (NEW.`paper_id` <=> a_paper) THEN
      SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = '报告的学员或试卷与那场考试对不上：越权判据就写在这两列上';
    END IF;
  END IF;
  -- ④⑤ 与挂哪一场考试无关，孤儿行也照守
  IF NEW.`status` = 'READY'
     AND (NEW.`content` IS NULL OR CHAR_LENGTH(REGEXP_REPLACE(NEW.`content`, '[[:space:]]', '')) = 0) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'READY 的报告必须有正文：状态说已生成而正文是空的，学员看到的是一页空白';
  END IF;
  IF NEW.`status` = 'FAILED'
     AND (NEW.`fail_reason` IS NULL OR CHAR_LENGTH(REGEXP_REPLACE(NEW.`fail_reason`, '[[:space:]]', '')) = 0) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'FAILED 的报告必须写明理由：不写理由的失败，学员和运维都无从下手';
  END IF;
END$$
DELIMITER ;


-- 措辞只能是「课程结业证明」，不得出现「认证」「资格」（G14）。
CREATE TABLE `certificate` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `cert_no`    VARCHAR(32)     NOT NULL              COMMENT '可验真编号',
  `user_id`    BIGINT UNSIGNED NOT NULL,
  `course_id`  BIGINT UNSIGNED NOT NULL,
  `attempt_id` BIGINT UNSIGNED NOT NULL,
  `issued_at`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `revoked_at` DATETIME(3)     NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cert_no` (`cert_no`),
  UNIQUE KEY `uk_user_course` (`user_id`,`course_id`),
  CONSTRAINT `ck_cert_revoke` CHECK (`revoked_at` IS NULL OR `revoked_at` >= `issued_at`)
) ENGINE=InnoDB COMMENT='结业证明';

-- 多套卷之后追加了第二条判据：这场考试所属的试卷必须发证。
--
-- 原来三条问的是「是不是一次真通过的考试、是不是这个人、是不是这门课」，
-- 问不到「这份卷凭什么发证」—— 而同一门课下有四份 50 题的章节练兵卷，
-- 考过任何一份都满足原来那三条。不加这条，看完第一章考过 50 题就能换出
-- 「课程结业证明」，而它与考完 100 题综合卷换出来的那张在库里一模一样、事后不可分辨。
--
-- ★ 用 JOIN 而不是「paper_id 为空就放行」：attempt.paper_id 为空恰恰是最该拒的情形 ——
--   那是一场没人说得清考的是哪份卷的考试，拿它换出来的证明也就说不清证明了什么。
DELIMITER $$
CREATE TRIGGER `trg_certificate_precheck`
BEFORE INSERT ON `certificate` FOR EACH ROW
BEGIN
  -- 证明是对外物料，签发依据必须是一次真实通过的考试，且考的就是这个人这门课
  IF NOT EXISTS (SELECT 1 FROM `exam_attempt`
                 WHERE id = NEW.attempt_id
                   AND passed = 1
                   AND user_id   = NEW.user_id
                   AND course_id = NEW.course_id) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '结业证明只能签发给该学员该门课一次已通过的考试';
  END IF;
  -- 那份卷得是发证的卷：章节练兵卷考过 50 题不等于结业
  IF NOT EXISTS (SELECT 1 FROM `exam_attempt` `a`
                   JOIN `exam_paper` `p` ON `p`.`id` = `a`.`paper_id`
                  WHERE `a`.`id` = NEW.attempt_id
                    AND `p`.`grants_certificate` <> 0) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '这份试卷不发结业证明：章节练兵卷与开放卷考过了也不等于结业';
  END IF;
END$$
DELIMITER ;


-- =============================================================================
--  J. 答疑（懂妈）
-- =============================================================================

CREATE TABLE `qa_ticket` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`         BIGINT UNSIGNED NOT NULL,
  `course_id`       BIGINT UNSIGNED NOT NULL,
  `lesson_id`       BIGINT UNSIGNED NULL,
  `title`           VARCHAR(255)    NOT NULL,
  `status`          ENUM('AI_ANSWERED','ESCALATED','HUMAN_ANSWERED','CLOSED') NOT NULL DEFAULT 'AI_ANSWERED',
  `ai_confidence`   DECIMAL(4,3)    NULL                  COMMENT '低于阈值自动升级人工',
  `escalate_reason` ENUM('LOW_CONFIDENCE','USER_REQUEST','SAFETY_BLOCK') NULL,
  `assignee_id`     BIGINT UNSIGNED NULL,
  `sla_due_at`      DATETIME(3)     NULL,
  `published_faq_id` BIGINT UNSIGNED NULL                 COMMENT '沉淀进公开问答库',
  `created_at`      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at`      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_user` (`user_id`,`created_at`),
  KEY `idx_queue` (`status`,`sla_due_at`),
  -- 置信度是「低于阈值升级人工」的判据。DECIMAL(4,3) 装得下 9.999，
  -- 一个恒大于任何阈值的置信度会让升级通道永远不触发。
  CONSTRAINT `ck_qa_confidence` CHECK (
    `ai_confidence` IS NULL OR (`ai_confidence` >= 0 AND `ai_confidence` <= 1))
) ENGINE=InnoDB COMMENT='答疑工单';


CREATE TABLE `qa_message` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `ticket_id`    BIGINT UNSIGNED NOT NULL,
  `sender_type`  ENUM('STUDENT','AI','MENTOR') NOT NULL,
  `sender_id`    BIGINT UNSIGNED NULL,
  `content`      TEXT            NOT NULL,
  `citations`    JSON            NULL                  COMMENT '[{lesson_id, ts_sec, snippet}] 带出处作答',
  `safety_verdict` ENUM('PASS','BLOCKED') NOT NULL DEFAULT 'PASS' COMMENT '双侧内容安全（G16）',
  `created_at`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_ticket` (`ticket_id`,`id`)
) ENGINE=InnoDB COMMENT='答疑消息';


CREATE TABLE `faq_entry` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `course_id`  BIGINT UNSIGNED NOT NULL,
  `question`   VARCHAR(512)    NOT NULL,
  `answer`     TEXT            NOT NULL,
  `citations`  JSON            NULL,
  `hit_count`  INT             NOT NULL DEFAULT 0,
  `status`     ENUM('ON','OFF') NOT NULL DEFAULT 'ON',
  `created_by` BIGINT UNSIGNED NOT NULL,
  `created_at` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_course` (`course_id`,`status`),
  FULLTEXT KEY `ft_question` (`question`) WITH PARSER ngram
) ENGINE=InnoDB COMMENT='公开问答库';


-- 懂妈的提问配额：AI 会成为课程内容的泄漏面，必须限（§3.1 的闸）。
CREATE TABLE `qa_quota_usage` (
  `user_id`     BIGINT UNSIGNED NOT NULL,
  `stat_date`   DATE            NOT NULL,
  `ask_count`   INT             NOT NULL DEFAULT 0,
  `blocked_count` INT           NOT NULL DEFAULT 0     COMMENT '被判为批量抽取型请求而拒绝的次数',
  PRIMARY KEY (`user_id`,`stat_date`),
  -- 负的已用次数等于把配额刷回去，配额就形同虚设
  CONSTRAINT `ck_qa_quota` CHECK (`ask_count` >= 0 AND `blocked_count` >= 0)
) ENGINE=InnoDB COMMENT='答疑配额';


-- =============================================================================
--  K. 发票与审计
-- =============================================================================

CREATE TABLE `invoice_request` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `applicant_user_id` BIGINT UNSIGNED NOT NULL,
  `scope`       ENUM('ORDER','WITHDRAW') NOT NULL,
  `ref_id`      BIGINT UNSIGNED NOT NULL,
  `title`       VARCHAR(128)    NOT NULL,
  `tax_no`      VARCHAR(32)     NULL,
  `amount_fen`  BIGINT          NOT NULL,
  `status`      ENUM('APPLIED','ISSUED','REJECTED') NOT NULL DEFAULT 'APPLIED',
  `invoice_no`  VARCHAR(64)     NULL,
  `issued_at`   DATETIME(3)     NULL,
  -- 这三样由 20260811_发票号唯一与驳回原因.sql 加在生产上，却【漏了回灌到这里】。
  -- 后果不是「新库少几列」那么轻：
  --   · InvoiceService.mine() 明文 SELECT reject_reason，新装的库上直接 500 ——
  --     学员点开「我的 → 发票」就是一句系统繁忙；
  --   · uk_invoice_no 不在，同一个发票号能填进两条申请，账面上一张票开了两笔收入，
  --     而两条记录各自看都合法、不报任何错。那条唯一键正是为这件事加的。
  -- 集成测试全部从这个文件建库，却没有一条覆盖 mine()，于是 360 条全绿也照不出来。
  `reject_reason` VARCHAR(255)   NULL COMMENT '驳回原因，申请人可见',
  `processed_by`  BIGINT UNSIGNED NULL COMMENT '开票 / 驳回的操作人',
  `created_at`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_scope_ref` (`scope`,`ref_id`),
  -- 允许多个 NULL（未开票的行不受影响），只挡「同一个票号填两次」
  UNIQUE KEY `uk_invoice_no` (`invoice_no`),
  KEY `idx_applicant` (`applicant_user_id`),
  CONSTRAINT `ck_invoice_amount` CHECK (`amount_fen` > 0)
) ENGINE=InnoDB COMMENT='开票申请';


-- 六个高争议动作必须留痕：开户、改价、改佣金规则、作废码、退款、提现打款。

CREATE TABLE `sms_code` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `phone`       VARCHAR(20)     NOT NULL,
  `scene`       ENUM('REGISTER','LOGIN','RESET','BIND') NOT NULL,
  `code_hash`   CHAR(64)        NOT NULL COMMENT 'SHA-256(码 + 全局盐)，不存明文',
  `expires_at`  DATETIME(3)     NOT NULL,
  `attempts`    INT             NOT NULL DEFAULT 0 COMMENT '已校验失败次数',
  `consumed_at` DATETIME(3)     NULL COMMENT '一次性消费，用过即作废',
  `client_ip`   VARCHAR(45)     NULL,
  `provider_biz_id` VARCHAR(64) NULL COMMENT '阿里云回执号，投诉时要拿它查',
  `created_at`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  -- 限流全靠这条索引：按手机号+场景+时间倒查最近一条与当日条数
  KEY `idx_phone_scene_time` (`phone`,`scene`,`created_at`),
  KEY `idx_created` (`created_at`),
  CONSTRAINT `ck_sms_expire` CHECK (`expires_at` > `created_at`),
  CONSTRAINT `ck_sms_attempts` CHECK (`attempts` >= 0)
) ENGINE=InnoDB COMMENT='短信验证码';

CREATE TABLE `sms_daily_quota` (
  `stat_date`   DATE            NOT NULL,
  `sent_count`  INT             NOT NULL DEFAULT 0,
  PRIMARY KEY (`stat_date`),
  CONSTRAINT `ck_sms_quota` CHECK (`sent_count` >= 0)
) ENGINE=InnoDB COMMENT='短信日发送量';

CREATE TABLE `audit_log` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `operator_id` BIGINT UNSIGNED NULL,
  `action`      VARCHAR(48)     NOT NULL,
  `target_type` VARCHAR(32)     NOT NULL,
  `target_id`   VARCHAR(64)     NOT NULL,
  `before_json` JSON            NULL,
  `after_json`  JSON            NULL,
  `client_ip`   VARCHAR(45)     NULL,
  `created_at`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_target` (`target_type`,`target_id`),
  KEY `idx_operator_time` (`operator_id`,`created_at`)
) ENGINE=InnoDB COMMENT='审计日志';


-- =============================================================================
--  L. 统计（月度销售，录音 01:06）
--  三个口径同时出：采购、动销、库存。真正反映渠道健康的是动销。
-- =============================================================================

CREATE TABLE `agent_monthly_stat` (
  `id`                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `agent_id`            BIGINT UNSIGNED NOT NULL,
  `stat_month`          CHAR(7)         NOT NULL          COMMENT 'YYYY-MM，按支付时间归月',
  `purchased_codes`     INT             NOT NULL DEFAULT 0 COMMENT '口径一：采购码数',
  `purchased_amount_fen` BIGINT         NOT NULL DEFAULT 0 COMMENT '口径一：采购额（我们的收入）',
  `activated_codes`     INT             NOT NULL DEFAULT 0 COMMENT '口径二：动销，本月被激活的码数',
  `direct_purchase_count` INT           NOT NULL DEFAULT 0 COMMENT '口径二：归属本代理的学员直购单数',
  `unactivated_stock`   INT             NOT NULL DEFAULT 0 COMMENT '口径三：期末未激活库存',
  `sub_agent_purchased_amount_fen` BIGINT NOT NULL DEFAULT 0 COMMENT '下级汇总（仅一级可见，不给明细防挖客）',
  `commission_earned_fen` BIGINT        NOT NULL DEFAULT 0,
  `computed_at`         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_month` (`agent_id`,`stat_month`),
  KEY `idx_month` (`stat_month`),
  -- CHAR(7) 装得下 'ABCDEFG'，不代表它是个月份。月份写错不会报错，
  -- 只会让那一个月的数据从报表里凭空消失。
  CONSTRAINT `ck_stat_month` CHECK (`stat_month` REGEXP '^[0-9]{4}-(0[1-9]|1[0-2])$'),
  CONSTRAINT `ck_stat_nonneg` CHECK (
    `purchased_codes` >= 0 AND `purchased_amount_fen` >= 0
    AND `activated_codes` >= 0 AND `direct_purchase_count` >= 0
    AND `unactivated_stock` >= 0)
) ENGINE=InnoDB COMMENT='代理商月度统计';


-- =============================================================================
--  讲次上架前的水印闸门
--
--  上架是视频第一次对学员可见的那一刻。此刻若水印布局是错的，从这一刻起投递出去的
--  每一份副本都不可取证，而且【补不回来】—— 已经流出去的东西没法追加水印。
--
--  为什么必须在触发器里：CHECK 只能约束单行，管不了"两讲的位区间不许重叠"这种跨行条件；
--  应用层的检查又总有绕过去的路径（后台改状态、跑脚本、手工 UPDATE）。
--  试听片段（is_preview=1）豁免：它本来就是公开物料，不带水印是有意的。
-- =============================================================================
DELIMITER $$

DROP PROCEDURE IF EXISTS `sp_lesson_publish_gate`$$
CREATE PROCEDURE `sp_lesson_publish_gate`(
    IN p_lesson_id BIGINT UNSIGNED, IN p_course_id BIGINT UNSIGNED,
    IN p_segment_count INT, IN p_stride INT, IN p_slot_count INT, IN p_bit_offset INT,
    IN p_chapter_id BIGINT UNSIGNED, IN p_hls_key VARCHAR(255), IN p_bit_base INT)
BEGIN
  DECLARE v_bit_length INT DEFAULT NULL;
  DECLARE v_overlap INT DEFAULT 0;
  DECLARE v_key_count INT DEFAULT 0;
  DECLARE v_distinct_keys INT DEFAULT 0;
  DECLARE v_blocks INT DEFAULT 0;
  DECLARE v_block_segs INT DEFAULT 0;
  DECLARE v_slots INT DEFAULT 0;
  DECLARE v_first INT DEFAULT NULL;
  DECLARE v_store INT DEFAULT 0;
  DECLARE v_ch_key VARCHAR(255) DEFAULT NULL;
  DECLARE v_ch_base INT DEFAULT NULL;
  DECLARE m INT; DECLARE lim INT;

  IF p_stride <= 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '水印取样步长必须为正，否则本讲一个槽位都没有';
  END IF;

  SELECT `hls_key`, `wm_bit_base`, `store_segment_count`
    INTO v_ch_key, v_ch_base, v_store
    FROM `chapter` WHERE `id` = p_chapter_id;
  IF v_ch_key IS NULL THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '本讲没有挂在章下，或该章还没登记片库目录';
  END IF;
  -- 讲次的 hls_key 与所在章对不上，播放列表会去另一个目录取片。
  -- 那个目录里同名的片是【别的内容】，播出来是另一节课，而这不会报错。
  IF p_hls_key <> v_ch_key THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '讲次的片库目录与所在章不一致：会去另一个目录取到同名但内容不同的片';
  END IF;
  IF p_bit_base <> v_ch_base THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '讲次的 wm_bit_base 与所在章不一致：投递侧算出的码位会整体错位，而错位的取证不报错';
  END IF;

  SET m = p_stride DIV 2;
  SET lim = (v_store DIV p_stride) * p_stride;   -- 打包脚本的槽位上界：slot < 片数/stride

  SELECT COUNT(*), COALESCE(SUM(`seg_to` - `seg_from`), 0),
         COALESCE(SUM(FLOOR((LEAST(`seg_to`,   lim) + p_stride - 1 - m) / p_stride)
                    - FLOOR((LEAST(`seg_from`, lim) + p_stride - 1 - m) / p_stride)), 0)
    INTO v_blocks, v_block_segs, v_slots
    FROM `lesson_segment_block` WHERE `lesson_id` = p_lesson_id;

  IF v_blocks = 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '讲次还没有分片区间（lesson_segment_block 为空）：先以 DRAFT 插入、灌完区间再置 ON';
  END IF;
  IF p_segment_count <> v_block_segs THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'segment_count 与分片区间的长度对不上：取片限速的阈值按它算，对不上会让闸门看起来在工作但阈值是错的';
  END IF;
  -- 槽位数按【区间实际覆盖到的槽位片】算，不是 segment_count/stride。
  -- 区间起点不在 stride 边界上时两者会差 1，而差 1 就是整段码字错位一位。
  IF p_slot_count <> v_slots THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'wm_slot_count 与分片区间覆盖到的槽位数对不上：布局是按旧切法算的，须重新分配位区间';
  END IF;

  SELECT MIN(p_bit_base + FLOOR((LEAST(`seg_from`, lim) + p_stride - 1 - m) / p_stride))
    INTO v_first
    FROM `lesson_segment_block`
   WHERE `lesson_id` = p_lesson_id
     AND FLOOR((LEAST(`seg_to`,   lim) + p_stride - 1 - m) / p_stride)
       > FLOOR((LEAST(`seg_from`, lim) + p_stride - 1 - m) / p_stride);
  IF v_first IS NULL THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '本讲一个水印槽位都没有：投出去的副本无法取证';
  END IF;
  IF p_bit_offset <> v_first THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'wm_bit_offset 与分片区间算出的首个码位对不上：取证会从错的位读起，指认到别人身上';
  END IF;

  SELECT `bit_length` INTO v_bit_length FROM `wm_scheme`
   WHERE `course_id` = p_course_id AND `status` = 'ACTIVE';
  IF v_bit_length IS NULL THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '该课程还没有现行水印码本，不能上架正片';
  END IF;
  IF p_bit_offset + p_slot_count > v_bit_length THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '本讲的位区间超出码本长度：超出的那一段取不到码字位，等于后半程没有水印';
  END IF;

  SELECT COUNT(*) INTO v_overlap FROM `lesson`
   WHERE `course_id` = p_course_id AND `id` <> p_lesson_id
     AND `is_preview` = 0
     AND p_bit_offset < `wm_bit_offset` + `wm_slot_count`
     AND `wm_bit_offset` < p_bit_offset + p_slot_count;
  IF v_overlap > 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '本讲的位区间与同课程另一讲重叠：重叠的那一段等于白费的码长，抗合谋能力会低于设计值';
  END IF;

  SELECT COUNT(*) INTO v_key_count FROM `lesson_content_key`
   WHERE `lesson_id` = p_lesson_id AND `status` = 'ACTIVE';
  IF v_key_count <> 1 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '本讲尚未封装现行内容密钥，上架后学员将无法播放';
  END IF;

  -- 同一片库的各讲共用同一批分片，所以内容密钥必须逐字节相同。
  -- 只给其中一讲换了密钥，另外几讲的播放器会拿错的 key 解出一堆垃圾字节 ——
  -- 播放器不报"密钥错"，它报的是解码失败或者干脆花屏。
  SELECT COUNT(DISTINCT `key_enc`) INTO v_distinct_keys FROM (
      SELECT k.`key_enc` FROM `lesson` l2
        JOIN `lesson_content_key` k ON k.lesson_id = l2.id AND k.status = 'ACTIVE'
       WHERE l2.chapter_id = p_chapter_id AND l2.status = 'ON'
         AND l2.is_preview = 0 AND l2.id <> p_lesson_id
      UNION ALL
      SELECT k.`key_enc` FROM `lesson_content_key` k
       WHERE k.lesson_id = p_lesson_id AND k.status = 'ACTIVE') t;
  IF v_distinct_keys > 1 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '同一片库下各讲的内容密钥不一致：分片是共用的，密钥不同的那几讲会解出垃圾字节而不是报错';
  END IF;
END$$

DROP TRIGGER IF EXISTS `trg_lesson_publish_gate_ins`$$
CREATE TRIGGER `trg_lesson_publish_gate_ins`
BEFORE INSERT ON `lesson` FOR EACH ROW
BEGIN
  IF NEW.status = 'ON' AND NEW.is_preview = 0 THEN
    CALL `sp_lesson_publish_gate`(NEW.id, NEW.course_id, NEW.segment_count,
                                  NEW.wm_slot_stride, NEW.wm_slot_count, NEW.wm_bit_offset,
                                  NEW.chapter_id, NEW.hls_key, NEW.wm_bit_base);
  END IF;
END$$

DROP TRIGGER IF EXISTS `trg_lesson_publish_gate_upd`$$
CREATE TRIGGER `trg_lesson_publish_gate_upd`
BEFORE UPDATE ON `lesson` FOR EACH ROW
BEGIN
  IF NEW.status = 'ON' AND NEW.is_preview = 0 THEN
    CALL `sp_lesson_publish_gate`(NEW.id, NEW.course_id, NEW.segment_count,
                                  NEW.wm_slot_stride, NEW.wm_slot_count, NEW.wm_bit_offset,
                                  NEW.chapter_id, NEW.hls_key, NEW.wm_bit_base);
  END IF;
END$$

DELIMITER ;

SET FOREIGN_KEY_CHECKS = 1;

-- =============================================================================
--  未加外键约束是有意的：高并发写入路径（订单/心跳/风控）上外键会带来锁竞争，
--  引用完整性由应用层 + 上面这些唯一键与 CHECK 保证。
--  唯一的例外是分销层级——它是合规红线，所以用触发器在数据库层强制。
-- =============================================================================
