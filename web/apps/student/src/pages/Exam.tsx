/**
 * 测评。
 *
 * ## 这一页最重要的两件事
 *
 * 1. **答案不能丢。** 45 分钟一场、100 道题，中途刷新一次就全没了是不可接受的
 *    ——而手机上"刷新"不需要用户主动做：来一通电话、切个微信回来，
 *    页面可能已经被系统回收重载了。所以每一次作答都立刻写进 localStorage，
 *    重进考卷时把它读回来。
 * 2. **状态放在 URL 里。** `?paper=3&attempt=12`。放在组件 state 里的话，
 *    刷新后回到规则页，而后端那场考试还开着、时间还在走 —— 学员会以为
 *    自己被踢出了考试，然后去点"重新开考"，白白烧掉一次机会。
 *
 * 倒计时以**打开考卷时后端给的 secondsLeft** 为准换算出本地截止时刻，
 * 不用 deadlineAt 直接和本地时钟比：手机时间不准是常事，差几分钟的机器
 * 会看到一个从一开始就不对的倒计时。
 *
 * ## 三屏与地址（2026-09-03 多套试卷）
 *
 * `/exam` 列我能考的全部卷 → `/exam?paper=3` 那一份的规则页 →
 * `/exam?paper=3&attempt=12` 答题 / 成绩。卷挂着课程时地址上再多一个 `&course=1`，
 * 它只服务于答题页那个"回课程"的链接 —— 开放卷没有课程，那条链接就不出现。
 *
 * **原来"只有一门课就自动跳进去"那段已经删掉。** 现在一门课底下是一张综合卷
 * 加四张章节卷，另外还有一张对所有注册账号开放的卷；自动跳会把学员按死在其中
 * 一份上，别的几份他永远不知道存在。
 *
 * 旧地址 `/exam?course=1`（Assess、CourseDetail 上"去测评"那两个按钮还在用）
 * 不再由前端挑卷，而是把人放到卷列表上：考哪一份现在是学员自己选的。
 * 在这里重算一遍"这门课发证的是哪张卷"，等于把后端那条兼容规则抄了第二份，
 * 两份迟早分叉 —— 分叉的表现是这一页进了 A 卷，而后端开的是 B 卷。
 */
import React from 'react';
import { Link, Navigate, useNavigate, useSearchParams } from 'react-router-dom';
import {
  api, Async, Button, Card, Empty, ErrorState, PageHead, Progress, Skeleton, Tag, useAsync, useToast,
} from '@wangke/shared';
import type { ExamRules, Paper, PaperQuestion } from '../types';
import { Confirm, countdownText, Note, Row, Screen, Stack } from '../kit';
import { useDongmaSuspend } from '../dongma';

/** 判断题的选项键。见交付说明里的假设一条：后端对 JUDGE 不下发 options。 */
const JUDGE_OPTIONS = [
  { key: 'T', text: '正确' },
  { key: 'F', text: '错误' },
];

/* ================================================================ 类型 */

/**
 * `GET /api/exam/papers` 的一行：一份我能看到的卷，外加"我在这份卷上的状态"。
 * 字段照《多套试卷规格》第二节 `PaperCard` 那一串写，后端此刻还没落地。
 *
 * ★ 可空的一律写成 `?:` 而不是 `| null`：后端全局开了
 *   `default-property-inclusion: non_null`，值为 null 时那个键在 JSON 里**整个消失**。
 *   写成 `| null` 的话类型上说着"这个键一定在"，`p.blockedReason.length`
 *   能过编译、运行时炸。（同 types.ts 里 Overview 那一段。）
 *
 * ★ `grantsCertificate` 声明成 `boolean | number`：库里那一列是 TINYINT(1)，
 *   经 Connector/J 取回来可能是 Boolean 也可能是 Number，序列化出去就是
 *   `true` 或 `1`。所以这一页只按真假用它，**不写 `=== true`** ——
 *   那个写法遇到 `1` 会静默判成"不发证"，于是结业卷的领证入口消失。
 *
 * ★ 这个 `PaperCard` 是【试卷】，和 types.ts 里的 `Paper`（一场考试的考卷）
 *   不是一回事，别把两个名字读串了。
 */
type PaperCard = {
  paperId: number;
  code: string;
  title: string;
  subtitle?: string | null;
  access: 'ENROLLED' | 'OPEN';
  /** 开放卷不挂课程，这个键会整个不出现。 */
  courseId?: number | null;
  courseTitle?: string | null;
  grantsCertificate: boolean | number;
  /**
   * ★ `rules`、`progressPct`、`attemptsLeft` 三个都可能**整个不在**。
   *
   * 后端在"这份卷还没配规则""状态暂时取不到"这类情况下会给一张**只带原因的卡**，
   * 数字一律留空（见 ExamService.card(p, none, reason) 上那句注释：空 = 不知道，不是 0）。
   * 直接写 `p.rules.questionCount`，一份没配规则的卷就会让整个测评页白屏 ——
   * 而这一页恰恰是要把那句原因带给学员的地方。
   * `progressPct` 对开放卷（没有课程）本来也没有意义，同样是空。
   */
  rules?: ExamRules | null;
  progressPct?: number | null;
  attemptsUsed: number;
  attemptsLeft?: number | null;
  inProgressAttemptId?: number | null;
  passedAttemptId?: number | null;
  cooldownSecondsLeft?: number | null;
  canStart: boolean;
  /** 不能考的整句理由，由后端给（与开考闸门同一句）。能考时这个键不出现。 */
  blockedReason?: string | null;
};

