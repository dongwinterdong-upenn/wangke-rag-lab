import React from 'react';
import { Link } from 'react-router-dom';
import { ApiError, api, yuan } from '@wangke/shared/api';
import { PageHead } from '@wangke/shared/shell';
import { Async, Button, Card, useAsync } from '@wangke/shared/ui';
import { Board, Note } from '../lib/kit';
import { monthStartStr, num, todayStr } from '../lib/fmt';
import { qs } from '../lib/q';

/**
 * 总览。
 *
 * <h3>为什么这一页要写两套取数</h3>
 * 后台还没有一个汇总接口。写死一个 `/api/admin/overview` 然后等它上线，
 * 这一页在此之前就是一屏红字；而把它做成「拿不到就算了」的静默降级，
 * 又会让人以为数字是 0（0 和「没这个数」在运营那里是完全不同的决定）。
 *
 * 所以：先要汇总接口，没有就用**真实存在的列表接口**把能算的算出来，
 * 算不出来的那几项明确写成「这个数后台还没有接口」而不是 0 或 —— 。
 * 一个说不出自己不知道什么的仪表盘，比没有仪表盘更危险。
 */
type Overview = {
  source: 'server' | 'composed';
  todayPaidFen: number | null;
  monthPaidFen: number | null;
  todayOrders: number | null;
  monthOrders: number | null;
  todayNewStudents: number | null;
  monthNewStudents: number | null;
  todayActivations: number | null;
  monthActivations: number | null;
  todo: { key: string; label: string; count: number | null; to: string; hint: string }[];
  failures: string[];
  /** 说明性提示（口径偏差、列表截断、时区偏移）——不是故障，但会影响怎么读这些数 */
  caveats: string[];
  /**
   * 后端明说"这一项没取到"的字段名集合。
   *
   * <p>它把「取数失败」和「后台还没有这个接口」分开。两者在屏幕上都是一个空位，
   * 但运营的下一步动作完全不同：前者要催修、要点重试，后者只能等排期。
   * 少了这个集合，取数失败会被显示成「后台还没有能算这个数的接口」——
   * 运营的结论是「这功能还没上」，于是不看日志、不催修、也不点重试。
   */
  unavailableFields: Set<string>;
};

export function Overview() {
  const state = useAsync<Overview>(load, []);
  return (
    <>
      <style>{MOBILE_OVERVIEW_CSS}</style>
      <PageHead title="总览"
        desc="今天与本月的成交、以及此刻在等你处理的事。数字都可以点进去看明细。" />
      <Async state={state}>{d => <Body d={d} onReload={state.reload} />}</Async>
    </>
  );
}

