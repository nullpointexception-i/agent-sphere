package com.buukle.agent.superagent.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.TenantContext;
import com.buukle.agent.common.context.TenantUtil;
import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.instance.dtvo.vo.SessionTimelinePageVO;
import com.buukle.agent.instance.spi.AgentTimelineSpi;
import com.buukle.agent.superagent.domain.AgentSuperAgent;
import com.buukle.agent.superagent.domain.AgentWorkItem;
import com.buukle.agent.superagent.dtvo.enums.WorkItemStatus;
import com.buukle.agent.superagent.dtvo.vo.WorkItemVO;
import com.buukle.agent.superagent.repository.AgentSuperAgentMapper;
import com.buukle.agent.superagent.repository.AgentWorkItemMapper;
import com.buukle.agent.superagent.service.WorkItemService;
import com.buukle.agent.superagent.spi.WorkerControlPort;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
public class WorkItemServiceImpl implements WorkItemService {

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Set<String> RETRYABLE = Set.of(
            WorkItemStatus.FAILED, WorkItemStatus.CANCELLED);
    private static final Set<String> CANCELLABLE = Set.of(
            WorkItemStatus.QUEUED, WorkItemStatus.DISPATCHING,
            WorkItemStatus.RUNNING, WorkItemStatus.WAITING);
    private static final String CANCEL_SUMMARY = "cancelled by user";

    private final AgentWorkItemMapper workItemMapper;
    private final AgentSuperAgentMapper superAgentMapper;
    private final AgentTimelineSpi agentTimelineSpi;
    private final ObjectProvider<WorkerControlPort> workerControlPortProvider;

    @Override
    public List<WorkItemVO> list(Long superAgentId, String status) {
        assertOwned(superAgentId);
        var query = Wrappers.<AgentWorkItem>lambdaQuery()
                .eq(AgentWorkItem::getSuperAgentId, superAgentId)
                .orderByAsc(AgentWorkItem::getPriority)
                .orderByDesc(AgentWorkItem::getId);
        if (status != null && !status.isBlank()) {
            query.eq(AgentWorkItem::getStatus, status.toUpperCase());
        }
        return systemRead(() -> workItemMapper.selectList(query)).stream().map(this::toVO).toList();
    }

