package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.infrastructure.service.AttachmentFileService;
import com.buukle.agent.infrastructure.service.ScreenshotFileService;
import com.buukle.agent.runtime.kernel.port.vo.PreparedAttachment;
import com.buukle.agent.runtime.orchestration.service.ChatAttachmentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ChatAttachmentServiceTest {

    @Mock
    AttachmentFileService attachmentFileService;

    @Mock
    ScreenshotFileService screenshotFileService;

    @InjectMocks
    ChatAttachmentService chatAttachmentService;

    @Test
    void resolve_nullOrEmpty_returnsEmptyList() {
        assertTrue(chatAttachmentService.resolve(null).isEmpty());
        assertTrue(chatAttachmentService.resolve(List.of()).isEmpty());
    }

    @Test
    void resolve_moreThanMax_rejected() {
        List<String> keys = List.of("k1", "k2", "k3", "k4", "k5");

        assertThrows(BizException.class, () -> chatAttachmentService.resolve(keys));
    }

    @Test
    void resolve_missingAttachment_rejectedWithoutReadingBytes() {
        given(attachmentFileService.supportedContentTypeOf("k1")).willReturn(null);

        assertThrows(BizException.class, () -> chatAttachmentService.resolve(List.of("k1")));
        verify(attachmentFileService, never()).toDataUrl(anyString());
        verify(attachmentFileService, never()).toDataUrl(any());
    }

    @Test
    void resolve_validKeys_carriesOnlyKeyAndContentType() {
        given(attachmentFileService.supportedContentTypeOf("k1")).willReturn("image/png");
        given(attachmentFileService.supportedContentTypeOf("k2")).willReturn("image/jpeg");

        List<PreparedAttachment> result = chatAttachmentService.resolve(List.of("k1", "k2"));

        assertEquals(2, result.size());
        assertEquals("k1", result.get(0).fileKey());
        assertEquals("image/png", result.get(0).contentType());
        assertEquals("k2", result.get(1).fileKey());
        // 校验阶段只读元数据：base64/字节读取延后到组模型消息时
        verify(attachmentFileService, never()).toDataUrl(anyString());
    }

    @Test
    void toDataUrl_delegatesToFileService() {
        given(attachmentFileService.toDataUrl("k1")).willReturn("data:image/png;base64,AAAA");

        assertEquals("data:image/png;base64,AAAA", chatAttachmentService.toDataUrl("k1"));
    }

    @Test
    void toDataUrl_fallsBackToScreenshotStoreWhenNotChatAttachment() {
        given(attachmentFileService.toDataUrl("shot-1")).willReturn(null);
        given(screenshotFileService.toDataUrl("shot-1")).willReturn("data:image/jpeg;base64,BBBB");

        assertEquals("data:image/jpeg;base64,BBBB", chatAttachmentService.toDataUrl("shot-1"));
    }
}