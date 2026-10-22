/**
 * 答疑：提问 + 我的工单。
 *
 * 提问框上方先放一个公开问答库的检索：**大部分问题是重复的**，
 * 能当场答掉的就不该变成一张工单 —— 对学员是少等几个小时，
 * 对平台是少一次人工。检索走公开接口，不消耗提问配额。
 */
import React from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import {
  api, Async, Button, Card, dateTime, Empty, Field, Input, Markdown, PageHead, Select,
  Skeleton, Tag, useAsync, useToast, IconChat,
} from '@wangke/shared';
import type { MyCourse, QaTicket } from '../types';
import { Note, Row, Screen, Stack } from '../kit';
import { emitQaChanged, onQaChanged } from '../dongma';

export const QA_STATUS: Record<QaTicket['status'], { text: string; tone: 'ok' | 'warn' | 'brand' | 'neutral' }> = {
  AI_ANSWERED: { text: '已解答', tone: 'ok' },
  ESCALATED: { text: '已转人工，等老师回复', tone: 'warn' },
  HUMAN_ANSWERED: { text: '老师已回复', tone: 'brand' },
  CLOSED: { text: '已结束', tone: 'neutral' },
};

export default function Qa() {
  const [sp] = useSearchParams();
  const preCourse = Number(sp.get('course'));
  const preLesson = Number(sp.get('lesson'));

  const courses = useAsync(
    () => api.get<{ courses: MyCourse[] }>('/api/learn/my/courses').then((r) => r.courses),
    [],
  );
  const tickets = useAsync(
    () => api.get<{ tickets: QaTicket[] }>('/api/qa/my').then((r) => r.tickets),
    [],
  );

  // 右下角那个挂件写的是同一批工单。不订阅的话，在挂件里问完一句、
  // 关掉它，这一页的「我问过的」里没有那条 —— 学员会以为提问丢了。
  React.useEffect(() => onQaChanged(tickets.reload), [tickets.reload]);

  return (
    <Screen>
      <PageHead title="答疑" desc="课程内容有不懂的地方，问一句就行。" />
      <Stack gap="var(--s6)">
        <AskCard
          coursesState={courses}
          preCourse={Number.isFinite(preCourse) ? preCourse : undefined}
          preLesson={Number.isFinite(preLesson) && preLesson > 0 ? preLesson : undefined}
          onAsked={tickets.reload}
        />

        <div>
          <h2 style={{ fontSize: 'var(--t-h3)', marginBottom: 'var(--s3)' }}>我问过的</h2>
          {tickets.loading ? <Stack><Skeleton h={72} /><Skeleton h={72} /></Stack> : (
            <Async
              state={tickets}
              empty={
                <Card>
                  <Empty
                    icon={<IconChat size={22} />}
                    title="你还没有提过问题"
                    desc="看课的时候有不明白的地方，直接在上面问，通常几秒钟就有答复。"
                  />
                </Card>
              }
            >
              {(list) => (
                <Stack gap="var(--s3)">
                  {list.map((t) => <TicketRow key={t.id} t={t} />)}
                </Stack>
              )}
            </Async>
          )}
        </div>
      </Stack>
    </Screen>
  );
}

function TicketRow({ t }: { t: QaTicket }) {
  const s = QA_STATUS[t.status] ?? { text: t.status, tone: 'neutral' as const };
  return (
    <Link to={`/qa/${t.id}`} style={{ textDecoration: 'none', color: 'inherit' }}>
      <Card style={{ cursor: 'pointer' }}>
        <Row style={{ justifyContent: 'space-between' }} align="flex-start">
          <div style={{ minWidth: 0 }}>
            <div style={{ fontWeight: 500 }}>{t.title}</div>
            <div style={{ fontSize: 'var(--t-xs)', color: 'var(--ink-3)', marginTop: 4 }}>
              {dateTime(t.createdAt)} · {t.messageCount} 条对话
            </div>
          </div>
          <Tag tone={s.tone}>{s.text}</Tag>
        </Row>
      </Card>
    </Link>
  );
}

/* ------------------------------------------------------------------ 提问 */

type AskResult = {
  ticketId: number; status: QaTicket['status']; answered: boolean;
  answer: string | null; quotaLeftToday: number | null;
};

