#!/usr/bin/env python3
"""
网课平台 schema 机制测试台。

对 schema.sql 里每一条「我声称它挡得住」的约束做正反用例：
  - 正例必须成功（约束不能误伤正常业务）
  - 反例必须失败（约束真的生效，而不是写了个装饰）

在独立的 wangke_test 库上跑，每次全量重建，不碰 wangke。
用法：  python3 test_schema.py
"""

import os
import subprocess, sys, re, os, pathlib

# 库名可覆盖。写死的后果不是"不方便"：这个脚本开头会 DROP DATABASE，
# 两个进程并行跑就会互相把对方的库端掉，表现是一片
# 「Duplicate entry」/「Table doesn't exist」/「代理商档案只能挂在 AGENT 角色的账号上」——
# 全是指不到根因的红，非常容易被读成真回归。（2026-09-01 四路并行时踩了两次。）
DB = os.environ.get("WANGKE_TEST_DB", "wangke_test")
HERE = pathlib.Path(__file__).parent
SCHEMA = HERE / "schema.sql"

PASS, FAIL = [], []
# 期望被拒的用例，记下它实际是被哪条约束拒的。
# 一条「被拒了」不等于「被该被的那条拒了」—— Y3 就是被无关的前置检查拦下、
# 白通过了一轮。WANGKE_SHOW_REASONS=1 把这张对照表打出来逐条核。
REASONS = []


def mysql(sql, db=DB):
    """执行 SQL，返回 (ok, output)。"""
    p = subprocess.run(
        ["sudo", "mysql", "-D", db, "--default-character-set=utf8mb4"],
        input=sql, capture_output=True, text=True,
    )
    return p.returncode == 0, (p.stdout + p.stderr).strip()


def raw(sql):
    p = subprocess.run(["sudo", "mysql", "--default-character-set=utf8mb4"],
                       input=sql, capture_output=True, text=True)
    return p.returncode == 0, (p.stdout + p.stderr).strip()


def case(name, sql, expect, err_contains=None):
    """expect: 'ok' 期望成功 | 'fail' 期望被拒"""
    ok, out = mysql(sql)
    if expect == "ok":
        good = ok
        detail = "" if ok else out.splitlines()[0][:120]
    else:
        good = not ok
        if good and err_contains:
            good = err_contains in out
        detail = "未被拒绝（约束形同虚设）" if ok else out.splitlines()[0][:120]
        if not ok:
            reason = re.sub(r"^ERROR \d+ \(\w+\) at line \d+: ", "", out.splitlines()[0])
            REASONS.append((name, err_contains or "—未锁定—", reason[:90]))
    (PASS if good else FAIL).append((name, detail))
    mark = "\033[32m PASS\033[0m" if good else "\033[31m FAIL\033[0m"
    print(f"{mark}  {name}")
    if not good and detail:
        print(f"        └─ {detail}")
    return good


def query(sql):
    ok, out = mysql(sql)
    return out


def section(title):
    print(f"\n\033[1m{title}\033[0m")
    print("─" * 66)


# =========================================================================
# 建库
# =========================================================================
print("重建测试库 …")
raw(f"DROP DATABASE IF EXISTS {DB};")
ddl = SCHEMA.read_text(encoding="utf-8")
ddl = ddl.replace("`wangke`", f"`{DB}`").replace("USE `wangke`", f"USE `{DB}`")
p = subprocess.run(["sudo", "mysql", "--default-character-set=utf8mb4"],
                   input=ddl, capture_output=True, text=True)
if p.returncode != 0:
    print("建库失败：", p.stderr[:500]); sys.exit(1)

n = query(f"SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='{DB}';")
print(f"建库完成，{n.splitlines()[-1]} 张表\n")

# 公共前置数据
mysql("""
INSERT INTO sys_user (id,phone,role) VALUES
  (1,'13000000001','PLATFORM_ADMIN'),
  (2,'13000000002','AGENT'),
  (3,'13000000003','AGENT'),
  (4,'13000000004','STUDENT'),
  (5,'13000000005','STUDENT'),
  (6,'13000000006','STUDENT'),
  -- 90/95~99 供 A、D 段的反例使用。它们必须是真实存在的 AGENT 账号，
  -- 否则「代理档案必须挂 AGENT 角色」会先开火，用例就测不到它本来要测的那条约束了。
  (90,'13000000090','AGENT'),(95,'13000000095','AGENT'),(96,'13000000096','AGENT'),
  (97,'13000000097','AGENT'),(98,'13000000098','AGENT'),(99,'13000000099','AGENT');
INSERT INTO course (id,title,status) VALUES (1,'测试课程','ON');
INSERT INTO sku (id,sku_code,name,buyer_type,course_id,code_qty) VALUES
  (1,'AGENT_1','单买','AGENT',1,1),
  (2,'AGENT_10','批量','AGENT',1,10),
  (3,'STUDENT_DIRECT','直购','STUDENT',1,1);
-- 章 = 一场录屏 = 一个片库目录；章下各讲共用这批分片，各拿一段片号区间。
-- store_segment_count 取 600 ⇒ 槽位容量 600/6 = 100 位，两章的码位是 [0,100) 与 [100,200)。
INSERT INTO chapter (id,course_id,title,hls_key,store_segment_count,wm_bit_base,sort_no) VALUES
  (1,1,'第一章','oss/l1',600,0,1),
  (2,1,'第二章','oss/l2',600,100,2);
INSERT INTO lesson (id,chapter_id,course_id,title,sort_no,duration_sec,hls_key)
  VALUES (1,1,1,'第一讲',1,3600,'oss/l1'),(2,2,1,'第二讲',2,3600,'oss/l2');
""")


# =========================================================================
section("A · 分销层级硬锁 2 级（§7.6 合规红线）")
# =========================================================================
case("A1 一级代理无上级 —— 应成功",
     "INSERT INTO agent_profile (user_id,agent_code,level,status) VALUES (2,'A1',1,'ACTIVE');", "ok")

a1 = query("SELECT id FROM agent_profile WHERE agent_code='A1';").splitlines()[-1]

case("A2 二级挂在一级下 —— 应成功",
     f"INSERT INTO agent_profile (user_id,agent_code,level,parent_agent_id,status) VALUES (3,'B2',2,{a1},'ACTIVE');", "ok")

b2 = query("SELECT id FROM agent_profile WHERE agent_code='B2';").splitlines()[-1]
# 给一级代理铺一笔佣金余额，供后续提现相关用例使用
mysql(f"INSERT INTO agent_balance (agent_id,commission_balance_fen) VALUES ({a1},1000000);")

case("A3 二级挂在二级下（三级链）—— 应被拒",
     f"INSERT INTO agent_profile (user_id,agent_code,level,parent_agent_id,status) VALUES (99,'C3',2,{b2},'ACTIVE');",
     "fail", "硬锁")
case("A4 一级却带上级 —— 应被拒",
     f"INSERT INTO agent_profile (user_id,agent_code,level,parent_agent_id,status) VALUES (98,'D1',1,{a1},'ACTIVE');",
     "fail", "ck_agent_parent")
case("A5 二级却无上级 —— 应被拒",
     "INSERT INTO agent_profile (user_id,agent_code,level,status) VALUES (97,'E2',2,'ACTIVE');",
     "fail", "ck_agent_parent")
case("A6 level 取 3 —— 应被拒",
     "INSERT INTO agent_profile (user_id,agent_code,level,parent_agent_id,status) VALUES (96,'F3',3,1,'ACTIVE');",
     "fail", "ck_agent_level")
mysql("INSERT INTO sys_user (id,phone,role) VALUES (89,'13000000089','AGENT');")
mysql("INSERT INTO agent_profile (user_id,agent_code,level,status) VALUES (89,'H1',1,'ACTIVE');")
h1 = query("SELECT id FROM agent_profile WHERE agent_code='H1';").splitlines()[-1]
case("A7 已有下级的一级降级、改挂到另一个一级下 —— 应被拒（会形成三级链）",
     f"UPDATE agent_profile SET level=2,parent_agent_id={h1} WHERE id={a1};", "fail", "已有下级")
case("A8 上级指向不存在的代理 —— 应被拒（悬空上级）",
     "INSERT INTO agent_profile (user_id,agent_code,level,parent_agent_id,status) VALUES (95,'G2',2,999999,'ACTIVE');",
     "fail", "硬锁")
case("A9 UPDATE 改挂到二级下 —— 应被拒",
     f"UPDATE agent_profile SET parent_agent_id={b2} WHERE id={b2};", "fail", "硬锁")
case("A10 代理挂在自己名下 —— 应被拒",
     f"UPDATE agent_profile SET parent_agent_id={a1} WHERE id={a1};", "fail", "自己名下")


# =========================================================================
section("B · 幂等键（重复入账的物理防线）")
# =========================================================================
mysql(f"""INSERT INTO biz_order
 (id,order_no,buyer_user_id,buyer_type,buyer_agent_id,buyer_agent_level,sku_id,
  sku_code_snapshot,code_qty_snapshot,amount_fen,expire_at)
 VALUES (1,'ORD001',3,'AGENT',{b2},2,2,'AGENT_10',10,349000,'2030-01-01');""")

case("B1 支付流水首次写入 —— 应成功",
     "INSERT INTO payment_record (order_id,order_no,provider,provider_txn_id,amount_fen,status,paid_at)"
     " VALUES (1,'ORD001','WECHAT','WX_TXN_001',349000,'SUCCESS',NOW(3));", "ok")
case("B2 同一笔微信交易重复入账 —— 应被拒",
     "INSERT INTO payment_record (order_id,order_no,provider,provider_txn_id,amount_fen,status,paid_at)"
     " VALUES (1,'ORD001','WECHAT','WX_TXN_001',349000,'SUCCESS',NOW(3));", "fail", "uk_provider_txn")
case("B3 资金台账首次写入 —— 应成功",
     "INSERT INTO account_ledger (owner_type,owner_id,biz_type,ref_no,direction,amount_fen,balance_before,balance_after)"
     " VALUES ('PLATFORM',1,'ORDER_PAY','ORD001','IN',349000,0,349000);", "ok")
case("B4 同 (biz_type, ref_no) 重复写台账 —— 应被拒",
     "INSERT INTO account_ledger (owner_type,owner_id,biz_type,ref_no,direction,amount_fen,balance_before,balance_after)"
     " VALUES ('PLATFORM',1,'ORDER_PAY','ORD001','IN',349000,349000,698000);", "fail", "uk_biz_ref")
case("B5 佣金记录首次写入 —— 应成功",
     f"INSERT INTO commission_record (beneficiary_agent_id,source_agent_id,order_id,order_no,rule_id,"
     f"calc_mode_snapshot,calc_basis_fen,amount_fen,freeze_until)"
     f" VALUES ({a1},{b2},1,'ORD001',1,'FIXED_PER_CODE',349000,51000,'2030-01-01');", "ok")
case("B6 同一订单对同一受益人重复结佣 —— 应被拒",
     f"INSERT INTO commission_record (beneficiary_agent_id,source_agent_id,order_id,order_no,rule_id,"
     f"calc_mode_snapshot,calc_basis_fen,amount_fen,freeze_until)"
     f" VALUES ({a1},{b2},1,'ORD001',1,'FIXED_PER_CODE',349000,51000,'2030-01-01');", "fail", "uk_order_beneficiary")
case("B7 佣金台账重复 —— 应被拒",
     f"INSERT INTO commission_ledger (agent_id,biz_type,ref_no,direction,amount_fen,balance_before,balance_after)"
     f" VALUES ({a1},'SETTLE','ORD001','IN',51000,0,51000);"
     f"INSERT INTO commission_ledger (agent_id,biz_type,ref_no,direction,amount_fen,balance_before,balance_after)"
     f" VALUES ({a1},'SETTLE','ORD001','IN',51000,51000,102000);", "fail", "uk_biz_ref")


# =========================================================================
section("C · 金额与 CHECK 约束")
# =========================================================================
case("C1 提现 99.99 元（低于门槛）—— 应被拒",
     f"INSERT INTO withdraw_order (withdraw_no,agent_id,amount_fen,channel,payee_name,payee_account)"
     f" VALUES ('W001',{a1},9999,'BANK_TRANSFER','张三','6222');", "fail", "ck_withdraw_amount")
case("C2 提现 100.00 元（正好门槛）—— 应成功",
     f"INSERT INTO withdraw_order (withdraw_no,agent_id,amount_fen,channel,payee_name,payee_account)"
     f" VALUES ('W002',{a1},10000,'BANK_TRANSFER','张三','6222');", "ok")
case("C3 台账金额 0 —— 应被拒",
     "INSERT INTO account_ledger (owner_type,owner_id,biz_type,ref_no,direction,amount_fen,balance_before,balance_after)"
     " VALUES ('PLATFORM',1,'ADJUST','Z0','IN',0,0,0);", "fail", "ck_ledger_amount")
case("C4 台账金额为负 —— 应被拒",
     "INSERT INTO account_ledger (owner_type,owner_id,biz_type,ref_no,direction,amount_fen,balance_before,balance_after)"
     " VALUES ('PLATFORM',1,'ADJUST','ZN','OUT',-100,0,-100);", "fail", "ck_ledger_amount")
case("C5 订单金额 0 —— 应被拒",
     "INSERT INTO biz_order (order_no,buyer_user_id,buyer_type,sku_id,sku_code_snapshot,code_qty_snapshot,amount_fen,expire_at)"
     " VALUES ('ORD_ZERO',4,'STUDENT',3,'STUDENT_DIRECT',1,0,'2030-01-01');", "fail", "ck_order_amount")
case("C6 价目表价格为 0 —— 应被拒",
     "INSERT INTO agent_price_tier (agent_level,sku_id,total_price_fen,effective_from,created_by)"
     " VALUES (1,1,0,'2026-01-01',1);", "fail", "ck_price_positive")
case("C7 佣金规则声明按码固定却不填金额 —— 应被拒",
     "INSERT INTO commission_rule (buyer_level,sku_id,calc_mode,effective_from,created_by)"
     " VALUES (2,1,'FIXED_PER_CODE','2026-01-01',1);", "fail", "ck_commission_mode")
case("C8 佣金规则声明按比例却不填比例 —— 应被拒",
     "INSERT INTO commission_rule (buyer_level,sku_id,calc_mode,effective_from,created_by)"
     " VALUES (2,1,'RATE_OF_PAID','2026-01-01',1);", "fail", "ck_commission_mode")
case("C9 佣金金额为 0 —— 应被拒",
     f"INSERT INTO commission_record (beneficiary_agent_id,order_id,order_no,rule_id,calc_mode_snapshot,"
     f"calc_basis_fen,amount_fen,freeze_until) VALUES ({b2},1,'ORD001',1,'X',0,0,'2030-01-01');",
     "fail", "ck_commission_amount")


# =========================================================================
section("D · 唯一性约束")
# =========================================================================
case("D1 手机号重复注册 —— 应被拒",
     "INSERT INTO sys_user (phone,role) VALUES ('13000000004','STUDENT');", "fail", "uk_phone")
case("D2 agent_code 重复 —— 应被拒",
     "INSERT INTO agent_profile (user_id,agent_code,level,status) VALUES (90,'A1',1,'ACTIVE');", "fail", "uk_agent_code")
case("D3 同一 user 开两个代理档案 —— 应被拒",
     "INSERT INTO agent_profile (user_id,agent_code,level,status) VALUES (2,'A1X',1,'ACTIVE');", "fail", "uk_user")

mysql(f"""INSERT INTO activation_code (id,code_hash,code_enc,code_masked,course_id,owner_agent_id,order_id,activate_deadline)
 VALUES (1,REPEAT('a',64),UNHEX('00'),'YG7X-****-3K9P',1,{a1},1,'2030-01-01'),
        (2,REPEAT('b',64),UNHEX('00'),'YG7X-****-4M2Q',1,{a1},1,'2030-01-01'),
        (5,REPEAT('f',64),UNHEX('00'),'YG7X-****-7B4L',1,{a1},1,'2030-01-01');""")
# 真实顺序是「先激活码、再开权益」，前置数据要照这个顺序摆，
# 否则 D5~D7 会被「权益必须开给激活该码的本人」提前拦下，测不到唯一键。
mysql("UPDATE activation_code SET status='BOUND',bound_user_id=4,bound_at=NOW(3) WHERE id IN (1,2);")
mysql("UPDATE activation_code SET status='BOUND',bound_user_id=6,bound_at=NOW(3) WHERE id=5;")

case("D4 激活码 hash 碰撞 —— 应被拒",
     f"INSERT INTO activation_code (code_hash,code_enc,code_masked,course_id,order_id,activate_deadline)"
     f" VALUES (REPEAT('a',64),UNHEX('00'),'X',1,1,'2030-01-01');", "fail", "uk_code_hash")

case("D5 学员激活首份权益 —— 应成功",
     "INSERT INTO enrollment (user_id,course_id,source,activation_code_id,valid_from,valid_until,agreement_version,agreed_at)"
     " VALUES (4,1,'ACTIVATION_CODE',1,NOW(3),'2030-01-01','v1',NOW(3));", "ok")
case("D6 同一用户同一课程再来一份 —— 应被拒",
     "INSERT INTO enrollment (user_id,course_id,source,activation_code_id,valid_from,valid_until,agreement_version,agreed_at)"
     " VALUES (4,1,'ACTIVATION_CODE',2,NOW(3),'2030-01-01','v1',NOW(3));", "fail", "uk_user_course")
case("D7 拿绑给别人的码开自己的权益 —— 应被拒（一码一人）",
     "INSERT INTO enrollment (user_id,course_id,source,activation_code_id,valid_from,valid_until,agreement_version,agreed_at)"
     " VALUES (5,1,'ACTIVATION_CODE',1,NOW(3),'2030-01-01','v1',NOW(3));", "fail", "本人")
case("D7b 同一个码开两份权益（跨课程）—— 应被拒（uk_code）",
     "INSERT INTO enrollment (user_id,course_id,source,activation_code_id,valid_from,valid_until,agreement_version,agreed_at)"
     " VALUES (6,1,'ACTIVATION_CODE',5,NOW(3),'2030-01-01','v1',NOW(3));"
     "INSERT INTO enrollment (user_id,course_id,source,activation_code_id,valid_from,valid_until,agreement_version,agreed_at)"
     " VALUES (6,2,'ACTIVATION_CODE',5,NOW(3),'2030-01-01','v1',NOW(3));", "fail", "uk_code")
case("D8 两笔直购权益（activation_code_id 均为 NULL）—— 应成功（NULL 不参与唯一）",
     "INSERT INTO enrollment (user_id,course_id,source,order_id,valid_from,valid_until,agreement_version,agreed_at)"
     " VALUES (5,1,'DIRECT_PURCHASE',1,NOW(3),'2030-01-01','v1',NOW(3));", "ok")

# 多套试卷（2026-09-03）之后，规则挂在【卷】上而不是课上，考试记录也按卷去重。
# 下面 D / E / U / V / AH 五段共用这三张卷，与生产上的 FDE_MAIN / FDE_CH1 / AIGC_OPEN 同形：
#   1 = 综合卷（发证）  2 = 章节练兵卷（不发证）  3 = 开放卷（不挂课程、不发证）
# 卷必须先于 exam_config 建出来 —— 规则行的 paper_id 指着它。
mysql("INSERT INTO exam_paper (id,code,title,course_id,access,grants_certificate,status,sort_no) VALUES"
      " (1,'T_MAIN','测试综合卷',1,'ENROLLED',1,'ON',10),"
      " (2,'T_CH1','测试章节练兵卷',1,'ENROLLED',0,'ON',20),"
      " (3,'T_OPEN','测试开放卷',NULL,'OPEN',0,'ON',30);")
