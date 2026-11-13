package com.buukle.agent.application.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.buukle.agent.application.domain.BizApplicationRun;
import com.buukle.agent.application.dtvo.ApplicationRunVO;
import com.buukle.agent.application.dtvo.ApplicationSceneVO;
import com.buukle.agent.application.dtvo.ApplicationVO;
import com.buukle.agent.application.dtvo.CreateApplicationRunDTO;
import com.buukle.agent.application.dtvo.enums.ApplicationRunStatus;
import com.buukle.agent.application.dtvo.enums.ApplicationTriggerType;
import com.buukle.agent.application.repository.BizApplicationRunMapper;
import com.buukle.agent.application.service.ApplicationRunService;
import com.buukle.agent.application.service.ApplicationService;
import com.buukle.agent.common.constant.RunMode;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.LocaleContext;
import com.buukle.agent.common.context.TenantContext;
import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.metrics.AgentMetrics;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.common.util.PageResult;
import com.buukle.agent.superagent.spi.EntityAgentDirectorySpi;
import com.buukle.agent.superagent.spi.EntityTypes;
import com.buukle.agent.superagent.spi.WorkItemCreateCommand;
import com.buukle.agent.superagent.spi.WorkItemDispatchSpi;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ApplicationRunServiceImpl implements ApplicationRunService {

    private static final Set<String> TERMINAL = Set.of(
            ApplicationRunStatus.SUCCEEDED,
            ApplicationRunStatus.FAILED,
            ApplicationRunStatus.CANCELLED);
    private static final Set<String> TRIGGERS = Set.of(
            ApplicationTriggerType.MANUAL, ApplicationTriggerType.CRON,
            ApplicationTriggerType.EVENT, ApplicationTriggerType.PAGE);
    private static final String REQUEST_SOURCE = "APPLICATION_RUN";

    private final BizApplicationRunMapper runMapper;
    private final ApplicationService applicationService;
    private final ObjectProvider<WorkItemDispatchSpi> dispatchPortProvider;
    private final ObjectProvider<EntityAgentDirectorySpi> entityDirectoryProvider;
    private final AgentMetrics metrics;

    @Override
    @Transactional
    public ApplicationRunVO trigger(Long sceneId, CreateApplicationRunDTO dto) {
        Long userId = currentUser();
        ApplicationSceneVO scene = applicationService.scene(sceneId);
        ApplicationVO app = applicationService.detail(scene.getApplicationId());
        Long agentId = resolveAgent(scene, userId);
        String runMode = normalizeRunMode(dto.getRunMode());
        String triggerType = normalizeTrigger(dto.getTriggerType());
        String idempotencyKey = idempotencyKey(app.getId(), sceneId, userId, dto.getClientRequestId());

        BizApplicationRun existing = runMapper.selectOne(new LambdaQueryWrapper<BizApplicationRun>()
                .eq(BizApplicationRun::getIdempotencyKey, idempotencyKey)
                .last("limit 1"));
        if (existing != null) {
            return toVO(existing);
        }

        BizApplicationRun run = new BizApplicationRun();
        run.setApplicationId(app.getId());
        run.setSceneId(sceneId);
        run.setTriggerType(triggerType);
        run.setTriggerKey(dto.getTriggerKey());
        run.setIdempotencyKey(idempotencyKey);
        run.setRequesterUserId(userId);
        run.setRunMode(runMode);
        run.setOutputLocale(LocaleContext.get());
        run.setStatus(ApplicationRunStatus.QUEUED);
        run.setAttempt(0);
        run.setTenantId(currentTenant());
        runMapper.insert(run);

        WorkItemDispatchSpi dispatch = dispatchPortProvider.getIfAvailable();
        if (dispatch == null) {
            throw new BizException(CommonErrorCode.SERVICE_UNAVAILABLE, "work-item dispatch unavailable");
        }
        Long workItemId = dispatch.dispatch(new WorkItemCreateCommand(
                run.getTenantId(),
                userId,
                agentId,
                null,
                app.getName() + " / " + (scene.getName() == null ? scene.getSceneKey() : scene.getName()),
                buildGoal(app, scene, dto),
                100,
                runMode,
                REQUEST_SOURCE,
                "application-run:" + run.getId(),
                idempotencyKey,
                EntityTypes.APPLICATION_SCENE,
                sceneId,
                sceneId));
        run.setWorkItemId(workItemId);
        runMapper.updateById(run);
        metrics.increment(AgentMetrics.APPLICATION_RUN_TRIGGERED,
                AgentMetrics.TAG_STATUS, ApplicationRunStatus.QUEUED);
        return toVO(run);
    }

    @Override
    @Transactional
    public ApplicationRunVO triggerCron(Long sceneId, Long runAsUserId,
                                        LocalDateTime scheduledAt) {
        ApplicationSceneVO scene = applicationService.sceneSystem(sceneId);
        ApplicationVO app = applicationService.detailSystem(scene.getApplicationId());
        Long agentId = resolveAgent(scene, runAsUserId);
        String idempotencyKey = "app:" + app.getId() + ":scene:" + sceneId
                + ":cron:" + scheduledAt;
        BizApplicationRun existing = runMapper.selectOne(new LambdaQueryWrapper<BizApplicationRun>()
                .eq(BizApplicationRun::getIdempotencyKey, idempotencyKey)
                .last("limit 1"));
        if (existing != null) {
            return toVO(existing);
        }
        BizApplicationRun run = new BizApplicationRun();
        run.setApplicationId(app.getId());
        run.setSceneId(sceneId);
        run.setTriggerType(ApplicationTriggerType.CRON);
        run.setTriggerKey(scheduledAt.toString());
        run.setIdempotencyKey(idempotencyKey);
        run.setRequesterUserId(runAsUserId);
        run.setRunMode(RunMode.READ_ONLY.name());
        run.setOutputLocale(app.getDefaultLocale());
        run.setStatus(ApplicationRunStatus.QUEUED);
        run.setAttempt(0);
        run.setTenantId(scene.getTenantId());
        runMapper.insert(run);

        WorkItemDispatchSpi dispatch = dispatchPortProvider.getIfAvailable();
        if (dispatch == null) {
            throw new BizException(CommonErrorCode.SERVICE_UNAVAILABLE, "work-item dispatch unavailable");
        }
        Long workItemId = dispatch.dispatch(new WorkItemCreateCommand(
                scene.getTenantId(),
                runAsUserId,
                agentId,
                null,
                app.getName() + " / " + (scene.getName() == null ? scene.getSceneKey() : scene.getName()),
                "Scheduled run: " + scheduledAt,
                100,
                RunMode.READ_ONLY.name(),
                REQUEST_SOURCE,
                "application-run:" + run.getId(),
                idempotencyKey,
                EntityTypes.APPLICATION_SCENE,
                sceneId,
                sceneId));
        run.setWorkItemId(workItemId);
        runMapper.updateById(run);
        metrics.increment(AgentMetrics.APPLICATION_RUN_TRIGGERED,
                AgentMetrics.TAG_STATUS, ApplicationRunStatus.QUEUED);
        return toVO(run);
    }

    @Override
    public PageResult<ApplicationRunVO> page(Long applicationId, Long sceneId,
                                              int page, int pageSize) {
        if (applicationId != null) {
            applicationService.detail(applicationId);
        }
        LambdaQueryWrapper<BizApplicationRun> wrapper = new LambdaQueryWrapper<BizApplicationRun>()
                .eq(applicationId != null, BizApplicationRun::getApplicationId, applicationId)
                .eq(sceneId != null, BizApplicationRun::getSceneId, sceneId)
                .eq(BizApplicationRun::getTenantId, currentTenant())
                .orderByDesc(BizApplicationRun::getCreatedAt);
        Page<BizApplicationRun> result = runMapper.selectPage(Page.of(page, pageSize), wrapper);
        return PageResult.of(result.getRecords().stream().map(this::toVO).toList(),
                result.getTotal(), page, pageSize);
    }

    @Override
    public ApplicationRunVO detail(Long id) {
        return toVO(requireRun(id));
    }

    @Override
    @Transactional
    public void cancel(Long id) {
        BizApplicationRun run = requireRun(id);
        if (TERMINAL.contains(run.getStatus())) {
            return;
        }
        run.setStatus(ApplicationRunStatus.CANCELLED);
        run.setFinishedAt(LocalDateTime.now());
        runMapper.updateById(run);
        WorkItemDispatchSpi dispatch = dispatchPortProvider.getIfAvailable();
        if (dispatch != null && run.getWorkItemId() != null) {
            dispatch.cancel(run.getWorkItemId());
        }
    }

    @Override
    @Transactional
    public ApplicationRunVO resume(Long id) {
        BizApplicationRun run = requireRun(id);
        if (!ApplicationRunStatus.WAITING_APPROVAL.equals(run.getStatus())) {
            return toVO(run);
        }
        WorkItemDispatchSpi dispatch = dispatchPortProvider.getIfAvailable();
        if (dispatch == null || run.getWorkItemId() == null
                || !dispatch.retry(run.getWorkItemId())) {
            throw new BizException(CommonErrorCode.SERVICE_UNAVAILABLE,
                    "work item cannot be resumed");
        }
        int nextAttempt = (run.getAttempt() == null ? 0 : run.getAttempt()) + 1;
        runMapper.update(null, Wrappers.<BizApplicationRun>lambdaUpdate()
                .eq(BizApplicationRun::getId, id)
                .set(BizApplicationRun::getStatus, ApplicationRunStatus.QUEUED)
                .set(BizApplicationRun::getAttempt, nextAttempt)
                .set(BizApplicationRun::getErrorMessage, null)
                .set(BizApplicationRun::getFinishedAt, null));
        run.setStatus(ApplicationRunStatus.QUEUED);
        run.setAttempt(nextAttempt);
        run.setErrorMessage(null);
        run.setFinishedAt(null);
        return toVO(run);
    }

    private BizApplicationRun requireRun(Long id) {
        BizApplicationRun run = runMapper.selectById(id);
        if (run == null) {
            throw new BizException(CommonErrorCode.RESOURCE_NOT_FOUND, "run not found");
        }
        applicationService.detail(run.getApplicationId());
        return run;
    }

    private Long resolveAgent(ApplicationSceneVO scene, Long userId) {
        EntityAgentDirectorySpi directory = entityDirectoryProvider.getIfAvailable();
        if (directory == null) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "entity agent directory unavailable");
        }
        List<Long> sceneAgents = directory.listBoundAgentIds(
                EntityTypes.APPLICATION_SCENE, scene.getId(), userId);
        if (!sceneAgents.isEmpty()) {
            return sceneAgents.get(0);
        }
        List<Long> appAgents = directory.listBoundAgentIds(
                EntityTypes.APPLICATION, scene.getApplicationId(), userId);
        if (!appAgents.isEmpty()) {
            return appAgents.get(0);
        }
        throw new BizException(CommonErrorCode.PARAM_INVALID,
                "no agent bound to this application scene");
    }

    private String normalizeTrigger(String value) {
        String trigger = StringUtils.hasText(value)
                ? value.trim().toUpperCase() : ApplicationTriggerType.MANUAL;
        if (!TRIGGERS.contains(trigger)) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "invalid trigger type");
        }
        return trigger;
    }

    private String normalizeRunMode(String value) {
        if (!StringUtils.hasText(value)) {
            return RunMode.READ_ONLY.name();
        }
        String mode = value.trim().toUpperCase();
        if ("READ".equals(mode)) {
            return RunMode.READ_ONLY.name();
        }
        return RunMode.from(mode).name();
    }

    private String idempotencyKey(Long applicationId, Long sceneId, Long userId, String requestId) {
        if (StringUtils.hasText(requestId)) {
            return "app:" + applicationId + ":scene:" + sceneId + ":user:" + userId
                    + ":req:" + requestId;
        }
        return "app:" + applicationId + ":scene:" + sceneId + ":user:" + userId
                + ":req:" + UUID.randomUUID();
    }

    private String buildGoal(ApplicationVO app, ApplicationSceneVO scene, CreateApplicationRunDTO dto) {
        StringBuilder goal = new StringBuilder("Run application scene ")
                .append(app.getAppKey()).append('/').append(scene.getSceneKey());
        if (dto.getInput() != null && !dto.getInput().isEmpty()) {
            goal.append(". Input: ").append(dto.getInput());
        }
        return goal.toString();
    }

    private ApplicationRunVO toVO(BizApplicationRun run) {
        ApplicationRunVO vo = new ApplicationRunVO();
        vo.setId(run.getId());
        vo.setApplicationId(run.getApplicationId());
        vo.setSceneId(run.getSceneId());
        vo.setTriggerType(run.getTriggerType());
        vo.setTriggerKey(run.getTriggerKey());
        vo.setIdempotencyKey(run.getIdempotencyKey());
        vo.setRequesterUserId(run.getRequesterUserId());
        vo.setWorkItemId(run.getWorkItemId());
        vo.setThreadId(run.getThreadId());
        vo.setRunMode(run.getRunMode());
        vo.setOutputLocale(run.getOutputLocale());
        vo.setStatus(run.getStatus());
        vo.setAttempt(run.getAttempt());
        vo.setErrorMessage(run.getErrorMessage());
        vo.setStartedAt(run.getStartedAt());
        vo.setFinishedAt(run.getFinishedAt());
        vo.setCreatedAt(run.getCreatedAt());
        return vo;
    }

    private Long currentTenant() {
        Long tenantId = TenantContext.get();
        return tenantId == null ? 0L : tenantId;
    }

    private Long currentUser() {
        Long userId = AuthContext.getUserId();
        if (userId == null) {
            throw new BizException(CommonErrorCode.UNAUTHORIZED);
        }
        return userId;
    }
}
