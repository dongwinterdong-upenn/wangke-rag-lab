/**
 * 课程详情 + 讲次列表。
 *
 * 两个接口一起取：
 *   - /api/learn/my/courses  拿标题、封面、有效期、总进度（这门课的"我的"那一份）
 *   - /api/learn/course/{id}/lessons  拿章节与逐讲进度
 *
 * 不用公开的 /api/public/course/{id} 取标题：那条路只返回 status='ON' 的课程，
 * 一门课下架之后，已经买了的学员仍然应当看得见自己的课 ——
 * 用公开接口的话，他会在这一页撞上"课程不存在"，而他的权益还好好的。
 */
import React from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import {
  Async, Button, Card, ErrorState, hms, PageHead, Progress, Tag, useAsync, api,
} from '@wangke/shared';
import type { CourseLessons, LessonRow, MyCourse, PublicCourse, SectionRow } from '../types';
import { Cover, ListSkeleton, Note, pctText, Row, Screen, Stack, ValidityTag } from '../kit';

/**
 * owned：他<b>有没有这门课的权益记录</b>（包括已过期的）。判据是「我的课程」里有没有这一门，
 * <b>不是</b>「讲次接口回没回 403」——
 * 讲次接口对「没买」和「买过但已过期 / 被暂停」回的是<b>同一句 403</b>
 * （Entitlements.DENIED，两者共用一句话是后端有意的，免得泄露课程是否存在）。
 * 拿 403 当"他还没买"，会把一个付过钱、看了 60% 的过期学员整页降级成"去下单"，
 * 进度条和全部学习记录一起消失。
 *
 * publicCatalog：这一份目录来自公开接口，<b>没有任何进度字段</b>。
 */
type Data = {
  course: MyCourse | null; lessons: CourseLessons;
  owned: boolean; publicCatalog: boolean;
};

export default function CourseDetail() {
  const { courseId } = useParams();
  const id = Number(courseId);

  const state = useAsync<Data>(async () => {
    const mine = await api.get<{ courses: MyCourse[] }>('/api/learn/my/courses');
    const course = mine.courses.find((c) => c.courseId === id) ?? null;
    try {
      const lessons = await api.get<CourseLessons>(`/api/learn/course/${id}/lessons`);
      return { course, lessons, owned: true, publicCatalog: false };
    } catch (e) {
      // 403 = 现在没有观看权限。可能是「还没买」，也可能是「买过但已过期」——
      // 后端刻意用同一句话答这两种，所以这里不靠它区分，靠上面那份「我的课程」。
      // 只吞 403：404/500 该照常报出来，否则"课程不存在"会被显示成一张空目录。
      if ((e as { status?: number }).status !== 403) throw e;
      const pub = await api.get<{ course: PublicCourse }>(`/api/public/course/${id}`);
      return {
        course,
        lessons: {
          courseId: pub.course.courseId,
          validUntil: course?.validUntil ?? '',
          chapters: pub.course.chapters,
        } as CourseLessons,
        owned: course != null,
        publicCatalog: true,
      };
    }
  }, [id]);

  if (!Number.isFinite(id)) {
    return <Screen><ErrorState message="课程编号不对，请从「我的课程」重新进入" /></Screen>;
  }

  return (
    <Screen>
      {state.loading ? (
        <>
          <PageHead title="课程" />
          <ListSkeleton rows={4} height={64} />
        </>
      ) : (
        <Async state={state}>
          {(d) => <Detail id={id} data={d} />}
        </Async>
      )}
    </Screen>
  );
}

