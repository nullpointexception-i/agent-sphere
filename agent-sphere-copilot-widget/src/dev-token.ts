/**
 * Dev-only playground helper (imported only from index.html — never part of the
 * IIFE lib build). Lets you skip the OIDC SSO round-trip by pasting a token or
 * a full `agent-sphere-widget:agent-user` JSON into localStorage.
 *
 * Before saving it validates the token against the local backend (GET /sso/me,
 * proxied by Vite to localhost:8080) so a bad/foreign token is reported
 * immediately instead of silently 401-ing into an automatic logout after mount.
 */

const USER_KEY = 'agent-sphere-widget:agent-user';
const AUTO_LOGIN_TRIED_KEY = 'agent-sphere-widget:auto-login-tried';
const LOGOUT_EVENT = 'agent-sphere:logout';
const ME_PATH = '/api/v1/sso/me';

let panelOpen = false;

function extractToken(value: string): { token: string; user: unknown | null } | null {
  const trimmed = value.trim();
  if (!trimmed) {
    return null;
  }
  try {
    const parsed = JSON.parse(trimmed) as { token?: unknown };
    if (parsed && typeof parsed.token === 'string' && parsed.token) {
      return { token: parsed.token, user: parsed };
    }
    // Plain token string
    return { token: trimmed, user: null };
  } catch {
    return { token: trimmed, user: null };
  }
}

/** Call one protected endpoint with the token to confirm the backend accepts it. Returns { ok, status } or { ok:false, network:true }. */
async function probeToken(token: string): Promise<{ ok: boolean; status?: number; network?: boolean }> {
  try {
    const res = await fetch(ME_PATH, {
      headers: { Authorization: `Bearer ${token}` },
    });
    return { ok: res.ok, status: res.status };
  } catch {
    return { ok: false, network: true };
  }
}

