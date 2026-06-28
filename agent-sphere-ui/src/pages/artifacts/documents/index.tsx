import { PageContainer, ProTable } from '@ant-design/pro-components';
import { useIntl, history } from '@umijs/max';
import { useEffect, useRef, useState } from 'react';
import { Modal, Descriptions, Tag, Button, message } from 'antd';
import { DeleteOutlined, EditOutlined, EyeOutlined, FileTextOutlined } from '@ant-design/icons';
import { agentApi } from '@/services/agentSphere/api';

export default function DocumentList() {
  const intl = useIntl();
  const locale = intl.locale;
  const [tableScrollY, setTableScrollY] = useState(400);
  const [detailModal, setDetailModal] = useState<{ open: boolean; doc: any }>({ open: false, doc: null });
  const actionRef = useRef<any>(null);

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
      width: 200,
      render: (_: any, record: any) => (
        <>
          <Button type="link" size="small" icon={<EyeOutlined />} onClick={() => setDetailModal({ open: true, doc: record })} />
          <Button type="link" size="small" icon={<FileTextOutlined />} onClick={() => history.push(`/artifacts/documents/${record.id}`)} />
          <Button type="link" size="small" icon={<EditOutlined />} onClick={() => history.push(`/artifacts/documents/${record.id}/edit`)} />
          <Button
            type="link"
            danger
            size="small"
            icon={<DeleteOutlined />}
            onClick={() => {
              Modal.confirm({
                title: intl.formatMessage({ id: 'pages.deleteConfirm.title', defaultMessage: 'Delete {name}' }, { name: 'document' }),
                content: intl.formatMessage({ id: 'pages.deleteConfirm.content', defaultMessage: 'Are you sure you want to delete this {name}?' }, { name: 'document' }),
                okType: 'danger',
                onOk: async () => {
                  await agentApi.artifacts.documents.delete(record.id);
                  message.success(intl.formatMessage({ id: 'pages.document.deleted', defaultMessage: 'Deleted' }));
                  actionRef.current?.reload();
                },
              });
            }}
          />
        </>
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
        actionRef={actionRef}
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
            <Descriptions.Item label={intl.formatMessage({ id: 'pages.table.createdBy', defaultMessage: 'Created By' })}>
              {detailModal.doc.createdBy || '-'}
            </Descriptions.Item>
            <Descriptions.Item label={intl.formatMessage({ id: 'pages.table.updatedBy', defaultMessage: 'Updated By' })}>
              {detailModal.doc.updatedBy || '-'}
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
