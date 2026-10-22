/**
 * 一张答疑工单：对话 + 追问。
 *
 * 消息按发送方分左右两侧，学员自己的在右。这不是装饰 ——
 * 一条条平铺的时候，"这句是我问的还是老师答的"要读完才知道，
 * 而这一页的人正在找答案，不是在读小说。
 */
import React from 'react';
import { Link, useParams } from 'react-router-dom';
import {
  api, Async, Button, Card, dateTime, Markdown, PageHead, Skeleton, Tag, useAsync, useToast,
} from '@wangke/shared';
import type { QaDetail as QaDetailData, QaMessage } from '../types';
import { Note, Row, Screen, Stack } from '../kit';
import { QA_STATUS } from './Qa';
import { emitQaChanged, onQaChanged, DongmaFace } from '../dongma';

export default function QaDetailPage() {
  const { ticketId } = useParams();
  const id = Number(ticketId);
  const state = useAsync<QaDetailData>(
    () => api.get<{ ticket: QaDetailData }>(`/api/qa/${id}`).then((r) => r.ticket),
    [id],
  );

  // 挂件里也能追问这同一张工单。两边各存一份、谁都不通知谁的话，
  // 在挂件里追问完再看这一页，缺的正是刚说的那两句。
  React.useEffect(() => onQaChanged(state.reload), [state.reload]);

  return (
    <Screen maxWidth={620}>
      <PageHead
        title="答疑详情"
        extra={<Link to="/qa"><Button variant="quiet">‹ 我问过的</Button></Link>}
      />
      {state.loading ? <Stack><Skeleton h={80} /><Skeleton h={120} /></Stack> : (
        <Async state={state}>
          {(t) => <Detail ticket={t} reload={state.reload} />}
        </Async>
      )}
    </Screen>
  );
}

