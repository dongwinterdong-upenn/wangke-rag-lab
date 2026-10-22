/**
 * 佣金流水。
 *
 * 这一页要回答的问题只有一个：<b>我的钱现在在哪一格里，什么时候能动。</b>
 *
 * 所以顶部不是四个并列的数字卡片，而是一条有方向的流水线：
 *   冻结中 → 可提现 → 提现中 → 已提现
 * 并列的卡片会让人以为这是四类互不相干的钱；画成流水线，
 * "为什么这笔还不能提"这个问题在看到界面的一瞬间就自己回答了。
 */
import React from 'react';
import { Link } from 'react-router-dom';
import {
  Async, Button, Card, dateTime, Empty, Modal, PageHead, Table, Tag,
  type Col, untilText, useAsync, IconSearch, IconTrend,
} from '@wangke/shared';
import {
  AgentApi, COMMISSION_STATUS_TEXT, COMMISSION_STATUS_TONE,
  type CommissionDetail, type CommissionRow, type CommissionStatus, type CommissionSummary,
} from '../api';
import { Gate, useActiveAgent } from '../gate';
import {
  KV, Money, Note, Pager, Section, Toolbar, Segmented, keepPrevious, useNarrow,
} from '../kit';

type Filter = 'ALL' | CommissionStatus;
const SIZE = 20;

export default function CommissionPage() {
  return (
    <>
      <PageHead
        title="佣金流水"
        desc="查看佣金的冻结、结算、提现与撤销记录。"
      />
      <Gate><Body /></Gate>
    </>
  );
}

