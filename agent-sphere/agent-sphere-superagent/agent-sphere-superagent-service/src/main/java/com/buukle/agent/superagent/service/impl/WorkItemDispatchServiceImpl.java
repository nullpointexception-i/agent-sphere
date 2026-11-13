package com.buukle.agent.superagent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.buukle.agent.common.constant.ActorType;
import com.buukle.agent.superagent.domain.AgentWorkItem;
import com.buukle.agent.superagent.dtvo.enums.WorkItemStatus;
import com.buukle.agent.superagent.repository.AgentWorkItemMapper;
import com.buukle.agent.superagent.spi.WorkItemCreateCommand;
import com.buukle.agent.superagent.spi.WorkItemDispatchSpi;
import com.buukle.agent.superagent.spi.WorkItemStatusSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class WorkItemDispatchServiceImpl implements WorkItemDispatchSpi {

    private static final int MAX_TITLE = 256;
    private static final Set<String> TERMINAL = Set.of(
            WorkItemStatus.COMPLETED, WorkItemStatus.DONE,
            WorkItemStatus.FAILED, WorkItemStatus.CANCELLED);

    private final AgentWorkItemMapper workItemMapper;

    @Override
    @Transactional
    public Long dispatch(WorkItemCreateCommand command) {
        if (command == null || command.superAgentId() == null) {
            throw new IllegalArgumentException("superAgentId is required");
        }
        if (StringUtils.hasText(command.idempotencyKey())) {
            AgentWorkItem existing = workItemMapper.selectOne(new LambdaQueryWrapper<AgentWorkItem>()
                    .eq(AgentWorkItem::getIdempotencyKey, command.idempotencyKey())
                    .last("limit 1"));
            if (existing != null) {
                return existing.getId();
            }
        }
        AgentWorkItem item = new AgentWorkItem();
        item.setTenantId(command.tenantId());
        item.setOwnerUserId(command.ownerUserId());
        item.setSuperAgentId(command.superAgentId());
        item.setThreadId(command.threadId());
        item.setTitle(truncate(command.title()));
        item.setGoal(command.goal());
        item.setPriority(command.priority() == null ? 100 : command.priority());
        item.setRunMode(command.runMode());
        item.setRequestSource(command.requestSource());
        item.setSourceRef(command.sourceRef());
        item.setIdempotencyKey(command.idempotencyKey());
        item.setEntityType(command.entityType());
        item.setEntityId(command.entityId());
        item.setSceneId(command.sceneId());
        item.setStatus(WorkItemStatus.QUEUED);
        item.setActorType(ActorType.SYSTEM);
        workItemMapper.insert(item);
        return item.getId();
    }

    @Override
    public WorkItemStatusSnapshot status(Long workItemId) {
        AgentWorkItem item = workItemMapper.selectById(workItemId);
        if (item == null) {
            return null;
        }
        return new WorkItemStatusSnapshot(item.getId(), item.getStatus(), item.getSummary());
    }

    @Override
    @Transactional
    public boolean cancel(Long workItemId) {
        AgentWorkItem item = workItemMapper.selectById(workItemId);
        if (item == null || TERMINAL.contains(item.getStatus())) {
            return false;
        }
        AgentWorkItem update = new AgentWorkItem();
        update.setId(workItemId);
        update.setStatus(WorkItemStatus.CANCELLED);
        update.setSummary("cancelled by application run");
        workItemMapper.updateById(update);
        return true;
    }

    @Override
    @Transactional
    public boolean retry(Long workItemId) {
        AgentWorkItem item = workItemMapper.selectById(workItemId);
        if (item == null || TERMINAL.contains(item.getStatus())) {
            return false;
        }
        int nextAttempt = (item.getAttempt() == null ? 0 : item.getAttempt()) + 1;
        workItemMapper.update(null, Wrappers.<AgentWorkItem>lambdaUpdate()
                .eq(AgentWorkItem::getId, workItemId)
                .set(AgentWorkItem::getStatus, WorkItemStatus.QUEUED)
                .set(AgentWorkItem::getAttempt, nextAttempt)
                .set(AgentWorkItem::getWorkerSessionId, null)
                .set(AgentWorkItem::getBlockedReason, null)
                .set(AgentWorkItem::getLastProgressAt, LocalDateTime.now()));
        return true;
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        String title = value.length() <= MAX_TITLE ? value : value.substring(0, MAX_TITLE);
        return title.isBlank() ? "Application run" : title;
    }
}
