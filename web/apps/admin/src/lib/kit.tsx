import React from 'react';
import { api, ApiError, yuan } from '@wangke/shared/api';
import {
  Async, Button, Card, Empty, Input, Modal, Select, Skeleton, Spinner, Tag, useAsync, useToast,
} from '@wangke/shared/ui';
import { fmtTime, label, num, tone } from './fmt';
import type { Domain } from './fmt';

/* ================================================================ 版面 */

/**
 * 后台的基本容器。和 shared 的 Panel 的区别只有一点：表格要能贴边，
 * 因为表格自己有内边距，外面再套一层就等于把可视行数又少掉一行 ——
 * 运营一屏少看两行，一天就是多翻几十次页。
 */
export function Board({ title, desc, extra, children, flush, id }: {
  title?: React.ReactNode; desc?: React.ReactNode; extra?: React.ReactNode;
  children: React.ReactNode; flush?: boolean; id?: string;
}) {
  return (
    <Card pad={false} id={id} style={{ overflow: 'hidden' }}>
      {(title || extra) && (
        <div style={{
          display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between',
          gap: 'var(--s3)', padding: 'var(--s3) var(--s4)', flexWrap: 'wrap',
          borderBottom: '1px solid var(--line)', background: 'var(--surface)',
        }}>
          <div style={{ minWidth: 0 }}>
            <div style={{ fontSize: 'var(--t-h3)', fontWeight: 600 }}>{title}</div>
            {desc && <div style={{ fontSize: 'var(--t-sm)', color: 'var(--ink-3)',
                                   marginTop: 'var(--s1)' }}>{desc}</div>}
          </div>
          {extra && <div style={{ display: 'flex', gap: 'var(--s2)', flexWrap: 'wrap' }}>{extra}</div>}
        </div>
      )}
      <div style={{ padding: flush ? 0 : 'var(--s4)' }}>{children}</div>
    </Card>
  );
}

/** 一行筛选控件。窄屏自动换行并各占满一行 —— 运营在路上也要能审一单。 */
export function Filters({ children, onReset, active }: {
  children: React.ReactNode; onReset?: () => void; active?: boolean;
}) {
  return (
    <Card pad={false} style={{ marginBottom: 'var(--s4)' }}>
      <div style={{
        display: 'flex', flexWrap: 'wrap', gap: 'var(--s3) var(--s4)',
        padding: 'var(--s4)',
        // 顶对齐而不是底对齐：有的控件下面挂了一句说明，底对齐会把它上面的
        // 输入框顶高一截，一排筛选看起来像是没对齐 —— 而它们的标签高度是一样的。
        alignItems: 'flex-start',
      }}>
        {children}
        {onReset && (
          <div style={{ marginLeft: 'auto' }}>
            <Button variant="quiet" size="sm" onClick={onReset} disabled={!active}>
              {active ? '清空筛选' : '未设筛选'}
            </Button>
          </div>
        )}
      </div>
    </Card>
  );
}

/** 一个筛选控件的外壳。比 shared 的 Field 紧一档：后台一行要塞下五六个。 */
export function Ctl({ label: lb, hint, width = 150, grow, children }: {
  label: string; hint?: React.ReactNode; width?: number; grow?: boolean; children: React.ReactNode;
}) {
  return (
    <label style={{
      display: 'block', flex: grow ? `1 1 ${width}px` : `0 1 ${width}px`,
      minWidth: 0, maxWidth: '100%',
    }}>
      <span style={{ display: 'block', fontSize: 'var(--t-xs)', color: 'var(--ink-3)',
                     marginBottom: 'var(--s1)' }}>{lb}</span>
      {children}
      {hint && <span style={{ display: 'block', fontSize: 'var(--t-xs)', color: 'var(--ink-3)',
                              marginTop: 'var(--s1)' }}>{hint}</span>}
    </label>
  );
}

/** 紧凑输入框：后台的行高比学员端小一档。 */
export const TextIn = React.forwardRef<HTMLInputElement,
    React.InputHTMLAttributes<HTMLInputElement>>(function TextIn(props, ref) {
  return <Input ref={ref} {...props} style={{ padding: '5px 2px', fontSize: 'var(--t-sm)',
                                              ...props.style }} />;
});

