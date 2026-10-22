/**
 * 共用组件。三端都从这里取，页面里不许再写一套。
 *
 * 每个组件都必须把【三种非正常状态】处理掉：加载中、空、出错。
 * 只画"有数据"那一种是前端最常见的偷工：真实系统里空态和错态出现的频率
 * 远高于设计稿里的样子，而一个没画空态的列表在没数据时就是一片惨白，
 * 用户不知道是没数据还是挂了。
 *
 * ## 这一版按 DragonAI DS v2.1 重做了外观与交互
 *
 * 三条改动是结构性的，不只是换色：
 *
 * 1. **悬停/按下从 JS 搬到 CSS**（tokens.css 里的 .wk-*）。
 *    原来靠 onMouseEnter 改 style —— 在触屏上 onMouseEnter 会「粘住」：
 *    手指点过的那一行会一直保持悬停色，直到点了别处。手机是学员端的主场景。
 *
 * 2. **表格在窄屏改成卡片**。横向滚动的表格在手机上要么看不全、
 *    要么滑到一半忘了这行是谁 —— 而代理商查授权码、运营在路上审一单，
 *    都是真实的手机场景。
 *
 * 3. **状态不再是一排彩色药丸**，改成 DS 的「点 + 字重」。
 *    后台一屏两百行，五种颜色的药丸铺满之后哪一个都不显眼了。
 */
import React from 'react';
import { auth } from './api';

/* ================================================================ 按钮 */

type BtnProps = React.ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: 'primary' | 'accent' | 'ghost' | 'danger' | 'quiet';
  size?: 'sm' | 'md' | 'lg';
  loading?: boolean;
  block?: boolean;
};

export function Button({ variant = 'primary', size = 'md', loading, block,
                         children, disabled, style, className, ...rest }: BtnProps) {
  const off = disabled || loading;
  // 手机上按钮至少 44px 高：低于这个数就是「点不中」，而点不中的那一次
  // 用户不会重试，他会打客服电话。桌面上不需要那么高，用 min-height 兜底。
  const pad = size === 'sm' ? '7px 13px' : size === 'lg' ? '13px 26px' : '10px 20px';
  const fs = size === 'sm' ? 'var(--t-sm)' : size === 'lg' ? 'var(--t-h3)' : 'var(--t-body)';
  const minH = size === 'sm' ? 32 : size === 'lg' ? 48 : 40;
  return (
    <button
      {...rest}
      disabled={off}
      data-variant={variant}
      // 「正在提交」和「不能点」是两回事，禁用配色只给后者，见 tokens.css
      data-busy={loading ? '' : undefined}
      className={['wk-btn', `wk-btn-${variant}`, className].filter(Boolean).join(' ')}
      style={{
        padding: pad, fontSize: fs, minHeight: minH,
        display: block ? 'flex' : 'inline-flex', width: block ? '100%' : undefined,
        ...style,
      }}
    >
      {loading && <Spinner size={size === 'sm' ? 12 : 14} />}
      {children}
    </button>
  );
}

export function Spinner({ size = 16, color }: { size?: number; color?: string }) {
  return (
    <span
      aria-hidden
      style={{
        width: size, height: size, display: 'inline-block', flexShrink: 0,
        border: `1.5px solid ${color || 'currentColor'}`, borderTopColor: 'transparent',
        borderRadius: '50%', animation: 'wk-spin .7s linear infinite',
      }}
    />
  );
}

/* ================================================================ 输入 */

type FieldProps = {
  label?: string; hint?: string; error?: string; required?: boolean;
  /** 右上角的一枚单等宽小标记，DS 表单卡里的 REQUIRED / EMAIL 那一栏 */
  meta?: React.ReactNode;
  children: React.ReactNode;
};

export function Field({ label, hint, error, required, meta, children }: FieldProps) {
  return (
    <label style={{ display: 'block', marginBottom: 'var(--s5)' }}>
      {label && (
        <span style={{
          display: 'flex', justifyContent: 'space-between', alignItems: 'baseline',
          gap: 'var(--s3)', fontSize: 'var(--t-sm)', color: 'var(--ink-2)',
          marginBottom: 'var(--s1)', fontWeight: 500,
        }}>
          <span>
            {label}
            {required && <span style={{ color: 'var(--brand)', marginLeft: 3 }}>*</span>}
          </span>
          {meta && <span className="da-overline bare" style={{ color: 'var(--ink-4)' }}>{meta}</span>}
        </span>
      )}
      {children}
      {/* 错误压住提示：两个都显示时用户不知道该看哪个 */}
      {error
        ? <span style={{ display: 'block', fontSize: 'var(--t-sm)', color: 'var(--brand)',
                         marginTop: 'var(--s2)', fontWeight: 500 }}>{error}</span>
        : hint
          ? <span style={{ display: 'block', fontSize: 'var(--t-sm)', color: 'var(--ink-3)',
                           marginTop: 'var(--s2)' }}>{hint}</span>
          : null}
    </label>
  );
}

