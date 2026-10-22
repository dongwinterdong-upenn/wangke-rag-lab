-- 讲次内容分节：把一段三小时的整讲录屏，按【课上真的在讲什么】切成可点击的目录。
--
-- ★ 这份迁移里的【触发器必须用 root 连接执行】，不能用应用账号：
--   COURSE 的 MySQL 开着 binlog 而 log_bin_trust_function_creators=0，
--   应用账号没有 SUPER/TRIGGER 权限，建触发器会报
--   「ERROR 1419: You do not have the SUPER privilege and binary logging is enabled」。
--   更坏的是这个错<b>只打在触发器那几行上</b>——CREATE TABLE 已经成功了，
--   于是表建出来了、闸门一个都没有，而后续插入全都通过。
--   库里现有的触发器 definer 全是 root@localhost，这一条与它们一致。
--       ssh COURSE 'mysql wangke < 这个文件'
--
-- ## 为什么是「一讲之内的时间点」，而不是把它切成更多讲次
--
-- 直觉上「分节」= 把 1 个 3 小时的 lesson 拆成 20 个 10 分钟的 lesson。那条路走不通，
-- 而且不是因为麻烦，是因为它会弄坏三样已经在生产里跑着的东西：
--
--   ① **水印位区间已经锁死。** wm_codeword 已经发出去 2 份，而 WatermarkService.assignBitOffsets
--      在有任何一份码字发出之后就拒绝重排 —— 重排会让已发出副本的取证整体错位，
--      而错位的打分不报错，只会指认到无辜的人身上。
--   ② **分片文件是按讲次目录编号的**（fde/l3/0.ts … 1153.ts）。拆讲次要给 lesson 加
--      segment_offset 这一维，M3u8Builder / 取证 / 上架闸门全都要跟着改。
--   ③ **learn_progress 按 (user, lesson) 唯一。** 拆完之后已有的观看进度无处安放：
--      要么丢掉，要么按时长瞎分摊 —— 后者算出来的每一个数字都是编的。
--
-- 而学员真正要的东西（「这一段在讲什么」「我想看的那段在哪」）用时间点目录就能给全，
-- 一个字节的片源都不用动。所以分节做成【挂在讲次上的时间区间】。
--
-- ## 这张表的三条不变量，都写在库里
--
-- 目录是靠人（或脚本）灌进去的，而灌错的表现全是「安静地不对」：
--   · end_sec 超出讲次时长 → 点进去 seek 到视频结尾之外，播放器什么都不做，也不报错；
--   · 两节区间重叠        → 同一时刻属于两节，进度算出来两节都「已看」或都「没看」；
--   · sort_no 撞车        → 目录顺序随查询计划变，今天这样明天那样。
-- 所以三条分别落在 CHECK、触发器、唯一键上。放应用层的话，下一个写导入脚本的人
-- 不会知道有这三条。
--
-- 注：本库全表都不建外键（见 schema.sql 的既有风格），所以删讲次不会级联删分节。
-- 实践中讲次只会置 status='OFF' 不会真删；真要删的话，导入脚本会先按 lesson_id 清空。

CREATE TABLE IF NOT EXISTS `lesson_section` (
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
