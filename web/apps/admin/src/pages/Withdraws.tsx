import React from 'react';
import { Link } from 'react-router-dom';
import { api, yuan } from '@wangke/shared/api';
import { PageHead } from '@wangke/shared/shell';
import { Async, Button, Table, useAsync } from '@wangke/shared/ui';
import type { Col } from '@wangke/shared/ui';
import {
  ActionDialog, AuditTrail, Blank, Board, Ctl, DetailModal, Filters, KV, Money, Note, Pager, Pick,
  ReasonBox, Sect, St, TextIn,
} from '../lib/kit';
import { MiniTable } from './Orders';
import { fenFromYuan, fmtTime, num, options, yuanInput } from '../lib/fmt';
import { hasFilter, qs, useQ } from '../lib/q';

export function Withdraws() {
  const { sp, get, int, set, reset } = useQ();
  const status = get('status'), agentId = get('agentId');
  const from = get('from'), to = get('to');
  const page = int('page', 1), size = int('size', 20);
  const sel = get('sel');
  const [rev, bump] = React.useReducer((n: number) => n + 1, 0);

  const list = useAsync<any>(
    () => api.get(`/api/admin/withdraws${qs({ status, agentId, from, to, page, size })}`),
    [status, agentId, from, to, page, size, rev]);

  const filtered = hasFilter(sp);

  const cols: Col<any>[] = [
    { key: 'withdrawNo', title: '提现单号',
      render: r => <span className="num" style={{ fontSize: 'var(--t-xs)' }}>{r.withdrawNo}</span> },
    { key: 'agent', title: '代理', render: r => (
      <div style={{ minWidth: 140 }}>
        <div>{r.companyName || `代理 #${r.agentId}`}</div>
        <div className="num" style={{ color: 'var(--ink-3)', fontSize: 'var(--t-xs)' }}>
          #{r.agentId} {r.agentCode ? `· ${r.agentCode}` : ''}
        </div>
      </div>) },
    { key: 'amountFen', title: '申请额', numeric: true, render: r => <Money fen={r.amountFen} bold /> },
    { key: 'taxWithheldFen', title: '代扣个税', numeric: true, render: r => <Money fen={r.taxWithheldFen} /> },
    { key: 'actualPaidFen', title: '实付', numeric: true, render: r => <Money fen={r.actualPaidFen} /> },
    { key: 'payeeName', title: '收款人', render: r => (
      <div><div>{r.payeeName}</div>
        <div className="num" style={{ color: 'var(--ink-3)', fontSize: 'var(--t-xs)' }}>
          {r.payeeAccountMasked}</div></div>) },
    { key: 'channel', title: '渠道', render: r => <St domain="channel" value={r.channel} /> },
    { key: 'status', title: '状态', render: r => <St domain="withdraw" value={r.status} /> },
    { key: 'createdAt', title: '申请时间', render: r => fmtTime(r.createdAt) },
  ];

  return (
    <>
      <PageHead title="提现审批"
        desc="审核 → 通过 → 打款 → 标记已打款。每一步都不能跳，跳步等于绕过风控把钱打出去。" />

      <Filters active={filtered} onReset={() => reset()}>
        <Ctl label="状态" width={150}>
          <Pick value={status} onChange={v => set({ status: v })} options={options('withdraw')} />
        </Ctl>
        <Ctl label="代理 ID" width={130}>
          <TextIn defaultValue={agentId} inputMode="numeric" placeholder="回车"
                  onKeyDown={e => { if (e.key === 'Enter') set({ agentId: e.currentTarget.value.trim() }); }} />
        </Ctl>
        <Ctl label="申请时间从" width={150}>
          <TextIn type="date" value={from} onChange={e => set({ from: e.target.value })} />
        </Ctl>
        <Ctl label="到" width={150}>
          <TextIn type="date" value={to} onChange={e => set({ to: e.target.value })} />
        </Ctl>
      </Filters>

      <Board flush title={`提现单${list.data ? ` · ${num(list.data.total)} 张` : ''}`}>
        <Async state={list}>{(d: any) => (
          <>
            <Table cols={cols} rows={d.items} rowKey={r => r.id}
                   onRowClick={r => set({ sel: r.id }, true)}
                   empty={<Blank filtered={filtered} thing="提现单" onClear={() => reset()} />} />
            <Pager total={num(d.total)} page={page} size={size}
                   onPage={p => set({ page: p }, true)} onSize={s => set({ size: s })} />
          </>
        )}</Async>
      </Board>

      {sel && <WithdrawDetail id={sel} onClose={() => set({ sel: '' }, true)} onChanged={bump} />}
    </>
  );
}