export const Input = React.forwardRef<HTMLInputElement,
    React.InputHTMLAttributes<HTMLInputElement> & { invalid?: boolean }>(
  function Input({ invalid, className, ...rest }, ref) {
    return (
      <input
        ref={ref} {...rest}
        aria-invalid={invalid || undefined}
        className={['wk-input', className].filter(Boolean).join(' ')}
      />
    );
  });

export function Select({ children, className, ...rest }: React.SelectHTMLAttributes<HTMLSelectElement>) {
  return (
    <select {...rest} className={['wk-input', className].filter(Boolean).join(' ')}>
      {children}
    </select>
  );
}

export const Textarea = React.forwardRef<HTMLTextAreaElement,
    React.TextareaHTMLAttributes<HTMLTextAreaElement> & { invalid?: boolean }>(
  function Textarea({ invalid, className, style, ...rest }, ref) {
    return (
      <textarea
        ref={ref} {...rest}
        aria-invalid={invalid || undefined}
        className={['wk-input', className].filter(Boolean).join(' ')}
        style={{ resize: 'vertical', minHeight: 88, lineHeight: 'var(--lh-body)', ...style }}
      />
    );
  });

/* ================================================================ 容器 */

export function Card({ children, pad = true, accent, style, className, ...rest }:
    React.HTMLAttributes<HTMLDivElement> & { pad?: boolean; accent?: boolean }) {
  return (
    <div {...rest} className={['wk-card', className].filter(Boolean).join(' ')} style={{
      background: 'var(--surface)',
      border: '1px solid var(--line)',
      // DS：卡片靠发丝线立结构，静止时不投影。投影在纸感底上会像贴纸，
      // 而且暗示「可拖动」—— 这些卡片都不能拖。
      borderTop: accent ? '2px solid var(--brand)' : undefined,
      borderRadius: 'var(--r-lg)',
      padding: pad ? 'var(--pad-card)' : 0,
      ...style,
    }}>{children}</div>
  );
}

export function Panel({ title, extra, eyebrow, accent, children, style }: {
  title?: React.ReactNode; extra?: React.ReactNode; eyebrow?: React.ReactNode;
  accent?: boolean; children: React.ReactNode; style?: React.CSSProperties;
}) {
  return (
    <Card pad={false} accent={accent} style={style}>
      {(title || extra || eyebrow) && (
        <div style={{
          display: 'flex', alignItems: 'center', justifyContent: 'space-between',
          gap: 'var(--s3)', padding: 'var(--s4) var(--pad-card)', flexWrap: 'wrap',
          borderBottom: '1px solid var(--line)',
        }}>
          <div style={{ minWidth: 0 }}>
            {eyebrow && <div className="da-overline" style={{ marginBottom: 5 }}>{eyebrow}</div>}
            <div style={{ fontSize: 'var(--t-h3)', fontWeight: 600, color: 'var(--ink-0)' }}>{title}</div>
          </div>
          {extra}
        </div>
      )}
      <div style={{ padding: 'var(--pad-card)' }}>{children}</div>
    </Card>
  );
}

