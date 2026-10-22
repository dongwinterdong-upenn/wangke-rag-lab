import React from 'react';
import { Link } from 'react-router-dom';
import { api } from '@wangke/shared/api';
import { PageHead } from '@wangke/shared/shell';
import { Async, Button, Table, useAsync } from '@wangke/shared/ui';
import type { Col } from '@wangke/shared/ui';
import {
  ActionDialog, Blank, Board, Ctl, Filters, Fold, KV, Note, Pager, Pick, Raw, ReasonBox, Sect, St,
  TextIn,
} from '../lib/kit';
import { MiniTable } from './Orders';
import { fmtTime, label, num, options } from '../lib/fmt';
import { hasFilter, qs, useQ } from '../lib/q';

/** 风控信号词表。和后端 RiskEngine.Signal 一一对应，多一个少一个都会让筛选框骗人。 */
const SIGNALS = [
  { value: 'IMPOSSIBLE_TRAVEL', text: '不可能旅行' },
  { value: 'CONCURRENT_SESSION', text: '多地并发播放' },
  { value: 'DEVICE_CHURN', text: '频繁换设备' },
  { value: 'SEGMENT_RATE_EXCEED', text: '取片速率异常' },
  { value: 'SEQUENTIAL_SCAN', text: '顺序抓片' },
  { value: 'KEY_REQUEST_BURST', text: '密钥索取暴增' },
  { value: 'WM_TAMPERED', text: '水印被篡改' },
];

export function Risk() {
  const { sp, get, int, set, reset } = useQ();
  const tab = get('tab') || 'events';
  const userId = get('userId');

  return (
    <>
      <PageHead title="风控"
        desc="共享账号的证据几乎总是概率性的：出差、换 wifi、家里两台设备，看起来都像共享。所以处置分档，并且一定要有人工解除这条路。" />

      {/*
        ★ 这块横幅不能删。

        全仓 grep 过：risk_event 与 risk_action 这两张表【没有任何一处代码在写】
        （RiskEngine 只被本页的「按人体检」当算式调用，播放、取密钥、绑设备那几条路
        一个信号都不产出）。也就是说采集这一半还没接上。

        不说这句话的代价不是「少一条说明」：下面那张空表配上原来那句
        「没有风险事件是好事，说明没人在共享账号或批量抓片」，
        读起来是【平台在盯着而且什么都没发现】—— 而真相是没人在盯。
        运营会据此判断「盗录风险可控」，那是一个基于假读数做出的真决定。
      */}
      <div style={{ marginBottom: 'var(--s4)' }}>
        <Note tone="warn" title="采集这一半还没接上，空表不等于没有风险">
          目前没有任何线上动作会写入风险事件与处置记录 —— 播放、取密钥、绑设备都不产出信号。
          所以这两张表为空，说的是<b>没有在采集</b>，不是<b>没有发生</b>。
          「按人体检」那一页的分值同理：它按库里现有事件算，事件为空就恒为 0 分。
          解除处置这条通道是通的，等采集接上就能用。
        </Note>
      </div>

      <div style={{ display: 'flex', gap: 'var(--s2)', marginBottom: 'var(--s4)', flexWrap: 'wrap' }}>
        {[['events', '风险事件'], ['actions', '处置记录'], ['check', '按人体检']].map(([k, t]) => (
          <Button key={k} size="sm" variant={tab === k ? 'primary' : 'ghost'}
                  onClick={() => set({ tab: k })}>{t}</Button>
        ))}
      </div>

      {tab === 'events' && <Events />}
      {tab === 'actions' && <Actions />}
      {tab === 'check' && <Checkup initialUserId={userId} onPick={v => set({ userId: v }, true)} />}
    </>
  );
}

/* ------------------------------------------------------------------ 事件 */

