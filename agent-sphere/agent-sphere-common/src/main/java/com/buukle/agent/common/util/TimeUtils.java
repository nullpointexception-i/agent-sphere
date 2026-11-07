package com.buukle.agent.common.util;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Time conversion utilities.
 *
 * <p>{@code timestamp without time zone} values in the database are always interpreted as
 * {@code Asia/Shanghai} (consistent with {@code Application#setDefaultTimezone}). The zone is
 * specified explicitly here so that a changed JVM default zone in tests or containers cannot
 * introduce an offset.
 */
public final class TimeUtils {

    /** Single database-wide time zone (kept consistent with Application#setDefaultTimezone). */
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private TimeUtils() {
    }

    /** {@code LocalDateTime} → epoch milliseconds; null passes through. */
    public static Long toEpochMillis(LocalDateTime time) {
        return time == null ? null : time.atZone(ZONE).toInstant().toEpochMilli();
    }
}
