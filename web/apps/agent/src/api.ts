/**
 * 代理后台的接口面。
 *
 * 全部走 shared/api.ts 的 api.*：401 的统一出口、错误原文透出、写操作去重
 * 都在那一层做完了，这里只负责把路径和形状钉死在一个地方 ——
 * 路径散在各页面里的话，后端改一个字段名要满仓库 grep。
 *
 * 后端仍在并行补全。凡是这里写了、代码里还读不到的接口，都在交付说明的
 * assumptions 里列了出来；形状按 db/schema.sql 的列名与既有 Service 的返回记录推的。
 */
import { api } from '@wangke/shared';

/* ---------------------------------------------------------------- 工具 */

export function qs(o: Record<string, string | number | boolean | undefined | null>): string {
  const p = new URLSearchParams();
  for (const [k, v] of Object.entries(o)) {
    if (v === undefined || v === null || v === '') continue;
    p.set(k, String(v));
  }
  const s = p.toString();
  return s ? `?${s}` : '';
}

/**
 * 元（用户输入的字符串）→ 分。
 *
 * 和后端 PricingService.yuanToFen 同一条规矩：<b>三位小数直接拒绝，不四舍五入</b>。
 * 不用 parseFloat(x)*100 —— 19.99*100 在浮点里是 1998.9999999999998，
 * 取整方式一变就差一分，而差一分的提现单财务对不平。
 */
export function yuanToFen(input: string): number | null {
  const t = input.trim();
  if (!/^\d{1,9}(\.\d{1,2})?$/.test(t)) return null;
  const [i, d = ''] = t.split('.');
  return Number(i) * 100 + Number((d + '00').slice(0, 2));
}

/**
 * 分 → 金额输入框里的字符串。是 {@link yuanToFen} 的逆运算。
 *
 * 展示金额一律用 shared 的 yuan()，但那个带千分位（"1,234.00"），
 * 塞回输入框会被自己的校验判成非法。所以只有「回填输入框」这一处用它，
 * 而且走整数运算，不碰浮点。
 */
export function fenToInput(fen: number): string {
  const neg = fen < 0 ? '-' : '';
  const a = Math.abs(Math.trunc(fen));
  return `${neg}${Math.floor(a / 100)}.${String(a % 100).padStart(2, '0')}`;
}

/* ---------------------------------------------------------------- 类型 */

export type Page<T> = { total: number; rows: T[] };

export type AgentStatus = 'PENDING' | 'ACTIVE' | 'SUSPENDED' | 'TERMINATED';
export type SubjectType = 'INDIVIDUAL' | 'SOLE_PROPRIETOR' | 'COMPANY';

export type AgentProfile = {
  id: number;
  agentCode: string;
  level: 1 | 2;
  status: AgentStatus;
  parentAgentCode?: string | null;
  companyName?: string | null;
  subjectType?: SubjectType | null;
  taxNo?: string | null;
  contactName?: string | null;
  settleBankName?: string | null;
  settleBankAcct?: string | null;
  approvedAt?: string | null;
  createdAt?: string | null;
  /**
   * 平台停用 / 驳回时填的那句理由，直接展示给代理看。
   *
   * 它落在审计日志里（后台 reject/suspend 的 after_json.reason），后端 me() 读出来给这里。
   * 在补上那一侧之前，这个字段全仓<b>没有人写过</b>：类型上一直在、页面也一直在读，
   * 于是被停用的代理永远只看得到兜底文案，真实理由躺在 audit_log 里没有出口。
   */
  statusNote?: string | null;

  /**
   * ★ 这三个字段拆的是同一件事：`TERMINATED` 在库里<b>有两种含义</b>。
   *
   * 后台「驳回申请」和「终止合作」写的是同一个值，而这两件事对当事人完全不同：
   * 前者是「你的申请没通过，改完资料还能再交」，后者是「合作结束了」。
   * 只看 status 的话，一个刚被驳回的申请人会读到「你的代理合作已终止 ——
   * 已经产生的佣金仍可提现」，而他既没合作过也没有一分钱佣金。
   *
   * 判据是 approvedAt：只有审批通过时才写它。
   */
  everApproved?: boolean;
  /** true = 这是一份被驳回的申请，不是一段被终止的合作 */
  rejectedApplication?: boolean;
  /** true = 可以改完资料重新提交（后端 apply 会接住，见 AgentService.reopen） */
  canReapply?: boolean;
};