function Events() {
  const { sp, get, int, set, reset } = useQ();
  const userId = get('userId'), signal = get('signal'), sessionId = get('sessionId');
  const minScore = get('minScore'), from = get('from'), to = get('to');
  const page = int('page', 1), size = int('size', 50);

  const list = useAsync<any>(
    () => api.get(`/api/admin/risk/events${qs({ userId, signal, sessionId, minScore, from, to, page, size })}`),
    [userId, signal, sessionId, minScore, from, to, page, size]);

  const filtered = hasFilter(sp, ['page', 'size', 'tab']);

  const cols: Col<any>[] = [
    { key: 'createdAt', title: '时间', render: r => (
      <span className="num" style={{ fontSize: 'var(--t-xs)' }}>{fmtTime(r.createdAt, true)}</span>) },
    { key: 'userId', title: '账号', render: r => (
      <Link to={`/users${qs({ sel: r.userId })}`} onClick={e => e.stopPropagation()}
            className="num">#{r.userId}</Link>) },
    { key: 'signal', title: '信号', render: r => (
      <div style={{ minWidth: 180 }}>
        <div>{SIGNALS.find(s => s.value === r.signal)?.text ?? r.signal}</div>
        <div style={{ color: 'var(--ink-3)', fontSize: 'var(--t-xs)' }}>{r.why ?? r.signal}</div>
      </div>) },
    { key: 'score', title: '分值', numeric: true, render: r => (
      <span className="num" style={{ fontWeight: 600,
        color: num(r.score) >= 50 ? 'var(--danger)' : num(r.score) >= 35 ? 'var(--warn)' : undefined,
      }}>{r.score}</span>) },
    { key: 'sessionId', title: '播放会话',
      render: r => r.sessionId
        ? <span className="num" style={{ fontSize: 'var(--t-xs)' }}>{r.sessionId}</span>
        : <span style={{ color: 'var(--ink-3)' }}>—</span> },
    { key: 'detail', title: '判定依据', render: r => r.detail
      ? <Fold summary="看依据"><Raw text={r.detail} /></Fold>
      : <span style={{ color: 'var(--ink-3)' }}>—</span> },
  ];

  return (
    <>
      <Filters active={filtered} onReset={() => reset(['size', 'tab'])}>
        <Ctl label="账号 ID" width={120}>
          <TextIn defaultValue={userId} inputMode="numeric" placeholder="回车"
                  onKeyDown={e => { if (e.key === 'Enter') set({ userId: e.currentTarget.value.trim() }); }} />
        </Ctl>
        <Ctl label="信号" width={170}>
          <Pick value={signal} onChange={v => set({ signal: v })} options={SIGNALS} />
        </Ctl>
        <Ctl label="最低分值" width={110}>
          <TextIn defaultValue={minScore} inputMode="numeric" placeholder="回车"
                  onKeyDown={e => { if (e.key === 'Enter') set({ minScore: e.currentTarget.value.trim() }); }} />
        </Ctl>
        <Ctl label="播放会话 ID" width={180}>
          <TextIn defaultValue={sessionId} placeholder="回车"
                  onKeyDown={e => { if (e.key === 'Enter') set({ sessionId: e.currentTarget.value.trim() }); }} />
        </Ctl>
        <Ctl label="从" width={150}>
          <TextIn type="date" value={from} onChange={e => set({ from: e.target.value })} />
        </Ctl>
        <Ctl label="到" width={150}>
          <TextIn type="date" value={to} onChange={e => set({ to: e.target.value })} />
        </Ctl>
      </Filters>

      <Board flush title={`风险事件${list.data ? ` · ${num(list.data.total)} 条` : ''}`}>
        <Async state={list}>{(d: any) => (
          <>
            <Table cols={cols} rows={d.items} rowKey={r => r.id}
                   empty={<Blank filtered={filtered} thing="风险事件"
                     onClear={() => reset(['size', 'tab'])}
                     action={<span style={{ fontSize: 'var(--t-sm)' }}>
                       {/* 原来这里写的是「没有风险事件是好事，说明没人在共享账号或批量抓片」——
                           一句把「没采集」讲成「没发生」的话。空表在这里只有一个含义。 */}
                       这张表现在恒为空：还没有任何线上动作会写风险事件。
                     </span>} />} />
            <Pager total={num(d.total)} page={page} size={size}
                   onPage={p => set({ page: p }, true)} onSize={s => set({ size: s })} />
          </>
        )}</Async>
      </Board>
    </>
  );
}

/* ------------------------------------------------------------------ 处置 */

function Actions() {
  const { sp, get, int, set, reset } = useQ();
  const userId = get('userId'), action = get('action'), onlyOpen = get('onlyOpen');
  const page = int('page', 1), size = int('size', 50);
  const [rev, bump] = React.useReducer((n: number) => n + 1, 0);
  const [rel, setRel] = React.useState<any>(null);

  const list = useAsync<any>(
    () => api.get(`/api/admin/risk/actions${qs({ userId, action, onlyOpen: onlyOpen || undefined, page, size })}`),
    [userId, action, onlyOpen, page, size, rev]);

  const filtered = hasFilter(sp, ['page', 'size', 'tab']);

  const cols: Col<any>[] = [
    { key: 'createdAt', title: '时间',
      render: r => <span className="num" style={{ fontSize: 'var(--t-xs)' }}>{fmtTime(r.createdAt, true)}</span> },
    { key: 'userId', title: '账号',
      render: r => <Link to={`/users${qs({ sel: r.userId })}`} className="num">#{r.userId}</Link> },
    { key: 'action', title: '处置', render: r => <St domain="riskAction" value={r.action} /> },
    { key: 'scoreAtTrigger', title: '触发时分值', numeric: true,
      render: r => <span className="num">{r.scoreAtTrigger}</span> },
    { key: 'userStatus', title: '账号当前状态', render: r => <St domain="user" value={r.userStatus} /> },
    { key: 'releasedAt', title: '解除情况', render: r => r.releasedAt
      ? <span style={{ fontSize: 'var(--t-xs)' }}>{fmtTime(r.releasedAt)} · 由 #{r.releasedBy}</span>
      : <span style={{ color: 'var(--warn)' }}>未解除</span> },
    { key: 'ops', title: '操作', render: r => !r.releasedAt ? (
      <Button size="sm" variant="ghost" onClick={() => setRel(r)}>解除处置</Button>
    ) : null },
  ];

  return (
    <>
      <Filters active={filtered} onReset={() => reset(['size', 'tab'])}>
        <Ctl label="账号 ID" width={120}>
          <TextIn defaultValue={userId} inputMode="numeric" placeholder="回车"
                  onKeyDown={e => { if (e.key === 'Enter') set({ userId: e.currentTarget.value.trim() }); }} />
        </Ctl>
        <Ctl label="处置档位" width={170}>
          <Pick value={action} onChange={v => set({ action: v })} options={options('riskAction')} />
        </Ctl>
        <Ctl label="只看未解除的" width={130}>
          <Pick value={onlyOpen} all={false} onChange={v => set({ onlyOpen: v })}
                options={[{ value: '', text: '全部' }, { value: 'true', text: '只看未解除' }]} />
        </Ctl>
      </Filters>

      <Board flush title={`处置记录${list.data ? ` · ${num(list.data.total)} 条` : ''}`}>
        <Async state={list}>{(d: any) => (
          <>
            <Table cols={cols} rows={d.items} rowKey={r => r.id}
                   empty={<Blank filtered={filtered} thing="处置记录"
                                 onClear={() => reset(['size', 'tab'])} />} />
            <Pager total={num(d.total)} page={page} size={size}
                   onPage={p => set({ page: p }, true)} onSize={s => set({ size: s })} />
          </>
        )}</Async>
      </Board>

      {rel && <Release row={rel} close={() => setRel(null)} onDone={bump} />}
    </>
  );
}

function Release({ row, close, onDone }: { row: any; close: () => void; onDone: () => void }) {
  const [reason, setReason] = React.useState('');
  return (
    <ActionDialog open onClose={close} verb="解除" onDone={onDone}
      title={`解除对账号 #${row.userId} 的处置`}
      disabled={!reason.trim()}
      consequences={<>
        解除的是<b>这一条风控判定</b>，不是账号本身的状态。<br />
        {row.userStatus === 'FROZEN' ? (
          <>这个账号当前是<b>冻结</b>状态 —— 解除处置<b>不会</b>把他放出来。
            确认要放行的话，还要到<Link to={`/users${qs({ sel: row.userId })}`}>学员管理</Link>里单独解冻。</>
        ) : (
          <>这个账号当前不是冻结状态，解除之后他照常使用。</>
        )}<br />
        两者刻意分开：合成一个动作之后，一次误判的撤销会顺手把一个因为别的原因
        （欠费、投诉、实名问题）被冻结的账号也放出来，而那不是风控该做的决定。
      </>}
      onConfirm={() => api.post(`/api/admin/risk/actions/${row.id}/release`, { reason: reason.trim() })}>
      <ReasonBox value={reason} onChange={setReason} label="解除理由"
                 hint="下一次这个人又被判定时，别人要能看到上次为什么放了他。" />
    </ActionDialog>
  );
}

/* ------------------------------------------------------------------ 体检 */

function Checkup({ initialUserId, onPick }: {
  initialUserId: string; onPick: (v: string) => void;
}) {
  const [input, setInput] = React.useState(initialUserId);
  const state = useAsync<any>(
    () => initialUserId ? api.get(`/api/admin/risk/users/${initialUserId}/summary`) : Promise.resolve(null),
    [initialUserId]);

  return (
    <>
      <Filters>
        <Ctl label="账号 ID" width={160} hint="输入后回车。这里算的分和线上判定用的是同一套算法。">
          <TextIn value={input} inputMode="numeric"
                  onChange={e => setInput(e.target.value.replace(/\D/g, ''))}
                  onKeyDown={e => { if (e.key === 'Enter') onPick(input); }} />
        </Ctl>
        <Button size="sm" onClick={() => onPick(input)} disabled={!input}>体检</Button>
      </Filters>

      {!initialUserId ? (
        <Board><Blank filtered={false} thing="体检结果"
          action={<span style={{ fontSize: 'var(--t-sm)' }}>
            填一个账号 ID，看他 7 天窗口内累计了多少风险分、系统建议怎么处置。
            采集接上之前，任何人算出来都是 0 分。
          </span>} /></Board>
      ) : (
        <Board title={`账号 #${initialUserId} 的 7 天风险体检`}>
          <Async state={state}>{(d: any) => (
            <div>
              <KV items={[
                ['账号状态', <St domain="user" value={d.userStatus} />],
                ['7 天事件数', <span className="num">{num(d.eventCount7d)}</span>],
                ['计入分数的事件', <span className="num">{num(d.countedEvents)}</span>],
                ['建议处置', <St domain="riskAction" value={d.suggestedAction} />],
              ]} />

              {d.unmappedNote && (
                <div style={{ marginTop: 'var(--s3)' }}>
                  <Note tone="danger" title="有信号没被算进去">
                    {d.unmappedNote}
                    <div className="num" style={{ marginTop: 'var(--s2)', fontSize: 'var(--t-xs)' }}>
                      {(d.unmappedSignals ?? []).join('、')}
                    </div>
                  </Note>
                </div>
              )}

              <Sect title="最近的处置">
                {(d.recentActions ?? []).length === 0
                  ? <span style={{ fontSize: 'var(--t-sm)', color: 'var(--ink-3)' }}>
                      还没有对这个账号做过任何处置
                    </span>
                  : <MiniTable rows={d.recentActions} cols={[
                      ['时间', (r: any) => fmtTime(r.created_at ?? r.createdAt, true)],
                      ['处置', (r: any) => <St domain="riskAction" value={r.action} />],
                      ['触发分', (r: any) => <span className="num">{r.score_at_trigger ?? r.scoreAtTrigger}</span>],
                      ['解除时间', (r: any) => fmtTime(r.released_at ?? r.releasedAt)],
                    ]} />}
              </Sect>

              <div style={{ marginTop: 'var(--s4)' }}>
                <Note>
                  「建议处置」是按 7 天累计分算出来的档位，<b>不是已经执行的处置</b>。
                  要看真的做过什么，去「处置记录」那一栏。
                </Note>
              </div>
            </div>
          )}</Async>
        </Board>
      )}
    </>
  );
}