/** DS 的区段头：单等宽编号 + 中文题 + 右侧元信息，压在一条实线上。 */
export function SectionHead({ no, title, en, meta, hair }: {
  no?: string; title: React.ReactNode; en?: string; meta?: React.ReactNode; hair?: boolean;
}) {
  return (
    <div style={{
      display: 'flex', alignItems: 'baseline', gap: 'var(--s3)', flexWrap: 'wrap',
      paddingBottom: 'var(--s2)', marginBottom: 'var(--s4)',
      borderBottom: `1px solid ${hair ? 'var(--line)' : 'var(--line-ink)'}`,
    }}>
      {no && <span className="num" style={{ fontSize: 'var(--t-mono-xs)', color: 'var(--brand)',
                                            letterSpacing: '.2em', fontWeight: 500 }}>{no}</span>}
      <span style={{ fontSize: 'var(--t-h3)', fontWeight: 600, color: 'var(--ink-0)' }}>{title}</span>
      {en && <span className="num" style={{ fontSize: 'var(--t-mono-xs)', color: 'var(--ink-3)',
                                            letterSpacing: '.14em', textTransform: 'uppercase' }}>{en}</span>}
      {meta && <span style={{ marginLeft: 'auto', fontSize: 'var(--t-xs)', color: 'var(--ink-3)' }}>{meta}</span>}
    </div>
  );
}

/** 单等宽眉标。`<Overline>AGENT / 授权码</Overline>` */
export function Overline({ children, bare, style }: {
  children: React.ReactNode; bare?: boolean; style?: React.CSSProperties;
}) {
  return <span className={bare ? 'da-overline bare' : 'da-overline'} style={style}>{children}</span>;
}

/* ================================================================ 状态 */

export function Skeleton({ h = 16, w = '100%', style }:
    { h?: number | string; w?: number | string; style?: React.CSSProperties }) {
  return <div aria-hidden style={{
    height: h, width: w, borderRadius: 'var(--r-md)',
    background: 'linear-gradient(90deg,var(--surface-2) 25%,var(--surface-3) 37%,var(--surface-2) 63%)',
    backgroundSize: '400% 100%', animation: 'wk-shimmer 1.4s ease infinite', ...style,
  }} />;
}

/**
 * 空态。
 *
 * 必须区分「还没有」和「筛选没命中」——前者要给用户一条出路（去做点什么），
 * 后者要提示他改条件。两种都画成"暂无数据"，用户会以为系统坏了。
 *
 * 视觉上不再放一个巨大的灰点：DS 的空是「有质感的空」——
 * 一小块斜纹底 + 单等宽标记，读起来像预留位而不像故障。
 */
export function Empty({ icon, title, desc, action }: {
  icon?: React.ReactNode; title: string; desc?: string; action?: React.ReactNode;
}) {
  return (
    <div style={{ textAlign: 'center', padding: 'var(--s7) var(--s5)', color: 'var(--ink-3)' }}>
      <div className="da-hatchbg" aria-hidden style={{
        width: 56, height: 56, margin: '0 auto var(--s4)',
        border: '1px solid var(--brand-line)',
        display: 'grid', placeItems: 'center',
        fontSize: 20, color: 'var(--brand)', opacity: .7,
      }}>{icon}</div>
      <div style={{ fontSize: 'var(--t-h3)', color: 'var(--ink)', fontWeight: 600 }}>{title}</div>
      {desc && <div style={{ marginTop: 'var(--s2)', fontSize: 'var(--t-sm)', maxWidth: 420,
                             marginInline: 'auto', lineHeight: 'var(--lh-body)' }}>{desc}</div>}
      {action && <div style={{ marginTop: 'var(--s5)' }}>{action}</div>}
    </div>
  );
}

/** 出错态。必须带重试——只显示错误信息等于让用户自己刷新页面。 */
export function ErrorState({ message, onRetry }: { message: string; onRetry?: () => void }) {
  return (
    <div style={{ textAlign: 'center', padding: 'var(--s7) var(--s5)' }}>
      <span className="da-overline en" style={{ color: 'var(--brand)' }}>ERROR</span>
      <div style={{ color: 'var(--ink)', fontWeight: 500, marginTop: 'var(--s3)',
                    maxWidth: 480, marginInline: 'auto', lineHeight: 'var(--lh-body)' }}>{message}</div>
      {onRetry && <div style={{ marginTop: 'var(--s4)' }}>
        <Button variant="ghost" size="sm" onClick={onRetry}>重试</Button>
      </div>}
    </div>
  );
}

/** 加载 / 出错 / 空 / 有数据，四态一次写完，页面里不再各写一遍。 */
export function Async<T>({ state, empty, children }: {
  state: { loading: boolean; error?: string; data?: T; reload?: () => void };
  empty?: React.ReactNode;
  children: (data: T) => React.ReactNode;
}) {
  if (state.loading) return (
    <div style={{ display: 'grid', gap: 'var(--s3)' }}>
      <Skeleton h={18} w="42%" /><Skeleton h={14} /><Skeleton h={14} w="80%" />
    </div>
  );
  if (state.error) return <ErrorState message={state.error} onRetry={state.reload} />;
  const d = state.data;
  const isEmpty = d == null || (Array.isArray(d) && d.length === 0);
  if (isEmpty) return <>{empty ?? <Empty title="暂无数据" />}</>;
  return <>{children(d as T)}</>;
}

