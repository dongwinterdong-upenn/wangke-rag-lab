/**
 * 懂妈常驻挂件：学员端每一屏的右下角。
 *
 * ## 它不是第二条答疑通道
 *
 * 挂件里发出去的每一句，走的都是 `/api/qa/ask` 与 `/api/qa/{id}/reply`，
 * 落的是同一张 qa_ticket / qa_message。这一条是刻意的，因为另一种做法
 * ——"挂件里随便聊聊，正式提问才建工单"——会同时废掉三样东西：
 *
 *   1. **转人工**。懂妈答不上来时，后端把工单推进导师队列。脱离工单的闲聊
 *      没有这个出口，学员问完一句得不到答案，也不知道还能找谁。
 *   2. **留痕**。刷新一下就没了的对话，学员第二天想回看时是空的。
 *   3. **配额**。qa_quota_usage 是 AI 答疑唯一的那道闸（课程内容可以靠不断提问
 *      一段段被问出来）。绕开工单就是绕开闸。
 *
 * 所以挂件是「答疑页的一个更近的入口」，不是一个新东西。它和 /qa 两处
 * 看到的必须是同一份真相 —— 见下面那个很小的事件总线。
 *
 * ## 位置：为什么不是简单的 bottom: 20px
 *
 * 学员端窄屏有一条 fixed 的底部标签栏（64px + 安全区）。按 20px 摆，
 * 按钮正好压在"我的/答疑"那两格上：点标签栏点到懂妈，点懂妈点到标签栏。
 * 所以窄屏要让开那一整条，断点跟 shell.tsx 里那个 860px 严格对齐 ——
 * 两处写不同的数，就会有一段宽度是两边都不管的。
 *
 * ## 形象：一个立在右下角的人，而不是一颗图标
 *
 * 底图是一张【抠过底的透明 PNG】，不是原图。原图是白底的，直接贴上来只要
 * 压到任何一块深色上就露馅 —— 最近的一块是播放器那个盒子（`.surface-cinema`），
 * 白底会在它上面变成一个白方块。
 *
 * <p>两件跟着形象走的事：
 * <ul>
 *   <li>阴影用 {@code filter: drop-shadow}，不用 {@code box-shadow} ——
 *       后者画的是元素的<b>矩形</b>边框，人物周围会浮出一个方框。
 *   <li>停用态（考试中）把人物一起灰掉。只灰胶囊、人物照常鲜亮的话，
 *       画面上最显眼的东西看起来仍然是可点的，而它点不动。
 * </ul>
 *
 * <p>图上那圈发丝之间的白缝是画本身就有的（不是抠图残留），在 84~112px 的
 * 实际尺寸下看不出来 —— 放大到原始 1024px 才会显形。别为它再去修图。
 */
import React from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { api, Button, IconClose, Markdown, Spinner } from '@wangke/shared';
import type { MyCourse, QaDetail, QaMessage } from './types';
import dongmaFigure from './assets/dongma.png';
import dongmaFace from './assets/dongma-face.png';

/* ================================================================ 跨页同步 */

const qaSubs = new Set<() => void>();

/**
 * 「有人写了答疑」。挂件与 /qa、/qa/:id 三处看的是同一批工单，
 * 谁写完谁喊一声，其余几处跟着重取。
 *
 * <b>只在用户真的写了东西之后喊</b>，重取本身绝不喊 —— 否则 A 刷新触发 B 刷新、
 * B 刷新又触发 A，两个组件会互相喂着转下去，而页面上什么都看不出来。
 */
export function emitQaChanged() { qaSubs.forEach((f) => f()); }

export function onQaChanged(fn: () => void) {
  qaSubs.add(fn);
  return () => { qaSubs.delete(fn); };
}

/* ================================================================ 停用闸 */

let suspendReason: string | null = null;
const suspendSubs = new Set<() => void>();
const emitSuspend = () => suspendSubs.forEach((f) => f());

