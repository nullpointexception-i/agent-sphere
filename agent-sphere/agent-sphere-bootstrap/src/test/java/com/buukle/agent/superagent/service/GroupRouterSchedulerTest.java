package com.buukle.agent.superagent.service;

import com.buukle.agent.common.metrics.AgentMetrics;
import com.buukle.agent.instance.spi.InstanceSpi;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.instance.spi.UserSpi;
import com.buukle.agent.superagent.domain.AgentEvent;
import com.buukle.agent.superagent.domain.AgentEventDelivery;
import com.buukle.agent.superagent.domain.AgentSuperAgent;
import com.buukle.agent.superagent.domain.AgentThread;
import com.buukle.agent.superagent.dtvo.GroupRouterDecision;
import com.buukle.agent.superagent.repository.AgentEventDeliveryMapper;
import com.buukle.agent.superagent.repository.AgentEventMapper;
import com.buukle.agent.superagent.repository.AgentSuperAgentMapper;
import com.buukle.agent.superagent.repository.AgentThreadMapper;
import com.buukle.agent.superagent.service.impl.GroupRouterScheduler;
import com.buukle.agent.superagent.spi.GroupRouterPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GroupRouterSchedulerTest {

    @Mock
    AgentEventMapper eventMapper;
    @Mock
    AgentThreadMapper threadMapper;
    @Mock
    AgentEventDeliveryMapper deliveryMapper;
    @Mock
    AgentSuperAgentMapper superAgentMapper;
    @Mock
    SessionSpi sessionSpi;
    @Mock
    InstanceSpi instanceSpi;
    @Mock
    UserSpi userSpi;
    @Mock
    GroupRouterPort groupRouterPort;
    @Mock
    RedissonClient redissonClient;
    @Mock
    AgentMetrics metrics;

    @org.junit.jupiter.api.BeforeEach
    void stubCurrentRouter() {
        com.buukle.agent.instance.dtvo.vo.InstanceVO system =
                new com.buukle.agent.instance.dtvo.vo.InstanceVO();
        system.setId(7L);
        system.setModelRouteId(99L);
        org.mockito.Mockito.lenient().when(instanceSpi.findCurrentGroupRouter(
                org.mockito.ArgumentMatchers.any())).thenReturn(system);
    }

    @Test
    void parseDecision_readsDeliverIgnoreAndSummary() {
        GroupRouterScheduler scheduler = scheduler();

        GroupRouterDecision decision = ReflectionTestUtils.invokeMethod(scheduler, "parseDecision",
                """
                {"deliver":[{"agentId":7,"reason":"owner"}],
                 "ignore":[{"agentId":8,"reason":"unrelated"}],
                 "summary":"one line"}
                """);

        assertNotNull(decision);
        assertEquals(7L, decision.getDeliver().get(0).getAgentId());
        assertEquals("owner", decision.getDeliver().get(0).getReason());
        assertEquals(8L, decision.getIgnore().get(0).getAgentId());
        assertEquals("one line", decision.getSummary());
    }

    @Test
    void parseDecision_returnsNullForNonJson() {
        GroupRouterScheduler scheduler = scheduler();

        GroupRouterDecision decision = ReflectionTestUtils.invokeMethod(
                scheduler, "parseDecision", "not json");

        assertNull(decision);
    }

    @Test
    void batchReady_trueWhenBatchFull() {
        GroupRouterScheduler scheduler = scheduler();
        ReflectionTestUtils.setField(scheduler, "maxEvents", 20);
        ReflectionTestUtils.setField(scheduler, "maxWaitMs", 3000L);
        when(eventMapper.countPending(10L)).thenReturn(20L);

        Boolean ready = ReflectionTestUtils.invokeMethod(scheduler, "batchReady", 10L);

        assertTrue(ready);
    }

    @Test
    void batchReady_waitsUntilMaxWaitElapsed() {
        GroupRouterScheduler scheduler = scheduler();
        ReflectionTestUtils.setField(scheduler, "maxEvents", 20);
        ReflectionTestUtils.setField(scheduler, "maxWaitMs", 3000L);
        when(eventMapper.countPending(10L)).thenReturn(1L);
        when(eventMapper.selectOldestPendingReceivedAt(10L))
                .thenReturn(LocalDateTime.now().minusSeconds(10));

        Boolean ready = ReflectionTestUtils.invokeMethod(scheduler, "batchReady", 10L);

        assertTrue(ready);
    }

    @Test
    void batchReady_falseWhenWindowStillOpen() {
        GroupRouterScheduler scheduler = scheduler();
        ReflectionTestUtils.setField(scheduler, "maxEvents", 20);
        ReflectionTestUtils.setField(scheduler, "maxWaitMs", 3000L);
        when(eventMapper.countPending(10L)).thenReturn(1L);
        when(eventMapper.selectOldestPendingReceivedAt(10L))
                .thenReturn(LocalDateTime.now());

        Boolean ready = ReflectionTestUtils.invokeMethod(scheduler, "batchReady", 10L);

        assertFalse(ready);
    }

    @Test
    void processThread_writesIgnoreDelivery() {
        GroupRouterScheduler scheduler = scheduler();
        ReflectionTestUtils.setField(scheduler, "maxEvents", 20);
        ReflectionTestUtils.setField(scheduler, "maxChars", 12000);
        ReflectionTestUtils.setField(scheduler, "maxTokens", 2000);
        ReflectionTestUtils.setField(scheduler, "summaryMaxChars", 2000);

        AgentThread thread = thread();
        when(threadMapper.selectById(10L)).thenReturn(thread);
        AgentEvent event = event();
        when(eventMapper.selectList(any())).thenReturn(List.of(event));
        AgentSuperAgent agent = new AgentSuperAgent();
        agent.setId(3L);
        agent.setStatus("ACTIVE");
        when(superAgentMapper.selectBatchIds(any())).thenReturn(List.of(agent));
        when(groupRouterPort.classify(any())).thenReturn(
                "{\"deliver\":[],\"ignore\":[{\"agentId\":3,\"reason\":\"unrelated\"}],\"summary\":\"ok\"}");

        ReflectionTestUtils.invokeMethod(scheduler, "processThread", 10L);

        var captor = org.mockito.ArgumentCaptor.forClass(AgentEventDelivery.class);
        verify(deliveryMapper).insert(captor.capture());
        assertEquals("IGNORE", captor.getValue().getDecision());
        assertEquals("PROCESSED", captor.getValue().getStatus());
        assertEquals("PROCESSED", event.getStatus());
        verify(metrics).increment(AgentMetrics.GROUP_ROUTER_BATCH, "decision", "OBSERVE");
    }

    @Test
    void processThread_schedulesRetryOnLlmFailure() {
        GroupRouterScheduler scheduler = scheduler();
        ReflectionTestUtils.setField(scheduler, "maxEvents", 20);
        ReflectionTestUtils.setField(scheduler, "retryMaxAttempts", 5);
        ReflectionTestUtils.setField(scheduler, "retryBaseBackoffMs", 3000L);

        when(threadMapper.selectById(10L)).thenReturn(thread());
        AgentEvent event = event();
        when(eventMapper.selectList(any())).thenReturn(List.of(event));
        AgentSuperAgent agent = new AgentSuperAgent();
        agent.setId(3L);
        agent.setStatus("ACTIVE");
        when(superAgentMapper.selectBatchIds(any())).thenReturn(List.of(agent));
        when(groupRouterPort.classify(any())).thenThrow(new IllegalStateException("llm down"));

        ReflectionTestUtils.invokeMethod(scheduler, "processThread", 10L);

        assertEquals(1, event.getAttempts());
        assertNotNull(event.getNextRetryAt());
        assertNull(event.getGroupProcessedAt());
        verify(eventMapper).updateById(event);
    }

    private GroupRouterScheduler scheduler() {
        return new GroupRouterScheduler(eventMapper, threadMapper, deliveryMapper, superAgentMapper,
                sessionSpi, instanceSpi, userSpi, groupRouterPort, redissonClient, metrics);
    }

    private static AgentThread thread() {
        AgentThread thread = new AgentThread();
        thread.setId(10L);
        thread.setTenantId(1L);
        thread.setExternalThreadId("group-1");
        thread.setGroupRouterSessionId(20L);
        return thread;
    }

    private static AgentEvent event() {
        AgentEvent event = new AgentEvent();
        event.setId(100L);
        event.setThreadId(10L);
        event.setSeq(1L);
        event.setKind("TEXT");
        event.setPayload("{\"text\":\"hi\",\"rawMeta\":\"{\\\"agentIds\\\":[3]}\"}");
        return event;
    }
}
