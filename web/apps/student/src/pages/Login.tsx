/**
 * 登录。验证码 / 密码两条路，外加一条"忘了密码"。
 *
 * 三条路共用同一个手机号输入框：切 tab 时不清空。清空是很自然的实现
 * （每个 tab 一份 state），但用户的动作是"我密码想不起来了，换验证码试试"，
 * 让他把手机号再打一遍，是在他已经受挫的时候再加一道手续。
 */
import React from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { api, Button, Field, Input } from '@wangke/shared';
import type { Session } from '../types';
import { acceptSession, rememberRef, takeRef } from '../session';
import { isPhone, Note, PhoneInput, Segmented, SmsButton, Stack } from '../kit';
import { AuthLayout } from './authLayout';

type Mode = 'code' | 'password' | 'reset';

export default function Login() {
  const nav = useNavigate();
  const [sp] = useSearchParams();
  const next = sp.get('next') || '/';

  const [mode, setMode] = React.useState<Mode>('code');
  const [phone, setPhone] = React.useState('');
  const [code, setCode] = React.useState('');
  const [pwd, setPwd] = React.useState('');
  const [busy, setBusy] = React.useState(false);
  const [err, setErr] = React.useState('');
  // 这一页在 ToastHost 之外（ToastHost 由 AuthLayout 渲染），
  // 所以成功提示不能用 toast —— 那样调用不会报错，只是什么也不会显示。
  const [notice, setNotice] = React.useState('');

  // 推广码只在**注册那一刻**有意义（后端 first-touch，之后不再改归属）。
  // 落地页可能是 /login?ref=xxx，所以这里也要接住，再透传给注册。
  const ref = sp.get('ref');
  React.useEffect(() => { rememberRef(ref); }, [ref]);

  const go = (s: Session) => {
    acceptSession(s, phone);
    nav(next.startsWith('/') ? next : '/', { replace: true });
  };

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setErr('');
    if (!isPhone(phone)) { setErr('请填写 11 位手机号'); return; }
    setBusy(true);
    try {
      if (mode === 'code') {
        const r = await api.post<{ session: Session }>('/api/auth/login/code',
          { phone, code: code.trim(), ref: takeRef() });
        go(r.session);
      } else if (mode === 'password') {
        const r = await api.post<{ session: Session }>('/api/auth/login/password',
          { phone, password: pwd });
        go(r.session);
      } else {
        await api.post('/api/auth/password/reset', { phone, code: code.trim(), password: pwd });
        setNotice('密码已重置，请用新密码登录');
        setMode('password');
        setCode('');
        setPwd('');
      }
    } catch (e2) {
      setErr((e2 as Error).message || '登录失败，请重试');
    } finally {
      setBusy(false);
    }
  };

  const canSubmit = isPhone(phone) && (
    mode === 'password' ? pwd.length >= 8
      : mode === 'code' ? code.trim().length >= 4
      : code.trim().length >= 4 && pwd.length >= 8);

  return (
    <AuthLayout
      title={mode === 'reset' ? '重设密码' : '登录'}
      desc={mode === 'reset'
        ? '用手机验证码重设密码。重设后所有已登录的设备都要重新登录。'
        : '用你购课时填的手机号登录。'}
      footer={mode === 'reset'
        ? <button type="button" onClick={() => setMode('password')} style={linkBtn}>返回登录</button>
        : <>还没有账号？<Link to={'/register' + (ref ? `?ref=${encodeURIComponent(ref)}` : '')}>去注册</Link></>}
    >
      <form onSubmit={submit}>
        <Stack gap="var(--s5)">
          {mode !== 'reset' && (
            <Segmented<Mode>
              value={mode}
              onChange={(m) => { setMode(m); setErr(''); setNotice(''); }}
              options={[
                { value: 'code', label: '验证码登录' },
                { value: 'password', label: '密码登录' },
              ]}
            />
          )}

          <div>
            <Field label="手机号">
              <PhoneInput value={phone} onChange={setPhone} autoFocus disabled={busy} />
            </Field>

            {(mode === 'code' || mode === 'reset') && (
              <Field label="短信验证码">
                <div style={{ display: 'flex', gap: 'var(--s3)', alignItems: 'flex-start' }}>
                  <Input
                    value={code}
                    onChange={(e) => setCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
                    inputMode="numeric"
                    autoComplete="one-time-code"
                    placeholder="6 位数字"
                    disabled={busy}
                    style={{ fontSize: 'var(--t-h3)', letterSpacing: '.24em' }}
                  />
                  <SmsButton phone={phone} scene={mode === 'reset' ? 'RESET' : 'LOGIN'} disabled={busy} />
                </div>
              </Field>
            )}

            {(mode === 'password' || mode === 'reset') && (
              <Field
                label={mode === 'reset' ? '新密码' : '密码'}
                hint={mode === 'reset' ? '至少 8 位，且要同时有字母和数字' : undefined}
              >
                <Input
                  type="password"
                  value={pwd}
                  onChange={(e) => setPwd(e.target.value)}
                  autoComplete={mode === 'reset' ? 'new-password' : 'current-password'}
                  placeholder={mode === 'reset' ? '设置新密码' : '输入密码'}
                  disabled={busy}
                />
              </Field>
            )}

            {mode === 'password' && (
              <button type="button" onClick={() => { setMode('reset'); setErr(''); }} style={linkBtn}>
                忘记密码？
              </button>
            )}
          </div>

          {notice && !err && <Note tone="ok">{notice}</Note>}
          {err && <Note tone="danger">{err}</Note>}

          <Button type="submit" size="lg" block loading={busy} disabled={!canSubmit}>
            {mode === 'reset' ? '重设密码' : '登录'}
          </Button>

          {mode === 'code' && (
            <div style={{ fontSize: 'var(--t-xs)', color: 'var(--ink-3)', textAlign: 'center' }}>
              手机号还没注册过的话，会直接为你创建账号
            </div>
          )}
        </Stack>
      </form>
    </AuthLayout>
  );
}

const linkBtn: React.CSSProperties = {
  background: 'none', border: 'none', padding: 0, cursor: 'pointer',
  color: 'var(--brand)', fontSize: 'var(--t-sm)',
};