/**
 * 把懂妈按下去，并说明为什么。
 *
 * <p>眼下唯一的调用方是<b>正在作答的考卷</b>：那一屏上摆一个随时能问的答疑助手，
 * 等于把结业测评变成开卷 —— 而这门课的证书正是靠这场测评发的。
 *
 * <p><b>这不是一道真闸，只是把邀请撤掉。</b>学员另开一个标签页照样能用 /qa，
 * 那个洞在挂件之前就存在。真闸只能在后端上（开考期间拒绝 /api/qa/ask），
 * 那会改变现有产品行为，得先拍板。这里做的是：不在考卷上主动递给他。
 *
 * <p>按钮<b>仍然在原位</b>，只是变灰并写明原因，而不是整个消失 ——
 * 一个别的页面都有、单单这一页没有的按钮，学员的第一反应是"这页坏了"。
 */
export function useDongmaSuspend(reason: string) {
  React.useEffect(() => {
    suspendReason = reason;
    emitSuspend();
    return () => {
      // 只清自己挂上的那条：将来若有第二处也要停用，后进的不该被先退的抹掉
      if (suspendReason === reason) { suspendReason = null; emitSuspend(); }
    };
  }, [reason]);
}

function useSuspended(): string | null {
  return React.useSyncExternalStore(
    (fn) => { suspendSubs.add(fn); return () => { suspendSubs.delete(fn); }; },
    () => suspendReason,
    () => null,
  );
}

/* ================================================================ 上下文 */

type Ctx = { courseId: number | null; lessonId: number | null };

const numOr = (v: string | null): number | null => {
  const n = Number(v);
  return Number.isFinite(n) && n > 0 ? n : null;
};

/**
 * 从当前地址推出「他正在看什么」。
 *
 * <p>带上讲次不是锦上添花：后端拿它约束答案出处的范围，导师队列里也能直接定位。
 * 但 lesson 必须和 course 配套 —— 后端会校验讲次属不属于这门课，
 * 配不上时回的是 400「所选讲次不属于这门课程」，学员看到的是一句莫名其妙的报错。
 * 所以这里两个一起出，调用方也必须一起用（见 askLesson）。
 */
function routeContext(pathname: string, search: string): Ctx {
  const sp = new URLSearchParams(search);
  const learn = pathname.match(/\/learn\/(\d+)/);
  if (learn) return { courseId: numOr(sp.get('course')), lessonId: Number(learn[1]) };
  const course = pathname.match(/\/course\/(\d+)/);
  if (course) return { courseId: Number(course[1]), lessonId: null };
  // /qa?course=&lesson= 这种带参进来的
  return { courseId: numOr(sp.get('course')), lessonId: numOr(sp.get('lesson')) };
}

/** 工单标题。挂件里没有标题这一栏，从正文首行截 —— 而不是编一个「来自懂妈的提问」。 */
function titleOf(text: string): string {
  const one = text.replace(/\s+/g, ' ').trim();
  return one.length <= 40 ? one : one.slice(0, 39) + '…';
}

/**
 * 懂妈的脸。头肩特写，跟立绘是同一张画裁出来的。
 *
 * <p>单独出一张小图而不是把立绘缩到 22px：立绘缩到那个尺寸，脸只占其中
 * 不到三分之一，等于一团糊。它只有 8KB。
 */
export function DongmaFace({ size = 22 }: { size?: number }) {
  return (
    <img
      className="wk-dm-face" src={dongmaFace} alt="" aria-hidden="true"
      width={size} height={size} draggable={false}
      // ★ 样式写在这里，不写进 DOCK_CSS。那一份是挂件自己 <style> 出来的，
      // 而这个零件也在 /qa/:id 上用 —— 靠别的组件挂着才有样式的东西，
      // 等于给它加了一条看不见的依赖：哪天挂件在某一页不渲染（比如考试页真把它摘掉），
      // 这里的头像会变成一个方的、没有描边的小图，而不会有任何报错。
      style={{
        width: size, height: size, flex: 'none',
        borderRadius: '50%', objectFit: 'cover',
        background: 'var(--surface-2)', boxShadow: '0 0 0 1px var(--line)',
        userSelect: 'none',
      }}
    />
  );
}

/* ================================================================ 挂件 */

const BOOT_IDLE = 0, BOOT_LOADING = 1, BOOT_READY = 2, BOOT_FAIL = 3;

