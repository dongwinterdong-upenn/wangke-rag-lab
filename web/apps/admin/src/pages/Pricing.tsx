import React from 'react';
import { api, yuan } from '@wangke/shared/api';
import { PageHead } from '@wangke/shared/shell';
import { Async, Button, Table, useAsync } from '@wangke/shared/ui';
import type { Col } from '@wangke/shared/ui';
import {
  ActionDialog, Blank, Board, Ctl, Fold, Money, Note, Pick, ReasonBox, Sect, St, TextIn,
} from '../lib/kit';
import { fenFromYuan, fmtTime, label, num, yuanInput } from '../lib/fmt';

/**
 * 价目：上半屏「标准价（按层级）」，下半屏「专属价（按代理）」。
 *
 * <h3>页面上一个价目字面量都没有</h3>
 * 全部从 /api/admin/pricing 取。理由写在 fmt.ts 的 fenFromYuan 头注上：
 * 这个包无需登录就能下载，硬编码的价目等于把成本价发出去。示例数字也不写真价。
 *
 * <h3>净收那三个数为什么必须由后端给</h3>
 * 佣金是按 commission_rule 算的（固定每码 / 按实付比例）。前端照公式再算一遍，
 * 就是第二套口径 —— 和后端 settleCommission 一旦漂移，
 * <b>运营在确认框里看到的净收和真正结算的净收就不是同一个数</b>，而两边都不报错。
 * 所以每次改价都先打 /preview，确认框里摆的就是后端算出来的那三个数。
 *
 * <h3>改价对已经下过的订单没有影响</h3>
 * 订单在下单那一刻把金额和取价来源都写进了自己的快照。这句话必须写在确认框里：
 * 运营最怕的就是「我改一下价，是不是把上个月那批单也改了」，
 * 没人告诉他的话他会去问研发，或者干脆不敢改。
 */
