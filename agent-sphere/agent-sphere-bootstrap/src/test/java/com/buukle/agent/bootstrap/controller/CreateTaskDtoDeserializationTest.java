package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.tasks.dtvo.CreateTaskDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CreateTaskDtoDeserializationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void configAsString_shouldDeserializeToMap() throws Exception {
        String body = "{\"goal\":\"寻访\",\"businessType\":\"sourcing\",\"config\":\"{\\\"channels\\\":[\\\"liepin\\\"]}\"}";
        CreateTaskDTO dto = MAPPER.readValue(body, CreateTaskDTO.class);
        assertEquals(Map.of("channels", List.of("liepin")), dto.getConfig());
    }

    @Test
    void configAsObject_shouldDeserializeToMap() throws Exception {
        String body = "{\"goal\":\"寻访\",\"businessType\":\"sourcing\",\"config\":{\"channels\":[\"liepin\"]}}";
        CreateTaskDTO dto = MAPPER.readValue(body, CreateTaskDTO.class);
        assertEquals(Map.of("channels", List.of("liepin")), dto.getConfig());
    }

    @Test
    void nullFields_shouldRemainNull() throws Exception {
        String body = "{\"goal\":\"寻访\",\"businessType\":\"sourcing\"}";
        CreateTaskDTO dto = MAPPER.readValue(body, CreateTaskDTO.class);
        assertNull(dto.getConfig());
        assertNull(dto.getContext());
        assertNull(dto.getExpectedOutput());
    }
}
