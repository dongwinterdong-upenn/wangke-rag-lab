/**
 * 激活码兑换。
 *
 * 这一页的每一处细节都对应一类真实的失败：
 *
 *  - **分段输入**：16 位连成一串，用户对不上位置，输错了也看不出错在哪。
 *  - **自动大写 / 自动跳格 / 整串粘贴**：码是从微信里复制来的，
 *    常常带着零宽字符、全角横杠、多余空格。要求用户"手工清理一下"是荒谬的。
 *  - **G→6、V→U 的纠正**：与后端 ActivationCodes.normalize 同一套规则。
 *    这两组是易混组里"只有一个成员在字母表内"的那两组，所以纠正是安全的；
 *    其余易混组（0O/1I/2Z/5S/8B）两个都不在表内，猜哪个都是错，所以不猜，
 *    直接把位置指出来让用户核对。
 *  - **失败按 reason 分型**：后端把失败原因做成了接口的一部分，就是为了
 *    让前端能说出"这个码已经被别人激活了"和"你已经有这门课了，码没被消耗"
 *    这两句完全不同的话。都显示"激活失败"的话，第二种情况的用户会以为
 *    自己那张码作废了 —— 而它其实还能转给别人。
 */
import React from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { ApiError, api, Button, Card, dateOnly, PageHead } from '@wangke/shared';
import { AGREEMENT_POINTS, AGREEMENT_VERSION } from '../session';
import { Confirm, Note, Screen, Stack, Row } from '../kit';

/** 与后端 ActivationCodes.ALPHABET 逐字对齐。改这里必须同时改那边。 */
const ALPHABET = 'ACDEFHJKLMNPQRTUWXY34679';
const GROUPS = 4;
const PER = 4;

/** 把任意输入规整成码里会出现的字符。与后端 normalize 同一套动作。 */
function clean(raw: string): string {
  let out = '';
  for (const ch0 of raw.toUpperCase()) {
    let ch = ch0;
    if (!/[A-Z0-9]/.test(ch)) continue;      // 横杠、空格、全角符号、零宽字符一律丢掉
    if (ch === 'G') ch = '6';
    if (ch === 'V') ch = 'U';
    out += ch;
  }
  return out.slice(0, GROUPS * PER);
}

/** 本地先判一次形状，能省掉一次白等的网络往返，且能把错指到具体第几位。 */
function localProblem(code: string): string | null {
  if (code.length === 0) return null;
  for (let i = 0; i < code.length; i++) {
    if (ALPHABET.indexOf(code[i]) < 0) {
      return `第 ${i + 1} 位『${code[i]}』不是激活码里会出现的字符，请核对`;
    }
  }
  if (code.length < GROUPS * PER) return null;   // 还没输完，不催
  return null;
}

type Result = { courseId: number; validUntil: string; firstTime: boolean; message: string };

