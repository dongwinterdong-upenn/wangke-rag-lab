/**
 * API 客户端。
 *
 * 这一层要挡住三件在前端最容易做错、且做错了很难查的事：
 *
 * 1. **登录失效没有统一出口。** 如果每个页面各自处理 401，迟早有页面漏掉，
 *    表现是"点了没反应"——不跳登录、不报错、控制台一行红字。用户只会觉得网站坏了。
 * 2. **错误信息被吃掉。** 后端的拒绝理由是精心写过的人话
 *    （"该激活码已被其他账号激活。一码一人，不可转移"），前端一律显示"请求失败"
 *    等于把这些工作全扔了，客服还得一个个去问。
 * 3. **重复提交。** 用户在慢网络下会连点。写操作必须在客户端也去一次重
 *    ——服务端有幂等键兜底，但那是防御的最后一层，不该拿它当日常。
 */

export type Json = Record<string, any>;

const LEGACY_TOKEN_KEY = 'wangke.token';

/**
 * 三个前端部署在同一个域名下，localStorage 也因此是共享的。令牌必须按入口隔离，
 * 否则在代理端登录会覆盖学生端，在管理端登录又会覆盖代理端。
 */
function tokenKey() {
  const path = window.location.pathname;
  if (path === '/admin' || path.startsWith('/admin/')) return 'wangke.admin.token';
  if (path === '/agent' || path.startsWith('/agent/')) return 'wangke.agent.token';
  return 'wangke.student.token';
}

function readToken() {
  const key = tokenKey();
  const current = localStorage.getItem(key);
  if (current) return current;

  // 平滑接住升级前仍在使用的登录，但旧令牌只能被一个入口消费。
  // 如果不立刻删除，同时打开的三个入口会各复制一份同一身份，又回到“串号”。
  const legacy = localStorage.getItem(LEGACY_TOKEN_KEY);
  if (legacy) {
    localStorage.setItem(key, legacy);
    localStorage.removeItem(LEGACY_TOKEN_KEY);
  }
  return legacy;
}
const listeners = new Set<(reason: string) => void>();

export function onUnauthorized(fn: (reason: string) => void) {
  listeners.add(fn);
  // 花括号不能省：Set.delete 返回 boolean，而 useEffect 的清理函数必须返回 void。
  // 直接 return listeners.delete(fn) 在运行期无害，但任何开 strict 的 typecheck 都会红。
  return () => { listeners.delete(fn); };
}

/**
 * 「这个号必须先改密码」的统一出口。
 *
 * <p>后端在 must_change_pwd=1 时，对 /api/account/** 以外的每一个接口都回 403 并带
 * `reason: 'MUST_CHANGE_PASSWORD'`。没有这个出口的话，用户看到的是满屏
 * 「请先修改初始密码，再使用其他功能」的报错块，而<b>没有任何地方能改密码</b> ——
 * 一句正确的提示配一条走不通的路，比不提示更让人恼火。
 *
 * <p>判据用 reason 不用 message：message 是给人看的、随时会被改文案，
 * 靠它做分支的前端会在某次纯文案改动之后静默失效。
 */
const MUST_CHANGE_PWD = 'MUST_CHANGE_PASSWORD';
const pwdListeners = new Set<(reason: string) => void>();

export function onMustChangePassword(fn: (reason: string) => void) {
  pwdListeners.add(fn);
  return () => { pwdListeners.delete(fn); };
}

/**
 * 令牌。
 *
 * ## 为什么它必须能被订阅
 *
 * 原来这就是三个裸的 localStorage 读写。问题出在「令牌变了、但**没有发生导航**」
 * 这条路径上：401 会在这里 `auth.clear()`，而挡板组件如果只是把令牌快照进 state、
 * 靠路径变化去重读，它永远收不到通知 —— 令牌已经删了，人还停在原地。
 *
 * 代理端就是这么卡死的：`Guard` 用 `useState(() => auth.token)` 存了一份，
 * 只在 `loc.pathname` 变时重读。令牌过期后刷新页面 → Guard 读到旧真值 → 渲染 Shell
 * → 首个 `/api/agent/me` 得到 401 → 这里把令牌删掉、通知 onUnauthorized ——
 * 而那一刻**一个监听者都没有**（唯一的监听者在 AppShell 里，AppShell 要等
 * me() 成功之后才挂载）。结果是一屏光秃秃的「登录状态已失效」加一个重试按钮：
 * 没有顶栏、没有导航、没有退出、**没有任何通往登录页的入口**，点重试永远回到同一屏。
 * 用户唯一的出路是自己改地址栏。控制台零红字。
 *
 * 所以令牌本身要会发通知，挡板用 useAuthToken 订阅它，而不是各自存一份快照。
 */
