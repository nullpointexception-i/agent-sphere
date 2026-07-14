package com.buukle.agent.infrastructure.aspect;

import com.buukle.agent.common.annotation.AuditLog;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.event.AuditEvent;
import com.buukle.agent.common.util.RequestUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.List;

@Aspect
@Component
public class AuditLogAspect {

    private static final int DETAIL_MAX_LENGTH = 1000;
    private static final List<String> SENSITIVE_KEYS = List.of(
            "password", "oldPassword", "newPassword", "repeatPassword",
            "keyValue", "secret", "apiKey", "token", "authorization"
    );

    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final ExpressionParser expressionParser;
    private final DefaultParameterNameDiscoverer parameterNameDiscoverer;

    public AuditLogAspect(ApplicationEventPublisher eventPublisher, ObjectMapper objectMapper) {
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
        this.expressionParser = new SpelExpressionParser();
        this.parameterNameDiscoverer = new DefaultParameterNameDiscoverer();
    }

    @Around("@annotation(auditLog)")
    public Object audit(ProceedingJoinPoint pjp, AuditLog auditLog) throws Throwable {
        String detail = resolveDetail(pjp);

        Object result;
        try {
            result = pjp.proceed();
            String resourceId = resolveResourceId(auditLog.resourceId(), pjp, result);
            publishEvent(auditLog, resourceId, detail, true, null);
            return result;
        } catch (Exception e) {
            String resourceId = resolveResourceId(auditLog.resourceId(), pjp, null);
            publishEvent(auditLog, resourceId, detail, false, e.getMessage());
            throw e;
        }
    }

    private void publishEvent(AuditLog auditLog, String resourceId, String detail,
                              boolean success, String errorMessage) {
        try {
            String ip = null;
            String ua = null;
            try {
                var attr = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
                if (attr != null) {
                    ip = RequestUtils.resolveClientIp(attr.getRequest());
                    ua = RequestUtils.resolveUserAgent(attr.getRequest());
                }
            } catch (Exception ignored) {
            }

            AuditEvent event = AuditEvent.builder()
                    .userId(AuthContext.getUserId())
                    .username(AuthContext.getUsername())
                    .action(auditLog.action())
                    .resourceType(auditLog.resourceType())
                    .resourceId(resourceId)
                    .detail(detail)
                    .ipAddress(ip)
                    .userAgent(ua)
                    .success(success)
                    .errorMessage(errorMessage)
                    .createdAt(LocalDateTime.now())
                    .build();

            eventPublisher.publishEvent(event);
        } catch (Exception ignored) {
            // never let audit failure affect the main flow
        }
    }

    private String resolveResourceId(String expression, ProceedingJoinPoint pjp, Object result) {
        String userId = AuthContext.getUserId() != null ? String.valueOf(AuthContext.getUserId()) : null;
        if (expression == null || expression.isEmpty()) return userId;
        try {
            MethodSignature sig = (MethodSignature) pjp.getSignature();
            MethodBasedEvaluationContext context = new MethodBasedEvaluationContext(
                    null, sig.getMethod(), pjp.getArgs(), parameterNameDiscoverer);
            if (result != null) context.setVariable("result", result);
            Object value = expressionParser.parseExpression(expression).getValue(context);
            if (value != null) return String.valueOf(value);
            return userId;
        } catch (Exception e) {
            return userId;
        }
    }

    private String resolveDetail(ProceedingJoinPoint pjp) {
        StringBuilder sb = new StringBuilder();
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        String[] names = sig.getParameterNames();
        Object[] args = pjp.getArgs();
        if (names != null && args != null) {
            for (int i = 0; i < Math.min(names.length, args.length); i++) {
                if (args[i] == null) continue;
                if (args[i] instanceof jakarta.servlet.ServletRequest
                        || args[i] instanceof jakarta.servlet.ServletResponse
                        || args[i] instanceof org.springframework.http.HttpInputMessage
                        || args[i] instanceof java.time.temporal.Temporal) continue;
                if (sb.length() > 0) sb.append(", ");
                sb.append(names[i]).append("=");
                try {
                    String val = args[i] instanceof String s ? s : maskSensitive(objectMapper.writeValueAsString(args[i]));
                    if (val.length() > 200) val = val.substring(0, 200) + "...";
                    sb.append(val);
                } catch (Exception e) {
                    sb.append("[unserializable]");
                }
            }
        }
        String detail = sb.toString();
        return detail.length() > DETAIL_MAX_LENGTH ? detail.substring(0, DETAIL_MAX_LENGTH) + "..." : detail;
    }

    private static String maskSensitive(String json) {
        String result = json;
        for (String key : SENSITIVE_KEYS) {
            result = result.replaceAll(
                    "\"" + key + "\"\\s*:\\s*\"[^\"]*\"",
                    "\"" + key + "\":\"****\""
            );
        }
        return result;
    }
}
