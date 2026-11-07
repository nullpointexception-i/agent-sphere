# AgentSphere Backend (agent-sphere)

Spring Boot 3.4.3, Java 21, Maven multi-module, Lombok. **No Maven wrapper** — use system `mvn`.

## Commands

```bash
mvn install -DskipTests                                  # build once
mvn -pl agent-sphere-bootstrap spring-boot:run -am       # run :8080
mvn test                                                 # all tests; no DB/Redis needed
mvn -pl agent-sphere-bootstrap test -Dtest=SomeTest      # one test class
mvn -pl agent-sphere-instance/agent-sphere-instance-service test -am
```

## Module layout (DDD)

```
agent-sphere-common          auth/tenant context, errors, events, AgentRuntimeProperties
agent-sphere-util            JsonUtils, JsonSchemaGenerator
agent-sphere-infrastructure  MyBatis-Plus, Flyway, Redis, web config, interceptors, CacheService
agent-sphere-runtime         kernel + orchestration
agent-sphere-bootstrap       only runnable module; Flyway migrations; application.yml
```

Business modules (`instance`, `model`, `capability`) are split by layer:

```
-domain → -exception → -dtvo → -spi → -repository → -service → -controller
```

Dependency direction: controller → service → repository → spi/dtvo → domain/exception. Controllers depend on capability `*-spi`, never implementations.

`capability` areas: `mcp`, `skill`, `cli`, `builtin` (plus `builtin-tool-spi`, `builtin-tool-webfetch`).

## Runtime package layering

```text
kernel/
├── SessionRunCoordinator          # only class at kernel root
├── reconcile/                     # OrphanRunSweeper, StaleRunningRunReconciler
├── runner/                        # only SessionRunner at runner root
│   ├── loop/                      # loop records + limit resolution
│   ├── resume/                    # approval/retry/recovery resume
│   ├── turn/                      # turn execution, events, route, retry
│   ├── tool/                      # finish, loop cap, tool policies
│   ├── prompt/                    # first-turn and tail prompt writers
│   ├── compaction/                # CompactionTracker
│   └── support/                   # Redis stores, input manager, JSON helpers
├── service/
├── port/
├── loader/
└── ...
```

Rules:

- `runner` root contains only `SessionRunner`; `kernel` root contains only `SessionRunCoordinator`.
- Dependency direction: `loop → turn/tool/resume/compaction/prompt → support`.
- Subpackages must not import `SessionRunner`.
- Guards: `RunnerRootPackageTest`, `KernelRootPackageTest`, `RunnerPackageDependencyTest`, `SessionRunnerMethodSizeTest`.

## Coding standards

### Magic values

- No raw strings/numbers in business logic.
- Scope order: method-local → class `private static final` → module constants/`enum` → `agent-sphere-common`.
- DB/API/event values are frozen: rename symbols only.
- Do not merge domain statuses: user/instance/session/provider `ACTIVE` lifecycles differ; `FAILED`/`PENDING`/`RUNNING` stay per domain.
- Shared constants: `HttpHeaders`, `McpServerType`, `LlmRoles`, `EventType`, `RunStatus`.

### Enums and states

- Persisted: `*Status` / `*State`.
- Events: `*Event` / `*EventType`, implementing `EventType`.
- No same-name different-meaning classes:
  - `SessionEventType` (event) vs `SessionStatus` (persisted)
  - `ToolApprovalEvent` (event) vs `ToolApprovalState` (persisted)
- Enum value changes are DB/API changes.

### Method and class size

- Every method under `runner/**` is ≤200 physical lines; enforced by `SessionRunnerMethodSizeTest`.
- Methods over 80 lines should be evaluated for extraction.
- `SessionRunner` is a facade/orchestrator, not a domain dump; keep it under ~500 lines.
- Facade roots: `runner` → only `SessionRunner`; `kernel` → only `SessionRunCoordinator`.

### Types

- Avoid `Map` as a method input. Use typed DTOs/POJOs.
- A generic map is acceptable only at framework boundaries (MyBatis result maps, HTTP bags) and must be converted to a typed object at the earliest layer.

## Tests and guards

