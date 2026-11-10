package com.buukle.agent.superagent.controller;

import com.buukle.agent.common.constant.ApiPaths;
import com.buukle.agent.superagent.service.LocalRuntimeAuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers device-token authentication for the local runtime endpoints. */
@Configuration
@RequiredArgsConstructor
public class LocalRuntimeWebConfig implements WebMvcConfigurer {

    private final LocalRuntimeAuthInterceptor localRuntimeAuthInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(localRuntimeAuthInterceptor)
                .addPathPatterns(ApiPaths.LOCAL_RUNTIME_DEVICE,
                        ApiPaths.LOCAL_RUNTIME_DEVICE + "/**");
    }
}
