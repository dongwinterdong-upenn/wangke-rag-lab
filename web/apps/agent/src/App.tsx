import React from 'react';
import {
  BrowserRouter, Routes, Route, Navigate, useNavigate, useLocation,
} from 'react-router-dom';
import {
  AppShell, Button, ErrorState, ForcePasswordChange, auth, onMustChangePassword,
  useAsync, useAuthToken, type NavItem,
  IconCart, IconDashboard, IconQr, IconTicket, IconTrend, IconUser, IconUsers, IconWallet,
} from '@wangke/shared';
import { AgentApi } from './api';
import { MeProvider } from './gate';
import { PageLoading } from './kit';

import Login from './pages/Login';
import Dashboard from './pages/Dashboard';

// 工作台之外的页面按需加载。
// 生成二维码的库只有「推广」和「进货」两页要用，它没有理由躺在首屏包里 ——
// 代理在路上用手机开这个后台，首屏每多 100KB 都是他在等。
const Apply = React.lazy(() => import('./pages/Apply'));
const Codes = React.lazy(() => import('./pages/Codes'));
const Students = React.lazy(() => import('./pages/Students'));
const Commission = React.lazy(() => import('./pages/Commission'));
const Withdraw = React.lazy(() => import('./pages/Withdraw'));
const Promote = React.lazy(() => import('./pages/Promote'));
const Buy = React.lazy(() => import('./pages/Buy'));
const Me = React.lazy(() => import('./pages/Me'));

/**
 * 导航按代理的工作循环排，不按后端接口排：
 *   进货 → 把码发给学员 → 看谁激活了 → 结佣金 → 提现
 * 「按模块列菜单」看起来更整齐，但代理每天做的事是一条线，
 * 菜单顺序和那条线对不上，他每次都要在菜单里找一遍。
 */
const NAV: NavItem[] = [
  { to: '/', label: '工作台', end: true, icon: <IconDashboard size={15} /> },
  { to: '/buy', label: '进货下单', group: '经营', icon: <IconCart size={15} /> },
  { to: '/codes', label: '激活码', group: '经营', icon: <IconTicket size={15} /> },
  { to: '/promote', label: '推广', group: '经营', icon: <IconQr size={15} /> },
  { to: '/students', label: '我的学员', group: '经营', icon: <IconUsers size={15} /> },
  { to: '/commission', label: '佣金流水', group: '结算', icon: <IconTrend size={15} /> },
  { to: '/withdraw', label: '提现', group: '结算', icon: <IconWallet size={15} /> },
  { to: '/me', label: '我的资料', group: '账号', icon: <IconUser size={15} /> },
];

function Shell() {
  const nav = useNavigate();
  const loc = useLocation();
  const state = useAsync(() => AgentApi.me(), []);

  if (state.loading) return <PageLoading label="正在读取你的代理资料" />;
  if (state.error || !state.data) {
    // 这一屏在 AppShell 之外，所以没有顶栏、没有导航、也没有退出按钮 ——
    // 它必须自己给出一条出路。少了这条，401 那种情况下用户会被困在
    // 「一句错误 + 一个永远回到同一屏的重试按钮」上，只能自己改地址栏。
    const dead = state.status === 401 || state.status === 403;
    return (
      <div style={{ padding: 'var(--s6)' }}>
        <ErrorState message={state.error || '读不到你的资料'}
                    onRetry={dead ? undefined : state.reload} />
        <div style={{ textAlign: 'center', marginTop: 'var(--s4)' }}>
          <Button variant={dead ? 'primary' : 'quiet'} size="sm"
                  onClick={() => { auth.clear(); nav('/login', { replace: true }); }}>
            重新登录
          </Button>
        </div>
      </div>
    );
  }

  const me = state.data;
  const level = me.agent ? (me.agent.level === 1 ? '一级代理' : '二级代理') : '未开通';

  return (
    <MeProvider value={{ me, reload: state.reload }}>
      <AppShell
        brand="代理后台"
        subtitle={me.agent?.agentCode ? `${level} · ${me.agent.agentCode}` : level}
        nav={NAV}
        density="compact"
        user={{ name: me.user.nickname || me.user.phone, role: me.user.role }}
        onLogout={() => { auth.clear(); nav('/login', { replace: true }); }}
      >
        {/* key 让每次换页都重新挂载，避免上一页的筛选状态残留到下一页 */}
        <div key={loc.pathname}>
          <React.Suspense fallback={<PageLoading label="正在打开" />}>
            <Routes>
              <Route path="/" element={<Dashboard />} />
              <Route path="/apply" element={<Apply />} />
              <Route path="/buy" element={<Buy />} />
              <Route path="/codes" element={<Codes />} />
              <Route path="/promote" element={<Promote />} />
              <Route path="/students" element={<Students />} />
              <Route path="/commission" element={<Commission />} />
              <Route path="/withdraw" element={<Withdraw />} />
              <Route path="/me" element={<Me />} />
              <Route path="*" element={<Navigate to="/" replace />} />
            </Routes>
          </React.Suspense>
        </div>
      </AppShell>
    </MeProvider>
  );
}

