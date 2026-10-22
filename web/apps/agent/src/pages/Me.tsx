/**
 * 我的资料。
 *
 * 这一页真正重要的只有一块：<b>结算账户</b>。改错了钱打不出去，
 * 改对了但户名和实名不一致，会被风控直接驳回。所以改动要二次确认，
 * 并且要说清"改完对已经在审的提现单没有影响"—— 不说的话，
 * 用户会以为改了账户就能把在审的那笔改道。
 */
import React from 'react';
import { Link, useNavigate } from 'react-router-dom';
import {
  ApiError, auth, Button, Field, Input, PageHead, StatusTag, Tag, useToast,
} from '@wangke/shared';
import { AgentApi } from '../api';
import { useMe } from '../gate';
import { Confirm, FormRow, KV, Note, Section } from '../kit';

export default function MePage() {
  const { me, reload } = useMe();
  const a = me.agent;

  return (
    <>
      <PageHead title="我的资料" desc="账号、代理身份和结算账户。" />

      <div style={{ display: 'grid', gap: 'var(--s4)' }}>
        <Section title="账号">
          <KV label="手机号" hint="登录用的号码。要更换请联系平台对接人。">
            <span className="num">{me.user.phone}</span>
          </KV>
          <KV label="实名">
            {me.user.realName || <span style={{ color: 'var(--ink-3)' }}>未登记</span>}
          </KV>
          <KV label="身份">
            {a
              ? <span style={{ display: 'inline-flex', gap: 'var(--s2)', alignItems: 'center' }}>
                  <Tag tone="brand">{a.level === 1 ? '一级代理' : '二级代理'}</Tag>
                  <StatusTag status={a.status} />
                </span>
              : <span style={{ display: 'inline-flex', gap: 'var(--s3)', alignItems: 'center' }}>
                  <span style={{ color: 'var(--ink-3)' }}>还不是代理</span>
                  <Link to="/apply">去申请</Link>
                </span>}
          </KV>
          {a && (
            <KV label="推广码" hint={a.level === 1
              ? '别人申请成为你的二级代理时要填这一串。'
              : '用于生成学员推广链接和识别学员归属；二级代理不能继续发展下级。'}>
              <span className="num">{a.agentCode}</span>
            </KV>
          )}
          {a && a.level === 2 && (
            <KV label="我的上级"><span className="num">{a.parentAgentCode || '—'}</span></KV>
          )}
        </Section>

        {a && <Settle onSaved={reload} />}

        <Password />
      </div>
    </>
  );
}

/* ------------------------------------------------------------ 结算账户 */

