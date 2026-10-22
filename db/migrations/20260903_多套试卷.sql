-- 多套试卷：一门课挂多份卷，外加一份不属于任何课程、对全部注册学员开放的 AIGC 卷。
--
-- ★ 这份迁移里的【触发器段必须用 root 连接执行】，不能用应用账号：
--   COURSE 的 MySQL 开着 binlog 而 log_bin_trust_function_creators=0，
--   应用账号没有 SUPER/TRIGGER 权限，建触发器会报
--   「ERROR 1419: You do not have the SUPER privilege and binary logging is enabled」，
--   而这个错**只打在触发器那几行上** —— 表、列、唯一键全都建好了，闸门一个没有，
--   而后续插入全部通过。20260822_超管手工发码.sql 与 20260901_代理专属价.sql 各记着一次。
--       ssh COURSE 'mysql wangke < 这个文件'
--   跑完不要只看「没报错」：要在库上真查一次
--       SELECT TRIGGER_NAME FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE();
--   确认 trg_exam_paper_precheck / trg_exam_paper_upd / trg_exam_config_paper /
--   trg_exam_attempt_ins / trg_exam_attempt_upd / trg_certificate_precheck 六样都在。
--   （末尾 sp_assert_landed 会连**触发器体**一起点名 —— 后三个是改体，名字在不等于体改了。）
--
--
-- ## 为什么「卷」是一张新表，而规则仍然留在 exam_config
--
-- 直觉上把 access / grants_certificate 这几列直接加到 exam_config 上更省事。走不通：
--
--   ① **exam_config 是版本化的，卷的身份不是。** 改规则 = 插一行新的 exam_config，
--      已开考的场次按 attempt.config_id 引用旧行（否则学员考到一半规则变了会交不上卷）。
--      身份列跟着版本化的话，同一份卷会有 N 行各自声明自己的 code / access / 发不发证，
--      而「这份卷发不发证」这一问就此没有唯一答案 —— 证书触发器要按哪一行判？
--      按最新一行判，等于「改一次规则就可能把历史证书的依据改掉」。
--   ② **准入是给「卷」的，不是给「某一版规则」的。** 学员端列表要在**没有任何 attempt**、
--      也没选中哪一版规则的时候就把卷列出来，这时候能拿到的只有身份。
--   ③ **开放卷没有 course_id。** exam_config.course_id 今天是 NOT NULL，
--      而「按课程找规则」是现有代码里唯一的入口。把它放宽到 NULL 之后，
--      「这行规则归谁」必须另有答案，那个答案就是 paper_id（见 trg_exam_config_paper）。
--
-- 所以分成两张：exam_paper 管身份与准入（一卷一行，永不版本化），
-- exam_config 管规则（一卷多行，版本化机制一个字不动）。
--
--
-- ## 「开放卷不发结业证明」为什么必须是库层的
--
-- 开放卷（AIGC_OPEN）任何注册账号都能考，不需要买课、不需要激活。它一旦能发证，
-- 「课程结业证明」这四个字就当场失真 —— 一个从没买过课、没看过一秒视频的人，
-- 注册完直接考 100 题就能拿到一张写着本课程名字的结业证明。
--
-- 而这条错的形态是全链路合法的：certificate 行的 cert_no 唯一、attempt 真的 passed=1、
-- user/course 也对得上，现有 trg_certificate_precheck 三条判据逐条满意。
-- 只有把「这份卷是干什么的」并进来才判得出来。证书是对外物料，发出去撤不回。
--
-- 同一条判据在 CertificateService 里也走一遍（规格 §2），两道互补：
-- 应用层那道给人话，这道保证绕不过去 —— 直接 INSERT、以后新写的某个补发功能都得过。
--
--
-- ## 「章节卷考过了不能换结业证明」是同一条闸的另一半
--
-- 四份章节卷是练兵：50 题、及格 40、不设进度门槛、次数放宽到 5 次。
-- 不加这条闸的话，学员看完第一章、考过一份 50 题的卷，就能换出「课程结业证明」——
-- 而它与考完 100 题综合卷换出来的那张，在库里一模一样、事后不可分辨。
--
--
-- ## exam_attempt 的唯一键为什么必须换，而不是加一个
--
-- uk_user_course_attempt(user_id, course_id, attempt_no) 在多套卷下直接是错的：
-- 同一个人在同一门课下考 FDE_CH1 的第 1 次与 FDE_CH2 的第 1 次，(user, course, 1)
-- 完全相同 —— 第二张卷的第一次考试**开不出来**，表现是学员点开考报「重复」。
-- 而 ExamService 那句注释写着「uk_user_course_attempt 是唯一的裁决点，先查后写在这里必然漏」，
-- 所以也不能只是把它删掉了事：换成 uk_user_paper_attempt(user_id, paper_id, attempt_no)，
-- 裁决点还在同一个位置，只是定义域从「课」换成「卷」。
--
-- ★ 换键要求表为空 —— 下面第一段就是这条断言，非空当场 SIGNAL 拒绝整个迁移。
--   非空时就地换键有两种坏法，都不响：存量行的 paper_id 是 NULL，而
--   **多个 NULL 不参与唯一性判定**，于是那批老行连「同一人同一次」都不再被拦；
--   反过来若先给老行回填 paper_id，回填错的那些会与新键撞车或不撞车，两种都无从校验。
--   生产的 exam_attempt 现在是 0 行（这个功能还没真正开考过），所以走「拒绝而不是猜」这条路。


-- ================================================================ 锁等待
--
-- 生产的 lock_wait_timeout 是默认值 31536000 秒（一整年）。下面给 exam_attempt / question /
-- exam_config 加列改列换键，要拿它们的排他元数据锁；只要那一刻有任何长事务握着这几张表，
-- ALTER 就会**等上一年**，而元数据锁是排队的 —— 排在它后面的每一条查询也一起无限期挂住。
--
-- 10 秒拿不到就认输：失败是响的、可重试的，而挂死是哑的。
SET SESSION lock_wait_timeout = 10;


-- ================================================================ 权限探针
--
-- ★ 这一段必须排在**任何 DDL 之前**，它的全部作用是「用错账号时先炸，别先拆东西」。
--
-- 用应用账号跑这份文件的话：下面 `DROP TRIGGER IF EXISTS trg_exam_attempt_ins`
-- **会成功**（DROP 只要 TRIGGER 权限，而应用账号是 GRANT ALL ON wangke.*），
-- 紧跟的 CREATE TRIGGER 因为 log_bin=ON 且 log_bin_trust_function_creators=OFF 报 1419。
-- **DDL 不回滚，删掉就是删掉。** 这一轮尤其致命：trg_exam_attempt_ins /
-- trg_exam_attempt_upd / trg_certificate_precheck 是【改体】，不是新建 —— 拆掉之后
-- 「10 分标记通过」「已交卷不得改分」「拿别人的考试记录发证」这三条**本来就在生产上跑着**的闸
-- 凭空消失，零报错、零日志、界面一切正常。
--
-- 文件末尾那段 sp_assert_landed 救不了这个：`mysql db < file` 首错即停，走不到那一行。
--
-- 探针建在 `sku` 上：运行期没有任何代码往 sku 插行，而 CREATE TRIGGER 要的权限与表无关。
-- 触发器体是空的，即便在存在的那一瞬间有人插也什么都不做。
--
--     正确跑法： ssh COURSE 'mysql wangke < 这个文件'      （默认就是 root@localhost）

