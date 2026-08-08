package com.buukle.agent.infrastructure.aspect;

import com.buukle.agent.common.context.AuthContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.annotation.Annotation;

@Aspect
@Component
public class ControllerLogAspect {

    private static final Logger log = LoggerFactory.getLogger(ControllerLogAspect.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${buukle.agent.controller-log.max-param-length:200}")
    private int maxParamLength;

    @Value("${buukle.agent.controller-log.max-result-length:300}")
    private int maxResultLength;

    @Value("${buukle.agent.controller-log.full-prefixes:}")
    private String fullPrefixes;

    private static String resolveHttpMethod(MethodSignature sig) {
        for (Annotation a : sig.getMethod().getDeclaredAnnotations()) {
            if (a instanceof GetMapping) return "GET";
            if (a instanceof PostMapping) return "POST";
            if (a instanceof PutMapping) return "PUT";
            if (a instanceof DeleteMapping) return "DELETE";
            if (a instanceof PatchMapping) return "PATCH";
            if (a.annotationType().getAnnotation(RequestMapping.class) != null) return "REQUEST";
        }
        return "?";
    }

    private static String resolvePath(MethodSignature sig, Object[] args) {
        try {
            var attr = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attr != null) return attr.getRequest().getRequestURI();
        } catch (Exception ignored) {
        }
        return sig.getMethod().getName();
    }

    @Around("execution(* com.buukle.agent..*Controller.*(..))")
    public Object logController(ProceedingJoinPoint pjp) throws Throwable {
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        String className = sig.getDeclaringType().getSimpleName();
        String methodName = sig.getName();

        // 跳过 SSE 流式端点的日志（心跳频繁）
        if ("stream".equals(methodName)) {
            return pjp.proceed();
        }

        String httpMethod = resolveHttpMethod(sig);
        String path = resolvePath(sig, pjp.getArgs());
        String user = AuthContext.getUsername();
        String userInfo = user != null ? " [" + user + "]" : "";
        boolean full = isFullLogPath(path);

        String params = argsToJson(sig.getParameterNames(), pjp.getArgs(), full);

        log.info("→ {} {}.{} {} {} params={}", httpMethod, className, methodName, path, userInfo, params);

        long start = System.currentTimeMillis();
        Object result;
        try {
            result = pjp.proceed();
        } catch (Exception e) {
            log.error("← {} {}.{} {} {} ERROR: {}", httpMethod, className, methodName, path, userInfo, e.getMessage());
            throw e;
        }
        long elapsed = System.currentTimeMillis() - start;
        String resultStr = resultToJson(result, full);
        log.info("← {} {}.{} {} {} {}ms result={}", httpMethod, className, methodName, path, userInfo, elapsed, resultStr);
        return result;
    }

    private boolean isFullLogPath(String path) {
        if (path == null || fullPrefixes == null || fullPrefixes.isBlank()) {
            return false;
        }
        for (String prefix : fullPrefixes.split(",")) {
            String trimmed = prefix.trim();
            if (!trimmed.isEmpty() && path.startsWith(trimmed)) {
                return true;
            }
        }
        return false;
    }

    private String argsToJson(String[] names, Object[] args, boolean full) {
        if (args == null || args.length == 0) return "";
        try {
            var sb = new StringBuilder();
            for (int i = 0; i < args.length; i++) {
                if (args[i] != null) {
                    String cls = args[i].getClass().getName();
                    if (cls.startsWith("jakarta.servlet") || cls.startsWith("org.springframework") || cls.startsWith("java.time"))
                        continue;
                    if (sb.length() > 0) sb.append(", ");
                    String val = args[i] instanceof String s ? s : mapper.writeValueAsString(args[i]);
                    if (!full && val.length() > maxParamLength) val = val.substring(0, maxParamLength) + "...";
                    sb.append(names != null && i < names.length ? names[i] : "arg" + i).append("=").append(val);
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "(serialize error)";
        }
    }

    private String resultToJson(Object result, boolean full) {
        if (result == null) return "null";
        try {
            Object target = (result instanceof ResponseEntity<?> re) ? re.getBody() : result;
            if (target == null) return "null";
            String json = mapper.writeValueAsString(target);
            return (!full && json.length() > maxResultLength) ? json.substring(0, maxResultLength) + "..." : json;
        } catch (Exception e) {
            String fallback = result.toString();
            return (!full && fallback.length() > maxResultLength) ? fallback.substring(0, maxResultLength) + "..." : fallback;
        }
    }
}
