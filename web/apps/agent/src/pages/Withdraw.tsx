/**
 * 提现。
 *
 * 这一页存在的全部理由是那句最常被问到的话：<b>"我余额明明够，为什么提不出来？"</b>
 *
 * 答案有两个，而且是两种完全不同的钱：
 *   ① 那笔佣金还在冻结期，压根没进余额；
 *   ② 余额进了，但已经被一张还没打款的提现单占着（在途）。
 *
 * 所以这里不显示"余额"一个数字，而是把账算给用户看：
 *   账面余额 − 在途提现 = 现在可以申请的
 * 并且把在途的每一笔单独列出来 —— 只给一个"在途 3,000"的合计，
 * 用户还是不知道是哪一笔占着，还是得来问。
 */
import React from 'react';
import { Link } from 'react-router-dom';
import {
  ApiError, Async, Button, Card, dateTime, Empty, Field, Input, PageHead,
  Select, Skeleton, Table, Tag, type Col, useAsync, useToast, yuan,
  IconWallet,
} from '@wangke/shared';
import {
  AgentApi, WITHDRAW_STATUS_TEXT, WITHDRAW_STATUS_TONE, fenToInput, yuanToFen,
  type Balance, type WithdrawRow,
} from '../api';
import { Gate, useActiveAgent } from '../gate';
import {
  Confirm, KV, Money, Note, Pager, Section, TableSkeleton, keepPrevious, useNarrow,
  type AsyncState,
} from '../kit';

const SIZE = 10;

/**
 * 还没走完、正占着余额的提现单状态。和 schema 里 trg_withdraw_precheck 的口径一致。
 *
 * FAILED 也在里面：打款失败的单子钱没出去、余额也没扣，但它<b>还没终结</b> ——
 * 平台可以改好收款信息重新打一次。不把它算作占用，代理就会看到额度已经回来了、
 * 再申请一张，而平台把那张失败的也重新打了 —— 同一笔钱出账两次。
 */
const INFLIGHT: string[] = ['APPLIED', 'REVIEWING', 'APPROVED', 'PAYING', 'FAILED'];

export default function WithdrawPage() {
  return (
    <>
      <PageHead title="提现" desc="把过了冻结期的佣金打到你的结算账户。" />
      <Gate><Body /></Gate>
    </>
  );
}

function Body() {
  const agent = useActiveAgent();
  const [page, setPage] = React.useState(1);
  const bal = useAsync(() => AgentApi.balance(), [agent?.id]);
  const list = useAsync(() => AgentApi.withdrawals({ page, size: SIZE }), [agent?.id, page]);

  const reloadAll = React.useCallback(() => { bal.reload(); list.reload(); }, [bal, list]);

  return (
    <div style={{ display: 'grid', gap: 'var(--s4)' }}>
      <Async state={bal}>
        {(b: Balance) => (
          <>
            <Ledger
              b={b}
              listLoaded={list.data !== undefined}
              inflightRows={(list.data?.rows || []).filter(r => INFLIGHT.includes(r.status))}
            />
            <ApplyForm b={b} onDone={reloadAll} />
          </>
        )}
      </Async>

      <History
        state={list} page={page} onPage={setPage} onChanged={reloadAll}
      />
    </div>
  );
}

/* ---------------------------------------------------------------- 账目 */

