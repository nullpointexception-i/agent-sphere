package com.buukle.agent.resource.template;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Resource template initializer: one implementation per resource type.
 * To add a resource type, add a single {@link ResourceInitializer} bean; the coordinator needs no changes.
 */
public interface ResourceInitializer {

    /** Resource type identifier, matching the "type" field of the template descriptor. */
    String type();

    /**
     * Performs initialization; throw {@link ResourceExistsException} when the resource already exists,
     * so it is counted as skipped.
     */
    void initialize(JsonNode descriptor, ResourceInitContext ctx);
}
