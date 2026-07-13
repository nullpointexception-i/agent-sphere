package com.buukle.agent.infrastructure.config;

import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.TenantUtil;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncConfig {

    private final AgentRuntimeProperties properties;

    public AsyncConfig(AgentRuntimeProperties properties) {
        this.properties = properties;
    }

    @Bean("runtimeAsyncExecutor")
    public Executor runtimeAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("runtime-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds((int) properties.getAsync().getAwaitTermination().getSeconds());
        executor.setTaskDecorator(runnable -> {
            String tenant = TenantUtil.get();
            String token = AuthContext.getToken();
            String username = AuthContext.getUsername();
            String displayName = AuthContext.getDisplayName();
            Long userId = AuthContext.getUserId();
            Boolean superAdmin = AuthContext.isSuperAdmin();
            return () -> {
                if (tenant != null && !tenant.isBlank()) TenantUtil.start(tenant);
                if (token != null) AuthContext.setToken(token);
                if (username != null) AuthContext.setUsername(username);
                if (displayName != null) AuthContext.setDisplayName(displayName);
                if (userId != null) AuthContext.setUserId(userId);
                AuthContext.setSuperAdmin(superAdmin != null && superAdmin);
                try {
                    runnable.run();
                } finally {
                    TenantUtil.stop();
                    AuthContext.clear();
                }
            };
        });
        executor.initialize();
        return executor;
    }
}
