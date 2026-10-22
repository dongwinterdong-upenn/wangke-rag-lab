/**
 * 学员端外壳。
 *
 * ## 为什么不用 shared 的 AppShell
 *
 * AppShell 是给**后台**设计的：左侧栏 + 窄屏收成抽屉。学员端的主场景是
 * 一只手拿着手机看课，抽屉式导航要"点开汉堡→找到条目→再点"三步，
 * 而底部标签栏是一步，且拇指够得到。另外 AppShell 在窄屏会强制显示汉堡按钮，
 * 传空 nav 时那个按钮点了什么也不会发生 —— 一个点了没反应的按钮，
 * 用户的结论是"这网站坏了"，不是"这里没有菜单"。
 *
 * 复用的是 AppShell 真正的价值所在：ToastHost、全局动画、
 * **登录失效的统一出口**（onUnauthorized）。那三样在这里一样接。
 */
import React from 'react';
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import {
  auth, GLOBAL_KEYFRAMES, IconCart, IconCert, IconCourses, IconDashboard, IconUser,
  onUnauthorized, ToastHost, type IconProps,
} from '@wangke/shared';
import { signOut } from './session';
import { DongmaDock } from './dongma';

type Tab = {
  to: string;
  label: string;
  mobileLabel?: string;
  Icon: React.ComponentType<IconProps>;
  end?: boolean;
  emphasis?: boolean;
  /** 属于这一格、但地址不是它的那些页。见下面 TABS 上的说明。 */
  also?: string[];
};

/**
 * 五格封顶。
 *
 * 平台正在长出更多模块，而每多一格，每一格就窄一点、字就小一点 ——
 * 到第七格的时候手机上谁也认不出图标了。所以导航只留五格，
 * 「有什么」这件事交给首页那张导览页去讲（见 pages/Home.tsx）。
 *
 * 激活与答疑因此从标签栏退了下来：激活在首页与「选课与激活」格子里，
 * 答疑在测评系统里、以及右下角那个一直都在的挂件里。
 *
 * ★ `also` 不是装饰。学员点进「测评」再点进某一场考试，地址就变成了 /exam，
 * 这时候五格<b>一格都不亮</b> —— 界面上没有任何东西告诉他自己在哪儿，
 * 而这正是最容易让人按返回键退出去的时刻。
 */
const TABS: Tab[] = [
  { to: '/', label: '首页', Icon: IconDashboard, end: true },
  { to: '/courses', label: '我的课程', mobileLabel: '课程', Icon: IconCourses,
    also: ['/course/', '/learn/'] },
  { to: '/assess', label: '测评系统', mobileLabel: '测评', Icon: IconCert,
    also: ['/exam', '/qa', '/cert'] },
  { to: '/orders', label: '购买课程', mobileLabel: '购课', Icon: IconCart, emphasis: true,
    also: ['/activate'] },
  { to: '/me', label: '我的', Icon: IconUser },
];

/**
 * 这一格该不该亮。
 *
 * 前缀一律带边界（结尾斜杠或全等），理由和后端那份白名单一样：
 * 写成 startsWith('/course') 的话，/courses 会把 /course/3 也算进自己，
 * 两格同时亮 —— 而它不报任何错，只是导航从此说不清你在哪儿。
 */
function tabActive(tab: Tab, path: string): boolean {
  if (!tab.also) return false;
  return tab.also.some(p => p.endsWith('/') ? path.startsWith(p) : path === p || path.startsWith(p + '/'));
}

/** 底栏高度要留给内容，否则最后一行永远被挡住 —— 而被挡住的往往正是提交按钮。 */
const BAR_H = 64;

