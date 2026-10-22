/**
 * 后台专用的格式化与状态词表。
 *
 * 这里不复用 shared 的 STATUS_TEXT / statusTone，原因不是风格，是**它会说错话**：
 * 同一个枚举字面量在不同的表里含义不同。`ISSUED` 在 activation_code 里是「未激活」，
 * 在 invoice_request 里是「已开票」；`APPLIED` 在退款单是「待审批」，在开票申请是「待开」。
 * 一张共用词表只能选一个含义，另一个就会被**安静地翻译错** —— 运营看到发票状态写着
 * 「未激活」不会报错，只会照着这个词做出错的决定。
 *
 * 所以状态一律按「哪张表」来译，语气也一并在这里定。
 */

export type Tone = 'neutral' | 'brand' | 'ok' | 'warn' | 'danger';

type Dict = Record<string, [string, Tone]>;

const D: Record<string, Dict> = {
  /** sys_user.status */
  user: {
    ACTIVE: ['正常', 'ok'],
    FROZEN: ['已冻结', 'danger'],
    DISABLED: ['已停用', 'danger'],
  },
  /** agent_profile.status */
  agent: {
    PENDING: ['待审批', 'warn'],
    ACTIVE: ['合作中', 'ok'],
    // 「已暂停」不是「已停用」：代理端对同一个值说的就是「你的代理资格已被暂停」，
    // 两端各叫一个名字，运营和代理在电话里对不上话。
    // 而「已停用」在这份词表里已经归 sys_user.status 的 DISABLED 了，一词两义更糟。
    SUSPENDED: ['已暂停', 'danger'],
    // TERMINATED 是两件事共用的一个值：驳回申请、终止合作。分不出来的判据是
    // approved_at 有没有值（代理端就是这么分的）。这里只译字面，别在词表里编故事。
    TERMINATED: ['已终止', 'danger'],
  },
  /** biz_order.status */
  order: {
    CREATED: ['待付款', 'warn'],
    PAID: ['已付款', 'brand'],
    FULFILLED: ['已发码', 'ok'],
    CLOSED: ['已关闭', 'neutral'],
    REFUNDED: ['已全额退款', 'danger'],
    PARTIAL_REFUNDED: ['已部分退款', 'warn'],
  },
  /**
   * biz_order.origin —— 这一单是买来的还是平台白送的。
   *
   * 只有两个值，但它不能省：白送单的应付金额照样计入 sumAmountFen，
   * 而它一分钱都没收到。运营在订单页上分不出这两种单时，会把「应付合计」当成
   * 「该收多少钱」去对账 —— 数字都是真的，只是含义不是他以为的那个。
   *
   * ADMIN_GIFT 给 warn 不给 neutral：这一列里绝大多数是自助单，
   * 要一眼挑出来的恰恰是不收钱的那几张。
   */
  orderOrigin: {
    SELF_SERVE: ['自助下单', 'neutral'],
    ADMIN_GIFT: ['平台发放', 'warn'],
  },
  /** refund_record.status */
  refund: {
    APPLIED: ['待审批', 'warn'],
    APPROVED: ['已批准待打款', 'brand'],
    SUCCESS: ['已退款到账', 'ok'],
    FAILED: ['打款失败', 'danger'],
    REJECTED: ['已驳回', 'neutral'],
  },
  /** withdraw_order.status */
  withdraw: {
    APPLIED: ['待审核', 'warn'],
    REVIEWING: ['审核中', 'warn'],
    APPROVED: ['已批准待打款', 'brand'],
    PAYING: ['打款中', 'brand'],
    PAID: ['已打款', 'ok'],
    FAILED: ['打款失败', 'danger'],
    RISK_REJECTED: ['已驳回', 'danger'],
    CANCELLED: ['已撤销', 'neutral'],
  },
  /** activation_code.status */
  code: {
    ISSUED: ['未激活', 'warn'],
    BOUND: ['已激活', 'ok'],
    EXPIRED: ['已过期', 'neutral'],
    REVOKED: ['已作废', 'danger'],
  },
  /** course.status / lesson.status */
  content: {
    DRAFT: ['草稿', 'neutral'],
    ON: ['已上架', 'ok'],
    OFF: ['已下架', 'warn'],
  },
  /** enrollment.status */
  enrollment: {
    ACTIVE: ['可观看', 'ok'],
    EXPIRED: ['已到期', 'neutral'],
    SUSPENDED: ['已暂停', 'danger'],
  },
  /** user_device.status */
  device: {
    ACTIVE: ['在用', 'ok'],
    REMOVED: ['已解绑', 'neutral'],
  },
  /** qa_ticket.status */
  ticket: {
    AI_ANSWERED: ['AI 已答', 'neutral'],
    ESCALATED: ['等人工回复', 'warn'],
    HUMAN_ANSWERED: ['人工已答', 'ok'],
    CLOSED: ['已关闭', 'neutral'],
  },
  /** invoice_request.status */
  invoice: {
    APPLIED: ['待开票', 'warn'],
    ISSUED: ['已开票', 'ok'],
    REJECTED: ['已驳回', 'neutral'],
  },
  /** commission_record.status */
  commission: {
    PENDING: ['冻结中', 'warn'],
    SETTLED: ['可提现', 'ok'],
    WITHDRAWING: ['提现中', 'brand'],
    WITHDRAWN: ['已提现', 'neutral'],
    REVOKED: ['已撤销', 'danger'],
  },
  /** risk_action.action */
  riskAction: {
    WATCH: ['观察', 'neutral'],
    CAPTCHA: ['要求人机验证', 'warn'],
    SMS_VERIFY: ['要求短信验证', 'warn'],
    FREEZE_24H: ['冻结 24 小时', 'danger'],
    SUSPEND: ['停用账号', 'danger'],
    NONE: ['无需处置', 'neutral'],
  },
  /**
   * payment_record.provider / biz_order.pay_channel / withdraw_order.channel
   *
   * 缺键不会报错也不会显示空 —— label() 的兜底是 `?? value`，直接把英文字面量
   * 原样印在页面上。支付切成支付宝之后这里漏了三个键，后台每一笔支付宝订单的
   * 「支付方式」列都写着 ALIPAY_PAGE，运营看了半天以为是脏数据。
   * 文案取支付宝官方产品名（电脑网站支付 / 手机网站支付），能直接跟对账单对上；
   * 「支付宝」这个前缀不能省，这一列里同时还有「微信扫码」。
   */
  channel: {
    ALIPAY: ['支付宝', 'neutral'],
    ALIPAY_PAGE: ['支付宝电脑网站', 'neutral'],
    ALIPAY_WAP: ['支付宝手机网站', 'neutral'],
    WECHAT: ['微信', 'neutral'],
    WECHAT_NATIVE: ['微信扫码', 'neutral'],
    WECHAT_JSAPI: ['微信内支付', 'neutral'],
    BANK_TRANSFER: ['对公转账', 'brand'],
    WECHAT_TRANSFER: ['微信转账', 'neutral'],
  },
  /** refund_record.scope */
  refundScope: {
    UNUSED_CODES: ['只退未激活的码', 'ok'],
    FULL: ['整单退款', 'danger'],
    MANUAL: ['人工特批金额', 'warn'],
  },
  /**
   * activation_code 出网时的 source（后端 CodeService.sourceOf 由 biz_order.origin 映射）。
   *
   * 词面必须和 orderOrigin 逐字一样：它们说的是同一件事（这个码挂的那张单是买的还是送的），
   * 只是一个从码这一侧看、一个从单那一侧看。两处各说一个词的话，运营会以为是两回事，
   * 然后在导出的 CSV 和订单页之间来回核对一个根本不存在的差异。
   */
  codeSource: {
    PURCHASE: ['自助下单', 'neutral'],
    PLATFORM_GRANT: ['平台发放', 'warn'],
  },
  /** enrollment.source / sys_user.attribution_source */
  source: {
    ACTIVATION_CODE: ['激活码', 'neutral'],
    DIRECT_PURCHASE: ['直接购买', 'neutral'],
    GRANT: ['平台赠送', 'neutral'],
    REF_LINK: ['推广链接', 'neutral'],
    MANUAL: ['人工指定', 'warn'],
  },
  /** agent_profile.subject_type —— §7.4 推荐个体工商户/企业主体，个人抬头要被看见 */
  subject: {
    INDIVIDUAL: ['个人', 'warn'],
    SOLE_PROPRIETOR: ['个体工商户', 'ok'],
    COMPANY: ['公司', 'ok'],
  },
  /** 代理层级 */
  level: {
    '1': ['一级代理', 'brand'],
    '2': ['二级代理', 'neutral'],
  },
  role: {
    PLATFORM_ADMIN: ['平台管理员', 'brand'],
    AGENT: ['代理商', 'neutral'],
    STUDENT: ['学员', 'neutral'],
    MENTOR: ['导师', 'neutral'],
  },
};

