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
import { fmtTime, num, options } from '../lib/fmt';
import { hasFilter, qs, useQ } from '../lib/q';

export function Refunds() {
  const { sp, get, int, set, reset } = useQ();
  const status = get('status'), orderId = get('orderId');
  const page = int('page', 1), size = int('size', 20);
  const sel = get('sel');
  const [rev, bump] = React.useReducer((n: number) => n + 1, 0);

  const list = useAsync<any>(
    () => api.get(`/api/admin/refunds${qs({ status, orderId, page, size })}`),
    [status, orderId, page, size, rev]);

  const filtered = hasFilter(sp);

  const cols: Col<any>[] = [
    { key: 'refundNo', title: '退款单号',
      render: r => <span className="num" style={{ fontSize: 'var(--t-xs)' }}>{r.refundNo}</span> },
    { key: 'orderNo', title: '订单号',
      render: r => <Link to={`/orders${qs({ orderNo: r.orderNo, sel: r.orderId })}`}
                         onClick={e => e.stopPropagation()}
                         className="num" style={{ fontSize: 'var(--t-xs)' }}>{r.orderNo}</Link> },
    { key: 'amountFen', title: '退款额', numeric: true, render: r => <Money fen={r.amountFen} bold /> },
    { key: 'scope', title: '范围', render: r => <St domain="refundScope" value={r.scope} /> },
    { key: 'status', title: '状态', render: r => <St domain="refund" value={r.status} /> },
    { key: 'reason', title: '原因', render: r => (
      <span style={{ display: 'inline-block', maxWidth: 220, color: 'var(--ink-2)' }}>
        {r.reason || '—'}
      </span>) },
    { key: 'appliedBy', title: '申请人', render: r => r.appliedBy ? `#${r.appliedBy}` : '—' },
    { key: 'approvedBy', title: '审批人', render: r => r.approvedBy ? `#${r.approvedBy}` : '—' },
    { key: 'createdAt', title: '申请时间', render: r => fmtTime(r.createdAt) },
  ];

  return (
    <>
      <PageHead title="退款审批"
        desc="退款要走「审批通过 → 标记打款成功」两步，钱才真的动。" />

      <div style={{ marginBottom: 'var(--s4)' }}>
        <Note tone="danger" title="激活即不退，是产品红线，不是这里的开关">
          只要一张订单里有<b>任何一个激活码已被学员激活</b>，整单退款（范围 = 整单）就会被数据库
          直接拒绝，后台没有放行的权限。这种单子能做的只有「只退未激活的码」。<br />
          发起退款的入口在<Link to="/orders">订单详情</Link>里 —— 在那里能看到这一单已激活多少个、
          还剩多少个可退，这两个数才是决定退多少的依据。
        </Note>
      </div>

      <Filters active={filtered} onReset={() => reset()}>
        <Ctl label="状态">
          <Pick value={status} onChange={v => set({ status: v })} options={options('refund')} />
        </Ctl>
        <Ctl label="订单 ID" width={140}>
          <TextIn defaultValue={orderId} inputMode="numeric" placeholder="回车搜索"
                  onKeyDown={e => { if (e.key === 'Enter') set({ orderId: e.currentTarget.value.trim() }); }} />
        </Ctl>
      </Filters>

      <Board flush title={`退款单${list.data ? ` · ${num(list.data.total)} 张` : ''}`}>
        <Async state={list}>{(d: any) => (
          <>
            <Table cols={cols} rows={d.items} rowKey={r => r.id}
                   onRowClick={r => set({ sel: r.id }, true)}
                   empty={<Blank filtered={filtered} thing="退款单" onClear={() => reset()}
                     action={<Link to="/orders"><Button size="sm" variant="ghost">去订单里发起退款</Button></Link>} />} />
            <Pager total={num(d.total)} page={page} size={size}
                   onPage={p => set({ page: p }, true)} onSize={s => set({ size: s })} />
          </>
        )}</Async>
      </Board>

      {sel && list.data && (
        <RefundDetail row={(list.data.items ?? []).find((x: any) => String(x.id) === sel)}
                      id={sel} onClose={() => set({ sel: '' }, true)} onChanged={bump} />
      )}
    </>
  );
}