export function Pick({ value, onChange, options: opts, allText = '全部', all = true,
                      placeholder = '请选择', ...rest }: {
  value: string; onChange: (v: string) => void;
  options: { value: string; text: string }[];
  allText?: string; all?: boolean; placeholder?: string;
} & Omit<React.SelectHTMLAttributes<HTMLSelectElement>, 'value' | 'onChange'>) {
  /**
   * ★ 当前 value 在选项里找不到落点时，必须显式摆一个 value="" 的占位项。
   *
   * 不摆的话，浏览器拿不到匹配项就【回落到显示第 0 项】：屏幕上写着
   * 「AI FDE 工程师就业实训营」、DOM 里 select.value="1"、option.selected=true ——
   * 看上去完完全全是选中的样子，而 React 的 state 还是空串。于是依赖它的
   * disabled 判断一直成立，确认按钮是灰的，而【屏幕上没有任何线索】说明为什么。
   *
   * 更死的一层：change 事件只在「选中项真的变了」时才发。选项恰好只有一项时，
   * 用户打开下拉、点那唯一一项 —— 浏览器认为什么都没变，事件不发，state 永远是空串。
   * 这个框就此永远提交不了，而且没有任何出路可试。
   *
   * 2026-08-23 在生产上真的踩到了：/admin/users 的「开通课程」，平台只有一门课，
   * 运营填完有效期和赠课原因，「确认开通」始终是灰的。同样的形状还在
   * Courses.tsx 的「章节」和 Users.tsx 的「角色」上。
   *
   * 所以这里的规矩是：<b>下拉框显示的东西，必须就是模型里的东西</b>。
   * 没选就显示「请选择」，别显示一个用户没选过的值。
   */
  const missing = !opts.some(o => o.value === value);
  return (
    <Select {...rest} value={value} onChange={e => onChange(e.target.value)}
            // 右侧那 22px 是留给下拉箭头的（见 tokens.css 的 select.wk-input）。
            // 只写 '5px 2px' 会把它覆盖掉，箭头就压在选中的文字上。
            style={{ padding: '5px 22px 5px 2px', fontSize: 'var(--t-sm)' }}>
      {all && <option value="">{allText}</option>}
      {!all && missing && <option value="">{placeholder}</option>}
      {opts.map(o => <option key={o.value} value={o.value}>{o.text}</option>)}
    </Select>
  );
}

/* ================================================================ 数据 */

/** 分页条。总数必须显示：没有总数时运营无法判断「是不是漏了」。 */
export function Pager({ total, page, size, onPage, onSize }: {
  total: number; page: number; size: number;
  onPage: (p: number) => void; onSize?: (s: number) => void;
}) {
  const pages = Math.max(1, Math.ceil(total / size));
  return (
    <div style={{
      display: 'flex', alignItems: 'center', gap: 'var(--s3)', flexWrap: 'wrap',
      padding: 'var(--s3) var(--s4)', borderTop: '1px solid var(--line)',
      fontSize: 'var(--t-sm)', color: 'var(--ink-2)',
    }}>
      <span>共 <b className="num">{total}</b> 条 · 第 {page}/{pages} 页</span>
      {onSize && (
        <label style={{ display: 'inline-flex', alignItems: 'center', gap: 'var(--s2)' }}>
          每页
          <Select value={String(size)} onChange={e => onSize(Number(e.target.value))}
                  style={{ width: 'auto', padding: '5px 22px 5px 2px',
                           fontSize: 'var(--t-sm)' }}>
            {[20, 50, 100, 200].map(n => <option key={n} value={n}>{n}</option>)}
          </Select>
        </label>
      )}
      <div style={{ marginLeft: 'auto', display: 'flex', gap: 'var(--s2)' }}>
        <Button size="sm" variant="ghost" disabled={page <= 1} onClick={() => onPage(page - 1)}>上一页</Button>
        <Button size="sm" variant="ghost" disabled={page >= pages} onClick={() => onPage(page + 1)}>下一页</Button>
      </div>
    </div>
  );
}