function Detail({ ticket, reload }: { ticket: QaDetailData; reload: () => void }) {
  const toast = useToast();
  const [text, setText] = React.useState('');
  const [busy, setBusy] = React.useState(false);
  // 临时的两条：学员刚发出的那句 + 正在长出来的答复。流一结束就清空，
  // 改由 reload() 取回来的工单说了算 —— 屏幕上留下的必须是库里有的那一份。
  const [pending, setPending] = React.useState<string | null>(null);
  const [streamed, setStreamed] = React.useState('');
  const s = QA_STATUS[ticket.status] ?? { text: ticket.status, tone: 'neutral' as const };
  const closed = ticket.status === 'CLOSED';

  // 追问走流式，和右下角挂件同一条接口。两处必须一致 ——
  // 一边边答边出、另一边转四秒圈，学员会以为其中一处坏了。
  const send = async (e: React.FormEvent) => {
    e.preventDefault();
    const sent = text.trim();
    setBusy(true);
    setText('');
    setPending(sent);
    setStreamed('');
    let cutOff = false;
    let frameErr = '';
    try {
      await api.stream(`/api/qa/${ticket.id}/reply/stream`, { content: sent }, (f) => {
        if (typeof f.delta === 'string') { setStreamed((s) => s + f.delta); return; }
        if (f.error) frameErr = String(f.error);
        if (f.aborted) cutOff = true;
      });
    } catch (e2) {
      // 流没建起来 = 这句没进库，把它还回输入框
      setPending(null); setStreamed(''); setText(sent); setBusy(false);
      toast({ tone: 'danger', text: (e2 as Error).message || '发送失败，请重试' });
      return;
    }
    if (frameErr) {
      setPending(null); setStreamed(''); setText(sent); setBusy(false);
      toast({ tone: 'danger', text: frameErr });
      return;
    }
    // 没答完的那一段服务端一个字都没落库，这里也必须撤掉 ——
    // 半句话读起来和一整句一样通顺，缺的往往正是后半段的注意事项
    // tone 用 danger 不用 info：这不是"顺便说一声"，是【你刚看到的那段不算数】。
    // 说轻了，他会以为答案还在，只是没显示全。
    if (cutOff) toast({ tone: 'danger', text: '这条懂妈没答完，刚才那段不作数，已经转给老师了' });
    setPending(null);
    setStreamed('');
    setBusy(false);
    reload();
    emitQaChanged();
  };

  return (
    <Stack gap="var(--s5)">
      <Card>
        <Row style={{ justifyContent: 'space-between' }} align="flex-start">
          <div style={{ minWidth: 0 }}>
            <div style={{ fontSize: 'var(--t-h3)', fontWeight: 600 }}>{ticket.title}</div>
            <div style={{ fontSize: 'var(--t-xs)', color: 'var(--ink-3)', marginTop: 4 }}>
              {dateTime(ticket.createdAt)}
              {/* 这里原来印的是 lessonId：一个全库自增的主键，于是一门四讲的课
                  能显示出「关联第 12 讲」，而学员会去目录里找第 12 讲。
                  现在只写标题，一个数都不写 —— 库里没有一列真的是「第几讲」
                  （sort_no 是留了间隔的排序键），而标题本身带着编号。 */}
              {ticket.lessonTitle ? ` · 关联「${ticket.lessonTitle}」` : ''}
            </div>
          </div>
          <Tag tone={s.tone}>{s.text}</Tag>
        </Row>
        {ticket.status === 'ESCALATED' && (
          <div style={{ marginTop: 'var(--s4)' }}>
            <Note tone="warn">
              这个问题已经转给老师了{ticket.slaDueAt ? `，预计 ${dateTime(ticket.slaDueAt)} 前回复` : ''}。
              回复会直接出现在下面，不用反复刷新。
            </Note>
          </div>
        )}
      </Card>

      {ticket.messages.length === 0 ? (
        <Card><Note>这张工单还没有任何对话内容。</Note></Card>
      ) : (
        <Stack gap="var(--s3)">
          {ticket.messages.map((m, i) => <Bubble key={i} m={m} />)}
        </Stack>
      )}

      {pending && (
        <Stack gap="var(--s3)">
          <Bubble m={{ senderType: 'STUDENT', content: pending, citations: null, createdAt: '' }} />
          <div style={{ display: 'flex', justifyContent: 'flex-start' }}>
            <div style={{ maxWidth: '86%' }}>
              <div style={{ fontSize: 'var(--t-xs)', color: 'var(--ink-3)', marginBottom: 4,
                             display: 'flex', alignItems: 'center', gap: 5 }}>
                <DongmaFace size={20} />懂妈
              </div>
              <div className={streamed ? 'wk-qa-live' : undefined} style={{
                background: 'var(--surface)', border: '1px solid var(--line)',
                borderRadius: 'var(--r-lg)', padding: 'var(--s3) var(--s4)',
                lineHeight: 'var(--lh-body)', overflowWrap: 'anywhere',
              }}>
                {streamed ? (
                  <>
                    <Markdown text={streamed} />
                    {/* 还没说完的记号。没有它，一段刚长到一半的答复
                        看起来和一段说完了的一模一样。 */}
                    <span className="wk-qa-caret" aria-hidden />
                  </>
                ) : <span style={{ color: 'var(--ink-3)' }}>懂妈正在想…</span>}
              </div>
            </div>
          </div>
          <style>{LIVE_CSS}</style>
        </Stack>
      )}

      {closed ? (
        <Note>这条答疑已经结束。还有新问题的话，回「答疑」重新提一条。</Note>
      ) : (
        <Card>
          <form onSubmit={send}>
            <textarea
              value={text} onChange={(e) => setText(e.target.value.slice(0, 1000))}
              rows={3} placeholder="还有哪里不清楚？接着问" disabled={busy}
              style={{
                width: '100%', padding: '10px 12px', background: 'var(--surface)',
                border: '1px solid var(--line-strong)', borderRadius: 'var(--r-md)',
                outline: 'none', resize: 'vertical', lineHeight: 'var(--lh-body)',
                fontFamily: 'var(--font)', fontSize: 'var(--t-body)',
              }}
            />
            <div style={{ marginTop: 'var(--s3)' }}>
              <Button type="submit" loading={busy} disabled={!text.trim()}>追问</Button>
            </div>
          </form>
        </Card>
      )}
    </Stack>
  );
}

