package com.buukle.agent.common.notification;

import java.util.Map;

/**
 * One user-facing notification. System copy is referenced by i18n key so the
 * client renders it in the viewer's locale; user content stays in {@code payload}.
 */
public record UserNotificationCommand(
        Long tenantId,
        Long userId,
        String type,
        String titleKey,
        String bodyKey,
        Map<String, Object> payload,
        String sourceType,
        Long sourceId
) {
}