function Body({ d, onReload }: { d: Overview; onReload: () => void }) {
  return (
    <div style={{ display: 'grid', gap: 'var(--s4)' }}>
      {d.source === 'composed' && (
        <Note tone="warn" title="这一页的数字是前端拼出来的">
          后台还没有汇总接口（<code>GET /api/admin/overview</code>），所以成交额与订单数是用
          订单列表接口按<b>下单时间</b>现算的 —— 它和按<b>支付时间</b>归月的口径会有出入，
          跨月那几天差得最明显。「新增学员」「激活数」两项没有任何现成接口能算，所以下面标的是
          「暂无接口」而不是 0。
        </Note>
      )}

      <Board title="待办" desc="这些是此刻在等人动手的。点数字直接进到已经筛好的列表。">
        <div className="wk-admin-todo-grid" style={{ display: 'grid', gap: 'var(--s3)',
                      gridTemplateColumns: 'repeat(auto-fill, minmax(190px, 1fr))' }}>
          {d.todo.map(({ key, ...t }) => <TodoTile key={key} {...t} />)}
        </div>
      </Board>

      <Board title="成交" desc="金额为实际收到的钱（已支付部分），不含未付款的订单。">
        <div className="wk-admin-metric-grid" style={{ display: 'grid', gap: 'var(--s3)',
                      gridTemplateColumns: 'repeat(auto-fill, minmax(190px, 1fr))' }}>
          <Metric label="今日成交额" money={d.todayPaidFen} failed={d.unavailableFields.has('todayPaidFen')}
                  to={`/orders${qs({ from: todayStr(), to: todayStr() })}`} />
          <Metric label="本月成交额" money={d.monthPaidFen} failed={d.unavailableFields.has('monthPaidFen')}
                  to={`/orders${qs({ from: monthStartStr(), to: todayStr() })}`} />
          <Metric label="今日订单数" value={d.todayOrders} failed={d.unavailableFields.has('todayOrders')}
                  to={`/orders${qs({ from: todayStr(), to: todayStr() })}`} />
          <Metric label="本月订单数" value={d.monthOrders} failed={d.unavailableFields.has('monthOrders')}
                  to={`/orders${qs({ from: monthStartStr(), to: todayStr() })}`} />
        </div>
      </Board>

      <Board title="学员" desc="新注册的学员，和被激活的码。">
        <div className="wk-admin-metric-grid" style={{ display: 'grid', gap: 'var(--s3)',
                      gridTemplateColumns: 'repeat(auto-fill, minmax(190px, 1fr))' }}>
          <Metric label="今日新增学员" value={d.todayNewStudents} failed={d.unavailableFields.has('todayNewStudents')} />
          <Metric label="本月新增学员" value={d.monthNewStudents} failed={d.unavailableFields.has('monthNewStudents')} />
          <Metric label="今日激活数" value={d.todayActivations} failed={d.unavailableFields.has('todayActivations')} />
          <Metric label="本月激活数" value={d.monthActivations} failed={d.unavailableFields.has('monthActivations')} />
        </div>
      </Board>

      {d.failures.length > 0 && (
        <Note tone="danger" title="有几项没取到">
          <ul style={{ margin: 'var(--s2) 0 0', paddingLeft: 'var(--s5)' }}>
            {d.failures.map((f, i) => <li key={i}>{f}</li>)}
          </ul>
          <div style={{ marginTop: 'var(--s3)' }}>
            <Button size="sm" variant="ghost" onClick={onReload}>重新取一次</Button>
          </div>
        </Note>
      )}

      {/* 口径提示。和上面那条是两回事：那条是「这个数没取到」，这条是
          「这个数取到了，但读的时候要知道它是怎么算的」——
          按支付时间还是下单时间、列表会不会截断、日界线准不准。
          都不给的话，运营会拿两个口径不同的数去对账，然后得出一个错的结论。 */}
      {d.caveats.length > 0 && (
        <Note tone="warn" title="读这几个数之前，先知道这些">
          <ul style={{ margin: 'var(--s2) 0 0', paddingLeft: 'var(--s5)' }}>
            {d.caveats.map((c, i) => <li key={i}>{c}</li>)}
          </ul>
        </Note>
      )}
    </div>
  );
}

function TodoTile({ label, count, to, hint }: {
  label: string; count: number | null; to: string; hint: string;
}) {
  const empty = count === 0;
  return (
    <>
      <div className="wk-only-mobile">
        <Link to={to} className={count && count > 0 ? 'wk-admin-todo-row hot' : 'wk-admin-todo-row'}>
          <span>
            <strong>{label}</strong>
            <small>{count === null ? '这项没取到，点进去看列表' : empty ? '没有待办' : hint}</small>
          </span>
          <b className="num">{count === null ? '—' : count}</b>
          <em>查看</em>
        </Link>
      </div>
      <Link to={to} className="wk-hide-mobile" style={{ textDecoration: 'none' }}>
        <Card style={{
          borderColor: count && count > 0 ? 'var(--brand-line)' : 'var(--line)',
          background: count && count > 0 ? 'var(--brand-soft)' : 'var(--surface)',
          height: '100%',
        }}>
          <div style={{ fontSize: 'var(--t-sm)', color: 'var(--ink-2)' }}>{label}</div>
          <div className="num" style={{
            fontSize: 'var(--t-h1)', fontWeight: 600, lineHeight: 1.2, marginTop: 'var(--s1)',
            color: count === null ? 'var(--ink-4)' : empty ? 'var(--ink-3)' : 'var(--brand)',
          }}>{count === null ? '取不到' : count}</div>
          <div style={{ fontSize: 'var(--t-xs)', color: 'var(--ink-3)', marginTop: 'var(--s1)' }}>
            {count === null ? '这项没取到，点进去看列表' : empty ? '没有待办' : hint}
          </div>
        </Card>
      </Link>
    </>
  );
}

