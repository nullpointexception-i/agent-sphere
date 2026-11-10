package com.buukle.agent.superagent.service.impl;

import com.buukle.agent.common.context.TenantScopeHolder;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.TenantContext;
import com.buukle.agent.common.context.TenantUtil;
import com.buukle.agent.common.metrics.AgentMetrics;
import com.buukle.agent.superagent.domain.AgentEventDelivery;
import com.buukle.agent.superagent.domain.AgentOutboundMessage;
import com.buukle.agent.superagent.domain.AgentWatchdogRun;
import com.buukle.agent.superagent.domain.AgentWorkItem;
import com.buukle.agent.superagent.dtvo.enums.AgentEventDeliveryStatus;
import com.buukle.agent.superagent.dtvo.enums.AgentOutboundMessageStatus;
import com.buukle.agent.superagent.dtvo.enums.AgentWatchdogRunStatus;
import com.buukle.agent.superagent.dtvo.enums.WorkItemStatus;
import com.buukle.agent.superagent.repository.AgentEventDeliveryMapper;
import com.buukle.agent.superagent.repository.AgentOutboundMessageMapper;
import com.buukle.agent.superagent.repository.AgentWatchdogRunMapper;
import com.buukle.agent.superagent.repository.AgentWorkItemMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/** Deterministic recovery for stuck Super-Agent runtime rows. */
@Slf4j
@Service
@RequiredArgsConstructor
public class SuperAgentWatchdogTask {

    private static final String LOCK_KEY = "scheduler:super-agent-watchdog";

    private final AgentWorkItemMapper workItemMapper;
    private final AgentEventDeliveryMapper deliveryMapper;
    private final AgentOutboundMessageMapper outboundMapper;
    private final AgentWatchdogRunMapper runMapper;
    private final RedissonClient redissonClient;
    private final AgentMetrics metrics;

    @Value("${buukle.agent.super-agent.enabled:false}")
    private boolean superAgentEnabled;
    @Value("${buukle.agent.super-agent.watchdog.enabled:false}")
    private boolean enabled;
    @Value("${buukle.agent.super-agent.watchdog.stale-minutes:15}")
    private int staleMinutes;
    @Value("${buukle.agent.super-agent.watchdog.batch-size:500}")
    private int batchSize;
    @Value("${buukle.agent.super-agent.watchdog.max-batches:20}")
    private int maxBatches;
    @Value("${buukle.agent.super-agent.watchdog.batch-sleep-ms:100}")
    private long batchSleepMs;

