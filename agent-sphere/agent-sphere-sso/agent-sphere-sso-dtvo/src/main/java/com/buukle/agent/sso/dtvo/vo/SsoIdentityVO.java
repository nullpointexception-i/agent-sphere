package com.buukle.agent.sso.dtvo.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * Third-party identity information for the current user (SSO login source).
 */
@Data
public class SsoIdentityVO implements Serializable {
    private String providerCode;
    private String subject;
}
