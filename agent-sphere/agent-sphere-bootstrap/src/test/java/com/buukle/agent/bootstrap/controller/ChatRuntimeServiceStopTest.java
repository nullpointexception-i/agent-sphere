package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.instance.dtvo.vo.RunVO;
import com.buukle.agent.instance.repository.AgentPendingClarificationMapper;
import com.buukle.agent.instance.spi.PendingWaitSpi;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.runtime.kernel.port.vo.RunStatus;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventVO;
import com.buukle.agent.runtime.kernel.runner.SessionInputManager;
import com.buukle.agent.runtime.kernel.runner.SessionRunner;
import com.buukle.agent.runtime.kernel.service.ToolApprovalService;
import com.buukle.agent.runtime.orchestration.handler.TimelineRecorder;
import com.buukle.agent.runtime.orchestration.orchestrator.RuntimeOrchestrator;
import com.buukle.agent.runtime.orchestration.service.ChatAttachmentService;
import com.buukle.agent.runtime.orchestration.service.ChatRuntimeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Stop-button regression guard — focused on the WAITING parked state.
 *
 * <p>The loop of a WAITING run (suspended by the wait tool) has already exited, so nothing consumes the cancel flag; stop must therefore
 * "set the terminal state directly + cancel the pending record + publish run_cancelled"; otherwise the run cannot be stopped, the
 * waker resumes it when due, and the frontend stays locked in "running / stop has no effect" because no terminal event arrives.
 */
@ExtendWith(MockitoExtension.class)
class ChatRuntimeServiceStopTest {

    @Mock
    RuntimeOrchestrator orchestrator;
    @Mock
    RunSpi runSpi;
    @Mock
    SessionSpi sessionSpi;
    @Mock
    ApplicationEventPublisher eventPublisher;
    @Mock
    AgentPendingClarificationMapper clarificationMapper;
    @Mock
    SessionRunner sessionRunner;
    @Mock
    TimelineRecorder timelineRecorder;
    @Mock
    ChatAttachmentService chatAttachmentService;
    @Mock
    PendingWaitSpi pendingWaitSpi;

    ChatRuntimeService service;

    @BeforeEach
    void setUp() {
        service = new ChatRuntimeService(orchestrator, runSpi, sessionSpi, eventPublisher,
                clarificationMapper, sessionRunner, timelineRecorder, chatAttachmentService, pendingWaitSpi, mock(ToolApprovalService.class), mock(SessionInputManager.class),
                mock(com.buukle.agent.runtime.orchestration.pipeline.RuntimeValidator.class),
                mock(com.buukle.agent.runtime.kernel.service.ReasoningEffortResolver.class),
                mock(com.buukle.agent.runtime.kernel.config.LlmRequestConfigurer.class),
                mock(com.buukle.agent.runtime.kernel.service.CompactionService.class));
        AuthContext.setSuperAdmin(true); // Skip the session ownership check (this test only cares about stop logic)
        lenient().when(clarificationMapper.selectList(any())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        AuthContext.setSuperAdmin(false);
    }

    private static RunVO run(String status) {
        RunVO run = new RunVO();
        run.setId(7L);
        run.setSessionId(1L);
        run.setStatus(status);
        return run;
    }

    @Test
    void stopRun_whileWaiting_cancelsRunPendingWaitAndEmitsCancelled() {
        RunVO waiting = run(RunStatus.WAITING.name());
        when(runSpi.getRun(7L)).thenReturn(waiting);
        when(runSpi.cancelIfActive(7L)).thenReturn(true);
        when(pendingWaitSpi.cancelPendingByRunId(7L)).thenReturn(1);

        service.stopRun(1L, 7L);

        verify(runSpi).cancelIfActive(7L);
        verify(pendingWaitSpi).cancelPendingByRunId(7L);
        ArgumentCaptor<RuntimeEventVO> cap = ArgumentCaptor.forClass(RuntimeEventVO.class);
        verify(eventPublisher).publishEvent(cap.capture());
        assertEquals(RunStatus.CANCELLED, cap.getValue().getEventType(), "必须发 run_cancelled 终态");
        // The parked state must also set the cancel flag: wake/resume paths must see the cancellation, otherwise a new wait after cancel gets woken again
        verify(sessionRunner).cancelRun(7L);
    }

    @Test
    void stopRun_whileAwaitingUser_stillWorks() {
        RunVO awaiting = run(RunStatus.AWAITING_USER.name());
        when(runSpi.getRun(7L)).thenReturn(awaiting);
        when(runSpi.cancelIfActive(7L)).thenReturn(true);

        service.stopRun(1L, 7L);

        verify(runSpi).cancelIfActive(7L);
        verify(sessionRunner).cancelRun(7L);
    }

    @Test
    void stopRun_whileRunning_setsCancelFlagAndConditionallyCancels() {
        RunVO running = run(RunStatus.RUNNING.name());
        when(runSpi.getRun(7L)).thenReturn(running);
        when(runSpi.cancelIfActive(7L)).thenReturn(false);

        service.stopRun(1L, 7L);

        // Running: let the loop consume the cancel flag first; also conditionally set the terminal state to cover the race where "the loop already exited"
        verify(sessionRunner).cancelRun(7L);
        verify(runSpi).cancelIfActive(7L);
        verify(pendingWaitSpi).cancelPendingByRunId(7L);
    }

    @Test
    void stopSession_whileWaiting_routesThroughParkedCancel() {
        RunVO waiting = run(RunStatus.WAITING.name());
        when(runSpi.findActiveRun(1L)).thenReturn(waiting);
        when(runSpi.getRun(7L)).thenReturn(waiting);
        when(runSpi.cancelIfActive(7L)).thenReturn(true);
        when(pendingWaitSpi.cancelPendingByRunId(7L)).thenReturn(1);

        service.stopSession(1L);

        // findActiveRun must find WAITING runs (otherwise it takes the "no active run" branch: clear the flag and cancel nothing)
        verify(runSpi).findActiveRun(1L);
        verify(runSpi).cancelIfActive(7L);
        verify(pendingWaitSpi).cancelPendingByRunId(7L);
        verify(sessionRunner).clearSessionCancelled(1L);
        verify(sessionRunner).cancelRun(7L);
    }

    @Test
    void stopSession_whileRunning_setsRunAndSessionCancelFlags() {
        RunVO running = run(RunStatus.RUNNING.name());
        when(runSpi.findActiveRun(1L)).thenReturn(running);
        when(runSpi.getRun(7L)).thenReturn(running);
        when(runSpi.cancelIfActive(7L)).thenReturn(false);

        service.stopSession(1L);

        verify(sessionRunner).cancelSession(1L);
        verify(sessionRunner).cancelRun(7L);
        verify(sessionRunner).clearSessionCancelled(1L);
    }
}
