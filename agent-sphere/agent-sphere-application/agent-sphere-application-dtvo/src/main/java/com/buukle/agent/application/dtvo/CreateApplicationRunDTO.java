package com.buukle.agent.application.dtvo;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

@Data
public class CreateApplicationRunDTO {
    @NotBlank
    private String triggerType;
    private String triggerKey;
    private String clientRequestId;
    private String runMode;
    private Map<String, Object> input;
}
