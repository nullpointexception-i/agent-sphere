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
}

export const DEFAULT_CONFIG: Required<Pick<WidgetConfig, 'apiBase' | 'provider' | 'autoLogin' | 'title'>> = {
  apiBase: '/api/v1',
  provider: 'business',
  autoLogin: true,
  title: 'Agent Sphere 助手',
};

export const SSO_AUTHORIZE_PATH = '/auth/sso/authorize';
export const SSO_EXCHANGE_PATH = '/auth/sso/exchange';
export const SSO_QUERY_PARAM_OTC = 'otc';
export const SSO_QUERY_PARAM_ERROR = 'error';
