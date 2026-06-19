package com.buukle.agent.runtime.kernel.port.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.io.Serializable;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuntimeTool implements Serializable {
    private String capabilityType;
    private Long capabilityId;
    private String llmToolName;
    private String displayName;
    private String description;
    private String parametersSchemaJson;
    private Map<String, Object> execBinding;
}
