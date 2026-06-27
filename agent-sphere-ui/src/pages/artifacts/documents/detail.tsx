import { ArrowLeftOutlined, MenuOutlined } from '@ant-design/icons';
import XMarkdown from '@ant-design/x-markdown';
import { history, useIntl, useParams } from '@umijs/max';
import { Button, Spin, Typography } from 'antd';
import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import '@ant-design/x-markdown/es/XMarkdown/index.css';
import { agentApi } from '@/services/agentSphere/api';

interface TocItem {
  level: number;
  text: string;
  id: string;
}

function TocPanel({
  items,
  onJump,
  visible,
  onClose,
}: {
  items: TocItem[];
  onJump: (id: string) => void;
  visible: boolean;
  onClose: () => void;
}) {
  if (!visible) return null;
  return (
    <div
      style={{
        width: 200,
        flexShrink: 0,
        borderLeft: '1px solid #e8e8e8',
        padding: '12px 0',
        overflowY: 'auto',
        maxHeight: 'calc(100vh - 280px)',
        position: 'sticky',
        top: 16,
        alignSelf: 'flex-start',
        background: '#fafafa',
      }}
    >
      <div
        style={{
          padding: '0 12px 8px',
          fontSize: 12,
          color: '#999',
          display: 'flex',
          justifyContent: 'space-between',
        }}
      >
        <span>Outline</span>
        <a onClick={onClose} style={{ cursor: 'pointer' }}>
          ✕
        </a>
      </div>
      {items.length === 0 && (
        <div style={{ padding: '0 12px', fontSize: 12, color: '#ccc' }}>
          No headings
        </div>
      )}
      {items.map((item, i) => (
        <div
          key={i}
          onClick={() => onJump(item.id)}
          style={{
            padding: '4px 12px 4px ' + (12 + (item.level - 1) * 16) + 'px',
            fontSize: 13,
            cursor: 'pointer',
            color: '#333',
            lineHeight: 1.6,
            borderLeft: item.level === 1 ? '2px solid #1677ff' : undefined,
          }}
          onMouseEnter={(e) => {
            (e.target as HTMLElement).style.background = '#e6f4ff';
          }}
          onMouseLeave={(e) => {
            (e.target as HTMLElement).style.background = '';
          }}
        >
          {item.text}
        </div>
      ))}
    </div>
  );
}

