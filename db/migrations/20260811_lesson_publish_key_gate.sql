-- 已有部署的 schema 里，水印上架触发器早于 lesson_content_key 表出现，
-- 因此原触发器只验水印布局，不验内容密钥。增量触发器补上这道闸。
-- 新建库的 schema.sql 中存储过程已经内置同一判据；重复检查是幂等的。

USE `wangke`;

DELIMITER $$

DROP TRIGGER IF EXISTS `trg_lesson_content_key_gate_upd`$$
CREATE TRIGGER `trg_lesson_content_key_gate_upd`
BEFORE UPDATE ON `lesson` FOR EACH ROW
FOLLOWS `trg_lesson_publish_gate_upd`
BEGIN
  DECLARE v_key_count INT DEFAULT 0;
  IF NEW.status = 'ON' AND OLD.status <> 'ON' AND NEW.is_preview = 0 THEN
    SELECT COUNT(*) INTO v_key_count FROM `lesson_content_key`
     WHERE `lesson_id` = NEW.id AND `status` = 'ACTIVE';
    IF v_key_count <> 1 THEN
      SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = '本讲尚未封装现行内容密钥，上架后学员将无法播放';
    END IF;
  END IF;
END$$

DROP TRIGGER IF EXISTS `trg_lesson_content_key_gate_ins`$$
CREATE TRIGGER `trg_lesson_content_key_gate_ins`
BEFORE INSERT ON `lesson` FOR EACH ROW
FOLLOWS `trg_lesson_publish_gate_ins`
BEGIN
  -- AUTO_INCREMENT id 在 BEFORE INSERT 阶段还不可用，内容密钥不可能事先正确绑定。
  -- 正片必须先以 DRAFT/OFF 建立，完成切片和密钥封装后再转 ON。
  IF NEW.status = 'ON' AND NEW.is_preview = 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '正片不能直接以 ON 状态新建；请先建草稿、封装内容密钥，再执行上架';
  END IF;
END$$

DELIMITER ;
