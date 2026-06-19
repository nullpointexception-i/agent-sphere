package com.buukle.agent.capability.builtin.tool.webfetch.config;

import com.buukle.agent.capability.builtin.tool.webfetch.util.FetchRetryPolicy;
import com.buukle.agent.common.config.AgentRuntimeProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * WebFetch 配置类 - 创建 FetchRetryPolicy Bean
 */
@Configuration
public class WebFetchConfiguration {

    @Bean
    public FetchRetryPolicy fetchRetryPolicy(AgentRuntimeProperties properties) {
        return new FetchRetryPolicy(properties.getTool().getWebFetchAdvanced());
    }
}
