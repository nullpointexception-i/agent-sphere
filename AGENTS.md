# AgentSphere — monorepo

Git root holds two **independent** projects (no shared root build/lockfile/CI):
- `agent-sphere/` — Java 21 / Spring Boot backend. **See `agent-sphere/AGENTS.md`** for module layout, Maven commands, Flyway, MyBatis-Plus, and code-style rules.
- `agent-sphere-ui/` — React 19 / UmiJS Max frontend (Ant Design Pro base).

GitHub flow off `main`. **No CI** — run each project's tests before pushing.

## How the two apps connect

- Backend serves API at `http://localhost:8080` with prefix `/api/v1/...`; SSE routes contain `/stream`.
- UI `npm run dev` (port 8000) proxies `/api/` → `http://localhost:8080` (see `agent-sphere-ui/config/proxy.ts`). For `/stream` requests the proxy **strips `Accept-Encoding`** and sets `Cache-Control: no-transform` — preserve this logic, SSE breaks without it.
- Typical local flow: start backend (`mvn -pl agent-sphere-bootstrap spring-boot:run -am`, needs Postgres+Redis), then UI (`cd agent-sphere-ui && npm run dev`).

## Infra / runtime prerequisites

- Postgres + Redis via `agent-sphere/agent-docker-middleware/docker-compose.yml` (note: under `agent-sphere/`, **not** the repo root). Volume paths are hardcoded to macOS (`/Users/elvin/Desktop/...`) — override or remove on other machines.
- Postgres DB name is `buukle_agent_2026061101` (set in `application.yml` and the compose file). Env overrides: `DB_HOST`, `DB_PORT`, `DB_USERNAME`, `DB_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`.
- Backend JVM/Jackson timezone `Asia/Shanghai`; virtual threads on by default.

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
