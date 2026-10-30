# AgentSphere — monorepo

## 回答语言 (response language)

- 默认用中文回答用户；只有用户明确要求时才使用其他语言。

## 第三方品牌 (no third-party brands in code)

- **禁止**在代码、注释、Javadoc、日志文案、文档、测试夹具里出现「被自动化/对接的具体
  第三方网站」的品牌名或域名（典型：招聘站点名、其主域与风控域），以避免纠纷。
- 需要指代具体站点时改用中性写法：
  - 域名 → `example.com` / `host.example` 等占位；
  - 判定用 URL/路径特征 → 泛化成不含品牌的特征（如匹配 `//safe.` 前缀 + 通用
    `captcha`/`intercept` 片段），并保持原有检测语义；
  - 确需固定的真实站点信息 → **移到配置**（`agent_system_config` / popup 设置 / yml），
    代码里只留键名与默认值。
- 全仓约束，四个子项目都适用。
- **已应用的 Flyway 迁移不可改写**（只可新增迁移）。历史 seed 含品牌时新增一版迁移改写，
  不要直接编辑 `V*.sql`。

## 四个独立项目（无根构建、无根 lockfile）

- `agent-sphere/` — Java 21 / Spring Boot 3.4.3 后端（Maven 多模块）。**动后端前先读
  `agent-sphere/AGENTS.md`**：模块分层、Maven 命令、Flyway、MyBatis-Plus、Redis/Redisson、
  run 状态机、上下文压缩、内置工具约定。
- `agent-sphere-ui/` — React 19 / UmiJS Max 管理端（Ant Design Pro 基座）。
- `agent-sphere-copilot-widget/` — 可嵌入聊天 widget（Vite lib IIFE + 自建 REST/SSE timeline）。
- `agent-sphere-chrome-extension/` — MV3 扩展，**见 `agent-sphere-chrome-extension/AGENTS.md`**。
- 其余：`k8s/` + `.github/workflows/deploy.yml` + `.local-workflow/` 是部署链路；
  `agent-sphere-readme/` 只放文档图片；`examples/parallel-search/mcp.json` 是 MCP 配置示例。

## 本地起服务

```bash
# 后端 :8080（需 Postgres + Redis；先 install 一次，之后 -am 可省）
mvn install -DskipTests
mvn -pl agent-sphere-bootstrap spring-boot:run -am

# 管理端 :8000（MOCK=none，/api/ 代理到 :8080）
cd agent-sphere-ui && npm run dev

# widget :5173（同样代理 /api）
cd agent-sphere-copilot-widget && npm run dev
```

- 扩展**无构建步骤**：`chrome://extensions` 加载 `agent-sphere-chrome-extension/` 未打包目录。
- 后端 `mvn test` **不需要** DB/Redis（MockMvc `standaloneSetup` + Mockito，不是 `@SpringBootTest`）。
- API 前缀 `/api/v1/...`；SSE 路由含 `/stream`。
- UI 与 widget 的 dev 代理对 `/stream` 都去掉 `Accept-Encoding` 并设 `Cache-Control: no-transform`
  （Umi 侧实际设 `Accept-Encoding: identity`）。**别删**：否则 SSE 断流/被缓冲。

## 中间件

- Postgres + Redis：`agent-sphere/agent-docker-middleware/docker-compose.yml`（在 `agent-sphere/`
  下，**不是**仓库根）。卷路径写死为 macOS `/Users/elvin/...`，其他机器需改/删。
- DB 名 `buukle_agent_2026061101`；env 覆盖 `DB_HOST`/`DB_PORT`/`DB_USERNAME`/
  `DB_PASSWORD`/`REDIS_HOST`/`REDIS_PORT`。
- Redis 必须走 Redisson（`RedisClient` / `CacheService`）。Spring Data Redis 只绑
  `spring.data.redis.*`，本项目用的是 legacy `spring.redis.*`，会**静默回落 localhost**。

## 后端约定（细节在 `agent-sphere/AGENTS.md`）

- 新表必须带通用列：`status` / `remark` / `delete_flag` / `created_by` / `updated_by` /
  `created_at` / `updated_at`；逻辑删除，不物理删除（会话清理任务是唯一有意例外）。
- 禁止魔法值：按作用域复用常量/枚举（方法内 → 类 `private static final` → 模块常量类/`enum`）。
- 不要用裸 `Map` 作为方法入参，用 typed DTO/POJO。

## UI (agent-sphere-ui)

- 命令：`npm run dev` / `build` / `biome`（自动修）/ `lint`（`biome lint` + `tsc`，只查）/
  `tsc` / `test`。验证顺序：`npm run biome` → `npm run lint` → `npm test`。
- 路由是**配置式**：`config/routes.ts`。页面目录对应后端域：`chat`、`instances`、
  `model-providers`、`capabilities/*`、`tasks`、`artifacts`、`admin`、`account`、`user` 等。
- `src/.umi/**` 由 `max dev` / `max setup`（`prepare`）生成，**勿手改**。别名 `@/*`→`src`、
  `@@/*`→`src/.umi`、`@root/*`→仓库根。
- Biome **忽略** `src/services` 与 `mock`；手写客户端 `src/services/agentSphere/api.ts`
  （`BASE = '/api/v1'`）**不被 lint**，需人工保持整洁。
- Biome：单引号、空格缩进、`jsxRuntime: reactClassic`；`noExplicitAny`、
  `useExhaustiveDependencies` 关闭。`.npmrc` 必须 `legacy-peer-deps=true`（否则装不上）。
- Node `>=20`；默认 `zh-CN`；`moment2dayjs` 把 moment 重写为 dayjs。

