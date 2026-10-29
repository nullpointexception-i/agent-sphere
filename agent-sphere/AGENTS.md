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

**Redis access must go through Redisson** (`RedisClient` bean in `infrastructure/config/RedisConfig`, inject `RedissonClient` or reuse `CacheService`). Do **not** use Spring Data Redis (`StringRedisTemplate` / `RedisTemplate`):

- the connection config lives under the **legacy prefix** `spring.redis.host/port` and is read only by that hand-written `@Value` config;
- Spring Data Redis auto-configuration only binds `spring.data.redis.*` (Boot 3 renamed it), so with only the legacy prefix it **silently falls back to `localhost:6379`** — there is no Redis in the pod, so every read/write fails;
- this once made task-level MCP credentials never get written, surfacing far away as the downstream error `缺少 X-Task-Mcp-Credential`, while the real failure was hidden in a `warn` log.

## Flyway

Migrations: `agent-sphere-bootstrap/src/main/resources/db/migration/V<n>__desc.sql` (currently V1–V81; check the directory for the current highest number before adding a new one). `baseline-on-migrate: true`, baseline 0. Add new `V<n>` files; never edit applied migrations.

## Session data cleanup (disk reclamation)

`SessionCleanupTask` (`instance-service/service/impl`) hard-deletes expired session data on a cron so the DB stops growing. Companion SQL lives in `SessionCleanupMapper` (`instance-repository`) — a **raw-SQL mapper that extends nothing**: it must reach `agent_task` / `agent_completions_call` / `agent_file_store`, which belong to other modules, and `infrastructure` already depends on `instance-service`, so instance must not depend back.

- **Hard delete, deliberately.** This is the one sanctioned exception to "never hard-delete": `agent_llm_interaction_record` stores full request/response/reasoning TEXT, so `@TableLogic` (`UPDATE delete_flag=1`) leaves every byte on disk. Cleanup SQL therefore bypasses MyBatis-Plus on purpose, and deliberately does **not** filter on `delete_flag` — already-logically-deleted rows occupy space too.
- **Delete order is a foreign-key contract.** Only two real FKs exist and neither has `ON DELETE CASCADE`: `agent_run.session_id → agent_session` and `agent_task_artifact.task_id → agent_task`. Method order in the mapper == execution order; `SessionCleanupTaskTest` asserts it with `InOrder`. Reordering breaks production with `violates foreign key constraint`.
- **Expiry = `agent_session.created_at < cutoff AND updated_at < cutoff`**, plus two `NOT EXISTS` guards for `PENDING`/`RUNNING` runs and `QUEUED`/`RUNNING` tasks (a long Bole task only updates `task`/`run` rows, never the session row, so time alone is not enough). `AWAITING_USER` is intentionally **not** guarded — a clarification nobody answers would block cleanup forever.
- **Multi-replica**: `k8s/05-backend.yaml` has `replicas: 2`, so the job takes the Redisson lock `scheduler:session-cleanup` (same pattern as `AuditLogCleanupTask`).
- **Config split**: retention days + kill switch live in `agent_system_config` (`config_group='session'`, keys in `SystemConfigKeys`, seeded by `V75`/`V77`); cron/batch size/sleep live in `application.yml` under the existing `buukle.agent.session` block. Change retention without a redeploy.
- **Every run is recorded** in `agent_session_cleanup_run` (`SessionCleanupRunMapper`, seeded table in `V76`): both the cron and the manual endpoint. The RUNNING row is inserted **before** the lock is taken, so "lock was busy" and "kill switch off" also leave a trace (`status=SKIPPED` + `skip_reason`) — otherwise nobody can explain why the disk did not shrink. Terminal states: `SUCCESS` (with `table_stats` jsonb per-table rows), `SKIPPED`, `FAILED` (+`error_message`, and the exception is still rethrown). Previews (`dryRun=true`) are recorded too — the irreversible-delete audit trail needs them. The run table prunes itself as the job's last phase (`session.cleanup-log-retention-days`, excluding the current row).
- **Do not reuse `sys_audit_log` for these records**: `AuditLogCleanupTask` deletes it after 7/90 days, so cleanup history would erase itself.
- **jsonb without a type handler**: `table_stats` is written with `CAST(#{json} AS jsonb)` and read with `CAST(table_stats AS text)` (parsed by `JsonUtils`). A MyBatis-Plus entity cannot do this — a `String` binding is rejected by PG for a jsonb column, and `JsonbTypeHandler` lives in `infrastructure`, which already depends on `instance-service`.
- **`GET /api/v1/instance/session-cleanup/runs`** lists the history (`Page<SessionCleanupRunVO>`, permission `admin:settings:read`). The SQL computes a `stale` flag (`finished_at IS NULL AND started_at < now() - 30min`) so a crashed process's zombie RUNNING row is not mistaken for an in-flight cleanup.
- **Indexes**: `V74` adds the ones cleanup needs (`agent_session(delete_flag,created_at)`, `agent_task(session_id)`, …). Without them the driving query seq-scans.
- **Manual trigger is asynchronous**: `POST /api/v1/instance/session-cleanup` (permission `admin:settings:update`, `dryRun` defaults to **true**) only *submits* — it inserts the RUNNING record on the request thread and returns it immediately; the work runs on `runtimeAsyncExecutor`. A round can span 200×50 sessions and take minutes, so blocking the request thread would tie up the pool and leave the client guessing. Poll `GET /runs/{id}` for progress and result.
- **Progress**: `countExpiredSessions` is called **once** up front and stored in the record's `total_sessions` (`V78`); every batch writes `session_count` / `total_rows` / `table_stats` back via `markProgress`. Never recompute the denominator mid-run — during a dry-run nothing is deleted, so a live count would drift with the numerator and the percentage would be meaningless.
- **Operator is captured before submitting**: `currentOperator()` runs on the request thread, otherwise a manual run would be recorded as `system`.
- **`runCleanup` stays synchronous on purpose** — `submitCleanup` wraps it for HTTP/cron, while the sync method keeps assertions on delete ordering and terminal record states straightforward in unit tests. Do not add `@Async` to the class: it would not apply to same-class calls anyway.
- **Disk is not returned to the OS.** Hard delete only frees space for reuse inside PG; the data files stay the same size. The job logs a `VACUUM (FULL, ANALYZE)` hint instead of running it (ACCESS EXCLUSIVE lock).
- Sibling job `AuditLogCleanupTask` stays in `infrastructure/config` — audit logs are that module's domain; only session cleanup moved.

## MyBatis-Plus

- Logic-delete column `delete_flag` (1=deleted, 0=active) — never hard-delete, always set `delete_flag`.
- `map-underscore-to-camel-case` enabled.
- Type handlers package: `com.buukle.agent.infrastructure.handler` (includes `JsonbTypeHandler` for PG jsonb).
- Audit meta auto-filled by `AuditMetaObjectHandler` (creates/updates timestamps and user info).

## Annotation SQL: how to write `<` in `@Select`/`@Delete`/…

MyBatis only XML-parses the annotation string when it is wrapped in `<script>`, and only then decodes entities. Without the wrapper the string reaches JDBC **verbatim**, so `created_at &lt; ?` reaches PG as `created_at lt ?` → `ERROR: column "lt" does not exist` (really hit: three `SessionCleanupMapper` selects forgot the wrapper). The reverse — a bare `<` inside `<script>` — blows up at parse time.

| Situation | Correct form |
| --- | --- |
| No dynamic SQL | bare `<` / `>`; **no** `<script>` (e.g. `SessionCleanupRunMapper#deleteOlderThan`) |
| Any dynamic tag (`<foreach>`, `<if>`, `<choose>`, `<trim>`…) | wrap in `<script>`, and write every `<` as `&lt;` |

`MapperSqlEscapingTest` (bootstrap, source-scanning) enforces both directions across all modules. Note when touching it: it must scan **depth-agnostically** — sub-modules nest one level deep (`agent-sphere/agent-sphere-instance/agent-sphere-instance-repository/src/main/java`), and assuming modules are direct children of the root silently scans almost nothing while still passing.

