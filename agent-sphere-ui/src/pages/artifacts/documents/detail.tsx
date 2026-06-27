import { useParams, history, useIntl } from '@umijs/max';
import { useEffect, useState } from 'react';
import { Spin, Button, Card, Typography } from 'antd';
import { ArrowLeftOutlined } from '@ant-design/icons';
import XMarkdown from '@ant-design/x-markdown';
import '@ant-design/x-markdown/es/XMarkdown/index.css';
import { agentApi } from '@/services/agentSphere/api';

export default function DocumentDetail() {
  const { id } = useParams<{ id: string }>();
  const intl = useIntl();
  const locale = intl.locale;
  const [doc, setDoc] = useState<any>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!id) return;
    setLoading(true);
    agentApi.artifacts.documents.getById(Number(id))
      .then(setDoc)
      .catch(() => setDoc(null))
      .finally(() => setLoading(false));
  }, [id]);

  if (loading) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '60vh' }}>
        <Spin size="large" />
      </div>
    );
  }

  if (!doc) {
    return (
      <div style={{ padding: 32 }}>
        <Typography.Text type="secondary">
          {intl.formatMessage({ id: 'pages.document.notFound', defaultMessage: 'Document not found' })}
        </Typography.Text>
      </div>
    );
  }

  return (
    <div style={{ padding: '24px 32px', maxWidth: 900, margin: '0 auto' }}>
      <Button
        type="link"
        icon={<ArrowLeftOutlined />}
        onClick={() => history.push('/artifacts/documents')}
        style={{ padding: 0, marginBottom: 16 }}
      >
        {intl.formatMessage({ id: 'pages.document.back', defaultMessage: 'Back' })}
      </Button>

      <Card>
        <Typography.Title level={3}>{doc.title || '-'}</Typography.Title>
        <div style={{ fontSize: 12, color: '#999', marginBottom: 16 }}>
          {intl.formatMessage({ id: 'pages.document.createdAt', defaultMessage: 'Created' })}:{' '}
          {doc.createdAt ? new Date(doc.createdAt).toLocaleString(locale === 'en-US' ? 'en-US' : 'zh-CN') : '-'}
          &nbsp;|&nbsp;
          {intl.formatMessage({ id: 'pages.document.session', defaultMessage: 'Session' })}:{' '}
          {doc.sessionId || '-'}
        </div>
        <div className="markdown-body" style={{ borderTop: '1px solid #e8e8e8', paddingTop: 16 }}>
          <XMarkdown>{doc.content || ''}</XMarkdown>
        </div>
      </Card>
    </div>
  );
}
