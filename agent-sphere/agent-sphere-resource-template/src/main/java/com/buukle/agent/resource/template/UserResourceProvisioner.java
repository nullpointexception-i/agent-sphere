package com.buukle.agent.resource.template;

import com.buukle.agent.admin.spi.TenantScopeSpi;
import com.buukle.agent.common.config.SystemConfigKeys;
import com.buukle.agent.common.config.SystemConfigSpi;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.TenantContext;
import com.buukle.agent.common.context.TenantScope;
import com.buukle.agent.common.context.TenantScopeHolder;
import com.buukle.agent.common.context.TenantUtil;
import com.buukle.agent.common.event.UserTenantChangedEvent;
import com.buukle.agent.instance.dtvo.vo.UserVO;
import com.buukle.agent.instance.spi.UserSpi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.StringUtils;

import java.util.Set;

/**
 * SSO user resource provisioning: on first login, generates a private resource copy for the user
 * from the identity provider's resource template.
 * {@link TenantUtil} isolates data permissions and audit filling per user, so every SPI query inside
 * {@link ResourceTemplateCoordinator} is automatically scoped to created_by = username,
 * name-based duplicate detection naturally applies per user, and new resources record created_by
 * as that user.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserResourceProvisioner {

    private final ResourceTemplateCoordinator resourceTemplateCoordinator;
    private final UserSpi userSpi;
    private final SystemConfigSpi systemConfigSpi;
    private final TenantScopeSpi tenantScopeSpi;

    /** Provider metadata used when the assignment comes from tenant administration rather than SSO. */
    private static final String ASSIGNMENT_PROVIDER_CODE = "native";
    private static final String ASSIGNMENT_PROVIDER_NAME = "AgentSphere";

    /**
     * Provisions a resource copy for the user's current tenant. Any exception is caught and
     * recorded in the result; it never affects the login flow.
     */
    public ResourceInitResult provision(Long userId, String providerCode, String providerName, String customTemplate) {
        UserVO user = userSpi.getByUserId(userId);
        if (user == null) {
            return failed("user not found: " + userId);
        }
        TenantScope scope = tenantScopeSpi.resolve(userId, user.getUsername());
        return provisionInTenant(user, scope == null ? null : scope.currentTenantId(),
                providerCode, providerName, resolveTemplate(customTemplate));
    }

    /**
     * Provisions a resource copy for an explicitly selected tenant. Used when a user is assigned
     * to, or switches to, a tenant after registration.
     */
    public ResourceInitResult provisionForTenant(Long userId, Long tenantId,
                                                 String providerCode, String providerName,
                                                 String customTemplate) {
        UserVO user = userSpi.getByUserId(userId);
        if (user == null) {
            return failed("user not found: " + userId);
        }
        return provisionInTenant(user, tenantId, providerCode, providerName,
                resolveTemplate(customTemplate));
    }

    /** Tenant assignment/switch is committed before private defaults are initialized. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onUserTenantChanged(UserTenantChangedEvent event) {
        if (event == null || event.userId() == null || event.tenantId() == null) {
            return;
        }
        try {
            ResourceInitResult result = provisionForTenant(
                    event.userId(), event.tenantId(),
                    ASSIGNMENT_PROVIDER_CODE, ASSIGNMENT_PROVIDER_NAME, null);
            if (result.getFailed() > 0) {
                log.warn("User tenant resource provisioning had {} failures: user={}, tenant={}, details={}",
                        result.getFailed(), event.userId(), event.tenantId(), result.getFailedDetails());
            }
        } catch (Exception e) {
            log.warn("User tenant resource provisioning failed: user={}, tenant={}",
                    event.userId(), event.tenantId(), e);
        }
    }

    private String resolveTemplate(String customTemplate) {
        if (StringUtils.hasText(customTemplate)) {
            return customTemplate;
        }
        return systemConfigSpi.get(SystemConfigKeys.USER_RESOURCE_TEMPLATE, "");
    }

    private ResourceInitResult provisionInTenant(UserVO user, Long tenantId,
                                                 String providerCode, String providerName,
                                                 String template) {
        if (tenantId == null) {
            return failed("user has no tenant assignment: " + user.getId());
        }
        String owner = user.getUsername();
        ContextSnapshot previous = ContextSnapshot.capture();
        try {
            TenantContext.set(tenantId);
            // User defaults are private copies: keep created_by = owner so another user's
            // same-named provider is not mistaken for this user's resource.
            TenantScopeHolder.start(new TenantScope(
                    user.getId(), owner, tenantId, Set.of(tenantId), true, Set.of(tenantId)));
            AuthContext.clear();
            AuthContext.setUserId(user.getId());
            AuthContext.setUsername(owner);
            AuthContext.setDisplayName(owner);
            AuthContext.setSuperAdmin(false);
            AuthContext.setPermissions(Set.of());
            TenantUtil.start(owner);
            return resourceTemplateCoordinator.initialize(
                    template, new ResourceInitContext(providerCode, providerName, owner));
        } catch (Exception e) {
            log.error("User resource provisioning failed: owner={}, tenant={}, provider={}",
                    owner, tenantId, providerCode, e);
            return failed("provisioning error: " + e.getMessage());
        } finally {
            previous.restore();
        }
    }

    private static ResourceInitResult failed(String detail) {
        ResourceInitResult result = new ResourceInitResult();
        result.failed(detail);
        return result;
    }

    private record ContextSnapshot(
            String token,
            String username,
            String displayName,
            Long userId,
            boolean superAdmin,
            Set<String> permissions,
            Long tenantId,
            TenantScope scope,
            String tenantUtil) {

        static ContextSnapshot capture() {
            return new ContextSnapshot(
                    AuthContext.getToken(), AuthContext.getUsername(), AuthContext.getDisplayName(),
                    AuthContext.getUserId(), AuthContext.isSuperAdmin(),
                    AuthContext.getPermissions(), TenantContext.get(),
                    TenantScopeHolder.get(), TenantUtil.get());
        }

        void restore() {
            AuthContext.clear();
            if (token != null) {
                AuthContext.setToken(token);
            }
            if (username != null) {
                AuthContext.setUsername(username);
            }
            if (displayName != null) {
                AuthContext.setDisplayName(displayName);
            }
            if (userId != null) {
                AuthContext.setUserId(userId);
            }
            AuthContext.setSuperAdmin(superAdmin);
            if (permissions != null) {
                AuthContext.setPermissions(permissions);
            }
            if (tenantId == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(tenantId);
            }
            if (scope == null) {
                TenantScopeHolder.clear();
            } else {
                TenantScopeHolder.start(scope);
            }
            if (tenantUtil == null) {
                TenantUtil.stop();
            } else {
                TenantUtil.start(tenantUtil);
            }
        }
    }
}
