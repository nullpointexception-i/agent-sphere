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
import { DEFAULT_PAGE_SIZE } from '@/constants/pagination';
import { PERM } from '@/constants/permissions';
import '@ant-design/x-markdown/es/XMarkdown/index.css';
import {
  App,
  Button,
  DatePicker,
  Dropdown,
  Form,
  Input,
  Modal,
  Switch,
  Tabs,
  Tag,
  Tooltip,
} from 'antd';
import type dayjs from 'dayjs';
import { useEffect, useRef, useState } from 'react';
import { Can } from '@/components/Can';
import { useCan } from '@/hooks/usePermission';
import { agentApi } from '@/services/agentSphere/api';
import { formatParamDate, formatTime } from '@/utils/format';
import { labelWithRule } from '@/utils/labelWithRule';
import SkillMarkdownEditor from './components/SkillMarkdownEditor';

/** Canonical promptTemplate template: keep in sync with the backend CapabilityBuiltinToolSkillManage.SKILL_TEMPLATE. */
const SKILL_TEMPLATE = `# <Skill name>

## Overview
<When to use / input and output / success criteria>

## step0 <Preparation>
### step0.1 <Sub-step>
### step0.2 <Sub-step>

## step1 <Main step>
### step1.1 <Sub-step>
### step1.2 <Sub-step>

## step2 <Main step>

## Wrap-up checklist
<Closing checks that must pass before completion; output format / acceptance criteria>`;

const SAMPLE_DEFINITION = `{
  "version": 1,
  "parameters": {
    "type": "object",
    "properties": {
      "keyword": { "type": "string" }
    },
    "required": ["keyword"]
  },
  "promptTemplate": ${JSON.stringify(SKILL_TEMPLATE)}
}`;

const EMPTY_PARAMETERS = { type: 'object', properties: {} };

interface SkillRecord {
  id: number;
  name: string;
  description?: string;
  definition?: string;
  status?: string;
  visibility?: string;
  originSkillId?: number;
  hubSkillId?: number;
  hubVersion?: number;
  publishedVersion?: number;
  hasUnpublished?: boolean;
  followed?: boolean;
  publisherName?: string;
  followCount?: number;
  mine?: boolean;
  installedSkillId?: number;
  installCount?: number;
  version?: number;
  autoUpdate?: boolean;
  /** Already synced to the origin version (the backend has always returned it; the frontend previously did not declare it, and the list also displays it) */
  originVersion?: number;
  /** Time of the last alignment with the origin; equals the install time for installs */
  syncedAt?: string;
  /** Origin version number of the last sync */
  syncedFromVersion?: number;
  createdAt?: string;
  createdBy?: string;
  updatedBy?: string;
  updatedAt?: string;
}

/** Parse definition back into structured fields (tolerates the legacy {"prompt": "..."}). */
function parseDefinition(def?: string): {
  promptTemplate: string;
  parameters: string;
} {
  if (!def) return { promptTemplate: '', parameters: '' };
  try {
    const raw = def.replace(/^```json\s*/i, '').replace(/```\s*$/, '');
    const obj = JSON.parse(raw);
    if (typeof obj.prompt === 'string') {
      return { promptTemplate: obj.prompt, parameters: '' };
    }
    return {
      promptTemplate: obj.promptTemplate || '',
      parameters: obj.parameters ? JSON.stringify(obj.parameters, null, 2) : '',
    };
  } catch {
    return { promptTemplate: '', parameters: '' };
  }
}

