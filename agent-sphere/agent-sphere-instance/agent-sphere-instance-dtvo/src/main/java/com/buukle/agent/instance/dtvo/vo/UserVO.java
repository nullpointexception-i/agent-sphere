package com.buukle.agent.instance.dtvo.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class UserVO implements Serializable {
    private Long id;
    private String username;
    private String displayName;
    private String englishName;
    private String email;
    private String avatar;
    private String token;
    private String status;
    private String superAdmin;
    private Boolean demo;
    private Long currentTenantId;
    private String currentTenantName;
    private LocalDateTime lastLoginAt;
    private List<String> roles;
    private List<UserRoleVO> roleDetails;
    private List<String> permissions;
    /** External IdP provider code (for example bole); null for local users. */
    private String ssoProviderCode;
    /** External IdP display username, shown as provider@subject. */
    private String ssoSubject;
}
