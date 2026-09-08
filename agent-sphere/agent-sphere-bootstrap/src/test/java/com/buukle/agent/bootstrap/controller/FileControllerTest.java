package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.infrastructure.controller.FileController;
import com.buukle.agent.infrastructure.controller.dtvo.AttachmentUploadResult;
import com.buukle.agent.infrastructure.file.StoredFile;
import com.buukle.agent.infrastructure.service.AttachmentFileService;
import com.buukle.agent.infrastructure.service.ScreenshotFileService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class FileControllerTest {

    MockMvc mockMvc;

    @Mock
    AttachmentFileService attachmentFileService;

    @Mock
    ScreenshotFileService screenshotFileService;

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

    @Test
    void download_returnsBytesWhenStored() throws Exception {
        given(attachmentFileService.load("key-1")).willReturn(new StoredFile(
                "chat-attachment", "key-1", "a.png", "image/png", 3L, new byte[]{1, 2, 3}));

        mockMvc.perform(get("/api/v1/files/key-1"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(content().bytes(new byte[]{1, 2, 3}));
    }

    @Test
    void download_returns404WhenAbsent() throws Exception {
        given(attachmentFileService.load("missing")).willReturn(null);
        given(screenshotFileService.load("missing")).willReturn(null);

        mockMvc.perform(get("/api/v1/files/missing"))
                .andExpect(status().isNotFound());
    }

    @Test
    void download_fallsBackToScreenshotStore() throws Exception {
        given(attachmentFileService.load("shot-1")).willReturn(null);
        given(screenshotFileService.load("shot-1")).willReturn(new StoredFile(
                "browser-screenshot", "shot-1", "shot-1.jpg", "image/jpeg", 3L, new byte[]{7, 8, 9}));

        mockMvc.perform(get("/api/v1/files/shot-1"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andExpect(content().bytes(new byte[]{7, 8, 9}));
    }

    @Test
    void download_returns404WhenEmptyContent() throws Exception {
        given(attachmentFileService.load("key-1")).willReturn(new StoredFile(
                "chat-attachment", "key-1", "a.png", "image/png", 0L, new byte[]{}));

        mockMvc.perform(get("/api/v1/files/key-1"))
                .andExpect(status().isNotFound());
    }
}