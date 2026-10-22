/**
 * 我的激活码 —— 代理待得最久的一页。
 *
 * 三条决定了这一页长什么样的事实：
 *
 * ① <b>默认只给掩码。</b>这个列表天天开着，投屏、截图、同事路过看一眼都是常态。
 *    明文只在显式点开时逐条给，而且每次都写审计日志 —— 日后出现
 *    "我的码被别人用了"，这条记录是平台和代理各自的立场来源。
 *
 * ② <b>最高频的操作是批量导出。</b>代理拿到码之后要成批发给学员，
 *    一个一个点开复制是不可能完成的。所以导出按钮是这一页唯一的强调色。
 *
 * ③ <b>码会过期。</b>发码后有激活期限，过期作废且不退钱。所以"还剩多久"
 *    要直接写在行上，不能让人自己去算日期差。
 */
import React from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import {
  ApiError, Async, Button, Card, Chip, dateTime, Empty, Input, Modal, PageHead,
  Spinner, StatusTag, Table, Tag, type Col, untilText, useAsync, useToast,
  IconSearch, IconTicket,
} from '@wangke/shared';
import { AgentApi, CODE_STATUS_HINT, type CodeRow, type RevealedCode } from '../api';
import { Gate, useActiveAgent } from '../gate';
import {
  Confirm, CopyButton, Note, Pager, Section, TableSkeleton, Toolbar, Segmented,
  downloadCsv, keepPrevious, stamp, useNarrow,
} from '../kit';

type Filter = 'ALL' | 'ISSUED' | 'BOUND' | 'DEAD';
const SIZE = 20;

/** 「已作废」这一档要把作废和过期一起收进去：对代理来说它们是同一件事 —— 这个码没用了。 */
const FILTER_TO_QUERY: Record<Filter, string | undefined> = {
  ALL: undefined, ISSUED: 'ISSUED', BOUND: 'BOUND', DEAD: 'REVOKED,EXPIRED',
};

export default function CodesPage() {
  return (
    <>
      <PageHead
        title="我的激活码"
        desc="把码发给学员，他在学员端填进去就能开课。一个码只能一个人用。"
      />
      <Gate><Body /></Gate>
    </>
  );
}

