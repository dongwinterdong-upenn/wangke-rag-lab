import React from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { ApiError, api } from '@wangke/shared/api';
import { Button, Field, Input } from '@wangke/shared/ui';
import { AuthFrame } from '@wangke/shared/auth-frame';
import { Note } from '../lib/kit';
import { session } from '../lib/session';

/**
 * 后台登录。
 *
 * 两处刻意的处理：
 *
 * ① **登错角色要当场说清楚**，而不是放他进去然后每个页面都报 403。
 *    后台的每个接口都要求 PLATFORM_ADMIN，一个代理商用自己的号登进来，
 *    看到的会是十一个页面十一条「没有权限」，他会以为后台坏了。
 *
 * ② **首次登录强制改密有一条真的出路**。平台开户的初始密码由人转达，
 *    库里 must_change_pwd=1。如果这里只显示一句「请改密」却不给入口，
 *    那句提示就是一堵墙。改密走的是短信验证码那条正式通道
 *    （POST /api/auth/sms → /api/auth/password/reset），改完令牌版本 +1，
 *    转达过初始密码的那个人手上的凭据当场作废 —— 这正是强制改密要的效果。
 */
export function Login() {
  const nav = useNavigate();
  const [sp] = useSearchParams();
  const [phone, setPhone] = React.useState('');
  const [password, setPassword] = React.useState('');
  const [err, setErr] = React.useState<string | null>(
    sp.get('wrongRole') ? '这个账号不是平台管理员，进不了后台。代理商请用代理端，学员请用学员端。' : null);
  const [busy, setBusy] = React.useState(false);
  const [mode, setMode] = React.useState<'login' | 'reset'>('login');

  async function login(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true); setErr(null);
    try {
      const r = await api.post<any>('/api/auth/login/password', { phone: phone.trim(), password });
      const s = r.session;
      if (s?.role !== 'PLATFORM_ADMIN') {
        setErr('这个账号不是平台管理员，进不了后台。代理商请用代理端，学员请用学员端。');
        return;
      }
      if (s.mustChangePwd) {
        // ★ 这一档【刻意不 session.save】。密码是别人转达给他的，在他自己改掉之前，
        //   这个号等于两个人共用 —— 存下令牌就等于把门打开了：
        //   原来先存再 setMode('reset')，而 mode 只是组件 state，按一下 F5 就没了，
        //   会话却已经落进 localStorage。刷新后 App 读到非空会话直接渲染整个后台，
        //   十一个页面全部可用，强制改密等于没有。转达过初始密码的那个人
        //   照样能这么进来，而且一直有效。
        //
        //   下面 ResetForm 走的是手机号 + 短信验证码（/api/auth/** 免登录），
        //   全程不需要令牌，所以不存也不影响改密。
        setErr(null);
        setMode('reset');
        return;
      }
      session.save({
        token: s.token, userId: s.userId, role: s.role,
        phoneMasked: s.phoneMasked, mustChangePwd: false,
      });
      nav('/', { replace: true });
    } catch (e2) {
      setErr(e2 instanceof ApiError ? e2.message : '登录失败，请稍后重试');
    } finally {
      setBusy(false);
    }
  }

  return (
    <AuthFrame
      brand="平台后台"
      kicker="ADMIN · 运营与审批"
      headline="经营、审批、留痕，在一个后台里闭合"
      lede="订单与佣金对得上账，退款和提现有人审，谁在什么时候改了哪一条，都记得住。"
      points={[
        '退款、提现、代理申请三条审批线，对面都是真金白银在等',
        '每一次改动写审计日志：改前改后各是什么，一并留存',
        '风控看的是行为，不是感觉：异常激活、异地登录都有据可查',
      ]}
      title={mode === 'login' ? '登录' : '设置新密码'}
      desc={mode === 'login'
        ? '只有平台管理员能进这里。代理商请用代理端，学员请用学员端。'
        : undefined}
      note="后台的每一次改动都会记进审计日志（谁、什么时候、改了哪条、改前改后各是什么）。请勿共用账号 —— 共用之后，追责这件事就不成立了。"
    >
      {mode === 'login' ? (
        <form onSubmit={login}>
          <Field label="手机号" meta="PHONE">
            <Input value={phone} onChange={e => setPhone(e.target.value)}
                   inputMode="numeric" autoComplete="username"
                   placeholder="登录用的手机号" />
          </Field>
          <Field label="密码" meta="PASSWORD">
            <Input type="password" value={password} onChange={e => setPassword(e.target.value)}
                   autoComplete="current-password" placeholder="密码" />
          </Field>
          {err && <div style={{ marginBottom: 'var(--s4)' }}>
            <Note tone="danger">{err}</Note>
          </div>}
          <Button type="submit" block size="lg" loading={busy}
                  disabled={!phone.trim() || !password}>登录</Button>
          <div style={{ marginTop: 'var(--s4)', textAlign: 'center' }}>
            <Button variant="quiet" size="sm" type="button"
                    onClick={() => { setErr(null); setMode('reset'); }}>
              忘记密码 / 首次登录要改密
            </Button>
          </div>
        </form>
      ) : (
        <ResetForm phone={phone} onBack={() => { setMode('login'); setErr(null); }} />
      )}
    </AuthFrame>
  );
}

