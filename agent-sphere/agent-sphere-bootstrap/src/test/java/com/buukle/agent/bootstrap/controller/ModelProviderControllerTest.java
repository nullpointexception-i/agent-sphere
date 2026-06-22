package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.model.controller.ModelProviderController;
import com.buukle.agent.model.dtvo.dto.CreateModelProviderDTO;
import com.buukle.agent.model.dtvo.vo.ModelProviderVO;
import com.buukle.agent.model.service.ModelProviderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ModelProviderControllerTest {

    MockMvc mockMvc;
    ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    ModelProviderService modelProviderService;

    @InjectMocks
    ModelProviderController modelProviderController;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(modelProviderController).build();
    }

    @Test
    void create_shouldReturn201() throws Exception {
        CreateModelProviderDTO dto = new CreateModelProviderDTO();
        dto.setName("OpenAI");
        dto.setBaseUrl("https://api.openai.com");
        dto.setApiKeyId(1L);
        dto.setConfig("{\"model\":\"gpt-4\"}");
        ModelProviderVO vo = new ModelProviderVO();
        vo.setId(1L);
        vo.setName("OpenAI");
        vo.setBaseUrl("https://api.openai.com");
        vo.setApiKeyId(1L);
        vo.setConfig("{\"model\":\"gpt-4\"}");
        vo.setStatus("ACTIVE");
        vo.setCreatedAt("2026-05-30 10:00:00");
        given(modelProviderService.createProvider(any(CreateModelProviderDTO.class))).willReturn(vo);

        mockMvc.perform(post("/api/v1/model/providers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1L))
                .andExpect(jsonPath("$.name").value("OpenAI"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }
}