function Body() {
  const agent = useActiveAgent();
  const toast = useToast();
  const narrow = useNarrow();
  const [sp, setSp] = useSearchParams();

  const initial = (sp.get('status') as Filter) || 'ALL';
  const [filter, setFilter] = React.useState<Filter>(
    ['ALL', 'ISSUED', 'BOUND', 'DEAD'].includes(initial) ? initial : 'ALL');
  const [keyword, setKeyword] = React.useState(sp.get('q') || '');
  const [applied, setApplied] = React.useState(keyword);
  const [page, setPage] = React.useState(1);

  const raw = useAsync(
    () => AgentApi.codes({ status: FILTER_TO_QUERY[filter], keyword: applied, page, size: SIZE }),
    [agent?.id, filter, applied, page]);
  // 翻页时留住上一屏，只有首次加载画骨架
  const state = keepPrevious(raw);

  // 单条查看明文
  const [revealing, setRevealing] = React.useState<CodeRow | null>(null);
  const [plain, setPlain] = React.useState<string | null>(null);
  const [revealErr, setRevealErr] = React.useState('');
  const [refUrl, setRefUrl] = React.useState<string | null>(null);
  // 取明文是异步的。连着点开两个码时，先发的那个响应可能后到 ——
  // 落到界面上就是「弹窗标着 B、里面显示的是 A 的码」，而这个码是要发给学员的。
  // 用一个自增序号裁决：只认最后一次点击的结果。
  const revealSeq = React.useRef(0);

  // 推广链接只在第一次要用邀请文案时取一次
  React.useEffect(() => {
    if (!revealing || refUrl !== null) return;
    AgentApi.promote().then(p => setRefUrl(p.refUrl || ''))
      .catch(() => setRefUrl(''));   // 取不到就写不带链接的文案，不要因此让整个弹窗失败
  }, [revealing, refUrl]);

  async function openReveal(row: CodeRow) {
    const seq = ++revealSeq.current;
    setRevealing(row);
    setPlain(null);
    setRevealErr('');
    try {
      const r = await AgentApi.reveal(row.id);
      if (seq === revealSeq.current) setPlain(r.code);
    } catch (e) {
      if (seq === revealSeq.current)
        setRevealErr(e instanceof ApiError ? e.message : '取不到这个码，请重试');
    }
  }

  // 批量导出
  const [exportAsk, setExportAsk] = React.useState(false);
  const [exporting, setExporting] = React.useState(false);

  async function doExport() {
    setExporting(true);
    try {
      const r = await AgentApi.exportCodes({ status: 'ISSUED', keyword: applied });
      if (!r.rows.length) {
        toast({ tone: 'danger', text: '没有可导出的未激活码' });
        setExportAsk(false);
        return;
      }
      downloadCsv(`未激活激活码-${stamp()}.csv`, [
        ['激活码', '掩码', '状态', '请在此日期前激活', '来源'],
        ...r.rows.map((c: RevealedCode) => [
          c.code, c.masked, '未激活', c.activateDeadline?.slice(0, 10),
          // 与表格那一列同一条规矩：白送的码不能在 CSV 里只留一个看起来正常的单号。
          // 这份文件是代理拿去对账和发货的底稿，落到表格里之后没有任何东西再解释一次，
          // 所以「平台发放」四个字要跟着单号一起写进这一格。
          c.source === 'PLATFORM_GRANT' ? `平台发放 ${c.orderNo || ''}`.trim() : c.orderNo,
        ]),
      ]);
      toast({ tone: 'ok', text: `已导出 ${r.rows.length} 个码` });
      setExportAsk(false);
    } catch (e) {
      toast({ tone: 'danger', text: e instanceof ApiError ? e.message : '导出失败，请重试' });
    } finally {
      setExporting(false);
    }
  }

  function pushFilter(f: Filter) {
    setFilter(f); setPage(1);
    const next = new URLSearchParams(sp);
    if (f === 'ALL') next.delete('status'); else next.set('status', f);
    setSp(next, { replace: true });
  }

  const cols: Col<CodeRow>[] = [
    {
      key: 'masked', title: '激活码', width: 250,
      render: r => (
        // 码是一个整体，断行会让人读错也没法拿它和手上的字符串比对，
        // 所以这一列宁可占宽也不许折
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 'var(--s2)',
                       whiteSpace: 'nowrap' }}>
          <span className="num">{r.masked}</span>
          <Button size="sm" variant="quiet" onClick={() => openReveal(r)}
                  style={{ padding: 'var(--s1) var(--s2)' }}>查看</Button>
        </span>
      ),
    },
    { key: 'status', title: '状态', width: 88, render: r => <StatusTag status={r.status} /> },
    {
      key: 'activateDeadline', title: '激活期限', width: 150,
      render: r => r.status === 'ISSUED'
        ? <DeadlineCell iso={r.activateDeadline} />
        : <span style={{ color: 'var(--ink-3)' }}>{r.activateDeadline?.slice(0, 10)}</span>,
    },
    {
      key: 'boundPhone', title: '谁激活的', width: 130,
      render: r => r.status === 'BOUND'
        ? <span className="num">{r.boundPhone || `用户 ${r.boundUserId}`}</span>
        : <span style={{ color: 'var(--ink-4)' }}>—</span>,
    },
    {
      key: 'boundAt', title: '激活时间', width: 150,
      render: r => r.boundAt ? dateTime(r.boundAt) : <span style={{ color: 'var(--ink-4)' }}>—</span>,
    },
    {
      // 这一列<b>不是</b>「订单号」。平台手工发的码挂在一张真订单上，单号也是真的，
      // 但那张单从来没有收到过钱（实付恒 0、到账时间恒空）。只把单号摆出来的话，
      // 代理会拿它去对账 —— 而账上没有这一笔，他只能来问平台「这个单号是什么」。
      // 所以白送的码这一格<b>不给单号</b>，给一句「平台发放」，用带框的 Chip
      // 而不是等宽数字，让它在扫这一列时一眼就和单号分得开。
      //
      // 顺带：这一列在后端补 orderNo 之前，对每一行都恒显示「—」，见 CodeRow.source 的注释。
      key: 'source', title: '来源', width: 170,
      render: r => r.source === 'PLATFORM_GRANT'
        ? <Chip>平台发放</Chip>
        : <span className="num" style={{ color: 'var(--ink-3)' }}>{r.orderNo || '—'}</span>,
    },
  ];

  const filtering = filter !== 'ALL' || !!applied;

  return (
    <div style={{ display: 'grid', gap: 'var(--s4)' }}>
      <Note tone="info" title="为什么默认看不到完整的码">
        列表里只显示掩码。点「查看」才会取出完整的码，而且<b>每一次查看都会被记一条日志</b>
        （谁、什么时候、看了哪个码）。这条记录在"我的码被别人用了"这类纠纷里，
        是你和平台各自说得清的唯一依据 —— 所以它是有意留下的，不是在监视你。
      </Note>

      <Section>
        <Toolbar
          left={
            <>
              <Segmented<Filter>
                value={filter}
                onChange={pushFilter}
                options={[
                  { value: 'ALL', label: '全部' },
                  { value: 'ISSUED', label: '未激活' },
                  { value: 'BOUND', label: '已激活' },
                  { value: 'DEAD', label: '已作废 / 过期' },
                ]}
              />
              {/* 下划线式输入框没有外框，按钮必须按【底边】对齐，
                  否则按钮会浮在那条下划线上方，看起来像是没对准。
                  宽度用 min(100%, 240px)：写死 240 时窄屏放不下，整行会被顶出容器。 */}
              <form onSubmit={e => { e.preventDefault(); setApplied(keyword.trim()); setPage(1); }}
                    style={{ display: 'flex', gap: 'var(--s2)', alignItems: 'flex-end',
                             flexWrap: 'wrap', maxWidth: '100%' }}>
                <Input
                  value={keyword} placeholder="搜掩码尾号、订单号、学员手机号"
                  onChange={e => setKeyword(e.target.value)}
                  style={{ width: 'min(100%, 240px)' }}
                />
                <Button type="submit" variant="ghost" size="sm">搜</Button>
                {applied && (
                  <Button type="button" variant="quiet" size="sm"
                          onClick={() => { setKeyword(''); setApplied(''); setPage(1); }}>清空</Button>
                )}
              </form>
            </>
          }
          right={
            <Button variant="accent" onClick={() => setExportAsk(true)}>
              导出未激活的码（CSV）
            </Button>
          }
        />

        {state.loading ? <TableSkeleton rows={8} cols={5} /> : (
          <Async state={state}>
            {(d) => d.rows.length === 0 ? (
              filtering ? (
                <Empty
                  icon={<IconSearch size={22} />}
                  title="这个条件下一个码都没有"
                  desc="换个状态看看，或者把搜索词清掉。"
                  action={<Button variant="ghost" onClick={() => {
                    setFilter('ALL'); setKeyword(''); setApplied(''); setPage(1);
                  }}>看全部的码</Button>}
                />
              ) : (
                <Empty
                  icon={<IconTicket size={22} />}
                  title="你手上还没有激活码"
                  // 这句话原来只写了进货一条路。平台可以直接发码到代理名下，
                  // 那批码出现的时候他没有对应的付款记录 —— 空态里先把两条来路都说清楚，
                  // 比他日后自己撞见一批"凭空多出来的码"再来问要好。
                  desc="激活码来自两处：你自己进货，或平台直接发放到你名下。下一单付完款，码就会出现在这里。"
                  action={<Link to="/buy"><Button variant="accent">去进货</Button></Link>}
                />
              )
            ) : narrow ? (
              <div style={{ display: 'grid', gap: 'var(--s2)' }}>
                {d.rows.map(r => <CodeCard key={r.id} row={r} onReveal={() => openReveal(r)} />)}
                <Pager page={page} size={SIZE} total={d.total} onPage={setPage} busy={raw.loading} />
              </div>
            ) : (
              <>
                <Table cols={cols} rows={d.rows} rowKey={r => r.id} />
                <Pager page={page} size={SIZE} total={d.total} onPage={setPage} busy={raw.loading} />
              </>
            )}
          </Async>
        )}
      </Section>

      <RevealModal
        row={revealing}
        plain={plain}
        error={revealErr}
        refUrl={refUrl}
        onClose={() => {
          revealSeq.current++;   // 关掉之后再回来的响应不该再往界面上写
          setRevealing(null); setPlain(null); setRevealErr('');
        }}
        onRetry={() => revealing && openReveal(revealing)}
      />

      <Confirm
        open={exportAsk}
        title="导出未激活的码"
        confirmText="确认导出"
        loading={exporting}
        onClose={() => setExportAsk(false)}
        onConfirm={doExport}
        consequence={<>
          导出的文件里是<b>能直接激活的完整码</b>，谁拿到谁就能开课。别整份发到群里，
          也别转发给不需要的人。每一个被导出的码都会记一条查看日志。
        </>}
      >
        <div style={{ fontSize: 'var(--t-sm)', color: 'var(--ink-2)', lineHeight: 'var(--lh-body)' }}>
          导出范围：你名下<b>所有还没被激活</b>的码
          {applied ? <>（并且命中当前搜索词「{applied}」）</> : null}。
          已激活、已作废、已过期的不会出现在文件里。
          <br />
          文件是 CSV，Excel 和 WPS 都能直接打开。
        </div>
      </Confirm>
    </div>
  );
}

