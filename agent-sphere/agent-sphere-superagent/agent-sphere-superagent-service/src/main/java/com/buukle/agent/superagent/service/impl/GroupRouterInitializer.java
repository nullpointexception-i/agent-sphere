package com.buukle.agent.superagent.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Group Router is a per-tenant system instance now.
 *
 * <p>Each tenant gets one {@code GROUP_ROUTER} system instance together with its
 * system model route; the instance is created lazily when the tenant's first
 * group is created. Nothing global is provisioned at startup.
 */
@Slf4j
@Component
public class GroupRouterInitializer implements ApplicationRunner {

    @Override
    public void run(ApplicationArguments args) {
        log.info("Group Router instances are resolved per tenant (instance_role=GROUP_ROUTER, system_managed=true)");
    }
}
