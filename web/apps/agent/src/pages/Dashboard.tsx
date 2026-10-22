/**
 * 工作台。
 *
 * 只回答三个问题，多一个都不放：
 *   ① 我手里还有多少货能发？   ② 这个月动了多少？   ③ 有没有事等着我处理？
 *
 * <b>原来第四格是「累计进货金额」，已经删掉</b>：进货价改成平台按代理一家一议之后，
 * 代理端不再出现任何进货价目 —— 而"累计进货金额 ÷ 累计进的码数"就是他的单价，
 * 一格聚合值等于把逐家不同的那个价原样还给他。
 * 也没有换成"累计进了多少个码"顶上：库里能数的只有「名下的码总数」，
 * 那个数含平台白送的部分，摆在这里会和第五格互相矛盾（见下面 ② 那条）。
 * 佣金那一格照常出金额 —— 那是代理自己挣的钱，去掉了他没法对账。
 *
 * 第四格（平台发放给你的码）<b>只在他真有这种码时才出现</b>，出现时回答的仍是 ①
 * 的后半句 ——「这批我没买过的货是哪来的」。常驻的话它就是第四个问题了。
 */
import React from 'react';
import { Link } from 'react-router-dom';
import {
  Async, Button, Card, dateTime, Empty, PageHead, Stat, Table,
  type Col, untilText, useAsync, IconDashboard,
} from '@wangke/shared';
import { AgentApi, type Dashboard as Dash, type StudentRow } from '../api';
import { Gate, useActiveAgent } from '../gate';
import { Money, Note, Section, TableSkeleton, useNarrow } from '../kit';

export default function DashboardPage() {
  return (
    <>
      <style>{MOBILE_DASH_CSS}</style>
      <PageHead title="工作台" desc="进货、把码发给学员、看谁激活了、把佣金提出来 —— 一天的活儿在这四件事里。" />
      <Gate><Body /></Gate>
    </>
  );
}

function Body() {
  const agent = useActiveAgent();
  const state = useAsync(() => AgentApi.dashboard(), [agent?.id]);

  // 平台手工发给他的码数，只有 /stats 有这个键 —— 工作台那个聚合接口没有它。
  // 单独取一次，并且<b>把失败吞成 null</b>：这一格是一句解释，不是 KPI。
  // 让它跟着 Async 一起判错的话，/stats 一慢一挂，四张卡连同待办条整片变成错误页 ——
  // 少一句解释是遗憾，工作台打不开是事故。
  const extra = useAsync(() => AgentApi.stats().catch(() => null), [agent?.id]);

  return (
    <div style={{ display: 'grid', gap: 'var(--s5)' }}>
      <Async state={state}>
        {(d: Dash) => (
          <>
            <MobilePrimaryAction d={d} />
            <Kpis d={d} granted={extra.data?.grantedCodes} />
            <Todos d={d} />
          </>
        )}
      </Async>
      <RecentStudents />
    </div>
  );
}

function MobilePrimaryAction({ d }: { d: Dash }) {
  const empty = d.stockUnactivated === 0;
  return (
    <Card className="wk-only-mobile wk-agent-mobile-action" pad={false}>
      <div className="wk-agent-mobile-action-body">
        <div className="da-overline">今天先做</div>
        <h2>{empty ? '库存空了，先去进货' : `还有 ${d.stockUnactivated} 个码可以发`}</h2>
        <p>{empty
          ? '补充库存后，才能继续给学员开课。'
          : '把码发给正在等开课的学员，是今天最直接的动作。'}</p>
        <Link to={empty ? '/buy' : '/codes'} className="wk-agent-primary-link">
          <Button size="lg" block>{empty ? '去进货' : '去发码'}</Button>
        </Link>
      </div>
      <div className="wk-agent-mobile-shortcuts">
        <Link to="/promote"><strong>推广给学员</strong><span>复制链接或二维码</span></Link>
        <Link to="/withdraw"><strong>查看提现</strong><span>可提 <Money fen={d.withdrawableFen} size="sm" /></span></Link>
      </div>
    </Card>
  );
}

