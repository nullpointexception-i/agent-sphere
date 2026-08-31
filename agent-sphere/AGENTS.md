# AgentSphere Backend (agent-sphere)

Stack: Spring Boot 3.4.3, Java 21, Maven multi-module, Lombok. **No Maven wrapper** — use system `mvn`.

## Module layout (DDD layered)

```
agent-sphere-common       — auth/tenant context, error codes, BizException/GlobalExceptionHandler,
  events, chrome-bridge DTOs (ChromeCommandDTO/ChromeCallbackDTO/ChromePendingStore),
  AgentRuntimeProperties
agent-sphere-util         — JsonUtils, JsonSchemaGenerator (victools JSON-schema gen from POJOs)
agent-sphere-infrastructure — MyBatis-Plus config, Flyway, Redis, web config, type handlers,
  interceptors (AuthInterceptor, DataPermissionInterceptor), AuditMetaObjectHandler,
  TraceIdFilter, ControllerLogAspect, CacheService
agent-sphere-runtime      — kernel + orchestration (langgraph4j agent workflow graphs)
agent-sphere-bootstrap    — the ONLY runnable module (spring-boot-maven-plugin),
  Application.java (com.buukle.agent.Application, @ComponentScan "com.buukle.agent", port 8080),
  Flyway migrations, application.yml
```

**Business modules** (`instance`, `model`, `capability`): each split by layer:
`-domain` → `-exception` → `-dtvo` → `-spi` → `-repository` → `-service` → `-controller`
Dependency direction: controller → service → repository → spi/dtvo → domain/exception. `common`+`util` are shared foundations. Controllers depend on capability `*-spi` contracts, NOT implementations.

`capability` has 4 areas: `mcp`, `skill`, `cli`, `builtin` (+ `builtin-tool-spi`, `builtin-tool-webfetch`).

## Commands

```bash
# Build everything (required before most operations)
mvn install -DskipTests

# Run the app (port 8080)
mvn -pl agent-sphere-bootstrap spring-boot:run -am

# After a full build, -am not needed:
mvn -pl agent-sphere-bootstrap spring-boot:run

# Run all tests (fast — no DB/Redis needed, see below)
mvn test

# Run one test class
mvn -pl agent-sphere-bootstrap test -Dtest=SessionControllerTest

# Run one module's tests
mvn -pl agent-sphere-instance/agent-sphere-instance-service test -am
```

## Tests

JUnit 5 + Mockito + MockMvc `standaloneSetup` — **NOT** `@SpringBootTest`. Controller tests mock services via `@ExtendWith(MockitoExtension.class)`. `mvn test` does NOT need Postgres/Redis.

## Runtime prerequisites

PostgreSQL (DB name `buukle_agent_2026061101`) + Redis. `docker-compose.yml` lives at `agent-sphere/agent-docker-middleware/docker-compose.yml` (under this project, **not** the repo root) and starts both, but volume paths are hardcoded to macOS (`/Users/elvin/Desktop/...`) — override or remove volumes on other machines.

Env overrides (defaults): `DB_HOST` (127.0.0.1), `DB_PORT` (5432), `DB_USERNAME` (buukle), `DB_PASSWORD` (buukle123), `REDIS_HOST` (127.0.0.1), `REDIS_PORT` (6379).

## Flyway

Migrations: `agent-sphere-bootstrap/src/main/resources/db/migration/V<n>__desc.sql`. 历史 52 个迁移(V1–V52)已整合为**单个 `V1__init.sql`**(终态建表 + 全量种子 + 序列对齐),原文件归档于 `agent-sphere-bootstrap/migrations-archive-v1-v52/`(仅供查阅,Flyway 不扫描)。新增改动一律从 **V2 起**编号;`baseline-on-migrate: true`, baseline 0。**严禁编辑已应用过的迁移**(校验和会不匹配;如需改已有迁移,先 `flyway repair` 或对受影响库删对应 `flyway_schema_history` 行再重放)。注意 `V1__init.sql` 含种子:`demo001/demo001` 演示账号、Bole completions id 1–7 为外部契约不可改号、末尾 setval 对齐序列。

## MyBatis-Plus

- Logic-delete column `delete_flag` (1=deleted, 0=active) — never hard-delete, always set `delete_flag`.
- `map-underscore-to-camel-case` enabled.
- Type handlers package: `com.buukle.agent.infrastructure.handler` (includes `JsonbTypeHandler` for PG jsonb).
- Audit meta auto-filled by `AuditMetaObjectHandler` (creates/updates timestamps and user info).

## Conventions

- JVM default + Jackson timezone: `Asia/Shanghai` (set in `Application.java` and `application.yml`).
- Virtual threads enabled by default (`ENABLE_VIRTUAL_THREADS=true`).
- API prefix: `/api/v1/...`; SSE streaming routes contain `/stream`.
- Logging: `com.buukle` → DEBUG; `*.repository` packages and `com.buukle.agent.capability` → WARN.
- Key libs beyond Spring: langgraph4j, redisson, resilience4j, caffeine, hutool, victools, swagger.

## Code style

- Avoid `Map` as a method input parameter. Use typed DTOs/POJOs so signatures, validation, and refactoring stay explicit. A generic `Map<String, Object>` is only acceptable at framework boundaries (e.g. MyBatis result maps, HTTP param bags) and must be converted to a typed object at the earliest layer.
- No magic values. Reuse existing constants/enums by scope before introducing new ones: method-local → `private static final` on the class → module-wide constants class / `enum`. Only declare a new constant when no suitable one exists in the relevant scope. Never inline raw numbers or strings inside business logic.

## Git

GitHub flow: feature branch off `main` → PR. No CI configured — run `mvn test` before pushing.
