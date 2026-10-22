/**
 * 三端共用的外壳：顶栏 + 侧栏 + 内容区。
 *
 * 抽出来是因为「一致性靠约定守不住」。三个应用各写一套导航的话，
 * 三个月后它们的间距、断点、登录失效跳转会各不相同，
 * 而不一致在用户那里读起来就是"这几个后台不是一家做的"。
 *
 * 移动端：学员端必须能用手机看（这是主要场景），所以侧栏在窄屏收成抽屉。
 * 后台两端默认给宽屏，但也不能在手机上完全打不开——运营在路上要能审一单。
 *
 * ## 这一版的抽屉重做了
 *
 * 原来的抽屉是「一个 div 直接出现」：没有动画、按 Esc 不关、打开后背景还能滚、
 * 关掉之后焦点掉回页首。这四件事凑在一起的效果是，用手机的人点开菜单、
 * 选完一项、页面跳转了，但他手指下面的背景已经被滑到了别处——
 * 回来时看到的是一个陌生的位置，会以为点错了。
 */
import React from 'react';
import { NavLink, useNavigate } from 'react-router-dom';
import { auth, onUnauthorized } from './api';
import { GLOBAL_KEYFRAMES, ToastHost } from './ui';

export type NavItem = {
  to: string; label: string; icon?: React.ReactNode; end?: boolean;
  /** 分组名。相邻同组的条目会共用一条单等宽小标题。 */
  group?: string;
};

export function AppShell({ brand, subtitle, nav, user, onLogout, density = 'default', children }: {
  brand: string;
  subtitle?: string;
  nav: NavItem[];
  user?: { name?: string | null; role?: string | null };
  onLogout?: () => void;
  density?: 'default' | 'compact';
  children: React.ReactNode;
}) {
  const [drawer, setDrawer] = React.useState(false);
  const nav2 = useNavigate();
  const burstRef = React.useRef<HTMLButtonElement>(null);

  React.useEffect(() => {
    document.documentElement.setAttribute('data-density', density === 'compact' ? 'compact' : '');
  }, [density]);

  // 登录失效的统一出口。每个页面各自处理的话，迟早有页面漏掉，
  // 表现是"点了没反应"——不跳转、不报错，用户只会觉得网站坏了。
  React.useEffect(() => {
    const off = onUnauthorized(() => {
      auth.clear();
      nav2('/login', { replace: true });
    });
    return () => { off(); };
  }, [nav2]);

  // 抽屉：Esc 关、锁背景滚动、关掉时把焦点还给汉堡按钮。
  // 不还焦点的话，只用键盘的人关掉菜单后会从页首重新 Tab 一遍。
  React.useEffect(() => {
    if (!drawer) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setDrawer(false); };
    window.addEventListener('keydown', onKey);
    const prev = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      window.removeEventListener('keydown', onKey);
      document.body.style.overflow = prev;
      burstRef.current?.focus();
    };
  }, [drawer]);

  return (
    <ToastHost>
      <style>{GLOBAL_KEYFRAMES}</style>
      <style>{SHELL_CSS}</style>
      <div style={{ minHeight: '100%', display: 'flex', flexDirection: 'column' }}>
        <header className="wk-shell-header" style={{
          position: 'sticky', top: 0, zIndex: 'var(--z-header)' as any,
          background: 'var(--surface)', borderBottom: '1px solid var(--line)',
          display: 'flex', alignItems: 'center', gap: 'var(--s4)',
          padding: '0 var(--s5)', height: 'var(--header-h)', flexShrink: 0,
        }}>
          <button ref={burstRef} onClick={() => setDrawer(v => !v)}
                  aria-label="菜单" aria-expanded={drawer} className="wk-burger wk-btn wk-btn-quiet"
                  style={{ display: 'none', padding: 0, width: 36, height: 36, minHeight: 0,
                           flex: 'none', marginLeft: 'calc(var(--s2) * -1)' }}>
            <Burger open={drawer} />
          </button>

          <BrandLockup brand={brand} subtitle={subtitle} />

          <div style={{ flex: 1 }} />

          {user && (
            <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--s3)',
                          fontSize: 'var(--t-sm)', color: 'var(--ink-2)', minWidth: 0 }}>
              <span className="wk-hide-mobile" style={{ overflow: 'hidden',
                    textOverflow: 'ellipsis', whiteSpace: 'nowrap', maxWidth: 180 }}>
                {user.name || '未命名'}
              </span>
              {onLogout && (
                <button onClick={onLogout} className="wk-btn wk-btn-quiet wk-shell-logout"
                        style={{ padding: '5px 11px', fontSize: 'var(--t-xs)', minHeight: 32,
                                 border: '1px solid var(--line-strong)' }}>退出</button>
              )}
            </div>
          )}
        </header>

        <div style={{ flex: 1, display: 'flex', minHeight: 0 }}>
          {nav.length > 0 && (
            <>
              <aside className="wk-side" style={{
                width: 212, flexShrink: 0, borderRight: '1px solid var(--line)',
                background: 'var(--surface)', padding: 'var(--s4) 0',
                position: 'sticky', top: 'var(--header-h)', alignSelf: 'flex-start',
                // 高度写满一屏，不只是 maxHeight：只给 maxHeight 的话，
                // 条目不够多时白色侧栏会停在半空，页面上多出一条横切的边，
                // 看起来像布局塌了。
                height: 'calc(100vh - var(--header-h))', overflowY: 'auto',
              }}>
                <SideNav nav={nav} onNavigate={() => setDrawer(false)} />
              </aside>

              {drawer && (
                <div onClick={() => setDrawer(false)} className="wk-drawer-scrim" style={{
                  position: 'fixed', inset: 0, background: 'rgba(43,43,43,.42)',
                  zIndex: 'var(--z-drawer)' as any, animation: 'wk-fade .14s var(--ease)',
                }}>
                  <nav onClick={e => e.stopPropagation()} aria-label="主导航" style={{
                    width: 'min(78vw, 268px)', height: '100%', background: 'var(--surface)',
                    padding: 'var(--s4) 0', boxShadow: 'var(--shadow-3)',
                    borderRight: '2px solid var(--brand)',
                    animation: 'wk-slide-in .22s var(--ease)',
                    overflowY: 'auto', WebkitOverflowScrolling: 'touch',
                  }}>
                    <div style={{ padding: '0 var(--s4) var(--s4)',
                                  borderBottom: '1px solid var(--line)', marginBottom: 'var(--s3)' }}>
                      <BrandLockup brand={brand} subtitle={subtitle} />
                    </div>
                    <SideNav nav={nav} onNavigate={() => setDrawer(false)} />
                  </nav>
                </div>
              )}
            </>
          )}

          <main className="wk-shell-main" style={{ flex: 1, minWidth: 0, padding: 'var(--s5)' }}>
            <div style={{ maxWidth: 'var(--maxw)', margin: '0 auto' }}>{children}</div>
          </main>
        </div>

        {nav.length > 0 && (
          <nav className="wk-shell-tabbar" aria-label="常用功能">
            {nav.slice(0, 4).map(n => (
              <NavLink key={n.to} to={n.to} end={n.end}
                className={({ isActive }) => isActive ? 'wk-shell-tab on' : 'wk-shell-tab'}>
                {n.icon && <span aria-hidden>{n.icon}</span>}
                <span>{n.label}</span>
              </NavLink>
            ))}
          </nav>
        )}
      </div>
    </ToastHost>
  );
}