export type AgentApplication = {
  id: number;
  level: 1 | 2;
  status: 'SUBMITTED' | 'APPROVED' | 'REJECTED';
  rejectReason?: string | null;
  createdAt: string;
};

export type AgentMe = {
  user: {
    id: number;
    phone: string;            // 后端已掩码
    nickname?: string | null;
    realName?: string | null;
    role: 'PLATFORM_ADMIN' | 'AGENT' | 'STUDENT' | 'MENTOR';
  };
  agent: AgentProfile | null;
  application: AgentApplication | null;
};

/**
 * 改密的返回体。字段名逐字照抄后端 AccountController.changePassword 的 key。
 *
 * 原来这里写的是 `{ ok: boolean }`，把后面两个字段丢掉了 —— 丢掉 message 只是浪费了
 * 后端写好的人话，丢掉 <b>reloginRequired 是错的</b>：改密之后 token_version +1，
 * <b>发起这次请求的这台设备，手里那张令牌也当场作废</b>。
 * 不读它的后果不是报错，而是用户带着一张死令牌继续点，直到某一次请求 401，
 * 被 shared/AppShell 的 onUnauthorized 统一出口无缘无故弹去登录页 ——
 * 他会以为是平台把他踢了。
 *
 * 放宽是加字段不是改字段：原来按 `{ok:boolean}` 用的调用点仍然编译得过。
 */
export type ChangePwdResult = {
  ok: boolean;
  /** 后端写好的人话，可直接 toast */
  message: string;
  /** true = 手里这张令牌已经作废，必须自己登出 */
  reloginRequired: boolean;
};

export type Dashboard = {
  /** 库存：还没发出去也没被激活的码 */
  stockUnactivated: number;
  /** 其中多少个的激活期限已经进入 90 天倒计时 */
  expiringSoonCodes: number;
  /** 最早的那个码的激活截止日 */
  nearestDeadline?: string | null;
  monthActivatedCodes: number;
  lastMonthActivatedCodes?: number;
  studentsTotal: number;
  /** 现在就能申请提现的钱（已扣掉在途提现单） */
  withdrawableFen: number;
  /** 已申请、还没打款的提现单合计 */
  inflightWithdrawFen: number;
  /** 还在冻结期、暂时提不了的佣金 */
  frozenCommissionFen: number;
  /** 被驳回 / 打款失败、需要你处理的提现单数 */
  withdrawNeedsAttention: number;

  // ★ purchasedAmountFen / monthPurchasedAmountFen 曾经在这里，工作台第四格画的就是它们
  //   （「累计进货金额」）。进货价改成一家一议之后，代理端不再出现任何价目，那一格已经删掉。
  //
  //   这两个键<b>故意不再声明</b>：不声明的话，谁想把那一格加回来会在 tsc 处当场红，
  //   而留着声明的话它只是一个"没人读的字段"，下一次改工作台时会被顺手用起来。
  //
  //   ⚠ 后端 /api/agent/dashboard 与 /api/agent/stats <b>仍然返回这两个数</b>
  //   （AgentDashboardService:148-149、AgentService:882）。那两个文件不在本轮的改动范围里，
  //   已作为偏差上报：F12 里现在还能看见累计进货金额。
};

/**
 * 我的统计（{@code GET /api/agent/stats}）。工作台只借它一个数：{@link Stats.grantedCodes}。
 *
 * 这里只声明工作台真的读的那几个键，不是把后端二十来个键抄全 ——
 * 抄全的那份没有任何东西保证它跟着后端走，读一个早就改名的键在 TS 里也照样编译得过。
 */
