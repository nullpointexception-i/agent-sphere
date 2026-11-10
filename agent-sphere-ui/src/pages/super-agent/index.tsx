import {
  AlertOutlined,
  ApiOutlined,
  BarChartOutlined,
  HistoryOutlined,
  PauseCircleOutlined,
  PlayCircleOutlined,
  PlusOutlined,
  RedoOutlined,
  RobotOutlined,
  SettingOutlined,
  StopOutlined,
  UnorderedListOutlined,
} from '@ant-design/icons';
import { PageContainer, ProTable } from '@ant-design/pro-components';
import { useIntl } from '@umijs/max';
import {
  App,
  Avatar,
  Button,
  Card,
  Descriptions,
  Drawer,
  Empty,
  Form,
  Input,
  List,
  Modal,
  Pagination,
  Select,
  Space,
  Spin,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import { useEffect, useRef, useState } from 'react';
import { Can } from '@/components/Can';
import { PERM } from '@/constants/permissions';
import {
  STATUS,
  SUPER_AGENT_STATUS,
  WORK_ITEM_ACTIVE_STATUSES,
  WORK_ITEM_STATUS,
} from '@/constants/status';
import { dictApi, type SystemDicEntry } from '@/services/agentSphere/dict';
import {
  type SuperAgent,
  type SuperAgentInstances,
  superAgentApi,
  type WorkItem,
} from '@/services/agentSphere/superAgent';

const AGENT_STATUS = SUPER_AGENT_STATUS;

const WORK_STATUS = WORK_ITEM_STATUS;

const SUPER_AGENT_PAGE_SIZE = 9;

function StatusTag({
  value,
  map,
}: {
  value?: string;
  map: Record<string, { color: string; labelKey: string }>;
}) {
  const intl = useIntl();
  const cfg = value ? map[value] : undefined;
  return cfg ? (
    <Tag color={cfg.color}>
      {intl.formatMessage({ id: cfg.labelKey, defaultMessage: value })}
    </Tag>
  ) : (
    <Tag>{value ?? '-'}</Tag>
  );
}

export default function SuperAgentPage() {
  const { message, modal } = App.useApp();
  const intl = useIntl();
  const workActionRef = useRef<any>(null);
  const [agents, setAgents] = useState<SuperAgent[]>([]);
  const [listLoading, setListLoading] = useState(false);
  const [page, setPage] = useState(1);
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

  const loadAgents = async () => {
    setListLoading(true);
    try {
      setAgents((await superAgentApi.list()) || []);
    } finally {
      setListLoading(false);
    }
  };

  useEffect(() => {
    loadAgents();
  }, []);

  useEffect(() => {
    const maxPage = Math.max(
      1,
      Math.ceil(agents.length / SUPER_AGENT_PAGE_SIZE),
    );
    if (page > maxPage) setPage(maxPage);
  }, [agents.length, page]);

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
      message.success(
        t('pages.superAgent.createSuccess', 'Super Agent created'),
      );
      setCreateOpen(false);
      form.resetFields();
      await loadAgents();
      setPage(1);
    } finally {
      setSubmitting(false);
    }
  };

  const toggle = async (record: SuperAgent) => {
    const pause = record.status === STATUS.ACTIVE;
    modal.confirm({
      title: pause
        ? t(
            'pages.superAgent.pauseConfirm',
            'Deliveries will be skipped after pausing. Continue?',
          )
        : t('pages.superAgent.resumeConfirm', 'Resume this Super Agent?'),
      onOk: async () => {
        if (pause) {
          await superAgentApi.pause(record.id);
        } else {
          await superAgentApi.resume(record.id);
        }
        message.success(t('pages.superAgent.updated', 'Status updated'));
        loadAgents();
      },
    });
  };

  const retryWorkItem = async (record: WorkItem) => {
    if (!selected) return;
    await superAgentApi.retryWorkItem(selected.id, record.id);
    message.success(t('pages.superAgent.retried', 'Queued again'));
    workActionRef.current?.reload();
  };

  const cancelWorkItem = (record: WorkItem) => {
    if (!selected) return;
    modal.confirm({
      title: t(
        'pages.superAgent.cancelConfirm',
        'Worker Run will be stopped after cancelling. Continue?',
      ),
      onOk: async () => {
        await superAgentApi.cancelWorkItem(selected.id, record.id);
        message.success(t('pages.superAgent.cancelled', 'Task cancelled'));
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
      message.success(t('pages.superAgent.updated', 'Status updated'));
      setSettingsOpen(false);
      loadAgents();
    } finally {
      setSettingsSaving(false);
    }
  };

  const toggleChannel = async (record: any) => {
    if (!insightAgent) return;
    const next = record.status === STATUS.ACTIVE ? 'DISABLED' : 'ACTIVE';
    await superAgentApi.updateChannelStatus(insightAgent.id, record.id, next);
    await openInsight(insightAgent, 'channels');
  };

  const workColumns = [
    {
      title: t('pages.table.id', 'ID'),
      dataIndex: 'id',
      width: 70,
    },
    {
      title: t('pages.superAgent.workTitle', 'Task'),
      dataIndex: 'title',
      ellipsis: true,
    },
    {
      title: t('pages.superAgent.status', 'Status'),
      dataIndex: 'status',
      width: 110,
      render: (v: string) => <StatusTag value={v} map={WORK_STATUS} />,
    },
    {
      title: t('pages.superAgent.attempt', 'Attempt'),
      dataIndex: 'attempt',
      width: 80,
      render: (v: number) => v ?? 1,
    },
    {
      title: t('pages.superAgent.workerSession', 'Worker Session'),
      dataIndex: 'workerSessionId',
      width: 120,
      render: (v: number) => (v ? `#${v}` : '-'),
    },
    {
      title: t('pages.superAgent.lastProgress', 'Last Progress'),
      dataIndex: 'lastProgressAt',
      width: 170,
    },
    {
      title: t('pages.superAgent.summary', 'Result'),
      dataIndex: 'summary',
      ellipsis: true,
    },
    {
      title: t('pages.table.action', 'Actions'),
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
            {t('pages.superAgent.timeline', 'Timeline')}
          </Button>
        ) : null,
        <Can key="retry" code={PERM.superAgentUpdate}>
          {record.status === STATUS.WORK_ITEM.FAILED ||
          record.status === STATUS.WORK_ITEM.CANCELLED ? (
            <Button
              type="link"
              size="small"
              icon={<RedoOutlined />}
              onClick={() => retryWorkItem(record)}
            >
              {t('pages.superAgent.retry', 'Retry')}
            </Button>
          ) : null}
        </Can>,
        <Can key="cancel" code={PERM.superAgentUpdate}>
          {WORK_ITEM_ACTIVE_STATUSES.has(record.status) ? (
            <Button
              type="link"
              size="small"
              danger
              icon={<StopOutlined />}
              onClick={() => cancelWorkItem(record)}
            >
              {t('pages.superAgent.cancel', 'Cancel')}
            </Button>
          ) : null}
        </Can>,
      ],
    },
  ];

  const usageColumns = [
    { title: t('pages.superAgent.date', 'Date'), dataIndex: 'day', width: 110 },
    {
      title: t('pages.superAgent.sessionType', 'Session Type'),
      dataIndex: 'sessionType',
      width: 130,
    },
    { title: 'Run', dataIndex: 'runCount', width: 80 },
    {
      title: t('pages.superAgent.calls', 'Calls'),
      dataIndex: 'interactionCount',
      width: 80,
    },
    { title: 'Prompt', dataIndex: 'promptTokens', width: 100 },
    { title: 'Completion', dataIndex: 'completionTokens', width: 110 },
    { title: 'Total', dataIndex: 'totalTokens', width: 100 },
    { title: 'Cache Hit', dataIndex: 'cacheHitTokens', width: 100 },
    { title: 'Cache Miss', dataIndex: 'cacheMissTokens', width: 105 },
  ];

  const watchdogColumns = [
    { title: 'ID', dataIndex: 'id', width: 70 },
    {
      title: t('pages.superAgent.status', 'Status'),
      dataIndex: 'status',
      width: 100,
    },
    { title: 'Dispatched', dataIndex: 'dispatchedStuck', width: 110 },
    { title: 'Delivery', dataIndex: 'deliveryStuck', width: 100 },
    { title: 'Outbound', dataIndex: 'outboundStuck', width: 100 },
    {
      title: t('pages.superAgent.start', 'Start'),
      dataIndex: 'startedAt',
      width: 170,
    },
    {
      title: t('pages.superAgent.end', 'End'),
      dataIndex: 'finishedAt',
      width: 170,
    },
    {
      title: t('pages.superAgent.error', 'Error'),
      dataIndex: 'errorMessage',
      ellipsis: true,
    },
  ];

  const channelColumns = [
    { title: 'ID', dataIndex: 'id', width: 70 },
    {
      title: t('pages.superAgent.channelType', 'Type'),
      dataIndex: 'channelType',
      width: 130,
    },
    {
      title: t('pages.superAgent.channelName', 'Name'),
      dataIndex: 'name',
      ellipsis: true,
    },
    {
      title: t('pages.superAgent.status', 'Status'),
      dataIndex: 'status',
      width: 100,
    },
    {
      title: t('pages.superAgent.actions', 'Actions'),
      valueType: 'option',
      width: 100,
      render: (_: unknown, record: any) => (
        <Can code={PERM.superAgentUpdate}>
          <Button
            type="link"
            size="small"
            onClick={() => toggleChannel(record)}
          >
            {record.status === STATUS.ACTIVE
              ? t('pages.superAgent.disable', 'Disable')
              : t('pages.superAgent.enable', 'Enable')}
          </Button>
        </Can>
      ),
    },
  ];

  const statusOptions = Object.entries(WORK_STATUS).map(([value, cfg]) => ({
    value,
    label: intl.formatMessage({ id: cfg.labelKey, defaultMessage: value }),
  }));

  const pagedAgents = agents.slice(
    (page - 1) * SUPER_AGENT_PAGE_SIZE,
    page * SUPER_AGENT_PAGE_SIZE,
  );

  return (
    <PageContainer title={false} breadcrumbRender={false}>
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: 12,
          marginBottom: 16,
          flexWrap: 'wrap',
        }}
      >
        <Typography.Title level={4} style={{ margin: 0 }}>
          {t('pages.superAgent.title', 'Super Agents')}
        </Typography.Title>
        <Space wrap>
          <Can key="create" code={PERM.superAgentCreate}>
            <Button
              type="primary"
              icon={<PlusOutlined />}
              onClick={() => setCreateOpen(true)}
            >
              {t('pages.superAgent.create', 'New Super Agent')}
            </Button>
          </Can>
        </Space>
      </div>
      <Spin spinning={listLoading}>
        {agents.length === 0 ? (
          <Empty
            description={t(
              'pages.superAgent.empty',
              'No digital employees yet',
            )}
          />
        ) : (
          <>
            <div
              style={{
                display: 'grid',
                gridTemplateColumns: 'repeat(auto-fill, minmax(320px, 1fr))',
                gap: 16,
              }}
            >
              {pagedAgents.map((agent) => (
                <Card key={agent.id} size="small" hoverable>
                  <div style={{ display: 'flex', gap: 12 }}>
                    <Avatar
                      size={44}
                      icon={<RobotOutlined />}
                      style={{
                        background: '#f1efff',
                        color: '#6c5ce7',
                        flexShrink: 0,
                      }}
                    />
                    <div style={{ flex: 1, minWidth: 0 }}>
                      <div
                        style={{
                          display: 'flex',
                          alignItems: 'center',
                          gap: 8,
                        }}
                      >
                        <Typography.Text
                          strong
                          ellipsis
                          style={{ flex: 1, minWidth: 0 }}
                        >
                          {agent.name}
                        </Typography.Text>
                        <StatusTag value={agent.status} map={AGENT_STATUS} />
                      </div>
                      <Typography.Paragraph
                        type="secondary"
                        ellipsis={{ rows: 2 }}
                        style={{
                          margin: '8px 0 0',
                          fontSize: 12,
                          minHeight: 36,
                        }}
                      >
                        {agent.description || '-'}
                      </Typography.Paragraph>
                      <Space size={4} wrap style={{ marginTop: 8 }}>
                        {agent.positionCode && (
                          <Tag color="purple">{positionName(agent)}</Tag>
                        )}
                        {agent.createdAt && <Tag>{agent.createdAt}</Tag>}
                      </Space>
                    </div>
                  </div>
                  <div
                    style={{
                      display: 'flex',
                      justifyContent: 'flex-end',
                      marginTop: 12,
                      flexWrap: 'wrap',
                    }}
                  >
                    <Tooltip title={t('pages.superAgent.tasks', 'Tasks')}>
                      <Button
                        type="text"
                        size="small"
                        icon={<UnorderedListOutlined />}
                        onClick={() => {
                          setWorkStatus(undefined);
                          setSelected(agent);
                        }}
                      />
                    </Tooltip>
                    <Tooltip title={t('pages.superAgent.usage', 'Usage')}>
                      <Button
                        type="text"
                        size="small"
                        icon={<BarChartOutlined />}
                        onClick={() => openInsight(agent, 'usage')}
                      />
                    </Tooltip>
                    <Tooltip title={t('pages.superAgent.watchdog', 'Watchdog')}>
                      <Button
                        type="text"
                        size="small"
                        icon={<AlertOutlined />}
                        onClick={() => openInsight(agent, 'watchdog')}
                      />
                    </Tooltip>
                    <Tooltip title={t('pages.superAgent.channels', 'Channels')}>
                      <Button
                        type="text"
                        size="small"
                        icon={<ApiOutlined />}
                        onClick={() => openInsight(agent, 'channels')}
                      />
                    </Tooltip>
                    <Can code={PERM.superAgentUpdate}>
                      <Tooltip
                        title={t('pages.superAgent.settings', 'Settings')}
                      >
                        <Button
                          type="text"
                          size="small"
                          icon={<SettingOutlined />}
                          onClick={() => openSettings(agent)}
                        />
                      </Tooltip>
                    </Can>
                    <Can code={PERM.superAgentUpdate}>
                      <Tooltip
                        title={
                          agent.status === STATUS.ACTIVE
                            ? t('pages.superAgent.pause', 'Pause')
                            : t('pages.superAgent.resume', 'Resume')
                        }
                      >
                        <Button
                          type="text"
                          size="small"
                          icon={
                            agent.status === STATUS.ACTIVE ? (
                              <PauseCircleOutlined />
                            ) : (
                              <PlayCircleOutlined />
                            )
                          }
                          onClick={() => toggle(agent)}
                        />
                      </Tooltip>
                    </Can>
                  </div>
                </Card>
              ))}
            </div>
            <div
              style={{
                display: 'flex',
                justifyContent: 'flex-end',
                marginTop: 16,
              }}
            >
              <Pagination
                current={page}
                pageSize={SUPER_AGENT_PAGE_SIZE}
                total={agents.length}
                showSizeChanger={false}
                onChange={setPage}
              />
            </div>
          </>
        )}
      </Spin>

      <Modal
        open={createOpen}
        title={t('pages.superAgent.create', 'New Super Agent')}
        onCancel={() => setCreateOpen(false)}
        onOk={submitCreate}
        confirmLoading={submitting}
        destroyOnHidden
      >
        <Form form={form} layout="vertical">
          <Form.Item
            name="name"
            label={t('pages.superAgent.name', 'Name')}
            rules={[{ required: true, max: 128 }]}
          >
            <Input />
          </Form.Item>
          <Form.Item
            name="description"
            label={t('pages.superAgent.description', 'Description')}
          >
            <Input.TextArea rows={3} maxLength={512} showCount />
          </Form.Item>
          <Form.Item
            name="positionCode"
            label={t('pages.superAgent.position', 'Role')}
            rules={[
              {
                required: true,
                message: t(
                  'pages.superAgent.positionRequired',
                  'Select a role',
                ),
              },
            ]}
          >
            <Select
              options={positionOptions}
              placeholder={t(
                'pages.superAgent.positionPlaceholder',
                'Select a role',
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
            ? `${t('pages.superAgent.tasks', 'Tasks')} · ${selected.name}`
            : t('pages.superAgent.tasks', 'Tasks')
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
                    label: t(
                      'pages.superAgent.routerInstance',
                      'Router Instance',
                    ),
                    children: selectedInstances?.routerInstance
                      ? `#${selectedInstances.routerInstance.id} ${selectedInstances.routerInstance.name} (${selectedInstances.routerInstance.instanceRole ?? '-'})`
                      : '-',
                  },
                  {
                    key: 'worker',
                    label: t(
                      'pages.superAgent.workerInstance',
                      'Worker Instance',
                    ),
                    children: selectedInstances?.workerInstance
                      ? `#${selectedInstances.workerInstance.id} ${selectedInstances.workerInstance.name} (${selectedInstances.workerInstance.instanceRole ?? '-'})`
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
            ? `${t('pages.superAgent.timeline', 'Timeline')} · #${timelineItem.id}`
            : t('pages.superAgent.timeline', 'Timeline')
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
                      color={
                        row.state === STATUS.WORK_ITEM.COMPLETED
                          ? 'green'
                          : 'default'
                      }
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
            ? `${insightMode === 'usage' ? t('pages.superAgent.usage', 'Usage') : insightMode === 'watchdog' ? t('pages.superAgent.watchdog', 'Watchdog') : t('pages.superAgent.channels', 'Channels')} · ${insightAgent.name}`
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
                <span>{t('pages.superAgent.timeRange', 'Time range')}</span>
                <Select
                  value={usageDays}
                  style={{ width: 120 }}
                  onChange={(value: number) => {
                    setUsageDays(value);
                    if (insightAgent) openInsight(insightAgent, 'usage', value);
                  }}
                  options={[7, 30, 90].map((value) => ({
                    value,
                    label: intl.formatMessage(
                      {
                        id: 'pages.superAgent.days',
                        defaultMessage: '{days} days',
                      },
                      { days: value },
                    ),
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
        open={settingsOpen}
        width={720}
        title={intl.formatMessage(
          {
            id: 'pages.superAgent.settingsTitle',
            defaultMessage: 'Settings · {name}',
          },
          { name: settingsAgent?.name ?? '' },
        )}
        confirmLoading={settingsSaving}
        onOk={saveSettings}
        onCancel={() => setSettingsOpen(false)}
        destroyOnHidden
      >
        <Form form={settingsForm} layout="vertical">
          <Form.Item
            name="name"
            label={t('pages.superAgent.name', 'Name')}
            rules={[{ required: true, max: 128 }]}
          >
            <Input />
          </Form.Item>
          <Form.Item
            name="description"
            label={t('pages.superAgent.description', 'Description')}
          >
            <Input.TextArea rows={3} maxLength={512} showCount />
          </Form.Item>
          <Form.Item
            name="positionCode"
            label={t('pages.superAgent.position', 'Role')}
            rules={[
              {
                required: true,
                message: t(
                  'pages.superAgent.positionRequired',
                  'Select a role',
                ),
              },
            ]}
          >
            <Select
              options={positionOptions}
              placeholder={t(
                'pages.superAgent.positionPlaceholder',
                'Select a role',
              )}
            />
          </Form.Item>
          <Form.Item
            name="config"
            label={t('pages.superAgent.configJson', 'Config JSON')}
          >
            <Input.TextArea rows={5} />
          </Form.Item>
        </Form>
      </Modal>
    </PageContainer>
  );
}