/* ================================================================ 路由 */

export default function Exam() {
  const [sp, setSp] = useSearchParams();
  const paperId = idParam(sp.get('paper'));
  const courseId = idParam(sp.get('course'));
  const attemptId = idParam(sp.get('attempt'));

  // 次序有讲究：**先看 attempt**。上线那一刻正在考试的人，地址还是旧的
  // `?course=1&attempt=12`，先判 course 会把他从考场里赶到卷列表上，
  // 而那一场的时间还在走。
  if (attemptId > 0) {
    return <PaperScreen attemptId={attemptId} courseId={courseId} onLeave={() => {
      sp.delete('attempt');
      setSp(sp, { replace: true });
    }} />;
  }
  if (paperId > 0) {
    return <IntroScreen paperId={paperId} />;
  }
  // 旧入口 `/exam?course=1`：不在前端挑卷，把人放到列表上自己选。
  if (courseId > 0) {
    return <Navigate to="/exam" replace />;
  }
  return <PaperPicker />;
}

/** 地址里的编号。`Number(null)` 是 0、`Number('abc')` 是 NaN，两种都当没给。 */
function idParam(raw: string | null): number {
  const n = Number(raw);
  return Number.isFinite(n) && n > 0 ? n : 0;
}

/**
 * 这一份卷在地址里的那一段。
 *
 * 带上 course 只为了答题页那个"回课程"的链接 —— 开放卷没有课程，
 * 这一段就不出现，链接也就不会指向一个不存在的课。
 */
const selfQuery = (p: PaperCard) =>
  `paper=${p.paperId}` + (p.courseId ? `&course=${p.courseId}` : '');

/* ================================================================ 选卷 */

function PaperPicker() {
  const state = useAsync(
    () => api.get<{ papers: PaperCard[] }>('/api/exam/papers').then((r) => r.papers),
    [],
  );

  return (
    <Screen>
      <style>{EXAM_CSS}</style>
      <PageHead title="测评" desc="选择要参加的试卷。每一份的题量、时长和考试次数都不一样。" />
      {state.loading ? (
        // 骨架的行数照真实首屏给：这一页正常是两组共六张卡，给一行的话
        // 真数据一来页面会明显往下跳一大截。
        <Stack>{[0, 1, 2].map((i) => <Skeleton key={i} h={188} />)}</Stack>
      ) : (
        <Async
          state={state}
          empty={
            <Card>
              {/* 开放卷对任何注册账号都可见，所以这里空掉通常不是"你还没买课"，
                  而是卷还没上架。别再写"你还没有课程"—— 那句话把学员支去激活，
                  而激活并不会让这一页多出任何东西。 */}
              <Empty
                icon="✎"
                title="现在没有可以参加的测评"
                desc="开放测评上架后会直接出现在这里；课程测评要先激活对应的课程。"
                action={<Link to="/activate"><Button variant="accent">输入激活码</Button></Link>}
              />
            </Card>
          }
        >
          {(papers) => {
            const enrolled = papers.filter((p) => p.access !== 'OPEN');
            const open = papers.filter((p) => p.access === 'OPEN');
            // 只有一门课时不必在每张卡上重复课程名 —— 五张卡上五行同样的字，
            // 是噪声不是信息。两门课以上才写，那时它是分辨这张卷属于谁的唯一线索。
            const multiCourse = new Set(enrolled.map((p) => p.courseId ?? 0)).size > 1;
            return (
              <Stack gap="var(--s6)">
                {enrolled.length > 0 && (
                  <Group
                    title="课程测评"
                    desc="综合测评的成绩决定结业证明；章节卷只用来自测，不发证，次数也给得宽。"
                  >
                    {sortPapers(enrolled).map((p) => (
                      <PaperRow key={p.paperId} p={p} showCourse={multiCourse} />
                    ))}
                  </Group>
                )}
                {open.length > 0 && (
                  <Group
                    title="开放测评"
                    desc="不需要购课、也不需要激活码，任何注册账号都能参加；这一类卷不发结业证明。"
                  >
                    {open.map((p) => <PaperRow key={p.paperId} p={p} />)}
                  </Group>
                )}
              </Stack>
            );
          }}
        </Async>
      )}
    </Screen>
  );
}

/**
 * 先按课程分块，块内综合卷排在章节卷前面。
 *
 * 判据用"发不发证"而不是卷名里有没有"章"：发证与否是 exam_paper 上的一列，
 * 而"综合/章节"只是标题里的两个字 —— 后台改一次标题就会把次序改掉，
 * 且没有任何地方会报错。
 *
 * 课程那一层不能省：学员手上有两门课时，只按发不发证排会把两门课的综合卷
 * 并排摆在最上面、八张章节卷压在下面，读起来像一堆互不相干的卷。
 * 课程之间的先后就用后端给的出场次序，不在这里另立一套。
 *
 * `Array#sort` 是稳定排序（ES2019 起写进规范），所以同一档里后端按 sort_no
 * 给的次序原样保留，第一章到第四章不会被打乱。
 */
function sortPapers(list: PaperCard[]): PaperCard[] {
  const rank = new Map<number, number>();
  for (const p of list) {
    const c = p.courseId ?? 0;
    if (!rank.has(c)) rank.set(c, rank.size);
  }
  const key = (p: PaperCard) => rank.get(p.courseId ?? 0) ?? 0;
  return [...list].sort((a, b) =>
    (key(a) - key(b)) || ((a.grantsCertificate ? 0 : 1) - (b.grantsCertificate ? 0 : 1)));
}

