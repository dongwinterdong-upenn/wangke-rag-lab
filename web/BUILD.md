# 构建

```bash
pnpm build        # 生产：三端分别以 /study/ /agent/ /admin/ 为 base
pnpm build:root   # 三端都挂根路径（单端独立域名部署时用）
```

## 为什么 `pnpm build` 不能是 `pnpm -r build`

`vite.config.ts` 里 `base` 取自 `process.env.APP_BASE`，缺省是 `/`。
直接 `pnpm -r build` 三个包都拿不到这个变量，产物里的资源引用会是 `/assets/...`；
挂到子路径之后浏览器去**根**取 JS，拿回来的是落地页的 HTML，
控制台只有一句 `Unexpected token '<'`，页面整片空白 —— 看不出是构建参数少了一个。

这条在 2026-08-13 真的发生过一次：`pnpm -r build` 出来的包白屏，
而三端 tsc 全绿、构建零警告、`ls dist/` 看起来一切正常。
所以基路径必须焊在构建命令里，不能靠发版的人记得 export。
