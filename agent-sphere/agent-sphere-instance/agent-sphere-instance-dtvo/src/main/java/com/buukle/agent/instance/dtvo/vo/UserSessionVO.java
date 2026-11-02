package com.buukle.agent.instance.dtvo.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 当前用户的一条登录会话（用于「设备管理 / 退出所有设备」）。
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
    /** 是否当前请求正在使用的会话。 */
    private Boolean current;
}
