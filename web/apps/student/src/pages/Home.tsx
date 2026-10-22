/**
 * 学习中心导览 —— 学员登录后看到的第一屏。
 *
 * ## 为什么首页不再直接是课程列表
 *
 * 平台正在从"一门课"长成"多个模块"（课程、测评、答疑，往后还会有更多课程）。
 * 首页直接是课程列表的话，新模块只能挤进底部标签栏，而标签栏一超过五格
 * 就没人看得清了。所以首页改成一层**导览**：它自己不承载内容，
 * 只回答"这里有什么、哪一样现在需要我"。
 *
 * ## 但它必须先让人一步接着看
 *
 * 导览页最容易变成"多点一次才能开始学"。回来的人十有八九是接着看，
 * 所以「继续上次」在导览页的最上面、是这一屏唯一的大按钮 ——
 * 模块格子排在它下面。少了这条，导览页对老学员就是纯粹的成本。
 *
 * ## 每个格子上的数字都必须是真的
 *
 * 格子上写"1 份测评可以参加"是要负责的：点进去必须真的能考。所以那些数字
 * 全部来自 /api/home/overview，由后端向测评接口逐份问出来 ——
 * 前端不重算一遍"进度够不够"。两处式子迟早分叉，而分叉的表现是
 * 首页说能考、点进去被拒。
 *
 * 取不到的那一片后端给的是 null（不是 0），界面上写"暂时取不到" ——
 * 一个"0 条等回复"的角标读起来是"没事要办"，那是故障时最不该说的话。
 */
import React from 'react';
import { Link } from 'react-router-dom';
import {
  api, Async, Button, Card, hms, Progress, useAsync,
  IconArrowRight, IconCart, IconCert, IconCourses, IconKey,
  type IconProps,
} from '@wangke/shared';
import type { MyCourse, Overview } from '../types';
import { forgetLastLesson, lastLesson } from '../session';
import { pctText, Screen, Stack } from '../kit';

export default function Home() {
  const state = useAsync(() => api.get<Overview>('/api/home/overview'), []);

  return (
    <Screen>
      <style>{HOME_CSS}</style>
      {/* 没有页面大标题：顶栏已经写着「学习中心」，再写一遍等于把第一屏
          最值钱的那段高度让给一句重复的话 —— 手机上它正好把「接着看」挤下去半屏。
          这一页的开场是那张续播卡本身。 */}
      <Stack gap="var(--s5)">
        <Async state={state}>{(o) => <Hub o={o} />}</Async>
      </Stack>
    </Screen>
  );
}

function Hub({ o }: { o: Overview }) {
  const last = lastLesson();
  // 上次看的课已经退掉、下架或到期了，那条"接着看"就是个死链，点进去只会 403。
  // 宁可不显示 —— 一个点了报错的按钮比没有这个按钮更伤。
  const lastCourse = last
    ? o.courses.items.find((c) => c.courseId === last.courseId && !c.expired)
    : undefined;
  React.useEffect(() => { if (last && !lastCourse) forgetLastLesson(); }, [last, lastCourse]);

  const current = lastCourse
    || o.courses.items.find((c) => !c.expired)
    || o.courses.items[0];

  return (
    <>
      {current
        ? <FocusCard course={current} last={lastCourse ? last : null} />
        : <NoCourseCard />}

      <nav className="wk-home-modules" aria-label="模块">
        <Module
          to="/courses" Icon={IconCourses} title="我的课程"
          {...coursesStat(o)}
        />
        <Module
          to="/assess" Icon={IconCert} title="测评系统"
          {...assessStat(o)}
        />
        <Module
          to="/orders" Icon={IconCart} title="选课与激活" accent
          stat="购买课程 · 兑换激活码"
          desc="支付后立即拿到激活码"
        />
      </nav>

      <div className="wk-home-tail">
        <Link to="/activate" className="wk-home-tail-link">
          <IconKey size={15} /> 有激活码？直接兑换
        </Link>
        {/* 这句话是给"我买的课怎么还没出现"的人看的。它不是一句客套的
            "敬请期待"：新上架的课程与新模块确实会自己出现在这一页，
            学员不需要做任何事，说清楚就少一通客服电话。 */}
        <p>之后上新的课程与模块会直接出现在这一页，不需要你做什么。</p>
      </div>
    </>
  );
}

