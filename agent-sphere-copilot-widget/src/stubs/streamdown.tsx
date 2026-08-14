import ReactMarkdown from 'react-markdown';
import type { ReactNode } from 'react';

/**
 * streamdown 轻量 stub：剔除 shiki（代码高亮全语言/全主题）与 mermaid（图表）全家桶
 * （约 16MB rendered，16MB 包的大头），用已打包的 react-markdown 做基本排版，
 * 不加高亮/图表插件。兼容 react-core 的两处用法：
 *   <Streamdown content={...} className={...} {...props} />
 *   <Streamdown>{stringChildren}</Streamdown>
 */
export function Streamdown(
  props: { content?: string; children?: ReactNode; className?: string } & Record<string, unknown>,
) {
  const { content, children, className } = props;
  const text =
    typeof content === 'string' ? content : typeof children === 'string' ? children : '';
  return (
    <div className={className}>
      <ReactMarkdown>{text}</ReactMarkdown>
    </div>
  );
}
