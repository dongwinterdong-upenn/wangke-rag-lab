import React from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { api } from '@wangke/shared/api';
import { PageHead } from '@wangke/shared/shell';
import { Async, Button, Table, useAsync } from '@wangke/shared/ui';
import type { Col } from '@wangke/shared/ui';
import {
  ActionDialog, AuditTrail, Blank, Board, Ctl, DetailModal, DetailSkeleton, Filters, KV, Money,
  Note, Pager, PhoneReveal, Pick, ReasonBox, Sect, St, TextIn,
} from '../lib/kit';
import { fmtTime, label, num, options } from '../lib/fmt';
import { hasFilter, qs, useQ } from '../lib/q';

type Agent = Record<string, any>;

export function Agents() {
  const { sp, get, int, set, reset } = useQ();
  const status = get('status'), level = get('level'), keyword = get('keyword');
  const page = int('page', 1), size = int('size', 20);
  const sel = get('sel');
  const [rev, bump] = React.useReducer((n: number) => n + 1, 0);

  const list = useAsync<any>(
    () => api.get(`/api/admin/agents${qs({ status, level, keyword, page, size })}`),
    [status, level, keyword, page, size, rev]);

  const filtered = hasFilter(sp);

  const cols: Col<Agent>[] = [
    { key: 'id', title: 'ID', numeric: true, width: 60 },
    { key: 'agentCode', title: '推广码', render: r => <span className="num">{r.agentCode}</span> },
    { key: 'level', title: '层级', render: r => <St domain="level" value={String(r.level)} /> },
    { key: 'who', title: '主体', render: r => (
      <div style={{ minWidth: 160 }}>
        <div>{r.companyName || r.realName || r.nickname || '（未填主体名称）'}</div>
        <div style={{ color: 'var(--ink-3)', fontSize: 'var(--t-xs)' }}>
          {label('subject', r.subjectType)}
          {r.contactName ? ` · 联系人 ${r.contactName}` : ''}
        </div>
      </div>
    ) },
    { key: 'phoneMasked', title: '手机号', render: r => <span className="num">{r.phoneMasked}</span> },
    { key: 'parentAgentId', title: '上级', numeric: true,
      render: r => r.parentAgentId ? <span className="num">#{r.parentAgentId}</span>
                                    : <span style={{ color: 'var(--ink-3)' }}>无</span> },
    { key: 'status', title: '状态', render: r => <St domain="agent" value={r.status} /> },
    { key: 'userStatus', title: '账号', render: r => <St domain="user" value={r.userStatus} /> },
    { key: 'createdAt', title: '申请时间', render: r => fmtTime(r.createdAt) },
  ];

  return (
    <>
      <PageHead title="代理管理"
        desc="开户审批、停用与层级调整。分销层级硬锁两级，这条由数据库强制，后台改不动。" />

      <Filters active={filtered} onReset={() => reset()}>
        <Ctl label="状态">
          <Pick value={status} onChange={v => set({ status: v })} options={options('agent')} />
        </Ctl>
        <Ctl label="层级" width={120}>
          <Pick value={level} onChange={v => set({ level: v })}
                options={[{ value: '1', text: '一级' }, { value: '2', text: '二级' }]} />
        </Ctl>
        <Ctl label="搜索" width={240} grow
             hint="手机号 / 推广码 / 公司名 / 联系人，任意一项包含即可">
          <TextIn defaultValue={keyword} placeholder="输入后回车"
                  onKeyDown={e => { if (e.key === 'Enter') set({ keyword: e.currentTarget.value }); }}
                  onBlur={e => { if (e.currentTarget.value !== keyword) set({ keyword: e.currentTarget.value }); }} />
        </Ctl>
      </Filters>

      <Board flush title={`代理列表${list.data ? ` · ${list.data.total} 家` : ''}`}>
        <Async state={list}>{(d: any) => (
          <>
            <Table cols={cols} rows={d.items} rowKey={r => r.id}
                   onRowClick={r => set({ sel: r.id }, true)}
                   empty={<Blank filtered={filtered} thing="代理"
                                 onClear={() => reset()}
                                 action={<span style={{ fontSize: 'var(--t-sm)' }}>
                                   代理开户由平台执行：一级代理提交推荐后会出现在这里等你审批。
                                 </span>} />} />
            <Pager total={num(d.total)} page={page} size={size}
                   onPage={p => set({ page: p }, true)} onSize={s => set({ size: s })} />
          </>
        )}</Async>
      </Board>

      {sel && <AgentDetail id={sel} onClose={() => set({ sel: '' }, true)} onChanged={bump} />}
    </>
  );
}

