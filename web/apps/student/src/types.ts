/**
 * 后端返回体的形状。
 *
 * 这些类型是**照着 Controller / Service 的返回体逐字段抄的**，不是猜的。
 * 抄不到的（后端还没写）在字段上标了 `// 假设`，交付说明里也列了一遍 ——
 * 把假设混在已确认的字段里，将来接口对不上时没人分得清哪些是我编的。
 */

/* ------------------------------------------------------------------ 账号 */

/** AuthService.Session */
export type Session = {
  token: string;
  userId: number;
  role: string;
  phoneMasked: string;
  mustChangePwd: boolean;
  expiresInSeconds: number;
};

/** AuthService.me()。注意 phone 是**打码后**的，见 session.ts 里水印那段说明。 */
export type Me = {
  id: number;
  phone: string;
  nickname: string | null;
  role: string;
  status: string;
  realName: string | null;
  mustChangePwd?: number;
  agentId?: number | null;
  agentLevel?: number | null;
  agentCode?: string | null;
};

/* ------------------------------------------------------------------ 课程 */

/** CourseService.myCourses() 的一行 */
export type MyCourse = {
  courseId: number;
  title: string;
  subtitle: string | null;
  coverUrl: string | null;
  status: 'ACTIVE' | 'EXPIRED' | 'SUSPENDED';
  source: 'ACTIVATION_CODE' | 'DIRECT_PURCHASE' | 'GRANT';
  validFrom: string;
  validUntil: string;
  expired: boolean;
  totalLessons: number;
  doneLessons: number;
  totalSeconds: number;
  watchedSeconds: number;
  progressPct: number;
};

/**
 * 讲次里的一节：一段三小时录屏按内容切出来的时间区间，点它就跳到那一刻。
 *
 * ★ `passed` / `current` 是【播放位置】的事实，不是「学过了」。
 * 服务端手里只有 max_position_sec 这一个标量，一个把进度条拖到片尾的人
 * 会让整讲每一节都 passed。所以界面上不许把它画成对勾或百分比 ——
 * 见 CourseService.viewSections 的注释。
 */
export type SectionRow = {
  sortNo: number;
  title: string;
  summary: string | null;
  kind: 'LECTURE' | 'DEMO' | 'HANDSON' | 'QA' | 'OTHER';
  startSec: number;
  endSec: number;
  /** 私有视图才有：播放位置已经越过这一节 */
  passed?: boolean;
  /** 私有视图才有：上次停在这一节里 */
  current?: boolean;
};

export type LessonRow = {
  lessonId: number;
  title: string;
  sortNo: number;
  durationSec: number;
  isPreview: boolean;
  /** 内容分节。没有分节的讲次给空数组，不给 null */
  sections: SectionRow[];
  /** 私有视图才有（/api/learn/course/{id}/lessons） */
  maxPositionSec?: number;
  watchedSec?: number;
  progressPct?: number;
  completed?: boolean;
};

export type Chapter = { chapterId: number; title: string; lessons: LessonRow[] };

/** GET /api/learn/course/{id}/lessons */
export type CourseLessons = { courseId: number; validUntil: string; chapters: Chapter[] };

/**
 * GET /api/public/course/{id}（CourseController.publicCourse）。
 * 没买这门课的人看到的那一份：有目录、有先导课标记，<b>没有任何进度字段</b>。
 */
export type PublicCourse = {
  courseId: number;
  title: string;
  subtitle: string | null;
  coverUrl: string | null;
  totalLessons: number;
  totalSeconds: number;
  /** 目录里一共多少节。写「4 讲」和写「4 讲 · 119 节」是两回事 */
  totalSections?: number;
  chapters: Chapter[];
};

/* ------------------------------------------------------------------ 播放 */