function Ledger({ b, inflightRows, listLoaded }: {
  b: Balance; inflightRows: WithdrawRow[]; listLoaded: boolean;
}) {
  // withdrawableFen 由后端给，但这里再算一遍并对一次账：
  // 两个数对不上时宁可把两边都摆出来，也不能挑一个显示 —— 用户按错的那个数去申请，
  // 提交时被后端拒绝，他会以为是系统坏了。
  const derived = b.commissionBalanceFen - b.inflightFen;
  const mismatch = derived !== b.withdrawableFen;

  // inflightRows 只是「提现记录」当前这一页上的在途单，不是全部。
  // 在途合计以后端给的 b.inflightFen 为准，这里只算差额，好在界面上交代清楚。
  const shownInflightFen = inflightRows.reduce((sum, r) => sum + r.amountFen, 0);
  const remainingInflightFen = Math.max(0, b.inflightFen - shownInflightFen);

  return (
    <Card>
      <div style={{ display: 'grid', gap: 'var(--s4)',
                    gridTemplateColumns: 'repeat(auto-fit, minmax(min(260px, 100%), 1fr))' }}>
        {/* 左：算式 */}
        <div>
          <div style={{ fontSize: 'var(--t-sm)', color: 'var(--ink-3)',
                        marginBottom: 'var(--s3)' }}>现在能提多少，是这么算出来的</div>

          <Row label="账面余额" sub="已经过了冻结期的佣金合计">
            <Money fen={b.commissionBalanceFen} />
          </Row>
          <Row label="减去 · 在途提现" sub={inflightRows.length
            ? `${inflightRows.length} 笔提现单还没打完款，这些钱被占着`
            : '已申请但还没打款的提现单会占用余额'} tone="warn">
            <Money fen={b.inflightFen} tone="warn" prefix={b.inflightFen > 0 ? '− ' : ''} />
          </Row>

          <div style={{ borderTop: '1px solid var(--line-strong)', marginTop: 'var(--s2)',
                        paddingTop: 'var(--s3)' }}>
            <Row label="现在可以申请" sub={`单笔不低于 ${yuan(b.minWithdrawFen)}`} strong>
              <Money fen={b.withdrawableFen} size="lg"
                     tone={b.withdrawableFen >= b.minWithdrawFen ? 'ok' : 'muted'} />
            </Row>
          </div>

          {mismatch && (
            <div style={{ marginTop: 'var(--s3)' }}>
              <Note tone="danger" title="这里的数对不上">
                按「账面 − 在途」应当是 <Money fen={derived} size="sm" />，
                但服务端给的可提金额是 <Money fen={b.withdrawableFen} size="sm" />。
                请以提交时的结果为准，并把这条告诉平台对接人。
              </Note>
            </div>
          )}
        </div>

        {/* 右：还没进账面的钱 + 在途明细 */}
        <div style={{ display: 'grid', gap: 'var(--s3)', alignContent: 'start' }}>
          <div style={{
            background: 'var(--warn-soft)', borderRadius: 'var(--r-md)',
            padding: 'var(--s4)',
          }}>
            <div style={{ fontSize: 'var(--t-sm)', color: 'var(--ink-2)' }}>还在冻结期（没算进上面的账面余额）</div>
            <div style={{ marginTop: 'var(--s1)' }}>
              <Money fen={b.frozenFen} size="lg" tone={b.frozenFen > 0 ? 'warn' : 'muted'} block />
            </div>
            <div style={{ fontSize: 'var(--t-xs)', color: 'var(--ink-3)', marginTop: 'var(--s1)' }}>
              冻结期是留给退款的。过了这段时间，这笔钱会自己进账面余额。
              <Link to="/commission" style={{ marginLeft: 'var(--s1)' }}>看每一笔什么时候解冻</Link>
            </div>
          </div>

          {b.inflightFen > 0 && (
            <div style={{ border: '1px solid var(--line)', borderRadius: 'var(--r-md)',
                          padding: 'var(--s3) var(--s4)' }}>
              <div style={{ fontSize: 'var(--t-sm)', color: 'var(--ink-3)',
                            marginBottom: 'var(--s2)' }}>在途的是这几笔</div>
              {listLoaded ? (
                <div style={{ display: 'grid', gap: 'var(--s2)' }}>
                  {inflightRows.map(r => (
                    <div key={r.id} style={{ display: 'flex', justifyContent: 'space-between',
                                             gap: 'var(--s3)', fontSize: 'var(--t-sm)' }}>
                      <span className="num" style={{ color: 'var(--ink-3)' }}>{r.withdrawNo}</span>
                      <span style={{ display: 'flex', gap: 'var(--s2)', alignItems: 'center' }}>
                        <Money fen={r.amountFen} size="sm" />
                        <Tag tone={WITHDRAW_STATUS_TONE[r.status]}>{WITHDRAW_STATUS_TEXT[r.status]}</Tag>
                      </span>
                    </div>
                  ))}
                </div>
              ) : (
                <div style={{ display: 'grid', gap: 'var(--s2)' }}>
                  <Skeleton h={14} /><Skeleton h={14} w="70%" />
                </div>
              )}
              {/* 这几笔只是「提现记录」当前这一页上的在途单，而上面的在途合计是全量的。
                  两个数对不上却不说，用户会自己去加一遍、然后发现加不出来 ——
                  这一页存在的理由就是不让他产生这种困惑，所以差额必须自己交代。 */}
              {listLoaded && remainingInflightFen > 0 && (
                <div style={{ marginTop: 'var(--s2)', paddingTop: 'var(--s2)',
                              borderTop: '1px solid var(--line)',
                              fontSize: 'var(--t-xs)', color: 'var(--ink-3)' }}>
                  另有 <Money fen={remainingInflightFen} size="sm" tone="muted" /> 的在途单子
                  不在下面那一页里，翻页能找到。
                </div>
              )}
            </div>
          )}
        </div>
      </div>
    </Card>
  );
}