/**
 * 强制改密这一档要整屏接管，不能放进 Shell 里。
 *
 * <p>后端在 must_change_pwd=1 时对 /api/account/** 以外的每个接口都回 403，
 * 而 Shell 做的第一件事就是 GET /api/agent/me —— 它必然 403，于是用户停在
 * 一屏「请先修改初始密码」的错误块上，而<b>那一屏里没有任何地方能改密码</b>。
 * 一句正确的提示配一条走不通的路，比不提示更让人恼火。
 *
 * <p>登录时并不知道要不要走这一档（Login 拿到 mustChangePwd 后直接跳 /me，
 * 那是修之前的老路），所以这里既接登录响应也接 403 —— 两条路都通到同一屏。
 */
function Guard() {
  const loc = useLocation();
  const [forced, setForced] = React.useState<string | null>(null);

  React.useEffect(() => onMustChangePassword(msg => setForced(msg)), []);

  // ★★ 这一行必须排在下面那个 `if (forced) return` 【之前】，不能挪到后面去。
  //
  //   useAuthToken 内部是 useEffect + useSyncExternalStore 两个真 hook。
  //   把它放在 early return 之后，首次挂载走 4 个 hook，而 forced 由 null 变成
  //   字符串的那一次重渲染只走 2 个 —— React 抛「Rendered fewer hooks than expected」。
  //   **这条在生产构建里同样会抛**（react-dom 的 finishRenderingHooks 无条件 throw），
  //   而全仓没有任何 ErrorBoundary，于是整个 root 被卸载：代理端白屏。
  //
  //   结果是最讽刺的一种：为强制改密加的那扇门，被这一行的位置从里面反锁了 ——
  //   门装上了，但谁也走不到它面前。tsc 看不见 hook 顺序，仓里也没有 eslint，
  //   所以这件事不会有任何东西变红，只对 must_change_pwd=1 的账号发生。
  //
  //   （另一层理由见下：令牌必须订阅，不能 useState(() => auth.token) 存快照。）
  //
  //   快照那版的死法：令牌过期后刷新页面 —— 快照读到的还是那串旧令牌（真值），
  //
  //   快照那版的死法：令牌过期后刷新页面 —— 快照读到的还是那串旧令牌（真值），
  //   于是渲染 <Shell/>，Shell 发出的首个 /api/agent/me 得到 401，
  //   api.ts 把令牌删掉并通知 onUnauthorized —— 而那一刻**一个监听者都没有**，
  //   因为唯一的监听者在 AppShell 里，AppShell 要等 me() 成功才挂载。
  //   结果是一屏光秃秃的「登录状态已失效」加一个重试按钮：没有顶栏、没有导航、
  //   没有退出、没有任何通往登录页的入口，点重试永远回到同一屏
  //   （令牌已删，请求连 Authorization 头都不带，文案换成「请先登录」）。
  //   用户唯一的出路是自己改地址栏。控制台零红字。
  //
  //   订阅之后，令牌被清掉的那一刻 Guard 就会重渲染并把人送回登录页。
  const token = useAuthToken();

  // ---- hook 全部调用完毕，从这里往下才允许 early return ----
  if (forced) return <ForcePasswordChange brand="代理后台" hint={forced} />;
  if (!token) return <Navigate to="/login" replace state={{ from: loc.pathname }} />;
  return <Shell />;
}

export default function App() {
  return (
    <BrowserRouter basename={import.meta.env.BASE_URL.replace(/\/$/, '')}>
      <Routes>
        <Route path="/login" element={<Login />} />
        <Route path="*" element={<Guard />} />
      </Routes>
    </BrowserRouter>
  );
}