export function Pricing() {
  const [rev, bump] = React.useReducer((n: number) => n + 1, 0);
  const state = useAsync<any>(() => api.get('/api/admin/pricing'), [rev]);
  const [tierDlg, setTierDlg] = React.useState<any>(null);
  const [ovrDlg, setOvrDlg] = React.useState<any>(null);
  const [closeDlg, setCloseDlg] = React.useState<any>(null);

  return (
    <>
      <PageHead title="价目"
        desc="标准价按层级定，某几家谈过价的代理可以单独覆盖。改价留历史，已经下过的订单不受影响。" />

      <Async state={state}>{(d: any) => {
        const skus: any[] = d.skus ?? [];
        const agents: any[] = d.agents ?? [];
        const tiers: any[] = d.tiers ?? [];
        const overrides: any[] = d.overrides ?? [];

        const tierCols: Col<any>[] = [
          { key: 'agentLevel', title: '档', primary: true, render: r => (
            <div>
              <div>{r.agentLevel === 0 ? '学员零售' : label('level', String(r.agentLevel))}</div>
              <div style={{ color: 'var(--ink-3)', fontSize: 'var(--t-xs)' }}>{r.skuName}</div>
            </div>) },
          { key: 'skuCode', title: '套餐',
            render: r => <span className="num" style={{ fontSize: 'var(--t-xs)' }}>{r.skuCode}</span> },
          { key: 'codeQty', title: '含码数', numeric: true, render: r => num(r.codeQty) },
          { key: 'totalPriceFen', title: '整单价', numeric: true,
            render: r => <Money fen={r.totalPriceFen} bold /> },
          { key: 'unit', title: '折合每码', numeric: true,
            render: r => <Money fen={Math.round(num(r.totalPriceFen) / Math.max(1, num(r.codeQty)))} /> },
          { key: 'commissionFen', title: '返上级佣金', numeric: true,
            render: r => num(r.commissionFen) ? <Money fen={r.commissionFen} />
                                              : <span style={{ color: 'var(--ink-3)' }}>不返</span> },
          // 净收是这一页唯一一个「看不见就会漏钱」的数：它变成负数时，
          // 订单、佣金、对账表每一处都还是平的，只有月底汇总才发现。
          { key: 'netFen', title: '平台净收', numeric: true,
            render: r => <Money fen={r.netFen} bold tone={num(r.netFen) < 0 ? 'danger' : undefined} /> },
          { key: 'act', title: '', align: 'right',
            render: r => <Button size="sm" variant="ghost" onClick={() => setTierDlg(r)}>改价</Button> },
        ];

        const ovrCols: Col<any>[] = [
          { key: 'agent', title: '代理', primary: true, render: r => (
            <div style={{ minWidth: 140 }}>
              <div>{r.agentName}</div>
              <div className="num" style={{ color: 'var(--ink-3)', fontSize: 'var(--t-xs)' }}>
                #{r.agentId} · {r.agentCode} · {label('level', String(r.level))}
              </div>
            </div>) },
          { key: 'skuName', title: '套餐', render: r => (
            <div>
              <div>{r.skuName}</div>
              <div className="num" style={{ color: 'var(--ink-3)', fontSize: 'var(--t-xs)' }}>{r.skuCode}</div>
            </div>) },
          { key: 'totalPriceFen', title: '专属价', numeric: true,
            render: r => <Money fen={r.totalPriceFen} bold /> },
          // 标准价与差额并排。一个专属价单看是个孤零零的数字，改错一位没人看得出来；
          // 摆上标准价，「便宜了 50」和「便宜了 500」一眼就分得开。
          { key: 'tierPriceFen', title: '同档标准价', numeric: true,
            render: r => <Money fen={r.tierPriceFen} /> },
          { key: 'diff', title: '差额', numeric: true, render: r => {
            if (r.tierPriceFen === null || r.tierPriceFen === undefined)
              return <span style={{ color: 'var(--ink-3)' }}>—</span>;
            const diff = num(r.totalPriceFen) - num(r.tierPriceFen);
            return <span className="num" style={{ color: diff < 0 ? 'var(--warn)' : undefined }}>
              {diff === 0 ? '同价' : `${diff > 0 ? '+' : '−'}${yuan(Math.abs(diff))}`}
            </span>;
          } },
          { key: 'netFen', title: '平台净收', numeric: true,
            render: r => <Money fen={r.netFen} bold tone={num(r.netFen) < 0 ? 'danger' : undefined} /> },
          { key: 'reason', title: '定价原因', hideOnMobile: true,
            render: r => <span style={{ fontSize: 'var(--t-xs)', color: 'var(--ink-2)' }}>{r.reason}</span> },
          { key: 'act', title: '', align: 'right', render: r => (
            <span style={{ display: 'inline-flex', gap: 'var(--s2)' }}>
              <Button size="sm" variant="ghost" onClick={() => setOvrDlg(r)}>改价</Button>
              <Button size="sm" variant="quiet" onClick={() => setCloseDlg(r)}>撤销</Button>
            </span>) },
        ];

        return (
          <div style={{ display: 'grid', gap: 'var(--s5)' }}>
            <Board flush title="标准价（按层级）"
              desc="没有单独定价的代理都按这里的价进货。改一档就是改这一层的所有代理。">
              <Table cols={tierCols} rows={tiers} rowKey={r => r.id}
                     empty={<Blank filtered={false} thing="生效中的标准价" />} />
            </Board>

            <Board flush title="专属价（按代理）"
              desc="只对这一家生效。撤销之后这家回到同档标准价 —— 撤销是封口不是删行，历史留着。"
              extra={<Button size="sm" onClick={() => setOvrDlg({ isNew: true })}>给某家代理定价</Button>}>
              <Table cols={ovrCols} rows={overrides} rowKey={r => r.id}
                     empty={<Blank filtered={false} thing="专属价"
                                   action={<Button size="sm" onClick={() => setOvrDlg({ isNew: true })}>
                                     给某家代理定价</Button>} />} />
            </Board>

            <Board title="改价历史"
              desc="每一次改价都是「给旧行封口 + 插一行新价」，所以老订单永远查得回当时的价。">
              <Fold summary={`标准价的改动（${(d.tierHistory ?? []).length} 条）`}>
                <History rows={d.tierHistory ?? []} kind="tier" />
              </Fold>
              <div style={{ height: 'var(--s3)' }} />
              <Fold summary={`专属价的改动（${(d.overrideHistory ?? []).length} 条）`}>
                <History rows={d.overrideHistory ?? []} kind="override" />
              </Fold>
            </Board>

            {tierDlg && (
              <TierDialog row={tierDlg} onClose={() => setTierDlg(null)} onDone={bump} />
            )}
            {ovrDlg && (
              <OverrideDialog row={ovrDlg} skus={skus} agents={agents} existing={overrides}
                              onClose={() => setOvrDlg(null)} onDone={bump} />
            )}
            {closeDlg && (
              <CloseDialog row={closeDlg} onClose={() => setCloseDlg(null)} onDone={bump} />
            )}
          </div>
        );
      }}</Async>
    </>
  );
}

