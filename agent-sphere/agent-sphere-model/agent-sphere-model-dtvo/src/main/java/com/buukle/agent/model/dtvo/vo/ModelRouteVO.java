package com.buukle.agent.model.dtvo.vo;

import lombok.Data;

import java.io.Serializable;

@Data
public class ModelRouteVO implements Serializable {
    private Long id;
    private Long providerId;
    private String modelName;
    private Integer weight;
    private String fallbackIds;
    private String fallbackNames;
    private Long maxInputTokens;
    private Long maxOutputTokens;
    private String status;
    private String company;
    private String providerName;
    private Boolean supportsAttachment;
    private String completionProtocol;
    /** Whether this route is the owner's default route. */
    private Boolean isDefault;
    private String ownerScope;
    /** Owning tenant, for the cross-tenant system route view. */
    private Long tenantId;
    /** Reasoning strength override: NULL = inherit from provider. */
    private Boolean reasoningSupported;
    private String reasoningMode;
    /** JSON array string, e.g. ["low","high","max"]. */
    private String reasoningLevels;
    private java.math.BigDecimal reasoningAnswerReserveRatio;
    private Boolean apiKeyConfigured;
    private String createdAt;
    private String createdBy;
    private String updatedBy;
    private String updatedAt;
}
