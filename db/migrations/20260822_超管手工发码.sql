-- 超管人工给代理发放激活码（赠送 / 补发）。
--
-- ★ 这份迁移里的【触发器段必须用 root 连接执行】，不能用应用账号：
--   COURSE 的 MySQL 开着 binlog 而 log_bin_trust_function_creators=0，
--   应用账号没有 SUPER/TRIGGER 权限，建触发器会报
--   「ERROR 1419: You do not have the SUPER privilege and binary logging is enabled」。
--   更坏的是这个错**只打在触发器那几行上** —— 列、唯一键、CHECK 全都加好了，
--   闸门一个没有，而后续插入全部通过。20260821_讲次内容分节.sql 记着这次事故。
--       ssh COURSE 'mysql wangke < 这个文件'
--   跑完不要只看「没报错」：要在库上真查一次
--       SELECT TRIGGER_NAME FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE();
--       SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS
--        WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='biz_order';
--   确认 trg_commission_precheck / trg_payment_precheck / ck_order_gift_unpaid /
--   ck_order_grant_by 四样真的在。
--
--
-- ## 为什么手工发的码仍然挂在一笔真订单上
--
-- 直觉上「手工发码」= 让 activation_code.order_id 可空，或者另起一张发放单表。
-- 两条都走不通，而且不是因为麻烦：
--
--   ① **撤不回来。** order_id 今天是 NOT NULL。只要落地一张不挂单的码，这一列就再也
--      收不回 NOT NULL，撤功能只剩「删掉代理手上的货」或「永久保留一批无来源凭据的码」
--      两条路 —— 而「来源必须带得出凭据」正是 ck_enrollment_source 那条不变量的原话。
--   ② **下游全是按订单圈的。** RefundService 五处 WHERE order_id=?、CodeService.listByOrder、
--      AgentCodeExportService 的 LEFT JOIN biz_order、以及 ActivationService 里那个
--      num(c.get("order_id"))（order_id 为 NULL 必 NPE —— 而它不在发码那一刻炸，
--      是在几天后学员激活时炸成 500）。放开 order_id 要逐处补，漏掉的那一处
--      表现全是「数字变小、页面不报错」。
--   ③ **quota 那条闸要改。** trg_activation_code_quota 现在是一条按订单的 COUNT，
--      改成两支形状不同的判据之后，「为了让新路插得进去把订单侧那条闸改松了」
--      这件事在 Java 侧会全绿 —— 触发器**体**的改动不在 SchemaDriftTest 射程内。
--
-- 所以走「合成订单」：超管发码时后端代建一笔 origin='ADMIN_GIFT' 的订单，
-- 码照常挂在它上面。activation_code 这张表、trg_activation_code_quota、
-- trg_activation_code_state 三样**一个字节都不动**。
--
--
-- ## 「白送不可能变成收入」为什么必须是库层的
--
-- 白送单的账面形态是：amount_fen = 目录价（>0，这是 ck_order_amount 逼的），
-- paid_amount_fen = 0，paid_at 恒 NULL，不写 payment_record，不产生佣金。
--
-- 这三条如果只写在 Java 里，失效的方式全是**不可逆且无症状**的：
--
--   · 佣金一旦误产生，冻结 15 天后由 CommissionSettleService 自动进 agent_balance，
--     此后与真佣金不可分辨；而白送单 paid_amount_fen=0 连 refund_record 都插不进去
--     （trg_refund_precheck 要求 paid > 0），撤回路径为零。
--   · 「先建白送单、事后偷偷标已付把它洗成收入」这条路，CHECK 在 UPDATE 时对整行求值，
--     所以两侧一起堵。
--   · 「confirm-transfer 打在白送单上」是一条真通路（详见下面 trg_payment_precheck 的注释），
--     它的表现是一条 SUCCESS 的假流水落库、总览四项纹丝不动、运营看到「已确认到账，
--     本单已出 0 个激活码」—— 只有拿银行流水逐笔对时才发现。
--
-- 所以升级成物理约束：两条 CHECK + 两个 BEFORE INSERT 触发器。
--
--
-- ## 可重复执行的写法为什么不照抄上一份迁移
--
-- 上一份用的是 sp_add_col_if_missing('表','列','列定义')，DDL 在存储过程体里 CONCAT 出来。
-- 那样写 **SchemaDriftTest 的 ADDED 正则一个都抓不到** —— 实测 20260821_讲次重切四十分钟.sql
-- 用同一个过程加的 4 列，0 命中。后果是最坏的一种：新装的库缺 ck_order_gift_unpaid，
-- 「白送不可能变成收入」这条物理保证降级成一句注释，而 SchemaDriftTest 全绿、
-- Java 侧全绿、迁移也跑成功了。
--
-- 这里换成「存在性判断在过程里、完整 DDL 字面量在调用点」：ADD COLUMN `origin` 这串字
-- 真的出现在文件里，正则抓得到，而且照样可重复执行。三个过程在文件末尾删掉。