function Group({ title, desc, children }: {
  title: string; desc: string; children: React.ReactNode;
}) {
  return (
    <section>
      <h2 className="wk-exam-h2">{title}</h2>
      <p className="wk-exam-groupdesc">{desc}</p>
      <Stack>{children}</Stack>
    </section>
  );
}

/** 数字取不到时的占位。空 = 不知道，和 0 不是一回事。 */
const DASH = '—';

function PaperRow({ p, showCourse }: { p: PaperCard; showCourse?: boolean }) {
  const passed = p.passedAttemptId != null;
  const inProgress = p.inProgressAttemptId != null;
  const r = p.rules;

  // 进行中 / 已通过 直接落到那一场上（继续答题、看成绩都在答题页里）；
  // 其余落到规则页 —— 包括不能考的，"为什么不能考"和规则都写在那一页。
  const cta = inProgress
    ? { to: `/exam?${selfQuery(p)}&attempt=${p.inProgressAttemptId}`, text: '继续答题', variant: 'accent' as const }
    : passed
      ? { to: `/exam?${selfQuery(p)}&attempt=${p.passedAttemptId}`, text: '查看成绩', variant: 'ghost' as const }
      : p.canStart
        ? { to: `/exam?${selfQuery(p)}`, text: '开始考试', variant: 'primary' as const }
        : { to: `/exam?${selfQuery(p)}`, text: '查看规则', variant: 'ghost' as const };

  return (
    <Card>
      <Row wrap={false} align="flex-start" style={{ justifyContent: 'space-between' }}>
        <div style={{ minWidth: 0 }}>
          {showCourse && p.courseTitle ? (
            <div className="wk-exam-course">{p.courseTitle}</div>
          ) : null}
          <div className="wk-exam-title">{p.title}</div>
          {p.subtitle && <div className="wk-exam-sub">{p.subtitle}</div>}
        </div>
        <StateTag p={p} />
      </Row>

      {/* 规则取不到时四个数字一律写「—」，不写 0：0 是一个具体的数，
          会被读成"这卷 0 题、还剩 0 次"，而真相是我们没问到。 */}
      <div className="wk-exam-meta">
        <Meta k="题量" v={r ? `${r.questionCount} 题` : DASH} />
        <Meta k="时长" v={r ? `${r.durationMin} 分钟` : DASH} />
        <Meta k="及格" v={r ? `${r.passScore} 分` : DASH} />
        <Meta k="剩余次数" v={r && p.attemptsLeft != null ? `${p.attemptsLeft} / ${r.maxAttempts} 次` : DASH} />
      </div>

      {p.grantsCertificate ? (
        <div className="wk-exam-hint">通过这一份可以领结业证明。</div>
      ) : null}

      {!p.canStart && p.blockedReason ? (
        // 理由整句由后端给（就是开考闸门那一句），这里一个字都不改写、也不加前缀。
        // 前端另编一句的话，卡上写的原因和点进去被拒的原因会是两回事，
        // 而学员只能看到前一句。
        <div className="wk-exam-reason">
          {p.blockedReason}
          {/* 等待时长另起一行：接在理由后面时两句挤成一条，读起来像"还不到 2 小时还需等待"。 */}
          {p.cooldownSecondsLeft ? (
            <div style={{ marginTop: 'var(--s1)' }}>还需等待 {countdownText(p.cooldownSecondsLeft)}</div>
          ) : null}
        </div>
      ) : null}

      <div className="wk-exam-cta">
        <Link to={cta.to}><Button variant={cta.variant}>{cta.text}</Button></Link>
      </div>
    </Card>
  );
}

/**
 * 状态标签，一张卡只出一个。
 *
 * 次序是「已通过、进行中、不可考」取第一个命中的。反过来先判 canStart 的话，考过的卷会
 * 显示成"不可考"—— 通过之后后端本来就不让再考，那是一句技术上没错、
 * 读起来却像出了故障的话。能考的卡不挂标签：那一行的红色 CTA 已经说清楚了。
 */
function StateTag({ p }: { p: PaperCard }) {
  if (p.passedAttemptId != null) return <Tag tone="ok">已通过</Tag>;
  if (p.inProgressAttemptId != null) return <Tag tone="warn">进行中</Tag>;
  if (!p.canStart) return <Tag tone="neutral">不可考</Tag>;
  return null;
}

function Meta({ k, v }: { k: string; v: string }) {
  return (
    <div>
      <div className="wk-exam-k">{k}</div>
      <div className="wk-exam-v num">{v}</div>
    </div>
  );
}

/* ================================================================ 开考前 */

