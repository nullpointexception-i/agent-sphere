# AgentSphere — monorepo

## Response language

- Reply in Chinese by default; use another language only when the user explicitly asks.

## Global hard rules

- **No third-party brands**: never put the brand or domain of a specific third-party site we automate into code, comments, Javadoc, logs, docs, or test fixtures.
  - Use `example.com` / `host.example` for domains.
  - Generalize URL/path fingerprints into brand-free matching rules.
  - If real site data is unavoidable, move it to config and keep only the key + default in code.
- **Applied Flyway migrations are immutable**; add a new migration instead. Historical seed data containing a brand must be rewritten by a new migration.
- **New Flyway migrations use English comments**; historical V1–V149 are frozen.
- The four projects are independent: **no root build, no root lockfile**. Run commands inside each project.

## Projects and reading order

| Directory | Description | Must read |
|---|---|---|
| `agent-sphere/` | Java 21 / Spring Boot 3.4.3 backend | `agent-sphere/AGENTS.md` |
| `agent-sphere-ui/` | React 19 / UmiJS Max admin UI | `agent-sphere-ui/AGENTS.md` |
| `agent-sphere-copilot-widget/` | Embeddable chat widget | `agent-sphere-copilot-widget/AGENTS.md` |
| `agent-sphere-chrome-extension/` | MV3 browser extension | `agent-sphere-chrome-extension/AGENTS.md` |

Other paths: `k8s/`, `.github/workflows/deploy.yml`, `.local-workflow/` are the deployment chain; `agent-sphere-readme/` holds documentation images; `examples/parallel-search/mcp.json` is an MCP config example.

## Cross-project coding rules

- **Magic values**: no raw strings/numbers in business logic. Scope order: method-local → class `private static final` → module constants/`enum` → shared common. DB/API/event values are frozen: rename symbols only, never change values.
- **Enums**: persisted states use `*Status`/`*State`; events use `*Event`/`*EventType`; never use the same name for different meanings. Do not merge domain-specific `ACTIVE`/`FAILED` values into a universal status.
- **Method size**: every method under backend `runner/**` is ≤200 physical lines. Outside that, consider splitting methods over 80 lines. Frontend: extract pure model logic first, then presentational components.
- **Layering**: backend DDD modules and kernel/runner subpackages depend one way only; frontend flows pages → components → hooks → services → constants. Subpackages must not import the facade class.

## Local startup

```bash
# Backend :8080 (Postgres + Redis required; install once first)
mvn install -DskipTests
mvn -pl agent-sphere-bootstrap spring-boot:run -am

# Admin UI :8000 (MOCK=none, /api proxied to :8080)
cd agent-sphere-ui && npm run dev

# Widget :5173 (also proxies /api)
cd agent-sphere-copilot-widget && npm run dev
```

- Extension has no build step: load `agent-sphere-chrome-extension/` unpacked via `chrome://extensions`.
- Backend `mvn test` needs no DB/Redis (MockMvc `standaloneSetup` + Mockito).
- API prefix `/api/v1/...`; SSE routes contain `/stream`.
- UI/widget dev proxies remove `Accept-Encoding` and set `Cache-Control: no-transform` for `/stream`. **Do not remove**: SSE will stall or be buffered.

## Middleware

- Postgres + Redis: `agent-sphere/agent-docker-middleware/docker-compose.yml` (not at repo root). Volume paths are hardcoded for macOS; override/remove on other machines.
- DB name `buukle_agent_2026061101`; env overrides `DB_HOST`/`DB_PORT`/`DB_USERNAME`/`DB_PASSWORD`/`REDIS_HOST`/`REDIS_PORT`.
- Redis access must go through Redisson. Spring Data Redis only binds `spring.data.redis.*`; this project uses legacy `spring.redis.*` and would silently fall back to localhost.

## Deployment (k3s / GitOps)

- `.github/workflows/deploy.yml` is the only workflow: push a `v*` tag (or manual dispatch) → build/push backend/frontend/widget images to ACR → rewrite image tags in `k8s/05-backend.yaml`, `06-frontend.yaml`, `08-widget.yaml` and commit to `main` → package the extension zip and create a Release. No test job.
- Tags: `v1.0.NN-alpha`; take the latest with `git tag --sort=-v:refname` and increment.
- Local `.local-workflow/deploy.sh`: default `--no-tag`; `--tag` increments the version, pushes the tag and creates a Release; `--dry-run` changes nothing; `--skip-existing` skips existing ACR images; `--only backend,frontend,widget` limits scope. Requires `main` and a clean working tree; arm64 hosts automatically add `--platform linux/amd64`.
- `mirror-middleware-images.sh` pushes PG/Redis images separately and updates `k8s/02`/`03`; wrong architecture causes `exec format error`.
- **No lint / test / typecheck in deploy**; run each project's checks before pushing.

## Git / environment

- GitHub flow: branch off `main` → PR. No test CI.
- The Windows checkout may not have `java` / `mvn` / `gh` / `zip` on PATH; use an installed environment.
- Never commit: `local-config/`, `.local-workflow/{.env,.cache,output}`, `package-lock.json`, `node_modules`, `dist`.
