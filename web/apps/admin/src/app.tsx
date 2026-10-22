import React from 'react';
import { Navigate, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import { onMustChangePassword, onUnauthorized } from '@wangke/shared/api';
import { ForcePasswordChange } from '@wangke/shared/force-password';
import { AppShell, NavItem } from '@wangke/shared/shell';
import {
  IconAudit, IconCart, IconCert, IconChat, IconDashboard, IconFile, IconKey, IconReceipt, IconRefund,
  IconShield, IconTicket, IconUsers, IconWallet,
} from '@wangke/shared/icons';
import { session, useSession } from './lib/session';
import { Login } from './pages/Login';
import { Overview } from './pages/Overview';
import { Agents } from './pages/Agents';
import { Orders } from './pages/Orders';
import { Refunds } from './pages/Refunds';
import { Users } from './pages/Users';
import { Courses } from './pages/Courses';
import { Withdraws } from './pages/Withdraws';
import { Qa } from './pages/Qa';
import { Invoices } from './pages/Invoices';
import { Risk } from './pages/Risk';
import { Audit } from './pages/Audit';
import { Pricing } from './pages/Pricing';
import { StudentCodes } from './pages/StudentCodes';
import { Exams } from './pages/Exams';

/**
 * 导航分组按「要不要我现在做点什么」排，不按数据表排。
 *
 * 审批那一组单列出来，是因为它是后台里唯一**有人在等**的一类事：
 * 退款、提现、代理申请压着不办，对面是真金白银在等。
 * 混在「订单/学员/课程」这些随时可查的东西里，它们会被当成一样的东西对待。
 */
const NAV: NavItem[] = [
  { to: '/', label: '总览', end: true, icon: <IconDashboard size={15} /> },
  { to: '/agents', label: '代理管理', group: '待办审批', icon: <IconUsers size={15} /> },
  { to: '/refunds', label: '退款审批', group: '待办审批', icon: <IconRefund size={15} /> },
  { to: '/withdraws', label: '提现审批', group: '待办审批', icon: <IconWallet size={15} /> },
  { to: '/qa', label: '答疑工单', group: '待办审批', icon: <IconChat size={15} /> },
  { to: '/pricing', label: '价目', group: '经营', icon: <IconCart size={15} /> },
  { to: '/orders', label: '订单', group: '经营', icon: <IconReceipt size={15} /> },
  { to: '/student-codes', label: '学员激活码', group: '经营', icon: <IconKey size={15} /> },
  { to: '/invoices', label: '发票', group: '经营', icon: <IconFile size={15} /> },
  { to: '/users', label: '学员管理', group: '经营', icon: <IconUsers size={15} /> },
  { to: '/courses', label: '课程与讲次', group: '内容', icon: <IconCert size={15} /> },
  { to: '/exams', label: '试卷与题库', group: '运营', icon: <IconTicket size={15} /> },
  { to: '/risk', label: '风控', group: '治理', icon: <IconShield size={15} /> },
  { to: '/audit', label: '审计日志', group: '治理', icon: <IconAudit size={15} /> },
];

/**
 * 全局样式补丁。只补两件 shared 的复位没管、而后台一定会踩的事：
 *  ① 页面 body 绝不允许横向滚 —— 宽表要在自己的容器里滚。
 *     overflow 写在 html 上而不是 body 上：写 body 会让 body 变成滚动容器，
 *     顶栏的 sticky 会当场失效，而这个副作用只在窄屏才看得出来。
 *  ② 表格里的长单号要能断行，否则一列单号能把整张表撑出屏幕。
 */
const GLOBAL = `
  html { overflow-x: hidden; }
  table td, table th { overflow-wrap: anywhere; }
  details > summary { list-style: revert; }
`;

export function App() {
  // ★ 必须用 useSession 订阅，不能 session.get() 直接读一次。
  //   直接读的话，登录成功之后这个组件不会重渲染（它自己不用任何路由 hook，
  //   而 main.tsx 里那个 <App /> 元素引用是稳定的，React 会跳过它），
  //   于是下面那个 element 里的 s 永远是登录前的 null —— 人被弹回登录页，
  //   而令牌其实已经存好了。完整解释见 lib/session.ts 顶部。
  const s = useSession();

  // 401 的统一出口在 AppShell 里，但它只在壳里生效，而且只调 auth.clear()：
  // 那把钥匙没了，这边这份「我是谁」还留在 localStorage 里，也不会通知任何人。
  // 在这里补一次，让令牌失效和界面回到登录页是同一件事。
  React.useEffect(() => onUnauthorized(() => session.clear()), []);

  // 强制改密。后台自己那条路走不到这里（Login 在 mustChangePwd 那一档刻意不存令牌，
  // 而重置密码必然同时 token_version+1，旧令牌当场作废），但后端这个 403 是对
  // **任何**已登录客户端都会发的，而后台没有处理的话就是一屏又一屏
  // 「请先修改初始密码」加上没有任何地方能改密码 —— 正是本轮一直在治的那种死胡同。
  // 三行的保险，不去赌那条路真的到不了。
  const [forced, setForced] = React.useState<string | null>(null);
  React.useEffect(() => onMustChangePassword(msg => setForced(msg)), []);
  if (forced) return <ForcePasswordChange brand="平台后台" hint={forced} />;

  return (
    <>
      <style>{GLOBAL}</style>
      <Routes>
        <Route path="/login" element={<Login />} />
        <Route path="*" element={s ? <Shell /> : <Navigate to="/login" replace />} />
      </Routes>
    </>
  );
}

function Shell() {
  const nav = useNavigate();
  const loc = useLocation();
  const s = useSession();

  // 不是平台管理员就别停在这里：所有 /api/admin/** 都会 403，
  // 界面上表现为「每个页面都报错」，而真正的原因是登错了角色
  React.useEffect(() => {
    if (s && s.role !== 'PLATFORM_ADMIN') {
      session.clear();
      nav('/login?wrongRole=1', { replace: true });
    }
  }, [s, nav]);

  return (
    <AppShell
      brand="平台后台"
      subtitle="网课平台"
      nav={NAV}
      density="compact"
      user={{ name: s?.phoneMasked ?? '平台管理员', role: s?.role }}
      onLogout={() => { session.clear(); nav('/login', { replace: true }); }}
    >
      <Routes location={loc}>
        <Route index element={<Overview />} />
        <Route path="/agents" element={<Agents />} />
        <Route path="/pricing" element={<Pricing />} />
        <Route path="/orders" element={<Orders />} />
        <Route path="/student-codes" element={<StudentCodes />} />
        <Route path="/refunds" element={<Refunds />} />
        <Route path="/withdraws" element={<Withdraws />} />
        <Route path="/users" element={<Users />} />
        <Route path="/courses" element={<Courses />} />
        <Route path="/exams" element={<Exams />} />
        <Route path="/qa" element={<Qa />} />
        <Route path="/invoices" element={<Invoices />} />
        <Route path="/risk" element={<Risk />} />
        <Route path="/audit" element={<Audit />} />
        <Route path="*" element={<NotFound />} />
      </Routes>
    </AppShell>
  );
}

function NotFound() {
  return (
    <div style={{ padding: 'var(--s7) 0', textAlign: 'center', color: 'var(--ink-3)' }}>
      <div style={{ fontSize: 'var(--t-h2)', color: 'var(--ink-2)' }}>这个地址后台里没有</div>
      <div style={{ marginTop: 'var(--s2)', fontSize: 'var(--t-sm)' }}>
        左边导航里挑一个。如果是别人发给你的链接，可能是他那边的地址写错了。
      </div>
    </div>
  );
}