function Metric({ label, value, money, to, failed }: {
  label: string; value?: number | null; money?: number | null; to?: string;
  /** true = 后端明说这一项没取到（催修 + 重试）；false = 压根没有接口能算（等排期） */
  failed?: boolean;
}) {
  const missing = (money === undefined ? value : money) === null;
  const body = (
    <Card style={{ height: '100%' }}>
      <div style={{ fontSize: 'var(--t-sm)', color: 'var(--ink-3)' }}>{label}</div>
      <div className="num" style={{
        fontSize: missing ? 'var(--t-h3)' : 'var(--t-h1)', fontWeight: 600,
        lineHeight: 1.3, marginTop: 'var(--s2)',
        color: missing ? 'var(--ink-4)' : 'var(--ink)',
      }}>
        {missing ? (failed ? '取不到' : '暂无接口') : money !== undefined ? yuan(money as number) : value}
      </div>
      {missing && (
        <div style={{ fontSize: 'var(--t-xs)', color: 'var(--ink-3)', marginTop: 'var(--s1)' }}>
          {failed
            ? '这一项这次没取到，不是 0。下面有原因，可以点重新取一次'
            : '后台还没有能算这个数的接口，所以这里不填 0'}
        </div>
      )}
    </Card>
  );
  return to && !missing ? <Link to={to} style={{ textDecoration: 'none', color: 'inherit' }}>{body}</Link> : body;
}

const MOBILE_OVERVIEW_CSS = `
@media (max-width: 860px) {
  .wk-admin-todo-grid { grid-template-columns: 1fr !important; gap: 0 !important; }
  .wk-admin-todo-row {
    display: flex; align-items: center; gap: var(--s3); min-height: 68px;
    margin: 0 calc(var(--s4) * -1); padding: var(--s3) var(--s4);
    color: var(--ink); text-decoration: none; border-bottom: 1px solid var(--line);
  }
  .wk-admin-todo-grid > div:last-child .wk-admin-todo-row { border-bottom: 0; }
  .wk-admin-todo-row:active { background: var(--brand-soft); }
  .wk-admin-todo-row > span { display: grid; flex: 1; min-width: 0; }
  .wk-admin-todo-row strong { font-size: var(--t-sm); }
  .wk-admin-todo-row small {
    overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
    color: var(--ink-3); font-size: var(--t-xs);
  }
  .wk-admin-todo-row b { font-size: 22px; color: var(--ink-3); }
  .wk-admin-todo-row.hot b { color: var(--brand); }
  .wk-admin-todo-row em { color: var(--brand); font-size: var(--t-xs); font-style: normal; }
  .wk-admin-metric-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr)) !important; gap: var(--s2) !important;
  }
  .wk-admin-metric-grid > * { min-width: 0; }
  .wk-admin-metric-grid > * > div,
  .wk-admin-metric-grid > div { padding: var(--s3) !important; }
}
`;

/* ------------------------------------------------------------------ 取数 */

async function load(): Promise<Overview> {
  try {
    const r = await api.get<any>('/api/admin/overview');
    // 后端为「算不出来的项能说出自己算不出来」做了一整套（每条 SUM 套 COALESCE、
    // 失败落 null 不落 0、reason 写成人话且不含表名与 SQL）。那套东西必须在这里落地 ——
    // 在这一行把 failures 写死成 [] 的话，它到 API 边界为止就断了，
    // 交付出去的不是「0 冒充没有数」，而是「取数失败冒充功能没做」，性质一样坏。
    const unavailable: { field?: string; label?: string; reason?: string }[] = r.unavailable ?? [];
    return {
      source: 'server',
      todayPaidFen: numOrNull(r.todayPaidFen), monthPaidFen: numOrNull(r.monthPaidFen),
      todayOrders: numOrNull(r.todayOrders), monthOrders: numOrNull(r.monthOrders),
      todayNewStudents: numOrNull(r.todayNewStudents),
      monthNewStudents: numOrNull(r.monthNewStudents),
      todayActivations: numOrNull(r.todayActivations),
      monthActivations: numOrNull(r.monthActivations),
      todo: todoFrom(r.todo ?? {}),
      failures: unavailable.map(u => `${u.label ?? u.field ?? '有一项'}：${u.reason ?? '没取到'}`),
      caveats: (r.caveats ?? []) as string[],
      unavailableFields: new Set(unavailable.map(u => u.field).filter(Boolean) as string[]),
    };
  } catch (e) {
    // 只有「这个接口不存在」才降级。401 要照常抛给 shared 的统一出口去踢登录，
    // 500 也要照常报错 —— 把服务端故障伪装成「降级模式」，故障就再也没人看见了
    const status = e instanceof ApiError ? e.status : -1;
    if (status !== 404 && status !== 405 && status !== 501) throw e;
    return compose();
  }
}

