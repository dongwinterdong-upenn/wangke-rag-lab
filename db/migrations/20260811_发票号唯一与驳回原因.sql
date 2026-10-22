-- =============================================================================
--  发票号全局唯一 + 驳回原因可回告申请人
--
--  ① uk_invoice_no
--     原表对 invoice_no 没有任何约束。同一个发票号被填进两条开票申请，
--     账面上就是「一张票开了两笔收入」—— 这不是数据不一致，是税务上的重复入账，
--     而且【不会有任何报错】：两条记录各自看都合法，只有把全表拉出来比对才看得见。
--     MySQL 的唯一索引允许多个 NULL，所以未开票的行（invoice_no IS NULL）不受影响。
--
--  ② reject_reason
--     驳回如果只写进 audit_log，申请人这一侧永远看不到为什么被拒，
--     结果是同一张抬头错误的申请被反复提交、反复驳回，客服替数据库背锅。
--
--  ③ processed_by
--     audit_log 里有操作人，但开票队列要按「谁处理的」筛，
--     每次都去 join 一张按 target_id 建索引的日志表不现实。这里冗余一列。
-- =============================================================================

USE `wangke`;

ALTER TABLE `invoice_request`
  ADD COLUMN `reject_reason` VARCHAR(255)     NULL COMMENT '驳回原因，申请人可见' AFTER `issued_at`,
  ADD COLUMN `processed_by`  BIGINT UNSIGNED  NULL COMMENT '开票 / 驳回的操作人' AFTER `reject_reason`,
  ADD UNIQUE KEY `uk_invoice_no` (`invoice_no`);
