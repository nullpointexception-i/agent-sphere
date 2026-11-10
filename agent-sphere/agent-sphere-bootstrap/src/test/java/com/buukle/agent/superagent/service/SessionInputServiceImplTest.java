package com.buukle.agent.superagent.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.TenantContext;
import com.buukle.agent.common.metrics.AgentMetrics;
import com.buukle.agent.instance.dtvo.vo.UserVO;
import com.buukle.agent.instance.spi.UserSpi;
import com.buukle.agent.superagent.domain.AgentOutboundMessage;
import com.buukle.agent.superagent.domain.AgentSessionInput;
import com.buukle.agent.superagent.domain.AgentSuperAgent;
import com.buukle.agent.superagent.repository.AgentOutboundMessageMapper;
import com.buukle.agent.superagent.repository.AgentSessionInputMapper;
import com.buukle.agent.superagent.repository.AgentEventMapper;
import com.buukle.agent.superagent.repository.AgentSuperAgentMapper;
import com.buukle.agent.superagent.service.event.AgentOutboundWakePublisher;
import com.buukle.agent.superagent.service.impl.SessionInputServiceImpl;
import com.buukle.agent.superagent.spi.RouterInputResumePort;
import com.buukle.agent.superagent.spi.SessionInputCommand;
import com.buukle.agent.superagent.spi.SessionInputResolveCommand;
import com.buukle.agent.superagent.spi.SessionInputStatus;
import com.buukle.agent.superagent.spi.SessionInputType;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Router parked-input projection: bridge row + DM card outbox + resume routing. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SessionInputServiceImplTest {

    private static final Long AGENT_ID = 20L;
    private static final Long SESSION_ID = 21L;
    private static final Long RUN_ID = 60L;
    private static final Long THREAD_ID = 19L;

    @Mock
    AgentSessionInputMapper inputMapper;
    @Mock
    AgentEventMapper eventMapper;
    @Mock
    AgentOutboundMessageMapper outboundMapper;
    @Mock
    AgentOutboundWakePublisher wakePublisher;
    @Mock
    AgentSuperAgentMapper superAgentMapper;
    @Mock
    UserSpi userSpi;
    @Mock
    ObjectProvider<RouterInputResumePort> resumePortProvider;
    @Mock
    RouterInputResumePort resumePort;
    @Mock
    ObjectProvider<MeterRegistry> registryProvider;
    @Mock
    org.springframework.context.ApplicationEventPublisher eventPublisher;

    SessionInputServiceImpl service;

    @BeforeEach
    void setUp() {
        AuthContext.setUserId(9L);
        AuthContext.setUsername("owner");
        when(resumePortProvider.getIfAvailable()).thenReturn(resumePort);
        service = new SessionInputServiceImpl(inputMapper, eventMapper, outboundMapper,
                wakePublisher, superAgentMapper, userSpi, new AgentMetrics(registryProvider),
                resumePortProvider, eventPublisher);
        AgentSuperAgent agent = new AgentSuperAgent();
        agent.setId(AGENT_ID);
        agent.setTenantId(1L);
        agent.setOwnerUserId(9L);
        when(superAgentMapper.selectById(AGENT_ID)).thenReturn(agent);
        UserVO owner = new UserVO();
        owner.setUsername("owner");
        when(userSpi.getByUserId(9L)).thenReturn(owner);
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
        TenantContext.clear();
    }

    @Test
    void projectPendingPersistsBridgeRowAndEnqueuesCard() {
        stubInsertIds();
        SessionInputCommand command = clarificationCommand("c1abc123");

        service.projectPending(command);

        ArgumentCaptor<AgentSessionInput> rowCaptor = ArgumentCaptor.forClass(AgentSessionInput.class);
        verify(inputMapper).insert(rowCaptor.capture());
        assertEquals(SessionInputType.CLARIFICATION, rowCaptor.getValue().getInputType());
        assertEquals(THREAD_ID, rowCaptor.getValue().getThreadId());
        assertEquals("owner", rowCaptor.getValue().getCreatedBy());

        ArgumentCaptor<AgentOutboundMessage> outboundCaptor =
                ArgumentCaptor.forClass(AgentOutboundMessage.class);
        verify(outboundMapper, atLeastOnce()).insert(outboundCaptor.capture());
        assertTrue(outboundCaptor.getValue().getIdempotencyKey()
                .startsWith("router-input-clarification-c1abc123"));
        verify(wakePublisher, atLeastOnce()).publish(any());
    }

    @Test
    void projectPendingDuplicateInputSkipsCard() {
        when(inputMapper.insert(any(AgentSessionInput.class)))
                .thenThrow(new DuplicateKeyException("dup"));

        service.projectPending(clarificationCommand("c1abc123"));

        verify(outboundMapper, never()).insert(any(AgentOutboundMessage.class));
        verify(wakePublisher, never()).publish(any());
    }

    @Test
    void answerRouterClarificationResumesRunAndMarksAnswered() {
        AgentSessionInput pending = pendingInput(SessionInputType.CLARIFICATION, "c1abc123");
        when(inputMapper.selectOne(any(Wrapper.class))).thenReturn(pending);
        // Bridge projectResolved marks the row ANSWERED earlier in the same call chain;
        // keep the double resolve active only when it has not happened.
        when(inputMapper.selectById(pending.getId())).thenReturn(pending);

        boolean consumed = service.answerRouterClarification(THREAD_ID, "Travel");

        assertTrue(consumed);
        verify(resumePort).answerClarification(SESSION_ID, RUN_ID, "c1abc123", "Travel");
        ArgumentCaptor<AgentSessionInput> patch = ArgumentCaptor.forClass(AgentSessionInput.class);
        verify(inputMapper, atLeastOnce()).updateById(patch.capture());
        assertTrue(patch.getAllValues().stream()
                .anyMatch(row -> SessionInputStatus.ANSWERED.equals(row.getStatus())));
    }

    @Test
    void answerRouterClarificationSkipsReResolveWhenSyncEventAlreadyResolved() {
        // PostgreSQL abort: projectResolved (via the synchronous RESPONDED event) has
        // already marked the row and enqueued the ANSWERED card; a second markResolved +
        // updateCard would trip the outbound idempotency index and silently roll back
        // the caller's transaction.
        AgentSessionInput pending = pendingInput(SessionInputType.CLARIFICATION, "c1abc123");
        when(inputMapper.selectOne(any(Wrapper.class))).thenReturn(pending);
        AgentSessionInput resolved = pendingInput(SessionInputType.CLARIFICATION, "c1abc123");
        resolved.setStatus(SessionInputStatus.ANSWERED);
        when(inputMapper.selectById(pending.getId())).thenReturn(resolved);

        boolean consumed = service.answerRouterClarification(THREAD_ID, "Travel");

        assertTrue(consumed);
        verify(resumePort).answerClarification(SESSION_ID, RUN_ID, "c1abc123", "Travel");
        // The answer itself is persisted before resuming; only the status patch is skipped.
        ArgumentCaptor<AgentSessionInput> patches = ArgumentCaptor.forClass(AgentSessionInput.class);
        verify(inputMapper, atLeastOnce()).updateById(patches.capture());
        assertTrue(patches.getAllValues().stream().noneMatch(row -> row.getStatus() != null));
        verify(outboundMapper, never()).insert(any(AgentOutboundMessage.class));
    }

    @Test
    void answerRouterClarificationWithoutPendingReturnsFalse() {
        when(inputMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        assertFalse(service.answerRouterClarification(THREAD_ID, "Travel"));

        verify(resumePort, never()).answerClarification(any(), any(), any(), any());
    }

    @Test
    void resolveRouterApprovalResolvesAndAnswers() {
        AgentSessionInput pending = pendingInput(SessionInputType.APPROVAL, "ap1abc456");
        when(inputMapper.selectOne(any(Wrapper.class))).thenReturn(pending);
        when(inputMapper.selectById(pending.getId())).thenReturn(pending);
        String decisions = "[{\"callId\":\"call_1\",\"decision\":\"ALLOW_ONCE\"}]";

        service.resolveRouterApproval(THREAD_ID, "ap1abc456", decisions);

        verify(resumePort).resolveApproval(SESSION_ID, RUN_ID, "ap1abc456",
                decisions, "owner");
        ArgumentCaptor<AgentSessionInput> patches = ArgumentCaptor.forClass(AgentSessionInput.class);
        verify(inputMapper, atLeastOnce()).updateById(patches.capture());
        assertTrue(patches.getAllValues().stream()
                .anyMatch(row -> decisions.equals(row.getDecisionsJson())));
    }

    @Test
    void resolveRouterApprovalSkipsReResolveWhenSyncEventAlreadyResolved() {
        AgentSessionInput pending = pendingInput(SessionInputType.APPROVAL, "ap1abc456");
        when(inputMapper.selectOne(any(Wrapper.class))).thenReturn(pending);
        AgentSessionInput resolved = pendingInput(SessionInputType.APPROVAL, "ap1abc456");
        resolved.setStatus(SessionInputStatus.ANSWERED);
        when(inputMapper.selectById(pending.getId())).thenReturn(resolved);

        service.resolveRouterApproval(THREAD_ID, "ap1abc456",
                "[{\"callId\":\"call_1\",\"decision\":\"ALLOW_ONCE\"}]");

        // Decisions are persisted before resuming; only the status patch + card update are skipped.
        ArgumentCaptor<AgentSessionInput> patches = ArgumentCaptor.forClass(AgentSessionInput.class);
        verify(inputMapper, atLeastOnce()).updateById(patches.capture());
        assertTrue(patches.getAllValues().stream().noneMatch(row -> row.getStatus() != null));
        verify(outboundMapper, never()).insert(any(AgentOutboundMessage.class));
    }

    @Test
    void projectResolvedUpdatesCardInPlace() {
        AgentSessionInput pending = pendingInput(SessionInputType.CLARIFICATION, "c1abc123");
        pending.setCardOutboundId(500L);
        when(inputMapper.selectOne(any(Wrapper.class))).thenReturn(pending);
        when(inputMapper.selectById(pending.getId())).thenReturn(pending);
        AgentOutboundMessage cardOutbound = new AgentOutboundMessage();
        cardOutbound.setExternalMessageId("9001");
        when(outboundMapper.selectById(500L)).thenReturn(cardOutbound);
        stubInsertIds();

        service.projectResolved(new SessionInputResolveCommand(
                SessionInputType.CLARIFICATION, "c1abc123", SessionInputStatus.CANCELLED));

        assertEquals(SessionInputStatus.CANCELLED, pending.getStatus());
        ArgumentCaptor<AgentOutboundMessage> updateCaptor =
                ArgumentCaptor.forClass(AgentOutboundMessage.class);
        verify(outboundMapper).insert(updateCaptor.capture());
        assertEquals("router-input-clarification-c1abc123-CANCELLED",
                updateCaptor.getValue().getIdempotencyKey());
        verify(wakePublisher).publish(any());
    }

    @Test
    void projectResolvedPinsCardUpdateToInputTenant() {
        // Regression: the ANSWERED/CANCELLED card outbound used to be stamped with
        // SYSTEM tenant 0, so delivery failed with "conversation not found" and the
        // card stayed pending forever.
        AgentSessionInput pending = pendingInput(SessionInputType.APPROVAL, "ap1abc456");
        pending.setTenantId(7L);
        pending.setCardOutboundId(500L);
        when(inputMapper.selectOne(any(Wrapper.class))).thenReturn(pending);
        when(inputMapper.selectById(pending.getId())).thenReturn(pending);
        AgentOutboundMessage cardOutbound = new AgentOutboundMessage();
        cardOutbound.setExternalMessageId("9001");
        when(outboundMapper.selectById(500L)).thenReturn(cardOutbound);
        stubInsertIds();
        final Long[] tenantAtInsert = new Long[1];
        when(outboundMapper.insert(any(AgentOutboundMessage.class))).thenAnswer(inv -> {
            tenantAtInsert[0] = TenantContext.get();
            AgentOutboundMessage row = inv.getArgument(0);
            row.setId(500L);
            return 1;
        });

        service.projectResolved(new SessionInputResolveCommand(
                SessionInputType.APPROVAL, "ap1abc456", SessionInputStatus.ANSWERED));

        assertEquals(7L, tenantAtInsert[0]);
        verify(outboundMapper).insert(any(AgentOutboundMessage.class));
    }

    @Test
    void projectRunTerminalCancelsAllPendingInputs() {
        AgentSessionInput pendingA = pendingInput(SessionInputType.CLARIFICATION, "c1abc123");
        AgentSessionInput pendingB = pendingInput(SessionInputType.APPROVAL, "ap1abc456");
        when(inputMapper.selectList(any(Wrapper.class))).thenReturn(java.util.List.of(pendingA, pendingB));
        when(inputMapper.selectById(any(Long.class))).thenReturn(null);

        service.projectRunTerminal(RUN_ID);

        verify(inputMapper, atLeastOnce()).updateById(any(AgentSessionInput.class));
        assertEquals(SessionInputStatus.CANCELLED, pendingA.getStatus());
        assertEquals(SessionInputStatus.CANCELLED, pendingB.getStatus());
    }

    private SessionInputCommand clarificationCommand(String clarificationId) {
        return new SessionInputCommand(AGENT_ID, THREAD_ID, SESSION_ID, RUN_ID,
                SessionInputType.CLARIFICATION, clarificationId,
                "Missing expense type", "Please add", "[\"Travel\",\"Office\"]", false, null);
    }

    private AgentSessionInput pendingInput(String inputType, String inputId) {
        AgentSessionInput input = new AgentSessionInput();
        input.setId(88L);
        input.setSuperAgentId(AGENT_ID);
        input.setThreadId(THREAD_ID);
        input.setSessionId(SESSION_ID);
        input.setRunId(RUN_ID);
        input.setInputType(inputType);
        input.setInputId(inputId);
        input.setTitle("Missing expense type");
        input.setStatus(SessionInputStatus.PENDING);
        return input;
    }

    private void stubInsertIds() {
        when(inputMapper.insert(any(AgentSessionInput.class))).thenAnswer(inv -> {
            AgentSessionInput row = inv.getArgument(0);
            row.setId(88L);
            return 1;
        });
        when(outboundMapper.insert(any(AgentOutboundMessage.class))).thenAnswer(inv -> {
            AgentOutboundMessage row = inv.getArgument(0);
            row.setId(500L);
            return 1;
        });
    }
}
