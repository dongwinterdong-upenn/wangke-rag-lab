import React from 'react';
import { Link } from 'react-router-dom';
import { api } from '@wangke/shared/api';
import { PageHead } from '@wangke/shared/shell';
import { Async, Button, Table, useAsync } from '@wangke/shared/ui';
import type { Col } from '@wangke/shared/ui';
import {
  ActionDialog, Blank, Board, Ctl, Filters, KV, Money, Note, Pager, Pick, ReasonBox, Sect, St,
  TextIn,
} from '../lib/kit';
import { downloadCsv, fmtDate, fmtTime, label, num, stamp } from '../lib/fmt';
import { hasFilter, qs, useQ } from '../lib/q';

/**
 * 超管一次生成一批<b>学员</b>激活码，归属可选。
 *
 * <h3>这一页和「给代理发码」不是一件事</h3>
 * 代理管理页那颗「发放激活码」发的是<b>代理档</b>的码：进的是那家代理的库存，他再转售。
 * 这里发的是<b>学员档</b>的码，平台自己拿去做活动、补偿、开内部账号，谁激活就是谁的。
 * 两页的入口分开、词面分开，是因为它们的钱与归属口径完全不同 ——
 * 合成一页之后运营只会在两个下拉之间猜，而猜错的方向永远是「码发给了不该拿的人」。
 *
 * <h3>「不归属」是这一页的主语</h3>
 * 默认不归属 = 平台直营：学员激活后不计入任何代理业绩、不结佣金。选了代理才归属。
 * 界面上必须把这句话写在<b>动手之前</b>，因为它的错法是不可逆的 ——
 * 归属一旦随激活落定就是 first-touch，改不回来。
 */