export type Domain = keyof typeof D;

export function label(domain: Domain, value?: string | null): string {
  if (!value) return '—';
  return D[domain]?.[value]?.[0] ?? value;
}

export function tone(domain: Domain, value?: string | null): Tone {
  if (!value) return 'neutral';
  return D[domain]?.[value]?.[1] ?? 'neutral';
}

/** 某一域的全部取值，给筛选下拉用。顺序即业务流程顺序，不按字母排。 */
export function options(domain: Domain): { value: string; text: string }[] {
  return Object.entries(D[domain] ?? {}).map(([value, [text]]) => ({ value, text }));
}

/* ------------------------------------------------------------------ 时间 */

/**
 * 时间格式化。刻意做得比 shared 的 dateTime 更能扛：
 * 后端的 LocalDateTime 在 Jackson 配置不同时会序列化成 ISO 串、毫秒数或
 * `[2026,8,11,9,30]` 这样的数组。前端把数组渲染成 "2026,8,11" 不会报错，
 * 只会让整列时间变成一串看不懂的数字，而且没人知道是哪一侧改的。
 */
export function fmtTime(v: unknown, withSeconds = false): string {
  const d = toDate(v);
  if (!d) return '—';
  const p = (n: number) => String(n).padStart(2, '0');
  const base = `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} `
             + `${p(d.getHours())}:${p(d.getMinutes())}`;
  return withSeconds ? `${base}:${p(d.getSeconds())}` : base;
}

