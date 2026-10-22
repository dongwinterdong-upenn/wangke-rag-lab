import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { fileURLToPath } from 'node:url';

const shared = (f: string) => fileURLToPath(new URL('../../shared/src/' + f, import.meta.url));

/**
 * 别名直指 shared 的源码，而不是 node_modules 里那条 workspace 软链。
 *
 * 三端并行开发，谁都可能在没跑过 install 的机器上构建，而「构建挂在一条软链上」
 * 这种失败读起来像是代码坏了，其实只是环境没装。指到源码则两种情况都能构建。
 *
 * 只挂具体文件、不挂包入口：包入口会把 Player 连同 hls.js 一起拖进来，
 * 而后台一帧视频都不放 —— 那是 500KB 白运给运营的流量。
 * 更具体的键写在前面：Vite 的对象别名按声明顺序做前缀匹配。
 */
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
    alias: {
      '@wangke/shared/tokens.css': shared('tokens.css'),
      '@wangke/shared/ui': shared('ui.tsx'),
      '@wangke/shared/icons': shared('icons.tsx'),
      '@wangke/shared/api': shared('api.ts'),
      '@wangke/shared/csv': shared('csv.ts'),
      '@wangke/shared/shell': shared('AppShell.tsx'),
      '@wangke/shared/auth-frame': shared('AuthFrame.tsx'),
      '@wangke/shared/force-password': shared('ForcePasswordChange.tsx'),
      '@wangke/shared/Markdown': shared('Markdown.tsx'),
      '@wangke/shared': shared('index.ts'),
    },
  },
  server: {
    port: 5183,
    strictPort: true,
    // shared/ 在应用目录之外，dev 下要显式放行，否则热更新读不到它
    fs: { allow: [fileURLToPath(new URL('../..', import.meta.url))] },
    proxy: { '/api': { target: 'http://127.0.0.1:8801', changeOrigin: true } },
  },
  build: { outDir: 'dist', sourcemap: false },
});
