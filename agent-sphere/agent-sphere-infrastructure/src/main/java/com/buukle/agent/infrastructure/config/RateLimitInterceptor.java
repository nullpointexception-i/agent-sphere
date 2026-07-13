package com.buukle.agent.infrastructure.config;

import com.buukle.agent.common.config.SystemConfigSpi;
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

    private final RedissonClient redissonClient;
    private final SystemConfigSpi systemConfigSpi;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        if (!request.getRequestURI().equals("/api/v1/auth/login")) {
            return true;
        }

        String ip = getClientIp(request);
        int maxAttempts = Integer.parseInt(systemConfigSpi.get("rate-limit.login-max-attempts", "5"));
        int windowMinutes = Integer.parseInt(systemConfigSpi.get("rate-limit.login-window-minutes", "1"));
        RRateLimiter limiter = redissonClient.getRateLimiter("rate:login:" + ip);
        limiter.trySetRate(RateType.OVERALL, maxAttempts, windowMinutes, RateIntervalUnit.MINUTES);

        if (!limiter.tryAcquire()) {
            log.warn("Login rate limited for IP: {}", ip);
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":\"A0400\",\"message\":\"请求频率限制\",\"userTip\":\"请稍后重试\"}");
            return false;
        }

        return true;
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
}