/** PlayService.Opened —— PlayController /api/play/session 的返回 */
export type PlayOpened = {
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

/** ProgressController 的统一返回体 */
export type ProgressSnapshot = {
  lessonId: number;
  maxPositionSec: number;
  watchedSec: number;
  durationSec: number;
  progressPct: number;
  completed: boolean;
  creditedSec: number;
};

/* ------------------------------------------------------------------ 测评 */

/** ExamService.Rules */
export type ExamRules = {
  configId: number;
  questionCount: number;
  passScore: number;
  durationMin: number;
  shuffleOptions: boolean;
  maxAttempts: number;
  retryCooldownHours: number;
  requireProgressPct: number;
};

/**
 * GET /api/exam/config?paperId=
 *
 * ★ **学员端已经不再调用这个接口了**（2026-09-03 多套试卷）。规则页要写的
 * 卷名与"发不发证"不在它的返回体里，而 `/api/exam/papers` 的一张卡是它的超集，
 * 所以那一页改成拉全量再 find —— 见 Exam.tsx 的 IntroScreen。
 * 类型留着是因为接口还在（后端与老客户端在用）；照着它把界面接回单卷调用之前，
 * 得先让 /config 也下发卷身份，否则规则页的标题会没有出处。
 * 现状的代价记在交付说明里：每进一次规则页，后端要对该学员可见的每一份卷各跑一次闸门。
 */
export type ExamStatus = {
  rules: ExamRules;
  progressPct: number;
  attemptsUsed: number;
  attemptsLeft: number;
  inProgressAttemptId: number | null;
  passedAttemptId: number | null;
  cooldownSecondsLeft: number | null;
  canStart: boolean;
  blockedReason: string | null;
};

export type PaperQuestion = {
  id: number;
  type: 'SINGLE' | 'MULTI' | 'JUDGE';
  stem: string;
  options: { key: string; text: string }[] | null;
};

/**
 * GET /api/exam/{attemptId} —— 一场考试的考卷（和试卷 `PaperCard` 不是一回事）。
 *
 * ★ `paperId` / `paperTitle` / `grantsCertificate` 三个是 2026-09-03 多套试卷加的：
 * 答题页由 attempt 驱动，交完卷要知道自己刚才考的是哪一份卷、那一份发不发证。
 *
 * ★ `grantsCertificate` 写成 `boolean | number`：库里那一列是 TINYINT(1)，
 * 经 Connector/J 取回来可能是 Boolean 也可能是 Number，序列化出去是 `true` 或 `1`。
 * 所以只按真假用它，**不要写 `=== true`** —— 那个写法遇到 `1` 会静默判成"不发证"。
 * 写成可选是因为老后端没有这个键（全局 non_null 下 null 也是整个键消失）：
 * 缺席的语义是「不知道」，成绩页据此两句话都不说，见 Exam.tsx 的 ResultScreen。
 */
export type Paper = {
  attemptId: number;
  /** 可空：后端是 LEFT JOIN exam_paper 取的，attempt.paper_id 为空时这个键在 non_null 下整个消失。
   *  写成必填会让下一个人照类型直接拼 `?paper=${paper.paperId}`，静默拼出 paper=undefined。 */
  paperId?: number;
  paperTitle?: string;
  grantsCertificate?: boolean | number;
  attemptNo: number;
  rules: ExamRules;
  deadlineAt: string;
  secondsLeft: number;
  submitted: boolean;
  score: number | null;
  passed: boolean | null;
  questions: PaperQuestion[];
};

/**
 * ExamService.AttemptRow
 *
 * ★ `courseId` **不再必填**：开放卷不挂课程，那一行的 course_id 是 NULL，
 * non_null 下这个键整个消失。原来声明成 `number` 的后果是编译期查不出
 * `titleOf(a.courseId)`，运行时渲染出一句字面量「课程 #undefined」。
 * `paperId` 是这一轮新加的，"这场考试属于哪份卷"只能靠它。
 */
export type AttemptRow = {
  id: number;
  paperId?: number;
  courseId?: number;
  attemptNo: number;
  score: number | null;
  passed: boolean | null;
  startedAt: string;
  deadlineAt: string;
  submittedAt: string | null;
  state: string;
};

/* ------------------------------------------------------------------ 测评报告 */

/**
 * 报告的生成状态，与库里 `exam_report.status` 那个 ENUM 一一对应。
 *
 * QUEUED/RUNNING 是「还在路上」，READY/FAILED 是终态。学员端据此决定
 * 还要不要接着轮询 —— 判据只有这一处，别在页面里另按 content 有没有值去猜。
 */
export type ExamReportStatus = 'QUEUED' | 'RUNNING' | 'READY' | 'FAILED';

/**
 * `GET /api/exam/{attemptId}/report` 的返回体，照《测评报告规格》第四节
 * `{status, stats, content, model, failReason, canRetry}` 那一行写。
 *
 * ★ **这一组类型第一版是照规格写的，现在已经和后端 ExamReportService 的
 *   View/Stats/Bucket/Wrong 逐个键对过一遍**（对出来的三处差错：分母叫
 *   `questionCount` 不叫 `total`、Bucket 没有 `key`、Stats 补了 `courseId`）。
 *   字段名再对不上时仍然改这里一处，不要在页面里各自兜底 ——
 *   兜底散开之后，没人说得清哪个键是真有的。
 *
 * ★ **两层必须分开取**：`stats` 是代码算的事实层，**QUEUED/RUNNING/FAILED 时照样有**
 *   （规格第四节写死了这一条：状态没到 READY 也要把 stats 返回）；
 *   `content` 是模型写的叙述层，只有 READY 才有。合成一个可空对象的话，
 *   模型一挂学员连自己错在哪都看不到，而那正是这份规格开头要防的事。
 *
 * ★ 可空的一律 `?:` 而不是 `| null`：后端全局开了 `default-property-inclusion: non_null`，
 *   值为 null 时那个键在 JSON 里**整个消失**（同本文件 Overview 那一段）。
 */
export type ExamReport = {
  status: ExamReportStatus;
  stats?: ExamReportStats;
  /** 叙述层的 Markdown 正文。用 shared/src/Markdown.tsx 渲染，不装 Markdown 库。 */
  content?: string;
  /** 写这一份的模型 id。署名要如实写出来，学员才知道这段字是谁写的。 */
  model?: string;
  /** FAILED 才有，整句由后端给（「模型引用了不存在的时间点」这一类），前端一个字不改。 */
  failReason?: string;
  /** 缺席按 false 用：能不能重试是后端的闸门（仅 FAILED 且过了限频），前端不重算一遍。 */
  canRetry?: boolean;
};

/**
 * 正确率的一组：一讲、一个板块、一种题型、一档难度，四种分组共用这一种形状。
 *
 * `label` 是界面上写的名字，由后端给。前端**不另按什么 id 去猜中文名** ——
 * 猜的那一份和后端聚合用的那一份迟早分叉，分叉的表现是「第三章」这一条
 * 在屏幕上变成了「未知分组」，而没有任何地方报错。
 *
 * ★ **没有 key、也没有 lessonId**：后端的 Bucket 只有 `(label, correct, total)` 三样
 *   （ExamReportService.Bucket）。原来这里多声明了这两个必填/可选字段，
 *   而 JSON 里从来没有过 —— 拿 `row.key` 当 React key 的那一版，一整组的 key
 *   全是同一个 `"按讲次:undefined"`。**声明一个线上根本不下发的键，tsc 一个字都不会说**，
 *   坏法只会出现在屏幕上。所以这里只留后端真给的三个。
 *   界面上的 React key 用 `label`：后端的四组是 `LinkedHashMap<label, …>` 聚出来的，
 *   label 就是聚合键本身，组内天然唯一。
 */
export type ExamReportGroup = {
  label: string;
  correct: number;
  /** 这一组的题数（分母）。为 0 时界面不做除法 —— NaN 会渲染成「NaN%」。 */
  total: number;
};

/**
 * 错题清单的一条。
 *
 * ★ **没有「正确答案」这一列，这是接口契约的一部分、不是漏写。**
 *   六份卷共 400 题，题库要复用（同一份卷补考时还会出到同一批题），
 *   而报告是学员自己能反复打开、能截图转发的页面。
 *   哪天有人往这里加一个 `answer` 并在页面上渲染，加的那一刻题库就失效了。
 */
export type ExamReportWrong = {
  questionId: number;
  type?: 'SINGLE' | 'MULTI' | 'JUDGE';
  /** 题干摘要（后端截的），不是全文。 */
  stem: string;
  /**
   * 他选的选项键，多选是 "AB" 这样一串（见 Exam.tsx 里 setAnswer 的拼法）。
   * **没作答时这个键不出现** —— 空 = 没答，与「答错了」是两件事，界面上要分得开。
   */
  myAnswer?: string;
  /** 细粒度考点，逐题显示。50 题里有 37 个互不相同的考点，所以它不用来分组。 */
  knowledgePoint?: string;
  /** 粗粒度分组（课程卷=章名，AIGC 卷=板块名）。分组统计走 byTopic，这里只是逐题的归属。 */
  topic?: string;
  /** 1 扎实理解 / 2 需要实操 / 3 踩过坑才会。回填脚本没灌到的题是空。 */
  difficulty?: number;
  /**
   * 出处。题库里 `lesson_id` / `source_ts_sec` 都可空，所以这三个键可能一个都没有；
   * 缺席时界面整行不画，不写「暂无」。
   */
  lessonId?: number;
  lessonTitle?: string;
  sourceTsSec?: number;
};

/**
 * 事实层。**纯 SQL + Java 算得出来，不经过模型**，所以模型挂了它照常显示。
 *
 * ★ 四组正确率各自可能整组不下发：`topic`/`knowledge_point`/`difficulty` 三列是
 *   回填脚本灌的，没灌到的题聚不出组；开放卷的题也不挂讲次。
 *   界面对空组的处理是**整组不画**，不是画一个 0%。
 */
export type ExamReportStats = {
  attemptId: number;
  attemptNo?: number;
  paperId?: number;
  paperTitle?: string;
  /**
   * 开放卷不挂课程，这个键不出现。播放地址少了 `?course=` 仍然能播
   * （只是播放页右侧没有目录，见 Learn.tsx 顶上那段），所以出处链接照样给得出来。
   */
  courseId?: number;
  score: number;
  /**
   * 分母。**键名叫 `questionCount`，与后端 ExamReportService.Stats 一致**
   * （站内别处也是这个名字：Exam.tsx 的 `rules.questionCount`、管理端的卷面题数）。
   *
   * ★ 原来这里写的是 `total`，而后端从头到尾没有过这个键。后果不是某块不出现，
   *   是首屏最大的那个数字变成「31 / 」—— 分母整个消失，tsc 不报、页面不报错。
   *   改名这一下必须和后端同轮：**这一段类型现在是照实现写的，不是照规格写的。**
   *
   * ★ 声明成可选不是因为后端会漏发（那边是 `int`，一定有值），是为了让页面里
   *   「分母缺席就不画那半段」那道兜底被 tsc 钉住 —— 下一次谁再改键名，
   *   屏幕上要么是完整的「31 / 50」，要么只剩「31」，不留半截数字这种中间态。
   *
   * 取的是他实际被判分的题数（答题明细行数），不是 exam_config 的卷面题数：
   * 题被删过时两者会不一致，而学员数得出来的是前者。
   */
  questionCount?: number;
  passScore?: number;
  /** TINYINT(1) 过来可能是 true 也可能是 1，只按真假用，**别写 `=== true`**。 */
  passed?: boolean | number;
  submittedAt?: string;
  /** 按板块/章聚合（question.topic）。 */
  byTopic?: ExamReportGroup[];
  /** 按讲次聚合。 */
  byLesson?: ExamReportGroup[];
  /** 按题型聚合。 */
  byType?: ExamReportGroup[];
  /** 按难度聚合。 */
  byDifficulty?: ExamReportGroup[];
  /** 错题清单。**全对时是空数组，键缺席的语义是「没算出来」** —— 两者界面上分得开。 */
  wrong?: ExamReportWrong[];
};

/* ------------------------------------------------------------------ 证明 */

/** CertificateService.MyCert */
export type MyCert = {
  certNo: string;
  courseId: number;
  courseTitle: string;
  issuedAt: string;
  revokedAt: string | null;
  status: string;
};

/** GET /api/public/cert/verify/{no} */
export type CertVerify = {
  certNo: string;
  status: string;
  courseTitle: string | null;
  holder: string | null;
  issuedAt: string | null;
  revokedAt: string | null;
};

/* ------------------------------------------------------------------ 答疑 */

export type QaTicket = {
  id: number;
  courseId: number;
  lessonId: number | null;
  title: string;
  status: 'AI_ANSWERED' | 'ESCALATED' | 'HUMAN_ANSWERED' | 'CLOSED';
  escalateReason: string | null;
  slaDueAt: string | null;
  createdAt: string;
  updatedAt: string;
  messageCount: number;
};

export type QaMessage = {
  senderType: 'STUDENT' | 'AI' | 'MENTOR';
  content: string;
  citations: string | null;
  createdAt: string;
};

export type QaDetail = Omit<QaTicket, 'messageCount'> & {
  publishedFaqId: number | null;
  /**
   * 关联讲次的标题。**没有「第几讲」这个数，这是有意的。**
   *
   * lessonId 是主键（全库自增的行号），这一页曾经拿它当序号渲染成「关联第 12 讲」，
   * 而这门课一共只有 4 讲。换 sort_no 也不行：它是留了间隔的排序键（20/30/40/70）。
   * 这个 schema 里没有一列能当序号用，而标题本身就带编号（"L3 后端与多模态"）。
   * 讲次没关联、或那一讲已被删掉时是 null（后端显式给 null，不是省略）。
   */
  lessonTitle: string | null;
  messages: QaMessage[];
};

/* ------------------------------------------------------------------ 交易 */

/** OrderQueryService.view() */
export type OrderRow = {
  orderId: number;
  orderNo: string;
  buyerType: 'AGENT' | 'STUDENT';
  skuCode: string;
  skuName: string | null;
  codeQty: number;
  amountFen: number;
  amountYuan: string;
  paidAmountFen: number;
  refundedAmountFen: number;
  status: 'CREATED' | 'PAID' | 'FULFILLED' | 'CLOSED' | 'REFUNDED' | 'PARTIAL_REFUNDED';
  payChannel: string | null;
  paidAt: string | null;
  expireAt: string;
  createdAt: string;
};

/*
 * 这里原来有 InvoiceRow（/api/invoice/my）与 EligibleOrder（/api/invoice/eligible-orders）。
 * 2026-09-01 学员端开票整块删除后一起删掉：留着没人 import 的类型不会让 typecheck 红，
 * 只会让下一个人以为学员端还有这条路，照着它把界面接回去 ——
 * 而服务端那侧已经对 STUDENT 一律 403，接回去的是一个点了必然报错的按钮。
 * 代理侧的开票（同样这三条接口）仍在，见 InvoiceController。
 */

/** GET /api/public/skus?buyerType=STUDENT（PublicSkuController）。 */
export type Sku = {
  skuId: number;
  skuCode: string;
  name: string;
  courseId: number;
  courseTitle?: string | null;
  codeQty: number;
  priceFen: number;
};

/**
 * GET /api/account/devices 的一行。
 *
 * **没有 deviceId 这一列**：设备指纹是取播放密钥时要带的凭据之一，后端只回尾号
 * （`deviceTail`）。这里原本声明的是 `deviceId: string`，一个后端从来不发的字段 ——
 * 类型上写着有、运行时永远是 undefined。眼下没有页面渲染它，所以它只是一句谎话；
 * 等哪天有人照着这个类型写 `{d.deviceId}`，页面上会出现一个安静的空白。
 */
export type DeviceRow = {
  id: number;
  deviceTail: string;
  deviceLabel: string | null;
  status: 'ACTIVE' | 'REMOVED';
  boundAt: string;
  removedAt: string | null;
};

/* ------------------------------------------------------------------ 导览页 */

/**
 * GET /api/home/overview —— 学习中心导览页要的全部数据，一次请求。
 *
 * ★ 三片可空的**语义是「取不到」，不是「是零」**。后端某一片挂掉时返回 null，
 * 这里就必须是可空而不是给个默认空对象 —— 一旦在类型里抹平，
 * 界面上会写着"0 条等回复"，而真相是我们根本没问到。那句话比不说更糟：
 * 它让学员认为没有事要处理，于是不会再点进去。
 *
 * ★ 写成 `?:` 而不是 `| null`：后端全局开了 `default-property-inclusion: non_null`，
 * 一片取不到时那个键在 JSON 里**整个消失**，而不是出现一个 `null`。
 * 声明成 `| null` 的话类型上写着"一定有这个键"，`o.qa.total` 能过编译、运行时炸。
 */
export type Overview = {
  courses: OverviewCourses;
  assess?: OverviewAssess | null;
  qa?: OverviewQa | null;
  cert?: OverviewCert | null;
};

export type OverviewCourses = {
  total: number;
  active: number;
  expired: number;
  doneLessons: number;
  totalLessons: number;
  watchedSeconds: number;
  totalSeconds: number;
  /** 按秒加权的合计进度，已到期的课不进分母。 */
  progressPct: number;
  items: MyCourse[];
};

/**
 * 一份卷当前的测评状态。
 *
 * `UNAVAILABLE` 与 `BLOCKED` 是两回事：前者是这份卷压根没配规则（后端给的是一张
 * 只带原因的卡），后者是规则都在、你还不满足条件。给学员的下一步动作完全不同。
 *
 * `EXPIRED` 现在不会再出现：权益到期的卷仍然留在列表里、只是不可考（可见但不能考），
 * 后端把它归进 BLOCKED 并在 reason 里说清楚。留着这个值是因为它不花什么钱，
 * 而删掉它只能防住一件本来就不该靠类型防的事 —— 渲染侧另有兜底。
 */
export type AssessState = 'READY' | 'IN_PROGRESS' | 'PASSED' | 'BLOCKED' | 'EXPIRED' | 'UNAVAILABLE';

/**
 * 导览页测评区块的一条 = **一份试卷**，不是一门课（2026-09-03 多套试卷）。
 *
 * 字段照 HomeService.assessBlock 里 put 的那一串逐个抄。可空的一律写 `?:` ——
 * 后端全局 non_null，null 的键在 JSON 里整个消失（同本文件 Overview 那一段）。
 *
 * ★ `courseId` / `courseTitle` 对**开放卷**是不存在的（它不挂任何课程），
 * 原来把这两个声明成必填，于是 `it.courseTitle` 编译期全绿、屏幕上是一张无名空白卡，
 * `/exam?course=${it.courseId}` 更是渲染成了 `/exam?course=undefined`。
 */
export type OverviewAssessItem = {
  paperId: number;
  code: string;
  title: string;
  subtitle?: string | null;
  /** 开放卷没有课程，这两个键不出现。 */
  courseId?: number;
  courseTitle?: string;
  access: 'ENROLLED' | 'OPEN';
  /** TINYINT(1) 过来可能是 true 也可能是 1，只按真假用，别写 `=== true`。 */
  grantsCertificate: boolean | number;
  state: AssessState;
  /** 原因整句由后端给（就是开考闸门那一句），前端只负责显示，不许改写。能考的时候这个键不出现。 */
  reason?: string | null;
  /** 规则取不到、或这份卷不挂课程时，下面这几个数一律不出现。空 = 不知道，不是 0。 */
  progressPct?: number;
  requireProgressPct?: number;
  questionCount?: number;
  passScore?: number;
  durationMin?: number;
  attemptsLeft?: number;
  inProgressAttemptId?: number;
  passedAttemptId?: number;
};

/** 三个计数的单位是**份卷**，不是门课：一门课下面有五份卷。界面上的量词要跟着。 */
export type OverviewAssess = {
  readyCount: number;
  inProgressCount: number;
  passedCount: number;
  /** 卷太多时没逐份去问的份数。不为 0 时界面必须说出来，不能装作全算过了。 */
  skippedCount: number;
  items: OverviewAssessItem[];
};

/** 字段叫 humanAnswered 不叫 unread：库里没有已读标记，叫 unread 就是在说一件我们不知道的事。 */
export type OverviewQa = {
  total: number;
  waiting: number;
  humanAnswered: number;
  aiAnswered: number;
};

export type OverviewCert = { total: number; valid: number };
