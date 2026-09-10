# AgentSphere — monorepo

Git root holds four **independent** projects (no shared root build/lockfile):
- `agent-sphere/` — Java 21 / Spring Boot backend. **See `agent-sphere/AGENTS.md`** for module layout, Maven commands, Flyway, MyBatis-Plus, and code-style rules.
- `agent-sphere-ui/` — React 19 / UmiJS Max frontend (Ant Design Pro base).
- `agent-sphere-copilot-widget/` — embeddable chat widget (Vite lib IIFE + self-built REST/SSE timeline chat), see below.
- `agent-sphere-chrome-extension/` — Manifest V3 Chrome extension (bridges backend with the browser for automated ops). **See `agent-sphere-chrome-extension/AGENTS.md`**. No build step: background is native ESM (`background.js` + `lib/*.js`), execution layer is `content.js`/`content-locator.js`, SSE lives in `offscreen.js`, MAIN-world bridges are `page-script.js`. `src/` is empty stubs and `npm run build` has no `build.js` — edit files directly and load unpacked. Plugin supports vision operation: `screenshot` (CDP capture, viewport + full-page) + `clickAt(x,y)` on viewport screenshot device pixels; plugin tabs auto-group under `AgentSphere`; `chrome.debugger` is confined to `lib/cdp-client.js`. Keep `offscreen.js` params whitelist in sync with `ChromeCommandDTO` when adding command fields.

GitHub flow off `main`. **No test CI** — run each project's tests before pushing. The only workflow is a deploy pipeline (see Deploy below).

## How the two apps connect

- Backend serves API at `http://localhost:8080` with prefix `/api/v1/...`; SSE routes contain `/stream`.
- UI `npm run dev` (port 8000) proxies `/api/` → `http://localhost:8080` (see `agent-sphere-ui/config/proxy.ts`). For `/stream` requests the proxy **strips `Accept-Encoding`** and sets `Cache-Control: no-transform` — preserve this logic, SSE breaks without it.
- Typical local flow: start backend (`mvn -pl agent-sphere-bootstrap spring-boot:run -am`, needs Postgres+Redis), then UI (`cd agent-sphere-ui && npm run dev`).

## Infra / runtime prerequisites

- Postgres + Redis via `agent-sphere/agent-docker-middleware/docker-compose.yml` (note: under `agent-sphere/`, **not** the repo root). Volume paths are hardcoded to macOS (`/Users/elvin/Desktop/...`) — override or remove on other machines.
- Postgres DB name is `buukle_agent_2026061101` (set in `application.yml` and the compose file). Env overrides: `DB_HOST`, `DB_PORT`, `DB_USERNAME`, `DB_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`.
- Backend JVM/Jackson timezone `Asia/Shanghai`; virtual threads on by default.

## Deploy (k3s / GitOps)

- `.github/workflows/deploy.yml` is the **only** workflow. Triggers on `v*` tags (or manual `workflow_dispatch`): builds & pushes backend/frontend/widget images to Aliyun ACR, rewrites image tags in `k8s/05-backend.yaml`, `06-frontend.yaml`, `08-widget.yaml`, commits them to `main` (Fleet applies them on k3s), and attaches the chrome-extension zip (packaged from the root-level extension files) to a GitHub Release. No test job — verify locally before tagging.
- `k8s/` holds the namespace + postgres/redis/backend/frontend/widget/ingress manifests; `04-backend-config.yaml` sets backend env (DB, Redis, JWT, etc.).
- Frontend image build takes `build-args: COMMIT_HASH` — referenced in the UI's version display.

### Tagging & pushing (deploy trigger)

- Tag convention: `v1.0.NN-alpha` (next after the latest remote tag). Create annotated tag on `main` HEAD: `git tag -a v1.0.NN-alpha -m "v1.0.NN-alpha"`.

- **This environment has no SSH key / gh CLI** — the `origin` remote (`git@github.com:...`) will fail with `Permission denied (publickey)`. Push tags (and any auth-required git op) over HTTPS using the GitHub token in `local-config/token.json` (`/local-config` is gitignored, never commit it):

  ```bash
  TOKEN=$(node -p "require('./local-config/token.json').github.token")
  git push "https://nullpointexception-i:${TOKEN}@github.com/nullpointexception-i/agent-sphere.git" v1.0.NN-alpha
  ```

- Pushing the `v*` tag triggers the deploy workflow (see above); verify the run at the repo's Actions tab.

## agent-sphere-copilot-widget (chat widget)

Independent package — install/build only from inside `agent-sphere-copilot-widget/`. Stack: Vite 6 (lib mode, IIFE `AgentSphereWidget`), React 19, TypeScript (strict). **No CopilotKit / AG-UI** — chat renders a typed REST+SSE timeline (same shape as the main UI `chat` page).

Commands (run inside `agent-sphere-copilot-widget/`):
```bash
npm run dev        # vite dev on :5173, proxies /api -> http://localhost:8080
npm run build      # tsc --noEmit && vite build (lib IIFE -> dist/agent-sphere-widget.js)
npm run typecheck  # tsc --noEmit
```