function Body() {
  const agent = useActiveAgent();
  const canDevelopDownline = agent?.level === 1;
  const narrow = useNarrow();
  const [filter, setFilter] = React.useState<Filter>('ALL');
  const [page, setPage] = React.useState(1);
  const [detailId, setDetailId] = React.useState<number | null>(null);

  const raw = useAsync(
    () => AgentApi.commissions({ status: filter === 'ALL' ? undefined : filter, page, size: SIZE }),
    [agent?.id, filter, page]);
  // 换筛选/翻页时留住上一屏：整页塌成骨架再长回来，比多等 200ms 更烦人
  const state = keepPrevious(raw);

  const cols: Col<CommissionRow>[] = [
    {
      key: 'amountFen', title: '佣金', numeric: true, width: 130,
      render: r => (
        <span style={{ display: 'inline-flex', flexDirection: 'column', alignItems: 'flex-end' }}>
          <Money fen={r.netAmountFen} tone={r.status === 'REVOKED' ? 'muted' : 'ink'} />
          {r.reversedFen > 0 && (
            <span style={{ fontSize: 'var(--t-xs)', color: 'var(--danger)' }}>已冲正 <Money fen={r.reversedFen} size="sm" tone="danger" />
            </span>
          )}
        </span>
      ),
    },
    {
      key: 'status', title: '状态', width: 96,
      render: r => <Tag tone={COMMISSION_STATUS_TONE[r.status]}>{COMMISSION_STATUS_TEXT[r.status]}</Tag>,
    },
    {
      key: 'when', title: '什么时候能提', width: 160,
      render: r => {
        if (r.status === 'PENDING') return <span style={{ color: 'var(--warn)' }}>{untilText(r.freezeUntil)}解冻</span>;
        if (r.status === 'SETTLED') return <span style={{ color: 'var(--ok)' }}>现在就能提</span>;
        if (r.status === 'WITHDRAWING') return <span style={{ color: 'var(--brand)' }}>在提现单里</span>;
        if (r.status === 'WITHDRAWN') return <span style={{ color: 'var(--ink-3)' }}>已打款</span>;
        return <span style={{ color: 'var(--danger)' }}>已撤销</span>;
      },
    },
    {
      key: 'source', title: '来自谁', width: 140,
      render: r => (
        <span style={{ display: 'inline-flex', flexDirection: 'column', lineHeight: 1.3 }}>
          <span>{r.sourceAgentName || '下级代理'}</span>
          <span className="num" style={{ fontSize: 'var(--t-xs)', color: 'var(--ink-3)' }}>
            {r.sourceAgentCode || '—'}
          </span>
        </span>
      ),
    },
    {
      key: 'orderNo', title: '来源订单', width: 180,
      render: r => (
        <button onClick={() => setDetailId(r.id)} style={{
          background: 'none', border: 'none', padding: 0, cursor: 'pointer',
          color: 'var(--brand)', fontFamily: 'var(--font-num)', fontSize: 'inherit',
        }}>{r.orderNo}</button>
      ),
    },
    { key: 'createdAt', title: '产生时间', width: 150, render: r => dateTime(r.createdAt) },
  ];

  return (
    <div style={{ display: 'grid', gap: 'var(--s4)' }}>
      <Async state={state}>
        {(d) => (
          <>
            <Pipeline s={d.summary} />

            <Section>
              <Toolbar
                left={
                  <Segmented<Filter>
                    value={filter}
                    onChange={f => { setFilter(f); setPage(1); }}
                    options={[
                      { value: 'ALL', label: '全部' },
                      { value: 'PENDING', label: '冻结中' },
                      { value: 'SETTLED', label: '可提现' },
                      { value: 'WITHDRAWING', label: '提现中' },
                      { value: 'WITHDRAWN', label: '已提现' },
                      { value: 'REVOKED', label: '已撤销' },
                    ]}
                  />
                }
                right={
                  d.summary.settledFen > 0
                    ? <Link to="/withdraw"><Button variant="accent">把可提现的钱提出来</Button></Link>
                    : null
                }
              />

              {d.rows.length === 0 ? (
                filter !== 'ALL' ? (
                  <Empty
                    icon={<IconSearch size={22} />}
                    title={`没有「${COMMISSION_STATUS_TEXT[filter as CommissionStatus]}」的佣金`}
                    desc="换一档看看，或者看全部。"
                    action={<Button variant="ghost" onClick={() => { setFilter('ALL'); setPage(1); }}>
                      看全部
                    </Button>}
                  />
                ) : (
                  <Empty
                    icon={<IconTrend size={22} />}
                    title="还没有佣金"
                    desc={canDevelopDownline
                      ? '佣金来自你的二级代理进货：他按二级价付款，你拿走和一级价之间的差价。你自己进货不产生佣金。'
                      : '二级代理不能继续发展下级，因此不会新增下级进货差价佣金。如果账户里有历史佣金，仍会保留并可按规则提现。'}
                    action={<Link to="/promote"><Button variant="accent">
                      {canDevelopDownline ? '去发展下级 / 推广' : '推广课程给学员'}
                    </Button></Link>}
                  />
                )
              ) : narrow ? (
                <div style={{ display: 'grid', gap: 'var(--s2)' }}>
                  {d.rows.map(r => (
                    <Card key={r.id} style={{ padding: 'var(--s3) var(--s4)' }}>
                      <div style={{ display: 'flex', justifyContent: 'space-between',
                                    alignItems: 'center', gap: 'var(--s3)' }}>
                        <Money fen={r.netAmountFen} size="lg" />
                        <Tag tone={COMMISSION_STATUS_TONE[r.status]}>
                          {COMMISSION_STATUS_TEXT[r.status]}
                        </Tag>
                      </div>
                      <div style={{ fontSize: 'var(--t-xs)', color: 'var(--ink-3)',
                                    marginTop: 'var(--s2)', lineHeight: 'var(--lh-body)' }}>
                        {r.status === 'PENDING'
                          ? <>{untilText(r.freezeUntil)}解冻</>
                          : <>{dateTime(r.createdAt)} 产生</>}
                        {' · '}来自 {r.sourceAgentCode || '下级'}
                      </div>
                      <div style={{ marginTop: 'var(--s2)' }}>
                        <Button size="sm" variant="quiet" onClick={() => setDetailId(r.id)}>
                          看来源订单
                        </Button>
                      </div>
                    </Card>
                  ))}
                  <Pager page={page} size={SIZE} total={d.total} onPage={setPage} busy={raw.loading} />
                </div>
              ) : (
                <>
                  <Table cols={cols} rows={d.rows} rowKey={r => r.id} />
                  <Pager page={page} size={SIZE} total={d.total} onPage={setPage} busy={raw.loading} />
                </>
              )}
            </Section>
          </>
        )}
      </Async>

      <Note tone="info" title="冻结期是干什么的">
        {canDevelopDownline
          ? '二级代理付款之后佣金不会立刻可提，要先过一段冻结期。这段时间是留给退款的；如果那一单后来退了，佣金要跟着撤销。冻结期一过，这笔钱就进你的可提现余额。'
          : '如果账户里有历史佣金，仍要先过冻结期才能提现；冻结期间来源订单退款，佣金会跟着撤销。二级代理不能继续发展下级，所以不会新增这类佣金。'}
      </Note>

      {detailId !== null && <DetailModal id={detailId} onClose={() => setDetailId(null)} />}
    </div>
  );
}

