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
    /** Stable tool reference (builtin:/mcp:/cli:/skill:/agent:), used for dedup and protocol routing. */
    private String toolRef;
    private String displayName;
    private String displayNameCn;
    private String displayNameEn;
    private String description;
    private String parametersSchemaJson;
    private Map<String, Object> execBinding;
    /** Tool result may be compressed in live/history projection. */
    private boolean compressEnabled;
    /** READ / WRITE / READ_WRITE. */
    private String accessMode;
    /** L1 / L2. */
    private String securityLevel;
    /** SESSION / RUN / ONCE (L2 only). */
    private String approvalScope;
}
