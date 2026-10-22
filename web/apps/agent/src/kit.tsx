/**
 * 代理后台自己的小组件。
 *
 * 只放 shared/ui.tsx 里没有、而这个后台反复要用的东西。
 * 不往 shared 里加：那是三端共用的，为一端的需要改它，另外两端会跟着变形。
 *
 * 硬规矩：这个文件里不许出现任何写死的颜色、字号、间距，一律 var(--...)。
 */
import React from 'react';
import { Button, Card, Modal, Skeleton, Spinner, useToast, yuan } from '@wangke/shared';

/** useAsync 的返回形状。要把它当参数传给子组件时用这个，免得到处写 ReturnType<...>。 */
export type AsyncState<T> = {
  loading: boolean; error?: string; data?: T; reload: () => void;
};

/**
 * 翻页 / 换筛选时保留上一屏的内容，只有<b>第一次</b>加载才画骨架。
 *
 * 不这么做的话，每点一次"下一页"整块表格会先塌成骨架再长回来 ——
 * 数据其实几十毫秒就回来了，闪那一下比慢更让人烦，而且会让人以为点错了。
 * 加载中的反馈交给 Pager 上那个小转圈。
 */
export function keepPrevious<T>(s: AsyncState<T>): AsyncState<T> {
  return s.loading && s.data !== undefined ? { ...s, loading: false } : s;
}

/* ------------------------------------------------------------ 分段筛选器 */

export type SegOption<T extends string> = { value: T; label: string; count?: number };

/**
 * 一排互斥的筛选。
 *
 * 用它而不是下拉框：筛选项只有三四个时，下拉把"现在筛的是什么"藏了起来，
 * 而运营最常问的问题恰恰是"我看到的这一屏是全部还是筛过的"。
 */
export function Segmented<T extends string>({ value, onChange, options }: {
  value: T; onChange: (v: T) => void; options: SegOption<T>[];
}) {
  // 形态是「压在一条线上的页签」而不是灰槽里浮起的白药丸：后者靠投影表达选中，
  // 而投影在暖纸底上几乎看不见 —— 选中态最不该是需要眯着眼找的那一个。
  return (
    <div role="tablist" style={{
      display: 'inline-flex', flexWrap: 'wrap',
      borderBottom: '1px solid var(--line)',
    }}>
      {options.map(o => {
        const on = o.value === value;
        return (
          <button key={o.value} role="tab" aria-selected={on} onClick={() => onChange(o.value)}
            className="wk-btn wk-btn-quiet"
            style={{
              padding: 'var(--s2) var(--s4)', borderRadius: 0, fontSize: 'var(--t-sm)',
              color: on ? 'var(--brand)' : 'var(--ink-2)',
              fontWeight: on ? 600 : 400, minHeight: 36,
              borderBottom: `2px solid ${on ? 'var(--brand)' : 'transparent'}`,
              marginBottom: -1, boxShadow: 'none',
            }}>
            {o.label}
            {o.count !== undefined && (
              <span className="num" style={{
                marginLeft: 'var(--s2)', fontSize: 'var(--t-xs)',
                color: on ? 'var(--brand)' : 'var(--ink-3)',
              }}>{o.count}</span>
            )}
          </button>
        );
      })}
    </div>
  );
}

/* ---------------------------------------------------------------- 金额 */

/**
 * 金额。等宽、可右对齐、语气可选。
 *
 * 钱和别的数字必须长得不一样 —— 一屏上"12 个码"和"12 元"用同一种字排出来，
 * 扫一眼是分不开的。
 */
