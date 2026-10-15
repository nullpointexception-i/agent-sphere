package com.buukle.agent.common.context;

import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.exception.BizException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话来源开关的真值表。
 *
 * <p>建会话与发消息共用这一条规则 —— 两处各写一遍真值表迟早会漂移，而漂移的表现是
 * 「某一端悄悄绕过了开关」，很难发现。
 */
class SessionSurfacePolicyTest {

    // ---------- INTERNAL：服务端内部调用（任务提交 / 提炼轮）永远放行 ----------

    @Test
    void internal_isAlwaysAllowedRegardlessOfSwitches() {
        // 否则任务路径会被自己的实例开关拦掉：任务建会话/续跑就是内部调用
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
        // 列默认是 FALSE（widget 禁止），null 必须按禁止处理，不能反向放行
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
        // 列默认是 TRUE（主站放行）
        assertTrue(SessionSurfacePolicy.allows(SessionSurface.CONSOLE, null, false));
    }

    // ---------- assertAllowed ----------

    @Test
    void assertAllowed_throwsDedicatedErrorCodeNotGenericForbidden() {
        // 用独立错误码：业务方要能区分「该入口未开放」与「这个用户没权限」
        BizException ex = org.junit.jupiter.api.Assertions.assertThrows(BizException.class,
                () -> SessionSurfacePolicy.assertAllowed(SessionSurface.WIDGET, true, false));
        // getErrorCode() 返回的是错误码字符串（如 A0301），不是枚举
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