export function DongmaDock() {
  const loc = useLocation();
  const nav = useNavigate();
  const suspended = useSuspended();

  const [open, setOpen] = React.useState(false);
  const [boot, setBoot] = React.useState(BOOT_IDLE);
  const [bootErr, setBootErr] = React.useState('');
  const [courses, setCourses] = React.useState<MyCourse[] | null>(null);
  const [ticket, setTicket] = React.useState<QaDetail | null>(null);
  const [picked, setPicked] = React.useState<number | null>(null);
  const [draft, setDraft] = React.useState('');
  const [pending, setPending] = React.useState<string | null>(null);
  // 正在长出来的那段答复。它是【临时的】：流一结束就清空，改由取回来的工单说了算。
  // 不清的话屏幕上会同时有两份（临时的一份 + 库里的一份），而且它们会慢慢分叉。
  const [streamed, setStreamed] = React.useState('');
  const [quota, setQuota] = React.useState<number | null>(null);
  const [err, setErr] = React.useState('');

  const ctx = routeContext(loc.pathname, loc.search);
  const live = React.useMemo(() => (courses ?? []).filter((c) => !c.expired), [courses]);

  // 地址里的课程只有在他真的持有、且还没到期时才算数。不核对的话，
  // 从一条旧链接进来会直接撞后端的 403「你还没有这门课的有效权益」，
  // 而他在挂件里根本没做过任何选择。
  const ctxCourse = ctx.courseId != null && live.some((c) => c.courseId === ctx.courseId)
    ? ctx.courseId : null;
  const askCourse = picked ?? ctxCourse ?? (live.length === 1 ? live[0].courseId : null);
  // 讲次只跟着【地址里那门课】走。他手动改选了别的课，讲次必须一起丢掉。
  const askLesson = askCourse != null && askCourse === ctxCourse ? ctx.lessonId : null;
  const active = ticket && ticket.status !== 'CLOSED' ? ticket : null;

  // 换页时人工选的课作废，页面上下文重新说了算；同一页里他改了就以他为准。
  React.useEffect(() => { setPicked(null); }, [ctxCourse]);

  // 考试一开始就把已经展开的面板收掉：只把按钮变灰而任由面板开着，等于闸没关。
  React.useEffect(() => { if (suspended) setOpen(false); }, [suspended]);

  const load = React.useCallback(async () => {
    setBoot(BOOT_LOADING);
    setBootErr('');
    try {
      const [cs, ts] = await Promise.all([
        api.get<{ courses: MyCourse[] }>('/api/learn/my/courses').then((r) => r.courses),
        api.get<{ tickets: { id: number; status: string }[] }>('/api/qa/my?limit=20')
           .then((r) => r.tickets),
      ]);
      setCourses(cs);
      // 接着上次那条没结束的聊。全都结束了就摆一张白纸 ——
      // 把 CLOSED 的翻出来只会让他在一条发不出去的工单里打字
      const last = ts.find((t) => t.status !== 'CLOSED');
      setTicket(last ? await api.get<{ ticket: QaDetail }>(`/api/qa/${last.id}`)
                              .then((r) => r.ticket) : null);
      setBoot(BOOT_READY);
    } catch (e) {
      setBoot(BOOT_FAIL);
      setBootErr((e as Error).message || '没加载出来');
    }
  }, []);

  React.useEffect(() => {
    if (!open || boot === BOOT_LOADING) return;
    // 课程列表空着时不认这份缓存：他很可能刚去激活了一门课回来
    if (boot === BOOT_IDLE || boot === BOOT_FAIL || live.length === 0) void load();
  }, [open]);   // eslint-disable-line react-hooks/exhaustive-deps

  // 别处（/qa 那两页）写了东西，这里跟着重取。挂件开着才取：关着的时候
  // 取回来也没人看，白白占一次请求。
  React.useEffect(() => onQaChanged(() => { if (open) void load(); }), [open, load]);

  const listRef = React.useRef<HTMLDivElement>(null);
  React.useEffect(() => {
    const el = listRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [ticket, pending, streamed, open]);

  const inputRef = React.useRef<HTMLTextAreaElement>(null);
  React.useEffect(() => {
    if (!open || boot !== BOOT_READY) return;
    // 光标只在宽屏自动落进输入框。窄屏上聚焦会立刻顶起软键盘，
    // 把刚展开的对话挤掉大半 —— 他还没看清上次聊到哪儿，屏幕已经只剩输入框了。
    if (window.matchMedia('(min-width: 861px)').matches) inputRef.current?.focus();
  }, [open, boot]);

  // Esc 收起。挂件不是模态的（宽屏下背后的页面照常能用），所以只收自己，
  // 不做焦点圈禁 —— 那会把 Tab 锁死在一个本来就不该独占屏幕的小面板里。
  React.useEffect(() => {
    if (!open) return;
    const h = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false); };
    window.addEventListener('keydown', h);
    return () => window.removeEventListener('keydown', h);
  }, [open]);

  const send = async () => {
    const text = draft.trim();
    // 门槛只有「不能是空的」这一条 —— 空串会被后端以 400「问题正文不能为空」打回来。
    // 原来卡的是 4 个字，那是我自己拍的一个数：「为啥」「L3 呢」「怎么做」都发不出去，
    // 而这些恰恰是追问最自然的说法。后端从来只要求非空，前端凭空多一道闸，
    // 表现是发送按钮灰着不动、也不说为什么 —— 用户只能猜。
    if (!text || pending) return;
    if (!active && askCourse == null) {
      setErr('先选一门课 —— 懂妈答的是课程内容，得知道你问的是哪一门');
      return;
    }
    setDraft('');
    setPending(text);
    setStreamed('');
    setErr('');

    // 收尾帧里的东西。放在闭包变量里而不是 state：state 的更新是异步的，
    // 而流一结束就要立刻拿它们做判断，读到的会是上一轮的值。
    let id = active ? active.id : 0;
    let ok = false;
    let cutOff = false;
    let frameErr = '';

    try {
      await api.stream(
        active ? `/api/qa/${active.id}/reply/stream` : '/api/qa/ask/stream',
        active
          ? { content: text }
          : { courseId: askCourse, lessonId: askLesson, title: titleOf(text),
              content: text, wantHuman: false },
        (f) => {
          if (typeof f.delta === 'string') { setStreamed((s) => s + f.delta); return; }
          if (f.error) { frameErr = String(f.error); return; }
          if (f.ticketId) id = Number(f.ticketId);
          if (f.quotaLeftToday != null) setQuota(Number(f.quotaLeftToday));
          if (f.done) ok = true;
          if (f.aborted) cutOff = true;
        },
      );
    } catch (e) {
      // 连流都没建起来（网络断、鉴权挂、后端 500）—— 这一句根本没进库，
      // 必须把它还回输入框。留在气泡里看起来像发出去了，他会等一个永远不来的答复。
      setPending(null);
      setStreamed('');
      setDraft(text);
      setErr((e as Error).message || '没发出去，再试一次');
      return;
    }

    if (frameErr) {
      // 流开起来之后才被拒（配额用尽最常见）。此时后端<b>什么都没写</b>，
      // 所以和上面一样，把话还给他。
      setPending(null);
      setStreamed('');
      setDraft(text);
      setErr(frameErr);
      return;
    }

    // ★ 这一段字已经在他眼前长出来过了，但服务端一个字都没落库（懂妈没说完）。
    //   必须把它撤掉 —— 留着最坏：半句话读起来和一整句一样通顺，而缺的
    //   往往正是后半段的注意事项，他会照着做。撤掉之后屏幕与库里是同一份真相。
    if (cutOff) setErr('这条懂妈没答完，上面那段不作数，已经转给老师了');

    // ★ 发送与「取回对话」是两件事，不能共用一个 catch：
    //   取回失败时那句话已经在库里了，这时候把草稿还给他，他会再发一遍 ——
    //   一条重复的提问，还多扣一次配额。
    try {
      if (id) setTicket(await api.get<{ ticket: QaDetail }>(`/api/qa/${id}`).then((r) => r.ticket));
    } catch {
      setErr('已经发出去了，但这边没取回来。关掉重开，或者去「答疑」页看');
    } finally {
      // 撤掉临时那两条，改由刚取回来的工单说了算 —— 屏幕上留下的就是库里有的
      setPending(null);
      setStreamed('');
      void ok;
      emitQaChanged();
    }
  };

  const onKey = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    // isComposing 不能省：中文输入法选词时按回车是「确认候选词」，
    // 不判这一下的话，打到一半的半句话会被直接发出去。
    if (e.key !== 'Enter' || e.shiftKey || (e.nativeEvent as any).isComposing) return;
    if (window.matchMedia('(max-width: 860px)').matches) return;   // 窄屏回车换行
    e.preventDefault();
    void send();
  };

  const go = (to: string) => { setOpen(false); nav(to); };

  return (
    <>
      <style>{DOCK_CSS}</style>

      {!open && (
        <button
          type="button"
          className="wk-dm-launcher"
          disabled={!!suspended}
          title={suspended ?? '问懂妈'}
          aria-label={suspended ? `懂妈暂停：${suspended}` : '打开懂妈答疑'}
          onClick={() => setOpen(true)}
        >
          {/* alt 留空 + aria-hidden：可读的名字已经在外面那颗按钮的 aria-label 上，
              这里再报一次，读屏会把同一个东西念两遍。 */}
          <img className="wk-dm-figure" src={dongmaFigure} alt="" aria-hidden="true"
               width={158} height={384} draggable={false} />
          <span className="wk-dm-pill">{suspended ? '懂妈 · 考试中' : '问懂妈'}</span>
        </button>
      )}

      {open && (
        <div className="wk-dm-panel" role="dialog" aria-label="懂妈答疑">
          <header className="wk-dm-head">
            <DongmaFace size={34} />
            <div style={{ minWidth: 0, flex: 1 }}>
              <div style={{ fontWeight: 600, color: 'var(--ink-0)' }}>懂妈</div>
              <div style={{ fontSize: 'var(--t-xs)', color: 'var(--ink-3)' }}>
                {active ? '接着上次那条聊' : '课程里不懂的，问一句就行'}
              </div>
            </div>
            <div style={{ display: 'flex', gap: 'var(--s2)', alignItems: 'center' }}>
              {active && (
                <button type="button" className="wk-dm-mini"
                        onClick={() => { setTicket(null); setErr(''); }}>新问题</button>
              )}
              <button type="button" className="wk-dm-mini" onClick={() => go('/qa')}>全部</button>
              <button type="button" className="wk-dm-mini" aria-label="收起"
                      onClick={() => setOpen(false)}><IconClose size={15} /></button>
            </div>
          </header>

          <div className="wk-dm-body" ref={listRef}>
            {boot === BOOT_LOADING && <Muted><Spinner size={13} /> 正在打开…</Muted>}

            {boot === BOOT_FAIL && (
              <Muted>
                {bootErr}
                <div style={{ marginTop: 'var(--s3)' }}>
                  <Button size="sm" variant="ghost" onClick={() => void load()}>重试</Button>
                </div>
              </Muted>
            )}

            {boot === BOOT_READY && live.length === 0 && (
              <Muted>
                答疑是跟着课程走的，你现在还没有生效中的课程。
                <div style={{ marginTop: 'var(--s3)' }}>
                  <Button size="sm" onClick={() => go('/activate')}>输入激活码</Button>
                </div>
              </Muted>
            )}

            {boot === BOOT_READY && live.length > 0 && !ticket && !pending && (
              <Muted>
                {askLesson
                  ? '你正在看的这一讲会一起带过去，直接问就行。'
                  : '把不明白的地方说清楚一点，答复会更准。'}
              </Muted>
            )}

            {ticket?.messages.map((m, i) => <Bubble key={i} m={m} />)}

            {pending && (
              <>
                <Bubble m={{ senderType: 'STUDENT', content: pending,
                             citations: null, createdAt: '' }} />
                <div className="wk-dm-row">
                  <div className="wk-dm-bubble wk-dm-them wk-dm-live">
                    <div className="wk-dm-who"><DongmaFace />懂妈</div>
                    {streamed
                      ? (
                        <>
                          <Markdown text={streamed} />
                          {/* 光标：只在还没收尾时闪。它是这一屏唯一说明
                              「还没说完，别急着照着做」的东西 —— 没有它，
                              一段刚长到一半的答复看起来和一段说完了的一模一样。 */}
                          <span className="wk-dm-caret" aria-hidden />
                        </>
                      )
                      : <span style={{ color: 'var(--ink-3)' }}><Spinner size={12} /> 懂妈正在想…</span>}
                  </div>
                </div>
              </>
            )}

            {ticket?.status === 'ESCALATED' && !pending && (
              <Muted>
                这条懂妈答不了，已经转给老师了。回复会出现在这里，不用一直等着。
              </Muted>
            )}

            {/* 工单在他打开挂件之后被老师关掉了。不说这一句的话，他对着一段
                看起来还能接着聊的对话打字，发出去的却是一条【新工单】——
                而屏幕上没有任何东西提示话题换了。 */}
            {ticket && !active && !pending && (
              <Muted>这条答疑已经结束了。在下面接着说，会另开一条新的。</Muted>
            )}
          </div>

          {boot === BOOT_READY && live.length > 0 && (
            <footer className="wk-dm-foot">
              {/* 选课只在【新问题】时出现：已经有工单了，课程是它建单时定死的，
                  这时候还摆一个能改的选择器，等于给一个改了不起作用的开关。 */}
              {!active && live.length > 1 && (
                <select className="wk-dm-pick" value={askCourse ?? ''}
                        onChange={(e) => setPicked(Number(e.target.value))}>
                  <option value="" disabled>选一门课</option>
                  {live.map((c) => (
                    <option key={c.courseId} value={c.courseId}>{c.title}</option>
                  ))}
                </select>
              )}

              {err && <div className="wk-dm-err">{err}</div>}

              <div style={{ display: 'flex', gap: 'var(--s2)', alignItems: 'flex-end' }}>
                <textarea
                  ref={inputRef}
                  className="wk-dm-input" rows={2} value={draft}
                  onChange={(e) => setDraft(e.target.value.slice(0, 1000))}
                  onKeyDown={onKey}
                  placeholder={active ? '接着问…' : '想问什么？'}
                  disabled={!!pending}
                />
                <Button size="sm" onClick={() => void send()}
                        loading={!!pending} disabled={!draft.trim()}>发送</Button>
              </div>

              <div className="wk-dm-meta">
                {quota != null ? `今天还可以问 ${quota} 次` : '答复由懂妈先给，答不上来会转给老师'}
              </div>
            </footer>
          )}
        </div>
      )}
    </>
  );
}