mysql("INSERT INTO exam_config (id,course_id,paper_id,effective_from) VALUES (1,1,1,'2026-01-01');")
mysql("INSERT INTO exam_attempt (id,user_id,course_id,paper_id,config_id,attempt_no,question_ids,deadline_at)"
      " VALUES (1,4,1,1,1,1,'[1,2,3]','2030-01-01');")
case("D9 同一用户同一份卷重复第 1 次考试 —— 应被拒",
     "INSERT INTO exam_attempt (user_id,course_id,paper_id,config_id,attempt_no,question_ids,deadline_at)"
     " VALUES (4,1,1,1,1,'[4,5]','2030-01-01');", "fail", "uk_user_paper_attempt")
case("D10 第 2 次考试 —— 应成功",
     "INSERT INTO exam_attempt (user_id,course_id,paper_id,config_id,attempt_no,question_ids,deadline_at)"
     " VALUES (4,1,1,1,2,'[4,5]','2030-01-01');", "ok")
case("D11 同一份考卷同一题重复作答 —— 应被拒",
     "INSERT INTO exam_answer (attempt_id,question_id,user_answer) VALUES (1,7,'A');"
     "INSERT INTO exam_answer (attempt_id,question_id,user_answer) VALUES (1,7,'B');",
     "fail", "uk_attempt_question")
mysql("UPDATE exam_attempt SET score=95,passed=1,submitted_at=NOW(3) WHERE id=1;")
case("D12 同一用户同一课程发两张结业证明 —— 应被拒",
     "INSERT INTO certificate (cert_no,user_id,course_id,attempt_id) VALUES ('C001',4,1,1);"
     "INSERT INTO certificate (cert_no,user_id,course_id,attempt_id) VALUES ('C002',4,1,1);",
     "fail", "uk_user_course")
case("D13 学习进度同 user+lesson 重复 —— 应被拒",
     "INSERT INTO learn_progress (user_id,course_id,lesson_id) VALUES (4,1,1);"
     "INSERT INTO learn_progress (user_id,course_id,lesson_id) VALUES (4,1,1);",
     "fail", "uk_user_lesson")
case("D14 同一设备重复绑定 —— 应被拒",
     "INSERT INTO user_device (user_id,device_id) VALUES (4,'DEV_AAA');"
     "INSERT INTO user_device (user_id,device_id) VALUES (4,'DEV_AAA');",
     "fail", "uk_user_device")
case("D15 同一用户同一课程发两个水印码字 —— 应被拒",
     "INSERT INTO wm_codeword (user_id,course_id,bits,bit_length) VALUES (4,1,UNHEX('FF'),8);"
     "INSERT INTO wm_codeword (user_id,course_id,bits,bit_length) VALUES (4,1,UNHEX('AA'),8);",
     "fail", "uk_user_course")
case("D16 同一代理同一月两条统计 —— 应被拒",
     f"INSERT INTO agent_monthly_stat (agent_id,stat_month) VALUES ({a1},'2026-08');"
     f"INSERT INTO agent_monthly_stat (agent_id,stat_month) VALUES ({a1},'2026-08');",
     "fail", "uk_agent_month")
case("D17 同一订单重复申请开票 —— 应被拒",
     "INSERT INTO invoice_request (applicant_user_id,scope,ref_id,title,amount_fen) VALUES (3,'ORDER',1,'某公司',349000);"
     "INSERT INTO invoice_request (applicant_user_id,scope,ref_id,title,amount_fen) VALUES (3,'ORDER',1,'某公司',349000);",
     "fail", "uk_scope_ref")
case("D18 提现单号重复 —— 应被拒",
     f"INSERT INTO withdraw_order (withdraw_no,agent_id,amount_fen,channel,payee_name,payee_account)"
     f" VALUES ('W002',{a1},20000,'BANK_TRANSFER','张三','6222');", "fail", "uk_withdraw_no")


# =========================================================================
section("E · 类型、枚举与 JSON")
# =========================================================================
case("E1 非法角色枚举 —— 应被拒",
     "INSERT INTO sys_user (phone,role) VALUES ('13900000001','SUPERUSER');", "fail", "Data truncated")
case("E2 非法激活码状态 —— 应被拒",
     "UPDATE activation_code SET status='USED' WHERE id=1;", "fail", "Data truncated")
case("E3 非法 JSON —— 应被拒",
     "INSERT INTO exam_attempt (user_id,course_id,paper_id,config_id,attempt_no,question_ids,deadline_at)"
     " VALUES (5,1,1,1,1,'{不是json','2030-01-01');", "fail", "Invalid JSON")
case("E4 合法 JSON 带中文 —— 应成功",
     "INSERT INTO qa_ticket (id,user_id,course_id,title) VALUES (1,4,1,'RAG 检索不到内容怎么办');"
     "INSERT INTO qa_message (ticket_id,sender_type,content,citations)"
     " VALUES (1,'AI','建议先看第四讲','[{\"lesson_id\":1,\"ts_sec\":735,\"snippet\":\"知识库与检索\"}]');", "ok")

binout = query("SELECT HEX(bits) FROM wm_codeword WHERE user_id=4;")
case("E5 VARBINARY 水印码字二进制往返无损", "SELECT 1;", "ok")
if "FF" not in binout.upper():
    FAIL.append(("E5 VARBINARY 往返", f"读回 {binout}")); PASS.pop()
    print("\033[31m FAIL\033[0m  E5 VARBINARY 往返异常")


# =========================================================================
section("F · 台账契约与金额精度")
# =========================================================================
mysql(f"DELETE FROM commission_ledger WHERE agent_id={a1};")
mysql(f"""
INSERT INTO commission_ledger (agent_id,biz_type,ref_no,direction,amount_fen,balance_before,balance_after) VALUES
 ({a1},'SETTLE','S001','IN', 51000,     0,  51000),
 ({a1},'SETTLE','S002','IN', 51000, 51000, 102000),
 ({a1},'WITHDRAW','W002','OUT',102000,102000,     0);
""")
chain = query(f"""SELECT
  (SELECT COUNT(*) FROM commission_ledger l1 JOIN commission_ledger l2
     ON l2.agent_id=l1.agent_id AND l2.id=(SELECT MIN(id) FROM commission_ledger l3
        WHERE l3.agent_id=l1.agent_id AND l3.id>l1.id)
   WHERE l1.agent_id={a1} AND l1.balance_after<>l2.balance_before) AS broken_links;""")
broken = chain.splitlines()[-1]
case(f"F1 台账链式一致：balance_after 恒等于下一行 balance_before（断链 {broken} 处）",
     "SELECT 1;" if broken == "0" else "SELECT * FROM nonexistent_table;", "ok")

case("F2 佣金冲正到负余额 —— 应成功（退款冲正已提现佣金，设计允许）",
     f"INSERT INTO commission_ledger (agent_id,biz_type,ref_no,direction,amount_fen,balance_before,balance_after)"
     f" VALUES ({a1},'REVOKE','R001','OUT',51000,0,-51000);", "ok")
neg = query(f"SELECT balance_after FROM commission_ledger WHERE agent_id={a1} ORDER BY id DESC LIMIT 1;")
case(f"F3 负余额确实落库（读回 {neg.splitlines()[-1]}）",
     "SELECT 1;" if neg.splitlines()[-1].startswith("-") else "SELECT * FROM nonexistent_table;", "ok")

mysql("INSERT INTO account_ledger (owner_type,owner_id,biz_type,ref_no,direction,amount_fen,balance_before,balance_after)"
      " VALUES ('PLATFORM',1,'BIG','BIG1','IN',9223372036854,0,9223372036854);")
big = query("SELECT amount_fen FROM account_ledger WHERE ref_no='BIG1';").splitlines()[-1]
case(f"F4 大额金额 BIGINT 分无精度损失（{big} 分 ≈ 922 亿元）",
     "SELECT 1;" if big == "9223372036854" else "SELECT * FROM nonexistent_table;", "ok")

# F5 是一个「已确认数据库拦不住」的盲区，不是待修 bug。
# 类型转换发生在 CHECK 与触发器求值之前，DB 层没有拦截点。
# 这条用例的作用是把盲区钉在台面上：一旦哪天 MySQL 行为变了，这里会红。
mysql("INSERT INTO account_ledger (owner_type,owner_id,biz_type,ref_no,direction,amount_fen,balance_before,balance_after)"
      " VALUES ('PLATFORM',1,'FRAC','F1','IN','12.345',0,12);")
trunc = query("SELECT amount_fen FROM account_ledger WHERE ref_no='F1';").splitlines()[-1]
case(f"F5 三位小数被 DB 静默截断为 {trunc}（已知盲区，须由应用层 yuanToFen 报错拦截）",
     "SELECT 1;" if trunc == "12" else "SELECT * FROM nonexistent_table;", "ok")


# =========================================================================
section("G · 激活码状态机（「激活即不退」的落点，录音 01:50）")
# =========================================================================
# code 6 专供 G4/G5 —— 必须是一张从未被激活的码，不能借用 D 段那批已绑定的
mysql(f"""INSERT INTO activation_code (id,code_hash,code_enc,code_masked,course_id,owner_agent_id,order_id,activate_deadline)
 VALUES (6,REPEAT('9',64),UNHEX('00'),'YG7X-****-6C1D',1,{a1},1,'2030-01-01');""")
case("G1 已绑定的码改绑到另一个学员 —— 应被拒（一码绑一人不可转移）",
     "UPDATE activation_code SET bound_user_id=5 WHERE id=1;", "fail", "不可改绑")
case("G2 已绑定的码被后台作废 —— 应被拒（激活即不退）",
     "UPDATE activation_code SET status='REVOKED' WHERE id=1;", "fail", "激活即不退")
# 同时清 bound_user_id 的话，先开火的是「不可改绑或解绑」那条，
# 状态回退这条其实没被测到。这里只动 status，把它单独逼出来。
case("G3 已绑定的码退回未激活（不动绑定人）—— 应被拒（激活即不退）",
     "UPDATE activation_code SET status='ISSUED' WHERE id=1;", "fail", "激活即不退")
case("G4 未激活的码作废 —— 应成功（G8：未激活可退）",
     "UPDATE activation_code SET status='REVOKED',revoked_at=NOW(3) WHERE id=6;", "ok")
case("G5 已作废的码再改状态 —— 应被拒（终态）",
     "UPDATE activation_code SET status='BOUND',bound_user_id=5,bound_at=NOW(3) WHERE id=6;", "fail", "终态")
case("G6 已绑定的码到期 BOUND→EXPIRED —— 应成功（权限到期是合法迁移）",
     "UPDATE activation_code SET status='EXPIRED' WHERE id=1;", "ok")

mysql(f"""INSERT INTO activation_code (id,code_hash,code_enc,code_masked,course_id,owner_agent_id,order_id,activate_deadline)
 VALUES (3,REPEAT('c',64),UNHEX('00'),'YG7X-****-9Z1T',1,{a1},1,'2030-01-01'),
        (4,REPEAT('d',64),UNHEX('00'),'YG7X-****-8Y2S',1,{a1},1,'2030-01-01');""")
case("G7 激活时不写 bound_user_id —— 应被拒",
     "UPDATE activation_code SET status='BOUND',bound_at=NOW(3) WHERE id=3;", "fail", "bound_user_id")
case("G8 激活时不写 bound_at —— 应被拒",
     "UPDATE activation_code SET status='BOUND',bound_user_id=5 WHERE id=3;", "fail", "bound_at")
case("G9 完整合法激活 ISSUED→BOUND —— 应成功",
     "UPDATE activation_code SET status='BOUND',bound_user_id=5,bound_at=NOW(3) WHERE id=3;", "ok")
case("G10 未激活的码超期 ISSUED→EXPIRED —— 应成功",
     "UPDATE activation_code SET status='EXPIRED' WHERE id=4;", "ok")


# =========================================================================
section("H · 价目与佣金规则的时间区间")
# =========================================================================
mysql("DELETE FROM agent_price_tier;")
mysql("INSERT INTO agent_price_tier (agent_level,sku_id,total_price_fen,effective_from,created_by)"
      " VALUES (1,1,39900,'2026-01-01',1);")
case("H1 同层级同套餐插入第二条「当前生效」价 —— 应被拒（否则取价二义）",
     "INSERT INTO agent_price_tier (agent_level,sku_id,total_price_fen,effective_from,created_by)"
     " VALUES (1,1,29900,'2026-06-01',1);", "fail", "uk_active_price")
case("H2 旧价先封口再插新价 —— 应成功",
     "UPDATE agent_price_tier SET effective_to='2026-06-01' WHERE agent_level=1 AND sku_id=1 AND effective_to IS NULL;"
     "INSERT INTO agent_price_tier (agent_level,sku_id,total_price_fen,effective_from,created_by)"
     " VALUES (1,1,29900,'2026-06-01',1);", "ok")
mysql("DELETE FROM commission_rule;")
mysql("INSERT INTO commission_rule (buyer_level,sku_id,calc_mode,fixed_per_code_fen,effective_from,created_by)"
      " VALUES (2,2,'FIXED_PER_CODE',5100,'2026-01-01',1);")
case("H3 同条件插入第二条「当前生效」佣金规则 —— 应被拒",
     "INSERT INTO commission_rule (buyer_level,sku_id,calc_mode,fixed_per_code_fen,effective_from,created_by)"
     " VALUES (2,2,'FIXED_PER_CODE',8000,'2026-06-01',1);", "fail", "uk_active_rule")


# =========================================================================
section("I · 公开问答库全文检索（中文 ngram）")
# =========================================================================
mysql("INSERT INTO faq_entry (course_id,question,answer,created_by) VALUES"
      " (1,'激活码激活后还能退款吗','激活码一经激活概不退费。未激活的码可在有效期内退。',1),"
      " (1,'如何绑定新设备','每个账号最多绑定 2 台设备，更换需短信验证。',1);")
hit = query("SELECT COUNT(*) FROM faq_entry WHERE MATCH(question) AGAINST('激活码' IN NATURAL LANGUAGE MODE);")
case(f"I1 中文全文检索「激活码」命中 {hit.splitlines()[-1]} 条",
     "SELECT 1;" if hit.splitlines()[-1] != "0" else "SELECT * FROM nonexistent_table;", "ok")
hit2 = query("SELECT COUNT(*) FROM faq_entry WHERE MATCH(question) AGAINST('设备' IN NATURAL LANGUAGE MODE);")
case(f"I2 中文全文检索「设备」命中 {hit2.splitlines()[-1]} 条",
     "SELECT 1;" if hit2.splitlines()[-1] != "0" else "SELECT * FROM nonexistent_table;", "ok")


# =========================================================================
section("J · 订单状态机")
# =========================================================================
mysql("""INSERT INTO biz_order (id,order_no,buyer_user_id,buyer_type,sku_id,sku_code_snapshot,
 code_qty_snapshot,amount_fen,expire_at) VALUES
 (10,'ORD010',4,'STUDENT',3,'STUDENT_DIRECT',1,79900,'2030-01-01'),
 (11,'ORD011',4,'STUDENT',3,'STUDENT_DIRECT',1,79900,'2030-01-01'),
 (12,'ORD012',4,'STUDENT',3,'STUDENT_DIRECT',1,79900,'2030-01-01');""")
case("J1 CREATED→PAID —— 应成功",
     "UPDATE biz_order SET status='PAID',paid_amount_fen=79900,paid_at=NOW(3) WHERE id=10;", "ok")
case("J2 PAID→FULFILLED —— 应成功",
     "UPDATE biz_order SET status='FULFILLED',fulfilled_at=NOW(3) WHERE id=10;", "ok")
case("J3 FULFILLED→CREATED（已发货退回待支付）—— 应被拒",
     "UPDATE biz_order SET status='CREATED' WHERE id=10;", "fail", "已发码")
# 原来这条先把一笔从未支付的订单改成 REFUNDED，第一句就被「未支付不能退款」
# 拦下了，第二句（真正要测的那句）根本没执行 —— 红都没红过，也从没测过。
case("J4 已退款订单改回已支付 —— 应被拒",
     "UPDATE biz_order SET status='PAID',paid_amount_fen=79900,paid_at=NOW(3) WHERE id=11;"
     "UPDATE biz_order SET status='REFUNDED',refunded_amount_fen=79900 WHERE id=11;"
     "UPDATE biz_order SET status='PAID' WHERE id=11;", "fail", "终态")
case("J5 已关闭订单改为已支付（关单后又付钱）—— 应被拒",
     "UPDATE biz_order SET status='CLOSED' WHERE id=12;"
     "UPDATE biz_order SET status='PAID',paid_amount_fen=79900 WHERE id=12;", "fail", "终态")
case("J6 标记已支付却不写 paid_at —— 应被拒",
     "INSERT INTO biz_order (id,order_no,buyer_user_id,buyer_type,sku_id,sku_code_snapshot,"
     "code_qty_snapshot,amount_fen,expire_at) VALUES (13,'ORD013',4,'STUDENT',3,'S',1,79900,'2030-01-01');"
     "UPDATE biz_order SET status='PAID',paid_amount_fen=79900 WHERE id=13;", "fail", "paid_at")


# =========================================================================
section("K · 佣金状态机（§7.3）")
# =========================================================================
mysql(f"""INSERT INTO commission_record (id,beneficiary_agent_id,source_agent_id,order_id,order_no,
 rule_id,calc_mode_snapshot,calc_basis_fen,amount_fen,freeze_until) VALUES
 (10,{a1},{b2},10,'ORD010',1,'FIXED_PER_CODE',79900,5100,'2026-01-01'),
 (11,{a1},{b2},11,'ORD011',1,'FIXED_PER_CODE',79900,5100,'2026-01-01');""")
case("K1 PENDING→SETTLED（冻结期满）—— 应成功",
     "UPDATE commission_record SET status='SETTLED',settled_at=NOW(3) WHERE id=10;", "ok")
case("K2 SETTLED→WITHDRAWING→WITHDRAWN —— 应成功",
     "UPDATE commission_record SET status='WITHDRAWING' WHERE id=10;"
     "UPDATE commission_record SET status='WITHDRAWN' WHERE id=10;", "ok")
case("K3 WITHDRAWN→SETTLED（已提现退回可提现，等于凭空多一笔）—— 应被拒",
     "UPDATE commission_record SET status='SETTLED' WHERE id=10;", "fail", "已提现的佣金")
case("K4 已撤销的佣金改回可提现 —— 应被拒",
     "UPDATE commission_record SET status='REVOKED',revoked_at=NOW(3) WHERE id=11;"
     "UPDATE commission_record SET status='SETTLED' WHERE id=11;", "fail", "已撤销")
case("K5 冻结期未满就转 SETTLED —— 应被拒",
     f"INSERT INTO commission_record (id,beneficiary_agent_id,order_id,order_no,rule_id,"
     f"calc_mode_snapshot,calc_basis_fen,amount_fen,freeze_until)"
     f" VALUES (12,{a1},12,'ORD012',1,'X',79900,5100,'2099-01-01');"
     "UPDATE commission_record SET status='SETTLED',settled_at=NOW(3) WHERE id=12;", "fail", "冻结期未满")


# =========================================================================
section("L · 提现状态机与勾稽")
# =========================================================================
mysql(f"""INSERT INTO withdraw_order (id,withdraw_no,agent_id,amount_fen,channel,payee_name,payee_account)
 VALUES (10,'W010',{a1},50000,'BANK_TRANSFER','张三','6222'),
        (11,'W011',{a1},50000,'BANK_TRANSFER','张三','6222');""")
