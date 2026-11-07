package com.buukle.agent.sso.spi;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.Serializable;

/**
 * agent-sphere identity resolved from an external caller.
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
