package com.buukle.agent.sso.spi;

import java.io.Serializable;

/**
 * Caller authentication info for the external capability API: SSO code + subject (identity) + businessType (resource matching).
 */
public record CallerAuth(String code, String subject, String businessType) implements Serializable {

    public static CallerAuth of(String code, String subject, String businessType) {
        return new CallerAuth(code, subject, businessType);
    }
}