/* ================================================================ 继续上次 */

function FocusCard({ course, last }: {
  course: MyCourse; last: ReturnType<typeof lastLesson>;
}) {
  const canLearn = !course.expired;
  const to = last
    ? `/learn/${last.lessonId}?course=${last.courseId}`
    : `/course/${course.courseId}`;

  return (
    <Card pad={false} className="wk-focus">
      <div className="wk-focus-body">
        <div className="da-overline">{last ? '继续上次' : '当前课程'}</div>
        <h2>{last?.lessonTitle || course.title}</h2>
        {last && (
          <div className="wk-focus-pos">
            {course.title} · 看到 <span className="num">{hms(last.positionSec)}</span>
          </div>
        )}
        <div className="wk-focus-meta">
          <span>已学 {pctText(course.progressPct)}</span>
          <span className="num">{course.doneLessons}/{course.totalLessons} 讲</span>
        </div>
        <Progress pct={course.progressPct} tone={course.expired ? 'ink' : 'brand'} />
        {canLearn ? (
          <Link to={to} className="wk-focus-go">
            <Button size="lg" block>{last ? '接着看' : '开始学习'}</Button>
          </Link>
        ) : (
          <div className="wk-focus-expired">
            这门课已到期，暂时不能继续播放。续期后会回到这一页。
          </div>
        )}
      </div>
    </Card>
  );
}

function NoCourseCard() {
  return (
    <Card className="wk-focus-empty">
      <div className="da-overline">还没有课程</div>
      <h2>先把课程放进来，就可以开始学了</h2>
      <p>已经有 16 位激活码的话直接兑换；没有的话可以在线选购，付款后立即拿到码。</p>
      <div className="wk-focus-empty-actions">
        <Link to="/orders"><Button size="lg" variant="accent">选购课程</Button></Link>
        <Link to="/activate"><Button size="lg" variant="ghost">兑换激活码</Button></Link>
      </div>
    </Card>
  );
}

/* ================================================================ 模块格子 */

type Stat = { stat: string; desc: string; alert?: boolean };

function coursesStat(o: Overview): Stat {
  const c = o.courses;
  if (c.total === 0)
    return { stat: '还没有课程', desc: '兑换激活码或选购之后，课程会出现在这里' };
  const parts = [`${c.active} 门在学`];
  if (c.expired > 0) parts.push(`${c.expired} 门已到期`);
  return {
    stat: parts.join(' · '),
    desc: `已学 ${pctText(c.progressPct)}，${c.doneLessons}/${c.totalLessons} 讲已完成`,
  };
}

function assessStat(o: Overview): Stat {
  const a = o.assess;
  // null = 后端这一片取不到。这里必须说"取不到"，不能顺手写成"没有可参加的测评" ——
  // 后者是一句我们并不知道真假的话。
  if (!a) return { stat: '状态暂时取不到', desc: '点进去看看，或稍后再回来' };

  const q = o.qa;
  const desc = !q
    ? '课程测评、答疑、结业证明都在这里'
    : q.waiting > 0
      ? `${q.waiting} 条问题在等老师回复`
      : q.humanAnswered > 0
        ? `老师回复过 ${q.humanAnswered} 条问题`
        : '课程测评、答疑、结业证明都在这里';

  // 量词是「份」不是「门」：2026-09-03 起后端按**卷**计数，一门课下面有五份卷
  // （综合卷加四份章节卷），另有一份开放卷。写"5 门可以参加测评"的时候
  // 学员手上只有一门课 —— 那个数他对不上，只会以为平台算错了。
  //
  // 顺序按"越要紧的越先说"：没交卷的那一场时间还在走，它压过一切。
  if (a.inProgressCount > 0)
    return { stat: `${a.inProgressCount} 场测评还没交卷`, desc, alert: true };
  if (a.readyCount > 0)
    return { stat: `${a.readyCount} 份测评可以参加`, desc, alert: true };
  if (a.passedCount > 0)
    return { stat: `已通过 ${a.passedCount} 份测评`, desc };
  // 一份都不能考时不猜原因：进度门槛、次数用完、冷却没过、卷还没上架都可能，
  // 而这一行只有一句话的位置 —— 猜错的那句会把学员支去做一件没用的事
  // （原来写死的是"学到规定进度后可以参加"，而章节卷与开放卷根本没有进度门槛）。
  return { stat: '暂时没有可以参加的测评', desc };
}

