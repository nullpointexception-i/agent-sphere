package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.admin.spi.RoleSpi;
import com.buukle.agent.instance.service.impl.UserServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceRegisterTest {

    @Mock
    RoleSpi roleSpi;

    @Test
    void resolveInitialRoleIds_shouldUseDefaultRoleWhenProvided() {
        List<Long> ids = UserServiceImpl.resolveInitialRoleIds(roleSpi, 2L, 9L);
        assertEquals(List.of(2L), ids);
    }

    @Test
    void resolveInitialRoleIds_shouldResolveUserRoleInsideTenant() {
        when(roleSpi.findIdByTenantAndCode(9L, "USER")).thenReturn(42L);
        List<Long> ids = UserServiceImpl.resolveInitialRoleIds(roleSpi, null, 9L);
        assertEquals(List.of(42L), ids);
    }

    @Test
    void resolveInitialRoleIds_shouldReturnEmptyWhenNoUserRole() {
        when(roleSpi.findIdByTenantAndCode(9L, "USER")).thenReturn(null);
        List<Long> ids = UserServiceImpl.resolveInitialRoleIds(roleSpi, null, 9L);
        assertEquals(0, ids.size());
    }
}
