package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.instance.dtvo.dto.RegisterDTO;
import com.buukle.agent.instance.dtvo.vo.UserVO;
import com.buukle.agent.instance.spi.UserSpi;
import com.buukle.agent.sso.domain.SsoIdentity;
import com.buukle.agent.sso.repository.SsoIdentityMapper;
import com.buukle.agent.sso.service.SsoProvisioningService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SsoProvisioningServiceTest {

    @Mock
    SsoIdentityMapper ssoIdentityMapper;

    @Mock
    UserSpi userSpi;

    SsoProvisioningService service;

    @BeforeEach
    void setUp() {
        service = new SsoProvisioningService(ssoIdentityMapper, userSpi);
    }

    private UserVO userVO(Long id) {
        UserVO vo = new UserVO();
        vo.setId(id);
        vo.setUsername("business_7");
        return vo;
    }

    @Test
    void provisionOrGet_shouldPassDefaultRoleIdToRegister() {
        when(ssoIdentityMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(userSpi.register(any(RegisterDTO.class), any())).thenReturn(userVO(7L));

        SsoProvisioningService.ProvisionResult result =
                service.provisionOrGet("business", "7", null, null, null, 42L);

        assertEquals(7L, result.userId());
        assertEquals("business_7", result.username());
        assertTrue(result.created());
        ArgumentCaptor<RegisterDTO> dto = ArgumentCaptor.forClass(RegisterDTO.class);
        ArgumentCaptor<Long> roleId = ArgumentCaptor.forClass(Long.class);
        verify(userSpi).register(dto.capture(), roleId.capture());
        assertEquals("business_7", dto.getValue().getUsername());
        assertNotNull(dto.getValue().getPassword());
        assertEquals(dto.getValue().getPassword(), dto.getValue().getRepeatPassword());
        assertEquals(42L, roleId.getValue());
        verify(ssoIdentityMapper).insert(any(SsoIdentity.class));
    }

    @Test
    void provisionOrGet_shouldPassNullDefaultRoleIdWhenAbsent() {
        when(ssoIdentityMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(userSpi.register(any(RegisterDTO.class), any())).thenReturn(userVO(7L));

        SsoProvisioningService.ProvisionResult result =
                service.provisionOrGet("business", "7", null, null, null, null);

        ArgumentCaptor<Long> roleId = ArgumentCaptor.forClass(Long.class);
        verify(userSpi).register(any(RegisterDTO.class), roleId.capture());
        assertEquals(null, roleId.getValue());
        assertTrue(result.created());
    }

    @Test
    void provisionOrGet_shouldRefreshDisplaySubjectOnExistingIdentity() {
        SsoIdentity existing = new SsoIdentity();
        existing.setAgentUserId(9L);
        existing.setProviderCode("business");
        existing.setSubject("7");
        existing.setDisplaySubject("old-name");
        when(ssoIdentityMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);
        when(userSpi.getByUserId(9L)).thenReturn(userVO(9L));

        SsoProvisioningService.ProvisionResult result =
                service.provisionOrGet("business", "7", null, "new-name", "new-name", 42L);

        assertEquals(9L, result.userId());
        assertEquals("business_7", result.username());
        assertFalse(result.created());
        assertEquals("new-name", existing.getDisplaySubject());
        verify(ssoIdentityMapper).updateById((SsoIdentity) any(SsoIdentity.class));
        verify(userSpi, never()).register(any(), any());
    }

    @Test
    void provisionOrGet_shouldSkipUpdateWhenDisplaySubjectUnchanged() {
        SsoIdentity existing = new SsoIdentity();
        existing.setAgentUserId(9L);
        existing.setProviderCode("business");
        existing.setSubject("7");
        existing.setDisplaySubject("same");
        when(ssoIdentityMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);
        when(userSpi.getByUserId(9L)).thenReturn(userVO(9L));

        service.provisionOrGet("business", "7", null, "same", "same", null);

        verify(ssoIdentityMapper, never()).updateById(any(SsoIdentity.class));
        verify(userSpi, never()).register(any(), any());
    }
}
