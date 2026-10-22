/**
 * 测评报告（交卷之后由 AI 出的那一份）。地址 `/exam/report?attempt=N`。
 *
 * ## 这一页的地基：两层是分开画的
 *
 * 事实层（分数、各分组的正确率、错题清单）由后端用 SQL 算，**任何状态下都画**；
 * 叙述层（诊断与行动清单）由模型写，只有 `status='READY'` 才有。
 * 所以下面的渲染从头到尾没有一句「报告还没好 → 整页什么都不显示」——
 * 模型会挂、会超时、会被那道时间点校验判死，而学员的分数和他错在哪是客观的。
 * 把两层揉成一块画的话，模型一挂整页就空了，学员连自己错在哪都看不到。
 *
 * ## 版面次序为什么不是规格里那个逐条编号
 *
 * 规格第五节把要素编成 1 分数 / 2 正确率条 / 3 错题清单 / 4 AI 正文，
 * 这里画成 分数 → 正确率条 → **AI 正文** → 错题清单。理由是错题清单会有几十条，
 * 把叙述层压在它后面等于没人看得到 —— 而「接下来该回看哪一讲的哪一段」正写在那一段里。
 * 规格里明确带位置的只有第 1 条「顶部分数」，那一条照做了；4、5 两条说的是
 * 用哪个渲染器、怎么轮询，不是先后。
 *
 * ## 不显示正确答案
 *
 * 题库要复用（六份卷共 400 题，一份卷的题会在下一次补考里再出现），
 * 而这一页是学员自己能反复打开、能截图转发的页面。所以错题清单里
 * **只有他自己选了什么**，没有对的是什么 —— 接口那一侧也不下发（见 types.ts
 * 的 ExamReportWrong）。哪天有人往这里加一行「正确答案：B」，加的那一刻题库就失效了。
 *
 * ## 出处用站内已有的播放地址形式
 *
 * `/learn/{lessonId}?course={courseId}&t={秒}` —— 与 CourseDetail 的目录、
 * Learn 自己的 `?t=` 是同一套（见 Learn.tsx 里 seekTo 那段）。这里不另造一种地址：
 * 造出来的那种今天能跳，等哪天播放页改参数名时它会静默失效，
 * 表现是点了出处从头开始播，而没有任何东西报错。
 */
import React from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import {
  api, Button, Card, Empty, ErrorState, hms, Markdown, PageHead, Progress, Skeleton,
  Spinner, Tag, useToast,
} from '@wangke/shared';
import type {
  ExamReport as ReportBody, ExamReportGroup, ExamReportStats, ExamReportWrong,
} from '../types';
import { Note, pctText, Row, Screen, Stack } from '../kit';

/**
 * 轮询节奏，照规格第五节：3 秒一次、最多 40 次（约两分钟）。
 *
 * 上限不是装饰：真实一次生成 19.3 秒，40 次还没出结果说明那一行卡住了
 * （进程中途挂掉的 RUNNING 要等后端那个十分钟的清扫才会退回 QUEUED）。
 * 无上限地问下去的话，一个开着不管的标签页会一直打后端，
 * 而学员看到的仍然是同一个转圈 —— 到点就停下、把「刷新看看」交给他，比假装还在进行诚实。
 */
const POLL_MS = 3000;
const POLL_MAX = 40;

/** 难度的中文，照规格第三节那三档写死。库里是 1/2/3，别的值一律不显示（不编第四档）。 */
const DIFFICULTY: Record<number, string> = {
  1: '扎实理解',
  2: '需要实操',
  3: '踩过坑才会',
};

/**
 * 判断题两个选项键的中文。
 *
 * 与 Exam.tsx 里的 JUDGE_OPTIONS 是同一份约定（后端对 JUDGE 不下发 options，
 * 键就是 T/F 两个）。**没有从那边 import**：那是一个页面模块，为了两个字
 * 把两页绑在一起，将来谁都不敢动 Exam.tsx。这两个键来自接口契约、不会自己变，
 * 所以这份重复是有边界的。
 * 不映射的后果不是排版难看，是屏幕上写着「我的作答：T」—— 学员根本没见过 T 这个东西。
 */
