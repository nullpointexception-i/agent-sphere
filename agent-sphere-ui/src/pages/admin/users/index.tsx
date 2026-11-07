import { useIntl } from '@umijs/max';
import { Button, Space, Table, Tag } from 'antd';
import { useEffect, useState } from 'react';
import { DEFAULT_PAGE_SIZE } from '@/constants/pagination';
import { PERM } from '@/constants/permissions';
import { STATUS } from '@/constants/status';
import { useCan } from '@/hooks/usePermission';
import { agentApi } from '@/services/agentSphere/api';
import UserAccessModal from './UserAccessModal';

interface UserRoleItem {
  id?: number;
  code: string;
  name?: string;
  tenantId?: number | null;
  tenantName?: string;
  platformRole?: boolean;
}

interface UserItem {
  id: number;
  username: string;
  displayName: string;
  englishName: string;
  status: string;
  createdAt: string;
  roles?: string[];
  roleDetails?: UserRoleItem[];
}

export default function AdminUsers() {
  const intl = useIntl();
  const [users, setUsers] = useState<UserItem[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [loading, setLoading] = useState(false);
  const [accessModalUser, setAccessModalUser] = useState<UserItem | null>(null);
  const canAssignTenant = useCan(PERM.adminTenantAssign);
  const canAssignRole = useCan(PERM.adminRoleAssign);
  const canManageAccess = canAssignTenant && canAssignRole;

  const loadUsers = async (p = page) => {
    setLoading(true);
    try {
      const res = await agentApi.admin.users.list(p, 10);
      setUsers(res.records || []);
      setTotal(res.total || 0);
    } catch {
      setUsers([]);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadUsers();
  }, []);

  const columns = [
    {
      title: intl.formatMessage({ id: 'pages.table.id', defaultMessage: 'ID' }),
      dataIndex: 'id',
      key: 'id',
      width: 80,
    },
    {
      title: intl.formatMessage({
        id: 'pages.register.subtitle',
        defaultMessage: 'Create your account',
      }),
      dataIndex: 'username',
      key: 'username',
    },
    {
      title: intl.formatMessage({
        id: 'pages.settings.nickname',
        defaultMessage: 'Nickname',
      }),
      dataIndex: 'displayName',
      key: 'displayName',
    },
    {
      title: intl.formatMessage({
        id: 'pages.settings.englishName',
        defaultMessage: 'English Name',
      }),
      dataIndex: 'englishName',
      key: 'englishName',
    },
    {
      title: intl.formatMessage({
        id: 'pages.admin.users.roles',
        defaultMessage: 'Roles',
      }),
      key: 'roles',
      render: (_: any, record: UserItem) => {
        const details: UserRoleItem[] = record.roleDetails?.length
          ? record.roleDetails
          : (record.roles || []).map((code) => ({ code }));
        if (!details.length) return '-';
        return details.map((role) => {
          const source = role.platformRole
            ? intl.formatMessage({
                id: 'pages.admin.users.roleSourcePlatform',
                defaultMessage: 'Platform',
              })
            : role.tenantName;
          return (
            <Tag key={`${role.id ?? role.code}-${role.tenantId ?? 'platform'}`}>
              {role.code}
              {source ? ` · ${source}` : ''}
            </Tag>
          );
        });
      },
    },
    {
      title: intl.formatMessage({
        id: 'pages.table.status',
        defaultMessage: 'Status',
      }),
      dataIndex: 'status',
      key: 'status',
      render: (s: string) => (
        <Tag color={s === STATUS.ACTIVE ? 'green' : 'default'}>{s || '-'}</Tag>
      ),
    },
    {
      title: intl.formatMessage({
        id: 'pages.table.created',
        defaultMessage: 'Created',
      }),
      dataIndex: 'createdAt',
      key: 'createdAt',
      render: (t: string) => (t ? new Date(t).toLocaleString() : '-'),
    },
    {
      title: intl.formatMessage({
        id: 'pages.table.actions',
        defaultMessage: 'Actions',
      }),
      key: 'actions',
      render: (_: any, record: UserItem) => (
        <Space>
          {canManageAccess && (
            <Button
              type="link"
              size="small"
              onClick={() => setAccessModalUser(record)}
            >
              {intl.formatMessage({
                id: 'pages.admin.users.accessTitle',
                defaultMessage: 'Tenants and roles',
              })}
            </Button>
          )}
        </Space>
      ),
    },
  ];

  return (
    <>
      <Table
        rowKey="id"
        dataSource={users}
        columns={columns}
        loading={loading}
        pagination={{
          current: page,
          total,
          pageSize: DEFAULT_PAGE_SIZE,
          onChange: (p) => {
            setPage(p);
            loadUsers(p);
          },
          showSizeChanger: false,
        }}
        size="small"
      />
      <UserAccessModal
        open={!!accessModalUser}
        userId={accessModalUser?.id ?? null}
        userName={accessModalUser?.displayName || accessModalUser?.username}
        onCancel={() => setAccessModalUser(null)}
        onSaved={() => {
          setAccessModalUser(null);
          loadUsers();
        }}
      />
    </>
  );
}