    @Override
    public WorkItemVO get(Long id) {
        AgentWorkItem item = systemRead(() -> workItemMapper.selectById(id));
        if (item == null) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "work item not found");
        }
        assertOwned(item.getSuperAgentId());
        return toVO(item);
    }

    @Override
    public WorkItemVO retry(Long superAgentId, Long id) {
        AgentWorkItem item = requireOwnedItem(superAgentId, id);
        if (!RETRYABLE.contains(item.getStatus())) {
            throw new BizException(CommonErrorCode.PARAM_INVALID,
                    "only FAILED or CANCELLED work items can be retried");
        }
        item.setStatus(WorkItemStatus.QUEUED);
        item.setAttempt((item.getAttempt() == null ? 1 : item.getAttempt()) + 1);
        item.setWorkerSessionId(null);
        item.setSummary(null);
        item.setLastProgressAt(LocalDateTime.now());
        workItemMapper.updateById(item);
        return toVO(item);
    }

    @Override
    public WorkItemVO cancel(Long superAgentId, Long id) {
        AgentWorkItem item = requireOwnedItem(superAgentId, id);
        if (!CANCELLABLE.contains(item.getStatus())) {
            throw new BizException(CommonErrorCode.PARAM_INVALID,
                    "work item is already terminal");
        }
        WorkerControlPort control = workerControlPortProvider.getIfAvailable();
        if (control != null && item.getWorkerSessionId() != null) {
            control.cancel(item.getWorkerSessionId());
        }
        item.setStatus(WorkItemStatus.CANCELLED);
        item.setSummary(CANCEL_SUMMARY);
        item.setLastProgressAt(LocalDateTime.now());
        workItemMapper.updateById(item);
        return toVO(item);
    }

    @Override
    public WorkItemVO confirmSpeculation(Long superAgentId, Long id) {
        AgentWorkItem item = requireOwnedItem(superAgentId, id);
        if (!WorkItemStatus.SPECULATING.equals(item.getStatus())) {
            throw new BizException(CommonErrorCode.PARAM_INVALID,
                    "work item is not speculating");
        }
        item.setStatus(WorkItemStatus.QUEUED);
        item.setLastProgressAt(LocalDateTime.now());
        workItemMapper.updateById(item);
        return toVO(item);
    }

    @Override
    public SessionTimelinePageVO workerTimeline(Long superAgentId, Long id,
                                                Long beforeSeq, Long afterSeq, int limit) {
        AgentWorkItem item = requireOwnedItem(superAgentId, id);
        SessionTimelinePageVO empty = new SessionTimelinePageVO();
        empty.setRows(List.of());
        if (item.getWorkerSessionId() == null) {
            return empty;
        }
        // Worker sessions are created by the scheduler; bypass created_by filtering for the owner.
        String tenant = TenantUtil.get();
        Long tenantId = TenantContext.get();
        boolean admin = AuthContext.isSuperAdmin();
        TenantUtil.stop();
        TenantContext.clear();
        AuthContext.setSuperAdmin(true);
        try {
            return agentTimelineSpi.page(item.getWorkerSessionId(), beforeSeq, afterSeq, limit);
        } finally {
            AuthContext.setSuperAdmin(admin);
            TenantContext.set(tenantId);
            if (tenant != null) {
                TenantUtil.start(tenant);
            }
        }
    }

    private AgentWorkItem requireOwnedItem(Long superAgentId, Long id) {
        AgentWorkItem item = systemRead(() -> workItemMapper.selectById(id));
        if (item == null || !item.getSuperAgentId().equals(superAgentId)) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "work item not found");
        }
        assertOwned(superAgentId);
        return item;
    }

    /**
     * Work items are inserted by schedulers. Explicit ownership checks above are the
     * authority; reads bypass created_by filtering.
     */
    private <T> T systemRead(Supplier<T> supplier) {
        String tenant = TenantUtil.get();
        Long tenantId = TenantContext.get();
        boolean admin = AuthContext.isSuperAdmin();
        TenantUtil.stop();
        TenantContext.clear();
        AuthContext.setSuperAdmin(true);
        try {
            return supplier.get();
        } finally {
            AuthContext.setSuperAdmin(admin);
            TenantContext.set(tenantId);
            if (tenant != null) {
                TenantUtil.start(tenant);
            }
        }
    }

    private void assertOwned(Long superAgentId) {
        AgentSuperAgent agent = superAgentMapper.selectById(superAgentId);
        if (agent == null) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "super agent not found");
        }
        if (!AuthContext.isSuperAdmin() && !AuthContext.getUserId().equals(agent.getOwnerUserId())) {
            throw new BizException(CommonErrorCode.FORBIDDEN);
        }
    }

    private WorkItemVO toVO(AgentWorkItem item) {
        WorkItemVO vo = new WorkItemVO();
        vo.setId(item.getId());
        vo.setSuperAgentId(item.getSuperAgentId());
        vo.setThreadId(item.getThreadId());
        vo.setWorkerSessionId(item.getWorkerSessionId());
        vo.setTriggerEventId(item.getTriggerEventId());
        vo.setAttempt(item.getAttempt());
        vo.setTitle(item.getTitle());
        vo.setGoal(item.getGoal());
        vo.setPriority(item.getPriority());
        vo.setStatus(item.getStatus());
        vo.setSummary(item.getSummary());
        vo.setLastProgressAt(item.getLastProgressAt() == null ? null
                : item.getLastProgressAt().format(DTF));
        vo.setCreatedAt(item.getCreatedAt() == null ? null : item.getCreatedAt().format(DTF));
        return vo;
    }
}