function Kpis({ d, granted }: { d: Dash; granted?: number }) {
  const mom = d.lastMonthActivatedCodes;
  return (
    <div className="wk-agent-kpis" aria-label="经营指标">
      <Stat
        label="还没发出去的码"
        value={<span>{d.stockUnactivated}</span>}
        tone={d.stockUnactivated === 0 ? 'warn' : undefined}
        sub={d.stockUnactivated === 0
          ? <Link to="/buy">库存空了，去进货</Link>
          : <>可以直接发给学员 · <Link to="/codes">去看</Link></>}
      />
      <Stat
        label="本月激活"
        value={<span>{d.monthActivatedCodes}</span>}
        sub={mom === undefined || mom === null
          ? '本月已经有学员用码开课的数量'
          : `上月同口径 ${mom}`}
      />
      <Stat
        label="现在能提的佣金"
        value={<Money fen={d.withdrawableFen} size="xl" tone={d.withdrawableFen > 0 ? 'ok' : 'muted'} />}
        sub={d.inflightWithdrawFen > 0
          ? <>另有 <Money fen={d.inflightWithdrawFen} size="sm" /> 在途，已被提现单占着</>
          : (d.frozenCommissionFen > 0
              ? <>另有 <Money fen={d.frozenCommissionFen} size="sm" /> 还在冻结期</>
              : '过了冻结期的佣金才会进这里')}
      />
      {/* ★ 这里原来是第四格「累计进货金额」（d.purchasedAmountFen / monthPurchasedAmountFen）。
          删掉的理由见文件头：进货价一家一议，代理端不再出现任何进货价目，
          而这一格是同一个价的聚合形态 —— 除以码数就是单价。
          api.ts 的 Dashboard 类型里也把这两个键删了，谁想把这一格加回来会在 tsc 处当场红。 */}
      {/* 平台手工发放的码。三条决定了它长这样：
          ① <b>只在真有这种码时才出这一格</b>。绝大多数代理一个都没有，
             常年挂一个 0 会让三问三答的工作台多出一个不回答任何问题的格子。
          ② <b>是额外的一格，不从别的数里减</b>。这个数与「还没发出去的码」没有包含关系：
             那一栏只数未激活的，而这个数含已激活/已过期的 —— 白送的码被学员用掉之后，
             它会<b>大于</b>库存本身。所以文案里一个「其中」都不能写，写了代理就会去做减法。
          ③ <b>要说清它在哪儿能对上</b>。原来这句话指的是「累计进货金额」那一格
             （白送单实付恒 0，一分钱都不进那一栏）—— 那一格已经删了，
             再照抄那句话就是指着一个不存在的格子。现在指进货记录：
             白送的码挂在一张真订单上，那张单在「我的进货记录」里标着
             「平台发放 · 无需付款」。不指路的话，看到的就是「库里凭空多出一批码」。 */}
      {typeof granted === 'number' && granted > 0 && (
        <Stat
          label="平台发放给你的码"
          value={<span>{granted}</span>}
          sub={<>平台直接发到你名下、你没有付过款的码（累计各状态）。
                 同样有激活期限，也一样能发给学员；
                 在「我的进货记录」里对应标着「平台发放 · 无需付款」的那张单。</>}
        />
      )}
    </div>
  );
}

const MOBILE_DASH_CSS = `
.wk-agent-kpis {
  display: grid; gap: var(--s4);
  grid-template-columns: repeat(auto-fit, minmax(min(200px, 100%), 1fr));
}
@media (max-width: 860px) {
  .wk-agent-mobile-action { overflow: hidden; border-color: var(--line-strong); }
  .wk-agent-mobile-action-body { padding: var(--s4); }
  .wk-agent-mobile-action h2 { margin-top: var(--s3); font-size: 20px; line-height: 1.35; }
  .wk-agent-mobile-action p { margin-top: var(--s2); color: var(--ink-3); font-size: var(--t-sm); }
  .wk-agent-primary-link { display: block; margin-top: var(--s4); text-decoration: none; }
  .wk-agent-mobile-shortcuts {
    display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); border-top: 1px solid var(--line);
  }
  .wk-agent-mobile-shortcuts a {
    display: grid; gap: 2px; align-content: center; min-height: 76px; padding: var(--s3);
    color: var(--ink); text-decoration: none; text-align: center;
  }
  .wk-agent-mobile-shortcuts a + a { border-left: 1px solid var(--line); }
  .wk-agent-mobile-shortcuts a:active { background: var(--brand-soft); }
  .wk-agent-mobile-shortcuts strong { font-size: var(--t-sm); }
  .wk-agent-mobile-shortcuts span { color: var(--ink-3); font-size: var(--t-xs); }
  .wk-agent-kpis {
    display: flex; gap: var(--s3); overflow-x: auto; scroll-snap-type: x mandatory;
    padding: 0 var(--s4) var(--s2); margin-inline: calc(var(--s4) * -1);
    scrollbar-width: none; overscroll-behavior-inline: contain;
  }
  .wk-agent-kpis::-webkit-scrollbar { display: none; }
  .wk-agent-kpis > * { flex: 0 0 76%; scroll-snap-align: start; }
}
`;

