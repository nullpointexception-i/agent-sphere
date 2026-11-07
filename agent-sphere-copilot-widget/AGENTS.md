# AgentSphere Copilot Widget

Embeddable chat widget: Vite library build (IIFE) + its own REST/SSE timeline. Independent npm project; run commands in this directory.

## Commands and artifacts

```bash
npm run dev        # :5173, /api proxied
npm run typecheck  # tsc --noEmit
npm test           # vitest run
npm run build      # tsc --noEmit && vite build && vite build --config vite.auth.config.ts
```

Build outputs:

- `dist/agent-sphere-widget.js`
- `dist/agent-sphere-auth.js` (built with `emptyOutDir:false`; do not remove that setting)

Verification: `npm run typecheck` + `npm test`. Current baseline: about 53 tests.

## Runtime shape

- `AgentSphereWidget.init({apiBase, provider, autoLogin, title})` mounts into a **shadow DOM**.
- CSS is injected as a Vite `?inline` import.
- OIDC: `?otc=` → `POST /auth/sso/exchange` → token stored at `sessionStorage['agent-sphere-widget:agent-user']`.
- `autoLogin` performs a one-shot `prompt=none` silent probe.

## Timeline and streaming

- Timeline: `GET /instance/sessions/{sid}/timeline` with `beforeSeq` / `afterSeq`, limit 5–50.
- Stream: SSE `/runtime/{sid}/stream`.
- `run_*`, `tool_call_*`, `clarification_*` events trigger an `afterSeq` refresh for authoritative merging.
- `mergeTimeline` deduplicates by `seq`; sub-agent placeholder rows use `seq < 0`.
- Send: `POST /runtime/{sid}/chat` → `{runId}`.
- Stop: `POST /runtime/{sid}/stop`.
- Clarify: `POST /runtime/{sid}/run/{runId}/clarify`.
- `useTimelineStream.connect(sessionId, token)` replaces stream state; run it in an effect keyed by the selected session id.

## Session switches

- `agent_instance.allow_widget_session` defaults to **false**; `allow_console_session` defaults to true.
- Every widget request sends `X-Client-Surface: widget`.
- Frontend `src/instance.ts#allowsWidgetSession` accepts only strict `true`.
- Server `SessionSurfacePolicy` is the strong constraint; frontend checks are UX only.

## Constants and magic values

- Shared values live in `src/constants.ts`: `TIMING`, `RUN_STATE`, `TOOL_STATE`, `TODO_STATUS`.
- Do not inline status strings or timing numbers in components/stream code; use the constants.

## Environment pitfalls

- Never copy `node_modules` or `package-lock.json` across machines/OSes: rollup platform optional dependencies cause `Cannot find module @rollup/rollup-*`.
- Local reinstall: `rm -rf node_modules package-lock.json && npm i`.

## Hard rules

- No third-party site brand/domain in code, comments, tests, or UI copy.
- Keep `/api` and `/stream` proxy behavior aligned with the admin UI; do not remove SSE headers.
