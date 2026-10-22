-- 测评报告：学员每交一次卷，平台给他出一份报告（事实层由代码算、叙述层由模型写）。
-- 本文件只管库结构：question 补三列（报告按它们聚合）+ 新表 exam_report 与它的两个闸门。
--
-- ★ 这份迁移里的【触发器段必须用 root 连接执行】，不能用应用账号：
--   COURSE 的 MySQL 开着 binlog 而 log_bin_trust_function_creators=0（实测 @@log_bin=1），
--   应用账号没有 SUPER/TRIGGER 权限，建触发器会报
--   「ERROR 1419: You do not have the SUPER privilege and binary logging is enabled」，
--   而这个错**只打在触发器那几行上** —— 表、列、CHECK 全都建好了，闸门一个没有。
--       ssh COURSE 'mysql wangke < 这个文件'
--   跑完不要只看「没报错」：要在库上真查一次
--       SELECT TRIGGER_NAME FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE();
--   确认 trg_exam_report_precheck / trg_exam_report_upd 两样都在。
--
--   ★ 这一轮少了闸门的后果比上一轮更隐蔽：exam_report 的四条判据**全部**在触发器里
--     （跨表那两条 CHECK 表达不了，见下），触发器没建成的话报告照常入库、
--     「READY 却没有正文」照常落地，学员点开报告看到的是一页空白，一个字都不报。
--
--
-- ## 为什么报告是一张新表，而不是 exam_attempt 上的几列
--
--   ① **两者的生命周期正好相反。** exam_attempt 交完卷就是发证依据，那张表上有
--      「已交卷不得改分」「已交卷不得改卷」两条闸；而报告在一分钟里要改三次状态
--      （QUEUED → RUNNING → READY），失败还要退回 QUEUED 重来。
--      把报告列挂到 exam_attempt 上，每一次状态流转都得从那两条闸下面过 ——
--      而为了让它过去就得放宽判据，等于把「改分」那条闸的边界重新划一遍。
--   ② **报告是可重生成的派生物。** 删掉一份报告重出一份，业务上什么都没发生；
--      而 exam_attempt 的任何一行都不可重来。
--   ③ 一场考试一份报告这件事，只有把 attempt_id 做成**唯一键**才守得住（见下）。
--
--
-- ## 为什么 stats 是 NOT NULL
--
-- 规格第一节把报告切成两层，全部理由就一句：模型会挂、会超时、会写错，
-- 而学员的分数与错题是客观的。事实层由代码算、**永远算得出来**，
-- 所以 enqueue 那一步就得把它算好写进去，不能等模型回来再补。
--
-- 允许 stats 为空的话，「模型还没跑完」与「连分数都没有」在库里同形，
-- 而报告页在 QUEUED/RUNNING 状态下要显示的正是 stats（规格 §4：状态是 QUEUED/RUNNING
-- 时也要把 stats 返回）—— 空的话学员在那 19 秒里看到的是一页空白，
-- 分两层这件事就白做了。
--
--
-- ## 四条判据为什么全在触发器里，而不是一半写成 CHECK
--
-- 「READY 必须有正文」「FAILED 必须有理由」这两条 CHECK 表达得了；
-- 「只能给已交卷的场次建报告」「报告的学员与试卷必须与那场考试一致」这两条要跨表查
-- exam_attempt，CHECK 做不到（CHECK 里不许有子查询）。
--
-- 拆成两处的代价是：以后问「一份报告落库要过哪些闸」得看两个地方，
-- 而更新侧那个同源触发器还得把 CHECK 那两条再抄一遍（CHECK 在 UPDATE 时也生效，
-- 于是同一条判据会在两个地方各写一遍、报出两句不同的话）。所以四条一起放触发器。


-- ================================================================ 锁等待
--
-- 生产的 lock_wait_timeout 是默认值 31536000 秒（一整年，刚在 COURSE 上查过）。
-- 下面给 question 加三列要拿它的排他元数据锁；只要那一刻有任何长事务握着这张表，
-- ALTER 就会**等上一年**，而元数据锁是排队的 —— 排在它后面的每一条查询也一起无限期挂住。
-- 抽题走的正是 question。
--
-- 10 秒拿不到就认输：失败是响的、可重试的，而挂死是哑的。
SET SESSION lock_wait_timeout = 10;


