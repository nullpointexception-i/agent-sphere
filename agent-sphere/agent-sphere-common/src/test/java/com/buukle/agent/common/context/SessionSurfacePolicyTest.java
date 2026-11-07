package com.buukle.agent.common.context;

import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.exception.BizException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Truth table of the session surface switches.
 *
 * <p>Session creation and messaging share this single rule — writing the truth table twice would
 * eventually drift, and drift shows up as "one surface quietly bypassing the switch", which is very
 * hard to spot.
 */
class SessionSurfacePolicyTest {

    // ---------- INTERNAL: server-internal calls (task submission / extraction turns) are always allowed ----------

    @Test
    void internal_isAlwaysAllowedRegardlessOfSwitches() {
        // otherwise task paths would be blocked by their own instance switches: task session creation / resumption is an internal call
        for (Boolean console : new Boolean[]{true, false, null}) {
            for (Boolean widget : new Boolean[]{true, false, null}) {
                assertTrue(SessionSurfacePolicy.allows(SessionSurface.INTERNAL, console, widget),
                        "INTERNAL 必须放行（console=" + console + ", widget=" + widget + "）");
            }
        }
    }

    // ---------- WIDGET ----------

    @Test
    void widget_followsWidgetSwitch() {
        assertTrue(SessionSurfacePolicy.allows(SessionSurface.WIDGET, true, true));
        assertTrue(SessionSurfacePolicy.allows(SessionSurface.WIDGET, false, true));
        assertFalse(SessionSurfacePolicy.allows(SessionSurface.WIDGET, true, false));
        assertFalse(SessionSurfacePolicy.allows(SessionSurface.WIDGET, false, false));
    }

    @Test
    void widget_nullSwitchDeniesToMatchColumnDefault() {
        // the column default is FALSE (widget denied); null must be treated as denied, not flipped to allowed
        assertFalse(SessionSurfacePolicy.allows(SessionSurface.WIDGET, true, null));
    }

    // ---------- CONSOLE ----------

    @Test
    void console_followsConsoleSwitch() {
        assertTrue(SessionSurfacePolicy.allows(SessionSurface.CONSOLE, true, false));
        assertTrue(SessionSurfacePolicy.allows(SessionSurface.CONSOLE, true, true));
        assertFalse(SessionSurfacePolicy.allows(SessionSurface.CONSOLE, false, true));
        assertFalse(SessionSurfacePolicy.allows(SessionSurface.CONSOLE, false, false));
    }

    @Test
    void console_nullSwitchAllowsToMatchColumnDefault() {
        // the column default is TRUE (console allowed)
        assertTrue(SessionSurfacePolicy.allows(SessionSurface.CONSOLE, null, false));
    }

    // ---------- assertAllowed ----------

    @Test
    void assertAllowed_throwsDedicatedErrorCodeNotGenericForbidden() {
        // use a dedicated error code: callers must be able to tell "this entry point is not enabled" from "this user lacks permission"
        BizException ex = org.junit.jupiter.api.Assertions.assertThrows(BizException.class,
                () -> SessionSurfacePolicy.assertAllowed(SessionSurface.WIDGET, true, false));
        // getErrorCode() returns the error code string (e.g. A0301), not the enum
        assertEquals(CommonErrorCode.SESSION_SURFACE_DISABLED.getCode(), ex.getErrorCode());
    }

    @Test
    void assertAllowed_passesWhenAllowed() {
        assertDoesNotThrow(() ->
                SessionSurfacePolicy.assertAllowed(SessionSurface.CONSOLE, true, false));
        assertDoesNotThrow(() ->
                SessionSurfacePolicy.assertAllowed(SessionSurface.INTERNAL, false, false));
    }
}
