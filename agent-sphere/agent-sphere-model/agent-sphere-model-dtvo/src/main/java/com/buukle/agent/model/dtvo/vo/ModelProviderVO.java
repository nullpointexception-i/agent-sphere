package com.buukle.agent.model.dtvo.vo;

import lombok.Data;

import java.io.Serializable;

@Data
public class ModelProviderVO implements Serializable {
    private Long id;
    private String name;
    private String baseUrl;
    private Long apiKeyId;
    private String config;
    private String status;
    private Long tenantId;
    /** USER: private to the creator; TENANT: tenant-level resource. */
    private String ownerScope;
    private String createdAt;
    private String createdBy;
    private String updatedBy;
    private String updatedAt;
}
