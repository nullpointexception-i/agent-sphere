import {
  AlertOutlined,
  BarChartOutlined,
  HistoryOutlined,
  PauseCircleOutlined,
  PlayCircleOutlined,
  PlusOutlined,
  RedoOutlined,
  SettingOutlined,
  StopOutlined,
  UnorderedListOutlined,
} from '@ant-design/icons';
import { PageContainer, ProTable } from '@ant-design/pro-components';
import { useIntl } from '@umijs/max';
import {
  App,
  Button,
  Descriptions,
  Drawer,
  Form,
  Input,
  List,
  Modal,
  Select,
  Space,
  Spin,
  Tag,
  Typography,
} from 'antd';
import { useEffect, useRef, useState } from 'react';
import { Can } from '@/components/Can';
import { dictApi, type SystemDicEntry } from '@/services/agentSphere/dict';
import {
  type SuperAgent,
  type SuperAgentInstances,
  superAgentApi,
  type WorkItem,
} from '@/services/agentSphere/superAgent';

const AGENT_STATUS: Record<string, { color: string; label: string }> = {
  ACTIVE: { color: 'green', label: '运行中' },
  PAUSED: { color: 'orange', label: '已暂停' },
  ARCHIVED: { color: 'default', label: '已归档' },
};

const WORK_STATUS: Record<string, { color: string; label: string }> = {
  QUEUED: { color: 'blue', label: '排队中' },
  DISPATCHING: { color: 'processing', label: '派发中' },
  RUNNING: { color: 'processing', label: '执行中' },
  WAITING: { color: 'gold', label: '等待中' },
  COMPLETED: { color: 'green', label: '已完成' },
  DONE: { color: 'green', label: '已完成' },
  FAILED: { color: 'red', label: '失败' },
  CANCELLED: { color: 'default', label: '已取消' },
};

function StatusTag({
  value,
  map,
}: {
  value?: string;
  map: Record<string, { color: string; label: string }>;
}) {
  const cfg = value ? map[value] : undefined;
  return cfg ? (
    <Tag color={cfg.color}>{cfg.label}</Tag>
  ) : (
    <Tag>{value ?? '-'}</Tag>
  );
}