-- ================================================================ 权限探针
--
-- ★ 这一段必须排在**任何 DDL 之前**，它的全部作用是「用错账号时先炸，别先拆东西」。
--
-- 不加它会怎样（已在演练库上实测复现）：用应用账号 `wangke` 跑这份文件，
-- 下面触发器段的 `DROP TRIGGER IF EXISTS trg_order_insert` **会成功**（DROP 只要
-- TRIGGER 权限，而应用账号是 GRANT ALL ON wangke.*），紧跟的 `CREATE TRIGGER`
-- 因为 log_bin=ON 且 log_bin_trust_function_creators=OFF 而报 ERROR 1419
-- （缺 SUPER / SET_USER_ID）。**DDL 不回滚，删掉就是删掉。**
--
-- 于是收场是这样：四列在、唯一键在、两条 CHECK 也在，看起来只是「触发器那段没成」；
-- 实际上 biz_order 上一个 BEFORE INSERT 触发器都不剩 —— 已暂停/已终止的代理可以继续下单、
-- expire_at 在过去的订单可以建出来，两条**本来就在生产上跑着**的闸凭空消失，
-- 零报错、零日志、界面一切正常。而且这份文件自称可重复执行、鼓励重跑，
-- 已经装好之后再用错账号重跑一遍，就是把三道闸一起拆掉。
--
-- 文件末尾那段 sp_assert_landed 救不了这个：`mysql db < file` 首错即停，
-- 根本走不到那一行（只有加 --force 才会走到，那时东西已经拆了）。
--
-- 探针建在 `sku` 上：运行期没有任何代码往 sku 插行，而 CREATE TRIGGER 要的权限与表无关。
-- 触发器体是空的，即便在存在的那一瞬间有人插也什么都不做。
--
--     正确跑法： ssh COURSE 'mysql wangke < 这个文件'      （默认就是 root@localhost）
--     错误跑法： . /etc/wangke.env; mysql -u"$WANGKE_DB_USER" -p… wangke < 这个文件

-- ================================================================ 锁等待
--
-- 生产的 lock_wait_timeout 是默认值 31536000 秒（一整年）。ADD CONSTRAINT CHECK 在
-- 8.0.46 上只能 ALGORITHM=COPY，要拿 biz_order 的排他元数据锁；只要那一刻有任何一个
-- 长事务/未提交事务握着这张表，ALTER 就会**等上一年**，而 MySQL 的元数据锁是排队的 ——
-- 排在它后面的每一条 biz_order 查询（下单、订单页、总览）也一起无限期挂住。
-- 表现是整站的订单相关功能同时卡死，且不会自愈、日志里一行错都没有。
--
-- 10 秒拿不到就认输：失败是响的、可重试的，而挂死是哑的。
SET SESSION lock_wait_timeout = 10;


DROP TRIGGER IF EXISTS `trg_privilege_probe`;

DELIMITER $$
CREATE TRIGGER `trg_privilege_probe` BEFORE INSERT ON `sku` FOR EACH ROW
BEGIN
END$$
DELIMITER ;

DROP TRIGGER `trg_privilege_probe`;


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

