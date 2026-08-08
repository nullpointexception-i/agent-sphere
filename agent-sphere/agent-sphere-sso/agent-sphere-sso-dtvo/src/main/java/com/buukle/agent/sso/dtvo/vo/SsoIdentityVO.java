package com.buukle.agent.sso.dtvo.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 当前用户的第三方身份认证信息（SSO 登录来源）。
 */
@Data
public class SsoIdentityVO implements Serializable {
    private String providerCode;
    private String subject;
}
