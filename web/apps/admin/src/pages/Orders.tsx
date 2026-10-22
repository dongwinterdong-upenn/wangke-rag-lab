import React from 'react';
import { Link } from 'react-router-dom';
import { api, yuan } from '@wangke/shared/api';
import { PageHead } from '@wangke/shared/shell';
import { Async, Button, Copyable, Table, useAsync, useToast } from '@wangke/shared/ui';
import type { Col } from '@wangke/shared/ui';
import {
  ActionDialog, AuditTrail, Blank, Board, Ctl, DetailModal, Filters, KV, Money, Note, Pager, Pick,
  ReasonBox, Sect, St, TextIn,
} from '../lib/kit';
import {
  downloadCsv, fenFromYuan, fmtDate, fmtTime, label, num, options, stamp, yuanInput,
} from '../lib/fmt';
import { hasFilter, qs, useQ } from '../lib/q';

type Order = Record<string, any>;

export function Orders() {
  const { sp, get, int, set, reset } = useQ();
  const status = get('status'), orderNo = get('orderNo'), payChannel = get('payChannel');
  const buyerUserId = get('buyerUserId'), agentId = get('agentId');
  const from = get('from'), to = get('to');
  // origin 与 grantKey 都从 URL 读：超管发完码就是被跳到 /orders?grantKey=…，
  // 那一屏必须能被刷新、能被转发给同事 —— 它是「这批码到底发出去没有」的唯一落点。
  const origin = get('origin'), grantKey = get('grantKey');
  const page = int('page', 1), size = int('size', 20);
  const sel = get('sel');
  const [rev, bump] = React.useReducer((n: number) => n + 1, 0);

  const list = useAsync<any>(
    () => api.get(`/api/admin/orders${qs({ status, origin, grantKey, orderNo, payChannel, buyerUserId, agentId, from, to, page, size })}`),
    [status, origin, grantKey, orderNo, payChannel, buyerUserId, agentId, from, to, page, size, rev]);

  const filtered = hasFilter(sp);

  const cols: Col<Order>[] = [
    { key: 'orderNo', title: '订单号', render: r => (
      <div style={{ minWidth: 150 }}>
        <span className="num" style={{ fontSize: 'var(--t-xs)' }}>{r.orderNo}</span>
        {/* 两种来源都印出来，但只有白送单给标签：一整列同样的中性标签等于没标，
            而运营要一眼挑出来的恰恰是不收钱的那几张。 */}
        <div style={{ marginTop: 'var(--s1)' }}>
          {r.origin === 'ADMIN_GIFT'
            ? <St domain="orderOrigin" value={r.origin} />
            : <span style={{ color: 'var(--ink-3)', fontSize: 'var(--t-xs)' }}>
                {label('orderOrigin', r.origin)}
              </span>}
        </div>
      </div>) },
    { key: 'buyer', title: '买家', render: r => (
      <div style={{ minWidth: 120 }}>
        <div>{r.buyerType === 'AGENT' ? `代理 #${r.buyerAgentId ?? '—'}` : `学员 #${r.buyerUserId}`}</div>
        <div style={{ color: 'var(--ink-3)', fontSize: 'var(--t-xs)' }}>
          {r.buyerType === 'AGENT'
            ? label('level', String(r.buyerAgentLevel))
            : r.attributedAgentId ? `归属代理 #${r.attributedAgentId}` : '无归属代理'}
        </div>
      </div>
    ) },
    { key: 'skuCode', title: '套餐', render: r => (
      <div><div className="num">{r.skuCode}</div>
        <div style={{ color: 'var(--ink-3)', fontSize: 'var(--t-xs)' }}>{r.codeQty} 个码</div></div>) },
    { key: 'amountFen', title: '应付', numeric: true, render: r => <Money fen={r.amountFen} /> },
    { key: 'paidAmountFen', title: '实付', numeric: true,
      render: r => <Money fen={r.paidAmountFen} bold tone={num(r.paidAmountFen) > 0 ? 'ok' : undefined} /> },
    { key: 'refundedAmountFen', title: '已退', numeric: true,
      render: r => num(r.refundedAmountFen) > 0
        ? <Money fen={r.refundedAmountFen} tone="danger" />
        : <span style={{ color: 'var(--ink-4)' }}>—</span> },
    { key: 'status', title: '状态', render: r => <St domain="order" value={r.status} /> },
    { key: 'payChannel', title: '支付方式', render: r => <St domain="channel" value={r.payChannel} /> },
    { key: 'createdAt', title: '下单时间', render: r => fmtTime(r.createdAt) },
  ];

  return (
    <>
      <PageHead title="订单"
        desc="查单、对公转账到账确认、发起退款。金额一律以实付为准，未付款的单不计入成交。" />

      <Filters active={filtered} onReset={() => reset()}>
        <Ctl label="订单号" width={200}>
          <TextIn defaultValue={orderNo} placeholder="完整单号，回车搜索"
                  onKeyDown={e => { if (e.key === 'Enter') set({ orderNo: e.currentTarget.value.trim() }); }}
                  onBlur={e => { if (e.currentTarget.value.trim() !== orderNo) set({ orderNo: e.currentTarget.value.trim() }); }} />
        </Ctl>
        <Ctl label="状态">
          <Pick value={status} onChange={v => set({ status: v })} options={options('order')} />
        </Ctl>
        {/* 只列真的会被写进来的渠道。微信三项已经没有任何后端写入路径了，
            留在下拉里等于给运营三个永远筛出 0 条的选项 —— 而「0 条」读起来是
            「这个渠道一单都没有」，不是「这个选项已经废了」。
            宽度从 140 提到 160：「支付宝电脑网站」比原来最长的「微信内支付」多两个字。 */}
        <Ctl label="支付方式" width={160}>
          <Pick value={payChannel} onChange={v => set({ payChannel: v })}
                options={[{ value: 'ALIPAY_PAGE', text: '支付宝电脑网站' },
                          { value: 'ALIPAY_WAP', text: '支付宝手机网站' },
                          { value: 'BANK_TRANSFER', text: '对公转账' }]} />
        </Ctl>
        <Ctl label="来源" width={130} hint="平台发放的单不收钱，也不进成交额">
          <Pick value={origin} onChange={v => set({ origin: v })} options={options('orderOrigin')} />
        </Ctl>
        <Ctl label="买家账号 ID" width={120}>
          <TextIn defaultValue={buyerUserId} inputMode="numeric" placeholder="回车"
                  onKeyDown={e => { if (e.key === 'Enter') set({ buyerUserId: e.currentTarget.value.trim() }); }} />
        </Ctl>
        <Ctl label="代理 ID" width={120} hint="他进的货 + 归属他的直购">
          <TextIn defaultValue={agentId} inputMode="numeric" placeholder="回车"
                  onKeyDown={e => { if (e.key === 'Enter') set({ agentId: e.currentTarget.value.trim() }); }} />
        </Ctl>
        <Ctl label="下单时间从" width={150}>
          <TextIn type="date" value={from} onChange={e => set({ from: e.target.value })} />
        </Ctl>
        <Ctl label="到" width={150}>
          <TextIn type="date" value={to} onChange={e => set({ to: e.target.value })} />
        </Ctl>
      </Filters>

      {grantKey && <GrantBanner grantKey={grantKey}
                                total={list.data ? num(list.data.total) : null}
                                onClear={() => set({ grantKey: '' })} />}

      <Board flush
        title="订单列表"
        desc={list.data
          ? <>当前筛选下共 <b className="num">{num(list.data.total)}</b> 单 ·
              应付合计 <Money fen={list.data.sumAmountFen} />
              {/* 白送单的应付也在 sumAmountFen 里（后端有意不给它单独减掉，否则就是
                  「列表按一套、汇总按另一套」）。所以这一句必须紧挨着应付合计：
                  少了它，运营会把应付合计当成「这批单该收多少钱」去对账，
                  数字全是真的，只是含义不是他以为的那个。 */}
              <span style={{ color: 'var(--ink-3)' }}> 其中平台发放（不收钱）
                <Money fen={list.data.sumGiftAmountFen} /></span> ·
              实付合计 <Money fen={list.data.sumPaidFen} /> ·
              已退合计 <Money fen={list.data.sumRefundedFen} />
              <span style={{ color: 'var(--ink-3)' }}>（合计跟着筛选条件走，不是全库）</span></>
          : undefined}>
        <Async state={list}>{(d: any) => (
          <>
            <Table cols={cols} rows={d.items} rowKey={r => r.id}
                   onRowClick={r => set({ sel: r.id }, true)}
                   empty={<Blank filtered={filtered} thing="订单" onClear={() => reset()} />} />
            <Pager total={num(d.total)} page={page} size={size}
                   onPage={p => set({ page: p }, true)} onSize={s => set({ size: s })} />
          </>
        )}</Async>
      </Board>

      {sel && <OrderDetail id={sel} onClose={() => set({ sel: '' }, true)} onChanged={bump} />}
    </>
  );
}

