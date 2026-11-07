package com.buukle.agent.superagent.service.impl;

import com.buukle.agent.common.context.TenantScopeHolder;


import com.buukle.agent.instance.dtvo.enums.InstanceRole;import com.buukle.agent.common.constant.CompletionProtocol;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.TenantUtil;
import com.buukle.agent.instance.dtvo.dto.CreateInstanceDTO;
import com.buukle.agent.instance.dtvo.vo.InstanceVO;
import com.buukle.agent.instance.spi.InstanceSpi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Shared Group Router instance initializer.
 *
 * <p>The Group Router is a single globally shared instance (not one per digital employee); at startup it
 * looks up {@code instance_role=GROUP_ROUTER AND system_managed=true} and creates the instance
 * automatically when a model route is configured and none exists. Even without configuring
 * {@code group-router.instance-id}, the scheduler falls back to this record.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GroupRouterInitializer implements ApplicationRunner {

    private static final String ROLE_GROUP_ROUTER = InstanceRole.GROUP_ROUTER.value();
    private static final String BUSINESS_TYPE = "SUPER_AGENT";

    private final InstanceSpi instanceSpi;

    @Value("${buukle.agent.super-agent.enabled:false}")
    private boolean superAgentEnabled;
    @Value("${buukle.agent.super-agent.group-router.auto-create:true}")
    private boolean autoCreate;
    @Value("${buukle.agent.super-agent.group-router.instance-id:0}")
    private Long configuredInstanceId;
    @Value("${buukle.agent.super-agent.group-router.model-route-id:0}")
    private Long groupRouterModelRouteId;
    @Value("${buukle.agent.super-agent.router.default-model-route-id:0}")
    private Long defaultModelRouteId;

    @Override
    public void run(ApplicationArguments args) {
        if (!superAgentEnabled || !autoCreate) {
            return;
        }
        TenantUtil.stop();
        TenantScopeHolder.clear();
        AuthContext.clear();
        InstanceVO existing = instanceSpi.findByRole(ROLE_GROUP_ROUTER);
        if (existing != null) {
            log.info("Group router instance ready: id={} (configured id={})",
                    existing.getId(), configuredInstanceId);
            return;
        }
        Long modelRouteId = positive(groupRouterModelRouteId) != null
                ? groupRouterModelRouteId : positive(defaultModelRouteId);
        if (modelRouteId == null) {
            log.warn("Group router instance not found and no model route configured; "
                    + "set super-agent.group-router.model-route-id or router.default-model-route-id");
            return;
        }
        CreateInstanceDTO dto = new CreateInstanceDTO();
        dto.setName("Group Router");
        dto.setDescription("Shared group-level dispatcher for Super-Agent");
        dto.setModelRouteId(modelRouteId);
        dto.setInstanceRole(ROLE_GROUP_ROUTER);
        dto.setSystemManaged(true);
        dto.setCompletionProtocol(CompletionProtocol.IMPLICIT_TEXT.name());
        dto.setBusinessType(BUSINESS_TYPE);
        dto.setAllowConsoleSession(false);
        dto.setAllowWidgetSession(false);
        InstanceVO created = instanceSpi.createInstance(dto);
        log.info("Group router instance created: id={}; configure "
                + "super-agent.group-router.instance-id={} if you prefer an explicit id",
                created.getId(), created.getId());
    }

    private static Long positive(Long value) {
        return value != null && value > 0 ? value : null;
    }
}
