import {
  DeleteOutlined,
  DownloadOutlined,
  EditOutlined,
  EyeOutlined,
  FileTextOutlined,
  ShareAltOutlined,
} from '@ant-design/icons';
import { PageContainer, ProTable } from '@ant-design/pro-components';
import { history, useIntl } from '@umijs/max';
import { App, Button, Descriptions, Modal, Tag, Typography } from 'antd';
import { QRCodeSVG } from 'qrcode.react';
import { useEffect, useRef, useState } from 'react';
import { Can } from '@/components/Can';
import { agentApi } from '@/services/agentSphere/api';
import { exportDocxToFile } from '@/utils/exportWord';

export default function DocumentList() {
  const intl = useIntl();
  const locale = intl.locale;
  const [tableScrollY, setTableScrollY] = useState(400);
  const [detailModal, setDetailModal] = useState<{ open: boolean; doc: any }>({
    open: false,
    doc: null,
  });
  const [shareModal, setShareModal] = useState<{
    open: boolean;
    doc: any;
    shareToken: string;
  }>({
    open: false,
    doc: null,
    shareToken: '',
  });
  const [selectedRowKeys, setSelectedRowKeys] = useState<number[]>([]);
  const actionRef = useRef<any>(null);
  const { message, modal } = App.useApp();

  useEffect(() => {
    const calc = () => setTableScrollY(window.innerHeight - 280);
    calc();
    window.addEventListener('resize', calc);
    return () => window.removeEventListener('resize', calc);
  }, []);

  const columns = [
    {
      title: intl.formatMessage({
        id: 'pages.document.title',
        defaultMessage: 'Title',
      }),
      dataIndex: 'title',
      key: 'title',
      width: 200,
      render: (_: any, record: any) => record.title || '-',
    },
    {
      title: intl.formatMessage({
        id: 'pages.document.preview',
        defaultMessage: 'Preview',
      }),
      dataIndex: 'content',
      key: 'content',
      ellipsis: true,
      render: (_: any, record: any) => {
        const text = (record.content || '').replace(/[#*`\n\r]+/g, ' ').trim();
        return text.length > 100 ? text.slice(0, 100) + '…' : text || '-';
      },
    },
    {
      title: intl.formatMessage({
        id: 'pages.document.session',
        defaultMessage: 'Session',
      }),
      dataIndex: 'sessionId',
      key: 'sessionId',
      width: 100,
    },
    {
      title: intl.formatMessage({
        id: 'pages.document.createdAt',
        defaultMessage: 'Created At',
      }),
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 180,
      render: (_: any, record: any) =>
        record.createdAt
          ? new Date(record.createdAt).toLocaleString(
              locale === 'en-US' ? 'en-US' : 'zh-CN',
            )
          : '-',
    },
    {
      title: intl.formatMessage({
        id: 'pages.table.actions',
        defaultMessage: 'Actions',
      }),
      key: 'actions',
      width: 260,
      render: (_: any, record: any) => (
        <>
          <Button
            type="link"
            size="small"
            icon={<EyeOutlined />}
            onClick={() => setDetailModal({ open: true, doc: record })}
          />
          <Button
            type="link"
            size="small"
            icon={<FileTextOutlined />}
            onClick={() => history.push(`/artifacts/documents/${record.id}`)}
          />
          <Can code="document:update">
            <Button
              type="link"
              size="small"
              icon={<EditOutlined />}
              onClick={() =>
                history.push(`/artifacts/documents/${record.id}/edit`)
              }
            />
          </Can>
          <Can code="document:share">
            <Button
              type="link"
              size="small"
              icon={<ShareAltOutlined />}
              onClick={async () => {
                try {
                  const res = await agentApi.artifacts.documents.createShare(
                    record.id,
                  );
                  setShareModal({
                    open: true,
                    doc: record,
                    shareToken: res.shareToken,
                  });
                } catch {
                  message.error(
                    intl.formatMessage({
                      id: 'pages.document.shareFailed',
                      defaultMessage: 'Share failed',
                    }),
                  );
                }
              }}
            />
          </Can>
          <Button
            type="link"
            size="small"
            icon={<DownloadOutlined />}
            onClick={async () => {
              try {
                const doc = await agentApi.artifacts.documents.getById(
                  record.id,
                );
                await exportDocxToFile(
                  doc.title || '',
                  doc.content || '',
                  doc.title || `document-${record.id}`,
                );
                message.success(
                  intl.formatMessage({
                    id: 'pages.document.exported',
                    defaultMessage: 'Exported',
                  }),
                );
              } catch {
                message.error(
                  intl.formatMessage({
                    id: 'pages.document.exportFailed',
                    defaultMessage: 'Export failed',
                  }),
                );
              }
            }}
          />
          <Can code="document:delete">
            <Button
              type="link"
              danger
              size="small"
              icon={<DeleteOutlined />}
              onClick={() => {
                modal.confirm({
                  title: intl.formatMessage(
                    {
                      id: 'pages.deleteConfirm.title',
                      defaultMessage: 'Delete {name}',
                    },
                    { name: 'document' },
                  ),
                  content: intl.formatMessage(
                    {
                      id: 'pages.deleteConfirm.content',
                      defaultMessage:
                        'Are you sure you want to delete this {name}?',
                    },
                    { name: 'document' },
                  ),
                  okType: 'danger',
                  onOk: async () => {
                    await agentApi.artifacts.documents.delete(record.id);
                    message.success(
                      intl.formatMessage({
                        id: 'pages.document.deleted',
                        defaultMessage: 'Deleted',
                      }),
                    );
                    actionRef.current?.reload();
                  },
                });
              }}
            />
          </Can>
        </>
      ),
    },
  ];

  const summaryText = (content: string) => {
    const text = (content || '').replace(/[#*`\n\r]+/g, ' ').trim();
    return text.length > 200 ? text.slice(0, 200) + '…' : text || '-';
  };

  const handleBatchDelete = () => {
    if (selectedRowKeys.length === 0) return;
    modal.confirm({
      title: intl.formatMessage(
        { id: 'pages.deleteConfirm.title', defaultMessage: 'Delete {name}' },
        { name: `${selectedRowKeys.length} documents` },
      ),
      content: intl.formatMessage(
        {
          id: 'pages.deleteConfirm.content',
          defaultMessage: 'Are you sure you want to delete this {name}?',
        },
        { name: 'documents' },
      ),
      okType: 'danger',
      onOk: async () => {
        await agentApi.artifacts.documents.batchDelete(selectedRowKeys);
        message.success(
          intl.formatMessage(
            {
              id: 'pages.batchDelete.success',
              defaultMessage: 'Deleted {count} items',
            },
            { count: selectedRowKeys.length },
          ),
        );
        setSelectedRowKeys([]);
        actionRef.current?.reload();
      },
    });
  };

  return (
    <PageContainer title={false} breadcrumbRender={false}>
      <ProTable
        rowKey="id"
        search={false}
        options={false}
        actionRef={actionRef}
        scroll={{ y: tableScrollY }}
        pagination={{
          defaultPageSize: 10,
          showSizeChanger: true,
          showQuickJumper: true,
          pageSizeOptions: [5, 10, 20, 50],
        }}
        rowSelection={{
          selectedRowKeys,
          onChange: (keys: any) => setSelectedRowKeys(keys),
        }}
        toolBarRender={() => [
          selectedRowKeys.length > 0 && (
            <Can code="document:delete">
              <Button
                key="batchDelete"
                danger
                icon={<DeleteOutlined />}
                onClick={handleBatchDelete}
              >
                {intl.formatMessage(
                  {
                    id: 'pages.batchDelete',
                    defaultMessage: 'Delete ({count})',
                  },
                  { count: selectedRowKeys.length },
                )}
              </Button>
            </Can>
          ),
        ]}
        request={async (params: any) => {
          const { current, pageSize } = params;
          const res = await agentApi.artifacts.documents.list({
            page: current,
            size: pageSize,
          });
          return {
            data: res?.records || res || [],
            total: res?.total || 0,
            success: true,
          };
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
          <Descriptions
            column={1}
            bordered
            size="small"
            style={{ marginTop: 16 }}
          >
            <Descriptions.Item
              label={intl.formatMessage({
                id: 'pages.document.summary',
                defaultMessage: 'Summary',
              })}
            >
              {summaryText(detailModal.doc.content)}
            </Descriptions.Item>
            <Descriptions.Item
              label={intl.formatMessage({
                id: 'pages.document.contentType',
                defaultMessage: 'Content Type',
              })}
            >
              <Tag>{detailModal.doc.contentType || 'markdown'}</Tag>
            </Descriptions.Item>
            <Descriptions.Item
              label={intl.formatMessage({
                id: 'pages.document.session',
                defaultMessage: 'Session',
              })}
            >
              {detailModal.doc.sessionId || '-'}
            </Descriptions.Item>
            <Descriptions.Item
              label={intl.formatMessage({
                id: 'pages.table.createdBy',
                defaultMessage: 'Created By',
              })}
            >
              {detailModal.doc.createdBy || '-'}
            </Descriptions.Item>
            <Descriptions.Item
              label={intl.formatMessage({
                id: 'pages.table.updatedBy',
                defaultMessage: 'Updated By',
              })}
            >
              {detailModal.doc.updatedBy || '-'}
            </Descriptions.Item>
            <Descriptions.Item
              label={intl.formatMessage({
                id: 'pages.document.createdAt',
                defaultMessage: 'Created At',
              })}
            >
              {detailModal.doc.createdAt
                ? new Date(detailModal.doc.createdAt).toLocaleString(
                    locale === 'en-US' ? 'en-US' : 'zh-CN',
                  )
                : '-'}
            </Descriptions.Item>
            <Descriptions.Item
              label={intl.formatMessage({
                id: 'pages.document.updatedAt',
                defaultMessage: 'Updated At',
              })}
            >
              {detailModal.doc.updatedAt
                ? new Date(detailModal.doc.updatedAt).toLocaleString(
                    locale === 'en-US' ? 'en-US' : 'zh-CN',
                  )
                : '-'}
            </Descriptions.Item>
          </Descriptions>
        )}
      </Modal>

      <Modal
        title={intl.formatMessage({
          id: 'pages.document.shareTitle',
          defaultMessage: 'Share Document',
        })}
        open={shareModal.open}
        onCancel={() =>
          setShareModal({ open: false, doc: null, shareToken: '' })
        }
        footer={null}
        width={360}
        centered
      >
        {shareModal.shareToken && (
          <div
            style={{
              padding: '16px 0',
              display: 'flex',
              flexDirection: 'column',
              alignItems: 'center',
              gap: 16,
            }}
          >
            <div
              style={{
                display: 'inline-flex',
                padding: 12,
                border: '1px solid #e8e8e8',
                borderRadius: 8,
                background: '#fff',
              }}
            >
              <QRCodeSVG
                value={`${window.location.origin}/s/${shareModal.shareToken}`}
                size={180}
              />
            </div>
            <Typography.Text
              copyable
              ellipsis
              style={{ maxWidth: 280, fontSize: 13 }}
            >
              {window.location.origin}/s/{shareModal.shareToken}
            </Typography.Text>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {intl.formatMessage({
                id: 'pages.document.shareHint',
                defaultMessage: 'Scan QR code or copy link to share',
              })}
            </Typography.Text>
          </div>
        )}
      </Modal>
    </PageContainer>
  );
}
