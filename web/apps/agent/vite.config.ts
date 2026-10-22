import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

/**
 * 代理后台（端口 5182）。
 *
 * 别名指向 shared 的源码而不是它的构建产物：三端共用一套令牌与组件，
 * 中间多一层 build 只会制造"改了 shared 但忘了重新构建"这类查不出来的怪 bug。
 */
const shared = (p: string) => new URL(`../../shared/src/${p}`, import.meta.url).pathname;
const local = (p: string) => new URL(`./src/${p}`, import.meta.url).pathname;

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
    alias: [
      { find: /^@wangke\/shared\/tokens\.css$/, replacement: shared('tokens.css') },
      // 指向本应用的窄桶而不是 shared/index.ts —— 原因见 src/shared.ts 顶部注释
      { find: /^@wangke\/shared$/, replacement: local('shared.ts') },
    ],
    // shared 与本应用必须共用同一个 react 实例，否则 hooks 在运行期直接报 invalid hook call
    dedupe: ['react', 'react-dom'],
  },
  server: {
    port: 5182,
    proxy: {
      '/api': { target: 'http://127.0.0.1:8801', changeOrigin: true },
    },
  },
  build: { outDir: 'dist', target: 'es2022' },
});