export function StudentCodes() {
  const { sp, get, int, set, reset } = useQ();
  const owner = get('owner');
  const page = int('page', 1), size = int('size', 20);
  const [open, setOpen] = React.useState(false);
  /** 刚生成的那一批。横幅上那颗「导出明文」是这一页存在的理由，不能等他自己去找。 */
  const [fresh, setFresh] = React.useState<any>(null);
  const [tick, setTick] = React.useState(0);

  const list = useAsync<any>(
    () => api.get(`/api/admin/student-code-batches${qs({ owner, page, size })}`),
    [owner, page, size, tick]);

  // 代理目录只为「归属」那个下拉服务，取 ACTIVE 的那些 —— 停用的代理后端会当场拒，
  // 摆在下拉里只会让人选完才吃到错
  const agents = useAsync<any>(
    () => api.get('/api/admin/agents?status=ACTIVE&size=100'), []);

  const filtered = hasFilter(sp, ['page', 'size']);

  const cols: Col<any>[] = [
    { key: 'createdAt', title: '生成时间', render: r => (
      <span className="num" style={{ fontSize: 'var(--t-xs)' }}>{fmtTime(r.createdAt)}</span>) },
    {
      key: 'ownerAgentId', title: '归属', render: r => (r.ownerAgentId
        // 「平台直营」四个字必须写出来，不能留一个「—」。破折号读起来是「这里没数据」，
        // 而它其实是一个被明确选择过的、有后果的状态
        ? <Link to={`/agents${qs({ sel: r.ownerAgentId })}`} onClick={e => e.stopPropagation()}>
            {r.ownerName || `代理 #${r.ownerAgentId}`}
            <span className="num" style={{ color: 'var(--ink-3)' }}> · {r.ownerAgentCode}</span>
          </Link>
        : <span style={{ color: 'var(--ink-2)' }}>平台直营（不归属）</span>),
    },
    { key: 'qty', title: '码数', numeric: true, render: r => (
      <span className="num">{num(r.issued)}</span>) },
    { key: 'bound', title: '已激活', numeric: true, render: r => (
      <span className="num" style={{ color: num(r.bound) ? 'var(--ok)' : 'var(--ink-3)' }}>
        {num(r.bound)}
      </span>) },
    { key: 'revoked', title: '已作废', numeric: true, render: r => (
      <span className="num" style={{ color: num(r.revoked) ? 'var(--danger)' : 'var(--ink-3)' }}>
        {num(r.revoked)}
      </span>) },
    // 「送出去多少钱的货」这本账。实付永远是 0，所以这一列写的是应付（目录价 × 码数）
    { key: 'amountFen', title: '目录价合计', numeric: true, render: r => <Money fen={r.amountFen} /> },
    { key: 'reason', title: '生成原因', render: r => (
      <span style={{ fontSize: 'var(--t-xs)', color: 'var(--ink-2)' }}>{r.reason || '—'}</span>) },
    { key: 'orderNo', title: '合成订单', render: r => (
      <Link to={`/orders${qs({ orderNo: r.orderNo })}`} onClick={e => e.stopPropagation()}
            className="num" style={{ fontSize: 'var(--t-xs)' }}>{r.orderNo}</Link>) },
    { key: 'act', title: '', render: r => <BatchActions batch={r} /> },
  ];

  return (
    <>
      <PageHead title="学员激活码"
        desc="平台自己生成一批学员激活码：做活动、补偿、开内部账号。默认不归属任何代理（平台直营），需要时可以指定归属一家。"
        extra={<Button onClick={() => setOpen(true)}>生成一批</Button>} />

      <Note tone="info" title="这些码和卖出去的码是同一种码，只是没有人付过钱">
        每一批会建一张<b>不收钱</b>的合成订单：应付记学员直购目录价 × 码数、
        <b>实付 0 元</b>、没有到账时间。它<b>不计入成交额、不结佣金、也开不出发票</b> ——
        三条都是数据库强制的，不是这个界面的承诺。<br />
        <b>不归属</b>的码谁激活都不算任何代理的业绩；<b>指定了代理</b>的码，
        激活它的学员会被算成那家代理带来的，而这件事<b>一旦发生就改不回来</b>。
      </Note>

      <div style={{ marginTop: 'var(--s4)' }}>
        {fresh && <FreshBanner batch={fresh} onClear={() => setFresh(null)} />}

        <Filters active={filtered} onReset={() => reset(['size'])}>
          <Ctl label="归属" width={220}
               hint="「平台直营」= 这批码不算任何代理的业绩">
            <Pick value={owner} onChange={v => set({ owner: v })}
                  options={[
                    { value: 'platform', text: '平台直营（不归属）' },
                    ...((agents.data?.items ?? []) as any[]).map(a => ({
                      value: String(a.id),
                      text: `${a.companyName || a.realName || a.nickname || `代理 #${a.id}`}（${a.agentCode}）`,
                    })),
                  ]} />
          </Ctl>
        </Filters>

        <Board title="已经生成过的批次"
          desc="一批 = 一张合成订单 = 一次导出。运营事后查「这批码是怎么来的」就在这一页。">
          <Async state={list}
            empty={<Blank filtered={filtered} thing="批次"
                          action={filtered ? undefined : '点右上角「生成一批」'}
                          onClear={() => reset(['size'])} />}>
            {(d: any) => (
              <>
                <Table cols={cols} rows={d.items ?? []} rowKey={(r: any) => r.orderId} />
                <Pager total={num(d.total)} page={page} size={size}
                       onPage={p => set({ page: p })} onSize={s => set({ size: s, page: 1 })} />
              </>
            )}
          </Async>
        </Board>
      </div>

      <NewBatch open={open} onClose={() => setOpen(false)}
        agents={(agents.data?.items ?? []) as any[]}
        onDone={(r: any) => { setFresh(r); setTick(t => t + 1); }} />
    </>
  );
}

/* ------------------------------------------------------------ 生成一批 */

/**
 * 生成对话框。
 *
 * <p>成功之后<b>不在这个框里画码</b>：ActionDialog 的 go() 在 onDone 之后无条件 onClose()，
 * 画在 children 里的掩码只会一闪而过 —— 运营再也找不到那批码，于是他会再生成一次。
 * 落点是外面那条横幅：它带着这一批的订单号和「导出明文」。
 */
