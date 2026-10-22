-- 播放令牌续签会刷新 play_session.last_seen_at。早期 schema 漏了这一列，
-- 导致视频播放约 90 秒后首次 renew 直接 500，长视频必然中断。

USE `wangke`;

SET @has_play_last_seen := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE()
     AND TABLE_NAME = 'play_session'
     AND COLUMN_NAME = 'last_seen_at'
);
SET @play_last_seen_ddl := IF(@has_play_last_seen = 0,
  'ALTER TABLE `play_session` ADD COLUMN `last_seen_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT ''最近一次播放令牌续签时间'' AFTER `started_at`',
  'DO 1');
PREPARE play_last_seen_stmt FROM @play_last_seen_ddl;
EXECUTE play_last_seen_stmt;
DEALLOCATE PREPARE play_last_seen_stmt;
