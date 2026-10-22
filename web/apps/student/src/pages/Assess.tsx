/**
 * 测评系统 —— 课程测评、答疑、结业证明三样在一处。
 *
 * ## 为什么这三样归成一个模块
 *
 * 它们是学员"学完之后"要做的事，也是同一条链：考过了才有结业证明，
 * 考之前不懂的地方去答疑。散在三个标签里的时候，学员要自己把这条链拼出来，
 * 而最常见的结果是他压根不知道有测评。
 *
 * ## 这一页不自己判断"能不能考"
 *
 * 每一份卷能不能考、为什么不能，整句都由后端给（就是开考闸门那一句）。
 * 这里只负责显示。在前端重算一遍进度够不够，两处式子迟早分叉 ——
 * 分叉的表现是这一页写着"可以参加"，点进去被拒。
 *
 * ## 一行是一份卷，不是一门课（2026-09-03 多套试卷）
 *
 * HomeService.assessBlock 现在按卷下发：一门课底下是一张综合卷加四张章节卷，
 * 另有一张不挂课程、对所有注册账号开放的卷。这一页此前照着"一门课一行"写，
 * 于是五份卷渲染成五行同名的课程名、卷名一个字都不出现，开放卷那一行连名字都没有
 * （它没有 courseTitle），CTA 还全部指向 `/exam?course=1` —— 五个按钮点下去到同一处。
 * 所以标题一律用卷名，课程名退到上面一行小字（与 Exam.tsx 的选卷页同形）。
 */
import React from 'react';
import { Link } from 'react-router-dom';
import {
  api, Async, Button, Card, PageHead, Progress, Tag, useAsync,
  IconCert, IconChat,
} from '@wangke/shared';
import type { AssessState, Overview, OverviewAssessItem } from '../types';
import { Note, Row, Screen, Stack } from '../kit';

export default function Assess() {
  const state = useAsync(() => api.get<Overview>('/api/home/overview'), []);

  return (
    <Screen>
      <style>{ASSESS_CSS}</style>
      <PageHead title="测评系统" desc="课程测评、答疑、结业证明都在这里。" />
      <Async state={state}>{(o) => (
        <Stack gap="var(--s6)">
          <ExamSection o={o} />
          <QaSection o={o} />
          <CertSection o={o} />
        </Stack>
      )}</Async>
    </Screen>
  );
}

/* ================================================================ 课程测评 */

function ExamSection({ o }: { o: Overview }) {
  const a = o.assess;
  // 只有一门课时不必在每张卡上重复课程名 —— 五张卡上五行同样的字是噪声不是信息。
  // 两门课以上才写，那时它是分辨这份卷属于谁的唯一线索。判据与 Exam.tsx 的选卷页一致。
  const multiCourse = a
    ? new Set(a.items.filter((it) => it.courseId).map((it) => it.courseId)).size > 1
    : false;
  return (
    <section>
      {/* 标题不能写死"课程测评"：这张列表里也有开放卷（不用买课、任何注册账号都能考），
          把它排在"课程测评"底下，学员会以为自己买过这门课，或者以为它属于某门课。
          全是开放卷时（一门课都没买的注册学员就是这种）标题必须换掉。 */}
      <h2 className="wk-assess-h2">
        {a && a.items.length > 0 && a.items.every((it) => !it.courseId) ? '开放测评' : '测评'}
      </h2>
      {!a ? (
        // 取不到就说取不到。这里最不该做的是渲染一个空列表 ——
        // 空列表读起来是"你没有可以参加的测评"，那是一句我们并不知道真假的话。
        <Card><Note tone="warn">测评状态暂时取不到。可以稍后刷新，或直接去测评页看看。</Note></Card>
      ) : a.items.length === 0 ? (
        <Card>
          {/* 开放卷对任何注册账号都可见，所以这里空掉通常不是"你还没买课"。
              别再写"你还没有课程"—— 那句话把学员支去激活，而激活并不会让
              一份都没上架的测评页多出任何东西。 */}
          <Note>现在没有可以参加的测评。开放测评上架后会直接出现在这里；课程测评要先激活对应的课程。</Note>
        </Card>
      ) : (
        <Stack>
          {a.items.map((it) => <ExamRow key={it.paperId} it={it} showCourse={multiCourse} />)}
          {a.skippedCount > 0 && (
            // 悄悄截断的界面上，"没有可以考的"是错的且看不出来。宁可说得难看一点。
            <Note tone="warn">
              你能参加的卷较多，还有 {a.skippedCount} 份没有在这里逐份核对状态。
              到<Link to="/exam">测评页</Link>可以看到全部试卷。
            </Note>
          )}
        </Stack>
      )}
    </section>
  );
}

