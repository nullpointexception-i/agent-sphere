package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.capability.builtin.dtvo.enums.BuiltinToolEnum;
import com.buukle.agent.capability.builtin.tool.currenttime.dtvo.dto.CurrentTimeExecuteContext;
import com.buukle.agent.capability.builtin.tool.currenttime.dtvo.vo.CurrentTimeResultVO;
import com.buukle.agent.capability.builtin.tool.currenttime.tool.CapabilityBuiltinToolCurrentTime;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.ToolInfoVO;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapabilityBuiltinToolCurrentTimeTest {

    private final CapabilityBuiltinToolCurrentTime tool = new CapabilityBuiltinToolCurrentTime();

    @Test
    void isAutoIncludedAndHasNoParameters() {
        assertFalse(tool.needConfig());
        assertEquals(BuiltinToolEnum.CURRENT_TIME, tool.getToolType());

        ToolInfoVO info = tool.getInfo();
        assertTrue(info.getParamSchema().contains("\"properties\""));
        assertFalse(info.getParamSchema().contains("sessionId"));
        assertFalse(info.getParamSchema().contains("runId"));
    }

    @Test
    void returnsShanghaiIsoTime() {
        long before = System.currentTimeMillis();
        CurrentTimeResultVO result = (CurrentTimeResultVO) tool.execute(new CurrentTimeExecuteContext());
        long after = System.currentTimeMillis();

        assertEquals("Asia/Shanghai", result.getTimezone());
        OffsetDateTime parsed = OffsetDateTime.parse(result.getTime());
        assertEquals("+08:00", parsed.getOffset().getId());
        assertTrue(result.getEpochMillis() >= before && result.getEpochMillis() <= after);
        assertNotNull(result.getWeekday());
        assertFalse(result.getWeekday().isBlank());
    }
}