export function StudentShell() {
  const nav = useNavigate();
  const loc = useLocation();

  // 登录失效的统一出口。每个页面各自处理 401 的话，迟早有页面漏掉，
  // 表现是"点了没反应"：不跳转、不报错，用户只会觉得网站坏了。
  React.useEffect(() => {
    // 包一层再返回：onUnauthorized 的退订函数返回的是 Set.delete 的 boolean，
    // 直接当 cleanup 用在 TS 里是类型错误（React 只接受 void）。
    const off = onUnauthorized(() => {
      auth.clear();
      nav('/login?next=' + encodeURIComponent(loc.pathname + loc.search), { replace: true });
    });
    return () => { off(); };
  }, [nav, loc.pathname, loc.search]);

  return (
    <ToastHost>
      <style>{GLOBAL_KEYFRAMES}</style>
      <style>{SHELL_CSS}</style>

      <div style={{ minHeight: '100%', display: 'flex', flexDirection: 'column' }}>
        <header className="wk-student-header" style={{
          position: 'sticky', top: 0, zIndex: 'var(--z-header)' as unknown as number,
          background: 'var(--surface)', borderBottom: '1px solid var(--line)',
          display: 'flex', alignItems: 'center', gap: 'var(--s5)',
          padding: '0 var(--s4)', height: 'var(--header-h)', flexShrink: 0,
        }}>
          <NavLink to="/" style={{
            display: 'flex', alignItems: 'center', gap: 'var(--s3)',
            textDecoration: 'none', minWidth: 0,
          }}>
            {/* DS 的角切三角。学员端没有别处放品牌记号，这一枚就是全站唯一的一处 */}
            <span aria-hidden style={{
              width: 0, height: 0, flex: 'none',
              borderStyle: 'solid', borderWidth: '0 0 18px 18px',
              borderColor: 'transparent transparent var(--brand) transparent',
            }} />
            <span style={{ display: 'grid' }}>
              <span className="da-serif wk-student-brand-name" style={{
                fontSize: 'var(--t-h3)', color: 'var(--ink-0)', lineHeight: 1.15,
                whiteSpace: 'nowrap',
              }}>学习中心</span>
              <span className="num wk-student-wordmark-en" style={{
                fontSize: 'var(--t-mono-xs)', color: 'var(--ink-3)', letterSpacing: '.16em',
                lineHeight: 1.3, whiteSpace: 'nowrap',
              }}>DRAGONAI</span>
            </span>
          </NavLink>

          <nav className="wk-desknav" style={{ gap: 'var(--s1)', flex: 1 }}>
            {TABS.map(t => (
              <NavLink key={t.to} to={t.to} end={t.end}
                       className={({ isActive }) =>
                         `wk-topnav${t.emphasis ? ' wk-topnav-buy' : ''}`
                         + (isActive || tabActive(t, loc.pathname) ? ' active' : '')}>
                <t.Icon size={16} />
                {t.label}
              </NavLink>
            ))}
          </nav>

          <div style={{ flex: 1 }} className="wk-mobile-spacer" />

          <button onClick={() => { signOut(); nav('/login', { replace: true }); }}
                  className="wk-btn wk-btn-quiet wk-student-exit" style={{
            border: '1px solid var(--line-strong)', padding: '5px 12px',
            fontSize: 'var(--t-xs)', minHeight: 32, whiteSpace: 'nowrap',
          }}>退出</button>
        </header>

        <main className="wk-student-main" style={{
          flex: 1, minWidth: 0, padding: 'var(--s5) var(--s4)',
          // 底栏是 fixed 的，不占文档流，所以内容要自己让出这段高度
          paddingBottom: `calc(${BAR_H}px + var(--s6) + env(safe-area-inset-bottom, 0px))`,
        }}>
          <Outlet />
        </main>
      </div>

      {/*
        底部标签栏。选中态是「顶部一道红杠 + 红图标」，不是整块底色：
        底色块在小屏上会把四个格子切成四个按钮，反而看不出哪个是当前页；
        一道 2px 的红杠只占一行像素，却是这一屏唯一的红，一眼就落上去。
      */}
      <nav className="wk-tabbar" aria-label="主导航" style={{
        position: 'fixed', left: 0, right: 0, bottom: 0,
        gridTemplateColumns: `repeat(${TABS.length}, 1fr)`,
        background: 'var(--surface)', borderTop: '1px solid var(--line)',
        zIndex: 'var(--z-header)' as unknown as number,
        paddingBottom: 'env(safe-area-inset-bottom, 0px)',
      }}>
        {TABS.map(t => (
          <NavLink key={t.to} to={t.to} end={t.end} style={{ height: BAR_H }}
                   className={({ isActive }) =>
                     'wk-tab' + (isActive || tabActive(t, loc.pathname) ? ' active' : '')}>
            <t.Icon size={20} />
            <span>{t.mobileLabel || t.label}</span>
          </NavLink>
        ))}
      </nav>

      {/*
        懂妈挂件。挂在这里而不是各页自己引，理由和 onUnauthorized 那段一样：
        「常驻每一屏」只有在<b>唯一一处</b>挂载时才是真的 —— 交给页面各挂一次，
        迟早有页面漏掉，而漏掉的表现不是报错，是这一页上她安静地不在。

        挂在 StudentShell 里同时也就把范围划准了：登录、注册、结业证明验真
        这三页在 RequireAuth 之外，本来就不该有一个要登录才能用的答疑入口。
      */}
      <DongmaDock />
    </ToastHost>
  );
}

