package com.buukle.agent.common.event;

import lombok.Data;

/** Published when a self-service registered user has been created (SSO provisioning via defaultRoleId does not publish it) */
@Data
public class UserRegisteredEvent {
    private Long userId;
    private String username;
}