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
      case 'navigate':
        return { success: true };

      case 'click': {
          // 等待元素出现且可交互（动态页面/慢加载）
          const el = await AS.waitForElement(params, params.waitMs || 3000);
          if (!el) {
            return { success: false, error: 'Element not found: ' + (params.ref != null ? 'ref ' + params.ref : params.selector || params.text), errorCategory: 'not_found' };
          }
          const isSubmitBtn = (el.tagName === 'BUTTON' && el.type === 'submit')
            || (el.tagName === 'INPUT' && el.type === 'submit');
          if (isSubmitBtn && el.form) {
            const formData = new FormData(el.form);
            const url = new URL(el.form.action || location.href);
            for (const [key, val] of formData.entries()) {
              url.searchParams.set(key, val);
            }
            return { success: true, data: { _submitUrl: url.href, tag: 'form', text: el.textContent?.trim().slice(0, 100) } };
          }
          if (el.tagName === 'FORM') {
            const formData = new FormData(el);
            const url = new URL(el.action || location.href);
            for (const [key, val] of formData.entries()) {
              url.searchParams.set(key, val);
            }
            return { success: true, data: { _submitUrl: url.href, tag: 'form' } };
          }
          const anchor = el.closest('a');
          const newTabExpected = !!(anchor?.target === '_blank')
            || !!(el.target === '_blank')
            || !!(el.closest('[onclick*="window.open"]'));
          const urlBefore = location.href;
          const domBefore = domFingerprint();
          // Automa-style synthetic mouse events + click() (React-friendly)
          try { el.scrollIntoView({ block: 'center', inline: 'center' }); } catch (e) { /* ignore */ }
          el.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, view: window }));
          el.dispatchEvent(new MouseEvent('mouseup', { bubbles: true, view: window }));
          el.click();
          let urlAfter = urlBefore;
          if (!newTabExpected) {
            for (let i = 0; i < 5; i++) {
              if (location.href !== urlBefore) { urlAfter = location.href; break; }
              await new Promise((r) => setTimeout(r, 100));
            }
          }
          // 动作后短 settle，避免 agent 立即重读
          await new Promise((r) => setTimeout(r, 250));
          return {
            success: true,
            data: {
              tag: el.tagName.toLowerCase(),
              text: el.textContent?.trim().slice(0, 100),
              _url: urlAfter,
              _newTabExpected: newTabExpected,
              changed: location.href !== urlBefore || domFingerprint() !== domBefore,
            },
          };
        }

        case 'type': {
          const el = await AS.waitForElement(params, params.waitMs || 3000);
          if (!el) return { success: false, error: 'Input not found: ' + (params.ref != null ? 'ref ' + params.ref : params.selector), errorCategory: 'not_found' };
          return AS.typeInElement(el, params.text, params.append === true);
        }

        case 'hover': {
          const el = await AS.waitForElement(params, params.waitMs || 3000);
          if (!el) {
            return { success: false, error: 'Element not found: ' + (params.ref != null ? 'ref ' + params.ref : params.selector || params.text), errorCategory: 'not_found' };
          }
          try { el.scrollIntoView({ block: 'center', inline: 'center' }); } catch (e) { /* ignore */ }
          el.dispatchEvent(new MouseEvent('mouseover', { bubbles: true, view: window }));
          el.dispatchEvent(new MouseEvent('mouseenter', { bubbles: true, view: window }));
          el.dispatchEvent(new MouseEvent('mousemove', { bubbles: true, view: window }));
          await new Promise((r) => setTimeout(r, 150));
          return { success: true, data: { tag: el.tagName.toLowerCase(), text: el.textContent?.trim().slice(0, 100) } };
        }

        case 'wait': {
          const timeout = params.timeout || 8000;
          if (params.selector || params.text || params.ref != null) {
            const el = await AS.waitForElement(params, timeout);
            return { success: true, data: { found: !!el } };
          }
          await new Promise((r) => setTimeout(r, params.ms || 0));
          return { success: true };
        }

        case 'key': {
          const key = params.key || params.keyCode || 'Enter';
          const target = await AS.waitForElement(params, params.waitMs || 3000);
          const el = target || document.activeElement || document.body;
          const code = params.codeKey || key;
          const opts = { key, code, bubbles: true, cancelable: true, view: window };
          el.dispatchEvent(new KeyboardEvent('keydown', opts));
          el.dispatchEvent(new KeyboardEvent('keypress', opts));
          el.dispatchEvent(new KeyboardEvent('keyup', opts));
          // 合成键盘事件不触发浏览器默认行为：Enter 时尝试真实表单提交
          if (key === 'Enter' || key === 'NumpadEnter') {
            const form = el.form || el.closest('form');
            if (form) {
              try { form.requestSubmit(); } catch (e) { /* ignore */ }
            }
          }
          return { success: true, data: { key } };
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
          const dialogs = [...document.querySelectorAll('[role="dialog"]')];
          for (const d of dialogs) {
            const closeBtn = d.querySelector(
              '[aria-label="Close"], [aria-label="关闭"], .ant-lpt-modal-close, .ant-modal-close, [class*="-modal-close"]',
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
            const inputs = [...document.querySelectorAll('input, textarea, select, [contenteditable="true"], [role="combobox"]')]
              .map(el => ({
                tag: el.tagName.toLowerCase(),
                type: el.type || '',
                name: el.name || '',
                selector: el.id ? `#${el.id}` : el.name ? `[name="${el.name}"]` : el.className && typeof el.className === 'string' ? `.${el.className.trim().split(/\s+/).filter(Boolean).join('.')}` : '',
                placeholder: el.placeholder || '',
                value: el.value || el.textContent?.slice(0, 50) || '',
              })).filter(i => i.selector || i.placeholder || i.name);
            const buttons = [...document.querySelectorAll('button, input[type="submit"], input[type="button"], a[role="button"], [role="button"]')]
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
                  selector: el.id ? `#${el.id}` : el.className ? `.${el.className.split(' ').filter(Boolean).join('.')}` : '',
                  text: name,
                };
              }).filter(b => b.text || b.selector);
            const forms = [...document.querySelectorAll('form')].map(f => ({
              selector: f.id ? `#${f.id}` : f.className ? `.${f.className.split(' ').filter(Boolean).join('.')}` : '',
              action: f.action || '',
              method: f.method || 'get',
              inputs: [...f.querySelectorAll('input[name], select[name], textarea[name]')].length,
            })).filter(f => f.inputs > 0);
            const navLinks = [...document.querySelectorAll('nav a, [role="navigation"] a, [role="menubar"] a, [role="menuitem"] a')]
              .map(el => ({
                text: el.textContent?.trim() || el.getAttribute('aria-label') || '',
                href: el.href || '',
              })).filter(l => l.text && l.href);
            const sections = [...document.querySelectorAll('details, [aria-expanded]')]
              .map(el => ({
                tag: el.tagName.toLowerCase(),
                label: el.getAttribute('aria-label') || el.getAttribute('title') || el.textContent?.trim().slice(0, 60) || '',
                expanded: el.getAttribute('aria-expanded') ?? (el.hasAttribute('open') ? 'true' : null),
              })).filter(s => s.label);
            const dialogs = [...document.querySelectorAll('[role="dialog"]')]
              .map(d => {
                const labelId = d.getAttribute('aria-labelledby');
                const labelEl = labelId && document.getElementById(labelId);
                return {
                  title: labelEl?.textContent?.trim() || d.getAttribute('aria-label') || '',
                  inputs: [...d.querySelectorAll('input:not([type="hidden"]), textarea, select')].length,
                  buttons: [...d.querySelectorAll('button, [role="button"]')].map(b => b.textContent?.trim() || b.getAttribute('aria-label') || '').filter(Boolean),
                };
              }).filter(d => d.title || d.inputs > 0 || d.buttons.length > 0);
            return { success: true, data: { _url: location.href, _title: document.title, inputs, buttons, forms, navLinks, sections, dialogs } };
          }
          if (params.mode === 'query') {
            if (!params.selector) return { success: false, error: 'selector required for query mode' };
            let nodes = [];
            try {
              nodes = [...document.querySelectorAll(params.selector)];
            } catch (e) {
              return { success: false, error: 'Invalid selector: ' + params.selector, errorCategory: 'not_found' };
            }
            const matches = nodes.slice(0, 50).map((el, i) => ({
              index: i + 1,
              tag: el.tagName.toLowerCase(),
              class: typeof el.className === 'string' ? el.className.trim() : '',
              id: el.id || '',
              text: el.textContent?.trim().slice(0, 80) || '',
              placeholder: el.getAttribute('placeholder') || '',
              value: el.value || '',
            }));
            return { success: true, data: { _url: location.href, total: nodes.length, matches } };
          }
          if (params.mode === 'snapshot') {
            const max = params.max || 200;
            const els = AS.collectInteractables({ max });
            const items = els.map((el, i) => AS.snapshotItem(el, i));
            return {
              success: true,
              data: {
                _url: location.href,
                _title: document.title,
                count: items.length,
                truncated: items.length >= max,
                domHash: domFingerprint(),
                items,
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