function IntroScreen({ paperId }: { paperId: number }) {
  const nav = useNavigate();
  const toast = useToast();
  const [starting, setStarting] = React.useState(false);

  // 规则页要写的东西（卷名、发不发证、属于哪门课）不在 /api/exam/config 的返回体里，
  // 而 /api/exam/papers 的一张卡就是 config 的全部字段再加上卷身份。用同一份数据
  // 画标题和画闸门，这一页写的每一句话都出自同一次计算 ——
  // 不会出现"标题写着第二章、规则却是综合卷"这种对不上的事。
  // 卷不在列表里（下架了、权益到期了）时 find 给 undefined，正好落进 Async 的空态。
  const state = useAsync(
    () => api.get<{ papers: PaperCard[] }>('/api/exam/papers')
      .then((r) => r.papers.find((p) => p.paperId === paperId)),
    [paperId],
  );

  const start = async (p: PaperCard) => {
    setStarting(true);
    try {
      const r = await api.post<{ attemptId: number; firstTime: boolean }>(
        '/api/exam/start', { paperId: p.paperId },
      );
      // firstTime=false 是"你有一场还开着"，不是错误。不说清楚的话，
      // 学员会以为自己新开了一场，然后奇怪为什么时间只剩十几分钟。
      if (!r.firstTime) toast({ tone: 'info', text: '你有一场还没交卷的考试，已经带你回到那一场' });
      nav(`/exam?${selfQuery(p)}&attempt=${r.attemptId}`, { replace: true });
    } catch (e) {
      toast({ tone: 'danger', text: (e as Error).message || '开考失败，请重试' });
      state.reload();
    } finally {
      setStarting(false);
    }
  };

  return (
    <Screen maxWidth={560}>
      <style>{EXAM_CSS}</style>
      <PageHead title="测评" desc="开考前请先看清楚规则。" />
      {state.loading ? <Skeleton h={260} /> : (
        // 显式写出泛型参数：state.data 是 `PaperCard | undefined`，不写的话
        // TS 会把 T 推成含 undefined 的那个联合，于是回调里的 p 也可能是 undefined，
        // 而 Async 在运行时已经把这一支拦在 empty 里了。
        <Async<PaperCard>
          state={state}
          empty={
            <Card>
              <Empty
                icon="✎"
                title="这份试卷现在不在你可以参加的名单里"
                desc="它可能已经下架，或者要先激活对应的课程。回到测评列表看看还有哪些卷可以考。"
                action={<Link to="/exam"><Button variant="accent">看全部试卷</Button></Link>}
              />
            </Card>
          }
        >
          {(p) => (
            <Stack gap="var(--s5)">
              <Card>
                <Stack gap="var(--s4)">
                  <Row wrap={false} align="flex-start" style={{ justifyContent: 'space-between' }}>
                    <div style={{ minWidth: 0 }}>
                      <div className="wk-exam-title">{p.title}</div>
                      {p.subtitle && <div className="wk-exam-sub">{p.subtitle}</div>}
                    </div>
                    <StateTag p={p} />
                  </Row>
                  {/* 规则取不到的卡没有 rules，这里就整块不画 ——
                      下面那条 blockedReason 会把原因原句说出来。 */}
                  {p.rules && <RuleGrid rules={p.rules} attemptsLeft={p.attemptsLeft} />}
                  {/* 进度条只在真有门槛时画。章节卷和开放卷的门槛是 0，
                      画一条"已学 12% / 需要 0%"会让人以为还差着什么条件；
                      开放卷更是没有课程，progressPct 对它根本没有意义（后端给的就是空）。 */}
                  {p.rules && p.rules.requireProgressPct > 0 && (
                    <>
                      <div style={{ height: 1, background: 'var(--line)' }} />
                      <div>
                        <Row style={{ justifyContent: 'space-between', fontSize: 'var(--t-sm)',
                                      color: 'var(--ink-2)' }}>
                          <span>课程学习进度</span>
                          <span className="num">
                            {p.progressPct == null ? DASH : `${Math.floor(p.progressPct)}%`}
                            {' '}/ 需要 {p.rules.requireProgressPct}%
                          </span>
                        </Row>
                        <div style={{ marginTop: 'var(--s2)' }}>
                          <Progress pct={p.progressPct ?? 0} />
                        </div>
                      </div>
                    </>
                  )}
                </Stack>
              </Card>

              {p.passedAttemptId != null && (
                <Note tone="ok">
                  你已经通过了这份测评。{p.grantsCertificate ? null : '这份卷不发结业证明。'}
                  <Row gap="var(--s4)" style={{ marginTop: 'var(--s2)' }}>
                    <Link to={`/exam?${selfQuery(p)}&attempt=${p.passedAttemptId}`}>查看成绩</Link>
                    {p.grantsCertificate ? <Link to="/cert">去领结业证明</Link> : null}
                  </Row>
                </Note>
              )}

              {p.inProgressAttemptId != null && (
                <Note tone="warn">
                  你有一场考试还没有交卷，时间还在走。继续那一场不会消耗新的考试次数。
                </Note>
              )}

              {!p.canStart && p.blockedReason && (
                // 眉标写"不能开考"，正文就是后端那一句，一个字不改。
                <Note tone="danger" title="不能开考">
                  {p.blockedReason}
                  {p.cooldownSecondsLeft ? (
                    <div style={{ marginTop: 'var(--s1)' }}>
                      还需等待 {countdownText(p.cooldownSecondsLeft)}
                      {p.rules ? `（两次考试之间要间隔 ${p.rules.retryCooldownHours} 小时）` : null}
                    </div>
                  ) : null}
                </Note>
              )}

              <Note>
                <b>交卷之后不能改答案</b>，成绩以服务端判定为准。
                考试过程中答案会自动保存在这台设备上，中途刷新不会丢。
              </Note>

              <Button
                size="lg" block variant="accent" loading={starting}
                disabled={!p.canStart}
                onClick={() => start(p)}
              >
                {p.inProgressAttemptId != null ? '继续未完成的考试'
                  : p.rules ? `开始考试（第 ${p.attemptsUsed + 1} 次，共 ${p.rules.maxAttempts} 次）`
                    : '开始考试'}
              </Button>

              <Row gap="var(--s4)" style={{ justifyContent: 'center' }}>
                <Link to="/exam" style={{ fontSize: 'var(--t-sm)' }}>看别的卷</Link>
                {p.courseId ? (
                  <Link to={`/course/${p.courseId}`} style={{ fontSize: 'var(--t-sm)' }}>回课程</Link>
                ) : null}
              </Row>
            </Stack>
          )}
        </Async>
      )}
    </Screen>
  );
}