## Skill Hub auto-update (skill sync)

`SkillSyncRunner` (`capability-skill-service/.../service/impl/SkillSyncRunner.java`) syncs installed
copies from their origin skill. Replaces the old `SkillAutoUpdateSweeper`.

- **The old scan window was the "no effect" root cause**: it filtered `created_at >= now - 7d`, i.e. by
  *install* time, so copies installed more than 7 days ago were **never scanned again**. The scan is now
  just `auto_update = true AND origin_skill_id IS NOT NULL`, which `idx_skill_auto_update(auto_update,
  origin_skill_id)` matches exactly. `SkillSyncRunnerTest#sweep_queryHasNoTimeWindow` locks this in — do
  not add a time window back.
- **Execution records are the only evidence.** The logger is covered by logback's
  `com.buukle.agent.capability=WARN` rule, so this task's `log.info` never lands in a file (the old
  sweeper had `if (handled > 0)` on top of that, i.e. silent even when enabled). A single
  `com.buukle.agent.capability.skill.service.impl.SkillSyncRunner` logger is pinned to INFO in
  `logback-spring.xml`; every scan also writes one `capability_skill_sync_run` row (`V80`) with
  scanned/updated/skipped counts plus a per-copy skip detail. Skip reasons are enumerated in
  `SkillSyncOutcome` — `syncFromOrigin`'s old boolean return could not tell `SOURCE_GONE` from
  `VERSION_NOT_AHEAD`, which is exactly the question you need answered when nothing synced.
- **Sync metadata is a display snapshot, separate from the optimistic lock.** `origin_version` is read and
  rewritten by the conditional UPDATE, so a failed update desyncs it from the UI; `synced_at` /
  `synced_from_version` (`V79`) are write-once-ish snapshots set on install and on successful sync.
  Do not collapse the two.
- Async submit + `scheduler:skill-auto-update` lock + terminal-state bookkeeping come from
  `AbstractRecordedTask` (in `common`), shared with `SessionCleanupTask`. Its context type is
  `AbstractRecordedTask<T, C>` because the run context **must** travel as a parameter — the body often
  runs on a virtual thread, and a field or `ThreadLocal` would silently default there (for the cleanup
  task that default direction is *actually deleting data*).
- Manual trigger: `POST /api/v1/capability/skill/sync-now`; poll `GET /sync-runs/{id}`. Both mirrors of
  the session-cleanup endpoints, and the frontend reuses `useRecordedTask`.

## wait 工具：挂起-恢复（不走常规工具路径）

`wait`（`BuiltinToolEnum.WAIT(10)` / `builtin_10`）**不阻塞**。工具执行体只写一条
`agent_pending_wait` 并立即返回 `{"status":"waiting"}`，SessionRunner 判定后 `break`
退出循环，到点再唤醒。**形态照抄 `ask_clarification`：没有独立 maven 模块、没有
`CapabilityBuiltinToolSpi` bean、没有 DB seed** —— 常量在 `WaitConstants`，手写 schema 与
显式注册在 `ContextPreparer`，执行拦截在 `ToolExecutor`（排在 `executeBuiltinTool` 之前）。

- **为什么必须挂起而不是 sleep。** 常规工具必须在一个 `tool.execution-timeout`（60s）的盒子里
  跑完，「等超过 60s」因此不可满足。挂起后等待发生在超时盒子之外：不占虚拟线程、不占
  `maxParallel:3` 信号量、不受 tool/step 超时约束。只有必要时才放宽全局超时 —— 别为单个
  需求去改 `FiberSet` 的整批 deadline。
- **`FiberSet` 一行都不用改。** 澄清已经证明这点：工具瞬间返回，挂起判定完全活在循环**之外**。
  `SuspenseStatusResolver` 是唯一的判定入口。
- **判定必须结构化解析。** 旧写法 `toolResult.contains("\"status\":\"awaiting_user\"")`
  依赖序列化后的字段顺序与字面量，改一次编码方式就会静默失效 —— 挂起不触发，run 拿着
  一条「等你回答」的结果继续空转，比报错难查得多。解析失败一律返回 `null`（不挂起）。
- **唤醒有两条路径，靠 CAS 保证只恢复一次。** 主路径 `PendingWaitWakeScheduler`（每条记录一个
  内存定时器），补偿路径 `WaitWakeTask`（cron，Redisson 锁 `scheduler:pending-wait`）。副本重启
  会丢光内存定时器。两者都先 `PendingWaitSpi#claimForResume` 做
  `UPDATE ... WHERE status='PENDING'` —— **必须是单条条件 UPDATE**，不能「先 select 再 update」，
  否则并发下双双通过检查。
- **跨模块只走 Spring 本地事件。** kernel（`ToolExecutor`）只能依赖 instance-spi，而恢复 run 的
  `ChatRuntimeService#resumeFromWait` 在 orchestration —— 依赖方向是 orchestration → kernel，
  反向调用会成环。故 `PendingWaitService` 发 `PendingWaitCreatedEvent`，由
  `PendingWaitWakeScheduler` 监听。run 是「单写者（本执行副本）」语义，本地定时器即够。
- **`loopCount` 必须持久化（V82 `agent_run.loop_count`）。** 它此前是 `runInternal` 的局部变量，
  每次挂起恢复都归零，于是 `maxLoopCount` 只约束「单次调用」—— 模型可反复挂起无限续命
  （澄清现在也有这个洞，V82 一并修掉）。恢复时从 `currentRun.getLoopCount()` 起步；
  自增随循环顶部那次已有的 `updateRun` 一并落库，不额外增加写。
  `RunConverter` 是手写的，**加字段必须两边都改**，漏了不会报错、只会让护栏静默失效。
- **轮次上限本身也必须固化（V88 `agent_run.loop_limit`），否则预算会中途缩水。** 只固化计数器
  （`loop_count`）而不固化**分母**是不完整的：上限原本在每次进入循环时重新解析，而主 run 有
  **5 个重入入口** —— 只有首次 `startRun` 在任务请求线程上（持有 `TaskLoopLimitHolder`，能拿到
  `runner.task-max-loop-count`），`wait` 恢复 / 澄清恢复×2 / `WaitingRunReconciler` 抢救都在
  **调度线程**上，会取不到 TASK 层而回落到更小的 `max-loop-count`（线上：任务本该 2000 轮，
  一次挂起后就按 128 轮被 loop cap 打断）。故 `LoopLimitSource` 多了 `PINNED`，
  `resolveMain` 把它排在最高优先级；`loop_limit` 与 `loop_count` 在**同一位置**落库，不额外增加写。
  连带堵住「运行中改实例 `max_loop_count` / 改 `max-loop-count` 配置」造成的预算漂移。
  **子 Agent 刻意不固化**：`SessionSubRunner` 是单次同步 for 循环、无挂起/恢复（每条出口恰好
  `finishSubAgentRun` 一次），上限在一次调用内不会漂移 —— 加固化只会多出永远为 null 的参数与死字段。
- **尾部预告块（loop 预算 + 终轮收口）必须每轮重建，不能原位替换。** 工具轮会往 messages 尾部
  追加 assistant(tool_calls)/tool/观察消息，预览若只做「原位替换」就会沉进历史中间 —— 既白烧
  context，又出现两份互相矛盾的「还剩几轮」。所以 `applyLoopBudget` 每轮先按
  `LOOP_BUDGET_PREFIX` marker **全表 removeIf**（顺带清掉残留的 `FINAL_TURN_INSTRUCTION`，
  否则上一轮收过口的 run 会被永久禁止调工具），再在尾部重新追加，保证全列表恒只有一份当前轮次。
  尾部顺序恒为 `[…, todolist?, loopBudget, (FINAL_TURN)]` —— 待办槽插在预告块之前，
  轮中刷新待办才不会被挤到中间。
- **倒计时 ≠ 硬收口。** `FINAL_TURN_INSTRUCTION`（"Do NOT call any more tools"）只在**最后一轮**
  注入；预算预告只是数感（`Loop n/total, remaining m`），剩余 ≤ `LOOP_BUDGET_LOW_THRESHOLD`(5) 才
  加一句催促。提前几轮就禁工具会把没做完的活截成半成品 —— 这正是把它从「n-1 提前到 n-5」时
  最容易踩的坑。
