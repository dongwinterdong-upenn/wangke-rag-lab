/**
 * 我的学员。
 *
 * 手机号一律掩码显示。代理确实需要联系学员，但这一页的用途是
 * "看谁激活了、学得怎么样"，不是通讯录 —— 一个能整页导出明文手机号的后台，
 * 迟早会变成一份在外面流通的名单，而名单里的人从没同意过这件事。
 */
import React from 'react';
import { Link } from 'react-router-dom';
import {
  Async, Button, Card, dateTime, Empty, Input, PageHead, Progress, Table,
  Tag, type Col, untilText, useAsync, IconSearch, IconUsers,
} from '@wangke/shared';
import { AgentApi, type StudentRow } from '../api';
import { Gate, useActiveAgent } from '../gate';
import { Note, Pager, Section, TableSkeleton, Toolbar, keepPrevious, useNarrow } from '../kit';

const SIZE = 20;

export default function StudentsPage() {
  return (
    <>
      <PageHead
        title="我的学员"
        desc="用你的码激活、或者从你的推广链接注册后买课的人，都会出现在这里。"
      />
      <Gate><Body /></Gate>
    </>
  );
}

function Body() {
  const agent = useActiveAgent();
  const narrow = useNarrow();
  const [keyword, setKeyword] = React.useState('');
  const [applied, setApplied] = React.useState('');
  const [page, setPage] = React.useState(1);

  const raw = useAsync(
    () => AgentApi.students({ keyword: applied, page, size: SIZE }),
    [agent?.id, applied, page]);
  const state = keepPrevious(raw);

  const cols: Col<StudentRow>[] = [
    {
      key: 'phone', title: '学员', width: 150,
      render: r => (
        <span style={{ display: 'inline-flex', flexDirection: 'column', lineHeight: 1.3 }}>
          <span className="num">{r.phone}</span>
          {r.nickname && <span style={{ fontSize: 'var(--t-xs)', color: 'var(--ink-3)' }}>{r.nickname}</span>}
        </span>
      ),
    },
    { key: 'activatedAt', title: '激活时间', width: 150, render: r => dateTime(r.activatedAt) },
    {
      key: 'codeMasked', title: '用的哪个码', width: 160,
      render: r => r.codeMasked
        ? <span className="num" style={{ color: 'var(--ink-2)' }}>{r.codeMasked}</span>
        : <Tag tone="brand">从推广链接直接买的</Tag>,
    },
    {
      key: 'progressPct', title: '学习进度', width: 170,
      render: r => <ProgressCell row={r} />,
    },
    {
      key: 'lastSeenAt', title: '最近一次学习', width: 150,
      render: r => r.lastSeenAt
        ? dateTime(r.lastSeenAt)
        : <span style={{ color: 'var(--ink-4)' }}>激活后还没看过</span>,
    },
    {
      key: 'validUntil', title: '看课权限', width: 130,
      render: r => r.validUntil
        ? <span style={{ color: 'var(--ink-2)' }}>{untilText(r.validUntil)}</span>
        : '—',
    },
  ];

  return (
    <div style={{ display: 'grid', gap: 'var(--s4)' }}>
      <Section>
        <Toolbar
          left={
            <form onSubmit={e => { e.preventDefault(); setApplied(keyword.trim()); setPage(1); }}
                  style={{ display: 'flex', gap: 'var(--s2)' }}>
              <Input
                value={keyword} placeholder="搜手机号尾号或激活码"
                onChange={e => setKeyword(e.target.value)}
                style={{ width: 'min(60vw, 260px)' }}
              />
              <Button type="submit" variant="ghost" size="sm">搜</Button>
              {applied && (
                <Button type="button" variant="quiet" size="sm"
                        onClick={() => { setKeyword(''); setApplied(''); setPage(1); }}>清空</Button>
              )}
            </form>
          }
          right={
            <span style={{ fontSize: 'var(--t-sm)', color: 'var(--ink-3)' }}>
              手机号按规定只显示中间打码的形式
            </span>
          }
        />

        {state.loading ? <TableSkeleton rows={8} cols={5} /> : (
          <Async state={state}>
            {(d) => d.rows.length === 0 ? (
              applied ? (
                <Empty
                  icon={<IconSearch size={22} />}
                  title="没有匹配的学员"
                  desc="换个手机号尾号或者激活码试试。"
                  action={<Button variant="ghost"
                                  onClick={() => { setKeyword(''); setApplied(''); setPage(1); }}>
                    看全部学员
                  </Button>}
                />
              ) : (
                <Empty
                  icon={<IconUsers size={22} />}
                  title="还没有人激活你的码"
                  desc="学员在学员端填入激活码之后，名字才会出现在这里。也可以把推广链接发出去，从链接注册的人也算你的。"
                  action={
                    <div style={{ display: 'flex', gap: 'var(--s2)', justifyContent: 'center', flexWrap: 'wrap' }}>
                      <Link to="/codes"><Button variant="accent">去拿激活码</Button></Link>
                      <Link to="/promote"><Button variant="ghost">去拿推广链接</Button></Link>
                    </div>
                  }
                />
              )
            ) : narrow ? (
              <div style={{ display: 'grid', gap: 'var(--s2)' }}>
                {d.rows.map(r => <StudentCard key={r.userId} row={r} />)}
                <Pager page={page} size={SIZE} total={d.total} onPage={setPage} busy={raw.loading} />
              </div>
            ) : (
              <>
                <Table cols={cols} rows={d.rows} rowKey={r => r.userId} />
                <Pager page={page} size={SIZE} total={d.total} onPage={setPage} busy={raw.loading} />
              </>
            )}
          </Async>
        )}
      </Section>

      <Note tone="info">
        进度按学员实际看过的时长算，不是按他点开了几节。倍速播放和拖进度条不会额外增加有效时长。
      </Note>
    </div>
  );
}