const JUDGE_TEXT: Record<string, string> = { T: '正确', F: '错误' };

const TYPE_TEXT: Record<string, string> = { SINGLE: '单选', MULTI: '多选', JUDGE: '判断' };

/* ================================================================ 路由 */

export default function ExamReport() {
  const [sp] = useSearchParams();
  const attemptId = idParam(sp.get('attempt'));

  // 地址上没有场次编号时不发请求：`/api/exam/0/report` 只会换回一句
  // 「考试记录编号不合法」，而那句话对着一个打错的链接说没有任何用。
  if (attemptId <= 0) {
    return (
      <Screen maxWidth={560}>
        <PageHead title="测评报告" />
        <Card>
          <Empty
            icon="✎"
            title="这个地址少了考试场次"
            desc="测评报告要从成绩页进来。回到测评页，点进那一场考试就能看到入口。"
            action={<Link to="/exam"><Button variant="accent">去测评</Button></Link>}
          />
        </Card>
      </Screen>
    );
  }
  return <ReportScreen attemptId={attemptId} />;
}

/** 地址里的编号。`Number(null)` 是 0、`Number('abc')` 是 NaN，两种都当没给。（同 Exam.tsx） */
function idParam(raw: string | null): number {
  const n = Number(raw);
  return Number.isFinite(n) && n > 0 ? n : 0;
}

/* ================================================================ 取数与轮询 */

type ReportState = {
  /** 首屏：手上一个字都还没有。拿到过一次之后永远是 false —— 轮询不许再让页面塌回骨架。 */
  loading: boolean;
  data?: ReportBody;
  /** 首屏就失败：整页退回 ErrorState。已经有内容之后的失败走 note，不走这里。 */
  error?: string;
  /** 这一场压根没有报告行（404），与「不是你的场次」对外是同一种结果。 */
  missing: boolean;
  /** 已经有内容之后某一次轮询失败：把这句话留在页面上，事实层照旧不撤。 */
  note?: string;
  /**
   * 不再自动问了，下一步交回给学员。**两种停法要分开记**：
   * `cap` 是轮满四十次还没有结论，`error` 是撞上一个不会自己好的错（4xx）。
   * 合成一个布尔的话，屏幕上会对着一个刚发生的 403 写「等了两分钟还没有结果」——
   * 一句时间上就是假的话。
   */
  stalled: 'cap' | 'error' | null;
};

/**
 * 一轮轮询的计划。`round` 变了就重开一轮；`delay` 决定这一轮的第一问要不要先等一个间隔。
 *
 * 放 state 而不放 ref：ref 在 StrictMode 的双次挂载里会被第一次跑掉，
 * 第二次读到的是已经清过的值 —— 于是「先等一下再问」这条只在生产上成立，
 * 而本地怎么点都复现不出来。
 */
type Plan = { round: number; delay: boolean };

/**
 * 取报告 + 生成中自动轮询。
 *
 * **不用 useAsync**：它的 reload 会把 loading 置回 true，于是每 3 秒整页塌成一次骨架 ——
 * 而事实层那一段本来是「永远都在」的。这里轮询只换 data，屏幕上不闪。
 *
 * 计数器 `polls` 与定时器都是 effect 内部的局部量，不进 state：
 * 进 state 的话每一次计数都要重跑一遍 effect，等于把轮询循环拆成了递归重挂，
 * 而 StrictMode 下那种写法很容易变成两条并行的循环（两倍的请求，且谁也停不掉）。
 */