- **`isLastLoop` 必须留在 `maxLoopCount - 1`。** 它不只管注入收口指令，还管 COMPLETE 分支与
  工具执行后的 `markLoopCapped`（loop cap 的执行时机）。为了调预告而改这个判据，会让
  loop cap 静默失效。倒计时用 `remaining = maxLoopCount - loopCount` 单独算，不复用它。
- **子 Agent 工具集必须剔除 `wait`**（`SessionSubRunner#filterTools`）。挂起判定只在主循环有；
  放行的话 `ToolExecutor` 仍会建 pending 记录并返回 `{"status":"waiting"}`，却没人认领 ——
  变成一次静默的空等。
- **`RunStatus.WAITING` 不能进终态白名单。** 前端 `PULL_TRIGGER_SUB_TYPES` 加它（要刷新显示
  「等待中」），但 `RUN_TERMINAL_SUB_TYPES` **不加** —— 加进去会让等待期间就解锁输入框。
  与 `AWAITING_USER` 分开正是为了区分「等时间」和「等人」。
- **等待不保证就绪。** `WAIT_RESUME_PREFIX` 与工具描述都强制模型醒来先 `getContent` 复核，
  且不得重复同一次 wait —— 沿用 `getContent(mode:ready)` 那套「等完必须复核」的经验。
- **`WAITING` 是一个真正的 run 状态，必须进整套状态机**（这份清单漏一处就静默失效，
  曾导致「停止按钮在等待时无效」）：
  `RunEnum.STATUS_WAITING` / `RunServiceImpl.findActiveRun`（否则 stopSession 走「无活动 run」
  分支，清掉刚设的取消标志、什么都不取消）/ `ChatRuntimeService.stopSession`+`stopRun`
  （停车态与 `AWAITING_USER` 同构：置 `CANCELLED` + `PendingWaitSpi.cancelPendingByRunId`
  取消挂起 + 发 `run_cancelled`）/ `RuntimeEventListener.handleRunLifecycle` /
  `TimelineRecorder.recordRunTerminal`（`default -> throw`，漏了直接抛）/
  `SessionCleanupTask` 活跃守卫。停车态的 loop 已退出，**取消标志没有消费者** ——
  停止必须「直接置终态 + 发事件」，不能只设标志等 loop 消费。
- **取消发 `RunStatus.CANCELLED`，不是 `FAILED`**（循环收口处）：旧实现发 FAILED，
  监听器又会把 DB 覆写回 FAILED，于是「取消」被记成「失败」。
- `OrphanRunSweeper` 把孤儿 run 置 FAILED 时必须**同时发终态事件**，否则该会话 timeline
  永久停在运行中，只有刷新才一致。
- **但它管不到「循环退出没写终态」的 run**：那条路径里 `SessionRunCoordinator` 已把 Redis state
  置 IDLE 并清掉 owner，而 sweeper 只认 state ∈ {RUNNING, RUNNING_WITH_PENDING} + 无 owner，
  两者永远对不上（线上 run 2263/2264 因此永久 RUNNING）。这类脏数据由
  `StaleRunningRunReconciler` 按 DB `updated_at` 兜底（见 L0/L1/L2 一节末尾）。

## 技能与待办（skill ↔ todo）——单向、模型主导

**读技能是纯读，绝不改动待办。** 旧 `seedTodosFromSkill`（技能 `get` 时把 H2 步骤写进
`agent_session_todo`）已删除：它在「只是看一眼技能」时就替换掉模型已建的计划，而且是
不可见的副作用（写的是表，模型看的却是另一个源）。不要再把任何「读技能 → 建计划」的隐式
联动加回来。

- **待办是唯一的计划载体，由模型用 `todowrite` 维护。** 无技能时模型自建；技能即整份计划时
  按 `## stepN`/`### stepN.M` 铺成 `N. 标题`/`N.M 标题`；技能只是某几步的方法时**保留原计划**、
  只补来源字段。三场景与文化口径写在 `RunnerConstants.PROMPT_TOOLS_FOOTER` 与
  `CapabilityBuiltinToolTodowrite.TOOL_DESCRIPTION`，**改文案要三处对齐**（footer、
  `PROMPT_TODOLIST_INSTRUCTION`、两个工具 description）。
- **技能来源是结构化字段，不是文本。** `todowrite` 条目的 `skillId`(Long)/`stepId`(String)，
  落到 `agent_session_todo.skill_id/step_id`（V84）。执行该条目时模型用
  `skill_library(action=get, skillId=…, sectionHeading=stepId)` 回查正文（`headingMatches` 是
  大小写不敏感子串匹配，H2 段会连带返回其 H3 子项）。**`stepId` 存标题里的原始标识**（`step1`）。
- **技能槽（`PROMPT_SKILL_SLOT_HEADER` / `loadSkillSlotText` / `applySkillSlot`）已整体移除。**
  「技能地图」的角色并入待办槽——待办槽本就是受保护、每轮原位刷新的尾部 system 段，注解随它常驻。
  这也顺手解决了旧技能槽的两个坑（只认最近一个技能；`list`/`search` 会清空槽）。
- **`todowrite` 是整体替换**（`SessionTodoServiceImpl.replaceAll` 先删后插）：模型每次必须回传
  **完整清单**，否则漏传的条目被删。`persistTodosFromResult`（tool 结果 → 表）与
  `applyTodolistSuffix`（tool 结果 artifact → 提示词待办槽）是两个消费端，各自保留。
- **多技能无需聚合**：每个条目自带来源指针，不再依赖「当前技能是谁」。

## 技能结构规范（create/update 强制校验）

技能 `definition` 的 `promptTemplate` 必须是固定标题结构：`# <技能名>`（有且仅有一个 H1）
+ `## stepN <步骤名>`（至少一个）+ `### stepN.M <子步骤名>`（挂在同编号的 `## stepN` 之下），
且不得层级跳跃。

- **校验只有一处实现**：`common` 的 `SkillTemplateValidator`（返回 `null` 或「违规清单 + 标准模板」）。
  **唯一强制点是 service** `CapabilitySkillServiceImpl#validateDefinition`（create/update 都调它），
  失败抛 `BizException(PARAM_INVALID, …)`。
- **两条入口都经过 service**：UI 表单走 `CapabilitySkillController` → service；agent 工具
  `CapabilityBuiltinToolSkillManage` → `skillSpi.createSkill/updateSkill` → service。因此
  **不要在工具里另加一份结构校验**（工具已委托同一个 validator，避免口径漂移）。
- **强校验含 legacy**：旧 `{"prompt":...}` 定义（无标题结构）同样被拒。故**默认资源模板里的
  「示例技能」必须合规**——`SkillInitializer` 会读它开户初始化；旧 seed 已在 **V85** 幂等改写为
  含合规 `promptTemplate` 的模板（改 `agent_system_config.user.resource-template`，勿改 V62/V63）。
- 已存在的 legacy 技能副本无法再 update/append，须先规范化 —— 有意为之。
- 模板三处保持一致：后端 `SkillTemplateValidator.SKILL_TEMPLATE`、工具 `TOOL_DESCRIPTION`（引用它）、
  前端 `agent-sphere-ui/src/pages/capabilities/skill/index.tsx` 的 `SKILL_TEMPLATE`/`SAMPLE_DEFINITION`。

## run 失败原因必须落库（V87）—— 排查线上问题的前提

**`agent_run.error_message` 是「这个 run 为什么挂」的唯一现场，缺了它就只能靠猜。**
此前 `RuntimeEventListener#handleRunLifecycle` 的 FAILED 分支只写 status、把事件里的
`errorMessage` 丢弃，而表上又没有对应列 —— 线上整批 run 被模型端打挂后**不留任何原因**。

