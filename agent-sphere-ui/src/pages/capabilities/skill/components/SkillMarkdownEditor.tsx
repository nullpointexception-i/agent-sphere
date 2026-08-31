import { EditOutlined, EyeOutlined } from '@ant-design/icons';
import XMarkdown from '@ant-design/x-markdown';
import '@ant-design/x-markdown/es/XMarkdown/index.css';
import { Segmented } from 'antd';
import { useState } from 'react';

interface Props {
  value?: string;
  onChange?: (value: string) => void;
  placeholder?: string;
}

/** Skill promptTemplate 的 Markdown 编辑器（等宽源码编辑 + XMarkdown 全量预览）。 */
export default function SkillMarkdownEditor({
  value = '',
  onChange,
  placeholder = '输入任务指令（支持 Markdown）…',
}: Props) {
  const [mode, setMode] = useState<'edit' | 'preview'>('edit');

  return (
    <div
      style={{
        border: '1px solid #d9d9d9',
        borderRadius: 8,
        overflow: 'hidden',
      }}
    >
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 4,
          padding: '4px 8px',
          borderBottom: '1px solid #f0f0f0',
        }}
      >
        <span style={{ fontSize: 12, color: '#8c8c8c' }}>
          {'支持 Markdown 语法（标题、引用、列表、代码块、表格等）'}
        </span>
        <div style={{ marginLeft: 'auto' }}>
          <Segmented
            size="small"
            value={mode}
            onChange={(v) => setMode(v as 'edit' | 'preview')}
            options={[
              { value: 'edit', label: <EditOutlined /> },
              { value: 'preview', label: <EyeOutlined /> },
            ]}
          />
        </div>
      </div>
      {mode === 'edit' ? (
        <textarea
          className="aw-skill-md-textarea"
          value={value}
          placeholder={placeholder}
          onChange={(e) => onChange?.(e.target.value)}
          style={{
            width: '100%',
            minHeight: 320,
            border: 'none',
            outline: 'none',
            resize: 'vertical',
            padding: '10px 12px',
            fontFamily:
              '"SFMono-Regular", Consolas, "Liberation Mono", Menlo, monospace',
            fontSize: 13,
            lineHeight: 1.7,
            color: '#333',
            background: '#fbfbf9',
            boxSizing: 'border-box',
          }}
        />
      ) : (
        <div
          style={{
            minHeight: 260,
            padding: '12px 16px',
            overflow: 'auto',
            background: '#fff',
          }}
        >
          <XMarkdown content={value || '（空）'} />
        </div>
      )}
    </div>
  );
}
