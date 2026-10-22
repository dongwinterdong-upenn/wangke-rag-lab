-- =============================================================================
--  2026-08-11 · 讲次内容密钥 + 心跳记账时钟
--
--  ── 缺口一：AES-128 内容密钥在库里没有任何落脚点 ─────────────────────────
--  HLS 的 #EXT-X-KEY 指向一个必须凭令牌才取得到的 URI，但「取得到什么」这件事
--  在这套 schema 里是空的 —— 没有一张表存过任何一讲的内容密钥。
--  于是只剩三条路，每条都是错的：
--    ① 全课程共用一把写死在配置里的密钥 —— 一讲的密钥泄漏 = 全课程泄漏，
--       而且轮换必须重转码全部视频，实际上等于永不轮换；
--    ② 播放时现场生成 —— 生成出来的密钥和当初加密分片用的那把不是同一把，
--       播放器报的是「解密失败」，服务端一条日志都没有；
--    ③ 明文存库 —— 拖库即拿到全部片源，加密这件事从头到尾没有发生过。
--  这张表走第四条：每讲一把、密文存储、KEK（密钥的密钥）在应用配置不入库。
--
--  ── 缺口二：watched_sec 的记账时钟与「最后活跃时间」共用一列 ──────────────
--  有效观看时长只能按服务端墙钟增量发放（前端心跳可以伪造，墙钟不能）。
--  这个增量要靠「上次记账时刻」算。若直接用 last_seen_at，进度上报（不记账）
--  也会把它刷新，客户端每 5 秒报一次进度就把心跳的墙钟增量压到 5 秒 ——
--  有效观看时长被系统性少记，学员永远学不满，而这件事不会报任何错，
--  表现只是「进度条卡在 90% 上不去」。所以记账时钟必须是独立的一列。
-- =============================================================================

USE `wangke`;

CREATE TABLE IF NOT EXISTS `lesson_content_key` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `lesson_id`   BIGINT UNSIGNED NOT NULL,
  `course_id`   BIGINT UNSIGNED NOT NULL              COMMENT '冗余一份，按课批量轮换/盘点时不必回表 lesson',
  `key_version` INT             NOT NULL DEFAULT 1    COMMENT '轮换即新增一版，旧版留着 —— 已发出去的分片还得能解',
  `algo`        VARCHAR(16)     NOT NULL DEFAULT 'AES-128'
                COMMENT 'HLS EXT-X-KEY METHOD=AES-128，内容密钥固定 16 字节',
  -- AES-256-GCM 密文，AAD 绑定 (lesson_id, key_version)。
  -- 绑 AAD 的理由与 activation_code.code_enc 完全一致：拿到库写权限的人
  -- 把 A 讲的密文复制到 B 讲时，解密会当场失败（AEADBadTagException），
  -- 而不是安静地解出另一讲的密钥。加密本身拦不住「密文搬家」。
  `key_enc`     VARBINARY(128)  NOT NULL              COMMENT 'AES-256-GCM(16 字节内容密钥)；KEK 在应用配置，不入库',
  `kek_id`      VARCHAR(32)     NOT NULL DEFAULT 'v1' COMMENT '用哪把 KEK 加的。换 KEK 时要能分辨哪些行还没重封',
  `status`      ENUM('ACTIVE','RETIRED') NOT NULL DEFAULT 'ACTIVE',
  -- 每讲至多一把现行密钥。两把并存时，下发哪把、分片当初用的是哪把，
  -- 全凭查询顺序决定，而选错的那一天服务端没有任何症状 —— 症状全在播放器那侧。
  `active_flag` TINYINT GENERATED ALWAYS AS (IF(`status` = 'ACTIVE', 1, NULL)) STORED,
  `created_at`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `retired_at`  DATETIME(3)     NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_lesson_version` (`lesson_id`,`key_version`),
  UNIQUE KEY `uk_lesson_active`  (`lesson_id`,`active_flag`),
  KEY `idx_course` (`course_id`,`status`),
  CONSTRAINT `ck_lesson_key_version` CHECK (`key_version` > 0),
  -- 16 字节明文经 GCM（12 字节 IV + 16 字节密文 + 16 字节 tag）必然是 44 字节。
  -- 短于 44 说明写进去的极可能【就是明文密钥本身】—— 那一刻这张表存在的全部意义
  -- 被取消掉了，而除了这条 CHECK 之外不会有任何其它症状：查询照常、播放照常。
  CONSTRAINT `ck_lesson_key_sealed` CHECK (LENGTH(`key_enc`) >= 44 AND LENGTH(`key_enc`) <= 128)
) ENGINE=InnoDB COMMENT='讲次内容密钥（密文存储，KEK 在应用配置不入库）';


-- learn_progress 增列。用动态 SQL 判存在再改，是为了让这份迁移可重复执行：
-- MySQL 的 ADD COLUMN 没有 IF NOT EXISTS，直接写会让「已经合进 schema.sql 之后
-- 再跑一次迁移」以报错收场，而那种报错看起来像是迁移写错了。
SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
                  WHERE TABLE_SCHEMA = DATABASE()
                    AND TABLE_NAME   = 'learn_progress'
                    AND COLUMN_NAME  = 'last_credit_at');
-- 注意用 CONCAT 而不是 ||：MySQL 默认 || 是逻辑或，不是字符串拼接，
-- 拼出来的会是一个布尔值 0，PREPARE 报的错跟这份迁移想做的事毫无关系。
SET @ddl := IF(@has_col = 0,
  CONCAT('ALTER TABLE `learn_progress` ADD COLUMN `last_credit_at` DATETIME(3) NULL ',
         'COMMENT ''上次给 watched_sec 记账的时刻。与 last_seen_at 分开：进度上报也刷新 last_seen_at，',
         '共用一列会把心跳的墙钟增量压小，有效观看时长被系统性少记且不报错'' ',
         'AFTER `heartbeat_count`'),
  'DO 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