- **唯一收口点是 `RuntimeEventListener#handleRunLifecycle` 的 `case FAILED`。** 所有 FAILED 发布方
  （`SessionRunner` 单轮失败 / `RuntimeOrchestrator` 提交期失败 / `OrphanRunSweeper` /
  `ChatRuntimeService#failStuckWait`）都已经把原因放进事件，不要在各自实现里另写一遍。
- **`RunConverter` 是手写的，加字段必须两个方向都改**（VO→DO 与 DO→VO），漏一边不报错、只会让
  失败原因静默丢失。
- **`LLMEvent.Error` 是事件不是异常。** `KernelLlmService` 过去只在 `catch` 里置 `success=false`，
  而供应商常态是以事件回报错误 → 记录成 `success=true / error_message=NULL`，
  **这类失败在库里完全不可见**。现在内层 consumer 会把事件错误记进 `errorEventRef`，finally 合并。
  另外 catch 里的 `if (future.isCancelled()) return;` 会在赋值前提前返回，注意它对取消语义的影响。
- **排查口径**：`agent_llm_interaction_record` 里 `prompt_tokens IS NULL AND duration_ms < 1000`
  的记录 = 模型端秒回错误。`response_body = '{}'` **不是**错误信号 —— 只回 tool call 不回正文时
  响应体天然为空。

## 单轮 LLM 失败：重试预算与退避

`llmTurnMaxRetries` 默认 3（合计 4 次尝试），退避 `LLM_TURN_RETRY_BASE_MS × 2^(attempt-1)`、
封顶 `LLM_TURN_RETRY_MAX_MS`（`SessionRunner#llmTurnRetryBackoffMs`）。

- **曾经是「固定 sleep(1s) + 1 次重试」= 合计 2 次尝试，线上因此整批挂掉**：模型端会**突发性连续**
  秒回错误，实测有 run 在 9 秒内连撞 3 次即耗尽预算而 FAILED，只有一个恰好只撞 2 次的活了下来。
  固定退避撞上突发窗口会在同一秒再打一次，**把一次抖动放大成连续失败**。
- 重试门槛仍是「本轮无输出且无工具调用」（`SessionRunner` `runTurn` 内）—— 已产出部分内容或
  已发过 tool call 时不重试，避免重复副作用。
- **目前不做「可重试/不可重试」错误分类**：上下文超限（400）、鉴权失败重试纯属浪费。
  V87 落库后已能拿到真实错误文案，是后续做分类的前提。

## 等待恢复：`resumeFromWait` 的提交边界

### 后台唤醒线程必须无租户，claim 之后不得静默丢弃（线上「进入 wait 一直卡」的根因）

`PendingWaitWakeScheduler` 的线程池是**懒创建的长驻单线程**：第一次 `schedule()` 时 `new Thread`
会复制父线程的 `InheritableThreadLocal`，而 `TenantUtil` 正是它、且控制台请求会在
`AuthInterceptor` 里 `TenantUtil.start(user.getUsername())`。于是唤醒线程被永久绑定成
「JVM 启动后第一个创建 wait 的那个用户」。

`DataPermissionInterceptor` 只拦 SELECT，会给 `getById` 追加 `AND created_by=<那个用户>`：

- `claimForResume` 是 UPDATE → 成功，记录变成 `RESUMED`；
- `getById` 是 SELECT → 别的用户的记录读成 `null` → 旧实现 `log.warn("record missing")` 后 `return`；
- 记录停在 `RESUMED`，而 `listDue`/`claimForResume` 只认 `PENDING` → cron 永远捞不到；
- 只有 `WaitingRunReconciler`（无租户的 `scheduling-*` 线程、且绕过 `getById`）能救 → **每次 wait 卡 15 分钟**。

为什么平时不暴露：**任务型 run 只设 `AuthContext`、不设 `TenantUtil`**（`AgentTaskServiceImpl`），
多数重启后第一个 wait 由任务创建 → 线程租户为空 → 拦截器跳过 → 所有用户都正常。只有当某次重启后
第一个 wait 来自**控制台会话**时，线程被污染，之后所有任务型 run 全部落 15 分钟兜底。

护栏（漏一处就可能复发）：

- `PendingWaitWakeScheduler#wake`、`WaitWakeTask#sweepDueWaits`、`WaitingRunReconciler#reconcile`、
  `ChatRuntimeService#resumeFromWait` 入口统一 `TenantUtil.stop()` + `AuthContext.clear()`；
- claim 之后 `getById == null` 必须 `PendingWaitSpi#releaseClaim`（**按 id 单条原子 UPDATE** 回置
  PENDING，不做回读 —— 回读正是失败场景），不能 `return`；
- `wake` 的 catch 用 `Throwable`（`Error` 同样会把记录留在 `RESUMED`），先尽力回置再重抛；
- 兜底阈值 `reconcile-stale-minutes` 11（必须 > `MAX_WAIT_MS`=10）、间隔 `PT30S`。

`resumeFromWait` 里 **`asyncHandleUserMessage` 一旦返回就等于恢复已提交**，此后再失败也不能把 run
退回 WAITING：

- 退回 WAITING → `onResumeOutcome` 把 wait 记录回置 PENDING（`wake_at` 已过期）→ cron 10s 内捞到并
  再次 `claimForResume` → **再提交一次异步执行**，两个循环并发写同一个 run。
- 提交**之前**失败则必须回退（`rollbackToWaiting`），否则 run 卡在 RUNNING 却没有任何循环在跑，
  下次重试也会因状态不匹配被判 SKIPPED。
- **补记 tool 成功结果（`recordWaitToolSucceeded`）是 best-effort 展示事件，自己吞异常。**
  它曾与恢复共用一个 try，导致一次 timeline 写失败就把已成功的恢复降级成 ERROR。

- **`loopCapped` 只在「真截断」时置位 —— COMPLETE 分支不得置。** 走到 COMPLETE 分支说明模型
  主动交付了答案，那是一次正常完成；只有「最后一轮仍在调工具」被强收口才是截断。两者曾共用
  `markLoopCapped`，导致「恰好在最后一轮收工」被当成半成品，任务守卫
  （`AgentTaskServiceImpl`）一票否决、丢弃整份产出 —— 线上 run 1575
  （`status=empty / stopReason=POOL_EXHAUSTED`，池子跑完的完整结论）就是这样只回给业务方
  一句 `{"error":"run loop limit reached"}`。**判据要看语义（有没有被强收口），不要看位置。**
- **`isLastLoop` 留在 n-1，硬收口指令提前到 n-3。** 两者刻意不同：`isLastLoop` 是 loop cap 的
  **执行时机**；`FINAL_TURN_INSTRUCTION` 只是提示、不强制，而模型常常正好在最后一轮才收工
  （run 1575/1569 两条都在最后一轮），只给 1 轮收尾太紧 —— 要么来不及写完整，要么只能把
  「被打断」当成交付理由（run 1569 就是 `status:"INCOMPLETE"`）。故提前
  `FINAL_TURN_INSTRUCTION_LEAD`=3 轮注入。
- **硬收口文案不得声称「这是最后一轮」。** 提前注入后那句话是假的，模型可能据此以为还剩一次
  机会而继续调工具。措辞必须是「接近上限 / 剩余轮次有限」。
- **任务失败也必须让业务方看得见 run 产出。** `markTerminal` 只回 `{"error":...}` 会让业务方
  完全看不到模型干了什么；`TaskCallbackBody.remark` 此前线上 153 个任务全为空串。loop cap
  失败路径现用 `markTerminalWithRemark`（带 `replyJson(run)` + 原因文案）。

## 任务超时：业务方指定值 + 服务端下限

`effectiveTimeoutSeconds` 是**唯一 chokepoint**，同时决定任务存活闸门（`pollOnce`）与 MCP 凭证
TTL（`submit` 签发 = 超时 + 15min），因此两者天然一致，不会出现「任务还在跑、凭证先过期」。

口径是「业务方指定值与 `hri-ai.tasks.min-timeout-seconds` 取大者；未指定则回落 `max-poll-seconds`」。

