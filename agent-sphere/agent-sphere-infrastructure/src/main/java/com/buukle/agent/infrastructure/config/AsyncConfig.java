package com.buukle.agent.infrastructure.config;

import com.buukle.agent.common.config.AgentRuntimeProperties;
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
        executor.initialize();
        return executor;
    }
}
