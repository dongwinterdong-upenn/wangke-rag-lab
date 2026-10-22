/**
 * 学员端自己的小组件。
 *
 * 放这里而不是 shared 的判据：**只有学员端会用到，或者它体现的是学员端
 * 特有的口味**（手机优先、字更大、按钮更大）。shared 是三端共用的，
 * 往里塞一个只有一端用的组件，另外两端就要跟着承担它的演化。
 */
import React from 'react';
import { api, Button, Card, Input, Modal, Skeleton, Tag } from '@wangke/shared';

/* ================================================================ 版面 */

/** 一屏的内容容器。学员端最宽 720：正文行长超过这个数，手机横屏读起来会跳行。 */
export function Screen({ maxWidth = 720, children }: {
  maxWidth?: number; children: React.ReactNode;
}) {
  return <div style={{ maxWidth, margin: '0 auto', width: '100%' }}>{children}</div>;
}

export function Stack({ gap = 'var(--s4)', children, style, className }: {
  gap?: string; children: React.ReactNode; style?: React.CSSProperties; className?: string;
}) {
  return <div className={className} style={{ display: 'grid', gap, ...style }}>{children}</div>;
}

export function Row({ gap = 'var(--s3)', wrap = true, align = 'center', children, style, className }: {
  gap?: string; wrap?: boolean; align?: React.CSSProperties['alignItems'];
  children: React.ReactNode; style?: React.CSSProperties; className?: string;
}) {
  return (
    <div className={className} style={{
      display: 'flex', gap, alignItems: align,
      flexWrap: wrap ? 'wrap' : 'nowrap', ...style,
    }}>{children}</div>
  );
}

/**
 * 横向滚动容器。
 *
 * 宽内容（长表格、题号跳转格）必须在**自己**这里滚，页面 body 绝不能横向滚：
 * body 一旦能左右拖，手机上每一次纵向滑动都会带出一点横向位移，
 * 整个页面读起来像在晃。
 */
export function ScrollX({ children, style }: { children: React.ReactNode; style?: React.CSSProperties }) {
  return (
    <div style={{
      overflowX: 'auto', WebkitOverflowScrolling: 'touch',
      // 滚动条在 macOS 上默认不可见，末尾留白是"这里还能滑"的唯一提示
      paddingBottom: 'var(--s2)', ...style,
    }}>{children}</div>
  );
}

/**
 * 说明性小字。用于把"这句话的后果"讲清楚，而不是塞进 toast 一晃而过。
 *
 * DS 的 callout 形态：淡底 + 左侧一道语义色实线 + 一枚单等宽眉标。
 * 眉标那三个字是有用的 —— 它把「这是提示」和「这是正文」在扫读时分开，
 * 不然一段灰底小字很容易被当成可以跳过的补充说明，而这里写的往往是后果。
 */
export function Note({ tone = 'quiet', title, children }: {
  tone?: 'quiet' | 'warn' | 'danger' | 'ok'; title?: React.ReactNode;
  children: React.ReactNode;
}) {
  const [bg, fg, line, tag] = {
    quiet:  ['var(--surface-2)', 'var(--ink-2)', 'var(--line-strong)', '说明'],
    warn:   ['var(--warn-soft)', 'var(--ink)', 'var(--warn)', '注意'],
    danger: ['var(--danger-soft)', 'var(--ink)', 'var(--danger)', '后果'],
    ok:     ['var(--surface-2)', 'var(--ink)', 'var(--ink)', '已完成'],
  }[tone];
  return (
    <div style={{
      background: bg, color: fg, borderLeft: `2px solid ${line}`,
      padding: 'var(--s3) var(--s4)',
      fontSize: 'var(--t-sm)', lineHeight: 'var(--lh-body)',
    }}>
      <span className="da-overline bare" style={{
        color: line === 'var(--line-strong)' ? 'var(--ink-3)' : line, marginBottom: 3, display: 'block',
      }}>{title ?? tag}</span>
      {children}
    </div>
  );
}

/* ================================================================ 骨架 */

/** 列表骨架。行数照着真实首屏给，给 3 行的话真数据一来页面会明显跳一下。 */
export function ListSkeleton({ rows = 3, height = 92 }: { rows?: number; height?: number }) {
  return (
    <Stack>
      {Array.from({ length: rows }, (_, i) => (
        <Card key={i}>
          <Row align="flex-start" wrap={false}>
            <Skeleton h={height * 0.66} w={112} />
            <div style={{ flex: 1, display: 'grid', gap: 'var(--s3)' }}>
              <Skeleton h={18} w="70%" />
              <Skeleton h={12} w="45%" />
              <Skeleton h={8} />
            </div>
          </Row>
        </Card>
      ))}
    </Stack>
  );
}

