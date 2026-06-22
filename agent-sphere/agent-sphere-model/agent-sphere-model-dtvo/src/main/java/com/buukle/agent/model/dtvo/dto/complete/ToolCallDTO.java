package com.buukle.agent.model.dtvo.dto.complete;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;

@Data
@Builder
public class ToolCallDTO implements Serializable {
    private String id;
    private String type;
    private FunctionDefinitionDTO function;
}
