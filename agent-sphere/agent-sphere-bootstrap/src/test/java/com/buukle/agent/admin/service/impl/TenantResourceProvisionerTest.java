package com.buukle.agent.admin.service.impl;

import com.buukle.agent.admin.domain.SysTenant;
import com.buukle.agent.common.config.SystemConfigKeys;
import com.buukle.agent.common.config.SystemConfigSpi;
import com.buukle.agent.common.constant.OwnerScope;
import com.buukle.agent.common.context.TenantContext;
import com.buukle.agent.common.context.TenantScopeHolder;
import com.buukle.agent.instance.dtvo.dto.CreateInstanceDTO;
import com.buukle.agent.instance.dtvo.enums.InstanceRole;
import com.buukle.agent.instance.dtvo.vo.InstanceVO;
import com.buukle.agent.instance.spi.InstanceSpi;
import com.buukle.agent.model.dtvo.dto.CreateModelProviderDTO;
import com.buukle.agent.model.dtvo.dto.CreateRouteDTO;
import com.buukle.agent.model.dtvo.vo.ModelProviderVO;
import com.buukle.agent.model.dtvo.vo.ModelRouteVO;
import com.buukle.agent.model.spi.ModelProviderSpi;
import com.buukle.agent.model.spi.RouteSpi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Tenant provisioning must create tenant-owned resources without an API key. */
@ExtendWith(MockitoExtension.class)
class TenantResourceProvisionerTest {

    private static final String TEMPLATE = """
            [
              {"type":"model_provider","name":"Tenant Provider","baseUrl":"https://api.example.com"},
              {"type":"model_route","provider":"Tenant Provider","modelName":"tenant-model","company":"deepseek"},
              {"type":"instance","name":"Tenant Router","instanceRole":"GROUP_ROUTER","route":"tenant-model"}
            ]
            """;

    @Mock
    SystemConfigSpi systemConfigSpi;
    @Mock
    ModelProviderSpi modelProviderSpi;
    @Mock
    RouteSpi routeSpi;
    @Mock
    InstanceSpi instanceSpi;

    @AfterEach
    void tearDown() {
        TenantScopeHolder.clear();
        TenantContext.clear();
    }

    private TenantResourceProvisioner provisioner() {
        return new TenantResourceProvisioner(systemConfigSpi, modelProviderSpi, routeSpi, instanceSpi);
    }

    private SysTenant tenant() {
        SysTenant tenant = new SysTenant();
        tenant.setId(9L);
        tenant.setName("Test tenant");
        return tenant;
    }

    @Test
    void provisionCreatesTenantOwnedResourcesAndReturnsCurrentInstance() {
        given(systemConfigSpi.get(SystemConfigKeys.TENANT_RESOURCE_TEMPLATE, ""))
                .willReturn(TEMPLATE);
        ModelProviderVO provider = new ModelProviderVO();
        provider.setId(11L);
        given(modelProviderSpi.createProvider(any())).willReturn(provider);
        ModelRouteVO route = new ModelRouteVO();
        route.setId(22L);
        given(routeSpi.createRoute(any())).willReturn(route);
        InstanceVO instance = new InstanceVO();
        instance.setId(33L);
        given(instanceSpi.createInstance(any())).willReturn(instance);

        Long current = provisioner().provision(tenant(), "tester");

        assertEquals(33L, current);

        ArgumentCaptor<CreateModelProviderDTO> providerCaptor =
                ArgumentCaptor.forClass(CreateModelProviderDTO.class);
        verify(modelProviderSpi).createProvider(providerCaptor.capture());
        assertEquals(OwnerScope.TENANT, providerCaptor.getValue().getOwnerScope());
        assertNull(providerCaptor.getValue().getApiKeyId());

        ArgumentCaptor<CreateRouteDTO> routeCaptor =
                ArgumentCaptor.forClass(CreateRouteDTO.class);
        verify(routeSpi).createRoute(routeCaptor.capture());
        assertEquals(11L, routeCaptor.getValue().getProviderId());
        assertFalse(routeCaptor.getValue().getIsDefault());

        ArgumentCaptor<CreateInstanceDTO> instanceCaptor =
                ArgumentCaptor.forClass(CreateInstanceDTO.class);
        verify(instanceSpi).createInstance(instanceCaptor.capture());
        assertEquals(OwnerScope.TENANT, instanceCaptor.getValue().getOwnerScope());
        assertEquals(InstanceRole.GROUP_ROUTER.value(),
                instanceCaptor.getValue().getInstanceRole());
        assertEquals(22L, instanceCaptor.getValue().getModelRouteId());
    }

    @Test
    void provisionSkipsWhenTemplateIsEmpty() {
        given(systemConfigSpi.get(SystemConfigKeys.TENANT_RESOURCE_TEMPLATE, ""))
                .willReturn("");

        Long current = provisioner().provision(tenant(), "tester");

        assertNull(current);
        verify(modelProviderSpi, never()).createProvider(any());
        verify(instanceSpi, never()).createInstance(any());
    }

    @Test
    void provisionRestoresPreviousTenantContext() {
        given(systemConfigSpi.get(SystemConfigKeys.TENANT_RESOURCE_TEMPLATE, ""))
                .willReturn("");
        TenantContext.set(2L);

        provisioner().provision(tenant(), "tester");

        assertEquals(2L, TenantContext.get());
        assertNull(TenantScopeHolder.get());
    }
}
