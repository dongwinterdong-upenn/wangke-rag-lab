-- =============================================================================
--  网课平台 · 种子数据
--
--  ⚠ 价目与佣金数字是「待确认的示例值」，取自需求理解 v2 §7.2 的示例表。
--    上线前必须由需求方拍板（见 v2 §8「需要你方提供的」）。
--    修改方式：插入新的 agent_price_tier / commission_rule 行并把旧行的
--    effective_to 置为切换时点 —— 不要 UPDATE 旧行，历史订单要能查回原价。
-- =============================================================================

USE `wangke`;

-- ---------- 课程 ----------
INSERT INTO `course` (`id`,`title`,`subtitle`,`total_lessons`,`total_seconds`,`status`) VALUES
  (1,'AI FDE 工程师就业实训营','认知与环境 · 前端与 Skill · 后端与多模态 · 知识库与 RAG · 日常 AI 工作流 · Agent 工程与评测',0,0,'DRAFT')
ON DUPLICATE KEY UPDATE `title`=VALUES(`title`);
-- 讲次与时长待视频补齐后录入；status 保持 DRAFT，G1 凭证轮换与 G2 补录完成前不得置 ON。

-- ---------- 套餐 ----------
INSERT INTO `sku` (`id`,`sku_code`,`name`,`buyer_type`,`course_id`,`code_qty`,`status`) VALUES
  (1,'AGENT_1',       '代理单买 · 1 个激活码',  'AGENT',   1, 1, 'ON'),
  (2,'AGENT_10',      '代理批量 · 10 个激活码', 'AGENT',   1,10, 'ON'),
  (3,'STUDENT_DIRECT','学员直购 · 1 份课程',    'STUDENT', 1, 1, 'ON')
ON DUPLICATE KEY UPDATE `name`=VALUES(`name`);

-- ---------- 价目表（agent_level：1=一级 2=二级 0=学员直购） ----------
--
--      层级 \ 档位      批量 10 个        单买 1 个
--      一级             2980 (298/码)     349
--      二级             3490 (349/码)     399
--      学员零售         —                 799
--      差价 = 佣金      51/码             50/码
--
-- 一级在每一档都比二级便宜 50，佣金恒等于差价，全表只有一套口径。
-- 2026-08-11 需求方拍板把一级单买由 399 改为 349（原先与二级同价、差价为 0，
-- 一级在下级的单买上一分钱拿不到）。改动见 migrations/20260811_一级单买定价.sql。
INSERT INTO `agent_price_tier` (`agent_level`,`sku_id`,`total_price_fen`,`effective_from`,`created_by`) VALUES
  (1, 1,  34900, '2026-01-01 00:00:00', 1),   -- 一级单买   349（2026-08-11 拍板）
  (1, 2, 298000, '2026-01-01 00:00:00', 1),   -- 一级批量  2980（折 298/码，录音 02:52）
  (2, 1,  39900, '2026-01-01 00:00:00', 1),   -- 二级单买   399
  (2, 2, 349000, '2026-01-01 00:00:00', 1),   -- 二级批量  3490（折 349/码）
  (0, 3,  79900, '2026-01-01 00:00:00', 1);   -- 学员直购   799（录音 00:33 / 01:32）

-- ---------- 返佣规则（二级代理下单时，其一级上级拿多少） ----------
-- 两档都用 FIXED_PER_CODE：佣金 = 层级差价。不混用比例制，避免两套口径并存。
INSERT INTO `commission_rule`
  (`buyer_level`,`sku_id`,`calc_mode`,`fixed_per_code_fen`,`rate_bp`,`freeze_days`,`effective_from`,`created_by`) VALUES
  (2, 2, 'FIXED_PER_CODE', 5100, NULL, 15, '2026-01-01 00:00:00', 1),  -- 批量：51/码 = 349-298
  (2, 1, 'FIXED_PER_CODE', 5000, NULL, 15, '2026-01-01 00:00:00', 1);  -- 单买：50/码 = 399-349
-- 由此得到一条恒等式，平台净收与「谁来买」无关：
--   二级付 3490 − 佣金 510 = 2980 = 一级批量价
--   二级付  399 − 佣金  50 =  349 = 一级单买价
-- test_schema.py 的 W 段会自动核对这条，改价改错会当场红。