function useReport(attemptId: number) {
  const [plan, setPlan] = React.useState<Plan>({ round: 0, delay: false });
  const [st, setSt] = React.useState<ReportState>({ loading: true, missing: false, stalled: null });

  React.useEffect(() => {
    let live = true;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let polls = 0;
    let hasData = false;

    const tick = async () => {
      try {
        const r = await api.get<ReportBody>(`/api/exam/${attemptId}/report`);
        if (!live) return;
        hasData = true;
        setSt({ loading: false, data: r, missing: false, stalled: null });
        // 有结论了就收工。READY / FAILED 都不会再自己变，继续问下去只是白打后端。
        if (r.status !== 'QUEUED' && r.status !== 'RUNNING') return;
        if (polls >= POLL_MAX) { setSt((s) => ({ ...s, stalled: 'cap' })); return; }
        polls += 1;
        timer = setTimeout(tick, POLL_MS);
      } catch (e) {
        if (!live) return;
        const err = e as { status?: number; message?: string };
        // 404 = 这一场没有报告行，或者它不是你的 —— 后端有意把两者对外做成同一种结果。
        if (err.status === 404 && !hasData) {
          setSt({ loading: false, missing: true, stalled: null });
          return;
        }
        const msg = err.message || '测评报告暂时取不到';
        if (!hasData) { setSt({ loading: false, error: msg, missing: false, stalled: null }); return; }
        setSt((s) => ({ ...s, note: msg }));
        // 4xx 是「这一场不是你的 / 编号不对」，再问四十遍还是同一句话。
        // 断网与 5xx 是会自己好的那一类（后端发布重启正是这一种），那种要接着等。
        const permanent = err.status != null && err.status >= 400 && err.status < 500;
        if (permanent) { setSt((s) => ({ ...s, stalled: 'error' })); return; }
        if (polls >= POLL_MAX) { setSt((s) => ({ ...s, stalled: 'cap' })); return; }
        polls += 1;
        timer = setTimeout(tick, POLL_MS);
      }
    };

    if (plan.delay) timer = setTimeout(tick, POLL_MS); else void tick();
    return () => { live = false; if (timer !== undefined) clearTimeout(timer); };
  }, [attemptId, plan]);

  /** 再开一轮轮询，立刻问。手上已有的事实层留着不动，所以按下去屏幕不会塌一下。 */
  const refresh = React.useCallback(() => {
    setSt((s) => ({ ...s, note: undefined, stalled: null }));
    setPlan((p) => ({ round: p.round + 1, delay: false }));
  }, []);

  /**
   * 重试接口回了 200 之后走这一条，与 refresh 有两处不同：
   *
   * 1. **先把状态按成 QUEUED**。这不是猜：`POST …/report/retry` 返回 200 的含义
   *    就是「这一行已经退回排队」，是服务端说的话。
   * 2. **第一问要等一个间隔**。紧接着就问的话，有可能问在那一行真正改回 QUEUED 之前，
   *    读回来的还是 FAILED —— 而 FAILED 是终态，轮询当场收工，
   *    屏幕上表现成「点了重新生成，什么都没发生」。
   */
  const requeued = React.useCallback(() => {
    setSt((s) => ({
      ...s,
      note: undefined,
      stalled: null,
      data: s.data ? { ...s.data, status: 'QUEUED' } : s.data,
    }));
    setPlan((p) => ({ round: p.round + 1, delay: true }));
  }, []);

  return { ...st, refresh, requeued };
}

/* ================================================================ 整页 */

