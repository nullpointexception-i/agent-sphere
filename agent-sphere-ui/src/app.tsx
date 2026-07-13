import type { Settings as LayoutSettings } from '@ant-design/pro-components';
import { SettingDrawer } from '@ant-design/pro-components';
import type { RequestConfig, RunTimeLayoutConfig } from '@umijs/max';
import { history, Link, request as umiRequest } from '@umijs/max';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import React from 'react';

dayjs.extend(relativeTime);

import { App } from 'antd';
import {
  AvatarDropdown,
  ErrorBoundary,
  LangDropdown,
  OfflineBanner,
} from '@/components';
import MessageInitializer from '@/components/MessageInitializer';
import { clearStoredUser, getStoredUser, toProCurrentUser } from '@/utils/auth';
import defaultSettings from '../config/defaultSettings';
import { errorConfig } from './requestErrorConfig';

const isDev = process.env.NODE_ENV === 'development';
const loginPath = '/user/login';

export async function getInitialState(): Promise<{
  settings?: Partial<LayoutSettings>;
  currentUser?: API.CurrentUser;
  loading?: boolean;
  fetchUserInfo?: () => Promise<API.CurrentUser | undefined>;
  settingDrawerOpen?: boolean;
}> {
  const fetchUserInfo = async () => {
    try {
      const stored = getStoredUser();
      if (!stored) return undefined;
      const user = await umiRequest('/api/v1/auth/me', {
        skipErrorHandler: true,
      });
      if (!user) {
        clearStoredUser();
        return undefined;
      }
      return toProCurrentUser(user);
    } catch (_error) {
      clearStoredUser();
      const { pathname, search, hash } = history.location;
      history.replace(
        `${loginPath}?redirect=${encodeURIComponent(pathname + search + hash)}`,
      );
    }
    return undefined;
  };
  const { location } = history;
  if (location.pathname !== loginPath) {
    const currentUser = await fetchUserInfo();
    return {
      fetchUserInfo,
      currentUser,
      settings: defaultSettings as Partial<LayoutSettings>,
      settingDrawerOpen: false,
    };
  }
  return {
    fetchUserInfo,
    settings: defaultSettings as Partial<LayoutSettings>,
    settingDrawerOpen: false,
  };
}

export const layout: RunTimeLayoutConfig = ({
  initialState,
  setInitialState,
}) => {
  return {
    menuItemRender: (item, dom) => {
      if (item.path) {
        return (
          <Link to={item.path} prefetch>
            {dom}
          </Link>
        );
      }
      return dom;
    },
    actionsRender: () => [<LangDropdown key="lang" />],
    avatarProps: {
      src: initialState?.currentUser?.avatar,
      title:
        initialState?.currentUser?.englishName ||
        initialState?.currentUser?.name ||
        'User',
      render: (_, avatarChildren) => (
        <AvatarDropdown>{avatarChildren}</AvatarDropdown>
      ),
    },
    footerRender: false,
    onPageChange: () => {
      const { location } = history;
      if (!initialState?.currentUser && location.pathname !== loginPath) {
        history.replace(
          `${loginPath}?redirect=${encodeURIComponent(location.pathname + location.search + location.hash)}`,
        );
      }
    },
    bgLayoutImgList: [
      {
        src: '/images/layout-avatar-1.png',
        left: 85,
        bottom: 100,
        height: '303px',
      },
      {
        src: '/images/layout-avatar-2.png',
        bottom: -68,
        right: -45,
        height: '303px',
      },
      {
        src: '/images/layout-avatar-3.png',
        bottom: 0,
        left: 0,
        width: '331px',
      },
    ],
    ErrorBoundary,
    menuHeaderRender: false,
    childrenRender: (children) => {
      return (
        <>
          {children}
          {isDev && (
            <SettingDrawer
              disableUrlParams
              enableDarkTheme
              collapse={initialState?.settingDrawerOpen}
              onCollapseChange={(open) => {
                setInitialState((s) => ({
                  ...s,
                  settingDrawerOpen: open,
                }));
              }}
              settings={initialState?.settings}
              onSettingChange={(settings) => {
                setInitialState((s) => ({
                  ...s,
                  settings,
                }));
              }}
            />
          )}
        </>
      );
    },
    ...initialState?.settings,
  };
};

export const request: RequestConfig = {
  baseURL: '',
  ...errorConfig,
};

export function rootContainer(container: React.ReactNode) {
  return (
    <App>
      <MessageInitializer />
      <OfflineBanner />
      <ErrorBoundary>{container}</ErrorBoundary>
    </App>
  );
}
