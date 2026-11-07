package com.buukle.agent.common.context;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TenantScopeTest {

    @Test
    void emptyScopeIsEmptyAndSelfOnly() {
        TenantScope scope = TenantScope.empty(7L, "alice");
        assertTrue(scope.isEmptyScope());
        assertTrue(scope.selfOnly());
        assertEquals(Set.of(), scope.visibleTenantIds());
        assertEquals(Set.of(), scope.manageableTenantIds());
    }

    @Test
    void tenantOnlyScopeIsLimitedToOneTenant() {
        TenantScope scope = TenantScope.tenantOnly(2L, "alice");
        assertEquals(2L, scope.currentTenantId());
        assertTrue(scope.visibleTo(2L));
        assertFalse(scope.visibleTo(3L));
        assertFalse(scope.selfOnly());
    }

    @Test
    void normalScopeExposesVisibleAndManageableSets() {
        TenantScope scope = new TenantScope(
                7L, "alice", 2L, Set.of(2L, 3L), false, Set.of(2L));
        assertTrue(scope.visibleTo(3L));
        assertTrue(scope.canManage(2L));
        assertFalse(scope.canManage(3L));
        assertFalse(scope.isEmptyScope());
    }
}