JUnit 5 + Mockito + MockMvc `standaloneSetup` — **not** `@SpringBootTest`. `mvn test` needs no Postgres/Redis.

Mandatory guards:

- `ApiPrefixScanTest` — no hard-coded `/api/v1` outside `ApiPrefix`.
- `MapperSqlEscapingTest` — annotation SQL escaping in both directions.
- `SessionRunnerMethodSizeTest` — `runner/**` methods ≤200 lines.
- `RunnerRootPackageTest` — runner root only `SessionRunner`.
- `KernelRootPackageTest` — kernel root only `SessionRunCoordinator`.
- `RunnerPackageDependencyTest` — runner subpackages do not import `SessionRunner`.

## Persistence

### MyBatis-Plus

- Logic delete `delete_flag` (1=deleted, 0=active); never hard-delete. The only sanctioned exception is session cleanup.
- `map-underscore-to-camel-case` enabled.
- Type handlers: `com.buukle.agent.infrastructure.handler` (includes PostgreSQL `jsonb`).
- Audit fields auto-filled by `AuditMetaObjectHandler`.
- New tables must carry: `status`, `remark`, `delete_flag`, `created_by`, `updated_by`, `created_at`, `updated_at`.

### Flyway

- Migrations: `agent-sphere-bootstrap/src/main/resources/db/migration/V<n>__desc.sql`.
- Current highest: **V149**. Check the directory before adding one.
- `baseline-on-migrate: true`, baseline 0.
- Never edit an applied migration. New migrations use English comments.

### Annotation SQL

- No dynamic tags: use bare `<` / `>` and **no** `<script>`.
- Any dynamic tag (`<if>`, `<foreach>`, `<choose>`, `<trim>`): wrap in `<script>` and escape every `<` as `&lt;`.

## Runtime invariants

### Run state machine

- `WAITING` = waiting for time; `AWAITING_USER` = waiting for a person; keep them distinct.
- `WAITING` is a real run state: include it in `RunStatus`, active-run lookup, stop handling, lifecycle persistence, timeline terminal recording, and cleanup guards.
- Stopping a parked run must set a terminal state and emit the event directly; the loop has already exited and will not consume a cancel flag.
- Emit `CANCELLED`, not `FAILED`, on cancellation. `FAILED` is a real failure.
- Every FAILED producer puts the reason in the event; `RuntimeEventListener#handleRunLifecycle` is the single writer to `agent_run.error_message`. `RunConverter` must map new fields both ways.
- `LLMEvent.Error` is an event, not an exception; record it so failures are visible in `agent_llm_interaction_record`.
- Exit safety net: if the loop exits while `currentRun` is still RUNNING, mark FAILED and emit a terminal event.

### wait / clarification suspension

- `wait` and `ask_clarification` do not block: they write a pending record and break the loop.
- Suspension detection is structural (`SuspenseStatusResolver`), never substring matching.
- Wake paths: in-memory scheduler + cron fallback. Both must claim atomically with `UPDATE ... WHERE status='PENDING'`; never select-then-update.
- After claim, if the record cannot be read, release the claim; never silently return.
- Wake/reconcile entry points must clear tenant/auth context (`TenantUtil.stop()` + `AuthContext.clear()`).
- Once `asyncHandleUserMessage` returns, the resume is committed; do not roll back to WAITING afterwards. Before submission, failures must roll back.
- `loopCount` and `loopLimit` are persisted with the run; resume must not restart at 0 or shrink the budget. `LoopLimitSource.PINNED` has highest priority.
- Sub-agents do not persist/pin loop limits and must not receive `wait` in their tool set.

### Loop budget and silent turns

- `isLastLoop` stays at `maxLoopCount - 1`; it drives loop-cap and completion decisions.
- Loop budget and `FINAL_TURN_INSTRUCTION` are rebuilt at the tail every loop, never replaced in place.
- Tail order: `[... , todolist?, loopBudget, (FINAL_TURN)]`.
- Hard-finalization text must not claim “this is the last loop”; completion instruction must exempt `finish`.
- No tool call is not completion. If tools were used but no tool call arrives, nudge; after `MAX_SILENT_TURNS`, FAIL.
- Pure chat runs (never used tools) may complete by text.
- `loopCapped` is set only on real truncation, never on the COMPLETE branch.

