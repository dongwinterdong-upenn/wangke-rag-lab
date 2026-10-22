import React from 'react';
import { Link } from 'react-router-dom';
import { api } from '@wangke/shared/api';
import { PageHead } from '@wangke/shared/shell';
import { Async, Button, Table, useAsync } from '@wangke/shared/ui';
import { Markdown } from '@wangke/shared/Markdown';
import type { Col } from '@wangke/shared/ui';
import {
  ActionDialog, Blank, Board, Ctl, DetailModal, Filters, KV, Note, Pick, Sect, St, TextIn,
} from '../lib/kit';
import { fmtTime, label, num, options } from '../lib/fmt';
import { hasFilter, qs, useQ } from '../lib/q';

/**
 * 答疑工单。
 *
 * <h3>状态筛选是真的，标题筛选不是 —— 两者必须在界面上分得清</h3>
 * 队列接口接受 `status`（服务端筛）与 `limit`，<b>没有</b>关键词检索、没有分页。
 * 所以标题那一栏仍是在已取回的这一批里筛的，界面上必须说清楚，
 * 否则运营会以为「筛不到 = 没有」。
 *
 * <p>★ 状态这一档原来是假的：接口恒定只回 ESCALATED，而下拉给了四个值。
 * 另外三档永远筛出 0 条 —— 而「这个接口不给这类工单」和「没有这类工单」
 * 在屏幕上长得一模一样，都是一张空表。更实际的后果是导师一回复，工单当场变成
 * HUMAN_ANSWERED 离开队列，于是「关闭工单」「沉淀成公开问答」这两个动作
 * <b>再也没有入口</b>（后台没有按 id 找工单的地方），刷新一下那张单就消失了。
 */
