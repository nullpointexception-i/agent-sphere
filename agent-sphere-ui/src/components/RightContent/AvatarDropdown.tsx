import {
  KeyOutlined,
  LogoutOutlined,
  SettingOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { history, request as umiRequest, useIntl, useModel } from '@umijs/max';
import type { MenuProps } from 'antd';
import { Spin } from 'antd';
import React, { startTransition } from 'react';
import { clearStoredUser } from '@/utils/auth';
import HeaderDropdown from '../HeaderDropdown';

type GlobalHeaderRightProps = {
  children?: React.ReactNode;
};

export const AvatarDropdown: React.FC<GlobalHeaderRightProps> = ({
  children,
}) => {
  const loginOut = async () => {
    try {
      await umiRequest('/api/v1/auth/logout', { method: 'POST' });
    } catch {}
    clearStoredUser();
    const { search, pathname } = window.location;
    const urlParams = new URL(window.location.href).searchParams;
    const searchParams = new URLSearchParams({
      redirect: pathname + search,
    });
    const redirect = urlParams.get('redirect');
    if (window.location.pathname !== '/user/login' && !redirect) {
      history.replace({
        pathname: '/user/login',
        search: searchParams.toString(),
      });
    }
  };
  const { initialState, setInitialState } = useModel('@@initialState');

  const onMenuClick: MenuProps['onClick'] = (event) => {
    const { key } = event;
    if (key === 'logout') {
      startTransition(() => {
        setInitialState((s) => ({ ...s, currentUser: undefined }));
      });
      loginOut();
      return;
    }
    if (key === 'admin') {
      history.push('/admin/users');
      return;
    }
    history.push(`/account/${key}`);
  };

  if (!initialState) {
    return <Spin size="small" />;
  }

  const { currentUser, permissions } = initialState;
  const canAdmin = permissions?.includes('admin:settings:read') ?? false;

  const intl = useIntl();

  if (!currentUser) {
    return <Spin size="small" />;
  }

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
    ...(canAdmin
      ? [
          {
            type: 'divider' as const,
          },
          {
            key: 'admin',
            icon: <SettingOutlined />,
            label: intl.formatMessage({
              id: 'menu.admin',
              defaultMessage: 'System Admin',
            }),
          },
        ]
      : []),
    {
      type: 'divider' as const,
    },
    {
      key: 'logout',
      icon: <LogoutOutlined />,
      label: intl.formatMessage({
        id: 'pages.logout',
        defaultMessage: 'Logout',
      }),
    },
  ];

  return (
    <HeaderDropdown
      placement="bottomRight"
      menu={{
        selectedKeys: [],
        onClick: onMenuClick,
        items: menuItems,
      }}
      arrow
    >
      {children}
    </HeaderDropdown>
  );
};