function Detail({ id, data }: { id: number; data: Data }) {
  const nav = useNavigate();
  const { course, lessons, owned, publicCatalog } = data;
  const all = lessons.chapters.flatMap((c) => c.lessons);
  // 讲次编号【跨章连续】：标题里写的就是「第 7 讲」，目录里再从每章重编一次的话，
  // 同一讲在两处会有两个号，学员之间对暗号就对不上了。
  const lessonNo = new Map(all.map((l, i) => [l.lessonId, i + 1]));
  const sectionTotal = all.reduce((n, l) => n + (l.sections?.length ?? 0), 0);
  const nextLesson = all.find((l) => !l.completed) ?? all[0];
  const started = all.some((l) => (l.maxPositionSec ?? 0) > 0);
  const expired = course?.expired ?? false;
  // 未购视角：能点开的只有先导课。第一节先导课就是这一页的主按钮。
  const preview = all.find((l) => l.isPreview) ?? null;

  return (
    <Stack gap="var(--s5)">
      <PageHead
        title={course?.title ?? `课程 #${id}`}
        desc={course?.subtitle ?? undefined}
      />

      <Card>
        <Row wrap={false} align="flex-start">
          <Cover url={course?.coverUrl} title={course?.title ?? '课'} width={96} />
          <div style={{ flex: 1, minWidth: 0 }}>
            <Row gap="var(--s2)">
              {owned && <ValidityTag validUntil={lessons.validUntil} expired={expired} />}
              <Tag tone="neutral">{lessons.chapters.length} 章 · {all.length} 讲</Tag>
              {/* 节数在前端就地数，不另开一条接口：目录本来就整份在手里，
                  再从服务端要一个数就等于同一件事有两个来源，而它们迟早对不上。
                  写「16 讲」和写「4 章 · 16 讲 · 90 节」是两回事 —— 后者才说清了这门课有多少内容。 */}
              {sectionTotal > 0 && <Tag tone="neutral">{sectionTotal} 节</Tag>}
              <Tag tone="neutral">共 {hms(all.reduce((s, l) => s + l.durationSec, 0))}</Tag>
              {!owned && preview && <Tag tone="brand">可试听</Tag>}
            </Row>

            {/* 进度条只对买了课的人有意义。对还没买的人画一条 0%，
                读起来像"你已经开始了但一点没学"，而他连课都还没有。 */}
            {owned && !publicCatalog && (
              <div style={{ marginTop: 'var(--s3)' }}>
                <Row gap="var(--s2)" style={{ justifyContent: 'space-between',
                                              fontSize: 'var(--t-sm)', color: 'var(--ink-2)' }}>
                  <span>已学 {pctText(course?.progressPct ?? 0)}</span>
                  <span className="num">{course?.doneLessons ?? 0}/{all.length} 讲学完</span>
                </Row>
                <div style={{ marginTop: 'var(--s2)' }}>
                  <Progress pct={course?.progressPct ?? 0} />
                </div>
              </div>
            )}
          </div>
        </Row>

        {/* publicCatalog 也要挡：权益被【暂停】的人 expired 为 false，
            但目录接口已经拒了他 —— 这时候画一颗「开始学习」，点下去就是 403。 */}
        {owned && !expired && !publicCatalog && nextLesson && (
          <div style={{ marginTop: 'var(--s5)' }}>
            <Button
              size="lg" block
              onClick={() => nav(`/learn/${nextLesson.lessonId}?course=${id}`)}
            >
              {started ? '继续学习' : '开始学习'} · {nextLesson.title}
            </Button>
          </div>
        )}

        {!owned && (
          <div style={{ marginTop: 'var(--s5)' }}>
            {preview ? (
              <Button size="lg" block
                      onClick={() => nav(`/learn/${preview.lessonId}?course=${id}`)}>
                免费试听 · {preview.title}
              </Button>
            ) : (
              <Note>这门课暂时还没有放出可以试听的讲次。</Note>
            )}
            <div style={{ marginTop: 'var(--s4)' }}>
              <Note>
                你还没有这门课。已经有激活码的话去<Link to="/activate">激活</Link>；
                也可以在<Link to="/orders">购买课程</Link>里直接下单。
              </Note>
            </div>
          </div>
        )}

        {owned && publicCatalog && (
          <div style={{ marginTop: 'var(--s4)' }}>
            <Note tone="danger">
              {expired
                ? <>观看有效期已于 {(lessons.validUntil || '').slice(0, 10) || '此前'} 结束，视频不再可以播放。</>
                : <>这门课当前不可观看（权限被暂停）。</>}
              {/* 目录来自公开接口，逐讲进度取不到 —— 不能再说「学习记录仍然保留」，
                  那句话配着一整页空进度读起来像"记录没了"。记录确实还在库里，
                  只是这一页现在看不到，说清楚比含糊好。 */}
              需要继续学习请联系平台续期；你的学习记录仍然在，续期后会回到这一页。
            </Note>
          </div>
        )}
        {owned && !publicCatalog && expired && (
          <div style={{ marginTop: 'var(--s4)' }}>
            <Note tone="danger">
              观看有效期已于 {lessons.validUntil?.slice(0, 10)} 结束，视频不再可以播放。
              目录和你的学习记录仍然保留。
            </Note>
          </div>
        )}
      </Card>

      <Row gap="var(--s3)">
        <Link to={`/exam?course=${id}`}><Button variant="ghost">去测评</Button></Link>
        <Link to={`/qa?course=${id}`}><Button variant="ghost">提问答疑</Button></Link>
        <Link to="/cert"><Button variant="ghost">结业证明</Button></Link>
      </Row>

      {lessons.chapters.length === 0 || all.length === 0 ? (
        <Card>
          <Note>
            这门课暂时还没有可以观看的讲次。内容上架后会出现在这里，不需要你做任何事。
          </Note>
        </Card>
      ) : (
        <Stack gap="var(--s5)">
          {lessons.chapters.map((ch, ci) => (
            <div key={ch.chapterId}>
              <div style={{
                fontSize: 'var(--t-sm)', color: 'var(--ink-3)', fontWeight: 600,
                marginBottom: 'var(--s2)', letterSpacing: '.04em',
              }}>
                第 {ci + 1} 章 · {ch.title}
              </div>
              <Card pad={false}>
                {ch.lessons.length === 0 ? (
                  <div style={{ padding: 'var(--s5)', color: 'var(--ink-3)', fontSize: 'var(--t-sm)' }}>
                    本章还没有上架的讲次
                  </div>
                ) : ch.lessons.map((l, li) => (
                  <LessonItem
                    key={l.lessonId} lesson={l} index={lessonNo.get(l.lessonId) ?? li + 1}
                    courseId={id}
                    // 未购时只有先导课点得开。这里挡的是「邀请」，
                    // 真正的闸门在服务端 Entitlements.requireLesson 上 —— 前端只负责别骗人。
                    // 三种形态：已购未过期 → 全开；已购已过期 → 全关（后端也会拒）；
                    // 没买 → 只有先导课点得开。挡的是「邀请」，
                    // 真正的闸门在服务端 Entitlements.requireLesson 上，前端只负责别骗人。
                    disabled={owned ? (expired || publicCatalog) : !l.isPreview}
                    last={li === ch.lessons.length - 1}
                  />
                ))}
              </Card>
            </div>
          ))}
        </Stack>
      )}
    </Stack>
  );
}