function ReportScreen({ attemptId }: { attemptId: number }) {
  const st = useReport(attemptId);
  const r = st.data;
  const s = r?.stats;

  // 回成绩页：带上卷号（与课程号）才能落回那一份卷的答题页，
  // 缺了也不拼一个 `paper=undefined` 出来 —— Exam.tsx 先看 attempt，只带场次也进得去。
  const back = s?.paperId
    ? `/exam?paper=${s.paperId}${s.courseId ? `&course=${s.courseId}` : ''}&attempt=${attemptId}`
    : `/exam?attempt=${attemptId}`;

  return (
    <Screen>
      <style>{REPORT_CSS}</style>
      <PageHead
        title="测评报告"
        desc="这一场考得怎么样、哪些地方还没吃透、接下来该回看哪一讲的哪一段。"
        extra={<Link to={back}><Button variant="ghost">回成绩页</Button></Link>}
      />

      {st.loading ? (
        // 骨架照真实首屏的块头给：一张分数卡 + 一组正确率条 + 一段正文。
        <Stack gap="var(--s5)">
          <Skeleton h={132} /><Skeleton h={180} /><Skeleton h={140} />
        </Stack>
      ) : st.missing ? (
        <Card>
          <Empty
            icon="✎"
            title="这一场没有测评报告"
            // 「不是你的」与「不存在」在接口那一侧是同一种结果，这里就不替它二选一。
            // 编一句「你没有权限」出来，遇到老场次时是冤枉学员；反过来也一样。
            desc="报告是交卷之后才生成的；很早以前考的那几场没有。也可能是地址里的场次编号不对。"
            // 这一档的出路给「看全部试卷」，不给页头那个「回成绩页」：
            // 编号本来就可能是错的，把人送回 `/exam?attempt=坏编号` 是再撞一次同一堵墙。
            action={<Link to="/exam"><Button variant="accent">看全部试卷</Button></Link>}
          />
        </Card>
      ) : st.error || !r ? (
        <ErrorState message={st.error || '测评报告暂时取不到'} onRetry={st.refresh} />
      ) : (
        <Stack gap="var(--s6)">
          {/* 事实层。stats 缺席（后端那一片算挂了）时整块不画，不用 0 填 ——
              一张写着「0 / 0 分」的报告比一句「算不出来」更能骗人。 */}
          {s ? <ScoreCard s={s} /> : (
            <Card><Note tone="warn">这一场的成绩与错题这次没有取到。可以过一会儿再刷新看看。</Note></Card>
          )}

          {st.note && (
            // 轮询中途失败：说一句就好，上面的事实层一个字都不撤。
            <Note tone="warn" title="刚才那次没问到">{st.note}</Note>
          )}

          {s && <Rates s={s} />}

          <Narrative
            attemptId={attemptId}
            r={r}
            stalled={st.stalled}
            onRefresh={st.refresh}
            onRequeued={st.requeued}
          />

          {s && <WrongList s={s} />}
        </Stack>
      )}
    </Screen>
  );
}

/* ================================================================ 分数 */

function ScoreCard({ s }: { s: ExamReportStats }) {
  // 三态，不给默认值：TINYINT(1) 过来可能是 true 也可能是 1，所以只按真假用；
  // 而这个键整个缺席时（后端那一片没算出来）两句话都不说 ——
  // 说「没通过」是在冤枉人，说「通过了」更糟。
  const passed = s.passed == null ? null : !!s.passed;
  // 元信息按「有什么写什么」拼：缺的那一段整段不出现。
  // 写成「第 undefined 次考试」的那种兜底，屏幕上是一句谁也看不懂的话。
  const meta = [
    s.attemptNo != null ? `第 ${s.attemptNo} 次考试` : null,
    s.passScore != null ? `及格线 ${s.passScore} 分` : null,
  ].filter(Boolean).join(' · ');

  // 分母（后端叫 questionCount）缺席或为 0 就整段不画，只剩一个分子。
  // 这不是防后端漏发（那边是 int，一定有值），是防**键名再一次对不上**：
  // 上一版这里写死了「/ {s.total}」，而后端下发的键是 questionCount，
  // 屏幕上首屏最大的那个数字就成了「31 / 」—— 断在半截、tsc 不说话、页面不报错。
  // 「31」是一个不完整但没有说谎的数字，「31 / 」是一处一眼能看见的坏；
  // 两者都比「31 / 0」强：那个 0 是编出来的分母。
  const denom = s.questionCount != null && s.questionCount > 0 ? s.questionCount : null;

  // 分数卡不加 accent（那道红顶边）：学员端一处都没用过它，
  // 而页头那道红杠已经是这一屏的红锚点，两处红并排会让人以为分数卡本身可点。
  return (
    <Card>
      {s.paperTitle && <div className="wk-rep-paper">{s.paperTitle}</div>}
      <Row wrap={false} align="flex-end" style={{ justifyContent: 'space-between' }}>
        <div className="num wk-rep-score" style={{
          color: passed ? 'var(--ok)' : 'var(--ink)',
        }}>
          {s.score}
          {denom != null && <span className="wk-rep-score-total"> / {denom}</span>}
        </div>
        {passed == null ? null : (
          <Tag tone={passed ? 'ok' : 'warn'}>{passed ? '已通过' : '没有通过'}</Tag>
        )}
      </Row>
      {meta && <div className="wk-rep-meta">{meta}</div>}
    </Card>
  );
}

