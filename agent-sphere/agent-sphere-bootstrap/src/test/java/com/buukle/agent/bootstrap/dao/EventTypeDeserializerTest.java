package com.buukle.agent.bootstrap.dao;

import com.buukle.agent.instance.dtvo.enums.RunStatus;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventDataVO;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EventTypeDeserializerTest {

    @Test
    void runStatusRoundTripsThroughJson() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        RuntimeEventDataVO data = RuntimeEventDataVO.builder()
                .sessionId(1L)
                .runId(2L)
                .build();
        RuntimeEventVO source = new RuntimeEventVO(RunStatus.RUNNING, data);

        String json = mapper.writeValueAsString(source);
        RuntimeEventVO restored = mapper.readValue(json, RuntimeEventVO.class);

        assertEquals(RunStatus.RUNNING, restored.getEventType());
        assertEquals(1L, restored.getData().getSessionId());
    }
}