case("L1 APPLIED→REVIEWING→APPROVED→PAYING→PAID —— 应成功",
     "UPDATE withdraw_order SET status='REVIEWING' WHERE id=10;"
     "UPDATE withdraw_order SET status='APPROVED' WHERE id=10;"
     "UPDATE withdraw_order SET status='PAYING' WHERE id=10;"
     "UPDATE withdraw_order SET status='PAID',actual_paid_fen=50000,paid_at=NOW(3) WHERE id=10;", "ok")
case("L2 已打款改回待申请（可重复打款）—— 应被拒",
     "UPDATE withdraw_order SET status='APPLIED' WHERE id=10;", "fail", "已终结")
case("L3 APPLIED 直接跳到 PAID（绕过审核）—— 应被拒",
     "UPDATE withdraw_order SET status='PAID',actual_paid_fen=50000,paid_at=NOW(3) WHERE id=11;", "fail", "不可跳步")
case("L4 实付 + 代扣税 ≠ 申请额 —— 应被拒",
     "UPDATE withdraw_order SET status='REVIEWING' WHERE id=11;"
     "UPDATE withdraw_order SET status='APPROVED' WHERE id=11;"
     "UPDATE withdraw_order SET status='PAYING' WHERE id=11;"
     "UPDATE withdraw_order SET status='PAID',actual_paid_fen=30000,tax_withheld_fen=5000,paid_at=NOW(3) WHERE id=11;",
     "fail", "代扣代缴")


# =========================================================================
section("M · 金额勾稽（钱不能凭空多出来）")
# =========================================================================
case("M1 退款额超过订单实付额 —— 应被拒",
     "UPDATE biz_order SET refunded_amount_fen=99900 WHERE id=10;", "fail", "ck_order_amount")
case("M2 实付额超过应付额 —— 应被拒",
     "UPDATE biz_order SET paid_amount_fen=99900 WHERE id=10;", "fail", "ck_order_amount")
case("M3 全额退款（退款额 = 实付额）—— 应成功",
     "UPDATE biz_order SET refunded_amount_fen=79900 WHERE id=10;", "ok")
case("M4 退款额为负 —— 应被拒",
     "UPDATE biz_order SET refunded_amount_fen=-100 WHERE id=10;", "fail", "ck_order_amount")


# =========================================================================
section("N · 时效（激活期限与权益有效期）")
# =========================================================================
mysql(f"""INSERT INTO activation_code (id,code_hash,code_enc,code_masked,course_id,owner_agent_id,
 order_id,activate_deadline) VALUES (20,REPEAT('e',64),UNHEX('00'),'EXP',1,{a1},1,'2020-01-01');""")
case("N1 超过激活期限的码仍被激活 —— 应被拒",
     "UPDATE activation_code SET status='BOUND',bound_user_id=5,bound_at=NOW(3) WHERE id=20;", "fail", "激活期限")
case("N2 权益有效期倒挂（valid_until < valid_from）—— 应被拒",
     "INSERT INTO enrollment (user_id,course_id,source,valid_from,valid_until,agreement_version,agreed_at)"
     " VALUES (89,1,'GRANT','2030-01-01','2026-01-01','v1',NOW(3));", "fail", "ck_enrollment_period")
case("N3 订单过期时间早于创建时间 —— 应被拒",
     "INSERT INTO biz_order (order_no,buyer_user_id,buyer_type,sku_id,sku_code_snapshot,"
     "code_qty_snapshot,amount_fen,expire_at) VALUES ('ORD_BAD',4,'STUDENT',3,'S',1,79900,'2020-01-01');", "fail", "过期时间")

# G5 的两个期限从代码搬进了 course 表（20260811_课程期限参数.sql）。
# 期限为 0 或负数是"发出去就已经过期"，属于配置错误里最难发现的一类：
# 出码那步照常成功，是激活那步才失败，而那时钱已经收了。
case("N4 激活期限配置为 0 —— 应被拒",
     "UPDATE course SET activate_months=0 WHERE id=1;", "fail", "ck_course_months")
case("N5 观看有效期配置为负 —— 应被拒",
     "UPDATE course SET access_months=-1 WHERE id=1;", "fail", "ck_course_months")
case("N6 期限为正 —— 应成功",
     "UPDATE course SET activate_months=12, access_months=24 WHERE id=1;", "ok")


# =========================================================================
section("O · 学习进度反作弊勾稽（G12）")
# =========================================================================
mysql("DELETE FROM learn_progress;")
case("O1 有效观看秒数超过课程总时长（3600s）—— 应被拒",
     "INSERT INTO learn_progress (user_id,course_id,lesson_id,max_position_sec,watched_sec,heartbeat_count)"
     " VALUES (4,1,1,3600,99999,10);", "fail", "有效观看时长")
case("O2 观看位置超过课程时长 —— 应被拒",
     "INSERT INTO learn_progress (user_id,course_id,lesson_id,max_position_sec,watched_sec,heartbeat_count)"
     " VALUES (5,1,1,99999,100,10);", "fail", "观看位置")
case("O3 心跳数明显撑不起观看时长（心跳 1 次却称看了 3600 秒）—— 应被拒",
     "INSERT INTO learn_progress (user_id,course_id,lesson_id,max_position_sec,watched_sec,heartbeat_count)"
     " VALUES (89,1,1,3600,3600,1);", "fail", "心跳次数")
case("O4 正常进度（心跳 240 次 × 15 秒 = 3600）—— 应成功",
     "INSERT INTO learn_progress (user_id,course_id,lesson_id,max_position_sec,watched_sec,heartbeat_count)"
     " VALUES (4,1,1,1800,1800,120);", "ok")
case("O5 进度前进 1800→2400 —— 应成功",
     "UPDATE learn_progress SET max_position_sec=2400,watched_sec=2400,heartbeat_count=160"
     " WHERE user_id=4 AND lesson_id=1;", "ok")
case("O6 进度回退 2400→600（旧端心跳回冲）—— 应被拒",
     "UPDATE learn_progress SET max_position_sec=600 WHERE user_id=4 AND lesson_id=1;", "fail", "只能前进")


# =========================================================================
section("P · 并发与幂等（真实竞态，不是顺序重放）")
# =========================================================================
import threading
results = []
lock = threading.Lock()

def racer(i):
    ok, out = mysql(
        "INSERT INTO account_ledger (owner_type,owner_id,biz_type,ref_no,direction,"
        "amount_fen,balance_before,balance_after) VALUES ('PLATFORM',1,'RACE','R001','IN',100,0,100);")
    with lock:
        results.append(ok)

threads = [threading.Thread(target=racer, args=(i,)) for i in range(8)]
for t in threads: t.start()
for t in threads: t.join()
succ = sum(results)
case(f"P1 8 个连接同时写同一 (biz_type, ref_no)：成功 {succ} 次（期望恰好 1）",
     "SELECT 1;" if succ == 1 else "SELECT * FROM nonexistent_table;", "ok")

rows = query("SELECT COUNT(*) FROM account_ledger WHERE biz_type='RACE';").splitlines()[-1]
case(f"P2 库内实际只有 {rows} 行（重复入账在物理层被裁决）",
     "SELECT 1;" if rows == "1" else "SELECT * FROM nonexistent_table;", "ok")

# 行锁串行化：两个连接同时对同一代理余额做 FOR UPDATE 加减
mysql(f"INSERT INTO agent_balance (agent_id,commission_balance_fen) VALUES ({a1},0)"
      f" ON DUPLICATE KEY UPDATE commission_balance_fen=0;")
def adder(i):
    mysql(f"""START TRANSACTION;
SELECT commission_balance_fen FROM agent_balance WHERE agent_id={a1} FOR UPDATE;
UPDATE agent_balance SET commission_balance_fen = commission_balance_fen + 1000 WHERE agent_id={a1};
COMMIT;""")
threads = [threading.Thread(target=adder, args=(i,)) for i in range(10)]
for t in threads: t.start()
for t in threads: t.join()
bal = query(f"SELECT commission_balance_fen FROM agent_balance WHERE agent_id={a1};").splitlines()[-1]
case(f"P3 10 个连接各 +1000 分，FOR UPDATE 行锁下最终 {bal}（期望 10000，无丢失更新）",
     "SELECT 1;" if bal == "10000" else "SELECT * FROM nonexistent_table;", "ok")


# =========================================================================
section("Q · 索引有效性（性能承诺不能只写在注释里）")
# =========================================================================
# 造量，让优化器有理由选索引
mysql("""
INSERT INTO risk_event (user_id,signal,score)
SELECT 4,'RATE_EXCEED',20 FROM information_schema.columns LIMIT 3000;
INSERT INTO question (course_id,lesson_id,type,stem,answer,origin,review_status)
SELECT 1,1,'SINGLE','题干','A','AI_GENERATED','APPROVED' FROM information_schema.columns LIMIT 3000;
""")

def explain_uses(sql, idx):
    out = query(f"EXPLAIN {sql}")
    return idx in out, out.splitlines()[-1][:150]

for name, sql, idx in [
    ("Q1 代理月度统计走 idx_agent_month",
     f"SELECT SUM(amount_fen) FROM biz_order WHERE buyer_agent_id={b2} AND paid_at BETWEEN '2026-08-01' AND '2026-09-01';",
     "idx_agent_month"),
    ("Q2 题库随机抽题走 idx_pool",
     "SELECT id FROM question WHERE course_id=1 AND review_status='APPROVED' AND type='SINGLE' LIMIT 100;",
     "idx_pool"),
    ("Q3 佣金冻结扫描走 idx_freeze_scan",
     "SELECT id FROM commission_record WHERE status='PENDING' AND freeze_until<=NOW();",
     "idx_freeze_scan"),
    ("Q4 风控 7 天窗口走 idx_user_time",
     "SELECT SUM(score) FROM risk_event WHERE user_id=4 AND created_at>=NOW()-INTERVAL 7 DAY;",
     "idx_user_time"),
    ("Q5 激活码按 hash 查（激活热路径）走唯一索引",
     f"SELECT id FROM activation_code WHERE code_hash=REPEAT('a',64);", "uk_code_hash"),
]:
    used, plan = explain_uses(sql, idx)
    case(f"{name}（实际 {plan.split()[5] if len(plan.split())>5 else '?'}）",
         "SELECT 1;" if used else "SELECT * FROM nonexistent_table;", "ok")


# =========================================================================
section("R · 字符集、边界与孤儿数据")
# =========================================================================
case("R1 昵称含 4 字节 emoji —— 应成功（utf8mb4）",
     "UPDATE sys_user SET nickname='学员🎓📚' WHERE id=4;", "ok")
emo = query("SELECT nickname FROM sys_user WHERE id=4;").splitlines()[-1]
case(f"R2 emoji 往返无损（读回 {emo}）",
     "SELECT 1;" if "🎓" in emo else "SELECT * FROM nonexistent_table;", "ok")
case("R3 手机号超出 VARCHAR(20) —— 应被拒（严格模式不静默截断）",
     "INSERT INTO sys_user (phone,role) VALUES ('1300000000000000000000000','STUDENT');", "fail", "Data too long")
case("R4 中文单字全文检索「码」—— ngram_token_size=2，单字检索命中 0 条属预期",
     "SELECT 1;", "ok")
single = query("SELECT COUNT(*) FROM faq_entry WHERE MATCH(question) AGAINST('码' IN NATURAL LANGUAGE MODE);")
print(f"        └─ 实测单字命中 {single.splitlines()[-1]} 条（ngram 最小切分 2 字，检索词须≥2字）")

mysql("INSERT INTO course (id,title,status) VALUES (99,'待删课程','ON');")
mysql("INSERT INTO chapter (id,course_id,title,sort_no) VALUES (99,99,'章',1);")
mysql("DELETE FROM course WHERE id=99;")
orphan = query("SELECT COUNT(*) FROM chapter WHERE course_id=99;").splitlines()[-1]
case(f"R5 删除课程后遗留 {orphan} 条孤儿章节（无外键，须应用层级联）",
     "SELECT 1;" if orphan == "1" else "SELECT * FROM nonexistent_table;", "ok")


# =========================================================================
section("S · 事务原子性（触发器拒绝时前面的写入必须一起回滚）")
# =========================================================================
mysql(f"""START TRANSACTION;
INSERT INTO sys_user (id,phone,role) VALUES (500,'13500000500','STUDENT');
UPDATE activation_code SET status='ISSUED',bound_user_id=NULL WHERE id=3;
COMMIT;""")
leaked = query("SELECT COUNT(*) FROM sys_user WHERE id=500;").splitlines()[-1]
case(f"S1 事务中途被触发器拒绝后，前面那条 INSERT 残留 {leaked} 行（期望 0）",
     "SELECT 1;" if leaked == "0" else "SELECT * FROM nonexistent_table;", "ok")


# =========================================================================
section("T · 归属规则不可变（§7.5 first-touch，代理最容易吵架的地方）")
# =========================================================================
case("T1 首次落定归属 —— 应成功",
     f"UPDATE sys_user SET attributed_agent_id={a1},attributed_at=NOW(3),"
     f"attribution_source='REF_LINK' WHERE id=5;", "ok")
case("T2 已有归属再改挂到另一个代理（截胡）—— 应被拒",
     f"UPDATE sys_user SET attributed_agent_id={b2} WHERE id=5;", "fail", "first-touch")
case("T3 清空已有归属 —— 应被拒",
     "UPDATE sys_user SET attributed_agent_id=NULL WHERE id=5;", "fail", "first-touch")
case("T4 落定归属却不写落定时间 —— 应被拒",
     f"UPDATE sys_user SET attributed_agent_id={a1} WHERE id=4;", "fail", "attributed_at")


# =========================================================================
section("U · 业务前置条件（不该发生的事不能被写进库）")
# =========================================================================
mysql(f"UPDATE agent_profile SET status='TERMINATED' WHERE id={b2};")
case("U1 已终止的代理商下单 —— 应被拒",
     f"INSERT INTO biz_order (order_no,buyer_user_id,buyer_type,buyer_agent_id,buyer_agent_level,"
     f"sku_id,sku_code_snapshot,code_qty_snapshot,amount_fen,expire_at)"
     f" VALUES ('ORD_TERM',3,'AGENT',{b2},2,2,'AGENT_10',10,349000,'2030-01-01');", "fail", "不在有效状态")
mysql(f"UPDATE agent_profile SET status='ACTIVE' WHERE id={b2};")

mysql("UPDATE sys_user SET status='FROZEN' WHERE id=89;")
case("U2 已冻结的学员激活权益 —— 应被拒",
     "INSERT INTO enrollment (user_id,course_id,source,valid_from,valid_until,agreement_version,agreed_at)"
     " VALUES (89,1,'GRANT',NOW(3),'2030-01-01','v1',NOW(3));", "fail", "不在正常状态")
mysql("UPDATE sys_user SET status='ACTIVE' WHERE id=89;")

mysql("INSERT INTO exam_attempt (id,user_id,course_id,paper_id,config_id,attempt_no,question_ids,"
      "deadline_at,score,passed,submitted_at) VALUES (5,5,1,1,1,1,'[1]','2030-01-01',40,0,NOW(3));")
case("U3 给未通过的考试发结业证明 —— 应被拒",
     "INSERT INTO certificate (cert_no,user_id,course_id,attempt_id) VALUES ('C900',5,1,5);", "fail", "已通过的考试")
case("U4 拿别人的考试记录给自己发证明 —— 应被拒",
     "INSERT INTO certificate (cert_no,user_id,course_id,attempt_id) VALUES ('C901',5,1,1);", "fail", "已通过的考试")

bal_now = query(f"SELECT commission_balance_fen FROM agent_balance WHERE agent_id={a1};").splitlines()[-1]
case(f"U5 提现额超过佣金余额（余额 {bal_now} 分，申请 999900）—— 应被拒",
     f"INSERT INTO withdraw_order (withdraw_no,agent_id,amount_fen,channel,payee_name,payee_account)"
     f" VALUES ('W900',{a1},999900,'BANK_TRANSFER','张三','6222');", "fail", "超过可提现")

mysql(f"UPDATE agent_balance SET commission_balance_fen=-5000 WHERE agent_id={a1};")
case("U6 负余额（退款冲正尚未抵扣完）时申请提现 —— 应被拒",
     f"INSERT INTO withdraw_order (withdraw_no,agent_id,amount_fen,channel,payee_name,payee_account)"
     f" VALUES ('W901',{a1},10000,'BANK_TRANSFER','张三','6222');", "fail", "余额为负")


# =========================================================================
section("V · 测评完整性（结业证明的上游：证书只信 passed，那谁把关 passed）")
# =========================================================================
mysql("INSERT INTO sys_user (id,phone,role) VALUES (600,'13000000600','STUDENT');")
mysql("INSERT INTO exam_attempt (id,user_id,course_id,paper_id,config_id,attempt_no,question_ids,deadline_at)"
      " VALUES (100,600,1,1,1,1,'[1,2]','2030-01-01');")

case("V1 分数超过题量（100 题却得 999 分）—— 应被拒",
     "UPDATE exam_attempt SET score=999,passed=1,submitted_at=NOW(3) WHERE id=100;", "fail", "题量")
case("V2 分数为负 —— 应被拒",
     "UPDATE exam_attempt SET score=-10,passed=0,submitted_at=NOW(3) WHERE id=100;", "fail", "ck_attempt_score")
case("V3 只考了 10 分却标记通过 —— 应被拒（证书链的根，passed 必须与分数自洽）",
     "UPDATE exam_attempt SET score=10,passed=1,submitted_at=NOW(3) WHERE id=100;", "fail", "及格线")
case("V4 交卷截止时间早于开考时间（等于零时长或无限时长）—— 应被拒",
     "INSERT INTO exam_attempt (user_id,course_id,paper_id,config_id,attempt_no,question_ids,started_at,deadline_at)"
     " VALUES (600,1,1,1,2,'[1]','2026-08-01 10:00:00','2026-08-01 09:00:00');", "fail", "ck_attempt_time")
case("V5 超出补考次数上限（max_attempts=3，考第 4 次）—— 应被拒",
     "INSERT INTO exam_attempt (user_id,course_id,paper_id,config_id,attempt_no,question_ids,deadline_at)"
     " VALUES (600,1,1,1,4,'[1]','2030-01-01');", "fail", "补考次数")
case("V6 没交卷却写了分数 —— 应被拒",
     "UPDATE exam_attempt SET score=95,passed=1 WHERE id=100;", "fail", "交卷时间")
case("V7 正常交卷 95 分通过（pass_score=90）—— 应成功",
     "UPDATE exam_attempt SET score=95,passed=1,submitted_at=NOW(3) WHERE id=100;", "ok")
case("V8 已交卷后改分（发证依据被篡改的唯一入口）—— 应被拒",
     "UPDATE exam_attempt SET score=100 WHERE id=100;", "fail", "不得改分")


# =========================================================================
section("W · 防共享账号的物理约束（设备上限、会话、心跳倍速）")
# =========================================================================
mysql("DELETE FROM user_device WHERE user_id=600;")
case("W1 绑定第 1、2 台设备 —— 应成功（承诺上限 2 台）",
     "INSERT INTO user_device (user_id,device_id) VALUES (600,'DEV_1');"
     "INSERT INTO user_device (user_id,device_id) VALUES (600,'DEV_2');", "ok")
case("W2 绑定第 3 台设备 —— 应被拒（否则一个号全班用，防共享的地基就没了）",
     "INSERT INTO user_device (user_id,device_id) VALUES (600,'DEV_3');", "fail", "上限")
