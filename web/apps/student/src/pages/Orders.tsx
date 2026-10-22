/**
 * 我的订单 + 直接购买 + 支付。
 *
 * ## 为什么这一页要**明确说出"你可能本来就不该有订单"**
 *
 * 大部分学员是拿代理给的激活码开通的课，他们在这里永远看不到任何一行。
 * 空态写"暂无数据"的话，这些人会以为自己的购买记录丢了，然后去找客服。
 * 所以空态要把这件事讲清楚。
 *
 * ## 支付状态以**轮询**为准，不以"跳回来了"为准
 *
 * 用户从支付宝跳回来，不代表钱到账了；他也可能付完直接关了页面。
 * 所以发起支付之后本页开始轮询 /api/pay/status/{orderNo}，
 * 由后端的 `paid` 字段单一判定 —— 前端自己 switch 状态字符串的话，
 * 迟早漏掉 PARTIAL_REFUNDED 这类，表现是收银台一直转圈。
 */
import React from 'react';
import { Link } from 'react-router-dom';
import {
  api, Async, Button, Card, dateTime, Empty, PageHead, Skeleton, StatusTag,
  useAsync, useToast, yuan, IconReceipt,
} from '@wangke/shared';
import type { OrderRow, Sku } from '../types';
import { Confirm, Note, Row, Screen, ScrollX, Stack } from '../kit';

export default function Orders() {
  const orders = useAsync(
    () => api.get<{ orders: OrderRow[]; total: number }>('/api/orders?limit=50').then((r) => r.orders),
    [],
  );
  // 售卖入口取不到不该让整页红：已经下过的单比"还能买什么"重要得多。
  const skus = useAsync<Sku[] | null>(
    () => api.get<{ skus: Sku[] }>('/api/public/skus?buyerType=STUDENT')
      .then((r) => r.skus)
      .catch(() => null),
    [],
  );

  return (
    <Screen>
      <style>{ORDERS_CSS}</style>
      <PageHead title="购买课程" desc="在线选择课程并通过支付宝安全付款，支付后即可获得激活码。" />
      <Stack gap="var(--s6)">
        {/* loading 也要单独接住：skus.data 在加载中同样是 null，直接传下去会让
            首屏先闪一下「当前没有可购买的课程」，然后才变成课程列表。 */}
        {skus.loading
          ? <Skeleton h={180} />
          : <BuyPanel skus={skus.data ?? null} failed={skus.data === null && !skus.loading}
                      onPlaced={orders.reload} />}

        <div>
          <h2 style={{ fontSize: 'var(--t-h3)', marginBottom: 'var(--s3)' }}>订单记录</h2>
          {orders.loading ? <Stack><Skeleton h={96} /><Skeleton h={96} /></Stack> : (
            <Async
              state={orders}
              empty={
                <Card>
                  <Empty
                    icon={<IconReceipt size={22} />}
                    title="你还没有在这里下过单"
                    /* 这一页只出【按学员价下的单】。原来写的是「你还没有在平台下过单」，
                       对一个只有代理进货单的账号那是假话 —— 他下过单、付过钱，
                       而且不是用激活码开通的。而代理确实会来学员端（开票入口只有这一处）。 */
                    desc="如果你的课程是用激活码开通的，那是通过代理购买的，订单不在这里 —— 课程仍然在「我的课程」里，不受影响。你如果是代理，进货单在代理后台的「进货记录」里。"
                    action={<Link to="/"><Button variant="ghost">回我的课程</Button></Link>}
                  />
                </Card>
              }
            >
              {(list) => (
                <Stack gap="var(--s3)">
                  {list.map((o) => (
                    <OrderCard key={o.orderId} order={o} onChanged={orders.reload} />
                  ))}
                </Stack>
              )}
            </Async>
          )}
        </div>
      </Stack>
    </Screen>
  );
}

/* ------------------------------------------------------------------ 购买 */