export function Qa() {
  const { sp, get, int, set, reset } = useQ();
  const status = get('status'), keyword = get('keyword');
  const limit = int('limit', 50);
  const sel = get('sel');
  const [rev, bump] = React.useReducer((n: number) => n + 1, 0);

  // 状态进服务端；关键词留在客户端（接口没有检索）。两者的边界就是这一行
  const queue = useAsync<any>(
    () => api.get(`/api/qa/admin/queue${qs({ status, limit })}`), [status, limit, rev]);

  const all: any[] = queue.data?.tickets ?? [];
  const rows = all.filter(t => !keyword || String(t.title ?? '').includes(keyword));
  const filtered = hasFilter(sp, ['sel', 'limit']);

  const cols: Col<any>[] = [
    { key: 'id', title: 'ID', numeric: true, width: 60 },
    { key: 'title', title: '问题', render: r => (
      <div style={{ minWidth: 220, maxWidth: 420 }}>{r.title}</div>) },
    { key: 'status', title: '状态', render: r => <St domain="ticket" value={r.status} /> },
    { key: 'escalateReason', title: '为什么转人工', render: r => (
      <span style={{ fontSize: 'var(--t-xs)', color: 'var(--ink-2)' }}>
        {r.escalateReason === 'LOW_CONFIDENCE' ? 'AI 没把握'
          : r.escalateReason === 'USER_REQUEST' ? '学员要求人工'
          : r.escalateReason === 'SAFETY_BLOCK' ? '内容安全拦截'
          : '—'}
      </span>) },
    { key: 'courseId', title: '课程', numeric: true,
      render: r => <span className="num">#{r.courseId}</span> },
    { key: 'slaDueAt', title: '应答期限', render: r => {
      const due = r.slaDueAt ? new Date(String(r.slaDueAt).replace(' ', 'T')) : null;
      const late = due ? +due < Date.now() : false;
      return <span className="num" style={{ color: late ? 'var(--danger)' : undefined }}>
        {fmtTime(r.slaDueAt)}{late ? '（已超时）' : ''}
      </span>;
    } },
    { key: 'createdAt', title: '提问时间', render: r => fmtTime(r.createdAt) },
  ];

  return (
    <>
      <PageHead title="答疑工单"
        desc="学员问 AI，AI 没把握或学员要求人工时会转到这里。回复完记得关闭，好答案沉淀进公开问答库。" />

      <Filters active={filtered} onReset={() => reset(['limit'])}>
        <Ctl label="状态" width={160}>
          {/* all={false}：这个下拉没有「全部」这一档 —— 接口一次只给一种状态。
              给了「全部」而后端做不到，就又回到「筛了但没生效」那种错觉。 */}
          <Pick value={status || 'ESCALATED'} all={false}
                onChange={v => set({ status: v })} options={options('ticket')} />
        </Ctl>
        <Ctl label="标题包含（本批内筛）" width={220} grow>
          <TextIn defaultValue={keyword} placeholder="输入后回车"
                  onKeyDown={e => { if (e.key === 'Enter') set({ keyword: e.currentTarget.value }); }} />
        </Ctl>
        <Ctl label="一次取多少条" width={140}>
          <Pick value={String(limit)} all={false} onChange={v => set({ limit: v })}
                options={[{ value: '20', text: '20' }, { value: '50', text: '50' },
                          { value: '100', text: '100（上限）' }]} />
        </Ctl>
      </Filters>

      <div style={{ marginBottom: 'var(--s4)' }}>
        <Note>
          默认看的是<b>待处理</b>（等人工回复）。换状态是<b>服务端</b>筛的 ——
          回复完的工单会离开待处理档，要关闭它或把答案沉淀进公开问答库，
          切到「人工已答」就能找回来（这一档按最近处理的排在最前）。<br />
          「标题包含」不一样，它只在<b>已取回的这 {limit} 条</b>里筛，
          而且接口没有分页 —— 筛不到不代表没有。工单多的时候先按应答期限处理最紧的那些。
        </Note>
      </div>

      <Board flush title={`${label('ticket', status || 'ESCALATED')} · 取回 ${all.length} 条`
                          + `${keyword ? `，标题筛出 ${rows.length} 条` : ''}`}>
        <Async state={queue}>{() => (
          <Table cols={cols} rows={rows} rowKey={r => r.id}
                 onRowClick={r => set({ sel: r.id }, true)}
                 empty={<Blank filtered={filtered} thing="工单" onClear={() => reset(['limit'])}
                   action={<span style={{ fontSize: 'var(--t-sm)' }}>
                     {/* 这句话只有在看「待处理」那一档时才成立。切到「已关闭」看到空表
                         却被告知「AI 都答上了」，说的是另一件事 */}
                     {(status || 'ESCALATED') === 'ESCALATED'
                       ? '待处理队列是空的 —— 学员的问题懂妈都答上了，没有需要人工接手的。'
                       : `当前没有「${label('ticket', status)}」的工单。`}
                   </span>} />} />
        )}</Async>
      </Board>

      {sel && <TicketDetail id={sel} onClose={() => set({ sel: '' }, true)} onChanged={bump} />}
    </>
  );
}

function TicketDetail({ id, onClose, onChanged }: {
  id: string; onClose: () => void; onChanged: () => void;
}) {
  const [rev, bump] = React.useReducer((n: number) => n + 1, 0);
  const state = useAsync<any>(() => api.get(`/api/qa/admin/${id}`), [id, rev]);
  const [dlg, setDlg] = React.useState<null | 'reply' | 'assign' | 'close' | 'faq'>(null);
  const t = state.data?.ticket;
  const after = () => { bump(); onChanged(); };

  return (
    <DetailModal open onClose={onClose} width={800}
      title={t ? t.title : `工单 #${id}`}
      subtitle={t ? <><St domain="ticket" value={t.status} /> · 提问于 {fmtTime(t.createdAt)}</> : undefined}
      actions={t && (<>
        <Button variant="quiet" onClick={() => setDlg('assign')}>指派导师</Button>
        <Button variant="quiet" onClick={() => setDlg('faq')}
                disabled={!!t.publishedFaqId}>
          {t.publishedFaqId ? '已沉淀为常见问答' : '沉淀为常见问答'}
        </Button>
        {t.status !== 'CLOSED' && <>
          <Button variant="ghost" onClick={() => setDlg('close')}>关闭工单</Button>
          <Button onClick={() => setDlg('reply')}>回复学员</Button>
        </>}
      </>)}>
      <Async state={state}>{(d: any) => {
        const g = d.ticket;
        return (
          <div>
            <KV items={[
              ['工单 ID', <span className="num">#{g.id}</span>],
              ['状态', <St domain="ticket" value={g.status} />],
              ['课程', <span className="num">#{g.courseId}</span>],
              ['讲次', g.lessonId ? <span className="num">#{g.lessonId}</span> : '未指定'],
              ['应答期限', fmtTime(g.slaDueAt)],
              ['已沉淀为问答', g.publishedFaqId ? `常见问答 #${g.publishedFaqId}` : '否'],
              ['最后更新', fmtTime(g.updatedAt)],
            ]} />

            <Sect title="对话">
              <div style={{ display: 'grid', gap: 'var(--s3)' }}>
                {(g.messages ?? []).map((m: any, i: number) => {
                  const mine = m.senderType === 'MENTOR';
                  const ai = m.senderType === 'AI';
                  return (
                    <div key={i} style={{
                      padding: 'var(--s3)', borderRadius: 'var(--r-md)',
                      background: mine ? 'var(--brand-soft)' : ai ? 'var(--surface-2)' : 'var(--surface)',
                      border: '1px solid var(--line)',
                    }}>
                      <div style={{ fontSize: 'var(--t-xs)', color: 'var(--ink-3)',
                                    marginBottom: 'var(--s1)' }}>
                        {/* 「AI 助教」是接懂妈之前的旧叫法。学员端这一档已经统一署名
                            「懂妈」，导师这边还叫助教，两边看的是同一条消息却各有一个名字，
                            对着学员核对时会对不上。 */}
                        {m.senderType === 'STUDENT' ? '学员' : ai ? '懂妈' : '导师 / 平台'}
                        · {fmtTime(m.createdAt, true)}
                      </div>
                      {/* 学员那一档保持纯文本：他打的 `**` 就是两个星号。
                          导师要按原样核对学员到底写了什么，替他渲染等于替他改写。 */}
                      <div style={{ fontSize: 'var(--t-sm)', lineHeight: 'var(--lh-body)',
                                    overflowWrap: 'anywhere',
                                    ...(m.senderType === 'STUDENT'
                                        ? { whiteSpace: 'pre-wrap' as const } : null) }}>
                        {m.senderType === 'STUDENT' ? m.content : <Markdown text={m.content} />}
                      </div>
                    </div>
                  );
                })}
                {(g.messages ?? []).length === 0 && (
                  <span style={{ fontSize: 'var(--t-sm)', color: 'var(--ink-3)' }}>还没有对话内容</span>
                )}
              </div>
            </Sect>
          </div>
        );
      }}</Async>

      {t && <Dialogs which={dlg} close={() => setDlg(null)} id={id} onDone={after} />}
    </DetailModal>
  );
}

function Dialogs({ which, close, id, onDone }: {
  which: string | null; close: () => void; id: string; onDone: () => void;
}) {
  const [content, setContent] = React.useState('');
  const [mentor, setMentor] = React.useState('');
  const [q, setQ] = React.useState('');
  const [a, setA] = React.useState('');
  React.useEffect(() => { setContent(''); setMentor(''); setQ(''); setA(''); }, [which]);

  return (
    <>
      <ActionDialog open={which === 'reply'} onClose={close} verb="发送" onDone={onDone} width={600}
        title="回复学员" disabled={!content.trim()}
        consequences={<>回复会直接推给学员，<b>发出去改不了</b>。工单转为「人工已答」。</>}
        onConfirm={() => api.post(`/api/qa/admin/${id}/reply`, { content: content.trim() })}>
        <label style={{ display: 'block' }}>
          <span style={{ display: 'block', fontSize: 'var(--t-sm)', color: 'var(--ink-2)',
                         marginBottom: 'var(--s2)', fontWeight: 500 }}>回复内容</span>
          <textarea value={content} onChange={e => setContent(e.target.value)} rows={6}
            placeholder="说人话。学员看不懂的回复等于没回。"
            style={{
              width: '100%', padding: 'var(--s3)', background: 'var(--surface)',
              border: '1px solid var(--line-strong)', borderRadius: 'var(--r-md)',
              outline: 'none', resize: 'vertical', fontFamily: 'var(--font)',
              fontSize: 'var(--t-body)', lineHeight: 'var(--lh-body)',
            }} />
        </label>
      </ActionDialog>

      <ActionDialog open={which === 'assign'} onClose={close} verb="指派" onDone={onDone}
        title="把这张工单指派给一位导师" disabled={!mentor.trim()}
        consequences={<>
          指派之后，<b>只有这位导师</b>能看到和回复这张工单。指派可以改，改成另一个人即可。<br />
          指派只有平台管理员能做 —— 让导师自己认领的话，「导师只能看自己的工单」就形同虚设。
        </>}
        onConfirm={() => api.post(`/api/qa/admin/${id}/assign`, { mentorUserId: Number(mentor) })}>
        <Ctl label="导师的账号 ID" width={200}
             hint={<>在<Link to="/users?role=MENTOR">学员管理里按角色筛「导师」</Link>能查到。</>}>
          <TextIn value={mentor} inputMode="numeric"
                  onChange={e => setMentor(e.target.value.replace(/\D/g, ''))} />
        </Ctl>
      </ActionDialog>

      <ActionDialog open={which === 'close'} onClose={close} verb="关闭" tone="danger" onDone={onDone}
        title="关闭这张工单"
        consequences={<>关闭之后学员不能在这张工单里继续追问，他要问得新开一张。回复记录会保留。</>}
        onConfirm={() => api.post(`/api/qa/admin/${id}/close`)} />

      <ActionDialog open={which === 'faq'} onClose={close} verb="沉淀" onDone={onDone} width={620}
        title="沉淀为公开的常见问答" disabled={!q.trim() || !a.trim()}
        consequences={<>
          沉淀之后这条问答对<b>所有学员公开</b>，别人问类似问题时会先被它命中。<br />
          <b>问题和答案要重新写一遍，不要照抄工单原文。</b>工单里常带着学员的姓名、手机号、订单号，
          原样搬进公开库就是把个人信息发到公网上 —— 而漏掉一处不会有任何提示。
        </>}
        onConfirm={() => api.post(`/api/qa/admin/${id}/publish`, { question: q.trim(), answer: a.trim() })}>
        <div style={{ display: 'grid', gap: 'var(--s4)' }}>
          <Ctl label="问题（重新写，去掉个人信息）" width={320} grow>
            <TextIn value={q} onChange={e => setQ(e.target.value)}
                    placeholder="例：激活码提示已被使用怎么办？" />
          </Ctl>
          <label style={{ display: 'block' }}>
            <span style={{ display: 'block', fontSize: 'var(--t-sm)', color: 'var(--ink-2)',
                           marginBottom: 'var(--s2)', fontWeight: 500 }}>答案（重新写）</span>
            <textarea value={a} onChange={e => setA(e.target.value)} rows={6}
              style={{
                width: '100%', padding: 'var(--s3)', background: 'var(--surface)',
                border: '1px solid var(--line-strong)', borderRadius: 'var(--r-md)',
                outline: 'none', resize: 'vertical', fontFamily: 'var(--font)',
                fontSize: 'var(--t-body)', lineHeight: 'var(--lh-body)',
              }} />
          </label>
        </div>
      </ActionDialog>
    </>
  );
}
