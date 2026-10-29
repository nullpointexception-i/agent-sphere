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
 * 当前系统时间查询。
 *
 * <p>默认绑定（needConfig=false），所有实例可用。时间不再注入 messages —— 需要时由模型主动调用，
 * 避免每轮携带动态时间破坏缓存。
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
        // 无参数；手写 schema 避免 ExecuteContext 的 sessionId/runId 暴露给模型
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
