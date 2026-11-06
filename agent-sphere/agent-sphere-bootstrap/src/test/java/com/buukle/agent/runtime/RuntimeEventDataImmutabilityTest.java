package com.buukle.agent.runtime;

import com.buukle.agent.runtime.kernel.port.vo.RuntimeEventDataVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guardrail for the immutable event payload.
 *
 * <p>The worker-report bug happened because one listener mutated a shared event instance.
 * Now the payload is a record: re-adding any setter would silently reopen that door, and
 * the compatibility bean getters must never leak into the SSE JSON.
 */
class RuntimeEventDataImmutabilityTest {

    @Test
    void payloadExposesNoSetters() {
        boolean hasSetter = Arrays.stream(RuntimeEventDataVO.class.getMethods())
                .anyMatch(method -> method.getName().startsWith("set"));
        assertFalse(hasSetter, "RuntimeEventDataVO must stay immutable (no setters)");
    }

    @Test
    void serializationKeepsRecordComponentNamesOnly() throws Exception {
        RuntimeEventDataVO data = RuntimeEventDataVO.builder()
                .sessionId(1L)
                .runId(2L)
                .assistantReply("report")
                .build();

        JsonNode node = new ObjectMapper().valueToTree(data);

        assertEquals(1L, node.get("sessionId").asLong());
        assertEquals("report", node.get("assistantReply").asText());
        // @JsonIgnore on the bean-style getters: no getSessionId / duplicate properties.
        assertFalse(node.has("getSessionId"));
        assertFalse(node.has("eventType"));
        assertTrue(node.isObject());
    }
}
