package com.buukle.agent.runtime.orchestration.superagent;

import com.buukle.agent.instance.dtvo.enums.RunStatus;
import com.buukle.agent.instance.dtvo.enums.SessionType;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.runtime.orchestration.constants.ChatConstant;
import com.buukle.agent.runtime.orchestration.dtvo.vo.ChatMessageResponseVO;
import com.buukle.agent.runtime.orchestration.service.ChatRuntimeService;
import com.buukle.agent.superagent.spi.GroupRouterRunCommand;
import com.buukle.agent.superagent.spi.GroupRouterRunResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Group Router batches run through the normal runtime and return the final assistant text. */
@ExtendWith(MockitoExtension.class)
class GroupRouterRunServiceTest {

    private static final Long SESSION_ID = 31L;
    private static final Long RUN_ID = 300L;

    @Mock
    SessionSpi sessionSpi;
    @Mock
    RunSpi runSpi;
    @Mock
    ChatRuntimeService chatRuntimeService;

    @InjectMocks
    GroupRouterRunService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "runTimeoutMs", 5000L);
    }

    @Test
    void returnsAssistantReplyWhenRunCompletes() {
        when(sessionSpi.getSession(SESSION_ID)).thenReturn(routerSession());
        when(runSpi.findActiveRun(SESSION_ID)).thenReturn(null);
        when(chatRuntimeService.chat(any(), any())).thenReturn(response(CHAT_RUNNING));
        RunVO run = run(RunStatus.COMPLETED, "{\"deliver\":[]}");
        when(runSpi.getRun(RUN_ID)).thenReturn(run);

        GroupRouterRunResult result = service.route(command());

        assertTrue(result.accepted());
        assertEquals("{\"deliver\":[]}", result.reply());
        assertEquals(RUN_ID, result.runId());
    }

    @Test
    void rejectsWhenSessionIsBusy() {
        when(sessionSpi.getSession(SESSION_ID)).thenReturn(routerSession());
        RunVO active = new RunVO();
        active.setId(99L);
        active.setStatus(RunStatus.RUNNING.name());
        when(runSpi.findActiveRun(SESSION_ID)).thenReturn(active);

        GroupRouterRunResult result = service.route(command());

        assertFalse(result.accepted());
        assertEquals("group router is busy", result.message());
        verify(chatRuntimeService, never()).chat(any(), any());
    }

    @Test
    void rejectsWhenRuntimeQueuesTheMessage() {
        when(sessionSpi.getSession(SESSION_ID)).thenReturn(routerSession());
        when(runSpi.findActiveRun(SESSION_ID)).thenReturn(null);
        ChatMessageResponseVO queued = response(CHAT_RUNNING);
        queued.setStatus(ChatConstant.RESPONSE_STATUS_QUEUED);
        when(chatRuntimeService.chat(any(), any())).thenReturn(queued);

        GroupRouterRunResult result = service.route(command());

        assertFalse(result.accepted());
        assertEquals("group router is busy", result.message());
    }

    @Test
    void rejectsFailedRunWithErrorMessage() {
        when(sessionSpi.getSession(SESSION_ID)).thenReturn(routerSession());
        when(runSpi.findActiveRun(SESSION_ID)).thenReturn(null);
        when(chatRuntimeService.chat(any(), any())).thenReturn(response(CHAT_RUNNING));
        RunVO run = run(RunStatus.FAILED, null);
        run.setErrorMessage("provider unavailable");
        when(runSpi.getRun(RUN_ID)).thenReturn(run);

        GroupRouterRunResult result = service.route(command());

        assertFalse(result.accepted());
        assertEquals("provider unavailable", result.message());
    }

    @Test
    void stopsRunAndRejectsOnTimeout() {
        ReflectionTestUtils.setField(service, "runTimeoutMs", 0L);
        when(sessionSpi.getSession(SESSION_ID)).thenReturn(routerSession());
        when(runSpi.findActiveRun(SESSION_ID)).thenReturn(null);
        when(chatRuntimeService.chat(any(), any())).thenReturn(response(CHAT_RUNNING));

        GroupRouterRunResult result = service.route(command());

        assertFalse(result.accepted());
        assertEquals("group router run timed out", result.message());
        verify(chatRuntimeService).stopRun(SESSION_ID, RUN_ID);
    }

    private static final String CHAT_RUNNING = "PROCESSING";

    private GroupRouterRunCommand command() {
        return new GroupRouterRunCommand(SESSION_ID, 19L, 2L, "batch prompt");
    }

    private static SessionVO routerSession() {
        SessionVO session = new SessionVO();
        session.setId(SESSION_ID);
        session.setSessionType(SessionType.GROUP_ROUTER);
        session.setCreatedBy("owner");
        return session;
    }

    private static ChatMessageResponseVO response(String status) {
        ChatMessageResponseVO response = new ChatMessageResponseVO();
        response.setRunId(RUN_ID);
        response.setStatus(status);
        return response;
    }

    private static RunVO run(RunStatus status, String reply) {
        RunVO run = new RunVO();
        run.setId(RUN_ID);
        run.setStatus(status.name());
        run.setAssistantReply(reply);
        return run;
    }
}
