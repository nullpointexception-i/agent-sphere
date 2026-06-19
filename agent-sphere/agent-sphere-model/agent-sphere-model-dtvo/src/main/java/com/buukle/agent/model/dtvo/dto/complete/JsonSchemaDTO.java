package com.buukle.agent.model.dtvo.dto.complete;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

@Data
@Accessors(chain = true)
public class JsonSchemaDTO implements Serializable {
    private String name;
    @JsonProperty("schema")
    private JsonNode schema;
}