export function Money({ fen, size = 'body', tone, block, prefix }: {
  fen: number | null | undefined;
  size?: 'sm' | 'body' | 'lg' | 'xl';
  tone?: 'ink' | 'muted' | 'ok' | 'warn' | 'danger' | 'brand';
  block?: boolean;
  prefix?: string;
}) {
  const fs = { sm: 'var(--t-sm)', body: 'var(--t-body)', lg: 'var(--t-h2)', xl: 'var(--t-h1)' }[size];
  const color = {
    ink: 'var(--ink)', muted: 'var(--ink-3)', ok: 'var(--ok)',
    warn: 'var(--warn)', danger: 'var(--danger)', brand: 'var(--brand)',
  }[tone ?? 'ink'];
  // 分→元只走 shared 的 yuan()。自己再写一遍除以 100，迟早会有一处的取整方式
  // 和别处不一样，而差一分的账在财务那边是要查一整天的。
  const text = yuan(fen);
  return (
    <span className="num" style={{
      fontSize: fs, color, fontWeight: size === 'body' || size === 'sm' ? 500 : 600,
      display: block ? 'block' : 'inline', whiteSpace: 'nowrap', lineHeight: 1.25,
    }}>{prefix}{text}</span>
  );
}

/* ------------------------------------------------------------ 版块与说明 */

export function Section({ title, desc, extra, children, style }: {
  title?: React.ReactNode; desc?: React.ReactNode; extra?: React.ReactNode;
  children: React.ReactNode; style?: React.CSSProperties;
}) {
  return (
    <Card pad={false} style={style}>
      {(title || extra) && (
        <div style={{
          display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between',
          gap: 'var(--s3)', padding: 'var(--s4) var(--pad-card)', flexWrap: 'wrap',
          borderBottom: '1px solid var(--line)',
        }}>
          <div style={{ minWidth: 0 }}>
            <div style={{ fontSize: 'var(--t-h3)', fontWeight: 600 }}>{title}</div>
            {desc && <div style={{ fontSize: 'var(--t-sm)', color: 'var(--ink-3)',
                                   marginTop: 'var(--s1)' }}>{desc}</div>}
          </div>
          {extra}
        </div>
      )}
      <div style={{ padding: 'var(--pad-card)' }}>{children}</div>
    </Card>
  );
}

/** 行内提示条。用来解释规则，不是用来喊口号，所以默认低调。 */
export function Note({ tone = 'info', title, children, style }: {
  tone?: 'info' | 'warn' | 'danger' | 'ok';
  title?: React.ReactNode; children?: React.ReactNode; style?: React.CSSProperties;
}) {
  // info 不给红：红是这一屏的锚点，而"顺带说一句"的提示条在页面上出现得最多。
  // 全染成红的结果是每页三四处红，真正要人动手的那一处反而不显眼了。
  const [bg, fg, line] = {
    info: ['var(--surface-2)', 'var(--ink)', 'var(--line-strong)'],
    warn: ['var(--warn-soft)', 'var(--warn)', 'var(--warn)'],
    danger: ['var(--danger-soft)', 'var(--danger)', 'var(--danger)'],
    ok: ['var(--surface-2)', 'var(--ink)', 'var(--ink)'],
  }[tone];
  return (
    <div style={{
      background: bg, borderLeft: `2px solid ${line}`,
      padding: 'var(--s3) var(--s4)', fontSize: 'var(--t-sm)', color: 'var(--ink-2)',
      lineHeight: 'var(--lh-body)', ...style,
    }}>
      {title && <div style={{ color: fg, fontWeight: 600, marginBottom: 'var(--s1)' }}>{title}</div>}
      {children}
    </div>
  );
}

/** 一对「说明 / 值」。资料页与详情弹窗共用，免得两处对不齐。 */
export function KV({ label, children, hint }: {
  label: React.ReactNode; children: React.ReactNode; hint?: React.ReactNode;
}) {
  return (
    <div style={{ display: 'flex', gap: 'var(--s4)', padding: 'var(--s3) 0',
                  borderBottom: '1px solid var(--line)', alignItems: 'flex-start' }}>
      <div style={{ width: 104, flexShrink: 0, color: 'var(--ink-3)', fontSize: 'var(--t-sm)' }}>{label}</div>
      <div style={{ flex: 1, minWidth: 0, wordBreak: 'break-word' }}>
        {children}
        {hint && <div style={{ fontSize: 'var(--t-xs)', color: 'var(--ink-3)',
                               marginTop: 'var(--s1)' }}>{hint}</div>}
      </div>
    </div>
  );
}

