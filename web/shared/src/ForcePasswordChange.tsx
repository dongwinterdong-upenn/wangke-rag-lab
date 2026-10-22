import React from 'react';
import { ApiError, api, auth } from './api';
import { AuthFrame } from './AuthFrame';
import { Button, Field, Input } from './ui';

/**
 * 强制改密屏。
 *
 * <h3>为什么它必须是一屏「整页」，而不是某个页面里的一个提示条</h3>
 * 后端在 {@code must_change_pwd=1} 时，对 {@code /api/account/**} 以外的每一个接口
 * 都回 403。也就是说这个状态下<b>整个后台一个数据都取不到</b> ——
 * 把改密入口放在某个页面里，那个页面本身就渲染不出来。
 *
 * <p>反过来，如果只有一堵墙没有这扇门，用户看到的是满屏
 * 「请先修改初始密码，再使用其他功能」而<b>没有任何地方能改密码</b>：
 * 一句正确的提示配一条走不通的路，比不提示更让人恼火。
 *
 * <h3>这一屏只用两个接口</h3>
 * {@code POST /api/account/password}（改密）。连「我是谁」都不取 ——
 * 少一个请求就少一处可能 403 的地方，而这一屏本来也不需要显示身份。
 *
 * <h3>改完必须重新登录，这不是多余的一步</h3>
 * 后端改密时 {@code token_version + 1}，此前签出的每一张令牌当场作废 ——
 * 包括<b>把初始密码转达给你的那个人</b>手上可能留下的任何凭据。
 * 那正是强制改密要达到的效果。手里这张也一起作废，所以这里主动清掉并回登录页，
 * 而不是让用户下一次点击时莫名其妙被弹走。
 */
export function ForcePasswordChange({ brand, hint, onDone }: {
  brand: string;
  /** 后端那句话，原样透出去 —— 用户需要知道自己为什么被挡在这里 */
  hint?: string;
  /** 改完之后往哪走。不传就清令牌 + 整页跳到本端的登录页 */
  onDone?: () => void;
}) {
  const [oldPwd, setOldPwd] = React.useState('');
  const [pwd, setPwd] = React.useState('');
  const [pwd2, setPwd2] = React.useState('');
  const [busy, setBusy] = React.useState(false);
  const [err, setErr] = React.useState<string | null>(null);
  const [ok, setOk] = React.useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (pwd !== pwd2) { setErr('两次输入的新密码不一样'); return; }
    if (pwd === oldPwd) { setErr('新密码不能和初始密码一样 —— 那等于没改'); return; }
    setBusy(true); setErr(null);
    try {
      await api.post('/api/account/password', { oldPassword: oldPwd, newPassword: pwd });
      setOk(true);
      setTimeout(() => {
        if (onDone) { onDone(); return; }
        auth.clear();
        // 路径必须带 base：三端分别挂在 /study/ /agent/ /admin/ 子路径下，
        // 写死 '/login' 会落到站点根的落地页 —— 而开发态 base 是 '/'，测不出来。
        const base = import.meta.env.BASE_URL.replace(/\/$/, '');
        window.location.assign(`${base}/login`);
      }, 1200);
    } catch (e2) {
      setErr(e2 instanceof ApiError ? e2.message : '改密失败，请重试');
    } finally {
      setBusy(false);
    }
  }

  return (
    <AuthFrame
      brand={brand}
      kicker="SECURITY · 首次登录"
      headline="先把密码改掉，再开始用"
      lede="这个账号现在用的是别人转达给你的初始密码 —— 在你改掉之前，知道那串密码的人和你有同样的权限。"
      points={[
        '改完之后，此前签发的所有登录状态立即作废',
        '包括把初始密码转达给你的那个人手上可能留下的任何凭据',
        '新密码至少 8 位，要同时有字母和数字',
      ]}
      title="设置你自己的密码"
      desc="改好之前，其他功能都用不了。这不是故障。"
      note="请勿把新密码告诉任何人，包括平台工作人员。平台不会向你索要密码。"
    >
      <form onSubmit={submit}>
        {hint && !ok && (
          <div style={{
            marginBottom: 'var(--s4)', padding: 'var(--s3)',
            background: 'var(--warn-soft, var(--da-bg-2))',
            border: '1px solid var(--line)', borderRadius: 'var(--r-md)',
            fontSize: 'var(--t-sm)', color: 'var(--ink-2)',
          }}>{hint}</div>
        )}
        {ok ? (
          <div style={{ fontSize: 'var(--t-sm)', color: 'var(--ink-2)', lineHeight: 1.7 }}>
            密码已改好。之前签出的登录状态全部作废，正在带你回登录页 —— 请用新密码登录。
          </div>
        ) : (
          <>
            <Field label="初始密码" hint="别人转达给你的那一串">
              <Input type="password" value={oldPwd} onChange={e => setOldPwd(e.target.value)}
                     autoComplete="current-password" />
            </Field>
            <Field label="新密码" hint="至少 8 位，要同时有字母和数字">
              <Input type="password" value={pwd} onChange={e => setPwd(e.target.value)}
                     autoComplete="new-password" />
            </Field>
            <Field label="再输一次新密码">
              <Input type="password" value={pwd2} onChange={e => setPwd2(e.target.value)}
                     autoComplete="new-password" />
            </Field>
            {err && (
              <div style={{ marginBottom: 'var(--s4)', fontSize: 'var(--t-sm)',
                            color: 'var(--danger)' }}>{err}</div>
            )}
            <Button type="submit" block size="lg" loading={busy}
                    disabled={!oldPwd || pwd.length < 8 || !pwd2}>
              改好密码，开始使用
            </Button>
            <div style={{ marginTop: 'var(--s4)', textAlign: 'center' }}>
              <Button variant="quiet" size="sm" type="button"
                      onClick={() => {
                        auth.clear();
                        const base = import.meta.env.BASE_URL.replace(/\/$/, '');
                        window.location.assign(`${base}/login`);
                      }}>
                换个账号登录
              </Button>
            </div>
          </>
        )}
      </form>
    </AuthFrame>
  );
}
