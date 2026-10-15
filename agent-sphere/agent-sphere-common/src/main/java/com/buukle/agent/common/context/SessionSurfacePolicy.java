package com.buukle.agent.common.context;

import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.exception.BizException;

/**
 * 会话来源开关的**唯一**判定规则。
 *
 * <p>建会话（{@code createSession}）与发消息（{@code chat}）共用它，避免两处各写一遍真值表而漂移。
 * 实例上的两个开关由 {@code agent_instance.allow_console_session} /
 * {@code allow_widget_session} 提供。
 */
public final class SessionSurfacePolicy {

    private SessionSurfacePolicy() {
    }

    /**
     * 该来源端是否被允许。
     *
     * <p>空值按列默认值处理：`allowConsole` 为 null 视为放行、`allowWidget` 为 null 视为禁止 ——
     * 与迁移里的 `DEFAULT TRUE` / `DEFAULT FALSE` 保持一致，避免空值语义与库默认值相反。
     */
    public static boolean allows(SessionSurface surface, Boolean allowConsole, Boolean allowWidget) {
        return switch (surface) {
            // 内部调用（任务提交 / 提炼轮）不走 HTTP 入口，不受实例开关约束
            case INTERNAL -> true;
            case WIDGET -> Boolean.TRUE.equals(allowWidget);
            case CONSOLE -> !Boolean.FALSE.equals(allowConsole);
        };
    }

    /** 不允许时抛 {@link CommonErrorCode#SESSION_SURFACE_DISABLED}。 */
    public static void assertAllowed(SessionSurface surface, Boolean allowConsole, Boolean allowWidget) {
        if (!allows(surface, allowConsole, allowWidget)) {
            throw new BizException(CommonErrorCode.SESSION_SURFACE_DISABLED,
                    "surface=" + surface);
        }
    }
}
