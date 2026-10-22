/// <reference types="vite/client" />

/** vite 允许把 css 当副作用 import，TS 不认识这种模块，这里给它一个声明。 */
declare module '*.css';
