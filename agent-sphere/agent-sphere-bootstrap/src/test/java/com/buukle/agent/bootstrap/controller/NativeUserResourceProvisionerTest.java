package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.common.config.SystemConfigKeys;
import com.buukle.agent.common.config.SystemConfigSpi;
import com.buukle.agent.common.event.UserRegisteredEvent;
import com.buukle.agent.resource.template.NativeUserResourceProvisioner;
import com.buukle.agent.resource.template.UserResourceProvisioner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class NativeUserResourceProvisionerTest {

    @Mock
    UserResourceProvisioner userResourceProvisioner;
    @Mock
    SystemConfigSpi systemConfigSpi;

    @InjectMocks
    NativeUserResourceProvisioner listener;

    private UserRegisteredEvent event(long userId, String username) {
        UserRegisteredEvent event = new UserRegisteredEvent();
        event.setUserId(userId);
        event.setUsername(username);
        return event;
    }

    @Test
    void onRegistered_usesConfiguredTemplateAsynchronously() {
        given(systemConfigSpi.get(SystemConfigKeys.USER_RESOURCE_TEMPLATE, ""))
                .willReturn("[{\"type\":\"document\"}]");

        listener.onUserRegistered(event(1L, "alice"));

        verify(userResourceProvisioner, org.mockito.Mockito.timeout(3000))
                .provision(eq(1L), eq("native"), eq("AgentSphere"), eq("[{\"type\":\"document\"}]"));
    }

    @Test
    void onRegistered_blankTemplateFallsBackToDefault() {
        given(systemConfigSpi.get(SystemConfigKeys.USER_RESOURCE_TEMPLATE, ""))
                .willReturn("");

        listener.onUserRegistered(event(2L, "bob"));

        verify(userResourceProvisioner, org.mockito.Mockito.timeout(3000))
                .provision(eq(2L), eq("native"), eq("AgentSphere"), eq(""));
    }
}