/* ================================================================ 二次确认 */

/**
 * 危险操作的确认框。
 *
 * `consequence` 是**必填**，而且它显示在按钮正上方：一个只写着
 * "确定要继续吗？"的确认框不提供任何信息，用户学会的是闭着眼点确定，
 * 于是下一次真正危险的那个确认框也会被闭着眼点掉。
 */
export function Confirm({ open, title, consequence, confirmText, tone = 'danger', busy,
                          onConfirm, onClose, children }: {
  open: boolean;
  title: string;
  consequence: React.ReactNode;
  confirmText: string;
  tone?: 'danger' | 'primary' | 'accent';
  busy?: boolean;
  onConfirm: () => void;
  onClose: () => void;
  children?: React.ReactNode;
}) {
  return (
    <Modal open={open} title={title} onClose={onClose} footer={
      <>
        <Button variant="ghost" onClick={onClose} disabled={busy}>再想想</Button>
        <Button variant={tone} onClick={onConfirm} loading={busy}>{confirmText}</Button>
      </>
    }>
      <Stack>
        {children}
        <Note tone={tone === 'danger' ? 'danger' : 'warn'}>{consequence}</Note>
      </Stack>
    </Modal>
  );
}

/* ================================================================ 手机号与验证码 */

export const isPhone = (p: string) => /^1[3-9]\d{9}$/.test(p.trim());

export function PhoneInput({ value, onChange, autoFocus, disabled }: {
  value: string; onChange: (v: string) => void; autoFocus?: boolean; disabled?: boolean;
}) {
  return (
    <Input
      value={value}
      onChange={(e) => onChange(e.target.value.replace(/\D/g, '').slice(0, 11))}
      // inputMode 决定手机上弹哪种键盘。给手机号弹全键盘，用户要多点两下切数字，
      // 而 type="number" 会带上步进箭头并允许 e/+/-，两个都不对。
      inputMode="numeric"
      autoComplete="tel"
      placeholder="11 位手机号"
      autoFocus={autoFocus}
      disabled={disabled}
      style={{ fontSize: 'var(--t-h3)', letterSpacing: '.04em' }}
    />
  );
}

/**
 * 验证码发送按钮。
 *
 * 倒计时的秒数**来自后端的 cooldownSeconds**，不是前端写死的 60：
 * 两边各写一个数，后端调成 120 之后前端 60 秒就把按钮点亮了，
 * 用户点一次收一句"发送过于频繁"，而他什么都没做错。
 */
