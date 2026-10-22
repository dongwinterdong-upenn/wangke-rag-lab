/**
 * 结业证明验真页。**公开，不需要登录。**
 *
 * 这一页的读者是拿到证明的第三方（用人单位、客户），不是学员本人。
 * 所以它只回答一个问题："这份证明是真的吗？"——而且必须把
 * "查无此号"和"真发过、但已被吊销"分开说。两者混成一句"验证失败"的话，
 * 验真这件事就等于没做：伪造者会说"系统抽风了，你再试试"。
 */
import { useParams } from 'react-router-dom';
import { api, Async, Card, dateOnly, ErrorState, Skeleton, Tag, useAsync } from '@wangke/shared';
import type { CertVerify as CertVerifyData } from '../types';
import { Note, Screen, Stack } from '../kit';

export default function CertVerifyPage() {
  const { certNo } = useParams();
  const state = useAsync<CertVerifyData>(
    () => api.get<CertVerifyData>(`/api/public/cert/verify/${encodeURIComponent(certNo ?? '')}`),
    [certNo],
  );

  return (
    <div style={{ padding: 'var(--s7) var(--s4)' }}>
      <Screen maxWidth={520}>
        <div style={{ textAlign: 'center', marginBottom: 'var(--s6)' }}>
          <div style={{ fontSize: 'var(--t-xs)', color: 'var(--ink-3)', letterSpacing: '.14em' }}>
            课程结业证明 · 验真
          </div>
          <h1 className="num" style={{ fontSize: 'var(--t-h2)', marginTop: 'var(--s2)' }}>
            {certNo}
          </h1>
        </div>

        {state.loading ? <Skeleton h={220} /> : state.error ? (
          <Card>
            {/* 404 在这一页有确定含义：这个编号平台从来没发过。
                和"网络不通"混成一句"查询失败"的话，验真就失去意义了。 */}
            <ErrorState
              message={/404|不存在|没有找到/.test(state.error)
                ? '没有查到这个编号。平台从未签发过该编号的结业证明。'
                : `暂时查不了：${state.error}`}
              onRetry={state.reload}
            />
          </Card>
        ) : (
          <Async state={state}>
            {(v) => {
              const revoked = v.status === 'REVOKED' || !!v.revokedAt;
              return (
                <Card>
                  <Stack gap="var(--s4)">
                    <div style={{ textAlign: 'center' }}>
                      <div aria-hidden style={{
                        width: 56, height: 56, margin: '0 auto var(--s4)', borderRadius: 'var(--r-full)',
                        background: revoked ? 'var(--danger-soft)' : 'var(--ok-soft)',
                        color: revoked ? 'var(--danger)' : 'var(--ok)',
                        display: 'grid', placeItems: 'center', fontSize: 'var(--t-h1)',
                      }}>{revoked ? '✕' : '✓'}</div>
                      <div style={{ fontSize: 'var(--t-h3)', fontWeight: 600 }}>
                        {revoked ? '这份结业证明已被吊销' : '这份结业证明真实有效'}
                      </div>
                      <div style={{ marginTop: 'var(--s2)' }}>
                        {revoked ? <Tag tone="danger">已吊销</Tag> : <Tag tone="ok">有效</Tag>}
                      </div>
                    </div>

                    <div style={{ height: 1, background: 'var(--line)' }} />

                    <Field k="课程" v={v.courseTitle ?? '—'} />
                    <Field k="持有人" v={v.holder ?? '—'} />
                    <Field k="签发日期" v={dateOnly(v.issuedAt)} num />
                    {revoked && <Field k="吊销日期" v={dateOnly(v.revokedAt)} num />}

                    <Note>
                      本证明表示持有人已完成该课程的学习并通过课程测评，
                      不构成任何职业资格或行业认证。持有人姓名按隐私要求做了脱敏。
                    </Note>
                  </Stack>
                </Card>
              );
            }}
          </Async>
        )}
      </Screen>
    </div>
  );
}

function Field({ k, v, num }: { k: string; v: string; num?: boolean }) {
  return (
    <div style={{ display: 'flex', justifyContent: 'space-between', gap: 'var(--s4)' }}>
      <span style={{ color: 'var(--ink-3)', fontSize: 'var(--t-sm)', flexShrink: 0 }}>{k}</span>
      <span className={num ? 'num' : undefined} style={{ textAlign: 'right' }}>{v}</span>
    </div>
  );
}
