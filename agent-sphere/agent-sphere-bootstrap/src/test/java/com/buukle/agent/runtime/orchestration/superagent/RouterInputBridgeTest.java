package com.buukle.agent.runtime.orchestration.superagent;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.buukle.agent.common.metrics.AgentMetrics;
import com.buukle.agent.instance.domain.AgentPendingClarification;
import com.buukle.agent.instance.dtvo.enums.SessionType;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.repository.AgentPendingClarificationMapper;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.runtime.kernel.port.vo.ClarificationStatus;
import com.buukle.agent.runtime.kernel.port.vo.RunStatus;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventVO;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventDataVO;
import com.buukle.agent.runtime.kernel.port.vo.ToolApprovalStatus;
import com.buukle.agent.superagent.spi.SessionInputCommand;
import com.buukle.agent.superagent.spi.SessionInputResolveCommand;
import com.buukle.agent.superagent.spi.SessionInputSpi;
import com.buukle.agent.superagent.spi.SessionInputStatus;
import com.buukle.agent.superagent.spi.SessionInputType;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Router parked-input bridging: runtime clarification/approval events → DM card projection. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RouterInputBridgeTest {

    private static final Long SESSION_ID = 21L;
    private static final Long RUN_ID = 60L;
    private static final Long THREAD_ID = 19L;
    private static final Long AGENT_ID = 20L;

    @Mock
    SessionSpi sessionSpi;
    @Mock
    AgentPendingClarificationMapper clarificationMapper;
    @Mock
    ObjectProvider<SessionInputSpi> inputProvider;
    @Mock
    SessionInputSpi sessionInputSpi;
    @Mock
    ObjectProvider<MeterRegistry> registryProvider;

    RouterInputBridge bridge;

    @BeforeEach
    void setUp() {
        when(inputProvider.getIfAvailable()).thenReturn(sessionInputSpi);
        bridge = new RouterInputBridge(sessionSpi, clarificationMapper, inputProvider,
                new AgentMetrics(registryProvider));
        ReflectionTestUtils.setField(bridge, "enabled", true);
        SessionVO session = new SessionVO();
        session.setId(SESSION_ID);
        session.setSessionType(SessionType.ROUTER);
        session.setSuperAgentId(AGENT_ID);
        session.setThreadId(THREAD_ID);
        when(sessionSpi.getSession(SESSION_ID)).thenReturn(session);
    }

    @Test
    void clarificationPendingProjectsCardCommand() {
        AgentPendingClarification row = new AgentPendingClarification();
        row.setTitle("缺少报销类型");
        row.setDescription("请补充差旅还是办公");
        row.setOptions("[\"差旅\",\"办公\"]");
        row.setMultiple(false);
        when(clarificationMapper.selectOne(any(Wrapper.class))).thenReturn(row);
        RuntimeEventDataVO data = RuntimeEventDataVO.builder()
                .sessionId(SESSION_ID).runId(RUN_ID)
                .clarificationId("c1abc123").prompt("缺少报销类型").multiple(true)
                .argumentsJson("[\"其他\"]").build();

        bridge.onEvent(new RuntimeEventVO(ClarificationStatus.PENDING, data));

        ArgumentCaptor<SessionInputCommand> captor = ArgumentCaptor.forClass(SessionInputCommand.class);
        verify(sessionInputSpi).projectPending(captor.capture());
        SessionInputCommand command = captor.getValue();
        assertEquals(AGENT_ID, command.superAgentId());
        assertEquals(THREAD_ID, command.threadId());
        assertEquals(SessionInputType.CLARIFICATION, command.inputType());
        assertEquals("c1abc123", command.inputId());
        // Row data's title/options/description take precedence; fall back to event data when the row lacks them
        assertEquals("请补充差旅还是办公", command.description());
        assertEquals("[\"差旅\",\"办公\"]", command.optionsJson());
        assertEquals(Boolean.FALSE, command.multiple());
    }

    @Test
    void clarificationRespondedMarksCardAnswered() {
        bridge.onEvent(new RuntimeEventVO(ClarificationStatus.RESPONDED, clarifyData()));

        ArgumentCaptor<SessionInputResolveCommand> captor =
                ArgumentCaptor.forClass(SessionInputResolveCommand.class);
        verify(sessionInputSpi).projectResolved(captor.capture());
        assertEquals(SessionInputType.CLARIFICATION, captor.getValue().inputType());
        assertEquals("c1abc123", captor.getValue().inputId());
        assertEquals(SessionInputStatus.ANSWERED, captor.getValue().status());
    }

    @Test
    void clarificationDismissedMarksCardCancelled() {
        bridge.onEvent(new RuntimeEventVO(ClarificationStatus.DISMISSED, clarifyData()));

        ArgumentCaptor<SessionInputResolveCommand> captor =
                ArgumentCaptor.forClass(SessionInputResolveCommand.class);
        verify(sessionInputSpi).projectResolved(captor.capture());
        assertEquals(SessionInputStatus.CANCELLED, captor.getValue().status());
    }

    @Test
    void approvalPendingProjectsApprovalCommand() {
        bridge.onEvent(new RuntimeEventVO(ToolApprovalStatus.TOOL_APPROVAL_PENDING,
                RuntimeEventDataVO.builder()
                        .sessionId(SESSION_ID).runId(RUN_ID)
                        .approvalId("ap1abc456").prompt("需要确认工具调用")
                        .toolCallsJson("[{\"callId\":\"call_1\"}]").build()));

        ArgumentCaptor<SessionInputCommand> captor = ArgumentCaptor.forClass(SessionInputCommand.class);
        verify(sessionInputSpi).projectPending(captor.capture());
        assertEquals(SessionInputType.APPROVAL, captor.getValue().inputType());
        assertEquals("ap1abc456", captor.getValue().inputId());
        assertEquals("[{\"callId\":\"call_1\"}]", captor.getValue().toolCallsJson());
    }

    @Test
    void terminalRunCancelsPendingInputs() {
        bridge.onEvent(new RuntimeEventVO(RunStatus.COMPLETED,
                RuntimeEventDataVO.builder().sessionId(SESSION_ID).runId(RUN_ID).build()));

        verify(sessionInputSpi).projectRunTerminal(RUN_ID);
    }

    @Test
    void nonRouterSessionIsIgnored() {
        SessionVO session = new SessionVO();
        session.setId(SESSION_ID);
        session.setSessionType(SessionType.CONSOLE);
        when(sessionSpi.getSession(SESSION_ID)).thenReturn(session);

        bridge.onEvent(new RuntimeEventVO(ClarificationStatus.PENDING, clarifyData()));
        bridge.onEvent(new RuntimeEventVO(RunStatus.COMPLETED,
                RuntimeEventDataVO.builder().sessionId(SESSION_ID).runId(RUN_ID).build()));

        verify(sessionInputSpi, never()).projectPending(any());
        verify(sessionInputSpi, never()).projectRunTerminal(any());
    }

    @Test
    void subAgentClarificationIsIgnored() {
        RuntimeEventDataVO data = clarifyData().toBuilder().subAgentRunId(99L).build();

        bridge.onEvent(new RuntimeEventVO(ClarificationStatus.PENDING, data));

        verify(sessionInputSpi, never()).projectPending(any());
    }

    private static RuntimeEventDataVO clarifyData() {
        return RuntimeEventDataVO.builder()
                .sessionId(SESSION_ID).runId(RUN_ID).clarificationId("c1abc123").build();
    }
}
