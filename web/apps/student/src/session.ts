/**
 * 学员端的本地状态：登录后的手机号、推广码、上次学到哪、协议版本。
 *
 * 全部走 localStorage，且每一处都**能容忍读不到**。理由不是防御性编程，
 * 是这些值在真实设备上确实会消失：iOS 的 Safari 在存储紧张时会清站点数据，
 * 用户自己"清理缓存"更是常事。读不到时必须还能用，只是少一点便利。
 */
import { api, auth, maskPhone } from '@wangke/shared';
import type { Me, Session } from './types';

/**
 * 激活时随手一并落库的服务协议版本（G6 留证）。
 *
 * 后端把它标成必填，因为"激活即不退"这条规则将来在纠纷里唯一能立住的依据就是它。
 * 所以这个常量**不能随手改**：改了它，同一份协议文本会在库里留下两个版本号，
 * 事后谁也说不清用户当时同意的是哪一份。改协议文本时才改这里，且要同步改
 * 下面 AGREEMENT_TEXT 里的条款。
 */
export const AGREEMENT_VERSION = 'v1.0';

/** 协议里与钱直接相关、必须原样出现在勾选框旁边的三条。 */
export const AGREEMENT_POINTS = [
  '激活码一经激活即绑定本账号，不可转让、不可退费',
  '课程自激活之日起 24 个月内可观看，到期后不再可播放',
  '同一账号最多绑定 2 台设备观看',
];

const K = {
  phone: 'wangke.phone',
  ref: 'wangke.ref',
  lastLesson: 'wangke.lastLesson',
} as const;

function read(k: string): string | null {
  try {
    return localStorage.getItem(k);
  } catch {
    return null;
  }
}
function write(k: string, v: string) {
  try {
    localStorage.setItem(k, v);
  } catch {
    /* 无痕模式下会抛，忽略：少一点便利，不该让页面崩 */
  }
}

/* ------------------------------------------------------------------ 手机号 */

/**
 * 记住本人手机号的**完整**形式。
 *
 * 为什么要在前端存这个：播放器的可见水印要能溯源到具体账号，打过码的
 * 138****8888 在一段盗录视频里指向上万个人，等于没有水印。
 * 而 /api/account/me 的 phone 字段出于"别人的手机号绝不出网"这条纪律，返回的是打码后的号 ——
 * 它对**自己**也一样打码。
 *
 * 这里存的不是别人的号，是用户刚刚在登录框里自己输进去的那一个，
 * 从来没有离开过这台设备。取不到时退回打码号（水印仍在，只是溯源精度下降），
 * 绝不因此拒绝播放。
 */
export function rememberPhone(phone: string) {
  const p = phone.replace(/\D/g, '');
  if (p.length === 11) write(K.phone, p);
}
export function myPhone(): string | null {
  return read(K.phone);
}
/**
 * 播放器可见水印的文案：**姓名 + 手机号**。
 *
 * <p>业务方 2026-08-19 的原话是「直接就手机号、姓名写上」，理由也说得很清楚 ——
 * 「带上他个人信息他就不敢传出去了」。这一层挡的是<b>录屏转发</b>：
 * 屏幕上写着自己的名字和号码时，人不会随手把片子丢进群里。
 *
 * <p>姓名取不到就只写号码，<b>绝不因为缺姓名而不画水印</b>：
 * 库里 real_name 对学员多半是空的（原本只有代理申请时会填），
 * 缺一半总比一个字都没有强。
 *
 * <h3>号码为什么用完整的</h3>
 * 打过码的 138****8888 在一段盗录里指向上万个人，等于没有水印。
 * 顺序：服务端给的完整号 > 本机存的号 > 打码号 > 账号 id。
 * 服务端那个排第一，是因为它跨设备一致 —— 本机记录清了缓存就没了，
 * 而"这份盗录属于谁"不该取决于他有没有清过浏览器缓存。
 *
 * <p><b>末档是账号 id，不是"本人账号"那种字样。</b>原来兜底写的是一句人话，
 * 它读起来很自然，但它对每一个用户都一模一样 —— 也就是说，最需要水印的那一档
 * （什么号都取不到）恰好画了一个谁都指不到的东西，而且没有任何迹象说明降级发生过。
 */