function RefundDetail({ row, id, onClose, onChanged }: {
  row: any; id: string; onClose: () => void; onChanged: () => void;
}) {
  const [rev, bump] = React.useReducer((n: number) => n + 1, 0);
  const [dlg, setDlg] = React.useState<null | 'approve' | 'reject' | 'settle' | 'fail'>(null);
  const order = useAsync<any>(
    () => row?.orderId ? api.get(`/api/admin/orders/${row.orderId}`) : Promise.resolve(null),
    [row?.orderId, rev]);

  const after = () => { bump(); onChanged(); };

  if (!row) return (
    <DetailModal open onClose={onClose} title={`退款单 #${id}`}>
      <Note tone="warn">
        这张退款单不在当前列表里 —— 多半是筛选条件变了或它已经翻到了别的页。
        把状态筛选清空再点一次。
      </Note>
    </DetailModal>
  );

  const o = order.data?.order;
  const bound = num((o?.codes ?? []).find((c: any) => c.status === 'BOUND')?.n, 0);

  return (
    <DetailModal open onClose={onClose}
      title={<span className="num">{row.refundNo}</span>}
      subtitle={<>退款 {yuan(num(row.amountFen))} · <St domain="refund" value={row.status} /></>}
      actions={<>
        {row.status === 'APPLIED' && <>
          <Button variant="ghost" onClick={() => setDlg('reject')}>驳回</Button>
          <Button onClick={() => setDlg('approve')}>通过审批</Button>
        </>}
        {(row.status === 'APPROVED' || row.status === 'FAILED') && <>
          <Button variant="ghost" onClick={() => setDlg('fail')}
                  disabled={row.status === 'FAILED'}>标记打款失败</Button>
          <Button onClick={() => setDlg('settle')}>标记已退款到账</Button>
        </>}
      </>}>
      <KV items={[
        ['退款单号', <span className="num">{row.refundNo}</span>],
        ['状态', <St domain="refund" value={row.status} />],
        ['退款范围', <St domain="refundScope" value={row.scope} />],
        ['退款金额', <Money fen={row.amountFen} bold />],
        ['关联订单', <Link to={`/orders${qs({ orderNo: row.orderNo, sel: row.orderId })}`}
                          className="num">{row.orderNo}</Link>],
        ['订单状态', <St domain="order" value={row.orderStatus} />],
        ['订单实付', <Money fen={row.paidAmountFen} />],
        ['订单已退', <Money fen={row.refundedAmountFen} />],
        ['申请人 / 时间', <>#{row.appliedBy} · {fmtTime(row.createdAt)}</>],
        ['审批人', row.approvedBy ? `#${row.approvedBy}` : null],
        ['渠道退款单号', row.providerRefundId ? <span className="num">{row.providerRefundId}</span> : null],
        ['完成时间', fmtTime(row.finishedAt)],
      ]} />

      <Sect title="退款原因">
        <div style={{ fontSize: 'var(--t-sm)', background: 'var(--surface-2)',
                      padding: 'var(--s3)', borderRadius: 'var(--r-sm)' }}>
          {row.reason || '（申请时没有填原因）'}
        </div>
      </Sect>

      <Sect title="这一单的码">
        {order.loading ? <span style={{ fontSize: 'var(--t-sm)', color: 'var(--ink-3)' }}>读取订单…</span>
         : order.error ? <Note tone="warn">订单读取失败，看不到激活情况：{order.error}</Note>
         : (
          <>
            <div style={{ display: 'flex', gap: 'var(--s2)', flexWrap: 'wrap' }}>
              {(o?.codes ?? []).length === 0
                ? <span style={{ fontSize: 'var(--t-sm)', color: 'var(--ink-3)' }}>这一单还没有发码</span>
                : o.codes.map((c: any) => (
                  <span key={c.status} style={{
                    padding: 'var(--s2) var(--s3)', background: 'var(--surface-2)',
                    borderRadius: 'var(--r-md)', fontSize: 'var(--t-sm)',
                  }}>
                    <St domain="code" value={c.status} />
                    <b className="num" style={{ marginLeft: 'var(--s2)' }}>{num(c.n)}</b> 个
                  </span>
                ))}
            </div>
            {bound > 0 && row.scope === 'FULL' && (
              <div style={{ marginTop: 'var(--s3) ' }}>
                <Note tone="danger" title="这张单批不过">
                  它的范围是「整单退款」，而这一单已经有 {bound} 个码被激活。
                  按「激活即不退」，数据库会拒绝 —— 请驳回它，让申请人改成「只退未激活的码」重提。
                </Note>
              </div>
            )}
          </>
        )}
      </Sect>

      <Sect title="改动记录">
        <AuditTrail targetType="refund_record" targetId={id} refreshKey={rev} />
      </Sect>

      <Dialogs which={dlg} close={() => setDlg(null)} row={row} onDone={after} />
    </DetailModal>
  );
}

function Dialogs({ which, close, row, onDone }: {
  which: string | null; close: () => void; row: any; onDone: () => void;
}) {
  const [reason, setReason] = React.useState('');
  const [txn, setTxn] = React.useState('');
  React.useEffect(() => { setReason(''); setTxn(''); }, [which]);

  const id = row.id;

  return (
    <>
      <ActionDialog open={which === 'approve'} onClose={close} verb="通过" onDone={onDone}
        title="通过这张退款单"
        consequences={<>
          通过之后<b>钱还没有退出去</b> —— 还要在实际打款完成后回来「标记已退款到账」，
          那一步才会把退款金额记到订单上、撤销对应的未激活码，并按累计退款比例冲正佣金。<br />
          通过之后<b>不能再驳回</b>（只能标记打款失败后重试），所以请先核对金额与范围。
        </>}
        onConfirm={() => api.post(`/api/admin/refunds/${id}/approve`)} />

      <ActionDialog open={which === 'reject'} onClose={close} verb="驳回" tone="danger" onDone={onDone}
        title="驳回这张退款单"
        disabled={!reason.trim()}
        consequences={<>
          驳回是<b>终态</b>，这张单不能再被审批。申请人要退，得重新提一张。<br />
          理由会进审计日志，也是申请人唯一能看到的解释 —— 不写清楚，同一张错单会被反复提交。
        </>}
        onConfirm={() => api.post(`/api/admin/refunds/${id}/reject`, { reason: reason.trim() })}>
        <ReasonBox value={reason} onChange={setReason} label="驳回理由" />
      </ActionDialog>

      <ActionDialog open={which === 'settle'} onClose={close} verb="登记" onDone={onDone}
        title="标记这笔退款已经到账"
        disabled={!txn.trim()}
        consequences={<>
          这一步才<b>真正把钱记到订单上</b>：订单的已退金额增加，退完则整单转为「已全额退款」。<br />
          系统会按<b>累计退款占实付的比例</b>自动冲正佣金。如果佣金已进可提余额，会同时写冲正台账并扣余额；
          如果钱已被提走，余额可能变为负数，后续佣金会自动抵扣。<br />
          对应数量的<b>未激活码会在同一笔事务中撤销</b>；码数不足时整个操作会失败，不会出现钱退了但课还能开的半成状态。<br />
          渠道退款单号上有唯一键：同一笔渠道退款不可能被记两次。
        </>}
        onConfirm={() => api.post(`/api/admin/refunds/${id}/settle`, { providerRefundId: txn.trim() })}>
        <Ctl label="渠道退款单号" width={320} grow
             hint="微信退款单号 / 银行回单流水号。没有它，这笔退款事后无法与渠道对账。">
          <TextIn value={txn} onChange={e => setTxn(e.target.value)} placeholder="必填" />
        </Ctl>
      </ActionDialog>

      <ActionDialog open={which === 'fail'} onClose={close} verb="标记失败" tone="danger" onDone={onDone}
        title="标记这笔退款打款失败"
        disabled={!reason.trim()}
        consequences={<>
          标记失败之后这张单还能救：修正信息后可以再次「标记已退款到账」。
          订单上的金额<b>不会</b>被改动 —— 钱没出去，账上就不该有这笔。
        </>}
        onConfirm={() => api.post(`/api/admin/refunds/${id}/fail`, { reason: reason.trim() })}>
        <ReasonBox value={reason} onChange={setReason} label="失败原因"
                   hint="比如「对方账户已注销」。写清楚才知道下次该改什么。" />
      </ActionDialog>
    </>
  );
}
