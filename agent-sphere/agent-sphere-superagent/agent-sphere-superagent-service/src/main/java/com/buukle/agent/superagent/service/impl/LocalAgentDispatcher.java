package com.buukle.agent.superagent.service.impl;

import com.buukle.agent.superagent.domain.AgentEvent;
import com.buukle.agent.superagent.domain.AgentEventDelivery;
import com.buukle.agent.superagent.domain.AgentLocalRuntime;
import com.buukle.agent.superagent.domain.AgentLocalTask;
import com.buukle.agent.superagent.domain.AgentLocalTaskDelivery;
import com.buukle.agent.superagent.domain.AgentSuperAgent;
import com.buukle.agent.superagent.dtvo.enums.AgentEventDeliveryStatus;
import com.buukle.agent.superagent.dtvo.enums.AgentType;
import com.buukle.agent.superagent.dtvo.enums.LocalRuntimeStatus;
import com.buukle.agent.superagent.dtvo.enums.LocalTaskStatus;
import com.buukle.agent.superagent.dtvo.vo.LocalTaskPayloadVO;
import com.buukle.agent.superagent.repository.AgentEventDeliveryMapper;
import com.buukle.agent.superagent.repository.AgentEventMapper;
import com.buukle.agent.superagent.repository.AgentLocalRuntimeMapper;
import com.buukle.agent.superagent.repository.AgentLocalTaskDeliveryMapper;
import com.buukle.agent.superagent.repository.AgentLocalTaskMapper;
import com.buukle.agent.superagent.repository.AgentSuperAgentMapper;
import com.buukle.agent.superagent.service.LocalRuntimeStreamService;
import com.buukle.agent.superagent.spi.LocalAgentDispatchCommand;
import com.buukle.agent.superagent.spi.LocalAgentDispatchPort;
import com.buukle.agent.superagent.spi.LocalAgentDispatchResult;
import com.buukle.agent.superagent.spi.LocalProjectMentionCommand;
import com.buukle.agent.superagent.spi.LocalProjectMentionDispatchPort;
import com.buukle.agent.superagent.spi.LocalProjectMentionDispatchResult;
import com.buukle.agent.superagent.spi.LocalTaskBusinessTypes;
import com.buukle.agent.util.json.JsonUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/** Converts router deliveries into a local task and wakes the owning desktop runtime. */
@Slf4j
@Service
@RequiredArgsConstructor
public class LocalAgentDispatcher implements LocalAgentDispatchPort,
        LocalProjectMentionDispatchPort {

    private static final long OFFLINE_RETRY_SECONDS = 15;
    private static final int ONLINE_TTL_SECONDS = 90;

    private final AgentSuperAgentMapper superAgentMapper;
    private final AgentLocalRuntimeMapper runtimeMapper;
    private final AgentEventMapper eventMapper;
    private final AgentEventDeliveryMapper deliveryMapper;
    private final AgentLocalTaskMapper taskMapper;
    private final AgentLocalTaskDeliveryMapper taskDeliveryMapper;
    private final LocalTaskPayloadBuilder payloadBuilder;
    private final LocalRuntimeStreamService streamService;

    @Value("${buukle.agent.super-agent.local.task-timeout-seconds:600}")
    private long taskTimeoutSeconds;
    @Value("${buukle.agent.super-agent.local.offline-ttl-seconds:300}")
    private long offlineTtlSeconds;

    @Override
    @Transactional
    public LocalAgentDispatchResult dispatch(LocalAgentDispatchCommand command) {
        if (command == null || command.agentId() == null || command.threadId() == null
                || command.deliveryIds() == null || command.deliveryIds().isEmpty()) {
            return new LocalAgentDispatchResult(false, null, "empty dispatch command");
        }

        AgentSuperAgent agent = superAgentMapper.selectById(command.agentId());
        if (agent == null || !AgentType.LOCAL.equals(agent.getAgentType())
                || agent.getLocalRuntimeId() == null) {
            failDeliveries(command.deliveryIds(), "local agent or runtime is not configured");
            return new LocalAgentDispatchResult(false, null, "local agent is not configured");
        }

        AgentLocalRuntime runtime = runtimeMapper.selectById(agent.getLocalRuntimeId());
        if (runtime == null || !LocalRuntimeStatus.ACTIVE.equals(runtime.getStatus())) {
            failDeliveries(command.deliveryIds(), "local runtime is missing or disabled");
            return new LocalAgentDispatchResult(false, null, "local runtime is unavailable");
        }

        if (!online(runtime)) {
            deferOrFail(command.deliveryIds());
            return new LocalAgentDispatchResult(false, null, "local runtime is offline");
        }

        List<AgentEventDelivery> deliveries = deliveryMapper.selectBatchIds(command.deliveryIds())
                .stream()
                .filter(row -> AgentEventDeliveryStatus.PENDING.equals(row.getStatus()))
                .filter(row -> Objects.equals(agent.getId(), row.getSuperAgentId()))
                .toList();
        if (deliveries.isEmpty()) {
            return new LocalAgentDispatchResult(false, null, "no pending deliveries");
        }

        LocalTaskPayloadVO payload = payloadBuilder.build(
                agent, command.threadId(), deliveries, taskTimeoutSeconds);
        LocalTaskPayloadVO.Trigger trigger = payload.getTrigger();
        long lastSeq = trigger != null && trigger.getSeqTo() != null ? trigger.getSeqTo() : 0L;

        AgentLocalTask task = new AgentLocalTask();
        task.setTenantId(agent.getTenantId());
        task.setSuperAgentId(agent.getId());
        task.setRuntimeId(runtime.getId());
        task.setThreadId(command.threadId());
        task.setStatus(LocalTaskStatus.PENDING);
        task.setInputPayload(JsonUtils.toJson(payload));
        task.setSessionRef(payload.getSessionRef());
        task.setLastSeq(lastSeq);
        task.setAttempts(0);
        task.setDeadlineAt(LocalDateTime.now().plusSeconds(Math.max(30, taskTimeoutSeconds)));
        taskMapper.insert(task);

        payload.setTaskId(task.getId());
        task.setInputPayload(JsonUtils.toJson(payload));
        taskMapper.updateById(task);

        for (AgentEventDelivery delivery : deliveries) {
            delivery.setStatus(AgentEventDeliveryStatus.RUNNING);
            delivery.setTargetTaskId(task.getId());
            delivery.setDeliveredAt(LocalDateTime.now());
            delivery.setAttempts((delivery.getAttempts() == null ? 0 : delivery.getAttempts()) + 1);
            deliveryMapper.updateById(delivery);

            AgentLocalTaskDelivery link = new AgentLocalTaskDelivery();
            link.setTaskId(task.getId());
            link.setDeliveryId(delivery.getId());
            link.setCreatedAt(LocalDateTime.now());
            taskDeliveryMapper.insert(link);
        }

        publishAfterCommit(runtime.getId(), task.getId());
        log.info("Local task created: task={}, agent={}, runtime={}, deliveries={}",
                task.getId(), agent.getId(), runtime.getId(), deliveries.size());
        return new LocalAgentDispatchResult(true, task.getId(), "queued");
    }

    @Override
    @Transactional
    public LocalProjectMentionDispatchResult dispatch(LocalProjectMentionCommand command) {
        if (command == null || command.agentId() == null || command.mentionId() == null
                || command.question() == null || command.question().isBlank()) {
            return LocalProjectMentionDispatchResult.rejected("invalid project mention command");
        }
        AgentSuperAgent agent = superAgentMapper.selectById(command.agentId());
        if (agent == null || !AgentType.LOCAL.equals(agent.getAgentType())
                || agent.getLocalRuntimeId() == null) {
            return LocalProjectMentionDispatchResult.rejected(
                    "local avatar or runtime is not configured");
        }
        AgentLocalRuntime runtime = runtimeMapper.selectById(agent.getLocalRuntimeId());
        if (runtime == null || !LocalRuntimeStatus.ACTIVE.equals(runtime.getStatus())) {
            return LocalProjectMentionDispatchResult.rejected(
                    "local runtime is missing or disabled");
        }
        if (!online(runtime)) {
            return LocalProjectMentionDispatchResult.retry("local runtime is offline");
        }
        AgentLocalTask existing = taskMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<AgentLocalTask>lambdaQuery()
                        .eq(AgentLocalTask::getBusinessType, LocalTaskBusinessTypes.PROJECT_MENTION)
                        .eq(AgentLocalTask::getBusinessId, command.mentionId())
                        .in(AgentLocalTask::getStatus,
                                LocalTaskStatus.PENDING, LocalTaskStatus.RUNNING)
                        .last("LIMIT 1"));
        if (existing != null) {
            return LocalProjectMentionDispatchResult.accepted(existing.getId());
        }

        LocalTaskPayloadVO payload = payloadBuilder.buildProjectMention(
                agent, command.threadId(), command.threadTitle(), command.question(),
                command.actorName(), taskTimeoutSeconds);
        AgentLocalTask task = new AgentLocalTask();
        task.setTenantId(agent.getTenantId());
        task.setSuperAgentId(agent.getId());
        task.setRuntimeId(runtime.getId());
        task.setThreadId(command.threadId());
        task.setBusinessType(LocalTaskBusinessTypes.PROJECT_MENTION);
        task.setBusinessId(command.mentionId());
        task.setStatus(LocalTaskStatus.PENDING);
        task.setInputPayload(JsonUtils.toJson(payload));
        task.setSessionRef(payload.getSessionRef());
        task.setLastSeq(0L);
        task.setAttempts(0);
        task.setDeadlineAt(LocalDateTime.now().plusSeconds(Math.max(30, taskTimeoutSeconds)));
        taskMapper.insert(task);

        payload.setTaskId(task.getId());
        task.setInputPayload(JsonUtils.toJson(payload));
        taskMapper.updateById(task);
        publishAfterCommit(runtime.getId(), task.getId());
        log.info("Local project mention task created: task={}, agent={}, mention={}",
                task.getId(), agent.getId(), command.mentionId());
        return LocalProjectMentionDispatchResult.accepted(task.getId());
    }

    private boolean online(AgentLocalRuntime runtime) {
        return runtime.getLastSeenAt() != null
                && runtime.getLastSeenAt().isAfter(
                        LocalDateTime.now().minusSeconds(ONLINE_TTL_SECONDS));
    }

    private void deferOrFail(List<Long> deliveryIds) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime retryAt = now.plusSeconds(OFFLINE_RETRY_SECONDS);
        for (Long deliveryId : deliveryIds) {
            AgentEventDelivery delivery = deliveryMapper.selectById(deliveryId);
            if (delivery == null || !AgentEventDeliveryStatus.PENDING.equals(delivery.getStatus())) {
                continue;
            }
            AgentEvent event = delivery.getEventId() == null
                    ? null : eventMapper.selectById(delivery.getEventId());
            boolean expired = event != null && event.getReceivedAt() != null
                    && event.getReceivedAt().isBefore(now.minusSeconds(offlineTtlSeconds));
            if (expired) {
                deliveryMapper.failPending(deliveryId, "local runtime offline beyond ttl");
            } else {
                deliveryMapper.backoffPending(deliveryId, retryAt, "local runtime offline");
            }
        }
    }

    private void failDeliveries(List<Long> deliveryIds, String reason) {
        for (Long deliveryId : deliveryIds) {
            deliveryMapper.failPending(deliveryId, truncate(reason));
        }
    }

    private void publishAfterCommit(Long runtimeId, Long taskId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            streamService.publishTaskReady(runtimeId, taskId);
                        }
                    });
        } else {
            streamService.publishTaskReady(runtimeId, taskId);
        }
    }

    private static String truncate(String value) {
        if (value == null) return null;
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
