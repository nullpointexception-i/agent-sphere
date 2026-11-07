package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.runtime.kernel.constants.ChatClarification;
import com.buukle.agent.runtime.kernel.constants.WaitConstants;
import com.buukle.agent.instance.dtvo.enums.RunStatus;
import com.buukle.agent.runtime.kernel.runner.tool.SuspenseStatusResolver;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Regression guard for suspension detection.
 *
 * <p>The most important case is {@link #fieldOrderAndWhitespaceDoNotMatter()}: the old implementation used a string
 * {@code contains("\"status\":\"awaiting_user\"")} check, so one extra space or a different field order
 * in the serialized output meant suspension never triggered, and the run spun on holding a "waiting for your answer" result. Such failures throw no exception;
 * they only show up as "clarification was answered but the agent keeps running", which is very hard to diagnose.
 */
class SuspenseStatusResolverTest {

    private static final String WAIT_TOOL = WaitConstants.TOOL_NAME;
    private static final String CLARIFY_TOOL = ChatClarification.CLARIFICATION_TOOL_NAME;

    @Test
    void waitResult_suspendsWithWaiting() {
        assertEquals(RunStatus.WAITING,
                SuspenseStatusResolver.resolve(WAIT_TOOL, "{\"status\":\"waiting\",\"wait_ms\":90000}"));
    }

    @Test
    void clarificationResult_suspendsWithAwaitingUser() {
        assertEquals(RunStatus.AWAITING_USER,
                SuspenseStatusResolver.resolve(CLARIFY_TOOL, "{\"status\":\"awaiting_user\",\"clarification_id\":\"c1\"}"));
    }

    @Test
    void fieldOrderAndWhitespaceDoNotMatter() {
        // The old implementation missed both forms
        assertEquals(RunStatus.WAITING,
                SuspenseStatusResolver.resolve(WAIT_TOOL, "{\"wait_ms\":1000,\"status\":\"waiting\"}"));
        assertEquals(RunStatus.AWAITING_USER,
                SuspenseStatusResolver.resolve(CLARIFY_TOOL, "{\"status\" : \"awaiting_user\"}"));
    }

    @Test
    void otherToolsWithSameStatus_areNotSuspendCandidates() {
        // Only clarification and wait are suspension tools; a "waiting" status from any other tool must not hijack the run
        assertNull(SuspenseStatusResolver.resolve("builtin_5", "{\"status\":\"waiting\"}"));
    }

    @Test
    void candidateToolWithNonSuspendStatus_returnsNull() {
        assertNull(SuspenseStatusResolver.resolve(CLARIFY_TOOL, "{\"status\":\"responded\"}"));
        assertNull(SuspenseStatusResolver.resolve(WAIT_TOOL, "{\"ok\":true}"));
        assertNull(SuspenseStatusResolver.resolve(WAIT_TOOL, "{\"error\":\"Failed to process wait\"}"),
                "失败的 wait 必须按普通结果继续，不能把 run 永久停住");
    }

    @Test
    void malformedResult_fallsBackToNotSuspending() {
        assertNull(SuspenseStatusResolver.resolve(WAIT_TOOL, "not-json"));
        assertNull(SuspenseStatusResolver.resolve(WAIT_TOOL, ""));
        assertNull(SuspenseStatusResolver.resolve(WAIT_TOOL, null));
    }

    // On "a second suspension after resume": the artifact rewritten by resumeFromWait also carries status=waiting,
    // but it is written to the tool call record; the resumed loop reads history through HistoryLoader, so wait is not re-executed,
    // and that artifact never flows into results to trigger this check. The defense is "do not re-run", not this code path.
}