case("W3 解绑一台后再绑新设备 —— 应成功（已移除的不占额度）",
     "UPDATE user_device SET status='REMOVED',removed_at=NOW(3) WHERE user_id=600 AND device_id='DEV_1';"
     "INSERT INTO user_device (user_id,device_id) VALUES (600,'DEV_3');", "ok")

case("W11 把已解绑的那台重新启用（此时已满 2 台）—— 应被拒（UPDATE 也要过上限）",
     "UPDATE user_device SET status='ACTIVE',removed_at=NULL WHERE user_id=600 AND device_id='DEV_1';",
     "fail", "上限")

case("W4 播放会话结束时间早于开始时间 —— 应被拒",
     "INSERT INTO play_session (session_id,user_id,lesson_id,device_id,client_ip,started_at,ended_at)"
     " VALUES ('S001',600,1,'DEV_2','1.2.3.4','2026-08-01 10:00:00','2026-08-01 09:00:00');", "fail", "ck_session_time")
case("W5 取片计数为负 —— 应被拒",
     "INSERT INTO play_session (session_id,user_id,lesson_id,device_id,client_ip,segment_fetched)"
     " VALUES ('S002',600,1,'DEV_2','1.2.3.4',-5);", "fail", "ck_session_fetched")
case("W6 心跳倍速 9.9 倍（倍速刷进度）—— 应被拒",
     "INSERT INTO learn_heartbeat (user_id,lesson_id,session_id,position_sec,playback_rate)"
     " VALUES (600,1,'S003',100,9.9);", "fail", "ck_heartbeat_rate")
case("W7 心跳播放位置为负 —— 应被拒",
     "INSERT INTO learn_heartbeat (user_id,lesson_id,session_id,position_sec)"
     " VALUES (600,1,'S003',-100);", "fail", "ck_heartbeat_pos")
case("W8 正常心跳 2 倍速 —— 应成功（倍速本身合法，只挡离谱值）",
     "INSERT INTO learn_heartbeat (user_id,lesson_id,session_id,position_sec,playback_rate)"
     " VALUES (600,1,'S003',100,2.0);", "ok")
case("W9 水印码字位长为 0 —— 应被拒（取证证据链，空码字等于没水印）",
     "INSERT INTO wm_codeword (user_id,course_id,bits,bit_length) VALUES (600,1,UNHEX('FF'),0);", "fail", "ck_wm_bits")
case("W10 码字字节数撑不起声称的位长（1 字节却称 256 位）—— 应被拒",
     "INSERT INTO wm_codeword (user_id,course_id,bits,bit_length) VALUES (600,1,UNHEX('FF'),256);", "fail", "ck_wm_bits")


# =========================================================================
section("X · 台账每一行自洽（前三轮只验了链条不断，没验单行算得对）")
# =========================================================================
case("X1 IN 方向但 after ≠ before + amount —— 应被拒",
     "INSERT INTO account_ledger (owner_type,owner_id,biz_type,ref_no,direction,amount_fen,balance_before,balance_after)"
     " VALUES ('PLATFORM',1,'ADJUST','ADJ_X1','IN',10000,0,999999);", "fail", "ck_ledger_arith")
case("X2 OUT 方向余额却增加了 —— 应被拒",
     "INSERT INTO account_ledger (owner_type,owner_id,biz_type,ref_no,direction,amount_fen,balance_before,balance_after)"
     " VALUES ('PLATFORM',1,'ADJUST','ADJ_X2','OUT',10000,50000,60000);", "fail", "ck_ledger_arith")
case("X3 合法 IN 行（0 + 10000 = 10000）—— 应成功",
     "INSERT INTO account_ledger (owner_type,owner_id,biz_type,ref_no,direction,amount_fen,balance_before,balance_after)"
     " VALUES ('PLATFORM',9,'ADJUST','ADJ_X3','IN',10000,0,10000);", "ok")
case("X4 合法 OUT 行（10000 - 4000 = 6000）—— 应成功",
     "INSERT INTO account_ledger (owner_type,owner_id,biz_type,ref_no,direction,amount_fen,balance_before,balance_after)"
     " VALUES ('PLATFORM',9,'ADJUST','ADJ_X4','OUT',4000,10000,6000);", "ok")
case("X5 佣金台账同样的算式不自洽 —— 应被拒",
     f"INSERT INTO commission_ledger (agent_id,biz_type,ref_no,direction,amount_fen,balance_before,balance_after)"
     f" VALUES ({a1},'SETTLE','CL_X5','IN',5100,0,88888);", "fail", "ck_comm_ledger_arith")
case("X6 佣金台账 OUT 到负余额（退款冲正，业务上合法）—— 应成功",
     f"INSERT INTO commission_ledger (agent_id,biz_type,ref_no,direction,amount_fen,balance_before,balance_after)"
     f" VALUES ({a1},'OFFSET','CL_X6','OUT',5100,0,-5100);", "ok")


# =========================================================================
section("Y · 支付与退款（「激活即不退」的钱侧落点）")
# =========================================================================
mysql("UPDATE biz_order SET status='PAID',paid_amount_fen=349000,paid_at=NOW(3) WHERE id=1;")
case("Y1 支付流水金额为 0 —— 应被拒",
     "INSERT INTO payment_record (order_id,order_no,provider,provider_txn_id,amount_fen,status,paid_at)"
     " VALUES (1,'ORD001','WECHAT','WX_ZERO',0,'SUCCESS',NOW(3));", "fail", "ck_payment_amount")
case("Y2 支付流水金额为负 —— 应被拒",
     "INSERT INTO payment_record (order_id,order_no,provider,provider_txn_id,amount_fen,status,paid_at)"
     " VALUES (1,'ORD001','WECHAT','WX_NEG',-100,'SUCCESS',NOW(3));", "fail", "ck_payment_amount")
case("Y3 退款金额为 0 —— 应被拒",
     "INSERT INTO refund_record (refund_no,order_id,amount_fen,scope,applied_by)"
     " VALUES ('RF_ZERO',1,0,'MANUAL',1);", "fail", "ck_refund_amount")

mysql("INSERT INTO refund_record (id,refund_no,order_id,amount_fen,scope,applied_by,status)"
      " VALUES (50,'RF050',1,10000,'MANUAL',1,'SUCCESS'),"
      "        (51,'RF051',1,10000,'MANUAL',1,'REJECTED');")
case("Y4 已成功的退款单退回待申请 —— 应被拒（可重复退款）",
     "UPDATE refund_record SET status='APPLIED' WHERE id=50;", "fail", "终结")
case("Y5 已驳回的退款单改成成功（绕过审批把钱退出去）—— 应被拒",
     "UPDATE refund_record SET status='SUCCESS' WHERE id=51;", "fail", "终结")

# 订单 30：出了 2 个码，其中 1 个已被学员激活
mysql(f"""INSERT INTO biz_order (id,order_no,buyer_user_id,buyer_type,buyer_agent_id,buyer_agent_level,
 sku_id,sku_code_snapshot,code_qty_snapshot,amount_fen,paid_amount_fen,status,paid_at,expire_at)
 VALUES (30,'ORD030',3,'AGENT',{b2},2,2,'AGENT_10',2,60000,60000,'FULFILLED',NOW(3),'2030-01-01');
INSERT INTO activation_code (id,code_hash,code_enc,code_masked,course_id,owner_agent_id,order_id,
 activate_deadline,status,bound_user_id,bound_at) VALUES
 (30,REPEAT('3',64),UNHEX('00'),'M1',1,{b2},30,'2030-01-01','BOUND',600,NOW(3)),
 (31,REPEAT('4',64),UNHEX('00'),'M2',1,{b2},30,'2030-01-01','ISSUED',NULL,NULL);""")
case("Y6 订单里已有码被激活，仍申请整单全额退款 —— 应被拒（录音 01:50 激活即不退）",
     "INSERT INTO refund_record (refund_no,order_id,amount_fen,scope,applied_by)"
     " VALUES ('RF_FULL',30,60000,'FULL',1);", "fail", "激活即不退")
case("Y7 只退该单里未激活的那一个码 —— 应成功（G8）",
     "INSERT INTO refund_record (refund_no,order_id,amount_fen,scope,applied_by)"
     " VALUES ('RF_PART',30,30000,'UNUSED_CODES',1);", "ok")
case("Y8 退款单走完 APPLIED→APPROVED→SUCCESS —— 应成功（正例，别把合法路径也堵死）",
     "INSERT INTO refund_record (refund_no,order_id,amount_fen,scope,applied_by)"
     " VALUES ('RF_B',30,30000,'UNUSED_CODES',1);"
     "UPDATE refund_record SET status='APPROVED',approved_by=1 WHERE refund_no='RF_B';"
     "UPDATE refund_record SET status='SUCCESS',finished_at=NOW(3) WHERE refund_no='RF_B';", "ok")
case("Y9 该单实付 60000 已被两张退款单占满，再退 100 分 —— 应被拒（在途退款也占额度）",
     "INSERT INTO refund_record (refund_no,order_id,amount_fen,scope,applied_by)"
     " VALUES ('RF_C',30,100,'MANUAL',1);", "fail", "在途退款")


# =========================================================================
section("Z · 身份与跨表一致性（分销系统最经典的几种薅法）")
# =========================================================================
mysql("INSERT INTO sys_user (id,phone,role) VALUES (601,'13000000601','STUDENT');")
case("Z1 给 STUDENT 角色的账号建代理商档案 —— 应被拒",
     "INSERT INTO agent_profile (user_id,agent_code,level,status) VALUES (601,'Z1',1,'ACTIVE');", "fail", "AGENT 角色")

mysql("INSERT INTO sys_user (id,phone,role) VALUES (602,'13000000602','AGENT');")
mysql("INSERT INTO agent_profile (id,user_id,agent_code,level,status) VALUES (60,602,'Z2',1,'ACTIVE');")
case("Z2 代理把自己的归属代理设成自己（自购返佣）—— 应被拒",
     "UPDATE sys_user SET attributed_agent_id=60,attributed_at=NOW(3),attribution_source='REF_LINK'"
     " WHERE id=602;", "fail", "自购返佣")

case("Z3 权益来源写激活码却没有激活码 id —— 应被拒（对不上账）",
     "INSERT INTO enrollment (user_id,course_id,source,valid_from,valid_until,agreement_version,agreed_at)"
     " VALUES (601,1,'ACTIVATION_CODE',NOW(3),'2030-01-01','v1',NOW(3));", "fail", "ck_enrollment_source")
case("Z4 权益来源写直购却没有订单 id —— 应被拒",
     "INSERT INTO enrollment (user_id,course_id,source,valid_from,valid_until,agreement_version,agreed_at)"
     " VALUES (601,1,'DIRECT_PURCHASE',NOW(3),'2030-01-01','v1',NOW(3));", "fail", "ck_enrollment_source")
case("Z5 码绑给 600，权益却开给 601 —— 应被拒（一码一人的另一半）",
     "INSERT INTO enrollment (user_id,course_id,source,activation_code_id,valid_from,valid_until,"
     "agreement_version,agreed_at) VALUES (601,1,'ACTIVATION_CODE',30,NOW(3),'2030-01-01','v1',NOW(3));",
     "fail", "本人")
case("Z6 该订单快照只出 2 个码，发第 3 个 —— 应被拒（多发一个就是白送一门课）",
     f"INSERT INTO activation_code (code_hash,code_enc,code_masked,course_id,owner_agent_id,order_id,"
     f"activate_deadline) VALUES (REPEAT('5',64),UNHEX('00'),'M3',1,{b2},30,'2030-01-01');", "fail", "白送")
case("Z7 激活码挂到不存在的订单 —— 应被拒（无外键，这条只能靠触发器）",
     f"INSERT INTO activation_code (code_hash,code_enc,code_masked,course_id,owner_agent_id,order_id,"
     f"activate_deadline) VALUES (REPEAT('6',64),UNHEX('00'),'M4',1,{b2},999999,'2030-01-01');",
     "fail", "存在的订单")


# =========================================================================
section("AA · 在途提现占用额度（余额够，不代表还没被别的单占着）")
# =========================================================================
mysql(f"UPDATE agent_balance SET commission_balance_fen=100000 WHERE agent_id={a1};")
mysql(f"DELETE FROM withdraw_order WHERE agent_id={a1};")
case("AA1 余额 100000，申请提现 60000 —— 应成功",
     f"INSERT INTO withdraw_order (id,withdraw_no,agent_id,amount_fen,channel,payee_name,payee_account)"
     f" VALUES (60,'W060',{a1},60000,'BANK_TRANSFER','张三','6222');", "ok")
case("AA2 同一余额再申请 60000（前一笔在途未扣）—— 应被拒，否则 100000 能提出 120000",
     f"INSERT INTO withdraw_order (withdraw_no,agent_id,amount_fen,channel,payee_name,payee_account)"
     f" VALUES ('W061',{a1},60000,'BANK_TRANSFER','张三','6222');", "fail", "在途")
case("AA3 撤销第一笔后再申请 60000 —— 应成功（终结态不占额度）",
     "UPDATE withdraw_order SET status='CANCELLED' WHERE id=60;"
     f"INSERT INTO withdraw_order (withdraw_no,agent_id,amount_fen,channel,payee_name,payee_account)"
     f" VALUES ('W062',{a1},60000,'BANK_TRANSFER','张三','6222');", "ok")


# =========================================================================
section("AB · 剩余边界（答疑配额、统计口径、证书、发票）")
# =========================================================================
case("AB1 AI 置信度 9.999（应在 0~1）—— 应被拒",
     "UPDATE qa_ticket SET ai_confidence=9.999 WHERE id=1;", "fail", "ck_qa_confidence")
case("AB2 答疑配额计数为负 —— 应被拒",
     "INSERT INTO qa_quota_usage (user_id,stat_date,ask_count) VALUES (600,'2026-08-10',-1);", "fail", "ck_qa_quota")
case("AB3 统计月份写成 'ABCDEFG' —— 应被拒（CHAR(7) 装得下，不代表它是个月份）",
     f"INSERT INTO agent_monthly_stat (agent_id,stat_month) VALUES ({a1},'ABCDEFG');", "fail", "ck_stat_month")
case("AB4 统计月份 '2026-13'（不存在的月）—— 应被拒",
     f"INSERT INTO agent_monthly_stat (agent_id,stat_month) VALUES ({a1},'2026-13');", "fail", "ck_stat_month")
case("AB5 结业证明的吊销时间早于签发时间 —— 应被拒",
     "UPDATE certificate SET revoked_at='2020-01-01' WHERE cert_no='C001';", "fail", "ck_cert_revoke")
case("AB6 开票金额为 0 —— 应被拒",
     "INSERT INTO invoice_request (applicant_user_id,scope,ref_id,title,amount_fen)"
     " VALUES (3,'ORDER',77,'某公司',0);", "fail", "ck_invoice_amount")


# =========================================================================
section("AC · 上架前水印闸门（错在这一刻就补不回来了）")
# =========================================================================
# 上架是视频第一次对学员可见的那一刻。此刻布局若是错的，从这一刻起投递出去的
# 每一份副本都不可取证，而且补不回来 —— 流出去的东西没法追加水印。
#
# 2026-08-21 之后闸门的判据换了：讲次不再等于一整场录屏，而是片库里的一段片号区间，
# 码位是【片库内全局片号】的纯函数。于是"槽位数 = segment_count / stride"这条老式子
# 不再成立（区间长度不是 stride 的整数倍时会差 1，而差 1 就是整段码字错位一位），
# 改成按区间实算。下面每一条都先把别的前提配齐，好让它红在被测的那一条上。
#
# 片库：章 1 = oss/l1（600 片，码位 [0,100)），章 2 = oss/l2（600 片，码位 [100,200)）。
mysql("INSERT INTO lesson_segment_block (lesson_id,sort_no,seg_from,seg_to) VALUES "
      "(1,1,0,300),(2,1,0,300);")
mysql("UPDATE lesson SET status='DRAFT', segment_count=300, wm_slot_stride=6, "
      "wm_slot_count=50, wm_bit_offset=0, wm_bit_base=0 WHERE id=1;")
mysql("UPDATE lesson SET status='DRAFT', segment_count=300, wm_slot_stride=6, "
      "wm_slot_count=50, wm_bit_offset=100, wm_bit_base=100 WHERE id=2;")

case("AC1 没有现行码本就上架正片 —— 应被拒",
     "UPDATE lesson SET status='ON' WHERE id=1;", "fail", "还没有现行水印码本")

mysql("INSERT INTO wm_scheme (course_id,max_colluders,users_planned,eps1,bit_length,bias) "
      "VALUES (1,2,500,0.001,400,REPEAT(0x00,3200));")

# 讲次先建 DRAFT、灌完区间再置 ON —— 区间为空是最常见的"灌了一半"，必须当场拒。
mysql("DELETE FROM lesson_segment_block WHERE lesson_id=1;")
case("AC2 还没有分片区间就上架 —— 应被拒",
     "UPDATE lesson SET status='ON' WHERE id=1;", "fail", "还没有分片区间")
mysql("INSERT INTO lesson_segment_block (lesson_id,sort_no,seg_from,seg_to) VALUES (1,1,0,300);")

mysql("UPDATE lesson SET wm_slot_count=99 WHERE id=1;")
case("AC3 槽位数与区间覆盖到的槽位对不上（[0,300) 实为 50，却写了 99）—— 应被拒",
     "UPDATE lesson SET status='ON' WHERE id=1;", "fail", "对不上")

mysql("UPDATE lesson SET wm_slot_count=50, segment_count=299 WHERE id=1;")
case("AC3b segment_count 与区间长度对不上（区间 300 片，却写了 299）—— 应被拒",
     "UPDATE lesson SET status='ON' WHERE id=1;", "fail", "segment_count 与分片区间的长度对不上")

mysql("UPDATE lesson SET segment_count=300, wm_bit_offset=7 WHERE id=1;")
case("AC3c wm_bit_offset 与区间算出的首个码位对不上 —— 应被拒（取证会从错的位读起）",
     "UPDATE lesson SET status='ON' WHERE id=1;", "fail", "首个码位对不上")
mysql("UPDATE lesson SET wm_bit_offset=0 WHERE id=1;")

# 位区间超出码本长度：把章 2 的基点推到 360，则第二讲占 [360,410) 而码长只有 400。
mysql("UPDATE chapter SET wm_bit_base=360 WHERE id=2;")
mysql("UPDATE lesson SET wm_bit_base=360, wm_bit_offset=360 WHERE id=2;")
case("AC4 位区间超出码本长度（360+50 > 400）—— 应被拒",
     "UPDATE lesson SET status='ON' WHERE id=2;", "fail", "超出码本长度")

case("AC5 布局齐但没有内容密钥 —— 应被拒",
     "UPDATE lesson SET status='ON' WHERE id=1;", "fail", "尚未封装现行内容密钥")

# 这里只验证库层上架勾稽，密文的 GCM 解封与 AAD 绑讲次由 LearnIT 验证。
mysql("INSERT INTO lesson_content_key (lesson_id,course_id,key_version,key_enc,kek_id) "
      "VALUES (1,1,1,RANDOM_BYTES(44),'schema-test'),(2,1,1,RANDOM_BYTES(44),'schema-test');")
case("AC6 布局与内容密钥都齐了 —— 应成功",
     "UPDATE lesson SET status='ON' WHERE id=1;", "ok")