function ProgressCell({ row }: { row: StudentRow }) {
  const pct = row.progressPct;
  if (pct === null || pct === undefined) {
    return <span style={{ color: 'var(--ink-4)' }}>—</span>;
  }
  return (
    <div style={{ minWidth: 130 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between',
                    fontSize: 'var(--t-xs)', color: 'var(--ink-3)', marginBottom: 'var(--s1)' }}>
        <span className="num">{Math.round(pct)}%</span>
        {row.totalLessons ? (
          <span className="num">{row.finishedLessons ?? 0}/{row.totalLessons} 节</span>
        ) : null}
      </div>
      <Progress pct={pct} />
    </div>
  );
}

function StudentCard({ row }: { row: StudentRow }) {
  return (
    <Card style={{ padding: 'var(--s3) var(--s4)' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between',
                    alignItems: 'baseline', gap: 'var(--s3)' }}>
        <span className="num" style={{ fontSize: 'var(--t-h3)' }}>{row.phone}</span>
        <span className="num" style={{ fontSize: 'var(--t-sm)', color: 'var(--ink-2)' }}>
          {row.progressPct === null || row.progressPct === undefined
            ? '—' : `${Math.round(row.progressPct)}%`}
        </span>
      </div>
      <div style={{ marginTop: 'var(--s2)' }}>
        <Progress pct={row.progressPct ?? 0} />
      </div>
      <div style={{ fontSize: 'var(--t-xs)', color: 'var(--ink-3)', marginTop: 'var(--s2)',
                    lineHeight: 'var(--lh-body)' }}>
        {dateTime(row.activatedAt)} 激活 · {row.codeMasked || '从推广链接直接买的'}
        <br />
        {row.lastSeenAt ? `最近学习 ${dateTime(row.lastSeenAt)}` : '激活后还没看过'}
        {row.validUntil ? ` · 看课权限${untilText(row.validUntil)}` : ''}
      </div>
    </Card>
  );
}
