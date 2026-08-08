package com.buukle.agent.resource.template;

import com.buukle.agent.common.context.TenantUtil;
import com.buukle.agent.instance.dtvo.vo.UserVO;
import com.buukle.agent.instance.spi.UserSpi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * SSO 用户资源开通：首次登录时按身份源资源模板为用户生成一份私有资源副本。
 * 通过 {@link TenantUtil} 将数据权限与审计填充按用户隔离，使
 * {@link ResourceTemplateCoordinator} 内所有 SPI 查询自动限定 created_by = username，
 * 同名判重天然按用户生效，新建资源 created_by 记为该用户。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserResourceProvisioner {

    private final ResourceTemplateCoordinator resourceTemplateCoordinator;
    private final UserSpi userSpi;

    /**
     * 为用户开通资源副本。任何异常均被捕获计入结果，绝不影响登录流程。
     */
    public ResourceInitResult provision(Long userId, String providerCode, String providerName, String customTemplate) {
        UserVO user = userSpi.getByUserId(userId);
        if (user == null) {
            ResourceInitResult result = new ResourceInitResult();
            result.failed("user not found: " + userId);
            return result;
        }
        String template = StringUtils.hasText(customTemplate) ? customTemplate : ResourceTemplates.DEFAULT;
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