-- ================================================================ 权限探针
--
-- ★ 这一段必须排在**任何 DDL 之前**，它的全部作用是「用错账号时先炸，别先拆东西」。
--
-- 用应用账号跑这份文件的话：CREATE TABLE 与 ADD COLUMN 都**会成功**
-- （应用账号是 GRANT ALL ON wangke.*），而 CREATE TRIGGER 因为 log_bin=ON 且
-- log_bin_trust_function_creators=OFF 报 1419。收场是「表和列全在、两个闸门一个没有」，
-- 而 exam_report 的判据全在那两个触发器里。
--
-- 文件末尾那段 sp_assert_landed 救不了这个：`mysql db < file` 首错即停，走不到那一行。
--
-- 探针建在 `sku` 上：运行期没有任何代码往 sku 插行，而 CREATE TRIGGER 要的权限与表无关。
-- 触发器体是空的，即便在存在的那一瞬间有人插也什么都不做。
-- （与 20260903_多套试卷.sql 里那段逐字同源 —— 同一天、同一套判据。）
--
--     正确跑法： ssh COURSE 'mysql wangke < 这个文件'      （默认就是 root@localhost）

DROP TRIGGER IF EXISTS `trg_privilege_probe`;

DELIMITER $$
CREATE TRIGGER `trg_privilege_probe` BEFORE INSERT ON `sku` FOR EACH ROW
BEGIN
END$$
DELIMITER ;

DROP TRIGGER `trg_privilege_probe`;


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

-- ★ 与 20260903_多套试卷.sql 里同名的那个过程差一段【探针】，是有意的：
--   那一份要防的是「先 DROP 再 ADD 把改约束做成了删约束」—— 存量数据里有一行顶回来时
--   ADD 失败而旧约束已经没了。本轮加的是一条全新约束、没有任何 DROP，
--   而失败的 ALTER 整条回滚（DDL 不留半截），所以先拿临时名验一遍纯属多跑一次表重建。
DROP PROCEDURE IF EXISTS `sp_run_if_check_missing`$$
CREATE PROCEDURE `sp_run_if_check_missing`(IN p_table VARCHAR(64), IN p_name VARCHAR(64), IN p_ddl TEXT)
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.TABLE_CONSTRAINTS
                  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table
                    AND CONSTRAINT_NAME = p_name AND CONSTRAINT_TYPE = 'CHECK') THEN
    SET @ddl = p_ddl; PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
  END IF;
END$$

DELIMITER ;


-- ---------------------------------------------------------------- question 补三列
--
-- 报告要按这三列聚合与逐题显示（规格 §3）。三列全都可空：上线顺序是
-- 「迁移 → 回填 400 题」，中间那一段里它们全是 NULL，而抽题与判分一个字都不看这三列，
-- 所以那一段时间里考试照常，只是报告的按考点聚合是空的。
--
--   · topic 是**聚合**用的粗粒度分组（课程卷=章名，AIGC 卷=板块名）。
--     ★ 为什么不复用 knowledge_point 做聚合：实测 50 题里有 37 个互不相同的考点，
--       按它分组等于一组一题，报告会变成错题复述 —— 那不是诊断，是把卷子再念一遍。
--   · knowledge_point 是细粒度考点，只用于错题清单里逐题显示。
--     160 是按现有题库最长的那条考点留的余量；VARCHAR 超长是**静默截断**还是报错
--     取决于 sql_mode，不留余量的话回填脚本可能悄悄把考点截掉半句。
--   · difficulty 1/2/3 = 扎实理解 / 需要实操 / 踩过坑才会。
--     它是 TINYINT，写 0、4、-1 进来在库层全都合法，而报告按难度分组时
--     那些行会落到一个谁也没定义过的桶里、在页面上显示成一个空标题的分组 ——
--     所以配一条 CHECK（见下）。
--
-- 三列的位置放在 source_ts_sec 之后：它前面是「这道题属于谁、出处在哪」，
-- 后面是「题面本身」，这三列讲的是「这道题考什么、多难」，插在中间读得下去。
-- 与 db/schema.sql 里的声明位置保持一致，免得两边建出来的表列序不同、
-- mysqldump 一比全是噪声。