# 位区间重叠。现在它只可能来自【章的基点分错】—— 同一章内各讲的区间不重叠，
# 码位就一定不重叠；分错基点的两章才会把两段码位叠在一起。
mysql("UPDATE chapter SET wm_bit_base=25 WHERE id=2;")
mysql("UPDATE lesson SET wm_bit_base=25, wm_bit_offset=25 WHERE id=2;")
case("AC7 与另一讲位区间重叠（[0,50) 与 [25,75)）—— 应被拒",
     "UPDATE lesson SET status='ON' WHERE id=2;", "fail", "重叠")

mysql("UPDATE chapter SET wm_bit_base=100 WHERE id=2;")
mysql("UPDATE lesson SET wm_bit_base=100, wm_bit_offset=100 WHERE id=2;")
case("AC8 区间挪开不重叠 —— 应成功",
     "UPDATE lesson SET status='ON' WHERE id=2;", "ok")

# 直接以 ON 插入是最容易绕过闸门的一条路
case("AC9 直接以 ON 插入没有分片区间的讲次 —— 应被拒",
     "INSERT INTO lesson (chapter_id,course_id,title,sort_no,duration_sec,hls_key,status) "
     "VALUES (1,1,'偷跑的讲',9,3600,'oss/l1','ON');", "fail", "还没有分片区间")

case("AC10 试听片段豁免（公开物料，不带水印是有意的）—— 应成功",
     "INSERT INTO lesson (chapter_id,course_id,title,sort_no,duration_sec,hls_key,status,is_preview) "
     "VALUES (1,1,'试听',10,120,'oss/preview','ON',1);", "ok")

# 讲次的 hls_key 与所在章对不上：播放列表会去另一个目录取片，那里同名的片是
# 【别的内容】—— 播出来是另一节课，而这不会报错。
mysql("INSERT INTO lesson (id,chapter_id,course_id,title,sort_no,duration_sec,hls_key,"
      "segment_count,wm_slot_count,wm_bit_offset,wm_bit_base) "
      "VALUES (7,1,1,'串库的讲',11,3000,'oss/l2',300,50,0,0);")
mysql("INSERT INTO lesson_segment_block (lesson_id,sort_no,seg_from,seg_to) VALUES (7,1,300,600);")
mysql("UPDATE lesson SET wm_bit_offset=50 WHERE id=7;")
case("AC11 讲次的片库目录与所在章不一致 —— 应被拒",
     "UPDATE lesson SET status='ON' WHERE id=7;", "fail", "片库目录与所在章不一致")

mysql("UPDATE lesson SET hls_key='oss/l1', wm_bit_base=99 WHERE id=7;")
case("AC12 讲次的 wm_bit_base 与所在章不一致 —— 应被拒（算出的码位会整体错位）",
     "UPDATE lesson SET status='ON' WHERE id=7;", "fail", "wm_bit_base 与所在章不一致")

# 同一片库的各讲共用同一批分片，内容密钥必须逐字节相同。只给一讲换了密钥，
# 另外几讲会拿错的 key 解出垃圾字节 —— 播放器报的是解码失败或干脆花屏，不是"密钥错"。
mysql("UPDATE lesson SET wm_bit_base=0 WHERE id=7;")
mysql("INSERT INTO lesson_content_key (lesson_id,course_id,key_version,key_enc,kek_id) "
      "VALUES (7,1,1,RANDOM_BYTES(44),'schema-test');")
case("AC13 同一片库下两讲的内容密钥不同 —— 应被拒",
     "UPDATE lesson SET status='ON' WHERE id=7;", "fail", "内容密钥不一致")

mysql("UPDATE lesson_content_key k JOIN lesson_content_key k1 ON k1.lesson_id=1 "
      "SET k.key_enc=k1.key_enc WHERE k.lesson_id=7;")
case("AC14 同一片库下密钥改成一致 —— 应成功",
     "UPDATE lesson SET status='ON' WHERE id=7;", "ok")


# =========================================================================
section("AD · 片库分片区间（一讲 = 片库里的一段片号；灌错全是安静地不对）")
# =========================================================================
# 章 3 的片库故意取 604 片 —— 不是 6 的整数倍。这不是为了刁难：生产上的
# fde/l7 就是 1060 片，而它最后那个"看着像槽位"的第 1059 片在磁盘上只有
# 1059.ts，没有 _a/_b。照公式推会去要 1059_a.ts → 404 → hls.js 归成
# NETWORK_ERROR 无限重试：最后一讲播到结尾画面就停住，一句错都不报。
mysql("INSERT INTO chapter (id,course_id,title,hls_key,store_segment_count,wm_bit_base,sort_no) "
      "VALUES (3,1,'第三章','oss/l3',604,200,3),(4,1,'第四章','oss/l4',600,300,4);")
# 空白片库上的一讲，专门用来验区间本身的约束（不受别的讲已占区间干扰）
mysql("INSERT INTO lesson (id,chapter_id,course_id,title,sort_no,duration_sec,hls_key) "
      "VALUES (10,4,1,'空白片库的讲',14,600,'oss/l4');")

case("AD1 区间超出片库（[0,700) 而片库只有 600 片）—— 应被拒",
     "INSERT INTO lesson_segment_block (lesson_id,sort_no,seg_from,seg_to) VALUES (10,9,0,700);",
     "fail", "超出片库")
case("AD2 区间倒着写（seg_to <= seg_from）—— 应被拒",
     "INSERT INTO lesson_segment_block (lesson_id,sort_no,seg_from,seg_to) VALUES (10,9,300,300);",
     "fail", "ck_block_range")
case("AD3 起点为负 —— 应被拒",
     "INSERT INTO lesson_segment_block (lesson_id,sort_no,seg_from,seg_to) VALUES (10,9,-5,-1);",
     "fail", "ck_block_range")
case("AD4 同一片库里两讲抢同一片（第一讲已占 [0,300)）—— 应被拒",
     "INSERT INTO lesson_segment_block (lesson_id,sort_no,seg_from,seg_to) VALUES (7,9,250,320);",
     "fail", "重叠")
case("AD5 别的片库里的同一段片号 —— 应成功（章不同，是两批完全不同的分片）",
     "INSERT INTO lesson_segment_block (lesson_id,sort_no,seg_from,seg_to) VALUES (10,1,0,10);", "ok")
case("AD6 同一讲再加两段区间（中间跳掉的是剪去的课间）—— 应成功",
     "INSERT INTO lesson_segment_block (lesson_id,sort_no,seg_from,seg_to) "
     "VALUES (10,2,40,90),(10,3,120,150);", "ok")
case("AD7 同一讲两段区间的 sort_no 撞车 —— 应被拒（播放顺序会随查询计划变）",
     "INSERT INTO lesson_segment_block (lesson_id,sort_no,seg_from,seg_to) VALUES (10,2,500,510);",
     "fail", "uk_block_sort")

# ★ 这一条就是把老式子（槽位数 = segment_count / stride）换掉的理由。
# [0,299) 是 299 片，里面 g ≡ 3 (mod 6) 的有 3,9,…,297 共 50 个；
# 而 FLOOR(299/6) = 49。差的这一位不会报错，只会让整段码字从此错位一位。
mysql("INSERT INTO lesson (id,chapter_id,course_id,title,sort_no,duration_sec,hls_key,"
      "segment_count,wm_slot_stride,wm_slot_count,wm_bit_offset,wm_bit_base) "
      "VALUES (8,3,1,'半片区间的讲',12,2990,'oss/l3',299,6,49,200,200);")
mysql("INSERT INTO lesson_segment_block (lesson_id,sort_no,seg_from,seg_to) VALUES (8,1,0,299);")
mysql("INSERT INTO lesson_content_key (lesson_id,course_id,key_version,key_enc,kek_id) "
      "VALUES (8,1,1,RANDOM_BYTES(44),'schema-test');")
case("AD8 区间长度不是步长整数倍时照老式子填槽位数（299/6=49，实为 50）—— 应被拒",
     "UPDATE lesson SET status='ON' WHERE id=8;", "fail", "对不上")
mysql("UPDATE lesson SET wm_slot_count=50 WHERE id=8;")
case("AD8b 按区间实算填 50 —— 应成功",
     "UPDATE lesson SET status='ON' WHERE id=8;", "ok")

# ★ 片库尾部那个不完整的步长：第 603 片 603 % 6 == 3 看着像槽位，
# 但 603 DIV 6 = 100 已经到了容量上界（604 DIV 6 = 100），磁盘上没有 603_a.ts。
# 于是这一讲一个槽位都没有 —— 投出去的副本无法取证，必须拦在上架这一刻。
mysql("INSERT INTO lesson (id,chapter_id,course_id,title,sort_no,duration_sec,hls_key,"
      "segment_count,wm_slot_stride,wm_slot_count,wm_bit_offset,wm_bit_base) "
      "VALUES (9,3,1,'只剩尾巴的讲',13,40,'oss/l3',4,6,0,250,200);")
mysql("INSERT INTO lesson_segment_block (lesson_id,sort_no,seg_from,seg_to) VALUES (9,1,600,604);")
mysql("INSERT INTO lesson_content_key (lesson_id,course_id,key_version,key_enc,kek_id) "
      "VALUES (9,1,1,RANDOM_BYTES(44),'schema-test');")
case("AD9 片库尾部不完整步长里那片不算槽位（603 看着像，磁盘上没有 603_a.ts）—— 应被拒",
     "UPDATE lesson SET status='ON' WHERE id=9;", "fail", "一个水印槽位都没有")


# =========================================================================
section("AS · 讲次内容分节（目录灌错的表现全是「安静地不对」）")
# =========================================================================
# 目录是靠导入脚本灌进去的，而灌错的三种方式没有一种会当场看出来：
#   · end_sec 超出讲次时长 → 学员点进去 seek 到视频之外，播放器什么都不做也不报错
#   · 两节区间重叠        → 同一时刻属于两节，进度算出来两节都「看过」或都「没看」
#   · sort_no 撞车        → 目录顺序随查询计划变，今天这样明天那样
# 所以三条分别落在触发器、触发器、唯一键上。这一节逐条正反验一遍。
mysql("DELETE FROM lesson_section;")

case("AS1 正常一节（讲次 1 长 3600 秒）—— 应成功",
     "INSERT INTO lesson_section (lesson_id,sort_no,title,kind,start_sec,end_sec) "
     "VALUES (1,1,'开场','LECTURE',0,600);", "ok")

case("AS2 首尾相接的下一节 —— 应成功（相接不算重叠，否则整讲根本铺不满）",
     "INSERT INTO lesson_section (lesson_id,sort_no,title,kind,start_sec,end_sec) "
     "VALUES (1,2,'第二节','DEMO',600,1200);", "ok")

case("AS3 与已有区间重叠 —— 应被拒",
     "INSERT INTO lesson_section (lesson_id,sort_no,title,kind,start_sec,end_sec) "
     "VALUES (1,3,'压上来的','LECTURE',900,1500);", "fail", "重叠")

# 只在 INSERT 上设闸的话，先插一个不重叠的、再 UPDATE 成重叠就整条绕过去了
case("AS4 改成重叠 —— 应被拒（更新侧也要有闸）",
     "INSERT INTO lesson_section (lesson_id,sort_no,title,kind,start_sec,end_sec) "
     "VALUES (1,3,'先不重叠','LECTURE',1200,1800); "
     "UPDATE lesson_section SET start_sec=700 WHERE lesson_id=1 AND sort_no=3;",
     "fail", "重叠")

case("AS5 区间超出讲次时长 —— 应被拒",
     "INSERT INTO lesson_section (lesson_id,sort_no,title,kind,start_sec,end_sec) "
     "VALUES (1,9,'越界','LECTURE',3000,3601);", "fail", "超出讲次时长")

case("AS6 正好收在片尾 —— 应成功（否则最后一节永远没法收口）",
     "INSERT INTO lesson_section (lesson_id,sort_no,title,kind,start_sec,end_sec) "
     "VALUES (1,9,'收尾','LECTURE',3000,3600);", "ok")

case("AS7 挂到不存在的讲次上 —— 应被拒",
     "INSERT INTO lesson_section (lesson_id,sort_no,title,kind,start_sec,end_sec) "
     "VALUES (999999,1,'野节','LECTURE',0,60);", "fail", "讲次不存在")

case("AS8 同一讲 sort_no 撞车 —— 应被拒",
     "INSERT INTO lesson_section (lesson_id,sort_no,title,kind,start_sec,end_sec) "
     "VALUES (1,1,'又一个第 1 节','LECTURE',1800,1900);", "fail", "Duplicate")

# 用 2000 这个空当，不用 900 —— 900 落在第 2 节 [600,1200) 里，
# 会先被重叠触发器拒掉，于是这条用例看起来是绿的，而 CHECK 约束一次都没被验过。
case("AS9 空区间（start == end）—— 应被拒",
     "INSERT INTO lesson_section (lesson_id,sort_no,title,kind,start_sec,end_sec) "
     "VALUES (1,20,'空的','LECTURE',2000,2000);", "fail", "ck_section_range")

case("AS9b 反向区间（end < start）—— 应被拒",
     "INSERT INTO lesson_section (lesson_id,sort_no,title,kind,start_sec,end_sec) "
     "VALUES (1,21,'反的','LECTURE',2500,2400);", "fail", "ck_section_range")

# 取 [-100,-50) 而不是 [-1,100)：后者会先撞上第 1 节 [0,600) 的重叠闸，
# 于是这条用例照样绿，而「起点不许为负」那一条一次都没被验过。
# 负区间在业务上不可能出现，这里测的是【约束本身写对了没有】。
case("AS9c 负数起点 —— 应被拒",
     "INSERT INTO lesson_section (lesson_id,sort_no,title,kind,start_sec,end_sec) "
     "VALUES (1,22,'负的','LECTURE',-100,-50);", "fail", "ck_section_range")

# 重叠只在【同一讲】之内判。跨讲同一时间段是正常的：每一讲都从 0 秒开始。
case("AS10 另一讲的同一时间段 —— 应成功（重叠只在讲内判）",
     "INSERT INTO lesson_section (lesson_id,sort_no,title,kind,start_sec,end_sec) "
     "VALUES (2,1,'第二讲的开场','LECTURE',0,600);", "ok")

mysql("DELETE FROM lesson_section;")


# =========================================================================
section("AF · 超管手工发码（白送不可能变成收入）")
# =========================================================================
# 超管人工发给代理的赠送/补发码，走的是一张 origin='ADMIN_GIFT' 的合成订单：
# amount_fen 记目录价（ck_order_amount 逼着 > 0），而实付恒 0、到账时间恒空、
# 不产生佣金、不写支付流水。
#
# 这四条如果只写在 Java 里，失效的方式全是**不可逆且无症状**的：
#   · 佣金一旦误产生，冻结期满自动进 agent_balance，此后与真佣金不可分辨；
#     而白送单 paid_amount_fen=0 连 refund_record 都插不进去，撤回路径为零。
#   · 「先建白送单、事后偷偷标已付把它洗成收入」——CHECK 在 UPDATE 时对整行求值，
#     所以两侧一起堵，这一节 AF3/AF4 各验一侧。
#   · confirm-transfer 打在白送单上是一条**真通路**（见 trg_payment_precheck 的注释），
#     它的表现是一条 SUCCESS 的假流水落库而总览四项纹丝不动。
#
# 另外 trg_order_insert 这一轮是 DROP+CREATE 改体的，所以 AF13/AF14 专门回头验
# 它原来那两条判据还在——改体的丢失不会有任何报错，只是闸门少了一条。
mysql("DELETE FROM biz_order WHERE origin <> 'SELF_SERVE';")

GIFT = ("INSERT INTO biz_order (id,order_no,buyer_user_id,buyer_type,buyer_agent_id,"
        "buyer_agent_level,sku_id,sku_code_snapshot,code_qty_snapshot,amount_fen,"
        "status,expire_at,origin,grant_by,grant_reason,grant_key) VALUES ")

case("AF1 合法的白送单（已发码 + 实付 0 + 三样齐全）—— 应成功",
     GIFT + f"(700,'GIFT700',2,'AGENT',{a1},1,1,'AGENT_1',1,34900,"
            "'FULFILLED',DATE_ADD(NOW(3), INTERVAL 2 MINUTE),'ADMIN_GIFT',1,'补发','K700');", "ok")

case("AF2 白送单以「待支付」建出来 —— 应被拒（代理会看见一个「去付款」按钮）",
     GIFT + f"(701,'GIFT701',2,'AGENT',{a1},1,1,'AGENT_1',1,34900,"
            "'CREATED',DATE_ADD(NOW(3), INTERVAL 2 MINUTE),'ADMIN_GIFT',1,'补发','K701');",
     "fail", "必须直接落到已发码")

case("AF3 白送单带实付金额 —— 应被拒（一次白送被记成一笔真实收入）",
     GIFT.replace("amount_fen,", "amount_fen,paid_amount_fen,")
         + f"(702,'GIFT702',2,'AGENT',{a1},1,1,'AGENT_1',1,34900,34900,"
           "'FULFILLED',DATE_ADD(NOW(3), INTERVAL 2 MINUTE),'ADMIN_GIFT',1,'补发','K702');",
     "fail", "ck_order_gift_unpaid")

# 只在 INSERT 上设闸的话，先建一张干净的白送单、事后 UPDATE 成已付，就把它洗成收入了
case("AF4 事后把白送单改成已付 —— 应被拒（更新侧也要有闸）",
     "UPDATE biz_order SET paid_amount_fen=34900, paid_at=NOW(3) WHERE id=700;",
     "fail", "ck_order_gift_unpaid")

case("AF5 白送单不记操作人与原因 —— 应被拒（事后复盘只有这一句）",
     GIFT.replace(",grant_by,grant_reason,grant_key", "")
         + f"(703,'GIFT703',2,'AGENT',{a1},1,1,'AGENT_1',1,34900,"
           "'FULFILLED',DATE_ADD(NOW(3), INTERVAL 2 MINUTE),'ADMIN_GIFT');",
     "fail", "ck_order_grant_by")

# 金额取一个合法的 >0 值：写 0 的话先被 ck_commission_amount 拦下，
# 用例照样绿，而新触发器一次都没被验过
case("AF6 给白送单结佣金 —— 应被拒（没有收到的钱不能返出去）",
     f"INSERT INTO commission_record (beneficiary_agent_id,source_agent_id,order_id,order_no,"
     f"rule_id,calc_mode_snapshot,calc_basis_fen,amount_fen,freeze_until) "
     f"VALUES ({a1},{b2},700,'GIFT700',1,'FIXED_PER_CODE',34900,5000,'2030-01-01');",
     "fail", "不产生佣金")

case("AF7 AF6 的阴性对照：同一段 SQL 挂在自助单上 —— 应成功",
     f"INSERT INTO biz_order (id,order_no,buyer_user_id,buyer_type,buyer_agent_id,buyer_agent_level,"
     f"sku_id,sku_code_snapshot,code_qty_snapshot,amount_fen,paid_amount_fen,status,paid_at,expire_at)"
     f" VALUES (704,'ORD704',3,'AGENT',{b2},2,1,'AGENT_1',1,39900,39900,'FULFILLED',NOW(3),'2030-01-01');"
     f"INSERT INTO commission_record (beneficiary_agent_id,source_agent_id,order_id,order_no,"
     f"rule_id,calc_mode_snapshot,calc_basis_fen,amount_fen,freeze_until) "
     f"VALUES ({a1},{b2},704,'ORD704',1,'FIXED_PER_CODE',39900,5000,'2030-01-01');", "ok")

case("AF8 给白送单写支付流水 —— 应被拒（confirm-transfer 打上来的那条真通路）",
     "INSERT INTO payment_record (order_id,order_no,provider,provider_txn_id,amount_fen,status,paid_at)"
     " VALUES (700,'GIFT700','BANK_TRANSFER','FAKE700',34900,'SUCCESS',NOW(3));",
     "fail", "不能有支付流水")