### finish protocol

- `finish` is the only legal agentic end state. `FinishConstants` + hand-written schema + registration in `ContextPreparer` + interception in `ToolExecutor`; no SPI bean, no DB seed.
- Register when `noClarification` or capabilities exist; pure chat does not get it.
- Handle `finish` before submitting any tool. Other calls in the same turn are not executed; close their PENDING records with FAILED.
- `finish` on the last loop is a clean completion, not a loop cap.
- `finish(status=failed|blocked)` still marks the run COMPLETED; status is carried by the finish record and summary.
- `finish.summary` must be visible as assistant text: push it as `CONTENT_TOKEN` when the body is empty, and persist it into the latest empty `CHAT_REPLY`.
- Hide the `finish` tool row in both UIs; keep the tool record for audit.
- Sub-agents do not receive `finish`.

### finish_reason

- Preserve the real `finish_reason` (`stop` / `length` / `content_filter` / `tool_calls`).
- With no tool calls, `length` and `content_filter` are ERROR, not COMPLETED.
- With tool calls, continue normally when the arguments are complete/usable.

### Compaction

- Compact only finished history: `COMPLETED`/`FAILED`/`CANCELLED`, excluding the current run.
- Cursor advances only to the last run actually written into the summary.
- Only `COMPLETED` triggers history reassembly. Other outcomes set `skipCompaction`, continue the turn; after repeated failures disable compaction for the run.
- Rebuild `[system + new history] + currentRunTail`; never clear everything and reload from DB.
- `currentRunStart` is relative to history; add the system-prompt base offset.
- Run-local compaction keeps current user + recent messages as tail; records use non-null `run_id`; session-level latest must ignore run-local records.
- Snapshot current run tail on wait/clarification suspension.
- Summary must be complete. Do not persist `length`/`content_filter`/unbalanced output; retry up to `summary-compress-rounds`, then trim only as last resort.
- Compaction calls use `thinking=disabled`.
- Token estimation: Chinese text `chars / 1.8`, tool schemas/JSON `chars / 3.5`, images 1,000 tokens each, total ×1.1.
- Reserve output budget before compaction; use `max_input - output_cap - margin`, floor at 25% of `max_input`.
- If fixed system+tools already exceed the hard input limit, skip compaction and send as-is.
- Compaction timeline: `CompactionStatus.RUNNING` creates the row; stream deltas use `CONTENT_TOKEN + compaction_output`; COMPLETED/FAILED always emitted.

### Session cleanup

- `SessionCleanupTask` hard-deletes expired session data; the only sanctioned hard-delete path.
- Delete order is a foreign-key contract; `SessionCleanupTaskTest` asserts it with `InOrder`.
- Expiry uses session `created_at`/`updated_at` plus NOT EXISTS guards for active runs/tasks. `AWAITING_USER` is intentionally not guarded.
- Multi-replica lock: `scheduler:session-cleanup`.
- Every run records `agent_session_cleanup_run`; RUNNING is inserted before the lock so SKIPPED runs leave a trace.
- Manual trigger is asynchronous; `dryRun` defaults to true; progress polls `GET /runs/{id}`.
- Capture operator on the request thread. Keep `runCleanup` synchronous; submit wrapper handles HTTP/cron.
- Hard delete does not return disk to the OS; log the `VACUUM (FULL, ANALYZE)` hint.

### Skill sync

- `SkillSyncRunner` scans `auto_update = true AND origin_skill_id IS NOT NULL`; **no time window**.
- Every scan writes `capability_skill_sync_run` with scanned/updated/skipped counts and skip details.
- Skip reasons use `SkillSyncOutcome`; keep them distinct.
- `synced_at` / `synced_from_version` are display snapshots, separate from the optimistic lock.
- Async submit + `scheduler:skill-auto-update` + terminal bookkeeping use `AbstractRecordedTask`; run context must be a parameter, not a field/ThreadLocal.
- Manual trigger: `POST /api/v1/capability/skill/sync-now`; poll `GET /sync-runs/{id}`.

### Capability policy / run mode

