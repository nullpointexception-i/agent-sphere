package com.buukle.agent.infrastructure.config;

import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.TenantUtil;
import com.buukle.agent.common.context.WithTenant;
import com.buukle.agent.infrastructure.persistence.CacheService;
import com.buukle.agent.instance.domain.AgentUser;
import com.buukle.agent.instance.dtvo.enums.UserEnum;
import com.buukle.agent.instance.repository.UserMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;

@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private static final List<String> SKIP_PATHS = List.of("/api/v1/auth/login", "/api/v1/auth/register");
    private static final String TOKEN_CACHE_PREFIX = "token:user:";
    private final UserMapper userMapper;
    private final CacheService cacheService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String path = request.getRequestURI();
        if (SKIP_PATHS.stream().anyMatch(path::startsWith)) return true;

        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            String cacheKey = TOKEN_CACHE_PREFIX + token;
            AgentUser user = cacheService.get(cacheKey);
            if (user == null) {
                user = userMapper.selectOne(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AgentUser>()
                                .eq(AgentUser::getToken, token));
                if (user != null) {
                    cacheService.set(cacheKey, user, 5);
                }
            }
            if (user != null) {
                AuthContext.setToken(token);
                AuthContext.setUsername(user.getUsername());
                AuthContext.setDisplayName(user.getDisplayName());

                if (handler instanceof HandlerMethod hm) {
                    Class<?> beanType = ClassUtils.getUserClass(hm.getBeanType());
                    if (beanType.isAnnotationPresent(WithTenant.class)
                            && !UserEnum.IS_SUPER_ADMIN.equals(user.getSuperAdmin())) {
                        TenantUtil.start(user.getUsername());
                    }
                }
                return true;
            }
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        TenantUtil.stop();
        AuthContext.clear();
    }
}