const LIVE_CSS = `
.wk-qa-caret {
  display: inline-block; width: 2px; height: 1em; vertical-align: -0.15em;
  margin-left: 2px; background: var(--brand); animation: wk-qa-blink 1s steps(2) infinite;
}
@keyframes wk-qa-blink { 50% { opacity: 0; } }
/* 光标要接在最后一段末尾。只改段落、且只在正在长的那一条上改 ——
   把 ul/pre 也改成 inline，或者作用到已经答完的气泡上，会把版式弄坏。 */
.wk-qa-live .wk-md > p:last-child { display: inline; }
`;

function Bubble({ m }: { m: QaMessage }) {
  const mine = m.senderType === 'STUDENT';
  // AI 这一档统一署名「懂妈」：它是自动答复这条渠道的名字。无论这次是命中了公开问答库
  // （人写好的答案）还是答疑助手现答的，对学员来说都是同一个答复人 —— 两种情况分别署名
  // 「助教」和「懂妈」，只会让人以为平台上有两个不同的人在答，而他分不清该信哪个。
  const ai = !mine && m.senderType !== 'MENTOR';
  const who = mine ? '我' : m.senderType === 'MENTOR' ? '老师' : '懂妈';
  return (
    <div style={{ display: 'flex', justifyContent: mine ? 'flex-end' : 'flex-start' }}>
      <div style={{ maxWidth: '86%' }}>
        {/* 署名行。带头像那一档要 flex —— img 按 inline 排会顶高行高，
            而「老师」那一档没有头像，两种气泡看起来就像用了两个字号。 */}
        <div style={{
          fontSize: 'var(--t-xs)', color: 'var(--ink-3)', marginBottom: 4,
          display: 'flex', alignItems: 'center', gap: 5,
          justifyContent: mine ? 'flex-end' : 'flex-start',
        }}>{ai && <DongmaFace size={20} />}{who} · {dateTime(m.createdAt)}</div>
        <div style={{
          background: mine ? 'var(--brand-soft)' : 'var(--surface)',
          border: `1px solid ${mine ? 'var(--brand-line)' : 'var(--line)'}`,
          borderRadius: 'var(--r-lg)', padding: 'var(--s3) var(--s4)',
          lineHeight: 'var(--lh-body)', overflowWrap: 'anywhere',
          // pre-wrap 只给学员自己那一侧：对面走 Markdown，块之间已经有真换行，
          // 再 pre-wrap 一遍每两段就多出一个空行
          ...(mine ? { whiteSpace: 'pre-wrap' as const } : null),
        }}>{mine ? m.content : <Markdown text={m.content} />}</div>
        <Citations raw={m.citations} />
      </div>
    </div>
  );
}

/**
 * 出处。后端把 citations 原样以 JSON 字符串下发，解析失败时**什么都不显示** ——
 * 出处是答案可信度的支撑，显示一段解析失败的原始 JSON 只会让人怀疑整条答复。
 */
function Citations({ raw }: { raw: string | null }) {
  if (!raw) return null;
  let items: { lesson_id?: number; ts_sec?: number; snippet?: string }[] = [];
  try {
    const parsed = JSON.parse(raw);
    if (Array.isArray(parsed)) items = parsed;
  } catch {
    return null;
  }
  if (items.length === 0) return null;
  return (
    <div style={{ marginTop: 'var(--s2)', fontSize: 'var(--t-xs)', color: 'var(--ink-3)' }}>
      出处：
      {items.map((c, i) => (
        <span key={i} style={{ marginLeft: i ? 'var(--s2)' : 4 }}>
          {c.lesson_id ? `第 ${c.lesson_id} 讲` : ''}
          {c.ts_sec != null ? ` ${Math.floor(c.ts_sec / 60)}:${String(c.ts_sec % 60).padStart(2, '0')}` : ''}
        </span>
      ))}
    </div>
  );
}
