package com.buukle.agent.runtime.orchestration.superagent;

import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.runtime.orchestration.service.ChatRuntimeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Background resume must run as the Router session owner (assertSessionOwnership passes). */
@ExtendWith(MockitoExtension.class)
class RouterInputResumeServiceTest {

    private static final Long SESSION_ID = 21L;
    private static final Long RUN_ID = 60L;

    @Mock
    SessionSpi sessionSpi;
    @Mock
    ChatRuntimeService chatRuntimeService;

    @BeforeEach
    void setUp() {
        AuthContext.clear();
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    @Test
    void answerClarificationRunsAsSessionOwner() {
        stubOwnerSession("owner");
        RouterInputResumeService service = new RouterInputResumeService(sessionSpi, chatRuntimeService);
        doAnswer(inv -> {
            assertEquals("owner", AuthContext.getUsername());
            assertTrue(AuthContext.isSuperAdmin());
            return null;
        }).when(chatRuntimeService).resumeFromClarification(anyLong(), anyLong(), anyString(), anyString());

        service.answerClarification(SESSION_ID, RUN_ID, "c1abc123", "差旅");

        verify(chatRuntimeService).resumeFromClarification(SESSION_ID, RUN_ID, "差旅", "c1abc123");
        // Context is cleared in finally so the scheduling thread is not polluted
        assertFalse(AuthContext.isSuperAdmin());
    }

    @Test
    void resolveApprovalRunsAsSessionOwner() {
        stubOwnerSession("owner");
        RouterInputResumeService service = new RouterInputResumeService(sessionSpi, chatRuntimeService);
        doAnswer(inv -> {
            assertEquals("owner", AuthContext.getUsername());
            return null;
        }).when(chatRuntimeService).resolveToolApproval(anyLong(), anyLong(), anyString(), anyString());

        service.resolveApproval(SESSION_ID, RUN_ID, "ap1abc456", "[{\"callId\":\"c1\"}]");

        verify(chatRuntimeService).resolveToolApproval(SESSION_ID, RUN_ID, "ap1abc456",
                "[{\"callId\":\"c1\"}]");
    }

    @Test
    void missingSessionIsIgnoredQuietly() {
        when(sessionSpi.getSession(SESSION_ID)).thenReturn(null);
        RouterInputResumeService service = new RouterInputResumeService(sessionSpi, chatRuntimeService);

        service.answerClarification(SESSION_ID, RUN_ID, "c1abc123", "差旅");

        verify(chatRuntimeService, never()).resumeFromClarification(anyLong(), anyLong(), anyString(), anyString());
    }

    private void stubOwnerSession(String username) {
        SessionVO session = new SessionVO();
        session.setId(SESSION_ID);
        session.setCreatedBy(username);
        when(sessionSpi.getSession(SESSION_ID)).thenReturn(session);
    }
}
