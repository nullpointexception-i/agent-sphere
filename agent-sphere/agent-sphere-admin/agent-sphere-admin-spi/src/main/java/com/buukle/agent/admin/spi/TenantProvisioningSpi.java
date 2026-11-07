package com.buukle.agent.admin.spi;

/**
 * System-level tenant provisioning for integrations such as SSO identity
 * providers. Unlike the admin tenant API, these calls run without a request
 * tenant scope and always mount the new tenant under the platform tenant.
 */
public interface TenantProvisioningSpi {

    /**
     * Create a tenant under the platform tenant, including its TENANT_ADMIN and
     * USER role templates, and return the new tenant id plus its USER role id.
     */
    ProvisionedTenant createUnderPlatform(String code, String name, String remark);

    /** Display name of a tenant, or null when it does not exist. */
    String findTenantName(Long tenantId);

    record ProvisionedTenant(
            Long tenantId,
            String tenantCode,
            String tenantName,
            Long userRoleId) {
    }
}