/** 激活期限。绝对日期看不出紧迫感，所以先写"还剩多久"，日期作为补充。 */
function DeadlineCell({ iso }: { iso: string }) {
  const days = Math.ceil((new Date(iso.replace(' ', 'T')).getTime() - Date.now()) / 86400000);
  const tone = days < 0 ? 'danger' : days <= 30 ? 'warn' : 'neutral';
  return (
    <span style={{ display: 'inline-flex', flexDirection: 'column', lineHeight: 1.3 }}>
      <span style={{
        color: tone === 'danger' ? 'var(--danger)' : tone === 'warn' ? 'var(--warn)' : 'var(--ink)',
        fontWeight: tone === 'neutral' ? 400 : 600,
      }}>{untilText(iso)}</span>
      {/* untilText 超过 30 天给的就是绝对日期，只有近期那档才需要补一行具体日子 */}
      {days >= 0 && days <= 30 && (
        <span className="num" style={{ fontSize: 'var(--t-xs)', color: 'var(--ink-3)' }}>
          {iso.slice(0, 10)}
        </span>
      )}
    </span>
  );
}

function CodeCard({ row, onReveal }: { row: CodeRow; onReveal: () => void }) {
  return (
    <Card style={{ padding: 'var(--s3) var(--s4)' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between',
                    alignItems: 'center', gap: 'var(--s3)' }}>
        <span className="num" style={{ fontSize: 'var(--t-h3)' }}>{row.masked}</span>
        <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--s2)' }}>
          {/* 窄屏走的是卡片，表格那一列整列都不出现 —— 手机上的代理会一条解释都看不到，
              而"码是哪来的"这件事恰恰是手机上最容易被问起的。只给白送的码加，
              自己买的那些不必每行重复一遍"这是你买的"。 */}
          {row.source === 'PLATFORM_GRANT' && <Chip>平台发放</Chip>}
          <StatusTag status={row.status} />
        </div>
      </div>
      <div style={{ fontSize: 'var(--t-xs)', color: 'var(--ink-3)', marginTop: 'var(--s2)' }}>
        {row.status === 'ISSUED'
          ? <>激活期限 {untilText(row.activateDeadline)}</>
          : row.status === 'BOUND'
            ? <>{row.boundPhone || '学员'} 于 {dateTime(row.boundAt)} 激活</>
            : <>{CODE_STATUS_HINT[row.status]}</>}
      </div>
      {row.status === 'ISSUED' && (
        <div style={{ marginTop: 'var(--s3)' }}>
          <Button size="sm" variant="ghost" block onClick={onReveal}>查看完整的码 / 生成邀请文案</Button>
        </div>
      )}
    </Card>
  );
}