/* ---------------------------------------------------------------- 零件 */

function Muted({ children }: { children: React.ReactNode }) {
  return <div className="wk-dm-muted">{children}</div>;
}

function Bubble({ m }: { m: QaMessage }) {
  const mine = m.senderType === 'STUDENT';
  return (
    <div className="wk-dm-row" style={{ justifyContent: mine ? 'flex-end' : 'flex-start' }}>
      <div className={`wk-dm-bubble ${mine ? 'wk-dm-me' : 'wk-dm-them'}`}
           style={mine ? { whiteSpace: 'pre-wrap' } : undefined}>
        {/* 署名只给对面那一侧：自己说的话不用告诉自己是谁说的。
            AI 与 MENTOR 分「懂妈 / 老师」两档 —— 这两个的分量对学员不一样。 */}
        {!mine && (
          <div className="wk-dm-who">
            {m.senderType === 'MENTOR' ? '老师' : <><DongmaFace />懂妈</>}
          </div>
        )}
        {/* 只给对面那一侧渲染 Markdown。学员自己打的 `**` 就是两个星号 ——
            替他"渲染"等于替他改写了他说过的话，而他一眼就能看出屏幕上的
            不是自己写的东西。 */}
        {mine ? m.content : <Markdown text={m.content} />}
      </div>
    </div>
  );
}

