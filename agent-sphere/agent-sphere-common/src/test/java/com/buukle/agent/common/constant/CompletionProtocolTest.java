package com.buukle.agent.common.constant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompletionProtocolTest {

    @Test
    void instanceOverrideWinsOverRouteDefault() {
        assertEquals(CompletionProtocol.STRICT_FINISH,
                CompletionProtocol.resolve("STRICT_FINISH", "IMPLICIT_TEXT"));
        assertEquals(CompletionProtocol.IMPLICIT_TEXT,
                CompletionProtocol.resolve("IMPLICIT_TEXT", "STRICT_FINISH"));
    }

    @Test
    void modelDefaultSentinelFallsBackToRoute() {
        assertTrue(CompletionProtocol.isInherit(null));
        assertTrue(CompletionProtocol.isInherit(""));
        assertTrue(CompletionProtocol.isInherit("MODEL_DEFAULT"));
        assertEquals(CompletionProtocol.IMPLICIT_TEXT,
                CompletionProtocol.resolve("MODEL_DEFAULT", "IMPLICIT_TEXT"));
        assertEquals(CompletionProtocol.IMPLICIT_TEXT,
                CompletionProtocol.resolve(null, "IMPLICIT_TEXT"));
    }

    @Test
    void unknownOrMissingValuesDefaultToStrict() {
        assertEquals(CompletionProtocol.STRICT_FINISH,
                CompletionProtocol.resolve(null, null));
        assertEquals(CompletionProtocol.STRICT_FINISH,
                CompletionProtocol.resolve("nonsense", "IMPLICIT_TEXT"));
    }
}
