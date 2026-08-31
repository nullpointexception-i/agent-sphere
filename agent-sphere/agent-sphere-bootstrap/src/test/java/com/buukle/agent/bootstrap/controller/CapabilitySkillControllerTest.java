package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.capability.skill.controller.CapabilitySkillController;
import com.buukle.agent.capability.skill.dtvo.dto.BatchUpdateSkillStatusDTO;
import com.buukle.agent.capability.skill.dtvo.vo.SkillVO;
import com.buukle.agent.capability.skill.service.CapabilitySkillService;
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

import java.util.List;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class CapabilitySkillControllerTest {

    MockMvc mockMvc;
    ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    CapabilitySkillService capabilitySkillService;

    @InjectMocks
    CapabilitySkillController controller;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void updateStatus_shouldReturn200() throws Exception {
        SkillVO vo = new SkillVO();
        vo.setId(1L);
        vo.setStatus("DISABLED");
        given(capabilitySkillService.updateStatus(1L, "DISABLED")).willReturn(vo);

        mockMvc.perform(put("/api/v1/capability/skill/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk());

        verify(capabilitySkillService).updateStatus(eq(1L), eq("DISABLED"));
    }

    @Test
    void batchUpdateStatus_shouldReturn200() throws Exception {
        BatchUpdateSkillStatusDTO dto = new BatchUpdateSkillStatusDTO();
        dto.setIds(List.of(1L, 2L));
        dto.setStatus("ENABLED");

        mockMvc.perform(post("/api/v1/capability/skill/batch/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk());

        verify(capabilitySkillService).batchUpdateStatus(anyList(), eq("ENABLED"));
    }
}