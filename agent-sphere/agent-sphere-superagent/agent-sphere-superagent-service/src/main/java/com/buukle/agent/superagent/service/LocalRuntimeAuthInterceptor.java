package com.buukle.agent.superagent.service;

import com.buukle.agent.common.constant.HttpHeaders;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.SessionSurface;
import com.buukle.agent.common.context.SessionSurfaceHolder;
import com.buukle.agent.common.context.TenantContext;
import com.buukle.agent.common.context.TenantScope;
import com.buukle.agent.common.context.TenantScopeHolder;
import com.buukle.agent.common.context.TenantUtil;
import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.exception.ErrorResponse;
import com.buukle.agent.common.util.TokenHasher;
import com.buukle.agent.instance.dtvo.enums.UserEnum;
import com.buukle.agent.instance.dtvo.vo.UserVO;
import com.buukle.agent.instance.spi.UserSpi;
import com.buukle.agent.superagent.domain.AgentLocalRuntime;
import com.buukle.agent.superagent.repository.AgentLocalRuntimeMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.Set;

/**
 * Device-token authentication for desktop runtime endpoints.
 *
 * <p>This path is skipped by {@link com.buukle.agent.infrastructure.config.AuthInterceptor};
 * here we resolve the token to one runtime, pin the owner and tenant, and clear the
 * request context afterwards.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalRuntimeAuthInterceptor implements HandlerInterceptor {

    public static final String RUNTIME_ATTR = "localRuntimeId";
    public static final String OWNER_ATTR = "localRuntimeOwnerUserId";

    private final AgentLocalRuntimeMapper runtimeMapper;
    private final UserSpi userSpi;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(HttpHeaders.BEARER_PREFIX)) {
            writeUnauthorized(response);
            return false;
        }

        String token = header.substring(HttpHeaders.BEARER_PREFIX.length());
        AgentLocalRuntime runtime = runtimeMapper.selectByTokenHash(TokenHasher.sha256(token));
        if (runtime == null) {
            writeUnauthorized(response);
            return false;
        }

        UserVO user = userSpi.getByUserId(runtime.getOwnerUserId());
        if (user == null || !UserEnum.STATUS_ACTIVE.equals(user.getStatus())) {
            writeUnauthorized(response);
            return false;
        }

        AuthContext.setToken(token);
        AuthContext.setUserId(user.getId());
        AuthContext.setUsername(user.getUsername());
        AuthContext.setDisplayName(user.getDisplayName());
        AuthContext.setSuperAdmin(false);
        AuthContext.setPermissions(Set.of());

        if (runtime.getTenantId() != null) {
            TenantContext.set(runtime.getTenantId());
            TenantScopeHolder.start(TenantScope.tenantOnly(runtime.getTenantId(), user.getUsername()));
        }
        TenantUtil.start(user.getUsername());
        SessionSurfaceHolder.set(SessionSurface.INTERNAL);

        request.setAttribute(RUNTIME_ATTR, runtime.getId());
        request.setAttribute(OWNER_ATTR, runtime.getOwnerUserId());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        TenantScopeHolder.clear();
        TenantUtil.stop();
        TenantContext.clear();
        AuthContext.clear();
        SessionSurfaceHolder.clear();
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(ErrorResponse.of(CommonErrorCode.UNAUTHORIZED).toJson());
    }
}
