package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.common.constant.PermissionCodes;
import com.buukle.agent.common.constant.SessionTypeCodes;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.SessionSurface;
import com.buukle.agent.common.context.SessionSurfaceHolder;
import com.buukle.agent.common.context.TenantScope;
import com.buukle.agent.common.context.TenantScopeHolder;
import com.buukle.agent.common.security.SessionAccessPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Visibility versus operation: tenant administrators read, creators write. */
class SessionAccessPolicyTest {

    private static final Long TENANT = 2L;

    @AfterEach
    void tearDown() {
        AuthContext.clear();
        TenantScopeHolder.clear();
        SessionSurfaceHolder.clear();
    }

    @Test
    void tenantAdminReadsOtherUsersSessionsButCannotWriteThem() {
        login("tenantAdmin", true, false);
        scope(false);

        assertTrue(SessionAccessPolicy.canRead(TENANT, "someone", SessionTypeCodes.CONSOLE));
        assertFalse(SessionAccessPolicy.canWrite(TENANT, "someone", SessionTypeCodes.CONSOLE));
        assertFalse(SessionAccessPolicy.canWrite(TENANT, "someone", SessionTypeCodes.ROUTER));
    }

    @Test
    void tenantAdminOwnsOwnUserSession() {
        login("tenantAdmin", true, false);
        scope(false);

        assertTrue(SessionAccessPolicy.canWrite(TENANT, "tenantAdmin", SessionTypeCodes.CONSOLE));
    }

    @Test
    void ordinaryUserCannotReadOthersEvenWithTenantVisibility() {
        login("alice", false, false);
        scope(false);

        assertTrue(SessionAccessPolicy.canRead(TENANT, "alice", SessionTypeCodes.CONSOLE));
        assertFalse(SessionAccessPolicy.canRead(TENANT, "bob", SessionTypeCodes.CONSOLE));
        assertFalse(SessionAccessPolicy.canRead(TENANT, "bob", SessionTypeCodes.GROUP_ROUTER));
    }

    @Test
    void internalSessionsAreReadOnlyForConsoleUsers() {
        login("alice", false, false);
        scope(true);
        SessionSurfaceHolder.set(SessionSurface.CONSOLE);
        assertFalse(SessionAccessPolicy.canWrite(TENANT, "alice", SessionTypeCodes.ROUTER));

        // System/runtime callers may execute internal sessions.
        AuthContext.setSuperAdmin(true);
        SessionSurfaceHolder.clear();
        assertTrue(SessionAccessPolicy.canWrite(TENANT, "alice", SessionTypeCodes.GROUP_ROUTER));
    }

    @Test
    void projectBaselineIsReadOnlyFromConsole() {
        login("alice", false, false);
        scope(true);
        SessionSurfaceHolder.set(SessionSurface.CONSOLE);

        assertFalse(SessionAccessPolicy.canWrite(
                TENANT, "alice", SessionTypeCodes.PROJECT_ROUTER, null));
    }

    @Test
    void projectBaselineRemainsWritableByRuntime() {
        login("alice", false, false);
        scope(true);
        SessionSurfaceHolder.set(SessionSurface.INTERNAL);

        assertTrue(SessionAccessPolicy.canWrite(
                TENANT, "alice", SessionTypeCodes.PROJECT_ROUTER, null));
    }

    @Test
    void projectForkIsWritableByItsOwner() {
        login("alice", false, false);
        scope(true);
        SessionSurfaceHolder.set(SessionSurface.CONSOLE);

        assertTrue(SessionAccessPolicy.canWrite(
                TENANT, "alice", SessionTypeCodes.PROJECT_ROUTER, 105L));
    }

    @Test
    void tenantAdminCannotWriteAnotherUsersProjectFork() {
        login("tenantAdmin", true, false);
        scope(false);
        SessionSurfaceHolder.set(SessionSurface.CONSOLE);

        assertFalse(SessionAccessPolicy.canWrite(
                TENANT, "someone", SessionTypeCodes.PROJECT_ROUTER, 105L));
    }

    @Test
    void tenantVisibilityStillChecksTheTenant() {
        login("alice", false, false);
        scope(true);

        assertTrue(SessionAccessPolicy.canRead(TENANT, "alice", SessionTypeCodes.CONSOLE));
        assertFalse(SessionAccessPolicy.canRead(99L, "alice", SessionTypeCodes.CONSOLE));
    }

    private static void login(String username, boolean tenantAdmin, boolean superAdmin) {
        AuthContext.setUsername(username);
        AuthContext.setSuperAdmin(superAdmin);
        AuthContext.setPermissions(tenantAdmin
                ? Set.of(PermissionCodes.INSTANCE_TENANT) : Set.of());
    }

    private static void scope(boolean selfOnly) {
        TenantScopeHolder.start(new TenantScope(
                1L, "user", TENANT, Set.of(TENANT), selfOnly, Set.of(TENANT)));
    }
}
