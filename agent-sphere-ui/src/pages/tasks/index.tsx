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
import { DEFAULT_PAGE_SIZE } from '@/constants/pagination';
import { PERM } from '@/constants/permissions';
import { STATUS, TASK_STATUS } from '@/constants/status';
import { agentApi } from '@/services/agentSphere/api';
import { formatParamDate, formatTime } from '@/utils/format';
import { labelWithRule } from '@/utils/labelWithRule';

const STATUS_TAG = TASK_STATUS;

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

  const jsonRule = {
    validator: (_: any, value: string) => {
      if (!value || value.trim() === '') return Promise.resolve();
      try {
        JSON.parse(value);
        return Promise.resolve();
      } catch {
        return Promise.reject(
          new Error(t('pages.admin.completions.invalidJson', 'Invalid JSON')),
        );
      }
    },
  };

  const statusRender = (value: any) => {
    const cfg = STATUS_TAG[value];
    return cfg ? (
      <Tag color={cfg.color}>{t(cfg.labelKey, value)}</Tag>
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
      title: t('pages.admin.tasks.goal', 'Goal'),
      dataIndex: 'goal',
      key: 'goal',
      ellipsis: true,
    },
    {
      title: t('pages.admin.tasks.status', 'Status'),
      dataIndex: 'status',
      key: 'status',
      width: 110,
      render: statusRender,
    },
    {
      title: t('pages.admin.tasks.instanceId', 'Instance'),
      dataIndex: 'instanceId',
      key: 'instanceId',
      width: 70,
      render: (v: any) => (v ? `#${v}` : '-'),
    },
    {
      title: t('pages.admin.tasks.sessionId', 'Session'),
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
      title: t('pages.admin.tasks.callbackUrl', 'Callback URL'),
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
          {(record.status === STATUS.TASK.RUNNING ||
            record.status === STATUS.TASK.QUEUED) && (
            <Can code={PERM.adminTasksUpdate}>
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
        businessType: values.businessType,
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
      message.error(t('pages.chat.loadFailed', 'Load failed'));
    } finally {
      setViewLoading(false);
    }
  };

  const handleStop = (record: any) => {
    modal.confirm({
      title: t('pages.admin.tasks.stopConfirm', 'Confirm stopping this task?'),
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
    { label: t('pages.admin.tasks.goal', 'Goal'), value: viewRecord?.goal },
    {
      label: t('pages.admin.tasks.status', 'Status'),
      value: viewRecord?.status
        ? STATUS_TAG[viewRecord.status]
          ? t(STATUS_TAG[viewRecord.status].labelKey, viewRecord.status)
          : viewRecord.status
        : '-',
    },
    {
      label: t('pages.admin.tasks.instanceId', 'Instance'),
      value: viewRecord?.instanceId ? `#${viewRecord.instanceId}` : '-',
    },
    {
      label: t('pages.admin.tasks.sessionId', 'Session'),
      value: viewRecord?.sessionId ? `#${viewRecord.sessionId}` : '-',
    },
    {
      label: t('pages.admin.tasks.runId', 'Run'),
      value: viewRecord?.runId ? `#${viewRecord.runId}` : '-',
    },
    {
      label: t('pages.admin.tasks.callbackUrl', 'Callback URL'),
      value: viewRecord?.callbackUrl || '-',
    },
    {
      label: t('pages.admin.tasks.context', 'Context'),
      value: viewRecord?.contextJson || '-',
    },
    {
      label: t('pages.admin.tasks.expectedOutput', 'Expected Output'),
      value: viewRecord?.expectedOutputJson || '-',
    },
    {
      label: t('pages.admin.completions.config', 'Config'),
      value: viewRecord?.config || '-',
    },
    {
      label: t('pages.admin.tasks.result', 'Result'),
      value: viewRecord?.resultJson || '-',
    },
    {
      label: t('pages.admin.tasks.remark', 'Remark'),
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
          defaultPageSize: DEFAULT_PAGE_SIZE,
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
            placeholder={t(
              'pages.admin.tasks.search.placeholder',
              'Search by goal',
            )}
            style={{ width: 200 }}
            onSearch={(value) => setKeyword(value)}
            allowClear
            onClear={() => setKeyword('')}
            maxLength={255}
          />,
          <Select
            key="status"
            allowClear
            placeholder={t(
              'pages.admin.tasks.filterStatus',
              'Filter by status',
            )}
            style={{ width: 130 }}
            value={status}
            onChange={(v) => setStatus(v)}
            options={Object.keys(STATUS_TAG).map((s) => ({
              value: s,
              label: t(STATUS_TAG[s].labelKey, s),
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
          <Can key="new" code={PERM.adminTasksCreate}>
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
        title={t('pages.admin.tasks.create', 'New Task')}
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
              t('pages.admin.tasks.goal', 'Goal'),
              intl.formatMessage({ id: 'pages.hint.text' }),
            )}
            rules={[{ required: true }]}
          >
            <Input.TextArea rows={3} maxLength={5000} />
          </Form.Item>
          <Form.Item
            name="businessType"
            label={labelWithRule(
              t('pages.admin.tasks.businessType', 'Business Type'),
              t(
                'pages.admin.tasks.businessType.extra',
                'Matching available instance by businessType; must match the instance businessType',
              ),
            )}
            rules={[{ required: true }]}
          >
            <Input maxLength={64} placeholder="sourcing" />
          </Form.Item>
          <Form.Item
            name="context"
            label={labelWithRule(
              t('pages.admin.tasks.context', 'Context'),
              intl.formatMessage({ id: 'pages.hint.text' }),
            )}
            rules={[jsonRule]}
          >
            <Input.TextArea rows={3} placeholder='{"key":"value"}' />
          </Form.Item>
          <Form.Item
            name="expectedOutput"
            label={labelWithRule(
              t('pages.admin.tasks.expectedOutput', 'Expected Output'),
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
            label={t('pages.admin.tasks.callbackUrl', 'Callback URL')}
          >
            <Input maxLength={500} placeholder="https://..." />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={`${t('pages.admin.tasks.detail', 'Task Detail')}${viewRecord ? ` (ID: ${viewRecord.id})` : ''}`}
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
