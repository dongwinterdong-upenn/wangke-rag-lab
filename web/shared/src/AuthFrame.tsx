/**
 * 登录 / 注册 / 改密页的统一版面。
 *
 * ## 为什么把三端的登录页并到一处
 *
 * 之前三端各写一套：学员端顶部左对齐、代理端整屏居中、后台又是另一种居中。
 * 单看每一个都不难看，但这三张页面是同一个平台**唯一会被并排比较**的界面 ——
 * 一个代理商本人往往同时是学员，他一天里会先后打开 /study 和 /agent；
 * 两张登录页长得不一样，他的直接结论是"这俩不是一家做的"。
 *
 * ## 版面
 *
 * 宽屏是 DS 的旗舰封面结构：左边一块暖网格底板承担品牌（衬线大字 + 单等宽眉标
 * + 红色切角 + 遥测刻度线），右边一张纸卡承担表单。红色全部集中在左板，
 * 右边除了主按钮不再出现第二处红 —— 这就是 DS 说的「一屏一个红锚点」。
 *
 * 窄屏把左板压成一条 88px 的品牌带：手机上没有横向空间演品牌，
 * 而**表单必须顶到上面**。居中的表单在软键盘弹起时会被顶得只剩半张脸，
 * 用户看到的是一个输入框飘在屏幕边缘、下面什么都没有的页面。
 */
import React from 'react';
import { GLOBAL_KEYFRAMES, ToastHost } from './ui';

export function AuthFrame({
  brand, kicker, headline, lede, points, title, desc, children, footer, note,
}: {
  /** 品牌行，例如「学习中心」。会用衬线排 */
  brand: string;
  /** 单等宽眉标，例如 STUDENT / 学员端 */
  kicker?: string;
  /** 左板大标题。写死的短句，衬线 */
  headline?: string;
  lede?: string;
  /** 左板下方三两条要点。别超过三条，超过就不是要点了 */
  points?: string[];
  /** 右侧表单卡的标题 */
  title: string;
  desc?: React.ReactNode;
  children: React.ReactNode;
  footer?: React.ReactNode;
  /** 卡片下方的补充说明（审计提醒、账号体系说明这类） */
  note?: React.ReactNode;
}) {
  return (
    <ToastHost>
      <style>{GLOBAL_KEYFRAMES}</style>
      <style>{AUTH_CSS}</style>
      <div className="wk-auth">
        <aside className="wk-auth-brand da-gridbg-warm">
          <div className="da-facet" aria-hidden style={{ borderWidth: '0 40px 40px 0' }} />
          <div className="wk-auth-brand-in">
            <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--s3)' }}>
              <span aria-hidden style={{
                width: 0, height: 0, flex: 'none',
                borderStyle: 'solid', borderWidth: '0 0 22px 22px',
                borderColor: 'transparent transparent var(--brand) transparent',
              }} />
              <span className="da-serif" style={{ fontSize: 'var(--t-h2)', color: 'var(--ink-0)' }}>
                {brand}
              </span>
            </div>

            {kicker && (
              <span className="da-overline wk-auth-kicker" style={{ marginTop: 'var(--s6)' }}>
                {kicker}
              </span>
            )}

            {headline && (
              <h1 className="da-serif wk-auth-headline">{headline}</h1>
            )}

            {lede && <p className="wk-auth-lede">{lede}</p>}

            {points && points.length > 0 && (
              <>
                <div className="da-tickrule wk-auth-rule" aria-hidden />
                <ul className="wk-auth-points">
                  {points.map(p => <li key={p}>{p}</li>)}
                </ul>
              </>
            )}
          </div>
        </aside>

        <main className="wk-auth-main">
          <div className="wk-auth-card">
            <span className="da-accent-bar" aria-hidden />
            <h2 className="da-serif" style={{ fontSize: 'var(--t-h1)' }}>{title}</h2>
            {desc && <div className="wk-auth-desc">{desc}</div>}
            <div style={{ marginTop: 'var(--s6)' }}>{children}</div>
            {footer && <div className="wk-auth-footer">{footer}</div>}
          </div>
          {note && <div className="wk-auth-note">{note}</div>}
        </main>
      </div>
    </ToastHost>
  );
}

const AUTH_CSS = `
.wk-auth { min-height: 100%; display: grid; grid-template-columns: 1fr; }

.wk-auth-brand {
  position: relative; overflow: hidden;
  border-bottom: 1px solid var(--line);
  display: flex; align-items: center;
  padding: var(--s4);
  min-height: 76px;
}
/* 手机上品牌带只有一行高，40px 的角切三角会盖掉右上角一大块 */
.wk-auth-brand > .da-facet { border-width: 0 22px 22px 0 !important; }
.wk-auth-brand-in { width: 100%; max-width: 420px; }
.wk-auth-kicker, .wk-auth-headline, .wk-auth-lede,
.wk-auth-rule, .wk-auth-points { display: none; }

.wk-auth-main {
  padding: var(--s6) var(--s4) var(--s8);
  display: flex; flex-direction: column; align-items: center;
}
.wk-auth-card { width: 100%; max-width: 400px; }
.wk-auth-desc {
  color: var(--ink-2); font-size: var(--t-sm);
  margin-top: var(--s2); line-height: var(--lh-body);
}
.wk-auth-footer {
  margin-top: var(--s6); padding-top: var(--s4);
  border-top: 1px solid var(--line);
  font-size: var(--t-sm); color: var(--ink-2); text-align: center;
}
.wk-auth-note {
  width: 100%; max-width: 400px; margin-top: var(--s5);
  font-size: var(--t-xs); color: var(--ink-3); line-height: var(--lh-body);
}

@media (min-width: 900px) {
  .wk-auth { grid-template-columns: minmax(420px, 46%) 1fr; }
  .wk-auth-brand {
    border-bottom: 0; border-right: 1px solid var(--line);
    align-items: center; padding: var(--s8) var(--s7);
    min-height: 100vh;
  }
  .wk-auth-brand > .da-facet { border-width: 0 40px 40px 0 !important; }
  .wk-auth-brand-in { max-width: 460px; margin-left: auto; }
  .wk-auth-kicker, .wk-auth-lede, .wk-auth-rule { display: block; }
  .wk-auth-kicker { display: inline-flex; }
  .wk-auth-headline {
    display: block;
    font-size: clamp(30px, 3.4vw, 44px);
    line-height: 1.14; margin: var(--s3) 0 0;
    color: var(--ink-0);
  }
  .wk-auth-lede {
    margin-top: var(--s4); color: var(--ink-2);
    font-size: var(--t-body); line-height: var(--lh-body); max-width: 34em;
  }
  .wk-auth-rule { margin: var(--s6) 0 var(--s4); }
  .wk-auth-points {
    display: grid; gap: var(--s3);
    margin: 0; padding: 0; list-style: none;
    font-size: var(--t-sm); color: var(--ink-2);
  }
  .wk-auth-points li { display: flex; gap: var(--s3); align-items: baseline; }
  .wk-auth-points li::before {
    content: ''; width: 5px; height: 5px; flex: none;
    background: var(--brand); transform: rotate(45deg);
    position: relative; top: -1px;
  }
  .wk-auth-main { justify-content: center; padding: var(--s8) var(--s7); align-items: flex-start; }
  .wk-auth-card, .wk-auth-note { max-width: 380px; }
}
`;