-- ---------- 试卷（2026-09-03 多套试卷） ----------
--
-- 与 db/migrations/20260903_多套试卷.sql 里那六行**逐字同形**，id 也写死 1..6。
-- 两处必须一起改：生产是「老库 + 跑迁移」，而开发/测试库是「schema.sql + seed.sql，
-- 不跑 db/migrations/」（见 ItDb.freshDatabase）—— 这里少了这六行的话，新装的库
-- exam_paper 是空表，学员端一张卷都列不出来、后台题库也无卷可挂，而且一句错都不报。
--
-- 还有一层更阴的：不给下面那行规则挂上 paper_id 的话，它在新口径下是孤儿
-- （effectiveRules 按 paper_id 取，取不到），而 trg_exam_config_paper 为兼容旧行
-- 明文放行它、trg_exam_attempt_ins 又用 <=> 比较 —— 于是针对这行规则的 attempt
-- 可以带着空 paper_id 落库，而 uk_user_paper_attempt 里多个 NULL 不参与唯一性判定，
-- 同一人同一份卷能开出任意多个「第 1 次考试」，一声不响。
--
-- ★ 集成测试若要自己造卷，先 DELETE 掉这六行再插（或者用不冲突的 id 段）：
--   这里占了 id 1..6 与 FDE_* / AIGC_OPEN 这几个 code，硬插同号会撞 uk_paper_code。
INSERT INTO `exam_paper`
  (`id`,`code`,`title`,`subtitle`,`course_id`,`access`,`grants_certificate`,`status`,`sort_no`) VALUES
  (1,'FDE_MAIN',  '结业综合测评',          '覆盖全部章节，通过后可申领课程结业证明', 1,   'ENROLLED',1,'ON', 10),
  (2,'FDE_CH1',   '第一章 · 前端与 skill',  '本章练兵卷，不发结业证明',            1,   'ENROLLED',0,'ON', 20),
  (3,'FDE_CH2',   '第二章 · 后端与多模态',  '本章练兵卷，不发结业证明',            1,   'ENROLLED',0,'ON', 30),
  (4,'FDE_CH3',   '第三章 · AI 原理与 RAG', '本章练兵卷，不发结业证明',            1,   'ENROLLED',0,'ON', 40),
  (5,'FDE_CH4',   '第四章 · 智能体开发',    '本章练兵卷，不发结业证明',            1,   'ENROLLED',0,'ON', 50),
  (6,'AIGC_OPEN', 'AIGC 内容生产能力测试',  '面向全部注册学员开放，不需要购课',      NULL,'OPEN',    0,'ON',100)
ON DUPLICATE KEY UPDATE `title`=VALUES(`title`);

-- ---------- 测评规则（参考驾照理论，G13；一卷一行，规则版本化机制不变） ----------
--
-- 六行与迁移跑完之后生产上的六行一致：结业综合测评 100 题 / 80 分 / 60 分钟
-- （2026-09-03 用户拍板由 90 分 / 45 分钟改成 80 分 / 60 分钟），
-- 四份章节练兵卷 50 题 / 40 分 / 35 分钟 / 5 次 / 冷却 2 小时 / 不设进度门槛
-- （练兵卷看完本章就该能考，80% 的进度门槛是给结业综合测评的），
-- 开放卷 100 题 / 80 分 / 60 分钟且 course_id 为 NULL —— 那是这一列放宽为可空的唯一用处。
--
-- effective_from 必须落在过去：effectiveRules 那句是
--   WHERE paper_id=? AND effective_from<=NOW(3) ORDER BY effective_from DESC, id DESC LIMIT 1
-- 写成将来的话一行都取不到，学员端把卷列出来、点开考报「测评规则不存在」。
INSERT INTO `exam_config`
  (`id`,`course_id`,`paper_id`,`question_count`,`pass_score`,`duration_min`,`shuffle_options`,
   `max_attempts`,`retry_cooldown_hours`,`require_progress_pct`,`effective_from`) VALUES
  (1, 1,    1, 100, 80, 60, 1, 3, 24, 80, '2026-01-01 00:00:00'),
  (2, 1,    2,  50, 40, 35, 1, 5,  2,  0, '2026-01-01 00:00:00'),
  (3, 1,    3,  50, 40, 35, 1, 5,  2,  0, '2026-01-01 00:00:00'),
  (4, 1,    4,  50, 40, 35, 1, 5,  2,  0, '2026-01-01 00:00:00'),
  (5, 1,    5,  50, 40, 35, 1, 5,  2,  0, '2026-01-01 00:00:00'),
  (6, NULL, 6, 100, 80, 60, 1, 3, 24,  0, '2026-01-01 00:00:00');

-- ---------- 测评报告：这里【故意】一行都不造 ----------
--
-- exam_report 是交卷之后由后台生成的派生物，不是环境的一部分。种子里放一份
-- 「凭空存在的报告」会把它变成一个假绿：集成测试里「交卷后报告出得来」那条断言，
-- 会在一份根本不是这一轮生成出来的报告上通过。
--
-- 而且硬造也造不动：exam_report 的闸门要求那一场 attempt 已交卷、且报告的
-- user_id/paper_id 与那场考试逐列一致（trg_exam_report_precheck），
-- 想在种子里放报告就得连 exam_attempt 一起编 —— 编出来的那场考试没有一行答题明细，
-- stats 里写什么都是编的。要报告数据的用例自己走「开考 → 交卷 → enqueue」这条正路。


-- ---------- 平台超管（初始密码须首次登录强制修改） ----------
INSERT INTO `sys_user` (`id`,`phone`,`password_hash`,`nickname`,`role`,`status`,`must_change_pwd`) VALUES
  (1,'00000000000',NULL,'平台超管','PLATFORM_ADMIN','ACTIVE',1)
ON DUPLICATE KEY UPDATE `nickname`=VALUES(`nickname`);
-- password_hash 留空：由部署脚本生成随机初始密码写入，不写进版本库。
