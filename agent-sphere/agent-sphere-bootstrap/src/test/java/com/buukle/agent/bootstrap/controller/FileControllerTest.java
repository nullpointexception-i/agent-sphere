package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.infrastructure.controller.FileController;
import com.buukle.agent.infrastructure.controller.dtvo.AttachmentUploadResult;
import com.buukle.agent.infrastructure.service.AttachmentFileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class FileControllerTest {

    MockMvc mockMvc;

    @Mock
    AttachmentFileService attachmentFileService;

    @InjectMocks
    FileController controller;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void upload_returnsFileKey() throws Exception {
        given(attachmentFileService.upload(any()))
                .willReturn(new AttachmentUploadResult("key-1", "image/png", 3L));

        MockMultipartFile file = new MockMultipartFile(
                "file", "a.png", "image/png", new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/api/v1/files/upload").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileKey").value("key-1"))
                .andExpect(jsonPath("$.contentType").value("image/png"))
                .andExpect(jsonPath("$.sizeBytes").value(3));
    }
}