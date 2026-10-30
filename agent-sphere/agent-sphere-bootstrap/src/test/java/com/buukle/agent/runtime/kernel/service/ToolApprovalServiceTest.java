package com.buukle.agent.runtime.kernel.service;

import com.buukle.agent.common.constant.ToolAccessMode;
import com.buukle.agent.common.constant.ToolApprovalScope;
import com.buukle.agent.common.constant.ToolSecurityLevel;
import com.buukle.agent.instance.dtvo.vo.ToolApprovalVO;
import com.buukle.agent.instance.spi.ToolApprovalGrantSpi;
import com.buukle.agent.instance.spi.ToolApprovalSpi;
import com.buukle.agent.runtime.kernel.contract.TurnToolCall;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolApprovalServiceTest {

    @Mock ToolApprovalSpi approvalSpi;
    @Mock ToolApprovalGrantSpi grantSpi;
    @Mock ApplicationEventPublisher eventPublisher;

    private ToolApprovalService service() {
        return new ToolApprovalService(approvalSpi, grantSpi, eventPublisher);
    }

    private static ToolApprovalService.ApprovalCall call(String ref, ToolApprovalScope scope) {
        RuntimeTool tool = RuntimeTool.builder()
                .llmToolName("builtin_6")
                .toolRef(ref)
                .displayName("文档写入")
                .accessMode(ToolAccessMode.WRITE.name())
                .securityLevel(ToolSecurityLevel.L2.name())
                .approvalScope(scope.name())
                .build();
        return new ToolApprovalService.ApprovalCall(
                new TurnToolCall("call-1", "builtin_6", "{\"action\":\"create\"}"), tool, true);
    }

    private static ToolApprovalVO approval(String toolCallsJson) {
        ToolApprovalVO vo = new ToolApprovalVO();
        vo.setApprovalId("a1");
        vo.setSessionId(1L);
        vo.setRunId(2L);
        vo.setStatus("PENDING");
        vo.setToolCallsJson(toolCallsJson);
        return vo;
    }

    private static String callsJson(ToolApprovalScope scope) {
        return "[{\"callId\":\"call-1\",\"toolRef\":\"builtin:6\",\"toolName\":\"builtin_6\","
                + "\"displayName\":\"文档写入\",\"action\":\"create\","
                + "\"arguments\":\"{\\\"action\\\":\\\"create\\\"}\","
                + "\"approvalScope\":\"" + scope.name() + "\",\"pending\":true}]";
    }

    @Test
    void createPendingPublishesApprovalAndReturnsId() {
        String id = service().createPending(1L, 2L,
                List.of(call("builtin:6", ToolApprovalScope.SESSION)));
        assertTrue(id != null && !id.isBlank());
        ArgumentCaptor<String> idCaptor = ArgumentCaptor.forClass(String.class);
        verify(approvalSpi).createPending(idCaptor.capture(), eq(1L), eq(2L), anyString());
        assertTrue(idCaptor.getValue().equals(id));
    }

    @Test
    void allowSessionWritesSessionGrant() {
        ToolApprovalService service = service();
        when(approvalSpi.getByApprovalId("a1")).thenReturn(approval(callsJson(ToolApprovalScope.SESSION)));
        assertTrue(service.resolve(1L, 2L, "a1",
                "[{\"callId\":\"call-1\",\"decision\":\"ALLOW_SESSION\"}]"));
        verify(grantSpi, atLeastOnce()).grant(eq(1L), eq(2L), eq("builtin:6"), anyString(), anyString(), eq("SESSION"), anyString());
        verify(approvalSpi).resolve(eq("a1"), anyString(), eq("APPROVED"), eq("user"));
    }

    @Test
    void denyWritesRunDenied() {
        ToolApprovalService service = service();
        when(approvalSpi.getByApprovalId("a1")).thenReturn(approval(callsJson(ToolApprovalScope.RUN)));
        assertTrue(service.resolve(1L, 2L, "a1",
                "[{\"callId\":\"call-1\",\"decision\":\"DENY\"}]"));
        verify(grantSpi, atLeastOnce()).deny(eq(1L), eq(2L), eq("builtin:6"), anyString());
    }

    @Test
    void allowOnceDoesNotPersistGrant() {
        ToolApprovalService service = service();
        when(approvalSpi.getByApprovalId("a1")).thenReturn(approval(callsJson(ToolApprovalScope.ONCE)));
        assertTrue(service.resolve(1L, 2L, "a1",
                "[{\"callId\":\"call-1\",\"decision\":\"ALLOW_ONCE\"}]"));
        verify(grantSpi, never()).grant(eq(1L), eq(2L), anyString(), anyString(), anyString(), anyString(), anyString());
        verify(grantSpi, never()).deny(eq(1L), eq(2L), anyString(), anyString());
    }

    @Test
    void rejectsDecisionWiderThanToolScope() {
        ToolApprovalService service = service();
        when(approvalSpi.getByApprovalId("a1")).thenReturn(approval(callsJson(ToolApprovalScope.ONCE)));
        assertFalse(service.resolve(1L, 2L, "a1",
                "[{\"callId\":\"call-1\",\"decision\":\"ALLOW_RUN\"}]"));
        verify(approvalSpi, never()).resolve(eq("a1"), anyString(), anyString(), anyString());
    }

    @Test
    void cancelDismissesPendingBatch() {
        ToolApprovalService service = service();
        ToolApprovalVO vo = approval(callsJson(ToolApprovalScope.ONCE));
        when(approvalSpi.listPendingByRunId(2L)).thenReturn(List.of(vo));
        service.cancelByRunId(2L);
        verify(approvalSpi).resolve(eq("a1"), eq(null), eq("DISMISSED"), eq("system"));
        verify(approvalSpi).dismissByRunId(2L, "DISMISSED");
    }
}
