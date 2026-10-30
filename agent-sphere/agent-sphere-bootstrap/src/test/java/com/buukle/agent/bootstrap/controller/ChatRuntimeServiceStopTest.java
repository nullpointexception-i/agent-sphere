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
 * 停止按钮的回归护栏 —— 重点是 WAITING 停车态。
 *
 * <p>WAITING（wait 工具挂起）的 loop 已经退出，取消标志没有消费者，所以停止必须
 * 「直接置终态 + 取消挂起记录 + 发 run_cancelled」，否则：run 关不掉、到点还会被
 * waker 恢复、前端因收不到终态事件而永久锁在“执行中/停止无效”。
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
                clarificationMapper, sessionRunner, timelineRecorder, chatAttachmentService, pendingWaitSpi, mock(ToolApprovalService.class), mock(SessionInputManager.class));
        AuthContext.setSuperAdmin(true); // 跳过会话归属校验（本测试只关心停止逻辑）
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
        when(pendingWaitSpi.cancelPendingByRunId(7L)).thenReturn(1);

        service.stopRun(1L, 7L);

        assertEquals(RunStatus.CANCELLED.name(), waiting.getStatus(), "WAITING run 必须被置为 CANCELLED");
        verify(runSpi).updateRun(waiting);
        verify(pendingWaitSpi).cancelPendingByRunId(7L);
        ArgumentCaptor<RuntimeEventVO> cap = ArgumentCaptor.forClass(RuntimeEventVO.class);
        verify(eventPublisher).publishEvent(cap.capture());
        assertEquals(RunStatus.CANCELLED, cap.getValue().getEventType(), "必须发 run_cancelled 终态");
        // 停车态不该走「设取消标志等 loop 消费」那条路（loop 已退出，标志没人消费）
        verify(sessionRunner, never()).cancelRun(any());
    }

    @Test
    void stopRun_whileAwaitingUser_stillWorks() {
        RunVO awaiting = run(RunStatus.AWAITING_USER.name());
        when(runSpi.getRun(7L)).thenReturn(awaiting);

        service.stopRun(1L, 7L);

        assertEquals(RunStatus.CANCELLED.name(), awaiting.getStatus());
        verify(sessionRunner, never()).cancelRun(any());
    }

    @Test
    void stopRun_whileRunning_setsCancelFlagAndDoesNotTouchPendingWait() {
        RunVO running = run(RunStatus.RUNNING.name());
        when(runSpi.getRun(7L)).thenReturn(running);

        service.stopRun(1L, 7L);

        // 运行中：交给 loop 消费取消标志，不在此直接改状态/发终态
        verify(sessionRunner).cancelRun(7L);
        verify(pendingWaitSpi, never()).cancelPendingByRunId(any());
        assertEquals(RunStatus.RUNNING.name(), running.getStatus());
    }

    @Test
    void stopSession_whileWaiting_routesThroughParkedCancel() {
        RunVO waiting = run(RunStatus.WAITING.name());
        when(runSpi.findActiveRun(1L)).thenReturn(waiting);
        when(runSpi.getRun(7L)).thenReturn(waiting);
        when(pendingWaitSpi.cancelPendingByRunId(7L)).thenReturn(1);

        service.stopSession(1L);

        // findActiveRun 必须能查到 WAITING（否则会走“无活动 run”分支：清标志、什么也不取消）
        verify(runSpi).findActiveRun(1L);
        assertEquals(RunStatus.CANCELLED.name(), waiting.getStatus());
        verify(pendingWaitSpi).cancelPendingByRunId(7L);
        verify(sessionRunner).clearSessionCancelled(1L);
        verify(sessionRunner, never()).cancelRun(any());
    }

    @Test
    void stopSession_whileRunning_setsRunAndSessionCancelFlags() {
        RunVO running = run(RunStatus.RUNNING.name());
        when(runSpi.findActiveRun(1L)).thenReturn(running);

        service.stopSession(1L);

        verify(sessionRunner).cancelSession(1L);
        verify(sessionRunner).cancelRun(7L);
        verify(sessionRunner, never()).clearSessionCancelled(any());
    }
}
