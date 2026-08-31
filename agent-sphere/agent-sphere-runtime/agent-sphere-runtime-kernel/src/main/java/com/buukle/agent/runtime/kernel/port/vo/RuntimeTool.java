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
    /** 稳定工具引用（builtin:/mcp:/cli:/skill:），供 Skill allowTools 白名单匹配。 */
    private String toolRef;
    private String displayName;
    private String displayNameCn;
    private String displayNameEn;
    private String description;
    private String parametersSchemaJson;
    private Map<String, Object> execBinding;
}
