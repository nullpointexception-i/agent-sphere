package com.buukle.agent.infrastructure.handler;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.TenantScopeHolder;
import com.buukle.agent.common.context.TenantUtil;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class AuditMetaObjectHandler implements MetaObjectHandler {

    /**
     * Tenant id used by system tasks that have no request tenant. It matches the
     * historical database default and keeps tenant_id non-null for NOT NULL
     * columns.
     */
    private static final long SYSTEM_TENANT_ID = 0L;

    private static String currentUser() {
        String scoped = TenantScopeHolder.currentUsername();
        if (scoped != null && !scoped.isBlank()) return scoped;
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
        if (!metaObject.hasSetter("tenantId")) {
            return;
        }
        Long tenantId = TenantScopeHolder.currentTenantId();
        if (tenantId == null) {
            tenantId = SYSTEM_TENANT_ID;
        }
        this.fillStrategy(metaObject, "tenantId", tenantId);
        // Some older entities still lack @TableField(fill = INSERT) on tenantId.
        // Set it directly so a forgotten annotation cannot produce an invisible
        // tenant_id = 0 row.
        if (metaObject.getValue("tenantId") == null) {
            metaObject.setValue("tenantId", tenantId);
        }
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        String username = currentUser();
        LocalDateTime now = LocalDateTime.now();
        this.fillStrategy(metaObject, "updatedBy", username);
        this.fillStrategy(metaObject, "updatedAt", now);
    }
}
