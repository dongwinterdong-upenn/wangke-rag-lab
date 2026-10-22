import React from 'react';
import { Link } from 'react-router-dom';
import { api } from '@wangke/shared/api';
import { PageHead } from '@wangke/shared/shell';
import { Async, Button, Table, useAsync } from '@wangke/shared/ui';
import type { Col } from '@wangke/shared/ui';
import { Blank, Board, Ctl, Filters, Fold, Note, Pager, Pick, Raw, TextIn } from '../lib/kit';
import { fmtTime, num } from '../lib/fmt';
import { hasFilter, qs, useQ } from '../lib/q';

/** 对象类型 → 能跳过去的页面。查审计时最常见的下一步就是「去看看这条现在长什么样」。 */
const TARGET_LINK: Record<string, (id: string) => string> = {
  agent_profile: id => `/agents${qs({ sel: id })}`,
  sys_user: id => `/users${qs({ sel: id })}`,
  biz_order: id => `/orders${qs({ sel: id })}`,
  refund_record: id => `/refunds${qs({ sel: id })}`,
  withdraw_order: id => `/withdraws${qs({ sel: id })}`,
  course: id => `/courses${qs({ course: id })}`,
};

const TARGET_TEXT: Record<string, string> = {
  agent_profile: '代理', sys_user: '账号', biz_order: '订单', refund_record: '退款单',
  withdraw_order: '提现单', course: '课程', chapter: '章', lesson: '讲次',
  commission_record: '佣金记录', risk_action: '风控处置', invoice_request: '开票申请',
  activation_code: '激活码', enrollment: '课程权益',
  qa_ticket: '答疑工单',
};

export function Audit() {
  const { sp, get, int, set, reset } = useQ();
  const operatorId = get('operatorId'), action = get('action');
  const targetType = get('targetType'), targetId = get('targetId');
  const from = get('from'), to = get('to');
  const page = int('page', 1), size = int('size', 50);

  const list = useAsync<any>(
    () => api.get(`/api/admin/audit${qs({ operatorId, action, targetType, targetId, from, to, page, size })}`),
    [operatorId, action, targetType, targetId, from, to, page, size]);

  // 动作字典从库里现取：写死一份的话，后端新增一个动作，筛选框里永远不会出现它
  const dict = useAsync<any>(() => api.get('/api/admin/audit/actions'), []);
  const actionOpts = (dict.data?.items ?? []).map((r: any) => ({
    value: String(r.action), text: `${r.action}（${num(r.n)}）`,
  }));

  const filtered = hasFilter(sp);

  const cols: Col<any>[] = [
    { key: 'createdAt', title: '时间',
      render: r => <span className="num" style={{ fontSize: 'var(--t-xs)' }}>{fmtTime(r.createdAt, true)}</span> },
    { key: 'operator', title: '操作人', render: r => (
      <div style={{ minWidth: 110 }}>
        <div>{r.operatorNickname || (r.operatorId ? `#${r.operatorId}` : '系统')}</div>
        {r.operatorRole && <div style={{ color: 'var(--ink-3)', fontSize: 'var(--t-xs)' }}>
          {r.operatorRole}</div>}
      </div>) },
    { key: 'action', title: '动作',
      render: r => <span className="num" style={{ fontSize: 'var(--t-xs)' }}>{r.action}</span> },
    { key: 'target', title: '对象', render: r => {
      const to2 = TARGET_LINK[r.targetType]?.(String(r.targetId));
      const text = `${TARGET_TEXT[r.targetType] ?? r.targetType} #${r.targetId}`;
      return to2 ? <Link to={to2}>{text}</Link> : <span>{text}</span>;
    } },
    { key: 'clientIp', title: 'IP',
      render: r => <span className="num" style={{ fontSize: 'var(--t-xs)' }}>{r.clientIp || '—'}</span> },
    { key: 'diff', title: '改了什么', render: r => (
      <Fold summary="展开">
        <div style={{ display: 'grid', gap: 'var(--s2)', minWidth: 260 }}>
          <div><div style={{ color: 'var(--ink-3)', fontSize: 'var(--t-xs)' }}>改之前</div>
            <Raw text={r.beforeJson} /></div>
          <div><div style={{ color: 'var(--ink-3)', fontSize: 'var(--t-xs)' }}>改之后</div>
            <Raw text={r.afterJson} /></div>
        </div>
      </Fold>) },
  ];

  return (
    <>
      <PageHead title="审计日志"
        desc="谁、什么时候、改了哪条记录、改前改后各是什么。只读 —— 一份能被后台改动的追责记录，在真出事时是没有证明力的。" />

      <Filters active={filtered} onReset={() => reset(['size'])}>
        <Ctl label="操作人账号 ID" width={140}>
          <TextIn defaultValue={operatorId} inputMode="numeric" placeholder="回车"
                  onKeyDown={e => { if (e.key === 'Enter') set({ operatorId: e.currentTarget.value.trim() }); }} />
        </Ctl>
        <Ctl label="动作" width={230} hint={dict.error ? '动作字典没取到，可手填' : undefined}>
          <Pick value={action} onChange={v => set({ action: v })} options={actionOpts} />
        </Ctl>
        <Ctl label="对象类型" width={160}>
          <Pick value={targetType} onChange={v => set({ targetType: v })}
                options={Object.entries(TARGET_TEXT).map(([value, text]) => ({ value, text: `${text}（${value}）` }))} />
        </Ctl>
        <Ctl label="对象 ID" width={110}>
          <TextIn defaultValue={targetId} placeholder="回车"
                  onKeyDown={e => { if (e.key === 'Enter') set({ targetId: e.currentTarget.value.trim() }); }} />
        </Ctl>
        <Ctl label="从" width={150}>
          <TextIn type="date" value={from} onChange={e => set({ from: e.target.value })} />
        </Ctl>
        <Ctl label="到" width={150}>
          <TextIn type="date" value={to} onChange={e => set({ to: e.target.value })} />
        </Ctl>
      </Filters>

      <div style={{ marginBottom: 'var(--s4)' }}>
        <Note>
          只有<b>改动性</b>的操作才写审计：查询、翻页不写。<b>五个例外</b>是「看完整手机号」
          （USER_PHONE_REVEAL）、「看提现单的完整收款账号」、「代理查看/导出激活码明文」
          （CODE_REVEAL）、「学员查看自己订单的激活码」（STUDENT_CODE_REVEAL）、
          「平台查看激活码明文」（ADMIN_CODE_REVEAL）—— 它们本身是只读的，
          但看的是能直接拿去用的东西，所以也留痕。
          <br />
          后三条<b>不是同一个人干的</b>：CODE_REVEAL 是代理、STUDENT_CODE_REVEAL 是学员本人、
          ADMIN_CODE_REVEAL 才是平台。纠纷复盘要查的正是「这串码到底谁看过」。
        </Note>
      </div>

      <Board flush title={`审计记录${list.data ? ` · ${num(list.data.total)} 条` : ''}`}>
        <Async state={list}>{(d: any) => (
          <>
            <Table cols={cols} rows={d.items} rowKey={r => r.id}
                   empty={<Blank filtered={filtered} thing="审计记录" onClear={() => reset(['size'])}
                     action={<span style={{ fontSize: 'var(--t-sm)' }}>
                       后台还没有人做过改动性操作。做一次（比如审批一家代理），这里就会出现记录。
                     </span>} />} />
            <Pager total={num(d.total)} page={page} size={size}
                   onPage={p => set({ page: p }, true)} onSize={s => set({ size: s })} />
          </>
        )}</Async>
      </Board>
    </>
  );
}
