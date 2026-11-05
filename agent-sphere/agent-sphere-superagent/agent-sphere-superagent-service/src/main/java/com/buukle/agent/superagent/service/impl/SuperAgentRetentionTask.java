package com.buukle.agent.superagent.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.TenantContext;
import com.buukle.agent.common.context.TenantUtil;
import com.buukle.agent.common.metrics.AgentMetrics;
import com.buukle.agent.superagent.domain.AgentEvent;
import com.buukle.agent.superagent.domain.AgentEventDelivery;
import com.buukle.agent.superagent.domain.AgentOutboundMessage;
import com.buukle.agent.superagent.domain.AgentWorkItem;
import com.buukle.agent.superagent.dtvo.enums.WorkItemStatus;
import com.buukle.agent.superagent.repository.AgentEventDeliveryMapper;
import com.buukle.agent.superagent.repository.AgentEventMapper;
import com.buukle.agent.superagent.repository.AgentOutboundMessageMapper;
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
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

/** Logical-delete retention for Super-Agent event/delivery/outbox rows. */
@Slf4j
@Service
@RequiredArgsConstructor
public class SuperAgentRetentionTask {

    private static final String LOCK_KEY = "scheduler:superagent-retention";
    private static final long LOCK_LEASE_SECONDS = 300;
    private static final String TABLE_DELIVERY = "agent_event_delivery";
    private static final String TABLE_EVENT = "agent_event";
    private static final String TABLE_OUTBOUND = "agent_outbound_message";
    private static final String TABLE_WORK_ITEM = "agent_work_item";

    private final AgentEventDeliveryMapper deliveryMapper;
    private final AgentEventMapper eventMapper;
    private final AgentOutboundMessageMapper outboundMapper;
    private final AgentWorkItemMapper workItemMapper;
    private final RedissonClient redissonClient;
    private final AgentMetrics metrics;

    @Value("${buukle.agent.super-agent.enabled:false}")
    private boolean enabled;
    @Value("${buukle.agent.super-agent.event.retention-days:30}")
    private int retentionDays;
    @Value("${buukle.agent.super-agent.retention.batch-size:500}")
    private int batchSize;
    @Value("${buukle.agent.super-agent.retention.max-batches:40}")
    private int maxBatches;
    @Value("${buukle.agent.super-agent.retention.batch-sleep-ms:200}")
    private long batchSleepMs;

    @Scheduled(cron = "${buukle.agent.super-agent.event.retention-cron:0 30 3 * * ?}")
    public void run() {
        if (!enabled || retentionDays <= 0) {
            return;
        }
        TenantUtil.stop();
        TenantContext.clear();
        AuthContext.clear();
        RLock lock = redissonClient.getLock(LOCK_KEY);
        boolean locked = false;
        try {
            locked = lock.tryLock(0, LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
            if (!locked) {
                return;
            }
            LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
            BatchBudget budget = new BatchBudget(Math.max(1, maxBatches));
            int size = Math.max(1, batchSize);
            drain(TABLE_DELIVERY, budget, size, () -> deliveryIds(cutoff, size),
                    ids -> deliveryMapper.deleteBatchIds(ids));
            drain(TABLE_EVENT, budget, size, () -> eventIds(cutoff, size),
                    ids -> eventMapper.deleteBatchIds(ids));
            drain(TABLE_OUTBOUND, budget, size, () -> outboundIds(cutoff, size),
                    ids -> outboundMapper.deleteBatchIds(ids));
            drain(TABLE_WORK_ITEM, budget, size, () -> workItemIds(cutoff, size),
                    ids -> workItemMapper.deleteBatchIds(ids));
            if (budget.truncated) {
                metrics.increment(AgentMetrics.RETENTION_TRUNCATED);
                log.warn("Super-Agent retention hit max-batches; remaining rows will be handled next run");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("Super-Agent retention failed: {}", e.getMessage());
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
            TenantContext.clear();
            AuthContext.clear();
        }
    }

    private void drain(String table, BatchBudget budget, int size,
                       Supplier<List<Long>> loader, Function<List<Long>, Integer> delete) {
        while (budget.remaining > 0) {
            List<Long> ids = loader.get();
            if (ids.isEmpty()) {
                return;
            }
            budget.remaining--;
            int deleted = delete.apply(ids);
            metrics.add(AgentMetrics.RETENTION_ROWS, "table", table, deleted);
            if (ids.size() >= size) {
                sleepBetweenBatches();
            }
        }
        budget.truncated = true;
    }

    private List<Long> deliveryIds(LocalDateTime cutoff, int limit) {
        return deliveryMapper.selectList(Wrappers.<AgentEventDelivery>lambdaQuery()
                        .select(AgentEventDelivery::getId)
                        .lt(AgentEventDelivery::getCreatedAt, cutoff)
                        .orderByAsc(AgentEventDelivery::getId)
                        .last("LIMIT " + limit))
                .stream().map(AgentEventDelivery::getId).toList();
    }

    private List<Long> eventIds(LocalDateTime cutoff, int limit) {
        return eventMapper.selectList(Wrappers.<AgentEvent>lambdaQuery()
                        .select(AgentEvent::getId)
                        .lt(AgentEvent::getCreatedAt, cutoff)
                        .orderByAsc(AgentEvent::getId)
                        .last("LIMIT " + limit))
                .stream().map(AgentEvent::getId).toList();
    }

    private List<Long> outboundIds(LocalDateTime cutoff, int limit) {
        return outboundMapper.selectList(Wrappers.<AgentOutboundMessage>lambdaQuery()
                        .select(AgentOutboundMessage::getId)
                        .lt(AgentOutboundMessage::getCreatedAt, cutoff)
                        .orderByAsc(AgentOutboundMessage::getId)
                        .last("LIMIT " + limit))
                .stream().map(AgentOutboundMessage::getId).toList();
    }

    private List<Long> workItemIds(LocalDateTime cutoff, int limit) {
        return workItemMapper.selectList(Wrappers.<AgentWorkItem>lambdaQuery()
                        .select(AgentWorkItem::getId)
                        .lt(AgentWorkItem::getCreatedAt, cutoff)
                        .in(AgentWorkItem::getStatus,
                                WorkItemStatus.DONE, WorkItemStatus.FAILED, WorkItemStatus.CANCELLED)
                        .orderByAsc(AgentWorkItem::getId)
                        .last("LIMIT " + limit))
                .stream().map(AgentWorkItem::getId).toList();
    }

    private void sleepBetweenBatches() {
        if (batchSleepMs <= 0) {
            return;
        }
        try {
            Thread.sleep(batchSleepMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("retention interrupted", e);
        }
    }

    private static final class BatchBudget {
        private int remaining;
        private boolean truncated;

        private BatchBudget(int remaining) {
            this.remaining = remaining;
        }
    }
}
