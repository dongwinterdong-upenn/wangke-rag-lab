/**
 * 进货下单。
 *
 * <h3>这一页不出现任何价目</h3>
 * 进货价由平台按代理逐家单独定，所以代理端摆一张统一价目表本身就是错的信息 ——
 * 金额只在超管后台和对公转账时出现。代理在这里只决定两件事：<b>买哪一档、几份</b>。
 *
 * 后端跟着一起停发（AgentShopService 的 skus / createOrder / view 三处出网体里
 * 没有 totalPriceFen / amountFen）。只把界面上的 ¥ 抹掉是更糟的做法：
 * 按一下 F12 就看得见，而"页面上不写"会让所有人以为它已经保密了。
 *
 * <h3>所以这一页必须多回答一个问题：提交之后会发生什么</h3>
 * 他提交了"批量档 × 3 份"，屏幕上一个数字都没有、也没有收银台。
 * 不把"等平台确认金额 → 线下对公转账 → 到账后出码"讲清楚，他会以为提交失败，
 * 然后再提交一次 —— 每提交一次就是一张真订单，要平台一张张核销。
 *
 * <h3>没有改动的东西</h3>
 * 订单状态机原样：CREATED → （超管确认到账）→ PAID → FULFILLED，取消仍然是 CLOSED。
 * 变的只有文案与展示。
 */
import React from 'react';
import { Link } from 'react-router-dom';
import {
  ApiError, Async, Button, Card, dateTime, Empty, PageHead, Select, Skeleton, Table,
  Tag, type Col, useAsync, useToast, IconCart,
} from '@wangke/shared';
import {
  AgentApi, ORDER_STATUS_TEXT, type OrderRow, type SkuRow,
} from '../api';
import { Gate, useActiveAgent } from '../gate';
import {
  Confirm, Note, Pager, Section, TableSkeleton, keepPrevious, useNarrow,
  type AsyncState,
} from '../kit';

const SIZE = 10;

/**
 * 一次最多提交几份。
 *
 * 每一份是<b>一张真订单</b>（后端一张进货单恰好装一个套餐档，code_qty 是 SKU 的快照，
 * 不接受份数参数），而每一张都要平台人工核销一次。写 10 是因为再多的量应当直接
 * 找对接人谈，而不是在这里点出 40 张单来。
 */
const MAX_PACKS = 10;

/** 已经提交出去的一批单。用它而不是"当前订单"：一次提交可能产生好几张单。 */
type Submission = {
  skuName: string;
  /** 每份多少个码 */
  codeQty: number;
  orderNos: string[];
  /** 中途失败时，少提交了几份、为什么。空 = 全提交成功 */
  shortfall?: string;
};

export default function BuyPage() {
  return (
    <>
      <PageHead title="进货下单" desc="选套餐和份数提交，平台确认到账后激活码直接进「我的激活码」。" />
      <Gate><Body /></Gate>
    </>
  );
}

function Body() {
  const agent = useActiveAgent();
  const [submitted, setSubmitted] = React.useState<Submission | null>(null);
  const [page, setPage] = React.useState(1);
  const orders = useAsync(() => AgentApi.orders({ page, size: SIZE }), [agent?.id, page]);

  if (submitted) {
    return (
      <Submitted
        s={submitted}
        onBack={() => { setSubmitted(null); orders.reload(); }}
      />
    );
  }

  return (
    <div style={{ display: 'grid', gap: 'var(--s4)' }}>
      <Skus onSubmitted={s => setSubmitted(s)} />
      <History state={orders} page={page} onPage={setPage}
               onOpen={o => setSubmitted(o)} onCancelled={() => orders.reload()} />
    </div>
  );
}

/* ---------------------------------------------------------------- 选套餐 */

