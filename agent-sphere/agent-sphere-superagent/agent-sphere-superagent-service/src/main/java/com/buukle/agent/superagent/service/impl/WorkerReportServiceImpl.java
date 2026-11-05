package com.buukle.agent.superagent.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.buukle.agent.common.constant.ActorType;
import com.buukle.agent.common.context.TenantContext;
import com.buukle.agent.common.metrics.AgentMetrics;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.dtvo.vo.UserVO;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.instance.spi.UserSpi;
import com.buukle.agent.superagent.domain.AgentEvent;
import com.buukle.agent.superagent.domain.AgentEventDelivery;
import com.buukle.agent.superagent.domain.AgentThread;
import com.buukle.agent.superagent.domain.AgentWorkItem;
import com.buukle.agent.superagent.dtvo.enums.AgentEventDeliveryStatus;
import com.buukle.agent.superagent.dtvo.enums.AgentEventStatus;
import com.buukle.agent.superagent.dtvo.enums.GroupRouterDecisionType;
import com.buukle.agent.superagent.dtvo.enums.WorkItemStatus;
import com.buukle.agent.superagent.dtvo.vo.WorkerReportPayloadVO;
import com.buukle.agent.superagent.repository.AgentEventDeliveryMapper;
import com.buukle.agent.superagent.repository.AgentEventMapper;
import com.buukle.agent.superagent.repository.AgentThreadMapper;
import com.buukle.agent.superagent.repository.AgentWorkItemMapper;
import com.buukle.agent.superagent.spi.WorkerReport;
import com.buukle.agent.superagent.spi.WorkerReportPort;
import com.buukle.agent.util.json.JsonUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Set;