const tokenSubs = new Set<() => void>();
const emitToken = () => tokenSubs.forEach(fn => fn());

export const auth = {
  get token() { return readToken(); },
  set(token: string) {
    localStorage.setItem(tokenKey(), token);
    localStorage.removeItem(LEGACY_TOKEN_KEY);
    emitToken();
  },
  clear() {
    localStorage.removeItem(tokenKey());
    localStorage.removeItem(LEGACY_TOKEN_KEY);
    emitToken();
  },
  /** 返回退订函数。花括号不能省，理由同 onUnauthorized。 */
  subscribe(fn: () => void) {
    tokenSubs.add(fn);
    return () => { tokenSubs.delete(fn); };
  },
  /** 别的标签页退出登录时，这一页也要跟着走。 */
  notifyExternalChange: emitToken,
};

export class ApiError extends Error {
  constructor(public status: number, message: string, public payload?: Json) {
    super(message);
  }
  /** 后端在激活失败时会带 reason 枚举，客服要靠它分清是哪一种失败 */
  get reason(): string | undefined { return this.payload?.reason; }
}

const inFlight = new Map<string, Promise<any>>();

async function request<T = Json>(
  method: string, path: string, body?: Json, opts: { dedupe?: boolean } = {}
): Promise<T> {
  const key = `${method} ${path} ${body ? JSON.stringify(body) : ''}`;
  // 写操作默认去重：慢网络下用户会连点，而"多下一单"是用真钱买的教训
  const dedupe = opts.dedupe ?? method !== 'GET';
  if (dedupe && inFlight.has(key)) return inFlight.get(key)!;

  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  const t = auth.token;
  if (t) headers.Authorization = `Bearer ${t}`;

  const p = (async () => {
    let resp: Response;
    try {
      resp = await fetch(path, {
        method, headers,
        body: body === undefined ? undefined : JSON.stringify(body),
      });
    } catch {
      // 网络层失败要和业务失败分开说。用户在地铁里断网时，
      // 一句"系统繁忙"会让他以为是平台坏了，然后去投诉。
      throw new ApiError(0, '网络连接不可用，请检查网络后重试');
    }

    const text = await resp.text();
    let data: Json = {};
    if (text) { try { data = JSON.parse(text); } catch { data = { message: text }; } }

    if (resp.status === 401 || (resp.status === 403 && data.reason === MUST_CHANGE_PWD))
      throw authFailure(resp.status, data);
    if (!resp.ok) throw new ApiError(resp.status, data.message || `请求失败（${resp.status}）`, data);
    return data as T;
  })();

  if (dedupe) {
    inFlight.set(key, p);
    // ★ 不能写成 p.finally(...)：finally 会派生出一个【新的】promise，
    //   而调用方 await 的是 p，不是它。p 一 reject，那个派生出来的也 reject
    //   且没有任何人接手 —— 于是每一次失败的写请求都在控制台留一条
    //   Uncaught (in promise) ApiError，并触发 window 的 unhandledrejection。
    //   页面本身是正常的（该显示的人话都显示了），但控制台被这些噪声填满，
    //   真异常就淹在里面了。学员输一个被占用的激活码、后台保存讲次被闸门拒，
    //   都会来一条；api.patch 接进来之后后台那条路也开始贡献。
    //
    //   加一个 catch 把派生链闭合：清理照做，拒绝不再外溢。p 本身原样返回，
    //   调用方拿到的还是那个会 reject 的 promise。
    p.then(() => inFlight.delete(key), () => inFlight.delete(key));
  }
  return p;
}

