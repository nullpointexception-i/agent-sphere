interface WidgetApi {
  init?: (options?: { apiBase?: string; provider?: string; autoLogin?: boolean; title?: string }) => unknown;
}

const api = (window as unknown as { AgentSphereWidget?: WidgetApi }).AgentSphereWidget;

document.getElementById('mount')?.addEventListener('click', () => {
  api?.init?.({ apiBase: '/api/v1', provider: 'bole', autoLogin: false });
});