/* ================================================================ 正确率 */

/**
 * 四组正确率条。每一组都可能整组不下发（题库那三列是回填的，没灌到的题聚不出组），
 * 空组一律不画标题 —— 一个只有标题没有内容的小节，看起来像加载坏了。
 */
function Rates({ s }: { s: ExamReportStats }) {
  const groups = [
    { title: '按讲次', rows: s.byLesson },
    { title: '按板块', rows: s.byTopic },
    { title: '按题型', rows: s.byType },
    { title: '按难度', rows: s.byDifficulty },
  ].filter((g): g is { title: string; rows: ExamReportGroup[] } =>
    Array.isArray(g.rows) && g.rows.length > 0);

  if (groups.length === 0) return null;

  return (
    <section>
      <h2 className="wk-rep-h2">正确率</h2>
      <Stack>
        {groups.map((g) => (
          <Card key={g.title}>
            <div className="wk-rep-h3">{g.title}</div>
            <Stack gap="var(--s4)">
              {/* React key 用 label：后端这四组是 `LinkedHashMap<label, …>` 聚出来的
                  （ExamReportService.tally），label 就是聚合键本身，组内一定唯一。
                  原来这里用的是 `row.key` —— 后端 Bucket 根本没有这个字段，
                  于是一整组的 key 都是同一个 `"按讲次:undefined"`。这一页每 3 秒换一批
                  新对象，重复 key 下 React 的复用不作保证：表现是某一条不跟着更新
                  或者两条对调，**而且不抛异常**，只有开发期控制台里那句 duplicate key。
                  别退回用数组下标：下标在「这一轮少了一组」时同样会把两条对错位。 */}
              {g.rows.map((row) => <RateBar key={`${g.title}:${row.label}`} row={row} />)}
            </Stack>
          </Card>
        ))}
      </Stack>
    </section>
  );
}

/**
 * 一条正确率。
 *
 * **条本身不可点。** 它是一段统计不是一处出处：点它只能跳到那一讲的续播位置，
 * 而那个位置和他错的那道题没有任何关系 —— 学员会以为自己点错了。
 * 能点的是错题清单里逐题的出处，那里带着秒数。
 *
 * 条子一律用 ink 不用 brand：DS 的规矩是每屏只留一个红锚点（页头那道红杠），
 * 而这一页正常会有十几条。（与 Assess.tsx 里那条进度条同色。）
 */
function RateBar({ row }: { row: ExamReportGroup }) {
  // total 是分母，为 0 时不做除法：NaN 一路走到 pctText 会渲染成「NaN%」。
  const pct = row.total > 0 ? (row.correct / row.total) * 100 : 0;
  return (
    <div>
      <div className="wk-rep-barhead">
        <span className="wk-rep-barlabel">{row.label}</span>
        <span className="num wk-rep-barnum">
          {row.correct} / {row.total} · {row.total > 0 ? pctText(pct) : '—'}
        </span>
      </div>
      <Progress pct={pct} tone="ink" />
    </div>
  );
}

/* ================================================================ AI 正文 */

/**
 * 叙述层。四种状态各画各的，**没有一种会把上下两侧的事实层挡住**。
 *
 * FAILED 时把后端那句理由原样显示（"模型引用了不存在的时间点"这一类），
 * 前端不改写也不加前缀：改写过的那一句和后台日志里的那一句对不上，
 * 客服拿着截图查不到任何东西。
 */
