package com.buukle.agent.tasks.service;

import com.buukle.agent.util.json.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Task contract validation: validates the structured JSON produced by the extraction phase against
 * the expectedOutput JSON Schema.
 * Based on com.networknt:json-schema-validator (draft 2020-12, matching the contract schema version).
 */
@Slf4j
@Component
public class TaskContractValidator {

    private final JsonSchemaFactory factory =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    /**
     * Validates content against the schema.
     *
     * @return list of validation errors; empty means it passed
     */
    public List<String> validate(String schemaJson, String contentJson) {
        if (schemaJson == null || schemaJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode schemaNode = JsonUtils.getMapper().readTree(schemaJson);
            JsonSchema schema = factory.getSchema(SchemaLocation.of("contract-schema"), schemaNode);
            JsonNode contentNode = JsonUtils.getMapper().readTree(contentJson);
            Set<ValidationMessage> errors = schema.validate(contentNode);
            return errors.stream().map(ValidationMessage::getMessage).toList();
        } catch (Exception e) {
            log.warn("Task contract validation failed to run: {}", e.getMessage());
            return List.of("Validation execution failed: " + e.getMessage());
        }
    }

    /** Whether it passes. */
    public boolean isValid(String schemaJson, String contentJson) {
        return validate(schemaJson, contentJson).isEmpty();
    }
}
