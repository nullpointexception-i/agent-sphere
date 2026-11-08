package com.buukle.agent.admin.spi;

import com.buukle.agent.common.context.TenantScope;

import java.util.List;
import java.util.Set;

/**
 * Resolves a user's tenant visibility scope and supplies the default assignment
 * for newly created users.
 */
public interface TenantScopeSpi {

    /**
     * Resolve the current tenant scope for a user. Implementations must fail
     * closed: no assignment or a disabled current tenant yields an empty scope.
     */
    TenantScope resolve(Long userId, String username);

    /**
     * Ensure a user has at least one active assignment and exactly one current
     * tenant. Used by registration and SSO provisioning.
     */
    void ensureDefaultAssignment(Long userId);

    /**
     * Ensure a user has an active assignment to the given tenant and that it is
     * the current tenant. Used by SSO provisioning.
     */
    void ensureAssignment(Long userId, Long tenantId);

    /** Default tenant id used for self-registration. */
    Long defaultTenantId();

    boolean isTenantActive(Long tenantId);

    /**
     * The tenant itself plus every ancestor up to the root. Used by tenant-scoped hubs whose
     * content is inherited by child tenants (the platform tenant's hub is platform-wide).
     */
    List<Long> ancestorTenantIds(Long tenantId);

    /** User ids assigned to any of the supplied tenants. Used for user/contact lists. */
    List<Long> listUserIdsInTenants(Set<Long> tenantIds);
}
