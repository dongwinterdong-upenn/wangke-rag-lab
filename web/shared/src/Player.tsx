/**
 * 课程播放器。
 *
 * 这是学员端的产品本体：其他页面出点毛病用户会忍，播放器卡一次他就来退款。
 * 所以这里处理的每一件事都是真实会发生的，不是"完备性"：
 *
 *  - **续播**：上次看到哪就从哪开始。做不到的话，两小时的课每次都要手动拖。
 *  - **进度上报**：节流 + 页面隐藏时补一发。只在 timeupdate 里报会把服务打垮；
 *    只在卸载时报则手机切后台就丢——移动端最常见的离开方式恰恰是切后台。
 *  - **可见水印**：半透明手机号，每 5 秒换一次位置。
 *    实测过：连续漂移（跑马灯）会让码率涨 80%，每 5 秒跳一格只涨 3.9%。
 *    这里是 DOM 覆盖层，不占码率，但同理——连续动画会让低端机掉帧。
 *  - **令牌过期**：密钥令牌只有 90 秒。播放中必须静默续签，
 *    否则学员看到一半突然黑屏，而他什么也没做错。
 *  - **错误分型**：网络断、权益过期、设备超限、令牌失效，四种要说不同的话。
 *    统统显示"播放失败"的话，用户不知道该等一下、该换设备、还是该找客服。
 */
import React from 'react';
import Hls from 'hls.js';
import { api, hms, deviceId } from './api';
import { Button, Spinner } from './ui';

export type PlayerProps = {
  lessonId: number;
  title: string;
  /** 水印文案：本人手机号。真出现在盗录里，这就是溯源的第一条线索 */
  watermark: string;
  /** 上次看到的秒数 */
  startAt?: number;
  onProgress?: (positionSec: number, durationSec: number) => void;
  onEnded?: () => void;
  /** 会话开出来时回调一次。心跳挂在会话上，页面要拿这个 id 去发心跳 */
  onSession?: (sessionId: string) => void;
};

/**
 * 后端 /api/play/session 与 /api/play/renew 的响应形状（两者逐字段相同）。
 *
 * ★ 这里曾经写错过，代价是"点开任何一讲都是黑屏"：
 *   本文件原来读的是 { session: { m3u8, keyUri, expiresInSeconds } } 这种嵌套形状，
 *   而后端返回的是平铺的 { sessionId, token, expiresInSec, m3u8Url }。
 *   两边字段名没有一个对得上，但 TypeScript 拦不住 —— 响应是 any，
 *   取到 undefined 之后 hls.loadSource(undefined) 只会安静地什么都不放。
 *   所以这个类型不是装饰，它是那次事故的封条：改后端字段名必须同时改这里。
 */
type Session = {
  ok: boolean;
  sessionId: string;
  token: string;
  expiresInSec: number;
  lessonId: number;
  title: string;
  durationSec: number;
  resumePositionSec: number;
  m3u8Url: string;
};

