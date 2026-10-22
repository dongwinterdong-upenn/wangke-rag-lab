/**
 * 个人中心：手机号、实名、改密、设备。
 *
 * 设备管理为什么要显式做出来：设备上限是 2 台且写死在数据库触发器里，
 * 撞上限时播放页只会说"请先去『我的 - 设备管理』里解绑一台"。
 * 那句话必须指向一个真实存在的地方，否则学员就卡死在播放页了。
 *
 * <p><b>这里没有发票，是删掉的、不是漏了的</b>（2026-09-01）。课程发票由售出方开，
 * 学员端自助开票从来就不该有，需求方是按 bug 报的。
 * 界面这一半只是把入口收走，真正的闸在服务端 InvoiceController.requireInvoiceableRole
 * ——只删界面的话 /api/invoice/** 仍然收得进学员的申请，而那批申请落库之后
 * 不报任何错，只是堆在运营那边没人能处理。要加回来的话两边一起加。
 */
import React from 'react';
import { Link, useNavigate } from 'react-router-dom';
import {
  ApiError, api, Async, Button, Card, dateTime, Empty, Field, Input, PageHead,
  Skeleton, StatusTag, useAsync, useToast,
} from '@wangke/shared';
import type { DeviceRow, Me as MeData } from '../types';
import { displayPhone, loadMe, signOut } from '../session';
import { Confirm, Note, Row, Screen, Stack } from '../kit';

export default function Me() {
  const nav = useNavigate();
  const me = useAsync<MeData>(loadMe, []);

  return (
    <Screen maxWidth={620}>
      <PageHead title="我的" />
      <Stack gap="var(--s6)">
        <Card>
          {me.loading ? <Skeleton h={64} /> : (
            <Async state={me}>
              {(u) => (
                <Row style={{ justifyContent: 'space-between' }}>
                  <div>
                    <div className="num" style={{ fontSize: 'var(--t-h2)', fontWeight: 600 }}>
                      {displayPhone(u)}
                    </div>
                    <div style={{ fontSize: 'var(--t-sm)', color: 'var(--ink-3)', marginTop: 2 }}>
                      {u.realName || u.nickname || '学员'}
                    </div>
                  </div>
                  {u.status !== 'ACTIVE' && <StatusTag status={u.status} />}
                </Row>
              )}
            </Async>
          )}
          <div style={{ marginTop: 'var(--s4)', display: 'flex', gap: 'var(--s3)', flexWrap: 'wrap' }}>
            <Link to="/cert"><Button variant="ghost">我的结业证明</Button></Link>
            <Link to="/orders"><Button variant="ghost">我的订单</Button></Link>
            {/* 激活自从不再占一格标签栏之后，这里是它的第三条路（另外两条在
                首页导览与「我的课程」里）。手里攥着一张码的人会先来"我的"找，
                找不到就只能打客服电话。 */}
            <Link to="/activate"><Button variant="ghost">兑换激活码</Button></Link>
          </div>
        </Card>

        <RealNameSection me={me} />
        <PasswordSection />
        <DeviceSection />

        <Button
          variant="ghost" block
          onClick={() => { signOut(); nav('/login', { replace: true }); }}
        >退出登录</Button>
      </Stack>
    </Screen>
  );
}

/* ---------------------------------------------------------------- 实名 */

/**
 * 补一次真实姓名。
 *
 * <p>为什么要问这一项：看课时画面上那条半透明水印按业务口径要写「姓名 + 手机号」，
 * 那是这门课被转发出去之后<b>唯一能追回到人</b>的东西。姓名空着的话水印上只有号码。
 *
 * <p>已经填过的不再提供入口 —— 后端也只允许从空补成有值（水印是证据，
 * 能随时改名就等于给反取证开了个口）。所以这一段在填过之后整块消失，
 * 而不是变成一个点了会报错的输入框。
 */