function RuleGrid({ rules, attemptsLeft }: { rules: ExamRules; attemptsLeft?: number | null }) {
  const items = [
    ['题量', `${rules.questionCount} 题`],
    ['及格分', `${rules.passScore} 分`],
    ['时长', `${rules.durationMin} 分钟`],
    ['剩余次数', attemptsLeft == null ? DASH : `${attemptsLeft} / ${rules.maxAttempts} 次`],
  ];
  return (
    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, 1fr)', gap: 'var(--s4)' }}>
      {items.map(([k, v]) => (
        <div key={k}>
          <div style={{ fontSize: 'var(--t-sm)', color: 'var(--ink-3)' }}>{k}</div>
          <div className="num" style={{ fontSize: 'var(--t-h2)', fontWeight: 600, marginTop: 2 }}>{v}</div>
        </div>
      ))}
    </div>
  );
}

/* ================================================================ 答题 */

type Answers = Record<string, string>;

const saveKey = (attemptId: number) => `wangke.exam.${attemptId}`;

function loadSaved(attemptId: number): Answers {
  try {
    return JSON.parse(localStorage.getItem(saveKey(attemptId)) || '{}') as Answers;
  } catch {
    return {};
  }
}
function persist(attemptId: number, a: Answers) {
  try {
    localStorage.setItem(saveKey(attemptId), JSON.stringify(a));
  } catch {
    /* 存不下就存不下，不能因此打断考试 */
  }
}

function PaperScreen({ attemptId, courseId, onLeave }: {
  attemptId: number; courseId: number; onLeave: () => void;
}) {
  const toast = useToast();
  const state = useAsync(() => api.get<Paper>(`/api/exam/${attemptId}`), [attemptId]);

  if (state.loading) {
    return (
      <Screen maxWidth={620}>
        <Stack>
          <Skeleton h={56} /><Skeleton h={160} /><Skeleton h={160} />
        </Stack>
      </Screen>
    );
  }
  if (state.error) {
    return (
      <Screen maxWidth={620}>
        <ErrorState message={state.error} onRetry={state.reload} />
        <div style={{ textAlign: 'center', marginTop: 'var(--s4)' }}>
          <Button variant="quiet" onClick={onLeave}>返回规则页</Button>
        </div>
      </Screen>
    );
  }
  const paper = state.data!;
  if (paper.submitted) {
    return <ResultScreen paper={paper} courseId={courseId} onRetake={onLeave} />;
  }
  if (paper.questions.length === 0) {
    return (
      <Screen maxWidth={620}>
        <Card>
          <Empty
            title="这张卷子没有题目"
            desc="题库出了问题，这一场考试无法进行。请联系平台客服，不要重复开考（每开一次都会消耗一次机会）。"
            action={<Button variant="ghost" onClick={onLeave}>返回</Button>}
          />
        </Card>
      </Screen>
    );
  }
  return (
    <Answering
      key={attemptId} paper={paper} courseId={courseId}
      onSubmitted={() => { state.reload(); toast({ tone: 'ok', text: '已交卷' }); }}
    />
  );
}