/* ---------------------------------------------------------------- 版面 */

const DOCK_CSS = `
/* 立在右下角的懂妈：上面是人，下面压一颗红胶囊当名牌。
   ⚠️ 记一句：DS 原来的规矩是「每屏只留一个红锚点」，而这颗是常驻的，
   在首页会和「开始学习」那颗红按钮同屏争一次注意力。这是用户明确要的取舍。 */
.wk-dm-launcher {
  position: fixed; right: 12px; z-index: var(--z-drawer);
  /* 让开那条 fixed 底栏（64px + 安全区），断点与 shell.tsx 严格一致 */
  bottom: calc(64px + env(safe-area-inset-bottom, 0px) + 12px);
  display: flex; flex-direction: column; align-items: center;
  padding: 0; border: 0; background: none; font: inherit; cursor: pointer;
  /* 人物是抠过底的，指针落在透明区域上没有意义 —— 但整颗按钮仍然要能点，
     所以只把 img 设成 pointer-events:none，不动 button 本身 */
}
.wk-dm-figure {
  height: 84px; width: auto; display: block;
  /* ★ 用 drop-shadow 不用 box-shadow：后者画的是元素的【矩形】边框，
     人物周围会浮出一个方框；drop-shadow 跟着 alpha 轮廓走。
     叠两层：先一圈很淡的浅色描边，再一层落影。
     浅色那层在白底上等于看不见，但压到播放器那个深色盒子上时，
     它是唯一能把深色开衫和深色底分开的东西 —— 没有它，压上去只剩一张脸浮着。 */
  filter: drop-shadow(0 0 3px rgba(255, 255, 255, .55))
          drop-shadow(0 5px 8px rgba(0, 0, 0, .22));
  pointer-events: none; -webkit-user-drag: none; user-select: none;
  transition: transform var(--dur) var(--ease);
}
.wk-dm-launcher:hover .wk-dm-figure { transform: translateY(-3px); }
/* 名牌。红底白字，配色直接借 .wk-btn-primary 那两个变量（--brand / --da-bg），
   不另写一组色值 —— 另写一组，将来品牌红调一次就会漏掉这一颗，
   于是全站唯一一个"红得不一样"的东西，还查不出是谁定的。
   往上压 6px 盖住鞋跟，人像是站在名牌上而不是浮在它上面。 */
.wk-dm-pill {
  margin-top: -6px;
  display: inline-flex; align-items: center;
  min-height: 28px; padding: 4px 12px;
  font-size: var(--t-xs); font-weight: 500; line-height: 1.2;
  color: var(--da-bg); background: var(--brand);
  border: 1px solid var(--brand); border-radius: 999px;
  box-shadow: var(--shadow-red); white-space: nowrap;
  transition: background var(--dur) var(--ease), border-color var(--dur) var(--ease);
}
.wk-dm-launcher:hover .wk-dm-pill { background: var(--brand-press); border-color: var(--brand-press); }
/* 停用态必须【彻底不红，而且人也要一起灰】。只灰名牌、人物照常鲜亮的话，
   这一屏上最显眼的东西看起来仍然最该点，而它点不动 ——
   用户的结论是"这页坏了"，不是"这里被停用了"。 */
.wk-dm-launcher:disabled { cursor: not-allowed; }
.wk-dm-launcher:disabled .wk-dm-figure { filter: grayscale(1) opacity(.45); }
.wk-dm-launcher:disabled .wk-dm-pill {
  color: var(--ink-4); background: var(--surface-2);
  border-color: var(--line-strong); box-shadow: none;
}
.wk-dm-launcher:disabled:hover .wk-dm-figure { transform: none; }

/* 头肩特写的样式在 DongmaFace 组件里内联给（那个零件在 /qa/:id 上也用，
   不能依赖这份挂件才注入的 CSS）。这里只补一条 CSS 才写得了的： */
.wk-dm-face { -webkit-user-drag: none; }

.wk-dm-panel {
  position: fixed; inset: 0; z-index: var(--z-drawer);
  display: flex; flex-direction: column;
  background: var(--surface); animation: wk-rise .2s var(--ease);
}
.wk-dm-head {
  flex: none; display: flex; align-items: center; justify-content: space-between;
  gap: var(--s3); padding: var(--s3) var(--s4);
  border-bottom: 1px solid var(--line); background: var(--surface);
}
.wk-dm-mini {
  font: inherit; font-size: var(--t-xs); color: var(--ink-2);
  background: none; border: 1px solid var(--line-strong); border-radius: var(--r-md);
  min-height: 30px; padding: 4px 9px; cursor: pointer;
  display: inline-flex; align-items: center; gap: 4px;
}
.wk-dm-mini:hover { color: var(--brand); border-color: var(--brand-line); }

.wk-dm-body {
  flex: 1; min-height: 0; overflow-y: auto; -webkit-overflow-scrolling: touch;
  /* 滚到顶/底之后不要把滚动接力给后面的页面：不写这条的话，在手机上翻对话
     翻到头再划一下，背后的课程列表会跟着走，关掉挂件才发现页面已经不在原处 */
  overscroll-behavior: contain;
  padding: var(--s4); display: grid; gap: var(--s3); align-content: start;
}
.wk-dm-row { display: flex; }
/* ★ white-space: pre-wrap 只留给学员自己那一侧（在 Bubble 里内联给）。
   懂妈那一侧走 Markdown，块与块之间本来就有真的换行符 ——
   容器再 pre-wrap 一遍，每两段之间会多出一到两个空行，
   一条八段的回答能被撑长小半屏，而看上去像是排版坏了。 */
.wk-dm-bubble {
  max-width: 86%; padding: 9px 12px; border-radius: var(--r-lg);
  font-size: var(--t-sm); line-height: var(--lh-body);
  overflow-wrap: anywhere;
}
.wk-dm-me   { background: var(--brand-soft); border: 1px solid var(--brand-line); }
.wk-dm-them { background: var(--surface-2); border: 1px solid var(--line); }
/* 署名行。带头像那一档要 flex —— 不然 img 按 inline 排，基线对不齐，
   一行字会被顶高两三个像素，而同一屏里「老师」那一档没有头像、高度不变，
   两种气泡看起来就像用了两个字号。 */
.wk-dm-who  {
  display: flex; align-items: center; gap: 5px;
  font-size: var(--t-xs); color: var(--ink-3); margin-bottom: 3px;
}
/* 还在往外冒字的光标。跟在正文后面，所以要 inline-block + 对齐到基线 */
.wk-dm-caret {
  display: inline-block; width: 2px; height: 1em; vertical-align: -0.15em;
  margin-left: 2px; background: var(--brand); animation: wk-dm-blink 1s steps(2) infinite;
}
@keyframes wk-dm-blink { 50% { opacity: 0; } }
/* 光标要接在最后一段的末尾，否则它会被挤到下一行单独占一行。
   ★ 只作用于【正在长的那一条】（.wk-dm-live），且只改段落 ——
   对已经答完的气泡动这个，或者把 ul/pre 也改成 inline，会把版式弄坏，
   而那是每一条历史消息都会中招的范围。 */
.wk-dm-live .wk-md > p:last-child { display: inline; }
.wk-dm-muted {
  font-size: var(--t-xs); color: var(--ink-3); line-height: var(--lh-body);
  background: var(--surface-2); border-left: 2px solid var(--line-strong);
  padding: var(--s3) var(--s4);
}

.wk-dm-foot {
  flex: none; display: grid; gap: var(--s2);
  padding: var(--s3) var(--s4);
  padding-bottom: calc(var(--s3) + env(safe-area-inset-bottom, 0px));
  border-top: 1px solid var(--line); background: var(--surface);
}
.wk-dm-input {
  flex: 1; min-width: 0; resize: none;
  padding: 8px 10px; font: inherit; font-size: var(--t-sm);
  line-height: var(--lh-body); color: var(--ink);
  background: var(--surface); border: 1px solid var(--line-strong);
  border-radius: var(--r-md); outline: none;
}
.wk-dm-input:focus { border-color: var(--brand); }
.wk-dm-pick {
  font: inherit; font-size: var(--t-xs); color: var(--ink-2);
  padding: 6px 8px; background: var(--surface);
  border: 1px solid var(--line-strong); border-radius: var(--r-md);
}
.wk-dm-err  { font-size: var(--t-xs); color: var(--danger); line-height: var(--lh-body); }
.wk-dm-meta { font-size: var(--t-xs); color: var(--ink-3); }

@media (min-width: 861px) {
  /* 宽屏没有底栏，贴右下角摆一块定宽面板；高度封顶，短窗口下不至于顶穿顶栏 */
  .wk-dm-launcher { right: 20px; bottom: 20px; }
  /* 宽屏上人物放大到 112px：窄屏那个 84px 是为了不吃掉手机上本来就少的屏，
     换到桌面就显得像一枚贴纸。 */
  .wk-dm-figure { height: 112px; }
  .wk-dm-pill { min-height: 30px; font-size: var(--t-sm); }
  .wk-dm-panel {
    inset: auto 20px 20px auto;
    width: 380px; height: min(600px, calc(100vh - 96px));
    border: 1px solid var(--line-strong); border-top: 2px solid var(--brand);
    border-radius: var(--r-md); box-shadow: var(--shadow-3);
    animation: wk-pop .16s var(--ease);
  }
}
`;
