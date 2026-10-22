import React from 'react';
import { api, hms } from '@wangke/shared/api';
import { PageHead } from '@wangke/shared/shell';
import { Async, Button, Table, Tag, useAsync } from '@wangke/shared/ui';
import type { Col } from '@wangke/shared/ui';
import {
  ActionDialog, Blank, Board, Ctl, Filters, Fold, KV, Note, Pager, Pick, ReasonBox, St, TextIn,
} from '../lib/kit';
import { fmtTime, num } from '../lib/fmt';
import { hasFilter, qs, useQ } from '../lib/q';

/**
 * 试卷与题库（docs/多套试卷规格.md 第三节）。
 *
 * 上半屏一行一卷，下半屏是题库。两块必须在同一页，因为它们之间有一条会咬人的耦合：
 * <b>抽题只抽本卷 review_status='APPROVED' 的题</b>，已审核题数少于卷面题数时，
 * 卷子看起来好好地挂在那儿、学员点「开始考试」当场被拒。运营在两个页面之间是看不出
 * 这件事的 —— 所以「已审核 / 卷面」那一列直接摆在试卷行上，不够就标红。
 *
 * <h3>改规则为什么是插新行而不是改旧行</h3>
 * 规则在 `exam_config` 里按 `effective_from` 版本化，已开考的场次由 `attempt.config_id`
 * 钉住它开考时那一行。改旧行等于把「正在考的这一场」的题数和时长换掉，
 * 学员交卷时会被服务端判成不一致 —— 一场考试就这么废了，而且他重考还要等冷却。
 *
 * <h3>这一页不自己算及格线</h3>
 * 及格分是<b>分数</b>不是百分比（用户拍板：80 分制，100 题卷 80、50 题卷 40）。
 * 页面只把「填的分 ÷ 题数」算给人看并在不等于 80% 时提醒，不替他改数：
 * 章节卷的 40/50 与综合卷的 80/100 都是 80%，而真要出现第三种卷面时，
 * 前端擅自换算出来的那个数会和后端判分口径分家。
 */
export function Exams() {
  const [rev, bump] = React.useReducer((n: number) => n + 1, 0);
  const papersState = useAsync<any>(() => api.get('/api/admin/exam/papers'), [rev]);

  const papers: Paper[] = React.useMemo(() => {
    const raw = papersState.data?.items ?? papersState.data?.papers ?? [];
    return (Array.isArray(raw) ? raw : []).map(normPaper);
  }, [papersState.data]);

  // 响应顶层的两个字段（列表之外）：几张已上架的卷开不了考，以及后端写好的那句话。
  const broken = num(papersState.data?.brokenOnCount);
  const brokenMsg = str(papersState.data?.message);

  const [rulesDlg, setRulesDlg] = React.useState<Paper | null>(null);
  const [statusDlg, setStatusDlg] = React.useState<{ paper: Paper; to: 'ON' | 'OFF' } | null>(null);

  const cols: Col<Paper>[] = [
    { key: 'title', title: '试卷', primary: true, render: p => (
      <div style={{ minWidth: 190 }}>
        <div style={{ fontWeight: 500 }}>{p.title || '（无标题）'}</div>
        <div className="num" style={{ color: 'var(--ink-3)', fontSize: 'var(--t-xs)' }}>
          #{p.id} · {p.code}
        </div>
        {p.subtitle && <div style={{ color: 'var(--ink-3)', fontSize: 'var(--t-xs)',
                                     maxWidth: 320 }}>{p.subtitle}</div>}
      </div>) },
    { key: 'access', title: '准入', render: p => (
      <div style={{ minWidth: 110 }}>
        <Lb dict={ACCESS} value={p.access} />
        <div style={{ color: 'var(--ink-3)', fontSize: 'var(--t-xs)', marginTop: 'var(--s1)' }}>
          {p.access === 'OPEN'
            ? '任何注册学员'
            : p.courseTitle ?? (p.courseId ? `课程 #${p.courseId}` : '未挂课程')}
        </div>
      </div>) },
    // 发证不能只在「是」的时候出标签：这一列要回答的是「学员考过这张卷能不能换结业证明」，
    // 而多数卷是不能的 —— 空着会被读成「没查到」，不是「不发」。
    { key: 'cert', title: '发证', render: p => p.grantsCert
        ? <Tag tone="brand">发结业证明</Tag>
        : <span style={{ color: 'var(--ink-3)', fontSize: 'var(--t-xs)' }}>不发证</span> },
    { key: 'questionCount', title: '题数', numeric: true, render: p => rule(p.questionCount) },
    // 及格线比卷面题数还高时这一列必须自己红：50 题卷填 80 分，
    // 印成灰色的「80 / 50（160%）」看起来只是个大一点的数，而这张卷已经谁都考不过。
    { key: 'passScore', title: '及格', numeric: true, render: p => {
      const impossible = p.questionCount > 0 && p.passScore > p.questionCount;
      return (
        <span className="num" style={{ color: impossible ? 'var(--danger)' : undefined,
                                       fontWeight: impossible ? 600 : undefined }}>
          {p.questionCount > 0 ? `${p.passScore} / ${p.questionCount}` : rule(p.passScore)}
          <span style={{ color: impossible ? 'var(--danger)' : 'var(--ink-3)',
                         fontSize: 'var(--t-xs)' }}>
            {p.questionCount > 0 ? `（${pctText(p.passScore, p.questionCount)}）` : ''}
          </span>
        </span>);
    } },
    { key: 'durationMin', title: '时长', numeric: true,
      render: p => p.durationMin > 0 ? <span className="num">{p.durationMin} 分钟</span> : rule(0) },
    { key: 'maxAttempts', title: '可考次数', numeric: true,
      render: p => p.maxAttempts > 0 ? <span className="num">{p.maxAttempts} 次</span> : rule(0) },
    { key: 'cooldown', title: '冷却', numeric: true, render: p => p.cooldownHours > 0
        ? <span className="num">{p.cooldownHours} 小时</span>
        : <span style={{ color: 'var(--warn)', fontSize: 'var(--t-xs)' }}>不冷却</span> },
    // 进度门槛对开放卷是死的（规格 §2：只在 pct>0 且 paper.course_id 非空时才算）。
    // 照直印一个 80% 会让运营以为它在生效，然后去查「为什么没看课也能考」。
    { key: 'progress', title: '进度门槛', numeric: true, render: p => p.progressPct <= 0
        ? <span style={{ color: 'var(--ink-3)', fontSize: 'var(--t-xs)' }}>不设</span>
        : p.courseId
          ? <span className="num">{p.progressPct}%</span>
          : <span style={{ color: 'var(--warn)', fontSize: 'var(--t-xs)' }}>
              {p.progressPct}%（没挂课程，不生效）</span> },
    { key: 'bank', title: '题库（已审核 / 卷面）', render: p => <BankCell p={p} /> },
    // 「能不能开考」和「上没上架」是两件事，摆在同一格里：一张已上架但开不了考的卷，
    // 学员在测评列表里看得到卡片、点下去吃 409，而后台这一行显示的是绿色的「已上架」。
    // blocker 四种原因（没规则 / 时长<=0 / 及格线高于题量 / 题不够）原样印，
    // 不在前端复述 —— 上架被拒时后端回的就是这一句，两处一字不差才对得上。
    { key: 'status', title: '状态',
      // exam_paper.status 的 DRAFT/ON/OFF 与 course/lesson 逐字同义（草稿 / 已上架 / 已下架），
      // 所以复用 fmt.ts 的 content 词表；含义不同的枚举才必须各译各的，见 fmt.ts 顶部那段。
      render: p => (
        <div style={{ minWidth: 150 }}>
          <St domain="content" value={p.status} />
          {/* 已上架才是红：那是正在发生的事故（学员点得到、点下去被拒）。
              草稿 / 已下架的卷开不了考是建卷途中的正常中间态，一律标红的话，
              真正该看的那几行会淹在一片红里 —— 但它同样要写出来：这样上架会被后端拒。 */}
          {p.blocker && (
            <div style={{ color: p.status === 'ON' ? 'var(--danger)' : 'var(--warn)',
                          fontSize: 'var(--t-xs)', marginTop: 'var(--s1)', maxWidth: 260 }}>
              {p.status === 'ON' ? '已上架，但现在开不了考：' : '现在这样上架会被拒：'}{p.blocker}
            </div>
          )}
        </div>) },
    { key: 'act', title: '操作', align: 'right', render: p => (
      <span style={{ display: 'inline-flex', gap: 'var(--s2)', flexWrap: 'wrap',
                     justifyContent: 'flex-end' }}>
        <Button size="sm" variant="ghost" onClick={() => setRulesDlg(p)}>改规则</Button>
        {p.status === 'ON'
          ? <Button size="sm" variant="quiet"
                    onClick={() => setStatusDlg({ paper: p, to: 'OFF' })}>下架</Button>
          : <Button size="sm" onClick={() => setStatusDlg({ paper: p, to: 'ON' })}>上架</Button>}
      </span>) },
  ];

  return (
    <>
      <PageHead title="试卷与题库"
        desc="一门课可以有好几份卷，还有一份不挂课程、对所有注册学员开放。改规则留版本，正在考的那一场不受影响。" />

      <div style={{ display: 'grid', gap: 'var(--s5)' }}>
        <Board flush title="试卷"
          desc="上架之后学员在自己的测评里看得到。抽题只抽本卷已通过审核的题 —— 题不够就开不了考。">
          <Async state={papersState}>{() => (
            <>
              {/* 顶层的 brokenOnCount 是后端替这一页数好的「几张已上架的卷现在开不了考」。
                  不摆出来的话，运营得自己一行行扫红字才知道出事了 —— 而它恰恰是
                  这一页最该第一眼看到的东西：那几张卷学员点得到、点下去被拒。 */}
              {broken > 0 && (
                <Note tone="danger" title={`有 ${broken} 张已上架的卷现在开不了考`}>
                  {brokenMsg ?? '学员在测评列表里看得到它们，点下去会被拒。'}<br />
                  后端说的 blocker 就是下面「状态」那一列的红字，每张卷的原因写在各自那一行。
                </Note>
              )}
              <Table cols={cols} rows={papers} rowKey={(p, i) => p.id || `row-${i}`}
                     empty={<Blank filtered={false} thing="试卷" />} />
            </>
          )}</Async>
        </Board>

        <Note title="两条容易被忘掉的口径">
          <b>及格分是分数不是百分比</b>：100 题卷填 80、50 题卷填 40，两个都是 80%。<br />
          <b>开放卷不发证</b>：只有 grants_certificate=1 的卷考过了才能换结业证明，
          这条闸门在数据库触发器里，后台改不掉也绕不过 —— 章节卷考满分也换不出证。
        </Note>

        <Bank papers={papers} rev={rev} onReviewed={bump} />
      </div>

      {rulesDlg && (
        <RulesDialog paper={rulesDlg} onClose={() => setRulesDlg(null)} onDone={bump} />
      )}
      {statusDlg && (
        <StatusDialog paper={statusDlg.paper} to={statusDlg.to}
                      onClose={() => setStatusDlg(null)} onDone={bump} />
      )}
    </>
  );
}

