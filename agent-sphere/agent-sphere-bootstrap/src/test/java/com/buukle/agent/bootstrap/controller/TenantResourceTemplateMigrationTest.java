package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.util.json.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The tenant.resource-template seeded by V172 must be a valid JSON array. */
class TenantResourceTemplateMigrationTest {

    @Test
    void v172TemplateShouldBeValidJson() throws Exception {
        String sql = new ClassPathResource("db/migration/V172__tenant_resource_template.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        String stored = extractTemplate(sql);
        assertFalse(stored.contains("\\\\"), "stored template must not contain double backslashes");

        JsonNode arr = JsonUtils.getMapper().readTree(stored);
        assertTrue(arr.isArray());
        assertEquals(3, arr.size());

        JsonNode provider = findBy(arr, "type", "model_provider");
        assertFalse(provider.has("apiKeyId"), "tenant provider must start without an API key");

        JsonNode route = findBy(arr, "type", "model_route");
        assertEquals("deepseek", route.path("company").asText());
        assertFalse(route.path("isDefault").asBoolean());

        JsonNode instance = findBy(arr, "type", "instance");
        assertEquals("GROUP_ROUTER", instance.path("instanceRole").asText());
        assertEquals(route.path("modelName").asText(), instance.path("route").asText());
    }

    private static JsonNode findBy(JsonNode arr, String field, String value) {
        for (JsonNode item : arr) {
            if (value.equals(item.path(field).asText())) {
                return item;
            }
        }
        throw new AssertionError("not found: " + field + "=" + value);
    }

    private static String extractTemplate(String sql) {
        String marker = "'tenant.resource-template',";
        int markerIndex = sql.indexOf(marker);
        if (markerIndex < 0) {
            throw new AssertionError("tenant.resource-template INSERT not found");
        }
        int start = sql.indexOf('\'', markerIndex + marker.length());
        start = sql.indexOf('[', start);
        int end = sql.indexOf("]'", start);
        if (start < 0 || end < 0) {
            throw new AssertionError("tenant.resource-template JSON array not found");
        }
        return sql.substring(start, end + 1).replace("''", "'");
    }
}
