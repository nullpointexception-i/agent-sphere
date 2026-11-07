package com.buukle.agent.resource.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.buukle.agent.util.json.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resource template coordinator: parses the template JSON array and dispatches each entry by type
 * to the matching {@link ResourceInitializer}.
 * Relies on Spring to collect all ResourceInitializer beans automatically, so new resource types need no changes.
 */
@Slf4j
@Component
public class ResourceTemplateCoordinator {

    private final Map<String, ResourceInitializer> byType;

    public ResourceTemplateCoordinator(List<ResourceInitializer> initializers) {
        this.byType = initializers.stream().collect(Collectors.toMap(ResourceInitializer::type, Function.identity()));
    }

    /**
     * Runs resource template initialization.
     *
     * @param templateJson resource descriptor JSON array; empty returns an empty result
     *                     (the caller decides whether to pass the default template)
     * @param ctx          context (identity provider info + cache of created resources)
     */
    public ResourceInitResult initialize(String templateJson, ResourceInitContext ctx) {
        ResourceInitResult result = new ResourceInitResult();
        if (templateJson == null || templateJson.isBlank()) {
            return result;
        }
        JsonNode arr;
        try {
            arr = JsonUtils.getMapper().readTree(templateJson);
        } catch (Exception e) {
            log.warn("Resource template parse failed: {}", e.getMessage());
            result.failed("template parse error: " + e.getMessage());
            return result;
        }
        if (arr == null || !arr.isArray()) {
            result.failed("template is not a JSON array");
            return result;
        }
        for (JsonNode item : arr) {
            if (!item.isObject()) {
                continue;
            }
            String type = item.path("type").asText("");
            ResourceInitializer initializer = byType.get(type);
            if (initializer == null) {
                result.unknown(type);
                continue;
            }
            try {
                initializer.initialize(item, ctx);
                result.created();
            } catch (ResourceExistsException e) {
                result.skipped();
            } catch (Exception e) {
                log.warn("Resource template init failed type={}: {}", type, e.getMessage());
                result.failed(type + ": " + e.getMessage());
            }
        }
        return result;
    }
}