How it works:
- `AgentSphereWidget.init({ apiBase?, provider?, autoLogin?, title? })` mounts into a **shadow DOM** root; CSS inlined via Vite `?inline` (`src/styles.css`).
- OIDC SSO: consumes `?otc=` → `POST /auth/sso/exchange` → token in `sessionStorage` (`agent-sphere-widget:agent-user`); `autoLogin` does a one-shot silent probe (`prompt=none`); `?otc=`/`?error=` stripped after handling.
- **Timeline channel** (`useTimelineStream`): `GET {apiBase}/instance/sessions/{sid}/timeline` (paged by `beforeSeq`/`afterSeq`, limit 5–50) + SSE `{apiBase}/runtime/{sid}/stream` (Bearer). SSE drives an `assistant` typewriter (push `content_token`/`reasoning_token` onto `seq+kind==='assistant'` rows) and sub-agent live aggregation; `run_completed/failed/cancelled/awaiting_user`, `tool_call_*`, `clarification_*` trigger an `afterSeq` refresh for authoritative merge (`mergeTimeline` dedupes by `seq`, placeholder sub-agent rows carry `seq<0`).
- Send: `POST {apiBase}/runtime/{sid}/chat` → `{runId}`; clarify: `POST /runtime/{sid}/run/{runId}/clarify`.
- Chat input is self-built (textarea + send/stop); stop calls `POST /runtime/{sid}/stop`. `WidgetTimeline` renders user/assistant/tool/clarification/subagent/run_status rows with copy buttons, RUNNING wobble dot, inline sub-agent cards (tool detail single-open, auto-scroll), inline clarification options.
- Vite proxy strips `Accept-Encoding` for `/stream` routes and sets `Cache-Control: no-transform` — SSE breaks without this. Preserve it.

Gotchas:
- **Never copy `node_modules` / `package-lock.json` across machines.** rollup 4 declares platform binaries as optionalDependencies (`@rollup/rollup-darwin-x64`, `-linux-x64-gnu`, …). Reusing a `node_modules`/lockfile generated on another OS/arch (e.g. Linux container) on macOS makes npm skip the current platform's binary → `npm run dev` fails with `Cannot find module @rollup/rollup-darwin-x64` (npm/cli#4828). Fix on the target machine: `rm -rf node_modules package-lock.json && npm i`. `package-lock.json` is gitignored — keep it machine-local.
- Timeline `rows` on the SSE wire: backend `AgentTimelineVO.content` is a resolve `String`-keyed object (not part arrays) — don't switch unless the backend DTO is updated. `sendMessage` returns `{runId,status}` synchronously; the rest arrives via SSE + `afterSeq` pulls.
- `useTimelineStream.connect(sessionId, token)` replaces state on session switch — call it in an effect keyed on the selected session id.

## Backend conventions (apply when touching `agent-sphere/`)

### 数据库表通用字段 (common columns)

Every new table adds the shared status/audit columns, aligned with the `sys_*` tables (reference entities: `SysRole`, `IdentityProvider`, `SsoIdentity`):

```sql
status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',   -- sys_* default
remark      VARCHAR(500) NULL,
delete_flag -- logical delete, never hard-delete
created_by  VARCHAR(100) NULL,
updated_by  VARCHAR(100) NULL,
created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
updated_at  TIMESTAMP    NOT NULL DEFAULT NOW()
```

Entity mapping (MyBatis-Plus `AuditMetaObjectHandler` fills these automatically):

```java
private String status;
private String remark;
@TableField(fill = FieldFill.INSERT)        private String createdBy;
@TableField(fill = FieldFill.INSERT_UPDATE) private String updatedBy;
@TableField(fill = FieldFill.INSERT)        private LocalDateTime createdAt;
@TableField(fill = FieldFill.INSERT_UPDATE) private LocalDateTime updatedAt;
// when the table has delete_flag:
@TableLogic private Boolean deleteFlag;
```

### 魔法值禁用 (no magic values)

Never inline raw strings/numbers in business logic. Reuse existing constants/enums by scope before declaring new ones: method-local → `private static final` on the class → module-wide constants class / `enum` (e.g. `SsoConstants`, `SsoProviderType`, `AguiEventType`, `AguiConstants`).

## agent-sphere-ui (frontend)

Stack: UmiJS Max, React 19, antd 6, TypeScript (strict), **Biome** for lint+format, **Tailwind v4** (via `@tailwindcss/postcss`), Jest. Node `>=20`. `.npmrc` sets `legacy-peer-deps=true` — required for install to resolve.

Commands (run inside `agent-sphere-ui/`):
```bash
npm run dev          # dev server on :8000, MOCK=none, proxies /api -> backend:8080
npm run build        # max build (hash + manifest + exportStatic)
npm run biome        # biome check --write  (auto-fix; run this first)
npm run lint         # biome lint && tsc --noEmit  (checks only, no write)
npm run tsc          # typecheck only (tsc --noEmit)
npm test             # jest
```
Verify order: `npm run biome` → `npm run lint` → `npm test`.

Conventions / gotchas:
- **Routing is config-based** in `config/routes.ts`, not file-based. Page dirs mirror backend domains: `dashboard`, `chat`, `instances`, `model-providers`, `capabilities/{mcp,skill,cli,builtin}`, `account`, `user`.
- `src/.umi/**` is **generated** by `max dev` / `max setup` (runs in `prepare`) — never hand-edit. Path alias `@@/*` resolves there.
- Path aliases: `@/*`→`src/*`, `@@/*`→`src/.umi/*`, `@root/*`→repo root.
- Biome **ignores** `src/services` and `mock` (see `biome.json`). The hand-written API client `src/services/agentSphere/api.ts` (uses `BASE = '/api/v1'`) is therefore **not linted** — keep it tidy manually.
- Biome style: single quotes, space indent, `jsxRuntime: reactClassic`. `noExplicitAny` and `useExhaustiveDependencies` are off.
- Default locale `zh-CN`; `moment2dayjs` rewrites moment→dayjs.