export type Stats = {
  /** 名下的码总数（各状态之和），含平台发放的那部分 */
  purchasedCodes: number;
  activatedCodes: number;
  unactivatedStock: number;
  /**
   * 其中平台手工发放给你的码，累计各状态之和。
   *
   * ★ 它是<b>额外的一维</b>，不是从 purchasedCodes 里切出去的一块：后端刻意没有把
   *   purchasedCodes 收窄成「只数自己买的」，因为那五个键之间有一条恒等式
   *   （已激活 + 未激活 + 过期 + 作废 = 名下总数）。界面上也绝不许拿它去做减法 ——
   *   减完之后两个数字各自都对，而代理会当成系统算错来投诉。
   *
   * ★ 也不能把它贴到「还没发出去的码」下面写成「其中 N 个」：那一栏只数 ISSUED，
   *   而这个数含已激活/已过期的，白送的码被学员用掉之后 N 会<b>大于</b>库存本身。
   */
  grantedCodes: number;
  // purchasedAmountFen 同 Dashboard：2026-09-01 起【后端也不再发】（AgentService.stats），
  // 不是"前端不画但接口还给"——那样 F12 里照样看得见，而所有人都会以为它保密了。
};

export type CodeStatus = 'ISSUED' | 'BOUND' | 'REVOKED' | 'EXPIRED';

/**
 * 这个码是怎么来的。
 *
 * `PLATFORM_GRANT` = 平台超管手工发放到代理名下的码。它挂在一张真订单上，
 * 有真单号，但那张单<b>从来没有收到过钱</b>（实付恒 0、到账时间恒空，库层 CHECK 逼着）。
 * 所以界面上不能只把单号摆出来：代理会拿它去对账，而账上没有这一笔。
 */
export type CodeSource = 'PURCHASE' | 'PLATFORM_GRANT';

export type CodeRow = {
  id: number;
  masked: string;
  status: CodeStatus;
  activateDeadline: string;
  boundUserId?: number | null;
  boundPhone?: string | null;   // 已掩码
  boundAt?: string | null;
  orderNo?: string | null;
  /**
   * ★★ 这一个是<b>必填</b>，不许照着上下这排 `?:` 抄成可选。
   *
   * 后端对每一行都给值（{@code CodeService.sourceOf}：origin 为空的孤儿码也回 PURCHASE），
   * 所以它真缺席只有一种可能 —— 后端还没上这次改动。那正是必须在编译期吵起来的时刻，
   * 因为界面靠它决定「这个码要不要说明它没花过钱」，缺了就会静默回到原样。
   *
   * 反例就在同一个类型里：orderNo / courseTitle / boundUserId / boundAt / accessMonths
   * 全声明成可选，于是「后端根本没返回过 orderNo」这件事，在界面上只表现为
   * 「来源订单」那一列<b>恒显示「—」</b>，TS 一声不吭，线上就这么活了很久。
   *
   * orderNo 保持可选是另一回事：后端那条 SQL 是 LEFT JOIN，
   * 孤儿码（order_id 指向不存在的订单）理论上真的取不到单号。
   */
  source: CodeSource;
  courseTitle?: string | null;
  createdAt?: string | null;
  /** 课程参数：激活后能看多少个月。用来把邀请文案里的权益写准，拿不到就不写这句 */
  accessMonths?: number | null;
};

export type RevealedCode = {
  id: number;
  code: string;
  masked: string;
  status: CodeStatus;
  activateDeadline: string;
  orderNo?: string | null;
  /** 同 {@link CodeRow.source}，必填同理：CSV 的「来源」那一列就是拿它写的 */
  source: CodeSource;
};

export type StudentRow = {
  userId: number;
  phone: string;                // 已掩码
  nickname?: string | null;
  activatedAt: string;
  codeId?: number | null;
  codeMasked?: string | null;
  courseTitle?: string | null;
  validUntil?: string | null;
  progressPct?: number | null;
  finishedLessons?: number | null;
  totalLessons?: number | null;
  lastSeenAt?: string | null;
};

export type CommissionStatus = 'PENDING' | 'SETTLED' | 'WITHDRAWING' | 'WITHDRAWN' | 'REVOKED';

export type CommissionRow = {
  id: number;
  amountFen: number;
  /** 原始佣金中已因退款冲正的部分 */
  reversedFen: number;
  /** 当前有效佣金 = amountFen - reversedFen */
  netAmountFen: number;
  status: CommissionStatus;
  orderId: number;
  orderNo: string;
  sourceAgentCode?: string | null;
  sourceAgentName?: string | null;
  calcModeSnapshot: 'FIXED_PER_CODE' | 'RATE_OF_PAID' | string;
  /** 只在 calcModeSnapshot === 'RATE_OF_PAID' 时出网 —— 它等于下级的进货价，
   *  固定每码那一档不需要它，也不该让上级看见。声明成必填的话，缺席时
   *  Money 会画出 ¥0.00，读起来像佣金按 0 算了。 */
  calcBasisFen?: number;
  freezeUntil: string;
  settledAt?: string | null;
  withdrawNo?: string | null;
  revokeReason?: string | null;
  createdAt: string;
};

