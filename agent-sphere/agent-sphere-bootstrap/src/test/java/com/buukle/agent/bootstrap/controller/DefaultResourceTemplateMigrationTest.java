package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.util.json.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 校验 V63 迁移写入的 user.resource-template 在 PostgreSQL standard_conforming_strings=on
 * 语义下存储为合法 JSON（V62 曾因 Java 双反斜杠转义导致 JSON 损坏，防止复发）。
 */
class DefaultResourceTemplateMigrationTest {

    @Test
    void v63TemplateShouldBeValidJsonAfterPostgresLiteralSemantics() throws Exception {
        String sql = new ClassPathResource("db/migration/V63__repair_default_resource_template.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        String value = extractInsertValue(sql);
        // PostgreSQL standard_conforming_strings=on: backslashes literal, '' -> '
        String stored = value.replace("''", "'");
        assertFalse(stored.contains("\\\\"), "stored template must not contain double backslashes");

        JsonNode arr = JsonUtils.getMapper().readTree(stored);
        assertTrue(arr.isArray());
        assertEquals(17, arr.size());
        for (JsonNode item : arr) {
            assertTrue(item.has("type"));
        }
        JsonNode resumeParse = findBy(arr, "businessType", "resume_parse");
        JsonNode config = JsonUtils.getMapper().readTree(resumeParse.get("config").asText());
        assertTrue(config.has("temperature"));
        assertEquals(0.1, config.get("temperature").asDouble());
        JsonNode instance = findBy(arr, "type", "instance");
        assertTrue(instance.get("systemPrompt").asText().contains("\n"));
    }

    private static JsonNode findBy(JsonNode arr, String field, String value) {
        for (JsonNode item : arr) {
            if (value.equals(item.path(field).asText())) {
                return item;
            }
        }
        throw new AssertionError("not found: " + field + "=" + value);
    }

    private static String extractInsertValue(String sql) {
        String marker = "VALUES ('user', 'user.resource-template', '";
        int start = sql.indexOf(marker) + marker.length();
        int end = sql.indexOf("', false,", start);
        if (start < marker.length() || end < 0) {
            throw new AssertionError("V63 INSERT value block not found");
        }
        return sql.substring(start, end);
    }
}