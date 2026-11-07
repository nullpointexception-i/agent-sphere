import {
  ApiOutlined,
  BankOutlined,
  ControlOutlined,
  EyeOutlined,
  HomeOutlined,
  IdcardOutlined,
  KeyOutlined,
  LogoutOutlined,
  SafetyCertificateOutlined,
  SafetyOutlined,
  SettingOutlined,
  TagsOutlined,
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
import { Avatar, Button, Dropdown, Layout, Menu, Space } from 'antd';
import { createStyles } from 'antd-style';
import { startTransition } from 'react';
import { LangDropdown } from '@/components/RightContent';
import TenantSwitcher from '@/components/TenantSwitcher';
import { API_PATHS } from '@/constants/endpoints';
import { useCan } from '@/hooks/usePermission';
import { clearStoredUser, ssoDisplayName } from '@/utils/auth';

const { Sider, Content } = Layout;

/** Match the main site's grouped side menu styling. */
const useStyles = createStyles(({ token, css }) => ({
  menu: css`
    .ant-menu-item-group-title {
      color: #1f2328 !important;
      font-size: 12px !important;
      font-weight: 700 !important;
      height: 30px !important;
      line-height: 18px !important;
      padding: 8px 16px 4px !important;
    }

    .ant-menu-item {
      color: ${token.colorTextSecondary} !important;
      height: 40px !important;
      line-height: 40px !important;
      margin: 4px !important;
      border-radius: 6px !important;
    }

    .ant-menu-item-selected {
      background: rgba(0, 0, 0, 0.04) !important;
      color: rgba(0, 0, 0, 0.95) !important;
    }
  `,
}));

const SIDEBAR_ITEMS = [
  {
    group: 'access',
    key: '/admin/users',
    icon: <UserOutlined />,
    locale: 'pages.admin.users.title',
    perm: 'admin:user:read',
  },
  {
    group: 'access',
    key: '/admin/tenants',
    icon: <BankOutlined />,
    locale: 'menu.admin.tenants',
    perm: 'admin:tenant:read',
  },
  {
    group: 'access',
    key: '/admin/roles',
    icon: <TeamOutlined />,
    locale: 'pages.admin.roles.title',
    perm: 'admin:role:read',
  },
  {
    group: 'access',
    key: '/admin/permissions',
    icon: <SafetyOutlined />,
    locale: 'pages.admin.permissions.title',
    perm: 'admin:permission:read',
  },
  {
    group: 'system',
    key: '/admin/settings',
    icon: <SettingOutlined />,
    locale: 'pages.admin.settings.title',
    perm: 'admin:settings:read',
  },
  {
    group: 'system',
    key: '/admin/dictionaries',
    icon: <TagsOutlined />,
    locale: 'pages.admin.dictionaries.title',
    perm: 'admin:dictionary:read',
  },
  {
    group: 'system',
    key: '/admin/audit-logs',
    icon: <EyeOutlined />,
    locale: 'pages.admin.auditLogs.title',
    perm: 'admin:audit-log:read',
  },
  {
    group: 'third-party',
    key: '/admin/identity-providers',
    icon: <IdcardOutlined />,
    locale: 'pages.admin.identityProviders.title',
    perm: 'admin:identity-provider:read',
  },
];

const SIDEBAR_GROUPS = [
  {
    key: 'access',
    icon: <SafetyCertificateOutlined />,
    locale: 'pages.admin.group.access',
    defaultMessage: '账号与权限',
  },
  {
    key: 'system',
    icon: <ControlOutlined />,
    locale: 'pages.admin.group.system',
    defaultMessage: '系统与安全',
  },
  {
    key: 'third-party',
    icon: <ApiOutlined />,
    locale: 'pages.admin.group.thirdParty',
    defaultMessage: '第三方接入',
  },
];

export default function AdminLayout() {
  const intl = useIntl();
  const { styles } = useStyles();
  const location = useLocation();
  const { initialState, setInitialState } = useModel('@@initialState');
  const currentUser = initialState?.currentUser;

  const canTenant = useCan('admin:tenant:read');
  const canRole = useCan('admin:role:read');
  const canPermission = useCan('admin:permission:read');
  const canSettings = useCan('admin:settings:read');
  const canDictionary = useCan('admin:dictionary:read');
  const canAuditLog = useCan('admin:audit-log:read');
  const canIdentityProvider = useCan('admin:identity-provider:read');
  const visibleItems = SIDEBAR_ITEMS.filter((item) => {
    if (item.perm === 'admin:tenant:read') return canTenant;
    if (item.perm === 'admin:role:read') return canRole;
    if (item.perm === 'admin:permission:read') return canPermission;
    if (item.perm === 'admin:settings:read') return canSettings;
    if (item.perm === 'admin:dictionary:read') return canDictionary;
    if (item.perm === 'admin:audit-log:read') return canAuditLog;
    if (item.perm === 'admin:identity-provider:read')
      return canIdentityProvider;
    return true;
  });

  const sidebarMenuItems: MenuProps['items'] = SIDEBAR_GROUPS.map((group) => ({
    type: 'group' as const,
    key: `group-${group.key}`,
    label: (
      <span
        style={{
          display: 'inline-flex',
          alignItems: 'center',
          gap: 6,
        }}
      >
        {group.icon}
        {intl.formatMessage({
          id: group.locale,
          defaultMessage: group.defaultMessage,
        })}
      </span>
    ),
    children: visibleItems
      .filter((item) => item.group === group.key)
      .map((item) => ({
        key: item.key,
        icon: item.icon,
        label: intl.formatMessage({
          id: item.locale,
          defaultMessage: item.locale,
        }),
      })),
  })).filter((group) => (group.children?.length ?? 0) > 0);

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
      umiRequest(API_PATHS.AUTH_LOGOUT, { method: 'POST' }).catch(() => {});
      clearStoredUser();
      history.push('/user/login');
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
        <Space size={8} align="center">
          <Button
            type="text"
            icon={<HomeOutlined />}
            onClick={() => history.push('/dashboard')}
          >
            {intl.formatMessage({
              id: 'pages.admin.backToMain',
              defaultMessage: 'Back to Main',
            })}
          </Button>
          <TenantSwitcher />
          <LangDropdown />
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
              <span>{ssoDisplayName(currentUser)}</span>
            </div>
          </Dropdown>
        </Space>
      </Layout.Header>
      <Layout>
        <Sider
          width={200}
          theme="light"
          style={{ borderRight: '1px solid #f0f0f0' }}
        >
          <Menu
            mode="inline"
            inlineIndent={16}
            className={styles.menu}
            selectedKeys={[location.pathname]}
            style={{ height: '100%', borderRight: 0 }}
            items={sidebarMenuItems}
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
