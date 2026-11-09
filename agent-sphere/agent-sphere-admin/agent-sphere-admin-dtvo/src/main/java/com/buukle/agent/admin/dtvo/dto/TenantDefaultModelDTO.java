package com.buukle.agent.admin.dtvo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/** Manually entered default model configuration for a new tenant. */
@Data
public class TenantDefaultModelDTO implements Serializable {

    @Size(max = 64)
    private String providerName;

    /** Provider company/type used by the route, e.g. openai / qwen / deepseek. */
    @NotBlank
    @Size(max = 64)
    private String providerCompany;

    @NotBlank
    @Size(max = 500)
    private String baseUrl;

    @Size(max = 5000)
    private String providerConfig;

    @Size(max = 255)
    private String apiKeyName;

    @NotBlank
    @Size(max = 2000)
    private String apiKeyValue;

    @NotBlank
    @Size(max = 64)
    private String modelName;

    private Long maxInputTokens;

    private Long maxOutputTokens;

    private Boolean supportsAttachment;
}