/** 规则字段缺值时不印 0：0 题、0 分钟是「这张卷没配规则」，不是「配成了 0」。 */
function rule(v: number) {
  return v > 0 ? <span className="num">{v}</span>
               : <span style={{ color: 'var(--danger)', fontSize: 'var(--t-xs)' }}>未配</span>;
}

function pctText(score: number, count: number): string {
  if (count <= 0) return '—';
  return `${Math.round((score / count) * 1000) / 10}%`;
}

/**
 * 题量对照。
 *
 * 判据钉在<b>屏幕上这两个数</b>上（已审核 vs 卷面题数），不问后端要一个 enough 布尔：
 * 后端算的和页面显示的一旦不是同一对数，运营看到的是「86 / 100」配一个绿色的「够了」，
 * 而两边都不报错。
 *
 * <p>后端的 startable / blocker 现在也收了（印在「状态」那一列），但<b>没有取代这里</b>：
 * 这一格算的是这两个数，后端算的是它自己那份统计。两边对不上时下面那句话会喊出来 ——
 * 「屏幕上的数」与「后端的判据」分家，正是这一列当初不肯要一个布尔的原因。
 */
function BankCell({ p }: { p: Paper }) {
  const short = p.questionCount > 0 && p.approved < p.questionCount;
  // 只有「这两个数说不够，而后端说能开考」才算分家。反过来（后端说不能考）不算：
  // blocker 还有另外三条原因，与题量无关。
  const disagree = short && p.startable === true;
  return (
    <div style={{ minWidth: 150 }}>
      <span className="num" style={{ color: short ? 'var(--danger)' : undefined,
                                     fontWeight: short ? 600 : undefined }}>
        {p.approved} / {p.questionCount}
      </span>
      {short && (
        <div style={{ color: 'var(--danger)', fontSize: 'var(--t-xs)' }}>
          题量不足，学员点开考会被拒
        </div>
      )}
      {disagree && (
        <div style={{ color: 'var(--danger)', fontSize: 'var(--t-xs)' }}>
          可后端说这张卷能开考 —— 两边口径不一致，别照这一行下判断，先去查清楚
        </div>
      )}
      <div style={{ color: 'var(--ink-3)', fontSize: 'var(--t-xs)' }}>
        待审 {p.pending} · 库内共 {p.bankTotal}
      </div>
    </div>
  );
}

/* ================================================================ 题库 */

