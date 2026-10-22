/**
 * 登录。和学员端同一套账号 —— 代理也是 sys_user。
 *
 * 默认给验证码登录：代理的号大多是平台开户的，密码是初始密码，
 * 记得住的人不多；而"忘了密码 → 找客服重置"是最贵的一条支持路径。
 */
import React from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { ApiError, AuthFrame, Button, Field, Input, api, auth } from '@wangke/shared';
import { Note, Segmented } from '../kit';

type Mode = 'code' | 'password';

export default function Login() {
  return <LoginBody />;
}

function LoginBody() {
  const nav = useNavigate();
  const loc = useLocation();
  const back = (loc.state as { from?: string } | null)?.from;

  const [mode, setMode] = React.useState<Mode>('code');
  const [phone, setPhone] = React.useState('');
  const [code, setCode] = React.useState('');
  const [password, setPassword] = React.useState('');
  const [err, setErr] = React.useState('');
  const [busy, setBusy] = React.useState(false);
  const [cooldown, setCooldown] = React.useState(0);
  const [sending, setSending] = React.useState(false);

  React.useEffect(() => {
    if (cooldown <= 0) return;
    const t = setTimeout(() => setCooldown(c => c - 1), 1000);
    return () => clearTimeout(t);
  }, [cooldown]);

  const phoneOk = /^1\d{10}$/.test(phone);

  async function sendCode() {
    setErr('');
    if (!phoneOk) { setErr('请填 11 位手机号'); return; }
    setSending(true);
    try {
      const r = await api.post<{ cooldownSeconds: number }>('/api/auth/sms',
        { phone, scene: 'LOGIN' });
      setCooldown(r.cooldownSeconds || 60);
    } catch (e) {
      setErr(e instanceof ApiError ? e.message : '验证码没发出去，请稍后再试');
    } finally {
      setSending(false);
    }
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setErr('');
    if (!phoneOk) { setErr('请填 11 位手机号'); return; }
    if (mode === 'code' && code.length < 4) { setErr('请填收到的验证码'); return; }
    if (mode === 'password' && !password) { setErr('请填密码'); return; }

    setBusy(true);
    try {
      const path = mode === 'code' ? '/api/auth/login/code' : '/api/auth/login/password';
      const body = mode === 'code' ? { phone, code } : { phone, password };
      const r = await api.post<{ session: { token: string; mustChangePwd: boolean } }>(path, body);
      auth.set(r.session.token);
      nav(r.session.mustChangePwd ? '/me' : (back && back !== '/login' ? back : '/'),
          { replace: true });
    } catch (e) {
      setErr(e instanceof ApiError ? e.message : '登录失败，请重试');
    } finally {
      setBusy(false);
    }
  }

  return (
    <AuthFrame
      brand="代理后台"
      kicker="AGENT · 代理端"
      headline="进货、发码、结佣金，一条线走完"
      lede="下单拿授权码，把码发给学员，谁激活了、该结多少佣金、什么时候能提，都在同一个后台里对得上账。"
      // 这三条【不许出现任何价格、差价、佣金比例】。这一页不需要登录就能画出来，
      // 落地页那三颗「三端入口」按钮里有一颗直接通到这里 —— 也就是说任何学员、
      // 任何搜索引擎都看得到它。原来第一条写的是「一级 2980 / 十码，二级 3490 / 十码」，
      // 第二条把佣金机制说破，两条一读就能算出每码的差价。
      // 而佣金规则被有意设成恰等于层级差价（db/seed.sql），所以露两个数第三个就出来了。
      // 业务口径：申请 + 审批通过之后就能在代理后台下单（/api/agent/skus，要求档案 ACTIVE）。
      // 进货价一家一议、不在代理端展示，由平台对接人单独告知。
      points={[
        '按层级拿进货价，下单即出授权码，价目在后台按你的层级自动生效',
        '发展二级代理，二级进货时你按规则计佣，冻结期满可提现',
        '推广码扫码即绑定，学员从哪来记得住',
      ]}
      title="登录"
      desc="用手机号登录。代理和学员是同一套账号，你用这个号买过课的话，进来看到的就是同一个号。"
      footer={<>还不是代理？登录后可以<Link to="/apply">提交申请</Link></>}
      note="佣金、提现、授权码的每一次变动都会留痕。请勿把账号借给别人使用。"
    >
      <form onSubmit={submit} noValidate>
        <div style={{ marginBottom: 'var(--s5)' }}>
          <Segmented<Mode>
            value={mode}
            onChange={v => { setMode(v); setErr(''); }}
            options={[
              { value: 'code', label: '验证码登录' },
              { value: 'password', label: '密码登录' },
            ]}
          />
        </div>

        <Field label="手机号" required meta="PHONE">
          <Input
            value={phone} inputMode="numeric" autoComplete="tel" maxLength={11}
            placeholder="11 位手机号"
            invalid={!!phone && !phoneOk}
            onChange={e => setPhone(e.target.value.replace(/\D/g, ''))}
          />
        </Field>

        {mode === 'code' ? (
          <Field label="短信验证码" required meta="SMS"
                 hint="收不到？看看是不是被拦截了，或者换密码登录">
            <div style={{ display: 'flex', gap: 'var(--s3)', alignItems: 'flex-start' }}>
              <Input
                value={code} inputMode="numeric" maxLength={6} autoComplete="one-time-code"
                placeholder="6 位数字"
                style={{ letterSpacing: '.24em' }}
                onChange={e => setCode(e.target.value.replace(/\D/g, ''))}
              />
              <Button type="button" variant="ghost" loading={sending}
                      disabled={cooldown > 0 || !phoneOk} onClick={sendCode}>
                {cooldown > 0 ? `${cooldown} 秒后重发` : '发验证码'}
              </Button>
            </div>
          </Field>
        ) : (
          <Field label="密码" required meta="PASSWORD">
            <Input
              type="password" value={password} autoComplete="current-password"
              placeholder="至少 8 位，含字母和数字"
              onChange={e => setPassword(e.target.value)}
            />
          </Field>
        )}

        {err && (
          <div style={{ marginBottom: 'var(--s4)' }}>
            <Note tone="danger">{err}</Note>
          </div>
        )}

        <Button type="submit" block size="lg" loading={busy}>登录</Button>
      </form>
    </AuthFrame>
  );
}