function Module({ to, Icon, title, stat, desc, alert, accent }: Stat & {
  to: string; Icon: React.ComponentType<IconProps>; title: string; accent?: boolean;
}) {
  return (
    <Link to={to} className={`wk-module${accent ? ' wk-module-accent' : ''}`}>
      <span className="wk-module-icon" aria-hidden><Icon size={20} /></span>
      <span className="wk-module-copy">
        <strong>
          {title}
          {alert && <span className="wk-module-dot" aria-label="有待办" />}
        </strong>
        <span className="wk-module-stat">{stat}</span>
        <span className="wk-module-desc">{desc}</span>
      </span>
      <span className="wk-module-go" aria-hidden><IconArrowRight size={16} /></span>
    </Link>
  );
}

const HOME_CSS = `
.wk-focus { overflow: hidden; border-color: var(--line-strong); }
.wk-focus-body { padding: var(--s5) var(--s4) var(--s4); }
.wk-focus-body h2 { margin-top: var(--s3); font-size: 20px; line-height: 1.4; color: var(--ink-0); }
.wk-focus-pos { margin-top: var(--s2); color: var(--ink-2); font-size: var(--t-sm); }
.wk-focus-meta {
  display: flex; justify-content: space-between; gap: var(--s3);
  margin: var(--s4) 0 var(--s2); color: var(--ink-2); font-size: var(--t-sm);
}
.wk-focus-go { display: block; margin-top: var(--s4); text-decoration: none; }
.wk-focus-expired {
  margin-top: var(--s4); padding: var(--s3); color: var(--ink-2);
  background: var(--surface-2); border-left: 2px solid var(--ink-3); font-size: var(--t-sm);
}
.wk-focus-empty h2 { margin-top: var(--s2); font-size: 20px; line-height: 1.4; }
.wk-focus-empty p { margin-top: var(--s2); color: var(--ink-2); font-size: var(--t-sm); }
.wk-focus-empty-actions { display: flex; flex-wrap: wrap; gap: var(--s3); margin-top: var(--s4); }

/* 格子做成"横条"而不是方块：横条在 360px 到 1200px 之间是同一个形状，
   只是一行变两列。方块在窄屏上会把标题挤成两行、把说明挤没。 */
/* 单列。两列排三格的话第三格孤零零占半行，而半行宽度装不下那句说明 ——
   它会被省略号截掉，且截掉之后没有任何提示。单列在 360px 到 720px 之间是同一个形状。 */
.wk-home-modules { display: grid; gap: var(--s3); }
.wk-module {
  display: flex; align-items: center; gap: var(--s3);
  padding: var(--s4); min-height: 92px;
  color: var(--ink); text-decoration: none;
  background: var(--surface); border: 1px solid var(--line);
  transition: border-color var(--dur) var(--ease), background var(--dur) var(--ease);
  -webkit-tap-highlight-color: transparent;
}
.wk-module:hover { border-color: var(--line-strong); text-decoration: none; }
.wk-module:active { background: var(--surface-2); }
.wk-module-icon {
  display: grid; place-items: center; width: 42px; height: 42px; flex: none;
  color: var(--brand); border: 1px solid var(--brand); border-radius: var(--r-full);
}
.wk-module-copy { display: grid; gap: 2px; min-width: 0; flex: 1; }
.wk-module-copy strong { display: flex; align-items: center; gap: 6px; font-size: var(--t-body); }
.wk-module-stat {
  color: var(--ink); font-size: var(--t-sm); font-weight: 600;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.wk-module-desc {
  color: var(--ink-3); font-size: var(--t-xs);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.wk-module-dot {
  width: 6px; height: 6px; flex: none; border-radius: 50%; background: var(--accent);
}
.wk-module-go { color: var(--ink-3); flex: none; }
.wk-module-accent { border-color: var(--accent); background: var(--accent-soft); }
.wk-module-accent .wk-module-icon { color: var(--accent-press); border-color: var(--accent); }

.wk-home-tail { display: grid; gap: var(--s2); justify-items: center; text-align: center; }
.wk-home-tail-link {
  display: inline-flex; align-items: center; gap: 6px; font-size: var(--t-sm);
}
.wk-home-tail p { color: var(--ink-3); font-size: var(--t-xs); }
`;
