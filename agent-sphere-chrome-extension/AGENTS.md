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
content.js + content-locator.js — primary execution layer (ISOLATED world)
page-script.js (MAIN world) — auth/session bridge (sessionStorage → content script)
```

Files:
- `background.js` — router; imports `lib/cdp-client.js`, `lib/tab-manager.js`, `lib/result.js`, `lib/offscreen-bridge.js`. **Keep it ESM**; the SW is `"type": "module"`.
- `lib/cdp-client.js` — single `chrome.debugger` wrapper: per-tab sessions, `onDetach` clears the tab's session (self-heal), `sendCommand` throws `Debugger is not attached…` when stale, all ops serialized through a promise queue, `evaluate` retries once on detach races. **Do not call `chrome.debugger` outside this file.**
- `lib/tab-manager.js` — controlled tab, **isolated AgentSphere window**, content-script injection (3 files, ordered), `askContent` auto-re-injects on "Receiving end does not exist", and the **`AgentSphere` tab group** (all plugin tabs live in the dedicated window; recreated if the window/group is closed).
- `lib/offscreen-bridge.js` — creates/pings the offscreen doc (alarm recreates it if the browser closes it).
- `offscreen.js` — holds `/api/v1/runtime/user/task/stream` SSE, zombie detection, reconnect, forwards `browser_operation` to background. **Offscreen documents support ONLY `chrome.runtime`** — no `chrome.storage`/`chrome.tabs` there. Token/baseUrl are fetched from background via `task:creds`, and connection status is reported via `task:status` (background writes `storage.local.taskConnected`). Do not call `chrome.storage` inside `offscreen.js`.
- `content-locator.js` / `content.js` — injected together (in that order) into the isolated world; share the `window.__asContent` namespace. Write actions (click/type/key/hover) run via CDP trusted input in `background.js`; content script only reads/wait/scroll/uploads.
- `inject.js` — **deleted.** There is no inject-bridge tier anymore.
- `page-script.js` — MAIN-world script (web_accessible) intercepting `window.open`.
- `lib/agent-cursor.js` — agent pointer: sends `AGENT_CURSOR_STATE`, then **waits for the arrival barrier** (`notifyCursorArrived`, 1.5s cap) before the CDP click is dispatched, so the pointer visibly lands before the action fires.

## Key behaviors

- **Cursor protocol**: background → content `{type:'AGENT_CURSOR_STATE', x, y, animateMovement, moveSequence}`; content → background `{type:'notifyCursorArrived', moveSequence}`. Rendering lives in `content.js`, which mutates the page DOM **directly from the ISOLATED world** — the same mechanism `highlightElement()` uses. There is deliberately **no MAIN-world injection and no postMessage bridge**: content scripts share the DOM, and an extra world hop only added ways for the cursor to silently not appear. The pointer stays visible after landing (it is not auto-hidden); only the trail fades.
- **Locator determinism**: snapshot refs are assigned by DOM order (`collectInteractables`), so they never shift between observe and act. `writeAction` relies on this: its three-alignment fallback passes a shared `_reuseToken`, and the content script reuses the already-resolved element object instead of re-resolving — cheaper *and* immune to the re-lookup drift a selector-based design would suffer.
- **Locator failure taxonomy** (`lib/result.js`): `not_found` (target absent) / `not_interactable` (present but disabled/hidden/unsettled — **never retried, other alignments can't help**) / `occluded` (pointer-events intercepted, names the interceptor) / `ambiguous` (multiple matches). These imply different recovery moves, so keep them distinct.
- **Locator loading is fail-fast**: `execute()` guards on `window.__asContent` before touching any `AS.*`. A missing/broken `content-locator.js` must surface as one `inject_failed` message, never as scattered `TypeError`s. Modes must not silently degrade either — `containers`/`ready` used to fall back to `[document]` when `AS.collectFrameDocs` was absent, which produced plausible-looking but top-frame-only results (a "container not found" conclusion that was simply wrong).

- **Screenshots (vision operation)**: `screenshot` action (viewport via `Page.captureScreenshot`, `scope=full` uses `captureBeyondViewport`) lives **only** in `lib/cdp-client.js#captureScreenshot` — the one place allowed to call `chrome.debugger`. The base64 is POSTed to `POST /api/v1/browser/screenshot` (Bearer) and the callback carries only the `fileKey` + dimensions (no large base64 over the callback/Redis). `clickAt(x, y)` clicks at the device pixels of the **last viewport screenshot** (converted to CSS px via `devicePixelRatio`); non-interactive points click through with a `warning`. Snapshot items carry `bounds` (top-frame CSS px rect) for screenshot↔ref alignment. Full-page screenshots are observation-only — no coordinate clicking.
- **executeJS is two-tier, debugger is the strict-CSP fallback** (in `background.js#executeJsOnTab`):
  1. MAIN world via `chrome.scripting` (`world:'MAIN'`) — the page CSP applies, so this works on most sites and fails fast on strict-CSP origins (cached in `cspBlockedOrigins`).
  2. `chrome.debugger` `Runtime.evaluate` (bypasses CSP; strict sites  land here).
  **There is NO isolated-world tier**: MV3's extension CSP (`script-src 'self' 'wasm-unsafe-eval' …`) forbids `eval()`/`new Function()` in content-script isolated worlds, so an isolated eval can never work — do not reintroduce it.
  Keep that order. Do not make debugger the default path.