/**
 * 一讲 + 它的内容分节。
 *
 * <h3>讲与节的分工</h3>
 * 一讲是「一次坐下来能学完的一段」（这门课约 40 分钟）；一节是讲内的一个知识点，
 * 点它跳到那一秒。2026-08-21 之前四场整录各是一讲、每讲三小时，
 * 那时这里写着「拆不成更多讲次」——理由是水印位区间在码字发出后不许重排。
 * 那条限制在同日被解掉了（码位改成片库内全局片号的纯函数），四场录屏现已切成 16 讲。
 *
 * <h3>默认收起，但正在看的那一讲自动展开</h3>
 * 16 讲一共 90 节，全展开就是一屏翻不完的清单，而学员多数时候只关心手上这一讲。
 * 判据用服务端给的 current（上次停在哪一节），不是「第一讲」——
 * 按序号猜的话，一个已经学到第十讲的人每次进来都要先滚过前九讲。
 */
function LessonItem({ lesson, index, courseId, disabled, last }: {
  lesson: LessonRow; index: number;
  courseId: number; disabled: boolean; last: boolean;
}) {
  const nav = useNavigate();
  const pct = lesson.progressPct ?? 0;
  const sections = lesson.sections ?? [];
  const hasCurrent = sections.some((x) => x.current);
  const [open, setOpen] = React.useState(hasCurrent);
  const go = (t?: number) => nav(
    `/learn/${lesson.lessonId}?course=${courseId}` + (t != null && t > 0 ? `&t=${t}` : ''));
  const body = (
    <>
      <div style={{
        width: 26, flexShrink: 0, textAlign: 'center', fontFamily: 'var(--font-num)',
        color: lesson.completed ? 'var(--ok)' : 'var(--ink-3)', fontSize: 'var(--t-sm)',
      }}>{lesson.completed ? '✓' : index}</div>

      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontSize: 'var(--t-body)', color: disabled ? 'var(--ink-3)' : 'var(--ink)' }}>
          {lesson.title}
          {lesson.isPreview && <span style={{ marginLeft: 'var(--s2)' }}><Tag tone="brand">试听</Tag></span>}
        </div>
        <Row gap="var(--s3)" style={{ marginTop: 4, fontSize: 'var(--t-xs)', color: 'var(--ink-3)' }}>
          <span className="num">{hms(lesson.durationSec)}</span>
          {/* 进度只在真的看过的时候才说。0% 也画一条空进度条，会让整页看起来
              到处都是"没学完"的红字，而其实他只是还没开始。 */}
          {pct > 0 && !lesson.completed && <span>已看 {pctText(pct)}</span>}
          {lesson.completed && <span style={{ color: 'var(--ok)' }}>已学完</span>}
        </Row>
        {pct > 0 && !lesson.completed && (
          <div style={{ marginTop: 'var(--s2)', maxWidth: 220 }}><Progress pct={pct} height={3} /></div>
        )}
      </div>

      <div style={{ flexShrink: 0, color: 'var(--ink-4)' }} aria-hidden>›</div>
    </>
  );

  return (
    <div style={{ borderBottom: last && !open ? 'none' : '1px solid var(--line)' }}>
      <div
        role={disabled ? undefined : 'button'}
        tabIndex={disabled ? undefined : 0}
        onClick={disabled ? undefined : () => go()}
        onKeyDown={disabled ? undefined : (e) => {
          if (e.key === 'Enter' || e.key === ' ') go();
        }}
        style={{
          display: 'flex', gap: 'var(--s3)', alignItems: 'center',
          padding: 'var(--s4) var(--s4)',
          cursor: disabled ? 'default' : 'pointer',
          // 触摸目标至少 44px 高：手机上比 44 小的行，点十次有两次点不中，
          // 而点不中在用户那里读作"卡了"
          minHeight: 56,
        }}
      >{body}</div>

      {sections.length > 0 && (
        <SectionList
          sections={sections} open={open} onToggle={() => setOpen((v) => !v)}
          // ★ 节号的前缀是【跨章连续的讲次号】。用「这一讲在本章里排第几」的话，
          // 四章里会各有一个 1.1，学员之间对暗号（「你看 7.3 那节」）就全对不上了。
          lessonNo={index} disabled={disabled} onGo={go}
        />
      )}
    </div>
  );
}