function Narrative({ attemptId, r, stalled, onRefresh, onRequeued }: {
  attemptId: number; r: ReportBody; stalled: 'cap' | 'error' | null;
  onRefresh: () => void; onRequeued: () => void;
}) {
  const toast = useToast();
  const [retrying, setRetrying] = React.useState(false);

  const retry = async () => {
    setRetrying(true);
    try {
      await api.post(`/api/exam/${attemptId}/report/retry`);
      toast({ tone: 'ok', text: '已经重新排队，稍等一会儿' });
      onRequeued();       // 重开一轮轮询，不用学员自己刷新
    } catch (e) {
      // 限频、状态不对（不是 FAILED）都会落到这里，整句由后端给。
      toast({ tone: 'danger', text: (e as Error).message || '没能重新排上队，请稍后再试' });
    } finally {
      setRetrying(false);
    }
  };

  const body = () => {
    if (r.status === 'READY' && (r.content ?? '').trim().length > 0) {
      return (
        <Card>
          <Markdown text={r.content!} />
          <div className="wk-rep-model">
            {r.model ? `由 ${r.model} 生成` : '由模型生成'}
            ，可能有疏漏；分数与错题以本页上下两段的事实为准。
          </div>
        </Card>
      );
    }
    // READY 却没有正文：库里那条触发器（status='READY' 必须有非空 content）
    // 不许这样落库，所以走到这里说明接口对不上了。**画一片空白比说出来更坏** ——
    // 学员会以为这份报告本来就这么短，而不会想到是坏了。
    if (r.status === 'READY') {
      return <Note tone="warn" title="正文是空的">
        报告标成了已生成，但正文没有取到内容。上面的成绩与下面的错题清单是代码算的，照常可用。
      </Note>;
    }
    if (r.status === 'FAILED') {
      return (
        <Stack gap="var(--s3)">
          <Note tone="warn" title="这次没有写出来">
            {r.failReason || '生成失败，原因没有记录下来。'}
            <div style={{ marginTop: 'var(--s2)' }}>
              上面的成绩与下面的错题清单是代码算出来的，不受影响。
            </div>
          </Note>
          <Row gap="var(--s3)">
            <Button
              variant="accent" loading={retrying}
              disabled={r.canRetry === false}
              onClick={retry}
            >重新生成</Button>
          </Row>
          {r.canRetry === false && (
            // 具体几分钟不写死在这里：那个数在后端，两处各写一个迟早对不上，
            // 而对不上的表现是按钮亮了、点下去被拒。
            <div className="wk-rep-quiet">刚刚已经重新生成过一次，过几分钟才能再点。</div>
          )}
        </Stack>
      );
    }
    // QUEUED / RUNNING
    return (
      <Card>
        <Row gap="var(--s3)" wrap={false} align="flex-start">
          <span style={{ paddingTop: 2, color: 'var(--ink-3)' }}><Spinner size={18} /></span>
          <div style={{ minWidth: 0 }}>
            <div style={{ fontSize: 'var(--t-body)', fontWeight: 600 }}>
              {r.status === 'QUEUED' ? '排队中' : '正在写这份报告'}
            </div>
            <div className="wk-rep-quiet" style={{ marginTop: 'var(--s1)' }}>
              一般要几十秒。这一页会自己刷新，不用守着；上面的成绩与下面的错题清单现在就能看。
            </div>
            {stalled && (
              <div style={{ marginTop: 'var(--s3)' }}>
                <div className="wk-rep-quiet" style={{ marginBottom: 'var(--s2)' }}>
                  {stalled === 'cap'
                    ? '等了两分钟还没有结果，这一页先不自动刷新了。'
                    : '刚才那一次没问到（原因写在上面），这一页先不自动刷新了。'}
                </div>
                {/* 传函数引用会把 click 事件当参数交给它，所以这一层箭头不能省 ——
                    refresh 现在不收参数，但下一个人给它加一个可选参数时，
                    那个 MouseEvent 会静默变成 true。 */}
                <Button variant="ghost" onClick={() => onRefresh()}>刷新看看</Button>
              </div>
            )}
          </div>
        </Row>
      </Card>
    );
  };

  return (
    <section>
      <h2 className="wk-rep-h2">诊断与下一步</h2>
      {body()}
    </section>
  );
}

