package com.buukle.agent.capability.builtin.tool.currenttime.dtvo.vo;

import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteResult;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/** 当前系统时间（Asia/Shanghai）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
public class CurrentTimeResultVO extends ExecuteResult {
    /** ISO-8601 offset，例如 2026-10-29T12:34:56+08:00。 */
    private String time;
    private String timezone;
    private long epochMillis;
    /** 星期几（MONDAY..SUNDAY），避免模型自行推算。 */
    private String weekday;
}
