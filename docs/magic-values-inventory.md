# Magic Values Inventory

This document tracks hard-coded domain values found during the 2026-11 refactor. It is a
working inventory, not a complete dump of every literal. Update it when a new shared constant
or enum is introduced.

## Status values

| Literal | Current usage | Target | Action |
|---|---|---|---|
| `PENDING` | run / delivery / approval / outbound | `RunStatus`, `AgentEventDeliveryStatus`, `ToolApprovalState`, `AgentOutboundMessageStatus` | REUSE per domain |
| `RUNNING` | run / delivery / work item | `RunStatus`, `AgentEventDeliveryStatus`, `WorkItemStatus` | REUSE per domain |
| `COMPLETED` | run / work item | `RunStatus`, `WorkItemStatus` | REUSE per domain |
| `FAILED` | run / delivery / work item / outbound | domain enums | REUSE per domain |
| `CANCELLED` | run / work item | `RunStatus`, `WorkItemStatus` | REUSE per domain |
| `AWAITING_USER` | run | `RunStatus` | REUSE |
| `WAITING` | run / work item | `RunStatus`, `WorkItemStatus` | REUSE per domain |
| `ACTIVE` / `DISABLED` / `ENABLED` | user / instance / session / provider / binding | domain enums (`UserEnum`, `InstanceEnum`, `AgentChannelBindingStatus`, ...) | REUSE per domain; do not create a universal status |
| `DONE` / `COMPLETED` | work item | `WorkItemStatus` | MERGE with compatibility helper (`isTerminal`, `normalize`) |

Completed:

- `RunEnum` was removed. `RunStatus` now lives in `instance-dtvo` and is the only enum;
  `RunType` / `RunDirection` / `RunStatus.Codes` cover the former string constants.
  `EventType` moved to `common` (non-sealed) so the persisted status enum can be shared
  without inverting module dependencies.

## Roles

| Literal | Current usage | Target |
|---|---|---|
| `CHAT` | `InstanceConverter`, `InstanceServiceImpl`, `AgentInstance.instanceRole` | `InstanceRole.CHAT` |
| `GROUP_ROUTER` | `GroupRouterInitializer`, `GroupRouterScheduler`, `GroupRouterTemplateServiceImpl` | `InstanceRole.GROUP_ROUTER` |
| `ROUTER` | `SuperAgentServiceImpl`, `SessionType.ROUTER` | `InstanceRole.ROUTER` / `SessionType.ROUTER` (different concepts) |
| `WORKER` | `SuperAgentServiceImpl`, `SessionType.WORKER` | `InstanceRole.WORKER` / `SessionType.WORKER` |
| `WATCHDOG` | `SessionType.WATCHDOG` | `InstanceRole.WATCHDOG` / `SessionType.WATCHDOG` |

`InstanceRole` and `SessionType` are intentionally separate: one describes an instance's
configured role, the other describes a session's execution lane.

## Permission codes

The 70 codes used by `@RequirePermission` are centralized in
`agent-sphere-common/.../constant/PermissionCodes.java`. SQL seed data remains the
catalogue source of truth (`sys_permission.code`).

High-frequency duplicates found before the refactor:

- `super-agent:update` × 16
- `super-agent:read` × 12
- `im:conversation:read` × 10
- `capability:skill:update` × 5
- `im:message:send` × 5

## HTTP headers and API prefix

| Literal | Target |
|---|---|
| `Authorization` | `HttpHeaders.AUTHORIZATION` |
| `Bearer ` | `HttpHeaders.BEARER_PREFIX` |
| `X-Client-Surface` | `HttpHeaders.CLIENT_SURFACE` / `SessionSurfaceHolder.HEADER` |
| `/api/v1` | `ApiPrefix.V1` and composed prefixes |

The front-end must keep its own contract constants in sync. Do not import Java constants
into TypeScript; keep the values documented and tested.

## Front-end status maps

Several pages declare near-identical `STATUS_*` / `*_STATUS` records:

- `agent-sphere-ui/src/pages/tasks/index.tsx`
- `agent-sphere-ui/src/pages/super-agent/index.tsx`
- `agent-sphere-ui/src/pages/im/components/MessageList.tsx`
- `agent-sphere-ui/src/pages/admin/.../sessionCleanupRuns.tsx`
- `agent-sphere-ui/src/pages/admin/.../skillSyncRuns.tsx`
- widget timeline and chat timeline

Target: shared `src/constants/status.ts` (UI) and `src/status.ts` (widget) with
`{ color, labelKey }` entries. Do not merge unrelated domains into one giant map; keep
`RUN`, `WORK_ITEM`, `AGENT`, `TASK`, `TOOL_CALL` groups.

## Progress (2026-11 refactor)

Implemented:

- `HttpHeaders` + `ApiPrefix` + `ApiPaths` + `PermissionCodes` in `agent-sphere-common`.
- All `*Controller` mappings use `ApiPrefix`; all `@RequirePermission` values use
  `PermissionCodes` (zero literal annotations remain).
- `ApiPrefixScanTest` guards main Java against new hard-coded `/api/v1`.
- `InstanceRole` enum added; instance/superagent role literals migrated.
- `SessionSurfaceHolder.HEADER` now delegates to `HttpHeaders.CLIENT_SURFACE`.
- `AuthInterceptor`, `LlmApiConstants` and `JinaReader` share `HttpHeaders`.
- `RunStatus` moved to `instance-dtvo`; `EventType` moved to `common` (non-sealed);
  `RunEnum` removed and replaced by `RunType` / `RunDirection` / `RunStatus.Codes`.
- `ErrorCode` exposes `namespace()`; `ErrorResponse` / `BizException` carry
  `errorNamespace`; all 11 error enums declare their namespace.
- Front-end `errors.<namespace>.<code>` locale files added; `localizeApiError` is used by
  the global request error handler and direct auth/chat catch blocks.
- `WorkItemStatus` exposes `isTerminal`, `isInFlight` and `normalizeTerminal`;
  `WorkerTerminalListener` now reports the canonical `DONE` for completed workers.
- Front-end `API_BASE`, `API_PATHS`, `PERM`, `status.ts` and `pagination.ts` constants added;
  service clients, auth calls, route access checks and table status metadata use them.

Kept intentionally:

- `SessionType` and `InstanceRole`: session lane vs configured instance role are different
  concepts even though some names overlap.
- `ACTIVE` per domain: user / instance / session / provider lifecycles differ.
- `ChatRuntimeService` approval parsing still accepts Chinese words (`允许` / `确认`) in
  addition to English, because users may type them.
- SQL migration comments and seed data remain Chinese and are frozen by Flyway checksums;
  see `docs/migration-comments-en.md`.

## Keep list

The following are intentionally not extracted:

- OpenAPI/JSON Schema field names and JSON payload keys.
- CSS colors, sizes and animation values.
- One-off test fixtures.
- Flyway migration literals (`V1`...`V149`).
- Domain-specific `ACTIVE` values that share a spelling but not a lifecycle.
- `null` / `0` / `1` used for indexing, arithmetic or optional flags.