function RealNameSection({ me }: { me: { data?: MeData; reload: () => void } }) {
  const toast = useToast();
  const [name, setName] = React.useState('');
  const [busy, setBusy] = React.useState(false);
  const u = me.data;

  // 加载中、已填过、或者还处在「必须改密」那一档，都不出这一段。
  // 【不再按"有没有代理档案"隐藏】：申请被驳回的人也留着一行档案，
  // 而他们的 real_name 恒为空、代理端资料页又是只读的 —— 隐藏掉等于两条路都堵死。
  if (!u || u.realName || u.mustChangePwd) return null;

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    const v = name.trim();
    if (!v) return;
    setBusy(true);
    try {
      await api.post('/api/account/real-name', { realName: v });
      toast({ tone: 'ok', text: '姓名已保存' });
      me.reload();
    } catch (err) {
      toast({ tone: 'danger', text: (err as ApiError).message || '保存失败，请重试' });
    } finally {
      setBusy(false);
    }
  };

  return (
    <Card>
      <div style={{ fontSize: 'var(--t-h3)', fontWeight: 600 }}>真实姓名</div>
      <div style={{ fontSize: 'var(--t-sm)', color: 'var(--ink-3)', marginTop: 4,
                    lineHeight: 'var(--lh-body)' }}>
        看课时画面上会显示你的姓名和手机号，用于防止课程被外传。
        <b>填过之后不能自己修改</b>，请确认无误再提交。
      </div>
      <form onSubmit={submit} style={{ marginTop: 'var(--s4)' }}>
        <Field label="姓名">
          <Input value={name} onChange={(e) => setName(e.target.value.slice(0, 32))}
                 placeholder="与身份证一致" disabled={busy} />
        </Field>
        <div style={{ marginTop: 'var(--s3)' }}>
          <Button type="submit" loading={busy} disabled={!name.trim()}>保存</Button>
        </div>
      </form>
    </Card>
  );
}

/* ------------------------------------------------------------------ 改密 */

function PasswordSection() {
  const toast = useToast();
  const [open, setOpen] = React.useState(false);
  const [oldPwd, setOld] = React.useState('');
  const [newPwd, setNew] = React.useState('');
  const [busy, setBusy] = React.useState(false);
  const [err, setErr] = React.useState('');

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setErr('');
    setBusy(true);
    try {
      await api.post('/api/account/password', { oldPassword: oldPwd || null, newPassword: newPwd });
      // 后端改密后会把令牌版本 +1，此前签出的每一张当场作废 —— 也包括手里这张。
      // 所以这里如实告诉用户要重新登录，而不是让他下一次点击时莫名其妙被弹去登录页。
      toast({ tone: 'ok', text: '密码已修改，请用新密码重新登录' });
      setOpen(false);
      // 路径必须带 base：学员端生产上挂在 /study/ 子路径，站点根留给落地页。
      // 写死 '/login' 在开发态（APP_BASE='/'）恰好重合，所以这条永远测不出来；
      // 一上生产就把刚改完密码的人甩到落地页，他会以为改密把账号弄坏了。
      setTimeout(() => {
        signOut();
        window.location.assign(`${import.meta.env.BASE_URL.replace(/\/$/, '')}/login`);
      }, 900);
    } catch (e2) {
      setErr((e2 as Error).message || '修改失败');
    } finally {
      setBusy(false);
    }
  };

  return (
    <Card>
      <Row style={{ justifyContent: 'space-between' }}>
        <div>
          <div style={{ fontWeight: 600 }}>登录密码</div>
          <div style={{ fontSize: 'var(--t-sm)', color: 'var(--ink-3)' }}>
            没设过密码的话，也可以在这里设一个
          </div>
        </div>
        <Button variant="ghost" onClick={() => setOpen((v) => !v)}>
          {open ? '收起' : '修改'}
        </Button>
      </Row>

      {open && (
        <form onSubmit={submit} style={{ marginTop: 'var(--s5)' }}>
          <Field label="当前密码" hint="从来没设过密码就留空">
            <Input type="password" value={oldPwd} onChange={(e) => setOld(e.target.value)}
                   autoComplete="current-password" disabled={busy} />
          </Field>
          <Field label="新密码" hint="至少 8 位，且要同时有字母和数字">
            <Input type="password" value={newPwd} onChange={(e) => setNew(e.target.value)}
                   autoComplete="new-password" disabled={busy} />
          </Field>
          {err && <div style={{ marginBottom: 'var(--s4)' }}><Note tone="danger">{err}</Note></div>}
          <Note>改完之后所有已登录的设备（包括这一台）都要重新登录。</Note>
          <div style={{ marginTop: 'var(--s4)' }}>
            <Button type="submit" loading={busy} disabled={newPwd.length < 8}>保存新密码</Button>
          </div>
        </form>
      )}
    </Card>
  );
}

