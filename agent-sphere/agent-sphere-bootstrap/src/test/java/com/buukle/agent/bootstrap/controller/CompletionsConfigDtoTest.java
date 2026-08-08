package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.completions.dtvo.CompletionsConfigDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompletionsConfigDtoTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void booleanThinking_shouldCoerceToString() throws Exception {
        CompletionsConfigDTO cfg = MAPPER.readValue(
                "{\"temperature\":0.3,\"thinking\":false,\"max_tokens\":1024}", CompletionsConfigDTO.class);
        assertEquals(0.3, cfg.getTemperature());
        assertEquals(1024, cfg.getMaxTokens());
        assertEquals("false", cfg.getThinking());
    }

    @Test
    void stringThinking_shouldKeepAsIs() throws Exception {
        CompletionsConfigDTO cfg = MAPPER.readValue(
                "{\"thinking\":\"disabled\",\"stop\":[\"END\"]}", CompletionsConfigDTO.class);
        assertEquals("disabled", cfg.getThinking());
        assertEquals(java.util.List.of("END"), cfg.getStop());
    }

    @Test
    void reasoningKey_shouldBeSupported() throws Exception {
        CompletionsConfigDTO cfg = MAPPER.readValue(
                "{\"reasoning\":\"enabled\"}", CompletionsConfigDTO.class);
        assertEquals("enabled", cfg.getReasoning());
    }
}
