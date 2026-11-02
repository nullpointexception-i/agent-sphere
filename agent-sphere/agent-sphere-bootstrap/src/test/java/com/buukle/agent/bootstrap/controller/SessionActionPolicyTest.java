package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.runtime.orchestration.service.SessionActionPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionActionPolicyTest {

    @Test
    void idleSessionCanSend() {
        SessionActionPolicy.Decision d = SessionActionPolicy.decide(null, 0, 0);
        assertTrue(d.canSend());
        assertTrue(d.blockReasons().isEmpty());
    }

    @Test
    void runningRunBlocks() {
        SessionActionPolicy.Decision d = SessionActionPolicy.decide("RUNNING", 0, 0);
        assertFalse(d.canSend());
        assertEquals(java.util.List.of(SessionActionPolicy.BLOCK_RUNNING), d.blockReasons());
    }

    @Test
    void awaitingUserDoesNotBlockByItself() {
        SessionActionPolicy.Decision d = SessionActionPolicy.decide("AWAITING_USER", 0, 0);
        assertTrue(d.canSend());
    }

    @Test
    void pendingClarificationBlocks() {
        SessionActionPolicy.Decision d = SessionActionPolicy.decide(null, 1, 0);
        assertFalse(d.canSend());
        assertEquals(java.util.List.of(SessionActionPolicy.BLOCK_CLARIFICATION), d.blockReasons());
    }

    @Test
    void pendingApprovalBlocks() {
        SessionActionPolicy.Decision d = SessionActionPolicy.decide("WAITING", 0, 2);
        assertFalse(d.canSend());
        assertEquals(java.util.List.of(
                SessionActionPolicy.BLOCK_RUNNING,
                SessionActionPolicy.BLOCK_TOOL_APPROVAL), d.blockReasons());
    }
}