function NewBatch({ open, onClose, agents, onDone }: {
  open: boolean; onClose: () => void; agents: any[]; onDone: (r: any) => void;
}) {
  const [qty, setQty] = React.useState('10');
  const [owner, setOwner] = React.useState('');       // '' = 不归属
  const [reason, setReason] = React.useState('');

  // 上限与目录价都从后端现取，不在前端各抄一份。
  // 上限抄错是响的（点下去才吃到后端那句话，而两个数字之间那段区间正是他会反复重试的地方）；
  // 单价抄错是哑的：下面那句「本批记 X 元的货」会和订单上的金额不一致，
  // 而运营正是拿这个数去判断「这一批送不送得起」的。
  const meta = useAsync<any>(
    () => (open ? api.get('/api/admin/student-code-batches/meta') : Promise.resolve(null)), [open]);
  const m = meta.data?.meta;
  const MAX = num(m?.maxBatch, 0);
  const unit: number | null = m?.unitPriceFen == null ? null : num(m.unitPriceFen);

  /**
   * ★ 幂等键在对话框<b>打开时</b>生成一次，关框才换。
   *
   * 写成「每次点确认现生成一个」的话，后端 uk_grant_key 那道防线等于不存在：
   * 运营在慢网络下双击一次，就是两批码真的发了出去，而<b>界面上两次都显示成功</b>
   * （第二次也确实成功了，它是另一批）。事后没有任何东西能判定这两批是同一次意图。
   *
   * 反过来，报错之后<b>不换键</b>正是要的：同一个键重试只会把第一次的结果查回来。
   */
  const key = React.useRef('');
  React.useEffect(() => {
    if (!open) { key.current = ''; return; }
    key.current = crypto.randomUUID();
    setQty('10'); setOwner(''); setReason('');
  }, [open]);

  const n = Number(qty || 0);
  const okQty = MAX > 0 && Number.isInteger(n) && n >= 1 && n <= MAX;
  const agent = agents.find(a => String(a.id) === owner);
  const who = agent
    ? `${agent.companyName || agent.realName || agent.nickname || `代理 #${agent.id}`}（${agent.agentCode}）`
    : null;

  return (
    <ActionDialog open={open} onClose={onClose} verb="生成" width={580}
      title="生成一批学员激活码"
      onDone={onDone}
      confirmLabel={okQty ? `确认生成 ${n} 个码` : '确认生成'}
      disabled={!okQty || !reason.trim() || reason.length > 255 || !m?.skuOn || unit == null}
      consequences={<>
        会新建 <b>一张不收钱的订单</b>挂 <b>{okQty ? n : '—'}</b> 个学员激活码：
        应付记 {unit == null ? '目录价' : <><b>{(unit / 100).toFixed(2)}</b> 元</>} × {okQty ? n : '—'}
        {unit != null && okQty ? <> = <b>{((unit * n) / 100).toFixed(2)}</b> 元</> : null}，
        <b>实付 0 元</b>、没有到账时间。<br />
        这张单<b>不计入成交额、不结佣金、也开不出发票</b> —— 三条都是数据库强制的。<br />
        {who
          ? <>这批码<b>归属 {who}</b>：激活它们的学员会被算成这家代理带来的，
              他的业绩与后续结算都算他的。<b>学员一激活就永久锁定</b>，改不回来。<br /></>
          : <>这批码<b>不归属任何代理</b>（平台直营）：谁激活都不计入任何代理的业绩、不结佣金。<br />
              <span style={{ color: 'var(--ink-3)' }}>
                注意「不归属」说的是<b>这批码</b>不带归属。如果激活它的学员早先已经被某家代理
                带进来过，那份权益仍按 first-touch 记在那家代理名下 ——
                不归属不会抹掉一个人已有的归属。
              </span><br /></>}
        生成之后明文要<b>手动导出</b>发给学员，每次导出都逐条写审计日志。
        还没被激活的码可以到订单详情里整单作废；<b>激活过的撤不回来</b>。
      </>}
      onConfirm={() => api.post('/api/admin/student-code-batches', {
        qty: n, ownerAgentId: owner ? Number(owner) : null,
        reason: reason.trim(), grantKey: key.current,
      })}>
      <div style={{ display: 'grid', gap: 'var(--s4)' }}>
        {meta.error && (
          <Note tone="danger" title="上限与目录价没取到">
            这一批要记多少钱的货、一批最多几个，都要从库里取。取不到就没法告诉你后果，
            所以这个框先不让提交。关掉重开再试。
          </Note>
        )}
        {m && !m.skuOn && (
          <Note tone="danger" title="学员直购档不在架上">
            这批白送码的记账依据就是它的目录价。它下架时价目也不该拿来记账 ——
            先去「课程与讲次」把它上架，再回来生成。
          </Note>
        )}

        <Ctl label="生成几个码" width={180}
             hint={MAX <= 0
               ? '上限没取到，先解决上面那条'
               : !okQty
                 ? `一批 1 到 ${MAX} 个，超过请分多批`
                 : <>这一批 <b>{n}</b> 个码，都挂在同一张单上</>}>
          <TextIn value={qty} inputMode="numeric"
                  onChange={e => setQty(e.target.value.replace(/\D/g, ''))} />
        </Ctl>

        <Ctl label="归属" width={320} grow
             hint={who
               ? '这批码算这家代理的业绩，学员激活后永久锁定'
               : '不归属 = 平台直营，谁激活都不算任何代理的业绩，也不结佣金'}>
          {/* all=false + 显式第一项：把「不归属」做成一个要被读到的选项，
              而不是「什么都不选」的副作用。默认值就是它，但它必须看得见 ——
              一个有后果的默认，藏在空白里等于没有被选择过。 */}
          <Pick value={owner} all={false} onChange={setOwner}
                options={[
                  { value: '', text: '不归属任何代理（平台直营）' },
                  ...agents.map(a => ({
                    value: String(a.id),
                    text: `${a.companyName || a.realName || a.nickname || `代理 #${a.id}`}（${a.agentCode}）`,
                  })),
                ]} />
        </Ctl>

        <ReasonBox value={reason} onChange={setReason} label="生成原因"
                   hint="事后有人问「这批码是怎么来的」，只有这一句能答。它会同时写进订单和审计日志。" />
        {reason.length > 255 && (
          <Note tone="danger" title="原因太长">
            已经 {reason.length} 个字，上限 255。超了会被后端拒绝，这一批一个码都生成不出来。
          </Note>
        )}
      </div>
    </ActionDialog>
  );
}

