package com.buukle.agent.infrastructure.config;

import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.TaskLoopLimitHolder;
import com.buukle.agent.common.context.TenantUtil;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    @Primary
    @Bean("runtimeAsyncExecutor")
    public Executor runtimeAsyncExecutor() {
        return task -> {
            String tenant = TenantUtil.get();
            String token = AuthContext.getToken();
            String username = AuthContext.getUsername();
            String displayName = AuthContext.getDisplayName();
            Long userId = AuthContext.getUserId();
            boolean superAdmin = AuthContext.isSuperAdmin();
            Integer taskLoopLimit = TaskLoopLimitHolder.get();

            Thread.ofVirtual().name("runtime-vt-").start(() -> {
                try {
                    if (tenant != null && !tenant.isBlank()) TenantUtil.start(tenant);
                    AuthContext.setToken(token);
                    AuthContext.setUsername(username);
                    AuthContext.setDisplayName(displayName);
                    AuthContext.setUserId(userId);
                    AuthContext.setSuperAdmin(superAdmin);
                    if (taskLoopLimit != null) TaskLoopLimitHolder.set(taskLoopLimit);
                    task.run();
                } finally {
                    TenantUtil.stop();
                    AuthContext.clear();
                    TaskLoopLimitHolder.clear();
                }
            });
        };
    }

    @Bean("auditTaskExecutor")
    public Executor auditTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("audit-");
        executor.initialize();
        return executor;
    }
}