export function watermarkText(me?: Me | null): string {
  const name = (me?.realName ?? (me as any)?.real_name ?? '').trim();
  const phone = (me as any)?.phoneFull ?? myPhone() ?? me?.phone ?? null;
  if (phone) return name ? `${name} ${phone}` : phone;
  // 号一个都取不到时退到账号 id。它对每个人不同，仍然指得到人。
  // 【不要退回一句人话】（"本人账号"那种）：它对所有用户完全相同，
  // 也就是说最需要水印的那一档恰好画了一个谁都指不到的东西，
  // 而且画面上看不出降级发生过。
  if (me?.id) return name ? `${name} ID${me.id}` : `ID${me.id}`;
  return name || '未识别账号';
}
/** 页面上一律显示打码号。水印是唯一的例外，且它不出现在任何可复制的文本里。 */
export function displayPhone(me?: Me | null): string {
  return me?.phone ?? maskPhone(myPhone());
}

/* ------------------------------------------------------------------ 推广码 */

/**
 * ?ref= 只在**注册那一刻**有意义（后端 first-touch，之后不再改归属）。
 * 存在 sessionStorage 而不是 localStorage：一次浏览会话内从落地页走到注册页
 * 要带着它，但半年后再打开还带着一个早就无关的推广码，只会让归属算错。
 */
export function rememberRef(ref: string | null) {
  if (!ref) return;
  try {
    sessionStorage.setItem(K.ref, ref.trim());
  } catch {
    /* ignore */
  }
}
export function takeRef(): string | undefined {
  try {
    return sessionStorage.getItem(K.ref) || undefined;
  } catch {
    return undefined;
  }
}

/* ------------------------------------------------------------------ 继续上次 */

export type LastLesson = {
  courseId: number;
  lessonId: number;
  lessonTitle: string;
  positionSec: number;
  at: number;
};

/**
 * "继续上次"的落点。
 *
 * 这是**本机**记录，不是账号级的：后端没有"我最后看的是哪一讲"这个接口，
 * 而进度表是逐讲的最大位置，从里面反推"最后一讲"会挑出进度最高的那讲，
 * 那通常不是他刚才在看的那讲。与其显示一个错的落点，不如只在本机准确。
 * 换设备时这一条不显示，"我的课程"仍然完整可用。
 */
export function rememberLastLesson(v: LastLesson) {
  write(K.lastLesson, JSON.stringify(v));
}
export function lastLesson(): LastLesson | null {
  const raw = read(K.lastLesson);
  if (!raw) return null;
  try {
    const v = JSON.parse(raw) as LastLesson;
    return typeof v?.lessonId === 'number' ? v : null;
  } catch {
    return null;
  }
}
export function forgetLastLesson(courseId?: number) {
  const v = lastLesson();
  if (courseId != null && v && v.courseId !== courseId) return;
  try {
    localStorage.removeItem(K.lastLesson);
  } catch {
    /* ignore */
  }
}

/* ------------------------------------------------------------------ 登录 */

/** 登录成功后统一走这里：令牌、手机号、推广码三件事只在一处收口。 */
export function acceptSession(s: Session, phone: string) {
  auth.set(s.token);
  rememberPhone(phone);
}

export function signOut() {
  auth.clear();
  try {
    localStorage.removeItem(K.phone);
    localStorage.removeItem(K.lastLesson);
  } catch {
    /* ignore */
  }
}

// ★ 路径是 /api/account/me，不是 /api/auth/me。
// /api/auth/ 是 AuthInterceptor 的【免登录白名单前缀】—— 放在那下面的接口
// 拿不到 userId，因为请求根本不经过鉴权。所以"我是谁"这类已登录才有意义的
// 接口一律走 /api/account/。写错的表现是 404，而个人中心把它降级成
// 一句"没有这个接口"，看起来像后端还没做，其实是路径挑错了门。
export const loadMe = () => api.get<{ ok: boolean; user?: Me } & Me>('/api/account/me')
  .then((r) => (r.user ?? r) as Me);
