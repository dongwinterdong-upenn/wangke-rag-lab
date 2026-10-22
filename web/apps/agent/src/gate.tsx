/**
 * 「你现在能不能做生意」这件事，只在这一个地方判断。
 *
 * 代理的状态有四种：还没申请、审核中、已生效、被停用/终止。
 * 如果让每个经营页面各自判断，迟早有页面漏判 —— 而漏判的表现是
 * 一个待审核的代理点进"我的激活码"，接口 403，界面弹一句"没有权限"。
 * 他会以为系统坏了，然后打电话给客服。
 *
 * 所以：审核中就明明白白写"审核中"，并告诉他大概多久、要等什么。
 */
import React from 'react';
import { Link } from 'react-router-dom';
import {
  Button, Card, Empty, IconAlert, IconFile,
} from '@wangke/shared';
import type { AgentMe } from './api';
import { Note } from './kit';

type Ctx = { me: AgentMe; reload: () => void };
const MeCtx = React.createContext<Ctx | null>(null);

export function MeProvider({ value, children }: { value: Ctx; children: React.ReactNode }) {
  return <MeCtx.Provider value={value}>{children}</MeCtx.Provider>;
}

export function useMe(): Ctx {
  const v = React.useContext(MeCtx);
  if (!v) throw new Error('useMe 必须在 MeProvider 里用');
  return v;
}

/** 已生效的代理档案；没生效时为 null。经营页面拿它来决定要不要发请求。 */
export function useActiveAgent() {
  const { me } = useMe();
  return me.agent && me.agent.status === 'ACTIVE' ? me.agent : null;
}

/**
 * 经营页面的门。没过门就画占位，<b>不发请求、不报错</b>。
 *
 * 占位不是"暂无权限"四个字：每一种状态都要说清「现在是什么情况、
 * 接下来该做什么」，否则用户唯一的下一步就是找客服。
 */
export function Gate({ children }: { children: React.ReactNode }) {
  const { me } = useMe();
  const agent = me.agent;

  if (!agent) {
    const app = me.application;
    if (app && app.status === 'SUBMITTED') return <Reviewing submittedAt={app.createdAt} />;
    if (app && app.status === 'REJECTED') {
      return (
        <Card>
          <Empty
            icon={<IconAlert size={22} />}
            title="上一次申请没有通过"
            desc={app.rejectReason || '平台没有填写具体原因，可以联系对接人问一下。'}
            action={<Link to="/apply"><Button variant="accent">修改资料，重新申请</Button></Link>}
          />
        </Card>
      );
    }
    return (
      <Card>
        <Empty
          icon={<IconFile size={22} />}
          title="你还不是代理"
          desc="成为代理之后才能进货、拿激活码、看学员和结佣金。填一份资料，平台审核通过就能开始。"
          action={<Link to="/apply"><Button variant="accent">去申请</Button></Link>}
        />
      </Card>
    );
  }

  if (agent.status === 'PENDING') return <Reviewing submittedAt={agent.createdAt} />;

  if (agent.status === 'SUSPENDED') {
    return (
      <Card>
        <Empty
          icon={<IconAlert size={22} />}
          title="你的代理资格已被暂停"
          desc={agent.statusNote
            || '暂停期间不能进货、不能提现，已经发出去的激活码不受影响，学员照常看课。请联系平台对接人处理。'}
        />
      </Card>
    );
  }

  if (agent.status === 'TERMINATED') {
    // ★ TERMINATED 在库里是两件事共用的一个值：「申请被驳回」和「合作被终止」。
    //
    //   后台那两个按钮是分开的，但都写 status='TERMINATED'，所以只看状态分不出来。
    //   分不出来的代价不是措辞难看：一个刚被驳回的申请人会读到「已经产生、且过了
    //   冻结期的佣金仍可提现」—— 他从没合作过，也没有一分钱佣金；而真正该给他的
    //   那句「改完资料再交一次」和那个按钮，他一个都看不到。
    //
    //   判据由后端给（approvedAt 是否为空），不在这里自己推：
    //   前端能拿到 approvedAt，但「什么叫从未通过过审批」这条口径必须只有一处。
    if (agent.rejectedApplication) {
      return (
        <Card>
          <Empty
            icon={<IconAlert size={22} />}
            title="这次申请没有通过"
            desc={agent.statusNote
              || '平台没有填写具体原因，可以联系对接人问一下，也可以补齐资料后再交一次。'}
            action={agent.canReapply
              ? <Link to="/apply"><Button variant="accent">修改资料，重新申请</Button></Link>
              : undefined}
          />
        </Card>
      );
    }
    return (
      <Card>
        <Empty
          icon={<IconAlert size={22} />}
          title="你的代理合作已终止"
          desc={agent.statusNote
            || '不能再进货。已经产生、且过了冻结期的佣金仍可提现，如需处理请联系平台对接人。'}
        />
      </Card>
    );
  }

  return <>{children}</>;
}

function Reviewing({ submittedAt }: { submittedAt?: string | null }) {
  return (
    <Card>
      <div style={{ display: 'grid', gap: 'var(--s4)', padding: 'var(--s5) 0',
                    justifyItems: 'center', textAlign: 'center' }}>
        <div style={{ fontSize: 'var(--t-display)', lineHeight: 1, opacity: .35 }}>◷</div>
        <div style={{ fontSize: 'var(--t-h2)', fontWeight: 600 }}>资料审核中</div>
        <div style={{ fontSize: 'var(--t-sm)', color: 'var(--ink-2)', maxWidth: 460 }}>
          平台正在核对你提交的主体与结算信息{submittedAt ? `（${submittedAt.slice(0, 10)} 提交）` : ''}。
          通过之后这里会变成你的工作台，进货、发码、提现都在这几页里完成。
        </div>
        <Note tone="info" style={{ textAlign: 'left', maxWidth: 460 }}>
          审核期间可以先看看「推广」页：那里的推广链接在你通过审核后立即生效，
          现在可以先想好发给谁。
        </Note>
        <Link to="/promote"><Button variant="ghost">先去看推广链接</Button></Link>
      </div>
    </Card>
  );
}
