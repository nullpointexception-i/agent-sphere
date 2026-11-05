package com.buukle.agent.infrastructure.config;

import com.buukle.agent.admin.spi.PermissionSpi;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.SessionSurfaceHolder;
import com.buukle.agent.common.context.SessionSurfaceResolver;
import com.buukle.agent.common.context.TenantContext;
import com.buukle.agent.common.context.TenantUtil;
import com.buukle.agent.common.context.WithTenant;
import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.exception.ErrorResponse;
import com.buukle.agent.common.util.TokenHasher;
import com.buukle.agent.infrastructure.persistence.CacheService;
import com.buukle.agent.instance.domain.AgentUser;
import com.buukle.agent.instance.dtvo.enums.UserEnum;
import com.buukle.agent.instance.repository.UserMapper;
import com.buukle.agent.instance.spi.UserTokenSpi;
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

    private static final List<String> SKIP_PATHS = List.of("/api/v1/auth/login", "/api/v1/auth/register", "/api/v1/auth/check-username", "/api/v1/auth/sso", "/api/v1/chrome", "/api/v1/artifacts/documents/shared", "/api/v1/public/shares", "/api/v1/system/config/public", "/api/v1/system/config/plugin/download");
    private static final String EXTERNAL_API_PREFIX = "/api/v1/api";
    private final UserMapper userMapper;
    private final CacheService cacheService;
    private final PermissionSpi permissionSpi;
    private final UserTokenSpi userTokenSpi;

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
        String cacheKey = TokenCacheKeys.USER_TOKEN_PREFIX + TokenHasher.sha256(token);
        AgentUser user = cacheService.get(cacheKey);
        if (user == null) {
            // 缓存未命中才回源：token 校验 + 用户查询。会话表支持一个用户多端并存，
            // 因此这里不再有「后登录覆盖先登录」的问题。
            Long userId = userTokenSpi.validate(token);
            if (userId != null) {
                user = userMapper.selectById(userId);
            }
            if (user != null) {
                cacheService.set(cacheKey, user, TokenCacheKeys.USER_TOKEN_CACHE_MINUTES);
            }
        }
        if (user == null || !UserEnum.STATUS_ACTIVE.equals(user.getStatus())) {
            writeUnauthorized(response);
            return false;
        }

        AuthContext.setToken(token);
        AuthContext.setUserId(user.getId());
        AuthContext.setUsername(user.getUsername());
        AuthContext.setDisplayName(user.getDisplayName());
        TenantContext.set(user.getTenantId());
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
        // 来源端（主站 / widget）必须在**鉴权通过之后**才设置：preHandle 提前 return false（401）时
        // Spring 不会调用 afterCompletion，在此之前的赋值会残留在池化线程上串给下一个请求。
        SessionSurfaceHolder.set(
                SessionSurfaceResolver.fromHeader(request.getHeader(SessionSurfaceHolder.HEADER)));
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
        TenantContext.clear();
        AuthContext.clear();
        // Tomcat 线程池会复用线程：不清理会把本请求的来源端串给下一个请求
        SessionSurfaceHolder.clear();
    }
}
