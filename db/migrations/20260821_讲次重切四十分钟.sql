-- 2026-08-21　把四场整录重切成 16 讲（每讲 30~48 分钟）
--
-- ────────────────────────────────────────────────────────────────────────
-- 一句话：讲次不再等于"一场录屏"，而是"片库里的一段（或几段）分片区间"。
-- 视频文件一个字节都没动，改的只是"哪一讲引用哪些片"。
--
-- 为什么上一轮说"分节只能是时间点，拆不成更多讲次"，这一轮又能拆了
-- ────────────────────────────────────────────────────────────────────────
-- 上一轮挡路的是三条，逐条说清楚这次是怎么过去的：
--
--  ① 水印位区间在码字发出后不许重排。
--     真正的原因是：位是按【讲次顺序】首尾相接分下去的，所以一重切，
--     同一片对应的位就整体挪一格，已发出的码字全部作废 —— 而作废不报错，
--     只会让取证算出一组错分数、指认到无辜的人。
--     这次把它改成【全局映射】：bit(g) = chapter.wm_bit_base + g DIV stride，
--     只跟"片在哪个片库目录、第几片"有关，跟它被排进第几讲毫无关系。
--     于是怎么切都不动位。实测：本次重切前后，四个片库的 wm_bit_base
--     正好是原来四讲的 wm_bit_offset（0 / 183 / 375 / 545），
--     也就是说【每一片对应的码位与重切前逐位相同】，两份已发码字继续有效。
--     代价：被剪掉的片所占的位永远不会投递（本次 80 位），它们只是没人用，不会错位。
--
--  ② 分片文件按讲次目录从 0 编号。
--     现在讲次不再拥有目录，目录归【章】（= 一场录屏 = 一个片库）。
--     讲次拿的是这个目录里的片号区间，片名照旧是全局片号，一个文件都不用改名。
--     随之而来的是 IV：AES-128 的 IV 一直是"分片序号"，打包时用的是全局片号，
--     而重切之后一讲的第 0 片可能是全局第 267 片。所以播放列表改为【逐片显式写 IV】。
--     不写就得靠 EXT-X-MEDIA-SEQUENCE 推，一讲内部有跳段时推出来的号全错。
--     而 IV 错了几乎看不出来：CBC 只有第一个分组吃 IV，一片 1.5 MB 里只错 2 个字节，
--     解复用器一对齐就过去了 —— 实测帧序列 md5 与明文逐帧相同。不报错、不花屏，
--     所以只能显式写死。
--
--  ③ 进度按 (人, 讲次) 唯一。
--     旧进度按"位置落在新讲的哪一段"迁移，见 ops/load_lessons.py。
--
-- ────────────────────────────────────────────────────────────────────────
-- 触发器必须用 root 连接建。应用账号在开着 binlog 的库上建触发器会撞
-- ERROR 1419，而报错只打在触发器那几行 —— 表和列都建好了，闸门一个都没有。
-- ────────────────────────────────────────────────────────────────────────

SET NAMES utf8mb4;

-- 加列写成可重复执行：这份迁移要在【发新 jar 之前】单独跑一遍（见 ops/deploy_lessons.sh
-- 的 --migrate），随后灌数据时又会整份再跑一遍。不可重复的 ALTER 会在第二遍炸掉，
-- 而那时候库已经改了一半。
DROP PROCEDURE IF EXISTS `sp_add_col_if_missing`;
DELIMITER $$
CREATE PROCEDURE `sp_add_col_if_missing`(IN p_table VARCHAR(64), IN p_col VARCHAR(64),
                                         IN p_ddl TEXT)
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table
                    AND COLUMN_NAME = p_col) THEN
    SET @s = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN ', p_ddl);
    PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
  END IF;
END$$
DELIMITER ;

-- ------------------------------------------------------------------ 章 = 片库

CALL `sp_add_col_if_missing`('chapter', 'hls_key',
  "`hls_key` VARCHAR(255) NULL COMMENT '本章那一场录屏切出来的片库目录。章以下各讲共用它，各拿一段片号区间' AFTER `title`");
CALL `sp_add_col_if_missing`('chapter', 'store_segment_count',
  "`store_segment_count` INT NOT NULL DEFAULT 0 COMMENT '片库目录里的分片总数（剪掉的片也算在内：水印容量按它算，按交付量算的话每重切一次位就得挪）' AFTER `hls_key`");
CALL `sp_add_col_if_missing`('chapter', 'wm_bit_base',
  "`wm_bit_base` INT NOT NULL DEFAULT 0 COMMENT '本片库第 0 个水印槽位在整门课码字里的位置。bit(g) = wm_bit_base + g DIV stride' AFTER `store_segment_count`");

-- 回填。此刻每章恰好挂着一讲，而那一讲的 hls_key / segment_count / wm_bit_offset
-- 就是这个片库的真实情况 —— 从生产现状回填，不重新推算。
UPDATE `chapter` c
  JOIN `lesson` l ON l.chapter_id = c.id
   SET c.hls_key = l.hls_key,
       c.store_segment_count = l.segment_count,
       c.wm_bit_base = l.wm_bit_offset;

-- ------------------------------------------------------------------ 讲 = 片号区间

CALL `sp_add_col_if_missing`('lesson', 'wm_bit_base',
  "`wm_bit_base` INT NOT NULL DEFAULT 0 COMMENT '所在片库的码位基点，同 chapter.wm_bit_base；上架闸门校验两边一致' AFTER `wm_bit_offset`");

UPDATE `lesson` l JOIN `chapter` c ON c.id = l.chapter_id SET l.wm_bit_base = c.wm_bit_base;

CREATE TABLE IF NOT EXISTS `lesson_segment_block` (
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
) ENGINE=InnoDB COMMENT='讲次交付的分片区间。一讲可以有几段，中间跳过的是剪掉的内容（课间、等 AI 的静音）';

-- 回填：现有的每一讲各占满自己那个片库。
--
-- 这一步是为了让【新 jar 配旧数据】也能正常投递 —— 否则发布顺序就只剩
-- "先换数据再发 jar"，而那期间旧 jar 读不懂新数据，两头都不通。
-- 回填之后新旧投递逐字节相同：讲次 1 的区间是 [0,1098)、基点 0，
-- 于是 isSlot(g) 与 bit(g) 与旧实现完全一致。
INSERT INTO `lesson_segment_block` (`lesson_id`,`sort_no`,`seg_from`,`seg_to`)
SELECT l.`id`, 1, 0, l.`segment_count` FROM `lesson` l
 WHERE l.`segment_count` > 0
   AND NOT EXISTS (SELECT 1 FROM `lesson_segment_block` b WHERE b.`lesson_id` = l.`id`);

DROP TRIGGER IF EXISTS `trg_block_bounds_ins`;
DROP TRIGGER IF EXISTS `trg_block_bounds_upd`;

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

CREATE TRIGGER `trg_block_bounds_ins`
BEFORE INSERT ON `lesson_segment_block` FOR EACH ROW
BEGIN
  CALL `sp_block_bounds`(NULL, NEW.lesson_id, NEW.seg_from, NEW.seg_to);
END$$

CREATE TRIGGER `trg_block_bounds_upd`
BEFORE UPDATE ON `lesson_segment_block` FOR EACH ROW
BEGIN
  CALL `sp_block_bounds`(NEW.id, NEW.lesson_id, NEW.seg_from, NEW.seg_to);
END$$

-- ------------------------------------------------------------------ 上架闸门

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

DROP PROCEDURE IF EXISTS `sp_add_col_if_missing`;