const SHELL_CSS = `
.wk-desknav { display: none; }
.wk-tabbar  { display: grid; }
@media (max-width: 860px) {
  .wk-student-header { padding-inline: var(--s4) !important; }
  .wk-student-brand-name { font-size: 18px !important; }
  .wk-student-wordmark-en { display: none !important; }
  .wk-student-exit { display: none !important; }
  .wk-student-main { padding-top: var(--s4) !important; }
}
@media (min-width: 861px) {
  .wk-desknav { display: flex; }
  .wk-tabbar  { display: none; }
  .wk-mobile-spacer { display: none; }
}

.wk-topnav {
  display: inline-flex; align-items: center; gap: 7px;
  padding: 7px 12px; border-radius: var(--r-md);
  text-decoration: none; font-size: var(--t-sm);
  color: var(--ink-2); white-space: nowrap;
  border-bottom: 2px solid transparent;
  transition: color var(--dur) var(--ease), border-color var(--dur) var(--ease);
}
.wk-topnav:hover { color: var(--ink); text-decoration: none; }
.wk-topnav.active { color: var(--brand); font-weight: 600; border-bottom-color: var(--brand); }
.wk-topnav-buy {
  margin-left: var(--s1); padding-inline: 15px;
  color: var(--da-bg); background: var(--accent); border: 1px solid var(--accent);
  border-bottom-width: 1px;
}
.wk-topnav-buy:hover,
.wk-topnav-buy.active {
  color: var(--da-bg); background: var(--accent-press); border-color: var(--accent-press);
}

.wk-tab {
  position: relative;
  display: grid; place-items: center; gap: 3px;
  text-decoration: none; font-size: var(--t-xs);
  color: var(--ink-3);
  -webkit-tap-highlight-color: transparent;
  transition: color var(--dur) var(--ease);
}
.wk-tab.active { color: var(--brand); font-weight: 600; }
.wk-tab.active::before {
  content: ''; position: absolute; top: 0; left: 22%; right: 22%;
  height: 2px; background: var(--brand);
}
.wk-tab:active { background: var(--brand-soft); }
/* 必须用 [href$=] 后缀匹配，不能写 [href="/orders"]：
   react-router 在 basename='/study' 下渲染出来的是 href="/study/orders"，
   等值选择器永不命中 —— 开发环境（base=/）一切正常，一上生产这格的橙色强调
   和右上角小红点就全没了，且不报任何错。学员端 TABS 里只有这一条以 /orders
   结尾，后缀匹配不会误伤。 */
.wk-tab[href$="/orders"] { color: var(--accent); }
.wk-tab[href$="/orders"]::after {
  content: ''; position: absolute; top: 7px; right: calc(50% - 18px);
  width: 6px; height: 6px; border-radius: 50%; background: var(--accent);
}
.wk-tab[href$="/orders"].active { color: var(--accent-press); }
.wk-tab[href$="/orders"].active::before { background: var(--accent); }
`;

/**
 * 未登录挡板。
 *
 * 带上 next：从"续播链接"点进来的人被弹去登录之后，登录完要回到他本来要看的那一讲，
 * 而不是回到首页让他自己再找一遍。找不回来的那一次，学员的印象是"这平台记不住事"。
 */
export function RequireAuth({ children }: { children: React.ReactNode }) {
  const loc = useLocation();
  if (!auth.token) {
    const next = encodeURIComponent(loc.pathname + loc.search);
    return <NavigateReplace to={`/login?next=${next}`} />;
  }
  return <>{children}</>;
}

function NavigateReplace({ to }: { to: string }) {
  const nav = useNavigate();
  React.useEffect(() => { nav(to, { replace: true }); }, [nav, to]);
  return null;
}
