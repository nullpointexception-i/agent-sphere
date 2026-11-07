package com.buukle.agent.infrastructure.handler;

import com.buukle.agent.common.context.TenantScope;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataPermissionInterceptorTest {

    private final DataPermissionInterceptor interceptor = new DataPermissionInterceptor();

    @Test
    void emptyScopeForcesAnEmptyResult() {
        String sql = interceptor.rewriteForTest(
                "SELECT * FROM agent_session", TenantScope.empty(7L, "alice"));
        assertTrue(sql.contains("1 = 0"));
    }

    @Test
    void tenantOwnerTableGetsTenantAndCreatorConditions() {
        TenantScope scope = new TenantScope(
                7L, "alice", 2L, Set.of(2L, 3L), true, Set.of(2L, 3L));
        String sql = interceptor.rewriteForTest(
                "SELECT * FROM agent_session", scope);
        assertTrue(sql.toLowerCase().contains("tenant_id in"));
        assertTrue(sql.contains("2"));
        assertTrue(sql.contains("3"));
        assertTrue(sql.contains("created_by = 'alice'"));
    }

    @Test
    void tenantOnlyTableDoesNotGetCreatorCondition() {
        TenantScope scope = new TenantScope(
                7L, "alice", 2L, Set.of(2L), false, Set.of(2L));
        String sql = interceptor.rewriteForTest(
                "SELECT * FROM sys_audit_log", scope);
        assertTrue(sql.toLowerCase().contains("tenant_id in"));
        assertFalse(sql.contains("created_by"));
    }

    @Test
    void sharedTableIsNotRewritten() {
        TenantScope scope = new TenantScope(
                7L, "alice", 2L, Set.of(2L), false, Set.of(2L));
        assertNull(interceptor.rewriteForTest("SELECT * FROM sys_tenant", scope));
    }

    @Test
    void joinAddsConditionForTenantAlias() {
        TenantScope scope = new TenantScope(
                7L, "alice", 2L, Set.of(2L), false, Set.of(2L));
        String sql = interceptor.rewriteForTest(
                "SELECT s.id FROM agent_session s JOIN agent_run r ON r.session_id = s.id", scope);
        assertTrue(sql.toLowerCase().contains("s.tenant_id in"));
        assertTrue(sql.toLowerCase().contains("r.tenant_id in"));
    }
}
