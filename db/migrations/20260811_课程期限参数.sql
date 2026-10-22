-- =============================================================================
--  2026-08-11 · 把 G5 的两个期限参数从代码里搬进库
--
--  背景：这两个数原本一个写死在 Java（FulfillService 里 plusMonths(12)），
--  一个压根没有实现（enrollment.valid_until 的 M 只存在于列注释里）。
--  写死在代码里的业务参数有个共同毛病：需求方看不见、改一次要发版，
--  而且同一个概念会在第二处、第三处被再写一遍，然后慢慢对不上。
--
--      activate_months  发码后允许激活的月数（码不能永远躺着，否则负债无限期）
--      access_months    激活后可观看的月数（G5 的 M）
--
--  ★ 需要拍板：access_months 暂定 24。这个数【必须在第一笔成交之前定】——
--    valid_until 是激活当时逐行算死写进 enrollment 的，事后调小对已激活的学员
--    无效（改了也不能往回缩，那是单方面减少已售权益），只能对新激活的生效。
--    也就是说：定短了以后可以放宽，定长了就再也收不回来。
--
--  activate_months 12 沿用代码里已在跑的值，不算新决定。
-- =============================================================================

USE `wangke`;

ALTER TABLE `course`
  ADD COLUMN `activate_months` INT NOT NULL DEFAULT 12
      COMMENT '发码后允许激活的月数（G5）' AFTER `status`,
  ADD COLUMN `access_months`   INT NOT NULL DEFAULT 24
      COMMENT '激活后可观看的月数（G5 的 M）' AFTER `activate_months`,
  ADD CONSTRAINT `ck_course_months`
      CHECK (`activate_months` > 0 AND `access_months` > 0);