- MCP/CLI definitions hold `compress_enabled`, `access_mode` (`READ`/`WRITE`/`READ_WRITE`), `security_level` (`L1`/`L2`), `approval_scope` (`SESSION`/`RUN`/`ONCE`).
- `agent_session.run_mode` is snapshotted to `agent_run.run_mode`.
- READ_ONLY: READ allowed; WRITE denied; MCP/CLI READ_WRITE allowed; builtin browser allows read actions and denies click/type/executeJS; TODOWRITE/FINISH/ASK_CLARIFICATION/WAIT always allowed.
- L2 approvals are a separate `agent_tool_approval` + grant flow, not clarification/timeline.
- `compress_enabled` affects live/history tool results only.

### Builtin tools

A builtin tool = one Maven module + `@Component implements CapabilityBuiltinToolSpi`. Schema is generated from the POJO unless hand-written (e.g. `wait`).

- `needConfig() == false` → auto-included in every instance. `true` → must be bound explicitly; flipping it silently removes the tool from all instances.
- Register in **all four places**:
  1. `BuiltinToolEnum` id.
  2. `agent-sphere-capability-builtin-tool/pom.xml` module.
  3. `agent-sphere-bootstrap/pom.xml` dependency.
  4. root `pom.xml` `dependencyManagement`.
- Tool name: `builtin.<SimpleClassName>`; model-side name: `builtin_<id>`.
- `@NotNull`/`@NotBlank` are not enforced on the runtime path; validate inside `execute` too.

### Provider strategies

- `ProviderStrategy`: `getCompany` / `adaptRequest` / `adaptResponse` / `extractToolCallsFromContent`.
- `ProviderStrategyFactory` selects by `ModelProviderCompany` only; no model-name fallback.
- `ChatMessageDTO` is class-level `@JsonInclude(NON_NULL)`: setting a field to null omits the key. Use empty strings when the provider requires the key.
- Qwen: must be selected explicitly as `qwen`; leading system messages are merged; trailing system messages are downgraded to user in place; never mutate the runner's shared message objects.
- Reasoning delta: support both `delta.reasoning` and `delta.reasoning_content`, skipping empty strings.
- `LLMEvent.Error` carries `httpStatus` + `retryable`; 4xx (except 408/429) are terminal and not retried.

### Session surface

- `allow_console_session` (default true) and `allow_widget_session` (default false) control session creation/messaging per entry point.
- Widget sends `X-Client-Surface: widget`.
- `INTERNAL` (no holder) must be allowed for task/internal calls.
- Single policy point: `SessionSurfacePolicy`. Enforcement: `SessionServiceImpl.createSession` and `ChatRuntimeService.createRun`.
- Clear the ThreadLocal in `AuthInterceptor.afterCompletion`; set it only after authentication succeeds.
- Server is the strong constraint; frontend checks are UX only.

### Run activity and usage

- Activity list returns metadata only; never select large fields (`request_body`, `response_body`, `reasoning`, `reply_content`, `arguments_json`, `artifact`).
- Large fields are fetched per activity via the detail endpoint.
- List/detail SQL must include `run_id` + `session_id` + `delete_flag`.
- Usage scopes are deliberately different: session cumulative, run cumulative, trend window (last 300).
- Cache hit rate = `hit / (hit + miss)`, one decimal; sum over visible points.

## Config pitfalls

- `AgentRuntimeProperties` is the only `@ConfigurationProperties(prefix = "buukle.agent")`.
- `wait.*` keys (`wake-cron`, `reconcile-*`) are read via `@Value`/`@Scheduled`, not the binding class.
- Wrong indentation is silently ignored (`ignoreUnknownFields=true`). After changing YAML, verify the path matches the binding field.

## Conventions

- JVM/Jackson timezone: `Asia/Shanghai`.
- Virtual threads enabled by default.
- API prefix `/api/v1/...`; SSE routes contain `/stream`.
- Logging: `com.buukle` DEBUG; `*.repository` and `com.buukle.agent.capability` WARN.
- Key libs: Redisson, Resilience4j, Caffeine, Hutool, victools, Swagger.

## Git

GitHub flow: branch off `main` → PR. No test CI; run `mvn test` before pushing.