export type CommissionSummary = {
  pendingFen: number;
  /** ★ 累计已结算，**不是**现在能提多少 —— 提过的钱仍然算在里面 */
  settledFen: number;
  withdrawingFen: number;
  withdrawnFen: number;
  revokedFen: number;
  /** 此刻真正能申请的金额，与提现闸同一道算式（权威口径） */
  withdrawableFen?: number;
  settledIsCumulative?: boolean;
};

export type CommissionPage = Page<CommissionRow> & { summary: CommissionSummary };

export type CommissionDetail = CommissionRow & {
  order: {
    orderNo: string;
    skuName?: string | null;
    codeQty: number;
    /* amountFen / paidAmountFen 删掉了：那是【下级】那张进货单的金额，
       进货价一家一议之后等于把下级的专属价端给上级看。删而不是留着不读 ——
       留着声明，下次改这一页会被顺手画回来；删了则画回来当场 tsc 红。 */
    paidAt?: string | null;
    buyerAgentCode?: string | null;
    buyerAgentName?: string | null;
  } | null;
};

export type Balance = {
  /** 台账余额：只算已过冻结期的佣金，不含冻结中的 */
  commissionBalanceFen: number;
  /** 冻结中，还没进余额 */
  frozenFen: number;
  /** 已申请、还没打款成功的提现单合计（余额还挂着，但不能再用） */
  inflightFen: number;
  /** = commissionBalanceFen - inflightFen */
  withdrawableFen: number;
  totalSettledFen: number;
  totalWithdrawnFen: number;
  /** 提现门槛，schema 里是 10000（100 元） */
  minWithdrawFen: number;
  payeeName?: string | null;
  payeeAccount?: string | null;
  payeeBankName?: string | null;
  /** 与 apply 的收款人闸同源：true = 现在提交不会因为收款信息被拒 */
  payeeReady?: boolean;
  /** payeeReady 为 false 时，该补什么的人话；为 true 时不出这个键 */
  payeeMissing?: string | null;
};

export type WithdrawStatus =
  | 'APPLIED' | 'RISK_REJECTED' | 'REVIEWING' | 'APPROVED'
  | 'PAYING' | 'PAID' | 'FAILED' | 'CANCELLED';

export type WithdrawRow = {
  id: number;
  withdrawNo: string;
  amountFen: number;
  taxWithheldFen: number;
  actualPaidFen: number;
  channel: 'WECHAT_TRANSFER' | 'BANK_TRANSFER';
  payeeName: string;
  payeeAccount: string;
  status: WithdrawStatus;
  riskNote?: string | null;
  failReason?: string | null;
  paidAt?: string | null;
  createdAt: string;
};

/**
 * 进货橱窗里的一档。
 *
 * ★ <b>没有价格字段，而且后端也不再发</b>（AgentShopService.skus）。
 *   进货价由平台按代理逐家单独定，摆一张统一价目表本身就是错的信息；
 *   金额只在超管后台和对公转账时出现。
 *   只把界面上的 ¥ 抹掉、字段留在 JSON 里是更糟的做法：按 F12 就看得见，
 *   而"页面上不写"会让所有人以为它已经保密了。
 */
export type SkuRow = {
  skuId: number;
  skuCode: string;
  name: string;
  /** 这一档一份是多少个激活码。代理现在能看到的"量"只有它 */
  codeQty: number;
  courseTitle?: string | null;
  /** 课程参数：发码后多少个月内要激活、激活后能看多少个月 */
  activateMonths?: number | null;
  accessMonths?: number | null;
  status?: 'ON' | 'OFF';
};

