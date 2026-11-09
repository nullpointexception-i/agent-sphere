package com.buukle.agent.instance.dtvo.vo;

import lombok.Data;

import java.io.Serializable;

/** Lightweight current user identity for cross-domain display. */
@Data
public class UserIdentityVO implements Serializable {
    private Long id;
    private String username;
    private String displayName;
    private String englishName;
    private String avatar;
    /** External IdP provider code (for example bole); null for local users. */
    private String ssoProviderCode;
    /** External IdP display username, shown as provider@subject. */
    private String ssoSubject;
}
