import { CopyOutlined } from '@ant-design/icons';
import { useIntl } from '@umijs/max';
import { Tooltip } from 'antd';

export default function MermaidBlock({ chart }: { chart: string }) {
  const intl = useIntl();
  return (
    <div style={{ position: 'relative' }}>
      <pre
        style={{
          background: '#f6f8fa',
          padding: 16,
          borderRadius: 8,
          fontSize: 13,
          lineHeight: 1.5,
          margin: '8px 0',
          whiteSpace: 'pre',
          overflowX: 'auto',
          overflowY: 'hidden',
          fontFamily:
            'ui-monospace,SFMono-Regular,SF Mono,Menlo,Consolas,monospace',
        }}
      >
        <code>{chart}</code>
      </pre>
      <Tooltip
        title={intl.formatMessage({
          id: 'chat.copied',
          defaultMessage: 'Copied',
        })}
      >
        <CopyOutlined
          style={{
            position: 'absolute',
            top: 8,
            right: 8,
            cursor: 'pointer',
            color: '#8c8c8c',
          }}
          onClick={() => navigator.clipboard.writeText(chart)}
        />
      </Tooltip>
    </div>
  );
}
