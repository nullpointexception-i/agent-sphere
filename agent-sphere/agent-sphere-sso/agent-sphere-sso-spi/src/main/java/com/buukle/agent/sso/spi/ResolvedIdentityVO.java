package com.buukle.agent.sso.spi;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.Serializable;

/**
 * 外部调用方解析出的 agent-sphere 身份。
 */
@Data
@AllArgsConstructor
public class ResolvedIdentityVO implements Serializable {
    private Long agentUserId;
    private String username;
    private String displayName;

    public static ResolvedIdentityVO of(Long agentUserId, String username, String displayName) {
        return new ResolvedIdentityVO(agentUserId, username, displayName);
    }
}
