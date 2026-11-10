export interface DesktopRuntimeConfig {
  desktop?: boolean;
  apiOrigin?: string;
  webOrigin?: string;
  appVersion?: string;
  platform?: string;
  configured?: boolean;
}

declare global {
  interface Window {
    __AS_RUNTIME__?: DesktopRuntimeConfig;
    agentSphereDesktop?: unknown;
  }
}

export function isDesktop(): boolean {
  return Boolean(window.__AS_RUNTIME__?.desktop);
}

export function getApiOrigin(): string {
  if (typeof window === 'undefined') return '';
  return (window.__AS_RUNTIME__?.apiOrigin ?? '').replace(/\/+$/, '');
}

export function getWebOrigin(): string {
  if (typeof window === 'undefined') return '';
  return window.__AS_RUNTIME__?.webOrigin || window.location.origin;
}