const STATE_TEXT: Record<AssessState, { text: string; tone: 'ok' | 'warn' | 'brand' | 'neutral' }> = {
  READY:       { text: '可以参加',   tone: 'brand' },
  IN_PROGRESS: { text: '进行中',     tone: 'warn' },
  PASSED:      { text: '已通过',     tone: 'ok' },
  BLOCKED:     { text: '暂不能参加', tone: 'neutral' },
  EXPIRED:     { text: '权益已到期', tone: 'neutral' },
  UNAVAILABLE: { text: '暂无测评',   tone: 'neutral' },
};

/** 后端哪天多出一个状态时，这一页要么照旧显示、要么整页白屏，不能是后者。 */
const UNKNOWN_STATE = { text: '状态未知', tone: 'neutral' as const };

/**
 * 这一份卷在测评页的地址。
 *
 * 带上 course 只为了答题页那个"回课程"的链接（与 Exam.tsx 的 selfQuery 同形）——
 * 开放卷没有课程，这一段就不出现，链接也不会指向一个不存在的课。
 * 有在跑/已通过的场次时直接落到那一场上：继续答题与看成绩都在答题页里。
 */
function paperHref(it: OverviewAssessItem, attemptId?: number): string {
  return `/exam?paper=${it.paperId}`
    + (it.courseId ? `&course=${it.courseId}` : '')
    + (attemptId ? `&attempt=${attemptId}` : '');
}

function ExamRow({ it, showCourse }: { it: OverviewAssessItem; showCourse?: boolean }) {
  const s = STATE_TEXT[it.state] ?? UNKNOWN_STATE;
  // 只有真的能走下一步时才给按钮。给一个点了被拒的按钮，比不给更伤 ——
  // 学员会认为是系统坏了，而不是自己还不满足条件。
  const cta = it.state === 'READY' ? { text: '开始测评', variant: 'primary' as const }
    : it.state === 'IN_PROGRESS' ? { text: '继续答题', variant: 'accent' as const }
    : it.state === 'PASSED' ? { text: '查看成绩', variant: 'ghost' as const }
    : it.state === 'BLOCKED' ? { text: '查看规则', variant: 'ghost' as const }
    : null;
  // 场次编号缺席时退回这份卷的规则页（那一页上有"继续未完成的考试"），
  // 而不是拼出一个 attempt=undefined 的地址。
  const attemptId = it.state === 'IN_PROGRESS' ? it.inProgressAttemptId
    : it.state === 'PASSED' ? it.passedAttemptId
    : undefined;

  return (
    <Card>
      <Row wrap={false} align="flex-start" style={{ justifyContent: 'space-between' }}>
        <div style={{ minWidth: 0 }}>
          {showCourse && it.courseTitle ? (
            <div className="wk-assess-course">{it.courseTitle}</div>
          ) : null}
          <Row gap="var(--s2)">
            <strong style={{ fontSize: 'var(--t-body)' }}>{it.title}</strong>
            <Tag tone={s.tone}>{s.text}</Tag>
          </Row>
          {it.reason && <div className="wk-assess-reason">{it.reason}</div>}
          {/* 进度条只在真有门槛时画：章节卷与开放卷的门槛是 0，
              画一条"83% / 门槛 0%"会让人以为还差着什么条件。 */}
          {it.state === 'BLOCKED' && it.requireProgressPct ? (
            <div className="wk-assess-bar">
              <Progress pct={it.progressPct ?? 0} tone="ink" />
              <div className="wk-assess-bar-meta">
                <span className="num">{it.progressPct ?? 0}%</span>
                <span>门槛 <span className="num">{it.requireProgressPct}%</span></span>
              </div>
            </div>
          ) : null}
          {it.state === 'READY' && it.attemptsLeft != null && (
            <div className="wk-assess-reason">还可以考 <span className="num">{it.attemptsLeft}</span> 次</div>
          )}
        </div>
        {cta && (
          <Link to={paperHref(it, attemptId)} style={{ flexShrink: 0 }}>
            <Button variant={cta.variant}>{cta.text}</Button>
          </Link>
        )}
      </Row>
    </Card>
  );
}

/* ================================================================ 答疑 */

