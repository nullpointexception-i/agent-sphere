import { ArrowLeftOutlined, MenuOutlined } from '@ant-design/icons';
import XMarkdown from '@ant-design/x-markdown';
import { history, useIntl, useParams } from '@umijs/max';
import { Button, Spin, Typography } from 'antd';
import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import '@ant-design/x-markdown/es/XMarkdown/index.css';
import { agentApi } from '@/services/agentSphere/api';
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

export default function DocumentDetail() {
  const { styles } = useStyles();
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

  const jumpToHeading = (item: TocItem) => {
    const el = document.getElementById(item.id);
    if (el) el.scrollIntoView({ behavior: 'smooth', block: 'center' });
  };

  return (
    <div
      ref={containerRef}
      className={styles.containerDetail}
      style={{ height: containerHeight }}
    >
      {loading ? (
        <div className={styles.spinWrapper}>
          <Spin size="large" />
        </div>
      ) : !doc ? (
        <div className={styles.notFound}>
          <Typography.Text type="secondary">
            {intl.formatMessage({
              id: 'pages.document.notFound',
              defaultMessage: 'Document not found',
            })}
          </Typography.Text>
        </div>
      ) : (
        <>
          <div className={styles.headerBarDetail}>
            <Button
              type="link"
              icon={<ArrowLeftOutlined />}
              onClick={() => history.push('/artifacts/documents')}
              className={styles.backBtn}
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
              {intl.formatMessage({ id: 'pages.document.outline', defaultMessage: 'Outline' })}
            </Button>
          </div>

          <div className={styles.contentFlex}>
            <div className={styles.contentCard}>
              <div className={styles.metaBar}>
                <Typography.Title level={3}>
                  {doc.title || '-'}
                </Typography.Title>
                <div className={styles.metaInfo}>
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
                    id: 'pages.document.updatedAt',
                    defaultMessage: 'Updated',
                  })}
                  :{' '}
                  {doc.updatedAt
                    ? new Date(doc.updatedAt).toLocaleString(
                        locale === 'en-US' ? 'en-US' : 'zh-CN',
                      )
                    : '-'}
                  <br />
                  {intl.formatMessage({
                    id: 'pages.table.createdBy',
                    defaultMessage: 'Created By',
                  })}
                  : {doc.createdBy || '-'}
                  &nbsp;|&nbsp;
                  {intl.formatMessage({
                    id: 'pages.table.updatedBy',
                    defaultMessage: 'Updated By',
                  })}
                  : {doc.updatedBy || '-'}
                  &nbsp;|&nbsp;
                  {intl.formatMessage({
                    id: 'pages.document.session',
                    defaultMessage: 'Session',
                  })}
                  : {doc.sessionId || '-'}
                </div>
              </div>
              <div className={`markdown-body ${styles.markdownScroll}`}>
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