function AskCard({ coursesState, preCourse, preLesson, onAsked }: {
  coursesState: { loading: boolean; error?: string; data?: MyCourse[]; reload: () => void };
  preCourse?: number;
  preLesson?: number;
  onAsked: () => void;
}) {
  const nav = useNavigate();
  const toast = useToast();
  const [courseId, setCourseId] = React.useState<number | ''>(preCourse ?? '');
  const [title, setTitle] = React.useState('');
  const [content, setContent] = React.useState('');
  const [wantHuman, setWantHuman] = React.useState(false);
  const [busy, setBusy] = React.useState(false);
  const [result, setResult] = React.useState<AskResult | null>(null);

  const all = coursesState.data;
  const live = React.useMemo(() => (all ?? []).filter((c) => !c.expired), [all]);

  // 只有一门课时直接选中它。依赖用 memo 过的 live，
  // 否则每次渲染都是一个新数组，effect 会在每一帧上重跑。
  React.useEffect(() => {
    if (courseId === '' && live.length === 1) setCourseId(live[0].courseId);
  }, [live, courseId]);

  if (coursesState.loading) return <Skeleton h={240} />;
  if (coursesState.error) {
    return <Card><Note tone="danger">课程列表没加载出来，暂时不能提问：{coursesState.error}</Note></Card>;
  }
  if (live.length === 0) {
    return (
      <Card>
        <Empty
          icon={<IconChat size={22} />}
          title="要先有课程才能提问"
          desc="答疑是跟着课程走的：先激活课程，学的时候遇到不懂的再来问。"
          action={<Link to="/activate"><Button variant="accent">输入激活码</Button></Link>}
        />
      </Card>
    );
  }

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    try {
      const r = await api.post<AskResult>('/api/qa/ask', {
        courseId, lessonId: preLesson ?? null, title: title.trim(),
        content: content.trim(), wantHuman,
      });
      setResult(r);
      setTitle('');
      setContent('');
      onAsked();
      emitQaChanged();   // 挂件此刻可能正开着，让它也看见这条新工单
    } catch (e2) {
      toast({ tone: 'danger', text: (e2 as Error).message || '提问失败，请重试' });
    } finally {
      setBusy(false);
    }
  };

  if (result) {
    return (
      <Card>
        <Stack>
          <Row style={{ justifyContent: 'space-between' }}>
            <div style={{ fontWeight: 600 }}>{result.answered ? '懂妈答复你了' : '已经转给老师了'}</div>
            <Tag tone={result.answered ? 'ok' : 'warn'}>
              {QA_STATUS[result.status]?.text ?? result.status}
            </Tag>
          </Row>
          {result.answered && result.answer ? (
            <div style={{
              lineHeight: 'var(--lh-body)', overflowWrap: 'anywhere',
              background: 'var(--surface-2)', padding: 'var(--s4)', borderRadius: 'var(--r-md)',
            }}><Markdown text={result.answer} /></div>
          ) : (
            <Note tone="warn">
              这个问题需要老师来答。回复会出现在这张工单里，你也会在「我问过的」里看到它。
            </Note>
          )}
          {result.quotaLeftToday != null && (
            <div style={{ fontSize: 'var(--t-xs)', color: 'var(--ink-3)' }}>
              今天还可以提问 {result.quotaLeftToday} 次
            </div>
          )}
          <Row gap="var(--s3)">
            <Button onClick={() => nav(`/qa/${result.ticketId}`)}>查看这条并追问</Button>
            <Button variant="ghost" onClick={() => setResult(null)}>再问一个</Button>
          </Row>
        </Stack>
      </Card>
    );
  }

  return (
    <Card>
      <form onSubmit={submit}>
        <Field label="哪门课">
          <Select value={courseId} onChange={(e) => setCourseId(Number(e.target.value))}>
            <option value="" disabled>请选择课程</option>
            {live.map((c) => <option key={c.courseId} value={c.courseId}>{c.title}</option>)}
          </Select>
        </Field>

        <Field label="一句话说明问题">
          <Input
            value={title} onChange={(e) => setTitle(e.target.value.slice(0, 60))}
            placeholder="例如：第 3 讲里说的那个流程顺序是什么"
            disabled={busy}
          />
        </Field>

        <Field label="详细说说" hint={preLesson ? '已经带上了你正在看的那一讲，老师能直接定位' : undefined}>
          <textarea
            value={content} onChange={(e) => setContent(e.target.value.slice(0, 1000))}
            rows={4} placeholder="把不明白的地方讲清楚一点，答复会更准" disabled={busy}
            style={{
              width: '100%', padding: '10px 12px', background: 'var(--surface)',
              border: '1px solid var(--line-strong)', borderRadius: 'var(--r-md)',
              outline: 'none', resize: 'vertical', lineHeight: 'var(--lh-body)',
              fontFamily: 'var(--font)', fontSize: 'var(--t-body)',
            }}
          />
        </Field>

        <label style={{ display: 'flex', gap: 'var(--s3)', alignItems: 'flex-start',
                        cursor: 'pointer', marginBottom: 'var(--s4)' }}>
          <input type="checkbox" checked={wantHuman} onChange={(e) => setWantHuman(e.target.checked)}
                 style={{ marginTop: 3, width: 18, height: 18, flexShrink: 0 }} />
          <span style={{ fontSize: 'var(--t-sm)', color: 'var(--ink-2)' }}>
            直接转人工老师
            {/* 「助教」是接懂妈之前的旧叫法。对话气泡里 AI 那一档已经统一署名「懂妈」，
                这里再叫助教，学员会以为答他的是另一个人。
                后半句也一并说准：追问同样先由懂妈接，答不上来才转老师 —— 而不是
                「一追问就转人工」（那是修好之前的行为）。 */}
            <span style={{ display: 'block', color: 'var(--ink-3)', fontSize: 'var(--t-xs)' }}>
              不勾的话先由懂妈答；追问也是懂妈先接，答不上来才转给老师
            </span>
          </span>
        </label>

        {/* 门槛与挂件保持一致：非空即可，与后端 requireText 的口径相同。
            两边写不同的数，就会出现「同一句话在右下角发得出去、在这一页发不出去」。
            注释放在标签【外面】：写在开标签的属性之间不是 JSX 注释，是语法错。 */}
        <Button type="submit" block size="lg" loading={busy}
                disabled={!courseId || !title.trim() || !content.trim()}>
          提交问题
        </Button>
      </form>
    </Card>
  );
}
