/**
 * 申请成为代理。
 *
 * 二级必须填推荐人的推广码：这不是形式，它决定了佣金往哪儿走
 *（二级下单，返给他上级的一级）。填错了以后改不了 —— 层级关系一旦落库
 * 就被触发器锁住了，所以这一栏要在提交前反复说清楚。
 */
import React from 'react';
import { useNavigate } from 'react-router-dom';
import {
  ApiError, Button, Card, Field, Input, PageHead, Select, StatusTag, useToast,
} from '@wangke/shared';
import { AgentApi, type SubjectType } from '../api';
import { useMe } from '../gate';
import { Confirm, FormRow, KV, Note, Section } from '../kit';

export default function Apply() {
  const { me, reload } = useMe();
  const nav = useNavigate();
  const toast = useToast();

  // 被驳回的申请可以改完再交一次。这一页因此有两种形态：
  //   有档案且不能重投 → 只读的「我的代理身份」
  //   没有档案，或档案是一份被驳回的申请 → 表单（后者用上一次填的内容打底，
  //     否则「修改资料」变成「重填一遍」，而他多半只需要改一个字段）
  const reapply = me.agent?.canReapply === true;
  const prev = reapply ? me.agent : null;

  const [level, setLevel] = React.useState<1 | 2>(prev?.level ?? 1);
  // 上级的推广码后端不回给下级以外的人，这一栏只有二级需要，回填拿得到的那份
  const [parentCode, setParentCode] = React.useState(prev?.parentAgentCode || '');
  const [contactName, setContactName] = React.useState(
    prev?.contactName || me.user.realName || '');
  const [subjectType, setSubjectType] = React.useState<SubjectType>(
    prev?.subjectType || 'INDIVIDUAL');
  const [companyName, setCompanyName] = React.useState(prev?.companyName || '');
  const [taxNo, setTaxNo] = React.useState(prev?.taxNo || '');
  const [bankName, setBankName] = React.useState(prev?.settleBankName || '');
  // 结算账号后端只回掩码（****1234），回填掩码等于让他提交一串假账号 ——
  // 宁可空着让他重填一次，也不能把掩码当成真值送回去
  const [bankAcct, setBankAcct] = React.useState('');
  const [err, setErr] = React.useState('');
  const [confirm, setConfirm] = React.useState(false);
  const [busy, setBusy] = React.useState(false);

  // 已经是代理了：这一页变成"你的代理身份"，不再是表单。
  // 但被驳回的申请不算「已经是代理」—— 它在库里也是一行 agent_profile
  // （status=TERMINATED），照原来这个判断走，他会看到一张写着「已终止」的只读身份卡，
  // 而 gate.tsx 那个「修改资料，重新申请」的按钮正是把他送到这一页来的。
  if (me.agent && !reapply) {
    const a = me.agent;
    return (
      <>
        <PageHead title="我的代理身份" desc="层级和上级一旦确定就不能再改，要调整只能联系平台。" />
        <Section title="身份">
          <KV label="推广码">
            <span className="num">{a.agentCode}</span>
          </KV>
          <KV label="层级">{a.level === 1 ? '一级代理' : '二级代理'}</KV>
          {a.level === 2 && (
            <KV label="我的上级" hint="你进货时，差价部分作为佣金结给他。">
              <span className="num">{a.parentAgentCode || '—'}</span>
            </KV>
          )}
          <KV label="状态"><StatusTag status={a.status} /></KV>
          <KV label="通过时间">{a.approvedAt ? a.approvedAt.slice(0, 10) : '—'}</KV>
        </Section>
        <div style={{ marginTop: 'var(--s4)' }}>
          <Button onClick={() => nav('/')}>回工作台</Button>
        </div>
      </>
    );
  }

  const submitted = me.application?.status === 'SUBMITTED';
  const rejected = me.application?.status === 'REJECTED';

  function validate(): string {
    if (!contactName.trim()) return '请填联系人姓名';
    if (level === 2 && !parentCode.trim()) return '二级代理必须填推荐人的推广码';
    if (subjectType !== 'INDIVIDUAL' && !companyName.trim()) return '选了个体工商户或企业，就要填主体名称';
    if (bankAcct && !bankName.trim()) return '填了结算账号就要填开户行';
    return '';
  }

  async function doSubmit() {
    setBusy(true);
    try {
      await AgentApi.apply({
        level,
        parentAgentCode: level === 2 ? parentCode.trim() : undefined,
        contactName: contactName.trim(),
        subjectType,
        companyName: companyName.trim() || undefined,
        taxNo: taxNo.trim() || undefined,
        settleBankName: bankName.trim() || undefined,
        settleBankAcct: bankAcct.trim() || undefined,
      });
      setConfirm(false);
      toast({ tone: 'ok', text: '已提交，等平台审核' });
      reload();
    } catch (e) {
      setConfirm(false);
      setErr(e instanceof ApiError ? e.message : '提交失败，请重试');
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <PageHead
        title="申请成为代理"
        desc="通过之后你就能按代理价进货、拿激活码发给学员，并按规则拿佣金。"
      />

      {submitted && (
        <div style={{ marginBottom: 'var(--s4)' }}>
          <Note tone="warn" title="上一份申请还在审核中">
            平台正在核对资料。审核期间可以改资料重新提交，以最后一次为准。
          </Note>
        </div>
      )}
      {rejected && (
        <div style={{ marginBottom: 'var(--s4)' }}>
          <Note tone="danger" title="上一次没通过">
            {me.application?.rejectReason || '平台没写具体原因，可以联系对接人问一下。'}
          </Note>
        </div>
      )}
      {reapply && (
        <div style={{ marginBottom: 'var(--s4)' }}>
          <Note tone="danger" title="上一次申请没有通过">
            {me.agent?.statusNote || '平台没有填写具体原因，可以联系对接人问一下。'}
            <div style={{ marginTop: 'var(--s2)' }}>
              下面是你上次填的内容，改完直接再交一次即可。
              推广码不会变（{me.agent?.agentCode}），之前发出去的推广链接仍然是这一个。
              结算账号需要重填一遍：这一栏平台只回掩码，回填掩码等于交上去一串假账号。
            </div>
          </Note>
        </div>
      )}

      <div style={{ display: 'grid', gap: 'var(--s4)' }}>
        <Section title="选层级" desc="选错了没法自己改，提交前请确认。">
          <FormRow>
            <LevelCard
              on={level === 1} onPick={() => setLevel(1)}
              title="一级代理"
              points={[
                '直接和平台签约、直接从平台进货',
                '进货价是全平台最低的一档',
                '可以推荐二级代理，二级进货时你拿差价',
              ]}
            />
            <LevelCard
              on={level === 2} onPick={() => setLevel(2)}
              title="二级代理"
              points={[
                '由一级代理推荐，挂在他名下',
                '进货价比一级高，差价就是一级的佣金',
                '不能再往下发展下级，层级只有两层',
              ]}
            />
          </FormRow>

          {level === 2 && (
            <div style={{ marginTop: 'var(--s4)' }}>
              <Field
                label="推荐人的推广码" required
                hint="向推荐你的一级代理要。这一栏决定了你的上级是谁，落库后不能更改。"
              >
                <Input
                  value={parentCode} placeholder="例如 AG7X2K9P"
                  onChange={e => setParentCode(e.target.value.trim().toUpperCase())}
                  style={{ fontFamily: 'var(--font-num)' }}
                />
              </Field>
            </div>
          )}
        </Section>

        <Section title="主体与联系人" desc="提现打款要求收款人和这里登记的实名一致，不能代收。">
          <FormRow>
            <Field label="联系人姓名" required hint="平台联系你本人时用">
              <Input value={contactName} onChange={e => setContactName(e.target.value)}
                     placeholder="真实姓名" />
            </Field>
            <Field label="主体类型" required>
              <Select value={subjectType}
                      onChange={e => setSubjectType(e.target.value as SubjectType)}>
                <option value="INDIVIDUAL">个人</option>
                <option value="SOLE_PROPRIETOR">个体工商户</option>
                <option value="COMPANY">企业</option>
              </Select>
            </Field>
          </FormRow>
          {subjectType !== 'INDIVIDUAL' && (
            <FormRow>
              <Field label="主体名称" required hint="和营业执照上完全一致">
                <Input value={companyName} onChange={e => setCompanyName(e.target.value)} />
              </Field>
              <Field label="统一社会信用代码" hint="开票要用，现在可以先不填">
                <Input value={taxNo} onChange={e => setTaxNo(e.target.value.trim())}
                       style={{ fontFamily: 'var(--font-num)' }} />
              </Field>
            </FormRow>
          )}
          {subjectType === 'INDIVIDUAL' && (
            <Note tone="info">
              以个人身份结算时，平台会按规定代扣代缴个人所得税，提现单上会分开列出「代扣」和「实际到账」。
              如果做得起量，建议注册个体工商户，税负和开票都方便些。
            </Note>
          )}
        </Section>

        <Section title="结算账户" desc="佣金打到这个账户。现在可以先不填，第一次提现之前补上就行。">
          <FormRow>
            <Field label="开户行" hint="例如：招商银行深圳分行">
              <Input value={bankName} onChange={e => setBankName(e.target.value)} />
            </Field>
            <Field label="银行账号">
              <Input value={bankAcct} inputMode="numeric"
                     onChange={e => setBankAcct(e.target.value.replace(/\s/g, ''))}
                     style={{ fontFamily: 'var(--font-num)' }} />
            </Field>
          </FormRow>
          <Note tone="warn">
            户名必须和上面的联系人/主体名称一致。打到别人账户的申请会被风控直接驳回，
            这条是硬规定，不接受"打给我爱人"这类情况。
          </Note>
        </Section>

        {err && <Note tone="danger">{err}</Note>}

        <Card>
          <Button size="lg" variant="accent" block onClick={() => {
            const v = validate();
            setErr(v);
            if (!v) setConfirm(true);
          }}>
            提交申请
          </Button>
        </Card>
      </div>

      <Confirm
        open={confirm}
        title="确认提交申请"
        confirmText="确认提交"
        loading={busy}
        onClose={() => setConfirm(false)}
        onConfirm={doSubmit}
        consequence={level === 2
          ? `你申请的是二级代理，上级是推广码 ${parentCode} 的那位。层级和上级一经审核通过就锁定，之后不能自己更改。`
          : '你申请的是一级代理，直接和平台结算。层级一经审核通过就锁定，之后不能自己更改。'}
      >
        <div style={{ fontSize: 'var(--t-sm)', color: 'var(--ink-2)' }}>
          提交后平台会人工核对资料。审核期间你还能改资料重新提交。
        </div>
      </Confirm>
    </>
  );
}

function LevelCard({ on, onPick, title, points }: {
  on: boolean; onPick: () => void; title: string; points: string[];
}) {
  return (
    <button type="button" onClick={onPick} style={{
      textAlign: 'left', cursor: 'pointer', padding: 'var(--s4)',
      borderRadius: 'var(--r-md)', background: on ? 'var(--brand-soft)' : 'var(--surface)',
      border: `1px solid ${on ? 'var(--brand)' : 'var(--line-strong)'}`,
      transition: `all var(--dur) var(--ease)`,
    }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--s2)',
                    fontWeight: 600, color: on ? 'var(--brand)' : 'var(--ink)' }}>
        <span style={{
          width: 14, height: 14, borderRadius: 'var(--r-full)', flexShrink: 0,
          border: `1px solid ${on ? 'var(--brand)' : 'var(--line-strong)'}`,
          background: on ? 'var(--brand)' : 'transparent',
          boxShadow: on ? 'inset 0 0 0 2px var(--surface)' : 'none',
        }} />
        {title}
      </div>
      <ul style={{ margin: 'var(--s3) 0 0', paddingLeft: 'var(--s5)',
                   fontSize: 'var(--t-sm)', color: 'var(--ink-2)', lineHeight: 'var(--lh-body)' }}>
        {points.map(p => <li key={p}>{p}</li>)}
      </ul>
    </button>
  );
}
