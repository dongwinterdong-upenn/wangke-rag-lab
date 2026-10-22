// 懂妈答疑 sidecar —— 低延迟流式面
//
// 为什么不走 OpenClaw 网关：这一版（2026.7.1-2）的入站面只有 WebSocket，没有 OpenAI 兼容端点
// （包里那些 /v1/* 字符串是【出站】调 provider 用的，/api/* 全 404）。走 CLI 每问一次要起一个
// node 进程、且不流式，实测 10~11 秒才吐第一个字 —— 做聊天界面等于坏的。
//
// 所以请求路径改成这里直接流式调模型，但【懂妈的定义仍然只有一份】：
// 系统提示词逐字来自 OpenClaw 工作区的 IDENTITY.md / SOUL.md / AGENTS.md（含全部课程知识点）。
// 改人设或改知识 = 改那三个文件，本服务按 mtime 热加载，不用重启、不用重新部署 Java。
//
// 只绑 127.0.0.1。对外由 wangke 后端代理，学员走平台自己的登录态。
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';

const WS_DIR   = process.env.DONGMA_WORKSPACE || '/opt/dongma/workspace';
const PORT     = Number(process.env.DONGMA_API_PORT || 18790);
// 监听地址。本机直接跑时只绑回环；放进容器里要让后端容器连得上，由编排文件设成 0.0.0.0，端口不对宿主机暴露。
const HOST     = process.env.DONGMA_API_HOST || '127.0.0.1';
const TOKEN    = process.env.DONGMA_API_TOKEN || '';
const DS_KEY   = process.env.DEEPSEEK_API_KEY || '';
const DS_BASE  = process.env.DEEPSEEK_BASE_URL || 'https://api.deepseek.com';
const DS_MODEL = process.env.DONGMA_MODEL || 'deepseek-v4-flash';
// 思考档位。答疑不需要长思考，而思考直接压在「第一个字什么时候出来」上。
const EFFORT   = process.env.DONGMA_REASONING_EFFORT || 'low';
// 思考（reasoning）也从这个额度里扣，所以它不是「答案最长多少」而是「思考+答案」。
// 1600 太紧：实测 reasoning 在 178~1482 token 之间跳，撞上高的那次正文就被切断了。
// 抬到 4000 让 length 变成罕见事件 —— 但抬额度只是降低概率，
// 真正的兜底是上面那段「finish_reason 不是 stop 就不发 done」。
const MAX_TOK  = Number(process.env.DONGMA_MAX_TOKENS || 4000);

if (!DS_KEY)  { console.error('缺 DEEPSEEK_API_KEY'); process.exit(1); }
if (!TOKEN)   { console.error('缺 DONGMA_API_TOKEN'); process.exit(1); }

// ── 系统提示词：三个 bootstrap 文件按固定顺序拼起来，带 mtime 热加载 ──────────
// 顺序有意：先身份、再规矩、最后知识。规矩放在知识前面，是因为长文档容易把靠前的
// 指令挤到注意力边缘 —— 而「缺讲不许编」这条恰恰是最不能丢的。
const FILES = ['IDENTITY.md', 'SOUL.md', 'AGENTS.md'];
let cache = { key: '', text: '' };
function systemPrompt() {
  const stamp = FILES.map(f => {
    try { const s = fs.statSync(path.join(WS_DIR, f)); return `${f}:${s.mtimeMs}:${s.size}`; }
    catch { return `${f}:missing`; }
  }).join('|');
  if (stamp === cache.key) return cache.text;
  const parts = FILES.map(f => {
    try { return fs.readFileSync(path.join(WS_DIR, f), 'utf8'); } catch { return ''; }
  }).filter(Boolean);
  if (!parts.length) throw new Error(`工作区里一个 bootstrap 文件都没读到：${WS_DIR}`);
  cache = { key: stamp, text: parts.join('\n\n---\n\n') };
  console.log(`[懂妈] 系统提示词已加载 ${cache.text.length} 字符（${FILES.join(', ')}）`);
  return cache.text;
}