function QaSection({ o }: { o: Overview }) {
  const q = o.qa;
  // 标题这一行放【状态】，邀请语退到下面一行 —— 和上面每一门课的排法一致。
  // 反过来把"有不懂的地方，问一句就行"当标题的话，窄屏上它要占两行，
  // 而真正要紧的那句"1 条在等老师回复"被挤成了灰色小字。
  const head = !q ? '提问记录暂时取不到'
    : q.waiting > 0 ? `${q.waiting} 条在等老师回复`
    : q.humanAnswered > 0 ? `老师回复过 ${q.humanAnswered} 条`
    : q.total > 0 ? `你问过 ${q.total} 条问题`
    : '还没有问过问题';

  return (
    <section>
      <h2 className="wk-assess-h2">答疑问答</h2>
      <Card>
        <Row wrap={false} align="flex-start" style={{ justifyContent: 'space-between' }}>
          <div style={{ minWidth: 0 }}>
            <div className="wk-assess-head">
              <span className="wk-assess-icon" aria-hidden><IconChat size={18} /></span>
              <strong>{head}</strong>
            </div>
            <div className="wk-assess-reason">
              课程内容有不懂的地方，问一句就行；右下角那个头像随时也能问。
            </div>
          </div>
          <Link to="/qa" style={{ flexShrink: 0 }}>
            <Button variant={q && q.waiting > 0 ? 'accent' : 'ghost'}>
              {q && q.total > 0 ? '去答疑' : '去提问'}
            </Button>
          </Link>
        </Row>
      </Card>
    </section>
  );
}

/* ================================================================ 结业证明 */

function CertSection({ o }: { o: Overview }) {
  const c = o.cert;
  return (
    <section>
      <h2 className="wk-assess-h2">结业证明</h2>
      <Card>
        <Row wrap={false} align="flex-start" style={{ justifyContent: 'space-between' }}>
          <div style={{ minWidth: 0 }}>
            <div className="wk-assess-head">
              <span className="wk-assess-icon" aria-hidden><IconCert size={18} /></span>
              <strong>
                {/* 「通过测评后可以领取」在多套卷之后是错的：六份卷里只有结业综合测评发证，
                    章节练兵卷与开放卷都不发。照旧写会让考过章节卷的人来这里等一张永远不来的证明。 */}
                {!c ? '数量暂时取不到' : c.valid > 0 ? `已获得 ${c.valid} 份结业证明` : '通过课程的结业综合测评后可以领取'}
              </strong>
            </div>
            <div className="wk-assess-reason">
              {/* 措辞要与证书页、验真页一致：它证明的只是"学完并通过测评"。
                  在这里说得比那两页大一点，等于替平台许下一个它兑现不了的承诺。 */}
              {!c
                ? '点进去看看。'
                : c.total > c.valid
                  ? `其中 ${c.total - c.valid} 份已被吊销。编号可公开验真。`
                  : '编号可公开验真；它不构成任何职业资格或行业认证。'}
            </div>
          </div>
          <Link to="/cert" style={{ flexShrink: 0 }}>
            <Button variant="ghost">{c && c.valid > 0 ? '查看' : '去看看'}</Button>
          </Link>
        </Row>
      </Card>
    </section>
  );
}

const ASSESS_CSS = `
.wk-assess-h2 { font-size: var(--t-h3); margin-bottom: var(--s3); }
/* 课程名退到卷名上面一行小字，与 Exam.tsx 的 .wk-exam-course 同形 */
.wk-assess-course { color: var(--ink-3); font-size: var(--t-xs); margin-bottom: 2px; }
.wk-assess-reason {
  margin-top: var(--s2); color: var(--ink-2);
  font-size: var(--t-sm); line-height: var(--lh-body);
}
.wk-assess-bar { margin-top: var(--s3); max-width: 320px; }
.wk-assess-bar-meta {
  display: flex; justify-content: space-between; gap: var(--s3);
  margin-top: 3px; color: var(--ink-3); font-size: var(--t-xs);
}
.wk-assess-icon { color: var(--brand); display: inline-grid; place-items: center; flex: none; }
/* 图标与标题必须同一行起头。放进会换行的 Row 里的话，标题一旦超过一行，
   图标就被挤到自己单独的一行上 —— 屏幕上是一枚孤零零的对话框，看着像渲染坏了。 */
.wk-assess-head { display: flex; align-items: flex-start; gap: var(--s2); }
.wk-assess-head strong { font-size: var(--t-body); line-height: 1.5; min-width: 0; }
`;