export default function SuperAgentPage() {
  const { message, modal } = App.useApp();
  const intl = useIntl();
  const actionRef = useRef<any>(null);
  const workActionRef = useRef<any>(null);
  const [createOpen, setCreateOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm();
  const [selected, setSelected] = useState<SuperAgent | null>(null);
  const [selectedInstances, setSelectedInstances] =
    useState<SuperAgentInstances | null>(null);
  const [instancesLoading, setInstancesLoading] = useState(false);
  const [workStatus, setWorkStatus] = useState<string | undefined>(undefined);
  const [timelineOpen, setTimelineOpen] = useState(false);
  const [timelineLoading, setTimelineLoading] = useState(false);
  const [timelineRows, setTimelineRows] = useState<any[]>([]);
  const [timelineItem, setTimelineItem] = useState<WorkItem | null>(null);
  const [insightOpen, setInsightOpen] = useState(false);
  const [insightMode, setInsightMode] = useState<
    'usage' | 'watchdog' | 'channels'
  >('usage');
  const [insightAgent, setInsightAgent] = useState<SuperAgent | null>(null);
  const [insightLoading, setInsightLoading] = useState(false);
  const [insightRows, setInsightRows] = useState<any[]>([]);
  const [usageDays, setUsageDays] = useState(30);
  const [templateOpen, setTemplateOpen] = useState(false);
  const [templateText, setTemplateText] = useState('');
  const [templateDefault, setTemplateDefault] = useState('');
  const [templateSaving, setTemplateSaving] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [settingsSaving, setSettingsSaving] = useState(false);
  const [settingsAgent, setSettingsAgent] = useState<SuperAgent | null>(null);
  const [settingsForm] = Form.useForm();
  const [positions, setPositions] = useState<SystemDicEntry[]>([]);

  const t = (id: string, defaultMessage: string) =>
    intl.formatMessage({ id, defaultMessage });

  const positionName = (record?: SuperAgent | null) => {
    if (!record) return '';
    return intl.locale === 'en-US'
      ? record.positionNameEn || record.positionNameCn
      : record.positionNameCn || record.positionNameEn;
  };

  const positionOptions = positions.map((item) => ({
    value: item.code,
    label: intl.locale === 'en-US' ? item.nameEn : item.nameCn,
  }));

  useEffect(() => {
    dictApi
      .listActive()
      .then(setPositions)
      .catch(() => setPositions([]));
  }, []);

  useEffect(() => {
    if (!selected) {
      setSelectedInstances(null);
      return;
    }
    let active = true;
    setInstancesLoading(true);
    superAgentApi
      .instances(selected.id)
      .then((data) => {
        if (active) setSelectedInstances(data);
      })
      .catch(() => {
        if (active) setSelectedInstances(null);
      })
      .finally(() => {
        if (active) setInstancesLoading(false);
      });
    return () => {
      active = false;
    };
  }, [selected]);

  const submitCreate = async () => {
    const values = await form.validateFields();
    setSubmitting(true);
    try {
      await superAgentApi.create(values);
      message.success(t('pages.superAgent.createSuccess', '数字员工已创建'));
      setCreateOpen(false);
      form.resetFields();
      actionRef.current?.reload();
    } finally {
      setSubmitting(false);
    }
  };

  const toggle = async (record: SuperAgent) => {
    const pause = record.status === 'ACTIVE';
    modal.confirm({
      title: pause
        ? t(
            'pages.superAgent.pauseConfirm',
            '暂停后该员工的投递会跳过，确认暂停？',
          )
        : t('pages.superAgent.resumeConfirm', '确认恢复该数字员工？'),
      onOk: async () => {
        if (pause) {
          await superAgentApi.pause(record.id);
        } else {
          await superAgentApi.resume(record.id);
        }
        message.success(t('pages.superAgent.updated', '状态已更新'));
        actionRef.current?.reload();
      },
    });
  };

  const retryWorkItem = async (record: WorkItem) => {
    if (!selected) return;
    await superAgentApi.retryWorkItem(selected.id, record.id);
    message.success(t('pages.superAgent.retried', '已重新排队'));
    workActionRef.current?.reload();
  };

  const cancelWorkItem = (record: WorkItem) => {
    if (!selected) return;
    modal.confirm({
      title: t(
        'pages.superAgent.cancelConfirm',
        '取消后 Worker Run 会停止，确认取消？',
      ),
      onOk: async () => {
        await superAgentApi.cancelWorkItem(selected.id, record.id);
        message.success(t('pages.superAgent.cancelled', '任务已取消'));
        workActionRef.current?.reload();
      },
    });
  };

  const openTimeline = async (record: WorkItem) => {
    if (!selected) return;
    setTimelineItem(record);
    setTimelineOpen(true);
    setTimelineLoading(true);
    try {
      const page = await superAgentApi.workerTimeline(selected.id, record.id);
      setTimelineRows(page?.rows ?? []);
    } finally {
      setTimelineLoading(false);
    }
  };

  const openInsight = async (
    record: SuperAgent,
    mode: 'usage' | 'watchdog' | 'channels',
    days = usageDays,
  ) => {
    setInsightAgent(record);
    setInsightMode(mode);
    setInsightOpen(true);
    setInsightLoading(true);
    try {
      const data =
        mode === 'usage'
          ? await superAgentApi.usage(record.id, days)
          : mode === 'watchdog'
            ? await superAgentApi.watchdogRuns(record.id)
            : await superAgentApi.channels(record.id);
      setInsightRows(data ?? []);
    } finally {
      setInsightLoading(false);
    }
  };

  const openTemplate = async () => {
    setTemplateOpen(true);
    const data = await superAgentApi.groupRouterTemplate();
    setTemplateText(data?.template ?? '');
    setTemplateDefault(data?.defaultTemplate ?? '');
  };

  const saveTemplate = async () => {
    setTemplateSaving(true);
    try {
      await superAgentApi.updateGroupRouterTemplate(templateText);
      message.success(t('pages.superAgent.templateSaved', '模板已保存'));
      setTemplateOpen(false);
    } finally {
      setTemplateSaving(false);
    }
  };

  const openSettings = (record: SuperAgent) => {
    setSettingsAgent(record);
    settingsForm.setFieldsValue({
      name: record.name,
      description: record.description,
      positionCode: record.positionCode,
      config: record.config,
    });
    setSettingsOpen(true);
  };

  const saveSettings = async () => {
    if (!settingsAgent) return;
    const values = await settingsForm.validateFields();
    setSettingsSaving(true);
    try {
      await superAgentApi.update(settingsAgent.id, values);
      message.success(t('pages.superAgent.updated', '状态已更新'));
      setSettingsOpen(false);
      actionRef.current?.reload();
    } finally {
      setSettingsSaving(false);
    }
  };

  const toggleChannel = async (record: any) => {
    if (!insightAgent) return;
    const next = record.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE';
    await superAgentApi.updateChannelStatus(insightAgent.id, record.id, next);
    await openInsight(insightAgent, 'channels');
  };

  const agentColumns = [
    {
      title: t('pages.table.id', 'ID'),
      dataIndex: 'id',
      width: 70,
    },
    {
      title: t('pages.superAgent.name', '名称'),
      dataIndex: 'name',
      ellipsis: true,
    },
    {
      title: t('pages.superAgent.description', '描述'),
      dataIndex: 'description',
      ellipsis: true,
    },
    {
      title: t('pages.superAgent.position', '职位'),
      dataIndex: 'positionCode',
      width: 150,
      render: (_: unknown, record: SuperAgent) => positionName(record) || '-',
    },
    {
      title: t('pages.superAgent.routerInstance', 'Router 实例'),
      dataIndex: 'routerInstanceId',
      width: 120,
      render: (v: number) => (v ? `#${v}` : '-'),
    },
    {
      title: t('pages.superAgent.workerInstance', 'Worker 实例'),
      dataIndex: 'workerInstanceId',
      width: 120,
      render: (v: number) => (v ? `#${v}` : '-'),
    },
    {
      title: t('pages.superAgent.status', '状态'),
      dataIndex: 'status',
      width: 110,
      render: (v: string) => <StatusTag value={v} map={AGENT_STATUS} />,
    },
    {
      title: t('pages.superAgent.createdAt', '创建时间'),
      dataIndex: 'createdAt',
      width: 170,
    },
    {
      title: t('pages.table.action', '操作'),
      valueType: 'option',
      width: 440,
      render: (_: unknown, record: SuperAgent) => [
        <Button
          key="tasks"
          type="link"
          size="small"
          icon={<UnorderedListOutlined />}
          onClick={() => {
            setWorkStatus(undefined);
            setSelected(record);
          }}
        >
          {t('pages.superAgent.tasks', '任务')}
        </Button>,
        <Button
          key="usage"
          type="link"
          size="small"
          icon={<BarChartOutlined />}
          onClick={() => openInsight(record, 'usage')}
        >
          用量
        </Button>,
        <Button
          key="watchdog"
          type="link"
          size="small"
          icon={<AlertOutlined />}
          onClick={() => openInsight(record, 'watchdog')}
        >
          巡检
        </Button>,
        <Can key="settings" code="super-agent:update">
          <Button
            type="link"
            size="small"
            icon={<SettingOutlined />}
            onClick={() => openSettings(record)}
          >
            设置
          </Button>
        </Can>,
        <Button
          key="channels"
          type="link"
          size="small"
          onClick={() => openInsight(record, 'channels')}
        >
          渠道
        </Button>,
        <Can key="toggle" code="super-agent:update">
          <Button
            type="link"
            size="small"
            icon={
              record.status === 'ACTIVE' ? (
                <PauseCircleOutlined />
              ) : (
                <PlayCircleOutlined />
              )
            }
            onClick={() => toggle(record)}
          >
            {record.status === 'ACTIVE'
              ? t('pages.superAgent.pause', '暂停')
              : t('pages.superAgent.resume', '恢复')}
          </Button>
        </Can>,
      ],
    },
  ];

  const workColumns = [
    {
      title: t('pages.table.id', 'ID'),
      dataIndex: 'id',
      width: 70,
    },
    {
      title: t('pages.superAgent.workTitle', '任务'),
      dataIndex: 'title',
      ellipsis: true,
    },
    {
      title: t('pages.superAgent.status', '状态'),
      dataIndex: 'status',
      width: 110,
      render: (v: string) => <StatusTag value={v} map={WORK_STATUS} />,
    },
    {
      title: t('pages.superAgent.attempt', '第几次'),
      dataIndex: 'attempt',
      width: 80,
      render: (v: number) => v ?? 1,
    },
    {
      title: t('pages.superAgent.workerSession', 'Worker 会话'),
      dataIndex: 'workerSessionId',
      width: 120,
      render: (v: number) => (v ? `#${v}` : '-'),
    },
    {
      title: t('pages.superAgent.lastProgress', '最后进展'),
      dataIndex: 'lastProgressAt',
      width: 170,
    },
    {
      title: t('pages.superAgent.summary', '结果'),
      dataIndex: 'summary',
      ellipsis: true,
    },
    {
      title: t('pages.table.action', '操作'),
      valueType: 'option',
      width: 150,
      render: (_: unknown, record: WorkItem) => [
        record.workerSessionId ? (
          <Button
            key="timeline"
            type="link"
            size="small"
            icon={<HistoryOutlined />}
            onClick={() => openTimeline(record)}
          >
            {t('pages.superAgent.timeline', '时间线')}
          </Button>
        ) : null,
        <Can key="retry" code="super-agent:update">
          {record.status === 'FAILED' || record.status === 'CANCELLED' ? (
            <Button
              type="link"
              size="small"
              icon={<RedoOutlined />}
              onClick={() => retryWorkItem(record)}
            >
              {t('pages.superAgent.retry', '重试')}
            </Button>
          ) : null}
        </Can>,
        <Can key="cancel" code="super-agent:update">
          {['QUEUED', 'DISPATCHING', 'RUNNING', 'WAITING'].includes(
            record.status,
          ) ? (
            <Button
              type="link"
              size="small"
              danger
              icon={<StopOutlined />}
              onClick={() => cancelWorkItem(record)}
            >
              {t('pages.superAgent.cancel', '取消')}
            </Button>
          ) : null}
        </Can>,
      ],
    },
  ];

  const usageColumns = [
    { title: '日期', dataIndex: 'day', width: 110 },
    { title: '会话类型', dataIndex: 'sessionType', width: 130 },
    { title: 'Run', dataIndex: 'runCount', width: 80 },
    { title: '调用', dataIndex: 'interactionCount', width: 80 },
    { title: 'Prompt', dataIndex: 'promptTokens', width: 100 },
    { title: 'Completion', dataIndex: 'completionTokens', width: 110 },
    { title: 'Total', dataIndex: 'totalTokens', width: 100 },
    { title: 'Cache Hit', dataIndex: 'cacheHitTokens', width: 100 },
    { title: 'Cache Miss', dataIndex: 'cacheMissTokens', width: 105 },
  ];

  const watchdogColumns = [
    { title: 'ID', dataIndex: 'id', width: 70 },
    { title: '状态', dataIndex: 'status', width: 100 },
    { title: 'Dispatched', dataIndex: 'dispatchedStuck', width: 110 },
    { title: 'Delivery', dataIndex: 'deliveryStuck', width: 100 },
    { title: 'Outbound', dataIndex: 'outboundStuck', width: 100 },
    { title: '开始', dataIndex: 'startedAt', width: 170 },
    { title: '结束', dataIndex: 'finishedAt', width: 170 },
    { title: '错误', dataIndex: 'errorMessage', ellipsis: true },
  ];

  const channelColumns = [
    { title: 'ID', dataIndex: 'id', width: 70 },
    { title: '类型', dataIndex: 'channelType', width: 130 },
    { title: '名称', dataIndex: 'name', ellipsis: true },
    { title: '状态', dataIndex: 'status', width: 100 },
    {
      title: '操作',
      valueType: 'option',
      width: 100,
      render: (_: unknown, record: any) => (
        <Can code="super-agent:update">
          <Button
            type="link"
            size="small"
            onClick={() => toggleChannel(record)}
          >
            {record.status === 'ACTIVE' ? '停用' : '启用'}
          </Button>
        </Can>
      ),
    },
  ];

  const statusOptions = Object.entries(WORK_STATUS).map(([value, cfg]) => ({
    value,
    label: cfg.label,
  }));

  return (
    <PageContainer title={false} breadcrumbRender={false}>
      <ProTable<SuperAgent>
        actionRef={actionRef}
        rowKey="id"
        headerTitle={t('pages.superAgent.title', '数字员工')}
        search={false}
        options={false}
        request={async () => {
          const data = await superAgentApi.list();
          return { data, success: true, total: data.length };
        }}
        pagination={false}
        columns={agentColumns as any}
        toolBarRender={() => [
          <Button key="template" onClick={openTemplate}>
            Group Router 模板
          </Button>,
          <Can key="create" code="super-agent:create">
            <Button
              type="primary"
              icon={<PlusOutlined />}
              onClick={() => setCreateOpen(true)}
            >
              {t('pages.superAgent.create', '新建数字员工')}
            </Button>
          </Can>,
        ]}
      />

      <Modal
        open={createOpen}
        title={t('pages.superAgent.create', '新建数字员工')}
        onCancel={() => setCreateOpen(false)}
        onOk={submitCreate}
        confirmLoading={submitting}
        destroyOnHidden
      >
        <Form form={form} layout="vertical">
          <Form.Item
            name="name"
            label={t('pages.superAgent.name', '名称')}
            rules={[{ required: true, max: 128 }]}
          >
            <Input />
          </Form.Item>
          <Form.Item
            name="description"
            label={t('pages.superAgent.description', '描述')}
          >
            <Input.TextArea rows={3} maxLength={512} showCount />
          </Form.Item>
          <Form.Item
            name="positionCode"
            label={t('pages.superAgent.position', '职位')}
            rules={[
              {
                required: true,
                message: t('pages.superAgent.positionRequired', '请选择职位'),
              },
            ]}
          >
            <Select
              options={positionOptions}
              placeholder={t(
                'pages.superAgent.positionPlaceholder',
                '请选择职位',
              )}
            />
          </Form.Item>
        </Form>
      </Modal>

      <Drawer
        open={selected != null}
        size={900}
        title={
          selected
            ? `${t('pages.superAgent.tasks', '任务')} · ${selected.name}`
            : t('pages.superAgent.tasks', '任务')
        }
        onClose={() => setSelected(null)}
        destroyOnHidden
      >
        {selected ? (
          <>
            <Spin spinning={instancesLoading}>
              <Descriptions
                size="small"
                column={1}
                bordered
                style={{ marginBottom: 16 }}
                items={[
                  {
                    key: 'router',
                    label: t('pages.superAgent.routerInstance', 'Router 实例'),
                    children: selectedInstances?.routerInstance
                      ? `#${selectedInstances.routerInstance.id} ${selectedInstances.routerInstance.name} (${selectedInstances.routerInstance.instanceRole ?? '-'})`
                      : '-',
                  },
                  {
                    key: 'worker',
                    label: t('pages.superAgent.workerInstance', 'Worker 实例'),
                    children: selectedInstances?.workerInstance
                      ? `#${selectedInstances.workerInstance.id} ${selectedInstances.workerInstance.name} (${selectedInstances.workerInstance.instanceRole ?? '-'})`
                      : '-',
                  },
                  {
                    key: 'guardian',
                    label: t('pages.superAgent.guardianSession', '守护会话'),
                    children: selectedInstances?.guardianSession
                      ? `#${selectedInstances.guardianSession.id} ${selectedInstances.guardianSession.title ?? ''} (${selectedInstances.guardianSession.sessionType ?? '-'})`
                      : '-',
                  },
                ]}
              />
            </Spin>
            <ProTable<WorkItem>
              actionRef={workActionRef}
              rowKey="id"
              search={false}
              options={false}
              pagination={false}
              columns={workColumns as any}
              params={{ superAgentId: selected.id, status: workStatus }}
              request={async (params) => {
                const status = (params as any).status as string | undefined;
                const data = await superAgentApi.workItems(selected.id, status);
                return { data, success: true, total: data.length };
              }}
              columnsState={{
                persistenceKey: `super-agent-work-${selected.id}`,
              }}
              toolBarRender={() => [
                <Space key="filter" wrap>
                  {statusOptions.map((option) => (
                    <Tag.CheckableTag
                      key={option.value}
                      checked={workStatus === option.value}
                      onChange={(checked) => {
                        setWorkStatus(checked ? option.value : undefined);
                      }}
                    >
                      {option.label}
                    </Tag.CheckableTag>
                  ))}
                </Space>,
              ]}
            />
          </>
        ) : null}
      </Drawer>

      <Modal
        open={timelineOpen}
        width={900}
        footer={null}
        title={
          timelineItem
            ? `${t('pages.superAgent.timeline', '时间线')} · #${timelineItem.id}`
            : t('pages.superAgent.timeline', '时间线')
        }
        onCancel={() => {
          setTimelineOpen(false);
          setTimelineRows([]);
          setTimelineItem(null);
        }}
        destroyOnHidden
      >
        <Spin spinning={timelineLoading}>
          <List
            dataSource={timelineRows}
            renderItem={(row: any) => {
              const content = row.content ?? {};
              const text =
                content.text ?? content.summary ?? content.response ?? '';
              return (
                <List.Item style={{ display: 'block' }}>
                  <Space size={8}>
                    <Tag>{row.kind}</Tag>
                    <Tag
                      color={row.state === 'COMPLETED' ? 'green' : 'default'}
                    >
                      {row.state ?? '-'}
                    </Tag>
                    <Typography.Text type="secondary">
                      seq {row.seq} · run {row.runId ?? '-'}
                    </Typography.Text>
                  </Space>
                  {row.title ? (
                    <div style={{ fontWeight: 600, marginTop: 4 }}>
                      {row.title}
                    </div>
                  ) : null}
                  {text ? (
                    <div style={{ marginTop: 4, whiteSpace: 'pre-wrap' }}>
                      {String(text)}
                    </div>
                  ) : null}
                </List.Item>
              );
            }}
          />
        </Spin>
      </Modal>

      <Drawer
        open={insightOpen}
        size={900}
        title={
          insightAgent
            ? `${insightMode === 'usage' ? '用量' : insightMode === 'watchdog' ? '巡检' : '渠道'} · ${insightAgent.name}`
            : ''
        }
        onClose={() => {
          setInsightOpen(false);
          setInsightRows([]);
        }}
        destroyOnHidden
      >
        <Spin spinning={insightLoading}>
          {insightMode === 'usage' ? (
            <>
              <Space style={{ marginBottom: 12 }}>
                <span>时间范围</span>
                <Select
                  value={usageDays}
                  style={{ width: 120 }}
                  onChange={(value: number) => {
                    setUsageDays(value);
                    if (insightAgent) openInsight(insightAgent, 'usage', value);
                  }}
                  options={[7, 30, 90].map((value) => ({
                    value,
                    label: `${value} 天`,
                  }))}
                />
              </Space>
              <ProTable
                rowKey={(record: any) => `${record.day}-${record.sessionType}`}
                search={false}
                options={false}
                pagination={false}
                dataSource={insightRows}
                columns={usageColumns as any}
              />
            </>
          ) : null}
          {insightMode === 'watchdog' ? (
            <ProTable
              rowKey="id"
              search={false}
              options={false}
              pagination={false}
              dataSource={insightRows}
              columns={watchdogColumns as any}
            />
          ) : null}
          {insightMode === 'channels' ? (
            <ProTable
              rowKey="id"
              search={false}
              options={false}
              pagination={false}
              dataSource={insightRows}
              columns={channelColumns as any}
            />
          ) : null}
        </Spin>
      </Drawer>

      <Modal
        open={templateOpen}
        width={760}
        title="Group Router 模板"
        confirmLoading={templateSaving}
        onOk={saveTemplate}
        onCancel={() => setTemplateOpen(false)}
        destroyOnHidden
      >
        <Typography.Paragraph type="secondary">
          修改共享 Group Router 实例的 system
          prompt；恢复默认时可粘贴下方默认模板。
        </Typography.Paragraph>
        <Input.TextArea
          value={templateText}
          onChange={(event) => setTemplateText(event.target.value)}
          autoSize={{ minRows: 8, maxRows: 18 }}
        />
        <Button
          size="small"
          style={{ marginTop: 8 }}
          onClick={() => setTemplateText(templateDefault)}
        >
          使用默认模板
        </Button>
      </Modal>

      <Modal
        open={settingsOpen}
        width={720}
        title={`设置 · ${settingsAgent?.name ?? ''}`}
        confirmLoading={settingsSaving}
        onOk={saveSettings}
        onCancel={() => setSettingsOpen(false)}
        destroyOnHidden
      >
        <Form form={settingsForm} layout="vertical">
          <Form.Item
            name="name"
            label="名称"
            rules={[{ required: true, max: 128 }]}
          >
            <Input />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <Input.TextArea rows={3} maxLength={512} showCount />
          </Form.Item>
          <Form.Item
            name="positionCode"
            label={t('pages.superAgent.position', '职位')}
            rules={[
              {
                required: true,
                message: t('pages.superAgent.positionRequired', '请选择职位'),
              },
            ]}
          >
            <Select
              options={positionOptions}
              placeholder={t(
                'pages.superAgent.positionPlaceholder',
                '请选择职位',
              )}
            />
          </Form.Item>
          <Form.Item name="config" label="配置 JSON">
            <Input.TextArea rows={5} />
          </Form.Item>
        </Form>
      </Modal>
    </PageContainer>
  );
}
