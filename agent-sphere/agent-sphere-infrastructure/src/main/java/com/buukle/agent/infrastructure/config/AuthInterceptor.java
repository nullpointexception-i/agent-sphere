package com.buukle.agent.infrastructure.config;

import com.buukle.agent.admin.spi.PermissionSpi;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.TenantUtil;
import com.buukle.agent.common.context.WithTenant;
import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.exception.ErrorResponse;
import com.buukle.agent.infrastructure.persistence.CacheService;
import com.buukle.agent.instance.domain.AgentUser;
import com.buukle.agent.instance.dtvo.enums.UserEnum;
import com.buukle.agent.instance.repository.UserMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;

@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private static final List<String> SKIP_PATHS = List.of("/api/v1/auth/login", "/api/v1/auth/register", "/api/v1/auth/check-username", "/api/v1/auth/sso", "/api/v1/chrome", "/api/v1/artifacts/documents/shared", "/api/v1/system/config/public", "/api/v1/system/config/plugin/download");
    private static final String EXTERNAL_API_PREFIX = "/api/v1/api";
    private static final String TOKEN_CACHE_PREFIX = "token:user:";
    private final UserMapper userMapper;
    private final CacheService cacheService;
    private final PermissionSpi permissionSpi;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        String path = request.getRequestURI();
        if (SKIP_PATHS.stream().anyMatch(path::startsWith)) return true;

        // 外部能力开放接口（/api/v1/api/*）：外部调用方直连免 token。
        // 仅放行，不设置用户身份：会话/任务归属由业务层按资源创建者（如 task 的 instance 创建者）显式初始化，
        // 避免外部调用污染为统一 service 用户而无法投递到真实用户的连接。
        // TODO 接口验签（后续版本）：校验来源签名后放行，当前阶段整体豁免。
        if (path.startsWith(EXTERNAL_API_PREFIX)) {
            return true;
        }

        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            writeUnauthorized(response);
            return false;
        }

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
        if (user == null) {
            writeUnauthorized(response);
            return false;
        }

        AuthContext.setToken(token);
        AuthContext.setUserId(user.getId());
        AuthContext.setUsername(user.getUsername());
        AuthContext.setDisplayName(user.getDisplayName());
        boolean isSuperAdmin = UserEnum.IS_SUPER_ADMIN.equals(user.getSuperAdmin());
        AuthContext.setSuperAdmin(isSuperAdmin);
        AuthContext.setPermissions(new HashSet<>(
                isSuperAdmin ? permissionSpi.listAllCodes() : permissionSpi.listCodesByUserId(user.getId())));

        if (handler instanceof HandlerMethod hm) {
            Class<?> beanType = ClassUtils.getUserClass(hm.getBeanType());
            if (beanType.isAnnotationPresent(WithTenant.class) && !AuthContext.isSuperAdmin()) {
                TenantUtil.start(user.getUsername());
            }
        }
        return true;
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(ErrorResponse.of(CommonErrorCode.UNAUTHORIZED).toJson());
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        TenantUtil.stop();
        AuthContext.clear();
    }
}