function Bank({ papers, rev, onReviewed }: {
  papers: Paper[]; rev: number; onReviewed: () => void;
}) {
  const { sp, get, int, set, reset } = useQ();
  const paperId = get('paper'), status = get('status'), type = get('type'), q = get('q');
  const page = int('page', 1), size = int('size', 20);
  // 这个 q 是「关键词」，不是题目 —— 审题弹框那一份存 row，两者别混
  const [dlg, setDlg] = React.useState<{ row: Q; to: 'APPROVED' | 'REJECTED' } | null>(null);

  const list = useAsync<any>(
    () => api.get(`/api/admin/exam/questions${qs({ paperId, status, type, q, page, size })}`),
    [paperId, status, type, q, page, size, rev]);

  const rows: Q[] = React.useMemo(() => {
    const raw = list.data?.items ?? list.data?.questions ?? [];
    return (Array.isArray(raw) ? raw : []).map(normQuestion);
  }, [list.data]);

  // 总数与页大小都优先用后端回的：后端对 size 有上限（AdminQuery.MAX_SIZE=200），
  // 它把 size 夹小之后前端还按自己那个数算页数，翻到后面就是一片空白 ——
  // 而空列表在运营眼里等同于「没有这批题」。
  const total = num(list.data?.total, rows.length);
  const pageSize = Math.max(1, num(list.data?.size, size));
  const filtered = hasFilter(sp);

  // ★ 第一项是「未挂卷」，不是某张卷：后端 GET /questions 专门收 paperId=none
  // （javadoc：迁移之后老题的 paper_id 全是 NULL，没有这个入口那批题在后台整片消失）。
  // 行里那句红色的「未挂卷，抽不到」只说明单行，四百多道题里要把它们挑出来集中处理，
  // 只能靠这个筛选项。withUnknown 容得下这个非数字值。
  const paperOpts = withUnknown(
    [{ value: 'none', text: '未挂卷（抽不到）' },
     ...papers.map(p => ({ value: p.id, text: `${p.title}（${p.code}）` }))],
    paperId, id => `试卷 #${id}`);

  const cols: Col<Q>[] = [
    { key: 'stem', title: '题干', primary: true, render: r => (
      <div style={{ minWidth: 240, maxWidth: 560 }}>
        <span className="num" style={{ color: 'var(--ink-3)', fontSize: 'var(--t-xs)' }}>#{r.id}　</span>
        {r.stem || <span style={{ color: 'var(--danger)' }}>（题干为空）</span>}
      </div>) },
    { key: 'type', title: '题型', render: r => <Lb dict={QTYPE} value={r.type} /> },
    // 没挂卷的题在新抽题口径下（WHERE paper_id=? AND review_status='APPROVED'）
    // 永远抽不到。它审过也没用，所以这里要写明白，不能只显示一个空。
    { key: 'paper', title: '所属试卷', render: r => r.paperId
        ? <span style={{ fontSize: 'var(--t-sm)' }}>
            {r.paperTitle ?? `试卷 #${r.paperId}`}
            {r.paperCode && <span className="num" style={{ color: 'var(--ink-3)',
              fontSize: 'var(--t-xs)' }}> · {r.paperCode}</span>}
          </span>
        : <span style={{ color: 'var(--danger)', fontSize: 'var(--t-xs)' }}>未挂卷，抽不到</span> },
    { key: 'status', title: '审核', render: r => <Lb dict={REVIEW} value={r.status} /> },
    { key: 'detail', title: '选项 / 答案 / 解析', render: r => <QDetail q={r} /> },
    { key: 'act', title: '操作', align: 'right', render: r => (
      <span style={{ display: 'inline-flex', gap: 'var(--s2)', flexWrap: 'wrap',
                     justifyContent: 'flex-end' }}>
        {/* 已经是这个状态的按钮禁掉：紧挨着的「审核」列就写着它现在是什么，
            所以这里禁掉不会变成一个没人知道原因的灰按钮。 */}
        <Button size="sm" variant="ghost" disabled={r.status === 'APPROVED'}
                onClick={() => setDlg({ row: r, to: 'APPROVED' })}>通过</Button>
        <Button size="sm" variant="quiet" disabled={r.status === 'REJECTED'}
                onClick={() => setDlg({ row: r, to: 'REJECTED' })}>驳回</Button>
      </span>) },
  ];

  return (
    <>
      <Filters active={filtered} onReset={() => reset(['size'])}>
        <Ctl label="试卷" width={230} grow hint="只有本卷已通过的题会被抽进本卷的考题">
          <Pick value={paperId} onChange={v => set({ paper: v })} allText="全部试卷"
                options={paperOpts} />
        </Ctl>
        <Ctl label="审核状态" width={140}>
          <Pick value={status} onChange={v => set({ status: v })}
                options={withUnknown(dictOpts(REVIEW), status, v => v)} />
        </Ctl>
        <Ctl label="题型" width={120}>
          <Pick value={type} onChange={v => set({ type: v })}
                options={withUnknown(dictOpts(QTYPE), type, v => v)} />
        </Ctl>
        {/* key 挂在关键词上：不挂的话「清空筛选」只清了 URL 和列表，输入框里那几个字
            还留在屏幕上 —— 于是页面同时显示着「按 xx 筛」和一份全量结果。 */}
        <Ctl label="关键词" width={220} grow hint="题干 / 解析里的字，回车搜索">
          <TextIn key={`q-${q}`} defaultValue={q} placeholder="回车搜索"
                  onKeyDown={e => { if (e.key === 'Enter') set({ q: e.currentTarget.value.trim() }); }}
                  onBlur={e => { if (e.currentTarget.value.trim() !== q)
                                   set({ q: e.currentTarget.value.trim() }); }} />
        </Ctl>
      </Filters>

      <Board flush title={`题库${list.data ? ` · ${total} 题` : ''}`}
        desc="审核是唯一的进卷闸门：只有「已通过」的题会被抽进考卷。答案与解析只有平台管理员看得到。">
        <Async state={list}>{() => (
          <>
            <Table cols={cols} rows={rows} rowKey={(r, i) => r.id || `q-${i}`}
                   empty={<Blank filtered={filtered} thing="题目" onClear={() => reset(['size'])} />} />
            {/* onSize 不带 page：改完每页条数还停在第 7 页，看到的多半是一屏空白，
                而空列表在运营眼里等同于「没有这批题」。useQ.set 默认会把 page 抹掉。 */}
            <Pager total={total} page={page} size={pageSize}
                   onPage={p => set({ page: p }, true)} onSize={s => set({ size: s })} />
          </>
        )}</Async>
      </Board>

      {dlg && (
        <ReviewDialog q={dlg.row} to={dlg.to} onClose={() => setDlg(null)} onDone={onReviewed} />
      )}
    </>
  );
}

