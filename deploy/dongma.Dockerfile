# 懂妈答疑服务。平台后端经 POST /ask 调它，接口约定见 docs/RAG实训开发要求.md。
# 人设与课程知识在 dongma/workspace，由编排文件挂进容器，改了不用重建镜像。
FROM node:24-slim
ENV TZ=Asia/Shanghai DONGMA_API_HOST=0.0.0.0 DONGMA_API_PORT=18790 DONGMA_WORKSPACE=/app/workspace
WORKDIR /app
COPY dongma/serve.mjs /app/serve.mjs
EXPOSE 18790
CMD ["node", "/app/serve.mjs"]
