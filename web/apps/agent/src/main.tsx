import React from 'react';
import { createRoot } from 'react-dom/client';
import '@wangke/shared/tokens.css';
import App from './App';

// 后台密度在这里先定一次：登录页不走 AppShell，不设的话它会用学员端的疏朗密度，
// 登录完跳进来字号忽然一变，像换了个网站。
document.documentElement.setAttribute('data-density', 'compact');

createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