/**
 * 鉴权失败的<b>唯一</b>处置点。
 *
 * <p>抽出来是因为流式那条路（{@link stream}）不走 {@link request}，
 * 而"再抄一份 401 处理"正是这个文件开头警告过的那种错：
 * 手抄的那份会绕开 auth.clear() 与 onUnauthorized，于是「令牌失效」
 * 和「界面回到登录页」不再是同一件事 —— 用户停在原地，点什么都没反应。
 */
function authFailure(status: number, data: Json): ApiError {
  if (status === 403) {
    // 强制改密走自己的出口。注意【不清令牌】：改密接口本身要带着它，
    // 清掉的话用户会被弹回登录页，用同一个初始密码登进来，再被同一堵墙挡住 —— 死循环。
    const reason = data.message || '请先修改初始密码';
    pwdListeners.forEach(fn => fn(reason));
    return new ApiError(403, reason, data);
  }
  // ★ 401 有两种意思，只有一种该清令牌。
  //
  //   「登录状态失效」→ 清，把人送回登录页。
  //   「你刚打的那串旧密码不对」→ **绝不能清**：改密接口用 401 表示凭据错，
  //     清掉之后用户第二次输对也只会拿到「请先登录」，永远改不成。
  //
  //   靠 reason 分辨，不靠 message：文案随时会改，改了这条分支就静默失效。
  if (data.reason === 'BAD_CREDENTIALS')
    return new ApiError(401, data.message || '凭据不正确', data);
  auth.clear();
  const reason = data.message || '登录已失效，请重新登录';
  listeners.forEach(fn => fn(reason));
  return new ApiError(401, reason, data);
}

/** SSE 的一帧，解析自 `data: {...}`。字段由各接口自己约定。 */
export type StreamFrame = Record<string, any>;

/**
 * 读一条 SSE 流（答疑的边答边送就走这条）。
 *
 * <h3>为什么不用 EventSource</h3>
 * 浏览器原生的 EventSource <b>发不了自定义请求头，也发不了 POST 体</b>，
 * 而这些接口既要 Bearer 令牌又要请求体。所以走 fetch + ReadableStream 自己拆帧。
 *
 * <h3>拆帧必须按 `\n\n` 切，不能按行切</h3>
 * 网络给的是<b>任意大小的字节块</b>，一帧可能被劈成两块到达，两帧也可能挤在一块里。
 * 按行读会把半个 JSON 交给 JSON.parse —— 表现是偶发的解析失败，
 * 而且答案越长越容易撞上（块越多，劈中的机会越多）。所以留一个 buf，
 * 只在看到完整的 `\n\n` 时才切出一帧。
 *
 * <p>鉴权失败与非 200 在<b>流开始之前</b>就处理掉，走 {@link authFailure} 那一处，
 * 与普通请求同一条出口。
 *
 * @param onFrame 每一帧调一次。它抛出的异常会中断整条流并向上抛。
 */
export async function stream(
  path: string, body: Json, onFrame: (f: StreamFrame) => void,
): Promise<void> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  const t = auth.token;
  if (t) headers.Authorization = `Bearer ${t}`;

  let resp: Response;
  try {
    resp = await fetch(path, { method: 'POST', headers, body: JSON.stringify(body) });
  } catch {
    throw new ApiError(0, '网络连接不可用，请检查网络后重试');
  }

  if (!resp.ok || !resp.body) {
    const text = await resp.text().catch(() => '');
    let data: Json = {};
    if (text) { try { data = JSON.parse(text); } catch { data = { message: text }; } }
    if (resp.status === 401 || (resp.status === 403 && data.reason === MUST_CHANGE_PWD))
      throw authFailure(resp.status, data);
    throw new ApiError(resp.status, data.message || `请求失败（${resp.status}）`, data);
  }

  const reader = resp.body.getReader();
  const dec = new TextDecoder();
  let buf = '';
  for (;;) {
    const { done, value } = await reader.read();
    if (value) buf += dec.decode(value, { stream: true });
    let i;
    while ((i = buf.indexOf('\n\n')) >= 0) {
      const raw = buf.slice(0, i);
      buf = buf.slice(i + 2);
      for (const line of raw.split('\n')) {
        if (!line.startsWith('data:')) continue;
        const payload = line.slice(5).trim();
        if (!payload) continue;
        // 单帧解析失败不该把整条流打断：前面已经收到的内容仍然是有效的，
        // 而这一帧丢了会由收尾帧（done / aborted）兜住 —— 收尾帧没来，
        // 调用方本来就会按「没答完」处置。
        try { onFrame(JSON.parse(payload) as StreamFrame); } catch { /* 跳过这一帧 */ }
      }
    }
    if (done) break;
  }
}