function parseToc(content: string): TocItem[] {
  const items: TocItem[] = [];
  const lines = content.split('\n');
  let counter = 0;
  for (const line of lines) {
    const match = line.match(/^(#{1,3})\s+(.+)$/);
    if (match) {
      const text = match[2].trim();
      const id =
        'h-' +
        counter++ +
        '-' +
        text.replace(/[^a-zA-Z0-9\u4e00-\u9fff]+/g, '-').replace(/^-|-$/g, '');
      items.push({ level: match[1].length, text, id });
    }
  }
  return items;
}

export default function DocumentDetail() {
  const { id } = useParams<{ id: string }>();
  const intl = useIntl();
  const locale = intl.locale;
  const [doc, setDoc] = useState<any>(null);
  const [loading, setLoading] = useState(true);
  const [tocOpen, setTocOpen] = useState(true);
  const [containerHeight, setContainerHeight] = useState('100vh');
  const containerRef = useRef<HTMLDivElement>(null);

  useLayoutEffect(() => {
    const el = containerRef.current;
    if (!el) return;
    const measure = () => {
      const rect = el.getBoundingClientRect();
      setContainerHeight(`${window.innerHeight - rect.top}px`);
    };
    measure();
    window.addEventListener('resize', measure);
    return () => window.removeEventListener('resize', measure);
  }, []);

  useEffect(() => {
    if (!id) return;
    setLoading(true);
    agentApi.artifacts.documents
      .getById(Number(id))
      .then(setDoc)
      .catch(() => setDoc(null))
      .finally(() => setLoading(false));
  }, [id]);

  const tocItems = useMemo(
    () => (doc?.content ? parseToc(doc.content) : []),
    [doc?.content],
  );

  const headingComponents = useMemo(
    () => ({
      h1: ({ children, domNode, streamStatus, ...props }: any) => {
        const text = extractText(children);
        const id = tocItems.find((t) => t.text === text)?.id || '';
        return (
          <h1 id={id} {...props}>
            {children}
          </h1>
        );
      },
      h2: ({ children, domNode, streamStatus, ...props }: any) => {
        const text = extractText(children);
        const id = tocItems.find((t) => t.text === text)?.id || '';
        return (
          <h2 id={id} {...props}>
            {children}
          </h2>
        );
      },
      h3: ({ children, domNode, streamStatus, ...props }: any) => {
        const text = extractText(children);
        const id = tocItems.find((t) => t.text === text)?.id || '';
        return (
          <h3 id={id} {...props}>
            {children}
          </h3>
        );
      },
    }),
    [tocItems],
  );

  const jumpToHeading = (id: string) => {
    const el = document.getElementById(id);
    if (el) el.scrollIntoView({ behavior: 'smooth', block: 'center' });
  };

  return (
    <div
      ref={containerRef}
      style={{
        height: containerHeight,
        overflow: 'hidden',
        display: 'flex',
        flexDirection: 'column',
        maxWidth: 1200,
        margin: '0 auto',
        padding: '0 32px 16px',
      }}
    >
      {loading ? (
        <div
          style={{
            flex: 1,
            display: 'flex',
            justifyContent: 'center',
            alignItems: 'center',
          }}
        >
          <Spin size="large" />
        </div>
      ) : !doc ? (
        <div style={{ padding: 32 }}>
          <Typography.Text type="secondary">
            {intl.formatMessage({
              id: 'pages.document.notFound',
              defaultMessage: 'Document not found',
            })}
          </Typography.Text>
        </div>
      ) : (
        <>
          <div
            style={{
              display: 'flex',
              justifyContent: 'space-between',
              alignItems: 'center',
              paddingTop: 24,
              paddingBottom: 12,
            }}
          >
            <Button
              type="link"
              icon={<ArrowLeftOutlined />}
              onClick={() => history.push('/artifacts/documents')}
              style={{ padding: 0 }}
            >
              {intl.formatMessage({
                id: 'pages.document.back',
                defaultMessage: 'Back',
              })}
            </Button>
            <Button
              icon={<MenuOutlined />}
              type={tocOpen ? 'primary' : 'default'}
              size="small"
              onClick={() => setTocOpen(!tocOpen)}
            >
              Outline
            </Button>
          </div>

          <div style={{ display: 'flex', gap: 0, flex: 1, overflow: 'hidden' }}>
            <div
              style={{
                flex: 1,
                minWidth: 0,
                display: 'flex',
                flexDirection: 'column',
                overflow: 'hidden',
                padding: 24,
                background: '#fff',
                borderRadius: 8,
                border: '1px solid #e8e8e8',
              }}
            >
              <div style={{ flexShrink: 0 }}>
                <Typography.Title level={3}>
                  {doc.title || '-'}
                </Typography.Title>
                <div style={{ fontSize: 12, color: '#999', marginBottom: 16 }}>
                  {intl.formatMessage({
                    id: 'pages.document.createdAt',
                    defaultMessage: 'Created',
                  })}
                  :{' '}
                  {doc.createdAt
                    ? new Date(doc.createdAt).toLocaleString(
                        locale === 'en-US' ? 'en-US' : 'zh-CN',
                      )
                    : '-'}
                  &nbsp;|&nbsp;
                  {intl.formatMessage({
                    id: 'pages.document.session',
                    defaultMessage: 'Session',
                  })}
                  : {doc.sessionId || '-'}
                </div>
              </div>
              <div
                className="markdown-body"
                style={{
                  flex: 1,
                  overflowY: 'auto',
                  overscrollBehavior: 'contain',
                  minHeight: 0,
                  borderTop: '1px solid #e8e8e8',
                  paddingTop: 16,
                }}
              >
                <XMarkdown components={headingComponents}>
                  {doc.content || ''}
                </XMarkdown>
              </div>
            </div>
            {tocOpen && (
              <TocPanel
                items={tocItems}
                onJump={jumpToHeading}
                visible
                onClose={() => setTocOpen(false)}
              />
            )}
          </div>
        </>
      )}
    </div>
  );
}

function extractText(children: any): string {
  if (typeof children === 'string') return children;
  if (Array.isArray(children)) return children.map(extractText).join('');
  if (children?.props?.children) return extractText(children.props.children);
  return '';
}
