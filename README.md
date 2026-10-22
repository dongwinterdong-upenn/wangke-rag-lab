# 网课平台 RAG 实训

烛龙智元 AI FDE 工程师就业实训营的 RAG 能力实训题。

仓库里是一套完整可运行的网课平台，包括学员端，代理端，平台后台，以及课程答疑助手懂妈，外加实训营的全部课程文字资料。懂妈现在靠一份写死在提示词里的知识摘要答疑，实训任务是给她加上检索增强生成，让她能从全部课程资料里检索依据再作答，并标明出处。

## 从哪里开始

1. 照 [本机部署教程](docs/本机部署教程.md) 在自己电脑上用 Docker 把平台跑起来，在学员端问懂妈几个问题。
2. 读 [RAG 实训开发要求](docs/RAG实训开发要求.md)，弄清必须遵守的接口，功能要求，评测方法与交付物。
3. 读 [课程资料说明](course/README.md)，了解语料的组织方式与评测集。

## 目录

| 目录 | 内容 |
| --- | --- |
| `dongma` | 懂妈答疑服务。`serve.mjs` 是现有实现，`workspace` 是人设，答疑规矩与知识摘要。**实训主要改这里** |
| `course` | 课程资料：课件，讲义，录屏转写，课程目录，知识点摘要，测评集 |
| `docs` | 本机部署教程与 RAG 实训开发要求 |
| `compose.yaml` `deploy` `.env.example` | Docker 本机部署 |
| `src` | 平台后端，Java 17，Spring Boot 3.3 |
| `web` | 平台三端前端，React 19，Vite |
| `db` | 数据库结构，种子数据与测评题入库脚本 |

## 平台本身

懂妈之外的平台代码一般不用改。想了解时：

- 后端按业务分包：`auth` 登录，`trade` 下单与激活码，`learn` 课程与播放，`exam` 测评与 AI 测评报告，`support` 答疑与懂妈调用，`agent` 代理，`admin` 平台后台。
- 学员提问的处理在 `src/main/java/tech/dragonai/wangke/support/QaService.java`，调懂妈在同目录的 `DongmaClient.java`。
- 学员端答疑页在 `web/apps/student/src/pages/Qa.tsx` 与 `QaDetail.tsx`。
- 平台的单元测试与集成测试要本机装 MySQL 8，实训不需要跑。

## 课程资料的使用范围

`course` 下的课件，讲义，转写与测评题只用于本实训，不要转发或用于其他用途。转写里的人名与个人信息已经隐去。
