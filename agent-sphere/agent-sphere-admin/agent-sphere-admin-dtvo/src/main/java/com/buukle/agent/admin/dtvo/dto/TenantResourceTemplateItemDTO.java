package com.buukle.agent.admin.dtvo.dto;

import com.buukle.agent.admin.dtvo.enums.TenantResourceTemplateType;
import lombok.Data;

/**
 * One entry of {@code tenant.resource-template}.
 *
 * <p>Union-style DTO: {@link #type} selects which fields are used, so JSON is bound once instead of
 * walking a generic tree with string keys.
 */
@Data
public class TenantResourceTemplateItemDTO {

    private TenantResourceTemplateType type;
    private String name;

    // model_provider
    private String baseUrl;
    private String config;

    // model_route
    private String provider;
    private String modelName;
    private Long maxInputTokens;
    private Long maxOutputTokens;
    private Boolean isDefault;
    private Boolean supportsAttachment;
    private String company;

    // instance
    private String description;
    private String systemPrompt;
    private String businessType;
    private String instanceRole;
    private String route;
    private Boolean allowConsoleSession;
    private Boolean allowWidgetSession;
}