    @Scheduled(fixedDelayString = "${buukle.agent.super-agent.watchdog.interval-ms:60000}")
    public void run() {
        if (!superAgentEnabled || !enabled) {
            return;
        }
        TenantUtil.stop();
        TenantScopeHolder.clear();
        TenantContext.clear();
        AuthContext.clear();
        RLock lock = redissonClient.getLock(LOCK_KEY);
        boolean locked = false;
        try {
            locked = lock.tryLock(0, 120, java.util.concurrent.TimeUnit.SECONDS);
            if (!locked) {
                return;
            }
            AgentWatchdogRun run = new AgentWatchdogRun();
            run.setStatus(AgentWatchdogRunStatus.RUNNING);
            run.setStartedAt(LocalDateTime.now());
            runMapper.insert(run);
            try {
                LocalDateTime cutoff = LocalDateTime.now().minusMinutes(Math.max(1, staleMinutes));
                WatchdogBudget budget = new WatchdogBudget(Math.max(1, maxBatches));
                int dispatched = recoverWorkItems(cutoff, budget);
                int deliveries = recoverDeliveries(cutoff, budget);
                int outbound = recoverOutbound(cutoff, budget);
                run.setDispatchedStuck(dispatched);
                run.setDeliveryStuck(deliveries);
                run.setOutboundStuck(outbound);
                if (dispatched > 0) {
                    metrics.increment(AgentMetrics.WATCHDOG_RECOVERED, AgentMetrics.TAG_KIND, "DISPATCH");
                }
                if (deliveries > 0) {
                    metrics.increment(AgentMetrics.WATCHDOG_RECOVERED, AgentMetrics.TAG_KIND, "DELIVERY");
                }
                if (outbound > 0) {
                    metrics.increment(AgentMetrics.WATCHDOG_RECOVERED, AgentMetrics.TAG_KIND, "OUTBOUND");
                }
                run.setStatus(AgentWatchdogRunStatus.SUCCESS);
                run.setFinishedAt(LocalDateTime.now());
                runMapper.updateById(run);
                log.info("Watchdog done: dispatched={}, deliveries={}, outbound={}",
                        dispatched, deliveries, outbound);
        } catch (Exception e) {
            run.setStatus(AgentWatchdogRunStatus.FAILED);
            run.setErrorMessage(e.getMessage());
            run.setFinishedAt(LocalDateTime.now());
            runMapper.updateById(run);
            log.warn("Watchdog failed: {}", e.getMessage());
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private int recoverWorkItems(LocalDateTime cutoff, WatchdogBudget budget) {
        int total = 0;
        int size = Math.max(1, batchSize);
        while (budget.remaining > 0) {
            List<Long> ids = workItemMapper.selectList(Wrappers.<AgentWorkItem>lambdaQuery()
                            .select(AgentWorkItem::getId)
                            .eq(AgentWorkItem::getStatus, WorkItemStatus.DISPATCHING)
                            .lt(AgentWorkItem::getLastProgressAt, cutoff)
                            .orderByAsc(AgentWorkItem::getId)
                            .last("LIMIT " + size))
                    .stream().map(AgentWorkItem::getId).toList();
            if (ids.isEmpty()) {
                break;
            }
            budget.remaining--;
            total += workItemMapper.update(null, Wrappers.<AgentWorkItem>lambdaUpdate()
                    .in(AgentWorkItem::getId, ids)
                    .eq(AgentWorkItem::getStatus, WorkItemStatus.DISPATCHING)
                    .set(AgentWorkItem::getStatus, WorkItemStatus.FAILED)
                    .set(AgentWorkItem::getSummary, "watchdog: dispatch timeout"));
            sleepBetweenBatches();
        }
        return total;
    }

    private int recoverDeliveries(LocalDateTime cutoff, WatchdogBudget budget) {
        int total = 0;
        int size = Math.max(1, batchSize);
        while (budget.remaining > 0) {
            List<Long> ids = deliveryMapper.selectList(Wrappers.<AgentEventDelivery>lambdaQuery()
                            .select(AgentEventDelivery::getId)
                            .eq(AgentEventDelivery::getStatus, AgentEventDeliveryStatus.CLAIMED)
                            .lt(AgentEventDelivery::getClaimedAt, cutoff)
                            .orderByAsc(AgentEventDelivery::getId)
                            .last("LIMIT " + size))
                    .stream().map(AgentEventDelivery::getId).toList();
            if (ids.isEmpty()) {
                break;
            }
            budget.remaining--;
            total += deliveryMapper.update(null, Wrappers.<AgentEventDelivery>lambdaUpdate()
                    .in(AgentEventDelivery::getId, ids)
                    .eq(AgentEventDelivery::getStatus, AgentEventDeliveryStatus.CLAIMED)
                    .set(AgentEventDelivery::getStatus, AgentEventDeliveryStatus.PENDING)
                    .set(AgentEventDelivery::getClaimedAt, null));
            sleepBetweenBatches();
        }
        return total;
    }

    private int recoverOutbound(LocalDateTime cutoff, WatchdogBudget budget) {
        int total = 0;
        int size = Math.max(1, batchSize);
        while (budget.remaining > 0) {
            List<Long> ids = outboundMapper.selectList(Wrappers.<AgentOutboundMessage>lambdaQuery()
                            .select(AgentOutboundMessage::getId)
                            .eq(AgentOutboundMessage::getStatus, AgentOutboundMessageStatus.SENDING)
                            .lt(AgentOutboundMessage::getUpdatedAt, cutoff)
                            .orderByAsc(AgentOutboundMessage::getId)
                            .last("LIMIT " + size))
                    .stream().map(AgentOutboundMessage::getId).toList();
            if (ids.isEmpty()) {
                break;
            }
            budget.remaining--;
            total += outboundMapper.update(null, Wrappers.<AgentOutboundMessage>lambdaUpdate()
                    .in(AgentOutboundMessage::getId, ids)
                    .eq(AgentOutboundMessage::getStatus, AgentOutboundMessageStatus.SENDING)
                    .set(AgentOutboundMessage::getStatus, AgentOutboundMessageStatus.RETRY)
                    .set(AgentOutboundMessage::getLeaseToken, null)
                    .set(AgentOutboundMessage::getLeaseUntil, null));
            sleepBetweenBatches();
        }
        return total;
    }

    private void sleepBetweenBatches() {
        if (batchSleepMs <= 0) {
            return;
        }
        try {
            Thread.sleep(batchSleepMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("watchdog interrupted", e);
        }
    }

    private static final class WatchdogBudget {
        private int remaining;

        private WatchdogBudget(int remaining) {
            this.remaining = remaining;
        }
    }
}
