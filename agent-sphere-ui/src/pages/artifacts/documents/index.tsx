import { PageContainer, ProTable } from '@ant-design/pro-components';
import { useIntl, history } from '@umijs/max';
import { useEffect, useState } from 'react';
import { Modal, Descriptions, Tag, Space } from 'antd';
import { EditOutlined, EyeOutlined, FileTextOutlined } from '@ant-design/icons';
import { agentApi } from '@/services/agentSphere/api';

export default function DocumentList() {
  const intl = useIntl();
  const locale = intl.locale;
  const [tableScrollY, setTableScrollY] = useState(400);
  const [detailModal, setDetailModal] = useState<{ open: boolean; doc: any }>({ open: false, doc: null });

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
      render: (_: any, record: any) => record.title || '-',
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
      width: 140,
      render: (_: any, record: any) => (
        <Space>
          <a onClick={() => setDetailModal({ open: true, doc: record })}>
            <EyeOutlined /> {intl.formatMessage({ id: 'pages.document.viewDetail', defaultMessage: 'View' })}
          </a>
          <a onClick={() => history.push(`/artifacts/documents/${record.id}`)}>
            <FileTextOutlined /> {intl.formatMessage({ id: 'pages.document.previewContent', defaultMessage: 'Preview' })}
          </a>
          <a onClick={() => history.push(`/artifacts/documents/${record.id}/edit`)}>
            <EditOutlined /> {intl.formatMessage({ id: 'pages.document.edit', defaultMessage: 'Edit' })}
          </a>
        </Space>
      ),
    },
  ];

  const summaryText = (content: string) => {
    const text = (content || '').replace(/[#*`\n\r]+/g, ' ').trim();
    return text.length > 200 ? text.slice(0, 200) + '…' : text || '-';
  };

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

      <Modal
        title={detailModal.doc?.title || '-'}
        open={detailModal.open}
        footer={null}
        width={600}
        onCancel={() => setDetailModal({ open: false, doc: null })}
      >
        {detailModal.doc && (
          <Descriptions column={1} bordered size="small" style={{ marginTop: 16 }}>
            <Descriptions.Item label={intl.formatMessage({ id: 'pages.document.summary', defaultMessage: 'Summary' })}>
              {summaryText(detailModal.doc.content)}
            </Descriptions.Item>
            <Descriptions.Item label={intl.formatMessage({ id: 'pages.document.contentType', defaultMessage: 'Content Type' })}>
              <Tag>{detailModal.doc.contentType || 'markdown'}</Tag>
            </Descriptions.Item>
            <Descriptions.Item label={intl.formatMessage({ id: 'pages.document.session', defaultMessage: 'Session' })}>
              {detailModal.doc.sessionId || '-'}
            </Descriptions.Item>
            <Descriptions.Item label={intl.formatMessage({ id: 'pages.document.createdAt', defaultMessage: 'Created At' })}>
              {detailModal.doc.createdAt ? new Date(detailModal.doc.createdAt).toLocaleString(locale === 'en-US' ? 'en-US' : 'zh-CN') : '-'}
            </Descriptions.Item>
            <Descriptions.Item label={intl.formatMessage({ id: 'pages.document.updatedAt', defaultMessage: 'Updated At' })}>
              {detailModal.doc.updatedAt ? new Date(detailModal.doc.updatedAt).toLocaleString(locale === 'en-US' ? 'en-US' : 'zh-CN') : '-'}
            </Descriptions.Item>
          </Descriptions>
        )}
      </Modal>
    </PageContainer>
  );
}