case("AF9 AF8 的阴性对照：同一段 SQL 挂在自助单上 —— 应成功",
     "INSERT INTO payment_record (order_id,order_no,provider,provider_txn_id,amount_fen,status,paid_at)"
     " VALUES (704,'ORD704','BANK_TRANSFER','REAL704',39900,'SUCCESS',NOW(3));", "ok")

case("AF10 同一个幂等键发两批 —— 第二批应被拒（后台按钮会被双击）",
     GIFT + f"(705,'GIFT705',2,'AGENT',{a1},1,1,'AGENT_1',1,34900,"
            "'FULFILLED',DATE_ADD(NOW(3), INTERVAL 2 MINUTE),'ADMIN_GIFT',1,'补发','K700');",
     "fail", "uk_grant_key")

# 做成「自助单只能有一张」的话全站下单从第二笔起全挂 —— 很吵，但没有这条正例，
# 会在上线那一刻才发现
case("AF11 两张 grant_key 为空的自助单 —— 都应成功（多个 NULL 不参与唯一性）",
     "INSERT INTO biz_order (id,order_no,buyer_user_id,buyer_type,sku_id,sku_code_snapshot,"
     "code_qty_snapshot,amount_fen,expire_at) VALUES "
     "(706,'ORD706',4,'STUDENT',3,'STUDENT_DIRECT',1,79900,'2030-01-01'),"
     "(707,'ORD707',4,'STUDENT',3,'STUDENT_DIRECT',1,79900,'2030-01-01');", "ok")

case("AF12 白送单留一个 30 分钟的支付窗口 —— 应被拒（它永远不会被支付）",
     GIFT + f"(708,'GIFT708',2,'AGENT',{a1},1,1,'AGENT_1',1,34900,"
            "'FULFILLED',DATE_ADD(NOW(3), INTERVAL 30 MINUTE),'ADMIN_GIFT',1,'补发','K708');",
     "fail", "支付窗口")

# ck_order_grant_by 写成 `origin='SELF_SERVE' OR (三列非空)` 的话，origin 一是自助
# 整条约束就短路了 —— 一张真实付款的订单可以带着完整的「平台发放」签名落库。
# 钱没错，但台账上的来源是假的；更实在的是它会在 uk_grant_key 上抢注掉将来某次
# 手工发码的幂等键，表现是发码报 500 而不是幂等返回。
# 这条是【反向】判据：AF5 只钉住了「白送单必须签名」。
case("AF15 自助单带上「平台发放」签名 —— 应被拒（发放签名与 origin 必须两向一致）",
     "INSERT INTO biz_order (id,order_no,buyer_user_id,buyer_type,sku_id,sku_code_snapshot,"
     "code_qty_snapshot,amount_fen,expire_at,origin,grant_by,grant_reason,grant_key) VALUES "
     "(711,'ORD711',4,'STUDENT',3,'STUDENT_DIRECT',1,79900,'2030-01-01',"
     "'SELF_SERVE',1,'看起来像补发','K711');",
     "fail", "ck_order_grant_by")

# 三列里只带一列同样不行 —— 抢注 uk_grant_key 只需要 grant_key 这一列
case("AF16 自助单只带 grant_key —— 应被拒（一列就够抢注幂等键）",
     "INSERT INTO biz_order (id,order_no,buyer_user_id,buyer_type,sku_id,sku_code_snapshot,"
     "code_qty_snapshot,amount_fen,expire_at,grant_key) VALUES "
     "(712,'ORD712',4,'STUDENT',3,'STUDENT_DIRECT',1,79900,'2030-01-01','K712');",
     "fail", "ck_order_grant_by")

# 存量单一律走列默认（origin=SELF_SERVE、三列 NULL）。收紧后它们必须照样插得进去 ——
# 这条正例挡住的是「把约束写成两向一致时顺手要求自助单也填点什么」
case("AF17 收紧后的存量形状：不写任何 origin/grant_* 的自助单 —— 应成功",
     "INSERT INTO biz_order (id,order_no,buyer_user_id,buyer_type,sku_id,sku_code_snapshot,"
     "code_qty_snapshot,amount_fen,expire_at) VALUES "
     "(713,'ORD713',4,'STUDENT',3,'STUDENT_DIRECT',1,79900,'2030-01-01');", "ok")

# trg_order_insert 这一轮是 DROP+CREATE 改体的。下面两条验的是它原来那两条判据
# 还在 —— 改体时丢掉一条不会有任何报错，只是闸门少了一条。
case("AF13 改体回归：过去的 expire_at 仍然被拒",
     "INSERT INTO biz_order (id,order_no,buyer_user_id,buyer_type,sku_id,sku_code_snapshot,"
     "code_qty_snapshot,amount_fen,expire_at) VALUES "
     "(709,'ORD709',4,'STUDENT',3,'STUDENT_DIRECT',1,79900,'2020-01-01');",
     "fail", "订单过期时间")

# 代理档案只能挂在 AGENT 角色的账号上，所以先建账号
mysql("INSERT INTO sys_user (id,phone,role) VALUES (88,'13900000088','AGENT');")
case("AF14 改体回归：已暂停的代理仍然不能下单",
     "INSERT INTO agent_profile (user_id,agent_code,level,status) VALUES (88,'AFS1',1,'SUSPENDED');"
     "INSERT INTO biz_order (id,order_no,buyer_user_id,buyer_type,buyer_agent_id,buyer_agent_level,"
     "sku_id,sku_code_snapshot,code_qty_snapshot,amount_fen,expire_at) VALUES "
     "(710,'ORD710',88,'AGENT',(SELECT id FROM agent_profile WHERE agent_code='AFS1'),1,"
     "1,'AGENT_1',1,34900,'2030-01-01');",
     "fail", "代理商不在有效状态")

mysql("DELETE FROM biz_order WHERE origin <> 'SELF_SERVE';")


# =========================================================================
section("W · 价目与佣金的恒等式（钱的口径只能有一套）")
# =========================================================================
# 装载真实 seed 的价目与佣金，核对「平台净收与谁来买无关」这条恒等式。
# 这一段测的不是 schema 而是【出货的那组数字】—— 价目表算错不会有任何报错，
# 只会在每一笔下级订单上悄悄多付或少付佣金。
mysql("DELETE FROM agent_price_tier; DELETE FROM commission_rule;")
seed = (HERE / "seed.sql").read_text(encoding="utf-8")
seed = seed.replace("`wangke`", f"`{DB}`").replace("USE `wangke`", f"USE `{DB}`")
# 只取价目与佣金两段，避免与已有测试数据撞主键
import re as _re
for stmt in _re.findall(r"INSERT INTO `(?:agent_price_tier|commission_rule)`.*?;", seed, _re.S):
    mysql(stmt)

INVARIANT = """
SELECT s.sku_code,
       p2.total_price_fen - r.fixed_per_code_fen * s.code_qty AS platform_net,
       p1.total_price_fen AS l1_price
FROM sku s
JOIN agent_price_tier p1 ON p1.sku_id=s.id AND p1.agent_level=1 AND p1.effective_to IS NULL
JOIN agent_price_tier p2 ON p2.sku_id=s.id AND p2.agent_level=2 AND p2.effective_to IS NULL
JOIN commission_rule  r  ON r.sku_id=s.id  AND r.buyer_level=2 AND r.effective_to IS NULL
WHERE s.buyer_type='AGENT';"""

rows = [l.split("\t") for l in query(INVARIANT).splitlines()[1:] if l.strip()]
case(f"W1 代理档位齐全（查到 {len(rows)} 个套餐的现行价与佣金）",
     "SELECT 1;" if len(rows) >= 2 else "SELECT * FROM nonexistent_table;", "ok")
for sku, net, l1 in rows:
    case(f"W2 {sku}：二级付款 − 佣金 = {int(net)/100:.0f} 元，应等于一级价 {int(l1)/100:.0f} 元",
         "SELECT 1;" if net == l1 else "SELECT * FROM nonexistent_table;", "ok")

case("W3 单买档一级二级不得同价（差价为 0 时一级拿不到佣金）—— 应有差价",
     "SELECT 1;" if query(
       "SELECT (SELECT total_price_fen FROM agent_price_tier WHERE agent_level=2 AND sku_id=1"
       " AND effective_to IS NULL) - (SELECT total_price_fen FROM agent_price_tier"
       " WHERE agent_level=1 AND sku_id=1 AND effective_to IS NULL);"
     ).splitlines()[-1] != "0" else "SELECT * FROM nonexistent_table;", "ok")

case("W4 每档一级价必须低于二级价 —— 应无倒挂",
     "SELECT 1;" if query(
       "SELECT COUNT(*) FROM agent_price_tier a JOIN agent_price_tier b"
       " ON a.sku_id=b.sku_id AND a.agent_level=1 AND b.agent_level=2"
       " AND a.effective_to IS NULL AND b.effective_to IS NULL"
       " WHERE a.total_price_fen >= b.total_price_fen;"
     ).splitlines()[-1] == "0" else "SELECT * FROM nonexistent_table;", "ok")

case("W5 批量单价必须低于单买单价（否则批量档没有存在意义）",
     "SELECT 1;" if query(
       "SELECT COUNT(*) FROM agent_price_tier p1 JOIN sku s1 ON s1.id=p1.sku_id"
       " JOIN agent_price_tier p10 ON p10.agent_level=p1.agent_level"
       " JOIN sku s10 ON s10.id=p10.sku_id AND s10.code_qty>1"
       " WHERE s1.code_qty=1 AND p1.effective_to IS NULL AND p10.effective_to IS NULL"
       " AND p10.total_price_fen/s10.code_qty >= p1.total_price_fen/s1.code_qty;"
     ).splitlines()[-1] == "0" else "SELECT * FROM nonexistent_table;", "ok")


# =========================================================================
section("AG · 代理专属价（层级价打底 + 单家覆盖）")
# =========================================================================
# 这一段跑在 W 段之后，用的就是 W 段刚装载的**真实 seed 价目与佣金规则**：
#   二级单买 399 元，佣金 50 元/码 → 平台净收 349 元
#   二级批量 3490 元，佣金 51 元/码 × 10 → 平台净收 2980 元
# 没有这套真规则，下面「净收为负必须被拒」那几条就是在一张空的佣金表上做的空断言 ——
# 佣金恒为 0 时任何正价的净收都 >= 0，闸门整个不会被求值而用例全绿。
#
# 本段守四件事：
#   ① uk_active_override 挡得住两条当前生效价（挡不住就是取价二义：同一家代理
#      下同一个套餐，两次下单可能按两个价成交，事后无法解释）；
#   ② 封口后能插新价（做不到的话「改价」只能 UPDATE 旧行，历史当场被改写）；
#   ③ ck_override_positive 挡得住 <= 0；
#   ④ ★ 净收为负必须被拒 —— 这条错全链路合法：订单金额 > 0、佣金正常结算、
#      对账表处处平，只有把「收到的钱」减去「付出的佣金」才看得见缺口，而那是月底的事。
mysql("DELETE FROM agent_price_override;")

OVR = ("INSERT INTO agent_price_override "
       "(agent_id,sku_id,total_price_fen,effective_from,reason,created_by) VALUES ")

case("AG1 给二级代理设一份专属价（300 元，高于 50 元佣金）—— 应成功",
     OVR + f"({b2},1,30000,'2026-09-01','谈过价：区域独家',1);", "ok")

case("AG2 同一家同一套餐再插一条当前生效价 —— 应被拒（否则取价二义，两次下单两个价）",
     OVR + f"({b2},1,28000,'2026-09-02','再改一次',1);", "fail", "uk_active_override")

case("AG3 旧价先封口再插新价 —— 应成功（这是改价的唯一正确姿势）",
     f"UPDATE agent_price_override SET effective_to='2026-09-02'"
     f" WHERE agent_id={b2} AND sku_id=1 AND effective_to IS NULL;"
     + OVR + f"({b2},1,28000,'2026-09-02','二次议价',1);", "ok")

# 价格写 0 而不是负数，是有意的：负数会先撞上净收那条闸（一级代理的佣金是 0，
# 净收 = -100 < 0），于是这条用例会被【另一条约束】拒掉而照样绿 —— 而 ck_override_positive
# 一次都没被求值。0 - 0 = 0 不算负，正好只剩 CHECK 能拦它。
# 同理挂在一级代理（a1）上而不是二级：二级的话 0 - 5000 < 0，还是净收闸先开火。
case("AG4 专属价 0 元 —— 应被拒（0 元进货在库层就造不出来）",
     OVR + f"({a1},1,0,'2026-09-01','白送',1);", "fail", "ck_override_positive")

# ★ 本段头号判据。40 元的专属价 < 50 元的佣金 ⇒ 平台每卖一单倒贴 10 元。
case("AG5 二级专属价低于返给上级的佣金 —— 应被拒（平台每卖一单倒贴，账面处处合法）",
     OVR + f"({b2},1,4000,'2026-09-03','老板说给个友情价',1);", "fail", "倒贴")

# 阴性对照。没有它的话，AG5 绿着只能证明「便宜的价会被拒」——
# 那样的闸门会把一级代理的正常降价也一起拒掉，而没有任何用例看得出来。
# 一级代理没有上级，这一单不返佣金，净收就是成交价本身，40 元完全合法。
case("AG6 AG5 的阴性对照：同样 40 元给一级代理 —— 应成功（一级没有上级，不返佣金）",
     OVR + f"({a1},2,4000,'2026-09-03','一级清库存价',1);", "ok")

case("AG7 专属价挂在不存在的代理上 —— 应被拒（悬空的专属价永远取不到，后台却显示已生效）",
     OVR + "(999999,1,30000,'2026-09-01','手滑填错了代理 id',1);",
     "fail", "不存在的代理")

# 老订单查得回原价，靠的就是「绝不 UPDATE 旧行」。原地改价在库层是一次完全合法的写入：
# 唯一键不动、CHECK 满意、行数正确，而按这个价成交过的每一张老订单，
# 再查「当时是什么价」查到的都是新价 —— 历史被改写，且没有一行日志说它被改过。
case("AG8 原地把专属价改成另一个数 —— 应被拒（老订单的原价会被静默改写）",
     f"UPDATE agent_price_override SET total_price_fen=19900"
     f" WHERE agent_id={b2} AND sku_id=1 AND effective_to IS NULL;",
     "fail", "不许原地改")

case("AG9 给当前生效行封口 —— 应成功（这是这张表上唯一允许的 UPDATE）",
     f"UPDATE agent_price_override SET effective_to=NOW(3)"
     f" WHERE agent_id={b2} AND sku_id=1 AND effective_to IS NULL;", "ok")

case("AG10 已封口的行再改生效区间 —— 应被拒（改区间等价于改历史价）",
     f"UPDATE agent_price_override SET effective_to='2027-01-01'"
     f" WHERE agent_id={b2} AND sku_id=1 AND effective_to='2026-09-02';",
     "fail", "已封口")

# 取价不二义：专属价与层级价各自最多一条当前生效，两侧都唯一时
# 「专属优先、没有则回落层级价」才是一个确定的结果。任一侧出现两行，
# 同一家代理的两次下单就可能按两个价成交。
mysql(OVR + f"({b2},2,300000,'2026-09-05','并存自检',1);")
_ovr_n = query(f"SELECT COUNT(*) FROM agent_price_override"
               f" WHERE agent_id={b2} AND sku_id=2 AND effective_to IS NULL;").splitlines()[-1]
_tier_n = query("SELECT COUNT(*) FROM agent_price_tier"
                " WHERE agent_level=2 AND sku_id=2 AND effective_to IS NULL;").splitlines()[-1]
case(f"AG11 专属价与层级价并存时两侧各只有一条当前生效（专属 {_ovr_n} 条 / 层级 {_tier_n} 条）",
     "SELECT 1;" if _ovr_n == "1" and _tier_n == "1" else "SELECT * FROM nonexistent_table;", "ok")

# 层级价那张老表上的同一道闸。它比专属价那条更要紧：专属价错了只坑一家，
# 层级价错了坑的是这一层的全部代理。两个触发器的判据是逐字同源的，
# 只改一处的表现是「专属价改不了、但层级价能改成同样的亏本数字」。
mysql("UPDATE agent_price_tier SET effective_to=NOW(3)"
      " WHERE sku_id=1 AND agent_level IN (1,2) AND effective_to IS NULL;")

case("AG12 二级层级价低于佣金 —— 应被拒（错一次坑的是整整一层代理）",
     "INSERT INTO agent_price_tier (agent_level,sku_id,total_price_fen,effective_from,created_by)"
     " VALUES (2,1,4000,'2026-09-03',1);", "fail", "倒贴")

case("AG13 AG12 的阴性对照：同样 40 元设成一级层级价 —— 应成功（一级不返佣金）",
     "INSERT INTO agent_price_tier (agent_level,sku_id,total_price_fen,effective_from,created_by)"
     " VALUES (1,1,4000,'2026-09-03',1);", "ok")

# 订单的取价来源只能有一个。两个指针都写上的话，复盘按哪一个取都算「有依据」，
# 而它们指向两张表里两个不同的金额 —— 同一张单会得出两个不同的「当时的价」。
case("AG14 订单同时写两个取价来源 —— 应被拒",
     "INSERT INTO biz_order (order_no,buyer_user_id,buyer_type,sku_id,sku_code_snapshot,"
     "code_qty_snapshot,price_tier_id,price_override_id,amount_fen,expire_at) VALUES "
     "('ORDPS1',4,'STUDENT',3,'STUDENT_DIRECT',1,1,1,79900,'2030-01-01');",
     "fail", "ck_order_price_source")

case("AG15 订单只写专属价来源 —— 应成功（这是按专属价成交的那张单该有的形状）",
     "INSERT INTO biz_order (order_no,buyer_user_id,buyer_type,sku_id,sku_code_snapshot,"
     "code_qty_snapshot,price_override_id,amount_fen,expire_at) VALUES "
     "('ORDPS2',4,'STUDENT',3,'STUDENT_DIRECT',1,1,79900,'2030-01-01');", "ok")


# =========================================================================
section("AH · 多套试卷（一门课多份卷 + 一份谁都能考的开放卷）")
# =========================================================================
# 上面 D 段已经把三张卷装好了（1=综合卷发证 / 2=章节练兵卷不发证 / 3=开放卷不挂课程），
# 这一段补它们的规则行，再逐条验四个闸门。
#
# 本段守的四件事，每一件的坏法都不响：
#   ① 唯一键从「课」换成「卷」。没换的话同一门课下的第二张卷**开不出第一次考试**，
#      而报出来的是一句「重复」，看起来像学员点了两下。
#   ② 开放卷不得发证 / 卷改成开放卷也不行。发出去的结业证明撤不回来。
#   ③ 章节练兵卷考过了不能换结业证明。它与综合卷换出来的那张在库里一模一样。
#   ④ attempt 的卷必须与 config 的卷是同一份。不一致时抽题按一份卷、判分按另一份，
#      **两边都不报错** —— 学员拿 50 题的卷面去撞 80 分的及格线，永远考不过。
#   ⑤ ③ 那道闸的更新侧。只守插入侧的话，一条 UPDATE 就把章节卷改成综合卷，
#      再发证一次通过 —— 换出来的那张与真证书在库里一模一样、事后不可分辨。
#   ⑥ attempt 挂的课程必须就是卷所属的课程。写错时库层全程不响，而 certificate.course_id
#      是 uk_user_course 的去重键 —— 换出来的是一张挂在别的课名下的结业证明。
mysql("INSERT INTO sys_user (id,phone,role) VALUES"
      " (700,'13000000700','STUDENT'),(701,'13000000701','STUDENT'),(702,'13000000702','STUDENT'),"
      " (703,'13000000703','STUDENT'),(704,'13000000704','STUDENT');")

