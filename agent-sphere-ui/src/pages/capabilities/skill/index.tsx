import {
  ClearOutlined,
  DeleteOutlined,
  DislikeOutlined,
  EditOutlined,
  EyeOutlined,
  LikeOutlined,
  PlusOutlined,
} from '@ant-design/icons';
import {
  PageContainer,
  type ProColumns,
  ProTable,
} from '@ant-design/pro-components';
import XMarkdown from '@ant-design/x-markdown';
import { useIntl } from '@umijs/max';
import '@ant-design/x-markdown/es/XMarkdown/index.css';
import { App, Button, DatePicker, Form, Input, Modal, Switch, Tag } from 'antd';
import type dayjs from 'dayjs';
import { useEffect, useRef, useState } from 'react';
import { Can } from '@/components/Can';
import { useCan } from '@/hooks/usePermission';
import { agentApi } from '@/services/agentSphere/api';
import { formatParamDate, formatTime } from '@/utils/format';
import { labelWithRule } from '@/utils/labelWithRule';
import SkillMarkdownEditor from './components/SkillMarkdownEditor';
import SkillToolPicker, { WILDCARD_ALL } from './components/SkillToolPicker';

const SAMPLE_DEFINITION = `{
  "version": 1,
  "parameters": {
    "type": "object",
    "properties": {
      "keyword": { "type": "string" }
    },
    "required": ["keyword"]
  },
  "promptTemplate": "请围绕 {{keyword}} 完成任务并返回最终结果。",
  "allowTools": [
    "builtin:chrome",
    "mcp:<capabilityId>:<nativeToolName>",
    "cli:<capabilityId>",
    "skill:<skillId>"
  ]
}`;

const EMPTY_PARAMETERS = { type: 'object', properties: {} };

interface SkillRecord {
  id: number;
  name: string;
  description?: string;
  definition?: string;
  status?: string;
  createdAt?: string;
  createdBy?: string;
  updatedBy?: string;
  updatedAt?: string;
}

/** 解析 definition 回填结构化字段（兼容遗留 {"prompt": "..."}）。 */
function parseDefinition(def?: string): {
  promptTemplate: string;
  parameters: string;
  allowTools: string[];
} {
  if (!def) return { promptTemplate: '', parameters: '', allowTools: [] };
  try {
    const raw = def.replace(/^```json\s*/i, '').replace(/```\s*$/, '');
    const obj = JSON.parse(raw);
    if (typeof obj.prompt === 'string') {
      return { promptTemplate: obj.prompt, parameters: '', allowTools: [] };
    }
    return {
      promptTemplate: obj.promptTemplate || '',
      parameters: obj.parameters ? JSON.stringify(obj.parameters, null, 2) : '',
      allowTools: Array.isArray(obj.allowTools) ? obj.allowTools : [],
    };
  } catch {
    return { promptTemplate: '', parameters: '', allowTools: [] };
  }
}

/** 用结构化字段组装 definition V1；parameters 非法时抛出异常。 */
function buildDefinition(values: {
  promptTemplate: string;
  parameters: string;
  allowTools: string[];
}): string {
  let parameters = EMPTY_PARAMETERS;
  if (values.parameters?.trim()) {
    try {
      parameters = JSON.parse(values.parameters);
    } catch {
      throw new Error('parameters 不是合法 JSON');
    }
  }
  const allowTools = (
    Array.isArray(values.allowTools) ? values.allowTools : []
  ).filter(Boolean);
  const finalAllowTools = allowTools.includes(WILDCARD_ALL)
    ? [WILDCARD_ALL]
    : allowTools;
  return JSON.stringify(
    {
      version: 1,
      parameters,
      promptTemplate: values.promptTemplate,
      ...(finalAllowTools.length > 0 ? { allowTools: finalAllowTools } : {}),
    },
    null,
    2,
  );
}

