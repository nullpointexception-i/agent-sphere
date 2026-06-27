import { PageContainer, ProTable } from '@ant-design/pro-components';
import { useIntl, history } from '@umijs/max';
import { useEffect, useState } from 'react';
import { agentApi } from '@/services/agentSphere/api';

export default function DocumentList() {
  const intl = useIntl();
  const locale = intl.locale;
  const [tableScrollY, setTableScrollY] = useState(400);

  useEffect(() => {
    const calc = () => setTableScrollY(window.innerHeight - 280);
    calc();
    window.addEventListener('resize', calc);
    return () => window.removeEventListener('resize', calc);
  }, []);

  const columns = [
    {
      title: intl.formatMessage({ id: 'pages.document.title', defaultMessage: 'Title' }),
      dataIndex: 'title',
      key: 'title',
      width: 200,
      render: (_: any, record: any) => (
        <a onClick={() => history.push(`/artifacts/documents/${record.id}`)}>
          {record.title || '-'}
        </a>
      ),
    },
    {
      title: intl.formatMessage({ id: 'pages.document.preview', defaultMessage: 'Preview' }),
      dataIndex: 'content',
      key: 'content',
      ellipsis: true,
      render: (_: any, record: any) => {
        const text = (record.content || '').replace(/[#*`\n\r]+/g, ' ').trim();
        return text.length > 100 ? text.slice(0, 100) + '…' : text || '-';
      },
    },
    {
      title: intl.formatMessage({ id: 'pages.document.session', defaultMessage: 'Session' }),
      dataIndex: 'sessionId',
      key: 'sessionId',
      width: 100,
    },
    {
      title: intl.formatMessage({ id: 'pages.document.createdAt', defaultMessage: 'Created At' }),
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 180,
      render: (_: any, record: any) => record.createdAt ? new Date(record.createdAt).toLocaleString(locale === 'en-US' ? 'en-US' : 'zh-CN') : '-',
    },
    {
      title: intl.formatMessage({ id: 'pages.table.actions', defaultMessage: 'Actions' }),
      key: 'actions',
      width: 80,
      render: (_: any, record: any) => (
        <a onClick={() => history.push(`/artifacts/documents/${record.id}`)}>
          {intl.formatMessage({ id: 'pages.chat.detail', defaultMessage: 'View' })}
        </a>
      ),
    },
  ];

  return (
    <PageContainer title={false} breadcrumbRender={false}>
      <ProTable
        rowKey="id"
        search={false}
        options={false}
        scroll={{ y: tableScrollY }}
        pagination={{ pageSize: 20, showSizeChanger: true }}
        request={async (params: any) => {
          const { current, pageSize } = params;
          const res = await agentApi.artifacts.documents.list({ page: current, size: pageSize });
          return { data: res?.records || res || [], total: res?.total || 0, success: true };
        }}
        columns={columns}
      />
    </PageContainer>
  );
}