export type OrderRow = {
  id: number;
  orderNo: string;
  skuCode: string;
  skuName?: string | null;
  codeQty: number;
  /**
   * ★ amountFen / paidAmountFen / refundedAmountFen 曾经在这里，进货记录的「金额」列画的就是它们。
   *   现在后端在代理这一侧整个不发（AgentShopService.view），这里也不再声明 ——
   *   声明留着的话，下一次给这张表加列的人会直接把它画回来，而且不会有任何东西变红。
   */
  status: 'CREATED' | 'PAID' | 'FULFILLED' | 'CLOSED' | 'REFUNDED' | 'PARTIAL_REFUNDED';
  /**
   * 关单时刻。★ <b>不许再拿它当任何按钮的闸</b>：付款已经改成线下对公转账，
   * 一张单等平台确认可能要几天，而 expire_at 只有 120 分钟，且库里没有任何定时任务会关它
   * （超管确认到账认的是 status='CREATED'，不看这个值）。
   * 把入口挂回这个数，代理会在提交两小时后看到「查看进度」整片消失，而单子其实还活着。
   */
  expireAt?: string | null;
  paidAt?: string | null;
  createdAt: string;
  /** 这一单是代理自己下的，还是平台超管手工发码合成出来的 */
  origin: 'SELF_SERVE' | 'ADMIN_GIFT';
  /**
   * = origin === 'ADMIN_GIFT'。后端算好给的，前端不要自己拿 origin 再判一遍：
   * 日后再多一种不收钱的来源（比如活动赠送），漏改的那一处会把它当成正常进货收钱展示。
   *
   * 必填的理由同 {@link CodeRow.source}：布尔值缺席时 `gift === true` 恒为 false，
   * 界面会安静地退回「照常画金额」，而这正是要修的那个 bug。
   */
  gift: boolean;
};

/**
 * 建单响应。<b>没有金额</b>：服务端照常按 priceOf 算价并写进 biz_order.amount_fen，
 * 只是不讲给代理听 —— 他这一步该知道的是"提交了几份、接下来等平台确认金额"。
 */
export type CreatedOrder = {
  orderId: number;
  orderNo: string;
  codeQty: number;
  skuName?: string | null;
};

// ★ PayInfo 与 AgentApi.pay（POST /api/pay/create）已从代理端撤掉：
//   线上收银台会在支付宝那一页把整单金额摆出来，那正是这一轮要拿掉的东西。
//   代理端的付款路径改成线下对公转账，金额由平台对接人单独告知。
//   2026-09-01：/api/pay/create 已对 buyer_type='AGENT' 的单直接 409（线下对公转账），
//   代理端也不再有任何调它的地方。

export type Promote = {
  agentCode: string;
  /** 学员端注册页带 ?ref= 的完整链接。由后端给，前端拼不出学员端域名 */
  refUrl: string;
  landingUrl?: string | null;
};

/* ---------------------------------------------------------------- 接口 */

