import {
  ClearOutlined,
  EyeOutlined,
  PlusOutlined,
  StopOutlined,
} from '@ant-design/icons';
import { PageContainer, ProTable } from '@ant-design/pro-components';
import { useIntl } from '@umijs/max';
import { App, Button, DatePicker, Form, Input, Modal, Select, Tag } from 'antd';
import type dayjs from 'dayjs';
import { useEffect, useRef, useState } from 'react';
import { Can } from '@/components/Can';
import { agentApi } from '@/services/agentSphere/api';
import { formatParamDate, formatTime } from '@/utils/format';
import { labelWithRule } from '@/utils/labelWithRule';

const STATUS_TAG: Record<string, { color: string; label: string }> = {
  QUEUED: { color: 'blue', label: 'pages.admin.tasks.statusQueued' },
  RUNNING: { color: 'processing', label: 'pages.admin.tasks.statusRunning' },
  COMPLETED: { color: 'green', label: 'pages.admin.tasks.statusCompleted' },
  FAILED: { color: 'red', label: 'pages.admin.tasks.statusFailed' },
  CANCELLED: { color: 'default', label: 'pages.admin.tasks.statusCancelled' },
};

export default function TaskList() {
  const { message, modal } = App.useApp();
  const intl = useIntl();
  const actionRef = useRef<any>(null);
  const [modalOpen, setModalOpen] = useState(false);
  const [form] = Form.useForm();
  const [submitting, setSubmitting] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [status, setStatus] = useState<string | undefined>(undefined);
  const [timeRange, setTimeRange] = useState<
    [dayjs.Dayjs | null, dayjs.Dayjs | null]
  >([null, null]);
  const [tableScrollY, setTableScrollY] = useState(400);
  const [instances, setInstances] = useState<any[]>([]);
  const [viewRecord, setViewRecord] = useState<any>(null);
  const [_viewLoading, setViewLoading] = useState(false);
  const [stoppingId, setStoppingId] = useState<number | null>(null);

  const t = (id: string, defaultMessage?: string) =>
    intl.formatMessage({ id, defaultMessage });

  useEffect(() => {
    const calc = () => setTableScrollY(window.innerHeight - 280);
    calc();
    window.addEventListener('resize', calc);
    return () => window.removeEventListener('resize', calc);
  }, []);

  useEffect(() => {
    agentApi.instances
      .listAll()
      .then(setInstances)
      .catch(() => {});
  }, []);

  const jsonRule = {
    validator: (_: any, value: string) => {
      if (!value || value.trim() === '') return Promise.resolve();
      try {
        JSON.parse(value);
        return Promise.resolve();
      } catch {
        return Promise.reject(
          new Error(t('pages.admin.completions.invalidJson', '无效 JSON')),
        );
      }
    },
  };

  const statusRender = (value: any) => {
    const cfg = STATUS_TAG[value];
    return cfg ? (
      <Tag color={cfg.color}>{t(cfg.label, value)}</Tag>
    ) : (
      <Tag>{value}</Tag>
    );
  };

  const columns = [
    {
      title: intl.formatMessage({ id: 'pages.table.id' }),
      dataIndex: 'id',
      key: 'id',
      width: 60,
    },
    {
      title: t('pages.admin.tasks.goal', '目标'),
      dataIndex: 'goal',
      key: 'goal',
      ellipsis: true,
    },
    {
      title: t('pages.admin.tasks.status', '状态'),
      dataIndex: 'status',
      key: 'status',
      width: 110,
      render: statusRender,
    },
    {
      title: t('pages.admin.tasks.instanceId', '实例'),
      dataIndex: 'instanceId',
      key: 'instanceId',
      width: 70,
      render: (v: any) => (v ? `#${v}` : '-'),
    },
    {
      title: t('pages.admin.tasks.sessionId', '会话'),
      dataIndex: 'sessionId',
      key: 'sessionId',
      width: 80,
      render: (v: any) => (v ? `#${v}` : '-'),
    },
    {
      title: t('pages.admin.tasks.runId', 'Run'),
      dataIndex: 'runId',
      key: 'runId',
      width: 80,
      render: (v: any) => (v ? `#${v}` : '-'),
    },
    {
      title: t('pages.admin.tasks.callbackUrl', '回调地址'),
      dataIndex: 'callbackUrl',
      key: 'callbackUrl',
      width: 180,
      ellipsis: true,
      render: (v: any) => v || '-',
    },
    {
      title: intl.formatMessage({ id: 'pages.table.created' }),
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 155,
      render: (v: any) => formatTime(v),
    },
    {
      title: intl.formatMessage({ id: 'pages.table.actions' }),
      key: 'actions',
      width: 100,
      render: (_: any, record: any) => (
        <>
          <Button
            type="link"
            size="small"
            icon={<EyeOutlined />}
            onClick={() => openDetail(record)}
          />
          {(record.status === 'RUNNING' || record.status === 'QUEUED') && (
            <Can code="admin:tasks:update">
              <Button
                type="link"
                danger
                size="small"
                icon={<StopOutlined />}
                loading={stoppingId === record.id}
                onClick={() => handleStop(record)}
              />
            </Can>
          )}
        </>
      ),
    },
  ];

  const handleSubmit = async () => {
    setSubmitting(true);
    try {
      const values = await form.validateFields();
      const payload = {
        goal: values.goal,
        instanceId: values.instanceId,
        callbackUrl: values.callbackUrl,
        context: values.context ? JSON.parse(values.context) : undefined,
        expectedOutput: values.expectedOutput
          ? JSON.parse(values.expectedOutput)
          : undefined,
        config: values.config ? JSON.parse(values.config) : undefined,
      };
      const vo = await agentApi.admin.tasks.create(payload);
      message.success(
        intl.formatMessage({
          id: 'pages.create.success',
          defaultMessage: 'Created',
        }),
      );
      setModalOpen(false);
      form.resetFields();
      actionRef.current?.reload();
      if (vo?.id) {
        const detail = await agentApi.admin.tasks.get(vo.id);
        setViewRecord(detail);
      }
    } finally {
      setSubmitting(false);
    }
  };

  const openDetail = async (record: any) => {
    setViewLoading(true);
    try {
      const d = await agentApi.admin.tasks.get(record.id);
      setViewRecord(d);
    } catch {
      message.error(t('pages.chat.loadFailed', '加载失败'));
    } finally {
      setViewLoading(false);
    }
  };

  const handleStop = (record: any) => {
    modal.confirm({
      title: t('pages.admin.tasks.stopConfirm', '确认停止该任务？'),
      okType: 'danger',
      onOk: async () => {
        setStoppingId(record.id);
        try {
          await agentApi.admin.tasks.stop(record.id);
          message.success(
            intl.formatMessage({
              id: 'pages.update.success',
              defaultMessage: 'Updated',
            }),
          );
          actionRef.current?.reload();
          if (viewRecord?.id === record.id) openDetail(record);
        } finally {
          setStoppingId(null);
        }
      },
    });
  };

  const detailRows = [
    {
      label: intl.formatMessage({ id: 'pages.table.id' }),
      value: viewRecord?.id,
    },
    { label: t('pages.admin.tasks.goal', '目标'), value: viewRecord?.goal },
    {
      label: t('pages.admin.tasks.status', '状态'),
      value: viewRecord?.status
        ? STATUS_TAG[viewRecord.status]
          ? t(STATUS_TAG[viewRecord.status].label, viewRecord.status)
          : viewRecord.status
        : '-',
    },
    {
      label: t('pages.admin.tasks.instanceId', '实例'),
      value: viewRecord?.instanceId ? `#${viewRecord.instanceId}` : '-',
    },
    {
      label: t('pages.admin.tasks.sessionId', '会话'),
      value: viewRecord?.sessionId ? `#${viewRecord.sessionId}` : '-',
    },
    {
      label: t('pages.admin.tasks.runId', 'Run'),
      value: viewRecord?.runId ? `#${viewRecord.runId}` : '-',
    },
    {
      label: t('pages.admin.tasks.callbackUrl', '回调地址'),
      value: viewRecord?.callbackUrl || '-',
    },
    {
      label: t('pages.admin.tasks.context', '上下文'),
      value: viewRecord?.contextJson || '-',
    },
    {
      label: t('pages.admin.tasks.expectedOutput', '预期输出'),
      value: viewRecord?.expectedOutputJson || '-',
    },
    {
      label: t('pages.admin.completions.config', 'Config'),
      value: viewRecord?.config || '-',
    },
    {
      label: t('pages.admin.tasks.result', '结果'),
      value: viewRecord?.resultJson || '-',
    },
    {
      label: t('pages.admin.tasks.remark', '备注'),
      value: viewRecord?.remark || '-',
    },
    {
      label: intl.formatMessage({ id: 'pages.table.created' }),
      value: formatTime(viewRecord?.createdAt),
    },
    {
      label: intl.formatMessage({
        id: 'pages.table.updatedAt',
        defaultMessage: 'Updated At',
      }),
      value: formatTime(viewRecord?.updatedAt),
    },
  ];

  return (
    <PageContainer title={false} breadcrumbRender={false}>
      <ProTable
        actionRef={actionRef}
        rowKey="id"
        search={false}
        options={false}
        scroll={{ y: tableScrollY }}
        pagination={{
          defaultPageSize: 10,
          showSizeChanger: true,
          showQuickJumper: true,
          pageSizeOptions: [5, 10, 20, 50],
          style: { justifyContent: 'flex-start' },
        }}
        params={{
          keyword: keyword || undefined,
          status: status || undefined,
          startTime: formatParamDate(timeRange[0]),
          endTime: formatParamDate(timeRange[1]?.endOf('day')),
        }}
        request={async (p) => {
          const res = await agentApi.admin.tasks.list({
            keyword: p.keyword,
            status: p.status,
            startTime: p.startTime,
            endTime: p.endTime,
            page: p.current,
            size: p.pageSize,
          });
          return {
            data: res.records || res,
            total: res.total ?? 0,
            success: true,
          };
        }}
        columns={columns}
        toolBarRender={() => [
          <Input.Search
            key="search"
            placeholder={t('pages.admin.tasks.search.placeholder', '搜索目标')}
            style={{ width: 200 }}
            onSearch={(value) => setKeyword(value)}
            allowClear
            onClear={() => setKeyword('')}
            maxLength={255}
          />,
          <Select
            key="status"
            allowClear
            placeholder={t('pages.admin.tasks.filterStatus', '状态筛选')}
            style={{ width: 130 }}
            value={status}
            onChange={(v) => setStatus(v)}
            options={Object.keys(STATUS_TAG).map((s) => ({
              value: s,
              label: t(STATUS_TAG[s].label, s),
            }))}
          />,
          <DatePicker.RangePicker
            key="date"
            value={
              timeRange[0] && timeRange[1]
                ? (timeRange as [dayjs.Dayjs, dayjs.Dayjs])
                : undefined
            }
            onChange={(dates) => {
              if (dates?.[0] && dates[1]) {
                const diff = dates[1].diff(dates[0], 'day');
                if (diff > 90) {
                  setTimeRange([dates[0], dates[0].add(90, 'day')]);
                  message.warning(
                    intl.formatMessage({ id: 'pages.dateRange.warning' }),
                  );
                  return;
                }
              }
              setTimeRange(dates || [null, null]);
            }}
          />,
          <Button
            key="clear"
            icon={<ClearOutlined />}
            onClick={() => {
              setKeyword('');
              setStatus(undefined);
              setTimeRange([null, null]);
              actionRef.current?.reload();
            }}
          />,
          <Can key="new" code="admin:tasks:create">
            <Button
              type="primary"
              icon={<PlusOutlined />}
              onClick={() => {
                form.resetFields();
                setModalOpen(true);
              }}
            />
          </Can>,
        ]}
      />

      <Modal
        title={t('pages.admin.tasks.create', '新建任务')}
        open={modalOpen}
        onOk={handleSubmit}
        onCancel={() => {
          setModalOpen(false);
          form.resetFields();
        }}
        confirmLoading={submitting}
        width={680}
      >
        <Form form={form} layout="vertical">
          <Form.Item
            name="goal"
            label={labelWithRule(
              t('pages.admin.tasks.goal', '目标'),
              intl.formatMessage({ id: 'pages.hint.text' }),
            )}
            rules={[{ required: true }]}
          >
            <Input.TextArea rows={3} maxLength={5000} />
          </Form.Item>
          <Form.Item
            name="instanceId"
            label={labelWithRule(
              t('pages.admin.tasks.instanceId', '实例'),
              t('pages.admin.tasks.instanceId.extra', '留空则使用首个可用实例'),
            )}
          >
            <Select
              allowClear
              showSearch
              optionFilterProp="label"
              placeholder={t(
                'pages.admin.tasks.instanceId.placeholder',
                '选择实例',
              )}
              options={instances
                .filter((i) => i.status === 'ENABLED')
                .map((i) => ({
                  value: i.id,
                  label: i.name ? `${i.name} (#${i.id})` : `#${i.id}`,
                }))}
            />
          </Form.Item>
          <Form.Item
            name="context"
            label={labelWithRule(
              t('pages.admin.tasks.context', '上下文 (JSON)'),
              intl.formatMessage({ id: 'pages.hint.text' }),
            )}
            rules={[jsonRule]}
          >
            <Input.TextArea rows={3} placeholder='{"key":"value"}' />
          </Form.Item>
          <Form.Item
            name="expectedOutput"
            label={labelWithRule(
              t('pages.admin.tasks.expectedOutput', '预期输出 (JSON)'),
              intl.formatMessage({ id: 'pages.hint.text' }),
            )}
            rules={[jsonRule]}
          >
            <Input.TextArea rows={3} placeholder='{"format":"json"}' />
          </Form.Item>
          <Form.Item
            name="config"
            label={t('pages.admin.completions.config', 'Config (JSON)')}
            rules={[jsonRule]}
          >
            <Input.TextArea rows={2} placeholder='{"temperature":0.3}' />
          </Form.Item>
          <Form.Item
            name="callbackUrl"
            label={t('pages.admin.tasks.callbackUrl', '回调地址')}
          >
            <Input maxLength={500} placeholder="https://..." />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={`${t('pages.admin.tasks.detail', '任务详情')}${viewRecord ? ` (ID: ${viewRecord.id})` : ''}`}
        open={!!viewRecord}
        onCancel={() => setViewRecord(null)}
        footer={null}
        width={720}
        destroyOnHidden
      >
        <table style={{ width: '100%', borderCollapse: 'collapse' }}>
          <tbody>
            {detailRows.map((row) => (
              <tr key={row.label}>
                <td
                  style={{
                    padding: '8px 12px',
                    fontWeight: 500,
                    color: '#8c8c8c',
                    borderBottom: '1px solid #f0f0f0',
                    width: 130,
                    verticalAlign: 'top',
                  }}
                >
                  {row.label}
                </td>
                <td
                  style={{
                    padding: '8px 12px',
                    borderBottom: '1px solid #f0f0f0',
                    whiteSpace: 'pre-wrap',
                    wordBreak: 'break-all',
                  }}
                >
                  {row.value || '-'}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </Modal>
    </PageContainer>
  );
}
