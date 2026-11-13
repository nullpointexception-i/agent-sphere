package com.buukle.agent.superagent.dtvo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class BindEntityAgentDTO {
    @NotBlank
    private String entityType;
    @NotNull
    private Long entityId;
    @NotNull
    private Long agentId;
    private String bindingRole;
}
