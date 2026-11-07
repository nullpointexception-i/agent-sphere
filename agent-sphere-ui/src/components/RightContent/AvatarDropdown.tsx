import {
  DisconnectOutlined,
  KeyOutlined,
  LogoutOutlined,
  SettingOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { history, request as umiRequest, useIntl, useModel } from '@umijs/max';
import type { MenuProps } from 'antd';
import { Spin } from 'antd';
import React, { startTransition } from 'react';
import { API_PATHS } from '@/constants/endpoints';
import { clearStoredUser } from '@/utils/auth';
import HeaderDropdown from '../HeaderDropdown';

type GlobalHeaderRightProps = {
  children?: React.ReactNode;
};

export const AvatarDropdown: React.FC<GlobalHeaderRightProps> = ({
  children,
}) => {
  const loginOut = async (endpoint: string = API_PATHS.AUTH_LOGOUT) => {
    try {
      await umiRequest(endpoint, { method: 'POST' });
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
  const intl = useIntl();

  const onMenuClick: MenuProps['onClick'] = (event) => {
    const { key } = event;
    if (key === 'logout') {
      startTransition(() => {
        setInitialState((s) => ({ ...s, currentUser: undefined }));
      });
      loginOut();
      return;
    }
    if (key === 'logout-all') {
      startTransition(() => {
        setInitialState((s) => ({ ...s, currentUser: undefined }));
      });
      // Revoke all sessions for this user: other devices / the widget must sign in again
      loginOut(API_PATHS.AUTH_LOGOUT_ALL);
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
  const canAdmin =
    currentUser?.access === 'admin' ||
    (permissions ?? []).some((permission) => permission.startsWith('admin:'));

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
      key: 'logout-all',
      icon: <DisconnectOutlined />,
      label: intl.formatMessage({
        id: 'pages.logoutAll',
        defaultMessage: 'Logout All Devices',
      }),
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
