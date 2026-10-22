import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { fileURLToPath, URL } from 'node:url';

/**
 * 学员端构建配置。
 *
 * 别名指向 shared 的**源码**而不是构建产物：三端同时在改，中间隔一层 dist
 * 意味着 shared 改完不重新构建就看不到效果 —— 而"看不到效果"的表现是
 * 页面照常工作、只是用的是旧组件，没有任何报错。
 */
const shared = (p: string) => fileURLToPath(new URL(`../../shared/src/${p}`, import.meta.url));

/**
 * 部署基路径。
 *
 * 生产上三端挂在 course.dragonai.tech 的子路径下（/study /agent /admin），
 * 根路径留给已有的落地页。base 不设的话，构建产物里的资源引用全是 /assets/...，
 * 挂到子路径后浏览器会去【根】取 JS —— 拿回来的是落地页的 HTML，
 * 报错是一句 "Unexpected token '<'"，一眼看不出是路径问题。
 *
 * 做成环境变量而不是写死：以后想把平台移到根，改一个变量重新构建即可，
 * 代码一个字不用动。
 */
const BASE = process.env.APP_BASE || '/';

export default defineConfig({
  base: BASE,
  plugins: [react()],
  resolve: {
    // 用数组而不是对象：对象形式是前缀匹配，'@wangke/shared' 会先把
    // '@wangke/shared/tokens.css' 吃掉，结果是拿 TS 解析器去解析一个 CSS 文件。
    // 顺序有意义：更长的路径必须排在前面，否则会被短的先匹配掉。
    alias: [
      { find: '@wangke/shared/tokens.css', replacement: shared('tokens.css') },
      { find: '@wangke/shared/api', replacement: shared('api.ts') },
      { find: '@wangke/shared/ui', replacement: shared('ui.tsx') },
      { find: '@wangke/shared/icons', replacement: shared('icons.tsx') },
      { find: '@wangke/shared/AppShell', replacement: shared('AppShell.tsx') },
      { find: '@wangke/shared/AuthFrame', replacement: shared('AuthFrame.tsx') },
      { find: '@wangke/shared/ForcePasswordChange', replacement: shared('ForcePasswordChange.tsx') },
      { find: '@wangke/shared/Markdown', replacement: shared('Markdown.tsx') },
      { find: '@wangke/shared/Player', replacement: shared('Player.tsx') },
      // '@wangke/shared' 指向本应用的 src/shared.ts（不是 shared 的 index.ts），
      // 原因写在那个文件的头部：barrel 会把 hls.js 拖进首屏包。
      { find: /^@wangke\/shared$/, replacement: fileURLToPath(new URL('./src/shared.ts', import.meta.url)) },
    ],
  },
  server: {
    port: 5181,
    // shared 在本包之外，dev server 默认不允许读它，不放开会 403
    fs: { allow: ['../..'] },
    proxy: {
      '/api': { target: 'http://127.0.0.1:8801', changeOrigin: true },
    },
  },
  // preview 的 proxy 是**另一份配置**，不会继承 server 的。
  // 少写这一段的话，`vite preview` 下所有接口 404，而页面本身能打开 ——
  // 看起来像"后端挂了"，其实只是这里少了三行。
  preview: {
    port: 5181,
    proxy: {
      '/api': { target: 'http://127.0.0.1:8801', changeOrigin: true },
    },
  },
  build: { outDir: 'dist' },
});
