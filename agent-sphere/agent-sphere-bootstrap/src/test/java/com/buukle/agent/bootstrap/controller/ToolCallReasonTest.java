package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.util.json.ToolCallReason;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCallReasonTest {

    @Test
    void extractAndStripReservedReason() {
        String args = "{\"url\":\"https://example.com\",\"_reason\":\"  检查页面内容  \"}";

        assertEquals("检查页面内容", ToolCallReason.extract(args));
        String stripped = ToolCallReason.strip(args);
        assertFalse(stripped.contains(ToolCallReason.REASON_KEY));
        assertTrue(stripped.contains("https://example.com"));
    }

    @Test
    void stripIsNoOpWhenReasonMissingOrJsonInvalid() {
        assertEquals("{\"a\":1}", ToolCallReason.strip("{\"a\":1}"));
        assertEquals("not-json", ToolCallReason.strip("not-json"));
        assertNull(ToolCallReason.extract("not-json"));
        assertNull(ToolCallReason.extract(null));
    }

    @Test
    void normalizeCollapsesWhitespaceAndCapsLength() {
        assertEquals("hello world", ToolCallReason.normalize("  hello\n\tworld  "));
        assertNull(ToolCallReason.normalize("   "));
        String longReason = "x".repeat(ToolCallReason.MAX_REASON_CHARS + 20);
        assertEquals(ToolCallReason.MAX_REASON_CHARS,
                ToolCallReason.normalize(longReason).length());
    }
}