/* ---------------------------------------------------- 刚生成的那一批 */

function FreshBanner({ batch, onClear }: { batch: any; onClear: () => void }) {
  const [ask, setAsk] = React.useState(false);
  return (
    <div style={{ marginBottom: 'var(--s4)' }}>
      <Note tone="ok" title="这一批已经生成好了">
        订单 <span className="num">{batch.orderNo}</span> · 共
        <b className="num"> {num(batch.codesIssued)} </b>个码 ·
        {batch.ownerAgentId ? ` 归属代理 #${batch.ownerAgentId}` : ' 平台直营（不归属）'}。
        <b>码现在只在库里</b>，要发给学员就得先把明文导出来。
        <div style={{ display: 'flex', gap: 'var(--s2)', flexWrap: 'wrap', marginTop: 'var(--s3)' }}>
          <Button size="sm" onClick={() => setAsk(true)}>导出这一批的明文 CSV</Button>
          <Button size="sm" variant="quiet" onClick={onClear}>知道了</Button>
        </div>
      </Note>
      <ExportDialog open={ask} onClose={() => setAsk(false)} batch={batch} />
    </div>
  );
}

/* ------------------------------------------------------------ 行内操作 */

function BatchActions({ batch }: { batch: any }) {
  const [ask, setAsk] = React.useState(false);
  const [codes, setCodes] = React.useState(false);
  return (
    <div style={{ display: 'flex', gap: 'var(--s2)', flexWrap: 'wrap' }}>
      <Button size="sm" variant="quiet" onClick={e => { e.stopPropagation(); setCodes(true); }}>
        看这一批
      </Button>
      <Button size="sm" onClick={e => { e.stopPropagation(); setAsk(true); }}>导出明文</Button>
      <ExportDialog open={ask} onClose={() => setAsk(false)} batch={batch} />
      {codes && <CodeList batch={batch} onClose={() => setCodes(false)} />}
    </div>
  );
}

/**
 * 批量取明文存成 CSV。
 *
 * <p>为什么要一道确认框：这不是一次查询，是一次<b>批量明文披露</b> ——
 * 文件里每一行都是能直接激活的码，发到群里就收不回来了。
 *
 * <p>走的是订单那条既有的导出接口，<b>不另起一条</b>：一批就是一张单，
 * 而多一条解密路径就多一处可能漏掉审计的地方 —— 漏掉的那次就是无痕的明文泄漏。
 *
 * <p>取不到行时<b>当成失败抛出来</b>，不生成文件：一张只有表头的 CSV 和一张真的空导出
 * 长得一模一样，而人是按文件里的行数去发货的。
 */