/* ================================================================ 标记 */

type Tone = 'neutral' | 'brand' | 'ok' | 'warn' | 'danger';

/**
 * 状态标记。
 *
 * DS 的做法：一个点 + 一段字，不是一颗彩色药丸。
 * 后台一屏两百行，五种颜色的药丸铺满之后哪一个都不显眼；
 * 而「点」只占 7px，颜色预算几乎为零，扫一列时反而更容易看出异常那几行。
 */
export function Tag({ tone = 'neutral', children }: { tone?: Tone; children: React.ReactNode }) {
  // brand 用空心红环 + 墨字，不是实心红。后台把 brand 用在「已付款」「一级代理」
  // 「打款中」这类**在途/可注意**的状态上，全给实心红会让每一屏都像在告警，
  // 而真正的异常（danger）就淹没在里面了。
  const dot = tone === 'ok' ? 'ok'
            : tone === 'warn' ? 'warn'
            : tone === 'danger' ? 'down'
            : tone === 'brand' ? 'mid'
            : 'idle';
  const color = tone === 'danger' ? 'var(--brand)'
              : tone === 'warn' ? 'var(--warn)'
              : tone === 'ok' || tone === 'brand' ? 'var(--ink)'
              : 'var(--ink-3)';
  return (
    <span style={{
      display: 'inline-flex', alignItems: 'center', gap: 6, color,
      fontSize: 'var(--t-xs)', fontWeight: 500, whiteSpace: 'nowrap',
    }}>
      <i className={`da-sdot ${dot}`} aria-hidden />
      {children}
    </span>
  );
}

/** 有边框的小标签。用于「不是状态」的分类标记（层级、类型、来源）。 */
export function Chip({ children, on, style }: {
  children: React.ReactNode; on?: boolean; style?: React.CSSProperties;
}) {
  return (
    <span style={{
      display: 'inline-flex', alignItems: 'center', gap: 5,
      border: `1px solid ${on ? 'var(--brand)' : 'var(--line-strong)'}`,
      color: on ? 'var(--brand)' : 'var(--ink-2)',
      background: on ? 'var(--brand-soft)' : 'transparent',
      padding: '2px 8px', fontSize: 'var(--t-xs)', whiteSpace: 'nowrap',
      borderRadius: 'var(--r-md)', ...style,
    }}>{children}</span>
  );
}

/** 状态 → 语气。集中一处映射，免得同一个状态在不同页面显示成不同颜色。 */
export function statusTone(s?: string): Tone {
  switch (s) {
    case 'ACTIVE': case 'SUCCESS': case 'PAID': case 'FULFILLED': case 'BOUND': case 'ON':
    case 'APPROVED':
      return 'ok';
    case 'PENDING': case 'CREATED': case 'ISSUED': case 'APPLIED': case 'REVIEWING': case 'PAYING':
      return 'warn';
    case 'FROZEN': case 'DISABLED': case 'REJECTED': case 'FAILED': case 'REVOKED':
    case 'TERMINATED': case 'EXPIRED': case 'SUSPENDED':
      return 'danger';
    default: return 'neutral';
  }
}

/**
 * 一张跨表共用的状态词表 —— 用它之前先确认这个字面量在你那张表里是不是这个意思。
 *
 * <p>同一个枚举值在不同的表里含义不同：`ISSUED` 在 activation_code 里是「未激活」，
 * 在 invoice_request 里是「已开票」；`APPLIED` 在退款单是「待审批」，在开票申请是「待开」。
 * 一张共用词表只能选一个含义，另一个就会被<b>安静地翻译错</b>。
 * 后台因此不用这里，自己按表分域译（apps/admin/src/lib/fmt.ts）。
 *
 * <p>这里的取值是按<b>面向学员 / 代理</b>那几处实际用法定的：
 * sys_user.status、agent_profile.status、activation_code.status、biz_order.status。
 * 别的表要用，先来这里确认，或者像后台那样自己分域。
 *
 * <p>订单那几档用的是学员看得懂的说法：他买的是课，不是货 ——
 * FULFILLED 之后这一页给他的按钮就是「查看激活码」，所以叫「已出码」而不是「已发货」。
 */
