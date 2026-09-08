package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.infrastructure.controller.dtvo.ScreenshotUploadResult;
import com.buukle.agent.infrastructure.file.GenericFileService;
import com.buukle.agent.infrastructure.file.StoredFile;
import com.buukle.agent.infrastructure.service.ScreenshotFileService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ScreenshotFileServiceTest {

    @Mock
    GenericFileService genericFileService;

    @InjectMocks
    ScreenshotFileService screenshotFileService;

    @Test
    void store_validJpeg_persistsUnderScreenshotBizKey() {
        byte[] bytes = new byte[]{1, 2, 3};
        String b64 = Base64.getEncoder().encodeToString(bytes);

        ScreenshotUploadResult result = screenshotFileService.store(b64, "image/jpeg");

        ArgumentCaptor<byte[]> contentCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(genericFileService).save(
                eq(ScreenshotFileService.BIZ_KEY), any(), any(), eq("image/jpeg"), contentCaptor.capture());
        assertEquals(3, contentCaptor.getValue().length);
        assertEquals("image/jpeg", result.contentType());
        assertEquals(3L, result.sizeBytes());
        assertTrue(result.fileKey().length() > 20, "fileKey 应为 UUID");
    }

    @Test
    void store_nullContent_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> screenshotFileService.store(null, "image/jpeg"));
        assertThrows(IllegalArgumentException.class,
                () -> screenshotFileService.store("  ", "image/jpeg"));
    }

    @Test
    void store_disallowedType_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> screenshotFileService.store(Base64.getEncoder().encodeToString(new byte[]{1}), "image/gif"));
    }

    @Test
    void store_overSizeLimit_rejected() {
        String huge = Base64.getEncoder().encodeToString(new byte[9 * 1024 * 1024]);
        assertThrows(IllegalArgumentException.class,
                () -> screenshotFileService.store(huge, "image/jpeg"));
    }

    @Test
    void load_absentReturnsNull() {
        given(genericFileService.get(ScreenshotFileService.BIZ_KEY, "missing")).willReturn(null);

        assertNull(screenshotFileService.load("missing"));
    }

    @Test
    void toDataUrl_presentReturnsDataUrl() {
        byte[] bytes = new byte[]{10, 20, 30};
        given(genericFileService.get(ScreenshotFileService.BIZ_KEY, "k")).willReturn(
                new StoredFile(ScreenshotFileService.BIZ_KEY, "k", "k.jpg", "image/jpeg", bytes.length, bytes));

        assertEquals("data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bytes),
                screenshotFileService.toDataUrl("k"));
    }

    @Test
    void toDataUrl_absentOrDisallowedReturnsNull() {
        given(genericFileService.get(ScreenshotFileService.BIZ_KEY, "missing")).willReturn(null);
        assertNull(screenshotFileService.toDataUrl("missing"));

        given(genericFileService.get(ScreenshotFileService.BIZ_KEY, "k")).willReturn(
                new StoredFile(ScreenshotFileService.BIZ_KEY, "k", "k.svg", "image/svg+xml", 1, new byte[]{1}));
        assertNull(screenshotFileService.toDataUrl("k"));
    }
}