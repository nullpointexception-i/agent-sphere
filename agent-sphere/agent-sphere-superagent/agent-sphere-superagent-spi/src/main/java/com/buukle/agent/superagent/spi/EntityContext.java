package com.buukle.agent.superagent.spi;

import java.util.Map;

/** Structured, already permission-checked summary of a business entity. */
public record EntityContext(
        String entityType,
        Long entityId,
        String title,
        String status,
        String summary,
        Map<String, String> attributes
) {
}
