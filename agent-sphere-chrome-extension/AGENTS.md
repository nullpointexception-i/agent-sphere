# AgentSphere Chrome Extension

Manifest V3 Chrome extension that bridges the AgentSphere backend with the browser for automated operations. Backend `agent-sphere-common` holds the chrome-bridge DTOs (`ChromeCommandDTO`, `ChromeCallbackDTO`, `ChromePendingStore`).

## Current state (observable — verify if changed)

- `package.json` scripts `build`/`dev` call `node build.js`, but **`build.js` does not exist** — `npm run build` FAILS. `src/` is empty stubs. Edit root-level files directly and load unpacked via `chrome://extensions`.
- **No build step.** Background is native ES modules (`background.js` with `"type": "module"` imports from `lib/`). Do not introduce a bundler — the deploy workflow zips these files directly.

## Architecture

```
backend (SSE task stream ⇄ HTTP callback)
   ↕
offscreen.html/js — long-lived task SSE + command queue (immune to SW suspension)
   ↕ runtime message {target:'background', action:'task:command'}
background.js (ESM) — message router, executeInPage, tab grouping, callback POST
   ↕ tabs.sendMessage / chrome.scripting / chrome.debugger
content.js + content-locator.js + content-editors.js — primary execution layer (ISOLATED world)
   ↕ postMessage
inject.js (MAIN world, web_accessible) — eval bridge for executeJS tier 2a
page-script.js (MAIN world) — auth/session bridge (sessionStorage → content script)
```

Files:
- `background.js` — router; imports `lib/cdp-client.js`, `lib/tab-manager.js`, `lib/result.js`, `lib/offscreen-bridge.js`. **Keep it ESM**; the SW is `"type": "module"`.
- `lib/cdp-client.js` — single `chrome.debugger` wrapper: per-tab sessions, `onDetach` clears the tab's session (self-heal), `sendCommand` throws `Debugger is not attached…` when stale, all ops serialized through a promise queue, `evaluate` retries once on detach races. **Do not call `chrome.debugger` outside this file.**
- `lib/tab-manager.js` — controlled tab, content-script injection (3 files, ordered), `askContent` auto-re-injects on "Receiving end does not exist", and the **`AgentSphere` tab group** (aggregates every plugin tab; recreated if the group is closed).
- `lib/offscreen-bridge.js` — creates/pings the offscreen doc (alarm recreates it if the browser closes it).
- `offscreen.js` — holds `/api/v1/runtime/user/task/stream` SSE, zombie detection, reconnect, forwards `browser_operation` to background. **Offscreen documents support ONLY `chrome.runtime`** — no `chrome.storage`/`chrome.tabs` there. Token/baseUrl are fetched from background via `task:creds`, and connection status is reported via `task:status` (background writes `storage.local.taskConnected`). Do not call `chrome.storage` inside `offscreen.js`.
- `content-locator.js` / `content-editors.js` / `content.js` — injected together (in that order) into the isolated world; share the `window.__asContent` namespace.
- `inject.js` — MAIN-world eval bridge (postMessage round-trip), CSP-dependent.

## Key behaviors

- **No screenshots.** The screenshot pipeline was removed end-to-end (extension, backend `ChromeCallbackController`, UI PiP). Do not reintroduce `Page.captureScreenshot`/`captureVisibleTab` without an explicit requirement.
- **executeJS is tiered, debugger is last resort** (in `background.js#executeJsOnTab`):
  1. isolated world via `chrome.scripting` (never CSP-blocked, no page globals)
  2. MAIN world via `inject.js` postMessage bridge (Ui.Vision pattern)
  3. MAIN world via `chrome.scripting` `world:'MAIN'` (skipped once an origin proves CSP-strict — `cspBlockedOrigins`)
  4. `chrome.debugger` `Runtime.evaluate` (bypasses CSP; strict sites like 猎聘 land here)
  Keep that order. Do not make debugger the default path.
- **Debugger disconnects**: an external detach (opening DevTools on the controlled tab, cross-site navigation) drops the session. The `onDetach` listener + retry-once in `cdp-client.js` make the next command self-heal. It is still best to avoid opening DevTools on the controlled tab and to avoid cross-site navigation mid-task.
- **Tab grouping**: every tab the plugin navigates/opens is added to the `AgentSphere` group (`tabGroups` permission). Requires the `tabGroups` + `offscreen` permissions — keep them in `manifest.json`.
- **Results** carry `errorCategory` (`not_found`/`csp_blocked`/`detached`/`inject_failed`/`timeout`/`no_tab`) and `method` so the backend agent can pick a retry strategy instead of blind retries.
- Auth flow unchanged: content script reports `token` → background stores in `storage.local` → offscreen reads it (storage change triggers reconnect).

## Permissions (manifest.json)

`tabs`, `scripting`, `storage`, `activeTab`, `alarms`, `debugger`, `webNavigation`, `tabGroups`, `offscreen`; host `<all_urls>`. `page-script.js` + `inject.js` are web-accessible.

## No tests / linter / CI

Manually reload the unpacked extension and smoke-test before pushing:
- log in on a widget/main site → Task badge on
- `navigate` several tabs → all land in the `AgentSphere` group
- executeJS on a normal site (isolated/inject) and on a strict-CSP site (debugger fallback)
- cross-site navigation mid-task → next executeJS recovers
- `devtools` on the controlled tab → session self-heals on next command

## Git

GitHub flow: feature branch off `main` → PR.
