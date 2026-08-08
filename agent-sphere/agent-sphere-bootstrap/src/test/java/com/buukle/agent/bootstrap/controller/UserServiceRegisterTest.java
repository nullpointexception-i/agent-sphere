package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.admin.dtvo.vo.SysRoleVO;
import com.buukle.agent.admin.spi.RoleSpi;
import com.buukle.agent.instance.service.impl.UserServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceRegisterTest {

    @Mock
    RoleSpi roleSpi;

    private SysRoleVO role(Long id, String code) {
        SysRoleVO vo = new SysRoleVO();
        vo.setId(id);
        vo.setCode(code);
        return vo;
    }

    @Test
    void resolveInitialRoleIds_shouldUseDefaultRoleWhenProvided() {
        List<Long> ids = UserServiceImpl.resolveInitialRoleIds(roleSpi, 2L);
        assertEquals(List.of(2L), ids);
    }

    @Test
    void resolveInitialRoleIds_shouldFallBackToUserRoleWhenDefaultAbsent() {
        when(roleSpi.listAll()).thenReturn(List.of(role(1L, "USER"), role(2L, "TENANT")));
        List<Long> ids = UserServiceImpl.resolveInitialRoleIds(roleSpi, null);
        assertEquals(List.of(1L), ids);
    }

    @Test
    void resolveInitialRoleIds_shouldReturnEmptyWhenNoUserRole() {
        when(roleSpi.listAll()).thenReturn(List.of(role(2L, "TENANT")));
        List<Long> ids = UserServiceImpl.resolveInitialRoleIds(roleSpi, null);
        assertEquals(0, ids.size());
        verify(roleSpi).listAll();
    }
}