/* ================================================================ 错题清单 */

function WrongList({ s }: { s: ExamReportStats }) {
  const rows = s.wrong;
  return (
    <section>
      <h2 className="wk-rep-h2">错题清单</h2>
      {rows == null ? (
        // 键整个缺席 = 没算出来，不是「全对」。这两件事在屏幕上必须分得开：
        // 把没算出来画成「这一场没有错题」，是替平台说了一句它并不知道的话。
        <Card><Note tone="warn">错题清单这次没有取到。</Note></Card>
      ) : rows.length === 0 ? (
        <Card><Note tone="ok" title="全对">这一场没有错题。</Note></Card>
      ) : (
        <Stack>
          {rows.map((w, i) => <WrongCard key={w.questionId} w={w} index={i + 1} courseId={s.courseId} />)}
        </Stack>
      )}
    </section>
  );
}

function WrongCard({ w, index, courseId }: {
  w: ExamReportWrong; index: number; courseId?: number;
}) {
  const diff = w.difficulty != null ? DIFFICULTY[w.difficulty] : undefined;
  return (
    <Card>
      <Row gap="var(--s3)" align="flex-start" wrap={false}>
        <span className="num wk-rep-idx">{index}</span>
        <div style={{ flex: 1, minWidth: 0 }}>
          <Row gap="var(--s2)">
            {w.type && <Tag tone={w.type === 'MULTI' ? 'warn' : 'neutral'}>{TYPE_TEXT[w.type]}</Tag>}
            {diff && <Tag tone="neutral">{diff}</Tag>}
          </Row>
          <div className="wk-rep-stem">{w.stem}</div>
          <div className="wk-rep-kv">
            <span className="wk-rep-k">我的作答</span>
            <span className="wk-rep-v">{answerText(w)}</span>
          </div>
          {w.knowledgePoint && (
            <div className="wk-rep-kv">
              <span className="wk-rep-k">考点</span>
              <span className="wk-rep-v">{w.knowledgePoint}</span>
            </div>
          )}
          {/* 出处没登记（题库里 lesson_id 可空）时整行不画：
              写一句「出处：暂无」既占地方又没用，而且看起来像内容缺失。 */}
          {w.lessonId != null && (
            <div className="wk-rep-kv">
              <span className="wk-rep-k">出处</span>
              <span className="wk-rep-v">
                <Link to={learnHref(w.lessonId, courseId, w.sourceTsSec)}>
                  {w.lessonTitle || '这一讲'}
                  {w.sourceTsSec != null && w.sourceTsSec > 0
                    ? <span className="num"> · {hms(w.sourceTsSec)}</span>
                    : null}
                </Link>
              </span>
            </div>
          )}
          {/* 这里刻意没有「正确答案」这一行，见文件头。 */}
        </div>
      </Row>
    </Card>
  );
}

/**
 * 他这道题选了什么。
 *
 * 没作答与答错是两件事，屏幕上要分得开：一个没答完卷的人看到满屏
 * 「我的作答：—」，会以为系统把他的答案弄丢了。
 */
function answerText(w: ExamReportWrong): React.ReactNode {
  const raw = (w.myAnswer ?? '').trim();
  if (!raw) return <span className="wk-rep-blank">没有作答</span>;
  if (w.type === 'JUDGE') return JUDGE_TEXT[raw] ?? raw;
  // 多选在库里是 "AB" 这样一串（见 Exam.tsx 里 setAnswer 的拼法），拆开读着才像话。
  return raw.split('').join('、');
}

