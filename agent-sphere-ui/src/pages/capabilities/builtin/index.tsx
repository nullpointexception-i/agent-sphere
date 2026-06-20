import { PageContainer, ProTable } from '@ant-design/pro-components';
import { Modal } from 'antd';
import { useIntl } from '@umijs/max';
import { useEffect, useState } from 'react';
import { agentApi } from '@/services/agentSphere/api';

export default function BuiltinList() {
  const intl = useIntl();
  const [tableScrollY, setTableScrollY] = useState(400);
  const [schemaModal, setSchemaModal] = useState<{ open: boolean; title: string; schema: string; label: string }>({ open: false, title: '', schema: '', label: '' });
  const [detailModal, setDetailModal] = useState<{ open: boolean; record: any }>({ open: false, record: null });

  useEffect(() => {
    const calc = () => setTableScrollY(window.innerHeight - 280);
    calc();
    window.addEventListener('resize', calc);
    return () => window.removeEventListener('resize', calc);
  }, []);

  const formatJson = (raw: string) => {
    try {
      return JSON.stringify(JSON.parse(raw), null, 2);
    } catch {
      return raw;
    }
  };

  const locale = intl.locale;
  const localizedName = (record: any) =>
    locale === 'en-US' ? (record.displayNameEn || record.name) : (record.displayNameCn || record.name);

  const columns = [
    { title: intl.formatMessage({ id: 'pages.table.name' }), dataIndex: 'name', key: 'name', render: (_: any, record: any) => localizedName(record) },
    {
      title: intl.formatMessage({ id: 'pages.capabilities.paramSchema' }),
      dataIndex: 'paramSchema',
      key: 'paramSchema',
      render: (_: any, record: any) => (
        <a onClick={() => setSchemaModal({ open: true, title: record.name, schema: record.paramSchema, label: intl.formatMessage({ id: 'pages.capabilities.paramSchema' }) })}>
          {intl.formatMessage({ id: 'pages.capabilities.viewSchema', defaultMessage: 'View Schema' })}
        </a>
      ),
    },
    {
      title: intl.formatMessage({ id: 'pages.capabilities.responseSchema' }),
      dataIndex: 'responseSchema',
      key: 'responseSchema',
      render: (_: any, record: any) => (
        <a onClick={() => setSchemaModal({ open: true, title: record.name, schema: record.responseSchema, label: intl.formatMessage({ id: 'pages.capabilities.responseSchema' }) })}>
          {intl.formatMessage({ id: 'pages.capabilities.viewSchema', defaultMessage: 'View Schema' })}
        </a>
      ),
    },
    {
      title: intl.formatMessage({ id: 'pages.table.actions' }),
      key: 'actions',
      width: 80,
      render: (_: any, record: any) => (
        <a onClick={() => setDetailModal({ open: true, record })}>
          {intl.formatMessage({ id: 'pages.chat.detail' })}
        </a>
      ),
    },
  ];

  return (
    <PageContainer title={false} breadcrumbRender={false}>
      <ProTable
        rowKey="name"
        search={false}
        options={false}
        scroll={{ y: tableScrollY }}
        pagination={false}
        request={async () => {
          const res = await agentApi.builtin.list();
          return { data: res, total: res.length, success: true };
        }}
        columns={columns}
      />
      <Modal
        title={`${schemaModal.title} - ${schemaModal.label}`}
        open={schemaModal.open}
        footer={null}
        width={700}
        onCancel={() => setSchemaModal({ open: false, title: '', schema: '', label: '' })}
      >
        <pre style={{ maxHeight: 500, overflow: 'auto', background: '#f5f5f5', padding: 16, borderRadius: 6, fontSize: 13, whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>
          {formatJson(schemaModal.schema)}
        </pre>
      </Modal>
      <Modal
        title={detailModal.record ? localizedName(detailModal.record) : ''}
        open={detailModal.open}
        footer={null}
        width={700}
        onCancel={() => setDetailModal({ open: false, record: null })}
      >
        {detailModal.record && (
          <div style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
            {detailModal.record.description || '-'}
          </div>
        )}
      </Modal>
    </PageContainer>
  );
}