function buildPanel(): void {
  if (document.getElementById('aw-dev-token-panel')) {
    return;
  }
  const panel = document.createElement('div');
  panel.id = 'aw-dev-token-panel';
  panel.style.cssText =
    'position:fixed;top:12px;right:12px;z-index:10000;width:340px;background:#fff;' +
    'border:1px solid #e5e7eb;border-radius:10px;box-shadow:0 8px 30px rgba(0,0,0,.18);' +
    'font:12px -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;color:#111827;' +
    'display:none;padding:10px 12px;box-sizing:border-box;';
  panel.innerHTML = `
    <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:8px;">
      <strong style="font-size:13px;">Token injection (dev only)</strong>
      <button id="aw-dev-token-toggle" style="border:none;background:none;cursor:pointer;font-size:14px;color:#6b7280;">✕</button>
    </div>
    <textarea id="aw-dev-token-input" rows="4" spellcheck="false"
      placeholder="Paste a token, or a full agent-user JSON"
      style="width:100%;box-sizing:border-box;resize:vertical;border:1px solid #d1d5db;border-radius:6px;
      padding:6px 8px;font:12px/1.5 monospace;outline:none;"></textarea>
    <div style="display:flex;gap:8px;margin-top:8px;">
      <button id="aw-dev-token-save" style="flex:1;border:none;background:#2563eb;color:#fff;border-radius:6px;padding:6px 0;cursor:pointer;">Validate and save</button>
      <button id="aw-dev-token-clear" style="flex:1;border:1px solid #d1d5db;background:#fff;color:#6b7280;border-radius:6px;padding:6px 0;cursor:pointer;">Clear and reload</button>
    </div>
    <div id="aw-dev-token-status" style="margin-top:8px;color:#6b7280;font-size:11px;line-height:1.5;
      white-space:pre-wrap;word-break:break-all;"></div>
  `;
  document.body.appendChild(panel);

  const input = panel.querySelector<HTMLTextAreaElement>('#aw-dev-token-input')!;
  const status = panel.querySelector<HTMLDivElement>('#aw-dev-token-status')!;

  const setStatus = (text: string, color = '#6b7280') => {
    status.style.color = color;
    status.textContent = text;
  };

  const refreshStatus = () => {
    const raw = localStorage.getItem(USER_KEY);
    if (!raw) {
      setStatus('Current: not injected. Paste a token and click "Validate and save". After a successful save the page reloads and opens the drawer automatically.\nNote: the token must be issued by the local backend at localhost:8080 (this page proxies /api to 8080).');
      return;
    }
    try {
      const u = JSON.parse(raw);
      setStatus(
        `Current user: ${u.ssoProviderCode ? u.ssoProviderCode + '@' : ''}${u.ssoSubject || u.username || u.displayName || '?'}` +
        `\ntoken: ${String(u.token || '').slice(0, 24)}…`,
      );
    } catch {
      setStatus('localStorage does not contain valid JSON; failed to read it.');
    }
  };
  refreshStatus();
  // Refresh the status when the window regains focus (another tab may have changed it)
  window.addEventListener('focus', refreshStatus);

  // Explain why the widget logged out automatically on 401, so "open it and it still asks me to log in" is not a mystery
  window.addEventListener(LOGOUT_EVENT, () => {
    setStatus(
      'The widget signed out automatically because the backend returned 401 - the current token is invalid, expired, or not issued by the local backend.\nFetch a new token (sign in on the main site localhost:8000, or run the mock IdP at :9000), then click "Validate and save".',
      '#b91c1c',
    );
    refreshStatus();
    panelOpen = true;
    panel.style.display = 'block';
  });

  panel.querySelector('#aw-dev-token-toggle')!.addEventListener('click', () => {
    panelOpen = false;
    panel.style.display = 'none';
  });

  panel.querySelector('#aw-dev-token-save')!.addEventListener('click', async () => {
    const parsed = extractToken(input.value);
    if (!parsed) {
      setStatus('Input is empty.', '#b91c1c');
      return;
    }
    setStatus('Validating the token against the local backend…');
    const probe = await probeToken(parsed.token);
    if (probe.network) {
      setStatus('Cannot reach the local backend (GET /api/v1/sso/me failed) - make sure localhost:8080 is running and the Vite proxy works.', '#b91c1c');
      return;
    }
    if (!probe.ok) {
      setStatus(
        `The backend rejected this token (GET /sso/me returned ${probe.status}).\nIf 401/403: the token is invalid, expired, or from another backend - fetch a new one from the local backend (sign in on the main site localhost:8000 / mock IdP :9000).`,
        '#b91c1c',
      );
      return;
    }

    // Validation passed: write it. Prefer the pasted full JSON for user; otherwise wrap the raw token as a dev user
    const user =
      parsed.user ??
      {
        id: 1,
        username: 'dev-user',
        displayName: 'dev user',
        englishName: '',
        email: '',
        avatar: '',
        token: parsed.token,
        status: 'ACTIVE',
      };
    try {
      localStorage.setItem(USER_KEY, JSON.stringify(user));
      sessionStorage.removeItem(AUTO_LOGIN_TRIED_KEY);
      setStatus('√ Token valid; saved to localStorage. Reloading and opening automatically…', '#16a34a');
      window.location.reload();
    } catch (err) {
      setStatus(`Failed to save: ${String(err)}`, '#b91c1c');
    }
  });

  panel.querySelector('#aw-dev-token-clear')!.addEventListener('click', () => {
    localStorage.removeItem(USER_KEY);
    sessionStorage.removeItem(AUTO_LOGIN_TRIED_KEY);
    input.value = '';
    setStatus('Cleared. After reloading, the normal SSO flow runs when autoLogin is enabled.');
    window.location.reload();
  });
}

function ensureToggleButton(): void {
  const btn = document.createElement('button');
  btn.id = 'aw-dev-token-fab';
  btn.textContent = '🪙 Fill Token';
  btn.title = 'Skip SSO: inject a token / agent-user JSON manually (dev only)';
  btn.style.cssText =
    'position:fixed;top:12px;left:12px;z-index:10000;border:none;background:#2563eb;color:#fff;' +
    'font:12px -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;border-radius:999px;' +
    'padding:6px 12px;cursor:pointer;box-shadow:0 4px 12px rgba(37,99,235,.35);';
  btn.addEventListener('click', () => {
    panelOpen = !panelOpen;
    const panel = document.getElementById('aw-dev-token-panel');
    if (panel) {
      panel.style.display = panelOpen ? 'block' : 'none';
    }
  });
  document.body.appendChild(btn);
}

if (typeof window !== 'undefined') {
  buildPanel();
  ensureToggleButton();
}