/* ------------------------------------------------------------------ 设备 */

const UNSUPPORTED = Symbol('unsupported');
type MaybeDevices = DeviceRow[] | typeof UNSUPPORTED;

function DeviceSection() {
  const toast = useToast();
  const [target, setTarget] = React.useState<DeviceRow | null>(null);
  const [busy, setBusy] = React.useState(false);

  const state = useAsync<MaybeDevices>(
    () => api.get<{ devices: DeviceRow[] }>('/api/account/devices')
      .then((r) => r.devices as MaybeDevices)
      .catch((e) => {
        // 接口还没上线时不该把这一块渲染成一片红：其它几块都是好的。
        if (e instanceof ApiError && e.status === 404) return UNSUPPORTED;
        throw e;
      }),
    [],
  );

  const unbind = async () => {
    if (!target) return;
    setBusy(true);
    try {
      await api.post(`/api/account/devices/${target.id}/unbind`);
      toast({ tone: 'ok', text: '已解绑' });
      setTarget(null);
      state.reload();
    } catch (e) {
      toast({ tone: 'danger', text: (e as Error).message || '解绑失败' });
    } finally {
      setBusy(false);
    }
  };

  return (
    <Card>
      <div style={{ fontWeight: 600 }}>我的设备</div>
      <div style={{ fontSize: 'var(--t-sm)', color: 'var(--ink-3)', marginTop: 2, marginBottom: 'var(--s4)' }}>
        一个账号最多同时绑定 2 台设备。换新手机时先在这里解绑旧的。
      </div>

      {state.loading ? <Skeleton h={72} /> : (
        <Async state={state}>
          {(d) => d === UNSUPPORTED ? (
            <Note>
              设备管理还没开放。如果你换了设备后播放提示"已绑定 2 台设备"，
              请联系平台客服解绑。
            </Note>
          ) : (
            (d as DeviceRow[]).filter((x) => x.status === 'ACTIVE').length === 0 ? (
              <Empty title="还没有绑定任何设备" desc="你在哪台设备上第一次播放课程，那台就会被记下来。" />
            ) : (
              <Stack gap="var(--s3)">
                {(d as DeviceRow[]).filter((x) => x.status === 'ACTIVE').map((x) => (
                  <Row key={x.id} style={{ justifyContent: 'space-between' }}>
                    <div style={{ minWidth: 0 }}>
                      <div>{x.deviceLabel || '未命名设备'}</div>
                      <div style={{ fontSize: 'var(--t-xs)', color: 'var(--ink-3)' }}>
                        {dateTime(x.boundAt)} 开始使用
                      </div>
                    </div>
                    <Button variant="quiet" onClick={() => setTarget(x)}>解绑</Button>
                  </Row>
                ))}
              </Stack>
            )
          )}
        </Async>
      )}

      <Confirm
        open={!!target}
        title="确认解绑这台设备？"
        confirmText="确认解绑"
        busy={busy}
        onConfirm={unbind}
        onClose={() => setTarget(null)}
        consequence={
          <>
            <b>解绑后，那台设备上正在播放的课程会中断，需要重新登录才能继续看。</b>
            <div style={{ marginTop: 'var(--s1)' }}>
              解绑不会影响你的课程权益和学习进度。
            </div>
          </>
        }
      >
        <div style={{ fontSize: 'var(--t-sm)', color: 'var(--ink-2)' }}>
          将要解绑：{target?.deviceLabel || '未命名设备'}
        </div>
      </Confirm>
    </Card>
  );
}