function Skus({ onSubmitted }: { onSubmitted: (s: Submission) => void }) {
  const toast = useToast();
  const state = useAsync(() => AgentApi.skus(), []);
  const [picked, setPicked] = React.useState<SkuRow | null>(null);
  const [packs, setPacks] = React.useState(1);
  const [busy, setBusy] = React.useState(false);

  function pick(s: SkuRow) {
    setPacks(1);          // 份数不跨档保留：上一档选了 5 份，换一档还挂着 5，他不会重看一眼
    setPicked(s);
  }

  async function create() {
    if (!picked) return;
    setBusy(true);
    const nos: string[] = [];
    let shortfall: string | undefined;
    try {
      for (let i = 0; i < packs; i++) {
        // ★ 一张 await 完再发下一张，绝不能 Promise.all：
        //   shared/api.ts 的写操作去重拿 (方法, 路径, 请求体) 当键，而这 N 次的请求体
        //   逐字节相同 —— 并发发出去会被合并成一次请求，N 个调用点拿到同一个单号，
        //   界面写着"已提交 3 份"而库里只有 1 张单，没有任何报错。
        const o = await AgentApi.createOrder({ skuId: picked.skuId });
        nos.push(o.orderNo);
      }
    } catch (e) {
      // 中途失败不能把已经建出来的单吞掉：它们是真订单，平台那边看得见。
      // 只报一句"提交失败"的话，代理会整批重提，于是同一批货下了两遍。
      shortfall = `第 ${nos.length + 1} 份没能提交：${e instanceof ApiError ? e.message : '网络异常'}`;
    } finally {
      setBusy(false);
    }

    setPicked(null);
    if (!nos.length) {
      toast({ tone: 'danger', text: shortfall || '提交失败，请重试' });
      return;
    }
    onSubmitted({
      skuName: picked.name,
      codeQty: picked.codeQty,
      orderNos: nos,
      shortfall,
    });
  }

  const totalCodes = picked ? picked.codeQty * packs : 0;

  return (
    <Section
      title="选套餐"
      desc="选好档位和份数就可以提交，进货价不在这一页显示。"
    >
      {state.loading ? (
        <div style={{ display: 'grid', gap: 'var(--s4)',
                      gridTemplateColumns: 'repeat(auto-fit, minmax(min(240px, 100%), 1fr))' }}>
          <Skeleton h={168} /><Skeleton h={168} />
        </div>
      ) : (
        <Async state={state}>
          {(d) => {
            const rows = d.rows.filter(s => s.status !== 'OFF');
            if (!rows.length) {
              return (
                <Empty
                  icon={<IconCart size={22} />}
                  title="现在没有可买的套餐"
                  desc="套餐可能临时下架了。稍后再看看，或者联系平台对接人确认。"
                />
              );
            }
            return (
              <>
                <div style={{ display: 'grid', gap: 'var(--s4)',
                              gridTemplateColumns: 'repeat(auto-fit, minmax(min(240px, 100%), 1fr))' }}>
                  {rows.map(s => <SkuCard key={s.skuId} sku={s} onPick={() => pick(s)} />)}
                </div>
                <div style={{ marginTop: 'var(--s4)' }}>
                  {/* 这句话必须写：一屏上一个价都没有，而他上周还在这里看得到价目卡片。
                      不解释的话，最自然的解读是"平台把价藏起来了"，
                      而真实原因恰恰相反 —— 价是一家一议的，摆一张统一价目表才是错的。 */}
                  <Note tone="info" title="金额在哪里看">
                    你的进货价由平台单独跟你定，所以不在这一页显示。
                    提交之后平台会按你的价核出金额并跟你确认，付款走线下对公转账。
                    码有激活期限、不是永久有效，按能发得出去的量提。
                  </Note>
                </div>
              </>
            );
          }}
        </Async>
      )}

      <Confirm
        open={!!picked}
        title="确认提交这一单进货"
        confirmText="提交，等平台确认"
        loading={busy}
        onClose={() => setPicked(null)}
        onConfirm={create}
        consequence={<>
          提交后<b>不会</b>立刻出码，也不会立刻扣钱：平台确认到账后，
          <b>{totalCodes}</b> 个激活码才会发到你名下。
          <b>激活码一经学员激活，概不退费</b>；没被激活的码可以按未激活部分申请退款。
        </>}
      >
        {picked && (
          <div style={{ display: 'grid', gap: 'var(--s3)', fontSize: 'var(--t-sm)',
                        color: 'var(--ink-2)' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', gap: 'var(--s3)' }}>
              <span>{picked.name}</span>
              <span className="num" style={{ color: 'var(--ink-3)' }}>
                {picked.codeQty} 个码 / 份
              </span>
            </div>

            <label style={{ display: 'flex', alignItems: 'center',
                            justifyContent: 'space-between', gap: 'var(--s3)' }}>
              <span>份数</span>
              <Select value={packs} disabled={busy}
                      onChange={e => setPacks(Number(e.currentTarget.value))}
                      style={{ width: 96 }}>
                {Array.from({ length: MAX_PACKS }, (_, i) => i + 1).map(n => (
                  <option key={n} value={n}>{n}</option>
                ))}
              </Select>
            </label>

            <div style={{ display: 'flex', justifyContent: 'space-between',
                          color: 'var(--ink)', fontWeight: 600 }}>
              <span>合计</span>
              <span className="num">{totalCodes} 个激活码</span>
            </div>

            {/* 份数不是"数量参数"，是"提交几次"。不说破的话，代理会在进货记录里
                看到 3 张单，以为自己手滑重复提交了，然后去取消其中两张。 */}
            {packs > 1 && (
              <div style={{ color: 'var(--ink-3)' }}>
                {packs} 份会生成 <span className="num">{packs}</span> 张进货单，
                每张 {picked.codeQty} 个码，平台一张张确认。
              </div>
            )}
            {picked.activateMonths ? (
              <div style={{ color: 'var(--ink-3)' }}>
                码需要在出码后 {picked.activateMonths} 个月内激活，过期作废。
              </div>
            ) : null}
            {picked.accessMonths ? (
              <div style={{ color: 'var(--ink-3)' }}>
                学员激活后 {picked.accessMonths} 个月内可以观看。
              </div>
            ) : null}
          </div>
        )}
      </Confirm>
    </Section>
  );
}

function SkuCard({ sku, onPick }: { sku: SkuRow; onPick: () => void }) {
  const bulk = sku.codeQty > 1;
  return (
    <Card style={{ display: 'flex', flexDirection: 'column', gap: 'var(--s3)' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--s2)', flexWrap: 'wrap' }}>
        <span style={{ fontSize: 'var(--t-h3)', fontWeight: 600 }}>{sku.name}</span>
        {/* 原来这里是「更划算」。没有价就没有"划算"可言 —— 那句话在这一版里
            是一个无法验证的承诺，而代理拿不到任何数字去核对它。 */}
        {bulk && <Tag tone="brand">批量档</Tag>}
      </div>
      <div>
        {/* 这一档现在唯一的量词就是码数，所以把它放到原来价格的位置上，
            用同一级字号 —— 卡片上得有一个能一眼比较的数，否则两张卡看起来一模一样。 */}
        <span className="num" style={{ fontSize: 'var(--t-h1)', fontWeight: 600, lineHeight: 1.25 }}>
          {sku.codeQty}
        </span>
        <div style={{ fontSize: 'var(--t-sm)', color: 'var(--ink-3)', marginTop: 'var(--s1)' }}>
          个激活码 / 份
        </div>
      </div>
      {sku.courseTitle && (
        <div style={{ fontSize: 'var(--t-sm)', color: 'var(--ink-2)' }}>{sku.courseTitle}</div>
      )}
      <div style={{ marginTop: 'auto' }}>
        <Button block variant={bulk ? 'accent' : 'ghost'} onClick={onPick}>选这个</Button>
      </div>
    </Card>
  );
}

/* ------------------------------------------------------------ 提交之后 */

/**
 * 提交回执。这一屏顶替了原来的收银台，回答的是同一个问题：<b>现在轮到谁了</b>。
 *
 * 原来那一屏靠二维码和倒计时回答（"轮到你扫码，还有 12 分 30 秒"）。
 * 现在轮到平台，而"轮到别人"没有任何视觉信号 —— 所以必须把三步写出来，
 * 并且明说不用再提交一次。少了这段，代理最可能做的动作就是回去再点一遍。
 */
function Submitted({ s, onBack }: { s: Submission; onBack: () => void }) {
  const total = s.codeQty * s.orderNos.length;
  return (
    <Section
      title="已提交，等平台确认"
      extra={<Button variant="quiet" size="sm" onClick={onBack}>回到进货记录</Button>}
    >
      <div style={{ display: 'grid', gap: 'var(--s5)', justifyItems: 'center',
                    textAlign: 'center', padding: 'var(--s5) 0' }}>
        <div style={{ fontSize: 'var(--t-display)', color: 'var(--ok)', lineHeight: 1 }}>✓</div>
        <div style={{ display: 'grid', gap: 'var(--s2)' }}>
          <div style={{ fontSize: 'var(--t-h2)', fontWeight: 600 }}>
            {s.skuName} × {s.orderNos.length} 份
          </div>
          <div style={{ fontSize: 'var(--t-sm)', color: 'var(--ink-2)' }}>
            共 <span className="num">{total}</span> 个激活码，等平台确认后发到你名下
          </div>
        </div>

        <div style={{ display: 'flex', gap: 'var(--s2)', flexWrap: 'wrap',
                      justifyContent: 'center', maxWidth: 520 }}>
          {s.orderNos.map(no => (
            <span key={no} className="num" style={{
              fontSize: 'var(--t-xs)', color: 'var(--ink-3)',
              border: '1px solid var(--line)', borderRadius: 'var(--r-sm)',
              padding: '2px var(--s2)',
            }}>{no}</span>
          ))}
        </div>

        {s.shortfall && (
          <Note tone="danger" title="这一批没有全部提交成功" style={{ textAlign: 'left', maxWidth: 520 }}>
            {s.shortfall}
            <br />
            上面列出的单号是<b>已经提交成功</b>的那几张，不要整批重提 ——
            差的份数按差额再提交一次就行。
          </Note>
        )}

        <Note tone="info" title="接下来会发生什么" style={{ textAlign: 'left', maxWidth: 520 }}>
          <div style={{ display: 'grid', gap: 'var(--s2)' }}>
            <div>① 平台按你的进货价核出金额，由对接人告诉你 —— 金额不在代理后台显示。</div>
            <div>② 你按线下对公转账付款。</div>
            <div>③ 平台确认到账后，这{total > s.codeQty ? '几' : ''}张单会自动出码，
                   {total} 个激活码直接进「我的激活码」。</div>
          </div>
          <div style={{ marginTop: 'var(--s3)', color: 'var(--ink-3)' }}>
            这一步不用你再做什么，这一页也不会自己跳转 —— 进度在下面的「我的进货记录」里看。
            <b>别重复提交</b>：每提交一次都会多出一张真订单，平台要一张张核销。
          </div>
        </Note>

        <div style={{ display: 'flex', gap: 'var(--s2)', flexWrap: 'wrap', justifyContent: 'center' }}>
          <Button variant="accent" onClick={onBack}>知道了，看进货记录</Button>
          <Link to="/codes"><Button variant="ghost">去看我的激活码</Button></Link>
        </div>
      </div>
    </Section>
  );
}

/* ---------------------------------------------------------------- 记录 */

function History({ state, page, onPage, onOpen, onCancelled }: {
  state: AsyncState<{ total: number; rows: OrderRow[] }>;
  page: number; onPage: (p: number) => void; onOpen: (s: Submission) => void;
  onCancelled: () => void;
}) {
  const narrow = useNarrow();
  const view = keepPrevious(state);
  // 要取消的那一单。放在这一层而不是每行各存一份 ——
  // 每行一份的话，确认框里的单号和真正被取消的那一单很容易对不上。
  const [cancelling, setCancelling] = React.useState<OrderRow | null>(null);
  const [busy, setBusy] = React.useState(false);
  const toast = useToast();
  const doCancel = async () => {
    if (!cancelling) return;
    setBusy(true);
    try {
      await AgentApi.cancelOrder(cancelling.orderNo);
      toast({ tone: 'ok', text: '这一单已取消' });
      setCancelling(null);
      onCancelled();
    } catch (e) {
      toast({ tone: 'danger', text: (e as Error).message || '取消失败，请重试' });
    } finally {
      setBusy(false);
    }
  };

  /** 单张单再看一次那段"接下来会发生什么"。他关掉回执之后，唯一能找回这段话的地方 */
  const reopen = (r: OrderRow): Submission => ({
    skuName: r.skuName || r.skuCode,
    codeQty: r.codeQty,
    orderNos: [r.orderNo],
  });

  const cols: Col<OrderRow>[] = [
    { key: 'orderNo', title: '订单号', width: 170,
      render: r => <span className="num" style={{ color: 'var(--ink-2)' }}>{r.orderNo}</span> },
    { key: 'skuName', title: '买的什么',
      // 平台手工发的那张单也要出现在这份记录里 —— 不出现的话，「我的激活码」里那批码
      // 挂着的单号在这里<b>查无此单</b>，比看到一个陌生单号更难解释。
      //
      // 「平台发放 · 无需付款」这一句要留着，而且只留给白送单。它不是价格，
      // 是在解释"这批码你没付过钱"：别的单在这一页什么钱都不写，
      // 不点破的话代理会拿这张单去问平台"这笔我是不是还欠着"。
      render: r => <>
        {r.skuName || r.skuCode}
        <span style={{ color: 'var(--ink-3)' }}>（{r.codeQty} 个码）</span>
        {r.gift && (
          <div style={{ color: 'var(--ink-3)', fontSize: 'var(--t-sm)', whiteSpace: 'nowrap' }}>
            平台发放 · 无需付款
          </div>
        )}
      </> },
    { key: 'status', title: '状态', width: 130,
      render: r => <Tag tone={r.status === 'FULFILLED' ? 'ok'
        : r.status === 'CREATED' ? 'warn'
        : r.status === 'CLOSED' ? 'neutral' : 'brand'}>
        {ORDER_STATUS_TEXT[r.status] || r.status}
      </Tag> },
    { key: 'createdAt', title: '下单时间', width: 150, render: r => dateTime(r.createdAt) },
    {
      // 两颗按钮的闸是 status === 'CREATED'，而平台白送的单<b>进不了这个状态</b>：
      // 库里的 trg_order_insert 强制 ADMIN_GIFT 单落地即 FULFILLED（不是应用层自觉），
      // 所以这里不需要为白送单再加一道判断。反过来说，哪天有人把闸改成看
      // expire_in_seconds / expireAt 之类的时间量，白送单（expire_at 为空）就会
      // 突然长出一颗按钮 —— 而且自己下的单会在提交两小时后失去入口，
      // 尽管它还等着平台确认（对公转账要几天，expire_at 只有 120 分钟）。
      key: 'act', title: '', width: 168,
      render: r => r.status !== 'CREATED' ? null : (
        <div style={{ display: 'flex', gap: 'var(--s2)' }}>
          <Button size="sm" variant="quiet" onClick={() => onOpen(reopen(r))}>查看进度</Button>
          {/* 也要能取消：库里没有任何定时任务会替他关掉这张单，
              不给取消入口的话它会永久停在这份记录的顶部显示「待平台确认」。 */}
          <Button size="sm" variant="quiet"
                  onClick={() => setCancelling(r)}>取消</Button>
        </div>
      ),
    },
  ];

  return (
    <Section title="我的进货记录">
      {view.loading ? <TableSkeleton rows={4} cols={5} /> : (
        <Async state={view}>
          {(d) => d.rows.length === 0 ? (
            <Empty
              icon={<IconCart size={22} />}
              title="还没进过货"
              desc="选一个套餐提交，平台确认到账后激活码会发到你名下。"
            />
          ) : narrow ? (
            <div style={{ display: 'grid', gap: 'var(--s2)' }}>
              {d.rows.map(r => (
                <Card key={r.id} style={{ padding: 'var(--s3) var(--s4)' }}>
                  <div style={{ display: 'flex', justifyContent: 'space-between',
                                alignItems: 'center', gap: 'var(--s3)' }}>
                    <span style={{ fontWeight: 600 }}>{r.skuName || r.skuCode}</span>
                    <Tag tone={r.status === 'FULFILLED' ? 'ok'
                      : r.status === 'CREATED' ? 'warn' : 'neutral'}>
                      {ORDER_STATUS_TEXT[r.status] || r.status}
                    </Tag>
                  </div>
                  <div style={{ fontSize: 'var(--t-xs)', color: 'var(--ink-3)',
                                marginTop: 'var(--s1)' }}>
                    {r.codeQty} 个码 · {dateTime(r.createdAt)}
                  </div>
                  {/* 与桌面那一列同一条规矩：白送单要说明它没付过钱。窄屏卡片是同一份数据的
                      另一种排版，只改一处的话手机上那张单就成了一张无法解释的单。 */}
                  {r.gift && (
                    <div style={{ fontSize: 'var(--t-xs)', color: 'var(--ink-2)',
                                  marginTop: 'var(--s1)' }}>
                      平台发放 · 无需付款
                    </div>
                  )}
                  {r.status === 'CREATED' && (
                    <div style={{ marginTop: 'var(--s2)', display: 'grid', gap: 'var(--s2)' }}>
                      <Button size="sm" variant="ghost" block
                              onClick={() => onOpen(reopen(r))}>查看进度</Button>
                      <Button size="sm" variant="quiet" block
                              onClick={() => setCancelling(r)}>取消这一单</Button>
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
        title="取消这一单进货"
        confirmText="确认取消"
        danger
        loading={busy}
        onClose={() => setCancelling(null)}
        onConfirm={doCancel}
        consequence={<>
          取消之后这张单作废，<b>不会</b>发出任何激活码，也不会产生任何费用。<br />
          还想进这一批货的话，回上面重新提交一次就行。
        </>}
      />
    </Section>
  );
}