export default function SkillList() {
  const { message, modal } = App.useApp();
  const intl = useIntl();
  const actionRef = useRef<any>(null);
  const [modalOpen, setModalOpen] = useState(false);
  const [sampleOpen, setSampleOpen] = useState(false);
  const [editing, setEditing] = useState<SkillRecord | null>(null);
  const [form] = Form.useForm();
  const [submitting, setSubmitting] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [timeRange, setTimeRange] = useState<
    [dayjs.Dayjs | null, dayjs.Dayjs | null]
  >([null, null]);
  const [tableScrollY, setTableScrollY] = useState(400);
  const [selectedRowKeys, setSelectedRowKeys] = useState<number[]>([]);
  const [viewRecord, setViewRecord] = useState<SkillRecord | null>(null);
  const canUpdate = useCan('capability:skill:update');

  useEffect(() => {
    const calc = () => setTableScrollY(window.innerHeight - 280);
    calc();
    window.addEventListener('resize', calc);
    return () => window.removeEventListener('resize', calc);
  }, []);

  const columns: ProColumns<SkillRecord>[] = [
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
      title: intl.formatMessage({ id: 'pages.table.status' }),
      dataIndex: 'status',
      key: 'status',
      width: 110,
      render: (_, record: SkillRecord) =>
        canUpdate ? (
          <Switch
            checked={record.status === 'ENABLED'}
            checkedChildren="启用"
            unCheckedChildren="禁用"
            onChange={(checked) => {
              const status = checked ? 'ENABLED' : 'DISABLED';
              agentApi.skill
                .updateStatus(record.id, status)
                .then(() => {
                  message.success('已更新');
                  actionRef.current?.reload();
                })
                .catch(() => {
                  message.error(
                    intl.formatMessage({ id: 'pages.chat.saveFailed' }),
                  );
                });
            }}
          />
        ) : (
          <Tag color={record.status === 'ENABLED' ? 'green' : 'default'}>
            {record.status || '-'}
          </Tag>
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
      width: 160,
      render: (_: any, record: any) => (
        <>
          <Button
            type="link"
            size="small"
            icon={<EyeOutlined />}
            onClick={() => setViewRecord(record)}
          />
          <Can code="capability:skill:update">
            <Button
              type="link"
              size="small"
              icon={<EditOutlined />}
              onClick={() => {
                setEditing(record);
                const parsed = parseDefinition(record.definition);
                form.setFieldsValue({
                  name: record.name,
                  description: record.description,
                  promptTemplate: parsed.promptTemplate,
                  parameters: parsed.parameters,
                  allowTools: parsed.allowTools,
                });
                setModalOpen(true);
              }}
            />
          </Can>
          <Can code="capability:skill:delete">
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
                    { name: 'skill' },
                  ),
                  content: intl.formatMessage(
                    {
                      id: 'pages.deleteConfirm.content',
                      defaultMessage:
                        'Are you sure you want to delete this {name}?',
                    },
                    { name: 'skill' },
                  ),
                  okType: 'danger',
                  onOk: async () => {
                    await agentApi.skill.delete(record.id);
                    message.success('Deleted');
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
      let definition: string;
      try {
        definition = buildDefinition(values);
      } catch (e: any) {
        message.error(e?.message || 'definition 格式错误');
        return;
      }
      const payload = {
        name: values.name,
        description: values.description,
        definition,
      };
      if (editing) {
        await agentApi.skill.update(editing.id, payload);
        message.success('Updated');
      } else {
        await agentApi.skill.create(payload);
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

  const handleBatchStatus = (status: string) => {
    if (selectedRowKeys.length === 0) return;
    const enabled = status === 'ENABLED';
    const label = enabled ? '启用' : '禁用';
    modal.confirm({
      title: `${label}选中的 ${selectedRowKeys.length} 个技能？`,
      okText: '确认',
      okButtonProps: enabled ? { type: 'primary' } : { danger: true },
      onOk: async () => {
        await agentApi.skill.batchUpdateStatus(selectedRowKeys, status);
        message.success(`${selectedRowKeys.length} 个技能已${label}`);
        setSelectedRowKeys([]);
        actionRef.current?.reload();
      },
    });
  };

  const handleBatchDelete = () => {
    if (selectedRowKeys.length === 0) return;
    modal.confirm({
      title: intl.formatMessage(
        { id: 'pages.deleteConfirm.title', defaultMessage: 'Delete {name}' },
        { name: `${selectedRowKeys.length} skills` },
      ),
      content: intl.formatMessage(
        {
          id: 'pages.deleteConfirm.content',
          defaultMessage: 'Are you sure you want to delete this {name}?',
        },
        { name: 'skill' },
      ),
      okType: 'danger',
      onOk: async () => {
        await agentApi.skill.batchDelete(selectedRowKeys);
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
          const res = await agentApi.skill.list({
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
              key="batchEnable"
              type="primary"
              icon={<LikeOutlined />}
              onClick={() => handleBatchStatus('ENABLED')}
            >
              启用 ({selectedRowKeys.length})
            </Button>
          ),
          selectedRowKeys.length > 0 && (
            <Button
              key="batchDisable"
              danger
              icon={<DislikeOutlined />}
              onClick={() => handleBatchStatus('DISABLED')}
            >
              禁用 ({selectedRowKeys.length})
            </Button>
          ),
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
              if (dates?.[0] && dates?.[1]) {
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
          <Can key="new" code="capability:skill:create">
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
                id: 'pages.modal.editSkill',
                defaultMessage: 'Edit Skill',
              })
            : intl.formatMessage({
                id: 'pages.modal.newSkill',
                defaultMessage: 'New Skill',
              })
        }
        open={modalOpen}
        onOk={handleSubmit}
        onCancel={() => {
          setModalOpen(false);
          setEditing(null);
        }}
        confirmLoading={submitting}
        width="80%"
        styles={{ body: { height: '80vh', overflowY: 'auto' } }}
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
            name="promptTemplate"
            label={labelWithRule(
              '任务指令 (promptTemplate)',
              '支持 Markdown 与 {{字段}} 占位符',
            )}
            rules={[{ required: true, message: '任务指令不能为空' }]}
          >
            <SkillMarkdownEditor />
          </Form.Item>
          <Form.Item
            name="parameters"
            label={labelWithRule(
              '入参 JSON Schema (parameters)',
              '可留空=空对象',
            )}
          >
            <Input.TextArea
              rows={5}
              placeholder={'{\n  "type": "object",\n  "properties": {}\n}'}
            />
          </Form.Item>
          <Form.Item
            name="allowTools"
            label={labelWithRule(
              '允许工具 (allowTools)',
              '选取 Skill 子 Agent 可调用的工具；未选择则禁止调用任何工具',
            )}
          >
            <SkillToolPicker />
          </Form.Item>
          <Button type="link" size="small" onClick={() => setSampleOpen(true)}>
            查看样例
          </Button>
        </Form>
      </Modal>
      <Modal
        title="Skill definition 样例"
        open={sampleOpen}
        onCancel={() => setSampleOpen(false)}
        footer={null}
        width={640}
      >
        <pre
          style={{
            whiteSpace: 'pre-wrap',
            wordBreak: 'break-all',
            background: '#f6f8fa',
            padding: 12,
            borderRadius: 8,
            maxHeight: 420,
            overflow: 'auto',
          }}
        >
          {SAMPLE_DEFINITION}
        </pre>
      </Modal>
      <Modal
        title={viewRecord?.name}
        open={!!viewRecord}
        onCancel={() => setViewRecord(null)}
        footer={null}
        width="80%"
        styles={{ body: { height: '80vh', overflowY: 'auto' } }}
      >
        {(() => {
          const parsed = parseDefinition(viewRecord?.definition);
          const allowTools = parsed.allowTools || [];
          return (
            <div>
              <div
                style={{
                  display: 'flex',
                  gap: 16,
                  flexWrap: 'wrap',
                  marginBottom: 12,
                  color: '#8c8c8c',
                }}
              >
                <span>
                  {intl.formatMessage({ id: 'pages.table.id' })}:{' '}
                  <b style={{ color: 'rgba(0,0,0,0.88)' }}>{viewRecord?.id}</b>
                </span>
                <span>状态: {viewRecord?.status || '-'}</span>
                <span>
                  {intl.formatMessage({ id: 'pages.table.created' })}:{' '}
                  {formatTime(viewRecord?.createdAt)}
                </span>
                <span>
                  {intl.formatMessage({
                    id: 'pages.table.updatedAt',
                    defaultMessage: 'Updated',
                  })}
                  : {formatTime(viewRecord?.updatedAt)}
                </span>
              </div>
              {viewRecord?.description ? (
                <div
                  style={{
                    marginBottom: 12,
                    color: '#555',
                    whiteSpace: 'pre-wrap',
                  }}
                >
                  {viewRecord.description}
                </div>
              ) : null}
              <div style={{ fontWeight: 600, marginBottom: 6 }}>
                任务指令 (promptTemplate)
              </div>
              <div
                style={{
                  border: '1px solid #f0f0f0',
                  borderRadius: 8,
                  padding: '12px 16px',
                  marginBottom: 16,
                  maxHeight: 380,
                  overflow: 'auto',
                }}
              >
                <XMarkdown content={parsed.promptTemplate || '（未设置）'} />
              </div>
              {parsed.parameters ? (
                <>
                  <div style={{ fontWeight: 600, marginBottom: 6 }}>
                    入参 JSON Schema (parameters)
                  </div>
                  <pre
                    style={{
                      whiteSpace: 'pre-wrap',
                      wordBreak: 'break-all',
                      background: '#f6f8fa',
                      padding: 12,
                      borderRadius: 8,
                      maxHeight: 260,
                      overflow: 'auto',
                      marginBottom: 16,
                    }}
                  >
                    {parsed.parameters}
                  </pre>
                </>
              ) : null}
              <div style={{ fontWeight: 600, marginBottom: 6 }}>
                允许工具 (allowTools)
              </div>
              <div>
                {allowTools.length === 0 ? (
                  <span style={{ color: '#999' }}>
                    未选择（不调用任何工具）
                  </span>
                ) : (
                  allowTools.map((ref) => (
                    <Tag key={ref} color={ref.includes('*') ? 'gold' : 'blue'}>
                      {ref}
                    </Tag>
                  ))
                )}
              </div>
            </div>
          );
        })()}
      </Modal>
    </PageContainer>
  );
}
