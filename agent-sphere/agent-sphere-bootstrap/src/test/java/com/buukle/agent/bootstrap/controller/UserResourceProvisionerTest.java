package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.admin.spi.TenantScopeSpi;
import com.buukle.agent.common.config.SystemConfigKeys;
import com.buukle.agent.common.config.SystemConfigSpi;
import com.buukle.agent.common.context.TenantContext;
import com.buukle.agent.common.context.TenantScope;
import com.buukle.agent.common.context.TenantScopeHolder;
import com.buukle.agent.common.context.TenantUtil;
import com.buukle.agent.instance.dtvo.vo.UserVO;
import com.buukle.agent.instance.spi.UserSpi;
import com.buukle.agent.resource.template.ResourceInitContext;
import com.buukle.agent.resource.template.ResourceInitResult;
import com.buukle.agent.resource.template.ResourceTemplateCoordinator;
import com.buukle.agent.resource.template.UserResourceProvisioner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserResourceProvisionerTest {

    @Mock
    UserSpi userSpi;

    @Mock
    ResourceTemplateCoordinator coordinator;

    @Mock
    SystemConfigSpi systemConfigSpi;

    @Mock
    TenantScopeSpi tenantScopeSpi;

    @InjectMocks
    UserResourceProvisioner provisioner;

    @BeforeEach
    void setUp() {
        lenient().when(tenantScopeSpi.resolve(anyLong(), anyString()))
                .thenReturn(TenantScope.tenantOnly(2L, "business_7"));
    }

    @AfterEach
    void tearDown() {
        TenantUtil.stop();
        TenantScopeHolder.clear();
        TenantContext.clear();
    }

    private UserVO user(Long id, String username) {
        UserVO vo = new UserVO();
        vo.setId(id);
        vo.setUsername(username);
        return vo;
    }

    @Test
    void provision_shouldUseCustomTemplateAndStampOwner() {
        when(userSpi.getByUserId(7L)).thenReturn(user(7L, "business_7"));
        when(coordinator.initialize(anyString(), any(ResourceInitContext.class)))
                .thenAnswer(inv -> {
                    assertEquals("business_7", TenantUtil.get(), "租户应在协调器执行期间生效");
                    return new ResourceInitResult();
                });

        provisioner.provision(7L, "business", "业务身份源", "[{\"type\":\"document\"}]");

        ArgumentCaptor<String> template = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<ResourceInitContext> ctx = ArgumentCaptor.forClass(ResourceInitContext.class);
        verify(coordinator).initialize(template.capture(), ctx.capture());
        assertEquals("[{\"type\":\"document\"}]", template.getValue());
        assertEquals("business", ctx.getValue().getProviderCode());
        assertEquals("业务身份源", ctx.getValue().getProviderName());
        assertEquals("business_7", ctx.getValue().getOperator());
        assertNull(TenantUtil.get(), "租户应在协调器执行后清理");
    }

    @Test
    void provision_shouldUseDefaultTemplateWhenBlank() {
        when(userSpi.getByUserId(7L)).thenReturn(user(7L, "business_7"));
        when(systemConfigSpi.get(eq(SystemConfigKeys.USER_RESOURCE_TEMPLATE), eq("")))
                .thenReturn("[{\"type\":\"document\"}]");
        when(coordinator.initialize(anyString(), any(ResourceInitContext.class)))
                .thenReturn(new ResourceInitResult());

        provisioner.provision(7L, "business", "业务身份源", null);

        ArgumentCaptor<String> template = ArgumentCaptor.forClass(String.class);
        verify(coordinator).initialize(template.capture(), any(ResourceInitContext.class));
        assertEquals("[{\"type\":\"document\"}]", template.getValue());
    }

    @Test
    void provisionForTenant_shouldUseTargetTenantAndRestorePreviousContext() {
        when(userSpi.getByUserId(7L)).thenReturn(user(7L, "business_7"));
        when(coordinator.initialize(anyString(), any(ResourceInitContext.class)))
                .thenAnswer(inv -> {
                    assertEquals(3L, TenantContext.get());
                    assertEquals(3L, TenantScopeHolder.currentTenantId());
                    assertEquals("business_7", TenantUtil.get());
                    return new ResourceInitResult();
                });

        TenantContext.set(2L);
        TenantScope previousScope = TenantScope.tenantOnly(2L, "admin");
        TenantScopeHolder.start(previousScope);
        TenantUtil.start("admin");

        provisioner.provisionForTenant(
                7L, 3L, "business", "业务身份源", "[{\"type\":\"document\"}]");

        assertEquals(2L, TenantContext.get());
        assertEquals(previousScope, TenantScopeHolder.get());
        assertEquals("admin", TenantUtil.get());
    }

    @Test
    void provision_shouldStopTenantAndReturnFailedOnCoordinatorError() {
        when(userSpi.getByUserId(7L)).thenReturn(user(7L, "business_7"));
        when(coordinator.initialize(anyString(), any(ResourceInitContext.class)))
                .thenThrow(new IllegalStateException("boom"));

        ResourceInitResult result = provisioner.provision(7L, "business", "业务身份源", "[]");

        assertEquals(0, result.getCreated());
        assertTrue(result.getFailed() > 0);
        assertNull(TenantUtil.get());
    }

    @Test
    void provision_shouldFailFastWhenUserMissing() {
        when(userSpi.getByUserId(999L)).thenReturn(null);

        ResourceInitResult result = provisioner.provision(999L, "business", "业务身份源", "[]");

        assertTrue(result.getFailed() > 0);
        verify(coordinator, never()).initialize(anyString(), any(ResourceInitContext.class));
        assertNull(TenantUtil.get());
    }
}
