-- =============================================================================
--  2026-08-11 · 讲次上架前的水印闸门
--
--  上架（lesson.status → ON）是视频第一次对学员可见的那一刻。
--  如果此刻水印布局是错的，那么从这一刻起投递出去的每一份副本都是不可取证的，
--  而且【补不回来】—— 已经流出去的东西没法追加水印。
--
--  DB 的 CHECK 只能约束单行，管不了「两讲的位区间不许重叠」这种跨行条件；
--  应用层的检查又总有绕过去的路径（后台改一下状态、跑个脚本、手工 UPDATE）。
--  所以这道闸门放在触发器里：不管谁、从哪条路把 status 改成 ON，都要过。
--
--  试听片段（is_preview=1）豁免：它本来就是给所有人看的公开物料，不带水印是有意的。
-- =============================================================================

USE `wangke`;

DELIMITER $$

DROP TRIGGER IF EXISTS `trg_lesson_publish_gate_upd`$$
CREATE TRIGGER `trg_lesson_publish_gate_upd`
BEFORE UPDATE ON `lesson` FOR EACH ROW
BEGIN
  IF NEW.status = 'ON' AND OLD.status <> 'ON' AND NEW.is_preview = 0 THEN
    CALL `sp_lesson_publish_gate`(NEW.id, NEW.course_id, NEW.segment_count,
                                  NEW.wm_slot_stride, NEW.wm_slot_count, NEW.wm_bit_offset);
  END IF;
END$$

DROP TRIGGER IF EXISTS `trg_lesson_publish_gate_ins`$$
CREATE TRIGGER `trg_lesson_publish_gate_ins`
BEFORE INSERT ON `lesson` FOR EACH ROW
BEGIN
  -- 直接以 ON 插入是最容易绕过闸门的一条路，所以插入侧也要拦
  IF NEW.status = 'ON' AND NEW.is_preview = 0 THEN
    CALL `sp_lesson_publish_gate`(NEW.id, NEW.course_id, NEW.segment_count,
                                  NEW.wm_slot_stride, NEW.wm_slot_count, NEW.wm_bit_offset);
  END IF;
END$$

DROP PROCEDURE IF EXISTS `sp_lesson_publish_gate`$$
CREATE PROCEDURE `sp_lesson_publish_gate`(
    IN p_lesson_id BIGINT UNSIGNED, IN p_course_id BIGINT UNSIGNED,
    IN p_segment_count INT, IN p_stride INT, IN p_slot_count INT, IN p_bit_offset INT)
BEGIN
  DECLARE v_bit_length INT DEFAULT NULL;
  DECLARE v_overlap INT DEFAULT 0;

  IF p_segment_count <= 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '讲次还没切片（segment_count=0），上架即等于投递没有水印的副本';
  END IF;

  IF p_stride <= 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '水印取样步长必须为正，否则本讲一个槽位都没有';
  END IF;

  -- 槽位数必须与切片数对得上。对不上说明布局是按旧的切片数算的，
  -- 而按旧布局投递会让码字位与实际分片错位 —— 错位的取证不会失败，只会指认到别人。
  IF p_slot_count <> FLOOR(p_segment_count / p_stride) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'wm_slot_count 与 segment_count/wm_slot_stride 对不上：布局是按旧切片数算的，须重新分配位区间';
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

  -- 跨行条件：CHECK 表达不了，只能在这里拦
  SELECT COUNT(*) INTO v_overlap FROM `lesson`
   WHERE `course_id` = p_course_id AND `id` <> p_lesson_id
     AND `is_preview` = 0
     AND p_bit_offset < `wm_bit_offset` + `wm_slot_count`
     AND `wm_bit_offset` < p_bit_offset + p_slot_count;
  IF v_overlap > 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '本讲的位区间与同课程另一讲重叠：重叠的那一段等于白费的码长，抗合谋能力会低于设计值';
  END IF;
END$$

DELIMITER ;
