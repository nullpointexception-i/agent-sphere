package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.admin.spi.PermissionSpi;
import com.buukle.agent.admin.spi.TenantScopeSpi;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.SessionSurfaceHolder;
import com.buukle.agent.common.context.TenantContext;
import com.buukle.agent.common.context.TenantScopeHolder;
import com.buukle.agent.infrastructure.config.AuthInterceptor;
import com.buukle.agent.infrastructure.persistence.CacheService;
import com.buukle.agent.instance.domain.AgentUser;
import com.buukle.agent.instance.dtvo.enums.UserEnum;
import com.buukle.agent.instance.repository.UserMapper;
import com.buukle.agent.instance.spi.UserTokenSpi;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthInterceptorTest {

    @Mock
    UserMapper userMapper;
    @Mock
    CacheService cacheService;
    @Mock
    PermissionSpi permissionSpi;
    @Mock
    UserTokenSpi userTokenSpi;
    @Mock
    TenantScopeSpi tenantScopeSpi;

    @InjectMocks
    AuthInterceptor authInterceptor;

    @AfterEach
    void tearDown() {
        AuthContext.clear();
        SessionSurfaceHolder.clear();
        TenantScopeHolder.clear();
        TenantContext.clear();
    }

    @Test
    void externalApiPath_shouldBypassBearerWithoutSettingUser() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        org.mockito.Mockito.when(request.getRequestURI()).thenReturn("/api/v1/api/completions");

        boolean allowed = authInterceptor.preHandle(request, response, null);

        assertTrue(allowed);
        assertNull(AuthContext.getUsername());
        verify(userMapper, never()).selectOne(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void otherApiPath_shouldNotBeExemptedByExternalPrefix() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        org.mockito.Mockito.when(request.getRequestURI()).thenReturn("/api/v1/admin/completions");
        org.mockito.Mockito.when(response.getWriter())
                .thenReturn(new java.io.PrintWriter(new java.io.StringWriter()));

        boolean allowed = authInterceptor.preHandle(request, response, null);

        assertFalse(allowed);
    }

    @Test
    void validSessionToken_shouldResolveUserAndAllow() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getRequestURI()).thenReturn("/api/v1/instance/instances");
        when(request.getHeader("Authorization")).thenReturn("Bearer raw-token");
        when(cacheService.get(anyString())).thenReturn(null);
        when(userTokenSpi.validate("raw-token")).thenReturn(7L);
        when(userMapper.selectById(7L)).thenReturn(activeUser(7L, "alice"));
        when(permissionSpi.listCodesByUserId(7L, null)).thenReturn(List.of());

        boolean allowed = authInterceptor.preHandle(request, response, null);

        assertTrue(allowed);
        assertEquals(7L, AuthContext.getUserId());
        assertEquals("alice", AuthContext.getUsername());
    }

    @Test
    void revokedSessionToken_shouldBeRejected() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getRequestURI()).thenReturn("/api/v1/instance/instances");
        when(request.getHeader("Authorization")).thenReturn("Bearer revoked-token");
        when(cacheService.get(anyString())).thenReturn(null);
        when(userTokenSpi.validate("revoked-token")).thenReturn(null);
        when(response.getWriter()).thenReturn(new java.io.PrintWriter(new java.io.StringWriter()));

        boolean allowed = authInterceptor.preHandle(request, response, null);

        assertFalse(allowed);
        assertNull(AuthContext.getUserId());
    }

    @Test
    void disabledUser_shouldBeRejectedEvenWithValidToken() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getRequestURI()).thenReturn("/api/v1/instance/instances");
        when(request.getHeader("Authorization")).thenReturn("Bearer raw-token");
        when(cacheService.get(anyString())).thenReturn(null);
        when(userTokenSpi.validate("raw-token")).thenReturn(8L);
        AgentUser disabled = activeUser(8L, "bob");
        disabled.setStatus(UserEnum.STATUS_DISABLED);
        when(userMapper.selectById(8L)).thenReturn(disabled);
        when(response.getWriter()).thenReturn(new java.io.PrintWriter(new java.io.StringWriter()));

        boolean allowed = authInterceptor.preHandle(request, response, null);

        assertFalse(allowed);
        assertNull(AuthContext.getUserId());
    }

    private static AgentUser activeUser(Long id, String username) {
        AgentUser user = new AgentUser();
        user.setId(id);
        user.setUsername(username);
        user.setDisplayName(username);
        user.setStatus(UserEnum.STATUS_ACTIVE);
        user.setSuperAdmin(UserEnum.NOT_SUPER_ADMIN);
        return user;
    }
}
