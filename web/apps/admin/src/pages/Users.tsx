import React from 'react';
import { Link } from 'react-router-dom';
import { api } from '@wangke/shared/api';
import { PageHead } from '@wangke/shared/shell';
import { Async, Button, Copyable, Modal, Table, useAsync } from '@wangke/shared/ui';
import type { Col } from '@wangke/shared/ui';
import {
  ActionDialog, AuditTrail, Blank, Board, Ctl, DetailModal, Filters, KV, Money, Note, Pager,
  PhoneReveal, Pick, ReasonBox, Sect, St, TextIn,
} from '../lib/kit';
import { MiniTable } from './Orders';
import { fmtTime, label, num, options, untilText } from '../lib/fmt';
import { hasFilter, qs, useQ } from '../lib/q';

export function Users() {
  const { sp, get, int, set, reset } = useQ();
  const keyword = get('keyword'), role = get('role'), status = get('status');
  const attributedAgentId = get('attributedAgentId');
  const page = int('page', 1), size = int('size', 20);
  const sel = get('sel');
  const [rev, bump] = React.useReducer((n: number) => n + 1, 0);

  const list = useAsync<any>(
    () => api.get(`/api/admin/users${qs({ keyword, role, status, attributedAgentId, page, size })}`),
    [keyword, role, status, attributedAgentId, page, size, rev]);

  const filtered = hasFilter(sp);
  const [creating, setCreating] = React.useState(false);
  const [temp, setTemp] = React.useState<{ pwd: string; phone: string } | null>(null);

  const cols: Col<any>[] = [
    { key: 'id', title: 'ID', numeric: true, width: 60 },
    { key: 'phoneMasked', title: '手机号', render: r => <span className="num">{r.phoneMasked}</span> },
    { key: 'name', title: '姓名 / 昵称', render: r => r.realName || r.nickname || '—' },
    { key: 'role', title: '角色', render: r => <St domain="role" value={r.role} /> },
    { key: 'status', title: '状态', render: r => <St domain="user" value={r.status} /> },
    { key: 'attributedAgentId', title: '归属代理', render: r => r.attributedAgentId
      ? <Link to={`/agents${qs({ sel: r.attributedAgentId })}`} onClick={e => e.stopPropagation()}
              className="num">#{r.attributedAgentId}</Link>
      : <span style={{ color: 'var(--ink-3)' }}>无</span> },
    { key: 'attributionSource', title: '归属来源',
      render: r => <span style={{ fontSize: 'var(--t-xs)', color: 'var(--ink-2)' }}>
        {label('source', r.attributionSource)}</span> },
    { key: 'lastLoginAt', title: '最近登录', render: r => fmtTime(r.lastLoginAt) },
    { key: 'createdAt', title: '注册时间', render: r => fmtTime(r.createdAt) },
  ];

  return (
    <>
      <PageHead title="学员管理"
        desc="查账号、看权益与设备、处置风险账号。归属代理一经落定不可更改，后台也改不了。"
        extra={<Button onClick={() => setCreating(true)}>新建账号</Button>} />

      <CreateUserDialog open={creating} onClose={() => setCreating(false)}
        onDone={(r, phone) => { setTemp({ pwd: r.temporaryPassword, phone }); bump(); }} />

      <Modal open={!!temp} onClose={() => setTemp(null)} title="初始密码（只显示这一次）"
        footer={<Button onClick={() => setTemp(null)}>我已经记下了</Button>}>
        <div style={{ display: 'grid', gap: 'var(--s4)' }}>
          <div style={{ fontSize: 'var(--t-sm)', color: 'var(--ink-3)' }}>
            账号 <span className="num">{temp?.phone}</span> 已创建。
          </div>
          <div style={{
            fontFamily: 'var(--font-num)', fontSize: 'var(--t-h1)', letterSpacing: '.06em',
            padding: 'var(--s4)', background: 'var(--surface-2)', borderRadius: 'var(--r-md)',
            textAlign: 'center', wordBreak: 'break-all',
          }}>{temp?.pwd}</div>
          <Note>
            这串密码<b>不会写进审计日志</b>，关掉这个框之后平台自己也查不到 ——
            写进日志等于给所有能查日志的人留一份可登录凭据。请通过可信渠道转达。
            他首次登录会被强制改密。
          </Note>
        </div>
      </Modal>

      <Filters active={filtered} onReset={() => reset()}>
        <Ctl label="搜索" width={220} grow hint="手机号 / 昵称 / 实名">
          <TextIn defaultValue={keyword} placeholder="输入后回车"
                  onKeyDown={e => { if (e.key === 'Enter') set({ keyword: e.currentTarget.value }); }}
                  onBlur={e => { if (e.currentTarget.value !== keyword) set({ keyword: e.currentTarget.value }); }} />
        </Ctl>
        <Ctl label="角色" width={140}>
          <Pick value={role} onChange={v => set({ role: v })} options={options('role')} />
        </Ctl>
        <Ctl label="账号状态" width={130}>
          <Pick value={status} onChange={v => set({ status: v })} options={options('user')} />
        </Ctl>
        <Ctl label="归属代理 ID" width={130}>
          <TextIn defaultValue={attributedAgentId} inputMode="numeric" placeholder="回车"
                  onKeyDown={e => { if (e.key === 'Enter') set({ attributedAgentId: e.currentTarget.value.trim() }); }} />
        </Ctl>
      </Filters>

      <Board flush title={`账号${list.data ? ` · ${num(list.data.total)} 个` : ''}`}>
        <Async state={list}>{(d: any) => (
          <>
            <Table cols={cols} rows={d.items} rowKey={r => r.id}
                   onRowClick={r => set({ sel: r.id }, true)}
                   empty={<Blank filtered={filtered} thing="账号" onClear={() => reset()} />} />
            <Pager total={num(d.total)} page={page} size={size}
                   onPage={p => set({ page: p }, true)} onSize={s => set({ size: s })} />
          </>
        )}</Async>
      </Board>

      {sel && <UserDetail id={sel} onClose={() => set({ sel: '' }, true)} onChanged={bump} />}
    </>
  );
}

