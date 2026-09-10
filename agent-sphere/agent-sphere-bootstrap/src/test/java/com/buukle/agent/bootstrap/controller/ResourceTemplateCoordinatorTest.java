package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.resource.template.ResourceExistsException;
import com.buukle.agent.resource.template.ResourceInitContext;
import com.buukle.agent.resource.template.ResourceInitResult;
import com.buukle.agent.resource.template.ResourceInitializer;
import com.buukle.agent.resource.template.ResourceTemplateCoordinator;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void initialize_shouldHandleBlankAndInvalidTemplates() {
        ResourceTemplateCoordinator coordinator = new ResourceTemplateCoordinator(List.of());
        assertEquals(0, coordinator.initialize(null, ctx).getCreated());
        assertEquals(0, coordinator.initialize("   ", ctx).getCreated());
        assertEquals(1, coordinator.initialize("{broken", ctx).getFailed());
        assertEquals(1, coordinator.initialize("{\"a\":1}", ctx).getFailed());
    }
}
