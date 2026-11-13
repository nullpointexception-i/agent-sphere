package com.buukle.agent.admin.service.impl;

import com.buukle.agent.admin.constant.TenantConstants;
import com.buukle.agent.admin.domain.SysTenant;
import com.buukle.agent.admin.repository.SysTenantMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/** Startup must touch the default tenant only. */
@ExtendWith(MockitoExtension.class)
class DefaultTenantResourceInitializerTest {

    @Mock
    private SysTenantMapper tenantMapper;
    @Mock
    private TenantService tenantService;

    @InjectMocks
    private DefaultTenantResourceInitializer initializer;

    @Test
    void ensuresDefaultTenantOnly() {
        given(tenantMapper.selectById(TenantConstants.DEFAULT_TENANT_ID))
                .willReturn(tenant());

        initializer.ensureDefaultTenantResources();

        verify(tenantService).ensureTenantResources(TenantConstants.DEFAULT_TENANT_ID, "system");
        verifyNoMoreInteractions(tenantService);
    }

    @Test
    void skipsWhenDefaultTenantIsMissing() {
        given(tenantMapper.selectById(TenantConstants.DEFAULT_TENANT_ID)).willReturn(null);

        initializer.ensureDefaultTenantResources();

        verify(tenantService, never()).ensureTenantResources(
                TenantConstants.DEFAULT_TENANT_ID, "system");
    }

    @Test
    void provisioningFailureDoesNotBreakStartup() {
        given(tenantMapper.selectById(TenantConstants.DEFAULT_TENANT_ID))
                .willReturn(tenant());
        willThrow(new IllegalStateException("broken template"))
                .given(tenantService).ensureTenantResources(TenantConstants.DEFAULT_TENANT_ID, "system");

        assertDoesNotThrow(() -> initializer.ensureDefaultTenantResources());
    }

    private static SysTenant tenant() {
        SysTenant tenant = new SysTenant();
        tenant.setId(TenantConstants.DEFAULT_TENANT_ID);
        tenant.setCode("DEFAULT");
        tenant.setStatus(TenantConstants.STATUS_ACTIVE);
        return tenant;
    }
}