CALL `sp_run_if_col_missing`('question', 'topic',
  'ALTER TABLE `question` ADD COLUMN `topic` VARCHAR(64) NULL COMMENT ''报告聚合用的粗粒度分组：课程卷=章名，AIGC 卷=板块名'' AFTER `source_ts_sec`');

CALL `sp_run_if_col_missing`('question', 'knowledge_point',
  'ALTER TABLE `question` ADD COLUMN `knowledge_point` VARCHAR(160) NULL COMMENT ''细粒度考点，错题清单里逐题显示；不做聚合（实测 50 题 37 个考点，一组一题）'' AFTER `topic`');

CALL `sp_run_if_col_missing`('question', 'difficulty',
  'ALTER TABLE `question` ADD COLUMN `difficulty` TINYINT NULL COMMENT ''1 扎实理解 / 2 需要实操 / 3 踩过坑才会'' AFTER `knowledge_point`');

-- 1..3 之外的难度不是「多一个值」，是报告里多一个没有名字的分组。
-- CHECK 在 8.0.16 起是真校验，回填脚本写错当场就被顶回来。
CALL `sp_run_if_check_missing`('question', 'ck_question_difficulty',
  'ALTER TABLE `question` ADD CONSTRAINT `ck_question_difficulty` CHECK (`difficulty` IS NULL OR `difficulty` BETWEEN 1 AND 3)');


DROP PROCEDURE IF EXISTS `sp_run_if_col_missing`;
DROP PROCEDURE IF EXISTS `sp_run_if_check_missing`;


-- ---------------------------------------------------------------- 新表：测评报告
--
--   · attempt_id **唯一**：一场考试一份报告。没有这条唯一键的话，重试或者两个实例
--     同时入队会插出第二行，而学员端是按 attempt 查报告的 —— 两行同时存在时
--     页面显示哪一份取决于查询顺序（`LIMIT 1` 没有 ORDER BY），
--     今天看到成功那份、刷新一下看到失败那份，而两行各自看都合法。
--     它同时也是「抢占」成立的前提：重试是 UPDATE 这一行，不是插新行。
--   · user_id 是**越权判据本身**（规格 §4：越权写在 SQL 的 WHERE 里 `AND user_id=?`，
--     「不是你的」与「不存在」对外同一种结果）。它必须与那场考试的 user_id 一致，
--     由触发器守着 —— 写错等于把别人的错题清单发给这个人，而两行记录各自看都合法。
--   · paper_id 冗余存一份：行动清单里要给「去考另一份卷」的真实卷名清单（规格 §2），
--     得先知道这一场考的是哪份卷。同样由触发器钉死它与那场考试一致。
--   · status 是学员端唯一的显示判据：QUEUED/RUNNING 转圈并轮询，READY 显示正文，
--     FAILED 显示理由与「重新生成」。
--   · model 记下**真正出这份正文的模型 id**，不是配置里写的那个。换模型之后回头看
--     「这份报告是哪个模型写的」只有这一列答得上来。
--   · content 用 MEDIUMTEXT：TEXT 是 64KB，一份报告远到不了，但 TEXT 超长是
--     **静默截断**（非严格模式下），而截断一份报告的表现是「最后一段话说到一半」——
--     与模型自己写崩了长得一模一样。留三个数量级的余量，这一类判断就不用做。
--   · stats NOT NULL：见文件头。
--   · tries 给自愈用：清扫把卡住的 RUNNING 退回 QUEUED 时 +1，到 3 就置 FAILED，
--     否则一份永远生成不出来的报告会被无限重试到天荒地老。
--   · requested_at / started_at / finished_at 三个时点分开记：
--     「排队排了多久」与「模型跑了多久」是两回事，只留一个 updated_at 的话
--     19 秒到底花在哪一段就再也查不出来了（规格 §零：真实一次 19.3 秒）。
--
-- 不加外键：全库没有一条外键（schema.sql 里 0 处），本表不破例。
--
-- idx_report_queue(status, requested_at) 对着后台那两句查：
--   取活：WHERE status='QUEUED' ORDER BY requested_at            —— 整条走索引
--   清扫：WHERE status='RUNNING' AND started_at < NOW()-10min    —— 走 status 前缀，
--         再比 started_at。扫的是「此刻正在跑的那几行」（2 线程 × 实例数），
--         不值得为它单开一条索引。

