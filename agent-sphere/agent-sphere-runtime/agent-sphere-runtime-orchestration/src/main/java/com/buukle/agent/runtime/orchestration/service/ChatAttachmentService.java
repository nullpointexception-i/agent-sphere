package com.buukle.agent.runtime.orchestration.service;

import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.infrastructure.service.AttachmentFileService;
import com.buukle.agent.infrastructure.service.ScreenshotFileService;
import com.buukle.agent.runtime.kernel.port.ChatAttachmentResolver;
import com.buukle.agent.runtime.kernel.port.vo.PreparedAttachment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Chat attachments: the send side only validates and carries fileKey (+content-type); base64 never enters the Redis queue.
 * Bytes are lazily read back and assembled into a data URL via {@link ChatAttachmentResolver#toDataUrl} only when model messages are built.
 * kernel depends only on the port interface; byte resolution is confined to this layer (orchestration → infrastructure).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatAttachmentService implements ChatAttachmentResolver {

    public static final int MAX_ATTACHMENTS = 4;

    private final AttachmentFileService attachmentFileService;
    private final ScreenshotFileService screenshotFileService;

    /** Validate and convert to a lightweight queue descriptor; a missing / non-image attachment yields an immediate 400. */
    public List<PreparedAttachment> resolve(List<String> attachmentKeys) {
        if (attachmentKeys == null || attachmentKeys.isEmpty()) {
            return Collections.emptyList();
        }
        if (attachmentKeys.size() > MAX_ATTACHMENTS) {
            throw new BizException(com.buukle.agent.common.error.CommonErrorCode.PARAM_INVALID,
                    "At most " + MAX_ATTACHMENTS + " attachments per turn");
        }
        List<PreparedAttachment> result = new ArrayList<>(attachmentKeys.size());
        for (String key : attachmentKeys) {
            if (key == null || key.isBlank()) {
                continue;
            }
            String contentType = attachmentFileService.supportedContentTypeOf(key);
            if (contentType == null) {
                throw new BizException(com.buukle.agent.common.error.CommonErrorCode.PARAM_INVALID,
                        "Attachment does not exist or is not an image: " + key);
            }
            result.add(new PreparedAttachment(key, contentType));
        }
        return result;
    }

    /** Attachment/screenshot data URL: chat attachments take priority; fall back to browser screenshots when not found (the fileKey spaces do not collide). */
    @Override
    public String toDataUrl(String fileKey) {
        String url = attachmentFileService.toDataUrl(fileKey);
        if (url != null) {
            return url;
        }
        // Browser screenshot (browser-screenshot bizKey): fall back to the screenshot when no chat attachment is found
        return screenshotFileService.toDataUrl(fileKey);
    }
}
