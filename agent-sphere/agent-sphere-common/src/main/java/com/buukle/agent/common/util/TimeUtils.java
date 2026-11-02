package com.buukle.agent.common.util;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 时间换算工具。
 *
 * <p>数据库里的 {@code timestamp without time zone} 一律按 {@code Asia/Shanghai} 解释
 * （与 {@code Application#setDefaultTimezone} 一致）。这里显式指定时区，避免依赖
 * JVM 默认时区在测试/容器里被改掉后出现偏移。
 */
public final class TimeUtils {

    /** 全库统一时区（与 Application#setDefaultTimezone 保持一致）。 */
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private TimeUtils() {
    }

    /** {@code LocalDateTime} → epoch 毫秒；null 透传。 */
    public static Long toEpochMillis(LocalDateTime time) {
        return time == null ? null : time.atZone(ZONE).toInstant().toEpochMilli();
    }
}
