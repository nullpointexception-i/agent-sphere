package com.buukle.agent.infrastructure.config;

import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.TenantUtil;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean("runtimeAsyncExecutor")
    public Executor runtimeAsyncExecutor() {
        return task -> {
            String tenant = TenantUtil.get();
            String token = AuthContext.getToken();
            String username = AuthContext.getUsername();
            String displayName = AuthContext.getDisplayName();
            Long userId = AuthContext.getUserId();
            boolean superAdmin = AuthContext.isSuperAdmin();

            Thread.ofVirtual().name("runtime-vt-").start(() -> {
                try {
                    if (tenant != null && !tenant.isBlank()) TenantUtil.start(tenant);
                    AuthContext.setToken(token);
                    AuthContext.setUsername(username);
                    AuthContext.setDisplayName(displayName);
                    AuthContext.setUserId(userId);
                    AuthContext.setSuperAdmin(superAdmin);
                    task.run();
                } finally {
                    TenantUtil.stop();
                    AuthContext.clear();
                }
            });
        };
    }
}