/* ------------------------------------------------------------------ 详情 */

function UserDetail({ id, onClose, onChanged }: {
  id: string; onClose: () => void; onChanged: () => void;
}) {
  const [rev, bump] = React.useReducer((n: number) => n + 1, 0);
  const state = useAsync<any>(() => api.get(`/api/admin/users/${id}`), [id, rev]);
  const orders = useAsync<any>(() => api.get(`/api/admin/orders${qs({ buyerUserId: id, size: 20 })}`), [id, rev]);
  const risks = useAsync<any>(() => api.get(`/api/admin/risk/events${qs({ userId: id, size: 10 })}`), [id, rev]);
  const [dlg, setDlg] = React.useState<null | 'freeze' | 'unfreeze' | 'reset' | 'grant' | 'rename'>(null);
  const [temp, setTemp] = React.useState<string | null>(null);
  // 冻结理由要同时被「确认按钮的可用性」和「提交体」读到，所以提到这一层：
  // 各自留一份局部 state 的话，很容易只改一处 —— 表现是按钮亮着、提交上去理由是空的
  const [freezeReason, setFreezeReason] = React.useState('');
  React.useEffect(() => { if (dlg !== 'freeze') setFreezeReason(''); }, [dlg]);

  const u = state.data?.user;
  const after = () => { bump(); onChanged(); };

  return (
    <DetailModal open onClose={onClose}
      title={u ? (u.realName || u.nickname || `账号 #${u.id}`) : `账号 #${id}`}
      subtitle={u ? <>{label('role', u.role)} · <span className="num">{u.phoneMasked}</span> · 注册于 {fmtTime(u.createdAt)}</> : undefined}
      actions={u && (
        <>
          <Button variant="ghost" onClick={() => setDlg('grant')}>开通课程</Button>
          <Button variant="ghost" onClick={() => setDlg('rename')}>更正姓名</Button>
          <Button variant="ghost" onClick={() => setDlg('reset')}>重置密码</Button>
          {u.status === 'ACTIVE'
            ? <Button variant="danger" onClick={() => setDlg('freeze')}>冻结账号</Button>
            : u.status === 'FROZEN'
              ? <Button onClick={() => setDlg('unfreeze')}>解冻</Button>
              : null}
        </>
      )}>
      <Async state={state}>{(d: any) => {
        const g = d.user;
        return (
          <div>
            {g.status === 'FROZEN' && (
              <Note tone="danger" title="这个账号现在是冻结状态">
                他无法登录、也看不了已购买的课。解冻之后需要<b>重新登录</b>——
                冻结时作废的旧登录状态不会恢复。
              </Note>
            )}

            <KV items={[
              ['账号 ID', <span className="num">#{g.id}</span>],
              ['手机号', <PhoneReveal userId={g.id} masked={g.phoneMasked} />],
              ['姓名', g.realName],
              ['昵称', g.nickname],
              ['身份证', g.idCardMasked ? <span className="num">{g.idCardMasked}</span> : null],
              ['角色', <St domain="role" value={g.role} />],
              ['状态', <St domain="user" value={g.status} />],
              ['归属代理', g.attributedAgentId
                ? <Link to={`/agents${qs({ sel: g.attributedAgentId })}`} className="num">#{g.attributedAgentId}</Link>
                : '无'],
              ['归属来源', label('source', g.attributionSource)],
              ['归属时间', fmtTime(g.attributedAt)],
              ['要求改密', num(g.mustChangePwd) === 1 ? '是（下次登录必须改）' : '否'],
              ['登录状态版本', <span className="num">{g.tokenVersion}</span>],
              ['最近登录', fmtTime(g.lastLoginAt)],
              ['7 天风险分', <span className="num" style={{
                color: num(g.riskScore7d) >= 200 ? 'var(--danger)'
                     : num(g.riskScore7d) >= 80 ? 'var(--warn)' : undefined,
              }}>{num(g.riskScore7d)}</span>],
            ]} />

            <Sect title="课程权益" extra={<span style={{ fontSize: 'var(--t-xs)', color: 'var(--ink-3)' }}>
              有效期从激活那一刻起算
            </span>}>
              {(g.enrollments ?? []).length === 0
                ? <span style={{ fontSize: 'var(--t-sm)', color: 'var(--ink-3)' }}>
                    还没有任何课程权益（没激活过码，也没直接买过）
                  </span>
                : <MiniTable rows={g.enrollments} cols={[
                    ['课程', (r: any) => r.course_title ?? r.courseTitle ?? `#${r.course_id ?? r.courseId}`],
                    ['来源', (r: any) => label('source', r.source)],
                    ['状态', (r: any) => <St domain="enrollment" value={r.status} />],
                    ['可看到', (r: any) => <>
                      {fmtTime(r.valid_until ?? r.validUntil)}
                      <span style={{ color: 'var(--ink-3)', marginLeft: 'var(--s2)' }}>
                        {untilText(r.valid_until ?? r.validUntil)}
                      </span>
                    </>],
                    ['归属代理', (r: any) => {
                      const a = r.attributed_agent_id ?? r.attributedAgentId;
                      return a ? `#${a}` : '无';
                    }],
                  ]} />}
            </Sect>

            <Sect title="设备" extra={<span style={{ fontSize: 'var(--t-xs)', color: 'var(--ink-3)' }}>
              一个账号最多同时绑 2 台
            </span>}>
              {(g.devices ?? []).length === 0
                ? <span style={{ fontSize: 'var(--t-sm)', color: 'var(--ink-3)' }}>还没有绑定过设备</span>
                : <MiniTable rows={g.devices} cols={[
                    ['设备', (r: any) => r.device_label ?? r.deviceLabel ?? '（未命名）'],
                    ['状态', (r: any) => <St domain="device" value={r.status} />],
                    ['绑定时间', (r: any) => fmtTime(r.bound_at ?? r.boundAt)],
                    ['解绑时间', (r: any) => fmtTime(r.removed_at ?? r.removedAt)],
                  ]} />}
            </Sect>

            <Sect title="订单" extra={
              <Link to={`/orders${qs({ buyerUserId: g.id })}`}
                    style={{ fontSize: 'var(--t-sm)' }}>去订单页看全部</Link>}>
              {orders.loading ? <Dim>读取中…</Dim>
               : orders.error ? <Note tone="warn">订单读取失败：{orders.error}</Note>
               : (orders.data?.items ?? []).length === 0 ? <Dim>他还没有下过单</Dim>
               : <MiniTable rows={orders.data.items} cols={[
                   ['订单号', (r: any) => <Link to={`/orders${qs({ orderNo: r.orderNo, sel: r.id })}`}
                                                className="num">{r.orderNo}</Link>],
                   ['套餐', (r: any) => <span className="num">{r.skuCode}</span>],
                   ['实付', (r: any) => <Money fen={r.paidAmountFen} />],
                   ['状态', (r: any) => <St domain="order" value={r.status} />],
                   ['下单时间', (r: any) => fmtTime(r.createdAt)],
                 ]} />}
            </Sect>

            <Sect title="风控事件" extra={
              <Link to={`/risk${qs({ userId: g.id })}`}
                    style={{ fontSize: 'var(--t-sm)' }}>去风控页看全部</Link>}>
              {risks.loading ? <Dim>读取中…</Dim>
               : risks.error ? <Note tone="warn">风控事件读取失败：{risks.error}</Note>
               : (risks.data?.items ?? []).length === 0 ? <Dim>没有风控事件，这是好事</Dim>
               : <MiniTable rows={risks.data.items} cols={[
                   ['时间', (r: any) => fmtTime(r.createdAt, true)],
                   ['信号', (r: any) => <span className="num">{r.signal}</span>],
                   ['含义', (r: any) => <span style={{ color: 'var(--ink-2)' }}>{r.why ?? '—'}</span>],
                   ['分值', (r: any) => <span className="num">{r.score}</span>],
                 ]} />}
            </Sect>

            <Sect title="改动记录">
              <AuditTrail targetType="sys_user" targetId={g.id} refreshKey={rev} />
            </Sect>
          </div>
        );
      }}</Async>

      {u && <>
        <ActionDialog open={dlg === 'freeze'} onClose={() => setDlg(null)} verb="冻结" tone="danger"
          title={`冻结账号 #${u.id}`} onDone={after}
          disabled={!freezeReason.trim()}
          consequences={<>
            <b>该用户会立即掉线</b>：他手上的登录状态当场作废，不用等自然过期。
            冻结期间他<b>无法登录、也看不了已经买过的课</b>。<br />
            解冻之后<b>不会</b>恢复那些被作废的登录状态 —— 他需要重新登录一次。
            这是有意的：冻结期间令牌可能已经落在别人手里。<br />
            冻结不退钱、不收回权益。要处理钱请另外走退款流程。
          </>}
          onConfirm={() => api.post(`/api/admin/users/${u.id}/freeze`, { reason: freezeReason.trim() })}>
          <ReasonBox value={freezeReason} onChange={setFreezeReason} label="冻结理由"
                     hint="他来问的时候，客服要能照着这一条解释。" />
        </ActionDialog>

        <ActionDialog open={dlg === 'unfreeze'} onClose={() => setDlg(null)} verb="解冻"
          title={`解冻账号 #${u.id}`} onDone={after}
          consequences={<>
            解冻之后他可以重新登录。<b>冻结时作废的旧登录状态不会恢复</b>，他必须重新登录一次。<br />
            解冻账号和「解除风控处置」是两个决定：这里只放开账号，风控那边的处置记录要单独去解除。
          </>}
          onConfirm={() => api.post(`/api/admin/users/${u.id}/unfreeze`)} />

        <ActionDialog open={dlg === 'reset'} onClose={() => setDlg(null)} verb="重置" tone="danger"
          title={`重置账号 #${u.id} 的密码`}
          onDone={(r: any) => { setTemp(r?.temporaryPassword ?? null); after(); }}
          consequences={<>
            系统会生成一个<b>一次性初始密码</b>，并强制他下次登录时改掉。<br />
            他手上<b>所有的登录状态当场作废</b> —— 这正是重置密码要达到的效果：
            如果账号已经被盗，光换密码而不踢掉在线会话，等于没换。<br />
            <b>初始密码只显示一次</b>，不会写进审计日志（写进去等于给所有能查日志的人留一份可登录凭据）。
            请通过可信渠道转达。
          </>}
          onConfirm={() => api.post(`/api/admin/users/${u.id}/reset-password`)} />

        <GrantDialog open={dlg === 'grant'} userId={u.id}
                     onClose={() => setDlg(null)} onDone={after} />

        <RenameDialog open={dlg === 'rename'} userId={u.id} current={u.realName}
                      onClose={() => setDlg(null)} onDone={after} />
      </>}

      <Modal open={!!temp} onClose={() => setTemp(null)} title="初始密码（只显示这一次）"
        footer={<Button onClick={() => setTemp(null)}>我已经记下了</Button>}>
        <div style={{ display: 'grid', gap: 'var(--s4)' }}>
          <div style={{
            fontFamily: 'var(--font-num)', fontSize: 'var(--t-h1)', letterSpacing: '.06em',
            padding: 'var(--s4)', background: 'var(--surface-2)', borderRadius: 'var(--r-md)',
            textAlign: 'center', wordBreak: 'break-all',
          }}>{temp}</div>
          <div style={{ textAlign: 'center' }}>{temp && <Copyable text={temp} label="复制这串密码" />}</div>
          <Note tone="warn">
            关掉这个框之后就再也看不到了 —— 系统只存了它的哈希。
            没记下就再重置一次，不要去审计日志里找（那里没有，也不该有）。<br />
            密码里没有 0/O/1/l/I 这些形近字符，是为了能靠电话念清楚。
          </Note>
        </div>
      </Modal>
    </DetailModal>
  );
}

function Dim({ children }: { children: React.ReactNode }) {
  return <span style={{ fontSize: 'var(--t-sm)', color: 'var(--ink-3)' }}>{children}</span>;
}

/* -------------------------------------------------------------- 新建账号 */

/**
 * 平台开户。
 *
 * <p>角色只给学员与导师两档：代理是自助申请 + 平台审批那条路（要填层级、上级推广码、
 * 收款信息，还要落 agent_profile），从这里塞一个 role=AGENT 出来只会得到一个
 * 没有档案的空壳 —— 能登录，但代理端每一页都 404。超管更不给，
 * 能自助增殖超管的话，一次越权就是永久的。
 */
function CreateUserDialog({ open, onClose, onDone }: {
  open: boolean; onClose: () => void;
  onDone: (r: any, phone: string) => void;
}) {
  const [phone, setPhone] = React.useState('');
  const [role, setRole] = React.useState('STUDENT');
  const [realName, setRealName] = React.useState('');
  React.useEffect(() => {
    if (!open) { setPhone(''); setRole('STUDENT'); setRealName(''); }
  }, [open]);

  const phoneOk = /^1[3-9]\d{9}$/.test(phone.trim());

  return (
    <ActionDialog open={open} onClose={onClose} verb="创建" title="新建账号"
      disabled={!phoneOk}
      onDone={(r: any) => { onDone(r, phone.trim()); onClose(); }}
      consequences={<>
        系统会生成一个<b>一次性初始密码</b>并只显示一次，该账号首次登录必须改密。<br />
        手机号就是登录名，<b>创建后不能改</b>。已经注册过的号会被拒绝。<br />
        代理不能从这里开 —— 代理走代理端自助申请、平台审批那条路。
      </>}
      onConfirm={() => api.post('/api/admin/users', {
        phone: phone.trim(), role,
        realName: realName.trim() || undefined,
      })}>
      <div style={{ display: 'grid', gap: 'var(--s4)' }}>
        <Ctl label="手机号" width={220} hint={phone && !phoneOk ? '格式不对' : '11 位，就是登录名'}>
          <TextIn value={phone} inputMode="numeric" placeholder="13800138000"
                  onChange={e => setPhone(e.target.value.replace(/\D/g, '').slice(0, 11))} />
        </Ctl>
        <Ctl label="角色" width={180}>
          <Pick value={role} all={false} onChange={setRole}
                options={[{ value: 'STUDENT', text: '学员' }, { value: 'MENTOR', text: '导师' }]} />
        </Ctl>
        <Ctl label="真实姓名" width={220} hint="可留空；看课水印会用它">
          <TextIn value={realName} onChange={e => setRealName(e.target.value.slice(0, 32))} />
        </Ctl>
      </div>
    </ActionDialog>
  );
}

/* ---------------------------------------------------------------- 赠课 */

/**
 * 直接开通课程（赠课 / 内部账号 / 试用）。
 *
 * <p>在这条路存在之前，运营想让某个人能看课，唯一的动作是「订单 → 确认对公转账到账」——
 * 那会把一次白送记成<b>一笔真实收入</b>并照着结佣金。这里走 enrollment.source='GRANT'，
 * 不产生订单、不产生归属、不结佣金。原因必填，会进审计日志。
 */
function GrantDialog({ open, userId, onClose, onDone }: {
  open: boolean; userId: number; onClose: () => void; onDone: () => void;
}) {
  const courses = useAsync<any>(() => (open ? api.get('/api/admin/courses') : Promise.resolve(null)), [open]);
  const [courseId, setCourseId] = React.useState('');
  const [months, setMonths] = React.useState('');
  const [reason, setReason] = React.useState('');
  React.useEffect(() => { if (!open) { setCourseId(''); setMonths(''); setReason(''); } }, [open]);

  const list: any[] = courses.data?.items ?? courses.data?.courses ?? [];

  return (
    <ActionDialog open={open} onClose={onClose} verb="开通" title={`给账号 #${userId} 开通课程`}
      disabled={!courseId || !reason.trim()}
      onDone={() => { onDone(); onClose(); }}
      consequences={<>
        这是<b>赠课</b>：不产生订单、不计入成交额、不产生归属代理、<b>不结佣金</b>。<br />
        原因会写进审计日志 —— 白送一门课要有人为它签字。<br />
        一个账号一门课只能有一份权益，已经有的会被拒绝。
      </>}
      onConfirm={() => api.post(`/api/admin/users/${userId}/enrollments`, {
        courseId: Number(courseId),
        months: months ? Number(months) : undefined,
        reason: reason.trim(),
      })}>
      <div style={{ display: 'grid', gap: 'var(--s4)' }}>
        <Ctl label="课程" width={280} grow>
          <Pick value={courseId} all={false} onChange={setCourseId}
                options={list.map((c: any) => ({ value: String(c.id), text: c.title }))} />
        </Ctl>
        <Ctl label="有效期（月）" width={150} hint="留空按课程默认">
          <TextIn value={months} inputMode="numeric"
                  onChange={e => setMonths(e.target.value.replace(/\D/g, '').slice(0, 3))} />
        </Ctl>
        <ReasonBox value={reason} onChange={setReason} label="赠课原因"
                   hint="例：讲师内部账号 / 客户试用 / 客服补偿工单 #123。会进审计日志" />
      </div>
    </ActionDialog>
  );
}

/* ------------------------------------------------------------ 更正姓名 */

/**
 * 更正真实姓名。
 *
 * <p>学员自助填名那条路是**写一次**（水印是溯源证据，能随时改名等于给反取证开口），
 * 填错了他自己改不了，那边的提示写的是「需要更正请联系平台」——
 * <b>这里就是那句话的落点</b>。没有它，那句提示是空头支票。
 */
function RenameDialog({ open, userId, current, onClose, onDone }: {
  open: boolean; userId: number; current?: string | null;
  onClose: () => void; onDone: () => void;
}) {
  const [name, setName] = React.useState('');
  const [reason, setReason] = React.useState('');
  React.useEffect(() => { if (open) { setName(current ?? ''); setReason(''); } }, [open, current]);

  return (
    <ActionDialog open={open} onClose={onClose} verb="更正" title={`更正账号 #${userId} 的姓名`}
      disabled={!name.trim() || !reason.trim()}
      onDone={() => { onDone(); onClose(); }}
      consequences={<>
        这个名字会出现在<b>学员看课时画面上的防盗录水印</b>里，也是他自己改不了的那一个。<br />
        改前改后都会写进审计日志（USER_REAL_NAME）。<br />
        如果这个账号有代理档案，这一列<b>同时是提现打款的收款人姓名</b>，改完要与打款一致。
      </>}
      onConfirm={() => api.post(`/api/admin/users/${userId}/real-name`, {
        realName: name.trim(), reason: reason.trim(),
      })}>
      <div style={{ display: 'grid', gap: 'var(--s4)' }}>
        <Ctl label="姓名" width={220} hint={current ? `当前：${current}` : '当前为空'}>
          <TextIn value={name} onChange={e => setName(e.target.value.slice(0, 32))} />
        </Ctl>
        <ReasonBox value={reason} onChange={setReason} label="更正原因"
                   hint="例：学员报错字、身份证核对不符。会进审计日志" />
      </div>
    </ActionDialog>
  );
}