/**
 * 品牌锁定块。
 *
 * DS 的做法：一枚红色低多边形切角 + 衬线中文名 + 单等宽副标。
 * 副标用单等宽是有用途的，不只是好看 —— 它把「你在哪个端」和品牌名
 * 在视觉上分成两层，三个端切换时那一行是唯一变的东西，扫一眼就能确认。
 */
function BrandLockup({ brand, subtitle }: { brand: string; subtitle?: string }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--s3)', minWidth: 0 }}>
      <span aria-hidden style={{
        width: 0, height: 0, flex: 'none',
        borderStyle: 'solid', borderWidth: '0 0 18px 18px',
        borderColor: 'transparent transparent var(--brand) transparent',
      }} />
      <span style={{ display: 'grid', minWidth: 0 }}>
        <span className="da-serif" style={{
          fontSize: 'var(--t-h3)', color: 'var(--ink-0)', lineHeight: 1.15,
          whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
        }}>{brand}</span>
        {subtitle && (
          <span className="num wk-shell-subtitle" style={{
            fontSize: 'var(--t-mono-xs)', color: 'var(--ink-3)', letterSpacing: '.08em',
            textTransform: 'uppercase', lineHeight: 1.3, whiteSpace: 'nowrap',
          }}>{subtitle}</span>
        )}
      </span>
    </div>
  );
}

/** 汉堡。开着的时候变成叉，省一个图标也省一次「这按钮现在是开还是关」的犹豫。 */
function Burger({ open }: { open: boolean }) {
  const bar: React.CSSProperties = {
    position: 'absolute', left: 9, width: 18, height: 1.5, background: 'currentColor',
    transition: 'transform var(--dur) var(--ease), opacity var(--dur) var(--ease)',
  };
  return (
    <span aria-hidden style={{ position: 'relative', width: 36, height: 36, display: 'block' }}>
      <i style={{ ...bar, top: 13, transform: open ? 'translateY(5px) rotate(45deg)' : undefined }} />
      <i style={{ ...bar, top: 18, opacity: open ? 0 : 1 }} />
      <i style={{ ...bar, top: 23, transform: open ? 'translateY(-5px) rotate(-45deg)' : undefined }} />
    </span>
  );
}

