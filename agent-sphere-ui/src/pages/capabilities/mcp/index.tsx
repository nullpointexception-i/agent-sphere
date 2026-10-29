import {
  ClearOutlined,
  DeleteOutlined,
  EditOutlined,
  EyeOutlined,
  MoreOutlined,
  PlusOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import { PageContainer, ProTable } from '@ant-design/pro-components';
import { useIntl } from '@umijs/max';
import {
  Alert,
  App,
  Button,
  DatePicker,
  Drawer,
  Dropdown,
  Empty,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Switch,
  Tag,
  Typography,
} from 'antd';
import type dayjs from 'dayjs';
import { useEffect, useRef, useState } from 'react';
import { Can } from '@/components/Can';
import { agentApi } from '@/services/agentSphere/api';
import { formatParamDate, formatTime } from '@/utils/format';
import { labelWithRule } from '@/utils/labelWithRule';

const { Text } = Typography;

interface McpTool {
  name: string;
  description?: string;
  inputSchema?: string;
}

export default function McpList() {
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
  const [selectedRowKeys, setSelectedRowKeys] = useState<number[]>([]);
  const [viewRecord, setViewRecord] = useState<any>(null);
  const [toolsOpen, setToolsOpen] = useState(false);
  const [toolsLoading, setToolsLoading] = useState(false);
  const [toolsError, setToolsError] = useState<string | null>(null);
  const [toolsRecord, setToolsRecord] = useState<any>(null);
  const [tools, setTools] = useState<McpTool[]>([]);
  const [callToolState, setCallToolState] = useState<{
    mcpId: number;
    toolName: string;
    args: string;
    open: boolean;
    loading: boolean;
    result?: string;
    error?: string;
  }>({ mcpId: 0, toolName: '', args: '{}', open: false, loading: false });

  useEffect(() => {
    const calc = () => setTableScrollY(window.innerHeight - 280);
    calc();
    window.addEventListener('resize', calc);
    return () => window.removeEventListener('resize', calc);
  }, []);

  const handleTestConnection = async (id: number) => {
    try {
      const res = await agentApi.mcp.test(id);
      if (res?.ok) {
        message.success(
          intl.formatMessage(
            {
              id: 'pages.capabilities.mcp.testSuccess',
              defaultMessage: '连接成功，发现 {count} 个工具',
            },
            { count: res.toolCount ?? 0 },
          ),
        );
      } else {
        message.error(
          res?.message || intl.formatMessage({ id: 'pages.chat.saveFailed' }),
        );
      }
    } catch (e: any) {
      message.error(e?.message || '连接测试失败');
    }
  };

  const openTools = async (record: any) => {
    setToolsRecord(record);
    setToolsOpen(true);
    setTools([]);
    setToolsError(null);
    setToolsLoading(true);
    try {
      const res = await agentApi.mcp.listTools(record.id);
      setTools(Array.isArray(res) ? res : []);
    } catch (e: any) {
      setToolsError(e?.message || '获取工具列表失败');
    } finally {
      setToolsLoading(false);
    }
  };

  const openCallTool = (tool: McpTool) => {
    setCallToolState({
      mcpId: toolsRecord?.id,
      toolName: tool.name,
      args: tool.inputSchema && tool.inputSchema !== '{}' ? '{}' : '{}',
      open: true,
      loading: false,
    });
  };

  const handleCallTool = async () => {
    const { mcpId, toolName, args, loading } = callToolState;
    if (loading) return;
    let parsed: any = {};
    try {
      parsed = args?.trim() ? JSON.parse(args) : {};
    } catch {
      message.error(
        intl.formatMessage({ id: 'pages.capabilities.mcp.argsInvalid' }),
      );
      return;
    }
    setCallToolState((s) => ({
      ...s,
      loading: true,
      error: undefined,
      result: undefined,
    }));
    try {
      const res = await agentApi.mcp.callTool(mcpId, toolName, parsed);
      setCallToolState((s) => ({
        ...s,
        loading: false,
        result: typeof res === 'string' ? res : JSON.stringify(res, null, 2),
      }));
    } catch (e: any) {
      setCallToolState((s) => ({
        ...s,
        loading: false,
        error: e?.message || '工具调用失败',
      }));
    }
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
      title: intl.formatMessage({ id: 'pages.table.description' }),
      dataIndex: 'description',
      key: 'description',
      ellipsis: true,
    },
    {
      title: intl.formatMessage({
        id: 'pages.capabilities.accessMode',
        defaultMessage: 'Policy',
      }),
      key: 'policy',
      width: 220,
      render: (_: any, r: any) => (
        <Space size={4} wrap>
          {r.compressEnabled ? <Tag color="blue">compress</Tag> : null}
          <Tag>{r.accessMode || 'READ_WRITE'}</Tag>
          <Tag color={r.securityLevel === 'L2' ? 'orange' : 'default'}>
            {r.securityLevel || 'L1'}
            {r.securityLevel === 'L2' ? ` / ${r.approvalScope || 'ONCE'}` : ''}
          </Tag>
        </Space>
      ),
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
      width: 180,
      render: (_: any, record: any) => (
        <>
          <Button
            type="link"
            size="small"
            icon={<EyeOutlined />}
            onClick={() => setViewRecord(record)}
          />
          <Can code="capability:mcp:update">
            <Button
              type="link"
              size="small"
              icon={<EditOutlined />}
              onClick={() => {
                setEditing(record);
                form.resetFields();
                form.setFieldsValue(record);
                setModalOpen(true);
              }}
            />
          </Can>
          <Can code="capability:mcp:delete">
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
                    { name: 'MCP' },
                  ),
                  content: intl.formatMessage(
                    {
                      id: 'pages.deleteConfirm.content',
                      defaultMessage:
                        'Are you sure you want to delete this {name}?',
                    },
                    { name: 'MCP' },
                  ),
                  okType: 'danger',
                  onOk: async () => {
                    await agentApi.mcp.delete(record.id);
                    message.success('Deleted');
                    actionRef.current?.reload();
                  },
                });
              }}
            />
          </Can>
          <Can code="capability:mcp:test">
            <Dropdown
              menu={{
                items: [
                  {
                    key: 'test',
                    label: intl.formatMessage({
                      id: 'pages.capabilities.mcp.test',
                    }),
                    onClick: () => handleTestConnection(record.id),
                  },
                  {
                    key: 'tools',
                    label: intl.formatMessage({
                      id: 'pages.capabilities.mcp.tools',
                    }),
                    onClick: () => openTools(record),
                  },
                ],
              }}
              trigger={['click']}
            >
              <Button type="link" size="small" icon={<MoreOutlined />} />
            </Dropdown>
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
        await agentApi.mcp.update(editing.id, values);
        message.success('Updated');
      } else {
        await agentApi.mcp.create(values);
        message.success('Created');
      }
      setModalOpen(false);
      setEditing(null);
      form.resetFields();
      actionRef.current?.reload();
    } finally {
      setSubmitting(false);
    }
  };

  const handleBatchDelete = () => {
    if (selectedRowKeys.length === 0) return;
    modal.confirm({
      title: intl.formatMessage(
        { id: 'pages.deleteConfirm.title', defaultMessage: 'Delete {name}' },
        { name: `${selectedRowKeys.length} MCPs` },
      ),
      content: intl.formatMessage(
        {
          id: 'pages.deleteConfirm.content',
          defaultMessage: 'Are you sure you want to delete this {name}?',
        },
        { name: 'MCP' },
      ),
      okType: 'danger',
      onOk: async () => {
        await agentApi.mcp.batchDelete(selectedRowKeys);
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
          const res = await agentApi.mcp.list({
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
        rowSelection={{
          selectedRowKeys,
          onChange: (keys: any) => setSelectedRowKeys(keys),
        }}
        toolBarRender={() => [
          selectedRowKeys.length > 0 && (
            <Button
              key="batchDelete"
              danger
              icon={<DeleteOutlined />}
              onClick={handleBatchDelete}
            >
              {intl.formatMessage(
                { id: 'pages.batchDelete', defaultMessage: 'Delete ({count})' },
                { count: selectedRowKeys.length },
              )}
            </Button>
          ),
          <Input.Search
            key="search"
            placeholder={intl.formatMessage({ id: 'pages.search.placeholder' })}
            style={{ width: 200 }}
            onSearch={(value) => {
              setKeyword(value);
            }}
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
              if (dates && dates[0] && dates[1]) {
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
          <Can key="new" code="capability:mcp:create">
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
            ? intl.formatMessage({
                id: 'pages.modal.editMcp',
                defaultMessage: 'Edit MCP',
              })
            : intl.formatMessage({
                id: 'pages.modal.newMcp',
                defaultMessage: 'New MCP',
              })
        }
        open={modalOpen}
        onOk={handleSubmit}
        onCancel={() => {
          setModalOpen(false);
          setEditing(null);
        }}
        confirmLoading={submitting}
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
            <Input maxLength={64} />
          </Form.Item>
          <Form.Item
            name="description"
            label={labelWithRule(
              intl.formatMessage({ id: 'pages.form.description' }),
              intl.formatMessage({ id: 'pages.hint.description' }),
            )}
          >
            <Input.TextArea rows={2} maxLength={255} />
          </Form.Item>
          <Form.Item
            name="serverUrl"
            label={labelWithRule(
              intl.formatMessage({ id: 'pages.capabilities.serverUrl' }),
              intl.formatMessage({ id: 'pages.hint.url' }),
            )}
            rules={[
              { required: true },
              {
                validator: (_: any, value: any) => {
                  if (!value || /^https?:\/\//i.test(String(value).trim())) {
                    return Promise.resolve();
                  }
                  return Promise.reject(
                    new Error(
                      intl.formatMessage({
                        id: 'pages.capabilities.mcp.serverUrlInvalid',
                        defaultMessage: '以 http:// 或 https:// 开头',
                      }),
                    ),
                  );
                },
              },
            ]}
          >
            <Input maxLength={500} />
          </Form.Item>
          <Form.Item
            name="serverType"
            label="Server Type"
            initialValue="http"
            rules={[{ required: true }]}
          >
            <Select
              options={[
                { value: 'http', label: 'http (Streamable HTTP)' },
                { value: 'sse', label: 'sse (Legacy HTTP+SSE)' },
              ]}
            />
          </Form.Item>
          <Form.Item
            name="compressEnabled"
            label={intl.formatMessage({
              id: 'pages.capabilities.compressEnabled',
              defaultMessage: 'Compress tool results',
            })}
            initialValue={false}
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>
          <Form.Item
            name="accessMode"
            label={intl.formatMessage({
              id: 'pages.capabilities.accessMode',
              defaultMessage: 'Access mode',
            })}
            initialValue="READ_WRITE"
          >
            <Select
              options={[
                { value: 'READ', label: intl.formatMessage({ id: 'pages.capabilities.accessMode.read', defaultMessage: 'Read' }) },
                { value: 'WRITE', label: intl.formatMessage({ id: 'pages.capabilities.accessMode.write', defaultMessage: 'Write' }) },
                { value: 'READ_WRITE', label: intl.formatMessage({ id: 'pages.capabilities.accessMode.readWrite', defaultMessage: 'Read/write' }) },
              ]}
            />
          </Form.Item>
          <Form.Item
            name="securityLevel"
            label={intl.formatMessage({
              id: 'pages.capabilities.securityLevel',
              defaultMessage: 'Security level',
            })}
            initialValue="L1"
          >
            <Select
              options={[
                { value: 'L1', label: 'L1' },
                { value: 'L2', label: 'L2' },
              ]}
            />
          </Form.Item>
          <Form.Item
            name="approvalScope"
            label={intl.formatMessage({
              id: 'pages.capabilities.approvalScope',
              defaultMessage: 'L2 approval scope',
            })}
            initialValue="ONCE"
          >
            <Select
              options={[
                { value: 'SESSION', label: intl.formatMessage({ id: 'pages.capabilities.approvalScope.session', defaultMessage: 'Session' }) },
                { value: 'RUN', label: intl.formatMessage({ id: 'pages.capabilities.approvalScope.run', defaultMessage: 'Run' }) },
                { value: 'ONCE', label: intl.formatMessage({ id: 'pages.capabilities.approvalScope.once', defaultMessage: 'Once' }) },
              ]}
            />
          </Form.Item>
          <Form.Item
            name="authConfig"
            label={labelWithRule(
              intl.formatMessage({ id: 'pages.capabilities.mcp.authConfig' }),
              intl.formatMessage({ id: 'pages.hint.text' }),
            )}
          >
            <Input.TextArea
              rows={3}
              placeholder={intl.formatMessage({
                id: 'pages.capabilities.mcp.authConfigPlaceholder',
              })}
            />
          </Form.Item>
        </Form>
      </Modal>
      <Modal
        title={viewRecord?.name}
        open={!!viewRecord}
        onCancel={() => setViewRecord(null)}
        footer={null}
        width={560}
      >
        <table style={{ width: '100%', borderCollapse: 'collapse' }}>
          <tbody>
            {[
              {
                label: intl.formatMessage({ id: 'pages.table.id' }),
                value: viewRecord?.id,
              },
              {
                label: intl.formatMessage({ id: 'pages.table.name' }),
                value: viewRecord?.name,
              },
              {
                label: intl.formatMessage({ id: 'pages.table.description' }),
                value: viewRecord?.description || '-',
              },
              {
                label: intl.formatMessage({
                  id: 'pages.capabilities.serverUrl',
                }),
                value: viewRecord?.serverUrl || '-',
              },
              { label: 'Server Type', value: viewRecord?.serverType || '-' },
              {
                label: intl.formatMessage({
                  id: 'pages.table.createdBy',
                  defaultMessage: 'Created By',
                }),
                value: viewRecord?.createdBy || '-',
              },
              {
                label: intl.formatMessage({ id: 'pages.table.created' }),
                value: formatTime(viewRecord?.createdAt),
              },
              {
                label: intl.formatMessage({
                  id: 'pages.table.updatedBy',
                  defaultMessage: 'Updated By',
                }),
                value: viewRecord?.updatedBy || '-',
              },
              {
                label: intl.formatMessage({
                  id: 'pages.table.updatedAt',
                  defaultMessage: 'Updated At',
                }),
                value: formatTime(viewRecord?.updatedAt),
              },
            ].map((row) => (
              <tr key={row.label}>
                <td
                  style={{
                    padding: '8px 12px',
                    fontWeight: 500,
                    color: '#8c8c8c',
                    borderBottom: '1px solid #f0f0f0',
                    width: 120,
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
      <Drawer
        title={`${toolsRecord?.name || ''} — ${intl.formatMessage({
          id: 'pages.capabilities.mcp.toolsTitle',
          defaultMessage: '工具列表',
        })}`}
        open={toolsOpen}
        onClose={() => setToolsOpen(false)}
        width={680}
      >
        {toolsLoading ? (
          <div style={{ textAlign: 'center', padding: 40 }}>
            {intl.formatMessage({ id: 'pages.capabilities.mcp.toolsLoading' })}
          </div>
        ) : toolsError ? (
          <Alert
            type="error"
            showIcon
            message={intl.formatMessage({
              id: 'pages.capabilities.mcp.toolsError',
              defaultMessage: '获取工具列表失败',
            })}
            description={toolsError}
            action={
              <Button size="small" onClick={() => openTools(toolsRecord)}>
                {intl.formatMessage({ id: 'pages.chat.retry' })}
              </Button>
            }
          />
        ) : tools.length === 0 ? (
          <Empty description={intl.formatMessage({ id: 'pages.table.empty' })} />
        ) : (
          <Space direction="vertical" style={{ width: '100%' }} size={12}>
            {tools.map((tool) => (
              <div
                key={tool.name}
                style={{
                  border: '1px solid #f0f0f0',
                  borderRadius: 8,
                  padding: '12px 14px',
                }}
              >
                <Space
                  style={{ width: '100%', justifyContent: 'space-between' }}
                >
                  <Tag color="blue">{tool.name}</Tag>
                  <Button
                    type="link"
                    size="small"
                    icon={<ThunderboltOutlined />}
                    onClick={() => openCallTool(tool)}
                  >
                    {intl.formatMessage({
                      id: 'pages.capabilities.mcp.tryCall',
                      defaultMessage: '试调用',
                    })}
                  </Button>
                </Space>
                {tool.description && (
                  <Text type="secondary" style={{ display: 'block' }}>
                    {tool.description}
                  </Text>
                )}
                {tool.inputSchema && tool.inputSchema !== '{}' && (
                  <pre
                    style={{
                      marginTop: 8,
                      maxHeight: 160,
                      overflow: 'auto',
                      fontSize: 12,
                      background: 'rgba(0,0,0,0.03)',
                      padding: 8,
                      borderRadius: 6,
                    }}
                  >
                    {(() => {
                      try {
                        return JSON.stringify(
                          JSON.parse(tool.inputSchema || '{}'),
                          null,
                          2,
                        );
                      } catch {
                        return tool.inputSchema;
                      }
                    })()}
                  </pre>
                )}
              </div>
            ))}
          </Space>
        )}
      </Drawer>
      <Modal
        title={`${callToolState.toolName} — ${intl.formatMessage({
          id: 'pages.capabilities.mcp.tryCallTitle',
          defaultMessage: '试调用',
        })}`}
        open={callToolState.open}
        onCancel={() =>
          setCallToolState((s) => ({
            ...s,
            open: false,
            result: undefined,
            error: undefined,
          }))
        }
        onOk={handleCallTool}
        confirmLoading={callToolState.loading}
        width={680}
      >
        <Form layout="vertical">
          <Form.Item
            label={intl.formatMessage({
              id: 'pages.capabilities.mcp.args',
              defaultMessage: '参数 (JSON)',
            })}
          >
            <Input.TextArea
              rows={6}
              value={callToolState.args}
              onChange={(e) =>
                setCallToolState((s) => ({ ...s, args: e.target.value }))
              }
            />
          </Form.Item>
        </Form>
        {callToolState.error && (
          <Alert
            type="error"
            showIcon
            message={callToolState.error}
            style={{ marginBottom: 8 }}
          />
        )}
        {callToolState.result !== undefined && (
          <pre
            style={{
              maxHeight: 240,
              overflow: 'auto',
              fontSize: 12,
              background: 'rgba(0,0,0,0.03)',
              padding: 8,
              borderRadius: 6,
            }}
          >
            {callToolState.result}
          </pre>
        )}
      </Modal>
    </PageContainer>
  );
}
