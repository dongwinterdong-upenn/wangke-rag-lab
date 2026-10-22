/**
 * 图标。
 *
 * ## 为什么是内联 SVG 而不是 emoji，也不是 CDN
 *
 * DS 的图标规矩有两条：**永远不用 emoji**（不管是标题、列表还是幻灯片），
 * 以及用 Lucide、1.75px 描边。原来三端的导航用的是 ▤ ⌗ ✽ ☺ 这类字符 ——
 * 它们不是图标，是**字体里的字**：同一个 ☺ 在 Windows 上是空心线框、
 * 在 iOS 上是一张黄脸、在部分安卓上直接是豆腐块。三端导航的第一列
 * 长成什么样，取决于用户装了什么字体，这不叫设计。
 *
 * DS 给的装载方式是 `<script src="https://unpkg.com/lucide@latest">`。
 * 这里不能这么用，理由和字体一样：unpkg 在境内不稳，
 * 一个挂住的脚本请求换来的是**图标永远不出现**，而且没有任何报错。
 * 而为了二十来个图标去打包整个图标库也不合理。
 *
 * 所以这里把用到的那些 Lucide 图形**原样内联**（Lucide 是 ISC 许可，可商用）。
 * 不是重画：坐标就是 Lucide 的坐标，24×24 视框、1.75 描边、圆头圆角，
 * 换回官方库时逐像素一致。
 */
import React from 'react';

export type IconProps = {
  size?: number;
  /** 描边粗细。DS 默认 1.75；大尺寸下可以调细一点免得糊成一团 */
  stroke?: number;
  className?: string;
  style?: React.CSSProperties;
};

function make(path: React.ReactNode, opts?: { fillRule?: boolean }) {
  return function Icon({ size = 18, stroke = 1.75, className, style }: IconProps) {
    return (
      <svg
        aria-hidden focusable="false"
        width={size} height={size} viewBox="0 0 24 24"
        fill="none" stroke="currentColor" strokeWidth={stroke}
        strokeLinecap="round" strokeLinejoin="round"
        className={className}
        style={{ flex: 'none', display: 'block', ...style }}
      >{path}</svg>
    );
  };
}

/* ---- 学员 ---- */
export const IconCourses = make(<>
  <path d="M12 7v14" />
  <path d="M3 18a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1h5a4 4 0 0 1 4 4 4 4 0 0 1 4-4h5a1 1 0 0 1 1 1v13a1 1 0 0 1-1 1h-6a3 3 0 0 0-3 3 3 3 0 0 0-3-3z" />
</>);

export const IconKey = make(<>
  <path d="M2.586 17.414A2 2 0 0 0 2 18.828V21a1 1 0 0 0 1 1h3a1 1 0 0 0 1-1v-1a1 1 0 0 1 1-1h1a1 1 0 0 0 1-1v-1a1 1 0 0 1 1-1h.172a2 2 0 0 0 1.414-.586l.814-.814a6.5 6.5 0 1 0-4-4z" />
  <circle cx="16.5" cy="7.5" r=".5" fill="currentColor" />
</>);

export const IconChat = make(
  <path d="M22 17a2 2 0 0 1-2 2H6l-4 4V4a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2z" />
);

export const IconUser = make(<>
  <path d="M18 20a6 6 0 0 0-12 0" />
  <circle cx="12" cy="10" r="4" />
  <circle cx="12" cy="12" r="10" />
</>);

export const IconPlay = make(
  <polygon points="6 3 20 12 6 21 6 3" />
);

export const IconCert = make(<>
  <path d="M21.42 10.922a1 1 0 0 0-.019-1.838L12.83 5.18a2 2 0 0 0-1.66 0L2.6 9.08a1 1 0 0 0 0 1.832l8.57 3.908a2 2 0 0 0 1.66 0z" />
  <path d="M22 10v6" />
  <path d="M6 12.5V16a6 3 0 0 0 12 0v-3.5" />
</>);

/* ---- 代理 / 后台 ---- */
export const IconDashboard = make(<>
  <rect width="7" height="9" x="3" y="3" rx="1" />
  <rect width="7" height="5" x="14" y="3" rx="1" />
  <rect width="7" height="9" x="14" y="12" rx="1" />
  <rect width="7" height="5" x="3" y="16" rx="1" />
</>);

export const IconCart = make(<>
  <circle cx="8" cy="21" r="1" />
  <circle cx="19" cy="21" r="1" />
  <path d="M2.05 2.05h2l2.66 12.42a2 2 0 0 0 2 1.58h9.78a2 2 0 0 0 1.95-1.57l1.65-7.43H5.12" />
</>);

export const IconTicket = make(<>
  <path d="M2 9a3 3 0 0 1 0 6v2a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-2a3 3 0 0 1 0-6V7a2 2 0 0 0-2-2H4a2 2 0 0 0-2 2Z" />
  <path d="M13 5v2" /><path d="M13 11v2" /><path d="M13 17v2" />
</>);

