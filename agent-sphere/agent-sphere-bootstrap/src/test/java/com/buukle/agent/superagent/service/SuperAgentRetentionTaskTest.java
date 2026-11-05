package com.buukle.agent.superagent.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.buukle.agent.common.metrics.AgentMetrics;
import com.buukle.agent.superagent.domain.AgentEvent;
import com.buukle.agent.superagent.domain.AgentEventDelivery;
import com.buukle.agent.superagent.domain.AgentOutboundMessage;
import com.buukle.agent.superagent.domain.AgentWorkItem;
import com.buukle.agent.superagent.repository.AgentEventDeliveryMapper;
import com.buukle.agent.superagent.repository.AgentEventMapper;
import com.buukle.agent.superagent.repository.AgentOutboundMessageMapper;
import com.buukle.agent.superagent.repository.AgentWorkItemMapper;
import com.buukle.agent.superagent.service.impl.SuperAgentRetentionTask;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SuperAgentRetentionTaskTest {

    @Mock
    AgentEventDeliveryMapper deliveryMapper;
    @Mock
    AgentEventMapper eventMapper;
    @Mock
    AgentOutboundMessageMapper outboundMapper;
    @Mock
    AgentWorkItemMapper workItemMapper;
    @Mock
    RedissonClient redissonClient;
    @Mock
    RLock lock;
    @Mock
    AgentMetrics metrics;

    @BeforeEach
    void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, AgentEventDelivery.class);
        TableInfoHelper.initTableInfo(assistant, AgentEvent.class);
        TableInfoHelper.initTableInfo(assistant, AgentOutboundMessage.class);
        TableInfoHelper.initTableInfo(assistant, AgentWorkItem.class);
    }

    @Test
    void run_skipsWhenLockBusy() throws Exception {
        SuperAgentRetentionTask task = task(true);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);

        task.run();

        verify(deliveryMapper, never()).selectList(any());
        verify(deliveryMapper, never()).deleteBatchIds(any());
        verify(lock, never()).unlock();
    }

    @Test
    void run_skipsWhenDisabled() {
        SuperAgentRetentionTask task = task(false);

        task.run();

        verify(redissonClient, never()).getLock(anyString());
        verify(deliveryMapper, never()).selectList(any());
    }

    @Test
    void run_deletesOneBatchAndStopsOnEmpty() throws Exception {
        SuperAgentRetentionTask task = task(true);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(deliveryMapper.selectList(any())).thenReturn(
                List.of(delivery(1L)), List.of());
        when(deliveryMapper.deleteBatchIds(any())).thenReturn(1);

        task.run();

        verify(deliveryMapper).deleteBatchIds(eq(List.of(1L)));
        verify(metrics).add(AgentMetrics.RETENTION_ROWS, "table", "agent_event_delivery", 1);
        verify(lock).unlock();
    }

    private SuperAgentRetentionTask task(boolean enabled) {
        SuperAgentRetentionTask task = new SuperAgentRetentionTask(
                deliveryMapper, eventMapper, outboundMapper, workItemMapper, redissonClient, metrics);
        ReflectionTestUtils.setField(task, "enabled", enabled);
        ReflectionTestUtils.setField(task, "retentionDays", 30);
        ReflectionTestUtils.setField(task, "batchSize", 10);
        ReflectionTestUtils.setField(task, "maxBatches", 5);
        ReflectionTestUtils.setField(task, "batchSleepMs", 0L);
        return task;
    }

    private static AgentEventDelivery delivery(Long id) {
        AgentEventDelivery delivery = new AgentEventDelivery();
        delivery.setId(id);
        return delivery;
    }
}