export default function Activate() {
  const [sp] = useSearchParams();
  const fromRegister = sp.get('from') === 'register';

  const [parts, setParts] = React.useState<string[]>(Array(GROUPS).fill(''));
  const [agreed, setAgreed] = React.useState(false);
  const [confirming, setConfirming] = React.useState(false);
  const [busy, setBusy] = React.useState(false);
  const [fail, setFail] = React.useState<{ title: string; detail: string; tone: 'danger' | 'warn' } | null>(null);
  const [done, setDone] = React.useState<Result | null>(null);
  const boxes = React.useRef<(HTMLInputElement | null)[]>([]);

  const code = parts.join('');
  const shaped = code.length === GROUPS * PER;
  const problem = localProblem(code);

  const setPart = (i: number, v: string) => {
    const cleaned = clean(v);
    const next = [...parts];
    // 一次粘进来的可能不止一格的量，往后面的格子里溢出去
    let rest = cleaned;
    for (let k = i; k < GROUPS && rest.length > 0; k++) {
      next[k] = rest.slice(0, PER);
      rest = rest.slice(PER);
    }
    if (cleaned.length === 0) next[i] = '';
    setParts(next);
    setFail(null);

    const filledTo = Math.min(GROUPS - 1, i + Math.max(0, Math.ceil(cleaned.length / PER) - 1));
    if (next[filledTo].length === PER && filledTo < GROUPS - 1) {
      boxes.current[filledTo + 1]?.focus();
    }
  };

  const onPaste = (i: number, e: React.ClipboardEvent) => {
    const text = e.clipboardData.getData('text');
    if (!text) return;
    e.preventDefault();
    const cleaned = clean(text);
    // 整串粘贴一律从第一格开始铺：用户复制的是完整的码，
    // 从光标所在格开始铺会把码错位，而错位之后的报错是"第 5 位不对"，
    // 他会以为是自己的码有问题。
    const from = cleaned.length > PER ? 0 : i;
    const next = [...parts];
    let rest = cleaned;
    for (let k = from; k < GROUPS; k++) {
      next[k] = rest.slice(0, PER);
      rest = rest.slice(PER);
    }
    setParts(next);
    setFail(null);
    boxes.current[Math.min(GROUPS - 1, from + Math.ceil(cleaned.length / PER) - 1)]?.focus();
  };

  const onKeyDown = (i: number, e: React.KeyboardEvent<HTMLInputElement>) => {
    const el = e.currentTarget;
    if (e.key === 'Backspace' && el.selectionStart === 0 && el.selectionEnd === 0 && i > 0) {
      e.preventDefault();
      boxes.current[i - 1]?.focus();
      const next = [...parts];
      next[i - 1] = next[i - 1].slice(0, -1);
      setParts(next);
    }
    if (e.key === 'ArrowLeft' && el.selectionStart === 0 && i > 0) boxes.current[i - 1]?.focus();
    if (e.key === 'ArrowRight' && el.selectionStart === el.value.length && i < GROUPS - 1) {
      boxes.current[i + 1]?.focus();
    }
  };

  const submit = async () => {
    setBusy(true);
    setFail(null);
    try {
      const r = await api.post<Result>('/api/learn/activate', {
        code: parts.join('-'),
        agreementVersion: AGREEMENT_VERSION,
      });
      setDone(r);
      setConfirming(false);
    } catch (e) {
      setConfirming(false);
      setFail(explain(e));
    } finally {
      setBusy(false);
    }
  };

  if (done) {
    return (
      <SuccessScreen
        result={done}
        onAgain={() => {
          // 就地清干净重来，不走 location.reload()：整页重载会把 SPA 冷启一遍，
          // 手机上是一秒多的白屏，而这里其实只是想清空四个输入框。
          setDone(null);
          setParts(Array(GROUPS).fill(''));
          setAgreed(false);
          setFail(null);
        }}
      />
    );
  }

  return (
    <Screen maxWidth={560}>
      <PageHead
        title="激活课程"
        desc="输入你买课时拿到的 16 位激活码。"
      />

      <Stack gap="var(--s5)">
        {fromRegister && (
          <Note tone="ok">账号已经创建好了。把激活码输进去，课程就会出现在「我的课程」里。</Note>
        )}

        <Card>
          <Stack gap="var(--s4)">
            <div style={{ fontSize: 'var(--t-sm)', color: 'var(--ink-2)' }}>激活码</div>

            <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--s2)' }}>
              {parts.map((p, i) => (
                <React.Fragment key={i}>
                  {i > 0 && <span aria-hidden style={{ color: 'var(--ink-4)' }}>–</span>}
                  <input
                    ref={(el) => { boxes.current[i] = el; }}
                    value={p}
                    onChange={(e) => setPart(i, e.target.value)}
                    onPaste={(e) => onPaste(i, e)}
                    onKeyDown={(e) => onKeyDown(i, e)}
                    aria-label={`激活码第 ${i + 1} 段`}
                    inputMode="text"
                    autoCapitalize="characters"
                    autoCorrect="off"
                    spellCheck={false}
                    autoFocus={i === 0}
                    maxLength={PER}
                    disabled={busy}
                    style={{
                      // 分段框宽度按 4 个等宽字符算，不按百分比：百分比在
                      // 小屏上会把 4 个字挤成两行半，看起来像坏了
                      width: '100%', minWidth: 0, textAlign: 'center',
                      padding: 'var(--s3) var(--s1)',
                      fontFamily: 'var(--font-num)', fontSize: 'var(--t-h2)',
                      letterSpacing: '.08em', textTransform: 'uppercase',
                      background: 'var(--surface)',
                      border: `1px solid ${problem ? 'var(--danger)' : 'var(--line-strong)'}`,
                      borderRadius: 'var(--r-md)', outline: 'none',
                    }}
                  />
                </React.Fragment>
              ))}
            </div>

            <div style={{ fontSize: 'var(--t-xs)', color: 'var(--ink-3)' }}>
              大小写都可以，横杠会自动补。整串复制粘贴到任意一格都能正确分配。
            </div>

            {problem && <Note tone="danger">{problem}</Note>}
          </Stack>
        </Card>

        <Card>
          <label style={{ display: 'flex', gap: 'var(--s3)', alignItems: 'flex-start', cursor: 'pointer' }}>
            <input
              type="checkbox" checked={agreed} onChange={(e) => setAgreed(e.target.checked)}
              style={{ marginTop: 3, width: 18, height: 18, flexShrink: 0 }}
            />
            <span style={{ fontSize: 'var(--t-sm)', lineHeight: 'var(--lh-body)' }}>
              我已阅读并同意以下条款：
              <ul style={{ margin: 'var(--s2) 0 0', paddingLeft: 'var(--s5)', color: 'var(--ink-2)' }}>
                {AGREEMENT_POINTS.map((t) => <li key={t}>{t}</li>)}
              </ul>
            </span>
          </label>
        </Card>

        {fail && (
          <Note tone={fail.tone}>
            <b>{fail.title}</b>
            <div style={{ marginTop: 'var(--s1)' }}>{fail.detail}</div>
          </Note>
        )}

        <Button
          size="lg" block variant="accent"
          disabled={!shaped || !!problem || !agreed || busy}
          onClick={() => setConfirming(true)}
        >
          激活
        </Button>

        <div style={{ fontSize: 'var(--t-xs)', color: 'var(--ink-3)', textAlign: 'center' }}>
          激活码找不到了？请联系把课程卖给你的那一方，平台无法凭手机号补发。
        </div>
      </Stack>

      <Confirm
        open={confirming}
        title="确认激活这门课？"
        confirmText="确认激活"
        tone="accent"
        busy={busy}
        onConfirm={submit}
        onClose={() => setConfirming(false)}
        consequence={
          <>
            <b>激活码一经激活即绑定本账号，概不退费、不可转让。</b>
            <div style={{ marginTop: 'var(--s1)' }}>
              课程从激活当天起 24 个月内可观看。如果这张码是要给别人用的，现在还来得及停下。
            </div>
          </>
        }
      >
        <div style={{ fontSize: 'var(--t-sm)', color: 'var(--ink-2)' }}>
          即将激活：<span className="num" style={{ color: 'var(--ink)', fontWeight: 600 }}>
            {parts.join('-')}
          </span>
        </div>
      </Confirm>
    </Screen>
  );
}

