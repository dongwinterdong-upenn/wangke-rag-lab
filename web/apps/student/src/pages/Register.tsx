/**
 * 注册。
 *
 * 密码是**选填**的：后端支持纯验证码登录的账号，逼着用户在这一步想一个密码
 * 只会让一部分人卡在这儿走掉。但要把"不设密码会怎样"说清楚 ——
 * 不说的话，下次他来登录发现没有密码可填，会以为账号丢了。
 */
import React from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { api, Button, Field, Input } from '@wangke/shared';
import type { Session } from '../types';
import { acceptSession, rememberRef, takeRef } from '../session';
import { isPhone, Note, PhoneInput, SmsButton, Stack } from '../kit';
import { AuthLayout } from './authLayout';

export default function Register() {
  const nav = useNavigate();
  const [sp] = useSearchParams();
  const ref = sp.get('ref');
  React.useEffect(() => { rememberRef(ref); }, [ref]);

  const [phone, setPhone] = React.useState('');
  const [code, setCode] = React.useState('');
  const [pwd, setPwd] = React.useState('');
  const [wantPwd, setWantPwd] = React.useState(false);
  const [busy, setBusy] = React.useState(false);
  const [err, setErr] = React.useState('');

  const refCode = ref || takeRef();

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setErr('');
    if (!isPhone(phone)) { setErr('请填写 11 位手机号'); return; }
    if (wantPwd && pwd.length < 8) { setErr('密码至少 8 位，且要同时有字母和数字'); return; }
    setBusy(true);
    try {
      const r = await api.post<{ session: Session }>('/api/auth/register', {
        phone,
        code: code.trim(),
        password: wantPwd ? pwd : undefined,
        ref: refCode,
      });
      acceptSession(r.session, phone);
      // 新注册的人手上多半正攥着一张激活码，直接送到激活页，
      // 而不是丢给他一个空空如也的"我的课程"让他自己找入口。
      nav('/activate?from=register', { replace: true });
    } catch (e2) {
      setErr((e2 as Error).message || '注册失败，请重试');
    } finally {
      setBusy(false);
    }
  };

  return (
    <AuthLayout
      title="注册"
      desc="注册后就可以用激活码兑换课程。"
      footer={<>已经有账号了？<Link to="/login">去登录</Link></>}
    >
      <form onSubmit={submit}>
        <Stack gap="var(--s5)">
          <div>
            <Field label="手机号" hint="这个号码将用于登录和找回账号">
              <PhoneInput value={phone} onChange={setPhone} autoFocus disabled={busy} />
            </Field>

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
                <SmsButton phone={phone} scene="REGISTER" disabled={busy} />
              </div>
            </Field>

            <label style={{
              display: 'flex', gap: 'var(--s3)', alignItems: 'flex-start',
              cursor: 'pointer', marginBottom: 'var(--s4)',
            }}>
              <input
                type="checkbox" checked={wantPwd}
                onChange={(e) => setWantPwd(e.target.checked)}
                style={{ marginTop: 4, width: 18, height: 18, flexShrink: 0 }}
              />
              <span style={{ fontSize: 'var(--t-sm)', color: 'var(--ink-2)' }}>
                顺便设置一个密码（可以不设）
                <span style={{ display: 'block', color: 'var(--ink-3)', fontSize: 'var(--t-xs)' }}>
                  不设的话，以后每次登录都要收一条短信验证码
                </span>
              </span>
            </label>

            {wantPwd && (
              <Field label="密码" hint="至少 8 位，且要同时有字母和数字">
                <Input
                  type="password" value={pwd} onChange={(e) => setPwd(e.target.value)}
                  autoComplete="new-password" placeholder="设置密码" disabled={busy}
                />
              </Field>
            )}
          </div>

          {refCode && (
            <Note>
              已记录推广码 <b>{refCode}</b>。它只在这次注册时生效，之后不会再变。
            </Note>
          )}

          {err && <Note tone="danger">{err}</Note>}

          <Button type="submit" size="lg" block loading={busy}
                  disabled={!isPhone(phone) || code.trim().length < 4}>
            注册并进入
          </Button>
        </Stack>
      </form>
    </AuthLayout>
  );
}
