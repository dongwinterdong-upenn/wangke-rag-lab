/**
 * 我的结业证明。
 *
 * 措辞纪律（G14）：对外一律叫「课程结业证明」，不出现"认证""资格""证书"。
 * 这不是文字游戏 —— 它证明的只是"这个人学完了这门课并通过了测评"，
 * 说成资格认证会让持有人拿它去做它撑不起的事，最后砸的是平台。
 *
 * 分享用的是**验真链接**而不是图片：图片谁都能 P，链接指向平台自己的
 * 公开验真接口，任何人都能当场核对编号、课程、状态（含"已吊销"）。
 *
 * ## "考过了"不等于"能领证"（2026-09-03 多套试卷）
 *
 * 一门课下面现在有五份卷，只有综合卷 `grants_certificate=1`；另有一份开放卷
 * 压根不挂课程。原来这一页把**所有 passed 的场次**都当成可领，于是：
 * 通过章节卷会冒出一行"领取结业证明"，点下去被 CertificateService 的
 * `JOIN exam_paper ... AND p.grants_certificate=1` 挡掉；通过开放卷更是没有
 * courseId，屏幕上写出一句字面量「课程 #undefined」，点了后端 400。
 * 所以可领判据换成**这场考试所属那份卷发不发证**，与库里 trg_certificate_precheck
 * 同一条判据 —— 这一页宁可少给一个入口，也不给一个点了必然报错的按钮。
 */
import React from 'react';
import { Link } from 'react-router-dom';
import QRCode from 'qrcode';
import {
  api, Async, Button, Card, Copyable, dateOnly, Empty, PageHead, Skeleton,
  Tag, useAsync, useToast, IconCert,
} from '@wangke/shared';
import type { AttemptRow, MyCert, MyCourse } from '../types';
import { Note, Row, Screen, Stack } from '../kit';

/**
 * `GET /api/exam/papers` 的一行，这一页只用得到"这份卷发不发证、挂在哪门课上"。
 * 完整形状在 Exam.tsx 的 `PaperCard`（那一页要拿它画整张卡），这里不复制那一份 ——
 * 复制过来的字段这一页一个都不会渲染，只会在后端改字段时多一处要跟着改的地方。
 *
 * `grantsCertificate` 是 TINYINT(1)，过来可能是 `true` 也可能是 `1`，只按真假用。
 */
type PaperBrief = {
  paperId: number;
  /** 开放卷不挂课程，这两个键整个不出现。 */
  courseId?: number;
  courseTitle?: string;
  grantsCertificate: boolean | number;
};

/** `papers` 为 null 的语义是**取不到**，不是"一份卷都没有"。见 claimable 那一段。 */
type Data = {
  certs: MyCert[]; courses: MyCourse[]; attempts: AttemptRow[]; papers: PaperBrief[] | null;
};

export default function Cert() {
  const state = useAsync<Data>(async () => {
    const [certs, courses, attempts, papers] = await Promise.all([
      api.get<{ certificates: MyCert[] }>('/api/cert/my'),
      api.get<{ courses: MyCourse[] }>('/api/learn/my/courses'),
      // 用来找出"考过了但还没领"的课。取不到不该让整页红：
      // 已经领到的证明比"还能领"更要紧，前者是他现在就想看的东西。
      api.get<{ attempts: AttemptRow[] }>('/api/exam/attempts').catch(() => ({ attempts: [] })),
      // 卷的身份（发不发证、属于哪门课）只有这条接口有。同样不让它红整页，
      // 但取不到时**不能退回"passed 就算能领"**——那正是要修掉的那条老路。
      api.get<{ papers: PaperBrief[] }>('/api/exam/papers')
        .then((r) => r.papers)
        .catch(() => null),
    ]);
    return {
      certs: certs.certificates, courses: courses.courses,
      attempts: attempts.attempts, papers,
    };
  }, []);

  return (
    <Screen>
      <PageHead title="我的结业证明" desc="通过课程的结业测评后可以领取一份，编号可公开验真。" />
      {state.loading ? <Stack><Skeleton h={150} /><Skeleton h={150} /></Stack> : (
        <Async state={state}>
          {(d) => <Body data={d} reload={state.reload} />}
        </Async>
      )}
    </Screen>
  );
}

