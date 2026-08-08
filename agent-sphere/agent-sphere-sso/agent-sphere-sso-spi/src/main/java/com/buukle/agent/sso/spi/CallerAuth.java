package com.buukle.agent.sso.spi;

import java.io.Serializable;

/**
 * 外部能力开放接口的调用方认证信息：SSO code + subject（身份）+ businessType（资源匹配）。
 */
public record CallerAuth(String code, String subject, String businessType) implements Serializable {

    public static CallerAuth of(String code, String subject, String businessType) {
        return new CallerAuth(code, subject, businessType);
    }
}
