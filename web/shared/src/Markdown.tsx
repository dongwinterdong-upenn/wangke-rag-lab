/**
 * 答疑消息的 Markdown 渲染。
 *
 * ## 为什么手写而不是装一个库
 *
 * 两条：
 *
 * 1. **体积。** 学员端首屏那一包的预算是被认真算过的（见 `apps/student/src/shared.ts`：
 *    830KB → 315KB 那一段）。`marked + dompurify` 压缩后约 40KB，而这里真正要渲染的
 *    只有懂妈会写的那几种记号。手写这一份 gzip 后约 1.5KB。
 * 2. **注入面。** 库的路子是「Markdown → HTML 字符串 → dangerouslySetInnerHTML」，
 *    安全与否全押在消毒那一步上。这里<b>从头到尾不产生 HTML 字符串</b>，
 *    直接产出 React 节点，正文里的 `<img onerror=...>` 只会是一串字符 ——
 *    不是"被过滤掉了"，是<b>压根没有一条能变成元素的路</b>。
 *    这一点很要紧：qa_message 里既有模型写的字，也有学员自己打的字。
 *
 * ## 只认这几种，其余原样显示
 *
 * 标题 `#`~`######` / 粗体 `**` / 行内码 `` ` `` / 代码块 ``` / 无序列表 `-·*·+` /
 * 有序列表 `1.` / 引用 `>` / 分隔线 `---` / 链接 `[字](http…)`。
 *
 * <b>刻意不支持单个 `*` 表示斜体</b>：导师是在一个纯文本框里打字的，
 * 「价格 3*5=15」这种写法一旦被当成斜体，屏幕上出现的是一句他没写过的话。
 * 少认一种记号，换掉一整类"我明明不是这个意思"。
 *
 * 认不出来的记号一律当正文原样显示 —— 半吊子地渲染比不渲染更坏：
 * 学员分不清哪一段是格式、哪一段是内容。
 *
 * ## 换行按聊天的规矩，不按 Markdown 的规矩
 *
 * Markdown 里单个换行等于一个空格。可这些消息是在对话气泡里读的，
 * 而这一版之前就是 `white-space: pre-wrap` —— 换成"单换行被吃掉"，
 * 老消息的排版会当场全变。所以段内换行保留成 `<br>`。
 */
import React from 'react';

/** 只放行这三种协议。其余（`javascript:`、`data:`、裸 `//host`）不给它一个能点的壳。 */
function safeHref(raw: string): string | null {
  const u = raw.trim();
  return /^(https?:\/\/|mailto:)/i.test(u) ? u : null;
}

const BULLET = /^\s{0,3}[-*+]\s+/;
const ORDERED = /^\s{0,3}\d{1,3}[.)]\s+/;
const HEADING = /^\s{0,3}(#{1,6})\s+(.*)$/;
const QUOTE = /^\s{0,3}>/;
const FENCE = /^\s*```/;
const RULE = /^\s*([-*_])\s*(\1\s*){2,}$/;

/**
 * 行内记号。一遍扫完，不做嵌套 ——
 * 递归解析在这个子集上收益极小，却会引入"`**a `b` c**` 该算谁的"这类没人说得清的边界。
 */
function inline(src: string, base: string): React.ReactNode[] {
  const out: React.ReactNode[] = [];
  const re = /\*\*(\S(?:[\s\S]*?\S)?)\*\*|`([^`\n]+)`|\[([^\]\n]+)\]\(([^)\s]+)\)/g;
  let last = 0;
  let n = 0;
  let m: RegExpExecArray | null;
  while ((m = re.exec(src)) !== null) {
    if (m.index > last) out.push(src.slice(last, m.index));
    const k = `${base}i${n++}`;
    if (m[1] !== undefined) out.push(<strong key={k}>{m[1]}</strong>);
    else if (m[2] !== undefined) out.push(<code key={k} className="wk-md-code">{m[2]}</code>);
    else {
      const href = safeHref(m[4]);
      // 协议不认识就把 `[字](串)` 原样留在正文里：既不吞掉内容，也不造一个能点的东西
      out.push(href
        ? <a key={k} href={href} target="_blank" rel="noopener noreferrer nofollow">{m[3]}</a>
        : m[0]);
    }
    last = re.lastIndex;
  }
  if (last < src.length) out.push(src.slice(last));
  return out;
}