function BuyPanel({ skus, failed, onPlaced }:
                  { skus: Sku[] | null; failed?: boolean; onPlaced: () => void }) {
  const toast = useToast();
  const [busy, setBusy] = React.useState<number | null>(null);

  // 原来这里是 `if (!skus || skus.length === 0) return null;` —— 一个标题写着
  // 「购买课程」的页面上，购买这件事整块消失，不报错、控制台干净。
  // 课程没上架时橱窗接口返回的就是空数组，于是线上静默了一整段时间，
  // 最后是有人真的在浏览器里点开才发现的。空和失败要各自说人话。
  if (failed) {
    return (
      <Card>
        <Note tone="warn" title="课程列表暂时取不到">
          请刷新重试。如果一直这样，说明平台侧有异常，请联系客服 —— 你已有的课程不受影响。
        </Note>
      </Card>
    );
  }
  if (!skus || skus.length === 0) {
    return (
      <Card>
        <Empty
          title="当前没有可购买的课程"
          desc="课程还没有上架。如果你手里已经有激活码，可以直接去「激活课程」开通。"
          action={<Link to="/activate"><Button variant="accent">激活课程</Button></Link>}
        />
      </Card>
    );
  }

  const place = async (sku: Sku) => {
    setBusy(sku.skuId);
    try {
      const r = await api.post<{ orderNo: string }>('/api/orders', { skuId: sku.skuId });
      toast({ tone: 'ok', text: `订单已创建（${r.orderNo}），请在下方完成支付` });
      onPlaced();
    } catch (e) {
      toast({ tone: 'danger', text: (e as Error).message || '下单失败，请重试' });
    } finally {
      setBusy(null);
    }
  };

  return (
    <Card className="wk-buy-panel">
      <div className="wk-buy-panel-head">
        <div>
          <div className="da-overline">课程直购</div>
          <h2>选择你要学习的课程</h2>
        </div>
        <span>支付宝安全支付</span>
      </div>
      <Stack gap="var(--s3)">
        {skus.map((s) => (
          <div key={s.skuId} className="wk-buy-product">
            <div className="wk-buy-product-copy">
              <div className="wk-buy-product-kicker">AI 实战课程</div>
              <h3>{s.courseTitle || s.name}</h3>
              <div className="wk-buy-benefits">
                <span>激活后 24 个月内可观看</span>
                <span>支付完成后立即生成激活码</span>
              </div>
            </div>
            <div className="wk-buy-product-action">
              <span className="wk-buy-price-label">课程价格</span>
              <strong className="num">{yuan(s.priceFen)}</strong>
              <Button size="lg" variant="accent" loading={busy === s.skuId}
                      onClick={() => place(s)}>立即购买 {yuan(s.priceFen)}</Button>
            </div>
          </div>
        ))}
      </Stack>
      <div style={{ marginTop: 'var(--s4)' }}>
        <Note>点击“立即购买”只会创建订单；进入支付宝并确认付款后才会扣款。未支付订单到期后会自动关闭。</Note>
      </div>
    </Card>
  );
}

const ORDERS_CSS = `
.wk-buy-panel { border-color: var(--accent); overflow: hidden; }
.wk-buy-panel-head {
  display: flex; align-items: flex-start; justify-content: space-between; gap: var(--s4);
  padding-bottom: var(--s4); border-bottom: 1px solid var(--line);
}
.wk-buy-panel-head h2 { margin-top: var(--s1); font-size: var(--t-h2); line-height: 1.3; }
.wk-buy-panel-head > span {
  padding: 5px 10px; color: var(--accent-press); background: var(--accent-soft);
  font-size: var(--t-xs); font-weight: 600; white-space: nowrap;
}
.wk-buy-product {
  display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: var(--s6);
  padding: var(--s5) 0 var(--s2);
}
.wk-buy-product-kicker { color: var(--accent-press); font-size: var(--t-xs); font-weight: 600; letter-spacing: .08em; }
.wk-buy-product h3 { margin-top: var(--s2); font-size: var(--t-h2); line-height: 1.35; }
.wk-buy-benefits { display: flex; flex-wrap: wrap; gap: var(--s2) var(--s4); margin-top: var(--s3); }
.wk-buy-benefits span { color: var(--ink-2); font-size: var(--t-sm); }
.wk-buy-benefits span::before { content: '✓'; margin-right: 6px; color: var(--accent-press); font-weight: 700; }
.wk-buy-product-action { display: grid; justify-items: end; min-width: 210px; }
.wk-buy-price-label { color: var(--ink-3); font-size: var(--t-xs); }
.wk-buy-product-action > strong { margin: 1px 0 var(--s3); color: var(--ink-0); font-size: 30px; line-height: 1.15; }
@media (max-width: 700px) {
  .wk-buy-panel-head { align-items: flex-start; }
  .wk-buy-panel-head h2 { font-size: var(--t-h3); }
  .wk-buy-product { grid-template-columns: 1fr; gap: var(--s4); padding-top: var(--s4); }
  .wk-buy-product h3 { font-size: 20px; }
  .wk-buy-benefits { display: grid; }
  .wk-buy-product-action { grid-template-columns: 1fr auto; align-items: end; min-width: 0; }
  .wk-buy-price-label { grid-column: 1; justify-self: start; }
  .wk-buy-product-action > strong { grid-column: 1; justify-self: start; margin-bottom: 0; }
  .wk-buy-product-action > button { grid-column: 2; grid-row: 1 / span 2; }
}
`;

