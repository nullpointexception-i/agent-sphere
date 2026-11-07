package com.buukle.agent.superagent.service.impl;

import com.buukle.agent.common.context.TenantScopeHolder;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.TenantContext;
import com.buukle.agent.common.context.TenantUtil;
import com.buukle.agent.common.metrics.AgentMetrics;
import com.buukle.agent.instance.dtvo.vo.UserVO;
import com.buukle.agent.instance.spi.UserSpi;
import com.buukle.agent.superagent.domain.AgentSuperAgent;
import com.buukle.agent.superagent.domain.AgentWorkItem;
import com.buukle.agent.superagent.dtvo.enums.SuperAgentStatus;
import com.buukle.agent.superagent.dtvo.enums.WorkItemStatus;
import com.buukle.agent.superagent.repository.AgentSuperAgentMapper;
import com.buukle.agent.superagent.repository.AgentWorkItemMapper;
import com.buukle.agent.superagent.spi.DispatchResult;
import com.buukle.agent.superagent.spi.WorkerDispatchCommand;
import com.buukle.agent.superagent.spi.WorkerDispatchPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Worker scheduler.
 *
 * <p>Claims QUEUED work items with CAS, starts a Worker run through
 * {@link WorkerDispatchPort}, and leaves terminal state updates to the report port.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkerScheduler {

    private static final Set<String> IN_FLIGHT = Set.of(
            WorkItemStatus.DISPATCHING, WorkItemStatus.RUNNING, WorkItemStatus.WAITING);
    private static final int MAX_TITLE = 120;
    private static final String SCOPE_GLOBAL = "GLOBAL";
    private static final String DISPATCH_LOCK_KEY = "scheduler:worker-dispatch";
    private static final long DISPATCH_LOCK_LEASE_SECONDS = 30;

    private final AgentWorkItemMapper workItemMapper;
    private final AgentSuperAgentMapper superAgentMapper;
    private final UserSpi userSpi;
    private final ObjectProvider<WorkerDispatchPort> dispatchPortProvider;
    private final RedissonClient redissonClient;
    private final AgentMetrics metrics;

    @Value("${buukle.agent.super-agent.enabled:false}")
    private boolean superAgentEnabled;
    @Value("${buukle.agent.super-agent.worker.enabled:false}")
    private boolean enabled;
    @Value("${buukle.agent.super-agent.worker.max-concurrent:5}")
    private int maxConcurrent;
    @Value("${buukle.agent.super-agent.worker.max-active-per-agent:8}")
    private int maxActivePerAgent;
    @Value("${buukle.agent.super-agent.worker.queue-capacity:200}")
    private int queueCapacity;
    @Value("${buukle.agent.super-agent.worker.batch-size:5}")
    private int batchSize;
    @Value("${buukle.agent.super-agent.worker.dispatch-timeout-minutes:10}")
    private int dispatchTimeoutMinutes;
    @Value("${buukle.agent.super-agent.worker.max-concurrent-scope:REPLICA}")
    private String maxConcurrentScope;

    @Scheduled(fixedDelayString = "${buukle.agent.super-agent.worker.interval-ms:3000}")
    public void run() {
        if (!superAgentEnabled || !enabled) {
            return;
        }
        TenantUtil.stop();
        TenantScopeHolder.clear();
        TenantContext.clear();
        AuthContext.clear();
        recoverStuckDispatching();
        WorkerDispatchPort port = dispatchPortProvider.getIfAvailable();
        if (port == null) {
            log.warn("WorkerDispatchPort is not available; worker scheduler skipped");
            return;
        }
        int inFlight = countInFlight();
        RLock dispatchLock = null;
        boolean locked = false;
        if (SCOPE_GLOBAL.equalsIgnoreCase(maxConcurrentScope)) {
            dispatchLock = redissonClient.getLock(DISPATCH_LOCK_KEY);
            try {
                locked = dispatchLock.tryLock(0, DISPATCH_LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!locked) {
                metrics.increment(AgentMetrics.WORKER_DISPATCH_LOCK_SKIPPED);
                return;
            }
            inFlight = countInFlight();
        }
        try {
            int quota = Math.max(0, maxConcurrent - inFlight);
            if (quota == 0) {
                return;
            }
            if (countQueued() > queueCapacity) {
                log.warn("Worker queue over capacity; dispatch skipped");
                return;
            }
            int limit = Math.min(Math.max(1, batchSize), quota);
            List<AgentWorkItem> items = workItemMapper.selectList(Wrappers.<AgentWorkItem>lambdaQuery()
                    .eq(AgentWorkItem::getStatus, WorkItemStatus.QUEUED)
                    .orderByAsc(AgentWorkItem::getPriority)
                    .orderByAsc(AgentWorkItem::getId)
                    .last("LIMIT " + limit));
            for (AgentWorkItem item : items) {
                try {
                    dispatchOne(port, item);
                } catch (Exception e) {
                    log.warn("Worker dispatch failed: workItem={}, error={}", item.getId(), e.getMessage());
                    fail(item, e.getMessage());
                } finally {
                    TenantContext.clear();
                }
            }
        } finally {
            if (locked && dispatchLock != null && dispatchLock.isHeldByCurrentThread()) {
                dispatchLock.unlock();
            }
            TenantContext.clear();
        }
    }

    private int countInFlight() {
        Long count = workItemMapper.selectCount(Wrappers.<AgentWorkItem>lambdaQuery()
                .in(AgentWorkItem::getStatus, IN_FLIGHT));
        return count == null ? 0 : count.intValue();
    }

    private long countQueued() {
        Long count = workItemMapper.selectCount(Wrappers.<AgentWorkItem>lambdaQuery()
                .eq(AgentWorkItem::getStatus, WorkItemStatus.QUEUED));
        return count == null ? 0 : count;
    }

    private long countInFlightForAgent(Long superAgentId) {
        Long count = workItemMapper.selectCount(Wrappers.<AgentWorkItem>lambdaQuery()
                .eq(AgentWorkItem::getSuperAgentId, superAgentId)
                .in(AgentWorkItem::getStatus, IN_FLIGHT));
        return count == null ? 0 : count;
    }

    /**
     * Recover rows stuck in DISPATCHING after a process crash.
     * A bound Worker session is treated as RUNNING; otherwise the item is failed.
     */
    private void recoverStuckDispatching() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(Math.max(1, dispatchTimeoutMinutes));
        List<AgentWorkItem> stuck = workItemMapper.selectList(Wrappers.<AgentWorkItem>lambdaQuery()
                .eq(AgentWorkItem::getStatus, WorkItemStatus.DISPATCHING)
                .lt(AgentWorkItem::getLastProgressAt, cutoff)
                .last("LIMIT " + Math.max(1, batchSize)));
        for (AgentWorkItem item : stuck) {
            boolean bound = item.getWorkerSessionId() != null;
            String next = bound ? WorkItemStatus.RUNNING : WorkItemStatus.FAILED;
            workItemMapper.update(null, Wrappers.<AgentWorkItem>lambdaUpdate()
                    .eq(AgentWorkItem::getId, item.getId())
                    .eq(AgentWorkItem::getStatus, WorkItemStatus.DISPATCHING)
                    .set(AgentWorkItem::getStatus, next)
                    .set(AgentWorkItem::getLastProgressAt, LocalDateTime.now())
                    .set(!bound, AgentWorkItem::getSummary, "dispatch timeout"));
            log.warn("Recovered stuck work item {}: {}", item.getId(), next);
        }
    }

    private void dispatchOne(WorkerDispatchPort port, AgentWorkItem item) {
        if (!claim(item.getId())) {
            return;
        }
        AgentSuperAgent agent = superAgentMapper.selectById(item.getSuperAgentId());
        if (agent == null || !SuperAgentStatus.ACTIVE.equals(agent.getStatus())) {
            fail(item, "super agent is not active");
            return;
        }
        TenantContext.set(agent.getTenantId());
        if (countInFlightForAgent(agent.getId()) >= Math.max(1, maxActivePerAgent)) {
            releaseClaim(item.getId());
            return;
        }
        Long workerInstanceId = agent.getWorkerInstanceId();
        if (workerInstanceId == null || workerInstanceId <= 0) {
            fail(item, "worker instance is not configured");
            return;
        }
        DispatchResult result = port.dispatch(new WorkerDispatchCommand(
                item.getId(),
                agent.getId(),
                item.getThreadId(),
                workerInstanceId,
                item.getGoal(),
                "WRITE",
                item.getPriority() == null ? 100 : item.getPriority(),
                item.getOwnerUserId(),
                agent.getTenantId(),
                ownerUsername(agent.getOwnerUserId())));
        if (result == null || !result.accepted()) {
            fail(item, result == null ? "dispatch returned null" : result.message());
            return;
        }
        int advanced = workItemMapper.update(null, Wrappers.<AgentWorkItem>lambdaUpdate()
                .eq(AgentWorkItem::getId, item.getId())
                .eq(AgentWorkItem::getStatus, WorkItemStatus.DISPATCHING)
                .set(AgentWorkItem::getStatus, WorkItemStatus.RUNNING)
                .set(AgentWorkItem::getWorkerSessionId, result.workerSessionId())
                .set(AgentWorkItem::getLastProgressAt, LocalDateTime.now()));
        if (advanced == 0) {
            log.info("Worker finished before RUNNING transition: workItem={}, session={}",
                    item.getId(), result.workerSessionId());
        } else {
            log.info("Worker dispatched: workItem={}, session={}, run={}",
                    item.getId(), result.workerSessionId(), result.runId());
        }
    }

    private boolean claim(Long workItemId) {
        return workItemMapper.update(null, Wrappers.<AgentWorkItem>lambdaUpdate()
                .eq(AgentWorkItem::getId, workItemId)
                .eq(AgentWorkItem::getStatus, WorkItemStatus.QUEUED)
                .set(AgentWorkItem::getStatus, WorkItemStatus.DISPATCHING)
                .set(AgentWorkItem::getLastProgressAt, LocalDateTime.now())) > 0;
    }

    private void releaseClaim(Long workItemId) {
        workItemMapper.update(null, Wrappers.<AgentWorkItem>lambdaUpdate()
                .eq(AgentWorkItem::getId, workItemId)
                .eq(AgentWorkItem::getStatus, WorkItemStatus.DISPATCHING)
                .set(AgentWorkItem::getStatus, WorkItemStatus.QUEUED));
    }

    private void fail(AgentWorkItem item, String reason) {
        item.setStatus(WorkItemStatus.FAILED);
        item.setSummary(truncate(reason == null ? "dispatch failed" : reason));
        item.setLastProgressAt(LocalDateTime.now());
        workItemMapper.updateById(item);
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

    private static String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= MAX_TITLE ? text : text.substring(0, MAX_TITLE);
    }
}
