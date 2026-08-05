/**
 * Dev-only entry: simulates a third-party business system (e.g. Human Resource
 * Intermediary) embedding the AgentSphere widget inside a right-side drawer via
 * `mountTo`. Mirrors the HRI `AgentSphereAssistant` integration:
 *   - open drawer -> mount widget into #widget-container (mountTo)
 *   - close drawer / click mask -> unmount widget
 *   - arrive with ?otc= / ?error= (OIDC callback) -> auto-open + mount
 */

const WIDGET_GLOBAL = 'AgentSphereWidget';

interface WidgetApi {
  init?: (options?: {
    apiBase?: string;
    provider?: string;
    autoLogin?: boolean;
    title?: string;
    mountTo?: HTMLElement;
  }) => { unmount: () => void } | undefined;
}

const drawer = document.getElementById('biz-drawer');
const mask = document.getElementById('drawer-mask');
const container = document.getElementById('widget-container');

let handle: { unmount: () => void } | null = null;

function openDrawer() {
  drawer?.classList.add('open');
  mask?.classList.add('open');
}

function closeDrawer() {
  drawer?.classList.remove('open');
  mask?.classList.remove('open');
  handle?.unmount();
  handle = null;
}

function ensureWidget() {
  if (handle || !container) {
    return;
  }
  const api = (window as unknown as { [K: string]: WidgetApi })[WIDGET_GLOBAL];
  if (!api?.init) {
    return;
  }
  handle =
    api.init({
      apiBase: '/api/v1',
      provider: 'bole',
      autoLogin: true,
      title: 'Agent Sphere 助手',
      mountTo: container,
    }) ?? null;
}

document.getElementById('assistant-btn')?.addEventListener('click', () => {
  openDrawer();
  setTimeout(ensureWidget, 0);
});
document.getElementById('assistant-fab')?.addEventListener('click', () => {
  openDrawer();
  setTimeout(ensureWidget, 0);
});
document.getElementById('drawer-close')?.addEventListener('click', closeDrawer);
mask?.addEventListener('click', closeDrawer);

const params = new URLSearchParams(window.location.search);
if (params.has('otc') || params.has('error')) {
  openDrawer();
  setTimeout(ensureWidget, 0);
}