function WithdrawDetail({ id, onClose, onChanged }: {
  id: string; onClose: () => void; onChanged: () => void;
}) {
  const [rev, bump] = React.useReducer((n: number) => n + 1, 0);
  const state = useAsync<any>(() => api.get(`/api/admin/withdraws/${id}`), [id, rev]);
  const [dlg, setDlg] = React.useState<null | 'approve' | 'reject' | 'paid' | 'fail'>(null);
  const w = state.data?.withdraw;
  const after = () => { bump(); onChanged(); };

  // 下面 actions 里的按钮必须逐档对齐后端接受的状态，多一个少一个都不是「界面小问题」：
  //
  //   · FAILED 原来只给「标记已打款」—— 而 markPaid 对 FAILED 一律 409
  //     （打款必须经过 审核→通过，不可跳步）。也就是说打款失败的单子在后台
  //     【唯一能点的按钮必定报错】，而真正能走的那个（重新审核通过）根本不显示。
  //     FAILED 还占着代理的可提额度，解不开就等于把他的钱锁死。
  //   · REVIEWING 原来一个按钮都没有。后端 approve/reject 都接受它；正常流程下
  //     它只是 approve 事务里的中间态，可一旦上一次操作在中途断掉，单子就停在这一档，
  //     后台从此对它无能为力。
  //   · 驳回现在也接受 FAILED —— 那是 FAILED 的另一条出路（终态，额度当场释放）。
  return (
    <DetailModal open onClose={onClose}
      title={w ? <span className="num">{w.withdrawNo}</span> : `提现单 #${id}`}
      subtitle={w ? <>{yuan(num(w.amountFen))} · <St domain="withdraw" value={w.status} /></> : undefined}
      actions={w && (<>
        {(w.status === 'APPLIED' || w.status === 'REVIEWING') && <>
          <Button variant="danger" onClick={() => setDlg('reject')}>驳回</Button>
          <Button onClick={() => setDlg('approve')}>审核通过</Button>
        </>}
        {w.status === 'APPROVED' && (
          <Button onClick={() => setDlg('paid')}>标记已打款</Button>)}
        {w.status === 'FAILED' && <>
          <Button variant="danger" onClick={() => setDlg('reject')}>驳回，退回额度</Button>
          <Button onClick={() => setDlg('approve')}>重新审核通过</Button>
        </>}
        {w.status === 'PAYING' && <>
          <Button variant="ghost" onClick={() => setDlg('fail')}>标记打款失败</Button>
          <Button onClick={() => setDlg('paid')}>标记已打款</Button>
        </>}
      </>)}>
      <Async state={state}>{(d: any) => {
        const g = d.withdraw;
        const mismatch = g.payeeMatchesRealName === false;
        return (
          <div>
            {mismatch && (
              <Note tone="danger" title="收款人和实名对不上">
                收款人写的是「{g.payeeName}」，账号实名是「{g.agentRealName || '（未填）'}」。
                按约定<b>不允许代收</b>：打给别人的钱，事后既算不清个税，也说不清是谁拿走的。
                核实清楚再批。
              </Note>
            )}

            <KV items={[
              ['提现单号', <span className="num">{g.withdrawNo}</span>],
              ['状态', <St domain="withdraw" value={g.status} />],
              ['代理', <Link to={`/agents${qs({ sel: g.agentId })}`}>
                {g.companyName || `代理 #${g.agentId}`}</Link>],
              ['申请金额', <Money fen={g.amountFen} bold />],
              ['代扣个税', <Money fen={g.taxWithheldFen} />],
              ['实付到账', <Money fen={g.actualPaidFen} />],
              ['当前可提余额', <Money fen={g.commissionBalanceFen} />],
              ['渠道', <St domain="channel" value={g.channel} />],
              ['收款人', g.payeeName],
              ['账号实名', g.agentRealName],
              ['收款账号', <span className="num">{g.payeeAccount ?? g.payeeAccountMasked}</span>],
              ['审核人 / 时间', g.reviewedBy ? <>#{g.reviewedBy} · {fmtTime(g.reviewedAt)}</> : null],
              ['打款流水号', g.providerTxnId ? <span className="num">{g.providerTxnId}</span> : null],
              ['发票号', g.invoiceNo ? <span className="num">{g.invoiceNo}</span> : null],
              ['打款时间', fmtTime(g.paidAt)],
              ['申请时间', fmtTime(g.createdAt)],
              ['风控备注', g.riskNote],
              ['失败原因', g.failReason],
            ]} />
            <div style={{ marginTop: 'var(--s3)' }}>
              <Note tone="warn">
                这一页显示了<b>完整收款账号</b>，这次查看已经记进审计日志。看完请及时关闭 ——
                审批列表天天开着，一整列真实银行账号被截图带走一次就够了。
              </Note>
            </div>

            <Sect title="他还有几笔在排队" extra={
              <span style={{ fontSize: 'var(--t-xs)', color: 'var(--ink-3)' }}>
                在途的提现单也占额度
              </span>}>
              {(g.inflight ?? []).length === 0
                ? <span style={{ fontSize: 'var(--t-sm)', color: 'var(--ink-3)' }}>
                    没有其他在途的提现单
                  </span>
                : <>
                    <MiniTable rows={g.inflight} cols={[
                      ['状态', (r: any) => <St domain="withdraw" value={r.status} />],
                      ['笔数', (r: any) => <span className="num">{num(r.n)}</span>],
                      ['金额合计', (r: any) => <Money fen={r.amt} />],
                    ]} />
                    <div style={{ marginTop: 'var(--s3)' }}>
                      <Note>
                        余额只在打款成功那一刻才扣。只看余额批的话，10 万余额连提三笔 6 万都能过闸 ——
                        所以判据是「余额 减去 在途」。这张表就是那个「在途」。
                      </Note>
                    </div>
                  </>}
            </Sect>

            <Sect title="改动记录">
              <AuditTrail targetType="withdraw_order" targetId={id} refreshKey={rev} />
            </Sect>
          </div>
        );
      }}</Async>

      {w && <Dialogs which={dlg} close={() => setDlg(null)} w={w} onDone={after} />}
    </DetailModal>
  );
}

function Dialogs({ which, close, w, onDone }: {
  which: string | null; close: () => void; w: any; onDone: () => void;
}) {
  const amount = num(w.amountFen);
  const [note, setNote] = React.useState('');
  const [reason, setReason] = React.useState('');
  const [txn, setTxn] = React.useState('');
  const [tax, setTax] = React.useState('0.00');
  const [actual, setActual] = React.useState(yuanInput(amount));
  const [paidAt, setPaidAt] = React.useState('');

  React.useEffect(() => {
    setNote(''); setReason(''); setTxn('');
    setTax('0.00'); setActual(yuanInput(amount)); setPaidAt('');
  }, [which, amount]);

  let taxFen: number | null = null, actualFen: number | null = null;
  try { taxFen = fenFromYuan(tax); } catch { taxFen = null; }
  try { actualFen = fenFromYuan(actual); } catch { actualFen = null; }
  const sum = taxFen !== null && actualFen !== null ? taxFen + actualFen : null;
  const balanced = sum === amount;

  return (
    <>
      <ActionDialog open={which === 'approve'} onClose={close} verb="通过" onDone={onDone}
        title="审核通过这笔提现"
        consequences={<>
          通过之后<b>钱还没有出去</b> —— 还要实际转账，然后回来「标记已打款」，那一步才扣余额。<br />
          {w.status === 'FAILED'
            ? <>这是上一次打款失败后的<b>重试</b>：请先确认收款信息已经改对，
                否则会再退一次。这笔额度一直没有释放过，重试不会重复占用。<br /></>
            : <>通过之后这笔的额度会一直被占着（在途也算占用），直到打款成功或被驳回。<br /></>}
          打款必须打给<b>实名本人</b>，不允许代收。
        </>}
        onConfirm={() => api.post(`/api/admin/withdraws/${w.id}/approve`, { note: note.trim() || undefined })}>
        <Ctl label="审核备注（可不填）" width={320} grow hint="比如「已电话核实收款账号」。会进审计日志。">
          <TextIn value={note} onChange={e => setNote(e.target.value)} />
        </Ctl>
      </ActionDialog>

      <ActionDialog open={which === 'reject'} onClose={close} verb="驳回" tone="danger" onDone={onDone}
        title="驳回这笔提现"
        disabled={!reason.trim()}
        consequences={<>
          驳回是<b>终态</b>，这张单不能再被审核。代理要提现得重新申请。<br />
          驳回会<b>释放它占用的可提现额度</b>，代理马上就能重新提。<br />
          {w.status === 'FAILED' && <>
            这一单已经打款失败过：钱<b>没有</b>出账，余额也没扣，驳回只是把它就此了结。
            要再试一次转账请改点「重新审核通过」，不要驳回。<br />
          </>}
          理由会回给代理看 —— 不写清楚，他只会原样再提一次。
        </>}
        onConfirm={() => api.post(`/api/admin/withdraws/${w.id}/reject`, { reason: reason.trim() })}>
        <ReasonBox value={reason} onChange={setReason} label="驳回理由" />
      </ActionDialog>

      <ActionDialog open={which === 'paid'} onClose={close} verb="登记打款" onDone={onDone} width={560}
        title="标记这笔提现已经打款"
        disabled={!txn.trim() || !balanced}
        consequences={<>
          这一步会<b>真正扣减该代理的佣金余额</b>，并写进佣金台账。<br />
          流水号上有唯一键：同一笔转账不可能被记两次，所以不确定是否点过就再点一次，
          比在库里查更安全。<br />
          <b>实付 + 代扣个税 必须正好等于申请金额</b>，差一分都要查 —— 对不上的账，
          事后没人能还原当时到底打了多少。
        </>}
        onConfirm={() => api.post(`/api/admin/withdraws/${w.id}/paid`, {
          providerTxnId: txn.trim(),
          taxWithheldFen: fenFromYuan(tax),
          actualPaidFen: fenFromYuan(actual),
          paidAt: paidAt || undefined,
        })}>
        <div style={{ display: 'grid', gap: 'var(--s4)' }}>
          <Ctl label="打款流水号" width={320} grow
               hint="银行回单 / 微信转账单号。没有它，这笔钱事后无法与银行对账。">
            <TextIn value={txn} onChange={e => setTxn(e.target.value)} placeholder="必填" />
          </Ctl>
          <div style={{ display: 'flex', gap: 'var(--s4)', flexWrap: 'wrap' }}>
            <Ctl label="代扣个税（元）" width={160}>
              <TextIn value={tax} inputMode="decimal" onChange={e => setTax(e.target.value)} />
            </Ctl>
            <Ctl label="实付到账（元）" width={160}>
              <TextIn value={actual} inputMode="decimal" onChange={e => setActual(e.target.value)} />
            </Ctl>
          </div>
          <Note tone={balanced ? 'ok' : 'danger'}
                title={balanced ? '对得上' : '对不上账'}>
            申请金额 {yuan(amount)}；
            实付 {actualFen === null ? '（填得不对）' : yuan(actualFen)}
            ＋ 代扣 {taxFen === null ? '（填得不对）' : yuan(taxFen)}
            ＝ {sum === null ? '—' : yuan(sum)}。
            {balanced ? '' : '这样提交会被服务端拒绝。'}
          </Note>
          <Ctl label="打款时间" width={220} hint="按回单填。留空按现在算。不能填未来时间。">
            <TextIn type="datetime-local" value={paidAt} onChange={e => setPaidAt(e.target.value)} />
          </Ctl>
        </div>
      </ActionDialog>

      <ActionDialog open={which === 'fail'} onClose={close} verb="标记失败" tone="danger" onDone={onDone}
        title="标记这笔提现打款失败"
        disabled={!reason.trim()}
        consequences={<>
          标记失败之后这张单还能救：修正收款信息后可以重新审核通过并再次打款。<br />
          余额<b>不会</b>被扣 —— 钱没出去，账上就不该有这笔。<br />
          但这笔金额<b>仍然占着该代理的可提额度</b>：这张单还没终结，随时可能被重新打出去。
          所以它必须走到终点 —— 要么重新打款成功，要么驳回（额度当场退回，他再重新申请）。
        </>}
        onConfirm={() => api.post(`/api/admin/withdraws/${w.id}/fail`, { reason: reason.trim() })}>
        <ReasonBox value={reason} onChange={setReason} label="失败原因"
                   hint="比如「账号名与开户名不符，银行退回」。" />
      </ActionDialog>
    </>
  );
}