export const IconQr = make(<>
  <rect width="5" height="5" x="3" y="3" rx="1" />
  <rect width="5" height="5" x="16" y="3" rx="1" />
  <rect width="5" height="5" x="3" y="16" rx="1" />
  <path d="M21 16h-3a2 2 0 0 0-2 2v3" />
  <path d="M21 21v.01" />
  <path d="M12 7v3a2 2 0 0 1-2 2H7" />
  <path d="M3 12h.01" /><path d="M12 3h.01" /><path d="M12 16v.01" />
  <path d="M16 12h1" /><path d="M21 12v.01" /><path d="M12 21v-1" />
</>);

export const IconUsers = make(<>
  <path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2" />
  <circle cx="9" cy="7" r="4" />
  <path d="M22 21v-2a4 4 0 0 0-3-3.87" />
  <path d="M16 3.13a4 4 0 0 1 0 7.75" />
</>);

export const IconTrend = make(<>
  <polyline points="22 7 13.5 15.5 8.5 10.5 2 17" />
  <polyline points="16 7 22 7 22 13" />
</>);

export const IconWallet = make(<>
  <path d="M19 7V4a1 1 0 0 0-1-1H5a2 2 0 0 0 0 4h15a1 1 0 0 1 1 1v4h-3a2 2 0 0 0 0 4h3a1 1 0 0 0 1-1v-2a1 1 0 0 0-1-1" />
  <path d="M3 5v14a2 2 0 0 0 2 2h15a1 1 0 0 0 1-1v-4" />
</>);

export const IconReceipt = make(<>
  <path d="M4 2v20l2-1 2 1 2-1 2 1 2-1 2 1 2-1 2 1V2l-2 1-2-1-2 1-2-1-2 1-2-1-2 1Z" />
  <path d="M16 8h-6a2 2 0 1 0 0 4h4a2 2 0 1 1 0 4H8" />
  <path d="M12 17.5v-11" />
</>);

export const IconRefund = make(<>
  <path d="M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8" />
  <path d="M3 3v5h5" />
</>);

export const IconFile = make(<>
  <path d="M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z" />
  <path d="M14 2v4a2 2 0 0 0 2 2h4" />
  <path d="M10 9H8" /><path d="M16 13H8" /><path d="M16 17H8" />
</>);

export const IconShield = make(<>
  <path d="M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z" />
  <path d="M12 8v4" /><path d="M12 16h.01" />
</>);

export const IconAudit = make(<>
  <path d="M15 12h-5" /><path d="M15 8h-5" />
  <path d="M19 17V5a2 2 0 0 0-2-2H4" />
  <path d="M8 21h12a2 2 0 0 0 2-2v-1a1 1 0 0 0-1-1H10a1 1 0 0 0-1 1v1a2 2 0 1 1-4 0V5a2 2 0 1 0-4 0v2a1 1 0 0 0 1 1h3" />
</>);

/* ---- 通用 ---- */
export const IconChevronRight = make(<path d="m9 18 6-6-6-6" />);
export const IconArrowRight = make(<><path d="M5 12h14" /><path d="m12 5 7 7-7 7" /></>);
export const IconCheck = make(<path d="M20 6 9 17l-5-5" />);
export const IconClose = make(<><path d="M18 6 6 18" /><path d="m6 6 12 12" /></>);
export const IconSearch = make(<><circle cx="11" cy="11" r="8" /><path d="m21 21-4.3-4.3" /></>);
export const IconRefresh = make(<>
  <path d="M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8" />
  <path d="M21 3v5h-5" />
  <path d="M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16" />
  <path d="M8 16H3v5" />
</>);
export const IconLogout = make(<>
  <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" />
  <path d="m16 17 5-5-5-5" /><path d="M21 12H9" />
</>);
export const IconAlert = make(<>
  <path d="m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3" />
  <path d="M12 9v4" /><path d="M12 17h.01" />
</>);
export const IconInbox = make(<>
  <polyline points="22 12 16 12 14 15 10 15 8 12 2 12" />
  <path d="M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z" />
</>);
export const IconLock = make(<>
  <rect width="18" height="11" x="3" y="11" rx="2" ry="2" />
  <path d="M7 11V7a5 5 0 0 1 10 0v4" />
</>);
export const IconPhone = make(
  <path d="M13.832 16.568a1 1 0 0 0 1.213-.303l.355-.465A2 2 0 0 1 17 15h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2A18 18 0 0 1 2 4a2 2 0 0 1 2-2h3a2 2 0 0 1 2 2v3a2 2 0 0 1-.8 1.6l-.468.351a1 1 0 0 0-.292 1.233 14 14 0 0 0 6.392 6.384" />
);
