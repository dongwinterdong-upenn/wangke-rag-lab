-- =============================================================================
--  2026-08-11 · 短信验证码
--
--  验证码是账号体系里唯一一处「凭一条短信就能拿到账号」的入口，所以它同时是
--  三种攻击的靶子，三种都要在表结构上留下防线：
--
--    暴力猜码   —— 6 位数字只有 100 万种。不限次数的话，一个有效期内就能撞开。
--                   → attempts 计数 + 达到上限即作废（不是"这次不算"，是整条码作废）
--    短信轰炸   —— 拿别人手机号刷发送，成本全在平台，骚扰全给机主。
--                   → 同号冷却 / 同号日限 / 全局日限，三层都要，缺一层就有绕法
--    撞库回放   —— 一条码被用过之后还能再用。
--                   → consumed_at 一次性消费，且靠唯一键而不是"先查再改"
--
--  ★ 码本身不落明文。库被读走时，明文码等于一把把可用的账号钥匙；
--    存 SHA-256(码 + 全局盐) 之后，读到的只是一串没用的哈希。
--    这跟激活码 code_hash 是同一个道理，只是那边还要能取回明文（发给代理），
--    这边不需要 —— 所以这边可以只存单向哈希，更干净。
-- =============================================================================

USE `wangke`;

CREATE TABLE IF NOT EXISTS `sms_code` (
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

-- 全局日发送量的水位。放单独一张表而不是 COUNT(sms_code)：
-- 后者随表增长越查越慢，而这是每次发送都要查的路径。
CREATE TABLE IF NOT EXISTS `sms_daily_quota` (
  `stat_date`   DATE            NOT NULL,
  `sent_count`  INT             NOT NULL DEFAULT 0,
  PRIMARY KEY (`stat_date`),
  CONSTRAINT `ck_sms_quota` CHECK (`sent_count` >= 0)
) ENGINE=InnoDB COMMENT='短信日发送量';

-- 登录会话。JWT 本身是无状态的，但「改密后旧令牌立刻失效」「后台踢下线」
-- 这两件事无状态做不到，所以留一张令牌版本表：签发时把版本写进 claim，
-- 校验时比对，改密/踢人就 +1。
ALTER TABLE `sys_user`
  ADD COLUMN `token_version` INT NOT NULL DEFAULT 0
      COMMENT '令牌版本。改密或踢下线时 +1，旧令牌立刻失效' AFTER `must_change_pwd`;
