import { EyeOutlined } from '@ant-design/icons';
import { useIntl } from '@umijs/max';
import { Button, Descriptions, Input, Modal, Select, Table, Tag } from 'antd';
import { useEffect, useState } from 'react';
import { agentApi } from '@/services/agentSphere/api';

const ACTION_OPTIONS = [
  'LOGIN',
  'LOGOUT',
  'REGISTER',
  'CREATE',
  'UPDATE',
  'DELETE',
  'BATCH_DELETE',
  'ASSIGN_ROLE',
  'ASSIGN_PERMISSION',
  'SET_MODEL_ROUTE',
  'SET_ACTIVE_KEY',
  'UPDATE_PROFILE',
  'UPDATE_PASSWORD',
  'REGENERATE',
];

interface AuditLogItem {
  id: number;
  username: string;
  action: string;
  resourceType: string;
  resourceId: string;
  detail: string;
  ipAddress: string;
  success: boolean;
  errorMessage: string;
  createdAt: string;
}

export default function AdminAuditLogs() {
  const intl = useIntl();
  const [items, setItems] = useState<AuditLogItem[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [loading, setLoading] = useState(false);
  const [usernameFilter, setUsernameFilter] = useState('');
  const [actionFilter, setActionFilter] = useState<string | undefined>(
    undefined,
  );
  const [detailItem, setDetailItem] = useState<AuditLogItem | null>(null);

  const loadItems = async (p = page) => {
    setLoading(true);
    try {
      const params: any = { page: p, size: 20 };
      if (usernameFilter) params.username = usernameFilter;
      if (actionFilter) params.action = actionFilter;
      const res = await agentApi.admin.auditLogs.list(params);
      setItems(res.records || []);
      setTotal(res.total || 0);
    } catch {
      setItems([]);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    setPage(1);
    loadItems(1);
  }, [usernameFilter, actionFilter]);

  const actionLabel = (action: string) =>
    intl.formatMessage({
      id: `pages.admin.auditLogs.actions.${action}`,
      defaultMessage: action,
    });

  const columns = [
    {
      title: intl.formatMessage({
        id: 'pages.admin.auditLogs.time',
        defaultMessage: '时间',
      }),
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 160,
      render: (t: string) => (t ? new Date(t).toLocaleString() : '-'),
    },
    {
      title: intl.formatMessage({
        id: 'pages.admin.auditLogs.user',
        defaultMessage: '用户',
      }),
      dataIndex: 'username',
      key: 'username',
      width: 120,
      render: (u: string) => u || '-',
    },
    {
      title: intl.formatMessage({
        id: 'pages.admin.auditLogs.action',
        defaultMessage: '操作',
      }),
      dataIndex: 'action',
      key: 'action',
      width: 120,
      render: (a: string) => <Tag>{actionLabel(a)}</Tag>,
    },
    {
      title: intl.formatMessage({
        id: 'pages.admin.auditLogs.resourceType',
        defaultMessage: '资源类型',
      }),
      dataIndex: 'resourceType',
      key: 'resourceType',
      width: 100,
      render: (t: string) => t || '-',
    },
    {
      title: intl.formatMessage({
        id: 'pages.admin.auditLogs.resourceId',
        defaultMessage: '资源 ID',
      }),
      dataIndex: 'resourceId',
      key: 'resourceId',
      width: 100,
      ellipsis: true,
      render: (r: string) => r || '-',
    },
    {
      title: intl.formatMessage({
        id: 'pages.admin.auditLogs.ipAddress',
        defaultMessage: 'IP 地址',
      }),
      dataIndex: 'ipAddress',
      key: 'ipAddress',
      width: 130,
      render: (ip: string) => ip || '-',
    },
    {
      title: intl.formatMessage({
        id: 'pages.admin.auditLogs.status',
        defaultMessage: '状态',
      }),
      dataIndex: 'success',
      key: 'success',
      width: 70,
      render: (s: boolean, record: AuditLogItem) => (
        <Tag color={s ? 'green' : 'red'} title={record.errorMessage || ''}>
          {s
            ? intl.formatMessage({
                id: 'pages.admin.auditLogs.success',
                defaultMessage: '成功',
              })
            : intl.formatMessage({
                id: 'pages.admin.auditLogs.failure',
                defaultMessage: '失败',
              })}
        </Tag>
      ),
    },
    {
      title: intl.formatMessage({
        id: 'pages.table.actions',
        defaultMessage: '操作',
      }),
      key: 'actions',
      width: 60,
      render: (_: any, record: AuditLogItem) => (
        <Button
          type="link"
          size="small"
          icon={<EyeOutlined />}
          onClick={() => setDetailItem(record)}
        />
      ),
    },
  ];

  return (
    <>
      <div style={{ marginBottom: 16, display: 'flex', gap: 12 }}>
        <Input.Search
          placeholder={intl.formatMessage({
            id: 'pages.admin.auditLogs.search.placeholder',
            defaultMessage: '搜索用户名',
          })}
          allowClear
          style={{ width: 220 }}
          onSearch={(val) => setUsernameFilter(val)}
          onPressEnter={(e: any) => setUsernameFilter(e.target.value)}
        />
        <Select
          allowClear
          placeholder={intl.formatMessage({
            id: 'pages.admin.auditLogs.filter.all',
            defaultMessage: '全部操作',
          })}
          style={{ width: 160 }}
          value={actionFilter}
          onChange={(val) => setActionFilter(val)}
          options={ACTION_OPTIONS.map((a) => ({
            label: actionLabel(a),
            value: a,
          }))}
        />
      </div>
      <Table
        rowKey="id"
        dataSource={items}
        columns={columns}
        loading={loading}
        pagination={{
          current: page,
          total,
          pageSize: 20,
          onChange: (p) => {
            setPage(p);
            loadItems(p);
          },
          showSizeChanger: false,
        }}
        size="small"
      />
      <Modal
        title={intl.formatMessage({
          id: 'pages.admin.auditLogs.title',
          defaultMessage: '审计日志',
        })}
        open={!!detailItem}
        onCancel={() => setDetailItem(null)}
        footer={null}
        width={700}
      >
        {detailItem && (
          <>
            <Descriptions
              column={2}
              size="small"
              bordered
              style={{ marginBottom: 16 }}
            >
              <Descriptions.Item
                label={intl.formatMessage({
                  id: 'pages.admin.auditLogs.user',
                  defaultMessage: '用户',
                })}
              >
                {detailItem.username || '-'}
              </Descriptions.Item>
              <Descriptions.Item
                label={intl.formatMessage({
                  id: 'pages.admin.auditLogs.time',
                  defaultMessage: '时间',
                })}
              >
                {detailItem.createdAt
                  ? new Date(detailItem.createdAt).toLocaleString()
                  : '-'}
              </Descriptions.Item>
              <Descriptions.Item
                label={intl.formatMessage({
                  id: 'pages.admin.auditLogs.action',
                  defaultMessage: '操作',
                })}
              >
                <Tag>{actionLabel(detailItem.action)}</Tag>
              </Descriptions.Item>
              <Descriptions.Item
                label={intl.formatMessage({
                  id: 'pages.admin.auditLogs.status',
                  defaultMessage: '状态',
                })}
              >
                <Tag color={detailItem.success ? 'green' : 'red'}>
                  {detailItem.success
                    ? intl.formatMessage({
                        id: 'pages.admin.auditLogs.success',
                        defaultMessage: '成功',
                      })
                    : intl.formatMessage({
                        id: 'pages.admin.auditLogs.failure',
                        defaultMessage: '失败',
                      })}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item
                label={intl.formatMessage({
                  id: 'pages.admin.auditLogs.resourceType',
                  defaultMessage: '资源类型',
                })}
              >
                {detailItem.resourceType || '-'}
              </Descriptions.Item>
              <Descriptions.Item
                label={intl.formatMessage({
                  id: 'pages.admin.auditLogs.resourceId',
                  defaultMessage: '资源 ID',
                })}
              >
                {detailItem.resourceId || '-'}
              </Descriptions.Item>
              <Descriptions.Item
                label={intl.formatMessage({
                  id: 'pages.admin.auditLogs.ipAddress',
                  defaultMessage: 'IP 地址',
                })}
              >
                {detailItem.ipAddress || '-'}
              </Descriptions.Item>
            </Descriptions>
            {detailItem.detail ? (
              <pre
                style={{
                  background: '#f5f5f5',
                  padding: 12,
                  borderRadius: 6,
                  fontSize: 12,
                  maxHeight: 300,
                  overflow: 'auto',
                  whiteSpace: 'pre-wrap',
                  wordBreak: 'break-all',
                }}
              >
                {detailItem.detail}
              </pre>
            ) : null}
            {detailItem.errorMessage ? (
              <div style={{ marginTop: 8 }}>
                <Tag color="red">
                  {intl.formatMessage({
                    id: 'pages.admin.auditLogs.failure',
                    defaultMessage: '失败',
                  })}
                </Tag>
                <span style={{ marginLeft: 8, color: '#ff4d4f', fontSize: 13 }}>
                  {detailItem.errorMessage}
                </span>
              </div>
            ) : null}
          </>
        )}
      </Modal>
    </>
  );
}