export function SmsButton({ phone, scene, disabled, onSent }: {
  phone: string;
  scene: 'REGISTER' | 'LOGIN' | 'RESET' | 'BIND';
  disabled?: boolean;
  onSent?: (cooldown: number) => void;
}) {
  const [left, setLeft] = React.useState(0);
  const [busy, setBusy] = React.useState(false);
  const [err, setErr] = React.useState('');

  React.useEffect(() => {
    if (left <= 0) return;
    const t = setTimeout(() => setLeft((v) => v - 1), 1000);
    return () => clearTimeout(t);
  }, [left]);

  const send = async () => {
    setErr('');
    setBusy(true);
    try {
      const r = await api.post<{ ok: boolean; cooldownSeconds: number }>('/api/auth/sms', { phone, scene });
      const cd = Math.max(1, r.cooldownSeconds || 60);
      setLeft(cd);
      onSent?.(cd);
    } catch (e) {
      setErr((e as Error).message || '验证码发送失败');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div>
      <Button
        variant="ghost"
        onClick={send}
        loading={busy}
        disabled={disabled || left > 0 || !isPhone(phone)}
        style={{ minWidth: 116, height: 44 }}
      >
        {left > 0 ? `${left} 秒后重发` : '获取验证码'}
      </Button>
      {err && <div style={{ color: 'var(--danger)', fontSize: 'var(--t-xs)', marginTop: 'var(--s1)' }}>{err}</div>}
    </div>
  );
}

/* ================================================================ 分段切换 */

/**
 * 分段切换。
 *
 * 形态是「压在一条线上的两三个页签」，不是灰槽里浮起的白药丸。
 * 白药丸 + 投影那一套是消费级 App 的做法，和 DS 的直角/发丝线正好相反；
 * 而且它靠**投影**表达"选中"，投影在暖纸底上几乎看不见 ——
 * 选中态最不该是需要眯着眼找的那一个。这里选中态是红字 + 一道红底线。
 */
export function Segmented<T extends string>({ value, options, onChange }: {
  value: T; options: { value: T; label: string }[]; onChange: (v: T) => void;
}) {
  return (
    <div role="tablist" style={{
      display: 'grid', gridTemplateColumns: `repeat(${options.length}, 1fr)`,
      borderBottom: '1px solid var(--line)',
    }}>
      {options.map((o) => {
        const on = o.value === value;
        return (
          <button
            key={o.value} role="tab" aria-selected={on} type="button"
            onClick={() => onChange(o.value)}
            className="wk-btn wk-btn-quiet"
            style={{
              padding: '10px 8px', borderRadius: 0, fontSize: 'var(--t-body)',
              fontWeight: on ? 600 : 400,
              color: on ? 'var(--brand)' : 'var(--ink-2)',
              borderBottom: `2px solid ${on ? 'var(--brand)' : 'transparent'}`,
              marginBottom: -1, minHeight: 42, boxShadow: 'none',
            }}
          >{o.label}</button>
        );
      })}
    </div>
  );
}

/* ================================================================ 进度与有效期 */

/**
 * 进度百分比的一致口径。
 *
 * **不向上取整。**看了 99.4% 显示 100% 的话，学员会以为学完了，
 * 而后台的完成判定（watched ≥ 90% 且位置 ≥ 95%）可能还没过 ——
 * 他会拿着"我明明学完了"来投诉，而两边说的都不是假话。
 */
export function pctText(pct: number | undefined | null): string {
  const v = Math.max(0, Math.min(100, Math.floor(pct ?? 0)));
  return `${v}%`;
}

/** 有效期的语气：30 天内转橙、已过期转红。颜色是提前量，不是装饰。 */
export function ValidityTag({ validUntil, expired }: { validUntil?: string | null; expired?: boolean }) {
  if (!validUntil) return null;
  const days = Math.ceil((new Date(validUntil.replace(' ', 'T')).getTime() - Date.now()) / 86400000);
  if (expired || days < 0) return <Tag tone="danger">已到期</Tag>;
  if (days <= 30) return <Tag tone="warn">{days === 0 ? '今天到期' : `还剩 ${days} 天`}</Tag>;
  return <Tag tone="neutral">{validUntil.slice(0, 10)} 到期</Tag>;
}

/** 到期是不是快了。快到期的卡片整张要变，不只是一个标签变色。 */
export function daysLeft(validUntil?: string | null): number | null {
  if (!validUntil) return null;
  return Math.ceil((new Date(validUntil.replace(' ', 'T')).getTime() - Date.now()) / 86400000);
}

/* ================================================================ 封面 */

/**
 * 课程封面。
 *
 * 图挂了不能留一个破图标：那会让人怀疑整个站点是不是坏了。
 * 退回一块带课程首字的色块 —— 它是有意的，看起来就像设计的一部分。
 */
export function Cover({ url, title, width = 112, ratio = 16 / 10 }: {
  url?: string | null; title: string; width?: number; ratio?: number;
}) {
  const [bad, setBad] = React.useState(false);
  const h = Math.round(width / ratio);
  if (!url || bad) {
    // 占位块不用红：一屏上有三门课就是三块红，而 DS 的规矩是每屏只留一个红锚点。
    // 红全部让给「现在该点的那一处」。这里只留一枚小三角当品牌记号。
    return (
      <div aria-hidden className="da-serif" style={{
        width, height: h, flexShrink: 0, position: 'relative',
        background: 'var(--surface-2)', color: 'var(--ink-3)',
        display: 'grid', placeItems: 'center',
        fontSize: Math.round(h * 0.46), lineHeight: 1,
        border: '1px solid var(--line)', borderRadius: 'var(--r-md)',
        overflow: 'hidden',
      }}>
        <span className="da-facet" style={{ borderWidth: '0 11px 11px 0' }} />
        {title.slice(0, 1)}
      </div>
    );
  }
  return (
    <img
      src={url} alt="" loading="lazy" onError={() => setBad(true)}
      style={{
        width, height: h, flexShrink: 0, objectFit: 'cover',
        borderRadius: 'var(--r-md)', background: 'var(--surface-2)',
        border: '1px solid var(--line)',
      }}
    />
  );
}

/* ================================================================ 倒计时 */

/** 剩余秒数 → mm:ss / h:mm:ss。考试倒计时和有效期都用它，两处格式必须一致。 */
export function countdownText(sec: number): string {
  const s = Math.max(0, Math.floor(sec));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const r = s % 60;
  const pad = (n: number) => String(n).padStart(2, '0');
  return h > 0 ? `${h}:${pad(m)}:${pad(r)}` : `${pad(m)}:${pad(r)}`;
}