# 章节卷的规则：50 题 / 及格 40 / 5 次 / 冷却 2 小时 / 无进度门槛（与生产的 FDE_CH1 同形）
mysql("INSERT INTO exam_config (id,course_id,paper_id,question_count,pass_score,duration_min,"
      "max_attempts,retry_cooldown_hours,require_progress_pct,effective_from)"
      " VALUES (2,1,2,50,40,35,5,2,0,'2026-01-01');")

case("AH1 开放卷的规则不挂课程（course_id 为 NULL）—— 应成功，这是把它放宽为可空的唯一用处",
     "INSERT INTO exam_config (id,course_id,paper_id,question_count,pass_score,duration_min,"
     "max_attempts,retry_cooldown_hours,require_progress_pct,effective_from)"
     " VALUES (3,NULL,3,100,80,60,3,24,0,'2026-01-01');", "ok")

# 放宽 course_id 的代价：(paper_id, course_id) 全空的规则行在库层合法，而它谁也查不到 ——
# 按卷查（新口径）查不到、按课查（旧口径）也查不到。表现是后台列表里看得见、
# 开考时报「测评规则不存在」，两边对不上，且没有一行日志说它是孤儿。
case("AH2 规则既不挂卷也不挂课 —— 应被拒（插得进去的话它是一行谁也查不到的僵尸规则）",
     "INSERT INTO exam_config (course_id,paper_id,effective_from) VALUES (NULL,NULL,'2026-01-01');",
     "fail", "至少挂在")

# 结构点名。新装的库缺了这几样时，上面每一条用例都会以别的理由红或者干脆绿，
# 指不到根因 —— 所以先把「东西在不在」单列成一条。
_struct = query(
    "SELECT (SELECT COUNT(*) FROM information_schema.TABLES"
    f"        WHERE TABLE_SCHEMA='{DB}' AND TABLE_NAME='exam_paper')"
    "     + (SELECT COUNT(*) FROM information_schema.COLUMNS"
    f"        WHERE TABLE_SCHEMA='{DB}' AND COLUMN_NAME='paper_id'"
    "          AND TABLE_NAME IN ('exam_config','question','exam_attempt'))"
    "     + (SELECT COUNT(*) FROM information_schema.COLUMNS"
    f"        WHERE TABLE_SCHEMA='{DB}' AND COLUMN_NAME='course_id' AND IS_NULLABLE='YES'"
    "          AND TABLE_NAME IN ('exam_config','exam_attempt','question'));").splitlines()[-1]
case(f"AH3 exam_paper 表 + 三处 paper_id + 三处 course_id 可空，共 7 样（实到 {_struct}）",
     "SELECT 1;" if _struct == "7" else "SELECT * FROM nonexistent_table;", "ok")

# 旧键必须**真的没了**，不是「新键也建上了」。两条并存的话 ① 那条错照旧发生，
# 而 AH5 会被旧键拒掉 —— 拒得有理有据，看起来还挺对。
_keys = query("SELECT GROUP_CONCAT(DISTINCT INDEX_NAME ORDER BY INDEX_NAME) FROM information_schema.STATISTICS"
              f" WHERE TABLE_SCHEMA='{DB}' AND TABLE_NAME='exam_attempt' AND NON_UNIQUE=0"
              " AND INDEX_NAME<>'PRIMARY';").splitlines()[-1]
case(f"AH4 exam_attempt 的唯一键换成了按卷去重（实到 {_keys}）",
     "SELECT 1;" if _keys == "uk_user_paper_attempt" else "SELECT * FROM nonexistent_table;", "ok")

# ★ 换键要守住的正例。同一个人、同一门课、两张不同的卷，各自的第 1 次考试。
# 老键 (user_id, course_id, attempt_no) 下这两行完全相同，第二张卷根本开不出考。
case("AH5 同一人同一门课下，第二张卷的第 1 次考试 —— 应成功（老键会把它当重复拒掉）",
     "INSERT INTO exam_attempt (user_id,course_id,paper_id,config_id,attempt_no,question_ids,deadline_at)"
     " VALUES (700,1,1,1,1,'[1]','2030-01-01');"
     "INSERT INTO exam_attempt (user_id,course_id,paper_id,config_id,attempt_no,question_ids,deadline_at)"
     " VALUES (700,1,2,2,1,'[1]','2030-01-01');", "ok")
# 反向的负例在 D9（同一人同一份卷的第 1 次考两遍 → uk_user_paper_attempt）。
# 少了那一条，AH5 绿着只能证明「键松了」。

case("AH6 开放卷的考试不挂课程（course_id 为 NULL）—— 应成功",
     "INSERT INTO exam_attempt (user_id,course_id,paper_id,config_id,attempt_no,question_ids,deadline_at)"
     " VALUES (700,NULL,3,3,1,'[1]','2030-01-01');", "ok")

case("AH7 新建一张「开放 + 发结业证明」的卷 —— 应被拒（不买课就能考的卷，证明证明不了任何事）",
     "INSERT INTO exam_paper (code,title,course_id,access,grants_certificate)"
     " VALUES ('T_BAD1','开放又发证',1,'OPEN',1);", "fail", "开放卷不能发结业证明")

case("AH8 ENROLLED 卷不挂课程 —— 应被拒（权益校验无从查起，这张卷谁都考不了）",
     "INSERT INTO exam_paper (code,title,access) VALUES ('T_BAD2','没挂课的卷','ENROLLED');",
     "fail", "必须挂课程")

# ★ 更新侧那条闸挡的路。插入侧全绿而更新侧不设防的话，
# 后台把一张正常的发证卷的 access 下拉框改成「开放」就完事了 —— 一个下拉框，
# 一张谁都能考、考过发结业证明的卷。
case("AH9 把发证的综合卷改成开放卷 —— 应被拒（这条路只要动一个下拉框）",
     "UPDATE exam_paper SET access='OPEN' WHERE code='T_MAIN';", "fail", "开放卷不能发结业证明")

# 阴性对照。没有它的话 AH9 绿着只能证明「exam_paper 改不动」，
# 而那样的闸门会把改标题、上下架也一起拒掉，没有任何用例看得出来。
case("AH10 AH9 的阴性对照：改标题、把卷下架 —— 应成功",
     "UPDATE exam_paper SET title='测试综合卷（改名）',status='OFF' WHERE code='T_MAIN';"
     "UPDATE exam_paper SET status='ON' WHERE code='T_MAIN';", "ok")

# ★ 本段第二号判据。config 2 是章节卷的规则（50 题 / 及格 40），paper 1 是 100 题的综合卷。
# 拒不掉的话：抽题按 attempt.paper_id 抽 100 题综合卷的题，判分按 config 的 40 分及格线，
# 两边一路不报错 —— 一张 100 题的卷 40 分就算过。
case("AH11 考试挂 1 号卷、规则却是 2 号卷的 —— 应被拒（抽题按一份卷、判分按另一份，两边都不报错）",
     "INSERT INTO exam_attempt (user_id,course_id,paper_id,config_id,attempt_no,question_ids,deadline_at)"
     " VALUES (701,1,1,2,1,'[1]','2030-01-01');", "fail", "不是同一份")

# paper_id 是可空列，但空不是「随便」：它还会顺带绕开新唯一键
# （多个 NULL 不参与唯一性判定 ⇒ 同一人同一份卷能开出任意多个第 1 次考试）。
# 这一条用 <=> 比较才拦得住 —— 写 `NEW.paper_id = cfg_paper` 的话结果是 NULL，
# IF NULL 走 false 分支，等于放行。
case("AH12 开考时漏写 paper_id（而规则挂着卷）—— 应被拒（空的 paper_id 会一并绕开新唯一键）",
     "INSERT INTO exam_attempt (user_id,course_id,config_id,attempt_no,question_ids,deadline_at)"
     " VALUES (701,1,1,1,'[1]','2030-01-01');", "fail", "不是同一份")

# ★ 本段第三号判据。章节卷是 50 题的练兵卷，考过它不等于结业。
# 原来那三条判据（真通过、是这个人、是这门课）在这里逐条满意 ——
# 少了 grants_certificate 这一条，看完第一章就能换出「课程结业证明」。
mysql("INSERT INTO exam_attempt (id,user_id,course_id,paper_id,config_id,attempt_no,question_ids,"
      "deadline_at,score,passed,submitted_at) VALUES (910,701,1,2,2,1,'[1]','2030-01-01',45,1,NOW(3));")
case("AH13 章节练兵卷考过了去换结业证明 —— 应被拒（原来那三条判据在这里逐条满意）",
     "INSERT INTO certificate (cert_no,user_id,course_id,attempt_id) VALUES ('CT_CH',701,1,910);",
     "fail", "不发结业证明")

# 阴性对照。没有它的话 AH13 绿着只能证明「证书发不出来」，
# 而那样的闸门会把综合卷考过的人也一起挡在门外，没有任何用例看得出来。
mysql("INSERT INTO exam_attempt (id,user_id,course_id,paper_id,config_id,attempt_no,question_ids,"
      "deadline_at,score,passed,submitted_at) VALUES (911,702,1,1,1,1,'[1]','2030-01-01',95,1,NOW(3));")
case("AH14 AH13 的阴性对照：综合卷考过了换结业证明 —— 应成功",
     "INSERT INTO certificate (cert_no,user_id,course_id,attempt_id) VALUES ('CT_MAIN',702,1,911);", "ok")

# 抽题按卷圈范围。Q 段已经灌了 3000 道 paper_id 为空的存量题（导题之前的形态），
# 这里再给两张卷各灌一批 —— 圈错范围的表现是「章节卷里抽出综合卷的题」，
# 题面看着都对，只有对着大纲逐题看才发现串了卷。
mysql("INSERT INTO question (course_id,paper_id,lesson_id,type,stem,answer,origin,review_status)"
      " SELECT 1,1,1,'SINGLE','综合卷题干','A','AI_GENERATED','APPROVED'"
      " FROM information_schema.columns LIMIT 200;"
      "INSERT INTO question (course_id,paper_id,lesson_id,type,stem,answer,origin,review_status)"
      " SELECT 1,2,1,'SINGLE','章节卷题干','A','AI_GENERATED','APPROVED'"
      " FROM information_schema.columns LIMIT 100;")
_p1 = query("SELECT COUNT(*) FROM question WHERE paper_id=1 AND review_status='APPROVED';").splitlines()[-1]
_p2 = query("SELECT COUNT(*) FROM question WHERE paper_id=2 AND review_status='APPROVED';").splitlines()[-1]
case(f"AH15 按卷抽题互不串（1 号卷 {_p1} 题 / 2 号卷 {_p2} 题，存量 3000 道无卷题一道不进）",
     "SELECT 1;" if (_p1, _p2) == ("200", "100") else "SELECT * FROM nonexistent_table;", "ok")

_used, _plan = explain_uses(
    "SELECT id FROM question WHERE paper_id=1 AND review_status='APPROVED' AND type='SINGLE' LIMIT 50;",
    "idx_paper_pool")
case(f"AH16 按卷随机抽题走 idx_paper_pool（实际 {_plan.split()[5] if len(_plan.split())>5 else '?'}）",
     "SELECT 1;" if _used else "SELECT * FROM nonexistent_table;", "ok")

# 开放卷的题不属于任何一门课。course_id 不放宽的话，导题那一步会在第一条 AIGC 题上
# 撞 NOT NULL 整批失败 —— 那还算好的；更坏的是「顺手填个 course_id=1 凑数」，
# 那批题就永远混在实训营的池子里，按 course_id 抽题的老代码会把它们抽进课程考卷。
case("AH17 开放卷的题不挂课程（course_id 为 NULL）—— 应成功",
     "INSERT INTO question (course_id,paper_id,type,stem,answer,origin,review_status)"
     " VALUES (NULL,3,'SINGLE','开放卷题干','A','MANUAL_UPLOAD','APPROVED');", "ok")

# 放宽 course_id 的代价的另一半：两头都空的题谁也抽不到 —— 按卷抽抽不到、按课抽也抽不到，
# 它只是静静躺在库里占着编号，而后台题库列表照样把它列出来、照样能审成 APPROVED。
case("AH18 题既不挂卷也不挂课 —— 应被拒（插得进去的话它是一道谁也抽不到的僵尸题）",
     "INSERT INTO question (course_id,paper_id,type,stem,answer,origin)"
     " VALUES (NULL,NULL,'SINGLE','孤儿题','A','MANUAL_UPLOAD');",
     "fail", "ck_question_scope")

# ★ AH13 那道闸的更新侧。只守插入侧的话它一条 UPDATE 就绕过去了，实测复现过：
# 910 是 701 那条已通过的章节卷记录（paper_id=2、config_id=2、passed=1），
# AH13 里直接发证被拒；而 `UPDATE exam_attempt SET paper_id=1 WHERE id=910` **成功**
# （此后 paper_id=1 而 config_id=2，正是插入侧明令禁止的形状），
# 紧接着同一句 INSERT certificate 一次通过，证书落库。
# 这条路今天应用侧走不到，但把判据放进库层的全部理由就是「直接 INSERT、
# 以后新写的某个补发功能都得过」—— 而更新侧原来是敞开的。
case("AH19 已交卷的章节卷记录改成综合卷 —— 应被拒（这一句成功的话 AH13 那道闸整条作废）",
     "UPDATE exam_attempt SET paper_id=1 WHERE id=910;", "fail", "不得改卷")

# AH19 的收口：那条记录必须还是 2 号卷，而拿它换结业证明必须还是被拒。
# 只验 UPDATE 被拒是不够的 —— 拒了却已经改了半行（比如触发器排在别的判据后面）
# 这种事看不出来，得从结果那一侧再问一次。
_bypass = query("SELECT paper_id FROM exam_attempt WHERE id=910;").splitlines()[-1]
case(f"AH20 AH19 之后那条记录仍挂在 2 号卷上（实到 paper_id={_bypass}）",
     "SELECT 1;" if _bypass == "2" else "SELECT * FROM nonexistent_table;", "ok")
case("AH21 走完 AH19 那条路再发证 —— 应仍被拒（这是当初实测能落库的那一张）",
     "INSERT INTO certificate (cert_no,user_id,course_id,attempt_id) VALUES ('CT_BYPASS',701,1,910);",
     "fail", "不发结业证明")

# 更新侧的「卷与规则同一份」。没交卷的场次也不许把卷单独换掉：换完之后抽题按一份卷、
# 判分按另一份，两边一路不报错 —— 与 AH11 是同一条判据的两侧。
mysql("INSERT INTO exam_attempt (id,user_id,course_id,paper_id,config_id,attempt_no,question_ids,"
      "deadline_at) VALUES (912,703,1,1,1,1,'[1]','2030-01-01');")
case("AH22 把没交卷的场次单独换成另一份卷（规则不动）—— 应被拒",
     "UPDATE exam_attempt SET paper_id=2 WHERE id=912;", "fail", "不是同一份")

# 阴性对照。没有它的话 AH19/AH22 绿着只能证明「exam_attempt 改不动」，
# 而那样的闸门会把正常交卷写分（V7）也一起拒掉。卷与规则一起换是自洽的，必须放行。
case("AH23 AH22 的阴性对照：卷与规则一起换成另一份 —— 应成功",
     "UPDATE exam_attempt SET paper_id=2,config_id=2 WHERE id=912;", "ok")

# ★ 三元关系里最后那条边：attempt 挂的课程必须就是卷所属的课程。
# 写错时库层原来全程不响，而 trg_certificate_precheck 是拿 attempt.course_id 去比对
# NEW.course_id 的、certificate.course_id 又是 uk_user_course 的去重键 ——
# 一场 1 号卷（course 1）的考试把 course_id 写成 2，换出来的就是一张挂在第 2 门课
# 名下的结业证明。开放卷两边同为 NULL，由 AH6 守着放行那一侧。
case("AH24 考试挂 1 号卷却把课程写成第 2 门课 —— 应被拒（换出来的会是挂错课的结业证明）",
     "INSERT INTO exam_attempt (user_id,course_id,paper_id,config_id,attempt_no,question_ids,deadline_at)"
     " VALUES (704,2,1,1,1,'[1]','2030-01-01');", "fail", "课程与试卷所属课程不一致")

# 卷不在了却还有规则指着它：这时候上面那条判据里 p_course 恒为 NULL，
# 不单独分支的话它会报成「课程与试卷所属课程不一致」—— 一句把人指到错地方的话。
mysql("INSERT INTO exam_config (id,course_id,paper_id,question_count,pass_score,duration_min,"
      "max_attempts,retry_cooldown_hours,require_progress_pct,effective_from)"
      " VALUES (99,1,99,50,40,35,5,2,0,'2026-01-01');")
case("AH25 规则指着一份不存在的卷 —— 应被拒且报的是「试卷不存在」，不是「课程不一致」",
     "INSERT INTO exam_attempt (user_id,course_id,paper_id,config_id,attempt_no,question_ids,deadline_at)"
     " VALUES (704,1,99,99,1,'[1]','2030-01-01');", "fail", "试卷不存在")


# =========================================================================
section("AI · 测评报告（交卷后 AI 出的那一份；事实层永远有，叙述层可以没有）")
# =========================================================================
# 报告分两层：事实层（stats）由代码算、永远算得出来；叙述层（content）由模型写、会挂。
# 这一段守的六件事，坏法全都不响：
#   ① 一场考试一份报告（uk_report_attempt）。没有它，重试会插出第二行，而学员端按
#      attempt 查 —— 两行同时在的时候页面显示哪一份取决于查询顺序，今天成功、明天失败。
#   ② 只能给**已交卷**的场次建报告。没交卷的场次一行答题明细都没有，那时算出来的
#      事实层是「0 分、全错」，与真考了 0 分在库里完全同形，学员看到一份说他全错的报告。
#   ③ READY 必须有正文。状态是学员端唯一的显示判据 —— READY 而正文为空 = 一页空白，
#      且轮询就此停下，学员再也看不到任何进展。
#   ④ FAILED 必须写明理由。理由是原样显示给学员的，没有它「重新生成」按钮下面一片空白。
#   ⑤ 报告的 user_id/paper_id 必须与那场考试一致。越权判据（AND user_id=?）就写在
#      这一列上，写错等于把别人那场考试的错题清单发给这个人，两行记录各自看都合法。
#   ⑥ ②③④⑤ 的**更新侧**。整条状态流转都是 UPDATE（抢占 → 落正文 → 置失败 → 清扫退回），
#      插入那一下 status 恒为 QUEUED、content 恒为空 —— 只守插入侧等于把闸门装在
#      唯一不会出错的那一步上。
mysql("INSERT INTO sys_user (id,phone,role) VALUES"
      " (710,'13000000710','STUDENT'),(711,'13000000711','STUDENT'),(712,'13000000712','STUDENT'),"
      " (713,'13000000713','STUDENT'),(714,'13000000714','STUDENT');")

# 四场考试：920 综合卷已交卷 / 921 综合卷【考到一半没交】 / 922 章节卷已交卷 /
# 923 综合卷已交卷且没有报告（更新侧要往它身上改）。
mysql("INSERT INTO exam_attempt (id,user_id,course_id,paper_id,config_id,attempt_no,question_ids,"
      "deadline_at,score,passed,submitted_at) VALUES"
      " (920,710,1,1,1,1,'[1,2]','2030-01-01',95,1,NOW(3)),"
      " (922,712,1,2,2,1,'[1,2]','2030-01-01',45,1,NOW(3)),"
      " (923,713,1,1,1,1,'[1,2]','2030-01-01',30,0,NOW(3));")
