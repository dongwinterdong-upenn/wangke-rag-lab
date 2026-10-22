import React from 'react';
import { api, hms } from '@wangke/shared/api';
import { PageHead } from '@wangke/shared/shell';
import { Async, Button, Table, useAsync, useToast } from '@wangke/shared/ui';
import type { Col } from '@wangke/shared/ui';
import {
  ActionDialog, AuditTrail, Blank, Board, Ctl, DetailModal, KV, Note, Pick, Sect, St, TextIn,
} from '../lib/kit';
import { fmtTime, num } from '../lib/fmt';
import { useQ } from '../lib/q';

export function Courses() {
  const { get, set } = useQ();
  const courseId = get('course');
  const [rev, bump] = React.useReducer((n: number) => n + 1, 0);
  const [newCourse, setNewCourse] = React.useState(false);

  const list = useAsync<any>(() => api.get('/api/admin/courses'), [rev]);

  const cols: Col<any>[] = [
    { key: 'id', title: 'ID', numeric: true, width: 56 },
    { key: 'title', title: '课程', render: r => (
      <div style={{ minWidth: 200 }}>
        <div style={{ fontWeight: 500 }}>{r.title}</div>
        {r.subtitle && <div style={{ color: 'var(--ink-3)', fontSize: 'var(--t-xs)',
                                     maxWidth: 420 }}>{r.subtitle}</div>}
      </div>) },
    { key: 'status', title: '状态', render: r => <St domain="content" value={r.status} /> },
    { key: 'totalLessons', title: '已上架讲数', numeric: true,
      render: r => <span className="num">{num(r.totalLessons)}</span> },
    { key: 'totalSeconds', title: '已上架总时长', numeric: true,
      render: r => <span className="num">{hms(num(r.totalSeconds))}</span> },
    { key: 'months', title: '期限', render: r => (
      <span style={{ fontSize: 'var(--t-xs)' }}>
        发码后 {num(r.activateMonths)} 个月内可激活<br />
        激活后 {num(r.accessMonths)} 个月内可观看
      </span>) },
    { key: 'createdAt', title: '创建时间', render: r => fmtTime(r.createdAt) },
  ];

  return (
    <>
      <PageHead title="课程与讲次"
        desc="录课、排版式、逐讲上架。正片上架要过水印闸门 —— 这道闸在数据库里，任何路径都绕不开。"
        extra={<Button onClick={() => setNewCourse(true)}>新建课程</Button>} />

      <Board flush title="课程">
        <Async state={list}>{(d: any) => (
          <Table cols={cols} rows={d.items ?? []} rowKey={r => r.id}
                 onRowClick={r => set({ course: r.id }, true)}
                 empty={<Blank filtered={false} thing="课程"
                   action={<Button size="sm" onClick={() => setNewCourse(true)}>新建第一门课</Button>} />} />
        )}</Async>
      </Board>

      {courseId && (
        <div style={{ marginTop: 'var(--s4)' }}>
          <CourseWork courseId={courseId} onCourseChanged={bump}
                      onClose={() => set({ course: '' }, true)} />
        </div>
      )}

      <CourseForm open={newCourse} onClose={() => setNewCourse(false)}
                  onDone={(r: any) => { bump(); if (r?.courseId) set({ course: r.courseId }, true); }} />
    </>
  );
}

/* ------------------------------------------------------------------ 单门课 */