/** Assemble definition V1 from structured fields; throws when parameters are invalid. */
function buildDefinition(values: {
  promptTemplate: string;
  parameters: string;
}): string {
  let parameters = EMPTY_PARAMETERS;
  if (values.parameters?.trim()) {
    try {
      parameters = JSON.parse(values.parameters);
    } catch {
      throw new Error('parameters is not valid JSON');
    }
  }
  return JSON.stringify(
    {
      version: 1,
      parameters,
      promptTemplate: values.promptTemplate,
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
  const [hubKeyword, setHubKeyword] = useState('');
  const [timeRange, setTimeRange] = useState<
    [dayjs.Dayjs | null, dayjs.Dayjs | null]
  >([null, null]);
  const [tableScrollY, setTableScrollY] = useState(400);
  const [selectedRowKeys, setSelectedRowKeys] = useState<number[]>([]);
  const [viewRecord, setViewRecord] = useState<SkillRecord | null>(null);
  const [activeTab, setActiveTab] = useState<'mine' | 'hub' | 'followed'>(
    'mine',
  );
  const hubActionRef = useRef<any>(null);
  const followedActionRef = useRef<any>(null);
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
      width: 220,
      ellipsis: true,
    },
    {
      title: intl.formatMessage({ id: 'pages.table.description' }),
      dataIndex: 'description',
      key: 'description',
      width: 360,
      ellipsis: { showTitle: false },
      render: (_, record: SkillRecord) => (
        <Tooltip placement="topLeft" title={record.description || ''}>
          <span>{record.description || '-'}</span>
        </Tooltip>
      ),
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
            checkedChildren={intl.formatMessage({
              id: 'pages.capabilities.skill.enabled',
              defaultMessage: 'On',
            })}
            unCheckedChildren={intl.formatMessage({
              id: 'pages.capabilities.skill.disabled',
              defaultMessage: 'Off',
            })}
            onChange={(checked) => {
              const status = checked ? 'ENABLED' : 'DISABLED';
              agentApi.skill
                .updateStatus(record.id, status)
                .then(() => {
                  message.success(
                    intl.formatMessage({
                      id: 'pages.capabilities.skill.statusUpdated',
                      defaultMessage: 'Updated',
                    }),
                  );
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
      title: intl.formatMessage({
        id: 'pages.capabilities.skill.hubStatus',
        defaultMessage: 'Skill Hub',
      }),
      key: 'hubState',
      width: 190,
      render: (_, record: SkillRecord) => {
        if (record.originSkillId) {
          return (
            <Tag color="blue">
              {intl.formatMessage(
                {
                  id: 'pages.capabilities.skill.installedFromHub',
                  defaultMessage: 'Installed · hub v{version}',
                },
                { version: record.originVersion ?? 1 },
              )}
            </Tag>
          );
        }
        if (!record.hubSkillId) {
          return (
            <Tag>
              {intl.formatMessage({
                id: 'pages.capabilities.skill.hubNotPublished',
                defaultMessage: 'Not published',
              })}
            </Tag>
          );
        }
        if (record.hasUnpublished) {
          return (
            <Tag color="orange">
              {intl.formatMessage(
                {
                  id: 'pages.capabilities.skill.hubOutdated',
                  defaultMessage: 'Hub v{version} · local updates pending',
                },
                { version: record.hubVersion ?? 1 },
              )}
            </Tag>
          );
        }
        return (
          <Tag color="gold">
            {intl.formatMessage(
              {
                id: 'pages.capabilities.skill.hubPublished',
                defaultMessage: 'Hub v{version}',
              },
              { version: record.hubVersion ?? 1 },
            )}
          </Tag>
        );
      },
    },
    {
      title: intl.formatMessage({
        id: 'pages.capabilities.skill.version',
        defaultMessage: 'Version',
      }),
      dataIndex: 'version',
      key: 'version',
      width: 80,
      // A replica's version does not increment on sync (only originVersion advances), so the
      // column name emphasizes "local version"
      render: (v: any, row: SkillRecord) =>
        row.originSkillId ? (
          <span>
            {v ?? 1}
            <Tag style={{ marginLeft: 6 }} color="default">
              {intl.formatMessage({
                id: 'pages.capabilities.skill.localVersionTag',
                defaultMessage: 'local',
              })}
            </Tag>
          </span>
        ) : (
          (v ?? 1)
        ),
    },
    {
      title: intl.formatMessage({
        id: 'pages.capabilities.skill.syncedVersion',
        defaultMessage: 'Synced origin version',
      }),
      dataIndex: 'syncedFromVersion',
      key: 'syncedFromVersion',
      width: 120,
      // Native skills have no origin, so this column is meaningless for them
      render: (v: any, row: SkillRecord) =>
        row.originSkillId ? (
          <span>
            {intl.formatMessage(
              {
                id: 'pages.capabilities.skill.syncedVersionValue',
                defaultMessage: 'origin v{v}',
              },
              // values must be the second positional argument: nest it into the descriptor and
              // react-intl will ignore it, rendering {v} literally.
              { v: v ?? row.originVersion ?? 1 },
            )}
          </span>
        ) : (
          '-'
        ),
    },
    {
      title: intl.formatMessage({
        id: 'pages.capabilities.skill.syncedAt',
        defaultMessage: 'Last synced',
      }),
      dataIndex: 'syncedAt',
      key: 'syncedAt',
      width: 170,
      render: (v: any, row: SkillRecord) =>
        row.originSkillId ? formatTime(v) : '-',
    },
    {
      title: intl.formatMessage({
        id: 'pages.capabilities.skill.autoUpdate',
        defaultMessage: 'Auto update',
      }),
      dataIndex: 'autoUpdate',
      key: 'autoUpdate',
      width: 100,
      render: (v: any, record: SkillRecord) =>
        record.originSkillId ? (
          <Switch
            checked={v === true}
            size="small"
            onChange={(checked) => {
              agentApi.skill
                .setAutoUpdate(record.id, checked)
                .then(() => {
                  message.success(
                    intl.formatMessage({
                      id: 'pages.modal.saved',
                      defaultMessage: 'Saved',
                    }),
                  );
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
          <span>-</span>
        ),
    },
    {
      title: intl.formatMessage({ id: 'pages.table.actions' }),
      key: 'actions',
      width: 160,
      fixed: 'right',
      render: (_: any, record: any) => (
        <>
          <Button
            type="link"
            size="small"
            icon={<EyeOutlined />}
            onClick={() => setViewRecord(record)}
          />
          <Can code={PERM.capabilitySkillUpdate}>
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
                });
                setModalOpen(true);
              }}
            />
          </Can>
          <Can code={PERM.capabilitySkillPublish}>
            <Button
              type="link"
              size="small"
              onClick={() => handlePublishToHub(record)}
            >
              {record.hubSkillId
                ? intl.formatMessage({
                    id: 'pages.capabilities.skill.updateHub',
                    defaultMessage: 'Update Hub',
                  })
                : intl.formatMessage({
                    id: 'pages.capabilities.skill.publish',
                    defaultMessage: 'Publish',
                  })}
            </Button>
          </Can>
          <Can code={PERM.capabilitySkillDelete}>
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
        message.error(
          e?.message ||
            intl.formatMessage({
              id: 'pages.capabilities.skill.definitionInvalid',
              defaultMessage: 'Invalid definition format',
            }),
        );
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

  const handlePublishToHub = (record: any) => {
    const published = Boolean(record.hubSkillId);
    modal.confirm({
      title: intl.formatMessage(
        {
          id: published
            ? 'pages.capabilities.skill.updateHubConfirm'
            : 'pages.capabilities.skill.publishConfirm',
          defaultMessage: published
            ? 'Update "{name}" in the tenant Skill Hub?'
            : 'Publish "{name}" to the tenant Skill Hub?',
        },
        { name: record.name },
      ),
      content: intl.formatMessage({
        id: 'pages.capabilities.skill.publishHint',
        defaultMessage:
          'Every publish increments the hub version; members of this tenant and its child tenants can follow or install it.',
      }),
      okText: intl.formatMessage({
        id: 'pages.modal.ok',
        defaultMessage: 'Confirm',
      }),
      onOk: async () => {
        const res = await agentApi.skill.publishToHub(record.id);
        message.success(
          intl.formatMessage(
            {
              id: 'pages.capabilities.skill.publishSuccess',
              defaultMessage: 'Published as hub v{version}',
            },
            { version: res?.version ?? 1 },
          ),
        );
        actionRef.current?.reload();
      },
    });
  };

  const handleFollow = async (record: any) => {
    try {
      if (record.followed) {
        await agentApi.skill.unfollow(record.id);
        message.success(
          intl.formatMessage({
            id: 'pages.capabilities.skill.unfollowSuccess',
            defaultMessage: 'Unfollowed',
          }),
        );
      } else {
        await agentApi.skill.follow(record.id);
        message.success(
          intl.formatMessage({
            id: 'pages.capabilities.skill.followSuccess',
            defaultMessage: 'Following the latest hub version',
          }),
        );
      }
      hubActionRef.current?.reload();
    } catch {
      message.error(intl.formatMessage({ id: 'pages.chat.saveFailed' }));
    }
  };

  const handleInstallHub = (record: any, autoUpdate: boolean) => {
    modal.confirm({
      title: intl.formatMessage(
        {
          id: 'pages.capabilities.skill.installConfirm',
          defaultMessage: 'Install "{name}" to my skills?',
        },
        { name: record.name },
      ),
      content: intl.formatMessage({
        id: autoUpdate
          ? 'pages.capabilities.skill.installAutoUpdateContent'
          : 'pages.capabilities.skill.installConfirmContent',
        defaultMessage: autoUpdate
          ? 'A private copy is created and kept in sync with the hub automatically.'
          : 'A private copy is created at the current hub version and can be edited independently.',
      }),
      okText: intl.formatMessage({
        id: 'pages.modal.ok',
        defaultMessage: 'Confirm',
      }),
      onOk: async () => {
        await agentApi.skill.installHub(record.id, autoUpdate);
        message.success(
          intl.formatMessage({
            id: 'pages.capabilities.skill.installSuccess',
            defaultMessage: 'Installed to my skills',
          }),
        );
        hubActionRef.current?.reload();
        actionRef.current?.reload();
      },
    });
  };

  const hubColumns: ProColumns<SkillRecord>[] = [
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
      width: 220,
      ellipsis: true,
    },
    {
      title: intl.formatMessage({ id: 'pages.table.description' }),
      dataIndex: 'description',
      key: 'description',
      width: 360,
      ellipsis: { showTitle: false },
      render: (_, record: SkillRecord) => (
        <Tooltip placement="topLeft" title={record.description || ''}>
          <span>{record.description || '-'}</span>
        </Tooltip>
      ),
    },
    {
      title: intl.formatMessage({
        id: 'pages.capabilities.skill.publisher',
        defaultMessage: 'Publisher',
      }),
      dataIndex: 'publisherName',
      key: 'publisherName',
      width: 120,
      ellipsis: true,
      render: (v: any) => v || '-',
    },
    {
      title: intl.formatMessage({
        id: 'pages.capabilities.skill.hubVersion',
        defaultMessage: 'Hub version',
      }),
      dataIndex: 'version',
      key: 'version',
      width: 100,
      render: (v: any) => `v${v ?? 1}`,
    },
    {
      title: intl.formatMessage({
        id: 'pages.capabilities.skill.installCount',
        defaultMessage: 'Installs',
      }),
      dataIndex: 'installCount',
      key: 'installCount',
      width: 90,
      render: (v: any) => v ?? 0,
    },
    {
      title: intl.formatMessage({
        id: 'pages.capabilities.skill.followCount',
        defaultMessage: 'Followers',
      }),
      dataIndex: 'followCount',
      key: 'followCount',
      width: 90,
      render: (v: any) => v ?? 0,
    },
    {
      title: intl.formatMessage({ id: 'pages.table.actions' }),
      key: 'actions',
      width: 260,
      fixed: 'right',
      render: (_: any, record: any) => (
        <>
          <Button
            type="link"
            size="small"
            icon={<EyeOutlined />}
            onClick={() => {
              agentApi.skill
                .hubDetail(record.id)
                .then((detail: any) => setViewRecord({ ...record, ...detail }))
                .catch(() => setViewRecord(record));
            }}
          />
          <Can code={PERM.capabilitySkillInstall}>
            {!record.mine && (
              <Button
                type="link"
                size="small"
                onClick={() => handleFollow(record)}
              >
                {record.followed
                  ? intl.formatMessage({
                      id: 'pages.capabilities.skill.unfollow',
                      defaultMessage: 'Unfollow',
                    })
                  : intl.formatMessage({
                      id: 'pages.capabilities.skill.follow',
                      defaultMessage: 'Follow',
                    })}
              </Button>
            )}
            <Dropdown
              menu={{
                items: [
                  {
                    key: 'install',
                    label: intl.formatMessage({
                      id: 'pages.capabilities.skill.install',
                      defaultMessage: 'Install',
                    }),
                    onClick: () => handleInstallHub(record, false),
                  },
                  {
                    key: 'install-auto',
                    label: intl.formatMessage({
                      id: 'pages.capabilities.skill.installAutoUpdate',
                      defaultMessage: 'Install + auto-update',
                    }),
                    onClick: () => handleInstallHub(record, true),
                  },
                ],
              }}
            >
              <Button type="link" size="small">
                {record.installedSkillId
                  ? intl.formatMessage({
                      id: 'pages.capabilities.skill.reinstall',
                      defaultMessage: 'Install again',
                    })
                  : intl.formatMessage({
                      id: 'pages.capabilities.skill.install',
                      defaultMessage: 'Install',
                    })}
              </Button>
            </Dropdown>
          </Can>
          {record.mine && record.sourceSkillId && (
            <Can code={PERM.capabilitySkillPublish}>
              <Button
                type="link"
                size="small"
                onClick={() =>
                  handlePublishToHub({
                    ...record,
                    id: record.sourceSkillId,
                    hubSkillId: record.id,
                  })
                }
              >
                {intl.formatMessage({
                  id: 'pages.capabilities.skill.updateHub',
                  defaultMessage: 'Update Hub',
                })}
              </Button>
            </Can>
          )}
        </>
      ),
    },
  ];

  const handleBatchStatus = (status: string) => {
    if (selectedRowKeys.length === 0) return;
    const enabled = status === 'ENABLED';
    modal.confirm({
      title: intl.formatMessage(
        {
          id: enabled
            ? 'pages.capabilities.skill.batchEnableTitle'
            : 'pages.capabilities.skill.batchDisableTitle',
          defaultMessage: enabled
            ? 'Enable {count} selected skills?'
            : 'Disable {count} selected skills?',
        },
        { count: selectedRowKeys.length },
      ),
      okText: intl.formatMessage({
        id: 'pages.modal.ok',
        defaultMessage: 'Confirm',
      }),
      okButtonProps: enabled ? { type: 'primary' } : { danger: true },
      onOk: async () => {
        await agentApi.skill.batchUpdateStatus(selectedRowKeys, status);
        message.success(
          intl.formatMessage(
            {
              id: enabled
                ? 'pages.capabilities.skill.batchEnabledSuccess'
                : 'pages.capabilities.skill.batchDisabledSuccess',
              defaultMessage: enabled
                ? 'Enabled {count} skills'
                : 'Disabled {count} skills',
            },
            { count: selectedRowKeys.length },
          ),
        );
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
      <Tabs
        activeKey={activeTab}
        onChange={(k) => setActiveTab(k as 'mine' | 'hub' | 'followed')}
        items={[
          {
            key: 'mine',
            label: intl.formatMessage({
              id: 'pages.capabilities.skill.tabs.mine',
              defaultMessage: 'My Skills',
            }),
          },
          {
            key: 'hub',
            label: intl.formatMessage({
              id: 'pages.capabilities.skill.tabs.hub',
              defaultMessage: 'Skill Hub',
            }),
          },
          {
            key: 'followed',
            label: intl.formatMessage({
              id: 'pages.capabilities.skill.tabs.followed',
              defaultMessage: 'Followed',
            }),
          },
        ]}
      />
      {activeTab === 'hub' ? (
        <ProTable
          key="hub"
          actionRef={hubActionRef}
          rowKey="id"
          search={false}
          options={false}
          scroll={{ x: 1200, y: tableScrollY }}
          pagination={{
            defaultPageSize: DEFAULT_PAGE_SIZE,
            showSizeChanger: true,
            showQuickJumper: true,
            pageSizeOptions: [5, 10, 20, 50],
            style: { justifyContent: 'flex-start' },
          }}
          params={{ keyword: hubKeyword || undefined }}
          request={async (p) => {
            const res = await agentApi.skill.hub({
              keyword: p.keyword,
              page: p.current,
              size: p.pageSize,
            });
            return {
              data: res.records || res,
              total: res.total ?? 0,
              success: true,
            };
          }}
          columns={hubColumns}
          toolBarRender={() => [
            <Input.Search
              key="search"
              placeholder={intl.formatMessage({
                id: 'pages.search.placeholder',
              })}
              style={{ width: 200 }}
              onSearch={(value) => {
                setHubKeyword(value);
              }}
              allowClear
              onClear={() => setHubKeyword('')}
              maxLength={255}
            />,
          ]}
        />
      ) : activeTab === 'followed' ? (
        <ProTable
          key="followed"
          actionRef={followedActionRef}
          rowKey="id"
          search={false}
          options={false}
          scroll={{ x: 1200, y: tableScrollY }}
          pagination={{
            defaultPageSize: DEFAULT_PAGE_SIZE,
            showSizeChanger: true,
            showQuickJumper: true,
            pageSizeOptions: [5, 10, 20, 50],
            style: { justifyContent: 'flex-start' },
          }}
          request={async () => {
            const rows = await agentApi.skill.followed();
            const list = Array.isArray(rows) ? rows : [];
            return { data: list, total: list.length, success: true };
          }}
          columns={hubColumns}
        />
      ) : (
        <ProTable
          key="mine"
          actionRef={actionRef}
          rowKey="id"
          search={false}
          options={false}
          scroll={{ x: 1630, y: tableScrollY }}
          pagination={{
            defaultPageSize: DEFAULT_PAGE_SIZE,
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
                {intl.formatMessage(
                  {
                    id: 'pages.capabilities.skill.batchEnable',
                    defaultMessage: 'Enable ({count})',
                  },
                  { count: selectedRowKeys.length },
                )}
              </Button>
            ),
            selectedRowKeys.length > 0 && (
              <Button
                key="batchDisable"
                danger
                icon={<DislikeOutlined />}
                onClick={() => handleBatchStatus('DISABLED')}
              >
                {intl.formatMessage(
                  {
                    id: 'pages.capabilities.skill.batchDisable',
                    defaultMessage: 'Disable ({count})',
                  },
                  { count: selectedRowKeys.length },
                )}
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
                  {
                    id: 'pages.batchDelete',
                    defaultMessage: 'Delete ({count})',
                  },
                  { count: selectedRowKeys.length },
                )}
              </Button>
            ),
            <Input.Search
              key="search"
              placeholder={intl.formatMessage({
                id: 'pages.search.placeholder',
              })}
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
            <Can key="new" code={PERM.capabilitySkillCreate}>
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
      )}
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
              intl.formatMessage({
                id: 'pages.admin.settings.template.skill.promptTemplate',
                defaultMessage: 'Task instruction (promptTemplate)',
              }),
              intl.formatMessage({
                id: 'pages.admin.settings.template.skill.promptTemplateHint',
                defaultMessage: 'Supports Markdown and {{field}} placeholders',
              }),
            )}
            rules={[
              {
                required: true,
                message: intl.formatMessage({
                  id: 'pages.admin.settings.template.skill.promptTemplateRequired',
                  defaultMessage: 'Task instruction is required',
                }),
              },
            ]}
          >
            <SkillMarkdownEditor placeholder={SKILL_TEMPLATE} />
          </Form.Item>
          <Form.Item
            name="parameters"
            label={labelWithRule(
              intl.formatMessage({
                id: 'pages.admin.settings.template.skill.parameters',
                defaultMessage: 'Input JSON Schema (parameters)',
              }),
              intl.formatMessage({
                id: 'pages.admin.settings.template.skill.parametersHint',
                defaultMessage: 'May be empty (empty object)',
              }),
            )}
          >
            <Input.TextArea
              rows={5}
              placeholder={'{\n  "type": "object",\n  "properties": {}\n}'}
            />
          </Form.Item>
          <Button type="link" size="small" onClick={() => setSampleOpen(true)}>
            {intl.formatMessage({
              id: 'pages.capabilities.skill.viewSample',
              defaultMessage: 'View sample',
            })}
          </Button>
        </Form>
      </Modal>
      <Modal
        title={intl.formatMessage({
          id: 'pages.capabilities.skill.sampleTitle',
          defaultMessage: 'Skill definition sample',
        })}
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
                <span>
                  {intl.formatMessage({ id: 'pages.table.status' })}:{' '}
                  {viewRecord?.status || '-'}
                </span>
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
                {/* Only installed replicas have an origin; these two fields are meaningless for native skills */}
                {viewRecord?.originSkillId ? (
                  <>
                    <span>
                      {intl.formatMessage({
                        id: 'pages.capabilities.skill.syncedVersion',
                        defaultMessage: 'Synced origin version',
                      })}
                      :{' '}
                      <b style={{ color: 'rgba(0,0,0,0.88)' }}>
                        {intl.formatMessage(
                          {
                            id: 'pages.capabilities.skill.syncedVersionValue',
                            defaultMessage: 'origin v{v}',
                          },
                          {
                            v:
                              viewRecord?.syncedFromVersion ??
                              viewRecord?.originVersion ??
                              1,
                          },
                        )}
                      </b>
                    </span>
                    <span>
                      {intl.formatMessage({
                        id: 'pages.capabilities.skill.syncedAt',
                        defaultMessage: 'Last synced',
                      })}
                      :{' '}
                      <b style={{ color: 'rgba(0,0,0,0.88)' }}>
                        {formatTime(viewRecord?.syncedAt)}
                      </b>
                    </span>
                    <span>
                      {intl.formatMessage({
                        id: 'pages.capabilities.skill.originSkill',
                        defaultMessage: 'Origin skill',
                      })}
                      : {viewRecord.originSkillId}
                    </span>
                    {viewRecord?.autoUpdate ? (
                      <Tag color="blue">
                        {intl.formatMessage({
                          id: 'pages.capabilities.skill.autoUpdateOn',
                          defaultMessage: 'Auto update on',
                        })}
                      </Tag>
                    ) : (
                      <Tag>
                        {intl.formatMessage({
                          id: 'pages.capabilities.skill.autoUpdateOff',
                          defaultMessage: 'Auto update off',
                        })}
                      </Tag>
                    )}
                  </>
                ) : null}
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
                {intl.formatMessage({
                  id: 'pages.admin.settings.template.skill.promptTemplate',
                  defaultMessage: 'Task instruction (promptTemplate)',
                })}
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
                <XMarkdown
                  content={
                    parsed.promptTemplate ||
                    intl.formatMessage({
                      id: 'pages.capabilities.skill.notSet',
                      defaultMessage: '(not set)',
                    })
                  }
                />
              </div>
              {parsed.parameters ? (
                <>
                  <div style={{ fontWeight: 600, marginBottom: 6 }}>
                    {intl.formatMessage({
                      id: 'pages.admin.settings.template.skill.parameters',
                      defaultMessage: 'Input JSON Schema (parameters)',
                    })}
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
            </div>
          );
        })()}
      </Modal>
    </PageContainer>
  );
}
