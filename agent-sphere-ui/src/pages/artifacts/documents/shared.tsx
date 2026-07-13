import XMarkdown from '@ant-design/x-markdown';
import { useIntl, useParams } from '@umijs/max';
import { Result, Spin, Typography } from 'antd';
import { useEffect, useState } from 'react';
import '@ant-design/x-markdown/es/XMarkdown/index.css';
import { useStyles } from './style';

export default function SharedDocument() {
  const { shareToken } = useParams<{ shareToken: string }>();
  const { styles } = useStyles();
  const intl = useIntl();
  const [doc, setDoc] = useState<any>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!shareToken) return;
    setLoading(true);
    fetch(`/api/v1/artifacts/documents/shared/${shareToken}`)
      .then((r) => (r.ok ? r.json() : null))
      .then(setDoc)
      .catch(() => setDoc(null))
      .finally(() => setLoading(false));
  }, [shareToken]);

  if (loading) {
    return (
      <div
        style={{
          display: 'flex',
          justifyContent: 'center',
          alignItems: 'center',
          height: '100vh',
        }}
      >
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
    <div className={styles.sharedContainer}>
      <Typography.Title style={{ marginBottom: 8 }}>
        {doc.title || '-'}
      </Typography.Title>
      <Typography.Text
        type="secondary"
        style={{ fontSize: 12, display: 'block', marginBottom: 24 }}
      >
        {intl.formatMessage({
          id: 'pages.document.sharedDocCreated',
          defaultMessage: 'Created',
        })}
        : {doc.createdAt ? new Date(doc.createdAt).toLocaleString() : '-'}
      </Typography.Text>
      <div className="markdown-body">
        <XMarkdown>{doc.content || ''}</XMarkdown>
      </div>
    </div>
  );
}