function CourseWork({ courseId, onCourseChanged, onClose }: {
  courseId: string; onCourseChanged: () => void; onClose: () => void;
}) {
  const [rev, bump] = React.useReducer((n: number) => n + 1, 0);
  const course = useAsync<any>(() => api.get(`/api/admin/courses/${courseId}`), [courseId, rev]);
  const chapters = useAsync<any>(() => api.get(`/api/admin/courses/${courseId}/chapters`), [courseId, rev]);
  const lessons = useAsync<any>(() => api.get(`/api/admin/courses/${courseId}/lessons`), [courseId, rev]);

  const [edit, setEdit] = React.useState(false);
  const [statusDlg, setStatusDlg] = React.useState<null | 'ON' | 'OFF'>(null);
  const [newChapter, setNewChapter] = React.useState(false);
  const [newLesson, setNewLesson] = React.useState(false);
  const [checkId, setCheckId] = React.useState<string | null>(null);
  const [editLesson, setEditLesson] = React.useState<any>(null);

  const after = () => { bump(); onCourseChanged(); };
  const c = course.data?.course;
  const onLessons = (lessons.data?.items ?? []).filter((l: any) => l.status === 'ON').length;

  return (
    <div style={{ display: 'grid', gap: 'var(--s4)' }}>
      <Board title={c ? c.title : `课程 #${courseId}`}
        desc={c ? <>课程 ID #{c.id} · <St domain="content" value={c.status} /></> : undefined}
        extra={<>
          <Button size="sm" variant="quiet" onClick={onClose}>收起</Button>
          {c && <Button size="sm" variant="ghost" onClick={() => setEdit(true)}>编辑课程</Button>}
          {c && (c.status === 'ON'
            ? <Button size="sm" variant="ghost" onClick={() => setStatusDlg('OFF')}>下架课程</Button>
            : <Button size="sm" onClick={() => setStatusDlg('ON')}>上架课程</Button>)}
        </>}>
        <Async state={course}>{(d: any) => {
          const g = d.course;
          return (
            <>
              <KV items={[
                ['课程标题', g.title],
                ['副标题', g.subtitle],
                ['状态', <St domain="content" value={g.status} />],
                ['封面', g.coverUrl
                  ? <span style={{ fontSize: 'var(--t-xs)', overflowWrap: 'anywhere' }}>{g.coverUrl}</span>
                  : null],
                ['可激活期限', <>发码后 <b>{num(g.activateMonths)}</b> 个月内必须激活，过期作废</>],
                ['可观看期限', <>激活后 <b>{num(g.accessMonths)}</b> 个月内可以观看</>],
                ['已上架讲数', <span className="num">{num(g.totalLessons)} 讲</span>],
                ['已上架时长', <span className="num">{hms(num(g.totalSeconds))}</span>],
              ]} />
              <div style={{ marginTop: 'var(--s3)' }}>
                <Note>
                  「已上架讲数 / 时长」只统计<b>已上架</b>的讲。草稿也算进去的话，宣传页上写着 40 讲、
                  学员进来只有 12 讲 —— 那是退款纠纷里最难辩的一种。
                </Note>
              </div>
            </>
          );
        }}</Async>
      </Board>

      <Board title="章" flush
        extra={<Button size="sm" variant="ghost" onClick={() => setNewChapter(true)}>新建章</Button>}>
        <Async state={chapters}>{(d: any) => (
          <Table rows={d.items ?? []} rowKey={(r: any) => r.id}
            cols={[
              { key: 'sort_no', title: '序号', numeric: true, width: 70,
                render: (r: any) => <span className="num">{r.sort_no ?? r.sortNo}</span> },
              { key: 'title', title: '章标题' },
              { key: 'id', title: 'ID', numeric: true, width: 70,
                render: (r: any) => <span className="num">#{r.id}</span> },
            ]}
            empty={<Blank filtered={false} thing="章"
              action={<Button size="sm" onClick={() => setNewChapter(true)}>先建一章，讲次要挂在章下面</Button>} />} />
        )}</Async>
      </Board>

      <Board title="讲次" flush
        desc={<>共 {(lessons.data?.items ?? []).length} 讲，其中 <b>{onLessons}</b> 讲已上架</>}
        extra={<Button size="sm" variant="ghost" onClick={() => setNewLesson(true)}
                       disabled={(chapters.data?.items ?? []).length === 0}>新建讲次</Button>}>
        <Async state={lessons}>{(d: any) => (
          <Table rows={d.items ?? []} rowKey={(r: any) => r.id}
            cols={lessonCols(setCheckId, setEditLesson)}
            empty={<Blank filtered={false} thing="讲次"
              action={<span style={{ fontSize: 'var(--t-sm)' }}>
                先建章，再建讲。新建的讲一律是草稿，要逐讲走上架自检才能上架。
              </span>} />} />
        )}</Async>
      </Board>

      <Sect title="课程的改动记录">
        <Board><AuditTrail targetType="course" targetId={courseId} refreshKey={rev} /></Board>
      </Sect>

      {c && <CourseForm open={edit} course={c} onClose={() => setEdit(false)} onDone={after} />}
      {c && <ActionDialog open={statusDlg === 'ON'} onClose={() => setStatusDlg(null)} verb="上架"
        title="上架这门课" onDone={after}
        consequences={<>
          上架之后这门课对学员可见、可购买。<br />
          <b>课程上架不等于讲次上架</b>：学员看到的目录只包含已上架的讲。
          当前已上架 <b>{onLessons}</b> 讲 —— 一讲都没上架的课，学员进来看到的是空目录，
          这一点服务端也会拦。
        </>}
        onConfirm={() => api.post(`/api/admin/courses/${c.id}/status`, { status: 'ON' })} />}
      {c && <ActionDialog open={statusDlg === 'OFF'} onClose={() => setStatusDlg(null)} verb="下架"
        tone="danger" title="下架这门课" onDone={after}
        consequences={<>
          下架之后这门课不再对外可见、不能再被购买。<br />
          <b>已经买了的学员不受影响</b>——他们的权益还在，课照看。下架不退钱。
        </>}
        onConfirm={() => api.post(`/api/admin/courses/${c.id}/status`, { status: 'OFF' })} />}

      <ChapterForm open={newChapter} courseId={courseId}
                   onClose={() => setNewChapter(false)} onDone={after} />
      <LessonForm open={newLesson} courseId={courseId} chapters={chapters.data?.items ?? []}
                  onClose={() => setNewLesson(false)} onDone={after} />
      {editLesson && <LessonForm open lesson={editLesson} courseId={courseId}
                                 chapters={chapters.data?.items ?? []}
                                 onClose={() => setEditLesson(null)} onDone={after} />}
      {checkId && <PublishCheck lessonId={checkId} onClose={() => setCheckId(null)} onDone={after} />}
    </div>
  );
}

function lessonCols(onCheck: (id: string) => void, onEdit: (l: any) => void): Col<any>[] {
  return [
    { key: 'sortNo', title: '序', numeric: true, width: 56,
      render: r => <span className="num">{r.sortNo}</span> },
    { key: 'title', title: '讲次', render: r => (
      <div style={{ minWidth: 180 }}>
        <div>{r.title}{num(r.isPreview) === 1 && (
          <span style={{ marginLeft: 'var(--s2)', fontSize: 'var(--t-xs)', color: 'var(--brand)' }}>试听</span>)}
        </div>
        <div style={{ color: 'var(--ink-3)', fontSize: 'var(--t-xs)' }}>{r.chapterTitle ?? '未归章'}</div>
      </div>) },
    { key: 'durationSec', title: '时长', numeric: true,
      render: r => <span className="num">{hms(num(r.durationSec))}</span> },
    { key: 'segmentCount', title: '片号区间', render: r => num(r.blockCount) > 0
        ? <span className="num" style={{ fontSize: 'var(--t-xs)' }}>
            [{num(r.segFrom)}, {num(r.segTo)}) · {r.segmentCount} 片
            {num(r.blockCount) > 1 && ` · ${r.blockCount} 段`}
          </span>
        : <span style={{ color: 'var(--danger)' }}>未分配区间</span> },
    // 目录（分节）是内容产物，权威在仓库的 db/content/*.json，走 ops/deploy_sections.sh 灌。
    // 这里【只报数不给编辑】：后台能改就等于有了第二个真相来源，两边迟早对不上，
    // 而对不上的时候两边看起来都对。
    { key: 'sectionCount', title: '目录分节', numeric: true,
      render: r => num(r.sectionCount) > 0
        ? <span className="num">{r.sectionCount} 节</span>
        : <span style={{ color: 'var(--ink-3)', fontSize: 'var(--t-xs)' }}>未分节</span> },
    { key: 'wm', title: '水印位区间', render: r => num(r.isPreview) === 1
      ? <span style={{ color: 'var(--ink-3)', fontSize: 'var(--t-xs)' }}>试听不带水印</span>
      : <span className="num" style={{ fontSize: 'var(--t-xs)' }}>
          [{num(r.wmBitOffset)}, {num(r.wmBitOffset) + num(r.wmSlotCount)}) · 步长 {num(r.wmSlotStride)}
        </span> },
    { key: 'status', title: '状态', render: r => <St domain="content" value={r.status} /> },
    { key: 'ops', title: '操作', render: r => (
      <div style={{ display: 'flex', gap: 'var(--s2)', flexWrap: 'wrap' }}
           onClick={e => e.stopPropagation()}>
        <Button size="sm" variant="ghost" onClick={() => onCheck(String(r.id))}>
          {r.status === 'ON' ? '查看 / 下架' : '上架自检'}
        </Button>
        <Button size="sm" variant="quiet" onClick={() => onEdit(r)}>编辑</Button>
      </div>) },
  ];
}

/* ------------------------------------------------------------------ 上架自检 */

/**
 * 上架前自检面板。
 *
 * 这一屏解决的是一个具体的痛：闸门在数据库里，它「遇到第一个不满足就拒绝」，
 * 于是运营改完一项、再点一次上架、又被下一项挡住 —— 改五项要来回八趟，
 * 而每一趟他都不知道后面还有几关。这里一次把全部问题列出来，每一条都带「怎么补」。
 *
 * 自检通过 ≠ 上架一定成功，而且这件事必须写在界面上：自检和上架之间隔着一次往返，
 * 这中间别人可能改了同课程另一讲的位区间。真正的裁决权只能在库里。
 */
function PublishCheck({ lessonId, onClose, onDone }: {
  lessonId: string; onClose: () => void; onDone: () => void;
}) {
  const [rev, bump] = React.useReducer((n: number) => n + 1, 0);
  const state = useAsync<any>(() => api.get(`/api/admin/lessons/${lessonId}/publish-check`), [lessonId, rev]);
  const [dlg, setDlg] = React.useState<null | 'publish' | 'unpublish'>(null);
  const toast = useToast();

  const d = state.data;
  const failed = (d?.items ?? []).filter((i: any) => !i.pass);

  return (
    <DetailModal open onClose={onClose} width={760}
      title={d ? `上架自检 · ${d.title}` : `上架自检 · 讲次 #${lessonId}`}
      subtitle={d ? (d.previewExempt ? '试听片段，按设计豁免水印闸门'
                                     : d.publishable ? '当前全部通过' : `还差 ${failed.length} 项`) : undefined}
      actions={<>
        <Button variant="quiet" onClick={() => { bump(); toast({ tone: 'info', text: '已重新自检' }); }}>
          重新自检
        </Button>
        <Button variant="ghost" onClick={() => setDlg('unpublish')}>下架这一讲</Button>
        <Button disabled={!d?.publishable} onClick={() => setDlg('publish')}>上架这一讲</Button>
      </>}>
      <Async state={state}>{(g: any) => (
        <div>
          {!g.publishable && (
            <Note tone="danger" title="现在还不能上架">
              下面标红的每一项都会让上架被数据库拒绝。全部处理完再来点上架。
            </Note>
          )}
          {g.publishable && !g.previewExempt && (
            <Note tone="ok" title="自检通过">
              可以上架了。但请注意：自检和上架之间隔着一次往返，如果这期间有人改了同一门课
              另一讲的位区间，上架仍可能被拒 —— 最终裁决在数据库里，它和上架是在同一把锁下算的。
            </Note>
          )}

          <Sect title="逐项检查">
            <ol style={{ listStyle: 'none', margin: 0, padding: 0, display: 'grid', gap: 'var(--s3)' }}>
              {(g.items ?? []).map((it: any, i: number) => (
                <li key={i} style={{
                  display: 'flex', gap: 'var(--s3)', padding: 'var(--s3)',
                  borderRadius: 'var(--r-md)',
                  background: it.pass ? 'var(--ok-soft)' : 'var(--danger-soft)',
                }}>
                  <span aria-hidden style={{
                    flex: '0 0 20px', textAlign: 'center', fontWeight: 700,
                    color: it.pass ? 'var(--ok)' : 'var(--danger)',
                  }}>{it.pass ? '✓' : '✗'}</span>
                  <div style={{ minWidth: 0, flex: 1 }}>
                    <div style={{ fontWeight: 600, color: it.pass ? 'var(--ok)' : 'var(--danger)' }}>
                      {it.item}
                    </div>
                    <div className="num" style={{ fontSize: 'var(--t-xs)', color: 'var(--ink-2)',
                                                  marginTop: 'var(--s1)' }}>{it.detail}</div>
                    {it.fix && (
                      <div style={{ fontSize: 'var(--t-sm)', color: 'var(--ink)', marginTop: 'var(--s2)',
                                    lineHeight: 'var(--lh-body)' }}>
                        <b>怎么补：</b>{it.fix}
                      </div>
                    )}
                  </div>
                </li>
              ))}
            </ol>
          </Sect>

          {g.note && (
            <div style={{ marginTop: 'var(--s4)' }}>
              <Note tone="info" title="关于自检本身">{g.note}</Note>
            </div>
          )}

          <Sect title="这一讲的改动记录">
            <AuditTrail targetType="lesson" targetId={lessonId} refreshKey={rev} />
          </Sect>
        </div>
      )}</Async>

      <ActionDialog open={dlg === 'publish'} onClose={() => setDlg(null)} verb="上架"
        title="上架这一讲" onDone={() => { bump(); onDone(); }}
        consequences={<>
          上架是这段视频<b>第一次对学员可见</b>的那一刻。从这一刻起投递出去的每一份副本
          都带着水印码字 —— 如果此刻布局是错的，<b>补不回来</b>（已经流出去的东西没法追加水印）。
          自检就是为这一步做的。<br />
          上架之后可以下架，但已经流出去的副本收不回。
        </>}
        onConfirm={() => api.post(`/api/admin/lessons/${lessonId}/publish`)} />

      <ActionDialog open={dlg === 'unpublish'} onClose={() => setDlg(null)} verb="下架" tone="danger"
        title="下架这一讲" onDone={() => { bump(); onDone(); }}
        consequences={<>
          下架之后已购学员看不到这一讲。<b>不退钱</b>。<br />
          已经发出去的水印码字与位区间<b>不受影响</b>，这一讲仍然占着它那段位区间 ——
          这是对的：把位区间放出来给别人用，会让已经投递出去的副本在取证时整体错位。
        </>}
        onConfirm={() => api.post(`/api/admin/lessons/${lessonId}/unpublish`)} />
    </DetailModal>
  );
}

/* ------------------------------------------------------------------ 表单 */

function CourseForm({ open, course, onClose, onDone }: {
  open: boolean; course?: any; onClose: () => void; onDone: (r: any) => void;
}) {
  const editing = !!course;
  const [f, setF] = React.useState({ title: '', subtitle: '', coverUrl: '', activateMonths: '12', accessMonths: '24' });

  React.useEffect(() => {
    if (!open) return;
    setF({
      title: course?.title ?? '', subtitle: course?.subtitle ?? '', coverUrl: course?.coverUrl ?? '',
      activateMonths: String(course?.activateMonths ?? 12), accessMonths: String(course?.accessMonths ?? 24),
    });
  }, [open, course]);

  const body = {
    title: f.title.trim(), subtitle: f.subtitle, coverUrl: f.coverUrl,
    activateMonths: Number(f.activateMonths), accessMonths: Number(f.accessMonths),
  };

  return (
    <ActionDialog open={open} onClose={onClose} verb={editing ? '保存' : '创建'}
      title={editing ? '编辑课程' : '新建课程'} onDone={onDone}
      disabled={!f.title.trim() || !(Number(f.activateMonths) > 0) || !(Number(f.accessMonths) > 0)}
      consequences={editing ? <>
        改期限只影响<b>之后</b>发出的码和之后开通的权益：这两个数分别在「发码那一刻」和
        「激活那一刻」各读一次。已经发出的码、已经开通的权益<b>不会跟着变</b>。
      </> : <>
        新建的课程是<b>草稿</b>状态，学员看不到。要先录章与讲、逐讲上架，最后再把课程置为已上架。
      </>}
      onConfirm={() => editing
        ? patch(`/api/admin/courses/${course.id}`, body)
        : api.post('/api/admin/courses', body)}>
      <div style={{ display: 'grid', gap: 'var(--s4)' }}>
        <Ctl label="课程标题" width={320} grow>
          <TextIn value={f.title} onChange={e => setF({ ...f, title: e.target.value })} />
        </Ctl>
        <Ctl label="副标题" width={320} grow hint="一句话说清这门课教什么。学员在购买页会看到。">
          <TextIn value={f.subtitle} onChange={e => setF({ ...f, subtitle: e.target.value })} />
        </Ctl>
        <Ctl label="封面图地址" width={320} grow>
          <TextIn value={f.coverUrl} onChange={e => setF({ ...f, coverUrl: e.target.value })} />
        </Ctl>
        <div style={{ display: 'flex', gap: 'var(--s4)', flexWrap: 'wrap' }}>
          <Ctl label="可激活月数" width={150} hint="发码之后多久内必须激活，过期作废">
            <TextIn value={f.activateMonths} inputMode="numeric"
                    onChange={e => setF({ ...f, activateMonths: e.target.value.replace(/\D/g, '') })} />
          </Ctl>
          <Ctl label="可观看月数" width={150} hint="激活之后可以看多久">
            <TextIn value={f.accessMonths} inputMode="numeric"
                    onChange={e => setF({ ...f, accessMonths: e.target.value.replace(/\D/g, '') })} />
          </Ctl>
        </div>
        <Note>
          按现在填的：激活码发出后 <b>{f.activateMonths || '—'}</b> 个月内要激活，
          激活之后 <b>{f.accessMonths || '—'}</b> 个月内可以观看。
          这两句话要和对外的售卖页写的一模一样 —— 不一致就是退款纠纷。
        </Note>
      </div>
    </ActionDialog>
  );
}

function ChapterForm({ open, courseId, onClose, onDone }: {
  open: boolean; courseId: string; onClose: () => void; onDone: () => void;
}) {
  const [title, setTitle] = React.useState('');
  const [sortNo, setSortNo] = React.useState('');
  const [hlsKey, setHlsKey] = React.useState('');
  const [store, setStore] = React.useState('');
  React.useEffect(() => {
    if (open) { setTitle(''); setSortNo(''); setHlsKey(''); setStore(''); }
  }, [open]);

  return (
    <ActionDialog open={open} onClose={onClose} verb="创建" title="新建章" onDone={onDone}
      disabled={!title.trim()}
      consequences={<>
        一章 = 一场录屏 = 一个片库目录。章下各讲共用这批分片，各拿一段片号区间，
        所以片库目录与分片总数填在【章】上，不填在讲次上。
      </>}
      onConfirm={() => api.post(`/api/admin/courses/${courseId}/chapters`, {
        title: title.trim(), sortNo: sortNo ? Number(sortNo) : undefined,
        hlsKey: hlsKey.trim() || undefined,
        storeSegmentCount: store ? Number(store) : undefined,
      })}>
      <div style={{ display: 'grid', gap: 'var(--s4)' }}>
        <Ctl label="章标题" width={320} grow>
          <TextIn value={title} onChange={e => setTitle(e.target.value)} />
        </Ctl>
        <Ctl label="排序号" width={150} hint="留空自动排到最后（按 10 递增，方便以后插队）">
          <TextIn value={sortNo} inputMode="numeric"
                  onChange={e => setSortNo(e.target.value.replace(/\D/g, ''))} />
        </Ctl>
        <Ctl label="片库目录" width={320} grow
             hint="私有桶里的对象前缀，绝不会下发直链。本章各讲共用它。">
          <TextIn value={hlsKey} onChange={e => setHlsKey(e.target.value)} />
        </Ctl>
        <Ctl label="片库分片总数" width={180}
             hint="目录里一共有多少片（剪掉的片也算）。水印槽位容量按它算，填错了整章的码位都会挪。">
          <TextIn value={store} inputMode="numeric"
                  onChange={e => setStore(e.target.value.replace(/\D/g, ''))} />
        </Ctl>
      </div>
    </ActionDialog>
  );
}

function LessonForm({ open, courseId, chapters, lesson, onClose, onDone }: {
  open: boolean; courseId: string; chapters: any[]; lesson?: any;
  onClose: () => void; onDone: () => void;
}) {
  const editing = !!lesson;
  const [f, setF] = React.useState<any>({});
  React.useEffect(() => {
    if (!open) return;
    setF({
      chapterId: String(lesson?.chapterId ?? chapters[0]?.id ?? ''),
      title: lesson?.title ?? '',
      sortNo: lesson?.sortNo != null ? String(lesson.sortNo) : '',
      durationSec: lesson?.durationSec != null ? String(lesson.durationSec) : '',
      segFrom: lesson?.segFrom != null ? String(lesson.segFrom) : '',
      segTo: lesson?.segTo != null ? String(lesson.segTo) : '',
      wmSlotStride: lesson?.wmSlotStride != null ? String(lesson.wmSlotStride) : '6',
      wmSlotCount: lesson?.wmSlotCount != null ? String(lesson.wmSlotCount) : '',
      wmBitOffset: lesson?.wmBitOffset != null ? String(lesson.wmBitOffset) : '',
      isPreview: String(num(lesson?.isPreview, 0)),
    });
  }, [open, lesson, chapters]);

  const n = (v: any) => (v === '' || v === undefined || v === null ? undefined : Number(v));
  const body: any = editing
    ? {
        chapterId: n(f.chapterId), title: f.title?.trim(), sortNo: n(f.sortNo),
        durationSec: n(f.durationSec),
        segFrom: n(f.segFrom), segTo: n(f.segTo), wmSlotStride: n(f.wmSlotStride),
        wmSlotCount: n(f.wmSlotCount), wmBitOffset: n(f.wmBitOffset), isPreview: n(f.isPreview),
      }
    : {
        chapterId: n(f.chapterId), title: f.title?.trim(), sortNo: n(f.sortNo),
        durationSec: n(f.durationSec),
        segFrom: n(f.segFrom), segTo: n(f.segTo), wmSlotStride: n(f.wmSlotStride),
        isPreview: n(f.isPreview),
      };

  return (
    <ActionDialog open={open} onClose={onClose} verb={editing ? '保存' : '创建'} width={620}
      title={editing ? `编辑讲次 #${lesson.id}` : '新建讲次'} onDone={onDone}
      disabled={!f.title?.trim() || !f.chapterId || !(Number(f.durationSec) > 0)
                || (editing && num(lesson?.blockCount) > 1
                    && (f.segFrom !== '' || f.segTo !== ''))}
      consequences={editing ? <>
        <b>切片数与位区间不是随手能改的字段。</b>这门课一旦发出过水印码字，
        改动它们会让此前投递的所有副本在取证时整体错位 —— 错位的打分不会报错，
        它会安静地算出一组错的分数然后指认到无辜的人身上。服务端会拦住这种改动。<br />
        已上架的讲次改试听标记也会被拒：那等于把一门在售课程的水印保护当场关掉。
      </> : <>
        新建的讲次一律是<b>草稿</b>，学员看不到，也不需要现在就填水印位区间 ——
        位区间由全课程统一分配。填完切片数之后走「上架自检」，那里会告诉你还差什么。
      </>}
      onConfirm={() => editing
        ? patch(`/api/admin/lessons/${lesson.id}`, body)
        : api.post(`/api/admin/courses/${courseId}/lessons`, body)}>
      <div style={{ display: 'grid', gap: 'var(--s4)' }}>
        <Ctl label="所属章" width={280} grow>
          <Pick value={f.chapterId} all={false} onChange={v => setF({ ...f, chapterId: v })}
                options={chapters.map((c: any) => ({
                  value: String(c.id), text: `${c.sort_no ?? c.sortNo ?? ''} ${c.title}`.trim(),
                }))} />
        </Ctl>
        <Ctl label="讲次标题" width={320} grow>
          <TextIn value={f.title} onChange={e => setF({ ...f, title: e.target.value })} />
        </Ctl>
        <div style={{ display: 'flex', gap: 'var(--s4)', flexWrap: 'wrap' }}>
          <Ctl label="排序号" width={130} hint="留空自动排到最后">
            <TextIn value={f.sortNo} inputMode="numeric"
                    onChange={e => setF({ ...f, sortNo: e.target.value.replace(/\D/g, '') })} />
          </Ctl>
          <Ctl label="时长（秒）" width={150}
               hint="必须大于 0：进度反作弊的判据全靠它，填 0 会让任何观看进度都被判为超长">
            <TextIn value={f.durationSec} inputMode="numeric"
                    onChange={e => setF({ ...f, durationSec: e.target.value.replace(/\D/g, '') })} />
          </Ctl>
          <Ctl label="是否试听" width={150}
               hint="试听片段必须是单独编码的一份，不能是正片的前几片">
            <Pick value={f.isPreview} all={false} onChange={v => setF({ ...f, isPreview: v })}
                  options={[{ value: '0', text: '正片（带水印）' },
                            { value: '1', text: '试听（公开，不带水印）' }]} />
          </Ctl>
        </div>
        <Sect title="片号区间">
          <div style={{ display: 'flex', gap: 'var(--s4)', flexWrap: 'wrap', alignItems: 'flex-end' }}>
            <Ctl label="起始片号" width={130} hint="含。片库目录里的全局片号">
              <TextIn value={f.segFrom} inputMode="numeric"
                      onChange={e => setF({ ...f, segFrom: e.target.value.replace(/\D/g, '') })} />
            </Ctl>
            <Ctl label="结束片号" width={130} hint="不含">
              <TextIn value={f.segTo} inputMode="numeric"
                      onChange={e => setF({ ...f, segTo: e.target.value.replace(/\D/g, '') })} />
            </Ctl>
            <div style={{ fontSize: 'var(--t-xs)', color: 'var(--ink-3)', paddingBottom: 6 }}>
              共 <b className="num">{Math.max(0, num(f.segTo) - num(f.segFrom))}</b> 片
            </div>
          </div>
          <div style={{ marginTop: 'var(--s3)' }}>
            <Note tone={editing && num(lesson?.blockCount) > 1 ? 'warn' : 'info'}>
              {editing && num(lesson?.blockCount) > 1
                ? <>这一讲有 <b>{num(lesson.blockCount)}</b> 段区间（中间跳掉的是剪去的课间与等 AI 的静音）。
                    后台只存得下一段，在这里填就会把多出来的段整段抹掉 ——
                    所以这两个框留空才能保存。要改去改
                    <span className="num"> db/content/fde_cut.json </span>
                    再跑 <span className="num">ops/deploy_lessons.sh</span>。</>
                : <>后台只支持「一讲一段连续区间」。多段区间由内容入库流水线
                    （<span className="num">ops/deploy_lessons.sh</span>）灌。</>}
            </Note>
          </div>
        </Sect>

        <Sect title="水印布局">
          <div style={{ display: 'flex', gap: 'var(--s4)', flexWrap: 'wrap' }}>
            <Ctl label="取样步长" width={130} hint="每 N 片取 1 片做 A/B 双版本，默认 6">
              <TextIn value={f.wmSlotStride} inputMode="numeric"
                      onChange={e => setF({ ...f, wmSlotStride: e.target.value.replace(/\D/g, '') })} />
            </Ctl>
            {editing && <>
              <Ctl label="槽位数" width={130} hint="改完点「上架自检」看服务端算出来的应为多少">
                <TextIn value={f.wmSlotCount} inputMode="numeric"
                        onChange={e => setF({ ...f, wmSlotCount: e.target.value.replace(/\D/g, '') })} />
              </Ctl>
              <Ctl label="起始位" width={130} hint="本讲在码字里占的起点">
                <TextIn value={f.wmBitOffset} inputMode="numeric"
                        onChange={e => setF({ ...f, wmBitOffset: e.target.value.replace(/\D/g, '') })} />
              </Ctl>
            </>}
          </div>
          {num(f.isPreview) === 0 && (
            <div style={{ marginTop: 'var(--s3)' }}>
              {/* 这里原来印着「切片数 ÷ 步长 = 槽位数应为 N」。那个式子现在是【错的】：
                  槽位按区间实际覆盖到的槽位片数算，区间长度不是步长整数倍时两者会差 1，
                  而片库尾部那个不完整步长里的片根本不是槽位。差一位就是整段码字错位一位。
                  不在前端重算一遍 —— 算错了它会理直气壮地指着一个错数字说「应为」。 */}
              <Note>
                槽位数与起始位由服务端按区间实算，别在这里凭切片数除步长推。
                填完点「上架自检」，那里会告诉你按区间算出来应该是多少。
              </Note>
            </div>
          )}
        </Sect>
      </div>
    </ActionDialog>
  );
}

/**
 * PATCH 走 shared 的 api 层，不再自己 fetch。
 *
 * 原来这里手搓了一份，注释写着「鉴权头与 401 处理照抄 api 层那一套」——
 * 抄漏了两处，而两处都只在生产上才看得见：
 *
 *  ① 401 时直接 `localStorage.removeItem('wangke.token')`，绕开 auth.clear()：
 *     不发 emitToken、不触发 onUnauthorized，于是 App 注册的 session.clear()
 *     不会执行，wangke.admin.session 变成孤儿记录留在库里。
 *  ② `location.assign('/login')` 写死了站点根路径，而后台生产上挂在 /admin/
 *     子路径（vite.config.ts 的 APP_BASE），根路径留给落地页。
 *     开发态 APP_BASE='/' 时两者恰好重合，所以这条**永远测不出来**；
 *     一上生产，编辑讲次时令牌过期就把人整个甩出后台，改了一半的内容一并丢失。
 *
 * 收口之后，401 走 api 层唯一那个出口，订阅者负责把人送回登录页 —— 不需要硬跳转。
 */
const patch = (path: string, body: any) => api.patch<any>(path, body);
