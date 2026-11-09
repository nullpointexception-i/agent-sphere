package com.buukle.agent.admin.service;

import com.buukle.agent.admin.domain.SysTenant;
import com.buukle.agent.admin.repository.SysPermissionMapper;
import com.buukle.agent.admin.repository.SysRoleMapper;
import com.buukle.agent.admin.repository.SysRolePermissionMapper;
import com.buukle.agent.admin.repository.SysTenantMapper;
import com.buukle.agent.admin.repository.SysUserRoleMapper;
import com.buukle.agent.admin.repository.SysUserTenantMapper;
import com.buukle.agent.admin.service.impl.TenantScopeServiceImpl;
import com.buukle.agent.admin.service.impl.TenantService;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.TenantContext;
import com.buukle.agent.common.context.TenantScopeHolder;
import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.model.dtvo.vo.ModelRouteVO;
import com.buukle.agent.model.spi.ApiKeySpi;
import com.buukle.agent.model.spi.ModelProviderSpi;
import com.buukle.agent.model.spi.RouteSpi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Group-router route configuration: reserved tenants are editable for this one
 * field, and the route must belong to the tenant (runtime resolves it inside
 * the group's tenant scope).
 */
@ExtendWith(MockitoExtension.class)
class TenantServiceRouteTest {

    @Mock
    SysTenantMapper tenantMapper;
    @Mock
    SysUserTenantMapper userTenantMapper;
    @Mock
    SysUserRoleMapper userRoleMapper;
    @Mock
    SysRoleMapper roleMapper;
    @Mock
    SysRolePermissionMapper rolePermissionMapper;
    @Mock
    SysPermissionMapper permissionMapper;
    @Mock
    TenantScopeServiceImpl tenantScopeService;
    @Mock
    ModelProviderSpi modelProviderSpi;
    @Mock
    ApiKeySpi apiKeySpi;
    @Mock
    RouteSpi routeSpi;

    @AfterEach
    void tearDown() {
        AuthContext.clear();
        TenantContext.clear();
        TenantScopeHolder.clear();
    }

    private TenantService service() {
        return new TenantService(tenantMapper, userTenantMapper, userRoleMapper, roleMapper,
                rolePermissionMapper, permissionMapper, tenantScopeService, modelProviderSpi,
                apiKeySpi, routeSpi);
    }

    @Test
    void defaultTenantAcceptsItsOwnRoute() {
        AuthContext.setSuperAdmin(true);
        SysTenant tenant = tenant(2L);
        when(tenantMapper.selectById(2L)).thenReturn(tenant);
        when(routeSpi.getRoute(5L)).thenReturn(new ModelRouteVO());

        SysTenant updated = service().updateGroupRouterRoute(2L, 5L);

        assertEquals(5L, updated.getGroupRouterModelRouteId());
        verify(tenantMapper).updateById(tenant);
    }

    @Test
    void foreignRouteIsRejected() {
        AuthContext.setSuperAdmin(true);
        SysTenant tenant = tenant(2L);
        when(tenantMapper.selectById(2L)).thenReturn(tenant);
        when(routeSpi.getRoute(99L)).thenThrow(new BizException(CommonErrorCode.PARAM_INVALID,
                "route not found"));

        BizException error = assertThrows(BizException.class,
                () -> service().updateGroupRouterRoute(2L, 99L));

        assertEquals(CommonErrorCode.PARAM_INVALID.getCode(), error.getErrorCode());
        verify(tenantMapper, never()).updateById(any(SysTenant.class));
    }

    @Test
    void platformTenantCanClearItsRoute() {
        AuthContext.setSuperAdmin(true);
        SysTenant tenant = tenant(1L);
        tenant.setGroupRouterModelRouteId(8L);
        when(tenantMapper.selectById(1L)).thenReturn(tenant);

        SysTenant updated = service().updateGroupRouterRoute(1L, null);

        assertNull(updated.getGroupRouterModelRouteId());
        verify(tenantMapper).updateById(tenant);
    }

    @Test
    void routeListIsQueriedInsideTheTargetTenantAndRestoresContext() {
        AuthContext.setSuperAdmin(true);
        when(routeSpi.listAllRoutes(isNull())).thenReturn(List.of(new ModelRouteVO()));
        TenantContext.set(1L);

        List<ModelRouteVO> routes = service().listModelRoutes(2L);

        assertEquals(1, routes.size());
        verify(routeSpi).listAllRoutes(isNull());
        // The caller's tenant context must be restored after the scoped query.
        assertEquals(1L, TenantContext.get());
        assertNull(TenantScopeHolder.get());
    }

    private static SysTenant tenant(Long id) {
        SysTenant tenant = new SysTenant();
        tenant.setId(id);
        tenant.setName("Tenant " + id);
        tenant.setStatus("ACTIVE");
        return tenant;
    }
}