## Widget (agent-sphere-copilot-widget)

- 独立包，npm 命令都在该目录内执行。`npm run build` =
  `tsc --noEmit && vite build && vite build -c vite.auth.config.ts`，产出
  `dist/agent-sphere-widget.js` + `dist/agent-sphere-auth.js`（第二个带 `emptyOutDir:false`，
  别删）。校验：`npm run typecheck` + `npm test`（vitest）。
- `AgentSphereWidget.init({apiBase, provider, autoLogin, title})` 挂到 **shadow DOM**；
  CSS 用 Vite `?inline` 注入。
- OIDC：`?otc=` → `POST /auth/sso/exchange` → token 存
  `sessionStorage['agent-sphere-widget:agent-user']`；`autoLogin` 是 `prompt=none` 一次性静默探测。
- Timeline：`GET /instance/sessions/{sid}/timeline`（`beforeSeq`/`afterSeq`，limit 5–50）+
  SSE `/runtime/{sid}/stream`。`run_*`/`tool_call_*`/`clarification_*` 触发 `afterSeq` 刷新做
  权威合并（`mergeTimeline` 按 `seq` 去重，子 Agent 占位行 `seq<0`）。
- 发送 `POST /runtime/{sid}/chat` → `{runId}`；停止 `POST /runtime/{sid}/stop`；澄清
  `POST /runtime/{sid}/run/{runId}/clarify`。
- 会话开关：`agent_instance.allow_widget_session`（默认 **false**）/
  `allow_console_session`（默认 true）。widget 每个请求带 `X-Client-Surface: widget`；前端判定
  `src/instance.ts#allowsWidgetSession` 只认严格 `true`；服务端 `SessionSurfacePolicy` 才是强约束。
- 坑：`node_modules` / `package-lock.json` **不可跨机器/跨 OS 复制**（rollup 平台可选依赖会让你
  报 `Cannot find module @rollup/rollup-*`）。本机重装：`rm -rf node_modules package-lock.json && npm i`。
- `useTimelineStream.connect(sessionId, token)` 会替换状态 → 放在按选中 session id 的 effect 里。

## Chrome 扩展

- **无构建**：`npm run build` 指向不存在的 `build.js`，别运行；直接改根目录文件并重新加载未打包
  扩展。`src/` 是空 stub，部署时直接 zip 这些文件。
- 关键约束（详见扩展 AGENTS）：`chrome.debugger` 只在 `lib/cdp-client.js`；executeJS 先
  MAIN-world `chrome.scripting`、再用 debugger 兜底；注入脚本（`content*.js`/`page-script.js`）
  必须用 `vm.Script` 做语法检查 —— `node --check` 会放过 Chrome 拒绝的顶层 `return`，导致整文件不执行。
- `deploy.yml` 的 zip 清单显式列根级文件（`lib/` 整目录）：**新增根级 JS 要加进清单**；
  清单里的 `inject.js`、`content-editors.js` 已不存在（zip 告警跳过，别为此复活）。

## 部署（k3s / GitOps）

- `.github/workflows/deploy.yml` 是**唯一** workflow：推 `v*` tag（或手动 dispatch）→ 构建推送
  backend/frontend/widget 三镜像到阿里云 ACR → 改写 `k8s/05-backend.yaml`、`06-frontend.yaml`、
  `08-widget.yaml` 的 image tag 并 commit/push `main`（Fleet 同步）→ 打扩展 zip 建 Release。
  **无测试 job**；不打算发版就别推 `v*`。
- Tag 约定 `v1.0.NN-alpha`，下一个版本用 `git tag --sort=-v:refname` 取最新再 +1；本地 CI 会自动
  patch+1 并保留 `-alpha`。
- `k8s/`：`02/03` 是 PG/Redis，`04-backend-config.yaml` 是后端 env，`07-ingress`，`09-logging`。
  前端镜像 build-arg `COMMIT_HASH`（UI 版本显示用）。
- 本地 CI `.local-workflow/deploy.sh`：**默认 `--no-tag`**（只构建推镜像 + 改 k8s 提交，避免与
  tag 触发的 Action 双跑）；`--tag` 才递增版本、推 tag、打扩展包、建 Release。`--dry-run` 不动
  任何文件；`--skip-existing` 跳过 ACR 已有镜像；`--only backend,frontend,widget` 裁剪。
  - 要求当前分支 `main` + 工作树干净；arm64 宿主自动加 `--platform linux/amd64`（k3s 节点是
    amd64）。凭据：ACR 在 `.local-workflow/.env`（gitignore），GitHub token 取 `GITHUB_TOKEN`
    或 gitignored `local-config/token.json`。
  - **不含 lint / test / typecheck**，代码仍要按各项目命令自测。
- `mirror-middleware-images.sh`：把 `k8s/02-postgres.yaml` / `03-redis.yaml` 的官方镜像推到同一
  ACR 并改这两处 `image:`（`deploy.sh` 只提交 05/06/08，中间件要单独跑它）。架构不符线上会
  `exec format error`，脚本推送前后各校验一次；`--dry-run` 可预演。

## Git / 环境

- GitHub flow off `main`；**没有测试 CI**，push 前按各项目命令自测。
- 当前 Windows checkout 的 PATH 无 `java` / `mvn` / `gh` / `zip`（`bash` 指向未配置的 WSL）：
  后端构建、`gh` 操作、`.local-workflow/*.sh` 需另找已装环境；`origin` 是 HTTPS。
- 以下均被 gitignore，勿提交：`local-config/`、`.local-workflow/{.env,.cache,output}`、
  `package-lock.json`、`node_modules`、`dist`。