function SideNav({ nav, onNavigate }: { nav: NavItem[]; onNavigate: () => void }) {
  let lastGroup: string | undefined;
  return (
    <div style={{ display: 'grid' }}>
      {nav.map(n => {
        const head = n.group && n.group !== lastGroup ? n.group : null;
        lastGroup = n.group;
        return (
          <React.Fragment key={n.to}>
            {head && (
              <span className="da-overline" style={{
                padding: 'var(--s4) var(--s4) var(--s2) 14px', color: 'var(--ink-4)',
              }}>{head}</span>
            )}
            <NavLink to={n.to} end={n.end} onClick={onNavigate}
              className={({ isActive }) => (isActive ? 'wk-navitem on' : 'wk-navitem')}>
              {n.icon && <span aria-hidden style={{ width: 15, textAlign: 'center', flex: 'none',
                                                    opacity: .85 }}>{n.icon}</span>}
              <span style={{ minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis',
                             whiteSpace: 'nowrap' }}>{n.label}</span>
            </NavLink>
          </React.Fragment>
        );
      })}
    </div>
  );
}

/**
 * 页头。红杠 + 标题 + 说明 + 右侧动作，三端统一。
 *
 * 红杠是 DS 的复现标记（36×3px），也是这一屏的红锚点 ——
 * 定在这里之后，页面正文里就不该再出现第二处大面积的红。
 */
export function PageHead({ title, desc, extra, eyebrow }: {
  title: React.ReactNode; desc?: React.ReactNode; extra?: React.ReactNode;
  eyebrow?: React.ReactNode;
}) {
  return (
    <div className="wk-page-head" style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between',
                  gap: 'var(--s4)', marginBottom: 'var(--s5)', flexWrap: 'wrap' }}>
      <div style={{ minWidth: 0 }}>
        <span className="da-accent-bar" aria-hidden />
        {eyebrow && <div className="da-overline" style={{ marginBottom: 6 }}>{eyebrow}</div>}
        <h1 className="da-serif" style={{ fontSize: 'var(--t-h1)' }}>{title}</h1>
        {desc && <div style={{ color: 'var(--ink-2)', fontSize: 'var(--t-sm)',
                               marginTop: 'var(--s2)', maxWidth: '68ch',
                               lineHeight: 'var(--lh-body)' }}>{desc}</div>}
      </div>
      {extra && <div style={{ display: 'flex', gap: 'var(--s2)', flexWrap: 'wrap',
                              alignItems: 'center' }}>{extra}</div>}
    </div>
  );
}

export function Grid({ min = 220, gap = 'var(--s4)', children }: {
  min?: number; gap?: string; children: React.ReactNode;
}) {
  return <div style={{ display: 'grid', gap,
    gridTemplateColumns: `repeat(auto-fill, minmax(min(${min}px, 100%), 1fr))` }}>{children}</div>;
}

const SHELL_CSS = `
.wk-shell-tabbar { display: none; }
@media (max-width: 860px) {
  .wk-side { display: none; }
  .wk-burger { display: inline-flex !important; }
  .wk-shell-header { padding-inline: var(--s4) !important; gap: var(--s3) !important; }
  .wk-shell-subtitle { display: none !important; }
  .wk-shell-main {
    padding: var(--s4) !important;
    padding-bottom: calc(76px + env(safe-area-inset-bottom, 0px)) !important;
  }
  .wk-shell-tabbar {
    position: fixed; inset-inline: 0; bottom: 0; z-index: var(--z-header);
    display: grid; grid-template-columns: repeat(4, minmax(0, 1fr));
    min-height: 64px; padding-bottom: env(safe-area-inset-bottom, 0px);
    background: color-mix(in srgb, var(--surface) 96%, transparent);
    border-top: 1px solid var(--line); backdrop-filter: blur(12px);
  }
  .wk-shell-tab {
    position: relative; display: grid; place-items: center; align-content: center;
    gap: 3px; min-width: 0; min-height: 64px; padding: 5px 2px;
    color: var(--ink-3); text-decoration: none; font-size: var(--t-xs);
    -webkit-tap-highlight-color: transparent;
  }
  .wk-shell-tab > span:last-child {
    max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  }
  .wk-shell-tab.on { color: var(--brand); font-weight: 600; }
  .wk-shell-tab svg { width: 18px; height: 18px; }
  .wk-shell-tab.on::before {
    content: ''; position: absolute; top: 0; left: 25%; right: 25%;
    height: 2px; background: var(--brand);
  }
  .wk-shell-tab:active { background: var(--brand-soft); }
}
`;