export const STATUS_TEXT: Record<string, string> = {
  ACTIVE: '正常', FROZEN: '已冻结', DISABLED: '已停用',
  PENDING: '待审核', SUSPENDED: '已暂停', TERMINATED: '已终止',
  CREATED: '待支付', PAID: '已付款', FULFILLED: '已出码', CLOSED: '已关闭',
  REFUNDED: '已退款', PARTIAL_REFUNDED: '部分退款',
  ISSUED: '未激活', BOUND: '已激活', REVOKED: '已作废', EXPIRED: '已过期',
  APPLIED: '待处理', APPROVED: '已通过', REJECTED: '已驳回',
  SUCCESS: '成功', FAILED: '失败', REVIEWING: '审核中', PAYING: '打款中',
  ON: '已上架', OFF: '已下架', DRAFT: '草稿',
};

export function StatusTag({ status }: { status?: string }) {
  if (!status) return <>—</>;
  return <Tag tone={statusTone(status)}>{STATUS_TEXT[status] ?? status}</Tag>;
}

/* ================================================================ 表格 */

export type Col<T> = {
  key: string;
  title: React.ReactNode;
  render?: (row: T, i: number) => React.ReactNode;
  width?: number | string;
  align?: 'left' | 'right' | 'center';
  /** 金额、单号这类要等宽对齐 */
  numeric?: boolean;
  /** 窄屏卡片模式下当作标题行。不指定时取第一列。 */
  primary?: boolean;
  /** 窄屏卡片模式下藏起来。给那些只在大屏对照时才有用的列。 */
  hideOnMobile?: boolean;
};

/**
 * 表格。
 *
 * 窄屏（≤860）自动改成卡片列表，**不是**让它横向滚。
 * 横向滚的表格在手机上有个具体的坏处：滑到第四列时已经看不见第一列，
 * 而第一列往往正是「这行是谁」—— 用户得来回滑两次才能确认一条记录。
 * 卡片把每一行摊成「标题 + 若干键值」，一眼到底。
 *
 * 表头下沿是 1.5px 实墨线（DS 密度层的做法），不是浅灰：
 * 密排表里浅灰表头和数据行糊在一起，扫列时找不到基准。
 */
