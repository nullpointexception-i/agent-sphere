package com.buukle.agent.superagent.service;

import com.buukle.agent.common.metrics.AgentMetrics;
import com.buukle.agent.instance.dtvo.dto.CreateSessionDTO;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.superagent.domain.AgentSuperAgent;
import com.buukle.agent.superagent.domain.AgentThread;
import com.buukle.agent.superagent.repository.AgentEventDeliveryMapper;
import com.buukle.agent.superagent.repository.AgentEventMapper;
import com.buukle.agent.superagent.repository.AgentSuperAgentMapper;
import com.buukle.agent.superagent.repository.AgentThreadMapper;
import com.buukle.agent.superagent.service.impl.AgentRouterScheduler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Each avatar gets its own Router session inside one group thread. */
class AgentRouterSessionTest {

    private static final Long THREAD_ID = 26L;

    @Test
    void reusesOnlyTheSameAgentsRouterSession() {
        SessionSpi sessionSpi = mock(SessionSpi.class);
        AgentThreadMapper threadMapper = mock(AgentThreadMapper.class);
        SessionVO existing = new SessionVO();
        existing.setId(100L);
        when(sessionSpi.findRouterSession(THREAD_ID, 8L)).thenReturn(existing);

        AgentRouterScheduler scheduler = scheduler(sessionSpi, threadMapper);
        Long id = ReflectionTestUtils.invokeMethod(scheduler, "ensureRouterSession",
                agent(8L, 70L), THREAD_ID);

        assertEquals(100L, id);
        verify(sessionSpi, never()).createSession(any());
    }

    @Test
    void createsASeparateSessionForTheSecondAvatar() {
        SessionSpi sessionSpi = mock(SessionSpi.class);
        AgentThreadMapper threadMapper = mock(AgentThreadMapper.class);
        AgentThread thread = new AgentThread();
        thread.setId(THREAD_ID);
        thread.setRouterSessionId(100L); // first avatar's legacy pointer
        thread.setCreatedBy("testx003");
        when(sessionSpi.findRouterSession(THREAD_ID, 9L)).thenReturn(null);
        when(threadMapper.selectById(THREAD_ID)).thenReturn(thread);
        SessionVO created = new SessionVO();
        created.setId(200L);
        when(sessionSpi.createSession(any())).thenReturn(created);

        AgentRouterScheduler scheduler = scheduler(sessionSpi, threadMapper);
        Long id = ReflectionTestUtils.invokeMethod(scheduler, "ensureRouterSession",
                agent(9L, 71L), THREAD_ID);

        assertEquals(200L, id);
        ArgumentCaptor<CreateSessionDTO> captor =
                ArgumentCaptor.forClass(CreateSessionDTO.class);
        verify(sessionSpi).createSession(captor.capture());
        assertEquals(9L, captor.getValue().getSuperAgentId());
        assertEquals(71L, captor.getValue().getAgentInstanceId());
        // The router session belongs to the avatar owner, not the group message sender.
        assertEquals("elvin", captor.getValue().getCreatedBy());
        // The legacy pointer is not overwritten by the second avatar.
        assertEquals(100L, thread.getRouterSessionId());
    }

    private static AgentSuperAgent agent(Long id, Long routerInstanceId) {
        AgentSuperAgent agent = new AgentSuperAgent();
        agent.setId(id);
        agent.setName("Agent " + id);
        agent.setRouterInstanceId(routerInstanceId);
        agent.setCreatedBy("elvin");
        return agent;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static AgentRouterScheduler scheduler(
            SessionSpi sessionSpi, AgentThreadMapper threadMapper) {
        ObjectProvider provider = mock(ObjectProvider.class);
        return new AgentRouterScheduler(
                mock(AgentEventDeliveryMapper.class),
                mock(AgentEventMapper.class),
                mock(AgentSuperAgentMapper.class),
                threadMapper,
                sessionSpi,
                mock(com.buukle.agent.instance.spi.RunSpi.class),
                mock(com.buukle.agent.superagent.repository.AgentRouterForkMapper.class),
                provider,
                provider,
                mock(AgentMetrics.class),
                mock(RedissonClient.class),
                mock(Executor.class));
    }
}