/** 四段流水线。段与段之间画箭头，说明钱是往一个方向走的。 */
function Pipeline({ s }: { s: CommissionSummary }) {
  const stages = [
    { key: 'PENDING', label: '冻结中', fen: s.pendingFen, tone: 'warn' as const,
      desc: '等退款窗口过去，还不能提' },
    // ★ 这一格是「累计已结算」，不是「现在能提多少」。
    //   commission_record 的状态机在生产里只走到 SETTLED 就停了（没有任何一处写
    //   WITHDRAWING），所以 settledFen 只增不减：提完 5000 之后它照样写 5000，
    //   而提现页写「现在可以申请 0」。把它标成「可提现·现在就能申请」是在骗人。
    //   真·可提由后端权威给出（withdrawableFen，与提现闸同一道算式），单列在下面。
    { key: 'SETTLED', label: '已结算', fen: s.settledFen, tone: 'ok' as const,
      desc: '累计已过冻结期的佣金' },
    { key: 'WITHDRAWING', label: '提现中', fen: s.withdrawingFen, tone: 'brand' as const,
      desc: '已在提现单里，等打款' },
    { key: 'WITHDRAWN', label: '已提现', fen: s.withdrawnFen, tone: 'muted' as const,
      desc: '钱已经打给你了' },
  ];
  // 权威口径单独说一句：上面四格是佣金记录的状态分布（累计量），
  // 这一行才是此刻能申请的金额。两者不是一回事，不说清楚就会被当成同一个数。
  const canWithdraw = s.withdrawableFen;
  return (
    <Card>
      {canWithdraw !== undefined && (
        <div style={{ marginBottom: 'var(--s3)', paddingBottom: 'var(--s3)',
                      borderBottom: '1px solid var(--line)', display: 'flex',
                      alignItems: 'baseline', gap: 'var(--s2)', flexWrap: 'wrap' }}>
          <span style={{ fontSize: 'var(--t-sm)', color: 'var(--ink-3)' }}>现在可以申请提现</span>
          <Money fen={canWithdraw} size="lg" tone={canWithdraw > 0 ? 'ok' : 'muted'} />
          <span style={{ fontSize: 'var(--t-xs)', color: 'var(--ink-3)' }}>
            与提现页同一道算式；下面四格是佣金记录的状态分布，其中「已结算」是累计量，提过的也仍然算在里面
          </span>
        </div>
      )}
      <div style={{ display: 'flex', alignItems: 'stretch', gap: 'var(--s2)', flexWrap: 'wrap' }}>
        {stages.map((st, i) => (
          <React.Fragment key={st.key}>
            <div style={{ flex: '1 1 150px', minWidth: 140 }}>
              <div style={{ fontSize: 'var(--t-sm)', color: 'var(--ink-3)' }}>{st.label}</div>
              <div style={{ marginTop: 'var(--s1)' }}>
                <Money fen={st.fen} size="lg" tone={st.fen > 0 ? st.tone : 'muted'} block />
              </div>
              <div style={{ fontSize: 'var(--t-xs)', color: 'var(--ink-3)',
                            marginTop: 'var(--s1)' }}>{st.desc}</div>
            </div>
            {i < stages.length - 1 && (
              <div aria-hidden style={{
                alignSelf: 'center', color: 'var(--ink-4)', fontSize: 'var(--t-h3)',
                flexShrink: 0, padding: '0 var(--s1)',
              }}>→</div>
            )}
          </React.Fragment>
        ))}
      </div>
      {s.revokedFen > 0 && (
        <div style={{ marginTop: 'var(--s4)', paddingTop: 'var(--s3)',
                      borderTop: '1px solid var(--line)', fontSize: 'var(--t-sm)',
                      color: 'var(--ink-2)' }}>
          另有 <Money fen={s.revokedFen} size="sm" tone="danger" /> 已撤销
          —— 对应的订单退了款，这部分佣金不再计入。
        </div>
      )}
    </Card>
  );
}