/**
 * 空态。
 *
 * 「还没有」和「筛选没命中」必须是两句不同的话：前者要给一条出路，
 * 后者要提示他改条件。两种都写「暂无数据」，运营会以为系统挂了，
 * 然后去问研发 —— 而真正的原因只是他上一次的筛选条件还挂着。
 */
export function Blank({ filtered, thing, action, onClear }: {
  filtered: boolean; thing: string; action?: React.ReactNode; onClear?: () => void;
}) {
  if (filtered) return (
    <Empty title={`没有符合条件的${thing}`}
           desc="换个筛选条件试试，或者把条件清空看全部。"
           action={onClear ? <Button size="sm" variant="ghost" onClick={onClear}>清空筛选</Button> : undefined} />
  );
  return <Empty title={`还没有${thing}`} action={action} />;
}

/* ================================================================ 小件 */

/** 状态标签。语气与词面都按「哪张表」取，见 fmt.ts 顶部那段说明。 */
export function St({ domain, value }: { domain: Domain; value?: string | null }) {
  if (!value) return <span style={{ color: 'var(--ink-3)' }}>—</span>;
  return <Tag tone={tone(domain, value)}>{label(domain, value)}</Tag>;
}

/** 金额。一律 yuan(fen)，页面里绝不自己除 100。 */
export function Money({ fen, bold, tone: t }: { fen: unknown; bold?: boolean; tone?: 'ok' | 'danger' }) {
  const v = fen === null || fen === undefined ? null : num(fen);
  return (
    <span className="num" style={{
      fontWeight: bold ? 600 : undefined,
      color: t === 'danger' ? 'var(--danger)' : t === 'ok' ? 'var(--ok)' : undefined,
    }}>{yuan(v)}</span>
  );
}

/** 详情页的字段表。两列网格，窄屏塌成一列。 */
export function KV({ items, cols = 2 }: {
  items: [React.ReactNode, React.ReactNode][]; cols?: number;
}) {
  return (
    <div style={{
      display: 'grid', gap: 'var(--s1) var(--s4)',
      gridTemplateColumns: `repeat(auto-fit, minmax(${cols === 1 ? 240 : 200}px, 1fr))`,
    }}>
      {items.map(([k, v], i) => (
        <div key={i} style={{
          display: 'flex', gap: 'var(--s3)', padding: 'var(--s2) 0',
          borderBottom: '1px solid var(--line)', fontSize: 'var(--t-sm)', minWidth: 0,
        }}>
          <span style={{ color: 'var(--ink-3)', flex: '0 0 88px' }}>{k}</span>
          <span style={{ flex: 1, minWidth: 0, overflowWrap: 'anywhere' }}>{v ?? '—'}</span>
        </div>
      ))}
    </div>
  );
}

export function Sect({ title, extra, children }: {
  title: React.ReactNode; extra?: React.ReactNode; children: React.ReactNode;
}) {
  return (
    <section style={{ marginTop: 'var(--s5)' }}>
      <div style={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between',
                    gap: 'var(--s3)', marginBottom: 'var(--s2)' }}>
        <h3 style={{ fontSize: 'var(--t-sm)', color: 'var(--ink-2)', letterSpacing: '.02em' }}>{title}</h3>
        {extra}
      </div>
      {children}
    </section>
  );
}

/** 说明块。用来把「这一步会发生什么」写在动手之前，而不是等报错。 */
export function Note({ tone: t = 'info', title, children }: {
  tone?: 'info' | 'warn' | 'danger' | 'ok'; title?: React.ReactNode; children?: React.ReactNode;
}) {
  // info 不再用红：红是每屏唯一的锚点，而说明块是后台里出现最多的东西。
  // 全染红之后，真正要人动手的那个按钮反而不显眼了。
  const c = t === 'danger' ? ['var(--danger-soft)', 'var(--danger)']
          : t === 'warn' ? ['var(--warn-soft)', 'var(--warn)']
          : t === 'ok' ? ['var(--surface-2)', 'var(--ink)']
          : ['var(--surface-2)', 'var(--line-strong)'];
  return (
    <div style={{
      background: c[0], borderLeft: `2px solid ${c[1]}`,
      padding: 'var(--s3) var(--s4)', fontSize: 'var(--t-sm)', lineHeight: 'var(--lh-body)',
      color: 'var(--ink-2)',
    }}>
      {title && <div style={{
        fontWeight: 600, marginBottom: 'var(--s1)',
        // info 的线色是发丝灰，拿它当标题色会读不出来。标题一律落回墨色。
        color: t === 'info' ? 'var(--ink)' : c[1],
      }}>{title}</div>}
      {children}
    </div>
  );
}

