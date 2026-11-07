package com.buukle.agent.capability.builtin.tool.currenttime.tool;

import com.buukle.agent.capability.builtin.dtvo.enums.BuiltinToolEnum;
import com.buukle.agent.capability.builtin.tool.currenttime.dtvo.dto.CurrentTimeExecuteContext;
import com.buukle.agent.capability.builtin.tool.currenttime.dtvo.vo.CurrentTimeResultVO;
import com.buukle.agent.capability.builtin.tool.spi.CapabilityBuiltinToolSpi;
import com.buukle.agent.capability.builtin.tool.spi.constant.BuiltinToolConstants;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteContext;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteResult;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.ToolInfoVO;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Current system time lookup.
 *
 * <p>Bound by default (needConfig=false), available to every instance. Time is no longer injected
 * into messages - the model calls this when needed, so a per-turn dynamic timestamp does not break
 * the cache.
 */
@Component
public class CapabilityBuiltinToolCurrentTime implements CapabilityBuiltinToolSpi {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String EMPTY_PARAM_SCHEMA = "{\"type\":\"object\",\"properties\":{}}";

    static final String TOOL_DESCRIPTION = """
            Return the current server date/time in Asia/Shanghai.

            Call this whenever the task depends on the current date, time, or weekday
            (e.g. "today", "this week", deadlines, freshness checks). Do NOT guess the time.
            The result contains an ISO-8601 offset timestamp, timezone, epoch milliseconds and weekday.""";

    @Override
    public BuiltinToolEnum getToolType() {
        return BuiltinToolEnum.CURRENT_TIME;
    }

    @Override
    public boolean needConfig() {
        return false;
    }

    @Override
    public ToolInfoVO getInfo() {
        ToolInfoVO info = new ToolInfoVO();
        info.setName(BuiltinToolConstants.NAME_PREFIX + CapabilityBuiltinToolCurrentTime.class.getSimpleName());
        info.setDescription(TOOL_DESCRIPTION);
        info.setDisplayNameCn("当前时间");
        info.setDisplayNameEn("Current Time");
        // No parameters; a hand-written schema keeps ExecuteContext's sessionId/runId hidden from the model
        info.setParamSchema(EMPTY_PARAM_SCHEMA);
        info.setResponseSchema(EMPTY_PARAM_SCHEMA);
        return info;
    }

    @Override
    public Class<? extends ExecuteContext> getContextType() {
        return CurrentTimeExecuteContext.class;
    }

    @Override
    public Class<? extends ExecuteResult> getResultType() {
        return CurrentTimeResultVO.class;
    }

    @Override
    public ExecuteResult execute(ExecuteContext ctx) {
        ZonedDateTime now = ZonedDateTime.now(ZONE);
        return new CurrentTimeResultVO(
                now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                now.getZone().getId(),
                now.toInstant().toEpochMilli(),
                now.getDayOfWeek().name());
    }
}
