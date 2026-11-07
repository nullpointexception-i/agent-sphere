package com.buukle.agent.infrastructure.config;


import com.buukle.agent.common.constant.HttpHeaders;import com.buukle.agent.admin.spi.PermissionSpi;
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

        // External capability open API (/api/v1/api/*): external callers connect directly without a token.
        // Allow only, do not set a user identity: session/task ownership is explicitly initialized by the
        // business layer from the resource creator (e.g. the instance creator for a task), so external calls
        // are not polluted into a single service user and can still be delivered to the real user's connection.
        // TODO API signature verification (later version): verify the source signature before allowing; for now waived entirely.
        if (path.startsWith(EXTERNAL_API_PREFIX)) {
            return true;
        }

        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith(HttpHeaders.BEARER_PREFIX)) {
            writeUnauthorized(response);
            return false;
        }

        String token = authHeader.substring(HttpHeaders.BEARER_PREFIX.length());
        String cacheKey = TokenCacheKeys.USER_TOKEN_PREFIX + TokenHasher.sha256(token);
        AgentUser user = cacheService.get(cacheKey);
        if (user == null) {
            // Only fall back to the source on a cache miss: token validation + user lookup. The session
            // table supports one user across multiple clients, so there is no longer a
            // "later login overrides earlier login" problem here.
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
        // The client surface (console / widget) must be set **only after authentication passes**: when
        // preHandle returns false early (401), Spring does not call afterCompletion, so any assignment made
        // before that leaks on the pooled thread into the next request.
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
        // The Tomcat thread pool reuses threads: not clearing leaks this request's client surface into the next one
        SessionSurfaceHolder.clear();
    }
}
