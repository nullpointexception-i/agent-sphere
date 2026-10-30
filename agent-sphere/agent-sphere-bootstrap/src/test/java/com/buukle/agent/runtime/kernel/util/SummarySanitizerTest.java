package com.buukle.agent.runtime.kernel.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SummarySanitizerTest {

    @Test
    void completeMarkdownIsAccepted() {
        assertTrue(SummarySanitizer.isComplete("""
                ## Goal
                Deploy the model.
                ## Next Steps
                - verify KV cache
                """));
    }

    @Test
    void unterminatedJsonIsRejected() {
        assertFalse(SummarySanitizer.isComplete("""
                ## Key Context
                - error: 模型调用异常 (404): {"detail":"Not"""));
    }

    @Test
    void oddCodeFenceIsRejected() {
        assertFalse(SummarySanitizer.isComplete("""
                ## Progress
                ```json
                {"ok": true}
                """));
    }

    @Test
    void trailingColonOrOpenBracketIsRejected() {
        assertFalse(SummarySanitizer.isComplete("## Next Steps\n- "));
        assertFalse(SummarySanitizer.isComplete("## Key Context\n["));
    }


    @Test
    void trimToTokenBudgetStopsAtSentenceBoundary() {
        String text = ("这是完整句子。" + "x".repeat(100)).repeat(100);
        String trimmed = SummarySanitizer.trimToTokenBudget(text, 100);
        assertTrue(trimmed != null && trimmed.length() <= 100 * com.buukle.agent.runtime.kernel.constants.RunnerConstants.TOKEN_ESTIMATE_DIVISOR);
        assertTrue(SummarySanitizer.isComplete(trimmed));
    }

    @Test
    void trimToTokenBudgetFallsBackToHardCutWithoutBoundary() {
        String trimmed = SummarySanitizer.trimToTokenBudget("x".repeat(10_000), 100);
        assertTrue(trimmed != null && trimmed.length() <= 100 * com.buukle.agent.runtime.kernel.constants.RunnerConstants.TOKEN_ESTIMATE_DIVISOR);
        assertTrue(SummarySanitizer.isComplete(trimmed));
    }

    @Test
    void trimToTokenBudgetKeepsTextWithinBudget() {
        String text = "## Goal\nDeploy the model.";
        assertEquals(text, SummarySanitizer.trimToTokenBudget(text, 1000));
    }

    @Test
    void blankIsRejected() {
        assertFalse(SummarySanitizer.isComplete(null));
        assertFalse(SummarySanitizer.isComplete("   "));
    }
}