DROP TRIGGER IF EXISTS `trg_privilege_probe`;

DELIMITER $$
CREATE TRIGGER `trg_privilege_probe` BEFORE INSERT ON `sku` FOR EACH ROW
BEGIN
END$$
DELIMITER ;

DROP TRIGGER `trg_privilege_probe`;


-- ================================================================ 换键前置断言
--
-- 见文件头「exam_attempt 的唯一键为什么必须换」。这一段也排在**任何 DDL 之前**：
-- 表非空时要的是「一个字都别改」，而不是「加完列才发现换不了键」。
--
-- 判据挂在【旧键还在不在】上，而不是无条件 COUNT：装过一次之后旧键已经没了，
-- 那时表里有多少行都与本次迁移无关 —— 否则这份文件在上线一周后就再也重跑不了，
-- 而「可重复执行」正是它自称的性质。

DROP PROCEDURE IF EXISTS `sp_assert_attempt_empty`;
DELIMITER $$
CREATE PROCEDURE `sp_assert_attempt_empty`()
BEGIN
  DECLARE v_rows BIGINT DEFAULT 0;
  DECLARE v_msg  VARCHAR(255);
  IF EXISTS (SELECT 1 FROM information_schema.STATISTICS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'exam_attempt'
                AND INDEX_NAME = 'uk_user_course_attempt') THEN
    SELECT COUNT(*) INTO v_rows FROM `exam_attempt`;
    IF v_rows > 0 THEN
      SET v_msg = CONCAT('exam_attempt 已有 ', v_rows,
                         ' 行，唯一键不能就地换：老行的 paper_id 为 NULL 而多个 NULL 不参与唯一性判定，'
                         '换完等于把这批行的重复保护静默摘掉。先决定这批行怎么回填再来');
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = v_msg;
    END IF;
  END IF;
END$$
DELIMITER ;
CALL `sp_assert_attempt_empty`();
DROP PROCEDURE `sp_assert_attempt_empty`;


-- ================================================================ 就地改旧规则的前置断言
--
-- 下面「六份规则」那一段要把现有那行 exam_config 的 paper_id 指向 FDE_MAIN，
-- 并就地把 pass_score 改成 80、duration_min 改成 60。允许「就地改」的前提是
-- **course 1 下只有一行**旧规则：那门课若已经改版过一次（两行以上还没挂卷），
-- 那句 UPDATE 会把历史那几行一起改写，而且不响。宁可在这里失败，让人来判定
-- 哪一行才是当前生效的那行。
--
-- ★ 这道断言与上面那条一样排在【任何 DDL 之前】，理由也一样，而且实测过：
--   它原来排在全部 DDL 与六张卷 INSERT 之后，失败时留下的是一个半落地的库 ——
--   给旧库补第二行未挂卷的规则再跑，报 45000 退出 1，而复查库里
--   exam_paper 已有 6 行且全是 status='ON'、三张表的列与新唯一键都换完、
--   触发器也都建好了，只有规则没挂上。这个状态比彻底失败更糟：
--   学员端会把六张卷全列出来（列表只看 status），点进去因为 effectiveRules
--   取不到行而报「测评规则不存在」；而且此后每次重跑都停在同一处，人不介入修不回来。
--
-- 计数一律限定 course_id = 1，与下面那句 UPDATE 的作用域逐字对齐。
-- 不限定的话，别的课程留下的历史规则行也会把它误触发 —— 那些行本来就轮不到这份文件管。
--
-- 判据分两支，因为它现在跑在【加列之前】：
--   · paper_id 列还不存在（首次迁移）→ 数 course 1 的全部规则行；
--   · 列已存在（重跑）→ 只数还没挂卷的那些。已经挂到 FDE_MAIN 上的那行不再算数，
--     否则这份文件跑完一次之后就再也重跑不了，而「可重复执行」正是它自称的性质。
--   （不取的那一支在 CALL 时不会被执行，所以它引用一个还不存在的列也不报错 ——
--     存储过程里的列名是执行期才解析的。）

DROP PROCEDURE IF EXISTS `sp_assert_one_legacy_config`;
DELIMITER $$
CREATE PROCEDURE `sp_assert_one_legacy_config`()
BEGIN
  DECLARE v_n   INT DEFAULT 0;
  DECLARE v_msg VARCHAR(255);
  IF EXISTS (SELECT 1 FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'exam_config'
                AND COLUMN_NAME = 'paper_id') THEN
    SELECT COUNT(*) INTO v_n FROM `exam_config`
      WHERE `paper_id` IS NULL AND `course_id` = 1;
  ELSE
    SELECT COUNT(*) INTO v_n FROM `exam_config` WHERE `course_id` = 1;
  END IF;
  IF v_n > 1 THEN
    SET v_msg = CONCAT('course 1 下有 ', v_n, ' 行 exam_config 还没挂卷，不能一律当成结业综合测评就地改：'
                       '先人工判定哪一行是当前生效的那一版');
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = v_msg;
  END IF;
END$$
DELIMITER ;
CALL `sp_assert_one_legacy_config`();
DROP PROCEDURE `sp_assert_one_legacy_config`;


-- ================================================================ 可重复执行的脚手架
--
-- 存在性判断在过程里、**完整 DDL 字面量在调用点**。反过来写（在过程体里 CONCAT 出 DDL）
-- 的话 SchemaDriftTest 的 ADDED 正则一个都抓不到 —— 实测 20260821_讲次重切四十分钟.sql
-- 用那种写法加的 4 列 0 命中。后果是最坏的一种：新装的库缺这些东西，而门禁全绿。
--
-- CREATE TABLE 不需要过程：它自带 IF NOT EXISTS，字面量本来就在文件里。

DELIMITER $$

DROP PROCEDURE IF EXISTS `sp_run_if_col_missing`$$
CREATE PROCEDURE `sp_run_if_col_missing`(IN p_table VARCHAR(64), IN p_col VARCHAR(64), IN p_ddl TEXT)
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table
                    AND COLUMN_NAME = p_col) THEN
    SET @ddl = p_ddl; PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
  END IF;
END$$

DROP PROCEDURE IF EXISTS `sp_run_if_index_missing`$$
CREATE PROCEDURE `sp_run_if_index_missing`(IN p_table VARCHAR(64), IN p_index VARCHAR(64), IN p_ddl TEXT)
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table
                    AND INDEX_NAME = p_index) THEN
    SET @ddl = p_ddl; PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
  END IF;
END$$

DROP PROCEDURE IF EXISTS `sp_run_if_index_present`$$
CREATE PROCEDURE `sp_run_if_index_present`(IN p_table VARCHAR(64), IN p_index VARCHAR(64), IN p_ddl TEXT)
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.STATISTICS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table
                AND INDEX_NAME = p_index) THEN
    SET @ddl = p_ddl; PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
  END IF;
END$$

