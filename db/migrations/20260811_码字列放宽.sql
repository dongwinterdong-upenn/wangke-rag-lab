-- 2026-08-11 · wm_codeword.bits 由 2048 放宽到 8192 字节
-- 当前课程 9120 位（1140 字节）用得下，但步长调小或课程更长就会顶到上限。
-- 严格模式下超长是报错不是截断，顶到了会当场炸；既然放宽只花几个字节，
-- 就没有理由让它贴着上限跑。
USE `wangke`;
ALTER TABLE `wm_codeword` MODIFY COLUMN `bits` VARBINARY(8192) NOT NULL COMMENT '按位打包的码字（MSB 在前）';