function Answering({ paper, courseId, onSubmitted }: {
  paper: Paper; courseId: number; onSubmitted: () => void;
}) {
  const toast = useToast();

  // ★ 作答期间把懂妈按下去。这一屏正在决定的是结业证书 ——
  //   右下角摆一个随时能问的答疑助手，等于把测评变成开卷。
  //   只在这个组件里挂：它只在【未交卷】时渲染，交卷后的成绩屏不受影响。
  //   注意这是「不主动递给他」，不是真闸：另开标签页仍然能用 /qa，
  //   那个洞在挂件之前就在，真要堵得在后端上（开考期间拒收提问）。
  useDongmaSuspend('考试进行中，交卷后可以继续问懂妈');

  const [answers, setAnswers] = React.useState<Answers>(() => loadSaved(paper.attemptId));
  const [confirming, setConfirming] = React.useState(false);
  const [busy, setBusy] = React.useState(false);
  const [jumpOpen, setJumpOpen] = React.useState(false);

  // 截止时刻由"打开考卷那一刻 + 后端给的剩余秒数"算出，不信本地时钟。
  const deadline = React.useRef(Date.now() + paper.secondsLeft * 1000);
  const [left, setLeft] = React.useState(paper.secondsLeft);
  const submittedOnce = React.useRef(false);

  const answered = paper.questions.filter((q) => (answers[String(q.id)] ?? '').length > 0).length;

  const submit = React.useCallback(async (auto: boolean) => {
    if (submittedOnce.current) return;
    submittedOnce.current = true;
    setBusy(true);
    try {
      await api.post(`/api/exam/${paper.attemptId}/submit`, { answers });
      try { localStorage.removeItem(saveKey(paper.attemptId)); } catch { /* ignore */ }
      setConfirming(false);
      onSubmitted();
    } catch (e) {
      submittedOnce.current = false;
      toast({ tone: 'danger', text: (e as Error).message || (auto ? '到时自动交卷失败' : '交卷失败，请重试') });
    } finally {
      setBusy(false);
    }
  }, [answers, paper.attemptId, onSubmitted, toast]);

  React.useEffect(() => {
    const t = setInterval(() => {
      const s = Math.max(0, Math.round((deadline.current - Date.now()) / 1000));
      setLeft(s);
      // 到点自动交卷：后端过了 deadline 就拒收，那时学员的答案会**一分都拿不到**
      // （超时不判分也不落分）。宁可替他交，也不能让他白考一场。
      if (s === 0) void submit(true);
    }, 1000);
    return () => clearInterval(t);
  }, [submit]);

  const setAnswer = (q: PaperQuestion, key: string) => {
    setAnswers((prev) => {
      const cur = prev[String(q.id)] ?? '';
      let next: string;
      if (q.type === 'MULTI') {
        const set = new Set(cur.split(''));
        if (set.has(key)) set.delete(key); else set.add(key);
        next = [...set].sort().join('');
      } else {
        next = cur === key ? '' : key;     // 再点一次可以取消，单选题误触很常见
      }
      const merged = { ...prev, [String(q.id)]: next };
      if (!next) delete merged[String(q.id)];
      persist(paper.attemptId, merged);
      return merged;
    });
  };

  const urgent = left <= 300;   // 最后 5 分钟

  return (
    <Screen maxWidth={620}>
      <div style={{
        position: 'sticky', top: 54, zIndex: 2,
        background: 'var(--paper)', paddingBottom: 'var(--s3)',
        // 顶栏和内容之间要有一条实边，否则滚动时文字会从它下面"穿"过去
        borderBottom: '1px solid var(--line)', marginBottom: 'var(--s4)',
      }}>
        <Row style={{ justifyContent: 'space-between', paddingTop: 'var(--s2)' }}>
          <div>
            <div style={{ fontSize: 'var(--t-xs)', color: 'var(--ink-3)' }}>剩余时间</div>
            <div className="num" style={{
              fontSize: 'var(--t-h1)', fontWeight: 600, lineHeight: 1.1,
              color: urgent ? 'var(--danger)' : 'var(--ink)',
            }}>{countdownText(left)}</div>
          </div>
          <div style={{ textAlign: 'right' }}>
            <div style={{ fontSize: 'var(--t-xs)', color: 'var(--ink-3)' }}>已答</div>
            <div className="num" style={{ fontSize: 'var(--t-h3)', fontWeight: 600 }}>
              {answered}/{paper.questions.length}
            </div>
          </div>
          <Button variant="accent" onClick={() => setConfirming(true)} disabled={busy}>交卷</Button>
        </Row>
        <div style={{ marginTop: 'var(--s2)' }}>
          <Progress pct={(answered / paper.questions.length) * 100} height={4} />
        </div>
        <button
          type="button" onClick={() => setJumpOpen((v) => !v)}
          style={{
            background: 'none', border: 'none', padding: 'var(--s2) 0 0', cursor: 'pointer',
            color: 'var(--brand)', fontSize: 'var(--t-xs)',
          }}
        >{jumpOpen ? '收起题号' : '题号一览'}</button>
        {jumpOpen && (
          <div style={{
            display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(38px, 1fr))',
            gap: 'var(--s2)', marginTop: 'var(--s2)', maxHeight: 168, overflowY: 'auto',
          }}>
            {paper.questions.map((q, i) => {
              const done = (answers[String(q.id)] ?? '').length > 0;
              return (
                <a key={q.id} href={`#q${q.id}`} onClick={() => setJumpOpen(false)} style={{
                  display: 'grid', placeItems: 'center', height: 34, borderRadius: 'var(--r-sm)',
                  fontSize: 'var(--t-xs)', textDecoration: 'none',
                  fontFamily: 'var(--font-num)',
                  background: done ? 'var(--brand)' : 'var(--surface-2)',
                  color: done ? 'var(--surface)' : 'var(--ink-2)',
                  border: '1px solid ' + (done ? 'var(--brand)' : 'var(--line)'),
                }}>{i + 1}</a>
              );
            })}
          </div>
        )}
      </div>

      <Stack gap="var(--s4)">
        {paper.questions.map((q, i) => (
          <QuestionCard
            key={q.id} q={q} index={i + 1}
            value={answers[String(q.id)] ?? ''}
            onPick={(k) => setAnswer(q, k)}
          />
        ))}

        <Button size="lg" block variant="accent" onClick={() => setConfirming(true)} disabled={busy}>
          交卷
        </Button>
        <div style={{ textAlign: 'center', fontSize: 'var(--t-xs)', color: 'var(--ink-3)' }}>
          答案已自动保存在这台设备上，中途刷新不会丢
          {courseId ? <> · <Link to={`/course/${courseId}`}>回课程</Link></> : null}
        </div>
      </Stack>

      <Confirm
        open={confirming}
        title="确认交卷？"
        confirmText="确认交卷"
        busy={busy}
        onConfirm={() => submit(false)}
        onClose={() => setConfirming(false)}
        consequence={
          <>
            <b>交卷后不能再修改答案，本次考试机会会被消耗。</b>
            <div style={{ marginTop: 'var(--s1)' }}>
              及格线是 {paper.rules.passScore} 分（共 {paper.rules.questionCount} 题），
              没过的话要等 {paper.rules.retryCooldownHours} 小时才能再考。
            </div>
          </>
        }
      >
        <div style={{ fontSize: 'var(--t-body)' }}>
          已答 <b className="num">{answered}</b> 题，
          还有 <b className="num" style={{ color: answered < paper.questions.length ? 'var(--danger)' : 'inherit' }}>
            {paper.questions.length - answered}
          </b> 题没答。没答的题按答错计分。
        </div>
      </Confirm>
    </Screen>
  );
}