export function Player({ lessonId, title, watermark, startAt = 0,
                         onProgress, onEnded, onSession }: PlayerProps) {
  const videoRef = React.useRef<HTMLVideoElement>(null);
  // 全屏必须作用在【外壳】上，不是 <video> 上。水印是 video 的兄弟节点，
  // 对 video 调 requestFullscreen 之后只有 video 那棵子树在画面里 ——
  // 水印当场消失，而全屏正是所有人看课和录屏的姿势。
  // 也就是说：唯一能在录屏文件里留痕的东西，恰好在录屏的那一刻不在。
  const shellRef = React.useRef<HTMLDivElement>(null);
  const hlsRef = React.useRef<Hls | null>(null);
  const [phase, setPhase] = React.useState<'idle' | 'loading' | 'ready' | 'error'>('loading');
  const [err, setErr] = React.useState<{ text: string; retry: boolean }>({ text: '', retry: true });
  const [rate, setRate] = React.useState(1);
  const [wmPos, setWmPos] = React.useState({ x: 12, y: 14 });

  /* ---- 水印位置：每 5 秒跳一格 -------------------------------------- */
  React.useEffect(() => {
    // 黄金比低差异序列：位置分布均匀且不重复，比 random 更不容易连续两次落在同一角
    let k = Math.floor(Math.random() * 100);
    const move = () => {
      k += 1;
      setWmPos({
        x: 6 + ((k * 0.618034) % 1) * 74,   // 留出边距，避免被播放条压住
        y: 8 + ((k * 0.381966) % 1) * 68,
      });
    };
    move();
    const t = setInterval(move, 5000);
    return () => clearInterval(t);
  }, []);

  /* ---- 令牌续签 ------------------------------------------------------- */
  /**
   * 播放令牌只有 90 秒，而它被【烧进 m3u8 里的 key URI】
   * （`/api/play/key?lessonId=..&token=..&deviceId=..`），而播放列表只生成一次。
   *
   * 不续签的后果不是"到期报错"，是更难查的那一种：
   * 90 秒后每个密钥请求都带着死令牌 → 403 → hls.js 把它归到 NETWORK_ERROR
   * → 走 `startLoad()` 无限重试。学员看到的是**两分钟后画面卡住，而播放器一句错都不报**。
   *
   * 做法：把最新令牌存在 ref 里，在 xhrSetup 里【重写密钥请求 URL 上的 token 参数】。
   * 这样不必重新加载播放列表（重载会打断播放），只有密钥这一路带新令牌。
   *
   * 续签走 /api/play/renew 而不是再调一次 /session —— 后者每次都新建 play_session、
   * 重绑设备、给风控加噪声，而心跳挂在会话上，一节两小时的课会开出上百条会话。
   */
  const tokenRef = React.useRef<string>('');
  const sessionRef = React.useRef<string>('');
  const renewTimer = React.useRef<ReturnType<typeof setTimeout> | null>(null);

  const withFreshToken = (url: string) => {
    if (!tokenRef.current) return url;
    // 只改 token，其余参数（lessonId / deviceId）原样保留
    return url.replace(/([?&]token=)[^&]*/, (_m, p1) => p1 + encodeURIComponent(tokenRef.current));
  };

  const scheduleRenew = React.useCallback((expiresInSec: number) => {
    if (renewTimer.current) clearTimeout(renewTimer.current);
    // 提前 1/3 个周期续，且不早于 10 秒 —— 贴着到期续的话，
    // 一次网络抖动就正好落在窗口外，而那时画面已经卡住了
    const delay = Math.max(10, Math.floor(expiresInSec * 2 / 3)) * 1000;
    renewTimer.current = setTimeout(async () => {
      try {
        const s = await api.post<Session>('/api/play/renew', {
          sessionId: sessionRef.current, deviceId: deviceId(),
        });
        tokenRef.current = s.token;
        scheduleRenew(s.expiresInSec);
      } catch (e: any) {
        // 续签失败要说人话并停下来。继续放任 hls 无限重试的话，
        // 学员盯着一个不动的画面，而权益其实已经被回收了。
        if (e?.status === 403) {
          setErr({ text: e?.message || '播放权限已失效，请重新打开本讲', retry: true });
          setPhase('error');
        } else {
          // 网络抖动：短暂退避后再试一次，不要直接把画面打成错误态
          renewTimer.current = setTimeout(() => scheduleRenew(0), 5000);
        }
      }
    }, delay);
  }, []);

  /* ---- 开播 ---------------------------------------------------------- */
  const start = React.useCallback(async () => {
    setPhase('loading');
    try {
      const s = await api.post<Session>('/api/play/session', {
        lessonId, deviceId: deviceId(),
      });
      const video = videoRef.current;
      if (!video) return;

      tokenRef.current = s.token;
      sessionRef.current = s.sessionId;
      onSession?.(s.sessionId);
      scheduleRenew(s.expiresInSec);

      if (Hls.isSupported()) {
        hlsRef.current?.destroy();
        const hls = new Hls({
          maxBufferLength: 30,
          // 分片是加密的，密钥要带令牌去取。播放列表里那张令牌 90 秒就死了，
          // 所以每一次密钥请求都把 URL 上的 token 换成手里最新的那张。
          xhrSetup: (xhr, url) => {
            if (url.includes('/api/play/key')) {
              xhr.withCredentials = false;
              xhr.open('GET', withFreshToken(url), true);
            }
          },
        });
        hlsRef.current = hls;
        // 自愈次数必须有上限。原来这里是无条件 startLoad()，
        // 那是一个【把所有失败都变成"画面不动、一句话不说"】的装置 ——
        // 令牌过期、权益被回收、密钥端点挂了，全都表现成永久重试。
        // 地铁里丢一两个分片值得自愈，连着三次拿不到就该说人话。
        let netRetries = 0;
        hls.on(Hls.Events.ERROR, (_e, data) => {
          if (!data.fatal) return;
          if (data.type === Hls.ErrorTypes.NETWORK_ERROR) {
            const status = (data.response as any)?.code;
            if (status === 403 || status === 401) {
              setErr({ text: '播放权限已失效，请重新打开本讲', retry: true });
              setPhase('error');
              hls.stopLoad();
              return;
            }
            // 取片速率闸（后端 SegmentService）。它必须单独一条分支：
            // 落到下面那条重试路径的话，学员看到的是"网络不稳定，请检查网络" ——
            // 而网络一点问题都没有，他检查不出任何东西，也不知道该做什么。
            // 重试三次还会把已经越界的速率推得更高。
            if (status === 429) {
              setErr({
                text: '取片速度远超正常播放速度，已被限速。等一会儿再点重试就能继续；'
                    + '如果你在用下载工具或加速插件，请先关掉。',
                retry: true,
              });
              setPhase('error');
              hls.stopLoad();
              return;
            }
            if (++netRetries <= 3) { hls.startLoad(); return; }
            setErr({ text: '网络不稳定，视频加载不上来，请检查网络后重试', retry: true });
            setPhase('error');
            hls.stopLoad();
            return;
          }
          if (data.type === Hls.ErrorTypes.MEDIA_ERROR) { hls.recoverMediaError(); return; }
          setErr({ text: '播放中断，请重试', retry: true });
          setPhase('error');
        });
        hls.loadSource(s.m3u8Url);
        hls.attachMedia(video);
      } else if (video.canPlayType('application/vnd.apple.mpegurl')) {
        video.src = s.m3u8Url;         // iOS Safari 原生 HLS
      } else {
        setErr({ text: '当前浏览器不支持在线播放，请更换 Chrome / Safari', retry: false });
        setPhase('error');
        return;
      }

      // 续播起点：调用方给了就听调用方的，没给就用服务端记的位置。
      // 服务端那个才是跨设备一致的 —— 本机记录换台手机就没了。
      const resume = startAt > 1 ? startAt : s.resumePositionSec;
      if (resume > 1) video.currentTime = resume;
      setPhase('ready');
    } catch (e: any) {
      // 分型说人话：每一种对应用户能采取的不同动作
      const s = e?.status;
      const msg: string = e?.message || '';
      if (s === 403 && msg.includes('设备'))
        setErr({ text: '你已在 2 台设备上观看过本课程。如需更换设备，请联系客服解绑', retry: false });
      else if (s === 403 && (msg.includes('过期') || msg.includes('有效期')))
        setErr({ text: '课程观看有效期已到期', retry: false });
      else if (s === 403)
        setErr({ text: msg || '你还没有本课程的观看权限', retry: false });
      else if (s === 0)
        setErr({ text: '网络连接不可用，请检查网络后重试', retry: true });
      else
        setErr({ text: msg || '播放失败，请重试', retry: true });
      setPhase('error');
    }
  }, [lessonId, startAt, scheduleRenew, onSession]);

  React.useEffect(() => {
    start();
    return () => {
      hlsRef.current?.destroy();
      hlsRef.current = null;
      // 续签定时器必须一起清。不清的话，换讲之后旧的那条还在按 60 秒一次
      // 给【上一讲的会话】续签 —— 每次都 403，风控看到的是一串失败的令牌请求。
      if (renewTimer.current) { clearTimeout(renewTimer.current); renewTimer.current = null; }
    };
  }, [start]);

  /* ---- 进度上报 ------------------------------------------------------ */
  const lastReport = React.useRef(0);
  // 回调放进 ref，让下面那个 effect【只挂一次】。
  //
  // 原来它的依赖是 [onProgress, onEnded]，而调用方传的是内联箭头函数 ——
  // 每次渲染都是新身份，于是每次渲染都重挂一遍。而这个组件本来就会频繁重渲染：
  // 水印每 5 秒换一次位置就是一次 setState。合起来的实际行为是
  //   · 每 5 秒卸载一次 → cleanup 里的 report(true) 每 5 秒发一次进度上报；
  //   · 每 5 秒新增一个 pagehide 监听，而它从来没被摘掉（见下面那条）。
  // 两条都不会报错，只是服务端看到一个人在匀速刷接口，而页面开久了监听越堆越多。
  const cbRef = React.useRef({ onProgress, onEnded });
  cbRef.current = { onProgress, onEnded };

  React.useEffect(() => {
    const video = videoRef.current;
    if (!video) return;

    const report = (force = false) => {
      const now = Date.now();
      if (!force && now - lastReport.current < 15000) return;   // 15 秒一次，够用且不打垮服务
      lastReport.current = now;
      if (video.duration && video.currentTime > 0)
        cbRef.current.onProgress?.(Math.floor(video.currentTime), Math.floor(video.duration));
    };
    const onTime = () => report(false);
    const onEnd = () => { report(true); cbRef.current.onEnded?.(); };
    // 手机上最常见的"离开"是切后台，不是关页面。
    // 只监听 unload 的话，移动端的进度基本都丢了。
    const onHide = () => { if (document.visibilityState === 'hidden') report(true); };
    // ★ 具名。原来这里是 window.addEventListener('pagehide', () => report(true))，
    //   一个匿名函数 —— 下面的 cleanup 摘不掉它，于是它只增不减。
    const onPageHide = () => report(true);

    video.addEventListener('timeupdate', onTime);
    video.addEventListener('ended', onEnd);
    document.addEventListener('visibilitychange', onHide);
    window.addEventListener('pagehide', onPageHide);
    return () => {
      report(true);
      window.removeEventListener('pagehide', onPageHide);
      video.removeEventListener('timeupdate', onTime);
      video.removeEventListener('ended', onEnd);
      document.removeEventListener('visibilitychange', onHide);
    };
    // 空依赖：videoRef 在整个生命周期里不变，回调走 cbRef。
  }, []);

  /* ---- 全屏 ---------------------------------------------------------- */
  // 原生控件那颗全屏按钮同样会把 <video> 单独全屏，所以 controlsList 里加了
  // nofullscreen 把它去掉，改由这里出一颗按钮，全屏外壳。
  // 【iOS Safari 说明】那边 nofullscreen 不生效，<video> 走的是系统级全屏层，
  // HTML 覆盖层在原理上就进不去。那种情况下可见水印确实不在画面里 ——
  // 兜底的是分片级 A/B 取证水印（它在内容里，跟全屏没关系）。这条不要试图用 JS 绕，
  // 绕出来的都是"看起来生效、实际没有"的东西。
  const [isFull, setIsFull] = React.useState(false);
  React.useEffect(() => {
    const onFs = () => setIsFull(document.fullscreenElement === shellRef.current);
    document.addEventListener('fullscreenchange', onFs);
    return () => document.removeEventListener('fullscreenchange', onFs);
  }, []);
  const toggleFullscreen = React.useCallback(() => {
    const shell = shellRef.current;
    const video = videoRef.current as any;
    if (!shell) return;
    if (document.fullscreenElement) { void document.exitFullscreen?.(); return; }
    if (shell.requestFullscreen) { void shell.requestFullscreen(); return; }
    // iOS Safari：<div> 上没有 requestFullscreen，只有 <video> 有 webkitEnterFullscreen。
    // 那条路走的是系统级播放层，HTML 覆盖层进不去 —— 也就是说【这一档没有可见水印】。
    // 但一颗点了没反应、没有任何提示的按钮更糟，所以退到系统全屏。
    // 兜底的是分片级 A/B 取证水印：它在内容里，跟全屏没关系。
    if (video?.webkitEnterFullscreen) video.webkitEnterFullscreen();
  }, []);

  // Firefox 不认 controlsList，原生那颗全屏按钮还在，点了会把 <video> 单独全屏 ——
  // 水印是它的兄弟节点，跟不过去。这里兜一下：发现全屏元素是 video 就换成外壳。
  // 这个切换发生在用户手势的同一串事件里，所以不会被"需要用户激活"挡掉。
  React.useEffect(() => {
    const onFs = () => {
      if (document.fullscreenElement && document.fullscreenElement === videoRef.current) {
        void document.exitFullscreen?.().then(() => shellRef.current?.requestFullscreen?.());
      }
    };
    document.addEventListener('fullscreenchange', onFs);
    return () => document.removeEventListener('fullscreenchange', onFs);
  }, []);

  /* ---- 倍速 ---------------------------------------------------------- */
  React.useEffect(() => {
    if (videoRef.current) videoRef.current.playbackRate = rate;
  }, [rate]);

  /* ---- 键盘 ---------------------------------------------------------- */
  React.useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const v = videoRef.current;
      if (!v || phase !== 'ready') return;
      const tag = (e.target as HTMLElement)?.tagName;
      if (tag === 'INPUT' || tag === 'TEXTAREA') return;
      switch (e.key) {
        case ' ': e.preventDefault(); v.paused ? v.play() : v.pause(); break;
        case 'ArrowLeft': v.currentTime = Math.max(0, v.currentTime - 5); break;
        case 'ArrowRight': v.currentTime = Math.min(v.duration, v.currentTime + 5); break;
        case 'f': e.preventDefault(); toggleFullscreen(); break;
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [phase, toggleFullscreen]);

  return (
    <div className="surface-cinema" style={{
      borderRadius: 'var(--r-lg)', overflow: 'hidden', position: 'relative',
      border: '1px solid var(--line)',
    }}>
      <style>{FS_BTN_CSS}</style>
      <div
        ref={shellRef}
        style={{
          position: 'relative', background: '#000',
          // 全屏时把 16:9 让开：全屏层是整块屏幕，再按比例撑会在上下留两条空白，
          // 而水印是按这个盒子的百分比定位的，盒子不对水印就会飘到画面外。
          ...(isFull ? { width: '100%', height: '100%' } : { aspectRatio: '16 / 9' }),
        }}
      >
        <video
          ref={videoRef} controls playsInline
          // nofullscreen：原生那颗全屏按钮全屏的是 <video> 自己，水印跟不过去。
          // 去掉它，改用下面那颗按钮全屏外壳。
          // disableRemotePlayback：投屏/AirPlay 是同一类洞 —— 画面到了电视上，
          // 水印留在浏览器里。controlsList 的 noremoteplayback 只藏按钮，
          // 能力还在（一行 video.remote.prompt() 就能唤起），要靠这个属性才真关掉。
          controlsList="nodownload noremoteplayback nofullscreen"
          disablePictureInPicture
          disableRemotePlayback
          onContextMenu={e => e.preventDefault()}
          style={{ width: '100%', height: '100%', display: 'block', background: '#000' }}
        />

        {phase === 'ready' && (
          <button
            type="button"
            onClick={toggleFullscreen}
            aria-label={isFull ? '退出全屏' : '全屏'}
            title={isFull ? '退出全屏（f）' : '全屏（f）'}
            className="wk-fs-btn"
            style={{
              // 贴右下角、压在原生控件条上方。原生控件会自动隐藏，所以这颗按钮
              // 平时压到半透明、指针移上去（或键盘聚焦）才亮起来 ——
              // 常亮的话，一颗浮在画面上的方块会一直在那儿碍眼。
              position: 'absolute', right: 8, bottom: 44, zIndex: 2,
              width: 34, height: 34, display: 'grid', placeItems: 'center',
              border: 'none', borderRadius: 8, cursor: 'pointer',
              background: 'rgba(0,0,0,.55)', color: '#fff', fontSize: 15, lineHeight: 1,
            }}
          >{isFull ? '\u2715' : '\u26f6'}</button>
        )}

        {/* 可见水印。pointerEvents:none —— 绝不能挡住播放控件 */}
        {phase === 'ready' && (
          <div aria-hidden style={{
            position: 'absolute',
            // 靠哪边近就从哪边定位。原来一律用 left，于是水印飘到右半边时，
            // 尾巴会被容器 overflow:hidden 切掉 —— 切掉的恰好是手机号后四位，
            // 也就是溯源时最要紧的那几位。窄屏 + 加了姓名之后更容易发生。
            // 靠哪边近就从哪边定位，且 maxWidth 要按【剩下的那点空间】算 ——
            // 写死 calc(100% - 16px) 的话，left:46% 的水印仍然能溢出右边 46%，
            // 被容器 overflow:hidden 切掉尾巴，而切掉的正是手机号后四位。
            ...(wmPos.x > 50
              ? { right: `${100 - wmPos.x}%`, maxWidth: `calc(${wmPos.x}% - 8px)` }
              : { left: `${wmPos.x}%`, maxWidth: `calc(${100 - wmPos.x}% - 8px)` }),
            top: `${wmPos.y}%`,
            whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
            color: 'rgba(255,255,255,.34)',
            // 白底幻灯上纯白水印几乎看不见（这是实测过的），所以必须配深色描边
            textShadow: '0 1px 3px rgba(0,0,0,.6), 0 0 1px rgba(0,0,0,.9)',
            fontSize: 'clamp(12px, 1.6vw, 18px)', fontWeight: 500, letterSpacing: '.04em',
            pointerEvents: 'none', userSelect: 'none',
            transition: 'left .6s var(--ease), right .6s var(--ease), top .6s var(--ease)',
          }}>{watermark}</div>
        )}

        {phase === 'loading' && (
          <div style={{ position: 'absolute', inset: 0, display: 'grid', placeItems: 'center',
                        color: '#fff', gap: 'var(--s3)' }}>
            <Spinner size={26} color="#fff" />
          </div>
        )}

        {phase === 'error' && (
          <div style={{ position: 'absolute', inset: 0, display: 'grid', placeItems: 'center',
                        background: 'rgba(0,0,0,.82)', padding: 'var(--s5)', textAlign: 'center' }}>
            <div>
              <div style={{ color: '#fff', fontSize: 'var(--t-h3)', maxWidth: 420 }}>{err.text}</div>
              {err.retry && <div style={{ marginTop: 'var(--s4)' }}>
                <Button variant="ghost" onClick={start}>重新加载</Button>
              </div>}
            </div>
          </div>
        )}
      </div>

      <div style={{
        display: 'flex', alignItems: 'center', gap: 'var(--s4)', flexWrap: 'wrap',
        padding: 'var(--s3) var(--s4)', background: 'var(--surface)',
        borderTop: '1px solid var(--line)',
      }}>
        <div style={{ fontWeight: 500, flex: 1, minWidth: 180, color: 'var(--ink)' }}>{title}</div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--s1)' }}>
          <span className="da-overline bare" style={{ marginRight: 4 }}>倍速</span>
          {[0.75, 1, 1.25, 1.5, 2].map(r => (
            // 数字走等宽：0.75 / 1 / 1.25 五个按钮的宽度在比例字形下参差不齐，
            // 一排看过去像是没对齐。等宽之后它们各自的宽度只由字数决定。
            <button key={r} onClick={() => setRate(r)} className="num wk-btn" style={{
              background: rate === r ? 'var(--brand)' : 'transparent',
              color: rate === r ? '#fff' : 'var(--ink-2)',
              border: `1px solid ${rate === r ? 'var(--brand)' : 'var(--line-strong)'}`,
              padding: '4px 9px', fontSize: 'var(--t-xs)', minHeight: 30, boxShadow: 'none',
            }}>{r}×</button>
          ))}
        </div>
      </div>

      <div style={{ padding: '0 var(--s4) var(--s3)', fontSize: 'var(--t-xs)', color: 'var(--ink-3)' }}>
        空格播放/暂停 · ←→ 快退快进 5 秒 · F 全屏 ·
        画面带你的账号水印，请勿外传（每份拷贝可溯源到具体账号）
      </div>
    </div>
  );
}

export { hms };

/* 自绘全屏按钮：平时半透明，指针移上去或键盘聚焦才实。
   :focus-visible 那一条是给键盘用户的 —— 只有 hover 的话，
   Tab 到这颗按钮上时它仍然是半透明的，看不出焦点在哪。 */
const FS_BTN_CSS = `
.wk-fs-btn { opacity: .38; transition: opacity .18s ease; }
.surface-cinema:hover .wk-fs-btn,
.wk-fs-btn:hover,
.wk-fs-btn:focus-visible { opacity: 1; }
@media (hover: none) { .wk-fs-btn { opacity: .72; } }
`;