/* ------------------------------------------------------------------ 失败分型 */

/**
 * 把后端的 reason 翻成学员能据此**采取行动**的话。
 *
 * 每一条的落点都不同：有的该去找卖家，有的该直接去学，有的只是打错了一位。
 * 统一成"激活失败"的话，这三种人都会打客服电话。
 */
function explain(e: unknown): { title: string; detail: string; tone: 'danger' | 'warn' } {
  const err = e as ApiError;
  const reason = err?.reason;
  switch (reason) {
    case 'ALREADY_BOUND_BY_OTHER':
      return {
        tone: 'danger',
        title: '这个码已经被别人激活了',
        detail: '一码一人，激活之后不能转移。如果这张码是你买的，请联系把码卖给你的那一方核实。',
      };
    case 'ALREADY_ENROLLED':
      return {
        tone: 'warn',
        title: '你已经有这门课了',
        detail: '这张码没有被消耗，还是完好的，可以转给别人用。直接去「我的课程」就能开始学。',
      };
    case 'EXPIRED':
      return {
        tone: 'danger',
        title: '这个码已经过了激活期限',
        detail: '激活码从发出之日起有激活时限，过期后无法再兑换。请联系把码卖给你的那一方处理。',
      };
    case 'REVOKED':
      return {
        tone: 'danger',
        title: '这个码已经被平台作废',
        detail: '通常是因为对应的订单发生了退款。请联系把码卖给你的那一方。',
      };
    case 'NOT_FOUND':
      return {
        tone: 'danger',
        title: '没有找到这个激活码',
        detail: '请再核对一遍每一位。注意 0 和 O、1 和 I、2 和 Z、5 和 S、8 和 B 都不会出现在激活码里。',
      };
    case 'MALFORMED':
      return { tone: 'danger', title: '激活码格式不对', detail: err?.message || '请核对后重新输入。' };
    case 'USER_NOT_ACTIVE':
      return {
        tone: 'danger',
        title: '当前账号不能激活课程',
        detail: '你的账号被冻结了。请联系平台客服处理后再试。',
      };
    case 'AGREEMENT_REQUIRED':
      return { tone: 'danger', title: '还没有勾选服务协议', detail: '请先勾选上面的条款再激活。' };
    default:
      return {
        tone: 'danger',
        title: '激活没有成功',
        detail: err?.message || '请稍后重试。如果一直不行，请联系平台客服。',
      };
  }
}

