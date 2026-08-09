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
 * 任务契约校验：用 expectedOutput JSON Schema 校验提炼阶段输出的结构化 JSON。
 * 基于 com.networknt:json-schema-validator（draft 2020-12，与契约 schema 版本一致）。
 */
@Slf4j
@Component
public class TaskContractValidator {

    private final JsonSchemaFactory factory =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    /**
     * 校验 content 是否符合 schema。
     *
     * @return 校验错误列表；为空表示通过
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
            return List.of("校验执行失败: " + e.getMessage());
        }
    }

    /** 是否通过。 */
    public boolean isValid(String schemaJson, String contentJson) {
        return validate(schemaJson, contentJson).isEmpty();
    }
}
