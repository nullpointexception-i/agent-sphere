package com.buukle.agent.admin.service.impl;

import com.buukle.agent.admin.constant.TenantConstants;
import com.buukle.agent.admin.domain.SysTenant;
import com.buukle.agent.admin.repository.SysTenantMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Repairs the default tenant's resources once per boot.
 *
 * <p>The default tenant is created by a migration, not by {@link TenantService#create}, so it never
 * went through the normal provisioning path. Other tenants are deliberately not scanned here: they
 * are provisioned when they are created.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultTenantResourceInitializer {

    private final SysTenantMapper tenantMapper;
    private final TenantService tenantService;

    @EventListener(ApplicationReadyEvent.class)
    public void ensureDefaultTenantResources() {
        SysTenant tenant = tenantMapper.selectById(TenantConstants.DEFAULT_TENANT_ID);
        if (tenant == null || !TenantConstants.STATUS_ACTIVE.equals(tenant.getStatus())) {
            return;
        }
        try {
            tenantService.ensureTenantResources(TenantConstants.DEFAULT_TENANT_ID, "system");
            log.info("Default tenant resource check completed: tenant={}", tenant.getCode());
        } catch (Exception e) {
            // A broken template must not prevent the application from starting.
            log.error("Default tenant resource initialization failed: tenant={}, code={}",
                    tenant.getId(), tenant.getCode(), e);
        }
    }
}
