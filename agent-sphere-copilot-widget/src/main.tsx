import { createRoot, type Root as ReactRoot } from 'react-dom/client';
import { DEFAULT_CONFIG, type WidgetConfig } from './config';
import { clearUser } from './auth';
import { Root } from './Root';
import styles from './styles.css?inline';
import copilotStyles from '@copilotkit/react-core/v2/styles.css?inline';

export interface WidgetHandle {
  unmount: () => void;
}

export interface AgentSphereWidget {
  /** Mount the widget into the current page. Returns a handle to tear it down. */
  init?: (options?: WidgetConfig) => WidgetHandle;
  /** Create the widget in a detached container (advanced usage). */
  mount?: (options?: WidgetConfig) => WidgetHandle;
  /** 清除当前用户会话并重新挂载，触发静默 SSO 重新登录（用户切换时由宿主调用）。 */
  relogin?: () => void;
}

function resolveConfig(options: WidgetConfig = {}): WidgetConfig {
  return {
    ...DEFAULT_CONFIG,
    ...options,
  };
}

function defaultContainer(): HTMLDivElement {
  const container = document.createElement('div');
  container.id = 'agent-sphere-widget-root';
  container.style.position = 'fixed';
  container.style.bottom = '0';
  container.style.right = '0';
  container.style.zIndex = '9999';
  container.style.fontFamily =
    '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif';
  document.body.appendChild(container);
  return container;
}

function hostedContainer(mountTo: HTMLElement): HTMLDivElement {
  const container = document.createElement('div');
  container.id = 'agent-sphere-widget-root';
  container.style.cssText =
    'position:relative;width:100%;height:100%;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"Helvetica Neue",Arial,sans-serif;';
  mountTo.appendChild(container);
  return container;
}

let activeRoot: ReactRoot | null = null;
let activeContainer: HTMLDivElement | null = null;
let lastConfig: WidgetConfig | null = null;

function mountWidget(options: WidgetConfig = {}): WidgetHandle {
  if (activeContainer) {
    activeContainer.remove();
    activeContainer = null;
  }
  if (activeRoot) {
    activeRoot.unmount();
    activeRoot = null;
  }
  const config = resolveConfig(options);
  lastConfig = config;
  activeContainer = config.mountTo ? hostedContainer(config.mountTo) : defaultContainer();
  const shadow = activeContainer.attachShadow({ mode: 'open' });

  const link = document.createElement('link');
  link.rel = 'stylesheet';
  link.href = 'https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600&display=swap';
  link.setAttribute('crossorigin', 'anonymous');
  shadow.appendChild(link);

  const style = document.createElement('style');
  style.textContent = `${copilotStyles}\n${styles}`;
  shadow.appendChild(style);

  const host = document.createElement('div');
  if (config.mountTo) {
    host.className = 'aw-hosted';
    host.style.cssText =
      'position:relative;width:100%;height:100%;display:flex;flex-direction:column;pointer-events:auto;isolation:isolate;';
  } else {
    host.style.cssText =
      'position:fixed;bottom:0;right:0;width:min(95vw,720px);height:min(92vh,760px);pointer-events:auto;isolation:isolate;';
  }
  shadow.appendChild(host);

  activeRoot = createRoot(host);
  activeRoot.render(<Root config={config} />);

  return {
    unmount: () => {
      activeRoot?.unmount();
      activeRoot = null;
      activeContainer?.remove();
      activeContainer = null;
    },
  };
}

const api: AgentSphereWidget = {
  init: mountWidget,
  mount: mountWidget,
  relogin: () => {
    // 清除当前用户会话（内存缓存 + sessionStorage），重置 auto-login 标记，
    // 重新挂载触发新的 Root autoLogin 静默 SSO（为切换后的新用户重新登录）
    clearUser();
    sessionStorage.removeItem('agent-sphere-widget:auto-login-tried');
    if (lastConfig) {
      mountWidget(lastConfig);
    }
  },
};

if (typeof window !== 'undefined') {
  (window as unknown as { AgentSphereWidget?: AgentSphereWidget }).AgentSphereWidget = api;
}

export default api;