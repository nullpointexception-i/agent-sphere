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
    private Boolean apiKeyConfigured;
    private String createdAt;
    private String createdBy;
    private String updatedBy;
    private String updatedAt;
}