/* ------------------------------------------------------------------ 历史 */

function History({ rows, kind }: { rows: any[]; kind: 'tier' | 'override' }) {
  if (!rows.length) return (
    <div style={{ fontSize: 'var(--t-sm)', color: 'var(--ink-3)' }}>
      还没有改过价。（这里空着是正常的，不是没加载出来）
    </div>
  );
  const cols: Col<any>[] = [
    ...(kind === 'override'
      ? [{ key: 'agent', title: '代理', primary: true,
           render: (r: any) => <span>{r.agentName}<span className="num" style={{
             color: 'var(--ink-3)', fontSize: 'var(--t-xs)' }}> #{r.agentId}</span></span> }]
      : [{ key: 'agentLevel', title: '档', primary: true,
           render: (r: any) => r.agentLevel === 0 ? '学员零售' : label('level', String(r.agentLevel)) }]),
    { key: 'skuName', title: '套餐', render: r => r.skuName },
    { key: 'totalPriceFen', title: '当时的价', numeric: true, render: r => <Money fen={r.totalPriceFen} /> },
    { key: 'effectiveFrom', title: '生效', render: r => fmtTime(r.effectiveFrom) },
    { key: 'effectiveTo', title: '停用', render: r => fmtTime(r.effectiveTo) },
    ...(kind === 'override'
      ? [{ key: 'reason', title: '当时的原因',
           render: (r: any) => <span style={{ fontSize: 'var(--t-xs)' }}>{r.reason}</span> }]
      : []),
  ];
  return <Table cols={cols} rows={rows} rowKey={r => r.id} />;
}

/* ------------------------------------------------------------------ 试算 */

type Preview = {
  totalPriceFen: number; commissionFen: number; netFen: number; calcMode: string | null;
  tierPriceFen: number | null; belowTier: boolean; negative: boolean; codeQty: number;
  blocker: string | null;
};

/**
 * 边打边试算。
 *
 * <p>seq 那个计数器不是防抖的替代品，是<b>竞态的唯一防线</b>：输入框里连打几个字会发出
 * 好几个请求，而它们的返回顺序不保证。没有它的话，屏幕上最后停住的可能是<b>倒数第二次</b>
 * 输入的试算结果 —— 于是运营看着上一个数按下确认，实际提交的是另一个价。
 * （这段注释里也不写真价目：示例数字同样会被打进 /admin/assets/*.js，见 fmt.ts 的红线。）
 */
function usePreview(body: any, enabled: boolean) {
  const [pv, setPv] = React.useState<Preview | null>(null);
  const [err, setErr] = React.useState<string | null>(null);
  const seq = React.useRef(0);
  const key = JSON.stringify(body);

  React.useEffect(() => {
    if (!enabled) { setPv(null); setErr(null); return; }
    const mine = ++seq.current;
    let alive = true;
    const t = setTimeout(async () => {
      try {
        const r = await api.post<any>('/api/admin/pricing/preview', body);
        if (alive && mine === seq.current) { setPv(r.preview); setErr(null); }
      } catch (e) {
        if (alive && mine === seq.current) { setPv(null); setErr((e as Error)?.message || '算不出来'); }
      }
    }, 250);
    return () => { alive = false; clearTimeout(t); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, enabled]);

  return { pv, err };
}

/** 试算结果的三个数。净收为负时它是红的，而且确认按钮会被禁掉。 */
function NetBox({ pv, err }: { pv: Preview | null; err: string | null }) {
  if (err) return <Note tone="warn" title="算不出这个价的净收">{err}。先别提交，刷新一下再试。</Note>;
  if (!pv) return (
    <Note tone="info" title="填个价，这里会算给你看">
      平台每卖一单实际收到多少 = 成交价 − 要返给上级代理的佣金。
    </Note>
  );
  return (
    <Note tone={pv.negative ? 'danger' : pv.belowTier ? 'warn' : 'ok'}
          title={pv.negative ? '这个价平台每卖一单都在倒贴'
                : pv.belowTier ? '低于同档标准价 —— 确认一下这是你要的' : '这个价平台每单实收'}>
      <div style={{ display: 'grid', gap: 'var(--s1)' }}>
        <div>成交价 <b className="num">{yuan(pv.totalPriceFen)}</b>
          {pv.codeQty > 1 && <span style={{ color: 'var(--ink-3)' }}>
            （{pv.codeQty} 个码，折合每码 {yuan(Math.round(pv.totalPriceFen / pv.codeQty))}）</span>}
        </div>
        <div>返给上级代理的佣金 <b className="num">−{yuan(pv.commissionFen)}</b>
          {pv.calcMode
            ? <span style={{ color: 'var(--ink-3)' }}>
                （{pv.calcMode === 'FIXED_PER_CODE' ? '按码固定' : '按实付比例'}）</span>
            : <span style={{ color: 'var(--ink-3)' }}>（这一档不返佣金）</span>}
        </div>
        <div style={{ borderTop: '1px solid var(--line)', paddingTop: 'var(--s1)' }}>
          平台净收 <b className="num" style={{ color: pv.negative ? 'var(--danger)' : undefined }}>
            {yuan(pv.netFen)}</b>
          {pv.tierPriceFen !== null && (
            <span style={{ color: 'var(--ink-3)' }}>
              　·　同档标准价 {yuan(pv.tierPriceFen)}
              {pv.totalPriceFen !== pv.tierPriceFen &&
                `（${pv.totalPriceFen < pv.tierPriceFen ? '便宜' : '贵'} ${yuan(Math.abs(pv.totalPriceFen - pv.tierPriceFen))}）`}
            </span>
          )}
        </div>
        {pv.blocker && <div style={{ color: 'var(--danger)' }}>{pv.blocker}</div>}
      </div>
    </Note>
  );
}

/** 金额输入。元⇄分的换算只走 fenFromYuan，页面里绝不自己乘 100。 */
function MoneyIn({ value, onChange, err, onErr, hint }: {
  value: string; onChange: (v: string) => void;
  err: string | null; onErr: (m: string | null) => void; hint?: React.ReactNode;
}) {
  return (
    <Ctl label="整单价（元）" width={220} hint={err ?? hint}>
      <TextIn value={value} inputMode="decimal" placeholder="填元，最多两位小数"
              onChange={e => {
                onChange(e.target.value);
                try { e.target.value.trim() && fenFromYuan(e.target.value); onErr(null); }
                catch (x) { onErr((x as Error).message); }
              }} />
    </Ctl>
  );
}

function toFen(s: string): number | null {
  try { return s.trim() ? fenFromYuan(s) : null; } catch { return null; }
}

/* ------------------------------------------------------------------ 改标准价 */

function TierDialog({ row, onClose, onDone }: { row: any; onClose: () => void; onDone: () => void }) {
  const [amt, setAmt] = React.useState(yuanInput(num(row.totalPriceFen)));
  const [err, setErr] = React.useState<string | null>(null);
  const [reason, setReason] = React.useState('');
  const fen = toFen(amt);
  const { pv, err: pvErr } = usePreview(
    { agentLevel: row.agentLevel, skuId: row.skuId, totalPriceFen: fen }, fen !== null);
  const levelText = row.agentLevel === 0 ? '学员零售' : label('level', String(row.agentLevel));

  return (
    <ActionDialog open onClose={onClose} verb="改价" width={620} onDone={onDone}
      title={<>改标准价 · {levelText} · {row.skuName}</>}
      disabled={fen === null || !reason.trim() || !!pv?.negative}
      consequences={<>
        这一档下<b>所有没有单独定价的代理</b>，从保存那一刻起按新价进货。<br />
        <b>已经下过的订单一张都不受影响</b> —— 订单在下单时就把金额和取价来源写进了自己的快照，
        改价不会回头去动它们。<br />
        旧价不会被覆盖：系统给它封口、再插一行新价，所以老订单永远查得回当时的价。
      </>}
      onConfirm={() => api.post('/api/admin/pricing/tiers', {
        agentLevel: row.agentLevel, skuId: row.skuId,
        totalPriceFen: fenFromYuan(amt), reason: reason.trim(),
      })}>
      <div style={{ display: 'grid', gap: 'var(--s4)' }}>
        <MoneyIn value={amt} onChange={setAmt} err={err} onErr={setErr}
                 hint={<>现价 {yuan(num(row.totalPriceFen))}
                   {num(row.codeQty) > 1 && <>　·　本档含 {num(row.codeQty)} 个码</>}</>} />
        <NetBox pv={pv} err={pvErr} />
        <ReasonBox value={reason} onChange={setReason} label="改价原因"
                   hint="进审计日志。事后有人问「这一档什么时候降的、为什么」，只有这一句能答。" />
      </div>
    </ActionDialog>
  );
}

/* ------------------------------------------------------------------ 设/改专属价 */

function OverrideDialog({ row, skus, agents, existing, onClose, onDone }: {
  row: any; skus: any[]; agents: any[]; existing: any[];
  onClose: () => void; onDone: () => void;
}) {
  const isNew = !!row.isNew;
  const [agentId, setAgentId] = React.useState(isNew ? '' : String(row.agentId));
  const [skuId, setSkuId] = React.useState(isNew ? '' : String(row.skuId));
  const [amt, setAmt] = React.useState(isNew ? '' : yuanInput(num(row.totalPriceFen)));
  const [err, setErr] = React.useState<string | null>(null);
  const [reason, setReason] = React.useState('');
  const fen = toFen(amt);

  // 只出代理档：学员直购档给代理设专属价没有任何效果，后端也会拒。
  // 让它出现在下拉里，等于把一个必然失败的选项摆在人面前。
  const agentSkus = skus.filter(s => s.buyerType === 'AGENT' && s.status === 'ON');
  const ready = !!agentId && !!skuId && fen !== null;
  const { pv, err: pvErr } = usePreview(
    { agentId: Number(agentId), skuId: Number(skuId), totalPriceFen: fen }, ready);
  const agent = agents.find(a => String(a.agentId) === agentId);

  /**
   * 「新增」的框里选中一个【已经有专属价】的组合时，这一步其实是<b>改价</b>：
   * 后端会把现有那份封口再插新行。不说的话，运营以为自己在新加一档，
   * 提交之后原来那份就停用了 —— 而列表里只是某一行的数字变了，看不出发生过替换。
   */
  const dup = isNew
    ? existing.find(o => String(o.agentId) === agentId && String(o.skuId) === skuId)
    : null;

  return (
    <ActionDialog open onClose={onClose} verb={isNew ? '定价' : '改价'} width={620} onDone={onDone}
      title={isNew ? '给某家代理设专属价' : <>改专属价 · {row.agentName} · {row.skuName}</>}
      disabled={!ready || !reason.trim() || !!pv?.negative}
      consequences={<>
        这份价<b>只对这一家生效</b>，其他代理照旧走标准价。保存那一刻起，这家按新价进货。<br />
        <b>已经下过的订单一张都不受影响</b> —— 订单存的是下单当时的金额快照。<br />
        {!isNew && <>原来那份专属价会被封口保留，不会被覆盖，历史查得到。<br /></>}
        以后不想再给单独价了，用「撤销」把它封口，这家自动回到标准价。
      </>}
      onConfirm={() => api.post('/api/admin/pricing/overrides', {
        agentId: Number(agentId), skuId: Number(skuId),
        totalPriceFen: fenFromYuan(amt), reason: reason.trim(),
      })}>
      <div style={{ display: 'grid', gap: 'var(--s4)' }}>
        {isNew && (
          <div style={{ display: 'flex', gap: 'var(--s4)', flexWrap: 'wrap' }}>
            <Ctl label="代理" width={260} grow
                 hint="只列合作中的代理。已暂停/已终止的定了价也取不到。">
              <Pick value={agentId} onChange={setAgentId} all={false} placeholder="请选择代理"
                    options={agents.map(a => ({
                      value: String(a.agentId),
                      text: `${a.name}（${a.agentCode} · ${label('level', String(a.level))}）`,
                    }))} />
            </Ctl>
            <Ctl label="套餐" width={220} grow hint="只列在售的代理档。">
              <Pick value={skuId} onChange={setSkuId} all={false} placeholder="请选择套餐"
                    options={agentSkus.map(s => ({
                      value: String(s.skuId), text: `${s.name}（${s.codeQty} 个码）`,
                    }))} />
            </Ctl>
          </div>
        )}
        {dup && (
          <Note tone="warn" title="这一档已经有专属价了，这一步是改价不是新增">
            {dup.agentName} 的「{dup.skuName}」现在是 <b>{yuan(num(dup.totalPriceFen))}</b>。
            提交之后那一份会被封口停用（历史留着），新价立刻生效。
          </Note>
        )}
        {!isNew && (
          <div style={{ fontSize: 'var(--t-sm)', color: 'var(--ink-2)' }}>
            <St domain="level" value={String(row.level)} />　{row.agentName}
            <span className="num" style={{ color: 'var(--ink-3)' }}> #{row.agentId} · {row.agentCode}</span>
            　·　{row.skuName}
          </div>
        )}
        <MoneyIn value={amt} onChange={setAmt} err={err} onErr={setErr}
                 hint={isNew
                   ? (agent ? `这家是${label('level', String(agent.level))}，先选套餐再填价，下面会算给你看`
                            : '先选代理和套餐')
                   : <>现在的专属价 {yuan(num(row.totalPriceFen))}</>} />
        <NetBox pv={pv} err={pvErr} />
        <ReasonBox value={reason} onChange={setReason} label="定价原因"
                   hint="会存进这一行价目里，不只是审计。事后有人问「这家凭什么比别人便宜」，只有这一句能答。" />
      </div>
    </ActionDialog>
  );
}

/* ------------------------------------------------------------------ 撤销专属价 */

function CloseDialog({ row, onClose, onDone }: { row: any; onClose: () => void; onDone: () => void }) {
  const [reason, setReason] = React.useState('');
  return (
    <ActionDialog open onClose={onClose} verb="撤销" tone="danger" width={560} onDone={onDone}
      title={<>撤销专属价 · {row.agentName} · {row.skuName}</>}
      disabled={!reason.trim()}
      consequences={<>
        这家代理从此按<b>同档标准价</b>
        {row.tierPriceFen !== null && row.tierPriceFen !== undefined
          ? <>（{yuan(num(row.tierPriceFen))}）</> : null}进货，
        现在这份 {yuan(num(row.totalPriceFen))} 的价立刻停用。<br />
        <b>已经下过的订单一张都不受影响。</b><br />
        撤销是给这一行封口，<b>不是删掉它</b> —— 按这个价成交过的老订单还查得回当时的价。
        想恢复的话重新定一次价即可。
      </>}
      onConfirm={() => api.post(`/api/admin/pricing/overrides/${row.id}/close`,
                                { reason: reason.trim() })}>
      <Sect title="撤销原因">
        <ReasonBox value={reason} onChange={setReason} label="撤销原因"
                   hint="进审计日志。代理下次进货发现价变了会来问，只有这一句能答。" />
      </Sect>
    </ActionDialog>
  );
}