DROP PROCEDURE IF EXISTS `sp_run_if_check_missing`$$
DROP PROCEDURE IF EXISTS `sp_replace_check`$$
-- CHECK 走【总是重建】，不是「不存在才建」。
--
-- 「按名字判存在」判不出内容：约束在、但定义是上一版的，这个过程会安静跳过并报成功。
-- 这条本轮真的踩到了 —— ck_order_grant_by 第一版只管「白送单必须签名」一个方向，
-- 反向（自助单不许带发放签名）漏了；改完子句后，凡是已经跑过一次迁移的库都不会更新。
-- CHECK 重建很便宜（biz_order 量级小，ADD CONSTRAINT 会全表复验一遍），
-- 而列与唯一键不这么做：那两样「存在但定义不同」意味着改类型/改索引，
-- 在有数据的表上不该由迁移自动决定。
CREATE PROCEDURE `sp_replace_check`(IN p_table VARCHAR(64), IN p_name VARCHAR(64), IN p_ddl TEXT)
BEGIN
  -- ★ 顺序是「先拿临时名验一遍数据，确认加得上，再拆掉旧的换正名」。
  --
  -- 直接 DROP 再 ADD 的话，只要存量数据里有一行顶回来（ADD CONSTRAINT 会全表复验），
  -- ADD 失败、脚本中止，而旧约束**已经被删掉了** —— 收场是这张表上那条不变量
  -- 永久消失，且看起来只是「迁移报了个错」。这是把「改约束」做成了「删约束」。
  --
  -- 探针用同名加后缀 _probe：加得上就说明数据满足新子句，接下来那次 ADD 不可能因数据失败。
  SET @probe = REPLACE(p_ddl, CONCAT('`', p_name, '`'), CONCAT('`', p_name, '_probe`'));
  PREPARE pr FROM @probe; EXECUTE pr; DEALLOCATE PREPARE pr;
  SET @dropprobe = CONCAT('ALTER TABLE `', p_table, '` DROP CHECK `', p_name, '_probe`');
  PREPARE dp FROM @dropprobe; EXECUTE dp; DEALLOCATE PREPARE dp;

  IF EXISTS (SELECT 1 FROM information_schema.TABLE_CONSTRAINTS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table
                AND CONSTRAINT_NAME = p_name) THEN
    SET @drop = CONCAT('ALTER TABLE `', p_table, '` DROP CHECK `', p_name, '`');
    PREPARE d FROM @drop; EXECUTE d; DEALLOCATE PREPARE d;
  END IF;
  SET @ddl = p_ddl; PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
END$$

DELIMITER ;


-- ---------------------------------------------------------------- 列
--
-- origin 只有两档。ADMIN_OFFLINE（线下已收款）**故意不加** —— 那件事今天已经有一条
-- 完整的路（代理自己下单 → 超管 confirm-transfer，且金额与应付逐笔相等），
-- 缺的只是「替代理下单」这一步，与「发码」是两件事。
-- ENUM 追加值在末尾是安全的，插在中间才危险（存的是下标），所以将来要加也来得及。

CALL `sp_run_if_col_missing`('biz_order', 'origin',
  'ALTER TABLE `biz_order` ADD COLUMN `origin` ENUM(''SELF_SERVE'',''ADMIN_GIFT'') NOT NULL DEFAULT ''SELF_SERVE'' COMMENT ''这张单是怎么来的：自助下单 / 平台手工发放'' AFTER `client_ip`');

CALL `sp_run_if_col_missing`('biz_order', 'grant_by',
  'ALTER TABLE `biz_order` ADD COLUMN `grant_by` BIGINT UNSIGNED NULL COMMENT ''手工发放的操作人（sys_user.id）'' AFTER `origin`');

CALL `sp_run_if_col_missing`('biz_order', 'grant_reason',
  'ALTER TABLE `biz_order` ADD COLUMN `grant_reason` VARCHAR(255) NULL COMMENT ''手工发放的原因，必填，事后复盘只有这一句'' AFTER `grant_by`');

-- 幂等键。后台按钮会被双击，而「双击 = 两批码发出去」在界面上两次都显示成功，
-- 事后没有任何东西能判定它们是同一次意图。前端在对话框【打开时】生成一次 UUID，
-- 关框才换 —— 写成每次点击现生成的话，这道防线等于不存在。
CALL `sp_run_if_col_missing`('biz_order', 'grant_key',
  'ALTER TABLE `biz_order` ADD COLUMN `grant_key` VARCHAR(64) NULL COMMENT ''一次发放意图的幂等键；自助单为 NULL'' AFTER `grant_reason`');


-- ---------------------------------------------------------------- 唯一键
--
-- 多个 NULL 不参与唯一性判定，所以自助单（grant_key IS NULL）不受任何影响 ——
-- 这一条必须有专门的正例钉住（test_schema.py AF11）：把它做成「自助单只能有一张」
-- 的话，全站下单从第二笔起全挂，很吵，但没有那条用例会在上线那一刻才发现。

CALL `sp_run_if_index_missing`('biz_order', 'uk_grant_key',
  'ALTER TABLE `biz_order` ADD UNIQUE KEY `uk_grant_key` (`grant_key`)');