/** 分节类型里值得单独标出来的那几档。讲解是常态，标了等于每行都有一个一样的标签。 */
const KIND_TAG: Record<string, string> = {
  DEMO: '演示', HANDSON: '实操', QA: '答疑',
};

function SectionList({ sections, open, onToggle, lessonNo, disabled, onGo }: {
  sections: SectionRow[]; open: boolean; onToggle: () => void;
  lessonNo: number; disabled: boolean; onGo: (t?: number) => void;
}) {
  return (
    <>
      <button
        type="button" onClick={onToggle} aria-expanded={open}
        style={{
          display: 'flex', alignItems: 'center', gap: 6, width: '100%',
          padding: '8px var(--s4)', minHeight: 38,
          font: 'inherit', fontSize: 'var(--t-xs)', color: 'var(--ink-3)',
          background: 'var(--surface-2)', border: 'none',
          borderTop: '1px solid var(--line)', cursor: 'pointer', textAlign: 'left',
        }}
      >
        <span aria-hidden style={{
          display: 'inline-block', transition: 'transform var(--dur) var(--ease)',
          transform: open ? 'rotate(90deg)' : 'none',
        }}>›</span>
        本讲分 {sections.length} 节{open ? '' : ' · 展开看每一节讲什么'}
      </button>

      {open && (
        <ol style={{ margin: 0, padding: 0, listStyle: 'none', background: 'var(--surface-2)' }}>
          {sections.map((sec) => (
            <li key={sec.sortNo}>
              <div
                role={disabled ? undefined : 'button'}
                tabIndex={disabled ? undefined : 0}
                onClick={disabled ? undefined : () => onGo(sec.startSec)}
                onKeyDown={disabled ? undefined : (e) => {
                  if (e.key === 'Enter' || e.key === ' ') onGo(sec.startSec);
                }}
                style={{
                  display: 'flex', gap: 'var(--s3)', alignItems: 'flex-start',
                  padding: '10px var(--s4) 10px 34px', minHeight: 44,
                  borderTop: '1px solid var(--line)',
                  cursor: disabled ? 'default' : 'pointer',
                  // ★ 只降不透明度，不换字色。换成 --ink-3 的话，「播过的」与
                  // 「没买课因而点不开的」在屏幕上长得一模一样。
                  opacity: sec.passed && !sec.current ? 0.62 : 1,
                  background: sec.current ? 'var(--brand-soft)' : undefined,
                }}
              >
                {/* 时间码用等宽数字，否则每一行的标题起点都对不齐 */}
                <span className="num" style={{
                  flexShrink: 0, width: 56, fontSize: 'var(--t-xs)',
                  color: 'var(--ink-3)', paddingTop: 2,
                }}>{hms(sec.startSec)}</span>
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ fontSize: 'var(--t-sm)', lineHeight: 'var(--lh-body)' }}>
                    <span className="num" style={{ color: 'var(--ink-3)', marginRight: 6 }}>
                      {lessonNo}.{sec.sortNo}
                    </span>
                    {sec.title}
                    {KIND_TAG[sec.kind] && (
                      <span style={{ marginLeft: 'var(--s2)' }}>
                        <Tag>{KIND_TAG[sec.kind]}</Tag>
                      </span>
                    )}
                    {sec.current && (
                      <span style={{ marginLeft: 'var(--s2)' }}>
                        <Tag tone="brand">上次看到这</Tag>
                      </span>
                    )}
                  </div>
                  {sec.summary && (
                    <div style={{
                      marginTop: 3, fontSize: 'var(--t-xs)', color: 'var(--ink-3)',
                      lineHeight: 'var(--lh-body)',
                    }}>{sec.summary}</div>
                  )}
                </div>
              </div>
            </li>
          ))}
        </ol>
      )}
    </>
  );
}