/**
 * 播放页地址。**站内已有的那一种**：`/learn/{lessonId}?course={courseId}&t={秒}`。
 *
 * - `course` 只服务于播放页右侧的目录，缺了照样能播（Learn 自己会说明），
 *   所以开放卷（不挂课程）也给得出一条能用的链接；
 * - `t` 只在大于 0 时才带 —— Learn 里 `?t=` 优先于续播位置，
 *   带一个 0 上去等于把他按在片头，而他本来该回到上次停的地方。
 */
function learnHref(lessonId: number, courseId?: number, tsSec?: number): string {
  const q: string[] = [];
  if (courseId) q.push(`course=${courseId}`);
  if (tsSec != null && tsSec > 0) q.push(`t=${Math.floor(tsSec)}`);
  return `/learn/${lessonId}` + (q.length > 0 ? `?${q.join('&')}` : '');
}

/* ================================================================ 版面 */

/**
 * 断点用 700，与 Exam.tsx / MyCourses / Orders 一致，覆盖 640 以下的手机。
 * 再补一个断点只会让几处窄屏行为在不同宽度上错开。
 */
const REPORT_CSS = `
.wk-rep-h2 { font-size: var(--t-h3); margin-bottom: var(--s3); }
.wk-rep-h3 {
  font-size: var(--t-sm); color: var(--ink-3);
  margin-bottom: var(--s3); padding-bottom: var(--s2);
  border-bottom: 1px solid var(--line);
}
.wk-rep-paper { color: var(--ink-3); font-size: var(--t-sm); margin-bottom: var(--s2); }
.wk-rep-score { font-size: var(--t-display); font-weight: 600; line-height: 1.05; }
.wk-rep-score-total { font-size: var(--t-h3); color: var(--ink-3); font-weight: 400; }
.wk-rep-meta { margin-top: var(--s2); color: var(--ink-3); font-size: var(--t-sm); }

.wk-rep-barhead {
  display: flex; justify-content: space-between; gap: var(--s3);
  align-items: baseline; margin-bottom: var(--s2);
  font-size: var(--t-sm);
}
/* 分组名可能很长（"第三章 · AI 原理与 RAG"），右边那串数字一个字都不能被挤掉：
   它才是这一条要说的事。所以让名字省略号，数字 flex: none。 */
.wk-rep-barlabel { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.wk-rep-barnum { flex: none; color: var(--ink-3); font-size: var(--t-xs); }

.wk-rep-idx { color: var(--ink-3); font-size: var(--t-sm); padding-top: 2px; flex: none; }
.wk-rep-stem {
  margin-top: var(--s2); font-size: var(--t-body);
  line-height: var(--lh-body); word-break: break-word;
}
.wk-rep-kv {
  display: flex; gap: var(--s3); margin-top: var(--s2);
  font-size: var(--t-sm); line-height: var(--lh-body);
}
/* 三个标签（我的作答/考点/出处）左侧对齐成一列，值才扫得下来。
   5em 装得下这三个词里最长的那个。 */
.wk-rep-k { flex: none; width: 5em; color: var(--ink-3); }
.wk-rep-v { min-width: 0; word-break: break-word; }
.wk-rep-blank { color: var(--ink-3); }
.wk-rep-model {
  margin-top: var(--s4); padding-top: var(--s3);
  border-top: 1px solid var(--line);
  color: var(--ink-3); font-size: var(--t-xs); line-height: var(--lh-body);
}
.wk-rep-quiet { color: var(--ink-3); font-size: var(--t-sm); line-height: var(--lh-body); }

@media (max-width: 700px) {
  /* 窄屏上标签另起一行：5em 一列 + 一句长考点，在 360px 屏上值那一侧只剩十来个字符，
     一个考点名会折成四行。 */
  .wk-rep-kv { display: block; }
  .wk-rep-k { width: auto; display: block; font-size: var(--t-xs); margin-bottom: 2px; }
}
`;