/* ---------------------------------------------------------------- 确认 */

/**
 * 二次确认。
 *
 * <b>必须写清后果</b>，不是"确定吗？"。用户点确认之前唯一该知道的事情是
 * "点下去之后会发生什么、能不能反悔"，而"确定吗"一个字都没回答这个问题。
 */
export function Confirm({ open, title, consequence, confirmText, danger, loading, onClose, onConfirm, children }: {
  open: boolean;
  title: React.ReactNode;
  /** 一句话说清后果，会以警示条呈现 */
  consequence: React.ReactNode;
  confirmText: string;
  danger?: boolean;
  loading?: boolean;
  onClose: () => void;
  onConfirm: () => void;
  children?: React.ReactNode;
}) {
  // 搭在 shared 的 Modal 上，不自己再画一层遮罩：
  // 遮罩那个半透明黑没有令牌，自己写就等于把一个颜色字面量抄进第二个文件，
  // 以后主题一改，只有一处会跟着变。
  return (
    <Modal
      open={open}
      title={title}
      width={460}
      onClose={() => { if (!loading) onClose(); }}
      footer={<>
        <Button variant="ghost" onClick={onClose} disabled={loading}>再想想</Button>
        <Button variant={danger ? 'danger' : 'accent'} loading={loading} onClick={onConfirm}>
          {confirmText}
        </Button>
      </>}
    >
      <div style={{ display: 'grid', gap: 'var(--s3)' }}>
        <Note tone={danger ? 'danger' : 'warn'}>{consequence}</Note>
        {children}
      </div>
    </Modal>
  );
}

/* ---------------------------------------------------------------- 翻页 */

export function Pager({ page, size, total, onPage, busy }: {
  page: number; size: number; total: number; onPage: (p: number) => void; busy?: boolean;
}) {
  const pages = Math.max(1, Math.ceil(total / size));
  if (total <= size) return null;
  const from = (page - 1) * size + 1;
  const to = Math.min(total, page * size);
  return (
    <div style={{
      display: 'flex', alignItems: 'center', justifyContent: 'space-between',
      gap: 'var(--s3)', marginTop: 'var(--s4)', flexWrap: 'wrap',
    }}>
      <div style={{ fontSize: 'var(--t-sm)', color: 'var(--ink-3)' }}>
        第 <span className="num">{from}–{to}</span> 条，共 <span className="num">{total}</span> 条
      </div>
      <div style={{ display: 'flex', gap: 'var(--s2)', alignItems: 'center' }}>
        <Button size="sm" variant="ghost" disabled={page <= 1 || busy}
                onClick={() => onPage(page - 1)}>上一页</Button>
        <span className="num" style={{ fontSize: 'var(--t-sm)', color: 'var(--ink-2)',
                                       display: 'inline-flex', alignItems: 'center', gap: 'var(--s2)' }}>
          {busy && <Spinner size={12} color="var(--brand)" />}
          {page} / {pages}
        </span>
        <Button size="sm" variant="ghost" disabled={page >= pages || busy}
                onClick={() => onPage(page + 1)}>下一页</Button>
      </div>
    </div>
  );
}

/* ---------------------------------------------------------------- 骨架 */

export function TableSkeleton({ rows = 6, cols = 5 }: { rows?: number; cols?: number }) {
  return (
    <div style={{ display: 'grid', gap: 'var(--s3)' }}>
      {Array.from({ length: rows }).map((_, r) => (
        <div key={r} style={{ display: 'grid', gap: 'var(--s3)',
                              gridTemplateColumns: `repeat(${cols}, 1fr)` }}>
          {Array.from({ length: cols }).map((__, c) => (
            <Skeleton key={c} h={14} w={c === 0 ? '80%' : '60%'} />
          ))}
        </div>
      ))}
    </div>
  );
}

/** 整页加载。用在需要先知道"你是不是代理"才能决定画什么的地方。 */
export function PageLoading({ label = '正在加载' }: { label?: string }) {
  return (
    <div style={{ display: 'grid', placeItems: 'center', padding: 'var(--s8)',
                  gap: 'var(--s3)', color: 'var(--ink-3)' }}>
      <Spinner size={20} color="var(--brand)" />
      <div style={{ fontSize: 'var(--t-sm)' }}>{label}</div>
    </div>
  );
}

