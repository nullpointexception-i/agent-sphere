package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.common.config.SystemConfigSpi;
import com.buukle.agent.instance.spi.UserSpi;
import com.buukle.agent.resource.template.ResourceInitResult;
import com.buukle.agent.resource.template.UserResourceProvisioner;
import com.buukle.agent.sso.domain.IdentityProvider;
import com.buukle.agent.sso.dtvo.enums.SsoProviderEnum;
import com.buukle.agent.sso.repository.IdentityProviderMapper;
import com.buukle.agent.sso.repository.SsoIdentityMapper;
import com.buukle.agent.sso.service.SsoOidcClient;
import com.buukle.agent.sso.service.SsoProvisioningService;
import com.buukle.agent.sso.service.SsoServiceImpl;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SsoCallbackProvisioningTest {

    @Mock
    IdentityProviderMapper identityProviderMapper;
    @Mock
    SsoIdentityMapper ssoIdentityMapper;
    @Mock
    SsoOidcClient ssoOidcClient;
    @Mock
    SsoProvisioningService provisioningService;
    @Mock
    UserResourceProvisioner userResourceProvisioner;
    @Mock
    ObjectProvider<UserResourceProvisioner> userResourceProvisionerProvider;
    @Mock
    UserSpi userSpi;
    @Mock
    RedissonClient redissonClient;
    @Mock
    SystemConfigSpi systemConfigSpi;
    @Mock
    RBucket<Object> bucket;

    AgentRuntimeProperties properties = new AgentRuntimeProperties();
    SsoServiceImpl ssoService;

    @BeforeEach
    void setUp() {
        ssoService = new SsoServiceImpl(identityProviderMapper, ssoIdentityMapper, ssoOidcClient,
                provisioningService, userResourceProvisionerProvider, userSpi, redissonClient, properties,
                systemConfigSpi);
        when(redissonClient.getBucket(anyString())).thenReturn(bucket);
        when(bucket.get()).thenReturn("business|https://app.example/cb|verifier|nonce");
        when(systemConfigSpi.get(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(1));
    }

    private IdentityProvider provider() {
        IdentityProvider p = new IdentityProvider();
        p.setCode("business");
        p.setName("业务身份源");
        p.setEnabled(true);
        p.setStatus(SsoProviderEnum.STATUS_ACTIVE);
        p.setResourceTemplate("[{\"type\":\"document\"}]");
        return p;
    }

    @Test
    void callback_shouldProvisionUserResourcesOnFirstLogin() {
        when(identityProviderMapper.selectOne(any(Wrapper.class))).thenReturn(provider());
        when(ssoOidcClient.exchangeAndVerify(any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new SsoOidcClient.IdTokenClaims("7", "e@x.com", "name", "pref"));
        when(provisioningService.provisionOrGet(anyString(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(SsoProvisioningService.ProvisionResult.of(7L, "business_7", true));
        when(userResourceProvisionerProvider.getIfAvailable()).thenReturn(userResourceProvisioner);
        when(userResourceProvisioner.provision(any(), anyString(), anyString(), anyString()))
                .thenReturn(new ResourceInitResult());

        String redirect = ssoService.callback("code", "state", "iss", null);

        assertNotNull(redirect);
        assertTrue(redirect.contains("otc="));
        ArgumentCaptor<Long> userId = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> template = ArgumentCaptor.forClass(String.class);
        verify(userResourceProvisioner).provision(userId.capture(), code.capture(), name.capture(), template.capture());
        assertTrue(userId.getValue() == 7L);
        assertTrue("business".equals(code.getValue()));
        assertTrue("业务身份源".equals(name.getValue()));
        assertTrue("[{\"type\":\"document\"}]".equals(template.getValue()));
    }

    @Test
    void callback_shouldSkipProvisioningOnReturningLogin() {
        when(identityProviderMapper.selectOne(any(Wrapper.class))).thenReturn(provider());
        when(ssoOidcClient.exchangeAndVerify(any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new SsoOidcClient.IdTokenClaims("7", "e@x.com", "name", "pref"));
        when(provisioningService.provisionOrGet(anyString(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(SsoProvisioningService.ProvisionResult.of(7L, "business_7", false));

        String redirect = ssoService.callback("code", "state", "iss", null);

        assertNotNull(redirect);
        assertTrue(redirect.contains("otc="));
        verify(userResourceProvisioner, never()).provision(any(), anyString(), anyString(), anyString());
    }

    @Test
    void callback_shouldNotFailLoginWhenProvisioningThrows() {
        when(identityProviderMapper.selectOne(any(Wrapper.class))).thenReturn(provider());
        when(ssoOidcClient.exchangeAndVerify(any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new SsoOidcClient.IdTokenClaims("7", "e@x.com", "name", "pref"));
        when(provisioningService.provisionOrGet(anyString(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(SsoProvisioningService.ProvisionResult.of(7L, "business_7", true));
        when(userResourceProvisionerProvider.getIfAvailable()).thenReturn(userResourceProvisioner);
        when(userResourceProvisioner.provision(any(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("boom"));

        String redirect = ssoService.callback("code", "state", "iss", null);

        assertNotNull(redirect);
        assertTrue(redirect.contains("otc="));
    }
}
