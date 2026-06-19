package com.buukle.agent.model.dtvo.dto.complete;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolDefinitionDTO implements Serializable {
    private String type;
    private FunctionDefinitionDTO function;
}
