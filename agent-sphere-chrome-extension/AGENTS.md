# AgentSphere Chrome Extension

Manifest V3 extension bridging the AgentSphere backend with the browser. Backend DTOs live in `agent-sphere-common` (`ChromeCommandDTO`, `ChromeCallbackDTO`, `ChromePendingStore`).

## No build step

- `package.json` scripts point to a missing `build.js`; **`npm run build` fails**.
- `src/` is empty stubs. Edit root-level files directly and reload the unpacked extension from `chrome://extensions`.
- Background is native ESM (`background.js`, `"type": "module"`). Do not introduce a bundler; deploy zips these files directly.

## Architecture

```text
backend (SSE task stream ⇄ HTTP callback)
   ↕
offscreen.html/js — long-lived task SSE + command queue
   ↕ runtime message {target:'background', action:'task:command'}
background.js — router, executeInPage, tab management, callback POST
   ↕ tabs.sendMessage / chrome.scripting / chrome.debugger
content.js + content-locator.js — isolated-world execution
page-script.js — MAIN-world auth/session bridge
```

- `background.js` — ESM router; imports `lib/cdp-client.js`, `lib/tab-manager.js`, `lib/result.js`, `lib/offscreen-bridge.js`.
- `lib/cdp-client.js` — the **only** file allowed to call `chrome.debugger`. Per-tab sessions, `onDetach` clears state, `sendCommand` throws when stale, all operations serialized with per-op deadlines.
- `lib/tab-manager.js` — controlled tab, isolated AgentSphere window, ordered content-script injection, `AgentSphere` tab group, per-session tab registry.
- `lib/offscreen-bridge.js` / `offscreen.js` — long-lived SSE and command queue. Offscreen documents support only `chrome.runtime`; never use `chrome.storage` or `chrome.tabs` there.
- `content-locator.js` / `content.js` — injected together, share `window.__asContent`; write actions use CDP trusted input from `background.js`.
- `page-script.js` — MAIN-world script intercepting `window.open`.
- `lib/agent-cursor.js` — pointer animation with arrival barrier before CDP click.

## Key behaviors

- **Cursor**: content script mutates the DOM directly from the isolated world; no MAIN-world postMessage bridge. Pointer stays visible after landing; only the trail fades.
- **Locator determinism**: snapshot refs follow DOM order; write actions reuse the resolved element via `_reuseToken`, never re-resolve.
- **Failure taxonomy** (`lib/result.js`): `not_found` / `not_interactable` / `occluded` / `ambiguous`; keep them distinct.
- **Fail-fast loading**: missing/broken `content-locator.js` must surface as `inject_failed`, not scattered `TypeError`s.
- **Screenshots**: `lib/cdp-client.js#captureScreenshot` only. Base64 POSTs to `/api/v1/browser/screenshot`; callback carries `fileKey` + dimensions. `clickAt` uses the last viewport screenshot coordinates. Full-page screenshots are observation-only.
- **executeJS is two-tier**: MAIN world via `chrome.scripting` first; `chrome.debugger` `Runtime.evaluate` only as strict-CSP fallback. There is **no isolated-world tier** (MV3 CSP forbids eval there). Keep that order.
- **executeJS results** carry `method`, `resultType`, and optional `warning`.
- **Timeout layering**: extension `EXECJS_TIMEOUT_MS` (27s) < backend `EXECUTEJS_TIMEOUT_SECONDS` (30s) < `COMMAND_LEASE_MS` (60s).
- **Tab grouping**: all agent tabs live in a dedicated agent window/group; user tabs are never reused or navigated. `tabGroups` + `offscreen` permissions are required.
- **Session-scoped tabs**: `findReusableTab()` only considers tabs registered to the current session; never fall back to any tab in the agent window.
- **navigate must verify the real page**: read `location.href` via `agent_sphere_ping`, not `chrome.tabs.url` (stale during redirects).
- **Results** include `errorCategory` (`not_found`/`csp_blocked`/`detached`/`inject_failed`/`timeout`/`no_tab`) and `method`.
- **Auth**: content script reports token → background stores in `storage.local` → offscreen reads it; storage change triggers reconnect.

## Permissions (manifest.json)

`tabs`, `scripting`, `storage`, `activeTab`, `alarms`, `debugger`, `webNavigation`, `tabGroups`, `offscreen`; host `<all_urls>`. `page-script.js` is web-accessible.

## Syntax checks

No tests/linter/CI. Injected scripts are classic scripts; `node --check` may accept top-level `return` that Chrome rejects. Use `vm.Script`:

```bash
node -e "const vm=require('vm'),fs=require('fs'); for (const f of ['content.js','content-locator.js','page-script.js']) new vm.Script(fs.readFileSync(f,'utf8'),{filename:f});"
```

- Top-level `return`/`await` is illegal in these files; put idempotency guards inside the IIFE.

## Manual smoke test

- Log in on a widget/main site → Task badge on.
- `navigate`: separate unfocused agent window opens; tabs land in the `AgentSphere` group; user window stays active.
- `navigate` twice with the same URL: reuse only inside the agent window.
- executeJS on a normal site (scripting) and a strict-CSP site (debugger).
- Cross-site navigation mid-task → next executeJS recovers.
- DevTools on the controlled tab → session self-heals on next command.
- Every `getContent` mode returns data; all failing at once suggests `content-locator.js` failed to load.
- Popup Metrics tab: enable metrics, run a task, counters rise.

## Local metrics (no backend)

- `lib/browser-metrics.js` is client-side only; off by default (`settings.browserMetrics`).
- `browserMetrics` holds counters/latency; `browserMetricsRecent` holds a 300-record ring buffer; keep them separate so popup re-render is cheap.
- Counters are monotonic; never trim them.
- `selfChangedUnknown` is excluded from both sides of the mis-click rate.
- `misclickSuspect = changed === true && selfChanged === false`; `audit_verdict` is ground truth.
- `popup.js#snapshotOf` duplicates `buildSnapshot()` arithmetic; change both together.

## Popup rendering

- Escape every tool/user-derived string with `esc()` before `innerHTML`. Tool errors contain markup (e.g. `<div#modal>`).
- Use one delegated listener per container; inline `onclick=` is illegal under MV3 CSP.
- Log rows use CSS ellipsis only; full text comes from `data-line` and `title`.

## No third-party brands

Never include a specific third-party site brand/domain in code, comments, logs, tests, or docs. `RISK_URL_PATTERNS` must stay generic (`//safe.` prefix + `captcha`/`intercept` fragments). Move real site data to config; never edit applied Flyway migrations.

## Release

`deploy.yml` zips an explicit root-level file list plus the whole `lib/`. **Add every new root-level JS file to that list.** Missing `inject.js` / `content-editors.js` entries are historical; do not recreate them.

## Git

GitHub flow: branch off `main` → PR.