CREATE TABLE IF NOT EXISTS `exam_report` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `attempt_id`   BIGINT UNSIGNED NOT NULL              COMMENT '一场考试一份报告；重试是改这一行，不是插新行',
  `user_id`      BIGINT UNSIGNED NOT NULL              COMMENT '越权判据就写在这一列上（AND user_id=?）；必须与那场考试一致',
  `paper_id`     BIGINT UNSIGNED NOT NULL              COMMENT '考的是哪份卷；行动清单要按它给「去考另一份卷」',
  `status`       ENUM('QUEUED','RUNNING','READY','FAILED') NOT NULL DEFAULT 'QUEUED'
                 COMMENT '学员端唯一的显示判据：转圈 / 正文 / 失败理由',
  `model`        VARCHAR(64)     NULL                  COMMENT '真正出这份正文的模型 id，不是配置里写的那个',
  `content`      MEDIUMTEXT      NULL                  COMMENT '叙述层正文（Markdown）；TEXT 超长会静默截断，故用 MEDIUMTEXT',
  `stats`        JSON            NOT NULL              COMMENT '事实层：分数、按讲次/考点的正确率、错题清单。模型挂了它照常显示',
  `fail_reason`  VARCHAR(255)    NULL                  COMMENT 'FAILED 时原样显示给学员，所以必须是人话',
  `tries`        INT             NOT NULL DEFAULT 0    COMMENT '清扫退回 QUEUED 时 +1；到 3 置 FAILED，不再无限重试',
  `requested_at` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `started_at`   DATETIME(3)     NULL                  COMMENT '抢到这一行的时刻；清扫按它判「卡住了」',
  `finished_at`  DATETIME(3)     NULL,
  `updated_at`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_report_attempt` (`attempt_id`),
  KEY `idx_report_queue` (`status`,`requested_at`)     COMMENT '后台取活与清扫走这条'
) ENGINE=InnoDB COMMENT='测评报告（事实层由代码算，叙述层由模型写）';


-- ---------------------------------------------------------------- 触发器
--
-- ★ 从这里往下必须用 root 连接执行（见文件头）。

DELIMITER $$

-- 报告的四条自洽，插入侧。判据与下面 trg_exam_report_upd 同源
-- （两处必须一起改 —— 只改一处的表现是「新建的报告建不成，而把一行老报告
-- UPDATE 成同样的形状能改成」，而报告的整条状态流转本来就走 UPDATE）。
-- ★ 唯一有意不同源的是①：更新侧对「已经孤儿的行」放行，理由写在下面那个触发器上方。
--
--   ① 挂的考试得存在。单独分支报「考试不存在」，不让它顺着 a_user 恒为 NULL
--      报成「与那场考试对不上」—— 那句话会把人指到错的地方去。
--   ② **只能给已交卷的场次建报告。** 报告里的每一个数都来自 exam_answer，
--      没交卷的场次一行明细都没有，那时算出来的事实层是「0 分、全错」——
--      而它与真考了 0 分在库里**完全同形**，事后不可分辨。学员看到的是一份
--      说他全错的报告，而他的卷子还开着。
--   ③ **学员与试卷必须与那场考试一致。** 报告页的越权判据就是 exam_report.user_id
--      （规格 §4），写错等于把别人那场考试的错题清单发给这个人，
--      而两行记录各自看都合法、一个字都不报。
--      比较用 <=>（NULL 安全）：exam_attempt.paper_id 是可空列，
--      一边空一边非空时 `=` 的结果是 NULL，IF NULL 走 false 分支 = **放行**。
--   ④ READY 必须有正文、FAILED 必须写明理由。见下面 trg_exam_report_upd 那段 ——
--      真正会写出这两种形状的是更新侧，插入侧这两条是为了「两处逐字同源」。
--
-- 判空不用 `content IS NULL OR content = ''`，也不用 TRIM：模型返回的空常常是
-- 「两个换行」或者一个全角空格，TRIM 只去半角空格 —— 实测 CHAR_LENGTH(TRIM('  \n  '))=1，
-- 判据会认为「有正文」而放行。REGEXP_REPLACE 去掉全部空白字符再数长度，
-- 换行、制表、全角空格三种都归零。
DROP TRIGGER IF EXISTS `trg_exam_report_precheck`$$
CREATE TRIGGER `trg_exam_report_precheck`
BEFORE INSERT ON `exam_report` FOR EACH ROW
BEGIN
  DECLARE a_cnt   INT DEFAULT 0;
  DECLARE a_user  BIGINT UNSIGNED DEFAULT NULL;
  DECLARE a_paper BIGINT UNSIGNED DEFAULT NULL;
  DECLARE a_sub   DATETIME(3) DEFAULT NULL;
  SELECT COUNT(*), MAX(`user_id`), MAX(`paper_id`), MAX(`submitted_at`)
    INTO a_cnt, a_user, a_paper, a_sub
    FROM `exam_attempt` WHERE `id` = NEW.`attempt_id`;
  IF a_cnt = 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '这份报告挂的考试不存在';
  END IF;
  IF a_sub IS NULL THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '没交卷的场次不能出测评报告：一行答题明细都没有，算出来的「全错」与真考零分同形';
  END IF;
  IF NOT (NEW.`user_id` <=> a_user) OR NOT (NEW.`paper_id` <=> a_paper) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '报告的学员或试卷与那场考试对不上：越权判据就写在这两列上';
  END IF;
  IF NEW.`status` = 'READY'
     AND (NEW.`content` IS NULL OR CHAR_LENGTH(REGEXP_REPLACE(NEW.`content`, '[[:space:]]', '')) = 0) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'READY 的报告必须有正文：状态说已生成而正文是空的，学员看到的是一页空白';
  END IF;
  IF NEW.`status` = 'FAILED'
     AND (NEW.`fail_reason` IS NULL OR CHAR_LENGTH(REGEXP_REPLACE(NEW.`fail_reason`, '[[:space:]]', '')) = 0) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'FAILED 的报告必须写明理由：不写理由的失败，学员和运维都无从下手';
  END IF;
END$$


-- 报告的四条自洽，更新侧。判据与上面 trg_exam_report_precheck 同源，
-- 只有「挂的考试不存在」这一条**有意与插入侧不同**（见下面那段 ★★★）。
--
-- ★ 更新侧才是主战场，这一点与本仓其它表相反：整条状态流转都是 UPDATE ——
--   QUEUED→RUNNING 抢占、RUNNING→READY 落正文、失败置 FAILED、清扫退回 QUEUED。
--   插入侧那几条守的是「刚入队的那一行」（status 缺省 QUEUED、content 必然为空），
--   而真正会写出「READY 却没有正文」的是**落正文那一句**：模型返回空串、
--   或者第二节那道时间点校验把正文判废之后忘了把 status 一起改回去。
--   只守插入侧等于把闸门装在唯一不会出错的那一步上。
--
-- 三元一致这条在更新侧同样要守：把一份报告改挂到另一场考试（attempt_id 换掉、
-- user_id 不动）就是「把这个人的报告页塞进别人的考试」，而 uk_report_attempt
-- 只拦「那场考试已经有报告了」这一种。
--
-- ★ 这里**故意没有状态机闸门**（不像 biz_order / withdraw_order 那样只许前进）：
--   报告的两条自愈路径都要往回走 —— 清扫把卡住的 RUNNING 退回 QUEUED（tries+1），
--   重试把 FAILED 退回 QUEUED。加一条「只能前进」的闸会把这两条一起拒掉，
--   而它们正是规格 §4 明写的。
--
-- ★★★ 「挂的考试不存在」在更新侧【只拦改挂，不拦已经孤儿的行】——
--   这是唯一一处与插入侧不同的地方，改动它之前先读完这一段。
--
--   删掉一场考试不会连带删它的报告（全库零外键），于是运维删过一场考试之后
--   库里就留着一行孤儿报告。清扫那两句是**整批** UPDATE
--   （`WHERE status='RUNNING' AND started_at < NOW()-10min`，不带 id），
--   BEFORE UPDATE 触发器对匹配到的每一行各跑一次 —— 孤儿那一行 SIGNAL 一响，
--   **整条语句回滚**，同一批里真正卡住的行一行都退不回队列。
--   表现是「全平台的报告一直转圈」，而日志里只有一句没有指向的「报告清扫失败」；
--   更糟的是运维连补救都做不了：那行孤儿的任何 UPDATE 都被同一条判据顶回来
--   （改不成 FAILED），只能整行 DELETE。已实测：删掉 attempt A 之后，
--   另一场健康但卡住的 B 也永远停在 RUNNING。
--
--   所以：a_cnt=0 且 attempt_id **没被改动**时放行 —— 一行孤儿报告本来就无一致性可言，
--   ②③ 那两条判据在它身上问不出任何东西（a_user/a_paper/a_sub 全是 NULL）。
--   而 attempt_id 被改动、改到的那一场又不存在，说明有人正在**制造**孤儿，照旧拒。
--   ④⑤（READY 要有正文、FAILED 要有理由）与挂哪一场考试无关，**无条件守**，
--   孤儿行也不许变成「READY 却一页空白」。
DROP TRIGGER IF EXISTS `trg_exam_report_upd`$$
CREATE TRIGGER `trg_exam_report_upd`
BEFORE UPDATE ON `exam_report` FOR EACH ROW
BEGIN
  DECLARE a_cnt   INT DEFAULT 0;
  DECLARE a_user  BIGINT UNSIGNED DEFAULT NULL;
  DECLARE a_paper BIGINT UNSIGNED DEFAULT NULL;
  DECLARE a_sub   DATETIME(3) DEFAULT NULL;
  SELECT COUNT(*), MAX(`user_id`), MAX(`paper_id`), MAX(`submitted_at`)
    INTO a_cnt, a_user, a_paper, a_sub
    FROM `exam_attempt` WHERE `id` = NEW.`attempt_id`;
  IF a_cnt = 0 THEN
    -- 见上面那段 ★★★：孤儿行放行（否则一行孤儿毒死整批清扫），改挂到不存在的场次照旧拒
    IF NOT (NEW.`attempt_id` <=> OLD.`attempt_id`) THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '这份报告挂的考试不存在';
    END IF;
  ELSE
    IF a_sub IS NULL THEN
      SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = '没交卷的场次不能出测评报告：一行答题明细都没有，算出来的「全错」与真考零分同形';
    END IF;
    IF NOT (NEW.`user_id` <=> a_user) OR NOT (NEW.`paper_id` <=> a_paper) THEN
      SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = '报告的学员或试卷与那场考试对不上：越权判据就写在这两列上';
    END IF;
  END IF;
  -- ④⑤ 与挂哪一场考试无关，孤儿行也照守
  IF NEW.`status` = 'READY'
     AND (NEW.`content` IS NULL OR CHAR_LENGTH(REGEXP_REPLACE(NEW.`content`, '[[:space:]]', '')) = 0) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'READY 的报告必须有正文：状态说已生成而正文是空的，学员看到的是一页空白';
  END IF;
  IF NEW.`status` = 'FAILED'
     AND (NEW.`fail_reason` IS NULL OR CHAR_LENGTH(REGEXP_REPLACE(NEW.`fail_reason`, '[[:space:]]', '')) = 0) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'FAILED 的报告必须写明理由：不写理由的失败，学员和运维都无从下手';
  END IF;
END$$

DELIMITER ;


-- ---------------------------------------------------------------- 收尾自检
--
-- 迁移「跑完没报错」不等于「都落地了」：上面每个 CALL 都是条件执行，
-- 触发器段又是另一套权限（binlog 下应用账号建触发器是 ERROR 1419，
-- 而错只打在触发器那几行，前面的表和列照样成功）。所以这里自己点一遍名，
-- 少一样就当场 SIGNAL —— 让迁移失败，而不是让上线后第一个交卷的学员去发现。
--
-- 逐组分开断言而不是加成一个总数：总数对不上只告诉你「少了点什么」，
-- 而这一轮要点名的有表、表的形状、唯一键、队列索引、列、CHECK、触发器、触发器体八类，
-- 少哪一类的补法完全不同。
--
-- ★ exam_report **按形状点名，不只按表名点名**。这张表用的是 CREATE TABLE IF NOT EXISTS，
--   而这份文件在定稿前跑过一版旧稿 —— 在跑过旧稿的库上，本次迁移会**整张表静默跳过**，
--   只查「表在不在」的话迁移与部署门禁双双全绿，而缺列的后果一句都不指向库结构：
--     · 缺 tries        → 清扫那句 `tries=tries+1` 每一轮 500，报告永远不自愈
--     · 缺 idx_report_queue → 取活与清扫全表扫，不报错，只是随行数一天天变慢
--     · stats 建成可空  → 「模型还没跑完」与「连分数都没有」在库里同形，分两层白做
--   所以关键列逐个点名（12 列）、stats 的非空点名、队列索引点名。
--
-- ★ 触发器**按体点名，不只按名字点名**。这一轮两个触发器都是新建的，
--   光查名字不像上一轮那样「从第一天起就是绿的」；但按名字点名仍然验不出
--   **装了半版旧稿**这一种 —— 而它是真会发生的：这份文件在定稿前跑过一版
--   只有 READY/FAILED 两条判据、没有跨表那两条的草稿，那一版的名字与现在一模一样。
--
--   ★ 钉的必须是**只可能出现在那条判据的分支里**的整句片段，不能是列名：
--     submitted_at 与 user_id 这两个词在触发器开头那句
--     `SELECT COUNT(*),MAX(user_id),…,MAX(submitted_at) INTO … FROM exam_attempt` 里就有，
--     于是「保留了这句跨表 SELECT、却把已交卷闸门或三元一致的 IF 删掉」的那种半版旧稿
--     照样四条全命中、判绿。所以一条判据钉它自己那一行 IF：
--       a_sub IS NULL → 已交卷闸门   ／ <=> a_user  → 三元一致
--       = 'READY'     → 正文非空     ／ = 'FAILED'  → 失败必须有理由
--   四段全都要在，缺一条就红。（四段都取 ASCII，是为了与 ops/deploy_course.sh 里同一组
--   判据逐字一致 —— 那边同一句 SQL 要穿过 ssh 与两层引号，少一层字符集的变数。）

DROP PROCEDURE IF EXISTS `sp_assert_landed`;
DELIMITER $$
CREATE PROCEDURE `sp_assert_landed`()
BEGIN
  DECLARE v_n   INT DEFAULT 0;
  DECLARE v_msg VARCHAR(255);

  SELECT COUNT(*) INTO v_n FROM information_schema.TABLES
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='exam_report';
  IF v_n <> 1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '迁移未完整落地：exam_report 表没建出来';
  END IF;

  -- 一场考试一份报告：没有这条唯一键，重试会插出第二行，而页面显示哪一份看查询顺序
  SELECT COUNT(*) INTO v_n FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='exam_report'
      AND INDEX_NAME='uk_report_attempt' AND NON_UNIQUE=0;
  IF v_n <> 1 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '迁移未完整落地：uk_report_attempt 不在，同一场考试能出两份报告';
  END IF;

  -- exam_report 的形状。CREATE TABLE IF NOT EXISTS 在跑过旧稿的库上整张跳过，
  -- 只点表名的话缺列缺索引一律判绿，而缺列的报错一句都不指向库结构（见上）。
  SELECT COUNT(*) INTO v_n FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='exam_report'
      AND COLUMN_NAME IN ('attempt_id','user_id','paper_id','status','model','content',
                          'stats','fail_reason','tries','requested_at','started_at','finished_at');
  IF v_n <> 12 THEN
    SET v_msg = CONCAT('迁移未完整落地：exam_report 的 12 个关键列实到 ', v_n,
                       ' —— 这张表是不是在跑过旧稿的库上被 IF NOT EXISTS 整张跳过了？');
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = v_msg;
  END IF;

  -- stats 可空 =「模型还没跑完」与「连分数都没有」同形，分两层这件事就白做了
  SELECT COUNT(*) INTO v_n FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='exam_report'
      AND COLUMN_NAME='stats' AND IS_NULLABLE='NO';
  IF v_n <> 1 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '迁移未完整落地：exam_report.stats 不是 NOT NULL，事实层可以为空 = 学员在那 19 秒里看一页空白';
  END IF;

  -- 队列索引。缺了它不报任何错，只是取活与清扫全表扫，随行数一天天变慢
  SELECT COUNT(*) INTO v_n FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='exam_report'
      AND INDEX_NAME='idx_report_queue';
  IF v_n < 1 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '迁移未完整落地：idx_report_queue 不在，后台取活与清扫会全表扫（不报错，只是越来越慢）';
  END IF;

  -- question 的 3 个新列
  SELECT COUNT(*) INTO v_n FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='question'
      AND COLUMN_NAME IN ('topic','knowledge_point','difficulty');
  IF v_n <> 3 THEN
    SET v_msg = CONCAT('迁移未完整落地：question 应加 topic/knowledge_point/difficulty 三列，实到 ', v_n);
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = v_msg;
  END IF;

  -- 难度只有 1/2/3。缺了它的话回填脚本写个 4 进来，报告里就多一个没有名字的分组
  SELECT COUNT(*) INTO v_n FROM information_schema.TABLE_CONSTRAINTS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='question'
      AND CONSTRAINT_NAME='ck_question_difficulty' AND CONSTRAINT_TYPE='CHECK';
  IF v_n <> 1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '迁移未完整落地：question.ck_question_difficulty 没建出来';
  END IF;

  -- 2 个触发器
  SELECT COUNT(*) INTO v_n FROM information_schema.TRIGGERS
    WHERE TRIGGER_SCHEMA=DATABASE()
      AND TRIGGER_NAME IN ('trg_exam_report_precheck','trg_exam_report_upd');
  IF v_n <> 2 THEN
    SET v_msg = CONCAT('迁移未完整落地：报告的触发器应有 2 个，实到 ', v_n,
                       ' —— 触发器段是不是没用 root 跑？');
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = v_msg;
  END IF;

  -- 按体点名：四条判据各钉住自己那一行 IF（不是钉列名 —— 列名在开头那句跨表 SELECT 里就有），
  -- 装了半版旧稿、或者留着 SELECT 却删了 IF 分支的稿子，照样红
  SELECT COUNT(*) INTO v_n FROM information_schema.TRIGGERS
    WHERE TRIGGER_SCHEMA=DATABASE()
      AND TRIGGER_NAME IN ('trg_exam_report_precheck','trg_exam_report_upd')
      AND ACTION_STATEMENT LIKE '%a_sub IS NULL%'
      AND ACTION_STATEMENT LIKE '%<=> a_user%'
      AND ACTION_STATEMENT LIKE '%= ''READY''%'
      AND ACTION_STATEMENT LIKE '%= ''FAILED''%';
  IF v_n <> 2 THEN
    SET v_msg = CONCAT('迁移未完整落地：两个报告触发器里只有 ', v_n,
                       ' 个四条判据齐全 —— 是不是装了半版旧稿（只判 READY/FAILED，'
                       '或留着跨表 SELECT 却没有那两行 IF）？');
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = v_msg;
  END IF;

  -- ★ 上面那四条**分不出修复前的更新侧触发器**：那一版里四个片段全都在，
  --   区别只是 ①②③ 没有包在「考试还在」的 IF 里，于是一行孤儿报告就能把
  --   requeueStuck 的整批 UPDATE 顶回来 —— 全平台卡住的报告一个都不自愈，
  --   而日志里只有一句没有指向的「清扫失败」。库上最可能残留的恰恰是那一版
  --   （生产是手工 scp 上去的文件，重跑一份陈旧副本即得）。
  --   `<=> OLD.` 只在修好的那一版里出现（attempt_id 不许被改那一条），单钉它。
  SELECT COUNT(*) INTO v_n FROM information_schema.TRIGGERS
    WHERE TRIGGER_SCHEMA=DATABASE() AND TRIGGER_NAME='trg_exam_report_upd'
      AND ACTION_STATEMENT LIKE '%<=> OLD.%';
  IF v_n <> 1 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '迁移未完整落地：trg_exam_report_upd 是修复前那一版（孤儿报告会毒死整批清扫），重跑本文件的触发器段';
  END IF;
END$$
DELIMITER ;
CALL `sp_assert_landed`();
DROP PROCEDURE `sp_assert_landed`;
