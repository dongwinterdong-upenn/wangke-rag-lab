/**
 * 登录 / 注册页的外壳。
 *
 * 这两页不进 StudentShell：底部标签栏在这里是纯噪声（没登录的人点哪个都会被弹回来），
 * 而顶栏的"退出"按钮更是荒谬。它们要的是一屏之内只有一件事可做。
 *
 * 版面本身交给 shared 的 AuthFrame —— 三端共用同一张登录版面，
 * 理由写在那个文件顶部：这是平台里唯一会被同一个人并排比较的界面。
 * 这里只负责填学员端自己的那几句话。
 */
import React from 'react';
import { AuthFrame } from '@wangke/shared';

export function AuthLayout({ title, desc, children, footer }: {
  title: string;
  desc?: React.ReactNode;
  children: React.ReactNode;
  footer?: React.ReactNode;
}) {
  return (
    <AuthFrame
      brand="学习中心"
      kicker="STUDENT · 学员端"
      headline="课在这里，随时接着上一次看"
      lede="进度按讲次记，换台设备也接得上。课程有效期、答疑、结业证明都在同一个账号下。"
      points={[
        '手机、平板、电脑都能看，进度自动同步',
        '有问题在答疑区提，讲师和助教都看得到',
        '学完拿结业证明，带可验真的编号',
      ]}
      title={title}
      desc={desc}
      footer={footer}
    >
      {children}
    </AuthFrame>
  );
}