export const AgentApi = {
  /**
   * 我的代理档案。
   *
   * ★ 404 不是错误，是一个答案：「你还没有代理档案」。
   *   后端对非代理有意返回 404（并附一句去申请的提示），而这一层如果原样抛出去，
   *   Shell 会把整个应用变成错误页 —— 包括 /apply，也就是非代理唯一需要的那一页。
   *   于是形成死循环：不申请就进不去，而申请页就是进不去的那个页面。
   *   实测确认过：六个页面全部显示「你还不是代理商，请先提交代理申请 重试」。
   *
   *   所以在这里把它翻译成空档案。真正的失败（500、断网）仍然照常抛。
   */
  me: () =>
    api.get<AgentMe>('/api/agent/me').catch(async (e: any) => {
      if (e?.status !== 404) throw e;
      // 档案是空的，但【人】还在 —— 外壳要显示他的手机号，Gate 要按角色说话。
      // 只回 { agent: null } 的话，App 里那句 me.user.nickname 会当场
      // TypeError: Cannot read properties of undefined，整个应用白屏，
      // 比原来那张错误页更糟（实测撞到过）。
      const u = await api.get<any>('/api/account/me').catch(() => null);
      return {
        user: {
          id: u?.id ?? 0,
          phone: u?.phone ?? '',
          nickname: u?.nickname ?? null,
          realName: u?.real_name ?? u?.realName ?? null,
          role: u?.role ?? 'STUDENT',
        },
        agent: null,
        application: null,
      } as AgentMe;
    }),

  apply: (b: {
    level: 1 | 2;
    parentAgentCode?: string;
    contactName: string;
    subjectType: SubjectType;
    companyName?: string;
    taxNo?: string;
    settleBankName?: string;
    settleBankAcct?: string;
    realName?: string;
  }) => api.post<{ ok: boolean }>('/api/agent/apply', b),

  updateProfile: (b: {
    contactName?: string;
    companyName?: string;
    taxNo?: string;
    settleBankName?: string;
    settleBankAcct?: string;
  }) => api.put<{ ok: boolean }>('/api/agent/profile', b),

  /**
   * 改自己的密码。
   *
   * ★ 路径是 /api/account/password，<b>和学员端同一个入口</b>（student 端
   *   pages/Me.tsx 一直调的就是它）。看到 /api/account/ 别以为是抄错了又改回 /api/me/ ——
   *   这里原来写的是 `PUT /api/me/password`，而整仓 22 个 @RequestMapping 里
   *   <b>一个 /api/me/ 都没有</b>（这个文件自己在 AgentApi.me 的兜底里调的就是
   *   /api/account/me）。那是笔误级的不一致，不是第二套设计。
   *
   *   两端必须共用一个入口，理由不在今天而在下一次改动：谁给改密加短信验证码、
   *   加审计、加风控，都只会改到其中一条，<b>另一条静默留成后门，
   *   而且不会有任何测试变红</b>——因为两条路各自都还"能用"。
   *
   * ★ 动词是 POST 不是 PUT：这个操作不幂等（每调一次 token_version +1，
   *   把所有设备踢一次），PUT 会让人以为重放无害。
   *   而且只改路径忘改动词的话，后端回的不是 405 而是 500「系统繁忙」
   *   （GlobalExceptionHandler 没有 HttpRequestMethodNotSupportedException 的分支），
   *   用户看到的是"平台坏了"。
   *
   * 返回体见 {@link ChangePwdResult}：reloginRequired 必须读，本机也被踢了。
   */
  changePassword: (b: { oldPassword?: string; newPassword: string }) =>
    api.post<ChangePwdResult>('/api/account/password', b),

  dashboard: () => api.get<Dashboard>('/api/agent/dashboard'),

  /**
   * 我的统计。工作台单独取它，只为拿 grantedCodes ——
   * /dashboard 那个聚合接口没有这个键，而它恰好是「我库里怎么凭空多出一批码」
   * 唯一的答案。取不到时工作台要照常出（见 Dashboard.tsx 里的兜底）：
   * 少一句解释是遗憾，四张 KPI 卡整片消失是事故。
   */
  stats: (q?: { month?: string }) => api.get<Stats>('/api/agent/stats' + qs(q || {})),

  codes: (q: { status?: string; keyword?: string; page?: number; size?: number }) =>
    api.get<Page<CodeRow>>('/api/agent/codes' + qs(q)),

  /** 单个取明文。后端写审计日志（CodeService.reveal 已实现这条） */
  reveal: (id: number) => api.post<{ code: string }>(`/api/agent/codes/${id}/reveal`),

  /** 批量取明文导出。同样逐条写审计日志 */
  exportCodes: (b: { status?: string; keyword?: string; ids?: number[] }) =>
    api.post<{ rows: RevealedCode[] }>('/api/agent/codes/export', b),

  students: (q: { keyword?: string; page?: number; size?: number; sort?: string }) =>
    api.get<Page<StudentRow>>('/api/agent/students' + qs(q)),

  commissions: (q: { status?: string; page?: number; size?: number }) =>
    api.get<CommissionPage>('/api/agent/commissions' + qs(q)),

  commission: (id: number) => api.get<CommissionDetail>(`/api/agent/commissions/${id}`),

  balance: () => api.get<Balance>('/api/agent/balance'),

  withdrawals: (q: { page?: number; size?: number }) =>
    api.get<Page<WithdrawRow>>('/api/agent/withdrawals' + qs(q)),

  applyWithdraw: (b: { amountFen: number; channel: 'BANK_TRANSFER' | 'WECHAT_TRANSFER' }) =>
    api.post<{ withdrawNo: string }>('/api/agent/withdrawals', b),

  cancelWithdraw: (id: number) =>
    api.post<{ ok: boolean }>(`/api/agent/withdrawals/${id}/cancel`),

  skus: () => api.get<Page<SkuRow>>('/api/agent/skus'),

  orders: (q: { page?: number; size?: number }) =>
    api.get<Page<OrderRow>>('/api/agent/orders' + qs(q)),

  order: (orderNo: string) => api.get<OrderRow>(`/api/agent/orders/${orderNo}`),

  /**
   * 放弃一张还没付款的进货单。
   *
   * 这条路原来是【借学员端那颗取消按钮】走的 —— 因为学员端「我的订单」
   * 曾经会把代理自己的进货单一并列出来。那个口子 2026-08-19 堵掉之后，
   * 按钮跟着没了，而库里并没有任何定时任务会替他关掉这张单。
   */
  cancelOrder: (orderNo: string) =>
    api.post<{ ok: boolean }>(`/api/agent/orders/${orderNo}/cancel`),

  /**
   * 下一张进货单。请求体只有 skuId：一张单装一个套餐档（份数靠多提交几次，见 Buy.tsx）。
   *
   * ★ 连着提交多份时<b>必须一张 await 完再发下一张</b>：shared/api.ts 的写操作去重
   *   是拿 (方法, 路径, 请求体) 当键的，而这几次请求的请求体逐字节相同。
   *   并发发出去（Promise.all）会被合并成<b>一次</b>请求，N 个调用点拿到同一个单号 ——
   *   界面显示"已提交 3 份"，库里只有 1 张单，不报任何错。
   */
  createOrder: (b: { skuId: number }) => api.post<CreatedOrder>('/api/agent/orders', b),

  promote: () => api.get<Promote>('/api/agent/promote'),
};

