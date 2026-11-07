export interface WidgetConfig {
  /**
   * AgentSphere backend base. Defaults to '/api/v1' (same-origin proxy assumed,
   * e.g. the business site proxies /api -> backend). Use an absolute URL when
   * the backend is on another origin.
   */
  apiBase?: string;
  /** OIDC provider code configured in agent_identity_provider. */
  provider?: string;
  /**
   * Automatically redirect to the IdP (prompt=none) on load when no token.
   * When the IdP has no session it redirects back with `error` and the widget
   * shows the login screen. Default true.
   */
  autoLogin?: boolean;
  /** Widget title shown in the header. */
  title?: string;
  /**
   * Optional DOM element to mount the widget into. When provided the widget
   * fills the container (position static) instead of rendering a fixed
   * bottom-right floating bubble. Default undefined (floating bubble).
   */
  mountTo?: HTMLElement;
  /**
   * Current user identity supplied by the host (for example Bole's username string). At mount time, if the cached user does not match
   * this identifier (the host switched users but a stale session remains), the widget drops the cache and triggers a fresh login,
   * preventing the old user's token from entering chat.
   */
  identityKey?: string;
  /**
   * Override for the Chrome extension download URL. When absent and the backend has plugin.download-url configured (in-app upload hosting or an external link),
   * the widget fetches it from the public config at runtime and shows a download entry above the "New session" button.
   */
  pluginDownloadUrl?: string;
}

export const DEFAULT_CONFIG: Required<Pick<WidgetConfig, 'apiBase' | 'provider' | 'autoLogin' | 'title'>> = {
  apiBase: '/api/v1',
  provider: 'business',
  autoLogin: true,
  title: 'Agent Sphere Assistant',
};

export const SSO_AUTHORIZE_PATH = '/auth/sso/authorize';
export const SSO_EXCHANGE_PATH = '/auth/sso/exchange';
export const SSO_QUERY_PARAM_OTC = 'otc';
export const SSO_QUERY_PARAM_ERROR = 'error';

/**
 * Request header declaring the call surface - must match the backend `SessionSurfaceHolder.HEADER`.
 *
 * <p>The server validates session creation/sends against the instance's `allow_widget_session` switch; without the header the call is treated as the **main site**,
 * so instances with the widget disabled would still be allowed here. Every request must therefore carry it.
 */
export const CLIENT_SURFACE_HEADER = 'X-Client-Surface';
export const CLIENT_SURFACE_WIDGET = 'widget';