/* ------------------------------------------------------------------ 详情 */

export function OrderDetail({ id, onClose, onChanged }: {
  id: string; onClose: () => void; onChanged: () => void;
}) {
  const [rev, bump] = React.useReducer((n: number) => n + 1, 0);
  const state = useAsync<any>(() => api.get(`/api/admin/orders/${id}`), [id, rev]);
  const [dlg, setDlg] = React.useState<null | 'confirm' | 'refund' | 'export' | 'revoke'>(null);
  // 作废的结果留在页面上，不留在对话框里：ActionDialog 成功后必关框，
  // 而「其中 N 张已经被激活、作废不了」正是运营唯一要看的那句 —— 一闪而过等于没说。
  const [revoked, setRevoked] = React.useState<any>(null);
  // 换一张单时必须清掉：切换订单时这个弹层是同一个组件实例（id 变、实例不变），
  // 不清的话上一单的「已作废 N 张」会原样挂在下一单的页面上 —— 不报错、不闪，
  // 只是那句话在这张单上是假的。
  React.useEffect(() => { setRevoked(null); }, [id]);
  const o = state.data?.order;
  const gift = o?.origin === 'ADMIN_GIFT';
  const after = () => { bump(); onChanged(); };

  const codeCount = (s: string) =>
    num((o?.codes ?? []).find((c: any) => c.status === s)?.n, 0);
  const bound = codeCount('BOUND'), issued = codeCount('ISSUED');

  return (
    <DetailModal open onClose={onClose}
      title={o ? <span className="num">{o.orderNo}</span> : `订单 #${id}`}
      subtitle={o ? <>{label('order', o.status)} · 下单于 {fmtTime(o.createdAt)}</> : undefined}
      actions={o && (
        <>
          {/* 白送单一颗收钱的按钮都不画 —— 点了会被后端 409，而那个红框读起来像系统故障。
              · 到账确认：AdminOrderController 对非 SELF_SERVE 直接 409（库层 trg_payment_precheck
                也挡）。今天白送单一建出来就是 FULFILLED，这个分支恒不成立，
                但状态机是会被改的，判据要钉在 origin 上而不是靠状态顺带挡住。
              · 发起退款：白送单 paid_amount_fen=0，trg_refund_precheck 那句
                「订单没有实付金额，不存在退款」连 refund_record 都不让插。
                这种单要撤的是码不是钱，路在下面那颗「整单作废未激活的码」。 */}
          {o.status === 'CREATED' && !gift && (
            <Button onClick={() => setDlg('confirm')}>确认对公转账到账</Button>
          )}
          {['PAID', 'FULFILLED', 'PARTIAL_REFUNDED'].includes(o.status) && !gift && (
            <Button variant="ghost" onClick={() => setDlg('refund')}>发起退款</Button>
          )}
          {/* 白送单上不画这颗：它一分钱都没收过，refund_record 连插都插不进去
              （trg_refund_precheck 要求 paid_amount_fen > 0），点过去必然是一张空表。
              而上面那条 Note 刚说完「退款那条路对这一单整条不存在」——
              紧接着摆一颗「看这单的退款记录」，看的人会以为那条路还在，
              只是这一单碰巧没有记录。 */}
          {!gift && (
            <Link to={`/refunds${qs({ orderId: id })}`}>
              <Button variant="quiet">看这单的退款记录</Button>
            </Link>
          )}
        </>
      )}>
      <Async state={state}>{(d: any) => {
        const g = d.order;
        return (
          <div>
            {g.origin === 'ADMIN_GIFT' && (
              <div style={{ marginBottom: 'var(--s4)' }}>
                <Note tone="warn" title="这是平台手工发放的单，一分钱都没有收到过">
                  应付记的是目录价（它是「送出去多少钱的货」这个口径的唯一来源），
                  <b>实付恒 0、没有到账时间</b>。这张单不计入成交额、不结佣金、开不出发票。<br />
                  发放原因：<b>{g.grantReason || '（这一单没有留下原因，按库层约束不该出现）'}</b><br />
                  操作人：{g.grantBy ? <span className="num">#{g.grantBy}</span> : '—'}
                  {g.grantKey && <> · 这一批的幂等键 <span className="num">{g.grantKey}</span></>}
                </Note>
              </div>
            )}

            <KV items={[
              ['订单号', <span className="num">{g.orderNo}</span>],
              ['状态', <St domain="order" value={g.status} />],
              ['来源', <St domain="orderOrigin" value={g.origin} />],
              ['买家', g.buyerType === 'AGENT'
                ? <>代理 #{g.buyerAgentId}（{label('level', String(g.buyerAgentLevel))}）</>
                : <>学员账号 #{g.buyerUserId}</>],
              ['归属代理', g.attributedAgentId ? `#${g.attributedAgentId}` : '无'],
              ['套餐', <span className="num">{g.skuCode}</span>],
              ['本单码数', <span className="num">{g.codeQty} 个</span>],
              ['应付', <Money fen={g.amountFen} bold />],
              ['实付', <Money fen={g.paidAmountFen} bold tone={num(g.paidAmountFen) > 0 ? 'ok' : undefined} />],
              ['已退', <Money fen={g.refundedAmountFen} tone={num(g.refundedAmountFen) > 0 ? 'danger' : undefined} />],
              ['支付方式', <St domain="channel" value={g.payChannel} />],
              ['付款时间', fmtTime(g.paidAt)],
              ['发码时间', fmtTime(g.fulfilledAt)],
              ['未付超时', fmtTime(g.expireAt)],
              ['下单 IP', g.clientIp ? <span className="num">{g.clientIp}</span> : null],
              // 白送单才有的三样。自助单上它们恒空，塞进去只会让每一单多三行「—」
              ...(g.origin === 'ADMIN_GIFT'
                ? [['发放原因', g.grantReason],
                   ['发放人', g.grantBy ? <span className="num">#{g.grantBy}</span> : null],
                   ['本批幂等键', g.grantKey ? <span className="num">{g.grantKey}</span> : null]]
                : []) as [React.ReactNode, React.ReactNode][],
            ]} />

            <Sect title="激活码" extra={(g.codes ?? []).length > 0 && (
              <div style={{ display: 'flex', gap: 'var(--s2)', flexWrap: 'wrap' }}>
                <Button size="sm" variant="quiet" onClick={() => setDlg('export')}>
                  导出全部明文 CSV
                </Button>
                {/* 作废只对白送单开，而且只在还有未激活的码时才画：
                    · 收过钱的单点了必定 409（作废码而不退钱，对账表是平的，
                      只有代理会来问「我的码怎么没了」），那条路是退款。
                    · 一张未激活的都没有时，这颗按钮唯一能做的事就是告诉他做不了。 */}
                {g.origin === 'ADMIN_GIFT' && issued > 0 && (
                  <Button size="sm" variant="danger" onClick={() => setDlg('revoke')}>
                    整单作废未激活的码
                  </Button>
                )}
              </div>
            )}>
              {(g.codes ?? []).length === 0 ? (
                <div style={{ fontSize: 'var(--t-sm)', color: 'var(--ink-3)' }}>
                  这一单还没有发码。付款确认之后系统会按订单快照一次性发出 {g.codeQty} 个。
                </div>
              ) : (
                <div style={{ display: 'flex', gap: 'var(--s2)', flexWrap: 'wrap' }}>
                  {g.codes.map((c: any) => (
                    <div key={c.status} style={{
                      padding: 'var(--s2) var(--s3)', borderRadius: 'var(--r-md)',
                      background: 'var(--surface-2)', fontSize: 'var(--t-sm)',
                    }}>
                      <St domain="code" value={c.status} />
                      <span className="num" style={{ marginLeft: 'var(--s2)', fontWeight: 600 }}>{num(c.n)}</span> 个
                    </div>
                  ))}
                </div>
              )}
              {(g.codes ?? []).length > 0 && (
                <div style={{ marginTop: 'var(--s4)' }}>
                  {/* refreshKey 不能省：作废之后上面的状态计数会跟着详情一起刷新，
                      而这份逐条列表是自己拉的 —— 不给它一个信号，同一屏上就会出现
                      「已作废 N 张」和一列写着「未激活」的码并排放着，两个都不报错。 */}
                  <CodeList orderId={g.id ?? g.orderId} qty={g.codeQty} refreshKey={rev} />
                </div>
              )}
              {/* 作废结果画在这里而不是对话框里：框关了那句「其中 N 张作废不了」就没了，
                  而它正是运营接着要去做别的事的依据。 */}
              {revoked && (
                <div style={{ marginTop: 'var(--s3)' }}>
                  <Note tone={(revoked.boundCodes ?? []).length > 0 ? 'warn' : 'ok'}
                        title={`已作废 ${num(revoked.revoked)} 张未激活的码`}>
                    {(revoked.boundCodes ?? []).length > 0 ? (
                      <>
                        其中 <b>{revoked.boundCodes.length}</b> 张已被学员激活、<b>作废不了</b>，
                        它们仍然可以正常观看：
                        <div className="num" style={{ marginTop: 'var(--s1)' }}>
                          {revoked.boundCodes.map((c: any) => c.masked).join('、')}
                        </div>
                      </>
                    ) : '这一单的码已经全部失效，代理手上的那几张现在激活不了了。'}
                  </Note>
                </div>
              )}
              {bound > 0 && g.origin === 'ADMIN_GIFT' && (
                <div style={{ marginTop: 'var(--s3)' }}>
                  <Note tone="warn" title="已经激活的码撤不回来">
                    这一单有 <b>{bound}</b> 个码被学员激活。学员的归属代理在激活那一刻就永久锁定了，
                    作废与退款都改不回来。<b>退款那条路对这一单整条不存在</b>（它一分钱都没收过），
                    还能做的只有把剩下 <b>{issued}</b> 个未激活的作废掉。
                  </Note>
                </div>
              )}
              {bound > 0 && g.origin !== 'ADMIN_GIFT' && (
                <div style={{ marginTop: 'var(--s3)' }}>
                  <Note tone="danger" title="这一单不能整单退款">
                    已经有 <b>{bound}</b> 个码被学员激活。按「激活即不退」，整单退款会被数据库直接拒绝 ——
                    这是业务铁律，后台放行不了。
                    {issued > 0 ? <>还剩 <b>{issued}</b> 个未激活，可以只退这部分。</>
                                : '本单已无未激活的码，没有可退的部分。'}
                  </Note>
                </div>
              )}
            </Sect>

            <Sect title="收款流水">
              {(g.payments ?? []).length === 0
                ? <span style={{ fontSize: 'var(--t-sm)', color: 'var(--ink-3)' }}>还没有收到钱</span>
                : <MiniTable rows={g.payments} cols={[
                    ['渠道', (r: any) => <St domain="channel" value={r.provider} />],
                    ['流水号', (r: any) => <span className="num">{r.provider_txn_id ?? r.providerTxnId}</span>],
                    ['金额', (r: any) => <Money fen={r.amount_fen ?? r.amountFen} />],
                    ['状态', (r: any) => r.status === 'SUCCESS' ? '成功' : '失败'],
                    ['确认人', (r: any) => (r.confirmed_by ?? r.confirmedBy) ? `#${r.confirmed_by ?? r.confirmedBy}` : '系统'],
                    ['到账时间', (r: any) => fmtTime(r.paid_at ?? r.paidAt)],
                  ]} />}
            </Sect>

            <Sect title="退款记录">
              {(g.refunds ?? []).length === 0
                ? <span style={{ fontSize: 'var(--t-sm)', color: 'var(--ink-3)' }}>没有退款</span>
                : <MiniTable rows={g.refunds} cols={[
                    ['退款单号', (r: any) => <span className="num">{r.refund_no ?? r.refundNo}</span>],
                    ['金额', (r: any) => <Money fen={r.amount_fen ?? r.amountFen} />],
                    ['范围', (r: any) => <St domain="refundScope" value={r.scope} />],
                    ['状态', (r: any) => <St domain="refund" value={r.status} />],
                    ['完成时间', (r: any) => fmtTime(r.finished_at ?? r.finishedAt)],
                  ]} />}
            </Sect>

            <Sect title="产生的佣金">
              {(g.commissions ?? []).length === 0
                ? <span style={{ fontSize: 'var(--t-sm)', color: 'var(--ink-3)' }}>这一单没有产生返佣</span>
                : <MiniTable rows={g.commissions} cols={[
                    ['受益代理', (r: any) => `#${r.beneficiary_agent_id ?? r.beneficiaryAgentId}`],
                    ['金额', (r: any) => <Money fen={r.amount_fen ?? r.amountFen} />],
                    ['状态', (r: any) => <St domain="commission" value={r.status} />],
                    ['冻结到', (r: any) => fmtTime(r.freeze_until ?? r.freezeUntil)],
                  ]} />}
            </Sect>

            <Sect title="改动记录">
              <AuditTrail targetType="biz_order" targetId={g.id} refreshKey={rev} />
            </Sect>
          </div>
        );
      }}</Async>

      {o && <ConfirmTransfer open={dlg === 'confirm'} order={o}
                             onClose={() => setDlg(null)} onDone={after} />}
      {o && <ApplyRefund open={dlg === 'refund'} order={o} bound={bound} issued={issued}
                         onClose={() => setDlg(null)} onDone={after} />}
      {o && <ExportCodes open={dlg === 'export'} order={o} onClose={() => setDlg(null)} />}
      {o && <RevokeGiftCodes open={dlg === 'revoke'} order={o} issued={issued} bound={bound}
                             onClose={() => setDlg(null)}
                             onDone={(r: any) => { setRevoked(r); after(); }} />}
    </DetailModal>
  );
}

export function MiniTable({ rows, cols }: {
  rows: any[]; cols: [string, (r: any) => React.ReactNode][];
}) {
  return (
    <div style={{ overflowX: 'auto' }}>
      <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 'var(--t-sm)' }}>
        <thead><tr>{cols.map(([h]) => (
          <th key={h} style={{
            textAlign: 'left', padding: 'var(--s1) var(--s3)', whiteSpace: 'nowrap',
            background: 'var(--surface-2)', color: 'var(--ink-2)', fontWeight: 500,
          }}>{h}</th>
        ))}</tr></thead>
        <tbody>{rows.map((r, i) => (
          <tr key={r.id ?? i}>{cols.map(([h, render]) => (
            <td key={h} style={{ padding: 'var(--s1) var(--s3)', borderBottom: '1px solid var(--line)' }}>
              {render(r)}
            </td>
          ))}</tr>
        ))}</tbody>
      </table>
    </div>
  );
}

/* ------------------------------------------------------------------ 到账确认 */

function ConfirmTransfer({ open, order, onClose, onDone }: {
  open: boolean; order: any; onClose: () => void; onDone: () => void;
}) {
  const due = num(order.amountFen);
  const [txn, setTxn] = React.useState('');
  const [amt, setAmt] = React.useState(yuanInput(due));
  const [paidAt, setPaidAt] = React.useState('');
  const [remark, setRemark] = React.useState('');
  const [amtErr, setAmtErr] = React.useState<string | null>(null);

  React.useEffect(() => {
    if (open) { setTxn(''); setAmt(yuanInput(due)); setPaidAt(''); setRemark(''); setAmtErr(null); }
  }, [open, due]);

  let fen: number | null = null;
  try { fen = amt.trim() ? fenFromYuan(amt) : null; } catch { fen = null; }
  const mismatch = fen !== null && fen !== due;

  return (
    <ActionDialog open={open} onClose={onClose} verb="确认到账" onDone={onDone} width={560}
      title="确认这笔对公转账已经到账"
      disabled={!txn.trim() || fen === null}
      consequences={<>
        确认之后系统会<b>立刻按订单快照发出 {order.codeQty} 个激活码</b>，并结算该结的上级佣金。
        发出去的码收不回来。<br />
        流水号是<b>去重依据</b>：同一个流水号重复确认不会重复发码，所以不确定是否点过就再点一次，
        比在库里查更安全。<br />
        <b>金额必须与应付一分不差</b>（应付 {yuan(due)}）。不一致的一律不入账 ——
        少收的钱不会有任何日志，改价和串单都长成「差一点」这个样子。
      </>}
      onConfirm={() => api.post(`/api/admin/orders/${order.id}/confirm-transfer`, {
        txnNo: txn.trim(),
        amountFen: fenFromYuan(amt),
        paidAt: paidAt || undefined,
        remark: remark.trim() || undefined,
      })}>
      <div style={{ display: 'grid', gap: 'var(--s4)' }}>
        <Ctl label="银行回单流水号" width={320} grow
             hint="照着银行回单抄。它是这笔到账的唯一凭据，也是重复确认时的去重依据。">
          <TextIn value={txn} onChange={e => setTxn(e.target.value)} placeholder="必填" />
        </Ctl>
        <Ctl label="到账金额（元）" width={200}
             hint={amtErr ?? `本单应付 ${yuan(due)}${fen !== null ? ` · 将提交 ${fen} 分` : ''}`}>
          <TextIn value={amt} inputMode="decimal"
                  onChange={e => {
                    setAmt(e.target.value);
                    try { e.target.value.trim() && fenFromYuan(e.target.value); setAmtErr(null); }
                    catch (err) { setAmtErr((err as Error).message); }
                  }} />
        </Ctl>
        {mismatch && (
          <Note tone="danger" title="金额和应付对不上">
            填的是 {yuan(fen!)}，本单应付 {yuan(due)}。这样提交会被服务端拒绝、不会入账。
            请核对银行回单；确实需要按不同金额入账的，走人工订正流程。
          </Note>
        )}
        <Ctl label="到账时间" width={220} hint="按回单上的时间填。留空按现在算。不能填未来时间。">
          <TextIn type="datetime-local" value={paidAt} onChange={e => setPaidAt(e.target.value)} />
        </Ctl>
        <Ctl label="备注" width={320} grow hint="会进审计日志。比如「客户 XX 8/11 电汇」。">
          <TextIn value={remark} onChange={e => setRemark(e.target.value)} />
        </Ctl>
      </div>
    </ActionDialog>
  );
}

/* ------------------------------------------------------------------ 发起退款 */

function ApplyRefund({ open, order, bound, issued, onClose, onDone }: {
  open: boolean; order: any; bound: number; issued: number;
  onClose: () => void; onDone: () => void;
}) {
  const paid = num(order.paidAmountFen);
  const refunded = num(order.refundedAmountFen);
  const remain = Math.max(0, paid - refunded);
  const qty = num(order.codeQty, 1) || 1;
  const perCode = Math.round(num(order.amountFen) / qty);
  const suggest = bound > 0 ? perCode * issued : remain;

  const [scope, setScope] = React.useState(bound > 0 ? 'UNUSED_CODES' : 'FULL');
  const [amt, setAmt] = React.useState(yuanInput(suggest));
  const [reason, setReason] = React.useState('');
  const [amtErr, setAmtErr] = React.useState<string | null>(null);

  React.useEffect(() => {
    if (!open) return;
    setScope(bound > 0 ? 'UNUSED_CODES' : 'FULL');
    setAmt(yuanInput(suggest));
    setReason(''); setAmtErr(null);
  }, [open, bound, suggest]);

  let fen: number | null = null;
  try { fen = amt.trim() ? fenFromYuan(amt) : null; } catch { fen = null; }

  return (
    <ActionDialog open={open} onClose={onClose} verb="创建退款单" tone="danger" onDone={onDone} width={580}
      title="发起退款"
      disabled={!reason.trim() || fen === null || fen <= 0 || (scope === 'FULL' && bound > 0)}
      consequences={<>
        这一步<b>只是创建一张待审批的退款单</b>，钱还没有退出去。
        要经过「审批通过 → 标记打款成功」两步，钱才真的动。<br />
        退款到账时，系统会按<b>累计退款比例</b>自动冲正返佣；已结算的佣金会同时冲正台账和余额。<br />
        对应数量的<b>未激活码也会被撤销</b>；激活过的码不会被撤销，整单退款会被拒绝。<br />
        在途的退款单也占额度：三张各自不超实付的单子，合计能退出两倍，所以库里按「含在途」算。
      </>}
      onConfirm={() => api.post(`/api/admin/orders/${order.id}/refunds`, {
        amountFen: fenFromYuan(amt), scope, reason: reason.trim(),
      })}>
      <div style={{ display: 'grid', gap: 'var(--s4)' }}>
        {bound > 0 && (
          <Note tone="danger" title="这一单有码已经被激活了">
            已激活 <b>{bound}</b> 个、未激活 <b>{issued}</b> 个。
            按「激活即不退」，<b>整单退款这条路是关着的</b> —— 不是这个界面不让点，是数据库会拒绝，
            后台没有放行的权限。能做的是「只退未激活的码」。
          </Note>
        )}

        <Ctl label="退款范围" width={280} grow>
          <select value={scope} onChange={e => setScope(e.target.value)} style={{
            width: '100%', padding: 'var(--s2) var(--s3)', background: 'var(--surface)',
            border: '1px solid var(--line-strong)', borderRadius: 'var(--r-md)',
            fontSize: 'var(--t-sm)',
          }}>
            <option value="UNUSED_CODES">只退未激活的码（未激活 {issued} 个）</option>
            <option value="FULL" disabled={bound > 0}>
              整单退款{bound > 0 ? '（本单已有码激活，库层会拒绝）' : ''}
            </option>
            <option value="MANUAL">人工特批金额</option>
          </select>
        </Ctl>

        <Ctl label="退款金额（元）" width={220}
             hint={amtErr ?? <>本单实付 {yuan(paid)}、已退 {yuan(refunded)}、还可退 {yuan(remain)}
               {bound > 0 ? ` · 建议值按 单价 ${yuan(perCode)} × 未激活 ${issued} 个 估算，可改` : ''}</>}>
          <TextIn value={amt} inputMode="decimal"
                  onChange={e => {
                    setAmt(e.target.value);
                    try { e.target.value.trim() && fenFromYuan(e.target.value); setAmtErr(null); }
                    catch (err) { setAmtErr((err as Error).message); }
                  }} />
        </Ctl>
        {fen !== null && fen > remain && (
          <Note tone="warn">
            填的金额超过这一单还能退的 {yuan(remain)}（已扣掉在途的退款单）。这样提交会被拒绝。
          </Note>
        )}

        <ReasonBox value={reason} onChange={setReason} label="退款原因"
                   hint="写清楚是谁提的、为什么。审批人和事后对账都要看这一条。" />
      </div>
    </ActionDialog>
  );
}

/* ------------------------------------------------------- 这一单的码，逐条 */

/**
 * 按订单逐条列码（掩码），并支持取一条明文。
 *
 * <p>原来这里只有「按状态计数」，回答不了客服每天都要回答的三个问题：
 * 我这单的码有没有被激活、被谁激活的、哪一张还没用。学员说「码丢了」时，
 * 平台手上一点材料都没有 —— 解密那条路的三个调用点全是代理和订单买家，
 * 超管一个都够不到。
 *
 * <p>明文是<b>按需一条条取</b>的，不是列表直接出：每取一次写一条
 * {@code ADMIN_CODE_REVEAL} 审计。这个动作名与代理侧的 {@code CODE_REVEAL} 分开，
 * 因为纠纷里最关键的那一问是「谁看过它」。
 */
function CodeList({ orderId, qty, refreshKey = 0 }: {
  orderId: number; qty: number; refreshKey?: number;
}) {
  const [open, setOpen] = React.useState(false);
  const state = useAsync<any>(
    () => (open ? api.get(`/api/admin/orders/${orderId}/codes`) : Promise.resolve(null)),
    [orderId, open, refreshKey]);
  const [plain, setPlain] = React.useState<Record<number, string>>({});
  const [busy, setBusy] = React.useState<number | null>(null);
  const toast = useToast();

  if (!open) {
    return <Button size="sm" variant="quiet" onClick={() => setOpen(true)}>
      逐条查看这 {qty} 个码
    </Button>;
  }

  const reveal = async (codeId: number) => {
    setBusy(codeId);
    try {
      const r: any = await api.post(`/api/admin/codes/${codeId}/reveal`);
      setPlain(p => ({ ...p, [codeId]: r.code }));
      toast({ tone: 'info', text: '这次查看已记入审计日志' });
    } catch (e: any) {
      toast({ tone: 'danger', text: e?.message || '取码失败' });
    } finally { setBusy(null); }
  };

  return (
    <Async state={state}>{(d: any) => (
      <div style={{ display: 'grid', gap: 'var(--s2)' }}>
        {(d?.codes ?? []).map((c: any) => (
          <div key={c.codeId} style={{
            display: 'flex', alignItems: 'center', gap: 'var(--s3)', flexWrap: 'wrap',
            padding: 'var(--s2) var(--s3)', background: 'var(--surface-2)',
            borderRadius: 'var(--r-md)', fontSize: 'var(--t-sm)',
          }}>
            <span className="num" style={{ fontWeight: 600, letterSpacing: '.04em' }}>
              {plain[c.codeId] ?? c.masked}
            </span>
            <St domain="code" value={c.status} />
            {c.boundUserId && (
              <Link to={`/users?sel=${c.boundUserId}`} style={{ color: 'var(--brand)' }}>
                已被账号 #{c.boundUserId} 激活
              </Link>
            )}
            <div style={{ marginLeft: 'auto' }}>
              {plain[c.codeId]
                ? <Copyable text={plain[c.codeId]} />
                : <Button size="sm" variant="quiet" loading={busy === c.codeId}
                          onClick={() => reveal(c.codeId)}>看明文</Button>}
            </div>
          </div>
        ))}
        <div style={{ fontSize: 'var(--t-xs)', color: 'var(--ink-3)' }}>
          每查看一次明文都会写一条审计（ADMIN_CODE_REVEAL），与代理自己查看的那个动作分开记。
        </div>
      </div>
    )}</Async>
  );
}

/* --------------------------------------------------- 刚发出去的那一批码 */

/**
 * 「发放激活码」成功后就是跳到这里：/orders?grantKey=…
 *
 * <p>发 20 份就是 20 张单，逐单导出要点 20 次 —— 而这正是最容易「漏导一单」的场景，
 * 漏掉的那些码躺在库里没人发，代理也不知道自己少了几张。所以这一条横幅上直接给
 * 「一次导全」，走 {@code POST /api/admin/code-grants/export}（后端按 grantKey 前缀
 * 把这一批的单全找回来）。
 */
function GrantBanner({ grantKey, total, onClear }: {
  // total 允许 null：还没读回来时写「0 张单」等于当着运营的面说「这批码没发出去」，
  // 而他刚按完发放 —— 他会立刻再发一次。
  grantKey: string; total: number | null; onClear: () => void;
}) {
  const [ask, setAsk] = React.useState(false);
  return (
    <div style={{ marginBottom: 'var(--s4)' }}>
      <Note tone="ok" title="下面是这一次发放建出来的订单">
        幂等键 <span className="num">{grantKey}</span> ·
        共 <b className="num">{total === null ? '…' : total}</b> 张单。
        码已经在代理的「我的激活码」里了；<b>要把明文发给他</b>，用下面这颗一次导全 ——
        逐单导出漏掉的那一单，代理不会知道自己少了几张。
        <div style={{ display: 'flex', gap: 'var(--s2)', flexWrap: 'wrap',
                      marginTop: 'var(--s3)' }}>
          <Button size="sm" onClick={() => setAsk(true)}>导出这一批的全部明文 CSV</Button>
          <Button size="sm" variant="quiet" onClick={onClear}>看全部订单</Button>
        </div>
      </Note>
      <CodeCsvExport open={ask} onClose={() => setAsk(false)}
        title="导出这一次发放的全部激活码明文"
        scope={<>这一次发放（幂等键 <span className="num">{grantKey}</span>）名下的全部订单</>}
        filename={`平台发放激活码-${stamp()}.csv`}
        request={() => api.post('/api/admin/code-grants/export', { grantKey })} />
    </div>
  );
}

/* ------------------------------------------------------------ 明文导出 */

/**
 * 批量取明文存成 CSV。
 *
 * <p>为什么要一道确认框：这不是一次查询，是一次<b>批量明文披露</b> ——
 * 文件里每一行都是能直接激活的码，而它一旦发到群里就收不回来了。
 * 后端会为每一个码逐条写审计，所以「谁在什么时候导出过」是查得到的；
 * 这一框要保证的是按下去的人知道自己在做什么。
 *
 * <p>取不到行时<b>当成失败抛出来</b>，不生成文件：一张只有表头的 CSV 和
 * 一张真的空导出长得一模一样，而人是按文件里的行数去发货的。
 */
function CodeCsvExport({ open, onClose, title, scope, filename, request }: {
  open: boolean; onClose: () => void; title: React.ReactNode;
  scope: React.ReactNode; filename: string; request: () => Promise<any>;
}) {
  return (
    <ActionDialog open={open} onClose={onClose} verb="导出" title={title} width={560}
      consequences={<>
        会把{scope}的<b>激活码明文</b>解密出来，存成一个 CSV 文件下载到你这台电脑上。<br />
        每一个码都会<b>逐条写进审计日志</b>（ADMIN_CODE_REVEAL）：谁、什么时候、导了哪些码，
        事后查得到。<br />
        文件里是可以直接激活的码，<b>请勿在群里转发</b> —— 一码一人，谁先激活算谁的。<br />
        超过 500 个码后端会<b>整批拒绝而不是截断</b>，请按订单分批导 ——
        截断之后你会以为剩下的那些已经发过了。
      </>}
      onConfirm={async () => {
        const r: any = await request();
        const rows: any[] = r?.rows ?? [];
        if (!rows.length) throw new Error('这次一个码都没取到，没有生成文件');
        downloadCsv(filename, [
          ['激活码', '掩码', '状态', '请在此日期前激活', '来源订单号', '来源'],
          ...rows.map(c => [
            c.code, c.masked, label('code', c.status), fmtDate(c.activateDeadline),
            c.orderNo, label('codeSource', c.source),
          ]),
        ]);
        return { ...r, message: `已导出 ${rows.length} 个码。${r?.notice ?? ''}` };
      }} />
  );
}

/* ------------------------------------------------------------ 整单作废 */

/**
 * 把这一单还没被激活的码全部作废。<b>只对平台手工发放的单开放</b>（按钮也只画在那种单上）。
 *
 * <p>收过钱的单为什么不给这条路：作废码而不退钱比不作废更坏 —— 订单实付一分不动、
 * 退款表一行不加、佣金不冲正，对账表完全是平的，只有代理来问「我的码怎么没了」。
 * 那种单要走退款（只退未激活的码）。
 */
function RevokeGiftCodes({ open, order, issued, bound, onClose, onDone }: {
  open: boolean; order: any; issued: number; bound: number;
  onClose: () => void; onDone: (r: any) => void;
}) {
  const [reason, setReason] = React.useState('');
  React.useEffect(() => { if (open) setReason(''); }, [open]);

  return (
    <ActionDialog open={open} onClose={onClose} verb="作废" tone="danger" width={560}
      title="作废这一单还没被激活的码"
      onDone={onDone}
      disabled={!reason.trim() || reason.length > 255}
      confirmLabel={`确认作废 ${issued} 张`}
      consequences={<>
        这一单 <b>{issued}</b> 张还没被激活的码会<b>立刻失效</b>，代理手上那几张再也激活不了。
        <b>作废不可撤销</b>，要恢复只能重新发一批新的。<br />
        {bound > 0
          ? <>另外 <b>{bound}</b> 张<b>已经被学员激活</b>，按「激活即不退」<b>作废不了</b>，
              它们仍然可以正常观看 —— 学员的归属代理也已经永久锁定，改不回来。<br /></>
          : null}
        这一单是平台白送的、没有收过钱，所以作废码不牵动任何金额：
        实付本来就是 0，不产生退款单、也没有佣金要冲正。<br />
        代理来问「为什么用不了」时，能答的只有你下面写的这一句 —— 它会一并写进每一张码的
        作废记录和审计日志。
      </>}
      onConfirm={() => api.post(`/api/admin/orders/${order.id}/codes/revoke`,
                                { reason: reason.trim() })}>
      <ReasonBox value={reason} onChange={setReason} label="作废原因"
                 hint="写清楚是谁要求的、为什么。事后代理来问，只有这一句能答。" />
    </ActionDialog>
  );
}

/**
 * 一张订单的全部码明文。
 *
 * <p>白送单与自助单都给：客服每天都要回答「我这单的码到底是哪些」，
 * 而在此之前超管只能一条条点「看明文」再一条条复制 —— 那正是最容易复制漏一个的做法。
 */
function ExportCodes({ open, order, onClose }: {
  open: boolean; order: any; onClose: () => void;
}) {
  return (
    <CodeCsvExport open={open} onClose={onClose}
      title="导出这一单的全部激活码明文"
      scope={<>订单 <span className="num">{order.orderNo}</span> 名下 {order.codeQty} 个码</>}
      filename={`激活码-${order.orderNo}-${stamp()}.csv`}
      request={() => api.post(`/api/admin/orders/${order.id}/codes/export`)} />
  );
}