function Row({ label, sub, children, tone, strong }: {
  label: string; sub?: string; children: React.ReactNode;
  tone?: 'warn'; strong?: boolean;
}) {
  return (
    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline',
                  gap: 'var(--s4)', padding: 'var(--s2) 0' }}>
      <div style={{ minWidth: 0 }}>
        <div style={{
          fontSize: strong ? 'var(--t-body)' : 'var(--t-sm)',
          fontWeight: strong ? 600 : 400,
          color: tone === 'warn' ? 'var(--warn)' : 'var(--ink)',
        }}>{label}</div>
        {sub && <div style={{ fontSize: 'var(--t-xs)', color: 'var(--ink-3)' }}>{sub}</div>}
      </div>
      <div style={{ flexShrink: 0, textAlign: 'right' }}>{children}</div>
    </div>
  );
}

/* ---------------------------------------------------------------- 申请 */

function ApplyForm({ b, onDone }: { b: Balance; onDone: () => void }) {
  const agent = useActiveAgent();
  const toast = useToast();
  const [amount, setAmount] = React.useState('');
  const [channel, setChannel] = React.useState<'BANK_TRANSFER' | 'WECHAT_TRANSFER'>('BANK_TRANSFER');
  const [ask, setAsk] = React.useState(false);
  const [busy, setBusy] = React.useState(false);

  const fen = yuanToFen(amount);
  // ★ 用后端给的 payeeReady，不要自己拿某个字段去代理这道闸。
  //   apply 的闸是「户名 + 账户」两条，只看 payeeAccount 会让「账户填了、实名没填」
  //   的代理看到一个亮着却必被拒的按钮，而界面上没有地方能补实名。
  //   payeeReady 恒为布尔且与 apply 同源；payeeMissing 是该补什么的人话。
  const noAccount = !b.payeeReady;

  let error = '';
  if (amount && fen === null) error = '金额只能填数字，最多两位小数';
  else if (fen !== null && fen < b.minWithdrawFen) error = `单笔至少 ${yuan(b.minWithdrawFen)}`;
  else if (fen !== null && fen > b.withdrawableFen) {
    error = b.inflightFen > 0
      ? `超出了现在可以申请的金额。账面上有 ${yuan(b.commissionBalanceFen)}，但其中 ${yuan(b.inflightFen)} 被在途的提现单占着。`
      : '超出了现在可以申请的金额';
  }

  const canSubmit = fen !== null && !error && !noAccount;

  async function submit() {
    if (fen === null) return;
    setBusy(true);
    try {
      const r = await AgentApi.applyWithdraw({ amountFen: fen, channel });
      setAsk(false);
      setAmount('');
      toast({ tone: 'ok', text: `提现申请已提交，单号 ${r.withdrawNo}` });
      onDone();
    } catch (e) {
      setAsk(false);
      toast({ tone: 'danger', text: e instanceof ApiError ? e.message : '提交失败，请重试' });
    } finally {
      setBusy(false);
    }
  }

  if (b.withdrawableFen < b.minWithdrawFen) {
    return (
      <Section title="申请提现">
        <Empty
          icon={<IconWallet size={22} />}
          title={b.commissionBalanceFen === 0 && b.frozenFen === 0
            ? '还没有可以提的佣金'
            : `还差一点，单笔至少 ${yuan(b.minWithdrawFen)}`}
          desc={b.frozenFen > 0
            ? '你有佣金还在冻结期，等它解冻进了账面余额就能提。'
            : agent?.level === 1
              ? '佣金来自二级代理进货。他按二级价付款，你拿走和一级价之间的差价。'
              : '二级代理不能继续发展下级，因此不会新增下级进货差价佣金；如果账户里有历史佣金，结算后仍可正常提现。'}
          action={<Link to="/commission"><Button variant="ghost">看佣金流水</Button></Link>}
        />
      </Section>
    );
  }

  return (
    <Section
      title="申请提现"
      desc="提交后由平台审核并打款。审核期间这笔钱会被这张单子占着，不能再用于其他提现。"
    >
      {noAccount && (
        <div style={{ marginBottom: 'var(--s4)' }}>
          <Note tone="warn" title={b.payeeMissing || '收款信息还不全'}>
            提现要先有收款账户，且户名必须和你登记的实名一致 —— 平台不接受代收。
            <Link to="/me" style={{ marginLeft: 'var(--s1)' }}>去补齐收款信息</Link>
          </Note>
        </div>
      )}

      <div style={{ display: 'grid', gap: 'var(--s4)',
                    gridTemplateColumns: 'repeat(auto-fit, minmax(min(240px, 100%), 1fr))' }}>
        <Field
          label="提现金额（元）"
          required
          error={error}
          hint={!error ? `最多可以填 ${fenToInput(b.withdrawableFen)}` : undefined}
        >
          <div style={{ display: 'flex', gap: 'var(--s2)' }}>
            <Input
              value={amount} inputMode="decimal" placeholder="0.00"
              invalid={!!error} disabled={noAccount}
              onChange={e => setAmount(e.target.value.replace(/[^\d.]/g, ''))}
              style={{ fontFamily: 'var(--font-num)', textAlign: 'right' }}
            />
            <Button type="button" variant="ghost" disabled={noAccount}
                    onClick={() => setAmount(fenToInput(b.withdrawableFen))}>全部</Button>
          </div>
        </Field>

        <Field label="打款方式" hint="按你登记的结算账户打款，不能改到别人名下">
          <Select value={channel} disabled={noAccount}
                  onChange={e => setChannel(e.target.value as 'BANK_TRANSFER' | 'WECHAT_TRANSFER')}>
            <option value="BANK_TRANSFER">银行转账</option>
            <option value="WECHAT_TRANSFER">微信转账</option>
          </Select>
        </Field>
      </div>

      <div style={{ background: 'var(--surface-2)', borderRadius: 'var(--r-md)',
                    padding: 'var(--s3) var(--s4)', marginBottom: 'var(--s4)' }}>
        <KV label="收款人">{b.payeeName || <span style={{ color: 'var(--ink-3)' }}>未填写</span>}</KV>
        <KV label="收款账户" hint={b.payeeBankName || undefined}>
          <span className="num">{b.payeeAccount || <span style={{ color: 'var(--ink-3)' }}>未填写</span>}</span>
        </KV>
      </div>

      <Button size="lg" variant="accent" disabled={!canSubmit} onClick={() => setAsk(true)}>
        提交提现申请
      </Button>

      <div style={{ marginTop: 'var(--s4)' }}>
        <Note tone="info">
          以个人身份结算的，平台按规定代扣代缴个人所得税。打款时会分成「代扣税额」和「实际到账」两笔数字
          列在提现单上，两者相加正好等于你申请的金额。
        </Note>
      </div>

      <Confirm
        open={ask}
        title="确认提交提现申请"
        confirmText="确认提交"
        loading={busy}
        onClose={() => setAsk(false)}
        onConfirm={submit}
        consequence={<>
          提交后这 <Money fen={fen ?? 0} size="sm" /> 会被这张提现单占住，
          审核和打款期间不能再用于其他提现。打款前你可以自己撤销，撤销后这笔钱立刻回到可申请的额度里。
        </>}
      >
        <div style={{ fontSize: 'var(--t-sm)', color: 'var(--ink-2)', lineHeight: 'var(--lh-body)' }}>
          打到：<b>{b.payeeName}</b>　<span className="num">{b.payeeAccount}</span>
          <br />
          户名和账号对不上会被风控驳回，请再核一遍。
        </div>
      </Confirm>
    </Section>
  );
}

