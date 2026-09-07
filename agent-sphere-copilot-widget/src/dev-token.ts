/**
 * Dev-only playground helper (imported only from index.html — never part of the
 * IIFE lib build). Lets you skip the OIDC SSO round-trip by pasting a token or
 * a full `agent-sphere-widget:agent-user` JSON into sessionStorage.
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
    // 纯 token 字符串
    return { token: trimmed, user: null };
  } catch {
    return { token: trimmed, user: null };
  }
}

/** 用 token 打一次受保护接口，确认后端认可它。返回 { ok, status } 或 { ok:false, network:true }。 */
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
      <strong style="font-size:13px;">Token 注入（仅 dev）</strong>
      <button id="aw-dev-token-toggle" style="border:none;background:none;cursor:pointer;font-size:14px;color:#6b7280;">✕</button>
    </div>
    <textarea id="aw-dev-token-input" rows="4" spellcheck="false"
      placeholder="粘贴 token，或整个 agent-user JSON"
      style="width:100%;box-sizing:border-box;resize:vertical;border:1px solid #d1d5db;border-radius:6px;
      padding:6px 8px;font:12px/1.5 monospace;outline:none;"></textarea>
    <div style="display:flex;gap:8px;margin-top:8px;">
      <button id="aw-dev-token-save" style="flex:1;border:none;background:#2563eb;color:#fff;border-radius:6px;padding:6px 0;cursor:pointer;">验证并写入</button>
      <button id="aw-dev-token-clear" style="flex:1;border:1px solid #d1d5db;background:#fff;color:#6b7280;border-radius:6px;padding:6px 0;cursor:pointer;">清空并刷新</button>
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
    const raw = sessionStorage.getItem(USER_KEY);
    if (!raw) {
      setStatus('当前：未注入。填入 token 后点「验证并写入」。写入成功刷新后会自动打开抽屉。\n注意：token 必须由本地后端 localhost:8080 签发（本页 /api 走代理到 8080）。');
      return;
    }
    try {
      const u = JSON.parse(raw);
      setStatus(
        `当前已注入：${u.ssoProviderCode ? u.ssoProviderCode + '@' : ''}${u.ssoSubject || u.username || u.displayName || '?'}` +
        `\ntoken: ${String(u.token || '').slice(0, 24)}…`,
      );
    } catch {
      setStatus('当前 sessionStorage 内容不是合法 JSON，读取失败。');
    }
  };
  refreshStatus();
  // 窗口 focus 时刷新状态（其它 tab 可能改过）
  window.addEventListener('focus', refreshStatus);

  // widget 因 401 自动登出时给出解释，避免“点开还是让登陆”成谜
  window.addEventListener(LOGOUT_EVENT, () => {
    setStatus(
      'widget 因后端 401 自动登出 —— 当前 token 无效/已过期/不属于本地后端。\n请重新获取 token（主站 localhost:8000 登录或 mock IdP :9000 走一次）后「验证并写入」。',
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
      setStatus('输入为空。', '#b91c1c');
      return;
    }
    setStatus('正在验证 token 与本地后端连通…');
    const probe = await probeToken(parsed.token);
    if (probe.network) {
      setStatus('无法连接本地后端（GET /api/v1/sso/me 网络失败）——确认 localhost:8080 已启动、vite 代理正常。', '#b91c1c');
      return;
    }
    if (!probe.ok) {
      setStatus(
        `后端拒绝该 token（GET /sso/me 返回 ${probe.status}）。\n若为 401/403：token 无效、过期、或来自其它后端 —— 请从本地后端（主站 localhost:8000 登录 / mock IdP :9000）重新获取。`,
        '#b91c1c',
      );
      return;
    }

    // 通过验证：写入。user 优先用粘贴的完整 JSON，否则用裸 token 包装成 dev 用户
    const user =
      parsed.user ??
      {
        id: 1,
        username: 'dev-user',
        displayName: 'dev 用户',
        englishName: '',
        email: '',
        avatar: '',
        token: parsed.token,
        status: 'ACTIVE',
      };
    try {
      sessionStorage.setItem(USER_KEY, JSON.stringify(user));
      sessionStorage.removeItem(AUTO_LOGIN_TRIED_KEY);
      setStatus('√ token 有效，已写入 sessionStorage，正在刷新并自动打开…', '#16a34a');
      window.location.reload();
    } catch (err) {
      setStatus(`写入失败：${String(err)}`, '#b91c1c');
    }
  });

  panel.querySelector('#aw-dev-token-clear')!.addEventListener('click', () => {
    sessionStorage.removeItem(USER_KEY);
    sessionStorage.removeItem(AUTO_LOGIN_TRIED_KEY);
    input.value = '';
    setStatus('已清空。刷新后若 autoLogin 开启将走正常 SSO。');
    window.location.reload();
  });
}

function ensureToggleButton(): void {
  const btn = document.createElement('button');
  btn.id = 'aw-dev-token-fab';
  btn.textContent = '🪙 填 Token';
  btn.title = '跳过 SSO：手动注入 token / agent-user JSON（仅 dev）';
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