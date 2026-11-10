package com.buukle.agent.superagent.dtvo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

@Data
public class CreateSuperAgentDTO implements Serializable {

    @NotBlank
    @Size(max = 128)
    private String name;
    @Size(max = 512)
    private String description;
    /** CLOUD (default) or LOCAL. */
    @Size(max = 16)
    private String agentType;
    /** Required when agentType=LOCAL. */
    private Long localRuntimeId;
    /** Role dictionary code (system_dic.type=POSITION); required on creation. */
    @Size(max = 64)
    private String positionCode;
    /** Budget/trigger/reply rules (JSON). */
    private String config;
}