function ExportDialog({ open, onClose, batch }: {
  open: boolean; onClose: () => void; batch: any;
}) {
  const orderId = batch.orderId ?? batch.orderId;
  return (
    <ActionDialog open={open} onClose={onClose} verb="导出" width={560}
      title="导出这一批激活码的明文"
      consequences={<>
        会把订单 <span className="num">{batch.orderNo}</span> 名下的
        <b>激活码明文</b>解密出来，存成一个 CSV 下载到你这台电脑上。<br />
        每一个码都会<b>逐条写进审计日志</b>（ADMIN_CODE_REVEAL）：谁、什么时候、导了哪些码，
        事后查得到。<br />
        文件里是可以直接激活的码，<b>请勿在群里转发</b> —— 一码一人，谁先激活算谁的。<br />
        {batch.ownerAgentId
          ? '这一批归属某家代理，学员激活后会被算成他带来的。'
          : '这一批不归属任何代理：谁激活都不算任何人的业绩。'}
      </>}
      onConfirm={async () => {
        const r: any = await api.post(`/api/admin/orders/${orderId}/codes/export`);
        const rows: any[] = r?.rows ?? [];
        if (!rows.length) throw new Error('这次一个码都没取到，没有生成文件');
        downloadCsv(`学员激活码-${batch.orderNo}-${stamp()}.csv`, [
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

/** 一批里的码。只出掩码 —— 后台页面天天开着、会被投屏与截图。 */
function CodeList({ batch, onClose }: { batch: any; onClose: () => void }) {
  const st = useAsync<any>(
    () => api.get(`/api/admin/student-code-batches/${batch.orderId}/codes`), [batch.orderId]);
  return (
    <div style={{
      position: 'fixed', inset: 0, background: 'rgba(0,0,0,.35)', zIndex: 60,
      display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 'var(--s4)',
    }} onClick={onClose}>
      <div onClick={e => e.stopPropagation()} style={{
        background: 'var(--bg)', borderRadius: 'var(--r-lg)', width: 'min(680px, 100%)',
        maxHeight: '80vh', overflow: 'auto', padding: 'var(--s5)',
      }}>
        <Sect title={<>这一批的码 · 订单 <span className="num">{batch.orderNo}</span></>}
              extra={<Button size="sm" variant="ghost" onClick={onClose}>关闭</Button>}>
          <KV items={[
            ['归属', batch.ownerAgentId
              ? `${batch.ownerName || `代理 #${batch.ownerAgentId}`}（${batch.ownerAgentCode}）`
              : '平台直营（不归属）'],
            ['目录价合计', <Money fen={batch.amountFen} />],
            ['生成原因', batch.reason],
            ['操作人', batch.operatorName || `#${batch.grantBy}`],
          ]} />
          <Async state={st}>{(d: any) => (
            <div style={{ display: 'grid', gap: 'var(--s2)', marginTop: 'var(--s4)' }}>
              {(d.items ?? []).map((c: any) => (
                <div key={c.codeId} style={{
                  display: 'flex', gap: 'var(--s3)', alignItems: 'center', flexWrap: 'wrap',
                  padding: 'var(--s2) 0', borderBottom: '1px solid var(--line)',
                  fontSize: 'var(--t-sm)',
                }}>
                  <span className="num">{c.masked}</span>
                  <St domain="code" value={c.status} />
                  <span style={{ color: 'var(--ink-3)', fontSize: 'var(--t-xs)' }}>
                    {fmtDate(c.activateDeadline)} 前激活
                  </span>
                  {c.boundUserId && (
                    <Link to={`/users${qs({ sel: c.boundUserId })}`}>
                      已被账号 #{c.boundUserId} 激活
                    </Link>
                  )}
                </div>
              ))}
              <div style={{ fontSize: 'var(--t-xs)', color: 'var(--ink-3)' }}>
                这里只出掩码。要明文就用「导出明文」—— 那一步会为每个码逐条写审计。
              </div>
            </div>
          )}</Async>
        </Sect>
      </div>
    </div>
  );
}