function Settle({ onSaved }: { onSaved: () => void }) {
  const { me } = useMe();
  const a = me.agent!;
  const toast = useToast();

  const [contactName, setContactName] = React.useState(a.contactName || '');
  const [companyName, setCompanyName] = React.useState(a.companyName || '');
  const [taxNo, setTaxNo] = React.useState(a.taxNo || '');
  const [bankName, setBankName] = React.useState(a.settleBankName || '');
  const [bankAcct, setBankAcct] = React.useState(a.settleBankAcct || '');
  const [ask, setAsk] = React.useState(false);
  const [busy, setBusy] = React.useState(false);
  const [err, setErr] = React.useState('');

  const dirty =
    contactName !== (a.contactName || '') ||
    companyName !== (a.companyName || '') ||
    taxNo !== (a.taxNo || '') ||
    bankName !== (a.settleBankName || '') ||
    bankAcct !== (a.settleBankAcct || '');

  const acctChanged = bankAcct !== (a.settleBankAcct || '') || bankName !== (a.settleBankName || '');

  async function save() {
    setBusy(true);
    try {
      await AgentApi.updateProfile({
        contactName: contactName.trim(),
        companyName: companyName.trim(),
        taxNo: taxNo.trim(),
        settleBankName: bankName.trim(),
        settleBankAcct: bankAcct.trim(),
      });
      setAsk(false);
      toast({ tone: 'ok', text: '已保存' });
      onSaved();
    } catch (e) {
      setAsk(false);
      setErr(e instanceof ApiError ? e.message : '保存失败，请重试');
    } finally {
      setBusy(false);
    }
  }

  return (
    <Section
      title="结算账户"
      desc="佣金打到这里。户名必须和你的实名或主体名称一致，平台不接受代收。"
    >
      <FormRow>
        <Field label="联系人姓名">
          <Input value={contactName} onChange={e => setContactName(e.target.value)} />
        </Field>
        <Field label="主体名称" hint="个人身份可以不填">
          <Input value={companyName} onChange={e => setCompanyName(e.target.value)} />
        </Field>
      </FormRow>
      <FormRow>
        <Field label="统一社会信用代码" hint="开票要用">
          <Input value={taxNo} onChange={e => setTaxNo(e.target.value.trim())}
                 style={{ fontFamily: 'var(--font-num)' }} />
        </Field>
        <Field label="开户行" hint="例如：招商银行深圳分行">
          <Input value={bankName} onChange={e => setBankName(e.target.value)} />
        </Field>
      </FormRow>
      <Field label="银行账号" hint="填错了钱会退回来，还要重新走一遍审核，所以填完请再核一遍">
        <Input
          value={bankAcct} inputMode="numeric"
          onChange={e => setBankAcct(e.target.value.replace(/\s/g, ''))}
          style={{ fontFamily: 'var(--font-num)' }}
        />
      </Field>

      {err && <div style={{ marginBottom: 'var(--s4)' }}><Note tone="danger">{err}</Note></div>}

      <div style={{ display: 'flex', gap: 'var(--s3)', alignItems: 'center', flexWrap: 'wrap' }}>
        <Button disabled={!dirty} onClick={() => { setErr(''); setAsk(true); }}>保存</Button>
        {dirty && (
          <Button variant="quiet" onClick={() => {
            setContactName(a.contactName || ''); setCompanyName(a.companyName || '');
            setTaxNo(a.taxNo || ''); setBankName(a.settleBankName || '');
            setBankAcct(a.settleBankAcct || ''); setErr('');
          }}>撤销改动</Button>
        )}
      </div>

      <Confirm
        open={ask}
        title="确认修改结算信息"
        confirmText="确认修改"
        loading={busy}
        onClose={() => setAsk(false)}
        onConfirm={save}
        consequence={acctChanged
          ? <>改完之后，<b>新提交</b>的提现会打到新账户。已经在审核或打款流程里的提现单仍按原账户走，不受影响。</>
          : <>资料改动会同步到平台侧的审核记录里。</>}
      >
        {acctChanged && (
          <div style={{ fontSize: 'var(--t-sm)', color: 'var(--ink-2)', lineHeight: 'var(--lh-body)' }}>
            新的收款账户：<br />
            <b>{contactName || companyName || '（未填户名）'}</b><br />
            <span className="num">{bankAcct || '（未填账号）'}</span>
            {bankName ? <>　{bankName}</> : null}
            <div style={{ marginTop: 'var(--s2)', color: 'var(--ink-3)' }}>
              户名和实名对不上会被风控驳回，钱不会丢，但要重新申请一次。
            </div>
          </div>
        )}
      </Confirm>
    </Section>
  );
}

/* ---------------------------------------------------------------- 改密 */

/**
 * 改密。
 *
 * <b>改完必须自己登出。</b>后端改密时把 token_version +1，此前签出的每一张令牌当场作废 ——
 * 包括发起这次请求的这一台。原来这里的 toast 写「其他设备上的登录会被踢下线」，
 * 那句话是错的：本机也被踢了。不主动登出的后果不是报错，而是用户带着一张死令牌继续点，
 * 直到某一次请求 401，被 AppShell 的 onUnauthorized 统一出口无缘无故弹去登录页 ——
 * 他会以为是平台把他踢了，然后打电话给客服。学员端一直是对的（Me.tsx 里 toast 后
 * signOut + 跳 /login），这里是把代理端补齐。
 */
