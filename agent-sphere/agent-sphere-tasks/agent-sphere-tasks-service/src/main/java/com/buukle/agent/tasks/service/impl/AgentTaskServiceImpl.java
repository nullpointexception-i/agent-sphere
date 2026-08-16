package com.buukle.agent.tasks.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.TaskLoopLimitHolder;
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

    private static final long MAX_POLL_SECONDS = 30 * 60; // 30 分钟兜底
    private static final int MAX_TITLE_LENGTH = 60;
    private static final String MSG_AUTONOMOUS_HEADER = "\n【自主任务模式】这是一个后台自动执行的任务：禁止向用户提问、请求澄清或确认任何内容；信息不足时基于已有上下文做合理假设并继续完成。\n";
    private static final String MSG_TASK_CONFIG_HEADER = "\n\n【任务配置】请严格依据以下配置执行任务：\n";
    private static final String MSG_EXPECTED_OUTPUT_HEADER = "\n\n【期望输出】请严格按以下 JSON Schema 返回最终结果（只输出符合 schema 的 JSON，不要额外说明）：\n";
    private static final String MSG_STRICT_RETURN = "\n\n请务必按上述配置完成任务，并将你的执行结果按照符合【期望输出】schema 的 JSON 返回。";
    // 结构化提炼（第二阶段）
    private static final String MSG_EXTRACT_HEADER = "\n\n【结构化提炼】请严格根据本会话上一轮的执行过程与结果，仅输出符合以下 JSON Schema 的最终结果 JSON 对象：\n";
    private static final String MSG_EXTRACT_RULES = "\n\n要求：\n1. 只输出一个 JSON 对象，不要 markdown 代码块、不要 ```json 标记、不要任何解释、标题或额外文字；\n2. 字段必须完整且类型符合 schema；\n3. 不要调用任何工具，直接输出最终结果。";
    private static final String MSG_REFINE_HEADER = "\n\n上一轮输出不符合契约校验，请严格依据以下校验错误修正后，重新输出完整且符合 schema 的 JSON 对象（同样要求：无代码块、无解释、不调用工具）：\n";
    private static final String ARTIFACT_TYPE_TASK_CONTRACT = "task_contract";
    private static final String PHASE_EXECUTE = "execute";
    private static final String PHASE_EXTRACT = "extract";
    private static final String PHASE_REFINE = "refine";

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

    @Value("${hri-ai.tasks.poll-interval:2s}")
    private Duration pollInterval;

    @Override
    public TaskVO submit(CreateTaskDTO dto, CallerAuth auth) {
        ResolvedIdentityVO identity = resolveIdentity(auth);
        InstanceVO instance = resolveInstanceByBusinessType(auth.businessType(), identity.getUsername());
        // 用解析出的调用方身份初始化当前上下文：task 关联的 session/run 归属为该用户，
        // 使浏览器插件按用户名建立的任务连接能收到执行过程中的指令投递（而非 external-service）。
        AuthContext.setUsername(identity.getUsername());
        AuthContext.setSuperAdmin(false);
        AuthContext.setPermissions(Set.of());
        AgentTask task = new AgentTask();
        task.setGoal(dto.getGoal());
        task.setContextJson(dto.getContext() == null ? null : JsonUtils.toJson(dto.getContext()));
        task.setExpectedOutputJson(dto.getExpectedOutput() == null ? null : JsonUtils.toJson(dto.getExpectedOutput()));
        task.setConfig(dto.getConfig() == null ? null : JsonUtils.toJson(dto.getConfig()));
        task.setCallbackUrl(dto.getCallbackUrl());
        task.setCreatedBy(identity.getUsername());
        task.setInstanceId(instance.getId());
        task.setStatus(TaskEnum.STATUS_QUEUED);
        taskMapper.insert(task);

        try {
            CreateSessionDTO sessionDTO = new CreateSessionDTO();
            sessionDTO.setAgentInstanceId(instance.getId());
            sessionDTO.setTitle(titleOf(dto.getGoal()));
            SessionVO session = sessionSpi.createSession(sessionDTO);

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

            // DB 化轮询：无需本地调度，由各副本的 @Scheduled sweep 认领（见 pollSweep）
        } catch (Exception e) {
            log.warn("Task {} submit failed: {}", task.getId(), e.getMessage());
            markTaskFailed(task.getId(), e.getMessage());
            throw e;
        }
        return toVO(task);
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

    /** 增量拉取 run 的执行日志（LLM 交互 + 工具调用），按 createdAt 正序合并。 */
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

    /** run 的 LLM 交互记录总条数（供调用方增量 offset 判断）。 */
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

    /** run 的工具调用记录总条数（供调用方增量 offset 判断）。 */
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
        if (task.getSessionId() != null && task.getRunId() != null) {
            try {
                chatRuntimeService.stopRun(task.getSessionId(), task.getRunId());
            } catch (Exception e) {
                log.warn("Failed to stop run for task {}: {}", id, e.getMessage());
            }
        }
        // DB 化轮询：无需取消本地 future；条件更新保证单胜者
        transitionToTerminal(task, TaskEnum.STATUS_CANCELLED, task.getResultJson());
    }

    private InstanceVO resolveInstanceByBusinessType(String businessType, String ownerUsername) {
        if (!StringUtils.hasText(businessType)) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "businessType 不能为空");
        }
        return instanceSpi.listInstances(null, null, null).stream()
                .filter(i -> InstanceEnum.STATUS_ENABLED.equals(i.getStatus()))
                .filter(i -> businessType.equals(i.getBusinessType()))
                .filter(i -> ownerUsername == null || ownerUsername.equals(i.getCreatedBy()))
                .findFirst()
                .orElseThrow(() -> new BizException(CommonErrorCode.FORBIDDEN, "businessType 无可用实例"));
    }

    /** 解析调用方身份：外部（code+subject）走 SSO 反查，内部（管理端）取 AuthContext；失败 → 401。 */
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

    /** 外部调用方只能访问自己的任务，且任务所属实例的 businessType 需匹配；内部（admin）由 RBAC+租户管控。 */
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

    /** 每副本 @Scheduled sweep：认领 RUNNING 且到期的任务，各执行一轮 pollOnce（DB 化，副本可互换）。 */
    @Scheduled(fixedDelayString = "${hri-ai.tasks.poll-interval:2s}")
    public void pollSweep() {
        LocalDateTime cutoff = LocalDateTime.now().minus(pollInterval);
        List<AgentTask> due = taskMapper.selectList(new LambdaQueryWrapper<AgentTask>()
                .eq(AgentTask::getStatus, TaskEnum.STATUS_RUNNING)
                .and(w -> w.isNull(AgentTask::getPolledAt).or().lt(AgentTask::getPolledAt, cutoff))
                .last("LIMIT 20"));
        for (AgentTask task : due) {
            if (!claimTask(task)) {
                continue; // 其他副本已认领
            }
            try {
                // 后台调度线程无请求上下文：以任务创建人身份执行，满足归属校验
                AuthContext.setUsername(task.getCreatedBy());
                AuthContext.setSuperAdmin(false);
                AuthContext.setPermissions(Set.of());
                pollOnce(task);
            } catch (Exception e) {
                log.warn("Poll task {} failed: {}", task.getId(), e.getMessage());
            } finally {
                AuthContext.clear();
            }
        }
    }

    /** 条件更新认领：仅当仍为 RUNNING 且 polled_at 过期时把 polled_at 前移；返回是否认领成功（单胜者）。 */
    private boolean claimTask(AgentTask task) {
        LocalDateTime cutoff = LocalDateTime.now().minus(pollInterval);
        int updated = taskMapper.update(null, new LambdaUpdateWrapper<AgentTask>()
                .eq(AgentTask::getId, task.getId())
                .eq(AgentTask::getStatus, TaskEnum.STATUS_RUNNING)
                .and(w -> w.isNull(AgentTask::getPolledAt).or().lt(AgentTask::getPolledAt, cutoff))
                .set(AgentTask::getPolledAt, LocalDateTime.now()));
        return updated > 0;
    }

    /** 单任务一轮轮询：超时/run 终态/阶段推进。 */
    private void pollOnce(AgentTask task) {
        Long taskId = task.getId();
        if (task.getStartedAt() != null
                && Duration.between(task.getStartedAt(), LocalDateTime.now()).toMillis() > MAX_POLL_SECONDS * 1000) {
            markTerminal(taskId, TaskEnum.STATUS_FAILED,
                    JsonUtils.toJson(Map.of("error", "task poll timeout")));
            return;
        }
        String phase = task.getPollPhase() == null ? PHASE_EXECUTE : task.getPollPhase();
        // 每次从 DB 读最新 runId（execute→extract→refine 会更新）
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
            return; // 仍在运行，等待下一轮
        }
        handleCompletedPhase(fresh, phase, currentRunId, run);
    }

    /**
     * 处理某阶段 run 完成。
     *
     * @return true 表示任务已终态，调用方停止轮询
     */
    private boolean handleCompletedPhase(AgentTask task, String phase, Long currentRunId, RunVO run) {
        switch (phase) {
            case PHASE_EXECUTE -> {
                // 命中循环次数上限被强收口 → 视为执行失败，不再进入提炼阶段
                if (Boolean.TRUE.equals(run.getLoopCapped())) {
                    log.warn("Task {} execute run {} hit loop cap, failing task", task.getId(), currentRunId);
                    markTerminal(task.getId(), TaskEnum.STATUS_FAILED,
                            JsonUtils.toJson(Map.of("error", "run loop limit reached")));
                    return true;
                }
                // 无契约（未配置 expectedOutput）→ 保持原有行为直接收尾
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
                // 提炼阶段失败 → 第三轮修正一次；修正阶段失败 → 不再重试，兜底回退
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

    /** 发起第二轮「结构化提炼」run（同 session）。 */
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

    /** 发起第三轮「修正」run（注入校验错误详情）。 */
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

    /** 在同 session 发起一条新 run（供提炼/修正），返回新 runId；异常返回 null。 */
    private Long fireFollowUpRun(AgentTask task, String message) {
        try {
            if (task.getSessionId() == null) {
                return null;
            }
            // 后台调度线程无请求上下文：以任务创建人身份发起，满足会话归属校验
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

    /** 提炼/修正提示：强契约（无代码块、无解释、不调用工具）+ schema；refine 时附加校验错误详情。 */
    private String buildExtractionPrompt(String schema, java.util.List<String> errors) {
        StringBuilder sb = new StringBuilder();
        sb.append(errors == null || errors.isEmpty() ? MSG_EXTRACT_HEADER : MSG_REFINE_HEADER);
        if (schema != null) {
            sb.append(schema);
        }
        if (errors != null && !errors.isEmpty()) {
            sb.append("\n\n【校验错误】\n");
            errors.forEach(e -> sb.append("- ").append(e).append('\n'));
        }
        sb.append(MSG_EXTRACT_RULES);
        return sb.toString();
    }

    /** 落库契约 artifact（resultJson 直接取 content）。 */
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

    private void transitionToTerminal(AgentTask task, String status, String resultJson) {
        if (isTerminal(task.getStatus())) {
            return;
        }
        // 条件更新保证多副本单胜者；仅获胜者触发一次 notifyTerminal
        int updated = taskMapper.update(null, new LambdaUpdateWrapper<AgentTask>()
                .eq(AgentTask::getId, task.getId())
                .in(AgentTask::getStatus, TaskEnum.STATUS_RUNNING, TaskEnum.STATUS_QUEUED)
                .set(AgentTask::getStatus, status)
                .set(AgentTask::getResultJson, resultJson));
        if (updated > 0) {
            task.setStatus(status);
            task.setResultJson(resultJson);
            taskCallbackService.notifyTerminal(task);
        }
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

    /** 任务 run 的更高轮次上限（配置缺失则回落默认 maxLoopCount）。 */
    private Integer propertiesTaskLoopLimit() {
        return runtimeProperties.getRunner().getTaskMaxLoopCount();
    }

    /** 组装任务 prompt：自主模式指令 + goal + 配置 + 期望输出 schema，禁止提问并要求严格按 schema 返回结构化结果。 */
    private String buildPrompt(CreateTaskDTO dto) {
        StringBuilder sb = new StringBuilder(MSG_AUTONOMOUS_HEADER);
        if (dto.getGoal() != null) {
            sb.append(dto.getGoal());
        }
        if (dto.getConfig() != null && !dto.getConfig().isEmpty()) {
            sb.append(MSG_TASK_CONFIG_HEADER).append(JsonUtils.toJson(dto.getConfig()));
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
            throw new BizException(CommonErrorCode.RESOURCE_NOT_FOUND, "任务不存在: " + id);
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
