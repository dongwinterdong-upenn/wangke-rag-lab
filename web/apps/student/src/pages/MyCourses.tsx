/**
 * 我的课程 —— 一份清单，不是首页。
 *
 * ## 「继续上次」为什么不在这里
 *
 * 它在导览页（pages/Home.tsx）上。同一个动作摆在两屏上，两处迟早不一致 ——
 * 而不一致的表现不是报错，是两张卡片说了两个不同的"上次看到哪"，
 * 学员就再也不敢信任何一个。这一页只回答一件事：我有哪些课、各自学到哪。
 *
 * ## 空态要给下一步，不是"暂无数据"
 *
 * 新用户第一次点进来看到的就是它。他手上多半正攥着一张激活码，
 * 或者刚付完钱在等码 —— 写"暂无数据"等于把人送走。
 */
import React from 'react';
import { Link, useNavigate } from 'react-router-dom';
import {
  Async, Button, Card, Empty, hms, PageHead, Progress, useAsync,
  IconCart, IconCourses,
} from '@wangke/shared';
import type { MyCourse } from '../types';
import { api } from '@wangke/shared';
import { Cover, daysLeft, ListSkeleton, Note, pctText, Row, Screen, Stack, ValidityTag } from '../kit';

export default function MyCourses() {
  const state = useAsync(
    () => api.get<{ courses: MyCourse[] }>('/api/learn/my/courses').then((r) => r.courses),
    [],
  );

  return (
    <Screen>
      <style>{COURSES_CSS}</style>
      <PageHead title="我的课程" desc="这里列出你已经拥有的课程，包括已经到期的。" />
      {state.loading ? <ListSkeleton rows={2} /> : (
        <Async state={state} empty={<NoCourses />}>
          {(courses) => <CourseList courses={courses} />}
        </Async>
      )}
    </Screen>
  );
}

/* ------------------------------------------------------------------ 有数据 */

function CourseList({ courses }: { courses: MyCourse[] }) {
  const expiringSoon = courses.filter((c) => {
    const d = daysLeft(c.validUntil);
    return !c.expired && d !== null && d <= 30;
  });

  return (
    <Stack gap="var(--s5)">
      {expiringSoon.length > 0 && (
        <Note tone="warn">
          {expiringSoon.length === 1
            ? <>《{expiringSoon[0].title}》即将到期（{expiringSoon[0].validUntil.slice(0, 10)}），到期后就不能再观看了。</>
            : <>有 {expiringSoon.length} 门课即将到期，到期后就不能再观看了。</>}
        </Note>
      )}

      <Stack>
        {courses.map((c) => <CourseCard key={c.courseId} c={c} />)}
      </Stack>

      <PurchaseRail />
    </Stack>
  );
}

function PurchaseRail() {
  return (
    <Card className="wk-purchase-rail">
      <div className="wk-purchase-rail-copy">
        <span className="wk-purchase-rail-icon" aria-hidden><IconCart size={22} /></span>
        <div>
          <div className="da-overline">继续拓展学习</div>
          <strong>选购新课程，支付后立即获得激活码</strong>
          <p>已有激活码也可以直接兑换，不需要重复购买。</p>
        </div>
      </div>
      <Row gap="var(--s2)" className="wk-purchase-rail-actions">
        <Link to="/activate"><Button variant="ghost">兑换激活码</Button></Link>
        <Link to="/orders"><Button variant="accent">购买课程</Button></Link>
      </Row>
    </Card>
  );
}