function QuestionCard({ q, index, value, onPick }: {
  q: PaperQuestion; index: number; value: string; onPick: (key: string) => void;
}) {
  const options = q.options && q.options.length > 0 ? q.options
    : q.type === 'JUDGE' ? JUDGE_OPTIONS : [];
  const typeText = q.type === 'MULTI' ? '多选' : q.type === 'JUDGE' ? '判断' : '单选';

  return (
    <Card id={`q${q.id}`} style={{ scrollMarginTop: 160 }}>
      <Row gap="var(--s2)" align="flex-start" wrap={false}>
        <span className="num" style={{ color: 'var(--ink-3)', fontSize: 'var(--t-sm)', paddingTop: 2 }}>
          {index}
        </span>
        <div style={{ flex: 1, minWidth: 0 }}>
          <Row gap="var(--s2)" align="flex-start">
            <Tag tone={q.type === 'MULTI' ? 'warn' : 'neutral'}>{typeText}</Tag>
          </Row>
          <div style={{ marginTop: 'var(--s2)', fontSize: 'var(--t-body)', lineHeight: 'var(--lh-body)' }}>
            {q.stem}
          </div>
        </div>
      </Row>

      {options.length === 0 ? (
        <Note tone="warn">这道题的选项没能加载出来，请联系平台客服。</Note>
      ) : (
        <Stack gap="var(--s2)" style={{ marginTop: 'var(--s4)' }}>
          {options.map((o) => {
            const on = value.includes(o.key);
            return (
              <button
                key={o.key} type="button" onClick={() => onPick(o.key)}
                style={{
                  display: 'flex', gap: 'var(--s3)', alignItems: 'flex-start', textAlign: 'left',
                  // 选项行 48px 起步：手机上比这更矮的行，戴着老花镜的人点不准
                  minHeight: 48, padding: 'var(--s3) var(--s4)', cursor: 'pointer',
                  borderRadius: 'var(--r-md)', fontSize: 'var(--t-body)',
                  background: on ? 'var(--brand-soft)' : 'var(--surface)',
                  border: `1px solid ${on ? 'var(--brand)' : 'var(--line-strong)'}`,
                  color: on ? 'var(--brand)' : 'var(--ink)',
                  fontWeight: on ? 600 : 400,
                }}
              >
                <span aria-hidden style={{
                  width: 24, height: 24, flexShrink: 0, display: 'grid', placeItems: 'center',
                  borderRadius: q.type === 'MULTI' ? 'var(--r-sm)' : 'var(--r-full)',
                  border: `1px solid ${on ? 'var(--brand)' : 'var(--line-strong)'}`,
                  background: on ? 'var(--brand)' : 'transparent',
                  color: on ? 'var(--surface)' : 'var(--ink-3)',
                  fontSize: 'var(--t-xs)', fontFamily: 'var(--font-num)',
                }}>{on ? '✓' : o.key}</span>
                <span style={{ flex: 1 }}>{o.text}</span>
              </button>
            );
          })}
          {q.type === 'MULTI' && (
            <div style={{ fontSize: 'var(--t-xs)', color: 'var(--ink-3)' }}>
              多选题：少选、多选都算错
            </div>
          )}
        </Stack>
      )}
    </Card>
  );
}

/* ================================================================ 成绩 */

