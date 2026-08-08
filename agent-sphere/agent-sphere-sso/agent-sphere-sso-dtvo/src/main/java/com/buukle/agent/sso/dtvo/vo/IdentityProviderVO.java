package com.buukle.agent.sso.dtvo.vo;

import lombok.Data;

import java.io.Serializable;

@Data
public class IdentityProviderVO implements Serializable {
    private Long id;
    private String code;
    private String type;
    private String name;
    private String issuer;
    private String clientId;
    private String clientSecret;
    private Boolean hasSecret;
    private String authorizationEndpoint;
    private String tokenEndpoint;
    private String jwksUrl;
    private String scopes;
    private String claimMappings;
    private Long defaultRoleId;
    private String resourceTemplate;
    private Boolean enabled;
    private String status;
    private String remark;
    private String createdAt;
    private String createdBy;
    private String updatedBy;
    private String updatedAt;
}