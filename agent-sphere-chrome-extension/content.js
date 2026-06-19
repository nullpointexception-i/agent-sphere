/**
 * Content Script - Reads auth state from page, executes browser operations.
 */
(function () {

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
  let lastSessionId = null;
  let lastConnected = null;
  let pendingSessionToast = false;

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

  async function checkAuth() {
    try {
      const raw = localStorage.getItem('agent-user');
      if (!raw) return;
      const user = JSON.parse(raw);
      if (!user || !user.token) return;
      const m = location.pathname.match(/\/chat\/(\d+)/);
      if (!m) return;

      const sid = Number(m[1]);
      if (sid === lastSessionId) return;
      const prevSessionId = lastSessionId;
      lastSessionId = sid;

      // Mark that a session switch happened and show toast once SSE is connected
      if (prevSessionId !== null) {
        pendingSessionToast = true;
      }

      const settings = await getSettings();
      const baseUrl = settings.backendUrl || 'http://localhost:8080';

      chrome.runtime.sendMessage(chrome.runtime.id, {
        type: 'auth',
        token: user.token,
        displayName: user.displayName || '',
        sessionId: sid,
        baseUrl,
      }).catch(() => {});
    } catch (e) {
      if (e.message?.includes('Extension context invalidated')) return;
      console.warn('[AgentSphere] Failed to read auth:', e);
    }
  }

  // Initial check + poll every 1s for session changes
  checkAuth().catch(() => {});
  setInterval(() => { checkAuth().catch(() => {}); }, 1000);

  // --- SSE status toast + session switch notification ---
  setInterval(() => {
    if (!chrome.runtime?.id) return;
    chrome.storage.local.get(['connected', 'sessionId'], (data) => {
      const connected = !!data.connected;

      // Show toast on session switch once SSE is connected
      if (pendingSessionToast && connected) {
        pendingSessionToast = false;
        showToast(true, '🔗 Bridge ready — Session #' + (data.sessionId || ''));
        return;
      }

      // Show/hide toast on connection status change
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

  // --- Editor framework detection for type action ---
  function detectEditorType(el) {
    if (!el) return 'unknown';
    // Draft.js
    if (el.closest('.DraftEditor-root') || el.classList.contains('public-DraftEditor-content') || el.classList.contains('DraftEditor-editorContainer')) {
      return 'draft-js';
    }
    // Quill
    if (el.closest('.ql-container') || el.classList.contains('ql-editor')) {
      return 'quill';
    }
    // ProseMirror / TipTap
    if (el.classList.contains('ProseMirror')) {
      return 'prosemirror';
    }
    // Slate.js
    if (el.closest('[data-slate-editor]') || el.hasAttribute('data-slate-editor')) {
      return 'slate';
    }
    // Lexical (used by some modern editors)
    if (el.closest('[data-lexical-editor]') || el.classList.contains('lexical-editor') || document.querySelector('[data-lexical-editor]')) {
      return 'lexical';
    }
    // Froala
    if (el.closest('.fr-box') || el.classList.contains('fr-element')) {
      return 'froala';
    }
    // TinyMCE
    if (el.closest('.mce-content-body') || el.id?.startsWith('tiny') || document.querySelector('.mce-tinymce')) {
      return 'tinymce';
    }
    // TipTap detached editor (falls back to prosemirror if .ProseMirror present)
    if (el.hasAttribute('contenteditable') || el.isContentEditable) {
      return 'contenteditable';
    }
    if (el.tagName === 'TEXTAREA' || el.tagName === 'INPUT') {
      return 'input';
    }
    return 'unknown';
  }

  // --- Draft.js paste simulation (triggers handlePastedText → React state update) ---
  async function typeInDraftJS(el, text, isAppend) {
    el.focus();
    const currentText = el.textContent || '';
    const finalText = isAppend ? currentText + text : text;

    // Strategy 1: Paste 模拟（触发 Draft.js handlePastedText → React state 更新）
    const dt = new DataTransfer();
    dt.setData('text/plain', finalText);
    dt.setData('text/html', `<p>${finalText.replace(/\n/g, '</p><p>')}</p>`);
    el.dispatchEvent(new ClipboardEvent('paste', {
      clipboardData: dt, bubbles: true, cancelable: true,
    }));
    dt.clearData();

    // 验证：等 React batch update 后检查文本是否写入
    await new Promise(r => setTimeout(r, 50));
    if ((el.textContent || '').includes(finalText.slice(0, 20))) {
      return { success: true, method: 'draft-paste' };
    }

    // Strategy 2: execCommand 兜底（Draft.js 部分版本响应 insertText）
    const sel = window.getSelection();
    sel.collapse(el, el.childNodes.length);
    document.execCommand('insertText', false, finalText);
    await new Promise(r => setTimeout(r, 50));
    if ((el.textContent || '').includes(finalText.slice(0, 20))) {
      return { success: true, method: 'draft-exec' };
    }

    return { success: false, error: 'Draft.js insert failed after paste and execCommand strategies' };
  }

  // --- Quill API insertion (accesses __quill on parent container) ---
  function typeInQuill(el, text, isAppend) {
    el.focus();
    const container = el.closest('.ql-container');
    const quill = container?.__quill || container?.parentElement?.__quill;
    if (quill) {
      const index = isAppend ? quill.getLength() : (quill.getSelection()?.index ?? quill.getLength());
      quill.insertText(index, text, 'user');
      return { success: true, method: 'quill-api' };
    }
    // Fallback: attempt dangerouslyPasteHTML
    if (container) {
      const qlEditor = container.querySelector('.ql-editor');
      if (qlEditor) {
        qlEditor.innerHTML = isAppend ? qlEditor.innerHTML + text : text;
        qlEditor.dispatchEvent(new InputEvent('input', { bubbles: true, cancelable: true }));
        return { success: true, method: 'quill-innerhtml' };
      }
    }
    return { success: false, error: 'Quill instance not found' };
  }

  // --- ProseMirror / TipTap insertion (execCommand already works, add dispatcher fallback) ---
  function typeInProseMirror(el, text, isAppend) {
    el.focus();
    if (isAppend) {
      const sel = window.getSelection();
      sel.collapse(el, el.childNodes.length);
    }
    document.execCommand('insertText', false, text);
    el.dispatchEvent(new InputEvent('input', { bubbles: true, cancelable: true }));
    return { success: true, method: 'prosemirror-exec' };
  }

  // --- Lexical editor insertion ---
  function typeInLexical(el, text, isAppend) {
    el.focus();
    if (isAppend) {
      const sel = window.getSelection();
      sel.collapse(el, el.childNodes.length);
    }
    document.execCommand('insertText', false, text);
    el.dispatchEvent(new InputEvent('input', { bubbles: true, cancelable: true }));
    return { success: true, method: 'lexical-exec' };
  }

  // --- Insert text into plain contenteditable ---
  function typeInContentEditable(el, text, isAppend) {
    el.focus();
    if (isAppend) {
      const sel = window.getSelection();
      sel.collapse(el, el.childNodes.length);
      document.execCommand('insertText', false, text);
    } else {
      el.textContent = text;
    }
    el.dispatchEvent(new InputEvent('input', { bubbles: true, cancelable: true }));
    return { success: true, method: 'contenteditable' };
  }

  // --- React-compatible input/textarea setter (native setter + _valueTracker cleanup) ---
  function typeInInput(el, text, isAppend) {
    el.focus();
    const currentValue = el.value;
    const newValue = isAppend ? currentValue + text : text;
    const proto = el.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
    const nativeSetter = Object.getOwnPropertyDescriptor(proto, 'value')?.set;
    if (!nativeSetter) {
      el.value = newValue;
    } else {
      nativeSetter.call(el, newValue);
      // Clear React's value tracker so it detects the change
      const tracker = el._valueTracker;
      if (tracker) {
        try { tracker.setValue(currentValue); } catch (e) {}
      }
    }
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
    return { success: true, method: 'input-native' };
  }

  // --- Listen for browser operation commands from background ---
  chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
    if (msg.type === 'browser_operation') {
      execute(msg.action, msg.params).then(sendResponse);
      return true;
    }
    if (msg.type === 'page_screenshot') {
      window.postMessage({ type: 'page_screenshot', screenshot: msg.screenshot, url: msg.url }, '*');
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
    try {
      highlightElement(params.selector);
      switch (action) {
      case 'navigate':
        return { success: true };

      case 'click': {
          let el = null;
          if (params.selector) el = document.querySelector(params.selector);
          if (!el && params.text) {
            const text = params.text.replace(/'/g, "\\'");
            // Phase 1: exact normalize-space XPath
            el = document.evaluate(`//*[text()[normalize-space()='${text}']]`, document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue;
            // Phase 2: contains() on direct text node
            if (!el) el = document.evaluate(`//*[contains(text(), '${text}')]`, document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue;
            // Phase 3: any descendant contains → up-search to clickable ancestor
            if (!el) {
              const anyNode = document.evaluate(`//*[contains(., '${text}')]`, document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue;
              if (anyNode) {
                el = anyNode.closest('a, button, [role="button"], [onclick], summary, [aria-haspopup], [tabindex]:not([tabindex="-1"])');
                if (!el) el = anyNode; // React delegation handles click on child elements
              }
            }
          }
          if (!el && params.text) el = document.querySelector(`[aria-label="${params.text}"]`);
          if (!el) return { success: false, error: 'Element not found: ' + (params.selector || params.text) };
          // 表单提交类元素 → 提取 action URL，不依赖 click()
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
          // <form> 元素本身 → 提取 action + 第一输入项
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
          el.click();
          // Poll briefly for SPA URL change (routing is async after el.click())
          let urlAfter = urlBefore;
          if (!newTabExpected) {
            for (let i = 0; i < 5; i++) {
              if (location.href !== urlBefore) { urlAfter = location.href; break; }
              await new Promise(r => setTimeout(r, 100));
            }
          }
          return { success: true, data: { tag: el.tagName.toLowerCase(), text: el.textContent?.trim().slice(0, 100), _url: urlAfter, _newTabExpected: newTabExpected } };
        }

        case 'type': {
          let el = document.querySelector(params.selector);
          if (!el) return { success: false, error: 'Input not found: ' + params.selector };

          // 如果选中的是非编辑 DIV，向内找 contenteditable 子元素
          if (el.tagName === 'DIV' && !el.isContentEditable) {
            const inner = el.querySelector('[contenteditable="true"]');
            if (inner) el = inner;
          }

          const isAppend = params.append === true;
          const editorType = detectEditorType(el);

          switch (editorType) {
            case 'draft-js':
              return typeInDraftJS(el, params.text, isAppend);
            case 'quill':
              return typeInQuill(el, params.text, isAppend);
            case 'prosemirror':
              return typeInProseMirror(el, params.text, isAppend);
            case 'lexical':
              return typeInLexical(el, params.text, isAppend);
            case 'contenteditable':
              return typeInContentEditable(el, params.text, isAppend);
            case 'input':
              return typeInInput(el, params.text, isAppend);
            default:
              // 未知类型 — 尝试降级链
              if (el.isContentEditable) {
                return typeInContentEditable(el, params.text, isAppend);
              }
              if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA') {
                return typeInInput(el, params.text, isAppend);
              }
              return { success: false, error: 'Unsupported element: ' + el.tagName };
          }
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
            const buttons = [...document.querySelectorAll('button, input[type="submit"], input[type="button"], a[role="button"]')]
              .map(el => ({
                tag: el.tagName.toLowerCase(),
                type: el.type || '',
                selector: el.id ? `#${el.id}` : el.className ? `.${el.className.split(' ').filter(Boolean).join('.')}` : '',
                text: el.textContent?.trim().slice(0, 50) || el.getAttribute('aria-label') || el.getAttribute('title') || el.value?.slice(0, 50) || '',
              })).filter(b => b.text || b.selector);
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
          const root = params.selector
            ? document.querySelector(params.selector)
            : document.body;
          if (!root) return { success: false, error: 'Root not found' };
          const dom = domToJSON(root) || {};
          dom._url = location.href;
          return { success: true, data: dom };
        }

        case 'executeJS': {
          // Delegated to background.js chrome.debugger.Runtime.evaluate (bypasses CSP)
          const result = await chrome.runtime.sendMessage(chrome.runtime.id, {
            type: 'execute_js',
            tabId: params.tabId || null,
            code: params.code,
          });
          return result || { success: false, error: 'executeJS delegation returned no result' };
        }

        default:
          return { success: false, error: 'Unknown action: ' + action };
      }
    } catch (e) {
      return { success: false, error: e.message };
    }
  }

  function domToJSON(node) {
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
