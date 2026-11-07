package com.buukle.agent.infrastructure.config;


import com.buukle.agent.common.constant.ApiPaths;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final RateLimitInterceptor rateLimitInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor).addPathPatterns(
                ApiPaths.AUTH_LOGIN, ApiPaths.AUTH_SSO_EXCHANGE);
        registry.addInterceptor(authInterceptor).addPathPatterns("/api/**");
    }
}
