/**
 * 导出 CSV。三端共用一份 —— 这三件事里任何一件在<b>某一端</b>被漏掉，
 * 都是「不报错但坏了」，而且坏在我们主动发给外部的文件上：
 *
 * ① **BOM**（写成 `'\ufeff'` 转义，不写字面字符 —— 那是个看不见的码位，
 *    被编辑器或一次 sed 顺手吃掉之后，源码看不出任何变化、导出照常，只是 Excel 里变乱码）。
 *    少了它 Excel 打开中文列名是一堆乱码。文件本身完全正确，
 *    只有人读不出来，于是他会以为导出功能坏了。
 * ② **公式注入转义**（`/^[=+\-@\t\r]/`）。以这些字符开头的单元格在 Excel 里会被当公式
 *    **执行**。订单号、备注、发放原因都是自由文本，而这份文件是我们主动发给代理的。
 * ③ **revokeObjectURL 退一帧再放**。立刻 revoke 在部分浏览器上会让下载中断，
 *    表现是点了按钮页面上什么都不发生，控制台一行红字都没有。
 *
 * 这三个函数原来在代理端 kit.tsx 和后台 fmt.ts 里各有一份逐字节相同的拷贝。
 * 提到这里不是为了少写几行 —— 是因为 ② 是一条安全判据，
 * 而「同一条安全判据存在两处」的失败方式是改了一处、忘了另一处，
 * 且两边都照常导出、照常打开，只有拿到文件的人会中招。
 */

export function csvCell(v: string | number | null | undefined): string {
  let s = v === null || v === undefined ? '' : String(v);
  if (/^[=+\-@\t\r]/.test(s)) s = "'" + s;
  return `"${s.replace(/"/g, '""')}"`;
}

/** 触发浏览器下载。带 BOM，否则 Excel 打开中文列名是乱码。 */
export function downloadCsv(filename: string, rows: (string | number | null | undefined)[][]) {
  const body = rows.map(r => r.map(csvCell).join(',')).join('\r\n');
  const blob = new Blob(['\ufeff' + body], { type: 'text/csv;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  // 立刻 revoke 在部分浏览器上会让下载中断，退一帧再放
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

/** 文件名里的时间戳。同一天导出好几次时，没有它几个文件会互相覆盖。 */
export function stamp(): string {
  const d = new Date();
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}-${p(d.getHours())}${p(d.getMinutes())}`;
}
