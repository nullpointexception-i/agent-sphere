import {
  ClearOutlined,
  DeleteOutlined,
  EditOutlined,
  EyeOutlined,
  PlusOutlined,
  QuestionCircleOutlined,
} from '@ant-design/icons';
import { PageContainer, ProTable } from '@ant-design/pro-components';
import { useIntl } from '@umijs/max';
import {
  App,
  Button,
  DatePicker,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Table,
  Tabs,
  Tag,
  Tooltip,
} from 'antd';
import type dayjs from 'dayjs';
import { useEffect, useMemo, useRef, useState } from 'react';
import { Can } from '@/components/Can';
import { agentApi } from '@/services/agentSphere/api';
import { formatParamDate, formatTime } from '@/utils/format';
import { labelWithRule } from '@/utils/labelWithRule';

export default function CompletionsList() {
  const { message, modal } = App.useApp();
  const intl = useIntl();
  const actionRef = useRef<any>(null);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<any>(null);
  const [form] = Form.useForm();
  const [submitting, setSubmitting] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [timeRange, setTimeRange] = useState<
    [dayjs.Dayjs | null, dayjs.Dayjs | null]
  >([null, null]);
  const [tableScrollY, setTableScrollY] = useState(400);
  const [routes, setRoutes] = useState<any[]>([]);

  const [viewRecord, setViewRecord] = useState<any>(null);
  const [viewLoading, setViewLoading] = useState(false);
  const [prompts, setPrompts] = useState<any[]>([]);
  const [calls, setCalls] = useState<any[]>([]);
  const [callsTotal, setCallsTotal] = useState(0);
  const [callsPage, setCallsPage] = useState(1);
  const [promptOpen, setPromptOpen] = useState(false);
  const [promptForm] = Form.useForm();
  const [promptSubmitting, setPromptSubmitting] = useState(false);
  const [activatingId, setActivatingId] = useState<number | null>(null);

  const t = (id: string, defaultMessage?: string) =>
    intl.formatMessage({ id, defaultMessage });

  const routeMap = useMemo(() => {
    const map = new Map<number, string>();
    routes.forEach((r) => {
      map.set(
        r.id,
        r.modelName
          ? `${r.modelName}${r.providerName ? ` (${r.providerName})` : ''}`
          : `#${r.id}`,
      );
    });
    return map;
  }, [routes]);

  useEffect(() => {
    const calc = () => setTableScrollY(window.innerHeight - 280);
    calc();
    window.addEventListener('resize', calc);
    return () => window.removeEventListener('resize', calc);
  }, []);

  useEffect(() => {
    agentApi.routes
      .listAll()
      .then(setRoutes)
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

  const columns = [
    {
      title: intl.formatMessage({ id: 'pages.table.id' }),
      dataIndex: 'id',
      key: 'id',
      width: 60,
    },
    {
      title: intl.formatMessage({ id: 'pages.table.name' }),
      dataIndex: 'name',
      key: 'name',
      ellipsis: true,
    },
    {
      title: t('pages.admin.completions.modelRoute', '模型路由'),
      key: 'modelRouteId',
      width: 180,
      render: (_: any, record: any) =>
        record.modelRouteId ? (
          <Tag>
            {routeMap.get(record.modelRouteId) || `#${record.modelRouteId}`}
          </Tag>
        ) : (
          <Tag color="orange">
            {t('pages.admin.completions.noRoute', '未绑定')}
          </Tag>
        ),
    },
    {
      title: t('pages.admin.completions.activePrompt', '生效版本'),
      key: 'activePromptId',
      width: 90,
      render: (_: any, record: any) =>
        record.activePromptId ? `v${record.activePromptId}` : '-',
    },
    {
      title: t('pages.admin.completions.status', '状态'),
      dataIndex: 'status',
      key: 'status',
      width: 90,
      render: (status: any) =>
        status === 'ACTIVE' ? (
          <Tag color="green">
            {t('pages.admin.completions.statusActive', '启用')}
          </Tag>
        ) : (
          <Tag>{status}</Tag>
        ),
    },
    {
      title: t('pages.admin.completions.remark', '备注'),
      dataIndex: 'remark',
      key: 'remark',
      width: 140,
      ellipsis: true,
    },
    {
      title: intl.formatMessage({ id: 'pages.table.created' }),
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 160,
      render: (v: any) => formatTime(v),
    },
    {
      title: intl.formatMessage({ id: 'pages.table.actions' }),
      key: 'actions',
      width: 110,
      render: (_: any, record: any) => (
        <>
          <Button
            type="link"
            size="small"
            icon={<EyeOutlined />}
            onClick={() => openDetail(record)}
          />
          <Can code="admin:completions:update">
            <Button
              type="link"
              size="small"
              icon={<EditOutlined />}
              onClick={() => {
                setEditing(record);
                form.setFieldsValue({
                  name: record.name,
                  description: record.description,
                  modelRouteId: record.modelRouteId,
                  inputSchema: record.inputSchema,
                  outputSchema: record.outputSchema,
                  config: record.config,
                  remark: record.remark,
                  businessType: record.businessType,
                });
                setModalOpen(true);
              }}
            />
          </Can>
          <Can code="admin:completions:delete">
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
                    { name: record.name },
                  ),
                  content: intl.formatMessage(
                    {
                      id: 'pages.deleteConfirm.content',
                      defaultMessage:
                        'Are you sure you want to delete this {name}?',
                    },
                    { name: record.name },
                  ),
                  okType: 'danger',
                  onOk: async () => {
                    await agentApi.admin.completions.delete(record.id);
                    message.success(
                      intl.formatMessage({
                        id: 'pages.delete.success',
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

  const handleSubmit = async () => {
    setSubmitting(true);
    try {
      const values = await form.validateFields();
      if (editing) {
        await agentApi.admin.completions.update(editing.id, values);
        message.success(
          intl.formatMessage({
            id: 'pages.update.success',
            defaultMessage: 'Updated',
          }),
        );
      } else {
        await agentApi.admin.completions.create(values);
        message.success(
          intl.formatMessage({
            id: 'pages.create.success',
            defaultMessage: 'Created',
          }),
        );
      }
      setModalOpen(false);
      setEditing(null);
      form.resetFields();
      actionRef.current?.reload();
    } finally {
      setSubmitting(false);
    }
  };

  const openDetail = async (record: any) => {
    setViewRecord(record);
    setViewLoading(true);
    setCallsPage(1);
    try {
      const [d, promptList] = await Promise.all([
        agentApi.admin.completions.get(record.id),
        agentApi.admin.completions.listPrompts(record.id),
      ]);
      setViewRecord(d);
      setPrompts(promptList || []);
      loadCalls(record.id, 1);
    } catch {
      message.error(t('pages.chat.loadFailed', '加载失败'));
    } finally {
      setViewLoading(false);
    }
  };

  const loadCalls = async (completionsId: number, p: number) => {
    try {
      const res = await agentApi.admin.completions.listCalls(
        completionsId,
        p,
        10,
      );
      setCalls(res.records || []);
      setCallsTotal(res.total || 0);
      setCallsPage(p);
    } catch {
      setCalls([]);
      setCallsTotal(0);
    }
  };

  const handleAddPrompt = async () => {
    setPromptSubmitting(true);
    try {
      const values = await promptForm.validateFields();
      const prompt = await agentApi.admin.completions.addPrompt(
        viewRecord.id,
        values,
      );
      setPrompts([...(prompts || []), prompt]);
      message.success(
        intl.formatMessage({
          id: 'pages.create.success',
          defaultMessage: 'Created',
        }),
      );
      setPromptOpen(false);
      promptForm.resetFields();
    } finally {
      setPromptSubmitting(false);
    }
  };

  const handleActivate = (promptId: number) => {
    modal.confirm({
      title: t(
        'pages.admin.completions.activatePromptConfirm',
        '确认激活该版本？',
      ),
      onOk: async () => {
        setActivatingId(promptId);
        try {
          await agentApi.admin.completions.activate(viewRecord.id, promptId);
          message.success(
            intl.formatMessage({
              id: 'pages.update.success',
              defaultMessage: 'Updated',
            }),
          );
          const [d, promptList] = await Promise.all([
            agentApi.admin.completions.get(viewRecord.id),
            agentApi.admin.completions.listPrompts(viewRecord.id),
          ]);
          setViewRecord(d);
          setPrompts(promptList || []);
        } finally {
          setActivatingId(null);
        }
      },
    });
  };

  const promptColumns = [
    {
      title: t('pages.admin.completions.promptVersion', '版本'),
      key: 'version',
      width: 70,
      render: (_: any, record: any) => `v${record.version}`,
    },
    {
      title: t('pages.admin.completions.promptSystem', 'System Prompt'),
      dataIndex: 'promptSystem',
      key: 'promptSystem',
      ellipsis: true,
    },
    {
      title: t('pages.admin.completions.promptUser', 'User Prompt'),
      dataIndex: 'promptUser',
      key: 'promptUser',
      ellipsis: true,
    },
    {
      title: intl.formatMessage({ id: 'pages.table.created' }),
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 150,
      render: (v: any) => formatTime(v),
    },
    {
      title: intl.formatMessage({ id: 'pages.table.actions' }),
      key: 'actions',
      width: 110,
      render: (_: any, record: any) =>
        viewRecord?.activePromptId === record.id ? (
          <Tag color="green">
            {t('pages.admin.completions.active', '当前生效')}
          </Tag>
        ) : (
          <Can code="admin:completions:update">
            <Button
              type="link"
              size="small"
              loading={activatingId === record.id}
              onClick={() => handleActivate(record.id)}
            >
              {t('pages.admin.completions.activate', '激活')}
            </Button>
          </Can>
        ),
    },
  ];

  const callColumns = [
    {
      title: intl.formatMessage({ id: 'pages.table.id' }),
      dataIndex: 'id',
      key: 'id',
      width: 60,
    },
    {
      title: t('pages.admin.completions.calls.model', '模型'),
      dataIndex: 'model',
      key: 'model',
      width: 140,
      render: (v: string) => v || '-',
    },
    {
      title: t('pages.admin.completions.calls.caller', '调用方'),
      dataIndex: 'caller',
      key: 'caller',
      width: 120,
    },
    {
      title: t('pages.admin.completions.calls.usage', 'Usage'),
      dataIndex: 'usage',
      key: 'usage',
      width: 140,
      render: (u: string) =>
        u ? <span style={{ fontSize: 12 }}>{u}</span> : '-',
    },
    {
      title: t('pages.admin.completions.calls.output', '输出'),
      dataIndex: 'output',
      key: 'output',
      ellipsis: true,
    },
    {
      title: intl.formatMessage({ id: 'pages.table.created' }),
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 150,
      render: (v: any) => formatTime(v),
    },
  ];

  const detailRows = [
    {
      label: intl.formatMessage({ id: 'pages.table.id' }),
      value: viewRecord?.id,
    },
    {
      label: intl.formatMessage({ id: 'pages.table.name' }),
      value: viewRecord?.name,
    },
    {
      label: t('pages.admin.completions.description', '描述'),
      value: viewRecord?.description || '-',
    },
    {
      label: t('pages.admin.completions.modelRoute', '模型路由'),
      value: viewRecord?.modelRouteId
        ? routeMap.get(viewRecord.modelRouteId) || `#${viewRecord.modelRouteId}`
        : t('pages.admin.completions.noRoute', '未绑定'),
    },
    {
      label: t('pages.admin.completions.activePrompt', '生效版本'),
      value: viewRecord?.activePromptId ? `v${viewRecord.activePromptId}` : '-',
    },
    {
      label: t('pages.admin.completions.status', '状态'),
      value:
        viewRecord?.status === 'ACTIVE'
          ? t('pages.admin.completions.statusActive', '启用')
          : viewRecord?.status || '-',
    },
    {
      label: t('pages.admin.completions.remark', '备注'),
      value: viewRecord?.remark || '-',
    },
    {
      label: t('pages.admin.completions.inputSchema', 'Input Schema'),
      value: viewRecord?.inputSchema || '-',
    },
    {
      label: t('pages.admin.completions.outputSchema', 'Output Schema'),
      value: viewRecord?.outputSchema || '-',
    },
    {
      label: t('pages.admin.completions.config', 'Config'),
      value: viewRecord?.config || '-',
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
          startTime: formatParamDate(timeRange[0]),
          endTime: formatParamDate(timeRange[1]?.endOf('day')),
        }}
        request={async (p) => {
          const res = await agentApi.admin.completions.list({
            keyword: p.keyword,
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
            placeholder={intl.formatMessage({ id: 'pages.search.placeholder' })}
            style={{ width: 200 }}
            onSearch={(value) => setKeyword(value)}
            allowClear
            onClear={() => setKeyword('')}
            maxLength={255}
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
              setTimeRange([null, null]);
              actionRef.current?.reload();
            }}
          />,
          <Can key="new" code="admin:completions:create">
            <Button
              type="primary"
              icon={<PlusOutlined />}
              onClick={() => {
                setEditing(null);
                form.resetFields();
                setModalOpen(true);
              }}
            />
          </Can>,
        ]}
      />

      <Modal
        title={
          editing
            ? t('pages.admin.completions.edit', '编辑 Completions')
            : t('pages.admin.completions.create', '新建 Completions')
        }
        open={modalOpen}
        onOk={handleSubmit}
        onCancel={() => {
          setModalOpen(false);
          setEditing(null);
        }}
        confirmLoading={submitting}
        width={680}
      >
        <Form form={form} layout="vertical">
          <Form.Item
            name="name"
            label={labelWithRule(
              intl.formatMessage({ id: 'pages.form.name' }),
              intl.formatMessage({ id: 'pages.hint.name' }),
            )}
            rules={[{ required: true }]}
          >
            <Input maxLength={200} />
          </Form.Item>
          <Form.Item
            name="description"
            label={labelWithRule(
              intl.formatMessage({ id: 'pages.form.description' }),
              intl.formatMessage({ id: 'pages.hint.description' }),
            )}
          >
            <Input.TextArea rows={2} maxLength={2000} />
          </Form.Item>
          <Form.Item
            name="modelRouteId"
            label={labelWithRule(
              t('pages.admin.completions.modelRoute', '模型路由'),
              t(
                'pages.admin.completions.modelRoute.extra',
                '留空则该能力不可执行',
              ),
            )}
          >
            <Select
              allowClear
              showSearch
              optionFilterProp="label"
              placeholder={t(
                'pages.admin.completions.modelRoute.placeholder',
                '选择模型路由',
              )}
              options={routes.map((r) => ({
                value: r.id,
                label: routeMap.get(r.id) || `#${r.id}`,
              }))}
            />
          </Form.Item>
          <Form.Item
            name="inputSchema"
            label={labelWithRule(
              t('pages.admin.completions.inputSchema', 'Input Schema (JSON)'),
              intl.formatMessage({ id: 'pages.hint.text' }),
            )}
            rules={[jsonRule]}
          >
            <Input.TextArea
              rows={4}
              placeholder='{"type":"object","properties":{}}'
            />
          </Form.Item>
          <Form.Item
            name="outputSchema"
            label={labelWithRule(
              t('pages.admin.completions.outputSchema', 'Output Schema (JSON)'),
              intl.formatMessage({ id: 'pages.hint.text' }),
            )}
            rules={[jsonRule]}
          >
            <Input.TextArea
              rows={4}
              placeholder='{"type":"object","properties":{}}'
            />
          </Form.Item>
          <Form.Item
            name="config"
            label={
              <Space size={4}>
                {t('pages.admin.completions.config', 'Config (JSON)')}
                <Tooltip
                  title={t(
                    'pages.admin.completions.config.help',
                    '支持参数（可选）：\ntemperature: 数字，如 0.1\nmax_tokens: 数字\ntop_p: 数字\npresence_penalty / frequency_penalty: 数字\nstop: 字符串数组，如 ["END"]\nthinking / reasoning: false=关闭思考, true=开启, 或 "disabled"/"enabled"\n示例: temperature=0.1, thinking=false, max_tokens=1024',
                  )}
                >
                  <QuestionCircleOutlined
                    style={{ color: '#999', cursor: 'pointer' }}
                  />
                </Tooltip>
              </Space>
            }
            rules={[jsonRule]}
          >
            <Input.TextArea
              rows={2}
              placeholder='{"temperature":0.1,"thinking":false}'
            />
          </Form.Item>
          <Form.Item
            name="remark"
            label={t('pages.admin.completions.remark', '备注')}
          >
            <Input.TextArea rows={2} maxLength={500} />
          </Form.Item>
          <Form.Item
            name="businessType"
            label={t('pages.admin.completions.businessType', '业务域')}
            extra={t(
              'pages.admin.completions.businessType.extra',
              '外部 API 按 businessType 匹配该能力，需与调用方一致',
            )}
          >
            <Input maxLength={64} placeholder="sourcing" />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={viewRecord?.name}
        open={!!viewRecord}
        onCancel={() => setViewRecord(null)}
        footer={null}
        width={820}
        destroyOnHidden
      >
        <Tabs
          items={[
            {
              key: 'info',
              label: t('pages.admin.completions.detail', '基本信息'),
              children: (
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
                            width: 150,
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
              ),
            },
            {
              key: 'prompts',
              label: t('pages.admin.completions.prompts', 'Prompt 版本'),
              children: (
                <>
                  <div style={{ marginBottom: 8, textAlign: 'right' }}>
                    <Can code="admin:completions:update">
                      <Button
                        size="small"
                        type="primary"
                        icon={<PlusOutlined />}
                        onClick={() => setPromptOpen(true)}
                      >
                        {t('pages.admin.completions.addPrompt', '新增版本')}
                      </Button>
                    </Can>
                  </div>
                  <Table
                    rowKey="id"
                    size="small"
                    dataSource={prompts}
                    columns={promptColumns}
                    pagination={false}
                    loading={viewLoading}
                  />
                </>
              ),
            },
            {
              key: 'calls',
              label: t('pages.admin.completions.calls', '调用记录'),
              children: (
                <Table
                  rowKey="id"
                  size="small"
                  dataSource={calls}
                  columns={callColumns}
                  loading={viewLoading}
                  pagination={{
                    current: callsPage,
                    pageSize: 10,
                    total: callsTotal,
                    showSizeChanger: false,
                    showQuickJumper: false,
                    showTotal: (n) =>
                      intl.formatMessage(
                        {
                          id: 'pages.table.total',
                          defaultMessage: 'Total {n} items',
                        },
                        { n },
                      ),
                    onChange: (p) => {
                      if (viewRecord) loadCalls(viewRecord.id, p);
                    },
                  }}
                />
              ),
            },
          ]}
        />
      </Modal>

      <Modal
        title={t('pages.admin.completions.addPrompt', '新增 Prompt 版本')}
        open={promptOpen}
        onOk={handleAddPrompt}
        onCancel={() => {
          setPromptOpen(false);
          promptForm.resetFields();
        }}
        confirmLoading={promptSubmitting}
        width={640}
        zIndex={1100}
      >
        <Form form={promptForm} layout="vertical">
          <Form.Item
            name="promptSystem"
            label={labelWithRule(
              t('pages.admin.completions.promptSystem', 'System Prompt'),
              t(
                'pages.admin.completions.promptPlaceholder',
                '支持 input 与 field 占位符',
              ),
            )}
          >
            <Input.TextArea rows={5} />
          </Form.Item>
          <Form.Item
            name="promptUser"
            label={t('pages.admin.completions.promptUser', 'User Prompt')}
            rules={[{ required: true }]}
          >
            <Input.TextArea rows={3} placeholder="{{input}}" />
          </Form.Item>
        </Form>
      </Modal>
    </PageContainer>
  );
}