const send = (res, code, obj) => {
  res.writeHead(code, { 'content-type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify(obj));
};

const server = http.createServer(async (req, res) => {
  if (req.method === 'GET' && req.url === '/health') {
    let n = 0;
    try { n = systemPrompt().length; } catch (e) { return send(res, 503, { ok: false, error: String(e.message) }); }
    return send(res, 200, { ok: true, promptChars: n, model: DS_MODEL, effort: EFFORT });
  }
  if (req.method !== 'POST' || req.url !== '/ask') return send(res, 404, { ok: false, error: 'not found' });
  if ((req.headers.authorization || '') !== `Bearer ${TOKEN}`) return send(res, 401, { ok: false, error: 'unauthorized' });

  const chunks = [];
  for await (const c of req) chunks.push(c);
  let body;
  try { body = JSON.parse(Buffer.concat(chunks).toString('utf8')); }
  catch { return send(res, 400, { ok: false, error: 'bad json' }); }

  const question = String(body.question || '').trim();
  if (!question) return send(res, 400, { ok: false, error: '问题不能为空' });
  // 历史只收 role/content，且由调用方（平台后端）负责截断 —— 它才知道这是哪个学员的哪一轮
  const history = Array.isArray(body.history) ? body.history
        .filter(m => m && (m.role === 'user' || m.role === 'assistant') && typeof m.content === 'string')
        .slice(-12) : [];

  let sys;
  try { sys = systemPrompt(); } catch (e) { return send(res, 503, { ok: false, error: String(e.message) }); }

  const upstream = await fetch(`${DS_BASE}/chat/completions`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', authorization: `Bearer ${DS_KEY}` },
    body: JSON.stringify({
      model: DS_MODEL,
      stream: true,
      max_tokens: MAX_TOK,
      reasoning_effort: EFFORT,
      messages: [{ role: 'system', content: sys }, ...history, { role: 'user', content: question }],
    }),
  }).catch(e => ({ ok: false, status: 0, _err: e }));

  if (!upstream || !upstream.ok) {
    const detail = upstream?._err ? String(upstream._err.message)
                 : await upstream.text?.().catch(() => '') || '';
    console.error('[懂妈] 上游失败', upstream?.status, detail.slice(0, 300));
    // 明确报错，不降级成一句假答案 —— 学员看到「懂妈答了但答得莫名其妙」比看到报错更糟
    return send(res, 502, { ok: false, error: '模型服务暂时不可用' });
  }

  res.writeHead(200, {
    'content-type': 'text/event-stream; charset=utf-8',
    'cache-control': 'no-cache, no-transform',
    connection: 'keep-alive',
    'x-accel-buffering': 'no',       // 别让任何一层反代把流攒起来再发，那等于没有流式
  });

  let full = '';
  let buf = '';
  let finish = null;                 // 上游给的收尾原因，见下面那段
  const dec = new TextDecoder();
  try {
    for await (const chunk of upstream.body) {
      buf += dec.decode(chunk, { stream: true });
      let i;
      while ((i = buf.indexOf('\n')) >= 0) {
        const line = buf.slice(0, i).trim();
        buf = buf.slice(i + 1);
        if (!line.startsWith('data:')) continue;
        const data = line.slice(5).trim();
        if (data === '[DONE]') continue;
        let j; try { j = JSON.parse(data); } catch { continue; }
        const c = j.choices?.[0];
        if (c?.finish_reason) finish = c.finish_reason;
        const piece = c?.delta?.content;
        if (typeof piece === 'string' && piece) {
          full += piece;
          res.write(`data: ${JSON.stringify({ delta: piece })}\n\n`);
        }
      }
    }

    // ★★ done 只有一个含义：这段回答是完整的。
    //
    //   原来这里【无条件】发 done:true —— 它表达的其实是「上游的流结束了」，
    //   而那和「答案说完了」不是一回事。最常见的分歧是 finish_reason='length'：
    //   reasoning_effort 产生的思考也吃 max_tokens，思考一涨就把正文的额度挤没了，
    //   回答在半句话上被切断。实测同一个追问连打 5 次，1 次撞上 length
    //   （思考 1482 token，正文只剩 213 字且断在句中）。
    //
    //   平台侧 DongmaClient 的判据是「没收到 done = 半截，转人工」，
    //   于是这条谎话把那道闸整个绕过去了：半截答案带着 done:true 落进 qa_message，
    //   学员看到的是一条像模像样的回答，缺的恰好是后半段的注意事项。
    //   零报错、日志里也看不出来 —— 这正是这套设计从一开始就要防的那种形态。
    //
    //   content 为空同理：上游只吐了思考、一个字正文都没有，也绝不能算完成。
    if (finish && finish !== 'stop') {
      console.error(`[懂妈] 回答未说完 finish_reason=${finish} 正文=${full.length}字`);
      res.write(`data: ${JSON.stringify({ error: `回答被截断（${finish}）`, truncated: true })}\n\n`);
    } else if (!full) {
      console.error('[懂妈] 上游没有产出任何正文（多半是思考吃光了额度）');
      res.write(`data: ${JSON.stringify({ error: '模型没有产出正文', truncated: true })}\n\n`);
    } else {
      res.write(`data: ${JSON.stringify({ done: true, text: full })}\n\n`);
    }
  } catch (e) {
    console.error('[懂妈] 流中断', e);
    res.write(`data: ${JSON.stringify({ error: '回答中断，请重试' })}\n\n`);
  }
  res.end();
});

server.listen(PORT, HOST, () => {
  try { systemPrompt(); } catch (e) { console.error('[懂妈] 启动即警告：', e.message); }
  console.log(`[懂妈] sidecar 监听 ${HOST}:${PORT}  模型=${DS_MODEL} 思考档=${EFFORT}`);
});