-- ---------------------------------------------------------------- CHECK
--
-- ck_order_gift_unpaid：白送单不许有实付金额、不许有到账时间。
--   amount_fen 写的是目录价而不是 0 —— ck_order_amount 第一句就是 amount_fen > 0，
--   0 元单在库层造不出来；写 1 分则让「这批白送值多少钱」这个口径永久失真，
--   写目录价则这张单同时是一份可读的「平台送出去多少钱的货」的账。
--   CHECK 在 UPDATE 时对整行求值，所以「先建白送单、事后标已付洗成收入」也堵住了。
--
-- ck_order_grant_by：发放签名与 origin 必须一致，【两个方向都要】。
--   正向：手工单必须记下操作人、原因、幂等键，一样都不能少 —— 事后有人问「这批码是谁
--   发的、为什么发」，答案得在订单行上，不能只在审计里（审计是业务事务之外写的，
--   写失败时订单还在）。
--   反向：自助单不许带发放签名。只写正向的话，origin='SELF_SERVE' 会让整条约束短路，
--   一张真实付款的订单可以带着完整的「平台发放」签名落库 —— 台账上的来源是假的，
--   而且它会在 uk_grant_key 上抢注掉将来某次手工发码的幂等键（表现是发码 500）。
--   存量行全部是 origin='SELF_SERVE' 且三列为 NULL，收紧后零冲突。

CALL `sp_replace_check`('biz_order', 'ck_order_gift_unpaid',
  'ALTER TABLE `biz_order` ADD CONSTRAINT `ck_order_gift_unpaid` CHECK (`origin` <> ''ADMIN_GIFT'' OR (`paid_amount_fen` = 0 AND `paid_at` IS NULL))');

CALL `sp_replace_check`('biz_order', 'ck_order_grant_by',
  'ALTER TABLE `biz_order` ADD CONSTRAINT `ck_order_grant_by` CHECK ((`origin` = ''SELF_SERVE'' AND `grant_by` IS NULL AND `grant_reason` IS NULL AND `grant_key` IS NULL) OR (`origin` <> ''SELF_SERVE'' AND `grant_by` IS NOT NULL AND `grant_reason` IS NOT NULL AND `grant_key` IS NOT NULL))');


DROP PROCEDURE IF EXISTS `sp_run_if_col_missing`;
DROP PROCEDURE IF EXISTS `sp_run_if_index_missing`;
DROP PROCEDURE IF EXISTS `sp_replace_check`;


-- ---------------------------------------------------------------- 触发器
--
-- ★ 从这里往下必须用 root 连接执行（见文件头）。

DELIMITER $$

-- trg_order_insert 是【改体】，不是新建。原来两条判据一字不动，只在后面追加两条，
-- 且两条都以 NEW.origin <> 'SELF_SERVE' 为前提 —— 生产路径与全部既有测试夹具
-- （不写 origin → 走列默认 SELF_SERVE）零连带。
--
-- 这里**刻意不加**「SELF_SERVE 必须以 status='CREATED' 建单」那条收紧。它与本功能无关，
-- 而射程是 9 处 Java 夹具 + db/test_schema.py 里那条 status='FULFILLED' 的直插 ——
-- 而 test_schema.py 的 mysql() 返回值没人看、客户端遇错即停，那条被拒之后
-- 后面几条用例的拒绝理由会集体张冠李戴。要收紧就单列一轮，那一轮的射程
-- 就是「把 9 处夹具改走正路」。
DROP TRIGGER IF EXISTS `trg_order_insert`$$
CREATE TRIGGER `trg_order_insert`
BEFORE INSERT ON `biz_order` FOR EACH ROW
BEGIN
  IF NEW.expire_at <= NOW(3) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '订单过期时间必须晚于当前时间';
  END IF;
  -- 已暂停 / 已终止的代理商不能再进货
  IF NEW.buyer_agent_id IS NOT NULL
     AND NOT EXISTS (SELECT 1 FROM `agent_profile`
                     WHERE id = NEW.buyer_agent_id AND status = 'ACTIVE') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '代理商不在有效状态，不能下单';
  END IF;

  -- 白送单必须一步到位落在「已发码」。允许它以 CREATED 建出来的话，界面上它是一张
  -- 待支付订单 —— 代理会看见一个「去付款」按钮，付完就成了一笔真实收入，
  -- 而它本来是白送的。
  IF NEW.origin = 'ADMIN_GIFT' AND NEW.status <> 'FULFILLED' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '平台手工发放的订单必须直接落到已发码状态';
  END IF;

  -- 手工单没有支付窗口这回事。给它留一个长窗口的话，它会混进「待支付订单」的
  -- 各种扫描与提醒里，而它永远不会被支付。
  IF NEW.origin <> 'SELF_SERVE'
     AND NEW.expire_at > DATE_ADD(NOW(3), INTERVAL 10 MINUTE) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '手工发放的订单不需要支付窗口，expire_at 不得超过 10 分钟';
  END IF;
END$$