- **executeJS result transparency**: results always carry `method` (`scripting-main`/`debugger`), `resultType`, and — when execution may have been blocked or the value was unserializable — a `warning` field. The backend surfaces these (`ChromeResultVO.warning/resultType`) so the agent stops retrying a blocked executeJS.
- **Content-script capabilities**: `click`/`type`/`hover` accept `index` (1-based, selector) and `occurrence` (1-based, text) to pick the Nth of identical elements (`content-locator.js`). `closeDialogs` closes every open `[role="dialog"]`/toast in one call. `getContent(mode: query, selector)` returns a compact up-to-50-element match list instead of the full DOM. `getContent` full mode is capped at 2000 DOM nodes.
- **Debugger disconnects**: an external detach (opening DevTools on the controlled tab, cross-site navigation) drops the session. The `onDetach` listener + retry-once in `cdp-client.js` make the next command self-heal. It is still best to avoid opening DevTools on the controlled tab and to avoid cross-site navigation mid-task.
- **CDP queue deadlines**: every operation queued through `cdp-client._serialized` carries its own deadline (`CDP_OP_TIMEOUT_MS`, 15s). Without it a single hung page command (e.g. `executeJS('while(true){}')`) would leave `_queue` pending forever and **every later CDP operation would stop executing until the service worker restarted**. A timeout cannot cancel the in-flight CDP command (CDP has no cancel semantics) — its only job is to keep the queue flowing.
- **Timeout layering**: the extension's `EXECJS_TIMEOUT_MS` (27s) is deliberately below the backend's `EXECUTEJS_TIMEOUT_SECONDS` (30s), and `COMMAND_LEASE_MS` (60s) is above it. Equal executeJS timeouts race: the backend gives up first, releases the pending future and the lease is still held, so the late callback is dropped and the next command is rejected by the concurrency guard.
- **Tab grouping / isolated window**: every tab the plugin navigates/opens is created in a dedicated **normal, unfocused window** and added to the `AgentSphere` group (`tabGroups` permission). The window is created with `focused:false`; `tabs.update({active:true})` only activates inside that window, so the user's window/tab is never taken over. User tabs are never reused or navigated. Requires the `tabGroups` + `offscreen` permissions — keep them in `manifest.json`.
- **Results** carry `errorCategory` (`not_found`/`csp_blocked`/`detached`/`inject_failed`/`timeout`/`no_tab`) and `method` so the backend agent can pick a retry strategy instead of blind retries.
- Auth flow unchanged: content script reports `token` → background stores in `storage.local` → offscreen reads it (storage change triggers reconnect).

## Permissions (manifest.json)

`tabs`, `scripting`, `storage`, `activeTab`, `alarms`, `debugger`, `webNavigation`, `tabGroups`, `offscreen`; host `<all_urls>`. `page-script.js` is web-accessible.

## No tests / linter / CI

### Syntax check injected content scripts with `vm.Script`, NOT `node --check`

`content.js` / `content-locator.js` / `page-script.js` are injected via
`chrome.scripting.executeScript({ files })`, which loads them as **classic scripts**.
`node --check` parses them inside Node's CommonJS wrapper, where a **top-level `return` is
legal** — so it silently passes a file that Chrome would reject with
`SyntaxError: Illegal return statement`. That failure mode is catastrophic: the whole file
never executes, `window.__asContent` is never created, and *every* locator call in
`content.js` throws a differently-worded `TypeError` (see "Locator failure taxonomy").

