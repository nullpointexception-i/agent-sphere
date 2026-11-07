import type { Settings as LayoutSettings } from '@ant-design/pro-components';
import { SettingDrawer } from '@ant-design/pro-components';
import type { RequestConfig, RunTimeLayoutConfig } from '@umijs/max';
import {
  FormattedMessage,
  history,
  Link,
  request as umiRequest,
} from '@umijs/max';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import React, { useEffect } from 'react';
import { API_PATHS } from '@/constants/endpoints';

dayjs.extend(relativeTime);

import { Alert, App, Button } from 'antd';
import {
  AvatarDropdown,
  ErrorBoundary,
  LangDropdown,
  OfflineBanner,
} from '@/components';
import MessageInitializer from '@/components/MessageInitializer';
import {
  clearStoredUser,
  getStoredUser,
  setStoredUser,
  ssoDisplayName,
  toProCurrentUser,
} from '@/utils/auth';
import { tracker } from '@/utils/tracker';
import defaultSettings from '../config/defaultSettings';
import { errorConfig } from './requestErrorConfig';

const isDev = process.env.NODE_ENV === 'development';
const loginPath = '/user/login';
const isPublicPath = (pathname: string) =>
  pathname === loginPath ||
  pathname.startsWith('/share/') ||
  pathname.startsWith('/s/');

const CHUNK_RELOAD_KEY = 'agent-sphere-ui:chunk-reloaded';
const VERSION_PARAM = '_v';

function isChunkLoadError(reason: unknown): boolean {
  const error = reason as Error | undefined;
  if (!error) return false;
  return (
    error.name === 'ChunkLoadError' ||
    /(?:loading|failed to load) (?:css )?chunk/i.test(error.message || '') ||
    /Failed to fetch dynamically imported module/i.test(error.message || '')
  );
}

/**
 * After a frontend redeploy, old sessions/caches may still reference stale chunks
 * (the manifest from the old index.html).
 * Async dynamic-import failures surface as unhandledrejection, which React ErrorBoundary
 * cannot catch. Listen here and force a refetch of the latest index.html with a version
 * query (_v), auto-reloading once so users do not have to refresh manually.
 */
function installChunkErrorAutoReload() {
  if (typeof window === 'undefined' || typeof sessionStorage === 'undefined') {
    return;
  }
  window.addEventListener('unhandledrejection', (event) => {
    if (!isChunkLoadError(event.reason)) return;
    try {
      if (sessionStorage.getItem(CHUNK_RELOAD_KEY)) return;
      sessionStorage.setItem(CHUNK_RELOAD_KEY, '1');
    } catch {
      return;
    }
    const version =
      process.env.COMMIT_HASH || Math.random().toString(36).slice(2);
    const sep = window.location.search ? '&' : '?';
    window.location.replace(
      `${window.location.pathname}${window.location.search}${sep}${VERSION_PARAM}=${encodeURIComponent(version)}`,
    );
  });
}

function stripVersionParam() {
  if (typeof window === 'undefined') return;
  const params = new URLSearchParams(window.location.search);
  if (!params.has(VERSION_PARAM)) return;
  params.delete(VERSION_PARAM);
  const qs = params.toString();
  window.history.replaceState(
    null,
    '',
    `${window.location.pathname}${qs ? `?${qs}` : ''}`,
  );
}

export async function getInitialState(): Promise<{
  settings?: Partial<LayoutSettings>;
  currentUser?: API.CurrentUser;
  loading?: boolean;
  fetchUserInfo?: () => Promise<API.CurrentUser | undefined>;
  settingDrawerOpen?: boolean;
  permissions?: string[];
}> {
  stripVersionParam();
  const fetchUserInfo = async () => {
    try {
      const stored = getStoredUser();
      if (!stored) return undefined;
      const user = await umiRequest(API_PATHS.AUTH_ME, {
        skipErrorHandler: true,
      });
      if (!user) {
        clearStoredUser();
        return undefined;
      }
      // SSO users: query the third-party identity (provider_code@subject) during
      // initialization, used for the top-right display
      const ssoIdentity = await umiRequest(API_PATHS.SSO_ME, {
        skipErrorHandler: true,
      }).catch(() => null);
      if (ssoIdentity?.providerCode && ssoIdentity?.subject) {
        user.ssoProviderCode = ssoIdentity.providerCode;
        user.ssoSubject = ssoIdentity.subject;
      }
      // Write fresh /auth/me data (including permissions/roles) back to local storage
      // so permission changes take effect on the next startup without re-login.
      setStoredUser({ ...stored, ...user });
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
  if (!isPublicPath(location.pathname)) {
    const currentUser = await fetchUserInfo();
    // Read permissions from the refreshed /auth/me instead of the login-time snapshot
    const fresh = getStoredUser();
    return {
      fetchUserInfo,
      currentUser,
      permissions: fresh?.permissions,
      settings: defaultSettings as Partial<LayoutSettings>,
      settingDrawerOpen: false,
    };
  }
  let permissions: string[] | undefined;
  try {
    const stored = getStoredUser();
    permissions = stored?.permissions;
  } catch {}
  return {
    fetchUserInfo,
    permissions,
    settings: defaultSettings as Partial<LayoutSettings>,
    settingDrawerOpen: false,
  };
}

export const layout: RunTimeLayoutConfig = ({
  initialState,
  setInitialState,
}) => {
  return {
    menu: {
      locale: true,
      type: 'group',
    },
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
      title: ssoDisplayName(initialState?.currentUser),
      render: (_, avatarChildren) => (
        <AvatarDropdown>{avatarChildren}</AvatarDropdown>
      ),
    },
    footerRender: false,
    onPageChange: () => {
      const { location } = history;
      if (!initialState?.currentUser && !isPublicPath(location.pathname)) {
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
      const isDemo = initialState?.currentUser?.demo === true;
      return (
        <>
          {isDemo && (
            <Alert
              message={<FormattedMessage id="pages.demo.banner" />}
              type="info"
              showIcon
              closable
              banner
              style={{ marginBottom: 0 }}
              action={
                <Button
                  size="small"
                  type="link"
                  onClick={() => history.push('/user/register')}
                >
                  <FormattedMessage id="pages.demo.bannerRegister" />
                </Button>
              }
            />
          )}
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
  installChunkErrorAutoReload();
  return (
    <App>
      <MessageInitializer />
      <OfflineBanner />
      <TrackerInit />
      <ErrorBoundary>{container}</ErrorBoundary>
    </App>
  );
}

function TrackerInit() {
  useEffect(() => {
    // Do not start tracking when signed out: avoids meaningless login-page traffic
    // and 401-triggered refreshes
    if (getStoredUser()?.token) {
      tracker.init();
    }
    return undefined;
  }, []);
  return null;
}