/**
 * 可以领的是"课程"，不是"考试场次"：一门课一份结业证明。
 *
 * 三道筛子缺一不可：这场考试通过了、**它那份卷发证**、这门课还没领过。
 * 认不出那份卷（列表取不到、或它已经下架）时一律不算可领 —— 少一个入口，
 * 总好过一个点下去被后端拒的按钮。取不到那一档在界面上另有一句话说明。
 */
function claimableOf(data: Data): { courseId: number; title: string }[] {
  const have = new Set(data.certs.map((c) => c.courseId));
  const byPaper = new Map((data.papers ?? []).map((p) => [p.paperId, p]));
  const out = new Map<number, string>();
  for (const a of data.attempts) {
    if (a.passed !== true || a.paperId == null) continue;
    const p = byPaper.get(a.paperId);
    // grantsCertificate 是 TINYINT(1)：可能是 true 也可能是 1，所以只看真假。
    // courseId 为空 = 开放卷，它连课程都没有，谈不上发结业证明。
    if (!p || !p.grantsCertificate || !p.courseId) continue;
    if (have.has(p.courseId) || out.has(p.courseId)) continue;
    out.set(p.courseId, courseTitleOf(data, p.courseId, p.courseTitle));
  }
  return [...out].map(([courseId, title]) => ({ courseId, title }));
}

/** 课名优先取"我的课程"里的那一份（它是学员在别处看到的同一个名字）。 */
function courseTitleOf(data: Data, id: number, fallback?: string | null): string {
  return data.courses.find((c) => c.courseId === id)?.title ?? fallback ?? `课程 #${id}`;
}

function Body({ data, reload }: { data: Data; reload: () => void }) {
  const toast = useToast();
  const [issuing, setIssuing] = React.useState<number | null>(null);
  const titleOf = (id: number) => courseTitleOf(data, id);

  const claimable = claimableOf(data);
  // 卷列表没取到、而他手上确实有通过的场次：这时"没有可以领的"是一句我们
  // 并不知道真假的话，得说出来，否则真该领证的人会以为平台没给。
  //
  // ★ 第二种同样"不知道"的情形：卷列表取到了，但他通过的那份卷不在里面。
  //   /api/exam/papers 只列 status='ON' 的卷，运营把一份发证的卷下架之后，
  //   考过它的人在这里会被告知"没有可以领取的" —— 而后端 CertificateService
  //   只看 passed 与 grants_certificate、压根不看上下架，其实是发得出来的。
  //   同一个人在成绩页看到的还是"去领结业证明"（那一屏用的是考试自带的字段），
  //   两页当场对不上。宁可说一句"查不到"，也不要替后端说一句它不认的话。
  const unknownPaper = data.papers !== null && data.attempts.some(
    (a) => a.passed === true && a.paperId != null
      && !data.papers!.some((p) => p.paperId === a.paperId));
  const unsure = (data.papers === null && data.attempts.some((a) => a.passed === true))
      || unknownPaper;

  const issue = async (courseId: number) => {
    setIssuing(courseId);
    try {
      await api.post('/api/cert/issue', { courseId });
      toast({ tone: 'ok', text: '结业证明已生成' });
      reload();
    } catch (e) {
      toast({ tone: 'danger', text: (e as Error).message || '领取失败，请重试' });
    } finally {
      setIssuing(null);
    }
  };

  if (data.certs.length === 0 && claimable.length === 0) {
    return (
      <Card>
        {unsure ? (
          <Note tone="warn">
            现在查不到你考过的那几份卷是不是发结业证明的，所以这里暂时列不出可领的课程。
            稍后刷新一下再看；已经领到的证明不受影响。
          </Note>
        ) : (
          <Empty
            icon={<IconCert size={22} />}
            title="还没有可以领取的结业证明"
            desc="通过课程的结业测评之后，就可以在这里领取该课程的结业证明；章节测评与开放测评不发证。"
            action={<Link to="/exam"><Button>去看看测评</Button></Link>}
          />
        )}
      </Card>
    );
  }

  return (
    <Stack gap="var(--s5)">
      {unsure && (
        <Card>
          <Note tone="warn">
            现在查不到你考过的那几份卷是不是发结业证明的，可领的课程可能还没列全。稍后刷新一下再看。
          </Note>
        </Card>
      )}
      {claimable.length > 0 && (
        <Card>
          <div style={{ fontWeight: 600, marginBottom: 'var(--s3)' }}>可以领取</div>
          <Stack gap="var(--s3)">
            {claimable.map((c) => (
              <Row key={c.courseId} style={{ justifyContent: 'space-between' }}>
                <div>
                  <div>{c.title}</div>
                  <div style={{ fontSize: 'var(--t-sm)', color: 'var(--ink-3)' }}>结业测评已通过</div>
                </div>
                <Button loading={issuing === c.courseId} onClick={() => issue(c.courseId)}>
                  领取结业证明
                </Button>
              </Row>
            ))}
          </Stack>
        </Card>
      )}

      {data.certs.map((c) => (
        <CertCard key={c.certNo} cert={c} title={c.courseTitle || titleOf(c.courseId)} />
      ))}
    </Stack>
  );
}