- **为什么需要下限**：线上 158 个任务里 **155 个由业务方显式传了 `taskTimeoutSeconds=3600`**，
  且已固化进 `task_timeout_seconds` 列 —— **只改 `max-poll-seconds` 对它们完全无效**（只影响
  3 个该列为 NULL 的任务）。下限是唯一能在不动业务方调用代码的前提下抬高实际超时的位置。
- **实测耗时**（155 个任务）：均值 838s、最大 9818s(2h43m)，>2h 仅 2 个、>3h 为 0。取 3h(10800s)。
- **下限默认 0 = 不设下限**，未配置该键的环境行为与改动前完全一致。
- 代价要说清：业务方传 3600 却被实际执行 10800，**服务端单方面覆盖了调用方参数**。若后续
  业务方主动改传更大值，两边自然对齐，下限退化为纯兜底。

## 会话来源开关（V89）—— 按入口控制能否建会话/发消息

一个实例可以按**来源端**分别控制是否允许发起/继续会话：`allow_console_session`（主站）与
`allow_widget_session`（嵌入式 widget）。列均为 `NOT NULL`，默认 **主站 true / widget false**。

- **为什么要来源信号**：主站与 widget 调的是**同一个**接口（`POST /api/v1/instance/sessions`、
  `POST /api/v1/runtime/{sid}/chat`），令牌层面也分不出（`agent_user.token` 每用户一个）。
  故由 widget 发送 `X-Client-Surface: widget`，拦截器解析进 `SessionSurfaceHolder`。
  **这是策略信号、不是安全边界** —— 伪造它没有意义（用户本就能直接用主站）。
- **第三态 `INTERNAL` 必须放行**：`createSession` 还被任务路径调用（`AgentTaskServiceImpl`），
  `chat` 也被提炼轮调用。这些是服务端内部调用、不经过拦截器，holder 未设置 → 按 `INTERNAL` 放行。
  漏了这一态，任务会建不了自己的会话。
- **判定只有一处**：`SessionSurfacePolicy`（common），建会话与发消息共用。两处各写一遍真值表迟早漂移，
  而漂移的表现是「某一端悄悄绕过开关」。
- **拦截落点**：`SessionServiceImpl.createSession` 与 `ChatRuntimeService.createRun`（`chat` 的唯一建 run 入口）。
- **ThreadLocal 清理是硬要求**：`AuthInterceptor.afterCompletion` 必须 `clear()` —— Tomcat 线程池会复用线程。
  同理来源必须在**鉴权通过之后**才 set：`preHandle` 提前 `return false`（401）时 Spring 不会调用
  `afterCompletion`，之前赋值会残留给下一个请求。
- **存量回填按 businessType**：带业务域的是任务专用实例（外部 API 用它跑任务），widget 置禁止；
  不带业务域的普通聊天实例保持放行。此后由管理员在实例表单上按需调整。
- 前端两侧同步：widget 读 `allowWidgetSession`（只认严格 `true`，对齐列默认 FALSE）；
  主站读 `allowConsoleSession`，关闭时禁用发送并提示。**服务端才是强约束**，前端只为体验一致。

## 新增内置工具（builtin tool）

一个内置工具 = 一个独立 maven 模块 + `@Component implements CapabilityBuiltinToolSpi`。
**参数 schema 不用手写** —— `ToolSchemaUtil.generateParamSchema(ExecuteContext.class)` 用 swagger
`ModelConverters` 从 POJO 自动生成（`wait` 那种手写 schema 是特例：它没有 SPI bean）。

- **`needConfig()` 决定「默认绑定」**：返回 `false` → 被 `BuiltinToolService#listAutoIncludeBuiltinTools`
  收录 → `ContextPreparer#resolveTools` 在**每个实例**上无条件注册，无需任何 DB 能力绑定或 seed
  （todowrite / docwrite / skillwrite / textcontains 都是这一档）。返回 `true` → 必须在实例能力里
  显式绑定（chrome / webfetch / webread；`InstanceInitializer#bindBuiltinBrowser` 就是干这个的）。
  **改成 true 不会报错，工具会从所有实例静默消失。**
- **装配有 4 处，漏一处就静默失效**（无编译错误）：
  1. `BuiltinToolEnum` 加 id（当前已用到 13；12=finish，13=current_time）；
  2. `agent-sphere-capability-builtin-tool/pom.xml` 加 `<module>`；
  3. `agent-sphere-bootstrap/pom.xml` 加 `<dependency>`（否则 `@Component` 不在 classpath、扫不到）；
  4. **根 `pom.xml` 的 `dependencyManagement` 加同名条目**（否则 3 里的无版本依赖解析不了）。
  工具名 = `builtin.<SimpleClassName>`，模型侧看到的是 `builtin_<id>`。
- **`@NotNull` / `@NotBlank` 只在 `parameterVerify` 里生效，而那条路径不在运行链路上**
  （`ToolExecutor` 直接调 `executeBuiltinTool`）。参数约束必须在 `execute` 里再守一道，
  否则非法入参会一路走到业务逻辑里静默产出错误结果（例：空 needle 会让 `String.contains("")` 返回 true）。

## 模型供应商策略（ProviderStrategy）

不同厂商对入参的宽容度不同，差异集中在 `agent-sphere-model-service/.../adapter/strategy/`：

- `ProviderStrategy`：`getCompany()` / `adaptRequest()` / `adaptResponse()` / `extractToolCallsFromContent()`
- `ProviderStrategyFactory`：**只按 `ModelProviderCompany.fromValue(company)` 选**，没有模型名兜底
- 落地入口：`ChatRequestAdapter.adapt(request, company)` 与 `ChatResponseAdapter`
- 现有实现：`Deepseek` / `Openai` / `Zhipu(GLM)` / `OrcaRouter` / `Qwen`

`company` 来自路由配置（`ModelRouteDrawer` 的下拉由 `GET /model/providers/companies` 动态生成，
新增枚举值会自动出现）。**新增厂商只要加枚举 + `@Component` 策略即可，前端无需改动。**

- **`ChatMessageDTO` 是类级 `@JsonInclude(NON_NULL)`，所以策略里把字段设成 `null` 是无效的** ——
  Jackson 仍会省略该键。要让某个键出现在请求体里，策略只能填**非 null 值（空串）**。
  这条直接决定了 Qwen 兼容的写法。
- **Qwen（`qwen`）必须显式选用**：Qwen3 的网关（vLLM / PAI-EAS）把 assistant 消息的
  `content`、`reasoning_content` 视为「键必须存在」，而只带 `tool_calls` 的 assistant 消息
  因为 content 为 null 会把整个键省略 → 400 `Field required`。`QwenProviderStrategy` 对这类
  消息补空串。**路由的 Company 必须设为 `qwen`**，否则会走 OpenAI 策略、修复不生效
  （刻意没做模型名前缀兜底，避免"看起来生效了其实走的是另一条策略"）。
- Qwen3 是 OpenAI 兼容接口，`thinking` / `tool_stream` 是本系统给智谱/DeepSeek 的内部字段，
  策略里剔除；SSE delta 解析与 OpenAI 一致。
- **Qwen3 还要求所有 system 消息必须在最前面**（否则 400 `System message must be at the
  beginning.`），且网关/模板**只认 `messages[0]`**，非 0 位置的 system 直接拒绝（不是忽略）。
  两类来源都要在 `QwenProviderStrategy` 里归一化：
  1. **leading system 合并**：runner 的系统提示词 + `HistoryLoader` 在「上一轮被取消」或
     「有压缩摘要」时注入到 history 最前的 system —— 必须合并成一条，否则第二条 leading
     system 仍 400。compaction summary 同样走这条路。
  2. **trailing system 降级为 user（保持原位）**：runner 为前缀缓存刻意把「待办 /
     loop 预算 / 静默追问 / 收口指令」作为尾部 system 追加；待办/追问/收口漏一个就继续 400。
     当前时间已改为 `builtin_13` 按需查询，不再注入。
  **必须新建 `ChatMessageDTO`/新列表**：request 的 messages 是 runner 列表的浅拷贝（元素共享），
  原地改 role 会污染 runner 历史。