mysql("INSERT INTO exam_attempt (id,user_id,course_id,paper_id,config_id,attempt_no,question_ids,"
      "deadline_at) VALUES (921,711,1,1,1,1,'[1,2]','2030-01-01');")

# 结构点名。缺了这几样时下面每一条用例都会以别的理由红或者干脆绿，指不到根因。
# 索引这里只点名字，不像 Q 段那样跑 EXPLAIN：exam_report 的三条闸门要求每一行都对应
# 一场真实的已交卷考试，造不出让优化器动心的量；在 3 行表上跑 EXPLAIN 测的是优化器的
# 心情，不是索引在不在。
_rpt = query(
    "SELECT (SELECT COUNT(*) FROM information_schema.TABLES"
    f"        WHERE TABLE_SCHEMA='{DB}' AND TABLE_NAME='exam_report')"
    "     + (SELECT COUNT(*) FROM information_schema.COLUMNS"
    f"        WHERE TABLE_SCHEMA='{DB}' AND TABLE_NAME='question'"
    "          AND COLUMN_NAME IN ('topic','knowledge_point','difficulty'))"
    "     + (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS"
    f"        WHERE TABLE_SCHEMA='{DB}' AND TABLE_NAME='question'"
    "          AND CONSTRAINT_NAME='ck_question_difficulty' AND CONSTRAINT_TYPE='CHECK')"
    "     + (SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS"
    f"        WHERE TABLE_SCHEMA='{DB}' AND TABLE_NAME='exam_report'"
    "          AND INDEX_NAME IN ('uk_report_attempt','idx_report_queue'));").splitlines()[-1]
case(f"AI1 exam_report 表 + question 三列 + 难度 CHECK + 两条索引，共 7 样（实到 {_rpt}）",
     "SELECT 1;" if _rpt == "7" else "SELECT * FROM nonexistent_table;", "ok")

# ★ 表名点到了不等于形状对。迁移里 exam_report 建在 CREATE TABLE IF NOT EXISTS 下面，
# 在跑过旧稿的库上整张表会被静默跳过 —— 缺列的后果一句都不指向库结构：
# 缺 tries 时清扫那句 `tries=tries+1` 每轮 500（报告永不自愈）；stats 建成可空时
# 「模型还没跑完」与「连分数都没有」在库里同形，分两层这件事就白做了。
# 这一条与迁移的 sp_assert_landed、deploy_course.sh 的门禁点同一批名字。
_rshape = query(
    "SELECT (SELECT COUNT(*) FROM information_schema.COLUMNS"
    f"        WHERE TABLE_SCHEMA='{DB}' AND TABLE_NAME='exam_report'"
    "          AND COLUMN_NAME IN ('attempt_id','user_id','paper_id','status','model','content',"
    "                              'stats','fail_reason','tries','requested_at','started_at','finished_at'))"
    "     + (SELECT COUNT(*) FROM information_schema.COLUMNS"
    f"        WHERE TABLE_SCHEMA='{DB}' AND TABLE_NAME='exam_report'"
    "          AND COLUMN_NAME='stats' AND IS_NULLABLE='NO');").splitlines()[-1]
case(f"AI1b exam_report 的 12 个关键列齐 + stats 非空，共 13 样（实到 {_rshape}）",
     "SELECT 1;" if _rshape == "13" else "SELECT * FROM nonexistent_table;", "ok")

# 正例：交卷之后入队。stats 是 NOT NULL —— 事实层由代码算、永远算得出来，
# enqueue 那一步就得把它写进去，因为 QUEUED/RUNNING 时页面显示的正是它。
case("AI2 已交卷的场次入队一份 QUEUED 报告（stats 当场就有）—— 应成功",
     "INSERT INTO exam_report (attempt_id,user_id,paper_id,stats)"
     " VALUES (920,710,1,JSON_OBJECT('score',95,'wrong',5));", "ok")

case("AI3 同一场考试再建第二份报告 —— 应被拒（两行同时在，页面显示哪一份看查询顺序）",
     "INSERT INTO exam_report (attempt_id,user_id,paper_id,stats)"
     " VALUES (920,710,1,JSON_OBJECT('score',95));", "fail", "uk_report_attempt")

# ★ 本段第二号判据。921 是考到一半的场次，一行答题明细都没有。
case("AI4 给没交卷的场次建报告 —— 应被拒（那时算出来的「全错」与真考零分同形）",
     "INSERT INTO exam_report (attempt_id,user_id,paper_id,stats)"
     " VALUES (921,711,1,JSON_OBJECT('score',0));", "fail", "没交卷")

case("AI5 READY 却没有正文 —— 应被拒（状态说已生成，学员点开是一页空白且轮询就此停下）",
     "INSERT INTO exam_report (attempt_id,user_id,paper_id,status,stats)"
     " VALUES (922,712,2,'READY',JSON_OBJECT('score',45));", "fail", "必须有正文")

# 模型返回的「空」常常是两个换行。判据写成 TRIM 的话这一条会绿 ——
# 实测 CHAR_LENGTH(TRIM('  \n  '))=1，TRIM 只去半角空格。
case("AI6 READY 的正文只有换行与全角空格 —— 应被拒（TRIM 判空在这一条上会放行）",
     "INSERT INTO exam_report (attempt_id,user_id,paper_id,status,content,stats)"
     " VALUES (922,712,2,'READY','\\n　\\t ',JSON_OBJECT('score',45));", "fail", "必须有正文")

case("AI7 FAILED 却没写理由 —— 应被拒（学员那边「重新生成」下面一片空白，运维也无从下手）",
     "INSERT INTO exam_report (attempt_id,user_id,paper_id,status,stats)"
     " VALUES (922,712,2,'FAILED',JSON_OBJECT('score',45));", "fail", "必须写明理由")

# ★ 本段第五号判据。922 是 712 的考试，报告却写成 710 的 —— 越权判据是
# `AND user_id=?`，这一行落库等于把 712 的错题清单发给了 710。
case("AI8 报告的学员与那场考试对不上 —— 应被拒（越权判据就写在这一列上）",
     "INSERT INTO exam_report (attempt_id,user_id,paper_id,stats)"
     " VALUES (922,710,2,JSON_OBJECT('score',45));", "fail", "对不上")

# paper 写错不越权，但行动清单会按错的卷给「去考另一份卷」，而它读起来完全通顺。
case("AI9 报告的试卷与那场考试对不上（922 考的是 2 号卷，报告写 1 号）—— 应被拒",
     "INSERT INTO exam_report (attempt_id,user_id,paper_id,stats)"
     " VALUES (922,712,1,JSON_OBJECT('score',45));", "fail", "对不上")

# 挂一场根本不存在的考试：必须报「考试不存在」而不是「对不上」——
# 不单独分支的话 a_user 恒为 NULL，判据会走到下一条上，把人指到错的地方去。
case("AI10 报告挂一场不存在的考试 —— 应被拒且报的是「考试不存在」，不是「对不上」",
     "INSERT INTO exam_report (attempt_id,user_id,paper_id,stats)"
     " VALUES (9999,712,2,JSON_OBJECT('score',45));", "fail", "考试不存在")

# 阴性对照。没有它的话 AI5~AI10 绿着只能证明「exam_report 插不进去」。
case("AI11 AI5/AI7 的阴性对照：FAILED 且写了理由 —— 应成功",
     "INSERT INTO exam_report (attempt_id,user_id,paper_id,status,stats,fail_reason,tries)"
     " VALUES (922,712,2,'FAILED',JSON_OBJECT('score',45),'模型引用了不存在的时间点',1);", "ok")

# ── 更新侧：整条状态流转都在这里，插入那一下 status 恒为 QUEUED、content 恒为空 ──
case("AI12 抢占：QUEUED → RUNNING（受影响行数=1 才算抢到）—— 应成功",
     "UPDATE exam_report SET status='RUNNING',started_at=NOW(3)"
     " WHERE attempt_id=920 AND status='QUEUED';", "ok")

# ★ 本段第六号判据，也是真正会出错的那一步：模型回了空串、或者时间点校验把正文判废之后
# 忘了把 status 一起改回去。只守插入侧的话这一句会成功，学员刷到的是一页空白。
case("AI13 落正文那一句把 status 改成 READY 却没有正文 —— 应被拒（只守插入侧的话这一句会过）",
     "UPDATE exam_report SET status='READY',finished_at=NOW(3) WHERE attempt_id=920;",
     "fail", "必须有正文")

case("AI14 更新侧把 status 改成 FAILED 却不写理由 —— 应被拒",
     "UPDATE exam_report SET status='FAILED',finished_at=NOW(3) WHERE attempt_id=920;",
     "fail", "必须写明理由")

case("AI15 AI13/AI14 的阴性对照：正文与 model 一起落，转 READY —— 应成功",
     "UPDATE exam_report SET status='READY',content='## 这次考得怎么样\\n\\n错在第 1 讲 18:47 那一段。',"
     "model='deepseek-v4-flash',finished_at=NOW(3) WHERE attempt_id=920;", "ok")

# ★ 更新侧的三元一致。uk_report_attempt 只拦「那场考试已经有报告了」，
# 而 923 还没有报告 —— 改挂过去等于把 710 的报告页塞进 713 的那场考试。
case("AI16 把一份报告改挂到别人那场考试（user_id 不动）—— 应被拒",
     "UPDATE exam_report SET attempt_id=923 WHERE attempt_id=920;", "fail", "对不上")

# 收口：只验 UPDATE 被拒不够，拒了却已经改了半行这种事看不出来，得从结果那一侧再问一次。
_moved = query("SELECT attempt_id FROM exam_report WHERE user_id=710;").splitlines()[-1]
case(f"AI17 AI16 之后那份报告仍挂在 920 那场考试上（实到 attempt_id={_moved}）",
     "SELECT 1;" if _moved == "920" else "SELECT * FROM nonexistent_table;", "ok")

# 更新侧的「已交卷」闸门：连 user_id/paper_id 一起改成 921 那场的，三元是自洽的，
# 只有「没交卷」这一条拦得住 —— 921 至今还开着卷。
case("AI18 把报告整份改挂到一场没交卷的考试上（三元一起改，自洽）—— 应被拒",
     "UPDATE exam_report SET attempt_id=921,user_id=711,paper_id=1 WHERE attempt_id=920;",
     "fail", "没交卷")

# ★ 库层【故意】没有「只能前进」的状态机闸（biz_order/withdraw_order 有）：
# 报告的两条自愈路径都要往回走 —— 清扫把卡住的 RUNNING 退回 QUEUED、重试把 FAILED
# 退回 QUEUED。加一条只许前进的闸会把规格 §4 明写的这两条一起拒掉。
case("AI19 重试：FAILED 退回 QUEUED 并 tries+1 —— 应成功（限频在应用层，库层不该拦）",
     "UPDATE exam_report SET status='QUEUED',tries=tries+1,fail_reason=NULL,started_at=NULL"
     " WHERE attempt_id=922 AND status='FAILED';", "ok")

# 开放卷（不挂课程）的考试也得出得了报告。exam_report 不存 course_id 正是为此 ——
# 报告只认「哪一场考试、哪一份卷」，而开放卷的那一场 course_id 是 NULL。
mysql("INSERT INTO exam_attempt (id,user_id,course_id,paper_id,config_id,attempt_no,question_ids,"
      "deadline_at,score,passed,submitted_at) VALUES (924,714,NULL,3,3,1,'[1,2]','2030-01-01',80,1,NOW(3));")
case("AI20 开放卷（考试不挂课程）的报告 —— 应成功",
     "INSERT INTO exam_report (attempt_id,user_id,paper_id,stats)"
     " VALUES (924,714,3,JSON_OBJECT('score',80));", "ok")

# ── question 那三列 ──
# 难度是给报告分组用的。TINYINT 写 0/4/-1 进来在库层全都合法，而那些行会落进
# 一个谁也没定义过的桶，在页面上显示成一个空标题的分组 —— 不报错，只是多一块看不懂的东西。
case("AI21 题目写上板块、考点与难度 —— 应成功",
     "INSERT INTO question (course_id,paper_id,lesson_id,source_ts_sec,topic,knowledge_point,difficulty,"
     "type,stem,answer,origin,review_status)"
     " VALUES (1,1,1,1127,'第一章 · 前端与 skill','skill 的触发条件写在 description 里',2,"
     "'SINGLE','题干','A','AI_GENERATED','APPROVED');", "ok")

# 回填脚本跑之前，400 道存量题的这三列全是 NULL —— 那一段时间里考试照常，
# 只是报告的按板块聚合是空的。这一条钉住「可空是真的可空」。
case("AI22 三列全空的题（回填之前 400 道题的形态）—— 应成功",
     "INSERT INTO question (course_id,paper_id,type,stem,answer,origin,review_status)"
     " VALUES (1,1,'JUDGE','没有板块与考点的题','T','MANUAL_UPLOAD','APPROVED');", "ok")

case("AI23 难度写 4（只定义了 1/2/3）—— 应被拒",
     "INSERT INTO question (course_id,paper_id,difficulty,type,stem,answer,origin)"
     " VALUES (1,1,4,'SINGLE','难度越界','A','AI_GENERATED');", "fail", "ck_question_difficulty")

# 0 是最容易漏进来的那个值：Java 里 int 字段的缺省就是 0，回填脚本少判一次
# 「这道题没标难度」就会把 0 写进来，而 0 在 TINYINT 上完全合法。
case("AI24 难度写 0（Java 里 int 的缺省值）—— 应被拒",
     "INSERT INTO question (course_id,paper_id,difficulty,type,stem,answer,origin)"
     " VALUES (1,1,0,'SINGLE','难度为零','A','AI_GENERATED');", "fail", "ck_question_difficulty")

# ── 孤儿报告（挂的考试被删掉了）不许毒死整批清扫 ──────────────────────────────
# 全库零外键，删掉一场考试不会连带删它的报告；而清扫是**整批** UPDATE
# （WHERE status='RUNNING' AND started_at < NOW()-10min，不带 id），BEFORE UPDATE
# 对匹配到的每一行各跑一次 —— 孤儿那一行 SIGNAL 一响整条语句回滚，同一批里真正卡住的
# 一行都退不回队列。对外是「全平台报告一直转圈」，日志里只有一句没有指向的「报告清扫失败」。
# 所以更新侧对「已经孤儿的行」放行，而「改挂到一场不存在的考试」（正在制造孤儿）照旧拒。
mysql("INSERT INTO sys_user (id,phone,role) VALUES (715,'13000000715','STUDENT'),(716,'13000000716','STUDENT');")
# 1 号规则的及格线是 90，passed 必须与「分数 ≥ 及格线」一致（trg_exam_attempt_ins），
# 所以这里写 95/1 —— 写 60/1 的话这两行 INSERT 会被那条闸拒掉，而 mysql() 不看返回值，
# 下面整段就在一张空表上「全绿」。AI25a 就是为了让这种空跑当场红。
mysql("INSERT INTO exam_attempt (id,user_id,course_id,paper_id,config_id,attempt_no,question_ids,"
      "deadline_at,score,passed,submitted_at) VALUES"
      " (925,715,1,1,1,1,'[1,2]','2030-01-01',95,1,NOW(3)),"
      " (926,716,1,1,1,1,'[1,2]','2030-01-01',95,1,NOW(3));")
mysql("INSERT INTO exam_report (attempt_id,user_id,paper_id,stats) VALUES"
      " (925,715,1,JSON_OBJECT('score',95)),(926,716,1,JSON_OBJECT('score',95));")
# 两行都做成「一小时前就在 RUNNING」= 清扫认定卡住的形态
mysql("UPDATE exam_report SET status='RUNNING',started_at=DATE_SUB(NOW(3),INTERVAL 60 MINUTE)"
      " WHERE attempt_id IN (925,926);")
# 运维删掉 925 那场考试：它的报告成了孤儿，而 926 是健康的、真卡住的那一行
mysql("DELETE FROM exam_attempt WHERE id=925;")

# 前置数据点名。少了它，AI25 那句整批 UPDATE 在一张没有 RUNNING 行的表上照样「成功」。
_stuck = query("SELECT COUNT(*) FROM exam_report WHERE status='RUNNING'"
               " AND started_at < DATE_SUB(NOW(3), INTERVAL 10 MINUTE);").splitlines()[-1]
_orphan = query("SELECT COUNT(*) FROM exam_report r WHERE r.attempt_id=925"
                " AND NOT EXISTS (SELECT 1 FROM exam_attempt a WHERE a.id=r.attempt_id);").splitlines()[-1]
case(f"AI25a 前置：两行卡住的 RUNNING（实到 {_stuck}），其中一行是孤儿（实到 {_orphan}）",
     "SELECT 1;" if (_stuck, _orphan) == ("2", "1") else "SELECT * FROM nonexistent_table;", "ok")

case("AI25 队列里躺着一行孤儿报告时，requeueStuck 那句整批 UPDATE —— 应成功（不被孤儿毒死）",
     "UPDATE exam_report SET status='QUEUED',tries=tries+1,started_at=NULL"
     " WHERE status='RUNNING' AND started_at < DATE_SUB(NOW(3), INTERVAL 10 MINUTE)"
     "   AND tries+1 < 3;", "ok")

# 只验「那句没报错」不够：整条语句回滚时它也可能是别的原因过的，得从结果那一侧再问一次。
_healed = query("SELECT status FROM exam_report WHERE attempt_id=926;").splitlines()[-1]
case(f"AI26 AI25 之后真卡住的那一行确实回到了 QUEUED（实到 {_healed}）",
     "SELECT 1;" if _healed == "QUEUED" else "SELECT * FROM nonexistent_table;", "ok")

case("AI27 孤儿报告能被运维改成 FAILED（写了理由）—— 应成功（否则那一行只能整行 DELETE）",
     "UPDATE exam_report SET status='FAILED',finished_at=NOW(3),"
     "fail_reason='挂的那场考试已被删除，这份报告作废' WHERE attempt_id=925;", "ok")

# 孤儿行也不许变成「FAILED 却没有理由」：④⑤ 与挂哪一场考试无关，无条件守。
case("AI28 孤儿报告改成 FAILED 却不写理由 —— 应被拒",
     "UPDATE exam_report SET status='FAILED',fail_reason=NULL WHERE attempt_id=925;",
     "fail", "必须写明理由")

# 「已经孤儿」放行，不等于「可以去制造孤儿」：改挂到一场不存在的考试照旧拒。
case("AI29 把一份报告改挂到一场根本不存在的考试 —— 应被拒（这是在制造孤儿）",
     "UPDATE exam_report SET attempt_id=99999 WHERE attempt_id=926;", "fail", "考试不存在")


# =========================================================================
# 汇总
# =========================================================================
if os.environ.get("WANGKE_SHOW_REASONS"):
    print("\n" + "═" * 66)
    print("\033[1m被拒用例的实际拒绝理由（核对「拒对了没有」）\033[0m")
    for nm, want, got in REASONS:
        flag = " " if want != "—未锁定—" else "?"
        print(f"{flag} {nm[:46]:<46} | 锁定={want[:14]:<14} | 实际={got}")

print("\n" + "═" * 66)
print(f"\033[1m通过 {len(PASS)} · 失败 {len(FAIL)}\033[0m")
if FAIL:
    print("\n\033[31m未通过的机制：\033[0m")
    for name, detail in FAIL:
        print(f"  · {name}")
        if detail:
            print(f"      {detail}")
print("═" * 66)
sys.exit(1 if FAIL else 0)
