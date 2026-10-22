# 学员端，代理端，平台后台三端前端，由 nginx 提供，并把 /api/ 转给后端。构建上下文是仓库根目录。
FROM node:24-slim AS build
# npm 官方源在国内很慢，默认走 npmmirror。在国外构建可以传 --build-arg NPM_REGISTRY=https://registry.npmjs.org
ARG NPM_REGISTRY=https://registry.npmmirror.com
RUN npm config set registry "$NPM_REGISTRY" && npm install -g pnpm@10.4.1
WORKDIR /src
COPY web ./web
RUN cd web && pnpm config set registry "$NPM_REGISTRY" && pnpm install --frozen-lockfile && pnpm build

FROM nginx:1.27-alpine
COPY deploy/nginx/wangke.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/web/apps/student/dist /usr/share/nginx/html/study
COPY --from=build /src/web/apps/agent/dist   /usr/share/nginx/html/agent
COPY --from=build /src/web/apps/admin/dist   /usr/share/nginx/html/admin
EXPOSE 80