- **reasoning 字段名因网关而异，必须双字段兼容**：vLLM / Qwen3 的 reasoning-parser 返回
  `delta.reasoning`，DeepSeek/智谱返回 `delta.reasoning_content`。只读后者会让 Qwen 的
  `reasoningLen=0`、思考正文整体丢失（线上 54 个分片实测 `reasoning_content` 出现 0 次）。
  `ModelProviderServiceImpl` 统一走 `LlmStreamUtils.firstTextual(delta, "reasoning", "reasoning_content")`
  （跳过空串，避免空 `reasoning` 遮蔽后一个字段）。请求侧 assistant 的 `reasoning_content` 补空串不动。
- **`LLMEvent.Error` 携带 `httpStatus` + `retryable`**：`agent_llm_interaction_record.http_status`
  由此落库（正常结束记 200）。4xx（408/429 除外）是终态错误，`SessionRunner` 不再做同轮重试
  —— 否则一次 400 会被放大成 4 次（线上实测）。`KernelLlmService` 在 finally 里序列化
  requestBody，记录的是**策略改写后实际发出的请求**。

## run 的结束判据：沉默不是完成（L0/L1/L2）

旧循环把「模型没有调用工具」当作完成 —— 这是个**二义信号**：既可能是做完了，也可能是模型只说了几句
就放弃。后者会被记成成功（静默失败）。现在的判据是三分支：

| 本轮输出 | 判定 |
|---|---|
| 含 `finish` 工具调用 | 完成（用声明的 `summary` 作为回复） |
| 有其它工具调用 | 继续 |
| 完全没有工具调用（且**已用过工具**） | 异常：追问，连续超限判 FAILED |
| 完全没有工具调用（**从未用过工具**，纯聊天） | 仍按完成 —— 保住问答体验 |

**L0 · `finish_reason` 必须透传。** `ModelProviderServiceImpl` 过去把流结束的终止原因硬编码成 `stop`，
于是 `length`（被 max_tokens 截断）与 `content_filter`（被安全策略拦截）跟「主动说完」无法区分，
半截答案被标 COMPLETED。现在保留真实原因（`FinishReason`，model-dtvo），`SessionRunner.runTurn`
在**没有工具调用**时把 `length`/`content_filter` 判为该轮 ERROR；有工具调用则照常继续
（工具参数已完整或本就不可用）。

**L1 · `finish` 是 agentic run 的唯一合法结束方式。** 形态照 `wait`/`ask_clarification`：
`FinishConstants` + 手写 schema + `ContextPreparer` 注册 + `ToolExecutor` 兜底拦截，**无 SPI bean、无 DB seed**。

- 注册条件 `noClarification || capabilities 非空`：纯聊天实例不下发，避免模型用 terse summary 顶替正常回答。
- `SessionRunner` 在**提交 fibers 之前**短路：只处理 `finish`，同轮其它工具**不执行**；但它们的
  PENDING 记录已由 `runTurn` 的 PENDING 事件创建，必须各补一条 FAILED 关闭，否则 timeline 永久停在 PENDING。
- 该分支必须先于 `isLastLoop` 的 `markLoopCapped` —— 最后一轮调 `finish` 是干净完成，不能被标
  `loopCapped`（否则任务守卫 `AgentTaskServiceImpl` 判 task FAILED，把完整产出丢掉）。
- `finish(status=failed|blocked)` **仍置 run COMPLETED**，状态只体现在 finish 的工具记录与 summary 里，
  保住任务的结构化提炼链路。
- **子 Agent 不接 `finish`**（`SessionSubRunner#filterTools` 剔除），收口判定只在主循环。
- **`finish.summary` 必须作为助手正文可见，否则界面空白。** 答案在 tool_calls 参数里，
  LLM 轮本身 content 为空；两层都要处理：
  1. 实时：`SessionRunner` 的 `LLMEvent.ToolCall` 分支在 `contentRef` 为空时把 summary 作为
     `CONTENT_TOKEN` 推出（必须在 `closeAssistantTurn` 之前；正文已流式输出时不重复发）。
  2. 持久化：时间线 ASSISTANT 行按 `agent_llm_interaction_record.reply_content` 渲染，
     `FinishReplyPersistListener` 在 run COMPLETED 时回填「最新一条且 reply 为空」的 CHAT_REPLY
     （只写 reply_content，不覆盖已有正文）。
- **`finish` 工具行对用户隐藏**（内部协议工具）：时间线 TOOL 行带 `toolName`，
  主站 `TimelineList` 与 widget `WidgetTimeline` 两端都过滤 `builtin_12`；工具记录仍保留供审计。

**L2 · 静默追问。** `toolsEverExecuted` 从**已持久化的 `loopCount`** 起步（`loopCount` 只在工具轮自增，
所以跨挂起恢复依然成立）。沉默时追加一条尾部 system 提示（`SILENT_TURN_NUDGE_PREFIX` marker，
每轮**替换而非累积**），超过 `MAX_SILENT_TURNS` 判 FAILED。

**硬收口文案必须豁免 `finish`。** `FINAL_TURN_INSTRUCTION` 现在写的是「除 `finish` 外不得再调用任何工具」——
否则最后几轮会被禁止走唯一合法的结束路径。

**压缩估算按内容类型分开（`CompactionService`）。** 请求体 = messages + tools + 输出，且必须计入工具
schema——线上 32k 路由（`max_input_tokens=32000`、tools≈11.7k）在 messages 估算 19,953 时未压缩，
实际请求 32,852 token 直接 400。现在：
- 中文正文 `chars / 1.8`（`TOKEN_ESTIMATE_DIVISOR`）；工具 schema / JSON `chars / 3.5`
  （`JSON_TOKEN_ESTIMATE_DIVISOR`，纯 ASCII 不能按中文密度估）；
- 图片 part 每张按 1,000 token 计（base64 不解析）；
- 总估算 `(messages + tools) × 1.1`。
`max_input_tokens` 为 NULL 时回落 1M 并**每个路由只告警一次**。

**输入预算必须给实际输出额度让位。** `LlmRequestConfigurer.resolveMaxOutputTokens` 取合并后的
sampling config（实例优先、全局兜底）；有值时预算 = `max_input - output_cap - margin`
（margin = max(512, 5%×max_input)），没有输出额度才回落 `budget-ratio`。下限为 `max_input × 25%`，
避免异常配置把预算压到不可用。**不要再假设固定 0.7×max_input 就够**。

**压缩不可能成功时必须跳过，而不是重试到死。** 主 system prompt 与工具 schema 是每轮固定开销，
压缩只能压历史。先算固定开销 `leading system + tools JSON/3.5`：一旦它**已超 `max_input_tokens` 硬上限**
（如测试路由 `max_input_tokens=20000`，而 system+tools 估算 26k），压缩永远不可能成功。
`shouldCompact` 此时直接返回 `false`（每路由只 WARN 一次），让请求带超上下文发出；模型接受就继续，
拒绝就是 400 → ERROR 终态。**门槛必须是硬上限而不是 0.7×budget** —— 后者会把「工具占大头但真实
仍可容纳」的 32k 路由永久关闭压缩，历史继续涨到真溢出。

**压缩只处理已结束的历史，且游标只推进到「真正写进摘要」的 run。**
- 候选 = 游标之后、状态 ∈ {COMPLETED, FAILED, CANCELLED}、且不是当前 run 的 run。
  RUNNING / WAITING / AWAITING_USER 由内存消息承载，不进入摘要候选。
- `buildCompactInput` 按从旧到新**连续**纳入，直到压缩输入预算（`max_input × 0.5`）用尽；
  若第一条就超限，对它做字段级/整体截断并显式标记 `[truncated]` 后停止——**绝不跳过它去压缩更新的 run**。
- 游标 = 实际纳入的最后一条 run；空 run 可以安全推进。`estimateRunTokens` 已删除：
  渲染与估算共用 `renderRun`（用户/助手正文各截 2,000 字符、工具参数 500、工具结果 500），
  避免「估了参数却摘要不到」。
- 摘要硬上限 `SUMMARY_MAX_TOKENS_CAP=4000`，并作为压缩请求的 `max_tokens`（写在 prompt 里的
  “MUST NOT exceed” 不是硬约束）。