/* ---------------------------------------------------------------- 杂项 */

/** 窄屏判定。表格在手机上横滚勉强能看，但高频页面直接换成卡片更好用。 */
export function useNarrow(px = 720): boolean {
  const [narrow, setNarrow] = React.useState(
    () => typeof window !== 'undefined' && window.matchMedia(`(max-width:${px}px)`).matches);
  React.useEffect(() => {
    const mq = window.matchMedia(`(max-width:${px}px)`);
    const h = (e: MediaQueryListEvent) => setNarrow(e.matches);
    mq.addEventListener('change', h);
    setNarrow(mq.matches);
    return () => mq.removeEventListener('change', h);
  }, [px]);
  return narrow;
}

/**
 * 手机上一行一个、宽屏并排的表单行。
 *
 * 轨道下限写成 min(240px, 100%) 而不是 240px：
 * 直接写 240px 的话，容器比它还窄时（iPhone SE 竖屏 320px，减去页面与卡片两层内边距
 * 只剩 240 出头）轨道不会缩，整个网格顶出容器 —— 表现是<b>整页可以左右拖动</b>。
 * 这个溢出在宽屏上完全看不见，只有真机上才会遇到。
 */
export function FormRow({ children }: { children: React.ReactNode }) {
  return (
    <div style={{
      display: 'grid', gap: 'var(--s4)',
      gridTemplateColumns: 'repeat(auto-fit, minmax(min(240px, 100%), 1fr))',
    }}>{children}</div>
  );
}

/** 页面级工具条：筛选在左、动作在右，窄屏自动换行。 */
export function Toolbar({ left, right }: { left?: React.ReactNode; right?: React.ReactNode }) {
  return (
    <div style={{
      display: 'flex', gap: 'var(--s3)', alignItems: 'center', flexWrap: 'wrap',
      justifyContent: 'space-between', marginBottom: 'var(--s4)',
    }}>
      <div style={{ display: 'flex', gap: 'var(--s3)', alignItems: 'center', flexWrap: 'wrap' }}>{left}</div>
      <div style={{ display: 'flex', gap: 'var(--s2)', alignItems: 'center', flexWrap: 'wrap' }}>{right}</div>
    </div>
  );
}

/**
 * 复制按钮。
 *
 * 和 shared 的 Copyable 的区别：这里要能带自定义文案与按钮样式
 *（"复制邀请文案"和"复制链接"在同一屏上出现，长得一样反而分不清）。
 */
export function CopyButton({ text, label, variant = 'ghost', size = 'sm', block, done = '已复制' }: {
  text: string; label: React.ReactNode;
  variant?: 'primary' | 'accent' | 'ghost' | 'danger' | 'quiet';
  size?: 'sm' | 'md' | 'lg'; block?: boolean; done?: string;
}) {
  const toast = useToast();
  return (
    <Button variant={variant} size={size} block={block} onClick={async () => {
      try {
        await navigator.clipboard.writeText(text);
        toast({ tone: 'ok', text: done });
      } catch {
        // http 页面下 clipboard 不可用。装作成功最坏：用户以为复制到了，
        // 粘贴出来是空的，只会怪平台。
        toast({ tone: 'danger', text: '这个浏览器不让自动复制，请手动选中上面的内容复制' });
      }
    }}>{label}</Button>
  );
}
/**
 * 导出 CSV 的三件套原来在这里各有一份，与后台 lib/fmt.ts 逐字节相同。
 * 已提到 shared/src/csv.ts —— 其中「公式注入转义」是一条安全判据，
 * 同一条判据存在两处的失败方式是改了一处忘了另一处，而两端都照常导出、照常打开，
 * 只有拿到文件的人会中招。这里保留再导出，调用点不用改 import。
 */
export { csvCell, downloadCsv, stamp } from '@wangke/shared';
