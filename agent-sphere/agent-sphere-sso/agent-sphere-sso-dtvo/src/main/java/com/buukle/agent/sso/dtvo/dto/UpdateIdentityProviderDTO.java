package com.buukle.agent.sso.dtvo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

@Data
public class UpdateIdentityProviderDTO implements Serializable {
    @NotBlank(message = "name can't be blank")
    @Size(min = 1, max = 128)
    private String name;
    @NotBlank(message = "issuer can't be blank")
    @Size(min = 1, max = 512)
    private String issuer;
    @NotBlank(message = "clientId can't be blank")
    @Size(min = 1, max = 256)
    private String clientId;
    @Size(min = 1, max = 1024)
    private String clientSecret;
    @NotBlank(message = "authorizationEndpoint can't be blank")
    @Size(min = 1, max = 512)
    private String authorizationEndpoint;
    @NotBlank(message = "tokenEndpoint can't be blank")
    @Size(min = 1, max = 512)
    private String tokenEndpoint;
    @NotBlank(message = "jwksUrl can't be blank")
    @Size(min = 1, max = 512)
    private String jwksUrl;
    @Size(max = 512)
    private String scopes;
    @Size(max = 5000)
    private String claimMappings;
    private Long defaultRoleId;
    @Size(max = 500)
    private String remark;
}