/* ------------------------------------------------------------------ 详情 */

function AgentDetail({ id, onClose, onChanged }: {
  id: string; onClose: () => void; onChanged: () => void;
}) {
  const [rev, bump] = React.useReducer((n: number) => n + 1, 0);
  const state = useAsync<any>(() => api.get(`/api/admin/agents/${id}`), [id, rev]);
  const [dlg, setDlg] = React.useState<
    null | 'approve' | 'reject' | 'suspend' | 'resume' | 'level' | 'grant'>(null);

  const a = state.data?.agent;
  const after = () => { bump(); onChanged(); };

  return (
    <DetailModal open onClose={onClose}
      title={a ? (a.companyName || a.realName || a.nickname || `代理 #${a.id}`) : `代理 #${id}`}
      subtitle={a ? <>推广码 <span className="num">{a.agentCode}</span> · {label('level', String(a.level))} · 申请于 {fmtTime(a.createdAt)}</> : undefined}
      actions={a && (
        <>
          {a.status === 'PENDING' && <>
            <Button variant="ghost" onClick={() => setDlg('reject')}>驳回</Button>
            <Button onClick={() => setDlg('approve')}>通过审批</Button>
          </>}
          {/* 发码只挂在 ACTIVE 上：ManualIssueService 对非 ACTIVE 的代理直接 409，
              而一颗「点了必定报错」的按钮比没有这颗按钮更糟 —— 运营会以为系统坏了，
              然后去别处找路（这个后台已经踩过一次）。 */}
          {a.status === 'ACTIVE' && <>
            <Button variant="ghost" onClick={() => setDlg('grant')}>发放激活码</Button>
            <Button variant="ghost" onClick={() => setDlg('level')}>调整层级</Button>
            <Button variant="danger" onClick={() => setDlg('suspend')}>停用</Button>
          </>}
          {a.status === 'SUSPENDED' && <Button onClick={() => setDlg('resume')}>恢复合作</Button>}
        </>
      )}>
      <Async state={state}>{(d: any) => {
        const g = d.agent;
        return (
          <div>
            {g.status === 'PENDING' && (
              <Note tone="warn" title="审批之前请先看完这三块">
                主体资料（是不是个体工商户/企业）、推荐关系链（他挂在谁下面）、以及这个手机号
                是不是已经作为学员在别的代理名下。通过之后他就能按层级价进货了。
              </Note>
            )}

            <Sect title="申请人资料">
              <KV items={[
                ['代理 ID', <span className="num">#{g.id}</span>],
                ['账号 ID', <span className="num">#{g.userId}</span>],
                ['推广码', <span className="num">{g.agentCode}</span>],
                ['层级', <St domain="level" value={String(g.level)} />],
                ['主体类型', <>
                  <St domain="subject" value={g.subjectType} />
                  {g.subjectType === 'INDIVIDUAL' && (
                    <span style={{ color: 'var(--ink-3)', fontSize: 'var(--t-xs)', marginLeft: 'var(--s2)' }}>
                      个人主体开不了票、代扣个税也要平台承担，按约定应推荐个体工商户或企业
                    </span>
                  )}
                </>],
                ['公司名称', g.companyName],
                ['税号', g.taxNo ? <span className="num">{g.taxNo}</span> : null],
                ['联系人', g.contactName],
                ['实名', g.realName],
                ['昵称', g.nickname],
                ['手机号', <PhoneReveal userId={g.userId} masked={g.phoneMasked} />],
                ['身份证', g.idCardMasked ? <span className="num">{g.idCardMasked}</span> : null],
                ['结算银行', g.settleBankName],
                ['结算账号', g.settleBankAcctMasked
                  ? <span className="num">{g.settleBankAcctMasked}</span> : null],
                ['账号状态', <St domain="user" value={g.userStatus} />],
                ['代理状态', <St domain="agent" value={g.status} />],
                ['最近登录', fmtTime(g.lastLoginAt)],
                ['审批人 / 时间', g.approvedBy
                  ? <>#{g.approvedBy} · {fmtTime(g.approvedAt)}</> : null],
              ]} />
              {g.settleBankAcctMasked && (
                <div style={{ marginTop: 'var(--s3)' }}>
                  <Note>
                    结算账号只出后 4 位。真正打款要用的完整账号在提现单上（那一步会留痕）——
                    这一页天天开着，不该常驻一个能直接拿去用的银行账号。
                  </Note>
                </div>
              )}
            </Sect>

            <Sect title="推荐关系链">
              <Chain agent={g} />
            </Sect>

            <Sect title="业务体检">
              <Health agentId={g.id} detail={g} />
            </Sect>

            <Sect title="改动记录" extra={<span style={{ fontSize: 'var(--t-xs)', color: 'var(--ink-3)' }}>
              来自审计日志，只读
            </span>}>
              <AuditTrail targetType="agent_profile" targetId={g.id} refreshKey={rev} />
            </Sect>
          </div>
        );
      }}</Async>

      {a && <Dialogs which={dlg} close={() => setDlg(null)} agent={a} onDone={after} />}
      {a && <GrantCodes open={dlg === 'grant'} agent={a}
                        onClose={() => setDlg(null)} onDone={after} />}
    </DetailModal>
  );
}

/** 上级 / 下级。列表接口没有「按上级筛选」，所以下级只能给出家数，不能给出名单。 */
function Chain({ agent }: { agent: any }) {
  const parent = useAsync<any>(
    () => agent.parentAgentId ? api.get(`/api/admin/agents/${agent.parentAgentId}`) : Promise.resolve(null),
    [agent.parentAgentId]);

  return (
    <div style={{ display: 'grid', gap: 'var(--s3)' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--s3)', flexWrap: 'wrap' }}>
        <Box title="上级">
          {agent.level === 1 ? (
            <span style={{ color: 'var(--ink-3)' }}>一级代理没有上级</span>
          ) : parent.loading ? '读取中…'
            : parent.error ? <span style={{ color: 'var(--danger)' }}>上级读取失败</span>
            : parent.data?.agent ? (
              <>
                <div>{parent.data.agent.companyName || parent.data.agent.realName || `代理 #${parent.data.agent.id}`}</div>
                <div className="num" style={{ fontSize: 'var(--t-xs)', color: 'var(--ink-3)' }}>
                  #{parent.data.agent.id} · {parent.data.agent.agentCode}
                </div>
              </>
            ) : <span style={{ color: 'var(--danger)' }}>挂着一个查不到的上级 #{agent.parentAgentId}</span>}
        </Box>
        <span style={{ color: 'var(--ink-3)' }}>→</span>
        <Box title="本代理" highlight>
          <div>{agent.companyName || agent.realName || `代理 #${agent.id}`}</div>
          <div className="num" style={{ fontSize: 'var(--t-xs)', color: 'var(--ink-3)' }}>
            #{agent.id} · {agent.agentCode}
          </div>
        </Box>
        <span style={{ color: 'var(--ink-3)' }}>→</span>
        <Box title="下级">
          <div className="num">{agent.subAgentCount ?? 0} 家</div>
          <div style={{ fontSize: 'var(--t-xs)', color: 'var(--ink-3)' }}>
            {agent.level === 2 ? '二级代理不能再有下级' : '名单需按上级筛选，列表接口暂不支持'}
          </div>
        </Box>
      </div>
      <Note>
        层级最多两级，由数据库强制：一级不能有上级，二级必须挂在一个存在的一级下面，
        已经有下级的一级不能降级。后台改层级时踩到这几条会被当场拒绝。
      </Note>
    </div>
  );
}

function Box({ title, children, highlight }: {
  title: string; children: React.ReactNode; highlight?: boolean;
}) {
  return (
    <div style={{
      flex: '1 1 180px', minWidth: 0, padding: 'var(--s3)', borderRadius: 'var(--r-md)',
      border: `1px solid ${highlight ? 'var(--brand-line)' : 'var(--line)'}`,
      background: highlight ? 'var(--brand-soft)' : 'var(--surface-2)',
      fontSize: 'var(--t-sm)',
    }}>
      <div style={{ fontSize: 'var(--t-xs)', color: 'var(--ink-3)', marginBottom: 'var(--s1)' }}>{title}</div>
      {children}
    </div>
  );
}

/** 归属学员数与成交额都是审批时的真判据：光看资料看不出他有没有在做事。 */
function Health({ agentId, detail }: { agentId: number; detail: any }) {
  const students = useAsync<any>(
    () => api.get(`/api/admin/users${qs({ attributedAgentId: agentId, size: 1 })}`), [agentId]);
  const orders = useAsync<any>(
    () => api.get(`/api/admin/orders${qs({ agentId, size: 1 })}`), [agentId]);

  return (
    <div style={{ display: 'grid', gap: 'var(--s3)',
                  gridTemplateColumns: 'repeat(auto-fill, minmax(160px, 1fr))' }}>
      <Tile label="相关订单">{orders.loading ? '…' : orders.error ? '取不到'
        : <span className="num">{num(orders.data?.total)} 单</span>}</Tile>
      <Tile label="累计实付">{orders.loading ? '…' : orders.error ? '取不到'
        : <Money fen={orders.data?.sumPaidFen} />}</Tile>
      {/* 「相关订单」里现在混着平台白送的单（它们实付恒 0）。不把这一格摆出来的话，
          一家只收到过赠码的代理在这里是「3 单 · 累计实付 0 元」—— 两个数都对，
          读起来却像数据坏了。金额直接用列表接口按同一套筛选算出来的 sumGiftAmountFen，
          不另发请求，也就不会有「两处各查一遍、数字对不上」那种事。 */}
      <Tile label="其中平台发放">{orders.loading ? '…' : orders.error ? '取不到'
        : <Money fen={orders.data?.sumGiftAmountFen} />}</Tile>
      <Tile label="归属学员">{students.loading ? '…' : students.error ? '取不到'
        : <span className="num">{num(students.data?.total)} 人</span>}</Tile>
      <Tile label="可提现佣金"><Money fen={detail.commissionBalanceFen} /></Tile>
      <Tile label="下级代理"><span className="num">{detail.subAgentCount ?? 0} 家</span></Tile>
      <Tile label="进货单数"><span className="num">{detail.orderCount ?? 0} 单</span></Tile>
    </div>
  );
}

function Tile({ label: lb, children }: { label: string; children: React.ReactNode }) {
  return (
    <div style={{ padding: 'var(--s3)', background: 'var(--surface-2)', borderRadius: 'var(--r-md)' }}>
      <div style={{ fontSize: 'var(--t-xs)', color: 'var(--ink-3)' }}>{lb}</div>
      <div style={{ fontSize: 'var(--t-h3)', fontWeight: 600, marginTop: 'var(--s1)' }}>{children}</div>
    </div>
  );
}

/* ------------------------------------------------------------------ 动作 */

function Dialogs({ which, close, agent, onDone }: {
  which: string | null; close: () => void; agent: any; onDone: () => void;
}) {
  const [reason, setReason] = React.useState('');
  const [level, setLevel] = React.useState(String(agent.level));
  const [parent, setParent] = React.useState(agent.parentAgentId ? String(agent.parentAgentId) : '');

  React.useEffect(() => {
    setReason('');
    setLevel(String(agent.level));
    setParent(agent.parentAgentId ? String(agent.parentAgentId) : '');
  }, [which, agent]);

  const id = agent.id;

  return (
    <>
      <ActionDialog open={which === 'approve'} onClose={close} verb="通过" onDone={onDone}
        title="通过这家代理的开户审批"
        consequences={<>
          通过之后，他<b>立刻</b>可以按 {label('level', String(agent.level))} 的层级价下单进货。
          层级与上级按现在的设置生效 —— 要改层级请先改完再批。
          这一步可以撤（改成停用），但他在停用之前进的货、发出去的码都算数。
        </>}
        onConfirm={() => api.post(`/api/admin/agents/${id}/approve`)} />

      <ActionDialog open={which === 'reject'} onClose={close} verb="驳回" tone="danger" onDone={onDone}
        title="驳回这家代理的开户申请"
        disabled={!reason.trim()}
        consequences={<>
          驳回之后这条申请会落到<b>「已终止」</b>，<b>不能再改回待审批</b>。
          他要重新合作，需要重新走一次开户。<br />
          驳回和停用是两个不同的动作：驳回的是还没开始的合作，停用的是已经在跑、要暂停的合作 ——
          混着用，事后在对账和结算上分不出来。
        </>}
        onConfirm={() => api.post(`/api/admin/agents/${id}/reject`, { reason: reason.trim() })}>
        <ReasonBox value={reason} onChange={setReason} label="驳回理由"
                   hint="这条会回给对方，也会进审计日志。写清楚缺什么、怎么补。" />
      </ActionDialog>

      <ActionDialog open={which === 'suspend'} onClose={close} verb="停用" tone="danger" onDone={onDone}
        title="停用这家代理"
        disabled={!reason.trim()}
        consequences={<>
          停用之后他<b>不能再下单进货</b>。已经发出去的激活码<b>不受影响</b> ——
          学员已经付过钱，停代理不该连带停学员的课。<br />
          <b>在途的提现单不会被自动拦下</b>，需要你到提现审批里逐笔处理。
          停用可以恢复。
        </>}
        onConfirm={() => api.post(`/api/admin/agents/${id}/suspend`, { reason: reason.trim() })}>
        <ReasonBox value={reason} onChange={setReason} label="停用理由" />
      </ActionDialog>

      <ActionDialog open={which === 'resume'} onClose={close} verb="恢复" onDone={onDone}
        title="恢复这家代理的合作"
        consequences={<>恢复之后他可以重新按层级价下单进货。停用期间的记录不会变。</>}
        onConfirm={() => api.post(`/api/admin/agents/${id}/resume`)} />

      <ActionDialog open={which === 'level'} onClose={close} verb="调整" onDone={onDone}
        title="调整层级与上级"
        disabled={level === '2' && !parent.trim()}
        consequences={<>
          层级决定他的<b>进货价</b>，以及他的上级能拿多少返佣。<br />
          <b>已成交的订单不会跟着变</b> —— 订单里存的是下单当时的价与佣金快照，这是有意的。<br />
          数据库会挡住三种改法：一级不能有上级；二级必须挂在一个存在的一级下面；
          已经有下级的一级不能降为二级（要降先把下级转挂走）。
        </>}
        onConfirm={() => api.post(`/api/admin/agents/${id}/level`, {
          level: Number(level),
          parentAgentId: level === '2' ? Number(parent) : null,
          reason: reason.trim() || undefined,
        })}>
        <div style={{ display: 'grid', gap: 'var(--s4)' }}>
          <Ctl label="目标层级" width={200}>
            <Pick value={level} all={false} onChange={setLevel}
                  options={[{ value: '1', text: '一级代理（直接与平台结算）' },
                            { value: '2', text: '二级代理（挂在某个一级下面）' }]} />
          </Ctl>
          {level === '2' && (
            <Ctl label="上级代理 ID" width={200}
                 hint="填一级代理的代理 ID（列表里的第一列）。填错会被数据库当场拒绝。">
              <TextIn value={parent} inputMode="numeric"
                      onChange={e => setParent(e.target.value.replace(/\D/g, ''))} />
            </Ctl>
          )}
          <ReasonBox value={reason} onChange={setReason} label="调整说明（可不填，但建议写）" />
        </div>
      </ActionDialog>
    </>
  );
}

/* -------------------------------------------------------------- 手工发码 */

/**
 * 可发放的代理档套餐。
 *
 * <p><b>写死是查过的，不是偷懒。</b>后台一个 SKU 列表接口都没有（admin 包下没有），
 * 而公开的 {@code GET /api/public/skus} 对 {@code buyerType=AGENT} <b>有意回 403</b>
 * （代理进货价不对没登录的人出）；它肯给的 STUDENT 档拿来发码会被
 * ManualIssueService 当场拒绝（「这个套餐不是代理档」）。两条路都不通。
 *
 * <p>档位与每份码数<b>从 /api/admin/grant-skus 现取，不写死在这里</b>。写死的话
 * skuId 抄错是响的（后端当场拒），而 <b>codeQty 抄错是哑的</b>：下面那句
 * 「N 份 × 每份 M 个 = X 个码」和确认按钮上的数全由它算，真正发几个却以库里的
 * {@code sku.code_qty} 为准 —— 有人把 AGENT_10 从 10 调成 12，运营看到「发 20 个」、
 * 实际发出去 24 个，两边都不报错，而这个数正是他按下按钮时唯一的依据。
 * 上限 maxCopies / maxCodes 同理，一并由后端出，不在前端各抄一份。
 *
 * <p><b>价格一个字都不出网、也不写在这里。</b>这份代码会被打进 {@code /admin/assets/*.js}，
 * 那个包无需登录就能下载（同 fmt.ts 里 fenFromYuan 那段说明）。应付金额由后端
 * 按这家代理的层级取现行价目，发完在订单页上看得到。
 */
type GrantSku = { skuId: number; codeQty: number; name: string; skuCode: string };

/**
 * 给这家代理白送一批激活码。
 *
 * <p>成功之后<b>不在这个框里画码</b>：ActionDialog 的 go() 在 onDone 之后无条件 onClose()，
 * 画在 children 里的掩码或明文只会一闪而过 —— 运营再也找不到那批码，于是他会再发一次。
 * 正确的落点是订单页：跳过去按这次的 grantKey 筛出来，在那儿导出明文。
 */
function GrantCodes({ open, agent, onClose, onDone }: {
  open: boolean; agent: any; onClose: () => void; onDone: () => void;
}) {
  const nav = useNavigate();
  const [skuId, setSkuId] = React.useState('');
  const [copies, setCopies] = React.useState('1');
  const [reason, setReason] = React.useState('');

  // 目录只在框打开时取一次。取不回来时不给默认档兜底 —— 拿一个猜的 codeQty
  // 去算「发 X 个码」，比让他看见「取不到套餐」要坏得多。
  const cat = useAsync<any>(
    () => (open ? api.get('/api/admin/grant-skus') : Promise.resolve(null)), [open]);
  const skus: GrantSku[] = cat.data?.skus ?? [];
  const MAX_COPIES = num(cat.data?.maxCopies, 0);
  const MAX_CODES = num(cat.data?.maxCodes, 0);

  /**
   * ★ 幂等键在对话框<b>打开时</b>生成一次，关框才换。
   *
   * 写成「每次点确认现生成一个」的话，后端 uk_grant_key 那道防线等于不存在：
   * 运营在慢网络下双击一次，就是两批码真的发了出去，而<b>界面上两次都显示成功</b>
   * （第二次也确实成功了，它是另一次发放）。事后没有任何东西能判定这两批是同一次意图 ——
   * 两批单号、两批码，在库里、在代理手上、在对账表上全都是真的。
   *
   * 反过来，报错之后<b>不换键</b>正是要的：同一个键重试只会把第一次的结果查回来。
   */
  const key = React.useRef('');

  React.useEffect(() => {
    if (!open) { key.current = ''; return; }
    key.current = crypto.randomUUID();
    setSkuId(''); setCopies('1'); setReason('');
  }, [open]);

  // 目录回来之后再选中第一档。放在上面那个 effect 里的话，选中的是上一次的档 ——
  // 而两次之间目录可能已经变了。
  React.useEffect(() => {
    if (skus.length && !skus.some(x => String(x.skuId) === skuId))
      setSkuId(String(skus[0].skuId));
  }, [skus, skuId]);

  const sku = skus.find(x => String(x.skuId) === skuId);
  const c = Number(copies || 0);
  // sku 未取到时 codes 是 0，而不是拿某个猜来的每份码数算出来的假数
  const codes = sku ? c * sku.codeQty : 0;
  const okCopies = !!sku && MAX_COPIES > 0
    && Number.isInteger(c) && c >= 1 && c <= MAX_COPIES;
  const tooMany = MAX_CODES > 0 && codes > MAX_CODES;
  const who = agent.companyName || agent.realName || agent.nickname || `代理 #${agent.id}`;

  return (
    <ActionDialog open={open} onClose={onClose} verb="发放" onDone={(r: any) => {
      onDone();
      // 明文与掩码都在订单页上看，这里只把人送过去。grantKey 用后端回的那个：
      // 它是这一批单真正挂着的键，前端的 ref 只是它的来源。
      nav(`/orders${qs({ grantKey: r?.grantKey || key.current })}`);
    }}
      title="给这家代理发放激活码"
      width={560}
      confirmLabel={sku ? `确认发放 ${codes} 个码` : '确认发放'}
      disabled={!sku || !reason.trim() || reason.length > 255 || !okCopies || tooMany}
      consequences={<>
        会给 <b>{who}</b>（推广码 <span className="num">{agent.agentCode}</span> ·
        代理 <span className="num">#{agent.id}</span>）新建 <b>{okCopies ? c : '—'}</b> 张
        <b>不收钱</b>的订单：应付记这家代理层级的目录价、<b>实付 0 元</b>、没有到账时间
        （具体金额发完在订单页上看得到）。<br />
        这些单<b>不计入成交额、不给上级结佣金、也开不出发票</b> ——
        三条都是数据库强制的，不是这个界面的承诺。<br />
        发出的 <b>{okCopies && !tooMany ? codes : '—'}</b> 个码会<b>立刻</b>出现在该代理的
        「我的激活码」里，他可以马上发给学员。<br />
        还没被激活的码可以到订单详情里<b>整单作废</b>；
        <b>一旦被学员激活就撤不回来</b> —— 学员的归属代理会在那一刻永久锁给他。
      </>}
      onConfirm={() => api.post('/api/admin/code-grants', {
        agentId: agent.id, skuId: Number(skuId), copies: c,
        reason: reason.trim(), grantKey: key.current,
      })}>
      <div style={{ display: 'grid', gap: 'var(--s4)' }}>
        {cat.error && (
          <Note tone="danger" title="套餐目录没取到">
            每份出几个码要从库里取，取不到就没法告诉你这一次会发出去多少个码。
            关掉重开再试；一直不行先别发，去看后端是不是挂了。
          </Note>
        )}

        <Ctl label="套餐" width={320} grow
             hint="只有代理档能发给代理。学员直购那一档会被后端当场拒绝。">
          {/* 每份出几个码摆在选项里：套餐名说的是「批量」，而运营要算的是「几个码」。
              把这个数留给他自己去猜，猜错的方向永远是发多了。 */}
          <Pick value={skuId} all={false} onChange={setSkuId}
                options={skus.map(x => ({
                  value: String(x.skuId),
                  text: `${x.name}（本单出 ${x.codeQty} 个码）`,
                }))} />
        </Ctl>

        <Ctl label="发几份" width={160}
             hint={!sku
               ? '套餐没取到，先解决上面那条'
               : !okCopies
                 ? `一次 1 到 ${MAX_COPIES} 份，超过请分多次发放`
                 : tooMany
                   ? `本次 ${c} × ${sku.codeQty} = ${codes} 个码，超过单次上限 ${MAX_CODES} 个`
                   : <>{c} 份 × 每份 {sku.codeQty} 个 = <b>{codes} 个码</b>（会建 {c} 张订单）</>}>
          <TextIn value={copies} inputMode="numeric"
                  onChange={e => setCopies(e.target.value.replace(/\D/g, ''))} />
        </Ctl>

        <ReasonBox value={reason} onChange={setReason} label="发放原因"
                   hint="事后有人问「这批码是怎么来的」，只有这一句能答。它会同时写进订单和审计日志。" />
        {reason.length > 255 && (
          <Note tone="danger" title="原因太长">
            已经 {reason.length} 个字，上限 255。超了会被后端拒绝，这一批一个码都发不出去。
          </Note>
        )}
      </div>
    </ActionDialog>
  );
}