/* ------------------------------------------------------------------ 一张订单 */

function OrderCard({ order, onChanged }: { order: OrderRow; onChanged: () => void }) {
  const toast = useToast();
  const [paying, setPaying] = React.useState(false);
  const [cancelling, setCancelling] = React.useState(false);
  const [busy, setBusy] = React.useState(false);
  const [revealing, setRevealing] = React.useState(false);
  const [activationCode, setActivationCode] = React.useState<string | null>(null);

  const payable = order.status === 'CREATED';

  /* ---- 支付状态轮询 --------------------------------------------------
   *
   * 从**页面挂载**就开始轮，不是从"点了去支付"才开始。
   * 支付要跳到支付宝，跳走的那一刻这个组件就被卸载了，"点过支付"这个状态
   * 根本活不到用户回来 —— 只在点击后开轮询的话，回来看到的仍然是"待支付"，
   * 而他刚刚明明付过钱了。
   *
   * 三分钟后停：一直转圈的收银台比一句"还没查到"更让人不知所措，
   * 而且后端的定时对账本来就会兜住漏掉的回调。之后还有手动那一颗按钮。
   */
  const [polling, setPolling] = React.useState(payable);
  React.useEffect(() => {
    if (!polling || !payable) return;
    let stop = false;
    const t = setInterval(async () => {
      try {
        const r = await api.get<{ paid: boolean }>(`/api/pay/status/${encodeURIComponent(order.orderNo)}`);
        if (r.paid && !stop) {
          setPolling(false);
          toast({ tone: 'ok', text: '支付成功，请查看激活码并完成课程激活' });
          onChanged();
        }
      } catch {
        /* 轮询失败不打断：用户可能还在支付宝那边，这里报错只会吓到他 */
      }
    }, 5000);
    const stopAt = setTimeout(() => { setPolling(false); stop = true; }, 180000);
    return () => { stop = true; clearInterval(t); clearTimeout(stopAt); };
  }, [polling, payable, order.orderNo, onChanged, toast]);

  const pay = async () => {
    setPaying(true);
    try {
      // 渠道按屏宽选：手机上给 PC 版收银台，会得到一个要缩放才能点的页面。
      const channel = window.innerWidth < 861 ? 'WAP' : 'PC';
      const r = await api.post<{ form: string | null; payUrl: string | null }>(
        '/api/pay/create', { orderId: order.orderId, channel },
      );
      setPolling(true);
      // 优先提交支付宝 SDK 返回的原始表单。WAP 的 GET 地址在部分内置浏览器里
      // 会被拦成空白页，而同一笔交易的 POST 表单可以稳定跳转。
      if (r.form) {
        // 支付宝返回的是一段会自动提交的表单。塞进 DOM 提交，不用 document.write ——
        // 后者会把整个 SPA 冲掉，用户从支付宝回来时页面是空的。
        const holder = document.createElement('div');
        holder.style.display = 'none';
        holder.innerHTML = r.form;
        document.body.appendChild(holder);
        const f = holder.querySelector('form');
        if (f) { f.target = '_self'; f.submit(); } else {
          toast({ tone: 'danger', text: '收银台没能打开，请稍后重试' });
        }
      } else if (r.payUrl) {
        window.location.assign(r.payUrl);
      } else {
        toast({ tone: 'danger', text: '收银台地址为空，请联系平台客服' });
      }
    } catch (e) {
      setPolling(false);
      toast({ tone: 'danger', text: (e as Error).message || '发起支付失败' });
    } finally {
      setPaying(false);
    }
  };

  const checkNow = async () => {
    setBusy(true);
    try {
      const r = await api.get<{ paid: boolean }>(`/api/pay/status/${encodeURIComponent(order.orderNo)}`);
      if (r.paid) { toast({ tone: 'ok', text: '已经收到款了' }); onChanged(); }
      else toast({ tone: 'info', text: '还没有收到这笔款。如果你刚付完，请过一会儿再查一次' });
    } catch (e) {
      toast({ tone: 'danger', text: (e as Error).message || '查询失败' });
    } finally {
      setBusy(false);
    }
  };

  const cancel = async () => {
    setBusy(true);
    try {
      await api.post(`/api/orders/${order.orderId}/cancel`);
      toast({ tone: 'ok', text: '订单已取消' });
      setCancelling(false);
      onChanged();
    } catch (e) {
      toast({ tone: 'danger', text: (e as Error).message || '取消失败' });
    } finally {
      setBusy(false);
    }
  };

  const revealActivationCode = async () => {
    setRevealing(true);
    try {
      const r = await api.post<{ code: string }>(
        `/api/orders/${order.orderId}/activation-code/reveal`,
      );
      setActivationCode(r.code);
    } catch (e) {
      toast({ tone: 'danger', text: (e as Error).message || '激活码读取失败' });
    } finally {
      setRevealing(false);
    }
  };

  return (
    <Card>
      <Stack gap="var(--s3)">
        <Row style={{ justifyContent: 'space-between' }} align="flex-start">
          <div style={{ minWidth: 0 }}>
            <div style={{ fontWeight: 600 }}>{order.skuName || order.skuCode}</div>
            <div className="num" style={{ fontSize: 'var(--t-xs)', color: 'var(--ink-3)', marginTop: 2 }}>
              单号 {order.orderNo}
            </div>
          </div>
          <StatusTag status={order.status} />
        </Row>

        <ScrollX>
          <div style={{
            display: 'grid', gridTemplateColumns: 'repeat(3, minmax(96px, 1fr))',
            gap: 'var(--s4)', minWidth: 300,
          }}>
            <Cell k="订单金额" v={yuan(order.amountFen)} />
            <Cell k="已付" v={yuan(order.paidAmountFen)} />
            {order.refundedAmountFen > 0
              ? <Cell k="已退" v={yuan(order.refundedAmountFen)} tone="danger" />
              : <Cell k="下单时间" v={dateTime(order.createdAt)} />}
          </div>
        </ScrollX>

        {payable && (
          <Note tone="warn">
            这单还没付款，{dateTime(order.expireAt)} 之后会自动关闭。
          </Note>
        )}

        {payable && polling && (
          <Note>
            正在自动查支付结果。付完之后这里会自己更新，不用重复付款。
          </Note>
        )}

        {activationCode && (
          <Note tone="warn">
            你的激活码：<b className="num">{activationCode}</b>。请勿转发；前往
            <Link to="/activate">「激活课程」</Link>阅读并确认协议后开通课程。
          </Note>
        )}

        <Row gap="var(--s3)">
          {payable && <Button loading={paying} onClick={pay}>去支付 {yuan(order.amountFen)}</Button>}
          {payable && <Button variant="ghost" loading={busy} onClick={checkNow}>我已付款，去查一下</Button>}
          {payable && <Button variant="quiet" onClick={() => setCancelling(true)}>取消订单</Button>}
          {order.status === 'FULFILLED' && (
            <Button variant="ghost" loading={revealing} onClick={revealActivationCode}>
              {activationCode ? '重新查看激活码' : '查看激活码'}
            </Button>
          )}
          {/* 这里曾经有一颗跳去「我的」开票的按钮。2026-09-01 连同「我的 → 发票」
              整块删掉：课程发票由售出方开，学员端自助开票是需求方按 bug 报的。
              别只把这颗按钮换成一句提示又留着 /me 那一段 —— 两处是同一个功能的两个入口，
              删一半的结果是按钮没了、老书签还能打开那一页。 */}
        </Row>
      </Stack>

      <Confirm
        open={cancelling}
        title="确认取消这单？"
        confirmText="确认取消"
        busy={busy}
        onConfirm={cancel}
        onClose={() => setCancelling(false)}
        consequence={
          <>
            <b>取消后这张订单不能恢复，需要重新下单。</b>
            <div style={{ marginTop: 'var(--s1)' }}>
              如果你已经付过款了，请不要取消 —— 先点「我已付款，去查一下」。
            </div>
          </>
        }
      />
    </Card>
  );
}

function Cell({ k, v, tone }: { k: string; v: string; tone?: 'danger' }) {
  return (
    <div>
      <div style={{ fontSize: 'var(--t-xs)', color: 'var(--ink-3)' }}>{k}</div>
      <div className="num" style={{
        marginTop: 2, fontWeight: 600,
        color: tone === 'danger' ? 'var(--danger)' : 'var(--ink)',
      }}>{v}</div>
    </div>
  );
}