function DetailModal({ id, onClose }: { id: number; onClose: () => void }) {
  const state = useAsync(() => AgentApi.commission(id), [id]);
  return (
    <Modal open title="这笔佣金是怎么来的" onClose={onClose} width={520}
           footer={<Button variant="ghost" onClick={onClose}>关闭</Button>}>
      <Async state={state}>
        {(d: CommissionDetail) => (
          <div>
            <div style={{ textAlign: 'center', padding: 'var(--s4) 0' }}>
              <Money fen={d.netAmountFen} size="xl" />
              <div style={{ marginTop: 'var(--s2)' }}>
                <Tag tone={COMMISSION_STATUS_TONE[d.status]}>{COMMISSION_STATUS_TEXT[d.status]}</Tag>
              </div>
            </div>

            <KV label="来源订单"><span className="num">{d.orderNo}</span></KV>
            {d.reversedFen > 0 && (
              <KV label="退款冲正">
                原佣金 <Money fen={d.amountFen} size="sm" />，已冲正{' '}
                <Money fen={d.reversedFen} size="sm" tone="danger" />
              </KV>
            )}
            {d.order && (
              <>
                <KV label="买的什么">
                  {d.order.skuName || '—'}
                  <span style={{ color: 'var(--ink-3)' }}>（{d.order.codeQty} 个激活码）</span>
                </KV>
                <KV label="下单人">
                  {d.order.buyerAgentName || '下级代理'}
                  <span className="num" style={{ color: 'var(--ink-3)' }}>
                    {d.order.buyerAgentCode ? ` ${d.order.buyerAgentCode}` : ''}
                  </span>
                </KV>
                {/* 「他实付」这一行去掉了：进货价一家一议之后，它等于把下级谈下来的
                    专属价端给上级看。上级要核对的是自己那笔佣金 —— 固定每码那一档
                    用「每个码固定金额 × 码数」就能自己验算，不需要知道下级花了多少。 */}
                <KV label="付款时间">{dateTime(d.order.paidAt)}</KV>
              </>
            )}
            <KV
              label="怎么算的"
              hint={d.calcModeSnapshot === 'FIXED_PER_CODE'
                ? '按码固定金额结算。规则以下单当时为准，之后平台改规则不影响这一笔。'
                : '按他实付金额的比例结算，不按标价 —— 打折成交时按实收算。'}
            >
              {d.calcModeSnapshot === 'FIXED_PER_CODE' ? '每个码固定金额' : '按实付金额的比例'}
              {/* 计算基数只在按比例结算时才出网（它就等于下级的进货价）。
                  固定每码那一档拿它没用处，所以后端按需给、这里按需画 ——
                  写成无条件渲染的话，字段缺席时 Money 会画出一个 ¥0.00，
                  而「计算基数 ¥0.00」读起来像平台把佣金按 0 算了。 */}
              {d.calcBasisFen != null && (
                <span style={{ color: 'var(--ink-3)' }}>
                  （计算基数 <Money fen={d.calcBasisFen} size="sm" />）
                </span>
              )}
            </KV>
            <KV label="冻结到">
              {dateTime(d.freezeUntil)}
              {d.status === 'PENDING' && (
                <span style={{ color: 'var(--warn)' }}> · {untilText(d.freezeUntil)}</span>
              )}
            </KV>
            {d.withdrawNo && <KV label="提现单号"><span className="num">{d.withdrawNo}</span></KV>}
            {d.revokeReason && (
              <KV label="撤销原因">
                <span style={{ color: 'var(--danger)' }}>{d.revokeReason}</span>
              </KV>
            )}
          </div>
        )}
      </Async>
    </Modal>
  );
}