function CourseCard({ c }: { c: MyCourse }) {
  const nav = useNavigate();
  const d = daysLeft(c.validUntil);
  const soon = !c.expired && d !== null && d <= 30;

  return (
    <Card
      style={{
        // 到期的课不隐藏也不删：学员要能看见"我买过、已到期"，客服也要凭它对话。
        // 但要一眼看出它和能看的课不是一档，靠的是整张卡的底色，不是一个小标签。
        background: c.expired ? 'var(--surface-2)' : 'var(--surface)',
        borderColor: soon ? 'var(--warn)' : 'var(--line)',
        cursor: 'pointer',
      }}
      onClick={() => nav(`/course/${c.courseId}`)}
    >
      <Row wrap={false} align="flex-start" className="wk-course-row">
        <Cover url={c.coverUrl} title={c.title} width={104} />
        <div style={{ flex: 1, minWidth: 0 }}>
          <Row gap="var(--s2)" style={{ justifyContent: 'space-between' }}>
            <div style={{
              fontSize: 'var(--t-h3)', fontWeight: 600, color: c.expired ? 'var(--ink-2)' : 'var(--ink)',
            }}>{c.title}</div>
            <ValidityTag validUntil={c.validUntil} expired={c.expired} />
          </Row>

          {c.subtitle && (
            <div className="wk-course-subtitle">{c.subtitle}</div>
          )}

          <div style={{ marginTop: 'var(--s3)' }}>
            <Row gap="var(--s2)" style={{ justifyContent: 'space-between',
                                          fontSize: 'var(--t-sm)', color: 'var(--ink-2)' }}>
              {/* 百分比按后端给的原值向下取整显示，不凑整：
                  看了 99% 写成 100% 之后，学员会拿着"我明明学完了"来问为什么不能考试。 */}
              <span>已学 {pctText(c.progressPct)}</span>
              <span className="num">{c.doneLessons}/{c.totalLessons} 讲 · 共 {hms(c.totalSeconds)}</span>
            </Row>
            <div style={{ marginTop: 'var(--s2)' }}>
              {/* 红条只留给「还在学、还能学」的那几门。
                  学完的和到期的都不需要用户再做什么，用墨色 ——
                  三门课三条红进度，一屏上就没有重点了。 */}
              <Progress pct={c.progressPct}
                        tone={c.expired || c.progressPct >= 100 ? 'ink' : 'brand'} />
            </div>
          </div>

          {c.expired && (
            <div style={{ marginTop: 'var(--s3)', fontSize: 'var(--t-sm)', color: 'var(--ink-2)' }}>
              观看有效期已于 {c.validUntil.slice(0, 10)} 结束，视频不再可以播放。
            </div>
          )}
        </div>
      </Row>
    </Card>
  );
}

/* ------------------------------------------------------------------ 空态 */

function NoCourses() {
  return (
    <Card>
      <Empty
        icon={<IconCourses size={22} />}
        title="你还没有课程"
        desc="你可以直接在线购买，也可以用已有的 16 位激活码兑换课程。"
        action={
          <Stack gap="var(--s3)">
            <Link to="/orders"><Button size="lg" variant="accent">选购课程</Button></Link>
            <Link to="/activate" style={{ fontSize: 'var(--t-sm)' }}>已有激活码？立即兑换</Link>
          </Stack>
        }
      />
    </Card>
  );
}

const COURSES_CSS = `
.wk-course-subtitle {
  margin-top: 2px; color: var(--ink-3); font-size: var(--t-sm);
  overflow: hidden; text-overflow: ellipsis;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical;
}
.wk-purchase-rail {
  display: flex; align-items: center; justify-content: space-between; gap: var(--s5);
  border-color: var(--accent); background: var(--accent-soft);
}
.wk-purchase-rail-copy { display: flex; align-items: center; gap: var(--s4); min-width: 0; }
.wk-purchase-rail-icon {
  display: grid; place-items: center; width: 44px; height: 44px; flex: none;
  color: var(--accent-press); background: var(--surface); border: 1px solid var(--accent);
}
.wk-purchase-rail-copy strong { display: block; margin-top: var(--s1); font-size: var(--t-h3); }
.wk-purchase-rail-copy p { margin-top: 3px; color: var(--ink-2); font-size: var(--t-sm); }
.wk-purchase-rail-actions { flex: none; }
@media (max-width: 700px) {
  .wk-purchase-rail { align-items: stretch; flex-direction: column; }
  .wk-purchase-rail-actions { justify-content: flex-end; }
  /* 窄屏上封面缩一号，把行长让给标题：104px 的封面在 360px 屏上会把
     "AI FDE 工程师就业实训营"挤成四行。 */
  .wk-course-row > :first-child { width: 84px !important; }
}
`;
