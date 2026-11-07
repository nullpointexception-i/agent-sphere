package com.buukle.agent.capability.builtin.tool.currenttime.dtvo.vo;

import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteResult;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/** Current system time (Asia/Shanghai). */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
public class CurrentTimeResultVO extends ExecuteResult {
    /** ISO-8601 offset, e.g. 2026-10-29T12:34:56+08:00. */
    private String time;
    private String timezone;
    private long epochMillis;
    /** Day of week (MONDAY..SUNDAY), so the model does not have to derive it. */
    private String weekday;
}