/** Persists Worker terminal state, projects the task card, and emits WORKER_REPORT. */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkerReportServiceImpl implements WorkerReportPort {

    private static final int MAX_SUMMARY = 4000;
    private static final String EVENT_KIND_WORKER_REPORT = "WORKER_REPORT";
    private static final String FINISH_STATUS_BLOCKED = "blocked";
    private static final String FINISH_STATUS_FAILED = "failed";
    private static final Set<String> TERMINAL_STATUSES = Set.of(
            WorkItemStatus.COMPLETED, WorkItemStatus.DONE,
            WorkItemStatus.BLOCKED,
            WorkItemStatus.FAILED, WorkItemStatus.CANCELLED);

    private final AgentWorkItemMapper workItemMapper;
    private final AgentThreadMapper threadMapper;
    private final AgentEventMapper eventMapper;
    private final AgentEventDeliveryMapper deliveryMapper;
    private final WorkItemCardProjector cardProjector;
    private final RunSpi runSpi;
    private final UserSpi userSpi;
    private final AgentMetrics metrics;

    @Override
    public void onWorkerTerminal(WorkerReport report) {
        if (report == null || report.workerSessionId() == null) {
            return;
        }
        AgentWorkItem item = workItemMapper.selectOne(Wrappers.<AgentWorkItem>lambdaQuery()
                .eq(AgentWorkItem::getWorkerSessionId, report.workerSessionId())
                .last("LIMIT 1"));
        if (item == null) {
            log.debug("Worker report ignored: no work item for session {}", report.workerSessionId());
            return;
        }
        TenantContext.set(item.getTenantId());
        try {
            applyReport(item, report);
        } finally {
            TenantContext.clear();
        }
    }

    private void applyReport(AgentWorkItem item, WorkerReport report) {
        String finishStatus = resolveFinishStatus(report.runId());
        String status = normalize(report.status(), finishStatus);
        metrics.increment(AgentMetrics.WORKER_REPORT, "status", status);
        item.setStatus(status);
        item.setSummary(summarize(report));
        item.setLastProgressAt(LocalDateTime.now());
        workItemMapper.updateById(item);

        if (item.getThreadId() != null && TERMINAL_STATUSES.contains(status)) {
            if (WorkItemStatus.COMPLETED.equals(status) || WorkItemStatus.DONE.equals(status)) {
                touchThread(item.getThreadId(), report.summary());
            }
            cardProjector.projectTerminal(item);
            appendWorkerReport(item, report, status);
        }
    }

    private void appendWorkerReport(AgentWorkItem item, WorkerReport report, String status) {
        AgentThread thread = threadMapper.selectById(item.getThreadId());
        if (thread == null) {
            return;
        }
        String dedupeKey = "worker:" + report.workerSessionId() + ":" + report.runId();
        Long seq = threadMapper.nextEventSeq(item.getThreadId());
        if (seq == null) {
            return;
        }
        String text = "[WORKER_REPORT] workItemId=" + item.getId()
                + ", status=" + status
                + ", summary=" + (report.summary() == null ? "" : report.summary());
        WorkerReportPayloadVO payload = new WorkerReportPayloadVO();
        payload.setText(text);
        payload.setWorkItemId(item.getId());
        payload.setStatus(status);
        payload.setSummary(report.summary());
        payload.setErrorMessage(report.errorMessage());

        AgentEvent event = new AgentEvent();
        event.setTenantId(item.getTenantId());
        event.setThreadId(item.getThreadId());
        event.setSeq(seq);
        event.setKind(EVENT_KIND_WORKER_REPORT);
        event.setExternalId(String.valueOf(report.runId()));
        event.setDedupeKey(dedupeKey);
        event.setPayload(JsonUtils.toJson(payload));
        event.setStatus(AgentEventStatus.DELIVERED);
        event.setGroupDecision(GroupRouterDecisionType.RELEVANT);
        event.setGroupReason("worker terminal report");
        event.setGroupProcessedAt(LocalDateTime.now());
        event.setReceivedAt(LocalDateTime.now());
        event.setCreatedBy(ownerUsername(item.getOwnerUserId()));
        event.setActorType(ActorType.AGENT);
        event.setActorId(item.getSuperAgentId());
        try {
            eventMapper.insert(event);
        } catch (DuplicateKeyException ignored) {
            return;
        }

        AgentEventDelivery delivery = new AgentEventDelivery();
        delivery.setTenantId(item.getTenantId());
        delivery.setEventId(event.getId());
        delivery.setSuperAgentId(item.getSuperAgentId());
        delivery.setDecision(GroupRouterDecisionType.RELEVANT);
        delivery.setReason("worker terminal report");
        delivery.setStatus(AgentEventDeliveryStatus.PENDING);
        delivery.setAttempts(0);
        delivery.setCreatedBy(ownerUsername(item.getOwnerUserId()));
        delivery.setActorType(ActorType.AGENT);
        delivery.setActorId(item.getSuperAgentId());
        try {
            deliveryMapper.insert(delivery);
        } catch (DuplicateKeyException ignored) {
            // Already delivered.
        }
    }

    private String resolveFinishStatus(Long runId) {
        if (runId == null) {
            return null;
        }
        try {
            RunVO run = runSpi.getRun(runId);
            return run == null ? null : run.getFinishStatus();
        } catch (Exception e) {
            return null;
        }
    }

    private static String normalize(String status, String finishStatus) {
        if (FINISH_STATUS_BLOCKED.equalsIgnoreCase(finishStatus)) {
            return WorkItemStatus.BLOCKED;
        }
        if (FINISH_STATUS_FAILED.equalsIgnoreCase(finishStatus)) {
            return WorkItemStatus.FAILED;
        }
        if (status == null || status.isBlank()) {
            return WorkItemStatus.FAILED;
        }
        return switch (status.toUpperCase()) {
            case WorkItemStatus.COMPLETED, WorkItemStatus.DONE -> WorkItemStatus.DONE;
            case WorkItemStatus.BLOCKED -> WorkItemStatus.BLOCKED;
            case WorkItemStatus.FAILED -> WorkItemStatus.FAILED;
            case WorkItemStatus.CANCELLED -> WorkItemStatus.CANCELLED;
            case WorkItemStatus.WAITING -> WorkItemStatus.WAITING;
            case WorkItemStatus.RUNNING -> WorkItemStatus.RUNNING;
            default -> WorkItemStatus.FAILED;
        };
    }

    private static String summarize(WorkerReport report) {
        String text = report.summary();
        if (text == null || text.isBlank()) {
            text = report.errorMessage();
        }
        if (text == null) {
            return null;
        }
        return text.length() <= MAX_SUMMARY ? text : text.substring(0, MAX_SUMMARY);
    }

    private void touchThread(Long threadId, String summary) {
        AgentThread thread = threadMapper.selectById(threadId);
        if (thread == null) {
            return;
        }
        if (summary != null && !summary.isBlank()) {
            thread.setSummary(summary.length() <= 2000 ? summary : summary.substring(0, 2000));
        }
        threadMapper.updateById(thread);
    }

    private String ownerUsername(Long ownerUserId) {
        if (ownerUserId == null) {
            return null;
        }
        try {
            UserVO owner = userSpi.getByUserId(ownerUserId);
            return owner == null ? null : owner.getUsername();
        } catch (Exception e) {
            log.debug("Owner lookup failed: userId={}", ownerUserId);
            return null;
        }
    }
}
