package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.infrastructure.config.PluginFileController;
import com.buukle.agent.infrastructure.config.PluginFileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PluginFileControllerTest {

    MockMvc mockMvc;

    @Mock
    PluginFileService pluginFileService;

    @InjectMocks
    PluginFileController controller;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void upload_zipSucceeds() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "plugin.zip", "application/zip", new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/api/v1/system/config/plugin/upload").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("上传成功"));

        verify(pluginFileService).upload(any(org.springframework.web.multipart.MultipartFile.class));
    }

    @Test
    void download_whenFileExists_streamsAttachment() throws Exception {
        ResponseEntity<byte[]> response = ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"agent-sphere-chrome-extension.zip\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new byte[]{1, 2, 3});
        given(pluginFileService.download()).willReturn(response);

        mockMvc.perform(get("/api/v1/system/config/plugin/download"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"agent-sphere-chrome-extension.zip\""));
    }

    @Test
    void download_whenNoFile_returns404() throws Exception {
        given(pluginFileService.download())
                .willReturn(ResponseEntity.notFound().build());

        mockMvc.perform(get("/api/v1/system/config/plugin/download"))
                .andExpect(status().isNotFound());
    }
}