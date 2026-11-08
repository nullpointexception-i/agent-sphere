package com.buukle.agent.superagent.service;

import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.superagent.dtvo.dto.ResolveApprovalDTO;
import com.buukle.agent.superagent.dtvo.vo.PendingApprovalVO;
import com.buukle.agent.superagent.repository.PendingApprovalRow;
import com.buukle.agent.superagent.repository.SuperAgentApprovalMapper;
import com.buukle.agent.superagent.service.impl.SuperAgentApprovalServiceImpl;
import com.buukle.agent.superagent.spi.RouterInputResumePort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SuperAgentApprovalServiceImplTest {

    private static final Long USER_ID = 1L;
    private static final String USERNAME = "alice";

    @Mock
    SuperAgentApprovalMapper approvalMapper;
    @Mock
    ObjectProvider<RouterInputResumePort> resumePortProvider;
    @Mock
    RouterInputResumePort resumePort;

    SuperAgentApprovalServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SuperAgentApprovalServiceImpl(approvalMapper, resumePortProvider);
        AuthContext.setUserId(USER_ID);
        AuthContext.setUsername(USERNAME);
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    @Test
    void listPending_mapsOwnerRows() {
        PendingApprovalRow row = row("ap1", "PENDING", USER_ID);
        row.setSessionType("WORKER");
        row.setAgentName("Finance");
        when(approvalMapper.listPendingForOwner(USER_ID, USERNAME)).thenReturn(List.of(row));

        List<PendingApprovalVO> result = service.listPending();

        assertEquals(1, result.size());
        assertEquals("ap1", result.get(0).getApprovalId());
        assertEquals("WORKER", result.get(0).getSessionType());
        assertEquals("Finance", result.get(0).getAgentName());
    }

    @Test
    void resolve_rejectsAnotherOwnersApproval() {
        when(approvalMapper.findByApprovalId("ap1")).thenReturn(row("ap1", "PENDING", 2L));

        BizException error = assertThrows(BizException.class,
                () -> service.resolve("ap1", dto()));

        assertEquals(CommonErrorCode.FORBIDDEN.getCode(), error.getErrorCode());
        verify(resumePort, never()).resolveApproval(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                anyString(), anyString(), anyString());
    }

    @Test
    void resolve_passesRealApproverAndDecisions() {
        when(approvalMapper.findByApprovalId("ap1")).thenReturn(row("ap1", "PENDING", USER_ID));
        when(resumePortProvider.getIfAvailable()).thenReturn(resumePort);

        service.resolve("ap1", dto());

        verify(resumePort).resolveApproval(
                eq(11L), eq(22L), eq("ap1"),
                org.mockito.ArgumentMatchers.argThat(json -> json.contains("\"callId\":\"c1\"")),
                eq(USERNAME));
    }

    @Test
    void resolve_isIdempotentWhenAlreadyClosed() {
        when(approvalMapper.findByApprovalId("ap1")).thenReturn(row("ap1", "APPROVED", USER_ID));

        service.resolve("ap1", dto());

        verify(resumePort, never()).resolveApproval(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                anyString(), anyString(), anyString());
    }

    private static PendingApprovalRow row(String approvalId, String status, Long ownerUserId) {
        PendingApprovalRow row = new PendingApprovalRow();
        row.setApprovalId(approvalId);
        row.setSessionId(11L);
        row.setRunId(22L);
        row.setStatus(status);
        row.setOwnerUserId(ownerUserId);
        row.setToolCallsJson("[{\"callId\":\"c1\"}]");
        row.setCreatedAt(LocalDateTime.now());
        return row;
    }

    private static ResolveApprovalDTO dto() {
        ResolveApprovalDTO.Decision decision = new ResolveApprovalDTO.Decision();
        decision.setCallId("c1");
        decision.setDecision("ALLOW_ONCE");
        ResolveApprovalDTO dto = new ResolveApprovalDTO();
        dto.setDecisions(List.of(decision));
        return dto;
    }
}
