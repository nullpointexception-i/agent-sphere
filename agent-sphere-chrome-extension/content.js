/**
 * Content Script — reads auth state from page, executes browser operations.
 * Primary execution layer: click/type/getContent/hover/closeDialogs run here (ISOLATED world).
 * executeJS is handled entirely in the background (MAIN-world scripting → chrome.debugger).
 */
(function () {

  // Idempotency guard: multiple injection triggers may call executeScript more
  // than once. Running twice would duplicate listeners/polls and act twice.
  if (window.__asContentLoaded) return;
  window.__asContentLoaded = true;

  // --- Inject MAIN-world script to intercept window.open ---
  // (content script ISOLATED world cannot override page methods)
  // Use chrome-extension:// file reference instead of inline <script> to avoid CSP
  try {
    const s = document.createElement('script');
    s.src = chrome.runtime.getURL('page-script.js');
    s.onload = () => s.remove();
    document.documentElement.appendChild(s);
  } catch (e) { /* fallback to target="_blank" detection only */ }

  // --- Read auth and session info ---
  let lastConnected = null;
  let sessionMissingLogged = false;

  // widget 用户 token：page-script.js（MAIN world）经 postMessage 转发（content script 无法读页面 sessionStorage）
  let widgetUserToken = null;
  let widgetDisplayName = '';
  let lastReportedToken = null;

  // --- 用户维度 token 上报（去重），建立用户级 task 连接 ---
  function reportAuthToken(token, displayName) {
    if (!token || token === lastReportedToken) return;
    lastReportedToken = token;
    getSettings().then((settings) => {
      chrome.runtime.sendMessage(chrome.runtime.id, {
        type: 'auth_token',
        token,
        displayName: displayName || '',
        baseUrl: settings.backendUrl || 'http://localhost:8080',
      }).catch(() => {});
    });
  }

  window.addEventListener('message', (event) => {
    if (event.data?.type !== 'agent-sphere:session-data') return;
    const userRaw = event.data.user;
    let parsedUser = null;
    if (userRaw) {
      try {
        parsedUser = JSON.parse(userRaw);
      } catch (e) {
        parsedUser = null;
      }
    }
    if (parsedUser && parsedUser.token) {
      widgetUserToken = parsedUser.token;
      widgetDisplayName = parsedUser.displayName || '';
      reportAuthToken(widgetUserToken, widgetDisplayName);
    } else {
      widgetUserToken = null;
    }
    checkAuth().catch(() => {});
  });

  async function getSettings() {
    return new Promise((resolve) => {
      chrome.storage.local.get(['settings'], (data) => {
        resolve(data.settings || { frontendUrl: 'http://localhost:8000', backendUrl: 'http://localhost:8080' });
      });
    });
  }

  function logToBackground(action, success, detail) {
    chrome.runtime.sendMessage(chrome.runtime.id, {
      type: 'log',
      action,
      success,
      detail: detail || '',
      time: Date.now(),
    }).catch(() => {});
  }

  // Resolve the current page's user token (widget sessionStorage bridge, or main-site localStorage); 不依赖会话
  async function resolveUser() {
    try {
      const settings = await getSettings();
      const baseUrl = settings.backendUrl || 'http://localhost:8080';

      // 优先直读页面 sessionStorage（content script 与页面同源，可访问同源存储）。
      // 认证后 ~1s 内即可拿到 token，摆脱 page-script 桥的注入时序，避免连接延迟数秒~十几秒。
      try {
        const widgetRaw = sessionStorage.getItem('agent-sphere-widget:agent-user');
        if (widgetRaw) {
          const widgetUser = JSON.parse(widgetRaw);
          if (widgetUser && widgetUser.token) {
            return {
              token: widgetUser.token,
              displayName:
                widgetUser.ssoProviderCode && widgetUser.ssoSubject
                  ? `${widgetUser.ssoProviderCode}@${widgetUser.ssoSubject}`
                  : widgetUser.displayName || '',
              baseUrl,
            };
          }
        }
      } catch (e) {
        // storage 受限或解析失败：忽略，走其它来源
      }

      if (widgetUserToken) {
        return {
          token: widgetUserToken,
          displayName: widgetDisplayName || '',
          baseUrl,
        };
      }

      const raw = localStorage.getItem('agent-user');
      if (raw) {
        const user = JSON.parse(raw);
        if (user && user.token) {
          return {
            token: user.token,
            displayName:
              user.ssoProviderCode && user.ssoSubject
                ? `${user.ssoProviderCode}@${user.ssoSubject}`
                : user.displayName || '',
            baseUrl,
          };
        }
      }
      return null;
    } catch (e) {
      if (e.message?.includes('Extension context invalidated')) return null;
      console.warn('[AgentSphere] resolveUser error:', e);
      return null;
    }
  }

  async function checkAuth() {
    try {
      const info = await resolveUser();
      if (!info) {
        if (!sessionMissingLogged) {
          sessionMissingLogged = true;
          console.log(
            '[AgentSphere] No user token on this page yet — make sure the widget/main site is logged in',
            location.href,
          );
        }
        return;
      }
      reportAuthToken(info.token, info.displayName);
    } catch (e) {
      if (e.message?.includes('Extension context invalidated')) return;
      console.warn('[AgentSphere] Failed to read auth:', e);
    }
  }

  checkAuth().catch(() => {});
  setInterval(() => { checkAuth().catch(() => {}); }, 1000);

  window.addEventListener('agent-sphere:session-change', () => {
    checkAuth().catch(() => {});
  });

  // --- Task connection status toast (user-level task 流) ---
  setInterval(() => {
    if (!chrome.runtime?.id) return;
    chrome.storage.local.get(['taskConnected'], (data) => {
      const connected = !!data.taskConnected;
      if (connected === lastConnected) return;
      lastConnected = connected;
      showToast(connected);
    });
  }, 2000);

  let toastTimer = null;

  function showToast(connected, customMsg) {
    const existing = document.getElementById('as-toast');
    if (existing) existing.remove();
    if (toastTimer) clearTimeout(toastTimer);

    const toast = document.createElement('div');
    toast.id = 'as-toast';
    toast.textContent = customMsg || (connected ? '🔗 Bridge connected' : '⚠️ Bridge disconnected');
    Object.assign(toast.style, {
      position: 'fixed', bottom: '24px', right: '24px',
      padding: '12px 20px', borderRadius: '12px',
      font: '14px/1.4 -apple-system, sans-serif',
      zIndex: '2147483647',
      boxShadow: '0 4px 16px rgba(0,0,0,0.15)',
      backdropFilter: 'blur(8px)',
      transition: 'all 0.3s ease',
      background: connected ? '#dafbe1' : '#ffebe9',
      color: connected ? '#116329' : '#cf222e',
    });
    document.body.appendChild(toast);

    if (connected || customMsg) {
      toastTimer = setTimeout(() => {
        toast.style.opacity = '0';
        toast.style.transform = 'translateY(10px)';
        setTimeout(() => toast.remove(), 300);
      }, 3000);
    }
  }

  // --- Listen for browser operation commands from background ---
  chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
    if (msg.type === 'agent_sphere_ping') {
      sendResponse({ success: true, ready: true, url: location.href });
      return false;
    }
    if (msg.type === 'browser_operation') {
      execute(msg.action, msg.params).then(sendResponse);
      return true;
    }
  });

  // --- Highlight element being operated on ---
  function highlightElement(selector) {
    if (!selector) return;
    const el = document.querySelector(selector);
    if (!el) return;
    const orig = { outline: el.style.outline, outlineOffset: el.style.outlineOffset };
    el.style.outline = '3px solid #0a84ff';
    el.style.outlineOffset = '2px';
    setTimeout(() => {
      el.style.outline = orig.outline;
      el.style.outlineOffset = orig.outlineOffset;
    }, 1500);
  }

  async function execute(action, params) {
    const AS = window.__asContent;
    try {
      highlightElement(params.selector);
      switch (action) {
      // 只读回读输入框当前值（不滚动、不受歧义/在屏约束）。供 type 后核对是否被累加/残留。
      case 'readInput': {
          let el = null;
          if (params.ref != null) el = AS.locateByRef(params.ref, params.frameId);
          else if (params.selector) el = AS.querySelectorAllCrossFrames(params.selector, params.scope, params.frameId)[0] || null;
          else if (params.text) el = AS.locateByText(params.text, params.occurrence, params.scope, params.frameId);
          if (!el) return { success: true, data: { ok: false, found: false } };
          let value = '';
          try {
            if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA') value = el.value || '';
            else if (el.isContentEditable) value = el.textContent || '';
            else value = el.value || '';
          } catch (e) { /* ignore */ }
          return { success: true, data: { ok: true, found: true, value, frame: frameOf(el) } };
        }

      // 内部定位：跨帧找到元素并计算主视口坐标，供 background 走 CDP 受信输入。
      case 'locate': {
          const t0 = Date.now();
          const wait = params.waitMs || 3000;
          let resolved = AS.resolveActionTarget(params);
          while ((!resolved.ok && resolved.error === 'not_found') && Date.now() - t0 < wait) {
            await new Promise((r) => setTimeout(r, 150));
            resolved = AS.resolveActionTarget(params);
          }
          if (!resolved.ok) {
            if (resolved.error === 'ambiguous') {
              return { success: true, data: { ok: false, error: 'ambiguous', matches: resolved.matches, suggested: resolved.suggested } };
            }
            return { success: false, error: 'Element not found', errorCategory: 'not_found' };
          }
          const el = resolved.el;
          // 屏外/离屏元素先拉进视口再算坐标；仍不可点则 not_found（绝不盲点）。
          try { el.scrollIntoView({ block: 'center', inline: 'center' }); } catch (e) { /* ignore */ }
          await new Promise((r) => setTimeout(r, 60));
          const point = AS.mainFramePoint(el);
          if (!point) return { success: false, error: 'Element not clickable (off-screen)', errorCategory: 'not_found' };
          return {
            success: true,
            data: {
              ok: true,
              point,
              count: resolved.count,
              tag: (el.tagName || '').toLowerCase(),
              text: (el.textContent || el.value || '').trim().slice(0, 100),
            },
          };
        }

      case 'wait': {
          // wait(ms) 固定等待；或 wait(selector|text|ref, timeout) 等待元素出现
          if (params.selector || params.text || params.ref != null) {
            const el = await AS.waitForElement(params, params.timeout || 8000, 200);
            return { success: true, data: { _url: location.href, _title: document.title, found: !!el, ...pageHints() } };
          }
          const ms = Math.min(params.ms || 1000, 30000);
          await new Promise((r) => setTimeout(r, ms));
          return { success: true, data: { _url: location.href, _title: document.title, ...pageHints() } };
        }

        case 'scroll': {
          const dir = params.direction || 'down';
          if (params.selector || params.text || params.ref != null) {
            const el = await AS.waitForElement(params, params.waitMs || 3000);
            if (!el) return { success: false, error: 'Element not found', errorCategory: 'not_found' };
            try { el.scrollIntoView({ block: 'center', inline: 'center' }); } catch (e) { /* ignore */ }
            return { success: true, data: { scrolled: 'element' } };
          }
          const amount = params.amount || Math.max(window.innerHeight * 0.8, 200);
          if (dir === 'up') window.scrollBy(0, -amount);
          else if (dir === 'left') window.scrollBy(-amount, 0);
          else if (dir === 'right') window.scrollBy(amount, 0);
          else window.scrollBy(0, amount);
          await new Promise((r) => setTimeout(r, 100));
          return { success: true, data: { scrolled: dir } };
        }

        case 'select': {
          const el = await AS.waitForElement(params, params.waitMs || 3000);
          if (!el || el.tagName !== 'SELECT') {
            return { success: false, error: 'select element not found: ' + (params.ref != null ? 'ref ' + params.ref : params.selector), errorCategory: 'not_found' };
          }
          if (params.value != null) {
            el.value = params.value;
          } else if (params.label != null) {
            const opt = [...el.options].find((o) => o.textContent?.trim() === params.label || o.value === params.label);
            if (opt) el.value = opt.value;
          }
          el.dispatchEvent(new Event('input', { bubbles: true }));
          el.dispatchEvent(new Event('change', { bubbles: true }));
          return { success: true, data: { selected: el.value } };
        }

        case 'upload': {
          const el = await AS.waitForElement(params, params.waitMs || 3000);
          if (!el || el.tagName !== 'INPUT' || el.type !== 'file') {
            return { success: false, error: 'file input not found: ' + (params.ref != null ? 'ref ' + params.ref : params.selector), errorCategory: 'not_found' };
          }
          if (!params.fileName || !params.fileBase64) {
            return { success: false, error: 'fileName and fileBase64 required for upload' };
          }
          const binary = atob(params.fileBase64);
          const bytes = new Uint8Array(binary.length);
          for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
          const file = new File([bytes], params.fileName, { type: params.fileType || 'application/octet-stream' });
          const dt = new DataTransfer();
          dt.items.add(file);
          el.files = dt.files;
          el.dispatchEvent(new Event('change', { bubbles: true }));
          el.dispatchEvent(new DragEvent('drop', { bubbles: true, cancelable: true, dataTransfer: dt }));
          return { success: true, data: { method: 'dataTransfer', file: params.fileName } };
        }

        case 'closeDialogs': {
          let closed = 0;
          const DIALOG_SEL = DIALOG_SELECTOR;
          const dialogs = [...document.querySelectorAll(DIALOG_SEL)];
          for (const d of dialogs) {
            const closeBtn = d.querySelector(
              '[aria-label="Close"], [aria-label="关闭"], [class*="close"], .ant-lpt-modal-close, .ant-modal-close, [class*="-modal-close"]',
            );
            if (closeBtn) {
              closeBtn.click();
              closed++;
              continue;
            }
            const ack = [...d.querySelectorAll('button')].find((b) => b.textContent?.trim() === '知道了');
            if (ack) {
              ack.click();
              closed++;
            }
          }
          const toast = document.getElementById('as-toast');
          if (toast) toast.remove();
          return { success: true, data: { closed, dialogs: dialogs.length } };
        }

        case 'getContent': {
          if (params.mode === 'summary') {
            // 跨帧扫描（同源 iframe + shadow），每项带 frame；frameId=0 时仅顶层。
            const qAll = (sel) => AS.querySelectorAllCrossFrames(sel, params.scope, params.frameId);
            const inputs = qAll('input, textarea, select, [contenteditable="true"], [role="combobox"]')
              .map(el => ({
                tag: el.tagName.toLowerCase(),
                type: el.type || '',
                name: el.name || '',
                frame: frameOf(el),
                selector: el.id ? `#${el.id}` : el.name ? `[name="${el.name}"]` : el.className && typeof el.className === 'string' ? `.${el.className.trim().split(/\s+/).filter(Boolean).join('.')}` : '',
                placeholder: el.placeholder || '',
                value: el.value || el.textContent?.slice(0, 50) || '',
              })).filter(i => i.selector || i.placeholder || i.name);
            const buttons = qAll('button, input[type="submit"], input[type="button"], a[role="button"], [role="button"]')
              .map(el => {
                const name = (
                  el.textContent?.trim() ||
                  el.getAttribute('aria-label') ||
                  el.getAttribute('title') ||
                  el.getAttribute('alt') ||
                  el.value?.trim() ||
                  el.querySelector('svg title')?.textContent?.trim() ||
                  ''
                ).slice(0, 50);
                return {
                  tag: el.tagName.toLowerCase(),
                  type: el.type || '',
                  role: el.getAttribute('role') || (el.tagName === 'BUTTON' ? 'button' : el.tagName === 'A' ? 'link' : ''),
                  frame: frameOf(el),
                  selector: el.id ? `#${el.id}` : el.className ? `.${el.className.split(' ').filter(Boolean).join('.')}` : '',
                  text: name,
                };
              }).filter(b => b.text || b.selector);
            const forms = qAll('form').map(f => ({
              selector: f.id ? `#${f.id}` : f.className ? `.${f.className.split(' ').filter(Boolean).join('.')}` : '',
              action: f.action || '',
              method: f.method || 'get',
              inputs: [...f.querySelectorAll('input[name], select[name], textarea[name]')].length,
            })).filter(f => f.inputs > 0);
            const navLinks = qAll('nav a, [role="navigation"] a, [role="menubar"] a, [role="menuitem"] a')
              .map(el => ({
                text: el.textContent?.trim() || el.getAttribute('aria-label') || '',
                href: el.href || '',
              })).filter(l => l.text && l.href);
            const sections = qAll('details, [aria-expanded]')
              .map(el => ({
                tag: el.tagName.toLowerCase(),
                label: el.getAttribute('aria-label') || el.getAttribute('title') || el.textContent?.trim().slice(0, 60) || '',
                expanded: el.getAttribute('aria-expanded') ?? (el.hasAttribute('open') ? 'true' : null),
              })).filter(s => s.label);
            const dialogs = qAll('[role="dialog"]')
              .map(d => {
                const labelId = d.getAttribute('aria-labelledby');
                const labelEl = labelId && d.ownerDocument && d.ownerDocument.getElementById(labelId);
                return {
                  title: labelEl?.textContent?.trim() || d.getAttribute('aria-label') || '',
                  frame: frameOf(d),
                  inputs: [...d.querySelectorAll('input:not([type="hidden"]), textarea, select')].length,
                  buttons: [...d.querySelectorAll('button, [role="button"]')].map(b => b.textContent?.trim() || b.getAttribute('aria-label') || '').filter(Boolean),
                };
              }).filter(d => d.title || d.inputs > 0 || d.buttons.length > 0);
            return { success: true, data: { _url: location.href, _title: document.title, inputs, buttons, forms, navLinks, sections, dialogs, ...pageHints() } };
          }
          if (params.mode === 'extract') {
            // 结构化提取重复列表块（候选人卡片等）：一次返回数组，避免多次 full DOM 读取
            if (!params.selector) return { success: false, error: 'selector required for extract mode' };
            let nodes = [];
            try {
              document.querySelector(params.selector); // validate selector before cross-frame traversal
              nodes = AS.querySelectorAllCrossFrames(params.selector, params.scope, params.frameId);
            } catch (e) {
              return { success: false, error: 'Invalid selector: ' + params.selector, errorCategory: 'not_found' };
            }
            const max = params.max || 50;
            const textMax = params.textMax || 200;
            const fields = Array.isArray(params.fields) && params.fields.length ? params.fields : ['text'];
            const matches = nodes.slice(0, max).map((el, i) => {
              const rec = { index: i + 1, tag: el.tagName.toLowerCase(), frame: frameOf(el) };
              for (const f of fields) {
                if (f === 'text') {
                  rec.text = el.textContent?.trim().slice(0, textMax) || '';
                } else if (f === 'href') {
                  rec.href = el.getAttribute('href') || (el.tagName === 'A' ? el.href : '') || '';
                } else if (f === 'value') {
                  rec.value = el.value || '';
                } else if (f.startsWith('.')) {
                  // 相对子选择器：取首个匹配子元素的文本
                  const sub = el.querySelector(f.slice(1));
                  rec[f] = sub?.textContent?.trim().slice(0, textMax) || '';
                } else if (f.startsWith('@')) {
                  rec[f.slice(1)] = el.getAttribute(f.slice(1)) || '';
                } else {
                  rec[f] = el.getAttribute(f) || '';
                }
              }
              return rec;
            });
            return { success: true, data: { _url: location.href, total: nodes.length, matches, ...pageHints() } };
          }
          if (params.mode === 'query') {
            if (!params.selector) return { success: false, error: 'selector required for query mode' };
            let nodes = [];
            try {
              document.querySelector(params.selector); // validate selector before cross-frame traversal
              nodes = AS.querySelectorAllCrossFrames(params.selector, params.scope, params.frameId);
            } catch (e) {
              return { success: false, error: 'Invalid selector: ' + params.selector, errorCategory: 'not_found' };
            }
            const matches = nodes.slice(0, 50).map((el, i) => ({
              index: i + 1,
              tag: el.tagName.toLowerCase(),
              frame: frameOf(el),
              class: typeof el.className === 'string' ? el.className.trim() : '',
              id: el.id || '',
              text: el.textContent?.trim().slice(0, 80) || '',
              placeholder: el.getAttribute('placeholder') || '',
              value: el.value || '',
            }));
            return { success: true, data: { _url: location.href, total: nodes.length, matches } };
          }
          if (params.mode === 'container' || params.mode === 'containers') {
            let want = [];
            if (Array.isArray(params.selectors)) want = params.selectors.filter(Boolean);
            else if (typeof params.selector === 'string' && params.selector.trim()) want = params.selector.split(',').map(s => s.trim()).filter(Boolean);
            const docs = AS.collectFrameDocs ? AS.collectFrameDocs(params.scope, params.frameId) : [document];
            const out = [];
            for (const sel of want) {
              let present = false, count = 0, frame = '', sample = '';
              for (const doc of docs) {
                let nodes;
                try { nodes = doc.querySelectorAll(sel); } catch (e) { nodes = []; continue; }
                if (nodes.length) {
                  present = true;
                  count += nodes.length;
                  if (!frame) {
                    const el = nodes[0];
                    frame = frameOf(el) || '';
                    try {
                      sample = (el.id ? '#' + el.id : '') + (typeof el.className === 'string' ? '.' + el.className.split(/\s+/).filter(Boolean).slice(0, 4).join('.') : '');
                    } catch (e2) { /* ignore */ }
                  }
                }
              }
              out.push({ selector: sel, present, count, frame, sample });
            }
            return { success: true, data: { _url: location.href, _title: document.title, containers: out, ...pageHints() } };
          }
          if (params.mode === 'snapshot') {
            const max = params.max || 200;
            const els = AS.collectInteractables({ max, frameId: params.frameId });
            const items = els.map((el, i) => ({ ...AS.snapshotItem(el, i), frame: frameOf(el) }));
            return {
              success: true,
              data: {
                _url: location.href,
                _title: document.title,
                count: items.length,
                truncated: items.length >= max,
                domHash: domFingerprint(),
                items,
                ...pageHints(),
              },
            };
          }
          __asDomNodes = 0;
          const root = params.selector
            ? document.querySelector(params.selector)
            : document.body;
          if (!root) return { success: false, error: 'Root not found' };
          const dom = domToJSON(root) || {};
          dom._url = location.href;
          return { success: true, data: dom };
        }

        default:
          return { success: false, error: 'Unknown action: ' + action };
      }
    } catch (e) {
      return { success: false, error: e.message };
    }
  }

  // Global DOM-node budget so a huge page cannot balloon the full-mode payload.
  let __asDomNodes = 0;
  const DOM_NODE_CAP = 2000;

  // 框架型弹层通用选择器（覆盖 Vue v-transfer-dom / 自定义 dialog 渲染到 body 的场景）。
  const DIALOG_SELECTOR =
    '[role="dialog"], [role="alertdialog"], .ant-modal, .el-dialog, .antd-fd-modal, [class*="dialog"], [class*="popup"]';

  // 轻量 DOM 指纹：动作前后对比，供 agent 判断"是否有实际变化"（避免动作后盲目重读）。
  function domFingerprint() {
    try {
      return (
        location.href +
        '|' +
        document.title +
        '|' +
        document.querySelectorAll('a,button,input,textarea,select,[role]').length
      );
    } catch (e) {
      return location.href;
    }
  }

  // ---- 页面提示（动作结果里附带，减少 agent 重读）----

  // 可见对话框标题列表（供 agent 知道弹了什么框、需不需要关）。
  function visibleDialogs() {
    try {
      const seen = new Set();
      const titles = [];
      for (const el of document.querySelectorAll(DIALOG_SELECTOR)) {
        const cs = getComputedStyle(el);
        const r = el.getBoundingClientRect();
        if (cs.display === 'none' || cs.visibility === 'hidden' || (r.width <= 0 && r.height <= 0)) continue;
        const t = (
          el.getAttribute('aria-label') ||
          el.getAttribute('aria-labelledby') ||
          (el.querySelector('[class*="title"], [class*="header"]')?.textContent?.trim() || '')
        ).slice(0, 60);
        if (t && !seen.has(t)) {
          seen.add(t);
          titles.push(t);
        }
      }
      return titles;
    } catch (e) {
      return [];
    }
  }

  // 当前生效的筛选筹码指纹（文本集），供 agent 判断筛选增删是否成功。
  function chipsFingerprint() {
    try {
      const out = [];
      for (const el of document.querySelectorAll('[class*="tag"], [class*="chip"]')) {
        const cs = getComputedStyle(el);
        const r = el.getBoundingClientRect();
        if (cs.display === 'none' || cs.visibility === 'hidden' || (r.width <= 0 && r.height <= 0)) continue;
        const active = el.getAttribute('aria-selected') === 'true'
          || el.classList.contains('checked')
          || el.classList.contains('active')
          || String(el.className).includes('Active');
        if (!active) continue;
        const t = el.textContent?.trim().slice(0, 20);
        if (t) out.push(t);
      }
      return out.sort().join(',');
    } catch (e) {
      return '';
    }
  }

  // 结果计数提示（"共有 N 份简历" 等），屏蔽异步刷新带来的重读。
  function countHint() {
    try {
      const leaf = (pred) => {
        for (const el of document.querySelectorAll('*')) {
          const txt = (el.textContent || '').trim();
          if (txt && !el.children.length && pred(txt)) return txt.slice(0, 40);
        }
        return '';
      };
      const cn = leaf((t) => /共有\s*[\d,]+\s*份/.test(t));
      if (cn) return cn;
      return leaf((t) => /resume/i.test(t) && /\d+/.test(t));
    } catch (e) {
      return '';
    }
  }

  function pageHints() {
    const h = { dialogs: visibleDialogs(), chips: chipsFingerprint() };
    const c = countHint();
    if (c) h.count = c;
    const fr = frameUrls();
    if (fr.length) h.frames = fr;
    return { _hints: h };
  }

  // 元素所在 frame 的 window：同源 iframe 内元素的事件 view 必须用它，否则 iframe 处理器收不到。
  function winOf(el) {
    return (el && el.ownerDocument && el.ownerDocument.defaultView) || window;
  }

  // 元素所在 frame 的 URL（同源 iframe 可读；主文档返回顶层 URL）。用于标注元素所属层。
  function frameOf(el) {
    try {
      const w = winOf(el);
      return w === window ? location.href : (w.location ? w.location.href : '');
    } catch (e) {
      return '';
    }
  }

  // 顶层 + 同源 iframe 当前 URL（便于判定搜索/导航是否真的生效，如 keywords= 参数变化）。
  function frameUrls() {
    const out = [];
    try {
      const topUrl = location.href;
      if (topUrl) out.push(topUrl);
      for (const f of document.querySelectorAll('iframe')) {
        try {
          const h = f.contentWindow && f.contentWindow.location && f.contentWindow.location.href;
          if (h && !out.includes(h)) out.push(h);
        } catch (e) { /* 跨源 iframe 忽略 */ }
      }
    } catch (e) { /* ignore */ }
    return out;
  }

  function domToJSON(node) {
    if (++__asDomNodes > DOM_NODE_CAP) return null;
    if (node.nodeType === Node.TEXT_NODE) {
      const text = node.textContent?.trim();
      return text || null;
    }
    if (node.nodeType !== Node.ELEMENT_NODE) return null;

    const el = node;
    const tag = el.tagName.toLowerCase();
    if (['script', 'style', 'noscript', 'iframe', 'svg', 'canvas'].includes(tag)) return null;

    if (tag === 'input') return { tag: 'input', name: el.name || '', placeholder: el.getAttribute('placeholder') || '', type: el.type || '' };
    if (tag === 'button') return { tag: 'button', text: el.textContent?.trim().slice(0, 100) };
    if (tag === 'textarea') return { tag: 'textarea', placeholder: el.getAttribute('placeholder') || '' };
    if (tag === 'select') return { tag: 'select', children: Array.from(el.options).map(o => o.text).filter(Boolean) };
    if (el.isContentEditable) return { tag, editable: true, placeholder: el.getAttribute('data-placeholder') || '', text: el.textContent?.trim().slice(0, 100) };

    let result = {};
    if (el.id) result._id = el.id;
    const cls = el.className && typeof el.className === 'string' ? el.className.trim() : '';
    if (cls) result._class = cls;

    const children = Array.from(el.childNodes).map(domToJSON).filter(Boolean);
    const directText = el.childNodes.length === 1 && el.firstChild?.nodeType === 3 ? el.textContent?.trim() : null;

    if (directText && children.length === 0) {
      result = { tag, text: directText.slice(0, 200) };
      if (el.id) result._id = el.id;
      if (tag === 'a' && el.href) result._href = el.href;
      if (el.getAttribute('placeholder')) result._ph = el.getAttribute('placeholder');
      return result;
    }

    if (children.length === 0 && !directText) return null;

    result.tag = tag;
    result.children = children;
    if (tag === 'a' && el.href) result._href = el.href;
    if (el.getAttribute('placeholder')) result._ph = el.getAttribute('placeholder');
    if (el.getAttribute('aria-label')) result._label = el.getAttribute('aria-label');

    if (result.children && result.children.length > 50) {
      result.children = result.children.slice(0, 50);
      result._truncated = true;
    }
    return result;
  }
})();
