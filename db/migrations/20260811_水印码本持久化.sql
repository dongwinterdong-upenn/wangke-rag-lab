-- =============================================================================
--  2026-08-11 · 水印码本持久化 + 逐讲位偏移
--
--  修两处「不会报错的失效」。这类缺口的共同形状是：系统照常发码字、照常打分，
--  只是谁也抓不到，而你只有在真出了泄漏、真去取证时才发现 —— 那时已经晚了。
--
--  ── 缺口一：码本参数 p 无处存 ────────────────────────────────────────────
--  TardosCode 的偏置数组 p_j 是打分公式的一部分（unitScore 里那个 √((1-p)/p)）。
--  签发码字用的是哪一套 p，取证时就必须用同一套。类注释第 30 行白纸黑字写着
--  「必须与码字一起持久化 —— 丢了就无法取证」，而 wm_codeword 只存了每人的 bits，
--  p 从来没有落脚点。进程一重启，已经发出去的每一份码字就都变成了不可验证的字节。
--
--  ── 缺口二：每一讲都从码字第 0 位开始用 ──────────────────────────────────
--  SlotBudget 算的 9120 槽位是【整门课】的（15.2 小时 ÷ 6 秒），
--  而 M3u8Builder.build 用的是 codeword[slot]，slot 从每一讲的 0 开始。
--  于是六讲全都只投递码字的前 1520 位，剩下 7600 位一次都没被投出去过 ——
--  有效码长被静默砍到 1/6，抗合谋能力从 c=2 掉回 c=1，而两个组件各自的测试都是绿的。
--  （这正是 SlotBudget 自己的类注释里警告过的那个情形，结果栽在自己写的警告上。）
--
--  修法：给每一讲分配一段互不重叠的位区间，整门课的码字才真正被用满。
--  一讲泄漏 = 一个已知偏移上的残缺样本，正好走 thresholdForPartial 那条路。
-- =============================================================================

USE `wangke`;

CREATE TABLE IF NOT EXISTS `wm_scheme` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `course_id`     BIGINT UNSIGNED NOT NULL,
  `algo`          VARCHAR(32)     NOT NULL DEFAULT 'TARDOS_SYM_V1'
                  COMMENT '打分算法版本。换算法必须换 scheme，不能原地改 —— 旧码字要按旧算法验',
  `max_colluders` INT             NOT NULL COMMENT '设计能抗的合谋人数 c',
  `users_planned` INT             NOT NULL COMMENT '设计用户规模 n（码长按它算，超了要新开 scheme）',
  `eps1`          DOUBLE          NOT NULL COMMENT '允许的漏判概率',
  `bit_length`    INT             NOT NULL,
  -- 不量化：存的必须与签发时用的逐位相同。差一个尾数位，打分就和当初不是同一套，
  -- 而这种偏差不会报错，只会让分数整体漂移，漂过阈值就是一次误判。
  `bias`          MEDIUMBLOB      NOT NULL
                  COMMENT 'p_j 序列，big-endian IEEE754 float64 × bit_length',
  `status`        ENUM('ACTIVE','RETIRED') NOT NULL DEFAULT 'ACTIVE',
  `created_at`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `retired_at`    DATETIME(3)     NULL,
  PRIMARY KEY (`id`),
  KEY `idx_course` (`course_id`,`status`),
  -- 长度对不上就是取证会读到错位的 p。错位不会报错，只会让每一位的权重都算错，
  -- 最后指认到一个无辜的人身上 —— 比抓不到更糟。
  CONSTRAINT `ck_wm_scheme_bias` CHECK (`bit_length` > 0 AND LENGTH(`bias`) = `bit_length` * 8),
  CONSTRAINT `ck_wm_scheme_params` CHECK (`max_colluders` > 0 AND `users_planned` > 0
                                          AND `eps1` > 0 AND `eps1` < 1)
) ENGINE=InnoDB COMMENT='水印码本参数（每门课一套现行）';

-- 一门课同时只能有一套 ACTIVE：两套并存时，签发走哪一套、取证按哪一套算，
-- 全凭查询顺序决定，而两边选得不一样的那一天不会有任何提示。
-- 用生成列 + 唯一键实现「至多一行现行」，与 agent_price_tier 同一套手法。
ALTER TABLE `wm_scheme`
  ADD COLUMN `active_flag` TINYINT GENERATED ALWAYS AS
      (IF(`status` = 'ACTIVE', 1, NULL)) STORED,
  ADD UNIQUE KEY `uk_course_active` (`course_id`,`active_flag`);

ALTER TABLE `lesson`
  ADD COLUMN `wm_bit_offset` INT NOT NULL DEFAULT 0
      COMMENT '本讲占用码字的起始位。各讲区间互不重叠，整门课才用得满整条码字'
      AFTER `wm_slot_count`,
  ADD CONSTRAINT `ck_lesson_wm_offset` CHECK (`wm_bit_offset` >= 0 AND `wm_slot_count` >= 0);

ALTER TABLE `wm_codeword`
  ADD COLUMN `scheme_id` BIGINT UNSIGNED NULL
      COMMENT '这份码字是按哪套码本发的。为空 = 历史遗留，不可取证'
      AFTER `course_id`,
  ADD KEY `idx_scheme` (`scheme_id`);