/* ------------------------------------------------------------------ 成功 */

function SuccessScreen({ result, onAgain }: { result: Result; onAgain: () => void }) {
  const nav = useNavigate();
  return (
    <Screen maxWidth={520}>
      <Card style={{ textAlign: 'center', padding: 'var(--s7) var(--s5)' }}>
        <div aria-hidden style={{
          width: 56, height: 56, margin: '0 auto var(--s5)', borderRadius: 'var(--r-full)',
          background: 'var(--ok-soft)', color: 'var(--ok)',
          display: 'grid', placeItems: 'center', fontSize: 'var(--t-h1)',
        }}>✓</div>

        <h2 style={{ fontSize: 'var(--t-h1)' }}>
          {result.firstTime ? '激活成功' : '这门课你已经激活过了'}
        </h2>

        <div style={{ color: 'var(--ink-2)', marginTop: 'var(--s3)', fontSize: 'var(--t-body)' }}>
          课程已加入「我的课程」。
        </div>

        <div style={{
          margin: 'var(--s5) 0', padding: 'var(--s4)', background: 'var(--surface-2)',
          borderRadius: 'var(--r-md)', fontSize: 'var(--t-body)',
        }}>
          可观看到 <b className="num">{dateOnly(result.validUntil)}</b>
          <div style={{ fontSize: 'var(--t-sm)', color: 'var(--ink-3)', marginTop: 'var(--s1)' }}>
            到期后视频不再可以播放，请留意这个日期
          </div>
        </div>

        <Stack gap="var(--s3)">
          <Button size="lg" block onClick={() => nav(`/course/${result.courseId}`)}>去学习</Button>
          <Row gap="var(--s4)" style={{ justifyContent: 'center' }}>
            <Link to="/" style={{ fontSize: 'var(--t-sm)' }}>回我的课程</Link>
            <button type="button" onClick={onAgain} style={{
              background: 'none', border: 'none', padding: 0, cursor: 'pointer',
              color: 'var(--brand)', fontSize: 'var(--t-sm)',
            }}>再激活一张</button>
          </Row>
        </Stack>
      </Card>
    </Screen>
  );
}