```bash
node -e "const vm=require('vm'),fs=require('fs');
for (const f of ['content.js','content-locator.js','page-script.js'])
  new vm.Script(fs.readFileSync(f,'utf8'),{filename:f});"
```

**Rule: top-level control flow (`return`, `await`, …) is illegal in these files.** Any
idempotency guard must live *inside* the IIFE, like the existing ones in `content.js`
(opens at line 6) and `content-locator.js`.

### Manual smoke test

Reload the unpacked extension before pushing:
- log in on a widget/main site → Task badge on
- run `navigate`: a separate normal window opens (unfocused), all agent tabs land in its `AgentSphere` group, and the user's current window/tab stays active
- `navigate` twice with the same URL: the tab is reused only inside the agent window; a same-URL user tab is never reused
- executeJS on a normal site (MAIN-world scripting) and on a strict-CSP site (debugger fallback)
- cross-site navigation mid-task → next executeJS recovers
- `devtools` on the controlled tab → session self-heals on next command
- **every `getContent` mode** (`snapshot` / `summary` / `query` / `containers` / `ready` / `extract`) returns data — if all of them fail at once, suspect `content-locator.js` failed to load rather than a per-mode bug
- popup **Metrics** tab: toggle `Collect browser metrics` in Settings, run a task, and confirm counters rise in the panel

## Local metrics (no backend)

`lib/browser-metrics.js` measures click/type precision **entirely client-side** — no DB,
no callback field, no wire-protocol change. Off by default (`settings.browserMetrics`).

- Two storage keys, split by weight on purpose: `browserMetrics` (counters + latency
  samples, light) and `browserMetricsRecent` (300-record ring buffer, ~55KB). popup.js
  listens to `storage.onChanged` for ANY key, so keeping the buffer in its own key stops
  automation writes from re-rendering the whole popup. The Metrics tab pulls both itself
  and **only while it is the active tab** (`activeTab` + 250ms debounce).
- Counters are **monotonic** (rates stay valid forever); the ring buffer is only for
  "what just happened" + manual audit. Never trim counters to bound size.
- `selfChanged === undefined` (no stable ref → no target fingerprint) goes to
  `selfChangedUnknown` and is excluded from **both** sides of the mis-click rate.
  Folding it in would silently make the rate look better than it is.
- `misclickSuspect` = `changed === true && selfChanged === false` — the page reacted but
  the target itself did not. Strongest automatic mis-click proxy; still a proxy.
  `audit_verdict` on a record is the only ground truth, set by hand from the popup.
- The popup duplicates `buildSnapshot()`'s arithmetic locally (`snapshotOf` in popup.js)
  because it cannot import an ESM module. **Change one, change both.**

## popup rendering: escape before `innerHTML`

`popup.js` builds most panels by string-concatenating into `innerHTML`, so **every
tool/user-derived string must go through `esc()`**. Tool errors are not inert text —
`occluded` messages literally contain markup (`"<div#modal> intercepts the click"`), so an
unescaped `${log.detail}` makes the browser parse `<div#modal>` as a tag: the leading
token vanishes (looks like the message is "incomplete") and the stray element swallows the
rest of the row's spans. The Logs list was the one sink that missed `esc()` while Metrics
already had it — keep it that way for any new sink.

Because rows are re-rendered wholesale, per-row listeners are a leak/footgun: attach ONE
delegated listener on the container (see `mList` and `logContainer`). Inline `onclick=` is
also illegal under MV3's CSP. Log rows keep a clipped single line via CSS `text-overflow:
ellipsis` — that is **visual only**, so the full text is recovered from `data-line`
(click-to-copy) and the `title` attribute (hover); no extra state is needed.

## no third-party brands

Never put the brand or domain of a **specific third-party site we automate** into code,
comments, log text, tests or docs (see the root `AGENTS.md` rule). This is why
`RISK_URL_PATTERNS` matches a generic `//safe.` host prefix plus `captcha` / `intercept`
path fragments instead of a brand domain, and why test fixtures use `host.example` /
`example`. If a brand-looking value is genuinely needed, move it to config (popup setting /
`agent_system_config` / yml) and keep only the key + default in code. Do **not** edit applied
Flyway migrations to scrub seeds — add a new migration.

## Git

GitHub flow: feature branch off `main` → PR.