- compact record 是 HistoryLoader 的权威来源：先 `updateCompleted`（摘要 + 游标同一条 UPDATE），
  再 best-effort 更新 `session.summary`。`tokenCount` 记摘要 token 数。

**`COMPACTED` 必须是“有进展”才重组。** `compact()` 返回 `CompactionOutcome`
（COMPLETED / NO_HISTORY / NO_PROGRESS / FAILED）；只有 COMPLETED 才重新组装历史。
其他结果或尝试次数达 3 次后置 `skipCompaction`（透传到 `runTurn` 的 `shouldCompact` 前置条件）
+ `hasToolCalls=true` + `continue`，把当前上下文直接发出去；工具轮成功后复位。旧实现只要触发过就
反复重试，且耗尽后 `break loop` 会让 run 永远停在 RUNNING。

**压缩重组只能替换历史前缀，当前 run 的内存消息必须原样保留。**
`runInternal` 用 `currentRunStart` 标记当前 run 第一条消息的位置；压缩成功后重建
`[system + 新历史] + currentRunTail`。不要清空全部 messages 再 `historyLoader.load` ——
HistoryLoader 默认排除当前 run，工具轮会丢 assistant(tool_calls)/tool(result)/截图观察，
首轮还会把当前 user 既写进摘要又重新加一遍。

**循环出口必须有终态安全网。** `runInternal` 收敛前若 `currentRun` 仍是 `RUNNING`，一律置 FAILED
并发终态事件（`RunnerConstants.RUN_LOOP_NO_TERMINAL_MSG`）。`break` 点将来再加也不会静默留 RUNNING。

**压缩必须渲染在对话框里（分割线 + 实时输出）。** `TimelineKind.COMPACTION` 落 `agent_timeline`
（kind=compaction，正文不落行），行通过 `ref_compact_id`（V92）指向 `agent_compact_record`，
刷新/翻页时从 `summary_after` 解析正文。事件链：
`CompactionService.doCompact` 先 `createRecord`（PENDING）→ 发 `CompactionStatus.RUNNING`
（唯一 `publishId`、带 `compactRecordId`）→ `callLLM` 的每个 `TextDelta` 发
`CONTENT_TOKEN + reasoning_sub_type=compaction_output`（**不是** reasoning_token，否则会被当成
主 Agent 推理写进 reasoning buffer）→ 成功 `updateCompleted` 发 COMPLETED，失败 `updateFailed`
发 FAILED（旧实现从不发 FAILED，行会永远 RUNNING）。
- `RuntimeEventListener.handleCompaction` 是唯一落行点：RUNNING 建行并回填 `seq/kind/assistantReply`，
  前端据此本地插行、把后续 token 追加到 `content.text`；**`compaction_running` 时不得 refreshLatest**
  （timeline 合并是浅合并，会用「尚无正文」的权威行覆盖已流出的文字），只在完成/失败时刷新取摘要。
- `handleFlow` 必须最先识别 `compaction_output` 并 `return`，不能走 `stampStreamingSeq`（否则会
  建一条 assistant 行）。

**单 run 长任务：run-local 压缩（V93）。**
- 第一轮/单个 RUNNING run 没有「已完成历史」时，session 级压缩返回 `NO_HISTORY`；此时对当前 run 做 run-local 压缩：
  保留**当前 user + 最近 3 条消息**（按 tool 组边界回退，不产生孤儿 tool 消息）作为 tail，
  把 user 之后的旧 assistant/tool 消息摘要为 `[Current run summary]`（leading system）。
- 记录写 `agent_compact_record`：`run_id` 非空、`tail_json` 存 tail；**session 级 `getLatestCompleted`
  必须过滤 `run_id IS NULL`**，run-local 不推进 session 游标。
- 每次 `runInternal` 最多 2 次 run-local 压缩；仍超预算就直接发送并 WARN。
- **wait/澄清挂起时** `snapshotRunTail` 会把当前未摘要上下文（当前 user→最新消息）写回最近的 run-local
  `tail_json`；否则 resume 会使用「上次压缩时」的旧 tail，丢掉之后的回合。快照超过 256k 字符时置 null，
  回退 HistoryLoader 的 DB 重建。
- 压缩序列化/估算/tail **一律不含 reasoning**；assistant 正文必须保留（live tool 轮的 assistant 消息现在带 `content`）。
- 触发与结果日志：`Session compaction made no progress ... attempting run-local`、
  `Run compaction completed: summarizedMessages=..., tailMessages=..., summaryTokens=...`。

**Resume 必须恢复当前 run 的持久化 tool 历史 + assistant 正文。**
- `HistoryLoader` 新增 `loadResult(...)`：返回 messages + 当前 run 起点 `currentRunStart`；
  wait/clarification 恢复用 `restoreCurrentRun=true`。
- 有 run-local 记录：用 `summary_after`（leading system）+ `tail_json`，不再重放当前 run；
  session 压缩重组（`restoreCurrentRun=false`）只补 run summary 前缀，tail 由 `SessionRunner#currentRunTail` 保留。
- 无 run-local 记录：用 `agent_tool_call_record`（时间升序）恢复 assistant(tool_calls)+tool；
  从 `agent_llm_interaction_record`（CHAT_REPLY、主 Agent、按 id 升序，只 select 轻量列）补回逐轮
  `reply_content` **正文**；**不读 reasoning**。配对失败/tail 损坏/快照超限时回退普通 tool 记录。
- `SessionRunner` 初始组装必须使用 `HistoryResult.currentRunStart`；压缩重组时 tail 起点仍是原始当前 user，
  否则 wait 恢复后的 session 压缩会把 resume input 当成当前 run 起点。

**run-local 压缩与 resume 的上下文边界（run 2294 复盘）。**
- `HistoryResult.currentRunStart` 是相对 **history 列表**的下标；`SessionRunner` 在 history 前已加 system prompt，
  必须用 `resolveCurrentRunStart(base, history, total)`（`base + history.currentRunStart()`）。漏掉偏移会让
  run-local tail 从 system prompt 开始，当前 user/todo 丢失，压缩后的请求出现「run summary → system prompt → 无 user」。
- run-local tail 只从 user/assistant/tool **工作消息**里选，排除 time/todo/loop/silent/final/run summary 等动态 system；
  按 `tail-ratio` 预算选，并保证最新一条浏览器结果进入 tail；压缩/重组后由 `refreshTodolistSlot` 从 DB 重新注入 todo 槽。
- 压缩调用必须 `thinking=disabled`（`callLLM` 在 `applyGlobal` 后覆盖）：全局 thinking=true 会把 4k 输出预算耗在
  reasoning 上，线上出现 completion=4000、reasoning 5–11k、content 为空（记录 FAILED）。
- 压缩策略：`target-ratio=0.55`、`input-ratio=0.35`、`tail-ratio=0.25`、`summary-max-tokens=4000`、
  `cooldown-turns=4`、`max-per-run=12`；fixed+tools+summary 已超 target 时返回 NO_PROGRESS，不写成功记录。
- live 工具结果预算：`LiveToolResultPolicy` 只投影**请求副本**，不修改 canonical `messages`；canonical 保留完整
  工具结果，run-local 压缩/挂起快照才能看到原文。非浏览器单条 4k、总 50k、最近 2 条保留；浏览器结果
  默认保留全文，仅单条超过 `live-browser-full-max-chars`（默认 20k 字符，大页面）时替换为带 `callId`
  的引用，不做 keepArrays 摘要、不设浏览器总预算。
- 模型可用 `builtin_14`（`read_tool_result`，`needConfig=false` 默认内置）按 `callId` 分页回读历史
  artifact（`offset`/`maxChars`，单次上限 8k）。`HistoryLoader` 回退重建时浏览器结果按同一阈值恢复全文，
  超阈值输出带 `callId` 的引用。
- 时间不再注入 messages；需要时模型调用 `builtin_13`（`CURRENT_TIME`，Asia/Shanghai，返回
  time/timezone/epochMillis/weekday）。`builtin_13` 是 `needConfig=false` 的自动包含工具。