/** JSON / 长文本。必须自己横向滚，页面 body 绝不能跟着滚。 */
export function Raw({ text }: { text: unknown }) {
  if (text === null || text === undefined || text === '') return <span style={{ color: 'var(--ink-3)' }}>—</span>;
  let s = typeof text === 'string' ? text : JSON.stringify(text);
  try { s = JSON.stringify(JSON.parse(s), null, 2); } catch { /* 不是 JSON 就原样显示 */ }
  return (
    <pre style={{
      margin: 0, padding: 'var(--s3)', background: 'var(--surface-2)', borderRadius: 'var(--r-sm)',
      fontSize: 'var(--t-xs)', fontFamily: 'var(--font-num)', overflowX: 'auto',
      whiteSpace: 'pre-wrap', wordBreak: 'break-all', maxHeight: 260, overflowY: 'auto',
    }}>{s}</pre>
  );
}

/** 折叠块。详情页里那些「多数时候不用看、但要能看」的东西放这里。 */
export function Fold({ summary, children, open }: {
  summary: React.ReactNode; children: React.ReactNode; open?: boolean;
}) {
  return (
    <details open={open} style={{ fontSize: 'var(--t-sm)' }}>
      <summary style={{ cursor: 'pointer', color: 'var(--brand)', padding: 'var(--s1) 0' }}>{summary}</summary>
      <div style={{ marginTop: 'var(--s2)' }}>{children}</div>
    </details>
  );
}

/* ================================================================ 动作 */

/**
 * 改动性动作的统一入口。
 *
 * 三件事必须一起做，缺一个都会在真出事时把平台放在没有立场的一侧：
 *  ① **说清后果**。二次确认框里只写「确定吗」等于没确认 —— 点确定的人并不知道
 *     自己在确定什么。所以 consequences 是必填的。
 *  ② **不可逆的要说不可逆**。冻结、驳回、退款、发码，每一个都有一条回不去的边。
 *  ③ **失败时不关框**。报错就把框关掉，运营刚填的十个字全没了，只能重来。
 */
