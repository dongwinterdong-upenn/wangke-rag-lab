/**
 * `@wangke/shared` 在本应用里的入口。
 *
 * ## 为什么不直接用 shared 自己的 index.ts
 *
 * 那个 barrel 里 `export * from './Player'`，而 Player 静态依赖 hls.js。
 * 于是**任何一个页面**只要从 barrel 里取一个 Button，打包器就得把整个
 * 视频引擎（压缩前约 450KB）拉进首屏那一包 —— 登录页、注册页、
 * 甚至那个公开的结业证明验真页，都要先把它下下来才能显示第一个字。
 * 副作用分析拦住了 tree-shaking，这不是配置能调掉的。
 *
 * 这里把 Player 之外的部分按子模块重新导出，Player 由学习页单独深路径引入。
 * 实测（vite build）：首屏包 830KB → 315KB，gzip 257KB → 98KB；
 * hls.js 落到只有真正看课时才下载的 Learn 分包里。
 *
 * 对页面代码完全透明：vite 与 tsconfig 都把 `@wangke/shared` 指到这个文件，
 * 所以各页面的 import 一行都不用改，将来 shared 收拾掉这个耦合时，
 * 删掉这个文件和那两条别名即可。
 *
 * 这里只做装配，不改任何组件的行为：导出面就是 shared 那几个子模块的并集，
 * 少的只有 Player。
 */
export * from '@wangke/shared/api';
export * from '@wangke/shared/ui';
export * from '@wangke/shared/icons';
export * from '@wangke/shared/AppShell';
export * from '@wangke/shared/AuthFrame';
export * from '@wangke/shared/ForcePasswordChange';
export * from '@wangke/shared/Markdown';
