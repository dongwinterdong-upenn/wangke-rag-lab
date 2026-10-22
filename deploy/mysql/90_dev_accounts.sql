-- 本机开发用的两个账号，只在 Docker 本机部署里自动灌入，不要用于任何对外的环境。
--   平台超管  手机号 13800000000  密码 Lab@admin2026   登录 http://localhost:8080/admin/
--   学员      手机号 13900000001  密码 Lab@student2026 登录 http://localhost:8080/study/
-- 学员已开通课程 1，可以直接在学员端的答疑页向懂妈提问，也能考全部 6 套卷。
-- 本机没有短信与支付，注册新学员走不通，需要更多学员时照下面的写法加。
SET NAMES utf8mb4;
USE `wangke`;

UPDATE `sys_user` SET `phone`='13800000000',
  `password_hash`='$2b$12$MpCEVZLEB4vU/C2vHwmaGuHlccbMc.EyAc9mlylbIlBBDgCiguMAm', `must_change_pwd`=0
WHERE `id`=1 AND `role`='PLATFORM_ADMIN';

INSERT INTO `sys_user` (`phone`,`password_hash`,`nickname`,`role`,`status`)
VALUES ('13900000001','$2b$12$.4Ir36NZvIXvi8/sZTn2ceIo16dpEr4KHaacVWE.dSYkcPAsebiva','实训学员','STUDENT','ACTIVE');

INSERT INTO `enrollment` (`user_id`,`course_id`,`source`,`valid_from`,`valid_until`,`agreement_version`,`agreed_at`)
SELECT `id`, 1, 'GRANT', NOW(3), DATE_ADD(NOW(3), INTERVAL 24 MONTH), 'v2026-08-11', NOW(3)
FROM `sys_user` WHERE `phone`='13900000001';