function ResultScreen({ paper, courseId, onRetake }: {
  paper: Paper; courseId: number; onRetake: () => void;
}) {
  const passed = paper.passed === true;
  // 「去领结业证明」只对发证的卷成立：多套试卷之后，通过一份 50 题的章节卷
  // 并不产生结业证明（库里 trg_certificate_precheck 会直接拒），把入口摆在
  // 这里等于让学员点进一个必然扑空的页面。
  //
  // ★ 三态，不给默认值。这个字段由 ExamController.paper() 下发（本轮加的）。
  //   原来写的是"字段缺席就按发证走"，而那时后端根本没有这个键 ——
  //   于是那条"兜底"分支是**恒定生效**的，每一个通过章节卷的学员都被送进死路。
  //   真缺席（老后端）时两句话都不说：说"可以领"是把人送进死路，
  //   说"不发证"又可能是在冤枉一份真的结业卷。
  const grants = paper.grantsCertificate == null ? null : !!paper.grantsCertificate;
  return (
    <Screen maxWidth={520}>
      <Card style={{ textAlign: 'center', padding: 'var(--s7) var(--s5)' }}>
        <div aria-hidden style={{
          width: 56, height: 56, margin: '0 auto var(--s5)', borderRadius: 'var(--r-full)',
          background: passed ? 'var(--ok-soft)' : 'var(--warn-soft)',
          color: passed ? 'var(--ok)' : 'var(--warn)',
          display: 'grid', placeItems: 'center', fontSize: 'var(--t-h1)',
        }}>{passed ? '✓' : '!'}</div>

        {/* 考的是哪一份卷要写出来：一门课下面五份卷，光一个分数说不清这是哪一场。
            后端没下发卷名时（老后端）就不写，不在这里编一个。 */}
        {paper.paperTitle && (
          <div style={{ color: 'var(--ink-3)', fontSize: 'var(--t-sm)' }}>{paper.paperTitle}</div>
        )}
        <h2 style={{ fontSize: 'var(--t-h1)' }}>{passed ? '恭喜，通过了' : '这次没有通过'}</h2>

        <div className="num" style={{
          fontSize: 'var(--t-display)', fontWeight: 600, marginTop: 'var(--s4)',
          color: passed ? 'var(--ok)' : 'var(--ink)',
        }}>
          {paper.score ?? 0}
          <span style={{ fontSize: 'var(--t-h3)', color: 'var(--ink-3)', fontWeight: 400 }}>
            {' '}/ {paper.rules.questionCount}
          </span>
        </div>
        <div style={{ color: 'var(--ink-3)', fontSize: 'var(--t-sm)', marginTop: 'var(--s2)' }}>
          第 {paper.attemptNo} 次考试 · 及格线 {paper.rules.passScore} 分
        </div>

        <Stack gap="var(--s3)" style={{ marginTop: 'var(--s6)' }}>
          {passed ? (
            grants === true
              ? <Link to="/cert"><Button size="lg" block>去领结业证明</Button></Link>
              // 眉标要显式给：Note 的 ok 档默认打的是"已完成"，
              // 而这一句说的是"没有证书可领"，两个词并排读起来像自相矛盾。
              : grants === false
                ? <Note title="结业证明">这份卷不发结业证明；发证的是课程的结业测评。</Note>
                : null
          ) : (
            <>
              <Note tone="warn">
                两次考试之间要间隔 {paper.rules.retryCooldownHours} 小时。
                建议先回去把没看完的讲次补上再来。
              </Note>
              <Button size="lg" block variant="ghost" onClick={onRetake}>查看补考规则</Button>
            </>
          )}
          {/* 测评报告入口。交卷之后由 AI 出一份诊断，报告页自己会轮询"还在生成中"——
              所以这里**不等它就绪再放入口**：要等就得在这一页也轮询一遍状态，
              而学员在成绩页停留的那几秒里报告多半还没写完，等于这个入口永远不出现。
              没通过的时候，这一页上真正要做的事是看报告（错在哪、下一步回看哪一段），
              所以给 accent 让它压过上面那个 ghost 的补考规则 —— 轻重靠颜色不靠先后。
              通过的时候首要动作是领证（primary），报告退成 ghost：一屏只留一个填色按钮。 */}
          <Link to={`/exam/report?attempt=${paper.attemptId}`}>
            <Button size="lg" block variant={passed ? 'ghost' : 'accent'}>查看测评报告</Button>
          </Link>

          {/* 开放卷没有课程，把人送回 "/" 却写着"回课程"是一句假话。
              没有课程时回到【这一份卷】的规则页：那一页写着这份卷现在还能不能再考、
              要等多久，比一个笼统的卷列表更接得住"我刚考完，接下来呢"。
              卷号也没有（老后端不下发）时才退回列表。 */}
          <Link
            to={courseId ? `/course/${courseId}` : paper.paperId ? `/exam?paper=${paper.paperId}` : '/exam'}
            style={{ fontSize: 'var(--t-sm)' }}
          >
            {courseId ? '回课程' : '回测评'}
          </Link>
        </Stack>
      </Card>
    </Screen>
  );
}

/* ================================================================ 版面 */

/**
 * 选卷与规则页的版面。
 *
 * 断点用 700 —— 本仓卡片列表（MyCourses、Orders）用的就是它，覆盖 640 以下的手机。
 * 再补一个断点只会让三处窄屏行为在不同宽度上错开。
 */
const EXAM_CSS = `
.wk-exam-h2 { font-size: var(--t-h3); margin-bottom: var(--s2); }
.wk-exam-groupdesc {
  color: var(--ink-2); font-size: var(--t-sm);
  line-height: var(--lh-body); margin-bottom: var(--s3);
}
.wk-exam-course { color: var(--ink-3); font-size: var(--t-xs); margin-bottom: 2px; }
.wk-exam-title { font-size: var(--t-body); font-weight: 600; line-height: 1.5; }
.wk-exam-sub {
  margin-top: 2px; color: var(--ink-3); font-size: var(--t-sm);
  overflow: hidden; text-overflow: ellipsis;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical;
}
.wk-exam-meta {
  display: grid; grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--s3); margin-top: var(--s4);
}
.wk-exam-k { color: var(--ink-3); font-size: var(--t-xs); }
.wk-exam-v { font-size: var(--t-h3); font-weight: 600; margin-top: 2px; }
.wk-exam-hint { margin-top: var(--s3); color: var(--ink-3); font-size: var(--t-xs); }
.wk-exam-reason {
  margin-top: var(--s3); color: var(--ink-2);
  font-size: var(--t-sm); line-height: var(--lh-body);
}
.wk-exam-cta { margin-top: var(--s4); display: flex; justify-content: flex-end; }
@media (max-width: 700px) {
  /* 四列在 360px 屏上每格只剩 75px，"2 / 3 次"会折成两行、数字和单位分家。 */
  .wk-exam-meta { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--s3) var(--s4); }
  /* 窄屏上按钮铺满：卡片整块不可点（嵌套可点区域读屏和键盘都进不去），
     这一个按钮就是唯一的入口，它必须好点。 */
  .wk-exam-cta > a { display: block; flex: 1; }
  .wk-exam-cta .wk-btn { width: 100%; }
}
`;
