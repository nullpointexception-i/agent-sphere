package com.buukle.agent.superagent.tool;

import com.buukle.agent.instance.dtvo.enums.SessionType;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.dtvo.vo.UserVO;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.instance.spi.UserSpi;
import com.buukle.agent.superagent.domain.AgentEvent;
import com.buukle.agent.superagent.domain.AgentOutboundMessage;
import com.buukle.agent.superagent.domain.AgentSuperAgent;
import com.buukle.agent.superagent.domain.AgentThread;
import com.buukle.agent.superagent.domain.AgentThreadSubscription;
import com.buukle.agent.superagent.domain.AgentWorkItem;
import com.buukle.agent.superagent.repository.AgentEventMapper;
import com.buukle.agent.superagent.repository.AgentOutboundMessageMapper;
import com.buukle.agent.superagent.repository.AgentSuperAgentMapper;
import com.buukle.agent.superagent.repository.AgentThreadMapper;
import com.buukle.agent.superagent.repository.AgentThreadSubscriptionMapper;
import com.buukle.agent.superagent.repository.AgentWorkItemMapper;
import com.buukle.agent.superagent.service.event.AgentOutboundWakePublisher;
import com.buukle.agent.superagent.service.impl.WorkItemCardProjector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentRouterToolServiceTest {

    @Mock
    SessionSpi sessionSpi;
    @Mock
    RunSpi runSpi;
    @Mock
    UserSpi userSpi;
    @Mock
    AgentSuperAgentMapper superAgentMapper;
    @Mock
    AgentWorkItemMapper workItemMapper;
    @Mock
    AgentOutboundMessageMapper outboundMapper;
    @Mock
    AgentEventMapper eventMapper;
    @Mock
    AgentThreadMapper threadMapper;
    @Mock
    AgentThreadSubscriptionMapper subscriptionMapper;
    @Mock
    WorkItemCardProjector cardProjector;
    @Mock
    ApplicationEventPublisher publisher;

    @Test
    void dispatch_queuesWorkItemAndProjectsCard() {
        AgentRouterToolService service = service();
        stubRouterSession(5L, 7L, 11L);

        AgentRouterToolResult result = service.dispatch(5L, 7L, "summarize thread", 10);

        assertTrue(result.isOk());
        ArgumentCaptor<AgentWorkItem> captor = ArgumentCaptor.forClass(AgentWorkItem.class);
        verify(workItemMapper).insert(captor.capture());
        assertEquals("summarize thread", captor.getValue().getGoal());
        assertEquals("QUEUED", captor.getValue().getStatus());
        assertEquals(11L, captor.getValue().getThreadId());
        verify(cardProjector).projectQueued(captor.getValue());
    }

    @Test
    void sendMessage_text_usesRunThreadAndLimitsReply() {
        AgentRouterToolService service = service();
        stubRouterSession(5L, 7L, 11L);
        RunVO run = new RunVO();
        run.setThreadId(12L);
        when(runSpi.getRun(7L)).thenReturn(run);

        AgentRouterToolResult result = service.sendMessage(5L, 7L, "call-1",
                "hello", "TEXT", null, null, null, null, null);

        assertTrue(result.isOk());
        ArgumentCaptor<AgentOutboundMessage> captor =
                ArgumentCaptor.forClass(AgentOutboundMessage.class);
        verify(outboundMapper).insert(captor.capture());
        assertEquals(12L, captor.getValue().getThreadId());
        assertEquals("router-5-7-call-1", captor.getValue().getIdempotencyKey());
        assertTrue(captor.getValue().getPayload().contains("hello"));
    }

    @Test
    void recall_rejectsEventFromAnotherThread() {
        AgentRouterToolService service = service();
        stubRouterSession(5L, 7L, 11L);
        RunVO run = new RunVO();
        run.setThreadId(12L);
        when(runSpi.getRun(7L)).thenReturn(run);
        AgentEvent event = new AgentEvent();
        event.setId(9L);
        event.setThreadId(99L);
        event.setPayload("{\"text\":\"secret\"}");
        when(eventMapper.selectById(9L)).thenReturn(event);

        AgentRouterToolResult result = service.recall(5L, 7L, 9L);

        assertFalse(result.isOk());
        assertTrue(result.getMessage().contains("current thread"));
    }

    @Test
    void subscribeThread_insertsActiveSubscription() {
        AgentRouterToolService service = service();
        stubRouterSession(5L, 7L, 11L);
        when(subscriptionMapper.insert(any(AgentThreadSubscription.class))).thenAnswer(inv -> {
            AgentThreadSubscription subscription = inv.getArgument(0);
            subscription.setId(30L);
            return 1;
        });

        AgentRouterToolResult result = service.subscribeThread(5L, 7L, "PASS_THROUGH");

        assertTrue(result.isOk());
        assertEquals(30L, result.getId());
        ArgumentCaptor<AgentThreadSubscription> captor =
                ArgumentCaptor.forClass(AgentThreadSubscription.class);
        verify(subscriptionMapper).insert(captor.capture());
        assertEquals("ACTIVE", captor.getValue().getStatus());
        assertEquals(11L, captor.getValue().getThreadId());
    }

    @Test
    void closeThread_marksThreadClosed() {
        AgentRouterToolService service = service();
        stubRouterSession(5L, 7L, 11L);
        AgentThread thread = new AgentThread();
        thread.setId(11L);
        thread.setState("OPEN");
        when(threadMapper.selectById(11L)).thenReturn(thread);

        AgentRouterToolResult result = service.closeThread(5L, 7L, "done");

        assertTrue(result.isOk());
        assertEquals("CLOSED", thread.getState());
        assertEquals("done", thread.getRemark());
        verify(threadMapper).updateById(thread);
    }

    @Test
    void chainedTools_shareResolvedSession() {
        AgentRouterToolService service = service();
        stubRouterSession(5L, 7L, 11L);
        when(subscriptionMapper.insert(any(AgentThreadSubscription.class))).thenAnswer(inv -> {
            AgentThreadSubscription subscription = inv.getArgument(0);
            subscription.setId(31L);
            return 1;
        });

        AgentRouterToolResult dispatch = service.dispatch(5L, 7L, "first", null);
        AgentRouterToolResult send = service.sendMessage(5L, 7L, "call-2",
                "second", "CARD", "TASK", "title", "QUEUED", "summary", 9L);
        AgentRouterToolResult subscribe = service.subscribeThread(5L, 7L, null);

        assertTrue(dispatch.isOk());
        assertTrue(send.isOk());
        assertTrue(subscribe.isOk());
        verify(workItemMapper).insert(any(AgentWorkItem.class));
        verify(outboundMapper).insert(any(AgentOutboundMessage.class));
        verify(subscriptionMapper).insert(any(AgentThreadSubscription.class));
    }

    @Test
    void dispatch_rejectsBlankGoal() {
        AgentRouterToolService service = service();
        stubRouterSession(5L, 7L, 11L);

        AgentRouterToolResult result = service.dispatch(5L, 7L, " ", null);

        assertFalse(result.isOk());
        verify(workItemMapper, org.mockito.Mockito.never()).insert(any(AgentWorkItem.class));
    }

    @Test
    void invalidSession_returnsFailure() {
        AgentRouterToolService service = service();
        when(sessionSpi.getSession(99L)).thenReturn(null);

        AgentRouterToolResult result = service.dispatch(99L, 7L, "goal", null);

        assertFalse(result.isOk());
    }

    @Test
    void sendFinalReply_persistsWhenNoSendMessage() {
        AgentRouterToolService service = service();
        stubRouterSession(5L, 7L, 11L);
        when(outboundMapper.selectCount(any())).thenReturn(0L);
        when(outboundMapper.insert(any(AgentOutboundMessage.class))).thenAnswer(inv -> {
            inv.getArgument(0, AgentOutboundMessage.class).setId(501L);
            return 1;
        });

        service.sendFinalReply(5L, 7L, 11L, "已完成汇总，详见消息列表。");

        ArgumentCaptor<AgentOutboundMessage> captor =
                ArgumentCaptor.forClass(AgentOutboundMessage.class);
        verify(outboundMapper).insert(captor.capture());
        assertEquals("router-final-5-7", captor.getValue().getIdempotencyKey());
        assertEquals(11L, captor.getValue().getThreadId());
        assertTrue(captor.getValue().getPayload().contains("已完成汇总"));
        verify(publisher).publishEvent(any(Object.class));
    }

    @Test
    void sendFinalReply_skipsWhenRunAlreadyReplied() {
        AgentRouterToolService service = service();
        stubRouterSession(5L, 7L, 11L);
        when(outboundMapper.selectCount(any())).thenReturn(1L);

        service.sendFinalReply(5L, 7L, 11L, "duplicate");

        verify(outboundMapper, never()).insert(any(AgentOutboundMessage.class));
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void sendFinalReply_ignoresBlankText() {
        AgentRouterToolService service = service();

        service.sendFinalReply(5L, 7L, 11L, " ");

        verify(outboundMapper, never()).selectCount(any());
        verify(outboundMapper, never()).insert(any(AgentOutboundMessage.class));
    }

    private AgentRouterToolService service() {
        AgentRouterToolService service = new AgentRouterToolService(
                sessionSpi, runSpi, userSpi, superAgentMapper, workItemMapper, outboundMapper,
                eventMapper, threadMapper, subscriptionMapper, cardProjector,
                new AgentOutboundWakePublisher(publisher));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "imMaxWords", 100);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "finalMaxChars", 8000);
        return service;
    }

    private void stubRouterSession(Long sessionId, Long runId, Long threadId) {
        SessionVO session = new SessionVO();
        session.setId(sessionId);
        session.setSessionType(SessionType.ROUTER);
        session.setSuperAgentId(3L);
        session.setThreadId(threadId);
        when(sessionSpi.getSession(sessionId)).thenReturn(session);
        AgentSuperAgent agent = new AgentSuperAgent();
        agent.setId(3L);
        agent.setTenantId(1L);
        agent.setOwnerUserId(100L);
        when(superAgentMapper.selectById(3L)).thenReturn(agent);
        UserVO owner = new UserVO();
        owner.setUsername("alice");
        when(userSpi.getByUserId(100L)).thenReturn(owner);
    }
}
