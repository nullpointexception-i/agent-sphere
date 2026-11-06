package com.buukle.agent.runtime.orchestration.superagent;

import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.instance.dtvo.enums.SessionType;
import com.buukle.agent.runtime.kernel.port.vo.RunStatus;
import com.buukle.agent.runtime.orchestration.service.ChatRuntimeService;
import com.buukle.agent.superagent.spi.AgentRouterRunCommand;
import com.buukle.agent.superagent.spi.AgentRouterRunResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Parked runs (AWAITING_USER) must not fork into parallel Router runs. */
@ExtendWith(MockitoExtension.class)
class AgentRouterRunServiceTest {

    private static final Long SESSION_ID = 21L;

    @Mock
    SessionSpi sessionSpi;
    @Mock
    RunSpi runSpi;
    @Mock
    ChatRuntimeService chatRuntimeService;

    @InjectMocks
    AgentRouterRunService service;

    @Test
    void awaitingUserRunRejectsNewDeliveriesInsteadOfForking() {
        when(sessionSpi.getSession(SESSION_ID)).thenReturn(routerSession());
        RunVO active = new RunVO();
        active.setId(60L);
        active.setStatus(RunStatus.AWAITING_USER.name());
        when(runSpi.findActiveRun(SESSION_ID)).thenReturn(active);

        AgentRouterRunResult result = service.start(
                new AgentRouterRunCommand(SESSION_ID, 20L, 19L, "hello", null));

        assertFalse(result.accepted());
        assertEquals("router is awaiting user input", result.message());
        verify(chatRuntimeService, never()).chat(any(), any());
    }

    @Test
    void noActiveRunProceedsNormally() {
        when(sessionSpi.getSession(SESSION_ID)).thenReturn(routerSession());
        when(runSpi.findActiveRun(SESSION_ID)).thenReturn(null);
        when(chatRuntimeService.chat(any(), any())).thenReturn(null);

        AgentRouterRunResult result = service.start(
                new AgentRouterRunCommand(SESSION_ID, 20L, 19L, "hello", null));

        assertEquals(true, result.accepted());
        verify(chatRuntimeService).chat(any(), any());
    }

    private SessionVO routerSession() {
        SessionVO session = new SessionVO();
        session.setId(SESSION_ID);
        session.setSessionType(SessionType.ROUTER);
        session.setSuperAgentId(20L);
        session.setThreadId(19L);
        session.setCreatedBy("owner");
        return session;
    }
}
