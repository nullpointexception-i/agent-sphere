package com.buukle.agent.application.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.buukle.agent.application.domain.BizApplicationRun;
import com.buukle.agent.application.dtvo.enums.ApplicationRunStatus;
import com.buukle.agent.application.repository.BizApplicationRunMapper;
import com.buukle.agent.common.context.TenantContext;
import com.buukle.agent.common.metrics.AgentMetrics;
import com.buukle.agent.superagent.dtvo.enums.WorkItemStatus;
import com.buukle.agent.superagent.spi.WorkItemDispatchSpi;
import com.buukle.agent.superagent.spi.WorkItemStatusSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "buukle.agent.application", name = "reconcile-enabled",
        havingValue = "true", matchIfMissing = true)
public class ApplicationRunReconciler {

    private static final Set<String> NON_TERMINAL = Set.of(
            ApplicationRunStatus.QUEUED,
            ApplicationRunStatus.RUNNING,
            ApplicationRunStatus.WAITING_APPROVAL);
    private static final String WORK_ITEM_MISSING = "work item missing";
    private static final String TIMEOUT_RETRY = "application run timed out; retry scheduled";
    private static final String TIMEOUT_FAILED = "application run timed out";

    private final BizApplicationRunMapper runMapper;
    private final ObjectProvider<WorkItemDispatchSpi> dispatchPortProvider;
    private final AgentMetrics metrics;

    @Value("${buukle.agent.application.reconcile-stale-minutes:10}")
    private int staleMinutes = 10;
    @Value("${buukle.agent.application.reconcile-max-attempts:3}")
    private int maxAttempts = 3;

    @Scheduled(fixedDelayString = "${buukle.agent.application.reconcile-interval-ms:60000}")
    public void reconcile() {
        WorkItemDispatchSpi dispatch = dispatchPortProvider.getIfAvailable();
        if (dispatch == null) {
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(staleMinutes);
        List<BizApplicationRun> runs = runMapper.selectList(
                new LambdaQueryWrapper<BizApplicationRun>()
                        .in(BizApplicationRun::getStatus, NON_TERMINAL)
                        .lt(BizApplicationRun::getUpdatedAt, cutoff)
                        .last("limit 100"));
        for (BizApplicationRun run : runs) {
            try {
                reconcileOne(run, dispatch);
            } catch (Exception e) {
                log.warn("Application run reconcile failed: runId={}, error={}",
                        run.getId(), e.getMessage());
            } finally {
                TenantContext.clear();
            }
        }
    }

    private void reconcileOne(BizApplicationRun run, WorkItemDispatchSpi dispatch) {
        if (run.getWorkItemId() == null) {
            markTerminal(run, ApplicationRunStatus.FAILED, WORK_ITEM_MISSING);
            return;
        }
        WorkItemStatusSnapshot snapshot = dispatch.status(run.getWorkItemId());
        if (snapshot == null) {
            markTerminal(run, ApplicationRunStatus.FAILED, WORK_ITEM_MISSING);
            return;
        }
        TenantContext.set(run.getTenantId());
        String status = map(snapshot.status());
        if (status == null) {
            return;
        }
        boolean stuckRunning = ApplicationRunStatus.RUNNING.equals(status)
                && ApplicationRunStatus.RUNNING.equals(run.getStatus());
        if (ApplicationRunStatus.QUEUED.equals(status) || stuckRunning) {
            retryOrFail(run, dispatch);
            return;
        }
        if (status.equals(run.getStatus())) {
            return;
        }
        if (ApplicationRunStatus.RUNNING.equals(status) && run.getStartedAt() == null) {
            run.setStartedAt(LocalDateTime.now());
        }
        run.setStatus(status);
        if (snapshot.summary() != null) {
            run.setErrorMessage(snapshot.summary());
        }
        if (!ApplicationRunStatus.RUNNING.equals(status)
                && !ApplicationRunStatus.WAITING_APPROVAL.equals(status)) {
            run.setFinishedAt(LocalDateTime.now());
        }
        runMapper.updateById(run);
    }

    /** Timeout recovery: requeue the work item until the run exhausts its attempts. */
    private void retryOrFail(BizApplicationRun run, WorkItemDispatchSpi dispatch) {
        int attempt = run.getAttempt() == null ? 0 : run.getAttempt();
        if (attempt >= maxAttempts || !dispatch.retry(run.getWorkItemId())) {
            markTerminal(run, ApplicationRunStatus.FAILED, TIMEOUT_FAILED);
            return;
        }
        runMapper.update(null, Wrappers.<BizApplicationRun>lambdaUpdate()
                .eq(BizApplicationRun::getId, run.getId())
                .set(BizApplicationRun::getStatus, ApplicationRunStatus.QUEUED)
                .set(BizApplicationRun::getAttempt, attempt + 1)
                .set(BizApplicationRun::getErrorMessage, TIMEOUT_RETRY)
                .set(BizApplicationRun::getFinishedAt, null));
        metrics.increment(AgentMetrics.APPLICATION_RUN_RETRY);
        log.info("Application run retried: runId={}, attempt={}", run.getId(), attempt + 1);
    }

    private void markTerminal(BizApplicationRun run, String status, String reason) {
        TenantContext.set(run.getTenantId());
        run.setStatus(status);
        run.setErrorMessage(reason);
        run.setFinishedAt(LocalDateTime.now());
        runMapper.updateById(run);
    }

    private String map(String workItemStatus) {
        if (WorkItemStatus.COMPLETED.equals(workItemStatus)
                || WorkItemStatus.DONE.equals(workItemStatus)) {
            return ApplicationRunStatus.SUCCEEDED;
        }
        if (WorkItemStatus.FAILED.equals(workItemStatus)) {
            return ApplicationRunStatus.FAILED;
        }
        if (WorkItemStatus.CANCELLED.equals(workItemStatus)) {
            return ApplicationRunStatus.CANCELLED;
        }
        if (WorkItemStatus.BLOCKED.equals(workItemStatus)) {
            return ApplicationRunStatus.WAITING_APPROVAL;
        }
        if (WorkItemStatus.RUNNING.equals(workItemStatus)
                || WorkItemStatus.DISPATCHING.equals(workItemStatus)) {
            return ApplicationRunStatus.RUNNING;
        }
        if (WorkItemStatus.QUEUED.equals(workItemStatus)) {
            return ApplicationRunStatus.QUEUED;
        }
        return null;
    }
}