/* ------------------------------------------------------------ 文案映射 */

export const CODE_STATUS_HINT: Record<string, string> = {
  ISSUED: '还没被激活，可以发给学员',
  BOUND: '已经有学员激活了，不能再发给第二个人',
  REVOKED: '已作废（通常是退了这一单里没用掉的码）',
  EXPIRED: '过了激活期限，作废了',
};

export const WITHDRAW_STATUS_TEXT: Record<WithdrawStatus, string> = {
  APPLIED: '已提交',
  REVIEWING: '审核中',
  APPROVED: '待打款',
  PAYING: '打款中',
  PAID: '已到账',
  FAILED: '打款失败',
  RISK_REJECTED: '风控驳回',
  CANCELLED: '已撤销',
};

export const WITHDRAW_STATUS_TONE: Record<WithdrawStatus, 'neutral' | 'brand' | 'ok' | 'warn' | 'danger'> = {
  APPLIED: 'warn',
  REVIEWING: 'warn',
  APPROVED: 'brand',
  PAYING: 'brand',
  PAID: 'ok',
  FAILED: 'danger',
  RISK_REJECTED: 'danger',
  CANCELLED: 'neutral',
};

export const COMMISSION_STATUS_TEXT: Record<CommissionStatus, string> = {
  PENDING: '冻结中',
  SETTLED: '可提现',
  WITHDRAWING: '提现中',
  WITHDRAWN: '已提现',
  REVOKED: '已撤销',
};

export const COMMISSION_STATUS_TONE: Record<CommissionStatus, 'neutral' | 'brand' | 'ok' | 'warn' | 'danger'> = {
  PENDING: 'warn',
  SETTLED: 'ok',
  WITHDRAWING: 'brand',
  WITHDRAWN: 'neutral',
  REVOKED: 'danger',
};

/**
 * 进货单状态的人话。★ 这一份是<b>代理端专用</b>的措辞：他看不到金额，
 * 也不在这一页付款（线下对公转账），所以 CREATED 不能写「待支付」——
 * 那会让他在页面上找一个并不存在的付款按钮，找不到就再提交一单。
 */
export const ORDER_STATUS_TEXT: Record<string, string> = {
  CREATED: '待平台确认',
  PAID: '已确认到账，正在出码',
  FULFILLED: '已出码',
  CLOSED: '已关闭',
  REFUNDED: '已退款',
  PARTIAL_REFUNDED: '部分退款',
};
