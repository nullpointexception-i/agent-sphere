package com.buukle.agent.runtime.kernel.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolResultCompressorTest {

    @Test
    void shortJsonKeepsArraysIntact() {
        String raw = "{\"skills\":[{\"id\":1},{\"id\":2},{\"id\":3},{\"id\":4},{\"id\":5}]}";
        assertEquals(raw, ToolResultCompressor.compress(raw, 4_000));
    }

    @Test
    void keepArraysPreservesAllItemsWhenOverBudget() {
        String raw = "{\"skills\":[{\"id\":1,\"text\":\"aaaa\"},{\"id\":2,\"text\":\"bbbb\"},"
                + "{\"id\":3,\"text\":\"cccc\"},{\"id\":4,\"text\":\"dddd\"},"
                + "{\"id\":5,\"text\":\"eeee\"}]}";
        String compressed = ToolResultCompressor.compressKeepArrays(raw, 10);
        assertTrue(compressed.contains("\"id\":5"), compressed);
        assertTrue(compressed.contains("\"id\":1"), compressed);
    }
}
