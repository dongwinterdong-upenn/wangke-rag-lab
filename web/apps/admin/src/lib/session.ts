import React from 'react';
import { auth } from '@wangke/shared/api';

/**
 * 后台登录态。
 *
 * 令牌本身交给 shared 的 auth（三端按入口使用独立 key，401 的统一出口也在那边）。
 * 这里多存一份「我是谁」，是为了不去猜一个后端还没有的 /api/auth/me：
 * 登录响应里已经给了 userId / role / phoneMasked，把它记下来就够顶栏用了。
 * 真正的权限判定在服务端每一次请求上（AuthInterceptor 每次都查库），
 * 前端这份只用来显示与引导，绝不当作授权依据。
 *
 * ## ★ 为什么这里必须是一个「可订阅」的存储，而不只是 localStorage 读写
 *
 * 原来 `session.get()` 只是同步读一次 localStorage，而 `App` 在渲染时读它：
 *
 *     const s = session.get();
 *     <Route path="*" element={s ? <Shell /> : <Navigate to="/login" replace />} />
 *
 * 登录成功后 Login 存好会话、`navigate('/')`。位置变了，`Routes` 会重渲染 ——
 * 但 **`App` 不会**：它挂在 `<BrowserRouter>` 下、自己不用任何路由 hook，
 * 而 main.tsx 里那个 `<App />` 元素引用始终是同一个对象，React 直接跳过它。
 * 于是 `Routes` 拿到的还是**上一次渲染时算好的那个 element**，里面的 `s` 仍是 null，
 * 匹配 `*` 时渲染的是 `<Navigate to="/login" replace />` —— 当场被弹回登录页。
 *
 * 表现正是「密码对、令牌也存下来了，但就是进不去」：
 * 网络面板全是 200，localStorage 里两把钥匙都在，控制台一条错都没有。
 * 这类 bug 不会报错，只会安静地把人挡在门外。
 *
 * 所以登录态要能**通知**：save / clear 时发一次事件，组件用
 * `useSyncExternalStore` 订阅。这样登录、退出、401 被清掉三条路都会重渲染，
 * 而不是只有「碰巧会重渲染的那条」。
 */
export type AdminSession = {
  token: string;
  userId: number;
  role: string;
  phoneMasked: string;
  mustChangePwd: boolean;
  at: number;
};

const KEY = 'wangke.admin.session';

const subscribers = new Set<() => void>();
const emit = () => subscribers.forEach(fn => fn());

/**
 * 快照缓存。
 *
 * `useSyncExternalStore` 会在每次渲染时调 getSnapshot，并用 `Object.is` 比较结果 ——
 * 每次都 `JSON.parse` 出一个新对象的话，两次结果永远不相等，
 * React 会认为状态一直在变，直接进入无限重渲染（报 "getSnapshot should be cached"）。
 * 所以按「原始字符串 + 令牌」缓存：这两样没变，就返回上一次那个对象引用。
 */
let cache: { raw: string | null; token: string | null; value: AdminSession | null } =
  { raw: null, token: null, value: null };

function snapshot(): AdminSession | null {
  const token = auth.token;
  const raw = token ? localStorage.getItem(KEY) : null;   // 令牌被 401 清掉了，这份就作废
  if (raw === cache.raw && token === cache.token) return cache.value;
  let value: AdminSession | null = null;
  if (raw) {
    try { value = JSON.parse(raw) as AdminSession; } catch { value = null; }
    // ★ 光看「令牌还在不在」不够，还要看【这份记录是不是属于当前这把钥匙】。
    //
    //   三端现在已经按入口使用独立令牌；这里仍校验身份记录和当前后台令牌一致，
    //   以接住升级前遗留数据、手工清理不完整等异常状态，避免顶栏身份与请求身份分裂。
    //
    //   这正是这次修复要根治的那一类：决定「画什么」的值与真实身份不同源，且零报错。
    if (value && value.token !== token) value = null;
  }
  cache = { raw, token, value };
  return value;
}

export const session = {
  get: snapshot,
  save(s: Omit<AdminSession, 'at'>) {
    auth.set(s.token);
    localStorage.setItem(KEY, JSON.stringify({ ...s, at: Date.now() }));
    emit();
  },
  clear() {
    auth.clear();
    localStorage.removeItem(KEY);
    emit();
  },
  subscribe(fn: () => void) {
    subscribers.add(fn);
    return () => { subscribers.delete(fn); };
  },
};

/**
 * 订阅登录态。凡是要按「登没登录」决定画什么的地方都必须用它，不许直接 `session.get()` ——
 * 直接读的那一处永远只反映**它自己上一次渲染时**的状态。
 *
 * 另外接一次 storage 事件：同一个人开着两个标签页，在一个里退出之后，
 * 另一个还停在后台里，点什么都 401 —— 而 401 的提示远不如「被登出了」清楚。
 */
export function useSession(): AdminSession | null {
  React.useEffect(() => {
    const onStorage = (e: StorageEvent) => {
      // key 为 null 是 localStorage.clear()；后台令牌或后台身份记录变化时都需要刷新快照。
      if (e.key === null || e.key.startsWith('wangke.')) emit();
    };
    window.addEventListener('storage', onStorage);
    return () => window.removeEventListener('storage', onStorage);
  }, []);
  return React.useSyncExternalStore(session.subscribe, snapshot, snapshot);
}
