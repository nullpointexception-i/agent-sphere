# AgentSphere — monorepo

## 回答语言 (response language)

- 默认使用中文回答用户；只有用户明确要求时才使用其他语言。

## 第三方品牌 (no third-party brands in code)

- **禁止**在代码、注释、Javadoc、日志文案、文档、测试夹具里出现「被自动化/对接的具体
  第三方网站」的品牌名或域名（典型：招聘站点名、其主域与风控域）。目的是避免纠纷。
- 需要指代具体站点时改用中性写法：
  - 域名 → `example.com` / `host.example` 等占位；
  - 判定用 URL/路径特征 → 泛化成不含品牌的特征（如把 `safe.<brand>.com` 写成匹配
    `//safe.` 前缀 + 通用 `captcha`/`intercept` 片段），并保持原有检测语义；
  - 确需固定的真实站点信息 → **移到配置**（`agent_system_config` / popup 设置 / yml），
    代码里只留键名与默认值。
- 本规则是**全仓**约束，四个子项目都适用。
- **已应用的 Flyway 迁移不可改写**（只可新增迁移）。若历史 seed 里含品牌，新增一版
  迁移去改写，或按需从配置读取 —— 不要直接编辑 `V*.sql`。

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

### 本地 CI（.local-workflow）— 替代慢的 GitHub Action

`deploy.yml` 的唯一 job 要把镜像跨境推到阿里云 ACR，这是全流程最慢的一步。`.local-workflow/deploy.sh` 在本地复刻同一流程（ ACR 登录 → buildx 串行构建推送三镜像 → 改写 k8s tag 并 commit&push main → 自动递增版本号 → 打包扩展 → 建 GitHub Release ），缓存用 `type=local` 落在 `.local-workflow/.cache/`，**用 `--dry-run` 可先看计划（不改任何文件）**。

```bash
cp .local-workflow/.env.example .local-workflow/.env   # 填 ACR_USERNAME / ACR_PASSWORD
./.local-workflow/deploy.sh --dry-run                  # 预演
./.local-workflow/deploy.sh                            # 完整发版
./.local-workflow/deploy.sh --skip-existing            # 重跑：镜像已在 ACR 就跳过
./.local-workflow/deploy.sh --no-tag --only backend     # 只发镜像不发 Release
```

- 脚本入库，`.env` / `.cache/` / `output/` 被 gitignore；ACR 凭据在 `.env`，GitHub token 复用 `local-config/token.json`（或 `GITHUB_TOKEN` 环境变量）。
- 与 `deploy.yml` 的关键差异：要求当前分支 = `main` 且工作树干净；`ACR_REGISTRY` 未配置时从 `k8s/05-backend.yaml` 反推；git 走 HTTPS + `GIT_ASKPASS`（token 不进 argv/日志）；扩展打包在 `mktemp -d` 里改版本号（`deploy.yml` 直接改仓库内 `manifest.json` 且不还原）；版本号由脚本 patch+1 自动递增并保留 `-alpha` 后缀。tag 仍锚在改 k8s 之前的提交，与 `deploy.yml` 同序。
- 三个镜像**每次全部重建推送**（`--skip-existing` / `--only` 可裁剪）。buildx 必须是 `docker-container` driver 才支持 `cache-to type=local`，脚本会自动建 `as-builder`。arm64 宿主自动加 `--platform linux/amd64`（k3s 节点是 amd64）。
- 本地 CI **不含 lint / test / typecheck**，改代码仍按各项目约定自测（见下）。

### 中间件镜像镜像化 — `.local-workflow/mirror-middleware-images.sh`

线上 k3s 拉不到 Docker Hub，`k8s/02-postgres.yaml`（`postgres:16-alpine`）与 `k8s/03-redis.yaml`（`redis:7-alpine`）需要镜像到同一个 ACR。此脚本把本地镜像 tag+push 到 ACR、改写这两处 `image:`、再 commit & push main（`deploy.sh` 的提交清单硬编码了 `05/06/08`，不带这两个文件）。

```bash
./.local-workflow/mirror-middleware-images.sh --dry-run   # 预演（不 pull/push/改文件/提交）
./.local-workflow/mirror-middleware-images.sh             # 完整执行
```

- **架构是头号坑**：k3s 节点 amd64，arm64 宿主本地镜像是 arm64，推上去线上 `exec format error`。两道防线——推送前 `image inspect` 校验（不符则 `pull --platform linux/amd64` 重拉，拉不到即中止），推送后复核 ACR 内架构（不符则报错且**不改 k8s**）。复核不能靠 `imagetools inspect | grep amd64`：`Platform:` 行只有多架构 index 才有，普通 `tag`+`push` 的单 manifest 镜像输出只有 Name/MediaType/Digest，会**误报**；单 manifest 靠从 ACR 拉回后 `docker image inspect` 判定（顺带验证 ACR 拉得到）。拿不到证据只告警，拿到反证才中止。
- **只扫 `k8s/`**：`agent-sphere/agent-docker-middleware/docker-compose.yml` 引用同名官方镜像但那是本地开发用的，必须保持指向 Docker Hub。
- **不加 `imagePullPolicy`**（最小 diff）：节点默认 `IfNotPresent`，重推同 tag 不自动生效，需 `kubectl -n agent-sphere rollout restart deploy/postgres`。
- ACR 403 多半是命名空间 `nullpointexception-i` 下还没建 `postgres`/`redis` 镜像仓库（阿里云要求先在控制台创建）；脚本会把 403 翻译成可照做的提示。
- 详见 `.local-workflow/README.md`。

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
- **Widget sessions are gated by an instance switch.** `agent_instance.allow_widget_session` (default **false**) decides whether the widget may start/continue sessions on an instance; `allow_console_session` (default true) does the same for the main site. When widget is off, the widget disables the new-session button (greyed, wrapped in a `<span title>` because native tooltips don't fire on `disabled` elements) and replaces the composer with a `--{businessType}自动会话--` placeholder plus an "apply for a chatbot" hint. Gating is **per instance**, not per session — task sessions still appear in the list and their timeline stays viewable. The predicate lives in `src/instance.ts` (`allowsWidgetSession`, unit-tested) and only trusts a strict `true`, matching the column's `NOT NULL DEFAULT FALSE`. The widget sends `X-Client-Surface: widget` on every request so the backend can apply the same rule.
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
