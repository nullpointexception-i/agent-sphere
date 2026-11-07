package com.buukle.agent.runtime.kernel.port.vo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolDeniedResultVOTest {

    @Test
    void serializesUserDeniedResultWithoutRawStringConcatenation() {
        ToolDeniedResultVO vo = ToolDeniedResultVO.of("builtin_5");

        assertEquals("USER_DENIED", vo.error());
        assertEquals("builtin_5", vo.tool());
        String json = vo.toString();
        assertTrue(json.contains("\"error\":\"USER_DENIED\""));
        assertTrue(json.contains("\"tool\":\"builtin_5\""));
        assertTrue(json.contains("Do not retry this tool in this run."));
    }
}
