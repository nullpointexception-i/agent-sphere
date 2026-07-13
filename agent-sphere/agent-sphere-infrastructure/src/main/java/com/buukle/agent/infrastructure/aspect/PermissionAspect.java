package com.buukle.agent.infrastructure.aspect;

import com.buukle.agent.common.annotation.RequirePermission;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.exception.BizException;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;

@Aspect
@Component
public class PermissionAspect {

    @Before("@within(requirePermission) || @annotation(requirePermission)")
    public void checkPermission(JoinPoint jp, RequirePermission requirePermission) {
        if (requirePermission == null) {
            Method method = ((MethodSignature) jp.getSignature()).getMethod();
            requirePermission = method.getAnnotation(RequirePermission.class);
            if (requirePermission == null) {
                requirePermission = jp.getTarget().getClass().getAnnotation(RequirePermission.class);
            }
        }
        if (requirePermission == null) return;

        String[] required = requirePermission.value();
        RequirePermission.MatchMode mode = requirePermission.mode();
        Set<String> userPermissions = AuthContext.getPermissions();

        if (userPermissions == null || userPermissions.isEmpty()) {
            throw new BizException(CommonErrorCode.FORBIDDEN);
        }

        if (mode == RequirePermission.MatchMode.AND) {
            boolean allMatch = Arrays.stream(required).allMatch(userPermissions::contains);
            if (!allMatch) throw new BizException(CommonErrorCode.FORBIDDEN);
        } else {
            boolean anyMatch = Arrays.stream(required).anyMatch(userPermissions::contains);
            if (!anyMatch) throw new BizException(CommonErrorCode.FORBIDDEN);
        }
    }
}
