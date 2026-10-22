import React from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';
import { ForcePasswordChange, Skeleton, onMustChangePassword } from '@wangke/shared';
import { RequireAuth, StudentShell } from './shell';
import Login from './pages/Login';
import Register from './pages/Register';
import Home from './pages/Home';
import MyCourses from './pages/MyCourses';
import Assess from './pages/Assess';
import Activate from './pages/Activate';
import CourseDetail from './pages/CourseDetail';
import Exam from './pages/Exam';
import ExamReport from './pages/ExamReport';
import Qa from './pages/Qa';
import QaDetailPage from './pages/QaDetail';
import Orders from './pages/Orders';
import Me from './pages/Me';

/**
 * 两个重家伙单独切出去，不进首屏那一包：
 *   - Learn 带 hls.js（几百 KB），只有真正开始看课时才需要
 *   - Cert 带 qrcode，只有领到结业证明的人才会打开
 *
 * 学员端第一次打开多半在移动网络上，首包每多 100KB 就多一段白屏，
 * 而白屏期他看到的是一个"打不开的网站"。
 */
const Learn = React.lazy(() => import('./pages/Learn'));
const Cert = React.lazy(() => import('./pages/Cert'));
const CertVerifyPage = React.lazy(() => import('./pages/CertVerify'));

/**
 * 路由。
 *
 * 公开与需登录两档分得很死：需登录的全部包在一个 RequireAuth 里，
 * 而不是每个页面自己判一次。自己判的写法迟早会漏掉一个页面，
 * 而漏掉的那个页面在未登录时是**空白**的 —— 请求全 401、组件全渲染空态，
 * 看起来像"你还没有课程"，而不是"你还没登录"。
 */
export default function App() {
  // 强制改密整屏接管。学员端此前对 must_change_pwd 完全没有处理 ——
  // 而管理员那个「重置密码」接口收的是任意 user id，学员一样会被置上这个标记。
  // 后端现在对 /api/account/** 以外一律 403，不接管的话，学员看到的是
  // 每一页都报「请先修改初始密码」，而全站没有一个地方能改密码。
  const [forced, setForced] = React.useState<string | null>(null);
  React.useEffect(() => onMustChangePassword(msg => setForced(msg)), []);
  if (forced) return <ForcePasswordChange brand="我的学习" hint={forced} />;

  return (
    <React.Suspense fallback={<PageFallback />}>
      <Routes>
        {/* 公开：登录、注册、结业证明验真（验真链接是发给别人看的，不能要求登录） */}
        <Route path="/login" element={<Login />} />
        <Route path="/register" element={<Register />} />
        <Route path="/verify/:certNo" element={<CertVerifyPage />} />

        <Route element={<RequireAuth><StudentShell /></RequireAuth>}>
          {/* 首页是一层导览，不直接是课程列表 —— 理由写在 pages/Home.tsx 顶上。
              旧书签落在 "/" 上的人会看到导览页，「继续上次」仍然在第一屏第一行。 */}
          <Route index element={<Home />} />
          <Route path="/courses" element={<MyCourses />} />
          <Route path="/assess" element={<Assess />} />
          <Route path="/activate" element={<Activate />} />
          <Route path="/course/:courseId" element={<CourseDetail />} />
          <Route path="/learn/:lessonId" element={<Learn />} />
          <Route path="/exam" element={<Exam />} />
          {/* 报告是【一场考试】的，不是一份卷的，所以场次编号走 query 而不是路径段：
              `/exam/report?attempt=12` 与测评页那套 `?paper=&course=&attempt=` 同形。
              写成 /exam/:attemptId/report 的话，这一格的 also 前缀（shell.tsx 里的
              '/exam'）照样亮，但两套地址形状并存，下一个人得先猜这一页归谁。 */}
          <Route path="/exam/report" element={<ExamReport />} />
          <Route path="/cert" element={<Cert />} />
          <Route path="/qa" element={<Qa />} />
          <Route path="/qa/:ticketId" element={<QaDetailPage />} />
          <Route path="/orders" element={<Orders />} />
          <Route path="/me" element={<Me />} />
        </Route>

        {/* 兜底回首页而不是渲染一个 404 页：学员端没有"深层内容页"，
            打错的地址几乎总是从旧链接来的，把他放回课程列表比给他一个死胡同好。 */}
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </React.Suspense>
  );
}

function PageFallback() {
  return (
    <div style={{ padding: 'var(--s5) var(--s4)', maxWidth: 720, margin: '0 auto',
                  display: 'grid', gap: 'var(--s4)' }}>
      <Skeleton h={28} w="40%" />
      <Skeleton h={180} />
      <Skeleton h={120} />
    </div>
  );
}
