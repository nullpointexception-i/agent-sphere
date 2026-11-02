package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.instance.dtvo.enums.TimelineKind;
import com.buukle.agent.instance.dtvo.enums.TimelineState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimelineStateTest {

    @Test
    void allCodesAreUpperSnakeCase() {
        for (TimelineState state : TimelineState.values()) {
            assertEquals(state.name(), state.getCode());
        }
    }

    @Test
    void legacyLowercaseIsRejected() {
        assertNull(TimelineState.from("succeeded"));
        assertNull(TimelineState.from("started"));
        assertEquals(TimelineState.SUCCEEDED, TimelineState.from("SUCCEEDED"));
    }

    @Test
    void kindAllowedStatesAreEnforced() {
        assertTrue(TimelineState.RUNNING.isValidFor(TimelineKind.ASSISTANT));
        assertFalse(TimelineState.RUNNING.isValidFor(TimelineKind.USER));
        assertFalse(TimelineState.PENDING.isValidFor(TimelineKind.TOOL));
        assertTrue(TimelineState.PENDING.isValidFor(TimelineKind.CLARIFICATION));
    }

    @Test
    void terminalStates() {
        assertTrue(TimelineState.COMPLETED.isTerminal());
        assertTrue(TimelineState.SUCCEEDED.isTerminal());
        assertFalse(TimelineState.RUNNING.isTerminal());
        assertFalse(TimelineState.IN_PROGRESS.isTerminal());
    }
}