function Todos({ d }: { d: Dash }) {
  const items: { key: string; tone: 'warn' | 'danger'; text: React.ReactNode; action: React.ReactNode }[] = [];

  if (d.expiringSoonCodes > 0) {
    items.push({
      key: 'expiring',
      tone: 'warn',
      text: <>
        有 <b className="num">{d.expiringSoonCodes}</b> 个码快到激活期限
        {d.nearestDeadline ? <>，最早的一个{untilText(d.nearestDeadline)}</> : null}。
        过期的码作废，钱不退，越早发出去越好。
      </>,
      action: <Link to="/codes?status=ISSUED"><Button size="sm" variant="ghost">去看这些码</Button></Link>,
    });
  }
  if (d.withdrawNeedsAttention > 0) {
    items.push({
      key: 'withdraw',
      tone: 'danger',
      text: <>
        有 <b className="num">{d.withdrawNeedsAttention}</b> 笔提现被驳回或打款失败，钱还在你的余额里，需要你重新处理。
      </>,
      action: <Link to="/withdraw"><Button size="sm" variant="ghost">去处理</Button></Link>,
    });
  }

  if (!items.length) {
    return (
      <Note tone="ok" title="没有待办">
        没有快过期的码，也没有卡住的提现。可以去发码或者进货了。
      </Note>
    );
  }

  return (
    <Section title="要你处理的事" desc="这几件不处理会真的损失钱，所以放在最前面。">
      <div style={{ display: 'grid', gap: 'var(--s3)' }}>
        {items.map(it => (
          <div key={it.key} style={{
            display: 'flex', gap: 'var(--s4)', alignItems: 'center', flexWrap: 'wrap',
            justifyContent: 'space-between',
            background: it.tone === 'danger' ? 'var(--danger-soft)' : 'var(--warn-soft)',
            borderLeft: `3px solid ${it.tone === 'danger' ? 'var(--danger)' : 'var(--warn)'}`,
            borderRadius: 'var(--r-sm)', padding: 'var(--s3) var(--s4)',
            fontSize: 'var(--t-sm)', color: 'var(--ink-2)',
          }}>
            <div style={{ flex: 1, minWidth: 200 }}>{it.text}</div>
            {it.action}
          </div>
        ))}
      </div>
    </Section>
  );
}

function RecentStudents() {
  const narrow = useNarrow();
  const state = useAsync(() => AgentApi.students({ page: 1, size: 8, sort: 'recent' }), []);

  const cols: Col<StudentRow>[] = [
    { key: 'phone', title: '学员', render: r => <span className="num">{r.phone}</span> },
    { key: 'activatedAt', title: '激活时间', render: r => dateTime(r.activatedAt) },
    { key: 'codeMasked', title: '用的码', render: r => <span className="num">{r.codeMasked || '—'}</span> },
    {
      key: 'progressPct', title: '学习进度', numeric: true, width: 96,
      render: r => r.progressPct === null || r.progressPct === undefined
        ? '—' : `${Math.round(r.progressPct)}%`,
    },
  ];

  return (
    <Section
      title="最近激活的学员"
      desc="有人激活就说明码发出去了、也用上了。"
      extra={<Link to="/students"><Button size="sm" variant="quiet">看全部</Button></Link>}
    >
      {state.loading ? <TableSkeleton rows={4} cols={4} /> : (
        <Async state={state}>
          {(d) => d.rows.length === 0 ? (
            <Empty
              icon={<IconDashboard size={22} />}
              title="还没有学员激活"
              desc="把激活码发给学员，他在学员端填码开课之后，这里就会出现他的名字。"
              action={<Link to="/codes"><Button variant="accent">去拿激活码</Button></Link>}
            />
          ) : narrow ? (
            <div style={{ display: 'grid', gap: 'var(--s2)' }}>
              {d.rows.map(r => (
                <Card key={r.userId} style={{ padding: 'var(--s3) var(--s4)' }}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', gap: 'var(--s3)' }}>
                    <span className="num">{r.phone}</span>
                    <span className="num" style={{ color: 'var(--ink-3)', fontSize: 'var(--t-sm)' }}>
                      {r.progressPct === null || r.progressPct === undefined
                        ? '—' : `${Math.round(r.progressPct)}%`}
                    </span>
                  </div>
                  <div style={{ fontSize: 'var(--t-xs)', color: 'var(--ink-3)', marginTop: 'var(--s1)' }}>
                    {dateTime(r.activatedAt)} 激活 · {r.codeMasked || '—'}
                  </div>
                </Card>
              ))}
            </div>
          ) : (
            <Table cols={cols} rows={d.rows} rowKey={r => r.userId} />
          )}
        </Async>
      )}
    </Section>
  );
}
