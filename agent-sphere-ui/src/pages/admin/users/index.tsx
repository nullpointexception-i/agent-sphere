import { PERM } from '@/constants/permissions';
import { useIntl } from '@umijs/max';
import { App, Button, Checkbox, Modal, Space, Table, Tag } from 'antd';
import { useEffect, useState } from 'react';
import { Can } from '@/components/Can';
import { agentApi } from '@/services/agentSphere/api';

interface UserItem {
  id: number;
  username: string;
  displayName: string;
  englishName: string;
  status: string;
  createdAt: string;
}

export default function AdminUsers() {
  const intl = useIntl();
  const { message } = App.useApp();
  const [users, setUsers] = useState<UserItem[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [loading, setLoading] = useState(false);
  const [roleModalUser, setRoleModalUser] = useState<UserItem | null>(null);
  const [allRoles, setAllRoles] = useState<any[]>([]);
  const [userRoleIds, setUserRoleIds] = useState<number[]>([]);
  const [roleSaving, setRoleSaving] = useState(false);

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

  const handleOpenRoleModal = async (user: UserItem) => {
    setRoleModalUser(user);
    try {
      const roles = await agentApi.admin.roles.listAll();
      setAllRoles(roles || []);
    } catch {
      setAllRoles([]);
    }
    try {
      const urs = await agentApi.admin.roles.listByUser(user.id);
      setUserRoleIds((urs || []).map((r: any) => r.id));
    } catch {
      setUserRoleIds([]);
    }
  };

  const handleSaveRoles = async () => {
    if (!roleModalUser) return;
    setRoleSaving(true);
    try {
      await agentApi.admin.roles.assignUser(roleModalUser.id, userRoleIds);
      message.success(
        intl.formatMessage({
          id: 'pages.save.success',
          defaultMessage: 'Saved',
        }),
      );
      setRoleModalUser(null);
    } catch {
      message.error(
        intl.formatMessage({
          id: 'pages.chat.saveFailed',
          defaultMessage: 'Save failed',
        }),
      );
    } finally {
      setRoleSaving(false);
    }
  };

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
      dataIndex: 'roles',
      key: 'roles',
      render: (roles: string[]) =>
        roles?.length ? roles.map((r) => <Tag key={r}>{r}</Tag>) : '-',
    },
    {
      title: intl.formatMessage({
        id: 'pages.table.status',
        defaultMessage: 'Status',
      }),
      dataIndex: 'status',
      key: 'status',
      render: (s: string) => (
        <Tag color={s === 'ACTIVE' ? 'green' : 'default'}>{s || '-'}</Tag>
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
        <Can code={PERM.adminRoleAssign}>
          <Button
            type="link"
            size="small"
            onClick={() => handleOpenRoleModal(record)}
          >
            {intl.formatMessage({
              id: 'pages.admin.users.assignRole',
              defaultMessage: 'Assign Roles',
            })}
          </Button>
        </Can>
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
          pageSize: 10,
          onChange: (p) => {
            setPage(p);
            loadUsers(p);
          },
          showSizeChanger: false,
        }}
        size="small"
      />
      <Modal
        title={intl.formatMessage({
          id: 'pages.admin.users.assignRole',
          defaultMessage: 'Assign Roles',
        })}
        open={!!roleModalUser}
        onOk={handleSaveRoles}
        onCancel={() => setRoleModalUser(null)}
        confirmLoading={roleSaving}
      >
        <Checkbox.Group
          value={userRoleIds}
          onChange={(vals) => setUserRoleIds(vals as number[])}
        >
          <Space orientation="vertical">
            {allRoles.map((r: any) => (
              <Checkbox key={r.id} value={r.id}>
                {r.name}
                <span style={{ color: '#999', marginLeft: 8, fontSize: 12 }}>
                  ({r.code})
                </span>
              </Checkbox>
            ))}
          </Space>
        </Checkbox.Group>
      </Modal>
    </>
  );
}