/* ---------------------------------------------------------------- 历史 */

function History({ state, page, onPage, onChanged }: {
  state: AsyncState<{ total: number; rows: WithdrawRow[] }>;
  page: number; onPage: (p: number) => void; onChanged: () => void;
}) {
  const toast = useToast();
  const narrow = useNarrow();
  const [cancelling, setCancelling] = React.useState<WithdrawRow | null>(null);
  const [busy, setBusy] = React.useState(false);
  // 翻页时留住上一屏
  const view = keepPrevious(state);

  async function doCancel() {
    if (!cancelling) return;
    setBusy(true);
    try {
      await AgentApi.cancelWithdraw(cancelling.id);
      toast({ tone: 'ok', text: '已撤销，钱回到可申请额度里了' });
      setCancelling(null);
      onChanged();
    } catch (e) {
      toast({ tone: 'danger', text: e instanceof ApiError ? e.message : '撤销失败，请重试' });
    } finally {
      setBusy(false);
    }
  }

  const cols: Col<WithdrawRow>[] = [
    { key: 'withdrawNo', title: '单号', width: 170,
      render: r => <span className="num" style={{ color: 'var(--ink-2)' }}>{r.withdrawNo}</span> },
    { key: 'amountFen', title: '申请金额', numeric: true, width: 120,
      render: r => <Money fen={r.amountFen} /> },
    { key: 'taxWithheldFen', title: '代扣税额', numeric: true, width: 110,
      render: r => r.status === 'PAID'
        ? <Money fen={r.taxWithheldFen} tone="muted" />
        : <span style={{ color: 'var(--ink-4)' }}>打款时确定</span> },
    { key: 'actualPaidFen', title: '实际到账', numeric: true, width: 120,
      render: r => r.status === 'PAID'
        ? <Money fen={r.actualPaidFen} tone="ok" />
        : <span style={{ color: 'var(--ink-4)' }}>—</span> },
    { key: 'status', title: '状态', width: 100,
      render: r => <Tag tone={WITHDRAW_STATUS_TONE[r.status]}>{WITHDRAW_STATUS_TEXT[r.status]}</Tag> },
    { key: 'note', title: '说明', render: r => <StatusNote row={r} /> },
    { key: 'createdAt', title: '申请时间', width: 150, render: r => dateTime(r.createdAt) },
    {
      key: 'act', title: '', width: 76,
      render: r => r.status === 'APPLIED'
        ? <Button size="sm" variant="quiet" onClick={() => setCancelling(r)}>撤销</Button>
        : null,
    },
  ];

  return (
    <Section title="提现记录" desc="每一笔的去向都在这里，包括被驳回的原因。">
      {view.loading ? <TableSkeleton rows={4} cols={6} /> : (
        <Async state={view}>
          {(d) => d.rows.length === 0 ? (
            <Empty
              icon={<IconWallet size={22} />}
              title="还没提过现"
              desc="第一次提现前请先确认结算账户填对了：户名要和你的实名一致，打给别人会被直接驳回。"
              action={<Link to="/me"><Button variant="ghost">检查结算账户</Button></Link>}
            />
          ) : narrow ? (
            <div style={{ display: 'grid', gap: 'var(--s2)' }}>
              {d.rows.map(r => (
                <Card key={r.id} style={{ padding: 'var(--s3) var(--s4)' }}>
                  <div style={{ display: 'flex', justifyContent: 'space-between',
                                alignItems: 'center', gap: 'var(--s3)' }}>
                    <Money fen={r.amountFen} size="lg" />
                    <Tag tone={WITHDRAW_STATUS_TONE[r.status]}>{WITHDRAW_STATUS_TEXT[r.status]}</Tag>
                  </div>
                  <div style={{ fontSize: 'var(--t-xs)', color: 'var(--ink-3)',
                                marginTop: 'var(--s2)', lineHeight: 'var(--lh-body)' }}>
                    <span className="num">{r.withdrawNo}</span> · {dateTime(r.createdAt)}
                    {r.status === 'PAID' && <><br />实际到账 {yuan(r.actualPaidFen)}，代扣 {yuan(r.taxWithheldFen)}</>}
                  </div>
                  <div style={{ marginTop: 'var(--s2)', fontSize: 'var(--t-sm)' }}>
                    <StatusNote row={r} />
                  </div>
                  {r.status === 'APPLIED' && (
                    <div style={{ marginTop: 'var(--s2)' }}>
                      <Button size="sm" variant="quiet" onClick={() => setCancelling(r)}>撤销这笔申请</Button>
                    </div>
                  )}
                </Card>
              ))}
              <Pager page={page} size={SIZE} total={d.total} onPage={onPage} busy={state.loading} />
            </div>
          ) : (
            <>
              <Table cols={cols} rows={d.rows} rowKey={r => r.id} />
              <Pager page={page} size={SIZE} total={d.total} onPage={onPage} busy={state.loading} />
            </>
          )}
        </Async>
      )}

      <Confirm
        open={!!cancelling}
        title="撤销这笔提现申请"
        confirmText="确认撤销"
        danger
        loading={busy}
        onClose={() => setCancelling(null)}
        onConfirm={doCancel}
        consequence={<>
          撤销之后这 <Money fen={cancelling?.amountFen ?? 0} size="sm" /> 会立刻回到可申请额度里，
          钱不会少。但这张单子作废，要提现得重新提交一张，审核也要重新排。
        </>}
      />
    </Section>
  );
}