/* ------------------------------------------------------------------ 一张证明 */

function CertCard({ cert, title }: { cert: MyCert; title: string }) {
  const revoked = !!cert.revokedAt || cert.status === 'REVOKED';
  // 验真页属于学员应用。部署在 /study/ 子路径时不能硬写站点根路径，
  // 否则复制或二维码里会是 /verify/...，扫码后直接跳出学员应用。
  const url = `${window.location.origin}${import.meta.env.BASE_URL}verify/${encodeURIComponent(cert.certNo)}`;
  const [qr, setQr] = React.useState<string>('');

  React.useEffect(() => {
    // 二维码在本地生成，不走任何第三方图床：那类地址会在内网、
    // 弱网、以及图床自己挂掉的时候变成一个破图，而这张纸是要拿出去给人看的。
    QRCode.toDataURL(url, { margin: 1, width: 280, errorCorrectionLevel: 'M' })
      .then(setQr)
      .catch(() => setQr(''));
  }, [url]);

  return (
    <Card>
      <style>{`
        @media print {
          /* 打印时只留证明本身。不做这一步的话，打出来的纸上会有
             底部导航条和"退出"按钮 —— 那是明显不能拿给人看的东西。 */
          body * { visibility: hidden; }
          .wk-cert-print, .wk-cert-print * { visibility: visible; }
          .wk-cert-print { position: absolute; inset: 0; padding: 24mm; }
          .wk-cert-noprint { display: none !important; }
        }
      `}</style>

      <div className="wk-cert-print">
        <Row style={{ justifyContent: 'space-between' }} align="flex-start">
          <div style={{ minWidth: 0 }}>
            <div style={{ fontSize: 'var(--t-xs)', color: 'var(--ink-3)', letterSpacing: '.14em' }}>
              课程结业证明
            </div>
            <h2 style={{ fontSize: 'var(--t-h2)', marginTop: 'var(--s2)' }}>{title}</h2>
            <div style={{ marginTop: 'var(--s3)', fontSize: 'var(--t-sm)', color: 'var(--ink-2)' }}>
              编号 <span className="num">{cert.certNo}</span>
            </div>
            <div style={{ fontSize: 'var(--t-sm)', color: 'var(--ink-2)' }}>
              签发日期 <span className="num">{dateOnly(cert.issuedAt)}</span>
            </div>
            <div style={{ marginTop: 'var(--s3)' }}>
              {revoked ? <Tag tone="danger">已吊销</Tag> : <Tag tone="ok">有效</Tag>}
            </div>
          </div>
          {qr && (
            <img
              src={qr} alt="验真二维码" width={96} height={96}
              style={{ borderRadius: 'var(--r-sm)', border: '1px solid var(--line)', flexShrink: 0 }}
            />
          )}
        </Row>

        <div style={{
          marginTop: 'var(--s5)', paddingTop: 'var(--s4)', borderTop: '1px solid var(--line)',
          fontSize: 'var(--t-xs)', color: 'var(--ink-3)', lineHeight: 'var(--lh-body)',
        }}>
          本证明表示持有人已完成上述课程的学习并通过课程测评，不构成任何职业资格或行业认证。
          扫码或访问下方链接可核验真伪。
          <div className="num" style={{ marginTop: 'var(--s1)', wordBreak: 'break-all' }}>{url}</div>
        </div>
      </div>

      {revoked && (
        <div className="wk-cert-noprint" style={{ marginTop: 'var(--s4)' }}>
          <Note tone="danger">
            这份结业证明已被平台吊销，验真页会如实显示"已吊销"。如有疑问请联系平台客服。
          </Note>
        </div>
      )}

      <Row gap="var(--s3)" className="wk-cert-noprint" style={{ marginTop: 'var(--s5)' }}>
        <Button variant="ghost" onClick={() => window.print()}>打印 / 存成 PDF</Button>
        <Copyable text={url} label="复制验真链接" />
      </Row>
    </Card>
  );
}
