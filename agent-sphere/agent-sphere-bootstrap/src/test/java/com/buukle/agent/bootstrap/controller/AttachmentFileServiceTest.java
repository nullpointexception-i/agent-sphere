package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.infrastructure.controller.dtvo.AttachmentUploadResult;
import com.buukle.agent.infrastructure.file.GenericFileService;
import com.buukle.agent.infrastructure.file.StoredFile;
import com.buukle.agent.infrastructure.service.AttachmentFileService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AttachmentFileServiceTest {

    @Mock
    GenericFileService genericFileService;

    @InjectMocks
    AttachmentFileService attachmentFileService;

    @Test
    void upload_nullFile_rejected() {
        assertThrows(IllegalArgumentException.class, () -> attachmentFileService.upload(null));
    }

    @Test
    void upload_emptyFile_rejected() {
        MockMultipartFile empty = new MockMultipartFile("file", "a.png", "image/png", new byte[0]);
        assertThrows(IllegalArgumentException.class, () -> attachmentFileService.upload(empty));
    }

    @Test
    void upload_disallowedMime_rejected() {
        MockMultipartFile txt = new MockMultipartFile("file", "a.txt", "text/plain", new byte[]{1});
        assertThrows(IllegalArgumentException.class, () -> attachmentFileService.upload(txt));
    }

    @Test
    void upload_overSizeLimit_rejected() {
        MockMultipartFile big = new MockMultipartFile("file", "a.png", "image/png",
                new byte[6 * 1024 * 1024]);
        assertThrows(IllegalArgumentException.class, () -> attachmentFileService.upload(big));
    }

    @Test
    void upload_validPng_storesUnderChatAttachmentBizKey() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.png", "image/png; charset=utf-8", new byte[]{1, 2, 3});

        AttachmentUploadResult result = attachmentFileService.upload(file);

        ArgumentCaptor<String> bizKeyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> contentCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(genericFileService).save(
                bizKeyCaptor.capture(), any(), any(), any(), contentCaptor.capture());
        assertEquals(AttachmentFileService.BIZ_KEY, bizKeyCaptor.getValue());
        assertEquals("image/png", result.contentType());
        assertEquals(3L, result.sizeBytes());
        assertTrue(result.fileKey().length() > 20, "fileKey 应为 UUID");
    }

    @Test
    void toDataUrl_storedAllowed_generatesDataUrl() {
        byte[] bytes = new byte[]{10, 20, 30};
        given(genericFileService.get(any(), any())).willReturn(
                new StoredFile(AttachmentFileService.BIZ_KEY, "k", "a.png", "image/png", bytes.length, bytes));

        String url = attachmentFileService.toDataUrl("k");

        assertEquals("data:image/png;base64," + Base64.getEncoder().encodeToString(bytes), url);
    }

    @Test
    void toDataUrl_missingFile_returnsNull() {
        given(genericFileService.get(any(), any())).willReturn(null);

        assertNull(attachmentFileService.toDataUrl("k"));
    }

    @Test
    void toDataUrl_disallowedType_returnsNull() {
        given(genericFileService.get(any(), any())).willReturn(
                new StoredFile(AttachmentFileService.BIZ_KEY, "k", "a.svg", "image/svg+xml", 1, new byte[]{1}));

        assertNull(attachmentFileService.toDataUrl("k"));
    }

    @Test
    void contentTypeOf_missingReturnsNull() {
        given(genericFileService.get(any(), any())).willReturn(null);

        assertNull(attachmentFileService.contentTypeOf("k"));
    }

    @Test
    void contentTypeOf_presentReturnsContentType() {
        given(genericFileService.get(any(), any())).willReturn(
                new StoredFile(AttachmentFileService.BIZ_KEY, "k", "a.png", "image/png", 1, new byte[]{1}));

        assertEquals("image/png", attachmentFileService.contentTypeOf("k"));
    }

    @Test
    void supportedContentTypeOf_allowedType_returnsContentType() {
        given(genericFileService.get(any(), any())).willReturn(
                new StoredFile(AttachmentFileService.BIZ_KEY, "k", "a.webp", "image/webp", 1, new byte[]{1}));

        assertEquals("image/webp", attachmentFileService.supportedContentTypeOf("k"));
    }

    @Test
    void supportedContentTypeOf_missingOrDisallowed_returnsNull() {
        given(genericFileService.get(any(), any())).willReturn(null);
        assertNull(attachmentFileService.supportedContentTypeOf("missing"));

        given(genericFileService.get(any(), any())).willReturn(
                new StoredFile(AttachmentFileService.BIZ_KEY, "k", "a.svg", "image/svg+xml", 1, new byte[]{1}));
        assertNull(attachmentFileService.supportedContentTypeOf("k"));
    }

    @Test
    void isSupportedImage_trueForAllowedType() {
        given(genericFileService.get(any(), any())).willReturn(
                new StoredFile(AttachmentFileService.BIZ_KEY, "k", "a.gif", "image/gif", 1, new byte[]{1}));

        assertTrue(attachmentFileService.isSupportedImage("k"));
    }

    @Test
    void isSupportedImage_falseForMissingOrDisallowed() {
        given(genericFileService.get(any(), any())).willReturn(null);
        assertFalse(attachmentFileService.isSupportedImage("missing"));

        given(genericFileService.get(any(), any())).willReturn(
                new StoredFile(AttachmentFileService.BIZ_KEY, "k", "a.bmp", "image/bmp", 1, new byte[]{1}));
        assertFalse(attachmentFileService.isSupportedImage("k"));
    }
}