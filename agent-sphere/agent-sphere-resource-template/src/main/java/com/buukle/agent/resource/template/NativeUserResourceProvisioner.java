package com.buukle.agent.resource.template;

import com.buukle.agent.common.config.SystemConfigKeys;
import com.buukle.agent.common.config.SystemConfigSpi;
import com.buukle.agent.common.event.UserRegisteredEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 自助注册用户资源初始化：监听 {@link UserRegisteredEvent}，读取 user.resource-template
 * 系统配置（留空回落默认模板 ResourceTemplates.DEFAULT），异步复用
 * {@link UserResourceProvisioner} 为用户开通一份私有资源副本。与 SSO 首登开通同构，
 * 全程绝不影响注册接口返回。
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