import XMarkdown from '@ant-design/x-markdown';
import '@ant-design/x-markdown/es/XMarkdown/index.css';
import { useIntl, useParams } from '@umijs/max';
import { Result, Spin, Typography } from 'antd';
import { useEffect, useMemo, useState } from 'react';

interface SharedRun {
  runId: number;
  userMessage?: string;
  assistantReply?: string;
  createdAt?: string;
}

export default function SharedChat() {
  const { shareToken } = useParams<{ shareToken: string }>();
  const intl = useIntl();
  const [data, setData] = useState<{ title?: string; runs?: SharedRun[] } | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!shareToken) return;
    setLoading(true);
    fetch(`/api/v1/public/shares/${shareToken}`)
      .then((r) => (r.ok ? r.json() : null))
      .then(setData)
      .catch(() => setData(null))
      .finally(() => setLoading(false));
  }, [shareToken]);

  const runs = useMemo(() => data?.runs || [], [data]);

  if (loading) {
    return (
      <div style={{ minHeight: '100vh', display: 'grid', placeItems: 'center' }}>
        <Spin size="large" />
      </div>
    );
  }

  if (!data) {
    return (
      <div style={{ padding: 24 }}>
        <Result status="404" title="分享不存在或已失效" />
      </div>
    );
  }

  return (
    <div
      style={{
        minHeight: '100vh',
        background: '#f7f8fa',
        display: 'flex',
        flexDirection: 'column',
      }}
    >
      <header
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 10,
          padding: '14px 18px',
          background: '#fff',
          borderBottom: '1px solid #eee',
        }}
      >
        <img src="/logo.svg" alt="AgentSphere" style={{ width: 28, height: 28 }} />
        <strong style={{ fontSize: 16 }}>AgentSphere</strong>
      </header>

      <main style={{ flex: 1, width: '100%', maxWidth: 860, margin: '0 auto', padding: '20px 14px 100px' }}>
        <Typography.Title level={3} style={{ textAlign: 'center', marginTop: 0 }}>
          {intl.formatMessage({
            id: 'pages.share.title',
            defaultMessage: '来自 AgentSphere 的分享',
          })}
        </Typography.Title>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
          {runs.map((run) => (
            <div key={run.runId}>
              {run.userMessage ? (
                <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
                  <div
                    style={{
                      maxWidth: '85%',
                      background: '#e6f7ea',
                      borderRadius: 12,
                      padding: '9px 12px',
                      whiteSpace: 'pre-wrap',
                    }}
                  >
                    {run.userMessage}
                  </div>
                </div>
              ) : null}
              {run.assistantReply ? (
                <div
                  style={{
                    marginTop: 8,
                    maxWidth: '92%',
                    background: '#fff',
                    borderRadius: 12,
                    padding: '10px 12px',
                    boxShadow: '0 1px 4px rgba(0,0,0,.04)',
                  }}
                >
                  <XMarkdown content={run.assistantReply} />
                </div>
              ) : null}
            </div>
          ))}
        </div>
      </main>

      <button
        type="button"
        onClick={() => {
          const root = new URL(window.location.href).origin;
          window.location.href = root;
        }}
        style={{
          position: 'fixed',
          left: '50%',
          bottom: 20,
          transform: 'translateX(-50%)',
          border: 'none',
          borderRadius: 999,
          padding: '10px 20px',
          background: '#1677ff',
          color: '#fff',
          fontSize: 14,
          boxShadow: '0 6px 20px rgba(22,119,255,.3)',
          cursor: 'pointer',
          whiteSpace: 'nowrap',
        }}
      >
        {intl.formatMessage({
          id: 'pages.share.continue',
          defaultMessage: '和 AgentSphere 继续聊',
        })}
      </button>
    </div>
  );
}