export function ActionDialog({
  open, onClose, title, verb, tone: t = 'brand', consequences, children,
  onConfirm, confirmLabel, disabled, width = 520, onDone,
}: {
  open: boolean; onClose: () => void; title: React.ReactNode; verb?: string;
  tone?: 'brand' | 'danger'; consequences: React.ReactNode;
  children?: React.ReactNode; confirmLabel?: string; disabled?: boolean; width?: number;
  onConfirm: () => Promise<unknown>;
  /** 成功后的回调，一般用来刷新列表与审计流水 */
  onDone?: (result: any) => void;
}) {
  const toast = useToast();
  const [busy, setBusy] = React.useState(false);
  const [err, setErr] = React.useState<string | null>(null);

  React.useEffect(() => { if (open) setErr(null); }, [open]);

  async function go() {
    setBusy(true); setErr(null);
    try {
      const r: any = await onConfirm();
      // 后端把「这条已经处理过了」判成 200 + changed:false，是有意的：
      // 双击不该变成一个红色错误框。所以这里也要照着分两种语气说
      const msg = r?.message || (r?.changed === false ? '没有改动' : `${verb ?? '操作'}成功`);
      toast({ tone: r?.changed === false ? 'info' : 'ok', text: msg });
      if (r?.warn) toast({ tone: 'danger', text: r.warn });
      if (r?.commissionWarn) toast({ tone: 'danger', text: r.commissionWarn });
      onDone?.(r);
      onClose();
    } catch (e) {
      const m = e instanceof ApiError
        ? e.message + (e.reason ? `（${e.reason}）` : '')
        : (e as Error)?.message || '操作失败';
      setErr(m);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal open={open} onClose={busy ? () => {} : onClose} title={title} width={width}
      footer={<>
        <Button variant="ghost" onClick={onClose} disabled={busy}>取消</Button>
        <Button variant={t === 'danger' ? 'danger' : 'primary'} loading={busy}
                disabled={disabled} onClick={go}>{confirmLabel ?? `确认${verb ?? ''}`}</Button>
      </>}>
      <div style={{ display: 'grid', gap: 'var(--s4)' }}>
        <Note tone={t === 'danger' ? 'danger' : 'info'} title="这一步会发生什么">
          {consequences}
        </Note>
        {children}
        {err && <Note tone="danger" title="没有执行">{err}</Note>}
      </div>
    </Modal>
  );
}

/** 必填理由。驳回 / 停用 / 冻结都要它 —— 没有理由的处置，在被质问时平台是没有立场的一方。 */
export function ReasonBox({ value, onChange, label: lb = '理由', hint }: {
  value: string; onChange: (v: string) => void; label?: string; hint?: React.ReactNode;
}) {
  return (
    <label style={{ display: 'block' }}>
      <span style={{ display: 'block', fontSize: 'var(--t-sm)', color: 'var(--ink-2)',
                     marginBottom: 'var(--s2)', fontWeight: 500 }}>
        {lb}<span style={{ color: 'var(--danger)', marginLeft: 2 }}>*</span>
      </span>
      <textarea value={value} onChange={e => onChange(e.target.value)} rows={3}
        placeholder="写清楚为什么。这条会进审计日志，事后要能拿出来看。"
        style={{
          width: '100%', padding: 'var(--s3)', background: 'var(--surface)',
          border: '1px solid var(--line-strong)', borderRadius: 'var(--r-md)',
          outline: 'none', resize: 'vertical', fontFamily: 'var(--font)',
          fontSize: 'var(--t-body)', lineHeight: 'var(--lh-body)',
        }} />
      {hint && <span style={{ display: 'block', fontSize: 'var(--t-sm)', color: 'var(--ink-3)',
                              marginTop: 'var(--s2)' }}>{hint}</span>}
    </label>
  );
}

/**
 * 完整手机号。
 *
 * 列表和详情一律只出掩码，完整号码要**点一下**才出来，而且每次都会记进审计日志。
 * 这不是防运营，是防「后台页面被投屏、被截图」—— 常驻一整列真实手机号，
 * 一次泄漏的是全库用户的联系方式。客服确实要打电话，所以不能没有这条路，
 * 只能让每一次使用都留下记录。
 */
export function PhoneReveal({ userId, masked }: { userId: number | string; masked?: string | null }) {
  const [full, setFull] = React.useState<string | null>(null);
  const [busy, setBusy] = React.useState(false);
  const [err, setErr] = React.useState<string | null>(null);

  if (full) return <span className="num">{full}</span>;
  return (
    <span style={{ display: 'inline-flex', gap: 'var(--s2)', alignItems: 'center', flexWrap: 'wrap' }}>
      <span className="num">{masked || '—'}</span>
      <Button size="sm" variant="quiet" loading={busy} onClick={async () => {
        setBusy(true); setErr(null);
        try {
          const r = await api.get<any>(`/api/admin/users/${userId}/phone`);
          setFull(r.phone);
        } catch (e) {
          setErr(e instanceof ApiError ? e.message : '取不到完整号码');
        } finally { setBusy(false); }
      }}>看完整号码</Button>
      <span style={{ fontSize: 'var(--t-xs)', color: 'var(--ink-3)' }}>（这次查看会进审计日志）</span>
      {err && <span style={{ fontSize: 'var(--t-xs)', color: 'var(--danger)' }}>{err}</span>}
    </span>
  );
}

/* ================================================================ 审计 */

/**
 * 某条记录的改动史。
 *
 * 每个详情页都挂一份，是因为「改动后要能看到它进了审计日志」不能靠一句 toast ——
 * toast 说的是「我提交了」，审计流水说的是「库里真的留下了」。两者不是一回事，
 * 而后者才是事后被问到时能拿出来的东西。
 */
export function AuditTrail({ targetType, targetId, refreshKey = 0 }: {
  targetType: string; targetId: string | number; refreshKey?: number;
}) {
  const state = useAsync<any>(
    () => api.get(`/api/admin/audit/target/${encodeURIComponent(targetType)}/${encodeURIComponent(String(targetId))}`),
    [targetType, targetId, refreshKey]);

  return (
    <Async state={state}>{(d: any) => {
      const items: any[] = d?.items ?? [];
      if (!items.length) return (
        <div style={{ fontSize: 'var(--t-sm)', color: 'var(--ink-3)' }}>
          这条记录还没有被后台改动过。（只读的查询不写审计，所以这里空着是正常的）
        </div>
      );
      return (
        <ol style={{ listStyle: 'none', margin: 0, padding: 0, display: 'grid', gap: 'var(--s2)' }}>
          {items.slice().reverse().map((r: any) => {
            const action = r.action ?? r.ACTION;
            const before = r.before_json ?? r.beforeJson;
            const after = r.after_json ?? r.afterJson;
            const who = r.operator_nickname ?? r.operatorNickname;
            const opId = r.operator_id ?? r.operatorId;
            return (
              <li key={r.id} style={{
                borderLeft: '2px solid var(--brand-line)', paddingLeft: 'var(--s3)',
                fontSize: 'var(--t-sm)',
              }}>
                <div style={{ display: 'flex', gap: 'var(--s2)', flexWrap: 'wrap',
                              alignItems: 'baseline' }}>
                  <b>{action}</b>
                  <span style={{ color: 'var(--ink-3)' }}>
                    {fmtTime(r.created_at ?? r.createdAt, true)}
                    ·{who || (opId ? `操作人 #${opId}` : '系统')}
                    {(r.client_ip ?? r.clientIp) ? ` · ${r.client_ip ?? r.clientIp}` : ''}
                  </span>
                </div>
                {(before || after) && (
                  <Fold summary="看改了什么">
                    <div style={{ display: 'grid', gap: 'var(--s2)' }}>
                      <div><div style={{ color: 'var(--ink-3)' }}>改之前</div><Raw text={before} /></div>
                      <div><div style={{ color: 'var(--ink-3)' }}>改之后</div><Raw text={after} /></div>
                    </div>
                  </Fold>
                )}
              </li>
            );
          })}
        </ol>
      );
    }}</Async>
  );
}

/* ================================================================ 详情壳 */

/** 详情弹层。开着哪一条也进 URL，刷新不丢、发给同事能直接打开。 */
export function DetailModal({ open, onClose, title, subtitle, actions, children, width = 860 }: {
  open: boolean; onClose: () => void; title: React.ReactNode; subtitle?: React.ReactNode;
  actions?: React.ReactNode; children: React.ReactNode; width?: number;
}) {
  return (
    <Modal open={open} onClose={onClose} width={width}
      title={<div style={{ minWidth: 0 }}>
        <div style={{ overflowWrap: 'anywhere' }}>{title}</div>
        {subtitle && <div style={{ fontSize: 'var(--t-sm)', color: 'var(--ink-3)',
                                   fontWeight: 400, marginTop: 'var(--s1)' }}>{subtitle}</div>}
      </div>}
      footer={actions ? <div style={{ display: 'flex', gap: 'var(--s2)', flexWrap: 'wrap',
                                      justifyContent: 'flex-end', width: '100%' }}>{actions}</div> : undefined}>
      {children}
    </Modal>
  );
}

/** 详情加载中的骨架。比一个转圈更能说明「等的是什么」。 */
export function DetailSkeleton() {
  return (
    <div style={{ display: 'grid', gap: 'var(--s3)' }}>
      <Skeleton h={16} w="40%" />
      <Skeleton h={12} /><Skeleton h={12} w="86%" /><Skeleton h={12} w="62%" />
      <Skeleton h={90} />
    </div>
  );
}

export { Spinner };