/** 一道题的全貌。列表里默认收起来 —— 展开的话一屏放不下三道题，而审题要来回比对。 */
function QDetail({ q }: { q: Q }) {
  const ans = q.answer.toUpperCase();
  // 收起来的时候也要认得出坏题：审题人是一行行扫过去的，
  // 选项坏没坏藏在折叠层里的话，这一行看起来和别的题一模一样。
  return (
    <Fold summary={q.optionsBroken
      ? <span style={{ color: 'var(--danger)' }}>选项读不出来 · 展开看原文 / 答案 / 解析</span>
      : '展开看选项 / 答案 / 解析 / 出处'}>
      <div style={{ display: 'grid', gap: 'var(--s3)', minWidth: 260, maxWidth: 620 }}>
        {/* 选项坏掉的题必须自己显形：它既不是「有选项」也不是「判断题没选项」，
            而是一道上了卷就会显示成空选项的题。原文照印 —— 审题人要照着它去改。 */}
        {q.optionsBroken
          ? <div style={{ background: 'var(--danger-soft)', borderLeft: '2px solid var(--danger)',
                          padding: 'var(--s2) var(--s3)', fontSize: 'var(--t-sm)',
                          lineHeight: 'var(--lh-body)' }}>
              <div style={{ color: 'var(--danger)', fontWeight: 600 }}>
                这道题的选项读不出来，别按「通过」
              </div>
              <div style={{ color: 'var(--ink-2)' }}>{q.optionsBroken.why}</div>
              <div className="num" style={{ color: 'var(--ink-2)', marginTop: 'var(--s1)',
                                            maxHeight: 140, overflow: 'auto',
                                            whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>
                {q.optionsBroken.raw || '（原文是空的）'}
              </div>
            </div>
          : q.options
          ? <ul style={{ listStyle: 'none', margin: 0, padding: 0, display: 'grid', gap: 4 }}>
              {q.options.map((o, i) => {
                // 多选的答案是 "AC" 这样的串，所以按字符包含判，不按相等判。
                const right = !!o.key && ans.includes(o.key.toUpperCase());
                return (
                  <li key={`${o.key}-${i}`} style={{
                    fontSize: 'var(--t-sm)', lineHeight: 'var(--lh-body)',
                    color: right ? 'var(--ok)' : 'var(--ink-2)', fontWeight: right ? 600 : undefined,
                  }}>
                    <b className="num">{o.key}</b>　{o.text}{right ? '　✓' : ''}
                  </li>
                );
              })}
            </ul>
          : <div style={{ color: 'var(--ink-3)', fontSize: 'var(--t-xs)' }}>
              这道题没有选项（判断题的答案直接写在下面）
            </div>}
        <KV cols={1} items={[
          ['正确答案', q.answer ? <b className="num">{q.answer}</b> : null],
          ['解析', q.explanation],
          ['出处', q.lessonId
            ? <>{q.lessonTitle ?? `讲次 #${q.lessonId}`}
                {q.sourceTsSec !== null && <span className="num"> · {hms(q.sourceTsSec)}</span>}</>
            : null],
          ['题目来源', q.origin ? (ORIGIN[q.origin] ?? q.origin) : null],
          ['审核时间', q.reviewedAt
            ? <>{fmtTime(q.reviewedAt, true)}{q.reviewedBy ? ` · 操作人 #${q.reviewedBy}` : ''}</>
            : null],
          ['入库时间', q.createdAt ? fmtTime(q.createdAt) : null],
        ]} />
      </div>
    </Fold>
  );
}

/* ================================================================ 改规则 */

function RulesDialog({ paper, onClose, onDone }: {
  paper: Paper; onClose: () => void; onDone: () => void;
}) {
  const [f, setF] = React.useState({
    questionCount: String(paper.questionCount || ''),
    passScore: String(paper.passScore || ''),
    durationMin: String(paper.durationMin || ''),
    maxAttempts: String(paper.maxAttempts || ''),
    cooldownHours: String(paper.cooldownHours ?? ''),
    progressPct: String(paper.progressPct ?? ''),
    shuffle: String(paper.shuffle === 0 ? 0 : 1),
  });
  const [reason, setReason] = React.useState('');
  const digits = (v: string) => v.replace(/\D/g, '');

  const qc = Number(f.questionCount || 0), ps = Number(f.passScore || 0);
  const dur = Number(f.durationMin || 0), att = Number(f.maxAttempts || 0);
  const cd = Number(f.cooldownHours || 0), pct = Number(f.progressPct || 0);
  const ratio = qc > 0 ? Math.round((ps / qc) * 1000) / 10 : null;

  const bad = !(qc > 0) || !(ps > 0) || ps > qc || !(dur > 0) || !(att >= 1) || pct > 100;
  const shortAfter = qc > 0 && paper.approved < qc;
  const shuffleOn = f.shuffle !== '0';

  /**
   * 这次保存到底改没改东西 —— 拿框里的七个数和这张卷现行的七个数比。
   *
   * <p>后端在「七项和现行的一模一样」时回 200 + changed:false + 「没有任何改动」，
   * 而 ActionDialog 把 changed:false 当正常态（info 色 toast、关框、刷列表）。
   * 那对「运营点开框什么都没动就保存」是对的，但如果他<b>确实改了数</b>还收到这一句，
   * 那只可能是请求体的形状后端没认出来 —— 曾经就是这样：七项平铺发出去，
   * Jackson 静默丢掉，接口一路 200，而 exam_config 一行没插、审计一条没写。
   * 所以这两种 changed:false 必须分开说，见 onConfirm。
   */
  const localDiff = qc !== paper.questionCount || ps !== paper.passScore
    || dur !== paper.durationMin || att !== paper.maxAttempts
    || cd !== paper.cooldownHours || pct !== paper.progressPct
    || shuffleOn !== (paper.shuffle !== 0);

  return (
    <ActionDialog open onClose={onClose} verb="保存" width={640} onDone={onDone}
      title={<>改规则 · {paper.title}</>}
      disabled={bad || !reason.trim()}
      consequences={<>
        改规则是<b>插一行新的考试规则</b>，旧的那行留着不动。<br />
        <b>正在考、还没交卷的场次不受影响</b> —— 每一场在开考那一刻就钉住了它用的那一行规则，
        否则学员考到一半题数变了，交卷会被服务端判成不一致，这一次考试就废了。<br />
        新规则从保存那一刻起，对<b>之后新开的场次</b>生效。
      </>}
      onConfirm={async () => {
        // ★ 七项规则必须裹在 rules 里，不能平铺。
        // AdminExamController.PaperReq 收的是 (title, subtitle, sortNo, status,
        // RulesReq rules, reason)，rules 是一个嵌套的盒子；而 Spring Boot 默认关着
        // FAIL_ON_UNKNOWN_PROPERTIES，平铺的七个键会被 Jackson 静默丢掉 ——
        // r.rules() 于是是 null，rulesChanged=false，接口回 200「没有任何改动」：
        // 运营把时长从 45 改成 60、点保存、看到一句像「我没动过」的提示，
        // 而 exam_config 一行没插、audit_log 一条没写。
        // 键名逐个对着 RulesReq 那个 record 写；shuffleOptions 在后端是 Boolean，
        // 给 true/false，不给 0/1。
        const r: any = await api.put(`/api/admin/exam/papers/${paper.id}`, {
          rules: {
            questionCount: qc, passScore: ps, durationMin: dur, maxAttempts: att,
            retryCooldownHours: cd, requireProgressPct: pct, shuffleOptions: shuffleOn,
          },
          reason: reason.trim(),
        });
        // 改了数却被告知「没有任何改动」= 后端没认出这次请求。这一句必须是红的、
        // 而且框不许关：安静地关掉，运营会以为保存成功了，直到有人问起为什么规则没变。
        if (r?.changed === false && localDiff)
          throw new Error('后端说这次「没有任何改动」，可框里的七项和这张卷现行的并不一样'
            + ' —— 这次保存没有落库，exam_config 里没有新行。'
            + '要么请求体的形状后端没认出来，要么这一页的数据已经过期（别人刚改过同一张卷）。'
            + '先刷新列表看一眼现在的规则；还是这样，就把这句话给后端看。');
        return r;
      }}>
      <div style={{ display: 'grid', gap: 'var(--s4)' }}>
        <div style={{ fontSize: 'var(--t-sm)', color: 'var(--ink-2)' }}>
          <span className="num">{paper.code}</span>　·　<Lb dict={ACCESS} value={paper.access} />
          {paper.grantsCert && <>　·　<Tag tone="brand">发结业证明</Tag></>}
          {paper.configId && (
            <div style={{ color: 'var(--ink-3)', fontSize: 'var(--t-xs)', marginTop: 'var(--s1)' }}>
              当前生效的规则行 #{paper.configId}
              {paper.effectiveFrom ? `，生效于 ${fmtTime(paper.effectiveFrom, true)}` : ''}
            </div>
          )}
        </div>

        <div style={{ display: 'flex', gap: 'var(--s4)', flexWrap: 'wrap' }}>
          <Ctl label="卷面题数" width={130} hint="一场考试抽多少题">
            <TextIn value={f.questionCount} inputMode="numeric"
                    onChange={e => setF({ ...f, questionCount: digits(e.target.value) })} />
          </Ctl>
          <Ctl label="及格分" width={130} hint="填分数，不是百分比">
            <TextIn value={f.passScore} inputMode="numeric"
                    onChange={e => setF({ ...f, passScore: digits(e.target.value) })} />
          </Ctl>
          <Ctl label="时长（分钟）" width={140} hint="情境题读题就要时间，别按旧卷的 45 分钟给">
            <TextIn value={f.durationMin} inputMode="numeric"
                    onChange={e => setF({ ...f, durationMin: digits(e.target.value) })} />
          </Ctl>
          <Ctl label="可考次数" width={120} hint="总共能考几次">
            <TextIn value={f.maxAttempts} inputMode="numeric"
                    onChange={e => setF({ ...f, maxAttempts: digits(e.target.value) })} />
          </Ctl>
          <Ctl label="冷却（小时）" width={130} hint="两次考试之间要等多久；填 0 就是不限，连点重考">
            <TextIn value={f.cooldownHours} inputMode="numeric"
                    onChange={e => setF({ ...f, cooldownHours: digits(e.target.value) })} />
          </Ctl>
          <Ctl label="进度门槛（%）" width={140} hint="看完多少课才能考；0 是不设">
            <TextIn value={f.progressPct} inputMode="numeric"
                    onChange={e => setF({ ...f, progressPct: digits(e.target.value) })} />
          </Ctl>
          <Ctl label="选项乱序" width={180} hint="乱序之后「答案是 B」这种口口相传不再有用">
            <Pick value={f.shuffle} all={false} onChange={v => setF({ ...f, shuffle: v })}
                  options={[{ value: '1', text: '乱序（推荐）' }, { value: '0', text: '按原顺序' }]} />
          </Ctl>
        </div>

        {/* 判据钉在人正在填的那两个数上，不是钉在「这张卷现在是什么」上：
            填错的那一刻就要看得见，而不是保存之后回列表才发现。 */}
        {ratio !== null && (
          <Note tone={ratio === 80 ? 'ok' : 'warn'}
                title={ratio === 80 ? '及格线 80%，与拍板一致' : `及格线是 ${ratio}%，不是 80%`}>
            {ps} 分 ÷ {qc} 题 = <b>{ratio}%</b>。
            {ratio === 80
              ? ' 100 题卷 80 分、50 题卷 40 分，都是这条线。'
              : ' 用户拍板的是 80 分制（100 题卷填 80、50 题卷填 40）。确认这是你要的再保存。'}
          </Note>
        )}
        {ps > qc && (
          <Note tone="danger" title="及格分比卷面题数还高">
            这张卷谁都考不过 —— 满分就是 {qc || '—'} 分。
          </Note>
        )}
        {shortAfter && (
          <Note tone="danger" title="题库里够格的题不够这个卷面题数">
            本卷已通过审核的题只有 <b className="num">{paper.approved}</b> 道，卷面要 <b className="num">{qc}</b> 道。
            {/* 后端对已上架的卷改规则会重跑上架闸并直接 409，被拒的是这次保存本身，
                不是学员那一下。两种情形的后果完全不同，不能共用一句话。 */}
            {paper.status === 'ON' ? (
              <> 这张卷是上架状态，<b>改成这套规则会被后端拒绝（409）</b>：
                先去下面的题库把待审的 {paper.pending} 道审掉、把卷面题数改到已审核题数以内，
                或者先把卷下架再改规则。</>
            ) : (
              <> 抽题只抽已通过的题，这套规则存得下去，但<b>学员点「开始考试」会被当场拒绝</b>。
                先去下面的题库把待审的 {paper.pending} 道审掉，或者把卷面题数改小。</>
            )}
          </Note>
        )}
        {pct > 0 && !paper.courseId && (
          <Note tone="warn" title="这张卷没挂课程，进度门槛不会生效">
            进度是按课程算的，开放卷没有课程可算。这个数填了也不起作用，别拿它当闸门。
          </Note>
        )}
        {cd === 0 && (
          <Note tone="warn" title="冷却填了 0">
            考完立刻能重考。次数放宽 + 不冷却 = 学员靠反复刷把题目背下来，这张卷的分数就不再说明什么。
          </Note>
        )}

        <ReasonBox value={reason} onChange={setReason} label="改动原因"
                   hint="进审计日志。事后有人问「这张卷的时长什么时候从 45 改成 60 的、为什么」，只有这一句能答。" />
      </div>
    </ActionDialog>
  );
}

/* ================================================================ 上下架 */

function StatusDialog({ paper, to, onClose, onDone }: {
  paper: Paper; to: 'ON' | 'OFF'; onClose: () => void; onDone: () => void;
}) {
  const short = paper.questionCount > 0 && paper.approved < paper.questionCount;
  const noRules = paper.questionCount <= 0;

  return (
    <ActionDialog open onClose={onClose} verb={to === 'ON' ? '上架' : '下架'}
      tone={to === 'ON' ? 'brand' : 'danger'} width={600} onDone={onDone}
      title={<>{to === 'ON' ? '上架' : '下架'}这张卷 · {paper.title}</>}
      consequences={to === 'ON'
        ? <>
            上架之后这张卷出现在符合准入条件的学员的测评页里，他们可以开考。<br />
            {paper.access === 'OPEN'
              ? <><b>这是开放卷</b>：不需要买课、不需要激活，<b>任何注册账号</b>都能看到并参加。</>
              : <>准入是<b>已购课</b>：只有对
                  {paper.courseTitle ? `「${paper.courseTitle}」` : `课程 #${paper.courseId ?? '—'}`}
                  有生效权益（未到期）的学员看得到。</>}<br />
            {paper.grantsCert
              ? <>考过这张卷<b>可以</b>换结业证明。</>
              : <>考过这张卷<b>换不出</b>结业证明 —— 这条闸门在数据库里，改不掉也绕不过。</>}
          </>
        : <>
            下架之后这张卷从学员的测评列表里消失，<b>不能再开新的考试</b>。<br />
            已经考过的记录、已经发出的结业证明<b>都还在</b>，下架不撤销任何人已经拿到的东西。<br />
            题库不受影响，下架期间照样能审题 —— 审好了再上架，学员看到的就是补齐的卷。<br />
            <b>正在考、还没交卷的那几场怎么算，由服务端的闸门说了算</b>，这一页管不着。
            要停一张正在被人考的卷，先确认没有进行中的场次。
          </>}
      onConfirm={() => api.put(`/api/admin/exam/papers/${paper.id}`, { status: to })}>
      {/* ★ 上架这一步是过闸的，不是「后果由学员承担」。
          AdminExamController.updatePaper 对 status=ON 且当前不是 ON 的请求强制跑一遍
          readyBlocker，不过就直接 409 —— 所以这里的话术只能是「后端会拒绝这次上架」。
          写成「按钮没禁掉，学员点开考会被拒」的话，运营读完说明去点确认，
          收到的是一句和刚读过的完全相反的报错。
          blocker 原样印：后端拒绝时回的就是这一句，两处一字不差才对得上。 */}
      {to === 'ON' && paper.blocker && (
        <Note tone="danger" title="现在上架会被后端拒绝">
          {paper.blocker}<br />
          出路只有两条：把题审够（本卷待审 <b className="num">{paper.pending}</b> 道），
          或者「改规则」把卷面题数改到已审核题数以内、把时长和及格分改成能开考的值。
          <br />
          按钮不禁：能不能上架由后端那一份判据说了算。前端多算一遍就会多一种口径，
          真出现两边不一致时，被前端拦下的那次连报错都看不到。
        </Note>
      )}
      {/* 下面两条是后端没给 blocker 时的兜底（老后端 / 字段缺席），话术与上面一致。 */}
      {to === 'ON' && !paper.blocker && noRules && (
        <Note tone="danger" title="这张卷还没有规则，上架会被后端拒绝">
          题数、时长、及格分都取不到，学员开考时服务端拿不到规则。先「改规则」再上架。
        </Note>
      )}
      {to === 'ON' && !paper.blocker && short && (
        <Note tone="danger" title="题不够，上架会被后端拒绝">
          本卷已通过审核的题 <b className="num">{paper.approved}</b> 道，卷面要 <b className="num">{paper.questionCount}</b> 道。
          抽题只抽已通过的题，凑不满卷面题数这张卷开不了考 —— 后端对这种上架直接回 409。
          <br />
          先把待审的 {paper.pending} 道审掉，或者把卷面题数改到已审核题数以内，再上架。
        </Note>
      )}
    </ActionDialog>
  );
}

/* ================================================================ 审题 */

function ReviewDialog({ q, to, onClose, onDone }: {
  q: Q; to: 'APPROVED' | 'REJECTED'; onClose: () => void; onDone: () => void;
}) {
  const [reason, setReason] = React.useState('');
  const approve = to === 'APPROVED';

  return (
    <ActionDialog open onClose={onClose} verb={approve ? '通过' : '驳回'}
      tone={approve ? 'brand' : 'danger'} width={620} onDone={onDone}
      title={<>{approve ? '通过这道题' : '驳回这道题'} · #{q.id}</>}
      disabled={!approve && !reason.trim()}
      consequences={approve
        ? <>
            通过之后，这道题<b>会被抽进</b>
            {q.paperTitle ? `「${q.paperTitle}」` : '它所属那张卷'}的考题里。<br />
            题面、答案、解析都是照现在这个样子发给学员的 —— 答案填错了，
            所有抽到它的学员都会被判错，而且不会有任何报错。<br />
            <b>已经开考的场次不受影响</b>：每一场的题目在开考那一刻就写进了抽题快照。
          </>
        : <>
            驳回之后这道题<b>不再进任何考卷</b>。<br />
            <b>已经抽到它的场次照旧按原题判分</b> —— 抽题快照在开考那一刻就定死了，
            驳回不会把学员正在答的题抽走。<br />
            驳回不是删除，改好之后还能重新审。
          </>}
      onConfirm={() => api.post(`/api/admin/exam/questions/${q.id}/review`,
        // 请求体的 status 是规格钉死的字段名。reason 是额外附带的，后端不收也不会出错
        // （Spring Boot 默认忽略多余字段），但驳回的理由必须有地方写。
        approve ? { status: 'APPROVED' } : { status: 'REJECTED', reason: reason.trim() })}>
      <div style={{ display: 'grid', gap: 'var(--s4)' }}>
        <div style={{ fontSize: 'var(--t-sm)', lineHeight: 'var(--lh-body)' }}>
          <div style={{ display: 'flex', gap: 'var(--s2)', alignItems: 'center', flexWrap: 'wrap',
                        color: 'var(--ink-3)', fontSize: 'var(--t-xs)', marginBottom: 'var(--s2)' }}>
            <Lb dict={QTYPE} value={q.type} />
            <span>{q.paperTitle ?? (q.paperId ? `试卷 #${q.paperId}` : '未挂卷')}</span>
            <span>· 当前</span>
            <Lb dict={REVIEW} value={q.status} />
          </div>
          {q.stem}
        </div>
        <QDetail q={q} />
        {/* 不禁用「通过」：审题人手上就有原文，改库的人也可能是他自己，
            这一步只是不许他在<b>不知情</b>的情况下按下去。 */}
        {approve && q.optionsBroken && (
          <Note tone="danger" title="这道题的选项现在是坏的，通过它等于把空选项发给学员">
            {q.optionsBroken.why}<br />
            通过之后它就会被抽进考卷，而学员看到的是一道<b>没有选项的选择题</b> ——
            他只能瞎选，判分照常进行，没有任何一处会报错。
            先把 question.options 这一列修好，再回来通过它。
          </Note>
        )}
        {approve && !q.paperId && (
          <Note tone="warn" title="这道题没挂在任何试卷上">
            抽题是按「本卷 + 已通过」抽的。没有 paper_id 的题审过也永远抽不到，
            通过它不会让任何一张卷的题量变够。
          </Note>
        )}
        {!approve && (
          <ReasonBox value={reason} onChange={setReason} label="驳回原因"
                     hint="写清楚哪里不对（题干有歧义 / 答案错 / 与课程内容对不上）。出题的人要照这句改。" />
        )}
      </div>
    </ActionDialog>
  );
}

/* ================================================================ 词表 */

type Tn = 'neutral' | 'brand' | 'ok' | 'warn' | 'danger';

/**
 * 这几张词表留在本页，不进 lib/fmt.ts —— 理由就是 fmt.ts 顶部那段：状态词按「哪张表」译。
 * access / review_status / origin 都只有这一页在读，摊进公共词表反而多给了别的页面一次误用的机会。
 * 试卷本身的 DRAFT/ON/OFF 与 course/lesson 逐字同义，那一列继续用 St domain="content"。
 */
const ACCESS: Record<string, [string, Tn]> = {
  ENROLLED: ['需买课', 'neutral'],
  OPEN: ['开放卷', 'brand'],
};

const REVIEW: Record<string, [string, Tn]> = {
  DRAFT: ['草稿', 'neutral'],
  PENDING: ['待审核', 'warn'],
  APPROVED: ['已通过', 'ok'],
  REJECTED: ['已驳回', 'danger'],
};

const QTYPE: Record<string, [string, Tn]> = {
  SINGLE: ['单选', 'neutral'],
  MULTI: ['多选', 'neutral'],
  JUDGE: ['判断', 'neutral'],
};

const ORIGIN: Record<string, string> = {
  AI_GENERATED: 'AI 生成',
  MANUAL_UPLOAD: '人工上传',
};

/** 词表里没有的值原样印出来，绝不吞掉：后端加了新枚举时要看得见，不是显示成空白。 */
function Lb({ dict, value }: { dict: Record<string, [string, Tn]>; value?: string | null }) {
  if (!value) return <span style={{ color: 'var(--ink-3)' }}>—</span>;
  const hit = dict[value];
  return <Tag tone={hit ? hit[1] : 'neutral'}>{hit ? hit[0] : value}</Tag>;
}

function dictOpts(dict: Record<string, [string, Tn]>): { value: string; text: string }[] {
  return Object.entries(dict).map(([value, [text]]) => ({ value, text }));
}

/**
 * URL 里带着一个选项列表里没有的值时，补一个能显示出来的选项。
 *
 * <p>kit.tsx 的 Pick 只在 `all=false` 时替不认识的值摆占位项；筛选框都是 `all=true`，
 * 那个 value="" 的「全部」永远匹配得上，于是浏览器<b>回落到显示第 0 项</b>：
 * 框上写着「全部试卷」，而请求里带着 paperId=7。运营看到的是一份少了一大半的题库，
 * 而屏幕上没有任何东西说明为什么 —— 这正是 Pick 那段注释治的同一种病，只是换了一侧。
 * （试卷下拉在 papers 还没加载完时也会撞上，所以这个补丁不是只为脏 URL 准备的。）
 */
function withUnknown(opts: { value: string; text: string }[], value: string,
                     text: (v: string) => string): { value: string; text: string }[] {
  if (!value || opts.some(o => o.value === value)) return opts;
  return [{ value, text: text(value) }, ...opts];
}

/* ================================================================ 后端契约 */

/**
 * 规格（docs/多套试卷规格.md §2）钉的是四个 URL，没钉响应体的键名，而后端是并行在写的。
 * 本仓两种键名都真实存在：AdminCourseController 的 courseView() 出 camelCase，
 * 而 /courses/{id}/chapters 是 SELECT * 原样出网（snake_case）——
 * Courses.tsx 里那句 `r.sort_no ?? r.sortNo` 就是这么来的。
 *
 * 所以「读后端」全部收在 normPaper / normQuestion 这两个函数里，页面其余部分只认
 * Paper / Q 两个类型。键名对不上时只有这里要改，而不是满页去猜；
 * 规则和统计既可能平铺在行上、也可能装在 rules / stats 这样的盒子里，两种都收。
 */

type Paper = {
  id: string; code: string; title: string; subtitle: string | null;
  courseId: string | null; courseTitle: string | null;
  access: string; grantsCert: boolean; status: string;
  configId: string | null; effectiveFrom: unknown;
  /** 以下七项来自 exam_config 当前生效的那一行 */
  questionCount: number; passScore: number; durationMin: number;
  maxAttempts: number; cooldownHours: number; progressPct: number; shuffle: number;
  /** 题库统计：已通过 / 待审 / 库内总数 */
  approved: number; pending: number; bankTotal: number;
  /**
   * 后端算的「这张卷现在开不开得了考」。
   *
   * <p>AdminExamService.readyBlocker 有<b>四条</b>：没有规则 / 时长 <= 0 /
   * 及格线高于卷面题数 / 已审核的题不够卷面题数。这一页自己只算得出最后一条
   * （屏幕上那两个数），另外三条只有后端知道 —— 不收这两个字段的话，
   * 一张 50 题卷把及格分填成 80 的卷子在列表里长得完全健康，而它已经开不了考。
   * blocker 是原样能贴给运营看的一句话，startable 在后端没给这两个字段时是 null。
   */
  startable: boolean | null; blocker: string | null;
};

type Q = {
  id: string; paperId: string | null; paperTitle: string | null; paperCode: string | null;
  type: string; stem: string; options: QOptions | null;
  /** 选项解不开时的原文与原因（见 parseOptions）。非空 = 这道题上卷就是空选项。 */
  optionsBroken: QBroken | null;
  answer: string; explanation: string | null; status: string; origin: string | null;
  lessonId: string | null; lessonTitle: string | null; sourceTsSec: number | null;
  reviewedAt: unknown; reviewedBy: string | null; createdAt: unknown;
};

/** 从行本体、以及它可能的嵌套盒子里，取第一个真的给了值的键。 */
function pick(row: any, ...keys: string[]): unknown {
  if (!row || typeof row !== 'object') return undefined;
  const boxes = [row, row.rules, row.config, row.stats, row.questionStats, row.bank];
  for (const b of boxes) {
    if (!b || typeof b !== 'object' || Array.isArray(b)) continue;
    for (const k of keys) {
      const v = (b as Record<string, unknown>)[k];
      if (v !== undefined && v !== null) return v;
    }
  }
  return undefined;
}

/**
 * id 一律转成字符串。
 *
 * BIGINT UNSIGNED 到了后端是 BigInteger，出网是 JSON 数字；而这一页拿它当 React key、
 * 当 URL 参数、还要和下拉框的选中值比相等 —— 三处各按各的类型来，
 * 「筛选框显示全部而请求带着 paperId」这种事就是这么发生的。
 */
function idOf(v: unknown): string | null {
  return v === undefined || v === null || v === '' ? null : String(v);
}

function str(v: unknown): string | null {
  if (v === undefined || v === null) return null;
  const s = String(v);
  return s === '' ? null : s;
}

function normPaper(r: any): Paper {
  const rules = (r?.rules ?? r?.config ?? null) as any;
  // startable 是布尔，false 是有意义的值 —— 只能拿「键在不在」区分「后端说不能考」
  // 和「后端根本没给这个字段」。num() 认 true/1 两种写法（见 fmt.ts 那段）。
  const startable = pick(r, 'startable');
  return {
    id: idOf(pick(r, 'id', 'paperId', 'paper_id')) ?? '',
    code: str(pick(r, 'code')) ?? '',
    title: str(pick(r, 'title')) ?? '',
    subtitle: str(pick(r, 'subtitle')),
    courseId: idOf(pick(r, 'courseId', 'course_id')),
    courseTitle: str(pick(r, 'courseTitle', 'course_title')),
    access: str(pick(r, 'access')) ?? '',
    // TINYINT(1) 出网可能是 true 也可能是 1，num() 两种都认（见 fmt.ts 那段注释）
    grantsCert: num(pick(r, 'grantsCertificate', 'grants_certificate')) === 1,
    status: str(pick(r, 'status')) ?? '',
    configId: idOf(pick(r, 'configId', 'config_id') ?? (rules ? rules.id : undefined)),
    effectiveFrom: pick(r, 'effectiveFrom', 'effective_from'),
    questionCount: num(pick(r, 'questionCount', 'question_count')),
    passScore: num(pick(r, 'passScore', 'pass_score')),
    durationMin: num(pick(r, 'durationMin', 'duration_min')),
    maxAttempts: num(pick(r, 'maxAttempts', 'max_attempts')),
    cooldownHours: num(pick(r, 'retryCooldownHours', 'retry_cooldown_hours')),
    progressPct: num(pick(r, 'requireProgressPct', 'require_progress_pct')),
    shuffle: num(pick(r, 'shuffleOptions', 'shuffle_options'), 1),
    approved: num(pick(r, 'approvedCount', 'approved', 'approved_count')),
    pending: num(pick(r, 'pendingCount', 'pending', 'pending_count')),
    bankTotal: num(pick(r, 'totalCount', 'questionTotal', 'total', 'total_count')),
    startable: startable === undefined ? null : num(startable) === 1,
    blocker: str(pick(r, 'blocker')),
  };
}

function normQuestion(r: any): Q {
  const ts = pick(r, 'sourceTsSec', 'source_ts_sec');
  const opt = parseOptions(pick(r, 'options'));
  return {
    id: idOf(pick(r, 'id', 'questionId', 'question_id')) ?? '',
    paperId: idOf(pick(r, 'paperId', 'paper_id')),
    paperTitle: str(pick(r, 'paperTitle', 'paper_title')),
    paperCode: str(pick(r, 'paperCode', 'paper_code')),
    type: str(pick(r, 'type')) ?? '',
    stem: str(pick(r, 'stem')) ?? '',
    options: opt.list,
    optionsBroken: opt.broken,
    answer: str(pick(r, 'answer')) ?? '',
    explanation: str(pick(r, 'explanation')),
    status: str(pick(r, 'reviewStatus', 'review_status', 'status')) ?? '',
    origin: str(pick(r, 'origin')),
    lessonId: idOf(pick(r, 'lessonId', 'lesson_id')),
    lessonTitle: str(pick(r, 'lessonTitle', 'lesson_title')),
    sourceTsSec: ts === undefined ? null : num(ts),
    reviewedAt: pick(r, 'reviewedAt', 'reviewed_at'),
    reviewedBy: idOf(pick(r, 'reviewedBy', 'reviewed_by')),
    createdAt: pick(r, 'createdAt', 'created_at'),
  };
}

/**
 * question.options 是 MySQL 的 JSON 列。
 *
 * <p>经 JdbcTemplate 取出来是<b>字符串</b>，原样出网之后前端拿到的就是一串
 * `[{"key":"A","text":"…"}]` 而不是数组 —— 直接 .map 会当场抛，
 * 而 render 里抛出来的错会把<b>整张表</b>打成白屏，不是只坏这一行。两种形状都收。
 *
 * <h3>★ 解不开的选项必须自己显形，不能变成「一道没有选项的题」</h3>
 * AdminExamController.options() 解析失败时返回
 * `[{"optionsRaw":"<原文>","error":"这道题的选项不是合法 JSON，考卷上会显示成空选项"}]`，
 * 注释写明用意就是<b>让那一行自己显形</b>。原来这里把它 map 成
 * `[{key:'A', text:''}]`（key/text 都取不到，回落成字母 A 和空串）——
 * 审题人看到的是一道「选项少了点但答案 A 打着绿勾」的题，很可能直接点通过，
 * 而这道题上卷之后学员看到的是空选项。
 *
 * <p>同一种吞法还有两处：字符串 JSON.parse 失败、以及 options 根本不是数组。
 * 两处原来都 `return null`，而 null 在 QDetail 里的意思是「判断题，没有选项」——
 * 一样是把坏数据说成正常形状。所以这里改成返回两样东西：能用的 list，
 * 和一个"这道题的选项坏了"的 broken，由界面去印。
 */
type QOptions = { key: string; text: string }[];
/** 选项坏掉的样子：raw 是原文（后端带出来的 optionsRaw，或者前端手上那一份），why 是原因。 */
type QBroken = { raw: string; why: string };

function parseOptions(v: unknown): { list: QOptions | null; broken: QBroken | null } {
  const ok = (list: QOptions | null) => ({ list, broken: null });
  const bad = (raw: string, why: string) => ({ list: null, broken: { raw, why } });

  let raw: unknown = v;
  if (typeof raw === 'string') {
    const s = raw.trim();
    if (!s) return ok(null);
    try { raw = JSON.parse(s); }
    catch { return bad(s, '这道题的选项不是合法 JSON（前端也解不开），考卷上会显示成空选项'); }
  }
  if (raw === undefined || raw === null) return ok(null);
  if (!Array.isArray(raw))
    return bad(safeText(raw), '这道题的选项不是一个数组，考卷上会显示成空选项');
  if (raw.length === 0) return ok(null);

  // 后端解析失败时的兜底形状。判据放宽到 optionsRaw / error 任意一个在，
  // 但要求它同时【不像一个正常选项】—— 免得哪天真选项多带一个 error 字段就被整条判坏。
  const first: any = raw[0];
  if (first && typeof first === 'object' && !Array.isArray(first)
      && (first.optionsRaw !== undefined || first.error !== undefined)
      && first.key === undefined && first.text === undefined
      && first.k === undefined && first.t === undefined)
    return bad(str(first.optionsRaw) ?? safeText(first),
               str(first.error) ?? '后端说这道题的选项解不开，考卷上会显示成空选项');

  return ok(raw.map((o: any, i: number) => ({
    key: str(o?.key ?? o?.k) ?? String.fromCharCode(65 + i),
    text: str(o?.text ?? o?.t) ?? '',
  })));
}

/** 把非字符串的原文变成能印在屏幕上的一行。印不出来也不能抛 —— 这是错误路径。 */
function safeText(v: unknown): string {
  try { return JSON.stringify(v) ?? String(v); } catch { return String(v); }
}
