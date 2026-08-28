package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.resource.template.ResourceExistsException;
import com.buukle.agent.resource.template.ResourceInitContext;
import com.buukle.agent.resource.template.ResourceInitResult;
import com.buukle.agent.resource.template.ResourceInitializer;
import com.buukle.agent.resource.template.ResourceTemplateCoordinator;
import com.buukle.agent.resource.template.ResourceTemplates;
import com.buukle.agent.util.json.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ResourceTemplateCoordinatorTest {

    private final ResourceInitContext ctx =
            new ResourceInitContext("business", "业务身份源", "admin");

    private ResourceInitializer initializer(String type, Runnable body) {
        return new ResourceInitializer() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public void initialize(JsonNode descriptor, ResourceInitContext context) {
                body.run();
            }
        };
    }

    @Test
    void initialize_shouldDispatchByTypeAndCountCreated() {
        ResourceTemplateCoordinator coordinator = new ResourceTemplateCoordinator(
                List.of(
                        initializer("model_provider", () -> {
                        }),
                        initializer("mcp", () -> {
                        })));

        ResourceInitResult result = coordinator.initialize("""
                [{"type":"model_provider","name":"DeepSeek"},
                 {"type":"mcp","name":"GitHub","url":"https://mcp.example.com"}]
                """, ctx);

        assertEquals(2, result.getCreated());
        assertEquals(0, result.getSkipped());
        assertEquals(0, result.getFailed());
    }

    @Test
    void initialize_shouldSkipWhenResourceExists() {
        ResourceTemplateCoordinator coordinator = new ResourceTemplateCoordinator(
                List.of(initializer("model_provider", () -> {
                    throw new ResourceExistsException();
                })));

        ResourceInitResult result = coordinator.initialize(
                "[{\"type\":\"model_provider\",\"name\":\"DeepSeek\"}]", ctx);

        assertEquals(0, result.getCreated());
        assertEquals(1, result.getSkipped());
        assertEquals(0, result.getFailed());
    }

    @Test
    void initialize_shouldCountFailedAndUnknownTypes() {
        ResourceTemplateCoordinator coordinator = new ResourceTemplateCoordinator(
                List.of(initializer("model_provider", () -> {
                    throw new IllegalStateException("upstream down");
                })));

        ResourceInitResult result = coordinator.initialize("""
                [{"type":"model_provider","name":"DeepSeek"},
                 {"type":"bogus_type"}]
                """, ctx);

        assertEquals(0, result.getCreated());
        assertEquals(1, result.getFailed());
        assertEquals(1, result.getUnknownTypes().size());
        assertEquals("bogus_type", result.getUnknownTypes().get(0));
        assertEquals("model_provider: upstream down", result.getFailedDetails().get(0));
    }

    @Test
    void defaultTemplate_shouldBeValidJsonWithEightCompletions() throws Exception {
        JsonNode arr = JsonUtils.getMapper().readTree(ResourceTemplates.DEFAULT);
        assertTrue(arr.isArray());
        List<JsonNode> completions = new ArrayList<>();
        for (JsonNode node : arr) {
            if ("completions".equals(node.path("type").asText())) {
                completions.add(node);
            }
        }
        assertEquals(8, completions.size());
        for (JsonNode c : completions) {
            assertTrue(c.hasNonNull("name"));
            assertTrue(c.hasNonNull("description"));
            assertTrue(c.hasNonNull("businessType"));
            assertTrue(c.hasNonNull("route"));
            assertTrue(c.hasNonNull("promptSystem"));
            assertTrue(c.hasNonNull("promptUser"));
            assertTrue(c.hasNonNull("inputSchema"));
            assertTrue(c.hasNonNull("outputSchema"));
            assertEquals("deepseek-v4-flash", c.path("route").asText());
            JsonNode config = JsonUtils.getMapper().readTree(c.path("config").asText());
            assertEquals(false, config.path("thinking").asBoolean());
        }
        Set<String> businessTypes = completions.stream()
                .map(c -> c.path("businessType").asText())
                .collect(Collectors.toSet());
        assertEquals(8, businessTypes.size(), "8 个 completions 的 businessType 应互不重复");

        JsonNode instance = null;
        for (JsonNode node : arr) {
            if ("instance".equals(node.path("type").asText())) {
                instance = node;
                break;
            }
        }
        assertTrue(instance != null, "默认模板应包含一个 instance 条目");
        assertEquals("Headhunter Assist", instance.path("name").asText());
        assertEquals("Headhunter Assist", instance.path("description").asText());
        assertEquals("task", instance.path("businessType").asText());
        assertEquals("deepseek-v4-flash", instance.path("route").asText());
        assertTrue(instance.hasNonNull("systemPrompt"));
        assertTrue(instance.path("systemPrompt").asText().contains("Strict rules"));
    }

    @Test
    void initialize_shouldHandleBlankAndInvalidTemplates() {
        ResourceTemplateCoordinator coordinator = new ResourceTemplateCoordinator(List.of());
        assertEquals(0, coordinator.initialize(null, ctx).getCreated());
        assertEquals(0, coordinator.initialize("   ", ctx).getCreated());
        assertEquals(1, coordinator.initialize("{broken", ctx).getFailed());
        assertEquals(1, coordinator.initialize("{\"a\":1}", ctx).getFailed());
    }
}
