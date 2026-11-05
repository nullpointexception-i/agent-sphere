package com.buukle.agent.superagent.service;

import com.buukle.agent.common.metrics.AgentMetrics;
import com.buukle.agent.superagent.domain.AgentEventDelivery;
import com.buukle.agent.superagent.domain.AgentOutboundMessage;
import com.buukle.agent.superagent.domain.AgentWatchdogRun;
import com.buukle.agent.superagent.domain.AgentWorkItem;
import com.buukle.agent.superagent.repository.AgentEventDeliveryMapper;
import com.buukle.agent.superagent.repository.AgentOutboundMessageMapper;
import com.buukle.agent.superagent.repository.AgentWatchdogRunMapper;
import com.buukle.agent.superagent.repository.AgentWorkItemMapper;
import com.buukle.agent.superagent.service.impl.SuperAgentWatchdogTask;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SuperAgentWatchdogTaskTest {

    @Mock
    AgentWorkItemMapper workItemMapper;
    @Mock
    AgentEventDeliveryMapper deliveryMapper;
    @Mock
    AgentOutboundMessageMapper outboundMapper;
    @Mock
    AgentWatchdogRunMapper runMapper;
    @Mock
    RedissonClient redissonClient;
    @Mock
    RLock lock;
    @Mock
    AgentMetrics metrics;

    @BeforeEach
    void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, AgentWorkItem.class);
        TableInfoHelper.initTableInfo(assistant, AgentEventDelivery.class);
        TableInfoHelper.initTableInfo(assistant, AgentOutboundMessage.class);
    }

    @Test
    void run_recoversThreeStuckKinds_andRecordsSuccess() throws Exception {
        SuperAgentWatchdogTask task = task(true);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(workItemMapper.selectList(any())).thenReturn(
                List.of(workItem(1L)), List.of());
        when(deliveryMapper.selectList(any())).thenReturn(
                List.of(delivery(2L)), List.of());
        when(outboundMapper.selectList(any())).thenReturn(
                List.of(outbound(3L)), List.of());
        when(workItemMapper.update(any(), any())).thenReturn(2);
        when(deliveryMapper.update(any(), any())).thenReturn(3);
        when(outboundMapper.update(any(), any())).thenReturn(4);

        task.run();

        ArgumentCaptor<AgentWatchdogRun> captor = ArgumentCaptor.forClass(AgentWatchdogRun.class);
        verify(runMapper).insert(captor.capture());
        verify(runMapper).updateById(captor.capture());
        AgentWatchdogRun saved = captor.getValue();
        assertEquals("SUCCESS", saved.getStatus());
        assertEquals(2, saved.getDispatchedStuck());
        assertEquals(3, saved.getDeliveryStuck());
        assertEquals(4, saved.getOutboundStuck());
        verify(metrics).increment(AgentMetrics.WATCHDOG_RECOVERED, "kind", "DISPATCH");
        verify(metrics).increment(AgentMetrics.WATCHDOG_RECOVERED, "kind", "DELIVERY");
        verify(metrics).increment(AgentMetrics.WATCHDOG_RECOVERED, "kind", "OUTBOUND");
        verify(lock).unlock();
    }

    @Test
    void run_skipsWhenAnotherReplicaHoldsLock() throws Exception {
        SuperAgentWatchdogTask task = task(true);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);

        task.run();

        verify(runMapper, never()).insert(any(AgentWatchdogRun.class));
        verify(workItemMapper, never()).update(any(), any());
        verify(lock, never()).unlock();
    }

    @Test
    void run_skipsWhenDisabled() {
        SuperAgentWatchdogTask task = task(false);

        task.run();

        verify(redissonClient, never()).getLock(anyString());
        verify(runMapper, never()).insert(any(AgentWatchdogRun.class));
    }

    private SuperAgentWatchdogTask task(boolean enabled) {
        SuperAgentWatchdogTask task = new SuperAgentWatchdogTask(
                workItemMapper, deliveryMapper, outboundMapper, runMapper, redissonClient, metrics);
        ReflectionTestUtils.setField(task, "superAgentEnabled", true);
        ReflectionTestUtils.setField(task, "enabled", enabled);
        ReflectionTestUtils.setField(task, "staleMinutes", 15);
        ReflectionTestUtils.setField(task, "batchSize", 10);
        ReflectionTestUtils.setField(task, "maxBatches", 20);
        ReflectionTestUtils.setField(task, "batchSleepMs", 0L);
        return task;
    }

    private static AgentWorkItem workItem(Long id) {
        AgentWorkItem item = new AgentWorkItem();
        item.setId(id);
        return item;
    }

    private static AgentEventDelivery delivery(Long id) {
        AgentEventDelivery delivery = new AgentEventDelivery();
        delivery.setId(id);
        return delivery;
    }

    private static AgentOutboundMessage outbound(Long id) {
        AgentOutboundMessage message = new AgentOutboundMessage();
        message.setId(id);
        return message;
    }
}
