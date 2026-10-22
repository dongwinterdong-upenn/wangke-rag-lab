/**
 * 学习页。
 *
 * ## 两条进度通道都要接
 *
 * 后端把进度拆成了两条路，各自能证明的东西不同：
 *   - `/api/learn/progress`  只说"我播到第几秒" → 只推进**断点续播**位置
 *   - `/api/learn/heartbeat` 带会话与倍速、留逐条明细 → 才给**有效观看时长**记账
 *
 * shared 的 `<Player>` 只回调第一条。只接第一条的后果不是"少个数字"：
 * watched_sec 恒为 0 → 课程进度恒 0% → 测评的 require_progress_pct 永远过不去，
 * 学员看完了也不许考试，而页面上没有任何东西提示他哪里不对。
 * 所以心跳在这一页自己发，`sessionId` 由 <Player> 的 onSession 回调给出
 * （不另开一次 /api/play/session —— 那会凭空多一条会话、多一次设备绑定）。
 *
 * ## 水印传完整手机号
 *
 * 打过码的 138****8888 在一段盗录视频里指向上万个人，等于没有水印。
 * 这个号是用户自己在登录框里输的，存在本机，从没离开过这台设备；
 * 页面上其它任何地方显示的都是打码后的形式。
 */
import React from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import {
  api, Button, Card, deviceId, ErrorState, hms, Progress, Skeleton, Tag, useAsync,
} from '@wangke/shared';
// 深路径导入是有意的，见 vite.config.ts 里那条别名的说明：
// 走 barrel 会把 hls.js 拖进首屏那一包。这里拿到的是同一份 shared/src/Player.tsx。
import { Player } from '@wangke/shared/Player';
import type { CourseLessons, LessonRow, ProgressSnapshot } from '../types';
import { loadMe, rememberLastLesson, watermarkText } from '../session';
import { Note, pctText, Row, Stack } from '../kit';

