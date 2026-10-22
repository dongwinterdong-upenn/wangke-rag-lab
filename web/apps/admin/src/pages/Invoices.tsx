import React from 'react';
import { Link } from 'react-router-dom';
import { api } from '@wangke/shared/api';
import { PageHead } from '@wangke/shared/shell';
import { Async, Button, Table, useAsync } from '@wangke/shared/ui';
import type { Col } from '@wangke/shared/ui';
import {
  ActionDialog, Blank, Board, Ctl, Filters, Money, Note, Pick, ReasonBox, St, TextIn,
} from '../lib/kit';
import { fmtTime, num, options } from '../lib/fmt';
import { qs, useQ } from '../lib/q';

/**
 * 开票。
 *
 * 开票接口按状态取一批（默认待开票），没有分页 —— 和答疑队列一样，
 * 界面要说清「这是一批，不是全部」。
 */
export function Invoices() {
  const { get, int, set, reset } = useQ();
  const status = get('status') || 'APPLIED';
  const limit = int('limit', 50);
  const [rev, bump] = React.useReducer((n: number) => n + 1, 0);
  const [dlg, setDlg] = React.useState<{ kind: 'issue' | 'reject'; row: any } | null>(null);

  const list = useAsync<any>(
    () => api.get(`/api/invoice/admin/list${qs({ status, limit })}`), [status, limit, rev]);

  const rows: any[] = list.data?.invoices ?? [];

  const cols: Col<any>[] = [
    { key: 'id', title: 'ID', numeric: true, width: 60 },
    { key: 'title', title: '发票抬头', render: r => (
      <div style={{ minWidth: 200 }}>
        <div>{r.title}</div>
        {r.taxNo && <div className="num" style={{ color: 'var(--ink-3)', fontSize: 'var(--t-xs)' }}>
          税号 {r.taxNo}</div>}
      </div>) },
    { key: 'amountFen', title: '开票金额', numeric: true, render: r => <Money fen={r.amountFen} bold /> },
    { key: 'scope', title: '开给什么', render: r => (
      <span style={{ fontSize: 'var(--t-sm)' }}>
        {r.scope === 'ORDER'
          ? <Link to={`/orders${qs({ sel: r.refId })}`}>订单 #{r.refId}</Link>
          : <Link to={`/withdraws${qs({ sel: r.refId })}`}>提现单 #{r.refId}</Link>}
      </span>) },
    { key: 'applicantUserId', title: '申请人', render: r => (
      <Link to={`/users${qs({ sel: r.applicantUserId })}`} className="num"
            onClick={e => e.stopPropagation()}>#{r.applicantUserId}</Link>) },
    { key: 'status', title: '状态', render: r => <St domain="invoice" value={r.status} /> },
    { key: 'invoiceNo', title: '发票号',
      render: r => r.invoiceNo ? <span className="num">{r.invoiceNo}</span>
                               : <span style={{ color: 'var(--ink-3)' }}>—</span> },
    { key: 'createdAt', title: '申请时间', render: r => fmtTime(r.createdAt) },
    { key: 'ops', title: '操作', render: r => r.status === 'APPLIED' ? (
      <div style={{ display: 'flex', gap: 'var(--s2)' }} onClick={e => e.stopPropagation()}>
        <Button size="sm" onClick={() => setDlg({ kind: 'issue', row: r })}>填发票号</Button>
        <Button size="sm" variant="quiet" onClick={() => setDlg({ kind: 'reject', row: r })}>驳回</Button>
      </div>
    ) : r.status === 'REJECTED' ? (
      <span style={{ fontSize: 'var(--t-xs)', color: 'var(--ink-3)' }}>{r.rejectReason || '已驳回'}</span>
    ) : (
      <span style={{ fontSize: 'var(--t-xs)', color: 'var(--ink-3)' }}>{fmtTime(r.issuedAt)}</span>
    ) },
  ];

  return (
    <>
      <PageHead title="发票"
        desc="开票金额在真正开票那一刻会被重新核一遍 —— 申请到开票之间可能已经退过款。" />

      <Filters active={status !== 'APPLIED'} onReset={() => reset(['limit'])}>
        <Ctl label="状态" width={160}>
          <Pick value={status} all={false} onChange={v => set({ status: v })}
                options={options('invoice')} />
        </Ctl>
        <Ctl label="一次取多少条" width={140}>
          <Pick value={String(limit)} all={false} onChange={v => set({ limit: v })}
                options={[{ value: '20', text: '20' }, { value: '50', text: '50' },
                          { value: '100', text: '100（上限）' }]} />
        </Ctl>
      </Filters>

      <Board flush title={`开票申请 · ${rows.length} 条`}
        desc="这个接口按状态取一批、没有分页。条数刚好等于上限时，说明后面还有。">
        <Async state={list}>{() => (
          <Table cols={cols} rows={rows} rowKey={r => r.id}
                 empty={<Blank filtered={status !== 'APPLIED'} thing="开票申请"
                   onClear={() => reset(['limit'])}
                   action={<span style={{ fontSize: 'var(--t-sm)' }}>
                     没有待开的票。代理和学员提交申请之后会出现在这里。
                   </span>} />} />
        )}</Async>
      </Board>

      {dlg && <Dialogs dlg={dlg} close={() => setDlg(null)} onDone={bump} />}
    </>
  );
}

function Dialogs({ dlg, close, onDone }: {
  dlg: { kind: 'issue' | 'reject'; row: any }; close: () => void; onDone: () => void;
}) {
  const [no, setNo] = React.useState('');
  const [reason, setReason] = React.useState('');
  React.useEffect(() => { setNo(''); setReason(''); }, [dlg]);
  const r = dlg.row;

  return (
    <>
      <ActionDialog open={dlg.kind === 'issue'} onClose={close} verb="登记" onDone={onDone}
        title="登记发票号并标记已开" disabled={!no.trim()}
        consequences={<>
          标记之后申请人会看到「已开票」。<br />
          金额会在这一刻<b>按当前的订单/提现状态重新核一遍</b> —— 申请到现在这段时间里如果发生过退款，
          原来的金额就开不出去了，服务端会拒绝。<br />
          开票动作会进审计日志：「这张票是谁开的」事后一定会被问到。
        </>}
        onConfirm={() => api.post(`/api/invoice/admin/${r.id}/issue`, { invoiceNo: no.trim() })}>
        <div style={{ display: 'grid', gap: 'var(--s4)' }}>
          <Note>
            抬头「{r.title}」{r.taxNo ? `，税号 ${r.taxNo}` : '（没填税号）'}，
            金额 <Money fen={r.amountFen} bold />。开之前请照着这三项核一遍。
          </Note>
          <Ctl label="发票号码" width={280} grow hint="开票系统里的那个号，照抄。">
            <TextIn value={no} onChange={e => setNo(e.target.value)} placeholder="必填" />
          </Ctl>
        </div>
      </ActionDialog>

      <ActionDialog open={dlg.kind === 'reject'} onClose={close} verb="驳回" tone="danger" onDone={onDone}
        title="驳回这条开票申请" disabled={!reason.trim()}
        consequences={<>
          驳回理由会<b>原样回给申请人</b>。不写清楚缺什么，同一张错抬头会被反复提交、反复驳回。
        </>}
        onConfirm={() => api.post(`/api/invoice/admin/${r.id}/reject`, { reason: reason.trim() })}>
        <ReasonBox value={reason} onChange={setReason} label="驳回理由"
                   hint="例：抬头与付款主体不一致，请改成「XX 有限公司」后重新提交。" />
      </ActionDialog>
    </>
  );
}