export function fmtDate(v: unknown): string {
  const d = toDate(v);
  if (!d) return '—';
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

function toDate(v: unknown): Date | null {
  if (v === null || v === undefined || v === '') return null;
  if (v instanceof Date) return isNaN(+v) ? null : v;
  if (typeof v === 'number') { const d = new Date(v); return isNaN(+d) ? null : d; }
  if (Array.isArray(v) && v.length >= 3) {
    const [y, mo, da, h = 0, mi = 0, s = 0] = v as number[];
    return new Date(y, mo - 1, da, h, mi, s);
  }
  if (typeof v === 'string') {
    const d = new Date(v.replace(' ', 'T'));
    return isNaN(+d) ? null : d;
  }
  return null;
}

/** 还剩多久到期。绝对日期看不出紧迫感，"还剩 3 天"才看得出。 */
export function untilText(v: unknown): string {
  const d = toDate(v);
  if (!d) return '—';
  const days = Math.ceil((+d - Date.now()) / 86400000);
  if (days < 0) return '已到期';
  if (days === 0) return '今天到期';
  if (days <= 30) return `还剩 ${days} 天`;
  return `${fmtDate(v)} 到期`;
}

/* ------------------------------------------------------------------ 金额 */

/**
 * 元 → 分。
 *
 * <p>三位及以上小数一律**报错**，不四舍五入 —— 这条和 schema 顶部那句警告是同一条：
 * 12.345 元被静默截断成 12 分，少收的钱不会有任何日志。前端多收一位、少收一位，
 * 到了后端就是一次金额不符的入账拒绝，运营还得回来猜是哪里错了。
 */
export function fenFromYuan(input: string): number {
  const s = (input ?? '').trim().replace(/,/g, '');
  if (!/^\d+(\.\d{1,2})?$/.test(s))
    // 示例数字【不要用真实价目】。原来这里写的是 3490，它逐字等于二级批量价 349000 分，
    // 而这行字符串会被打进 /admin/assets/*.js —— 那个包无需登录就能下载。
    throw new Error('金额请填成元，最多两位小数（例：100 或 100.00）');
  const [int, dec = ''] = s.split('.');
  const fen = Number(int) * 100 + Number((dec + '00').slice(0, 2));
  if (!Number.isSafeInteger(fen)) throw new Error('金额超出可处理范围');
  return fen;
}

/** 分 → 可编辑的元字符串（不带符号、不带千分位，回填输入框用）。 */
export function yuanInput(fen: number | null | undefined): string {
  if (fen === null || fen === undefined) return '';
  return (fen / 100).toFixed(2);
}

/** 手机号掩码。后端已经掩过，这里兜住偶尔漏掩的字段。 */
export function maskPhone(p?: string | null): string {
  if (!p) return '—';
  return p.length >= 11 ? `${p.slice(0, 3)}****${p.slice(-4)}` : p;
}

/**
 * 宽松取数。用在「后端给的值可能是数字、可能是数字字符串」的地方。
 *
 * <p><b>布尔要单独认一档。</b>后端的 tinyint(1) 经 {@code AdminQuery.flag} 出网时是
 * 真正的 JSON {@code true}/{@code false}（不是 0/1），而 {@code typeof true !== 'number'}，
 * 于是它会一路掉到 dflt。讲次编辑页的 {@code isPreview} 就是这么坏的：
 * 一条试听讲打开编辑框永远回显成「正片」，随便改个标题保存，
 * 就把 is_preview 静默改回了 0 —— 页面不报错，列表上那个标签第二天才发现没了。
 */
export function num(v: unknown, dflt = 0): number {
  if (typeof v === 'boolean') return v ? 1 : 0;
  const n = typeof v === 'string' ? Number(v) : (v as number);
  return typeof n === 'number' && isFinite(n) ? n : dflt;
}

/** 今天 / 本月第一天，YYYY-MM-DD。后端的日期参数就吃这个格式。 */
export function todayStr(): string { return fmtDate(new Date()); }
export function monthStartStr(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-01`;
}
/**
 * 导出 CSV 的三件套。实现在 shared/src/csv.ts —— 本轮它一度在这里和代理端
 * kit.tsx 里各有一份逐字节相同的拷贝，而其中「公式注入转义」是安全判据。
 * 这里保留再导出，调用点不用改 import。
 */
export { csvCell, downloadCsv, stamp } from '@wangke/shared/csv';
