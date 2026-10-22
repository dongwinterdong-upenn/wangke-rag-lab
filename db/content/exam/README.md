# 测评题库

六套测评卷共 400 道题的入库脚本，含标准答案与解析。试卷本身由 `db/seed.sql` 建好，这里只灌题目。

| 文件 | 作用 |
| --- | --- |
| `import_questions.sql` | 插入 400 道题。按 `exam_paper.code` 关联试卷，不写死自增编号。以解析开头的题库编号判重，重复执行不会重复插入。 |
| `backfill_question_meta.sql` | 回填每道题的板块 `topic`，考点 `knowledge_point` 与难度 `difficulty`。AI 测评报告按这三项聚合，可重复执行。 |

在 `db/schema.sql` 与 `db/seed.sql` 之后执行，先导入再回填：

```bash
mysql wangke < db/content/exam/import_questions.sql
mysql wangke < db/content/exam/backfill_question_meta.sql
```

| 试卷 | 名称 | 题数 |
| --- | --- | --- |
| `FDE_MAIN` | 结业综合测评，通过后可申领结业证明 | 100 |
| `FDE_CH1` 到 `FDE_CH4` | 四章各一套练兵卷 | 各 50 |
| `AIGC_OPEN` | AIGC 内容生产能力测试，面向全部注册学员开放 | 100 |

课程卷的 `lesson_id` 等于讲次号加 4，对应 `ops/load_lessons.py` 灌入的讲次编号 5 到 20，`source_ts_sec` 是讲内秒。

题目在 2026-09-03 一次导入生产。2026-10-22 核对过，这两份脚本灌出的 400 道题与生产库逐题一致，比对范围是题型，题干，选项，答案，解析，讲次，出处时间点，板块，考点，难度与审核状态。

两份文件含标准答案，仓库不要公开。