export function Table<T>({ cols, rows, rowKey, empty, onRowClick, cards = true }: {
  cols: Col<T>[]; rows: T[]; rowKey: (r: T, i: number) => string | number;
  empty?: React.ReactNode; onRowClick?: (r: T) => void;
  /** 关掉窄屏卡片化。只有「本来就是矩阵、必须横向对照」的表才该关。 */
  cards?: boolean;
}) {
  if (!rows.length) return <>{empty ?? <Empty title="暂无数据" />}</>;

  const primary = cols.find(c => c.primary) ?? cols[0];
  const restCols = cols.filter(c => c !== primary && !c.hideOnMobile);
  const cell = (c: Col<T>, r: T, i: number) => (c.render ? c.render(r, i) : (r as any)[c.key]);

  return (
    <>
      <div className={cards ? 'wk-hide-mobile' : undefined} style={{ overflowX: 'auto' }}>
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 'var(--t-sm)' }}>
          <thead>
            <tr>{cols.map(c => (
              <th key={c.key} style={{
                textAlign: c.align ?? (c.numeric ? 'right' : 'left'),
                padding: '8px 12px', width: c.width, whiteSpace: 'nowrap',
                background: 'var(--surface)', color: 'var(--ink-2)', fontWeight: 500,
                fontSize: 'var(--t-xs)', letterSpacing: '.04em',
                borderBottom: '1.5px solid var(--line-ink)',
                position: 'sticky', top: 0, zIndex: 1,
              }}>{c.title}</th>
            ))}</tr>
          </thead>
          <tbody>
            {rows.map((r, i) => (
              <tr key={rowKey(r, i)}
                  className={onRowClick ? 'wk-row clickable' : 'wk-row'}
                  onClick={onRowClick ? () => onRowClick(r) : undefined}
                  style={{ height: 'var(--row-h)' }}>
                {cols.map(c => (
                  <td key={c.key} className={c.numeric ? 'num' : undefined} style={{
                    padding: '8px 12px', borderBottom: '1px solid var(--line)',
                    textAlign: c.align ?? (c.numeric ? 'right' : 'left'),
                  }}>{cell(c, r, i)}</td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {cards && (
        <div className="wk-only-mobile" style={{ display: 'grid', gap: 'var(--s3)' }}>
          {rows.map((r, i) => (
            <div key={rowKey(r, i)}
                 onClick={onRowClick ? () => onRowClick(r) : undefined}
                 style={{
                   border: '1px solid var(--line)', borderRadius: 'var(--r-lg)',
                   background: 'var(--surface)', padding: 'var(--s3) var(--s4)',
                   cursor: onRowClick ? 'pointer' : undefined,
                 }}>
              <div style={{ fontWeight: 600, color: 'var(--ink-0)', marginBottom: 'var(--s2)',
                            paddingBottom: 'var(--s2)', borderBottom: '1px solid var(--line)',
                            display: 'flex', alignItems: 'center', gap: 'var(--s2)',
                            justifyContent: 'space-between', flexWrap: 'wrap' }}>
                <span className={primary.numeric ? 'num' : undefined}
                      style={{ minWidth: 0, wordBreak: 'break-all' }}>{cell(primary, r, i)}</span>
              </div>
              <div style={{ display: 'grid', gap: 5 }}>
                {restCols.map(c => (
                  <div key={c.key} style={{
                    display: 'flex', gap: 'var(--s3)', alignItems: 'baseline',
                    justifyContent: 'space-between', fontSize: 'var(--t-sm)',
                  }}>
                    <span style={{ color: 'var(--ink-3)', flex: 'none', fontSize: 'var(--t-xs)' }}>{c.title}</span>
                    <span className={c.numeric ? 'num' : undefined}
                          style={{ textAlign: 'right', minWidth: 0, wordBreak: 'break-word' }}>
                      {cell(c, r, i)}
                    </span>
                  </div>
                ))}
              </div>
            </div>
          ))}
        </div>
      )}
    </>
  );
}

/* ================================================================ 反馈 */

type Toast = { id: number; tone: 'ok' | 'danger' | 'info'; text: string };
const ToastCtx = React.createContext<(t: Omit<Toast, 'id'>) => void>(() => {});
export const useToast = () => React.useContext(ToastCtx);

export function ToastHost({ children }: { children: React.ReactNode }) {
  const [items, setItems] = React.useState<Toast[]>([]);
  const push = React.useCallback((t: Omit<Toast, 'id'>) => {
    const id = Date.now() + Math.random();
    setItems(v => [...v, { ...t, id }]);
    // 出错的多停一会儿：报错文案往往比"已保存"长，一晃而过等于没说
    setTimeout(() => setItems(v => v.filter(x => x.id !== id)), t.tone === 'danger' ? 5200 : 2800);
  }, []);
  return (
    <ToastCtx.Provider value={push}>
      {children}
      <div style={{
        position: 'fixed', top: 'calc(var(--header-h) + var(--s3))', left: '50%',
        transform: 'translateX(-50%)',
        zIndex: 'var(--z-toast)' as any, display: 'grid', gap: 'var(--s2)', pointerEvents: 'none',
        width: 'max-content', maxWidth: 'min(92vw, 560px)',
      }}>
        {items.map(t => (
          <div key={t.id} role="status" style={{
            // 墨底 + 左侧一道语义色。整块染成红/绿的提示条在纸感底上太抢，
            // 而「已复制」这种小事不配占用一整条红。
            background: 'var(--da-ink)', color: 'var(--da-bg)',
            borderLeft: `3px solid ${t.tone === 'danger' ? 'var(--da-red-500)'
                                   : t.tone === 'ok' ? 'var(--da-orange-400)' : 'var(--da-ink-3)'}`,
            padding: '10px 18px', borderRadius: 'var(--r-md)',
            boxShadow: 'var(--shadow-3)', fontSize: 'var(--t-sm)',
            lineHeight: 'var(--lh-snug)',
            animation: 'wk-drop .18s var(--ease)',
          }}>{t.text}</div>
        ))}
      </div>
    </ToastCtx.Provider>
  );
}

/**
 * 弹窗。
 *
 * 手机上从底部升起并贴住底边（bottom sheet）：居中的弹窗在小屏上
 * 会被软键盘顶掉一半，而底部弹层是被键盘往上推，输入框始终可见。
 */
export function Modal({ open, title, onClose, children, footer, width = 480 }: {
  open: boolean; title?: React.ReactNode; onClose: () => void;
  children: React.ReactNode; footer?: React.ReactNode; width?: number;
}) {
  React.useEffect(() => {
    if (!open) return;
    const h = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', h);
    // 打开时锁住背景滚动：不锁的话手机上滑弹窗会带着后面的列表一起走，
    // 关掉弹窗才发现列表已经滚到了别处。
    const prev = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => { window.removeEventListener('keydown', h); document.body.style.overflow = prev; };
  }, [open, onClose]);
  if (!open) return null;
  return (
    <div onClick={onClose} className="wk-scrim" style={{
      position: 'fixed', inset: 0, background: 'rgba(43,43,43,.42)',
      display: 'grid', zIndex: 'var(--z-modal)' as any, animation: 'wk-fade .12s var(--ease)',
    }}>
      <div role="dialog" aria-modal onClick={e => e.stopPropagation()} className="wk-sheet" style={{
        background: 'var(--surface)', borderRadius: 'var(--r-lg)',
        width: 'min(100%,' + width + 'px)',
        borderTop: '2px solid var(--brand)',
        boxShadow: 'var(--shadow-3)', maxHeight: '88vh', display: 'flex', flexDirection: 'column',
        animation: 'wk-pop .16s var(--ease)',
      }}>
        {title && <div style={{ padding: 'var(--s4) var(--s5)', borderBottom: '1px solid var(--line)',
                                fontSize: 'var(--t-h2)', fontWeight: 600,
                                color: 'var(--ink-0)' }}>{title}</div>}
        <div style={{ padding: 'var(--s5)', overflow: 'auto', WebkitOverflowScrolling: 'touch' }}>{children}</div>
        {footer && <div style={{ padding: 'var(--s4) var(--s5)', borderTop: '1px solid var(--line)',
                                 display: 'flex', gap: 'var(--s3)', justifyContent: 'flex-end',
                                 flexWrap: 'wrap' }}>{footer}</div>}
      </div>
    </div>
  );
}

/* ================================================================ 小件 */

/**
 * 指标块。DS 的 KPI：单等宽大数字 + 菱形小标签。
 * 一排里最多一个红数字 —— 全红等于没有重点。
 */
export function Stat({ label, value, sub, tone }: {
  label: string; value: React.ReactNode; sub?: React.ReactNode;
  tone?: 'brand' | 'ok' | 'warn' | 'danger';
}) {
  const hot = tone === 'brand' || tone === 'danger';
  const color = hot ? 'var(--brand)' : tone === 'warn' ? 'var(--warn)' : 'var(--ink-0)';
  return (
    <Card>
      <div className="da-overline">{label}</div>
      <div className="num" style={{ fontSize: 'clamp(24px, 3.4vw, 32px)', fontWeight: 500, color,
                                    marginTop: 'var(--s2)', lineHeight: 1.1,
                                    letterSpacing: '-.02em' }}>{value}</div>
      {sub && <div style={{ fontSize: 'var(--t-sm)', color: 'var(--ink-3)',
                            marginTop: 'var(--s2)' }}>{sub}</div>}
    </Card>
  );
}

export function Copyable({ text, label }: { text: string; label?: React.ReactNode }) {
  const toast = useToast();
  const [done, setDone] = React.useState(false);
  return (
    <button onClick={async (e) => {
      e.stopPropagation();
      try {
        await navigator.clipboard.writeText(text);
        setDone(true);
        setTimeout(() => setDone(false), 1400);
        toast({ tone: 'ok', text: '已复制' });
      } catch {
        // http 页面下 clipboard API 不可用。不能假装成功——
        // 用户以为复制到了，粘贴出来是空的，只会怪平台
        toast({ tone: 'danger', text: '当前环境不允许自动复制，请手动选中复制' });
      }
    }} className="wk-btn wk-btn-quiet" style={{
      padding: '1px 6px', margin: '-1px -6px', fontSize: 'inherit',
      color: 'var(--brand)', minHeight: 0, boxShadow: 'none',
    }}>
      {label ?? text}
      <span className="da-overline bare" style={{ color: 'currentColor', opacity: .75 }}>
        {done ? '已复制' : '复制'}
      </span>
    </button>
  );
}

/** 进度条。DS 的 .da-bar：方角、5px、红条。圆角胶囊是消费级味道。 */
export function Progress({ pct, height = 5, tone = 'brand' }: {
  pct: number; height?: number; tone?: 'brand' | 'ink';
}) {
  const v = Math.max(0, Math.min(100, pct || 0));
  return (
    <div role="progressbar" aria-valuenow={Math.round(v)} aria-valuemin={0} aria-valuemax={100}
         style={{ background: 'var(--surface-3)', height, overflow: 'hidden' }}>
      <div style={{ width: `${v}%`, height: '100%',
                    background: tone === 'ink' ? 'var(--ink)' : 'var(--brand)',
                    transition: `width .4s var(--ease)` }} />
    </div>
  );
}

/* ================================================================ 数据钩子 */

/**
 * 订阅登录令牌。
 *
 * **凡是按「登没登录」决定画什么的挡板，都必须用它**，不许 `useState(() => auth.token)`
 * 存一份快照 —— 令牌会在没有任何导航的情况下被清掉（接口返回 401 时 api.ts 就会清），
 * 而快照那一份不会跟着变，挡板于是继续把人留在一个已经失效的界面里。
 * 完整的事故经过写在 api.ts 里 auth 的注释上。
 *
 * 返回的是字符串或 null，是原始值，所以 useSyncExternalStore 不需要额外做快照缓存。
 */
export function useAuthToken(): string | null {
  React.useEffect(() => {
    // 同一个人开两个标签页，在 A 里退出，B 也要跟着回到登录页。
    // storage 事件只在【别的】标签页触发，本页自己的改动走 auth 内部的通知。
    const onStorage = (e: StorageEvent) => {
      if (e.key === null || e.key.startsWith('wangke.')) auth.notifyExternalChange();
    };
    window.addEventListener('storage', onStorage);
    return () => window.removeEventListener('storage', onStorage);
  }, []);
  return React.useSyncExternalStore(
    auth.subscribe,
    () => auth.token,
    () => null,
  );
}

export function useAsync<T>(fn: () => Promise<T>, deps: any[] = []) {
  // status 必须一起带出来。只留 message 的话，调用方无法区分
  // 「服务挂了」和「这条数据本来就不存在」——而这两种要给用户完全不同的出路。
  // 代理端就是这么栽的：非代理访问 /api/agent/me 得到 404（后端有意的语义），
  // 前端一律当加载失败，于是整个应用变成一张错误页，
  // 连"去申请代理"那个页面都进不去 —— 不申请就进不去，而申请页正是进不去的那个。
  const [state, set] = React.useState<{
    loading: boolean; error?: string; status?: number; data?: T;
  }>({ loading: true });
  const run = React.useCallback(() => {
    let live = true;
    set(s => ({ ...s, loading: true, error: undefined, status: undefined }));
    fn().then(d => live && set({ loading: false, data: d }))
        .catch(e => live && set({
          loading: false,
          error: e?.message || '加载失败',
          status: typeof e?.status === 'number' ? e.status : undefined,
        }));
    return () => { live = false; };
  }, deps);
  React.useEffect(run, [run]);
  return { ...state, reload: run };
}

/** 全局动画与少量只能用 CSS 表达的规则。挂一次即可。 */
export const GLOBAL_KEYFRAMES = `
@keyframes wk-spin { to { transform: rotate(360deg); } }
@keyframes wk-shimmer { 0% { background-position: 100% 50%; } 100% { background-position: 0 50%; } }
@keyframes wk-fade { from { opacity: 0; } }
@keyframes wk-pop { from { opacity: 0; transform: translateY(10px); } }
@keyframes wk-rise { from { opacity: 0; transform: translateY(100%); } }
@keyframes wk-drop { from { opacity: 0; transform: translateY(-10px); } }
@keyframes wk-slide-in { from { transform: translateX(-100%); } }

.wk-scrim { place-items: center; padding: var(--s4); }
@media (max-width: 640px) {
  /* 手机：底部升起并贴边。居中弹窗会被软键盘顶掉一半 */
  .wk-scrim { place-items: end stretch; padding: 0; }
  .wk-sheet {
    width: 100% !important;
    max-height: 92vh !important;
    border-radius: var(--r-lg) var(--r-lg) 0 0 !important;
    animation: wk-rise .22s var(--ease) !important;
    padding-bottom: env(safe-area-inset-bottom, 0px);
  }
}
`;
