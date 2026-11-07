package com.buukle.agent.resource.template;

import com.buukle.agent.common.config.SystemConfigKeys;
import com.buukle.agent.common.config.SystemConfigSpi;
import com.buukle.agent.common.context.TenantUtil;
import com.buukle.agent.instance.dtvo.vo.UserVO;
import com.buukle.agent.instance.spi.UserSpi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * SSO user resource provisioning: on first login, generates a private resource copy for the user
 * from the identity provider's resource template.
 * {@link TenantUtil} isolates data permissions and audit filling per user, so every SPI query inside
 * {@link ResourceTemplateCoordinator} is automatically scoped to created_by = username,
 * name-based duplicate detection naturally applies per user, and new resources record created_by
 * as that user.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserResourceProvisioner {

    private final ResourceTemplateCoordinator resourceTemplateCoordinator;
    private final UserSpi userSpi;
    private final SystemConfigSpi systemConfigSpi;

    /**
     * Provisions a resource copy for the user. Any exception is caught and recorded in the result;
     * it never affects the login flow.
     */
    public ResourceInitResult provision(Long userId, String providerCode, String providerName, String customTemplate) {
        UserVO user = userSpi.getByUserId(userId);
        if (user == null) {
            ResourceInitResult result = new ResourceInitResult();
            result.failed("user not found: " + userId);
            return result;
        }
        String template = customTemplate;
        if (!StringUtils.hasText(template)) {
            template = systemConfigSpi.get(SystemConfigKeys.USER_RESOURCE_TEMPLATE, "");
        }
        String owner = user.getUsername();
        TenantUtil.start(owner);
        try {
            return resourceTemplateCoordinator.initialize(
                    template, new ResourceInitContext(providerCode, providerName, owner));
        } catch (Exception e) {
            log.error("User resource provisioning failed: owner={}, provider={}", owner, providerCode, e);
            ResourceInitResult result = new ResourceInitResult();
            result.failed("provisioning error: " + e.getMessage());
            return result;
        } finally {
            TenantUtil.stop();
        }
    }
}
