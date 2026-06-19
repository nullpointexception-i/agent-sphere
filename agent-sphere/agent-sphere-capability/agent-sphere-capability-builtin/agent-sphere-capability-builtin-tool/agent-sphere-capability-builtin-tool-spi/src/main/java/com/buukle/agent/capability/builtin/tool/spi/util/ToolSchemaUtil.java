package com.buukle.agent.capability.builtin.tool.spi.util;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.Schema;

import java.util.List;
import java.util.Map;

public final class ToolSchemaUtil {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private static final String SCHEMA_REF_PREFIX = "#/components/schemas/";

    private ToolSchemaUtil() {}

    public static String generateParamSchema(Class<?> contextClass) {
        try {
            Map<String, Schema> schemas = ModelConverters.getInstance().readAll(contextClass);
            if (schemas.isEmpty()) return "{}";
            Schema mainSchema = schemas.get(contextClass.getSimpleName());
            if (mainSchema == null) mainSchema = schemas.values().iterator().next();
            resolveRefs(mainSchema, schemas);
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(mainSchema);
        } catch (Exception e) {
            return "{\"error\":\"Failed to generate schema: " + e.getMessage() + "\"}";
        }
    }

    private static void resolveRefs(Schema<?> schema, Map<String, Schema> allSchemas) {
        if (schema == null) return;

        if (schema.get$ref() != null && schema.get$ref().startsWith(SCHEMA_REF_PREFIX)) {
            String refName = schema.get$ref().substring(SCHEMA_REF_PREFIX.length());
            Schema<?> resolved = allSchemas.get(refName);
            if (resolved != null) {
                schema.set$ref(null);
                schema.setType(resolved.getType());
                schema.setProperties(resolved.getProperties());
                schema.setRequired(resolved.getRequired());
                schema.setDescription(resolved.getDescription());
                resolveRefs(schema, allSchemas);
                return;
            }
        }

        if (schema.getProperties() != null) {
            for (Object value : schema.getProperties().values()) {
                if (value instanceof Schema) resolveRefs((Schema<?>) value, allSchemas);
            }
        }

        if (schema.getItems() != null) {
            resolveRefs(schema.getItems(), allSchemas);
        }

        for (Schema<?> sub : listOrEmpty(schema.getAllOf())) resolveRefs(sub, allSchemas);
        for (Schema<?> sub : listOrEmpty(schema.getAnyOf())) resolveRefs(sub, allSchemas);
        for (Schema<?> sub : listOrEmpty(schema.getOneOf())) resolveRefs(sub, allSchemas);
    }

    @SuppressWarnings("unchecked")
    private static List<Schema<?>> listOrEmpty(List<?> list) {
        return list != null ? (List<Schema<?>>) list : List.of();
    }
}