function blocks(text: string, base: string): React.ReactNode[] {
  const lines = text.replace(/\r\n?/g, '\n').split('\n');
  const out: React.ReactNode[] = [];
  let i = 0;
  let n = 0;

  while (i < lines.length) {
    const line = lines[i];
    if (!line.trim()) { i++; continue; }
    const k = `${base}b${n++}`;

    if (FENCE.test(line)) {
      const body: string[] = [];
      i++;
      while (i < lines.length && !FENCE.test(lines[i])) body.push(lines[i++]);
      i++;   // 吃掉收尾那行。没有收尾行也照样收工 —— 回答被 length 截断时就是这样，
             //   那时候前半段代码仍然该看得见，而不是整条消息塌成一片空白
      out.push(<pre key={k} className="wk-md-pre"><code>{body.join('\n')}</code></pre>);
      continue;
    }

    if (RULE.test(line)) { out.push(<hr key={k} className="wk-md-hr" />); i++; continue; }

    const h = line.match(HEADING);
    if (h) {
      // 用 div 不用 h1~h6：气泡里出现一个真的 h1，既是字号灾难，
      // 也会把整页的标题层级搅乱（读屏软件按标题跳转时会跳进一条聊天记录里）
      out.push(
        <div key={k} className={`wk-md-h wk-md-h${Math.min(h[1].length, 3)}`}>
          {inline(h[2], k)}
        </div>,
      );
      i++;
      continue;
    }

    if (QUOTE.test(line)) {
      const body: string[] = [];
      while (i < lines.length && QUOTE.test(lines[i]))
        body.push(lines[i++].replace(/^\s{0,3}>\s?/, ''));
      out.push(
        <blockquote key={k} className="wk-md-quote">{blocks(body.join('\n'), k)}</blockquote>,
      );
      continue;
    }

    if (BULLET.test(line) || ORDERED.test(line)) {
      const ordered = ORDERED.test(line);
      const mark = ordered ? ORDERED : BULLET;
      const items: string[] = [];
      while (i < lines.length && mark.test(lines[i])) items.push(lines[i++].replace(mark, ''));
      const children = items.map((t, j) => <li key={j}>{inline(t, `${k}l${j}`)}</li>);
      out.push(ordered
        ? <ol key={k} className="wk-md-list">{children}</ol>
        : <ul key={k} className="wk-md-list">{children}</ul>);
      continue;
    }

    const para: string[] = [];
    while (i < lines.length && lines[i].trim()
           && !FENCE.test(lines[i]) && !RULE.test(lines[i]) && !HEADING.test(lines[i])
           && !QUOTE.test(lines[i]) && !BULLET.test(lines[i]) && !ORDERED.test(lines[i]))
      para.push(lines[i++]);
    out.push(
      <p key={k} className="wk-md-p">
        {para.flatMap((t, j) => (j
          ? [<br key={`${k}br${j}`} />, ...inline(t, `${k}p${j}`)]
          : inline(t, `${k}p${j}`)))}
      </p>,
    );
  }
  return out;
}

/**
 * 一段答疑消息。
 *
 * <p><b>只给 AI 与导师的消息用，不给学员自己的。</b>学员在输入框里打的 `**`
 * 就是两个星号，替他"渲染"等于替他改写了他说过的话。
 */
export function Markdown({ text }: { text: string }) {
  const nodes = React.useMemo(() => blocks(text ?? '', 'm'), [text]);
  return <div className="wk-md">{nodes}</div>;
}
