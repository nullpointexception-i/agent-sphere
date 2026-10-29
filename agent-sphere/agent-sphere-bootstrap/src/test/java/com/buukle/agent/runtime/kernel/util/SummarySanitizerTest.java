package com.buukle.agent.runtime.kernel.util;

import org.junit.jupiter.api.Test;

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
    void blankIsRejected() {
        assertFalse(SummarySanitizer.isComplete(null));
        assertFalse(SummarySanitizer.isComplete("   "));
    }
}
