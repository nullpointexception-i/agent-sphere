package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.admin.spi.PermissionSpi;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.infrastructure.config.AuthInterceptor;
import com.buukle.agent.infrastructure.persistence.CacheService;
import com.buukle.agent.instance.repository.UserMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AuthInterceptorTest {

    @Mock
    UserMapper userMapper;
    @Mock
    CacheService cacheService;
    @Mock
    PermissionSpi permissionSpi;

    @InjectMocks
    AuthInterceptor authInterceptor;

    @AfterEach
    void tearDown() {
        AuthContext.clear();
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
}
