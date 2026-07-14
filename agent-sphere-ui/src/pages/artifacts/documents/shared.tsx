import { MenuOutlined } from '@ant-design/icons';
import XMarkdown from '@ant-design/x-markdown';
import { useIntl, useParams } from '@umijs/max';
import { Button, Result, Spin, Typography } from 'antd';
import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import '@ant-design/x-markdown/es/XMarkdown/index.css';
import { useStyles } from './style';
import { type TocItemBase, TocPanel } from './TocPanel';

interface TocItem extends TocItemBase {
  id: string;
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

function extractText(children: any): string {
  if (typeof children === 'string') return children;
  if (Array.isArray(children)) return children.map(extractText).join('');
  if (children?.props?.children) return extractText(children.props.children);
  return '';
}

export default function SharedDocument() {
  const { shareToken } = useParams<{ shareToken: string }>();
  const { styles } = useStyles();
  const intl = useIntl();
  const [doc, setDoc] = useState<any>(null);
  const [loading, setLoading] = useState(true);
  const [tocOpen, setTocOpen] = useState(true);
  const containerRef = useRef<HTMLDivElement>(null);
  const [containerHeight, setContainerHeight] = useState('100vh');

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
  }, [doc]);

  useEffect(() => {
    if (!shareToken) return;
    setLoading(true);
    fetch(`/api/v1/artifacts/documents/shared/${shareToken}`)
      .then((r) => (r.ok ? r.json() : null))
      .then(setDoc)
      .catch(() => setDoc(null))
      .finally(() => setLoading(false));
  }, [shareToken]);

  const tocItems = useMemo(
    () => (doc?.content ? parseToc(doc.content) : []),
    [doc?.content],
  );

  const headingComponents = useMemo(
    () => ({
      h1: ({ children, ...props }: any) => {
        const text = extractText(children);
        const id = tocItems.find((t) => t.text === text)?.id || '';
        return (
          <h1 id={id} {...props}>
            {children}
          </h1>
        );
      },
      h2: ({ children, ...props }: any) => {
        const text = extractText(children);
        const id = tocItems.find((t) => t.text === text)?.id || '';
        return (
          <h2 id={id} {...props}>
            {children}
          </h2>
        );
      },
      h3: ({ children, ...props }: any) => {
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

  const jumpToHeading = (item: TocItem) => {
    const el = document.getElementById(item.id);
    if (!el) return;
    el.scrollIntoView({ behavior: 'smooth', block: 'center' });
    const orig = el.style.background;
    el.style.transition = 'background 1s';
    el.style.background = '#ffe58f';
    setTimeout(() => {
      el.style.background = 'transparent';
      setTimeout(() => {
        el.style.transition = '';
      }, 100);
    }, 1000);
  };

  if (loading) {
    return (
      <div className={styles.spinWrapper} style={{ height: '100vh' }}>
        <Spin size="large" />
      </div>
    );
  }

  if (!doc) {
    return (
      <div style={{ padding: 48 }}>
        <Result
          status="404"
          title={intl.formatMessage({
            id: 'pages.document.sharedDocNotFound',
            defaultMessage: 'Document not found or sharing has been cancelled',
          })}
        />
      </div>
    );
  }

  return (
    <div
      ref={containerRef}
      className={styles.containerDetail}
      style={{ height: containerHeight }}
    >
      <div className={styles.headerBarDetail}>
        <div style={{ flex: 1, minWidth: 0 }}>
          <Typography.Title level={3} style={{ margin: 0 }}>
            {doc.title || '-'}
          </Typography.Title>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {intl.formatMessage({
              id: 'pages.document.sharedDocCreated',
              defaultMessage: 'Created',
            })}
            : {doc.createdAt ? new Date(doc.createdAt).toLocaleString() : '-'}
          </Typography.Text>
        </div>
        <Button
          icon={<MenuOutlined />}
          type={tocOpen ? 'primary' : 'default'}
          size="small"
          onClick={() => setTocOpen(!tocOpen)}
        >
          {intl.formatMessage({
            id: 'pages.document.outline',
            defaultMessage: 'Outline',
          })}
        </Button>
      </div>
      <div className={styles.contentFlex}>
        <div className={`markdown-body ${styles.contentCard}`}>
          <div className={styles.markdownScroll}>
            <XMarkdown components={headingComponents}>
              {doc.content || ''}
            </XMarkdown>
          </div>
        </div>
        <TocPanel
          items={tocItems}
          onJump={jumpToHeading}
          visible={tocOpen}
          onClose={() => setTocOpen(false)}
        />
      </div>
    </div>
  );
}