function Password() {
  const toast = useToast();
  const nav = useNavigate();
  const [oldPwd, setOldPwd] = React.useState('');
  const [newPwd, setNewPwd] = React.useState('');
  const [again, setAgain] = React.useState('');
  const [busy, setBusy] = React.useState(false);
  const [err, setErr] = React.useState('');

  // 改完到跳走之间要留一小会儿让人看见那句提示。定时器存起来，
  // 组件先被卸载时要清掉，否则会在一个已经不存在的界面上触发导航。
  const timer = React.useRef<number | undefined>(undefined);
  React.useEffect(() => () => { if (timer.current) window.clearTimeout(timer.current); }, []);

  const rule = /^(?=.*[A-Za-z])(?=.*\d).{8,72}$/;
  const newOk = rule.test(newPwd);
  const match = newPwd.length > 0 && newPwd === again;

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setErr('');
    if (!newOk) { setErr('新密码至少 8 位，要同时有字母和数字'); return; }
    if (!match) { setErr('两次输入的新密码不一样'); return; }
    setBusy(true);
    try {
      // oldPassword 传 undefined 而不是空串：JSON.stringify 会把整个 key 丢掉，
      // 到后端就是 null —— 那正是"平台开户、从来没设过密码"的账号要走的那条路。
      const res = await AgentApi.changePassword({
        oldPassword: oldPwd || undefined,
        newPassword: newPwd,
      });
      setOldPwd(''); setNewPwd(''); setAgain('');

      // 只有后端明确说 false 才留在原地。字段缺失（比如线上还是旧版本后端）时按"要重登"办：
      // 猜错的两种代价不对称 —— 多登一次是一次点击，少登一次是一张死令牌加一次莫名其妙的弹出。
      const mustRelogin = res?.reloginRequired !== false;
      toast({
        tone: 'ok',
        text: res?.message || '密码已修改，所有设备需要重新登录',
      });
      if (mustRelogin) {
        // busy 保持 true：这 0.9 秒里表单不能再提交（再提交也只会 401，
        // 因为手里这张令牌已经死了）。
        timer.current = window.setTimeout(() => {
          auth.clear();
          nav('/login', { replace: true });
        }, 900);
        return;
      }
    } catch (e2) {
      setErr(e2 instanceof ApiError ? e2.message : '改密失败，请重试');
    }
    setBusy(false);
  }

  return (
    <Section title="修改密码" desc="改完之后所有已登录的设备都要重新登录 —— 包括你正在用的这一台。">
      <form onSubmit={submit}>
        <FormRow>
          <Field label="当前密码" hint="平台开户后没设过密码的可以留空">
            <Input type="password" value={oldPwd} autoComplete="current-password"
                   onChange={e => setOldPwd(e.target.value)} />
          </Field>
          <Field label="新密码" required
                 hint="至少 8 位，要同时有字母和数字"
                 error={newPwd && !newOk ? '还不满足：至少 8 位，且要有字母也有数字' : undefined}>
            <Input type="password" value={newPwd} autoComplete="new-password"
                   invalid={!!newPwd && !newOk}
                   onChange={e => setNewPwd(e.target.value)} />
          </Field>
        </FormRow>
        <Field label="再输一次新密码" required
               error={again && !match ? '和上面不一样' : undefined}>
          <Input type="password" value={again} autoComplete="new-password"
                 invalid={!!again && !match}
                 onChange={e => setAgain(e.target.value)} />
        </Field>

        {err && <div style={{ marginBottom: 'var(--s4)' }}><Note tone="danger">{err}</Note></div>}

        <Button type="submit" loading={busy} disabled={!newOk || !match}>修改密码</Button>
      </form>
    </Section>
  );
}