-- 放宽为可空走【还是 NOT NULL 才改】，不是无条件 MODIFY。
--
-- 无条件 MODIFY COLUMN 在 8.0.46 上是一次实打实的表重建（ALGORITHM=COPY），
-- 重跑一次就再复制一遍整张表并再抢一次排他元数据锁 —— 对「可重复执行」是纯负担。
-- 而按 IS_NULLABLE 判存在是判得准的：这一列要么已经可空（跳过），要么还没改（改）。
-- 列的**注释**变了它判不出来，那是可以接受的：注释不是判据。
DROP PROCEDURE IF EXISTS `sp_run_if_col_notnull`$$
CREATE PROCEDURE `sp_run_if_col_notnull`(IN p_table VARCHAR(64), IN p_col VARCHAR(64), IN p_ddl TEXT)
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table
                AND COLUMN_NAME = p_col AND IS_NULLABLE = 'NO') THEN
    SET @ddl = p_ddl; PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
  END IF;
END$$

-- CHECK 走【探针先行】，与 20260822 那一版同一个理由：
-- 直接 DROP 再 ADD，只要存量数据里有一行顶回来，ADD 失败而旧约束已经没了 ——
-- 把「改约束」做成了「删约束」。这里加的是全新约束，所以只做「不存在才加」，
-- 但仍然先拿临时名验一遍数据：加不上就当场失败，而不是留下一个半截状态。
DROP PROCEDURE IF EXISTS `sp_run_if_check_missing`$$
CREATE PROCEDURE `sp_run_if_check_missing`(IN p_table VARCHAR(64), IN p_name VARCHAR(64), IN p_ddl TEXT)
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.TABLE_CONSTRAINTS
                  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table
                    AND CONSTRAINT_NAME = p_name AND CONSTRAINT_TYPE = 'CHECK') THEN
    SET @probe = REPLACE(p_ddl, CONCAT('`', p_name, '`'), CONCAT('`', p_name, '_probe`'));
    PREPARE pr FROM @probe; EXECUTE pr; DEALLOCATE PREPARE pr;
    SET @drop = CONCAT('ALTER TABLE `', p_table, '` DROP CHECK `', p_name, '_probe`');
    PREPARE dp FROM @drop; EXECUTE dp; DEALLOCATE PREPARE dp;
    SET @ddl = p_ddl; PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
  END IF;
END$$

DELIMITER ;


-- ---------------------------------------------------------------- 新表：试卷
--
-- 只放身份与准入，一条规则都不放（见文件头）。
--
--   · code 是稳定标识：后端按 code 认「哪张是发证的综合卷」（courseId 兼容口那条），
--     导题脚本按 code 认卷，运营改标题不影响任何一处。所以它 UNIQUE 且不参与展示。
--   · course_id 可空 —— 这是开放卷存在的全部技术含义。ENROLLED 卷必须挂课程，
--     由 trg_exam_paper_precheck 逼着（可空 ≠ 随便空）。
--   · grants_certificate 是证书链的新一环：certificate 那边原来只问「passed 吗、是这个人这门课吗」，
--     问不到「这份卷凭什么发证」。
--   · status 缺省 DRAFT：新建的卷默认对学员不可见，先审题再上架。
--     本次迁移里的六张直接给 ON —— 它们的题库与规则在同一轮上线，没有中间态可言。
--   · idx_paper_list 给学员端列表用：一次查全部 status='ON' 并按 sort_no 排。

