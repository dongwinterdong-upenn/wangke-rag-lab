/**
 * 本应用看到的 @wangke/shared。
 *
 * shared 的 index.ts 把 Player 也导出了，而 Player 顶层 import 了 hls.js。
 * 打包器不能假设一个没标 sideEffects 的包可以随便丢模块，于是整个 hls.js
 * 会被拖进代理后台的首包 —— 实测 +600KB，而这个后台一秒钟视频都不放。
 *
 * 所以这里做一个只含用得到的那三面的桶，vite/tsconfig 把 '@wangke/shared'
 * 指到这个文件。<b>不改 shared 本身</b>：那是三端共用的，为了一端的包体积
 * 去动它的导出面，另外两端会跟着受影响。
 *
 * 需要 Player 的那一天，在这里补一行就行。
 */
export * from '../../../shared/src/api';
export * from '../../../shared/src/csv';
export * from '../../../shared/src/ui';
export * from '../../../shared/src/icons';
export * from '../../../shared/src/AppShell';
export * from '../../../shared/src/AuthFrame';
export * from '../../../shared/src/ForcePasswordChange';
