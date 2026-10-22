/**
 * 推广。链接 + 二维码 + 一段能直接发出去的话。
 *
 * 归属是 first-touch：学员第一次是从谁的链接注册的，就永远算谁的，
 * 之后再点别人的链接也不会改。这条规则要在这一页说明白 ——
 * 不说的话，代理会以为"再发一次链接就能把人抢回来"，然后为此吵架。
 */
import React from 'react';
import QRCode from 'qrcode';
import {
  Async, Button, Card, PageHead, Skeleton, useAsync,
} from '@wangke/shared';
import { AgentApi, type Promote } from '../api';
import { Gate, useMe } from '../gate';
import { CopyButton, Note, Section } from '../kit';

export default function PromotePage() {
  const { me } = useMe();

  return (
    <>
      <PageHead
        title="推广"
        desc="把链接或二维码发出去。从这里注册的人，以后买课都算你的。"
      />
      {/* 推广码是建代理档案时就分配的，所以「审核中」也能先看链接 —— 这一页对
          PENDING 是放行的。但档案<b>压根不存在</b>时接口必然拒绝，
          此时发请求只会换来一句红色报错，用户读不出下一步是什么。
          交给 Gate 画「你还不是代理 → 去申请」，一个请求都不发。 */}
      {me.agent ? <Loaded /> : <Gate>{null}</Gate>}
    </>
  );
}

function Loaded() {
  const { me } = useMe();
  const state = useAsync(() => AgentApi.promote(), [me.agent?.id]);
  const notYetActive = me.agent?.status !== 'ACTIVE';

  return (
    <>
      {notYetActive && (
        <div style={{ marginBottom: 'var(--s4)' }}>
          <Note tone="warn" title="你的代理资格还没生效">
            链接现在可以先看、先复制，但要等审核通过之后，从链接注册的学员才会算到你名下。
          </Note>
        </div>
      )}

      <Async state={state}>
        {(p: Promote) => <Body p={p} level={me.agent!.level} />}
      </Async>
    </>
  );
}

function Body({ p, level }: { p: Promote; level: number }) {
  const [qr, setQr] = React.useState<string | null>(null);
  const [qrErr, setQrErr] = React.useState(false);

  React.useEffect(() => {
    let live = true;
    if (!p.refUrl) { setQrErr(true); return; }
    // 颜色从令牌里读，不写死。qrcode 要的是十六进制字符串，令牌本身就是十六进制。
    // 用「墨 + 卡片面」而不是主色：彩色码在部分老扫码器上识别率会掉，
    // 而这张图会被印在传单上、投在屏幕上，扫不出来是最贵的失败。
    const css = getComputedStyle(document.documentElement);
    const dark = css.getPropertyValue('--ink').trim();
    const light = css.getPropertyValue('--surface').trim();
    QRCode.toDataURL(p.refUrl, {
      width: 480, margin: 1,
      color: dark && light ? { dark, light } : undefined,
      errorCorrectionLevel: 'M',
    })
      .then(url => { if (live) setQr(url); })
      .catch(() => { if (live) setQrErr(true); });
    return () => { live = false; };
  }, [p.refUrl]);

  const pitch = [
    '我在带这门课，名额有限，感兴趣可以先看看：',
    p.refUrl,
    '',
    '从这个链接进去注册就行，有问题随时问我。',
  ].join('\n');

  return (
    <div style={{ display: 'grid', gap: 'var(--s4)',
                  gridTemplateColumns: 'repeat(auto-fit, minmax(min(300px, 100%), 1fr))' }}>
      <Section title="我的推广链接" desc="发微信、发朋友圈、放在名片上都行。">
        <div style={{ display: 'grid', gap: 'var(--s4)' }}>
          <div>
            <div style={{ fontSize: 'var(--t-sm)', color: 'var(--ink-3)',
                          marginBottom: 'var(--s2)' }}>链接</div>
            <div className="num" style={{
              background: 'var(--surface-2)', border: '1px solid var(--line)',
              borderRadius: 'var(--r-md)', padding: 'var(--s3) var(--s4)',
              wordBreak: 'break-all', fontSize: 'var(--t-sm)', userSelect: 'all',
            }}>{p.refUrl || '还没生成'}</div>
            <div style={{ marginTop: 'var(--s3)', display: 'flex', gap: 'var(--s2)', flexWrap: 'wrap' }}>
              <CopyButton text={p.refUrl} label="复制链接" variant="primary" size="md" />
              <CopyButton text={pitch} label="复制一段可以直接发的话" variant="ghost" size="md" />
            </div>
          </div>

          <div>
            <div style={{ fontSize: 'var(--t-sm)', color: 'var(--ink-3)',
                          marginBottom: 'var(--s2)' }}>我的推广码</div>
            <div className="num" style={{ fontSize: 'var(--t-h2)', fontWeight: 600,
                                          letterSpacing: '.04em' }}>{p.agentCode}</div>
            <div style={{ fontSize: 'var(--t-xs)', color: 'var(--ink-3)', marginTop: 'var(--s1)' }}>
              {level === 1
                ? '别人申请成为你的二级代理时，要填的就是这一串。'
                : '用于识别从你链接注册的学员；二级代理不能继续发展下级代理。'}
            </div>
          </div>
        </div>
      </Section>

      <Section title="二维码" desc="长按保存，或者直接让人扫。">
        <div style={{ display: 'grid', justifyItems: 'center', gap: 'var(--s3)' }}>
          {qrErr ? (
            <div style={{ padding: 'var(--s6) var(--s4)', textAlign: 'center',
                          color: 'var(--ink-3)', fontSize: 'var(--t-sm)' }}>
              二维码没生成出来。用上面的链接也一样能推广，或者刷新页面再试一次。
            </div>
          ) : qr === null ? (
            <Skeleton h={220} w={220} />
          ) : (
            <>
              <img
                src={qr} alt={`推广二维码 ${p.agentCode}`}
                style={{
                  width: 220, height: 220, display: 'block', borderRadius: 'var(--r-md)',
                  border: '1px solid var(--line)', background: 'var(--surface)',
                }}
              />
              <a href={qr} download={`推广二维码-${p.agentCode}.png`}>
                <Button variant="ghost" size="sm">下载二维码</Button>
              </a>
            </>
          )}
        </div>
      </Section>

      <Card style={{ gridColumn: '1 / -1' }}>
        <Note tone="info" title="学员算谁的，是这么定的">
          学员<b>第一次</b>是从谁的链接注册的，就一直算谁的，之后再点别人的链接也不会改。
          已经注册过的老账号，再点链接不会重新归属 —— 想把老学员算到自己名下，
          正确的做法是给他一个你的激活码。
        </Note>
      </Card>
    </div>
  );
}
