import {
  KeyOutlined,
  LogoutOutlined,
  SafetyOutlined,
  SettingOutlined,
  TeamOutlined,
  UserOutlined,
} from '@ant-design/icons';
import {
  history,
  Outlet,
  request as umiRequest,
  useIntl,
  useLocation,
  useModel,
} from '@umijs/max';
import type { MenuProps } from 'antd';
import { Avatar, Dropdown, Layout, Menu, Typography } from 'antd';
import { startTransition } from 'react';
import { useCan } from '@/hooks/usePermission';
import { clearStoredUser } from '@/utils/auth';

const { Sider, Content } = Layout;

const SIDEBAR_ITEMS = [
  {
    key: '/admin/users',
    icon: <UserOutlined />,
    locale: 'pages.admin.users.title',
    perm: 'admin:user:read',
  },
  {
    key: '/admin/roles',
    icon: <TeamOutlined />,
    locale: 'pages.admin.roles.title',
    perm: 'admin:role:read',
  },
  {
    key: '/admin/permissions',
    icon: <SafetyOutlined />,
    locale: 'pages.admin.permissions.title',
    perm: 'admin:permission:read',
  },
  {
    key: '/admin/settings',
    icon: <SettingOutlined />,
    locale: 'pages.admin.settings.title',
    perm: 'admin:settings:read',
  },
];

export default function AdminLayout() {
  const intl = useIntl();
  const location = useLocation();
  const { initialState, setInitialState } = useModel('@@initialState');
  const currentUser = initialState?.currentUser;

  const canUser = useCan('admin:user:read');
  const canRole = useCan('admin:role:read');
  const canPerm = useCan('admin:permission:read');
  const canSettings = useCan('admin:settings:read');
  const visibleItems = SIDEBAR_ITEMS.filter((item) => {
    if (item.perm === 'admin:role:read') return canRole;
    if (item.perm === 'admin:settings:read') return canSettings;
    return true;
  });

  const menuItems: MenuProps['items'] = [
    {
      key: 'profile',
      icon: <UserOutlined />,
      label: intl.formatMessage({
        id: 'pages.settings.profile',
        defaultMessage: 'Profile',
      }),
    },
    {
      key: 'password',
      icon: <KeyOutlined />,
      label: intl.formatMessage({
        id: 'pages.settings.password',
        defaultMessage: 'Change Password',
      }),
    },
    { type: 'divider' },
    {
      key: 'back',
      icon: <SettingOutlined />,
      label: intl.formatMessage({
        id: 'pages.admin.backToMain',
        defaultMessage: '返回主站',
      }),
    },
    { type: 'divider' },
    {
      key: 'logout',
      icon: <LogoutOutlined />,
      label: intl.formatMessage({
        id: 'pages.logout',
        defaultMessage: 'Logout',
      }),
    },
  ];

  const onMenuClick: MenuProps['onClick'] = (event) => {
    const { key } = event;
    if (key === 'logout') {
      startTransition(() =>
        setInitialState((s: any) => ({ ...s, currentUser: undefined })),
      );
      umiRequest('/api/v1/auth/logout', { method: 'POST' }).catch(() => {});
      clearStoredUser();
      history.push('/user/login');
      return;
    }
    if (key === 'back') {
      history.push('/dashboard');
      return;
    }
    history.push(`/account/${key}`);
  };

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Layout.Header
        style={{
          background: '#fff',
          borderBottom: '1px solid #f0f0f0',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          padding: '0 24px',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <img src="/logo.svg" alt="logo" style={{ height: 28, width: 28 }} />
          <span style={{ fontSize: 16, fontWeight: 600 }}>AS Admin</span>
        </div>
        <Dropdown
          menu={{ items: menuItems, onClick: onMenuClick }}
          placement="bottomRight"
          arrow
        >
          <div
            style={{
              cursor: 'pointer',
              display: 'flex',
              alignItems: 'center',
              gap: 8,
            }}
          >
            <Avatar
              src={currentUser?.avatar}
              icon={!currentUser?.avatar ? <UserOutlined /> : undefined}
            />
            <span>
              {currentUser?.name || currentUser?.englishName || 'User'}
            </span>
          </div>
        </Dropdown>
      </Layout.Header>
      <Layout>
        <Sider
          width={200}
          theme="light"
          style={{ borderRight: '1px solid #f0f0f0' }}
        >
          <Menu
            mode="inline"
            selectedKeys={[location.pathname]}
            style={{ height: '100%', borderRight: 0 }}
            items={visibleItems.map((item) => ({
              ...item,
              label: intl.formatMessage({
                id: item.locale,
                defaultMessage: item.locale,
              }),
            }))}
            onClick={({ key }) => history.push(key)}
          />
        </Sider>
        <Content
          style={{
            padding: 24,
            background: '#f5f5f5',
            minHeight: 'calc(100vh - 64px)',
          }}
        >
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  );
}