-- 白送的订单不产生佣金 —— 库层保证，不是代码纪律。
--
-- 「settleCommission 只有 FulfillService 一个调用点、新路径不经过它」是今天的代码事实，
-- 下一个人加一行就没了。而这条错一旦发生是**不可逆**的：那笔佣金冻结 15 天后由
-- CommissionSettleService 自动进 agent_balance，此后与真佣金不可分辨；
-- 而白送单 paid_amount_fen=0 连 refund_record 都插不进去（trg_refund_precheck 要求 paid > 0），
-- 撤回路径为零。
DROP TRIGGER IF EXISTS `trg_commission_precheck`$$
CREATE TRIGGER `trg_commission_precheck`
BEFORE INSERT ON `commission_record` FOR EACH ROW
BEGIN
  IF EXISTS (SELECT 1 FROM `biz_order`
              WHERE id = NEW.order_id AND `origin` = 'ADMIN_GIFT') THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '平台白送的订单不产生佣金：没有收到的钱不能返出去';
  END IF;
END$$


-- 白送的订单不能有支付流水。
--
-- ★ 这一条堵的是一条**真通路**，不是防御性代码。真跑一遍是这样：
--   AdminOrderController.confirmTransfer 打在一张白送单上 →
--   FulfillService.doConfirm 先插 payment_record（成功，一笔假流水就此落库，
--   还吃掉一个 uk_provider_txn 名额）→ 再 UPDATE ... WHERE id=? AND status='CREATED'
--   影响 0 行（白送单是 FULFILLED），所以 ck_order_gift_unpaid **根本没被求值** →
--   issueCodes 算出 need=0 → UPDATE status='FULFILLED' 在 OLD='FULFILLED' 上无规则命中 →
--   最后 settleCommission，只有「买家是二级且有上级且规则表配了」时才会插 commission_record
--   并被上面那条拦下回滚；买家是一级、或没有上级、或规则没配，**整个事务提交**。
--
--   结果：一张 origin='ADMIN_GIFT'、paid_at 仍为 NULL 的单上挂着一条 SUCCESS 的假流水，
--   总览四项纹丝不动（它们按 paid_at 圈窗口），运营看到的是
--   「已确认到账，本单已出 0 个激活码」。只有拿银行流水逐笔对时才发现。
--
-- 应用层在 confirmTransfer 里也挡了一道（先看 origin 再入账），两道互补：
-- 应用层那道给人话，这道保证绕不过去。
DROP TRIGGER IF EXISTS `trg_payment_precheck`$$
CREATE TRIGGER `trg_payment_precheck`
BEFORE INSERT ON `payment_record` FOR EACH ROW
BEGIN
  IF EXISTS (SELECT 1 FROM `biz_order`
              WHERE id = NEW.order_id AND `origin` = 'ADMIN_GIFT') THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '平台白送的订单不能有支付流水：这张单从来没有收到过钱';
  END IF;
END$$

DELIMITER ;


-- ---------------------------------------------------------------- 收尾自检
--
-- 迁移「跑完没报错」不等于「都落地了」：上面每个 CALL 都是条件执行，
-- 触发器段又是另一条连接、另一套权限（binlog 下应用账号建触发器是 ERROR 1419，
-- 而错只打在触发器那几行，前面的列和 CHECK 照样成功）。所以这里自己点一遍名，
-- 少一样就当场 SIGNAL —— 让迁移失败，而不是让上线后的第一张白送单去发现。

SELECT
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='biz_order'
      AND COLUMN_NAME IN ('origin','grant_by','grant_reason','grant_key'))
+ (SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='biz_order' AND INDEX_NAME='uk_grant_key')
+ (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='biz_order'
      AND CONSTRAINT_NAME IN ('ck_order_gift_unpaid','ck_order_grant_by'))
+ (SELECT COUNT(*) FROM information_schema.TRIGGERS
    WHERE TRIGGER_SCHEMA=DATABASE()
      AND TRIGGER_NAME IN ('trg_order_insert','trg_commission_precheck','trg_payment_precheck'))
INTO @landed;

-- 4 列 + 1 唯一键 + 2 CHECK + 3 触发器 = 10
DROP PROCEDURE IF EXISTS `sp_assert_landed`;
DELIMITER $$
CREATE PROCEDURE `sp_assert_landed`()
BEGIN
  IF @landed <> 10 THEN
    SET @msg = CONCAT('迁移未完整落地：应有 10 样，实到 ', @landed,
                      ' —— 触发器段是不是没用 root 跑？');
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = @msg;
  END IF;
END$$
DELIMITER ;
CALL `sp_assert_landed`();
DROP PROCEDURE `sp_assert_landed`;