function StatusNote({ row }: { row: WithdrawRow }) {
  if (row.status === 'RISK_REJECTED') {
    return <span style={{ color: 'var(--danger)' }}>
      {row.riskNote || '风控驳回。常见原因是收款户名和实名不一致。'}
    </span>;
  }
  if (row.status === 'FAILED') {
    // 不能再写「钱还在你的余额里，可以重新申请」：这张单仍占着额度（它还没终结，
    // 平台会重新打或者驳回），照这句话去再提一张，会撞上「超出可提额度」——
    // 而他刚被告知钱还在。两句话在他那里都是平台说的。
    return <span style={{ color: 'var(--danger)' }}>
      {row.failReason || '打款失败，钱没有出账。'}
      {' '}这笔金额仍占着你的可提额度：平台会改好收款信息重新打一次；
      确实打不出去会驳回，那时额度立刻退回，你再重新申请。
    </span>;
  }
  if (row.status === 'PAID') {
    return <span style={{ color: 'var(--ink-3)' }}>{dateTime(row.paidAt)} 已打款</span>;
  }
  if (row.status === 'CANCELLED') {
    return <span style={{ color: 'var(--ink-3)' }}>你自己撤销的，钱没动</span>;
  }
  if (row.status === 'APPLIED') return <span style={{ color: 'var(--ink-3)' }}>等平台受理</span>;
  if (row.status === 'REVIEWING') return <span style={{ color: 'var(--ink-3)' }}>平台正在审核</span>;
  if (row.status === 'APPROVED') return <span style={{ color: 'var(--ink-3)' }}>审核过了，排队打款中</span>;
  return <span style={{ color: 'var(--ink-3)' }}>银行处理中</span>;
}
