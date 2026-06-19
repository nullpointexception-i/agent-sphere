package com.buukle.agent.infrastructure.handler;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.buukle.agent.common.context.TenantUtil;
import com.buukle.agent.common.context.AuthContext;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class AuditMetaObjectHandler implements MetaObjectHandler {

    private static String currentUser() {
        String tenant = TenantUtil.get();
        if (tenant != null && !tenant.isBlank()) return tenant;
        String auth = AuthContext.getUsername();
        if (auth != null && !auth.isBlank()) return auth;
        return "system";
    }

    @Override
    public void insertFill(MetaObject metaObject) {
        String username = currentUser();
        LocalDateTime now = LocalDateTime.now();
        this.fillStrategy(metaObject, "createdBy", username);
        this.fillStrategy(metaObject, "updatedBy", username);
        this.fillStrategy(metaObject, "createdAt", now);
        this.fillStrategy(metaObject, "updatedAt", now);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        String username = currentUser();
        LocalDateTime now = LocalDateTime.now();
        this.fillStrategy(metaObject, "updatedBy", username);
        this.fillStrategy(metaObject, "updatedAt", now);
    }
}
