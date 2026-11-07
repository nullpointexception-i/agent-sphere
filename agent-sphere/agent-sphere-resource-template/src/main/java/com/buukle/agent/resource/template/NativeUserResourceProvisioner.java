package com.buukle.agent.resource.template;

import com.buukle.agent.common.config.SystemConfigKeys;
import com.buukle.agent.common.config.SystemConfigSpi;
import com.buukle.agent.common.event.UserRegisteredEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Self-service registration user resource initialization: listens for {@link UserRegisteredEvent},
 * reads the user.resource-template system config (empty means no resources are initialized), and
 * asynchronously delegates to {@link UserResourceProvisioner} to provision a private resource copy
 * for the user. Structurally identical to SSO first-login provisioning, and never affects the
 * registration endpoint's response.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NativeUserResourceProvisioner {

    private static final String PROVIDER_CODE = "native";
    private static final String PROVIDER_NAME = "AgentSphere";

    private final UserResourceProvisioner userResourceProvisioner;
    private final SystemConfigSpi systemConfigSpi;

    @EventListener
    public void onUserRegistered(UserRegisteredEvent event) {
        String template = systemConfigSpi.get(SystemConfigKeys.USER_RESOURCE_TEMPLATE, "");
        Thread.startVirtualThread(() -> {
            try {
                ResourceInitResult result = userResourceProvisioner.provision(
                        event.getUserId(), PROVIDER_CODE, PROVIDER_NAME, template);
                if (result != null && result.getFailed() > 0) {
                    log.warn("Native user {} resource provisioning had {} failures: {}",
                            event.getUsername(), result.getFailed(), result.getFailedDetails());
                }
            } catch (Exception e) {
                log.warn("Native user resource provisioning failed for user {}", event.getUsername(), e);
            }
        });
    }
}