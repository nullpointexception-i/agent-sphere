package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.infrastructure.controller.BrowserScreenshotController;
import com.buukle.agent.infrastructure.controller.dtvo.ScreenshotUploadResult;
import com.buukle.agent.infrastructure.service.ScreenshotFileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class BrowserScreenshotControllerTest {

    MockMvc mockMvc;

    @Mock
    ScreenshotFileService screenshotFileService;

    @InjectMocks
    BrowserScreenshotController controller;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void screenshot_returnsFileKey() throws Exception {
        given(screenshotFileService.store("QUJD", "image/jpeg"))
                .willReturn(new ScreenshotUploadResult("shot-1", "image/jpeg", 3L));

        mockMvc.perform(post("/api/v1/browser/screenshot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentType\":\"image/jpeg\",\"base64\":\"QUJD\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileKey").value("shot-1"))
                .andExpect(jsonPath("$.contentType").value("image/jpeg"))
                .andExpect(jsonPath("$.sizeBytes").value(3));
    }
}