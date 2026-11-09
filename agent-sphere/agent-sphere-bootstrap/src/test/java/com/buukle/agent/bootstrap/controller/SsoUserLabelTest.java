package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.resource.template.UserResourceProvisioner;
import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.common.config.SystemConfigSpi;
import com.buukle.agent.instance.spi.UserSpi;
import com.buukle.agent.sso.domain.SsoIdentity;
import com.buukle.agent.sso.repository.IdentityProviderMapper;
import com.buukle.agent.sso.repository.SsoIdentityMapper;
import com.buukle.agent.sso.service.SsoOidcClient;
import com.buukle.agent.sso.service.SsoProvisioningService;
import com.buukle.agent.sso.service.SsoServiceImpl;
import com.buukle.agent.sso.spi.SsoUserLabelVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Batch SSO labels used by workspace contacts and IM participant display. */
@ExtendWith(MockitoExtension.class)
class SsoUserLabelTest {

    @Mock
    IdentityProviderMapper identityProviderMapper;
    @Mock
    SsoIdentityMapper ssoIdentityMapper;
    @Mock
    SsoOidcClient ssoOidcClient;
    @Mock
    SsoProvisioningService provisioningService;
    @Mock
    ObjectProvider<UserResourceProvisioner> userResourceProvisionerProvider;
    @Mock
    UserSpi userSpi;
    @Mock
    RedissonClient redissonClient;
    @Mock
    SystemConfigSpi systemConfigSpi;

    SsoServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SsoServiceImpl(identityProviderMapper, ssoIdentityMapper, ssoOidcClient,
                provisioningService, userResourceProvisionerProvider, userSpi, redissonClient,
                new AgentRuntimeProperties(), systemConfigSpi);
    }

    @Test
    void labelsByUserIds_latestIdentityWinsAndFallsBackToSubject() {
        given(ssoIdentityMapper.selectList(any())).willReturn(List.of(
                identity(1L, "bole", "raw-1", "elvin2"),
                identity(1L, "bole", "raw-1", "elvin"),
                identity(2L, "bole", "raw-2", " ")));

        Map<Long, SsoUserLabelVO> labels = service.labelsByUserIds(List.of(1L, 2L, 1L));

        assertEquals(2, labels.size());
        assertEquals("bole@elvin2", label(labels.get(1L)));
        assertEquals("bole@raw-2", label(labels.get(2L)));
    }

    @Test
    void labelsByUserIds_emptyInputSkipsQuery() {
        assertTrue(service.labelsByUserIds(List.of()).isEmpty());
        verify(ssoIdentityMapper, never()).selectList(any());
    }

    private static String label(SsoUserLabelVO label) {
        return label.getProviderCode() + "@" + label.getSubject();
    }

    private static SsoIdentity identity(Long userId, String provider, String subject, String displaySubject) {
        SsoIdentity identity = new SsoIdentity();
        identity.setAgentUserId(userId);
        identity.setProviderCode(provider);
        identity.setSubject(subject);
        identity.setDisplaySubject(displaySubject);
        return identity;
    }
}