CREATE TABLE IF NOT EXISTS `exam_paper` (
  `id`                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `code`               VARCHAR(32)     NOT NULL              COMMENT '稳定标识，后端与导题脚本按它认卷；改标题不影响',
  `title`              VARCHAR(128)    NOT NULL,
  `subtitle`           VARCHAR(255)    NULL                  COMMENT '卡片上的一句话；不要写题数分数，那些是 exam_config 的',
  `course_id`          BIGINT UNSIGNED NULL                  COMMENT '开放卷可以不挂课程',
  `access`             ENUM('ENROLLED','OPEN') NOT NULL      COMMENT 'ENROLLED=要有该课有效权益；OPEN=任何注册学员都能考',
  `grants_certificate` TINYINT(1)      NOT NULL DEFAULT 0    COMMENT '考过发不发结业证明；trg_certificate_precheck 按它把关',
  `status`             ENUM('DRAFT','ON','OFF') NOT NULL DEFAULT 'DRAFT',
  `sort_no`            INT             NOT NULL DEFAULT 0,
  `created_at`         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_paper_code` (`code`),
  KEY `idx_paper_list` (`status`,`sort_no`)               COMMENT '学员端列卷走这条'
) ENGINE=InnoDB COMMENT='试卷（身份与准入；规则仍在 exam_config）';


-- ---------------------------------------------------------------- 规则挂到卷上
--
-- exam_config 的版本化机制一个字不动：改规则仍然是插新行，已开考的场次按
-- attempt.config_id 引用旧行。变的只有「这行规则归谁」：原来只能按 course_id，
-- 现在按 paper_id（旧行 paper_id 为 NULL，按 course_id，见 trg_exam_config_paper）。
--
-- idx_paper_rule 对着 effectiveRules 那句查：
--   WHERE paper_id=? AND effective_from<=NOW(3) ORDER BY effective_from DESC, id DESC LIMIT 1
-- 没有它就是全表扫 —— 今天 6 行无所谓，但这张表是只增不减的（每改一次规则加一行）。

CALL `sp_run_if_col_missing`('exam_config', 'paper_id',
  'ALTER TABLE `exam_config` ADD COLUMN `paper_id` BIGINT UNSIGNED NULL COMMENT ''这套规则属于哪份卷；旧行为 NULL 时按 course_id 认（trg_exam_config_paper）'' AFTER `course_id`');

CALL `sp_run_if_col_notnull`('exam_config', 'course_id',
  'ALTER TABLE `exam_config` MODIFY COLUMN `course_id` BIGINT UNSIGNED NULL COMMENT ''开放卷的规则不挂课程；为空时 paper_id 必须非空''');

CALL `sp_run_if_index_missing`('exam_config', 'idx_paper_rule',
  'ALTER TABLE `exam_config` ADD KEY `idx_paper_rule` (`paper_id`,`effective_from`)');


-- ---------------------------------------------------------------- 题库分卷
--
-- 旧索引 idx_pool(course_id, review_status, type) **保留不动**：
-- 上线顺序是「先迁移、再导题」，中间那一段时间里全部存量题的 paper_id 都是 NULL，
-- 而按 course_id 抽题的老代码还在跑。删掉旧索引 = 在那一段里让抽题全表扫，
-- 而它只会表现成「开考那一下慢」，没人会去查是索引没了。
--
-- 抽题的新口径是 WHERE paper_id=? AND review_status='APPROVED'，走 idx_paper_pool。
-- 两条索引并存的代价是每次写题多维护一棵 B+ 树 —— 题库是低频写、每次开考都读，换得值。

CALL `sp_run_if_col_missing`('question', 'paper_id',
  'ALTER TABLE `question` ADD COLUMN `paper_id` BIGINT UNSIGNED NULL COMMENT ''这道题属于哪份卷；抽题按它圈范围，不再按 course_id'' AFTER `course_id`');

CALL `sp_run_if_index_missing`('question', 'idx_paper_pool',
  'ALTER TABLE `question` ADD KEY `idx_paper_pool` (`paper_id`,`review_status`,`type`) COMMENT ''多套卷后按卷抽题走这条''');

-- question.course_id 也要放宽：开放卷不挂课程，它的题自然也不属于任何一门课。
-- 不放宽的话，导题那一步会在第一条 AIGC 题上撞 NOT NULL 而整批失败 —— 那还算好的；
-- 更坏的写法是「顺手填个 course_id=1 凑数」，那批题就永远混在实训营的池子里，
-- 而按 course_id 抽题的老代码会把它们抽进课程考卷，学员在结业卷上看到剪辑题。
CALL `sp_run_if_col_notnull`('question', 'course_id',
  'ALTER TABLE `question` MODIFY COLUMN `course_id` BIGINT UNSIGNED NULL COMMENT ''开放卷的题不挂课程；为空时 paper_id 必须非空''');

-- 两个都空的题谁也抽不到，静静躺在库里占着编号。CHECK 在 8.0.16 起是真校验。
CALL `sp_run_if_check_missing`('question', 'ck_question_scope',
  'ALTER TABLE `question` ADD CONSTRAINT `ck_question_scope` CHECK (`paper_id` IS NOT NULL OR `course_id` IS NOT NULL)');


-- ---------------------------------------------------------------- 考试记录换键
--
-- 见文件头「exam_attempt 的唯一键为什么必须换」。前置断言已经在文件开头跑过。
--
-- paper_id 声明成可空，但它在业务上是必填的 —— 逼它的不是 NOT NULL 而是
-- trg_exam_attempt_ins 那条「NEW.paper_id 必须等于该 config 的 paper_id」：
-- 迁移跑完之后 course_id=1 的规则行都挂上了卷（末尾 sp_assert_landed 数的是「六张卷各有规则」，
-- 它**不保证**库里没有别的课程留下的 paper_id 为空的历史规则行 —— 那些行本来也轮不到这份文件管）。
-- 于是任何一条 paper_id 为空的 attempt 都会被那条判据当场拒掉：只要它引用的规则行挂了卷。
-- 这一点很要紧：新唯一键里 paper_id 为空的行**不参与唯一性判定**，
-- 真让它空进来的话，同一个人同一份卷可以开出任意多个第 1 次考试，而且一声不响。
--
-- 不另加 idx_user(user_id, paper_id)：新唯一键的最左前缀就是它。

CALL `sp_run_if_col_missing`('exam_attempt', 'paper_id',
  'ALTER TABLE `exam_attempt` ADD COLUMN `paper_id` BIGINT UNSIGNED NULL COMMENT ''考的是哪份卷；必须与 exam_config.paper_id 一致（trg_exam_attempt_ins）'' AFTER `course_id`');

CALL `sp_run_if_col_notnull`('exam_attempt', 'course_id',
  'ALTER TABLE `exam_attempt` MODIFY COLUMN `course_id` BIGINT UNSIGNED NULL COMMENT ''开放卷的考试不挂课程''');

-- 先加新键再删旧键：中间那一瞬两条键并存，任何一条重复都还是插不进去。
-- 反过来（先删后加）如果新键因为什么原因加不上，脚本中止，而旧键**已经被删掉了** ——
-- 收场是这张表上一条重复保护都没有，且看起来只是「迁移报了个错」。
CALL `sp_run_if_index_missing`('exam_attempt', 'uk_user_paper_attempt',
  'ALTER TABLE `exam_attempt` ADD UNIQUE KEY `uk_user_paper_attempt` (`user_id`,`paper_id`,`attempt_no`)');

CALL `sp_run_if_index_present`('exam_attempt', 'uk_user_course_attempt',
  'ALTER TABLE `exam_attempt` DROP INDEX `uk_user_course_attempt`');


DROP PROCEDURE IF EXISTS `sp_run_if_col_missing`;
DROP PROCEDURE IF EXISTS `sp_run_if_index_missing`;
DROP PROCEDURE IF EXISTS `sp_run_if_index_present`;
DROP PROCEDURE IF EXISTS `sp_run_if_col_notnull`;
DROP PROCEDURE IF EXISTS `sp_run_if_check_missing`;


-- ---------------------------------------------------------------- 触发器
--
-- ★ 从这里往下必须用 root 连接执行（见文件头）。

DELIMITER $$

-- 卷的三条身份自洽，插入侧。判据与下面 trg_exam_paper_upd 逐字同源（两处必须一起改 ——
-- 只改一处的表现是「新建的卷建不成，而把一张老卷 UPDATE 成同样的形状能改成」）。
--
--   ① 开放卷不得发证：见文件头。这一条守的是「课程结业证明」这四个字的含义。
--   ② ENROLLED 卷必须挂课程：不挂课程的话 requireEnrollment 无从查起 ——
--      「查这个人对 NULL 这门课有没有权益」恒为假，整张卷谁都考不了，
--      而学员端会照常把它列出来（列表只看 status），点下去才被拒。
--   ③ 发证的卷必须挂课程：certificate.course_id 是 NOT NULL 且 uk_user_course 按它去重，
--      一张不挂课程的发证卷，考过之后连往哪门课发证都答不上来。
--
-- ★ 说清楚射程：③ 今天**一次都不会触发**。access 是 NOT NULL 的两值枚举，
--   「发证 + 不挂课程」这组合要么落在 OPEN（① 先开火）、要么落在 ENROLLED（② 先开火）。
--   留着它是因为 ENUM 追加值是安全的、以后大概率会追加（比如受邀卷），
--   而那一天 ③ 就是唯一挡得住这条组合的判据。所以 test_schema.py 里没有单独钉 ③ 的用例 ——
--   钉不住的用例只会给出一条假绿：它测的其实是 ① 或者 ②。
--
-- grants_certificate 一律用 <> 0 判，不用 = 1：它是 TINYINT(1)，
-- 写 2 进来在库层完全合法，而 = 1 会把它读成「不发证」—— 一张实际发证的卷从这三条闸下面走过去。
DROP TRIGGER IF EXISTS `trg_exam_paper_precheck`$$
CREATE TRIGGER `trg_exam_paper_precheck`
BEFORE INSERT ON `exam_paper` FOR EACH ROW
BEGIN
  IF NEW.`access` = 'OPEN' AND NEW.`grants_certificate` <> 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '开放卷不能发结业证明：不买课不激活就能考的卷，发出去的证明证明不了任何事';
  END IF;
  IF NEW.`access` = 'ENROLLED' AND NEW.`course_id` IS NULL THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '按课程开放的卷必须挂课程，否则权益校验无从查起，这张卷谁都考不了';
  END IF;
  IF NEW.`grants_certificate` <> 0 AND NEW.`course_id` IS NULL THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '发结业证明的卷必须挂课程：不挂课程就答不上来往哪门课发证';
  END IF;
END$$


-- 卷的三条身份自洽，更新侧。判据与上面 trg_exam_paper_precheck 逐字同源。
--
-- 光有插入侧是不够的，而且缺口正对着最危险的那条路：一张 access='ENROLLED'、
-- grants_certificate=1 的正常卷，UPDATE 成 access='OPEN' 之后就是一张
-- 「谁都能考、考过发结业证明」的卷 —— 后台改一个下拉框就能做到，插入侧那三条一条都不过问。
DROP TRIGGER IF EXISTS `trg_exam_paper_upd`$$
CREATE TRIGGER `trg_exam_paper_upd`
BEFORE UPDATE ON `exam_paper` FOR EACH ROW
BEGIN
  IF NEW.`access` = 'OPEN' AND NEW.`grants_certificate` <> 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '开放卷不能发结业证明：不买课不激活就能考的卷，发出去的证明证明不了任何事';
  END IF;
  IF NEW.`access` = 'ENROLLED' AND NEW.`course_id` IS NULL THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '按课程开放的卷必须挂课程，否则权益校验无从查起，这张卷谁都考不了';
  END IF;
  IF NEW.`grants_certificate` <> 0 AND NEW.`course_id` IS NULL THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '发结业证明的卷必须挂课程：不挂课程就答不上来往哪门课发证';
  END IF;
END$$


-- 一行规则必须归得了属。
--
-- course_id 放宽到可空之后，(paper_id, course_id) 全空的规则行在库层是合法的，
-- 而它**谁也查不到**：按卷查（新口径）查不到、按课查（旧口径）也查不到。
-- 表现是插进去了、后台列表里看得见、开考时报「测评规则不存在，不能开考」，
-- 两边对不上，而没有任何一行日志说这行规则是孤儿。
--
-- 只做插入侧：改规则的正路是插新行（版本化），UPDATE 旧行本来就不该发生 ——
-- 那会把已开考场次脚下的规则换掉。给 UPDATE 也上闸是另一件事（该管的是「不许原地改」，
-- 不是「归属」），单列一轮，别混进来。
DROP TRIGGER IF EXISTS `trg_exam_config_paper`$$
CREATE TRIGGER `trg_exam_config_paper`
BEFORE INSERT ON `exam_config` FOR EACH ROW
BEGIN
  IF NEW.`paper_id` IS NULL AND NEW.`course_id` IS NULL THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '测评规则必须至少挂在一份试卷或一门课程上，两边都空的规则行谁也查不到';
  END IF;
END$$


-- trg_exam_attempt_ins 是【改体】，不是新建。原来三条判据一字不动，只在前面追加一条。
--
-- 新增的那条：NEW.paper_id 必须等于该 config 的 paper_id。
-- 两边不一致时说明调用方把 A 卷的题挂到了 B 卷的规则上 —— 抽题按 attempt.paper_id 走、
-- 判分与次数上限按 config 走，**各按各的，且一路不报错**：
-- 学员拿到 50 题的章节卷题面，判分却按 100 题综合卷的 80 分及格线，永远考不过；
-- 反过来则是 100 题的卷按 40 分及格，随手就过。
--
-- 比较用 <=>（NULL 安全）而不是 =：
--   · 两边都空 → 放行。这是给旧行留的门（老 exam_config 的 paper_id 是 NULL），
--     本轮迁移跑完之后生产上已经没有这种行了，但 db/test_schema.py 与集成测试里还有。
--   · 一边空一边非空 → 拒。用 = 的话结果是 NULL，IF NULL 走 false 分支 = **放行**，
--     而「config 有卷、attempt 没写 paper_id」正是最可能发生的那一种漏写，
--     它还会顺带绕开新唯一键（多个 NULL 不参与唯一性判定）。
--
-- 第二条新增的是【课程与卷所属课程一致】。本轮引进的是 paper / config / attempt
-- 三元关系，上面那条只管住了 attempt↔config 这条边，attempt.course_id 完全自由：
-- 一场 paper=FDE_MAIN（course 1）的考试若把 course_id 写成 2，库层全程不响，
-- 而 trg_certificate_precheck 是拿 attempt.course_id 去比对 NEW.course_id 的、
-- certificate.course_id 又是 uk_user_course 的去重键 —— 换出来的就是一张
-- 挂在第 2 门课名下的结业证明。今天 ExamService 是从 paper 行取 course_id 写进去的，
-- 所以不是可达路径；但这是这三元关系里唯一没有库层判据的一条边。
-- 开放卷两边同为 NULL，<=> 天然放行。
DROP TRIGGER IF EXISTS `trg_exam_attempt_ins`$$
CREATE TRIGGER `trg_exam_attempt_ins`
BEFORE INSERT ON `exam_attempt` FOR EACH ROW
BEGIN
  DECLARE q_cnt INT DEFAULT NULL;
  DECLARE p_score INT DEFAULT NULL;
  DECLARE max_try INT DEFAULT NULL;
  DECLARE cfg_paper BIGINT UNSIGNED DEFAULT NULL;
  DECLARE p_course BIGINT UNSIGNED DEFAULT NULL;
  DECLARE p_cnt INT DEFAULT 0;
  SELECT `question_count`,`pass_score`,`max_attempts`,`paper_id`
    INTO q_cnt, p_score, max_try, cfg_paper
    FROM `exam_config` WHERE id = NEW.config_id;
  IF q_cnt IS NULL THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '测评规则不存在，不能开考';
  END IF;
  -- 试卷与规则必须是同一份，否则抽题与判分各按各的走，且一路不报错
  IF NOT (NEW.`paper_id` <=> cfg_paper) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '这场考试的试卷与规则不是同一份：抽题按一份卷、判分按另一份';
  END IF;
  -- 这场考试挂的课程必须就是该卷所属的课程（COUNT/MAX 一次取回：卷不在时 p_cnt=0，
  -- 单独报「试卷不存在」而不是让它顺着 p_course 恒为 NULL 报成「课程不一致」——
  -- 那句话会把人指到错的地方去）
  IF NEW.`paper_id` IS NOT NULL THEN
    SELECT COUNT(*), MAX(`course_id`) INTO p_cnt, p_course
      FROM `exam_paper` WHERE `id` = NEW.`paper_id`;
    IF p_cnt = 0 THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '这场考试挂的试卷不存在';
    END IF;
    IF NOT (NEW.`course_id` <=> p_course) THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '这场考试挂的课程与试卷所属课程不一致：换出来的会是一张挂错课的结业证明';
    END IF;
  END IF;
  -- 不限次补考等于没考（§3.3）
  IF NEW.attempt_no > max_try THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '已超出该课程的补考次数上限';
  END IF;
  IF NEW.score IS NOT NULL THEN
    IF NEW.score > q_cnt THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '得分不能超过卷面题量';
    END IF;
    IF NEW.submitted_at IS NULL THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '没有交卷时间的考试不能有分数';
    END IF;
    IF NEW.passed IS NOT NULL AND NEW.passed <> (NEW.score >= p_score) THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '通过标记必须与得分和及格线一致';
    END IF;
  END IF;
END$$


-- trg_exam_attempt_upd 也是【改体】：原来那四条（分数不超题量 / 没交卷不能有分数 /
-- passed 与分数自洽 / 已交卷不得改分）一字不动，在后面追加三条。
--
-- ★ 不补这三条的话，「章节卷换不出结业证明」这道闸一条 UPDATE 就绕过去了。
--   实测（在按生产结构建的临时库上）：拿一条已通过的章节卷记录
--   （paper_id=2、config_id=2、passed=1），先直接发证被拒 ——
--       ERROR 1644: 这份试卷不发结业证明：章节练兵卷与开放卷考过了也不等于结业
--   然后 `UPDATE exam_attempt SET paper_id=1 WHERE id=910` **成功**（此后该行
--   paper_id=1 而 config_id=2，正是插入侧明令禁止的形状），紧接着
--   `INSERT INTO certificate (...) VALUES ('CT_BYPASS',701,1,910)` 一次通过，证书落库。
--
--   本轮为 exam_paper 专门补了 trg_exam_paper_upd，理由写得很清楚（「光有插入侧不够，
--   缺口正对着最危险的那条路」）—— 同一条推理原样适用于 exam_attempt。
--   今天应用侧没有改 paper_id 的路径，但把这条判据放进库层的全部理由就是
--   「直接 INSERT、以后新写的某个补发功能都得过」，而 UPDATE 侧原来是敞开的。
--
-- 追加的三条与插入侧逐字同源：① 卷与规则同一份；② 卷与课程同一门；
-- ③ 已交卷不得改卷（与「已交卷不得改分」同一位置、同一理由：交完卷之后，
-- 这一行就是发证依据本身，改它等于改依据）。
DROP TRIGGER IF EXISTS `trg_exam_attempt_upd`$$
CREATE TRIGGER `trg_exam_attempt_upd`
BEFORE UPDATE ON `exam_attempt` FOR EACH ROW
BEGIN
  DECLARE q_cnt INT DEFAULT NULL;
  DECLARE p_score INT DEFAULT NULL;
  DECLARE cfg_paper BIGINT UNSIGNED DEFAULT NULL;
  DECLARE p_course BIGINT UNSIGNED DEFAULT NULL;
  DECLARE p_cnt INT DEFAULT 0;
  SELECT `question_count`,`pass_score`,`paper_id` INTO q_cnt, p_score, cfg_paper
    FROM `exam_config` WHERE id = NEW.config_id;
  IF NEW.score IS NOT NULL THEN
    IF NEW.score > q_cnt THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '得分不能超过卷面题量';
    END IF;
    IF NEW.submitted_at IS NULL THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '没有交卷时间的考试不能有分数';
    END IF;
    IF NEW.passed IS NOT NULL AND NEW.passed <> (NEW.score >= p_score) THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '通过标记必须与得分和及格线一致';
    END IF;
  END IF;
  -- 交卷后不得改分：改分是发证依据被篡改的唯一入口
  IF OLD.submitted_at IS NOT NULL AND NOT (NEW.score <=> OLD.score) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '已交卷的考试不得改分';
  END IF;
  -- 交卷后也不得改卷：章节卷考完改成综合卷，换出来的就是一张假的结业证明
  IF OLD.submitted_at IS NOT NULL AND NOT (NEW.`paper_id` <=> OLD.`paper_id`) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '已交卷的考试不得改卷';
  END IF;
  -- 与插入侧同源的两条：卷与规则同一份、卷与课程同一门
  IF NOT (NEW.`paper_id` <=> cfg_paper) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '这场考试的试卷与规则不是同一份：抽题按一份卷、判分按另一份';
  END IF;
  IF NEW.`paper_id` IS NOT NULL THEN
    SELECT COUNT(*), MAX(`course_id`) INTO p_cnt, p_course
      FROM `exam_paper` WHERE `id` = NEW.`paper_id`;
    IF p_cnt = 0 THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '这场考试挂的试卷不存在';
    END IF;
    IF NOT (NEW.`course_id` <=> p_course) THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '这场考试挂的课程与试卷所属课程不一致：换出来的会是一张挂错课的结业证明';
    END IF;
  END IF;
END$$


-- trg_certificate_precheck 也是【改体】。原来那条判据一字不动，只在后面追加一条。
--
-- 追加的是「这场考试所属的试卷必须发证」。原来的三条问的是「是不是一次真通过的考试、
-- 是不是这个人、是不是这门课」，问不到「这份卷凭什么发证」—— 而多套卷上线之后，
-- 同一门课下有四份 50 题的章节练兵卷，考过任何一份都能满足原来那三条。
--
-- 判据落在 exam_paper.grants_certificate 上，与 CertificateService 取「已通过的考试」时
-- 那句 JOIN 是同一条（规格 §2）。
--
-- ★ 用 JOIN 而不是「paper_id 为空就放行」：attempt.paper_id 为空恰恰是最该拒的情形 ——
--   那是一场没人说得清考的是哪份卷的考试，拿它换出来的证明也就说不清证明了什么。
--   生产的 exam_attempt 是 0 行，这一条对存量零射程。
DROP TRIGGER IF EXISTS `trg_certificate_precheck`$$
CREATE TRIGGER `trg_certificate_precheck`
BEFORE INSERT ON `certificate` FOR EACH ROW
BEGIN
  -- 证明是对外物料，签发依据必须是一次真实通过的考试，且考的就是这个人这门课
  IF NOT EXISTS (SELECT 1 FROM `exam_attempt`
                 WHERE id = NEW.attempt_id
                   AND passed = 1
                   AND user_id   = NEW.user_id
                   AND course_id = NEW.course_id) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '结业证明只能签发给该学员该门课一次已通过的考试';
  END IF;
  -- 那份卷得是发证的卷：章节练兵卷考过 50 题不等于结业
  IF NOT EXISTS (SELECT 1 FROM `exam_attempt` `a`
                   JOIN `exam_paper` `p` ON `p`.`id` = `a`.`paper_id`
                  WHERE `a`.`id` = NEW.attempt_id
                    AND `p`.`grants_certificate` <> 0) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '这份试卷不发结业证明：章节练兵卷与开放卷考过了也不等于结业';
  END IF;
END$$

DELIMITER ;


-- ---------------------------------------------------------------- 上线的六张卷
--
-- 规格里的表逐行落地。id 写死 1..6：exam_paper 是本轮新建的表，这六个号一定是空的，
-- 而写死之后导题脚本、前端链接、以后的运维脚本都能按号说话，不必先查一次 code。
--
-- ON DUPLICATE KEY UPDATE 里**故意不带 status**：重跑这份文件不应该把运营手动下架的卷
-- 又给上回去。其余字段跟着文件走 —— 它们是这份文件定义的东西，改文件就是改它们。
--
-- sort_no 留 10 的间隔：以后在中间插一张卷不必给整列重新编号。

INSERT INTO `exam_paper`
  (`id`,`code`,`title`,`subtitle`,`course_id`,`access`,`grants_certificate`,`status`,`sort_no`) VALUES
  (1,'FDE_MAIN',  '结业综合测评',          '覆盖全部章节，通过后可申领课程结业证明', 1,   'ENROLLED',1,'ON', 10),
  (2,'FDE_CH1',   '第一章 · 前端与 skill',  '本章练兵卷，不发结业证明',            1,   'ENROLLED',0,'ON', 20),
  (3,'FDE_CH2',   '第二章 · 后端与多模态',  '本章练兵卷，不发结业证明',            1,   'ENROLLED',0,'ON', 30),
  (4,'FDE_CH3',   '第三章 · AI 原理与 RAG', '本章练兵卷，不发结业证明',            1,   'ENROLLED',0,'ON', 40),
  (5,'FDE_CH4',   '第四章 · 智能体开发',    '本章练兵卷，不发结业证明',            1,   'ENROLLED',0,'ON', 50),
  (6,'AIGC_OPEN', 'AIGC 内容生产能力测试',  '面向全部注册学员开放，不需要购课',      NULL,'OPEN',    0,'ON',100)
ON DUPLICATE KEY UPDATE
  `title`=VALUES(`title`), `subtitle`=VALUES(`subtitle`), `course_id`=VALUES(`course_id`),
  `access`=VALUES(`access`), `grants_certificate`=VALUES(`grants_certificate`),
  `sort_no`=VALUES(`sort_no`);


-- ---------------------------------------------------------------- 六份规则
--
-- ★ FDE_MAIN 复用**现有那行 exam_config**（种子里 course_id=1 的那行），
--   把它的 paper_id 指过去，并就地把 pass_score 改成 80、duration_min 改成 60。
--
--   就地改在这张表上平时是禁忌 —— 它是版本化的，已开考的场次按 attempt.config_id
--   引用旧行，改旧行等于把学员脚下的规则换掉。这一次可以，理由只有一条而且是可验证的：
--   **生产的 exam_attempt 是 0 行**，没有任何一场考试引用着这行规则，
--   所以「改旧行」与「插新行再让旧行永远没人引用」在可观测行为上完全等价，
--   而前者不会在库里留下一行谁都查不到的僵尸规则。（文件开头那段断言把这个前提钉住了：
--   表非空的话整个迁移在动第一个 DDL 之前就已经拒绝了。）
--
--   另外三个数不改：question_count 仍是 100、max_attempts 仍是 3、retry_cooldown_hours
--   仍是 24、require_progress_pct 仍是 80 —— 规格表里这四项与现状一致。
--
-- 守一道：允许「就地改」的前提是 course 1 下**只有一行**旧规则。那道断言已经在
-- 文件开头跑过了（见「就地改旧规则的前置断言」）—— 它必须排在任何 DDL 之前，
-- 排在这里的话失败时留下的是一个半落地的库。

UPDATE `exam_config`
   SET `paper_id` = 1, `pass_score` = 80, `duration_min` = 60
 WHERE `paper_id` IS NULL AND `course_id` = 1;

-- 兜底：目标库里没有那行旧规则时（按 db/schema.sql 新装而没灌 seed、
-- 或者旧规则挂在别的课程下），上面那句 UPDATE 命中 0 行，FDE_MAIN 这张卷就没有规则。
-- 而这个坑要等到文件末尾 sp_assert_landed 才炸，那时全部 DDL、六张卷、五行规则
-- 都已经写进去了 —— 实测（按 schema.sql 建库、不灌 seed，再跑本文件）：
--     ERROR 1644 (45000): 迁移未完整落地：六张卷里只有 5 张有规则，剩下的开不了考
-- 退出码 1，而 exam_paper 已有 6 行 status='ON'、exam_config 已有 5 行（paper 2..6），
-- 且此后每次重跑都停在同一处，永远修不回来。
--
-- 所以补一句与下面 paper 2..6 那五句同形的「不存在才插」。数字与上面那句 UPDATE
-- 改出来的形状逐字一致（100 题 / 80 分 / 60 分钟 / 3 次 / 24 小时 / 进度 80%）。
-- 生产走的是上面那句 UPDATE（旧行在），这一句在生产上命中 0 行。
INSERT INTO `exam_config`
  (`course_id`,`paper_id`,`question_count`,`pass_score`,`duration_min`,
   `shuffle_options`,`max_attempts`,`retry_cooldown_hours`,`require_progress_pct`,`effective_from`)
SELECT 1, 1, 100, 80, 60, 1, 3, 24, 80, '2026-01-01 00:00:00' FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `exam_config` WHERE `paper_id` = 1);

-- 另外五行是新插的。逐句写成「不存在才插」的反连接，而不是 ON DUPLICATE KEY ——
-- exam_config 上没有、也不该有以 paper_id 为唯一键的约束（一卷多版本正是它的用途）。
--
-- effective_from 一律取 2026-01-01：它必须**落在过去**，否则 effectiveRules 那句
--   WHERE paper_id=? AND effective_from<=NOW(3)
-- 一行都取不到，学员端把卷列出来、点开考报「测评规则不存在」。写 NOW(3) 也不行 ——
-- 迁移与应用重启之间那几秒钟会有一段时间差，而且重跑时会插出第二行。
--
-- 章节卷的 require_progress_pct 是 0：它们是练兵卷，看完第一章就该能考第一章，
-- 而 80% 的进度门槛是给结业综合测评的。

INSERT INTO `exam_config`
  (`course_id`,`paper_id`,`question_count`,`pass_score`,`duration_min`,
   `shuffle_options`,`max_attempts`,`retry_cooldown_hours`,`require_progress_pct`,`effective_from`)
SELECT 1, 2, 50, 40, 35, 1, 5, 2, 0, '2026-01-01 00:00:00' FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `exam_config` WHERE `paper_id` = 2);

INSERT INTO `exam_config`
  (`course_id`,`paper_id`,`question_count`,`pass_score`,`duration_min`,
   `shuffle_options`,`max_attempts`,`retry_cooldown_hours`,`require_progress_pct`,`effective_from`)
SELECT 1, 3, 50, 40, 35, 1, 5, 2, 0, '2026-01-01 00:00:00' FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `exam_config` WHERE `paper_id` = 3);

INSERT INTO `exam_config`
  (`course_id`,`paper_id`,`question_count`,`pass_score`,`duration_min`,
   `shuffle_options`,`max_attempts`,`retry_cooldown_hours`,`require_progress_pct`,`effective_from`)
SELECT 1, 4, 50, 40, 35, 1, 5, 2, 0, '2026-01-01 00:00:00' FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `exam_config` WHERE `paper_id` = 4);

INSERT INTO `exam_config`
  (`course_id`,`paper_id`,`question_count`,`pass_score`,`duration_min`,
   `shuffle_options`,`max_attempts`,`retry_cooldown_hours`,`require_progress_pct`,`effective_from`)
SELECT 1, 5, 50, 40, 35, 1, 5, 2, 0, '2026-01-01 00:00:00' FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `exam_config` WHERE `paper_id` = 5);

-- 开放卷：course_id 为 NULL，这是 exam_config.course_id 放宽到可空的唯一用处。
INSERT INTO `exam_config`
  (`course_id`,`paper_id`,`question_count`,`pass_score`,`duration_min`,
   `shuffle_options`,`max_attempts`,`retry_cooldown_hours`,`require_progress_pct`,`effective_from`)
SELECT NULL, 6, 100, 80, 60, 1, 3, 24, 0, '2026-01-01 00:00:00' FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `exam_config` WHERE `paper_id` = 6);


-- ---------------------------------------------------------------- 收尾自检
--
-- 迁移「跑完没报错」不等于「都落地了」：上面每个 CALL 都是条件执行，
-- 触发器段又是另一套权限（binlog 下应用账号建触发器是 ERROR 1419，
-- 而错只打在触发器那几行，前面的表和列照样成功）。所以这里自己点一遍名，
-- 少一样就当场 SIGNAL —— 让迁移失败，而不是让上线后第一个拿章节卷换证书的学员去发现。
--
-- 逐组分开断言而不是加成一个总数：总数对不上只告诉你「少了点什么」，
-- 而这一轮要点名的有表、列、可空性、索引、触发器、触发器体、数据六类，
-- 少哪一类的补法完全不同。
--
-- ★ 两个改体的触发器**按体点名，不按名字点名**：trg_exam_attempt_ins 与
--   trg_certificate_precheck 在迁移之前就存在，光查名字在不在恒为真 ——
--   那条断言从第一天起就是绿的，什么都没验。所以查 ACTION_STATEMENT 里
--   有没有这一轮才引进来的那两个词。

DROP PROCEDURE IF EXISTS `sp_assert_landed`;
DELIMITER $$
CREATE PROCEDURE `sp_assert_landed`()
BEGIN
  DECLARE v_n   INT DEFAULT 0;
  DECLARE v_msg VARCHAR(255);

  SELECT COUNT(*) INTO v_n FROM information_schema.TABLES
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='exam_paper';
  IF v_n <> 1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '迁移未完整落地：exam_paper 表没建出来';
  END IF;

  -- 3 个新列
  SELECT COUNT(*) INTO v_n FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA=DATABASE() AND COLUMN_NAME='paper_id'
      AND TABLE_NAME IN ('exam_config','question','exam_attempt');
  IF v_n <> 3 THEN
    SET v_msg = CONCAT('迁移未完整落地：paper_id 应加在 3 张表上，实到 ', v_n);
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = v_msg;
  END IF;

  -- 3 个放宽为可空的列。这一条必须按 IS_NULLABLE 查，不能按列名查 ——
  -- course_id 本来就在，按名字查恒为真。
  SELECT COUNT(*) INTO v_n FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA=DATABASE() AND COLUMN_NAME='course_id' AND IS_NULLABLE='YES'
      AND TABLE_NAME IN ('exam_config','exam_attempt','question');
  IF v_n <> 3 THEN
    SET v_msg = CONCAT('迁移未完整落地：course_id 应在 3 张表上放宽为可空，实到 ', v_n,
                       ' —— 没放宽的话开放卷的规则、考试与题目都写不进去');
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = v_msg;
  END IF;

  -- 题必须至少挂一头（卷或课），否则谁也抽不到，静静躺在库里占着编号
  SELECT COUNT(*) INTO v_n FROM information_schema.TABLE_CONSTRAINTS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='question'
      AND CONSTRAINT_NAME='ck_question_scope' AND CONSTRAINT_TYPE='CHECK';
  IF v_n <> 1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '迁移未完整落地：question.ck_question_scope 没建出来';
  END IF;

  -- 3 条新索引
  SELECT COUNT(DISTINCT CONCAT(TABLE_NAME,'.',INDEX_NAME)) INTO v_n FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA=DATABASE()
      AND ((TABLE_NAME='exam_attempt' AND INDEX_NAME='uk_user_paper_attempt')
        OR (TABLE_NAME='question'     AND INDEX_NAME='idx_paper_pool')
        OR (TABLE_NAME='exam_config'  AND INDEX_NAME='idx_paper_rule'));
  IF v_n <> 3 THEN
    SET v_msg = CONCAT('迁移未完整落地：新索引应有 3 条，实到 ', v_n);
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = v_msg;
  END IF;

  -- 旧唯一键必须真的没了。留着它的话，同一门课下的第二张卷开不出第一次考试。
  SELECT COUNT(*) INTO v_n FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='exam_attempt'
      AND INDEX_NAME='uk_user_course_attempt';
  IF v_n <> 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '迁移未完整落地：旧唯一键 uk_user_course_attempt 还在，同一门课的第二张卷开不了考';
  END IF;

  -- 6 个触发器（3 新建 + 3 改体）
  SELECT COUNT(*) INTO v_n FROM information_schema.TRIGGERS
    WHERE TRIGGER_SCHEMA=DATABASE()
      AND TRIGGER_NAME IN ('trg_exam_paper_precheck','trg_exam_paper_upd','trg_exam_config_paper',
                           'trg_exam_attempt_ins','trg_exam_attempt_upd','trg_certificate_precheck');
  IF v_n <> 6 THEN
    SET v_msg = CONCAT('迁移未完整落地：触发器应有 6 个，实到 ', v_n,
                       ' —— 触发器段是不是没用 root 跑？');
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = v_msg;
  END IF;

  -- 三个改体的触发器：名字在不等于体改了。
  -- 两个 attempt 触发器点的名是 p_course（本轮最后引进来的那个变量）而不是 cfg_paper：
  -- 装过一版「只补了 cfg_paper、没补课程一致」的旧稿时，按 cfg_paper 点名是绿的。
  SELECT COUNT(*) INTO v_n FROM information_schema.TRIGGERS
    WHERE TRIGGER_SCHEMA=DATABASE()
      AND ((TRIGGER_NAME='trg_exam_attempt_ins'     AND ACTION_STATEMENT LIKE '%p_course%')
        OR (TRIGGER_NAME='trg_exam_attempt_upd'     AND ACTION_STATEMENT LIKE '%p_course%')
        OR (TRIGGER_NAME='trg_certificate_precheck' AND ACTION_STATEMENT LIKE '%grants_certificate%'));
  IF v_n <> 3 THEN
    SET v_msg = CONCAT('迁移未完整落地：三个改体的触发器只有 ', v_n,
                       ' 个是新版 —— 它们本来就存在，重跑时是不是被别的连接建成旧版了？');
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = v_msg;
  END IF;

  -- 六张卷。按 code 点名而不是 COUNT(*)=6：以后运营新建了第七张卷，
  -- 这份文件还得能重跑。
  SELECT COUNT(*) INTO v_n FROM `exam_paper`
    WHERE `code` IN ('FDE_MAIN','FDE_CH1','FDE_CH2','FDE_CH3','FDE_CH4','AIGC_OPEN');
  IF v_n <> 6 THEN
    SET v_msg = CONCAT('迁移未完整落地：六张卷实到 ', v_n, ' 张');
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = v_msg;
  END IF;

  -- 六份规则。按 DISTINCT paper_id 数，因为一卷可以有多版规则（版本化本来就是这么用的）。
  SELECT COUNT(DISTINCT `c`.`paper_id`) INTO v_n
    FROM `exam_config` `c` JOIN `exam_paper` `p` ON `p`.`id` = `c`.`paper_id`
   WHERE `p`.`code` IN ('FDE_MAIN','FDE_CH1','FDE_CH2','FDE_CH3','FDE_CH4','AIGC_OPEN');
  IF v_n <> 6 THEN
    SET v_msg = CONCAT('迁移未完整落地：六张卷里只有 ', v_n, ' 张有规则，剩下的开不了考');
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = v_msg;
  END IF;

  -- 用户拍板的及格线 80 分、时长 60 分钟真的改到了那行老规则上。
  -- 只查「有没有这张表」是查不出来的：老行原来是 90 分 / 45 分钟，两个数都合法。
  SELECT COUNT(*) INTO v_n
    FROM `exam_config` `c` JOIN `exam_paper` `p` ON `p`.`id` = `c`.`paper_id`
   WHERE `p`.`code` = 'FDE_MAIN' AND `c`.`pass_score` = 80 AND `c`.`duration_min` = 60;
  IF v_n < 1 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '迁移未完整落地：结业综合测评的及格线/时长没改成 80 分 / 60 分钟';
  END IF;
END$$
DELIMITER ;
CALL `sp_assert_landed`();
DROP PROCEDURE `sp_assert_landed`;