/* ------------------------------------------------------------ 明文弹窗 */

function inviteText(code: string, row: CodeRow, refUrl: string | null): string {
  const lines: string[] = [];
  lines.push(`【${row.courseTitle || '课程'}】开课通知`);
  lines.push('');
  if (refUrl) lines.push(`1. 打开：${refUrl}`);
  else lines.push('1. 打开学员端网页');
  lines.push('2. 用你的手机号注册或登录');
  lines.push(`3. 填这个激活码：${code}`);
  lines.push('');
  if (row.accessMonths) lines.push(`激活后 ${row.accessMonths} 个月内可以随时回看。`);
  lines.push(`请在 ${row.activateDeadline?.slice(0, 10)} 之前激活，过期作废。`);
  lines.push('这个码只能一个人用，激活之后不能转给别人，也不退费。');
  return lines.join('\n');
}

function RevealModal({ row, plain, error, refUrl, onClose, onRetry }: {
  row: CodeRow | null; plain: string | null; error: string;
  refUrl: string | null; onClose: () => void; onRetry: () => void;
}) {
  if (!row) return null;
  return (
    <Modal open title="完整的激活码" onClose={onClose} width={520}
           footer={<Button variant="ghost" onClick={onClose}>关闭</Button>}>
      {error ? (
        <div style={{ display: 'grid', gap: 'var(--s4)', justifyItems: 'center',
                      padding: 'var(--s5) 0' }}>
          <div style={{ color: 'var(--danger)' }}>{error}</div>
          <Button variant="ghost" size="sm" onClick={onRetry}>重试</Button>
        </div>
      ) : plain === null ? (
        <div style={{ display: 'grid', gap: 'var(--s3)', justifyItems: 'center',
                      padding: 'var(--s6) 0', color: 'var(--ink-3)' }}>
          <Spinner size={20} color="var(--brand)" />
          <div style={{ fontSize: 'var(--t-sm)' }}>正在取出这个码</div>
        </div>
      ) : (
        <div style={{ display: 'grid', gap: 'var(--s4)' }}>
          <div style={{
            background: 'var(--surface-2)', border: '1px solid var(--line)',
            borderRadius: 'var(--r-md)', padding: 'var(--s5)', textAlign: 'center',
          }}>
            <div className="num" style={{
              fontSize: 'var(--t-h1)', fontWeight: 600, letterSpacing: '.06em',
              wordBreak: 'break-all', userSelect: 'all',
            }}>{plain}</div>
            <div style={{ marginTop: 'var(--s4)', display: 'flex', gap: 'var(--s2)',
                          justifyContent: 'center', flexWrap: 'wrap' }}>
              <CopyButton text={plain} label="复制这个码" variant="primary" size="md" />
              {/* 邀请文案只给还能用的码。已激活/已作废的码配上一段"填进去就能开课"的话，
                  代理一转发出去，收到的人照做一遍必然失败 —— 而失败的那一刻他面对的
                  不是平台，是发码给他的这个代理。 */}
              {row.status === 'ISSUED' && (
                <CopyButton
                  text={inviteText(plain, row, refUrl)}
                  label="复制整段邀请文案" variant="ghost" size="md"
                  done="邀请文案已复制，直接发给学员就行"
                />
              )}
            </div>
          </div>

          <div style={{ display: 'flex', gap: 'var(--s2)', flexWrap: 'wrap',
                        alignItems: 'center', fontSize: 'var(--t-sm)', color: 'var(--ink-2)' }}>
            <StatusTag status={row.status} />
            <Tag tone="neutral">激活期限 {row.activateDeadline?.slice(0, 10)}</Tag>
          </div>

          {row.status === 'ISSUED' ? (
            <div>
              <div style={{ fontSize: 'var(--t-sm)', color: 'var(--ink-3)',
                            marginBottom: 'var(--s2)' }}>发给学员的话术（可直接复制）</div>
              <pre style={{
                margin: 0, background: 'var(--surface-2)', border: '1px solid var(--line)',
                borderRadius: 'var(--r-md)', padding: 'var(--s4)', whiteSpace: 'pre-wrap',
                fontFamily: 'var(--font)', fontSize: 'var(--t-sm)', color: 'var(--ink-2)',
                lineHeight: 'var(--lh-body)',
              }}>{inviteText(plain, row, refUrl)}</pre>
            </div>
          ) : (
            <Note tone="warn" title="这个码已经不能再发给别人了">
              {row.status === 'BOUND'
                ? <>它在 {dateTime(row.boundAt)} 被 {row.boundPhone || '一位学员'} 激活了。
                    一码一人，激活之后不能改绑，也不退费。</>
                : CODE_STATUS_HINT[row.status]}
            </Note>
          )}

          {row.status === 'ISSUED' && (
            <Note tone="warn">
              这次查看已经记进日志了。码发出去之前请确认收件人 —— 谁先填进学员端，这门课就是谁的，
              激活之后不能改绑，也不退费。
            </Note>
          )}
        </div>
      )}
    </Modal>
  );
}