export default function Learn() {
  const { lessonId: raw } = useParams();
  const [sp] = useSearchParams();
  const lessonId = Number(raw);
  const courseId = Number(sp.get('course'));
  const nav = useNavigate();
  const wrap = React.useRef<HTMLDivElement>(null);

  const [watermark, setWatermark] = React.useState<string>(() => watermarkText(null));
  const [snap, setSnap] = React.useState<ProgressSnapshot | null>(null);

  // 目录用于右侧/下方的讲次列表、上一讲下一讲、续播起点。
  // 没带 course 参数（比如从别处贴来的链接）时**不拦播放**——播放器自己会
  // 去开会话、后端自己会判权益。只是少了目录，不是少了课。
  // 试听（先导课）走到这里时他【没有这门课的权益】，那条目录接口会回 403。
  // 那不是错误，是"他还没买"—— 吞掉它，播放照常，只是右侧没有目录。
  // 只吞 403：别的错该照常冒出来，否则一个真的挂了的接口会被显示成"这门课没有目录"。
  const dir = useAsync<CourseLessons | null>(
    () => (Number.isFinite(courseId) && courseId > 0
      ? api.get<CourseLessons>(`/api/learn/course/${courseId}/lessons`)
          .catch((e: { status?: number }) => {
            if (e?.status === 403) return null;
            throw e;
          })
      : Promise.resolve(null)),
    [courseId],
  );

  // 水印手机号，取号顺序见 session.watermarkText：
  // 服务端完整号 > 本机存的号 > 打码号 > 占位。取不到也照播 ——
  // 水印宁可弱一点，也不能因为它把课挡住。
  React.useEffect(() => {
    let live = true;
    loadMe().then((me) => { if (live) setWatermark(watermarkText(me)); }).catch(() => {});
    return () => { live = false; };
  }, []);

  const all: LessonRow[] = React.useMemo(
    () => dir.data?.chapters.flatMap((c) => c.lessons) ?? [],
    [dir.data],
  );
  const here = all.find((l) => l.lessonId === lessonId);
  const idx = all.findIndex((l) => l.lessonId === lessonId);
  const prev = idx > 0 ? all[idx - 1] : null;
  const nextL = idx >= 0 && idx < all.length - 1 ? all[idx + 1] : null;

  // sessionId 由 <Player> 的 onSession 回调给出。
  // 原来这里是从一个包住 window.fetch 的全局补丁里读的 —— 那个补丁是
  // shared/Player 契约没对齐时的临时物，shared 修好后已经拆掉。
  const [sessionId, setSessionId] = React.useState<string | null>(null);
  React.useEffect(() => { setSessionId(null); }, [lessonId]);
  const title = here?.title ?? `第 ${lessonId} 讲`;

  /**
   * 续播起点**定一次就不再改**。
   *
   * `<Player>` 把 startAt 放进了开播回调的依赖里：这个值一变，它会重新
   * 开一次播放会话。而目录是异步来的 —— 先给 0、目录到了再给 618 的话，
   * 学员会看到视频从头播了两秒然后整个重载，服务端那边多出一条会话、
   * 多一次设备绑定、风控里多一条噪声。所以等目录落定再挂播放器。
   *
   * <p><b>?t= 优先于续播位置。</b>它是学员刚刚在目录上点的那一节 ——
   * 他明确说了要从这里看。反过来让续播位置赢的话，点第 3 节会跳到上次停的第 18 节，
   * 而页面上没有任何东西解释为什么。
   *
   * <p>?t= 不等目录：它来自地址栏，第一帧就有。等目录的是「续播」那一支，
   * 因为那个值只有目录里才有。
   */
  const seekTo = (() => {
    const raw = Number(sp.get('t'));
    return Number.isFinite(raw) && raw > 0 ? Math.floor(raw) : null;
  })();
  const [startAt, setStartAt] = React.useState<number | null>(seekTo);
  React.useEffect(() => { setStartAt(seekTo); }, [lessonId, seekTo]);
  React.useEffect(() => {
    if (startAt !== null || dir.loading) return;
    setStartAt(here?.maxPositionSec ?? 0);
  }, [startAt, dir.loading, here]);

  /* ---- 断点续播位置上报（Player 的 onProgress） ---------------------- */
  const onProgress = React.useCallback((positionSec: number) => {
    api.post<ProgressSnapshot>('/api/learn/progress', { lessonId, positionSec })
      .then(setSnap)
      .catch(() => { /* 进度报不上不该打断看课，下一次心跳还会再报一遍 */ });

    // 课程名不存进来：它在「我的课程」那边本来就有一份，存两份迟早会对不上
    // （课程改名之后，续播卡上仍然是旧名字，而旁边那张课程卡已经是新的）。
    if (Number.isFinite(courseId) && courseId > 0) {
      rememberLastLesson({ courseId, lessonId, lessonTitle: title, positionSec, at: Date.now() });
    }
  }, [lessonId, courseId, title]);

  /* ---- 有效观看时长的心跳 ------------------------------------------- */
  React.useEffect(() => {
    if (!sessionId) return;
    const tick = () => {
      const v = wrap.current?.querySelector('video');
      // 暂停 / 播完不发：后端按"真实流逝秒数 × 倍速"记账，
      // 暂停时照发等于把没看的时间记成看过了，而那是反作弊要挡的行为。
      if (!v || v.paused || v.ended || !Number.isFinite(v.currentTime)) return;
      api.post<ProgressSnapshot>('/api/learn/heartbeat', {
        lessonId,
        sessionId,
        positionSec: Math.floor(v.currentTime),
        playbackRate: v.playbackRate,
        deviceId: deviceId(),
      }).then(setSnap).catch(() => {});
    };
    const t = setInterval(tick, 15000);
    return () => clearInterval(t);
  }, [sessionId, lessonId]);

  /**
   * 侧栏里「现在正播到第几节」。
   *
   * <p>服务端给的 current 是<b>打开这一页那一刻</b>的快照，学员往下看、或者拖了进度条之后
   * 它就不动了 —— 目录上高亮的还是十分钟前那一节。所以这里自己盯着播放位置。
   *
   * <p><b>只在「落到了另一节」的时候才 setState。</b>每秒把 currentTime 存进 state 的话，
   * 整页每秒重渲一次（侧栏有二三十行），而屏幕上一秒里通常什么都没变。
   */
  const nowSections = here?.sections ?? [];
  const [nowIdx, setNowIdx] = React.useState(-1);
  React.useEffect(() => { setNowIdx(-1); }, [lessonId]);
  React.useEffect(() => {
    if (nowSections.length === 0) return;
    const tick = () => {
      const v = wrap.current?.querySelector('video');
      if (!v || !Number.isFinite(v.currentTime)) return;
      const t = v.currentTime;
      const i = nowSections.findIndex((sec) => t >= sec.startSec && t < sec.endSec);
      setNowIdx((prev) => (prev === i ? prev : i));
    };
    tick();
    const id = setInterval(tick, 1000);
    return () => clearInterval(id);
  }, [lessonId, nowSections.length]);

  /**
   * 在<b>当前这一讲</b>里跳到某一节：直接改 video.currentTime，不走路由。
   *
   * <p>走路由（`?t=` 换一个新值）也能到，但代价是 startAt 变了 → `<Player>` 重开一次
   * 播放会话 → 新建一条 play_session、重绑一次设备、给风控加一条噪声。
   * 一节课来回跳十次就是十条会话，而学习时长的记账是挂在会话上的。
   * 跨讲才走路由（那时本来就要换片源）。
   */
  const seekInPlace = React.useCallback((sec: number) => {
    const v = wrap.current?.querySelector('video');
    if (!v) return;
    v.currentTime = sec;
    // 用户点目录就是想看，暂停着的话顺手播起来。被浏览器的自动播放策略拒了也无所谓 ——
    // 这一刻是真实用户手势触发的，正常不会被拒。
    void v.play?.().catch(() => {});
  }, []);

  if (!Number.isFinite(lessonId)) {
    return <ErrorState message="这一讲的地址不对，请从课程目录重新进入" />;
  }

  const pct = snap?.progressPct ?? here?.progressPct ?? 0;

  return (
    <div>
      <style>{`
        .wk-learn { display: grid; gap: var(--s5); }
        /* 手机上播放器要全宽：main 的左右内边距在这里是浪费的画面。
           负边距把它抵消掉，其余内容仍在原来的栏宽里。 */
        .wk-stage { margin: calc(var(--s5) * -1) calc(var(--s4) * -1) 0; }
        @media (min-width: 861px) {
          .wk-learn { grid-template-columns: minmax(0, 1fr) 320px; align-items: start; }
          .wk-stage { margin: 0; }
          .wk-aside { position: sticky; top: calc(54px + var(--s5)); max-height: calc(100vh - 54px - var(--s7)); overflow: auto; }
        }
      `}</style>

      <div className="wk-learn">
        <div>
          <div className="wk-stage" ref={wrap}>
            {startAt === null ? (
              <div style={{
                aspectRatio: '16 / 9', background: 'var(--surface-3)',
                display: 'grid', placeItems: 'center', color: 'var(--ink-3)',
                fontSize: 'var(--t-sm)', borderRadius: 'var(--r-lg)',
              }}>正在准备播放…</div>
            ) : (
              <Player
                lessonId={lessonId}
                title={title}
                watermark={watermark}
                startAt={startAt}
                onProgress={onProgress}
                onSession={setSessionId}
                onEnded={() => {
                  if (nextL) nav(`/learn/${nextL.lessonId}?course=${courseId}`);
                }}
              />
            )}
          </div>

          <div style={{ marginTop: 'var(--s5)' }}>
            <Stack>
              <Row gap="var(--s3)" style={{ justifyContent: 'space-between' }}>
                <div style={{ minWidth: 0 }}>
                  <h1 style={{ fontSize: 'var(--t-h2)' }}>{title}</h1>
                  <Row gap="var(--s3)" style={{ marginTop: 'var(--s2)', fontSize: 'var(--t-sm)',
                                                color: 'var(--ink-3)' }}>
                    {here && <span className="num">时长 {hms(here.durationSec)}</span>}
                    <span>本讲已看 {pctText(pct)}</span>
                    {(snap?.completed ?? here?.completed) && <Tag tone="ok">已学完</Tag>}
                  </Row>
                </div>
              </Row>

              <Progress pct={pct} />

              <Row gap="var(--s3)">
                {prev && (
                  <Button variant="ghost" onClick={() => nav(`/learn/${prev.lessonId}?course=${courseId}`)}>
                    ‹ 上一讲
                  </Button>
                )}
                {nextL && (
                  <Button onClick={() => nav(`/learn/${nextL.lessonId}?course=${courseId}`)}>
                    下一讲 ›
                  </Button>
                )}
                {Number.isFinite(courseId) && courseId > 0 && (
                  <Link to={`/qa?course=${courseId}&lesson=${lessonId}`}>
                    <Button variant="quiet">这一讲有疑问</Button>
                  </Link>
                )}
              </Row>
            </Stack>
          </div>
        </div>

        <aside className="wk-aside">
          <LessonSidebar
            state={dir} lessonId={lessonId} courseId={courseId} onSeek={seekInPlace}
            nowIdx={nowIdx}
          />
        </aside>
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ 目录 */

function LessonSidebar({ state, lessonId, courseId, onSeek, nowIdx }: {
  state: { loading: boolean; error?: string; data?: CourseLessons | null; reload?: () => void };
  lessonId: number;
  courseId: number;
  /** 在当前这一讲里跳到某一秒。跨讲不走它，走路由 */
  onSeek: (sec: number) => void;
  /** 现在正播到本讲第几节（下标，-1 = 还不知道）。跟着播放走，不是打开页面那一刻的快照 */
  nowIdx: number;
}) {
  const nav = useNavigate();

  if (state.loading) {
    return (
      <Card pad={false}>
        <div style={{ padding: 'var(--s4)', display: 'grid', gap: 'var(--s3)' }}>
          <Skeleton h={14} w="40%" />
          <Skeleton h={40} /><Skeleton h={40} /><Skeleton h={40} />
        </div>
      </Card>
    );
  }
  if (state.error) {
    return <Card><ErrorState message={`目录没能加载出来：${state.error}`} onRetry={state.reload} /></Card>;
  }
  if (!state.data) {
    // 这里有两种完全不同的"没有目录"，原来共用一句话，而那句话对第二种是**假的**：
    //   ① 链接压根没带 course 参数 —— 播放确实不受影响；
    //   ② 带了，但目录接口回 403（没买 / 权益已过期）—— 播放很可能也播不了，
    //      而这恰恰是唯一该出现「去买 / 去激活」的位置。
    const noCourse = !Number.isFinite(courseId) || courseId <= 0;
    return (
      <Card>
        <Note>
          {noCourse ? (
            <>这个链接没带课程信息，所以目录显示不出来 —— 播放不受影响。</>
          ) : (
            <>
              你还没有这门课的观看权限（或权限已到期），所以看不到完整目录。
              先导课可以直接看；要看后面几讲，去
              <Link to={`/course/${courseId}`}>课程页</Link>
              买或<Link to="/activate">激活</Link>。
            </>
          )}
          <div style={{ marginTop: 'var(--s2)' }}>
            <Link to="/">回我的课程</Link>
          </div>
        </Note>
      </Card>
    );
  }

  const chapters = state.data.chapters.filter((c) => c.lessons.length > 0);
  if (chapters.length === 0) {
    return <Card><Note>这门课暂时还没有可以观看的讲次。</Note></Card>;
  }

  return (
    <Card pad={false}>
      <div style={{
        padding: 'var(--s4)', borderBottom: '1px solid var(--line)',
        fontSize: 'var(--t-sm)', fontWeight: 600, color: 'var(--ink-2)',
      }}>课程目录</div>
      <div>
        {chapters.map((ch, ci) => (
          <div key={ch.chapterId}>
            <div style={{
              padding: 'var(--s3) var(--s4)', background: 'var(--surface-2)',
              fontSize: 'var(--t-xs)', color: 'var(--ink-3)', fontWeight: 600,
            }}>第 {ci + 1} 章 · {ch.title}</div>
            {ch.lessons.map((l) => {
              const on = l.lessonId === lessonId;
              return (
                <React.Fragment key={l.lessonId}>
                <button
                  onClick={() => nav(`/learn/${l.lessonId}?course=${courseId}`)}
                  style={{
                    display: 'flex', gap: 'var(--s3)', alignItems: 'center', width: '100%',
                    textAlign: 'left', border: 'none', cursor: 'pointer', minHeight: 52,
                    padding: 'var(--s3) var(--s4)',
                    borderBottom: '1px solid var(--line)',
                    background: on ? 'var(--brand-soft)' : 'transparent',
                    color: on ? 'var(--brand)' : 'var(--ink)',
                    fontWeight: on ? 600 : 400, fontSize: 'var(--t-sm)',
                  }}
                >
                  <span aria-hidden style={{ width: 14, color: l.completed ? 'var(--ok)' : 'var(--ink-4)' }}>
                    {l.completed ? '✓' : on ? '▶' : '·'}
                  </span>
                  <span style={{ flex: 1, minWidth: 0 }}>
                    {l.title}
                    <span style={{ display: 'block', fontSize: 'var(--t-xs)', color: 'var(--ink-3)',
                                   fontWeight: 400 }} className="num">
                      {hms(l.durationSec)}
                      {(l.progressPct ?? 0) > 0 && !l.completed && ` · 已看 ${pctText(l.progressPct)}`}
                      {(l.sections?.length ?? 0) > 0 && ` · ${l.sections.length} 节`}
                    </span>
                  </span>
                </button>
                {/* ★ 只给【正在看的那一讲】铺开分节。16 讲一共 90 节，全铺开的话
                    这条侧栏比正片还长，而学员这一刻只在这一讲里跳。
                    ★ 必须跟讲次按钮在同一次 map 里出来。分两次 map 的话，
                    分节会落在【本章所有讲次之后】，而不是紧跟着它自己那一讲。
                    2026-08-21 之前每章只有一讲，这条看不出来；现在每章四五讲，
                    写错了就是「第 5 讲的目录挂在第 8 讲下面」。 */}
                {on && (l.sections?.length ?? 0) > 0 && (
                <ol style={{
                  margin: 0, padding: 0, listStyle: 'none', background: 'var(--surface-2)',
                }}>
                  {l.sections.map((sec, si) => {
                    // 正在播的那一节优先于服务端快照里的 current：
                    // 快照是打开页面那一刻的，学员看了十分钟之后它就不对了。
                    const playing = nowIdx >= 0 ? si === nowIdx : !!sec.current;
                    return (
                    <li key={sec.sortNo}>
                      <button
                        type="button" onClick={() => onSeek(sec.startSec)}
                        aria-current={playing ? 'true' : undefined}
                        style={{
                          display: 'flex', gap: 8, alignItems: 'flex-start', width: '100%',
                          textAlign: 'left', border: 'none', cursor: 'pointer', minHeight: 40,
                          padding: '8px var(--s4) 8px 30px', font: 'inherit',
                          fontSize: 'var(--t-xs)', lineHeight: 'var(--lh-body)',
                          borderBottom: '1px solid var(--line)',
                          background: playing ? 'var(--brand-soft)' : 'transparent',
                          color: playing ? 'var(--brand)' : 'var(--ink-2)',
                          opacity: sec.passed && !playing ? 0.62 : 1,
                        }}
                      >
                        <span className="num" style={{ flexShrink: 0, color: 'var(--ink-3)' }}>
                          {hms(sec.startSec)}
                        </span>
                        <span style={{ flex: 1, minWidth: 0 }}>{sec.title}</span>
                      </button>
                    </li>
                    );
                  })}
                </ol>
                )}
                </React.Fragment>
              );
            })}
          </div>
        ))}
      </div>
    </Card>
  );
}
