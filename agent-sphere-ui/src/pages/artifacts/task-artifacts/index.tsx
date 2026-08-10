import { CopyOutlined, EyeOutlined } from '@ant-design/icons';
import { PageContainer, ProTable } from '@ant-design/pro-components';
import { useIntl } from '@umijs/max';
import { App, Button, Drawer, Space, Tag } from 'antd';
import { useRef, useState } from 'react';
import { agentApi } from '@/services/agentSphere/api';
import { formatTime } from '@/utils/format';

export default function TaskArtifactList() {
  const { message } = App.useApp();
  const intl = useIntl();
  const actionRef = useRef<any>(null);
  const [detail, setDetail] = useState<any>(null);

  const t = (id: string, defaultMessage?: string) =>
    intl.formatMessage({ id, defaultMessage });

  const formattedContent = (() => {
    if (!detail?.content) return '';
    try {
      return JSON.stringify(JSON.parse(detail.content), null, 2);
    } catch {
      return detail.content;
    }
  })();

  const handleCopy = async () => {
    const text = formattedContent || '';
    try {
      await navigator.clipboard.writeText(text);
    } catch {
      const ta = document.createElement('textarea');
      ta.value = text;
      document.body.appendChild(ta);
      ta.select();
      document.execCommand('copy');
      document.body.removeChild(ta);
    }
    message.success(t('pages.artifacts.taskArtifacts.copied', '已复制'));
  };

  const columns = [
    {
      title: t('pages.table.id', 'ID'),
      dataIndex: 'id',
      key: 'id',
      width: 80,
    },
    {
      title: t('pages.artifacts.taskArtifacts.task', '任务'),
      key: 'task',
      ellipsis: true,
      render: (_: any, record: any) => (
        <Space size={4} direction="vertical">
          <span>{record.taskGoal || `#${record.taskId}`}</span>
          <span style={{ color: 'rgba(0,0,0,0.45)', fontSize: 12 }}>
            #{record.taskId}
          </span>
        </Space>
      ),
    },
    {
      title: t('pages.artifacts.taskArtifacts.type', '类型'),
      dataIndex: 'artifactType',
      key: 'artifactType',
      width: 140,
      render: (v: any) => (v ? <Tag>{v}</Tag> : '-'),
    },
    {
      title: t('pages.artifacts.taskArtifacts.schemaRef', 'Schema Ref'),
      dataIndex: 'schemaRef',
      key: 'schemaRef',
      width: 150,
      render: (v: any) => v || '-',
    },
    {
      title: t('pages.artifacts.taskArtifacts.runId', 'Run ID'),
      dataIndex: 'runId',
      key: 'runId',
      width: 100,
      render: (v: any) => v ?? '-',
    },
    {
      title: t('pages.table.status', '状态'),
      dataIndex: 'status',
      key: 'status',
      width: 100,
      render: (v: any) =>
        v ? <Tag color={v === 'ACTIVE' ? 'green' : 'default'}>{v}</Tag> : '-',
    },
    {
      title: t('pages.table.created', '创建时间'),
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 170,
      render: (v: any) => formatTime(v),
    },
    {
      title: t('pages.table.actions', '操作'),
      key: 'actions',
      width: 90,
      render: (_: any, record: any) => (
        <Button
          type="link"
          size="small"
          icon={<EyeOutlined />}
          onClick={() => setDetail(record)}
        >
          {t('pages.table.detail', '详情')}
        </Button>
      ),
    },
  ];

  return (
    <PageContainer title={false} breadcrumbRender={false}>
      <ProTable
        actionRef={actionRef}
        rowKey="id"
        search={false}
        options={false}
        columns={columns}
        request={async (params: any) => {
          const res = await agentApi.admin.taskArtifacts.list({
            page: params.current || 1,
            size: params.pageSize || 10,
          });
          return {
            data: res.records || res,
            total: res.total ?? 0,
            success: true,
          };
        }}
        pagination={{ defaultPageSize: 10 }}
      />
      <Drawer
        title={t('pages.artifacts.taskArtifacts.detailTitle', '任务产物详情')}
        open={!!detail}
        onClose={() => setDetail(null)}
        size={760}
        destroyOnHidden
        extra={
          <Button type="primary" icon={<CopyOutlined />} onClick={handleCopy}>
            {t('pages.artifacts.taskArtifacts.copy', '复制内容')}
          </Button>
        }
      >
        <div style={{ marginBottom: 16 }}>
          {[
            {
              label: t('pages.table.id', 'ID'),
              value: detail?.id,
            },
            {
              label: t('pages.artifacts.taskArtifacts.task', '任务'),
              value: detail?.taskGoal || `#${detail?.taskId}`,
            },
            {
              label: t('pages.artifacts.taskArtifacts.type', '类型'),
              value: detail?.artifactType,
            },
            {
              label: t('pages.artifacts.taskArtifacts.schemaRef', 'Schema Ref'),
              value: detail?.schemaRef,
            },
            {
              label: t('pages.artifacts.taskArtifacts.runId', 'Run ID'),
              value: detail?.runId,
            },
            {
              label: t('pages.artifacts.taskArtifacts.taskId', '任务 ID'),
              value: detail?.taskId,
            },
            {
              label: t('pages.artifacts.taskArtifacts.createdBy', '创建人'),
              value: detail?.createdBy,
            },
            {
              label: t('pages.table.created', '创建时间'),
              value: formatTime(detail?.createdAt),
            },
            {
              label: t('pages.artifacts.taskArtifacts.remark', '备注'),
              value: detail?.remark || '-',
            },
          ].map((row) => (
            <div
              key={row.label}
              style={{ display: 'flex', padding: '4px 0', gap: 12 }}
            >
              <span
                style={{
                  width: 110,
                  color: 'rgba(0,0,0,0.45)',
                  flexShrink: 0,
                }}
              >
                {row.label}
              </span>
              <span style={{ wordBreak: 'break-all' }}>{row.value ?? '-'}</span>
            </div>
          ))}
        </div>
        <strong>
          {t('pages.artifacts.taskArtifacts.content', '产物内容')}
        </strong>
        <pre
          style={{
            marginTop: 8,
            maxHeight: 'calc(100vh - 320px)',
            overflow: 'auto',
            fontSize: 12,
            lineHeight: 1.6,
            whiteSpace: 'pre-wrap',
            wordBreak: 'break-all',
            background: '#f6f8fa',
            padding: 12,
            borderRadius: 8,
          }}
        >
          {formattedContent || '-'}
        </pre>
      </Drawer>
    </PageContainer>
  );
}
