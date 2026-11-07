package com.buukle.agent.instance.dtvo.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * One login session of the current user (used by "device management / sign out all devices").
 */
@Data
public class UserSessionVO implements Serializable {
    private Long id;
    /** CONSOLE / WIDGET / INTERNAL / LEGACY。 */
    private String source;
    private String loginIp;
    private String userAgent;
    private LocalDateTime expiresAt;
    private LocalDateTime lastUsedAt;
    private LocalDateTime createdAt;
    /** Whether this is the session used by the current request. */
    private Boolean current;
}
