package com.buukle.agent.tasks.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.buukle.agent.common.mcp.TaskMcpCredentialStore;
import com.buukle.agent.util.markdown.MarkdownRenderer;
import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.TaskLoopLimitHolder;
import com.buukle.agent.common.context.TenantContext;
import com.buukle.agent.common.context.TenantScope;
import com.buukle.agent.common.context.TenantScopeHolder;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.instance.dtvo.dto.CreateSessionDTO;
import com.buukle.agent.instance.dtvo.dto.SendMessageDTO;
import com.buukle.agent.instance.dtvo.enums.InstanceEnum;
import com.buukle.agent.instance.dtvo.vo.InstanceVO;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.dtvo.vo.AgentLlmInteractionRecordVO;
import com.buukle.agent.instance.dtvo.vo.AgentToolCallRecordVO;
import com.buukle.agent.instance.spi.AgentLlmInteractionRecordSpi;
import com.buukle.agent.instance.spi.AgentToolCallRecordSpi;
import com.buukle.agent.instance.spi.InstanceSpi;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.runtime.orchestration.dtvo.vo.ChatMessageResponseVO;
import com.buukle.agent.runtime.orchestration.service.ChatRuntimeService;
import com.buukle.agent.sso.spi.CallerAuth;
import com.buukle.agent.sso.spi.ResolvedIdentityVO;
import com.buukle.agent.sso.spi.SsoIdentitySpi;
import com.buukle.agent.tasks.domain.AgentTask;
import com.buukle.agent.tasks.domain.AgentTaskArtifact;
import com.buukle.agent.tasks.dtvo.CreateTaskDTO;
import com.buukle.agent.tasks.dtvo.TaskArtifactVO;
import com.buukle.agent.tasks.dtvo.TaskExecutionLogVO;
import com.buukle.agent.tasks.dtvo.TaskVO;
import com.buukle.agent.tasks.dtvo.enums.TaskEnum;
import com.buukle.agent.tasks.repository.AgentTaskArtifactMapper;
import com.buukle.agent.tasks.repository.AgentTaskMapper;
import com.buukle.agent.tasks.service.AgentTaskService;
import com.buukle.agent.tasks.service.TaskCallbackService;
import com.buukle.agent.tasks.service.TaskContractValidator;
import com.buukle.agent.util.json.JsonUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentTaskServiceImpl implements AgentTaskService {

    private static final int MAX_TITLE_LENGTH = 60;
    /**
     * When the task carries an mcpCredential, the credential's Redis TTL = task timeout + this margin.
     *
     * <p>It must be **greater** than the margin the caller adds when issuing the JWT (agreed 5 minutes);
     * otherwise Redis expires before the JWT — the symptom is "missing credential" rather than
     * "credential expired", which is extremely hard to diagnose.
     */
    private static final Duration CREDENTIAL_TTL_MARGIN = Duration.ofMinutes(15);
    private static final String MSG_AUTONOMOUS_HEADER = "\n[AUTONOMOUS TASK MODE] This task runs automatically in the background: do NOT ask the user questions, request clarification, or seek confirmation on anything. If information is insufficient, make reasonable assumptions from the available context and keep going.\n";
    private static final String MSG_TASK_CONFIG_HEADER = "\n\n[TASK CONFIGURATION] Execute the task strictly according to the following configuration:\n";
    private static final String MSG_EXPECTED_OUTPUT_HEADER = "\n\n[EXPECTED OUTPUT] Return the final result strictly according to the following JSON Schema (output only JSON that conforms to the schema, with no extra explanation):\n";
    private static final String MSG_STRICT_RETURN = "\n\nComplete the task strictly according to the configuration above, and return your execution result as JSON that conforms to the [EXPECTED OUTPUT] schema.";
    // Structured extraction (second phase)
    private static final String MSG_EXTRACT_HEADER = "\n\n[STRUCTURED EXTRACTION] Based strictly on the execution process and results of the previous turn in this session, output only the final result JSON object that conforms to the following JSON Schema:\n";
    private static final String MSG_EXTRACT_RULES = "\n\nRequirements:\n1. Output exactly one JSON object: no markdown code fence, no ```json marker, no explanation, heading, or extra text;\n2. Every field must be present and its type must match the schema;\n3. Do not call any tools; output the final result directly.";
    private static final String MSG_REFINE_HEADER = "\n\nThe previous output failed contract validation. Correct it strictly according to the validation errors below, then re-output a complete JSON object that conforms to the schema (same requirements: no code fence, no explanation, no tool calls):\n";
    private static final String ARTIFACT_TYPE_TASK_CONTRACT = "task_contract";
    private static final String PHASE_EXECUTE = "execute";
    private static final String PHASE_EXTRACT = "extract";
    private static final String PHASE_REFINE = "refine";
    /** Failure reason returned to the caller (sent in the callback's remark field). */
    private static final String REASON_LOOP_LIMIT = "The task reached the maximum number of execution turns and was forcibly truncated on the final turn; the model could not finalize";

    private final AgentTaskMapper taskMapper;
    private final AgentTaskArtifactMapper artifactMapper;
    private final AgentRuntimeProperties runtimeProperties;
    private final InstanceSpi instanceSpi;
    private final SessionSpi sessionSpi;
    private final RunSpi runSpi;
    private final ChatRuntimeService chatRuntimeService;
    private final TaskCallbackService taskCallbackService;
    private final SsoIdentitySpi ssoIdentitySpi;
    private final TaskContractValidator contractValidator;
    private final AgentLlmInteractionRecordSpi llmInteractionRecordSpi;
    private final AgentToolCallRecordSpi toolCallRecordSpi;
    private final TaskMcpCredentialStore taskMcpCredentialStore;

    @Value("${hri-ai.tasks.poll-interval:2s}")
    private Duration pollInterval;

    /**
     * Task timeout fallback (seconds): used when the caller does not specify {@code taskTimeoutSeconds}
     * (existing tasks also have NULL in that column).
     * When the caller specifies a value, the caller's value wins and is persisted in the
     * task_timeout_seconds column.
     */
    @Value("${hri-ai.tasks.max-poll-seconds:3600}")
    private long defaultMaxPollSeconds;

    /**
     * Task timeout lower bound (seconds): values below this are raised to the bound.
     *
     * <p>Why not just adjust max-poll-seconds: 155 of 158 production tasks had the caller
     * <b>explicitly</b> pass taskTimeoutSeconds=3600, already persisted in the {@code task_timeout_seconds}
     * column — changing the default only affects the 3 tasks where that column is NULL. The lower bound is
     * the only place that can raise the actual timeout without touching the caller's code.
     *
     * <p>Measured durations (155 tasks): mean 838s, max 9818s (2h43m); only 2 exceeded 2h and none
     * exceeded 3h. A 3h value covers every observed case. The default 0 means "no lower bound", so
     * environments without this key behave exactly as before.
     */
    @Value("${hri-ai.tasks.min-timeout-seconds:0}")
    private long minTimeoutSeconds;

    @Override
    public TaskVO submit(CreateTaskDTO dto, CallerAuth auth) {
        ResolvedIdentityVO identity = resolveIdentity(auth);
        InstanceVO instance = resolveInstanceByBusinessType(auth.businessType(), identity.getUsername());

        // The external task API has no tenant scope: without this, audit fills tenant_id=0 and
        // the runtime can never see the instance again (wait resume fails with "Agent instance not found").
        Long tenantId = instance.getTenantId() != null
                ? instance.getTenantId()
                : TenantScopeHolder.currentTenantId();
        if (tenantId == null) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "agent instance has no tenant");
        }

        // Initialize the current context with the resolved caller identity: the task's session/run is owned
        // by that user, so the browser extension's per-username task connection receives instruction
        // deliveries during execution (instead of external-service).
        AuthContext.setUsername(identity.getUsername());
        AuthContext.setSuperAdmin(false);
        AuthContext.setPermissions(Set.of());
        TenantScopeHolder.start(TenantScope.tenantOnly(tenantId, identity.getUsername()));
        TenantContext.set(tenantId);
        try {
            AgentTask task = new AgentTask();
            task.setGoal(dto.getGoal());
            task.setContextJson(dto.getContext() == null ? null : JsonUtils.toJson(dto.getContext()));
            task.setExpectedOutputJson(dto.getExpectedOutput() == null ? null : JsonUtils.toJson(dto.getExpectedOutput()));
            task.setConfig(dto.getConfig() == null ? null : JsonUtils.toJson(dto.getConfig()));
            task.setCallbackUrl(dto.getCallbackUrl());
            // Shares the same value as the MCP credential TTL issued by the caller (see CreateTaskDTO#taskTimeoutSeconds)
            task.setTaskTimeoutSeconds(dto.getTaskTimeoutSeconds());
            task.setCreatedBy(identity.getUsername());
            task.setTenantId(tenantId);
            task.setInstanceId(instance.getId());
            task.setStatus(TaskEnum.STATUS_QUEUED);
            taskMapper.insert(task);

            try {
                CreateSessionDTO sessionDTO = new CreateSessionDTO();
                sessionDTO.setAgentInstanceId(instance.getId());
                sessionDTO.setTitle(titleOf(dto.getGoal()));
                SessionVO session = sessionSpi.createSession(sessionDTO);

                // Must be registered before chat: tool calls happen while the run executes, and registering later
                // means the first MCP call cannot obtain the credential (symptom: "the first candidate was not
                // deduplicated, later ones were").
                // TTL uses the task's actual timeout + margin: a fixed value would expire before long tasks (up to 4h).
                taskMcpCredentialStore.put(session.getId(), dto.getMcpCredential(),
                        CREDENTIAL_TTL_MARGIN.plusSeconds(effectiveTimeoutSeconds(dto.getTaskTimeoutSeconds())));

                SendMessageDTO message = new SendMessageDTO();
                message.setMessage(buildPrompt(dto));
                message.setNoClarification(true);
                TaskLoopLimitHolder.set(propertiesTaskLoopLimit());
                ChatMessageResponseVO resp;
                try {
                    resp = chatRuntimeService.chat(session.getId(), message);
                } finally {
                    TaskLoopLimitHolder.clear();
                }

                task.setSessionId(session.getId());
                task.setRunId(resp.getRunId());
                task.setStatus(TaskEnum.STATUS_RUNNING);
                task.setPollPhase(PHASE_EXECUTE);
                task.setStartedAt(LocalDateTime.now());
                task.setPolledAt(LocalDateTime.now());
                taskMapper.updateById(task);

                // DB-backed polling: no local scheduling needed; each replica's @Scheduled sweep claims tasks (see pollSweep)
            } catch (Exception e) {
                log.warn("Task {} submit failed: {}", task.getId(), e.getMessage());
                markTaskFailed(task.getId(), e.getMessage());
                throw e;
            }
            return toVO(task);
        } finally {
            TenantScopeHolder.clear();
            TenantContext.clear();
            AuthContext.clear();
        }
    }

    @Override
    public TaskVO get(Long id, Integer logOffset, Integer toolLogOffset, CallerAuth auth) {
        AgentTask task = requireTask(id);
        requireTaskAccess(task, auth);
        TaskVO vo = toVO(task);
        vo.setExecutionLogs(listExecutionLogs(task, logOffset, toolLogOffset));
        vo.setExecutionLogCount((int) countLlmLogs(task));
        vo.setToolLogCount((int) countToolLogs(task));
        return vo;
    }

    /** Incrementally fetches a run's execution logs (LLM interactions + tool calls), merged in ascending createdAt order. */
    private List<TaskExecutionLogVO> listExecutionLogs(AgentTask task, Integer logOffset, Integer toolLogOffset) {
        if (task.getRunId() == null) {
            return List.of();
        }
        int llmOff = logOffset == null || logOffset < 0 ? 0 : logOffset;
        int toolOff = toolLogOffset == null || toolLogOffset < 0 ? 0 : toolLogOffset;
        try {
            List<TaskExecutionLogVO> llm = llmInteractionRecordSpi.listByRunId(task.getRunId(), llmOff, 50)
                    .stream().map(r -> toExecutionLogVO(r, task.getRunId())).toList();
            List<TaskExecutionLogVO> tools = toolCallRecordSpi.listByRunId(task.getRunId(), toolOff, 50)
                    .stream().map(r -> toExecutionLogVO(r, task.getRunId())).toList();
            List<TaskExecutionLogVO> merged = new ArrayList<>(llm);
            merged.addAll(tools);
            merged.sort(java.util.Comparator
                    .comparing(TaskExecutionLogVO::getCreatedAt,
                            java.util.Comparator.nullsLast(String::compareTo))
                    .thenComparingLong(TaskExecutionLogVO::getId));
            return merged;
        } catch (Exception e) {
            log.warn("Resolve task execution logs failed for task {}: {}", task.getId(), e.getMessage());
            return List.of();
        }
    }

    private TaskExecutionLogVO toExecutionLogVO(AgentLlmInteractionRecordVO r, Long runId) {
        TaskExecutionLogVO vo = new TaskExecutionLogVO();
        vo.setId(r.getId());
        vo.setRunId(runId);
        vo.setLogType(TaskExecutionLogVO.TYPE_LLM);
        vo.setCreatedAt(r.getCreatedAt());
        vo.setInteractionType(r.getInteractionType());
        vo.setModelName(r.getModelName());
        vo.setResponseBody(r.getResponseBody());
        vo.setSuccess(r.getSuccess());
        vo.setErrorMessage(r.getErrorMessage());
        return vo;
    }

    private TaskExecutionLogVO toExecutionLogVO(AgentToolCallRecordVO r, Long runId) {
        TaskExecutionLogVO vo = new TaskExecutionLogVO();
        vo.setId(r.getId());
        vo.setRunId(runId);
        vo.setLogType(TaskExecutionLogVO.TYPE_TOOL);
        vo.setCreatedAt(r.getCreatedAt());
        vo.setToolName(r.getToolName());
        vo.setDisplayNameCn(r.getDisplayNameCn());
        vo.setArgumentsJson(r.getArgumentsJson());
        vo.setArtifact(r.getArtifact() != null ? r.getArtifact() : r.getCompressedArtifact());
        vo.setStatus(r.getStatus());
        vo.setErrorMessage(r.getErrorMessage());
        return vo;
    }

    /** Total LLM interaction records for the run (for caller-side incremental offset checks). */
    private long countLlmLogs(AgentTask task) {
        if (task.getRunId() == null) {
            return 0;
        }
        try {
            return llmInteractionRecordSpi.countByRunId(task.getRunId());
        } catch (Exception e) {
            log.warn("Count task LLM logs failed for task {}: {}", task.getId(), e.getMessage());
            return 0;
        }
    }

    /** Total tool call records for the run (for caller-side incremental offset checks). */
    private long countToolLogs(AgentTask task) {
        if (task.getRunId() == null) {
            return 0;
        }
        try {
            return toolCallRecordSpi.countByRunId(task.getRunId());
        } catch (Exception e) {
            log.warn("Count task tool logs failed for task {}: {}", task.getId(), e.getMessage());
            return 0;
        }
    }

    @Override
    public Page<TaskVO> page(String keyword, String status, LocalDateTime startTime, LocalDateTime endTime, int page, int size) {
        LambdaQueryWrapper<AgentTask> wrapper = new LambdaQueryWrapper<AgentTask>()
                .like(StringUtils.hasText(keyword), AgentTask::getGoal, keyword)
                .eq(StringUtils.hasText(status), AgentTask::getStatus, status)
                .ge(startTime != null, AgentTask::getCreatedAt, startTime)
                .le(endTime != null, AgentTask::getCreatedAt, endTime)
                .orderByDesc(AgentTask::getId);
        var mpPage = taskMapper.selectPage(new Page<>(page, size), wrapper);
        var voPage = new Page<TaskVO>(mpPage.getCurrent(), mpPage.getSize(), mpPage.getTotal());
        voPage.setRecords(mpPage.getRecords().stream().map(this::toVO).toList());
        return voPage;
    }

    @Override
    public Page<TaskArtifactVO> pageArtifacts(String keyword, Long taskId, int page, int size) {
        LambdaQueryWrapper<AgentTaskArtifact> wrapper = new LambdaQueryWrapper<AgentTaskArtifact>()
                .eq(taskId != null, AgentTaskArtifact::getTaskId, taskId)
                .and(StringUtils.hasText(keyword), w -> w
                        .like(AgentTaskArtifact::getArtifactType, keyword)
                        .or()
                        .like(AgentTaskArtifact::getRemark, keyword)
                        .or()
                        .like(AgentTaskArtifact::getSchemaRef, keyword))
                .orderByDesc(AgentTaskArtifact::getId);
        var mpPage = artifactMapper.selectPage(new Page<>(page, size), wrapper);
        Map<Long, String> taskGoals = taskGoalMap(mpPage.getRecords());
        var voPage = new Page<TaskArtifactVO>(mpPage.getCurrent(), mpPage.getSize(), mpPage.getTotal());
        voPage.setRecords(mpPage.getRecords().stream()
                .map(a -> toArtifactVO(a, taskGoals.get(a.getTaskId())))
                .toList());
        return voPage;
    }

    @Override
    public TaskArtifactVO getArtifact(Long id) {
        AgentTaskArtifact artifact = artifactMapper.selectById(id);
        if (artifact == null) {
            throw new BizException(CommonErrorCode.RESOURCE_NOT_FOUND, "task artifact not found");
        }
        AgentTask task = taskMapper.selectById(artifact.getTaskId());
        return toArtifactVO(artifact, task == null ? null : task.getGoal());
    }

    private Map<Long, String> taskGoalMap(List<AgentTaskArtifact> artifacts) {
        List<Long> taskIds = artifacts.stream()
                .map(AgentTaskArtifact::getTaskId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (taskIds.isEmpty()) {
            return Map.of();
        }
        return taskMapper.selectBatchIds(taskIds).stream()
                .collect(Collectors.toMap(AgentTask::getId, AgentTask::getGoal, (a, b) -> a));
    }

    private TaskArtifactVO toArtifactVO(AgentTaskArtifact a, String taskGoal) {
        TaskArtifactVO vo = new TaskArtifactVO();
        vo.setId(a.getId());
        vo.setTaskId(a.getTaskId());
        vo.setTaskGoal(taskGoal);
        vo.setArtifactType(a.getArtifactType());
        vo.setContent(a.getContent());
        vo.setSchemaRef(a.getSchemaRef());
        vo.setRunId(a.getRunId());
        vo.setStatus(a.getStatus());
        vo.setRemark(a.getRemark());
        vo.setCreatedBy(a.getCreatedBy());
        vo.setCreatedAt(a.getCreatedAt() == null ? null : a.getCreatedAt().toString());
        vo.setUpdatedAt(a.getUpdatedAt() == null ? null : a.getUpdatedAt().toString());
        return vo;
    }

    @Override
    public void stop(Long id, CallerAuth auth) {
        AgentTask task = requireTask(id);
        requireTaskAccess(task, auth);
        if (!TaskEnum.STATUS_RUNNING.equals(task.getStatus()) && !TaskEnum.STATUS_QUEUED.equals(task.getStatus())) {
            return;
        }
        // The external API has no request context (AuthInterceptor sets no identity for the /api/v1/api prefix):
        // cancel the run as the task creator, otherwise ChatRuntimeService.assertSessionOwnership throws FORBIDDEN;
        // once swallowed, the Redis cancel set is never written and the run cannot be stopped.
        runAsTaskTenant(task, () -> {
            try {
                if (task.getSessionId() != null && task.getRunId() != null) {
                    chatRuntimeService.stopRun(task.getSessionId(), task.getRunId());
                }
            } catch (Exception e) {
                log.warn("Failed to stop run for task {}: {}", id, e.getMessage());
            }
        });
        // DB-backed polling: no local future to cancel; a conditional update guarantees a single winner
        transitionToTerminal(task, TaskEnum.STATUS_CANCELLED, task.getResultJson());
    }

    private InstanceVO resolveInstanceByBusinessType(String businessType, String ownerUsername) {
        if (!StringUtils.hasText(businessType)) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "businessType must not be empty");
        }
        return instanceSpi.listInstances(null, null, null).stream()
                .filter(i -> InstanceEnum.STATUS_ENABLED.equals(i.getStatus()))
                .filter(i -> businessType.equals(i.getBusinessType()))
                .filter(i -> ownerUsername == null || ownerUsername.equals(i.getCreatedBy()))
                .findFirst()
                .orElseThrow(() -> new BizException(CommonErrorCode.FORBIDDEN, "no available instance for businessType"));
    }

    /** Resolves the caller identity: external (code+subject) is looked up via SSO, internal (admin UI) uses AuthContext; failure → 401. */
    private ResolvedIdentityVO resolveIdentity(CallerAuth auth) {
        if (StringUtils.hasText(auth.code()) && StringUtils.hasText(auth.subject())) {
            ResolvedIdentityVO identity = ssoIdentitySpi.resolveByCodeSubject(auth.code(), auth.subject());
            if (identity == null) {
                throw new BizException(CommonErrorCode.UNAUTHORIZED);
            }
            return identity;
        }
        String username = AuthContext.getUsername();
        if (username == null || username.isBlank()) {
            throw new BizException(CommonErrorCode.UNAUTHORIZED);
        }
        return ResolvedIdentityVO.of(AuthContext.getUserId(), username, AuthContext.getDisplayName());
    }

    /** External callers can only access their own tasks, and the task's instance businessType must match; internal (admin) access is governed by RBAC + tenant. */
    private void requireTaskAccess(AgentTask task, CallerAuth auth) {
        boolean external = StringUtils.hasText(auth.code());
        if (!external) {
            return;
        }
        ResolvedIdentityVO identity = resolveIdentity(auth);
        if (!identity.getUsername().equals(task.getCreatedBy())) {
            throw new BizException(CommonErrorCode.FORBIDDEN);
        }
        if (StringUtils.hasText(auth.businessType()) && task.getInstanceId() != null) {
            InstanceVO instance = instanceSpi.getInstance(task.getInstanceId());
            if (instance != null && StringUtils.hasText(instance.getBusinessType())
                    && !instance.getBusinessType().equals(auth.businessType())) {
                throw new BizException(CommonErrorCode.FORBIDDEN);
            }
        }
    }

    /** Per-replica @Scheduled sweep: claims due RUNNING tasks and runs one pollOnce for each (DB-backed, replicas are interchangeable). */
    @Scheduled(fixedDelayString = "${hri-ai.tasks.poll-interval:2s}")
    public void pollSweep() {
        // Scheduler threads are reused: clear any scope inherited from the previous task before querying.
        TenantScopeHolder.clear();
        TenantContext.clear();
        AuthContext.clear();
        LocalDateTime cutoff = LocalDateTime.now().minus(pollInterval);
        List<AgentTask> due = taskMapper.selectList(new LambdaQueryWrapper<AgentTask>()
                .eq(AgentTask::getStatus, TaskEnum.STATUS_RUNNING)
                .and(w -> w.isNull(AgentTask::getPolledAt).or().lt(AgentTask::getPolledAt, cutoff))
                .last("LIMIT 20"));
        for (AgentTask task : due) {
            if (!claimTask(task)) {
                continue; // another replica already claimed it
            }
            try {
                // The background scheduler thread has no request context: run as the task creator and tenant
                // so ownership checks pass and extract/refine runs are written into the same tenant.
                runAsTaskTenant(task, () -> pollOnce(task));
            } catch (Exception e) {
                log.warn("Poll task {} failed: {}", task.getId(), e.getMessage());
            }
        }
    }

    /** Runs task-scoped work under the task's tenant; the external task API and scheduler have no request scope. */
    private void runAsTaskTenant(AgentTask task, Runnable action) {
        Long tenantId = task.getTenantId();
        String username = task.getCreatedBy();
        if (tenantId != null) {
            TenantScopeHolder.start(TenantScope.tenantOnly(tenantId, username));
            TenantContext.set(tenantId);
        }
        try {
            if (username != null && !username.isBlank()) {
                AuthContext.setUsername(username);
            }
            AuthContext.setSuperAdmin(false);
            AuthContext.setPermissions(Set.of());
            action.run();
        } finally {
            TenantScopeHolder.clear();
            TenantContext.clear();
            AuthContext.clear();
        }
    }

    /** Conditional-update claim: moves polled_at forward only when the task is still RUNNING and polled_at is stale; returns whether the claim succeeded (single winner). */
    private boolean claimTask(AgentTask task) {
        LocalDateTime cutoff = LocalDateTime.now().minus(pollInterval);
        int updated = taskMapper.update(null, new LambdaUpdateWrapper<AgentTask>()
                .eq(AgentTask::getId, task.getId())
                .eq(AgentTask::getStatus, TaskEnum.STATUS_RUNNING)
                .and(w -> w.isNull(AgentTask::getPolledAt).or().lt(AgentTask::getPolledAt, cutoff))
                .set(AgentTask::getPolledAt, LocalDateTime.now()));
        return updated > 0;
    }

    /**
     * Task timeout in seconds: the larger of the caller-provided value and the configured lower bound;
     * falls back to the configured default when unspecified.
     *
     * <p>Semantics changed: previously "caller value wins, unconditionally". A lower bound was added —
     * callers commonly pass 3600, but tasks have been measured running for 2h43m; under the old rule they
     * would be judged "task poll timeout" and their completed structured output discarded.
     *
     * <p><b>Shared downstream</b>: this method determines both the task liveness gate ({@code pollOnce})
     * and the MCP credential TTL (issued in {@code submit}), so the two are naturally consistent and a task
     * never keeps running after its credential expires.
     */
    long effectiveTimeoutSeconds(Integer taskTimeoutSeconds) {
        if (taskTimeoutSeconds == null) {
            return Math.max(defaultMaxPollSeconds, minTimeoutSeconds);
        }
        return Math.max(taskTimeoutSeconds, minTimeoutSeconds);
    }

    /** One polling round for a single task: timeout / run terminal state / phase advancement. */
    private void pollOnce(AgentTask task) {
        Long taskId = task.getId();
        long timeoutSeconds = effectiveTimeoutSeconds(task.getTaskTimeoutSeconds());
        if (task.getStartedAt() != null
                && Duration.between(task.getStartedAt(), LocalDateTime.now()).toMillis() > timeoutSeconds * 1000) {
            markTerminal(taskId, TaskEnum.STATUS_FAILED,
                    JsonUtils.toJson(Map.of("error", "task poll timeout")));
            return;
        }
        String phase = task.getPollPhase() == null ? PHASE_EXECUTE : task.getPollPhase();
        // Read the latest runId from the DB each time (execute→extract→refine updates it)
        AgentTask fresh = taskMapper.selectById(taskId);
        if (fresh == null || fresh.getRunId() == null) {
            markTerminal(taskId, TaskEnum.STATUS_FAILED,
                    JsonUtils.toJson(Map.of("error", "run not found")));
            return;
        }
        Long currentRunId = fresh.getRunId();
        RunVO run = runSpi.getRun(currentRunId);
        if (run == null) {
            markTerminal(taskId, TaskEnum.STATUS_FAILED,
                    JsonUtils.toJson(Map.of("error", "run not found")));
            return;
        }
        String rs = run.getStatus();
        if (TaskEnum.STATUS_FAILED.equals(rs) || TaskEnum.STATUS_CANCELLED.equals(rs)) {
            markTerminal(taskId, TaskEnum.STATUS_FAILED.equals(rs)
                    ? TaskEnum.STATUS_FAILED : TaskEnum.STATUS_CANCELLED, replyJson(run));
            return;
        }
        if (!TaskEnum.STATUS_COMPLETED.equals(rs)) {
            return; // still running; wait for the next round
        }
        handleCompletedPhase(fresh, phase, currentRunId, run);
    }

    /**
     * Handles completion of a phase's run.
     *
     * @return true if the task is already terminal, so the caller should stop polling
     */
    private boolean handleCompletedPhase(AgentTask task, String phase, Long currentRunId, RunVO run) {
        switch (phase) {
            case PHASE_EXECUTE -> {
                // Hit the loop cap and was <b>actually truncated</b> (still calling tools in the last round)
                // → treat it as an execution failure and do not enter the extraction phase.
                // Note that loopCapped is now only set in SessionRunner's tool-round finalization branch; the
                // model delivering an answer in the last round (COMPLETE branch) no longer sets it, otherwise a
                // normal completion would be misjudged as a partial result and its output discarded.
                if (Boolean.TRUE.equals(run.getLoopCapped())) {
                    log.warn("Task {} execute run {} hit loop cap, failing task", task.getId(), currentRunId);
                    // Must include the run's actual output: the run has usually written a structured conclusion
                    // (even a self-declared interruption with status=INCOMPLETE), and returning only {"error": ...}
                    // leaves the caller unable to see what the model did or where it got stuck — production tasks
                    // 360/362 received only an unrelated error string exactly this way.
                    markTerminalWithRemark(task.getId(), TaskEnum.STATUS_FAILED,
                            replyJson(run), REASON_LOOP_LIMIT);
                    return true;
                }
                // No contract (expectedOutput not configured) → keep the previous behavior and finish directly
                if (!StringUtils.hasText(task.getExpectedOutputJson())) {
                    markTerminal(task.getId(), TaskEnum.STATUS_COMPLETED, replyJson(run));
                    return true;
                }
                log.info("Task {} first run completed, starting contract extraction", task.getId());
                startExtraction(task, run);
                return false;
            }
            case PHASE_EXTRACT, PHASE_REFINE -> {
                String content = run.getAssistantReply();
                AgentTask fresh = taskMapper.selectById(task.getId());
                String schema = fresh == null ? null : fresh.getExpectedOutputJson();
                if (!StringUtils.hasText(content)) {
                    log.warn("Task {} {} reply empty, falling back", task.getId(), phase);
                    markTerminal(task.getId(), TaskEnum.STATUS_COMPLETED,
                            replyJson(runSpi.getRun(fresh != null && fresh.getRunId() != null ? fresh.getRunId() : currentRunId)));
                    return true;
                }
                java.util.List<String> errors = contractValidator.validate(schema, content);
                if (errors.isEmpty()) {
                    log.info("Task {} {} output passed contract validation", task.getId(), phase);
                    saveArtifact(task.getId(), phase.equals(PHASE_REFINE) ? PHASE_REFINE : PHASE_EXTRACT, content, schema, currentRunId);
                    markTerminal(task.getId(), TaskEnum.STATUS_COMPLETED, content);
                    return true;
                }
                // Extraction failed → one refine round; refine failed → no more retries, fall back
                if (phase.equals(PHASE_EXTRACT)) {
                    log.warn("Task {} extract output invalid ({}), starting refine", task.getId(), errors.size());
                    startRefine(task, content, errors);
                    return false;
                }
                log.warn("Task {} refine output still invalid ({}), falling back", task.getId(), errors.size());
                markTerminal(task.getId(), TaskEnum.STATUS_COMPLETED, JsonUtils.toJson(Map.of("reply", content)));
                return true;
            }
            default -> {
                markTerminal(task.getId(), TaskEnum.STATUS_COMPLETED, replyJson(run));
                return true;
            }
        }
    }

    /** Starts the second "structured extraction" run (same session). */
    private void startExtraction(AgentTask task, RunVO executeRun) {
        String message = buildExtractionPrompt(task.getExpectedOutputJson(), null);
        Long extractRunId = fireFollowUpRun(task, message);
        if (extractRunId == null) {
            log.warn("Task {} extraction run not created, falling back to execute reply", task.getId());
            markTerminal(task.getId(), TaskEnum.STATUS_COMPLETED, replyJson(executeRun));
            return;
        }
        task.setPollPhase(PHASE_EXTRACT);
        task.setRunId(extractRunId);
        taskMapper.updateById(task);
        log.info("Task {} extraction run {} started", task.getId(), extractRunId);
    }

    /** Starts the third "refine" run (injects validation error details). */
    private void startRefine(AgentTask task, String invalidContent, java.util.List<String> errors) {
        String message = buildExtractionPrompt(task.getExpectedOutputJson(), errors);
        Long refineRunId = fireFollowUpRun(task, message);
        if (refineRunId == null) {
            log.warn("Task {} refine run not created, falling back to invalid extract content", task.getId());
            markTerminal(task.getId(), TaskEnum.STATUS_COMPLETED, JsonUtils.toJson(Map.of("reply", invalidContent)));
            return;
        }
        task.setPollPhase(PHASE_REFINE);
        task.setRunId(refineRunId);
        taskMapper.updateById(task);
        log.info("Task {} refine run {} started", task.getId(), refineRunId);
    }

    /** Starts a new run in the same session (for extraction/refine), returning the new runId; null on error. */
    private Long fireFollowUpRun(AgentTask task, String message) {
        try {
            if (task.getSessionId() == null) {
                return null;
            }
            // The background scheduler thread has no request context: start as the task creator to satisfy session ownership checks
            com.buukle.agent.common.context.AuthContext.setUsername(task.getCreatedBy());
            com.buukle.agent.common.context.AuthContext.setSuperAdmin(false);
            SendMessageDTO send = new SendMessageDTO();
            send.setMessage(message);
            send.setNoClarification(true);
            TaskLoopLimitHolder.set(propertiesTaskLoopLimit());
            ChatMessageResponseVO resp;
            try {
                resp = chatRuntimeService.chat(task.getSessionId(), send);
            } finally {
                TaskLoopLimitHolder.clear();
            }
            return resp == null ? null : resp.getRunId();
        } catch (Exception e) {
            log.warn("Task {} follow-up run failed: {}", task.getId(), e.getMessage());
            return null;
        } finally {
            com.buukle.agent.common.context.AuthContext.clear();
        }
    }

    /** Extraction/refine prompt: strict contract (no code blocks, no explanation, no tool calls) + schema; refine also appends validation error details. */
    private String buildExtractionPrompt(String schema, java.util.List<String> errors) {
        StringBuilder sb = new StringBuilder();
        sb.append(errors == null || errors.isEmpty() ? MSG_EXTRACT_HEADER : MSG_REFINE_HEADER);
        if (schema != null) {
            sb.append(schema);
        }
        if (errors != null && !errors.isEmpty()) {
            sb.append("\n\n[VALIDATION ERRORS]\n");
            errors.forEach(e -> sb.append("- ").append(e).append('\n'));
        }
        sb.append(MSG_EXTRACT_RULES);
        return sb.toString();
    }

    /** Persists the contract artifact (resultJson takes content directly). */
    private void saveArtifact(Long taskId, String phase, String content, String schema, Long runId) {
        try {
            AgentTaskArtifact artifact = new AgentTaskArtifact();
            artifact.setTaskId(taskId);
            artifact.setArtifactType(ARTIFACT_TYPE_TASK_CONTRACT);
            artifact.setContent(content);
            artifact.setSchemaRef(schema);
            artifact.setRunId(runId);
            artifact.setRemark("phase=" + phase);
            artifactMapper.insert(artifact);
            log.info("Task {} artifact saved (phase={}, run={})", taskId, phase, runId);
        } catch (Exception e) {
            log.warn("Task {} artifact persist failed: {}", taskId, e.getMessage());
        }
    }

    private void markTaskFailed(Long taskId, String error) {
        AgentTask task = taskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        transitionToTerminal(task, TaskEnum.STATUS_FAILED,
                JsonUtils.toJson(Map.of("error", error == null ? "" : error)));
    }

    private void markTerminal(Long taskId, String status, String resultJson) {
        AgentTask task = taskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        transitionToTerminal(task, status, resultJson);
    }

    /**
     * Terminal state + human-readable reason. {@code remark} is sent to the caller with the callback
     * ({@code TaskCallbackBody.remark}); previously all 153 production tasks had an empty remark, so the
     * caller received no readable failure explanation.
     */
    private void markTerminalWithRemark(Long taskId, String status, String resultJson, String remark) {
        AgentTask task = taskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        if (isTerminal(task.getStatus())) {
            return;
        }
        // Same "conditional update guarantees a single winner across replicas" semantics as transitionToTerminal
        int updated = taskMapper.update(null, new LambdaUpdateWrapper<AgentTask>()
                .eq(AgentTask::getId, task.getId())
                .in(AgentTask::getStatus, TaskEnum.STATUS_RUNNING, TaskEnum.STATUS_QUEUED)
                .set(AgentTask::getStatus, status)
                .set(AgentTask::getRemark, remark)
                .set(AgentTask::getResultJson, resultJson));
        if (updated > 0) {
            task.setStatus(status);
            task.setRemark(remark);
            task.setResultJson(resultJson);
            afterTerminal(task);
        }
    }

    private void transitionToTerminal(AgentTask task, String status, String resultJson) {
        if (isTerminal(task.getStatus())) {
            return;
        }
        // Conditional update guarantees a single winner across replicas; only the winner triggers notifyTerminal once
        int updated = taskMapper.update(null, new LambdaUpdateWrapper<AgentTask>()
                .eq(AgentTask::getId, task.getId())
                .in(AgentTask::getStatus, TaskEnum.STATUS_RUNNING, TaskEnum.STATUS_QUEUED)
                .set(AgentTask::getStatus, status)
                .set(AgentTask::getResultJson, resultJson));
        if (updated > 0) {
            task.setStatus(status);
            task.setResultJson(resultJson);
            afterTerminal(task);
        }
    }

    /** Post-terminal cleanup: evict the credential + send the callback. Shared by transitionToTerminal / markTerminalWithRemark. */
    private void afterTerminal(AgentTask task) {
        // The task is terminal: the credential has no reason to exist, so evict it immediately instead of waiting for the TTL.
        // Placed before notifyTerminal so cleanup completes even if the callback times out or throws.
        taskMcpCredentialStore.evict(task.getSessionId());
        taskCallbackService.notifyTerminal(task);
    }

    private boolean isTerminal(String status) {
        return TaskEnum.STATUS_COMPLETED.equals(status)
                || TaskEnum.STATUS_FAILED.equals(status)
                || TaskEnum.STATUS_CANCELLED.equals(status);
    }

    private String replyJson(RunVO run) {
        String reply = run.getAssistantReply();
        return JsonUtils.toJson(Map.of("reply", reply == null ? "" : reply));
    }

    private String titleOf(String goal) {
        String title = goal == null ? "" : goal.trim();
        return title.length() > MAX_TITLE_LENGTH ? title.substring(0, MAX_TITLE_LENGTH) : title;
    }

    /** Higher loop limit for task runs (falls back to the default maxLoopCount when the config is absent). */
    private Integer propertiesTaskLoopLimit() {
        return runtimeProperties.getRunner().getTaskMaxLoopCount();
    }

    /** Assembles the task prompt: autonomous-mode instruction + goal + config + expected-output schema; forbids questions and requires strict schema-conformant structured output. */
    private String buildPrompt(CreateTaskDTO dto) {
        StringBuilder sb = new StringBuilder(MSG_AUTONOMOUS_HEADER);
        if (dto.getGoal() != null) {
            sb.append(dto.getGoal());
        }
        if (dto.getConfig() != null && !dto.getConfig().isEmpty()) {
            // Task config is rendered as Markdown rather than JSON: config holds instructions for the model to
            // execute, and JSON braces/quotes make the model read it as data; long-form rules are also harder to
            // follow as instructions.
            sb.append(MSG_TASK_CONFIG_HEADER).append(MarkdownRenderer.renderConfig(dto.getConfig()));
        }
        if (dto.getExpectedOutput() != null && !dto.getExpectedOutput().isEmpty()) {
            sb.append(MSG_EXPECTED_OUTPUT_HEADER).append(JsonUtils.toJson(dto.getExpectedOutput()));
        }
        sb.append(MSG_STRICT_RETURN);
        return sb.toString();
    }

    private AgentTask requireTask(Long id) {
        AgentTask task = taskMapper.selectById(id);
        if (task == null) {
            throw new BizException(CommonErrorCode.RESOURCE_NOT_FOUND, "Task not found: " + id);
        }
        return task;
    }

    private TaskVO toVO(AgentTask t) {
        TaskVO vo = new TaskVO();
        vo.setId(t.getId());
        vo.setGoal(t.getGoal());
        vo.setStatus(t.getStatus());
        vo.setInstanceId(t.getInstanceId());
        vo.setSessionId(t.getSessionId());
        vo.setRunId(t.getRunId());
        vo.setContextJson(t.getContextJson());
        vo.setExpectedOutputJson(t.getExpectedOutputJson());
        vo.setConfig(t.getConfig());
        vo.setResultJson(t.getResultJson());
        vo.setRemark(t.getRemark());
        vo.setCallbackUrl(t.getCallbackUrl());
        vo.setCreatedAt(t.getCreatedAt() == null ? null : t.getCreatedAt().toString());
        vo.setUpdatedAt(t.getUpdatedAt() == null ? null : t.getUpdatedAt().toString());
        return vo;
    }
}
