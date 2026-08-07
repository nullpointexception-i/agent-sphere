package com.buukle.agent.tasks.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.instance.dtvo.dto.CreateSessionDTO;
import com.buukle.agent.instance.dtvo.dto.SendMessageDTO;
import com.buukle.agent.instance.dtvo.enums.InstanceEnum;
import com.buukle.agent.instance.dtvo.vo.InstanceVO;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.spi.InstanceSpi;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.runtime.orchestration.dtvo.vo.ChatMessageResponseVO;
import com.buukle.agent.runtime.orchestration.service.ChatRuntimeService;
import com.buukle.agent.tasks.domain.AgentTask;
import com.buukle.agent.tasks.dtvo.CreateTaskDTO;
import com.buukle.agent.tasks.dtvo.TaskVO;
import com.buukle.agent.tasks.dtvo.enums.TaskEnum;
import com.buukle.agent.tasks.repository.AgentTaskMapper;
import com.buukle.agent.tasks.service.AgentTaskService;
import com.buukle.agent.tasks.service.TaskCallbackService;
import com.buukle.agent.util.json.JsonUtils;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentTaskServiceImpl implements AgentTaskService {

    private static final long MAX_POLL_SECONDS = 30 * 60; // 30 分钟兜底
    private static final int MAX_TITLE_LENGTH = 60;

    private final AgentTaskMapper taskMapper;
    private final InstanceSpi instanceSpi;
    private final SessionSpi sessionSpi;
    private final RunSpi runSpi;
    private final ChatRuntimeService chatRuntimeService;
    private final TaskCallbackService taskCallbackService;

    @Value("${hri-ai.tasks.poll-interval:2s}")
    private Duration pollInterval;

    private final ScheduledExecutorService pollScheduler = Executors.newScheduledThreadPool(2);
    private final Map<Long, ScheduledFuture<?>> pollFutures = new ConcurrentHashMap<>();

    @Override
    public TaskVO submit(CreateTaskDTO dto) {
        InstanceVO instance = resolveInstance(dto.getInstanceId());
        AgentTask task = new AgentTask();
        task.setGoal(dto.getGoal());
        task.setContextJson(dto.getContext() == null ? null : JsonUtils.toJson(dto.getContext()));
        task.setExpectedOutputJson(dto.getExpectedOutput() == null ? null : JsonUtils.toJson(dto.getExpectedOutput()));
        task.setConfig(dto.getConfig() == null ? null : JsonUtils.toJson(dto.getConfig()));
        task.setCallbackUrl(dto.getCallbackUrl());
        task.setCreatedBy(instance.getCreatedBy());
        task.setInstanceId(instance.getId());
        task.setStatus(TaskEnum.STATUS_QUEUED);
        taskMapper.insert(task);

        try {
            CreateSessionDTO sessionDTO = new CreateSessionDTO();
            sessionDTO.setAgentInstanceId(instance.getId());
            sessionDTO.setTitle(titleOf(dto.getGoal()));
            SessionVO session = sessionSpi.createSession(sessionDTO);

            SendMessageDTO message = new SendMessageDTO();
            message.setMessage(dto.getGoal());
            ChatMessageResponseVO resp = chatRuntimeService.chat(session.getId(), message);

            task.setSessionId(session.getId());
            task.setRunId(resp.getRunId());
            task.setStatus(TaskEnum.STATUS_RUNNING);
            taskMapper.updateById(task);

            schedulePoll(task);
        } catch (Exception e) {
            log.warn("Task {} submit failed: {}", task.getId(), e.getMessage());
            markTaskFailed(task.getId(), e.getMessage());
            throw e;
        }
        return toVO(task);
    }

    @Override
    public TaskVO get(Long id) {
        AgentTask task = requireTask(id);
        return toVO(task);
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
    public void stop(Long id) {
        AgentTask task = requireTask(id);
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
        ScheduledFuture<?> poll = pollFutures.remove(id);
        if (poll != null) {
            poll.cancel(false);
        }
        transitionToTerminal(task, TaskEnum.STATUS_CANCELLED, task.getResultJson());
    }

    private InstanceVO resolveInstance(Long callerProvided) {
        if (callerProvided != null) {
            InstanceVO instance = instanceSpi.getInstance(callerProvided);
            if (instance == null) {
                throw new BizException(CommonErrorCode.PARAM_INVALID, "指定的 instanceId 不存在: " + callerProvided);
            }
            return instance;
        }
        return instanceSpi.listInstances(null, null, null).stream()
                .filter(i -> InstanceEnum.STATUS_ENABLED.equals(i.getStatus()))
                .findFirst()
                .orElseThrow(() -> new BizException(CommonErrorCode.RESOURCE_NOT_FOUND,
                        "未找到可用的 Agent 实例，请指定 instanceId"));
    }

    private void schedulePoll(AgentTask task) {
        final Long taskId = task.getId();
        final Long runId = task.getRunId();
        final long start = System.currentTimeMillis();

        ScheduledFuture<?> future = pollScheduler.scheduleWithFixedDelay(() -> {
            try {
                if (System.currentTimeMillis() - start > MAX_POLL_SECONDS * 1000) {
                    markTerminal(taskId, TaskEnum.STATUS_FAILED,
                            JsonUtils.toJson(Map.of("error", "task poll timeout")));
                    throw new StopPolling();
                }
                RunVO run = runSpi.getRun(runId);
                if (run == null) {
                    markTerminal(taskId, TaskEnum.STATUS_FAILED,
                            JsonUtils.toJson(Map.of("error", "run not found")));
                    throw new StopPolling();
                }
                String rs = run.getStatus();
                if (TaskEnum.STATUS_COMPLETED.equals(rs)) {
                    markTerminal(taskId, TaskEnum.STATUS_COMPLETED, replyJson(run));
                    throw new StopPolling();
                }
                if (TaskEnum.STATUS_FAILED.equals(rs)) {
                    markTerminal(taskId, TaskEnum.STATUS_FAILED, replyJson(run));
                    throw new StopPolling();
                }
                if (TaskEnum.STATUS_CANCELLED.equals(rs)) {
                    markTerminal(taskId, TaskEnum.STATUS_CANCELLED, replyJson(run));
                    throw new StopPolling();
                }
            } catch (StopPolling sp) {
                ScheduledFuture<?> poll = pollFutures.remove(taskId);
                if (poll != null) {
                    poll.cancel(false);
                }
            } catch (Exception e) {
                log.warn("Poll task {} failed: {}", taskId, e.getMessage());
            }
        }, pollInterval.toMillis(), pollInterval.toMillis(), TimeUnit.MILLISECONDS);
        pollFutures.put(taskId, future);
        log.info("Task {} scheduled for polling run {}", taskId, runId);
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
        task.setStatus(status);
        task.setResultJson(resultJson);
        taskMapper.updateById(task);
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

    @PreDestroy
    public void shutdown() {
        pollScheduler.shutdownNow();
    }

    private static final class StopPolling extends RuntimeException {
    }
}
