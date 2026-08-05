package com.buukle.agent.infrastructure.config;

import com.buukle.agent.common.config.SystemConfigKeys;
import com.buukle.agent.common.config.SystemConfigSpi;
import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.exception.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final String LOGIN_PATH = "/api/v1/auth/login";
    private static final String SSO_EXCHANGE_PATH = "/api/v1/auth/sso/exchange";
    private static final String RATE_KEY_LOGIN = "rate:login:";
    private static final String RATE_KEY_SSO_EXCHANGE = "rate:sso:exchange:";

    private static final int SSO_EXCHANGE_MAX_ATTEMPTS = 10;
    private static final int SSO_EXCHANGE_WINDOW_MINUTES = 1;

    private final RedissonClient redissonClient;
    private final SystemConfigSpi systemConfigSpi;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        RateLimitPolicy policy = policyFor(request.getRequestURI());
        if (policy == null) {
            return true;
        }

        String ip = getClientIp(request);
        RRateLimiter limiter = redissonClient.getRateLimiter(policy.keyPrefix() + ip);
        limiter.trySetRate(RateType.OVERALL, policy.maxAttempts(), policy.windowMinutes(), RateIntervalUnit.MINUTES);

        if (!limiter.tryAcquire()) {
            log.warn("Rate limited path={}, ip={}", request.getRequestURI(), ip);
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(ErrorResponse.of(CommonErrorCode.RATE_LIMITED).toJson());
            return false;
        }

        return true;
    }

    private RateLimitPolicy policyFor(String uri) {
        if (uri.equals(LOGIN_PATH)) {
            int maxAttempts = Integer.parseInt(systemConfigSpi.get(SystemConfigKeys.LOGIN_MAX_ATTEMPTS, "5"));
            int windowMinutes = Integer.parseInt(systemConfigSpi.get(SystemConfigKeys.LOGIN_WINDOW_MINUTES, "1"));
            return new RateLimitPolicy(RATE_KEY_LOGIN, maxAttempts, windowMinutes);
        }
        if (uri.equals(SSO_EXCHANGE_PATH)) {
            return new RateLimitPolicy(RATE_KEY_SSO_EXCHANGE, SSO_EXCHANGE_MAX_ATTEMPTS, SSO_EXCHANGE_WINDOW_MINUTES);
        }
        return null;
    }

    private static String getClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        String xri = request.getHeader("X-Real-IP");
        if (xri != null && !xri.isBlank()) {
            return xri.trim();
        }
        return request.getRemoteAddr();
    }

    private record RateLimitPolicy(String keyPrefix, int maxAttempts, int windowMinutes) {
    }
}