function numOrNull(v: unknown): number | null {
  return v === null || v === undefined ? null : num(v);
}

function todoFrom(t: any) {
  const c = (k: string) => (t?.[k] === undefined || t?.[k] === null ? null : num(t[k]));
  return [
    { key: 'agent', label: '待审代理', count: c('pendingAgents'), to: '/agents?status=PENDING',
      hint: '有代理在等开户审批' },
    { key: 'withdraw', label: '待审提现', count: c('pendingWithdraws'), to: '/withdraws?status=APPLIED',
      hint: '有代理在等打款审核' },
    { key: 'refund', label: '待审退款', count: c('pendingRefunds'), to: '/refunds?status=APPLIED',
      hint: '有退款单在等审批' },
    { key: 'qa', label: '待处理工单', count: c('openTickets'), to: '/qa',
      hint: '有学员在等人工回复' },
    { key: 'invoice', label: '待开发票', count: c('pendingInvoices'), to: '/invoices?status=APPLIED',
      hint: '有开票申请在等处理' },
  ];
}

/** 用真实存在的列表接口拼一份。拿不到的项留 null，绝不填 0。 */
async function compose(): Promise<Overview> {
  const today = todayStr(), month = monthStartStr();
  const failures: string[] = [];

  const [
    todayOrd, monthOrd, agents, withdraws, refunds, tickets, invoices,
  ] = await Promise.allSettled([
    api.get<any>(`/api/admin/orders${qs({ from: today, to: today, size: 1 })}`),
    api.get<any>(`/api/admin/orders${qs({ from: month, to: today, size: 1 })}`),
    api.get<any>(`/api/admin/agents${qs({ status: 'PENDING', size: 1 })}`),
    api.get<any>(`/api/admin/withdraws${qs({ status: 'APPLIED', size: 1 })}`),
    api.get<any>(`/api/admin/refunds${qs({ status: 'APPLIED', size: 1 })}`),
    api.get<any>(`/api/qa/admin/queue${qs({ limit: 100 })}`),
    api.get<any>(`/api/invoice/admin/list${qs({ status: 'APPLIED', limit: 100 })}`),
  ]);

  const pick = <T,>(r: PromiseSettledResult<T>, what: string): T | null => {
    if (r.status === 'fulfilled') return r.value;
    failures.push(`${what}：${(r.reason as Error)?.message ?? '取数失败'}`);
    return null;
  };

  const t = pick(todayOrd, '今日订单'), m = pick(monthOrd, '本月订单');
  const ag = pick(agents, '待审代理'), wd = pick(withdraws, '待审提现');
  const rf = pick(refunds, '待审退款'), tk = pick(tickets, '待处理工单');
  const iv = pick(invoices, '待开发票');

  const ticketCount = tk ? (tk.tickets?.length ?? 0) : null;

  return {
    source: 'composed',
    todayPaidFen: t ? num(t.sumPaidFen) : null,
    monthPaidFen: m ? num(m.sumPaidFen) : null,
    todayOrders: t ? num(t.total) : null,
    monthOrders: m ? num(m.total) : null,
    todayNewStudents: null, monthNewStudents: null,
    todayActivations: null, monthActivations: null,
    todo: [
      { key: 'agent', label: '待审代理', count: ag ? num(ag.total) : null,
        to: '/agents?status=PENDING', hint: '有代理在等开户审批' },
      { key: 'withdraw', label: '待审提现', count: wd ? num(wd.total) : null,
        to: '/withdraws?status=APPLIED', hint: '有代理在等打款审核' },
      { key: 'refund', label: '待审退款', count: rf ? num(rf.total) : null,
        to: '/refunds?status=APPLIED', hint: '有退款单在等审批' },
      { key: 'qa', label: '待处理工单', count: ticketCount,
        to: '/qa', hint: ticketCount === 100 ? '至少 100 条，队列接口一次最多给 100' : '有学员在等人工回复' },
      { key: 'invoice', label: '待开发票', count: iv ? (iv.invoices?.length ?? 0) : null,
        to: '/invoices?status=APPLIED', hint: '有开票申请在等处理' },
    ],
    failures,
    caveats: [],
    // 拼盘模式下这四项是真的「没有接口能算」，不是「取数失败」——
    // 所以这个集合是空的，Metric 会照常说「暂无接口」。
    unavailableFields: new Set<string>(),
  };
}