export const api = {
  get:  <T = Json>(p: string) => request<T>('GET', p),
  post: <T = Json>(p: string, b?: Json) => request<T>('POST', p, b),
  put:  <T = Json>(p: string, b?: Json) => request<T>('PUT', p, b),
  // 后台的编辑接口用 PATCH（只改传上来的那几个字段）。它必须在这里，
  // 不能让调用方自己 fetch —— 少了它，后台就出现了第二条鉴权路径：
  // 那份手搓的 401 分支绕开 auth.clear()，既不发通知也不触发 onUnauthorized，
  // 于是「令牌失效」和「界面回到登录页」不再是同一件事。
  patch: <T = Json>(p: string, b?: Json) => request<T>('PATCH', p, b),
  del:  <T = Json>(p: string) => request<T>('DELETE', p),
  /**
   * 边收边给（SSE）。同样挂在这里而不是让调用方自己 fetch，理由同上：
   * 一条自己 fetch 的路就是一条绕开统一鉴权出口的路。
   *
   * <p><b>刻意不参与 inFlight 去重</b>：去重是拿 (方法,路径,请求体) 当键的，
   * 而两次内容相同的追问是两次真实的提问，合并掉第二次 = 学员发出去的话消失了。
   * 连点的防护在调用方（发送期间输入框禁用 + pending 闸）。
   */
  stream,
};

/* ---------------------------------------------------------------- 格式化 */

/** 分 → 元。金额永远以分传输，前端只在展示的最后一刻转 —— 中途转会累积浮点误差。 */
export function yuan(fen: number | null | undefined, withSymbol = true): string {
  if (fen === null || fen === undefined) return '—';
  const s = (fen / 100).toFixed(2).replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  return withSymbol ? `¥${s}` : s;
}

export function maskPhone(p?: string | null): string {
  if (!p) return '—';
  return p.length >= 11 ? `${p.slice(0, 3)}****${p.slice(-4)}` : p;
}

/** 秒 → 1:02:03。课程时长与播放位置都用它，两处格式必须一致。 */
export function hms(sec: number | null | undefined): string {
  if (sec == null || !isFinite(sec)) return '—';
  const s = Math.max(0, Math.floor(sec));
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), r = s % 60;
  const pad = (n: number) => String(n).padStart(2, '0');
  return h > 0 ? `${h}:${pad(m)}:${pad(r)}` : `${m}:${pad(r)}`;
}

export function dateTime(v?: string | null): string {
  if (!v) return '—';
  const d = new Date(v.replace(' ', 'T'));
  if (isNaN(+d)) return v;
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

export function dateOnly(v?: string | null): string {
  const s = dateTime(v);
  return s === '—' ? s : s.slice(0, 10);
}

/** 距今多久。用于"还剩 x 天到期"这类提示——绝对日期看不出紧迫感。 */
export function untilText(v?: string | null): string {
  if (!v) return '—';
  const d = new Date(v.replace(' ', 'T'));
  const days = Math.ceil((+d - Date.now()) / 86400000);
  if (days < 0) return '已过期';
  if (days === 0) return '今天到期';
  if (days <= 30) return `还剩 ${days} 天`;
  return `${dateOnly(v)} 到期`;
}

/**
 * 设备指纹。播放令牌绑定设备，所以每台设备要有个稳定标识。
 *
 * 这不是"防破解"——浏览器里任何东西都能被改。它挡的是【顺手转发】：
 * 把播放链接发给同学，对方设备号不同，取不到密钥。
 * 真要绕过需要动手改本地存储，那已经不是"顺手"了。
 */
export function deviceId(): string {
  const K = 'wangke.device';
  let d = localStorage.getItem(K);
  if (!d) {
    d = 'd_' + crypto.randomUUID().replace(/-/g, '').slice(0, 24);
    localStorage.setItem(K, d);
  }
  return d;
}