- builtin_5（Chrome）description/schema 与 `PROMPT_TOOLS_FOOTER` 只做文案/描述精简；**不改变工具集合、
  needConfig、绑定关系**。

**压缩摘要必须完整，截断摘要不得落库。** 线上 session 860 的 compact record id=49 就是被
`max_tokens=2000` 截断的摘要（`finish_reason=length`、`completion_tokens=2000`，结尾停在
`{"detail":"Not`），但旧代码只判断 `isBlank`，把残缺文本写成 COMPLETED 并作为后续所有 run 的
`[Conversation summary]`，还把它作为下一次压缩的 `existingSummary`，形成 id 40–49 的连续截断链。

- 摘要上限路由自适应：`summaryCap = max(512, min(summary-max-tokens(4000), maxInputTokens × summary-max-ratio(0.12)))`；
  再与 `maxOutputTokens × 2/3`、输入 token 的 2/3 取 min。
- `callLLM` 必须捕获 `LLMEvent.Finish` / `LLMEvent.Error`，返回 finishReason/error；`doCompact` /
  `doCompactRun` 仅在 `stop`（或无 finish 但结构完整）且 `SummarySanitizer.isComplete` 时写 COMPLETED。
  `length`/`content_filter`/异常 → 用绝对上限重试一次；仍不完整 → FAILED，**不更新 session.summary、
  不推进 compacted_upto_run_id**，保留旧摘要/游标。
- `SummarySanitizer` 只做 `isComplete`（代码围栏奇偶、`{}`/`[]` 平衡、结尾字符），**不做读取时 sanitize**；
  `HistoryLoader` 原样使用旧摘要。已有残缺摘要不会被自动修复，只会在下一次完整压缩成功后覆盖。
- 摘要 prompt 必须禁止原始 JSON/代码块/长表格，并要求完整收尾；不要把“be as detailed as necessary”写回。

**`StaleRunningRunReconciler` 是 DB 层最后一道网。** 每 `stale-reconcile-interval`（默认 1m）扫
`findStaleByStatus(RUNNING, now - stale-reconcile-minutes)`（默认 30m），**且 session owner 租约不存在**
才动手（活副本每 60s 续约，长工具/长 LLM 调用不会被误杀）：置 FAILED + 发终态事件；若 session state
仍是 RUNNING/RUNNING_WITH_PENDING 再复位并 `coordinator.wake`，IDLE 则只收口 run。入口必须
`TenantUtil.stop()` + `AuthContext.clear()`，否则 `findStaleByStatus` 会被 `created_by` 过滤。
同一处还调用 `AgentCompactRecordSpi.failStalePending`：把超过 15 分钟的 PENDING/RUNNING 压缩记录
置 FAILED，避免进程崩溃留下的压缩记录永远悬挂。

**路由的 token 上限必须在 `RuntimeValidator.toFull` 复制到 `ModelRouteFullVO`。** compaction 读的是
`KernelContext` 里那个 full VO（`RouteListBuilder.fromContext` 直接返回它），而 `ModelRouteFullVO`
的 `maxInputTokens/maxOutputTokens` 是**继承自 `ModelRouteVO`** 的——`RuntimeValidator.toFull`
漏拷过这两个字段，于是所有路由在运行时都是 null → 回落 1M → 预算 700k。线上 session 951
（instance 2 / route 4 = 20k）与 Qwen 32k 的 400 都是这个原因；`RouteListBuilder.toFull` 是对的，
只有 `RuntimeValidator.toFull` 曾经漏掉（现在已补，并有 `RuntimeValidatorTest` 锁定）。

**System prompt 不再重复工具描述，动态规则按能力注入。**
- `RunPromptBuilder.buildSystemPrompt` 的 Available tools 只列 `llmToolName`；完整 description/schema
  以请求 `tools` 为唯一权威来源（此前每个工具说明在 system 和 tools 里各出现一次）。
- clarification 规则从无条件的 `PROMPT_TOOLS_FOOTER` 拆出：注册了 `ask_clarification`
  才追加 `PROMPT_CLARIFICATION_RULES`；未注册（自主任务）追加 `PROMPT_NO_CLARIFICATION_RULE`
  （禁止提问，阻塞时走 `finish(status=blocked)`）。**不要再无条件要求模型调用不存在的工具。**
- `todowrite` 的工具回执只保留 `TODO_WRITE_ACK` 短确认；完整清单由尾部待办槽（每次从 DB artifact
  刷新）提供，避免同一份计划在 tool 消息和历史里重复。待办仍是全量替换协议，不能改成增量。

## Run activity 列表/详情

`GET /api/v1/instance/runs/{runId}/activities` 只返回 `RunActivitySummaryVO` 元数据，**不要**在列表 SQL
里 SELECT `request_body` / `response_body` / `reasoning` / `reply_content` / `arguments_json` / `artifact`。
线上 run 2299 有 246 次 LLM 交互，`request_body` 合计约 **97 MB**；旧列表页 10 条就会传输数 MB。

- 大字段按需走 `GET /api/v1/instance/runs/{runId}/activities/{activityType}/{id}?sessionId=...`
  （`activityType` = `llm_interaction` / `tool_call`）；前端 `InteractionModal` 的详情按钮异步调用。
- 列表/详情 SQL 都必须带 `run_id` + `session_id` + `delete_flag`，避免跨 run/会话读取。
- 新增活动字段时先判断是否属于列表列；默认只进详情 VO，不要回加到大字段查询。

## Conventions

- JVM default + Jackson timezone: `Asia/Shanghai` (set in `Application.java` and `application.yml`).
- Virtual threads enabled by default (`ENABLE_VIRTUAL_THREADS=true`).
- API prefix: `/api/v1/...`; SSE streaming routes contain `/stream`.
- Logging: `com.buukle` → DEBUG; `*.repository` packages and `com.buukle.agent.capability` → WARN.
- Key libs beyond Spring: langgraph4j, redisson, resilience4j, caffeine, hutool, victools, swagger.

### application.yml：缩进错了会**静默失效**

`AgentRuntimeProperties` 是**唯一**的 `@ConfigurationProperties(prefix = "buukle.agent")`；`wait.*` 那三项
（`wake-cron` / `reconcile-*`）靠 `@Value` / `@Scheduled` 直接读，不进绑定类。因此**只有这一处绑定树**。

- **曾踩过**：`web-read` / `web-fetch` / `web-read-advanced` / `web-fetch-advanced` / `js-render` 五块被误缩进
  到 `wait:` 之下（正确位置是 `tool:`）。`wait` 没有对应的绑定目标，Spring 的 `ignoreUnknownFields`
  默认 `true`，于是这些键被**静默忽略**、代码一直走类内默认值 —— 当时两者取值恰好相同，所以没有任何
  症状；一旦有人改那几个 yml 值就会「改了不生效、也不报错」。
- **加/改配置键后自查**：确认路径与绑定类字段一致（`buukle.agent.<block>.<field>`），可参考
  `CONFIG_REFERENCE.md`（仓库根，与该文件同源）。误缩进不会抛异常，只会无声失效。

## Code style

- Avoid `Map` as a method input parameter. Use typed DTOs/POJOs so signatures, validation, and refactoring stay explicit. A generic `Map<String, Object>` is only acceptable at framework boundaries (e.g. MyBatis result maps, HTTP param bags) and must be converted to a typed object at the earliest layer.
- No magic values. Reuse existing constants/enums by scope before introducing new ones: method-local → `private static final` on the class → module-wide constants class / `enum`. Only declare a new constant when no suitable one exists in the relevant scope. Never inline raw numbers or strings inside business logic.
- **No third-party brands** (see the root `AGENTS.md`): never reference the brand or domain of a third-party site we automate in code, comments, Javadoc, logs, docs or tests. Use neutral placeholders (`example.com`) or generalize the matching feature. **Applied Flyway migrations must not be edited** to scrub a seed — add a new migration instead.

## Git

GitHub flow: feature branch off `main` → PR. No CI configured — run `mvn test` before pushing.