function ResetForm({ phone: initPhone, onBack }: { phone: string; onBack: () => void }) {
  const nav = useNavigate();
  const [phone, setPhone] = React.useState(initPhone);
  const [code, setCode] = React.useState('');
  const [pwd, setPwd] = React.useState('');
  const [pwd2, setPwd2] = React.useState('');
  const [cool, setCool] = React.useState(0);
  const [busy, setBusy] = React.useState(false);
  const [err, setErr] = React.useState<string | null>(null);
  const [ok, setOk] = React.useState<string | null>(null);

  React.useEffect(() => {
    if (cool <= 0) return;
    const t = setTimeout(() => setCool(c => c - 1), 1000);
    return () => clearTimeout(t);
  }, [cool]);

  async function sendCode() {
    setErr(null); setOk(null);
    try {
      const r = await api.post<any>('/api/auth/sms', { phone: phone.trim(), scene: 'RESET' });
      setCool(Number(r.cooldownSeconds) || 60);
      setOk('验证码已发出，请查收短信。');
    } catch (e) {
      setErr(e instanceof ApiError ? e.message : '验证码发送失败');
    }
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (pwd !== pwd2) { setErr('两次输入的新密码不一样'); return; }
    setBusy(true); setErr(null);
    try {
      await api.post('/api/auth/password/reset',
        { phone: phone.trim(), code: code.trim(), password: pwd });
      // 改密后令牌版本 +1，旧令牌全部作废，所以必须重新登录一次
      session.clear();
      setOk('密码已改好。之前签出的登录状态全部作废，请用新密码重新登录。');
      setTimeout(() => nav('/login', { replace: true }), 1200);
      onBack();
    } catch (e2) {
      setErr(e2 instanceof ApiError ? e2.message : '改密失败');
    } finally {
      setBusy(false);
    }
  }

  return (
    <form onSubmit={submit}>
      <div style={{ marginBottom: 'var(--s4)' }}>
        <Note tone="warn" title="改完之后要重新登录一次">
          新密码生效的同时，此前签发的所有登录状态都会作废 —— 包括把初始密码转达给你的那个人
          手上可能留下的任何凭据。这是强制改密要达到的效果，不是多余的一步。
        </Note>
      </div>
      <Field label="手机号">
        <Input value={phone} onChange={e => setPhone(e.target.value)} inputMode="numeric" />
      </Field>
      <Field label="短信验证码">
        <div style={{ display: 'flex', gap: 'var(--s2)' }}>
          <Input value={code} onChange={e => setCode(e.target.value)} inputMode="numeric"
                 placeholder="6 位数字" />
          <Button type="button" variant="ghost" onClick={sendCode}
                  disabled={!phone.trim() || cool > 0}>
            {cool > 0 ? `${cool} 秒后可重发` : '获取验证码'}
          </Button>
        </div>
      </Field>
      <Field label="新密码" hint="至少 8 位，要同时有字母和数字">
        <Input type="password" value={pwd} onChange={e => setPwd(e.target.value)}
               autoComplete="new-password" />
      </Field>
      <Field label="再输一次新密码">
        <Input type="password" value={pwd2} onChange={e => setPwd2(e.target.value)}
               autoComplete="new-password" />
      </Field>
      {err && <div style={{ marginBottom: 'var(--s4)' }}><Note tone="danger">{err}</Note></div>}
      {ok && <div style={{ marginBottom: 'var(--s4)' }}><Note tone="ok">{ok}</Note></div>}
      <Button type="submit" block loading={busy}
              disabled={!phone.trim() || !code.trim() || pwd.length < 8}>改密码</Button>
      <div style={{ marginTop: 'var(--s4)', textAlign: 'center' }}>
        <Button variant="quiet" size="sm" type="button" onClick={onBack}>返回登录</Button>
      </div>
    </form>
  );
}
