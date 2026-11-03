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
  function reportAuthToken(token, displayName, source) {
    if (!token || token === lastReportedToken) return;
    lastReportedToken = token;
    getSettings().then((settings) => {
      chrome.runtime.sendMessage(chrome.runtime.id, {
        type: 'auth_token',
        token,
        displayName: displayName || '',
        source: source || '',
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
      reportAuthToken(widgetUserToken, widgetDisplayName, 'WIDGET');
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

  // Resolve the current page's user token (widget localStorage / main-site localStorage); 不依赖会话
  async function resolveUser() {
    try {
      const settings = await getSettings();
      const baseUrl = settings.backendUrl || 'http://localhost:8080';

      // 优先直读页面存储（content script 与页面同源，可访问同源存储）。
      // widget token 已改为 localStorage（同一浏览器所有标签页共用一份）；
      // sessionStorage 仅在旧版本迁移期兜底。
      try {
        const widgetRaw = localStorage.getItem('agent-sphere-widget:agent-user')
          || sessionStorage.getItem('agent-sphere-widget:agent-user');
        if (widgetRaw) {
          const widgetUser = JSON.parse(widgetRaw);
          if (widgetUser && widgetUser.token) {
            return {
              token: widgetUser.token,
              source: 'WIDGET',
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
          source: 'WIDGET',
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
            source: 'CONSOLE',
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
      reportAuthToken(info.token, info.displayName, info.source);
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

  // --- 多设备选择浮层：后端发现同一用户有多条插件连接时由 background 下发 ---
  function showDeviceChooser(payload) {
    try {
      document.getElementById('agent-sphere-device-chooser')?.remove();
      const devices = Array.isArray(payload?.devices) ? payload.devices : [];
      if (!devices.length) return;
      const preferredDeviceId = payload?.preferredDeviceId || '';
      const preferredDevice = devices.find((d) => d.deviceId && d.deviceId === preferredDeviceId) || null;

      const mask = document.createElement('div');
      mask.id = 'agent-sphere-device-chooser';
      mask.style.cssText = 'position:fixed;inset:0;z-index:2147483647;background:rgba(15,23,42,.45);'
        + 'display:flex;align-items:center;justify-content:center;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;';

      const card = document.createElement('div');
      card.style.cssText = 'background:#fff;border-radius:12px;padding:20px 20px 12px;width:min(92vw,440px);'
        + 'box-shadow:0 20px 50px rgba(0,0,0,.25);';

      const title = document.createElement('div');
      title.textContent = '选择用哪台浏览器执行';
      title.style.cssText = 'font-size:16px;font-weight:600;color:#111827;margin-bottom:6px;';
      card.appendChild(title);

      const desc = document.createElement('div');
      desc.textContent = preferredDevice
        ? `当前执行设备是「${preferredDevice.deviceName || '未知设备'}」。有新设备登录，可切换为下面任意一台。`
        : '检测到同一账号有多台浏览器在线，请选择本次任务要操作的那台。';
      desc.style.cssText = 'font-size:12px;color:#6b7280;line-height:1.6;margin-bottom:12px;white-space:pre-line;';
      card.appendChild(desc);

      for (const device of devices) {
        const selected = !!(device.deviceId && device.deviceId === preferredDeviceId);
        const row = document.createElement('button');
        row.type = 'button';
        row.style.cssText = 'display:flex;align-items:center;gap:8px;width:100%;'
          + 'padding:10px 12px;margin-bottom:8px;border:1px solid #e5e7eb;border-radius:8px;background:#fff;'
          + 'cursor:pointer;font-size:13px;color:#111827;text-align:left;';
        if (selected) {
          row.style.borderColor = '#2563eb';
          row.style.background = '#eff6ff';
        }

        const name = document.createElement('span');
        name.textContent = device.deviceName || '未知设备';
        name.style.cssText = 'flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;';
        row.appendChild(name);

        if (selected) row.appendChild(chooserBadge('执行中', '#dbeafe', '#1d4ed8'));
        if (device.current) row.appendChild(chooserBadge('本机', '#dcfce7', '#166534'));

        const meta = document.createElement('span');
        meta.textContent = device.source === 'CONSOLE' ? '主站' : device.source === 'WIDGET' ? '助手' : '';
        meta.style.cssText = 'font-size:11px;color:#6b7280;flex-shrink:0;';
        row.appendChild(meta);

        row.addEventListener('click', () => {
          chrome.runtime.sendMessage(chrome.runtime.id, {
            type: 'chooseDevice',
            deviceId: device.deviceId,
          }).catch(() => {});
          mask.remove();
        });
        card.appendChild(row);
      }

      const tip = document.createElement('div');
      tip.textContent = '切换后如需修改，可在浏览器扩展的「Devices」标签页里重新选择。';
      tip.style.cssText = 'font-size:11px;color:#6b7280;line-height:1.6;margin:2px 0 10px;';
      card.appendChild(tip);

      const later = document.createElement('button');
      later.type = 'button';
      later.textContent = '稍后再说';
      later.style.cssText = 'width:100%;padding:8px;border:none;background:transparent;color:#6b7280;'
        + 'font-size:12px;cursor:pointer;';
      later.addEventListener('click', () => mask.remove());
      card.appendChild(later);

      mask.appendChild(card);
      document.documentElement.appendChild(mask);
    } catch (e) {
      console.warn('[AgentSphere] showDeviceChooser failed:', e);
    }
  }

  function chooserBadge(text, background, color) {
    const span = document.createElement('span');
    span.textContent = text;
    span.style.cssText = `font-size:10px;line-height:16px;padding:0 6px;border-radius:999px;`
      + `background:${background};color:${color};flex-shrink:0;`;
    return span;
  }

  // --- Listen for browser operation commands from background ---
  chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
    if (msg.type === 'showDeviceChooser') {
      showDeviceChooser(msg);
      sendResponse({ ok: true });
      return false;
    }
    if (msg.type === 'agent_sphere_ping') {
      sendResponse({ success: true, ready: true, url: location.href });
      return false;
    }
    // 光标：ISOLATED world 无法保证盖住站点高层级 z-index，转发给 MAIN world 渲染。
    if (msg.type === 'AGENT_CURSOR_STATE') {
      if (window.top !== window) {
        // 子 frame 不渲染，但也要回执 —— background 靠 ok 决定是否等到达屏障
        sendResponse({ ok: true, rendered: false });
        return false;
      }
      agentCursorMove(msg.x, msg.y, msg.animateMovement, msg.moveSequence);
      sendResponse({ ok: true, rendered: true });
      return false;
    }
    if (msg.type === 'browser_operation') {
      execute(msg.action, msg.params).then(sendResponse);
      return true;
    }
  });

  // --- Agent cursor（可视指针 + 移动路径）---
  // 与 highlightElement 同一机制：content script 直接改页面 DOM，元素就在真实
  // 渲染树上。不需要 MAIN world 注入 / postMessage 桥（content-cursor.js 已删除）。
  // 只有顶层 frame 渲染：background 下发的 x/y 是主视口坐标，子 frame 里定位不到。
  const AS_CURSOR = {
    root: null, pointer: null, dots: [],
    pts: [], x: -999, y: -999,
    raf: 0, timer: 0, target: null, reported: -1, fading: 0, settle: null, localSeq: 0,
  };
  // 按时间插值（而非按帧 ×固定系数）：位移时长与距离、帧率都无关。
  // 早先按帧缓动时，同一段距离在 60fps 下和 rAF 挂起的 32ms 兜底下耗时完全不同，
  // 观感就是「时快时慢、不连贯」。cubic ease-out 起步跟手、收尾干净。
  const AS_CURSOR_DUR = 250;         // 每段位移固定耗时 ms
  const AS_TRAIL_MAX = 18;           // 轨迹点上限
  const AS_TRAIL_MIN_DIST = 4;       // 小于该位移不记点（原地不动时不堆点）
  const AS_TRAIL_MIN_GAP = 14;       // 轨迹采样最小时间间隔 ms（按时长均匀铺点）
  const AS_TRAIL_LIFE = 620;         // 单点存活时长 ms
  const AS_TRAIL_FADE = 460;         // 落位后整体淡出时长 ms
  // 光标落位后常驻可见：会被下一次动作移走，不再自动隐藏。

  function asEaseOutCubic(t) {
    const u = 1 - t;
    return 1 - u * u * u;
  }

  // 经典箭头指针轮廓（放大到 ~1.7 倍系统指针）：半透明深色填充 + 白色描边，
  // 在浅色和深色页面上都能看清，又不会像实心圆那样糊住目标元素。
  const AS_POINTER_SVG =
    "data:image/svg+xml;charset=utf-8,"
    + encodeURIComponent(
      '<svg xmlns="http://www.w3.org/2000/svg" width="34" height="40" viewBox="0 0 17 20">'
      + '<path d="M1 1 L1 15.4 L4.4 12.1 L7 17.7 L9.9 16.6 L7.3 11.1 L12.1 10.9 Z"'
      + ' fill="#0f172a" fill-opacity="0.72" stroke="#ffffff" stroke-opacity="0.9"'
      + ' stroke-width="1.1" stroke-linejoin="round"/></svg>');

  function ensureCursorEl() {
    let root = document.getElementById('__as-agent-cursor');
    if (root && root.isConnected) { AS_CURSOR.root = root; return root; }
    root = document.createElement('div');
    root.id = '__as-agent-cursor';
    root.style.cssText = 'position:fixed;left:0;top:0;width:0;height:0;'
      + 'z-index:2147483647;pointer-events:none;opacity:0;transition:opacity .18s ease';
    (document.body || document.documentElement).appendChild(root);

    // 轨迹点容器放在指针之下，缩放 0 以免污染页面点击
    const trail = document.createElement('div');
    trail.style.cssText = 'position:absolute;left:0;top:0;transform:scale(0)';
    root.appendChild(trail);
    for (let i = 0; i < AS_TRAIL_MAX; i++) {
      const d = document.createElement('div');
      d.style.cssText = 'position:absolute;left:0;top:0;width:6px;height:6px;margin:-3px 0 0 -3px;'
        + 'border-radius:50%;background:#2563eb;will-change:transform,opacity;opacity:0';
      trail.appendChild(d);
      AS_CURSOR.dots.push(d);
    }

    const pointer = document.createElement('div');
    pointer.style.cssText = 'position:absolute;left:0;top:0;width:34px;height:40px;'
      + 'background-image:url("' + AS_POINTER_SVG + '");background-size:100% 100%;'
      + 'filter:drop-shadow(0 2px 6px rgba(15,23,42,.35));will-change:transform';
    root.appendChild(pointer);

    AS_CURSOR.root = root;
    AS_CURSOR.pointer = pointer;
    return root;
  }

  function cursorArrived(seq) {
    AS_CURSOR.reported = seq;
    AS_CURSOR.fading = Date.now();
    // 本地等待者（content 侧动作，如 select/upload）与 background 的到达屏障共用此回调
    const r = AS_CURSOR.settle;
    AS_CURSOR.settle = null;
    if (r) r();
    chrome.runtime.sendMessage({ type: 'notifyCursorArrived', moveSequence: seq }).catch(() => {});
    // 不自动隐藏：指针停在最后落点，直到下一次动作把它移走。
    // 尾迹照旧淡出（renderTrail 在 fading 到 0 后停机，指针本身保持 opacity:1）。
  }

  // content 侧动作（select/upload）没有 background 屏障，就地驱动一次光标落位，
  // 让这些纯 DOM 操作也有和 click/type 一致的视觉线索。
  async function cursorVisitElement(el) {
    if (!el || window.top !== window) return;
    const root = ensureCursorEl();
    if (!root) return;
    try { el.scrollIntoView({ block: 'center', inline: 'nearest', behavior: 'instant' }); } catch (e) { /* ignore */ }
    await AS.waitStableRect(el);
    const pt = AS.mainFramePoint(el);
    if (!pt) return;
    root.style.opacity = '1';
    AS_CURSOR.fading = 0;
    AS_CURSOR.localSeq = (AS_CURSOR.localSeq || 0) + 1;
    const seq = AS_CURSOR.localSeq;
    await new Promise((resolve) => {
      AS_CURSOR.settle = resolve;
      const bail = setTimeout(resolve, 700); // 兜底：渲染器异常时不挂住动作
      AS_CURSOR.settle = () => { clearTimeout(bail); resolve(); };
      agentCursorMove(pt.x, pt.y, true, seq);
    });
  }

  // 任何时刻最多一个 rAF + 一个 timer 在飞，谁先到谁清理另一个并驱动下一帧。
  // （早先版本每帧都新建 timer 而不清除旧的，导致定时器指数堆积 → 浏览器卡死。）
  function scheduleCursorTick() {
    if (AS_CURSOR.raf) cancelAnimationFrame(AS_CURSOR.raf);
    if (AS_CURSOR.timer) clearTimeout(AS_CURSOR.timer);
    AS_CURSOR.raf = requestAnimationFrame(() => {
      AS_CURSOR.raf = 0;
      if (AS_CURSOR.timer) { clearTimeout(AS_CURSOR.timer); AS_CURSOR.timer = 0; }
      cursorTick();
    });
    // rAF 在隐藏标签页里挂起 → 32ms 兜底，保证自动化 tab（用户没盯着）也能动
    AS_CURSOR.timer = setTimeout(() => {
      AS_CURSOR.timer = 0;
      if (AS_CURSOR.raf) { cancelAnimationFrame(AS_CURSOR.raf); AS_CURSOR.raf = 0; }
      cursorTick();
    }, 32);
  }

  function stopCursorTick() {
    if (AS_CURSOR.raf) { cancelAnimationFrame(AS_CURSOR.raf); AS_CURSOR.raf = 0; }
    if (AS_CURSOR.timer) { clearTimeout(AS_CURSOR.timer); AS_CURSOR.timer = 0; }
  }

  function recordTrailPoint(x, y) {
    const now = Date.now();
    const last = AS_CURSOR.pts[AS_CURSOR.pts.length - 1];
    // 按时间 + 距离双阈值采样：按时长均匀铺点，长距离不会点太密、短距离不会点太疏
    if (last
      && now - last.t < AS_TRAIL_MIN_GAP
      && Math.abs(last.x - x) < AS_TRAIL_MIN_DIST
      && Math.abs(last.y - y) < AS_TRAIL_MIN_DIST) return;
    AS_CURSOR.pts.push({ x, y, t: now });
    while (AS_CURSOR.pts.length > AS_TRAIL_MAX) AS_CURSOR.pts.shift();
  }

  // 轨迹渲染：越新的点越大越实，越旧越淡；落位后整体衰减到透明。
  // 只写 transform/opacity（合成层属性），不触发布局。
  function renderTrail() {
    const now = Date.now();
    let fade = 1;
    if (AS_CURSOR.fading) {
      const k = 1 - (now - AS_CURSOR.fading) / AS_TRAIL_FADE;
      fade = k <= 0 ? 0 : k * k;
      if (fade <= 0) AS_CURSOR.fading = 0;
    }
    const pts = AS_CURSOR.pts;
    while (pts.length && (now - pts[0].t > AS_TRAIL_LIFE || fade <= 0)) pts.shift();
    const n = pts.length;
    for (let i = 0; i < AS_CURSOR.dots.length; i++) {
      const dot = AS_CURSOR.dots[i];
      if (i >= n || fade <= 0) { dot.style.opacity = '0'; continue; }
      const p = pts[n - 1 - i];       // 最新的点在最前
      const k = 1 - i / n;             // 0(最新) → 1(最旧)
      dot.style.transform = 'translate3d(' + p.x + 'px,' + p.y + 'px,0) scale(' + (1 - k * 0.62).toFixed(3) + ')';
      dot.style.opacity = (fade * (1 - k) * (1 - k) * 0.55).toFixed(3);
    }
    return n > 0 || !!AS_CURSOR.fading;
  }

  function cursorTick() {
    if (!AS_CURSOR.pointer) return;
    const t = AS_CURSOR.target;
    let arrivedNow = false;
    if (t) {
      const now = Date.now();
      const p = AS_CURSOR_DUR <= 0 ? 1 : Math.min(1, (now - t.t0) / AS_CURSOR_DUR);
      const e = asEaseOutCubic(p);
      AS_CURSOR.x = t.fx + (t.x - t.fx) * e;
      AS_CURSOR.y = t.fy + (t.y - t.fy) * e;
      if (p >= 1) {
        AS_CURSOR.x = t.x;
        AS_CURSOR.y = t.y;
        AS_CURSOR.target = null;
        arrivedNow = true;
      }
      recordTrailPoint(AS_CURSOR.x, AS_CURSOR.y);
      AS_CURSOR.pointer.style.transform = 'translate3d(' + AS_CURSOR.x + 'px,' + AS_CURSOR.y + 'px,0)';
    }
    if (arrivedNow && AS_CURSOR.reported !== t.seq) cursorArrived(t.seq);
    // 目标未达，或轨迹还没淡出完 → 继续；否则自然停机（不会空转）
    if (AS_CURSOR.target || renderTrail()) scheduleCursorTick();
    else stopCursorTick();
  }

  function agentCursorMove(x, y, animate, seq) {
    if (window.top !== window) return; // 只在顶层 frame 渲染
    if (x == null || y == null) return;
    const root = ensureCursorEl();
    if (!root) { cursorArrived(seq); return; }
    root.style.opacity = '1';
    AS_CURSOR.fading = 0; // 新指令取消上一段的淡出
    if (!animate) {
      stopCursorTick();
      AS_CURSOR.target = null;
      AS_CURSOR.x = x;
      AS_CURSOR.y = y;
      AS_CURSOR.pts.length = 0;
      AS_CURSOR.pointer.style.transform = 'translate3d(' + x + 'px,' + y + 'px,0)';
      cursorArrived(seq);
      return;
    }
    // 始终 retarget 到最新请求：飞行中的循环收敛到 target 当前值并回报该 seq，
    // 所以中途改目标时最后一个 seq 仍能解除到达屏障。
    // 新起点必须是「当前位置」而不是上一个 target 的终点 —— 用后者会让指针
    // 在中途改目标时先跳回旧目的地再出发，肉眼可见的瞬移。
    const now = Date.now();
    AS_CURSOR.target = { x, y, seq, t0: now, fx: AS_CURSOR.x, fy: AS_CURSOR.y };
    if (!AS_CURSOR.raf && !AS_CURSOR.timer) scheduleCursorTick();
  }

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

  // 降级复用缓存：background 对同一条命令的三档对齐共享一个 token。
  // 只存最近一次，避免跨命令串味；元素脱离文档时调用方会自动回退到完整重解析。
  let lastResolved = null;

  /** 目标元素自身状态指纹。changed 是全页指纹（URL+title+每帧交互元素数），
   *  纯 CSS 展开 / 勾选切换 / 输入值变化都不会改变它 —— 这两种最常见的成功反馈
   *  恰恰是 changed 测不到的。 */
  function targetFingerprint(el) {
    try {
      return [
        el.tagName,
        typeof el.className === 'string' ? el.className : '',
        el.getAttribute('aria-expanded') || '',
        el.getAttribute('aria-selected') || '',
        el.getAttribute('aria-checked') || '',
        el.disabled ? '1' : '0',
        el.value == null ? '' : String(el.value).slice(0, 60),
        el.textContent ? el.textContent.trim().slice(0, 60) : '',
      ].join('~');
    } catch (e) {
      return '';
    }
  }

  async function execute(action, params) {
    const AS = window.__asContent;
    // 依赖守卫：content-locator.js 先于本文件注入，它一旦没加载成功（语法错误 / 注入被拒），
    // window.__asContent 就不存在，下面每一个 AS.* 调用都会抛 TypeError，且消息各不相同
    // （'reading collectInteractables' / 'reading querySelectorAllCrossFrames' …），
    // 把「一个依赖没到位」放大成十几处语义不明的失败。这里收敛成一条可操作的错误。
    if (!AS || typeof AS.querySelectorAllCrossFrames !== 'function') {
      return {
        success: false,
        error: 'locator not loaded — content-locator.js failed to parse or inject; reload the extension and retry',
        errorCategory: 'inject_failed',
      };
    }
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
          // 降级复用：background 对同一条命令的三档对齐传同一个 token。首档解析成功后
          // 记下元素对象，后两档直接复用 —— 既省掉重复的 DOM 遍历，也避免了「页面重排
          // → 按 ref 重查拿到另一个元素」的漂移。元素脱离文档时自动回退到完整重解析。
          const token = params._reuseToken || null;
          let el = null;
          let resolved = null;
          let ambiguous = null;
          if (token && lastResolved && lastResolved.token === token) {
            const cached = lastResolved.el;
            if (cached && cached.isConnected) el = AS.retargetByLabel(cached);
          }

          if (!el) {
            const t0 = Date.now();
            const wait = params.waitMs || 3000;
            resolved = AS.resolveActionTarget(params);
            while ((!resolved.ok && resolved.error === 'not_found') && Date.now() - t0 < wait) {
              await new Promise((r) => setTimeout(r, 150));
              resolved = AS.resolveActionTarget(params);
            }
            // 歧义不再就地失败：按 DOM 序取首个继续尝试（background 还有三档滚动降级），
            // 但把 matches 一并带出去，降级成功也可能是点偏了，调用方据此判断。
            if (!resolved.ok && resolved.error === 'ambiguous') {
              ambiguous = { matches: resolved.matches, suggested: resolved.suggested };
              resolved = await AS.resolveActionTarget({ ...params, occurrence: 1 });
            }
            if (!resolved.ok) {
              if (resolved.error === 'ambiguous') {
                return { success: true, data: { ok: false, error: 'ambiguous', matches: resolved.matches, suggested: resolved.suggested } };
              }
              return { success: false, error: 'Element not found', errorCategory: 'not_found' };
            }
            // LLM 给的 ref 常落在包裹控件的 <label> 上，先重定向到真实控件
            el = AS.retargetByLabel(resolved.el);
            if (token) lastResolved = { token, el: resolved.el };
          }

          const align = params.align || { block: 'center', inline: 'nearest' };

          // 前置断言 → instant 滚动 → 等几何收敛 → 滚动后二次断言
          // （对应 Codex elementState visible+enabled 滚动前后各跑一遍）
          const pre = AS.assertActionable(el);
          if (!pre.ok) {
            return { success: false, error: 'Element is not ' + pre.state, errorCategory: 'not_interactable', blockedBy: pre.state };
          }
          // behavior:'instant' 关掉平滑滚动动画，否则动画中途取坐标必然偏
          try { el.scrollIntoView({ ...align, behavior: 'instant' }); } catch (e) { /* ignore */ }
          const settled = await AS.waitStableRect(el);
          if (!settled) {
            return { success: false, error: 'Element did not settle after scroll', errorCategory: 'not_interactable', blockedBy: 'unsettled' };
          }
          const post = AS.assertActionable(el);
          if (!post.ok) {
            return { success: false, error: 'Element is not ' + post.state, errorCategory: 'not_interactable', blockedBy: post.state };
          }

          const point = AS.mainFramePoint(el);
          if (!point) return { success: false, error: 'Element not clickable (off-screen)', errorCategory: 'not_found' };
          // 命中自校验只对需要落点的动作有意义：type/key/select/upload 走键盘或纯 DOM，
          // elementFromPoint 对它们是纯浪费。
          const local = params.needHitTest === false ? { ok: true, desc: '' } : AS.hitDescriptorAt(el, point.x, point.y);
          return {
            success: true,
            data: {
              ok: true,
              point,
              count: resolved ? resolved.count : 1,
              tag: (el.tagName || '').toLowerCase(),
              text: (el.textContent || el.value || '').trim().slice(0, 100),
              // 动作前基线：写动作后由 background 对比 → changed；clickable 供判断是否为真实点击目标
              preHash: domFingerprint(),
              // 目标元素自身状态：changed 是全页指纹，纯 CSS 展开 / 勾选切换 / 输入值变化
              // 都不改变它，_selfChanged 才能回答「我点的那个东西变了吗」。
              selfFp: targetFingerprint(el),
              selfRef: el.__asRef != null ? el.__asRef : null,
              // 指标用：label 重定向是否改写了目标 / 几何是否收敛
              retargeted: el !== resolved.el,
              settled: true,
              clickable: AS.isActionable(el),
              hitOk: local.ok,
              hitDesc: local.desc,
              ambiguous,
            },
          };
        }

        // clickAt 坐标解析：设备像素（截图）→ CSS px → 最近可交互元素中心（background 据此受信点击）
        case 'locatePoint': {
          if (params.x == null || params.y == null) {
            return { success: false, error: 'x/y required', errorCategory: 'not_found' };
          }
          const res = AS.locatePoint(params.x, params.y, params.frameId);
          if (!res.ok) {
            return { success: false, error: res.error || 'locatePoint failed', errorCategory: 'not_found' };
          }
          return { success: true, data: res };
        }

        // 写动作后置校验（background 在 CDP 派发 + settle 后调用）：回当前指纹与页面提示，
        // background 据此计算 changed/_hints/_clickable，让动作结果携带真实状态而非空断言。
        case 'verify': {
          const hints = pageHints()._hints;
          // 回读目标元素自身状态：动作后它多半已改变引用（可能已重渲染），
          // 所以按 token 缓存的元素优先，缓存失效才退回按 ref 查。
          let selfFp = null;
          if (params._reuseToken && lastResolved && lastResolved.token === params._reuseToken
              && lastResolved.el && lastResolved.el.isConnected) {
            selfFp = targetFingerprint(lastResolved.el);
          } else if (params.ref != null) {
            const el = AS.locateByRef(params.ref);
            if (el) selfFp = targetFingerprint(el);
          }
          return {
            success: true,
            data: {
              _url: location.href,
              _title: document.title,
              fp: domFingerprint(),
              selfFp,
              _hints: hints,
            },
          };
        }

        case 'scroll': {
          const dir = params.direction || 'down';
          if (params.selector || params.text || params.ref != null) {
            const el = await AS.waitForElement(params, params.waitMs || 3000);
            if (!el) return { success: false, error: 'Element not found', errorCategory: 'not_found' };
            try { el.scrollIntoView({ block: 'center', inline: 'center', behavior: 'instant' }); } catch (e) { /* ignore */ }
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
          await cursorVisitElement(el); // 纯 DOM 操作也要有光标落位，与 click/type 观感一致
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
          await cursorVisitElement(el); // 同 select：光标先落到 file input
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
            // 校验与遍历必须分开 catch：合在一起时，遍历阶段的任何 TypeError 都会被
            // 误报成 'Invalid selector'，把「依赖没加载」伪装成「选择器写错了」。
            try {
              document.querySelector(params.selector);
            } catch (e) {
              return { success: false, error: 'Invalid selector: ' + params.selector, errorCategory: 'not_found' };
            }
            try {
              nodes = AS.querySelectorAllCrossFrames(params.selector, params.scope, params.frameId);
            } catch (e) {
              return { success: false, error: 'selector traversal failed: ' + e.message, errorCategory: 'unknown' };
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
              document.querySelector(params.selector); // 只校验语法
            } catch (e) {
              return { success: false, error: 'Invalid selector: ' + params.selector, errorCategory: 'not_found' };
            }
            try {
              nodes = AS.querySelectorAllCrossFrames(params.selector, params.scope, params.frameId);
            } catch (e) {
              return { success: false, error: 'selector traversal failed: ' + e.message, errorCategory: 'unknown' };
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
            // 宽度护栏：命中过多说明 selector 过宽（如 [class*=x]）——提示收窄到 containers/scope，避免无决策价值的噪声读取
            const tooBroad = nodes.length > 100;
            return {
              success: true,
              data: {
                _url: location.href,
                total: nodes.length,
                tooBroad,
                warning: tooBroad
                  ? '命中过多(' + nodes.length + ')，selector 过宽：优先用 containers 探测 + 容器 scope 收敛，勿用宽泛选择器扫描'
                  : undefined,
                matches,
              },
            };
          }
          if (params.mode === 'container' || params.mode === 'containers') {
            let want = [];
            if (Array.isArray(params.selectors)) want = params.selectors.filter(Boolean);
            else if (typeof params.selector === 'string' && params.selector.trim()) want = params.selector.split(',').map(s => s.trim()).filter(Boolean);
            // 不做「AS.collectFrameDocs 不存在就退化成 [document]」的静默降级：那会让结果看起来
            // 正常却只覆盖顶层，容器全在 iframe 里时会得出「不存在」的错误结论 —— 比直接失败更难查。
            // execute() 开头的依赖守卫已确保 AS 存在。
            const docs = AS.collectFrameDocs(params.scope, params.frameId);
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
          // 一次"就绪+建图"：navigate 后一步完成帧图 + 容器探测 + 就绪判定。
          // 替代"wait 数次 → snapshot → containers"的多轮空探测；ready:false 时应只 wait 再 ready，不重扫。
          if (params.mode === 'ready') {
            const waitMs = Math.min(Number(params.waitMs) || 1200, 15000);
            if (waitMs > 0) await new Promise((r) => setTimeout(r, waitMs));
            const want = [];
            if (Array.isArray(params.selectors)) want.push(...params.selectors.filter(Boolean));
            else if (typeof params.selector === 'string' && params.selector.trim()) {
              want.push(...params.selector.split(',').map(s => s.trim()).filter(Boolean));
            }
            // 不做「AS.collectFrameDocs 不存在就退化成 [document]」的静默降级：那会让结果看起来
            // 正常却只覆盖顶层，容器全在 iframe 里时会得出「不存在」的错误结论 —— 比直接失败更难查。
            // execute() 开头的依赖守卫已确保 AS 存在。
            const docs = AS.collectFrameDocs(params.scope, params.frameId);
            const containers = [];
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
              containers.push({ selector: sel, present, count, frame, sample });
            }
            const missing = containers.filter((c) => !c.present).map((c) => c.selector);
            const hints = pageHints()._hints;
            return {
              success: true,
              data: {
                _url: location.href,
                _title: document.title,
                frames: collectFrames(),
                containers,
                ready: missing.length === 0,
                missing,
                _hints: hints,
              },
            };
          }
          if (params.mode === 'snapshot') {
            const max = params.max || 200;
            const els = AS.collectInteractables({ max, frameId: params.frameId });
            const items = els.map((el, i) => ({ ...AS.snapshotItem(el, i), frame: frameOf(el) }));
            // 首屏即暴露 frame 结构（壳页面 + iframe 内容区）：顶层 snapshot 看不到的控件要能直接定位归属
            const frames = collectFrames();
            return {
              success: true,
              data: {
                _url: location.href,
                _title: document.title,
                count: items.length,
                truncated: items.length >= max,
                domHash: domFingerprint(),
                frames,
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
  // 跨 frame 统计（顶层 + 各同源 iframe 的可交互元素数）：壳页面 + iframe 内容区的
  // 动作变化（如筛选/搜索区在 iframe 内刷新）才能被 changed 捕获到。
  function domFingerprint() {
    try {
      const INTERACTIVE = 'a,button,input,textarea,select,[role]';
      const parts = [];
      const topCount = document.querySelectorAll(INTERACTIVE).length;
      parts.push(location.href + ':0:' + topCount);
      for (const f of document.querySelectorAll('iframe')) {
        try {
          const w = f.contentWindow;
          if (!w || !w.document) continue;
          const frUrl = (w.location && w.location.href) || '';
          const cnt = w.document.querySelectorAll(INTERACTIVE).length;
          parts.push(frUrl + ':iframe:' + cnt);
        } catch (e) { /* 跨源 iframe 忽略 */ }
      }
      return location.href + '|' + document.title + '|' + parts.join(',');
    } catch (e) {
      return location.href;
    }
  }

  // 同源 iframe 结构清单（url + 可交互数 + 是否同源可读），供 snapshot 首屏识别"搜索区在哪个 frame"。
  function collectFrames() {
    const out = [];
    try {
      for (const f of document.querySelectorAll('iframe')) {
        let url = '';
        let count = 0;
        const sameOrigin = true;
        try {
          url = (f.contentWindow && f.contentWindow.location && f.contentWindow.location.href) || '';
          count = f.contentWindow.document
            ? f.contentWindow.document.querySelectorAll('a,button,input,textarea,select,[role]').length
            : 0;
        } catch (e) {
          continue; // 跨源 iframe：不列入（无法操作）
        }
        out.push({ frame: url, interactables: count, sameOrigin });
      }
    } catch (e) { /* ignore */ }
    return out;
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
