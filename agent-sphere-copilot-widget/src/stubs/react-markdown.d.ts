import type { ReactNode } from 'react';

/**
 * react-markdown 的类型 shim（仅 tsc 用）：
 * react-markdown v8 内部类型引用已移除的全局 JSX 命名空间（React 19），
 * 与 widget 严格 tsconfig 冲突。通过 tsconfig paths 把 'react-markdown' 的类型
 * 解析到这里，运行时仍由 vite resolve.alias/真实包处理（bundle 不变）。
 */
declare const ReactMarkdown: (props: {
  children?: string;
  className?: string;
  [key: string]: unknown;
}) => ReactNode;

export default ReactMarkdown;
