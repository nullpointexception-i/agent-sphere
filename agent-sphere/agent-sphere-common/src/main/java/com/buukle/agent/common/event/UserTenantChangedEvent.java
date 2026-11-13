package com.buukle.agent.common.event;

/**
 * Published after a user is assigned to, or switches to, a tenant. The resource-template module
 * uses it to initialize that tenant's private user resources.
 */
public record UserTenantChangedEvent(Long userId, Long tenantId) {
}
