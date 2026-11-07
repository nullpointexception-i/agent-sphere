package com.buukle.agent.im.controller;



import com.buukle.agent.common.constant.ApiPrefix;
import com.buukle.agent.common.constant.PermissionCodes;
import com.buukle.agent.common.annotation.RequirePermission;
import com.buukle.agent.common.context.WithTenant;
import com.buukle.agent.common.file.StoredFile;
import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.im.service.ImAttachmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

@RestController
@ConditionalOnProperty(prefix = "buukle.agent.im", name = "enabled", havingValue = "true")
@RequestMapping(ApiPrefix.V1 + "/im/conversations/{conversationId}/attachments")
@WithTenant
@RequiredArgsConstructor
public class ImAttachmentController extends BaseController {

    private final ImAttachmentService attachmentService;

    @RequirePermission(PermissionCodes.IM_MESSAGE_SEND)
    @PostMapping
    public ResponseEntity<?> upload(@PathVariable Long conversationId,
                                    @RequestParam("file") MultipartFile file) {
        return created(attachmentService.upload(conversationId, file));
    }

    @RequirePermission(PermissionCodes.IM_CONVERSATION_READ)
    @GetMapping("/{fileKey}")
    public ResponseEntity<byte[]> download(@PathVariable Long conversationId,
                                           @PathVariable String fileKey) {
        StoredFile stored = attachmentService.download(conversationId, fileKey);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(stored.fileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType(stored.contentType()))
                .contentLength(stored.sizeBytes())
                .body(stored.content());
    }
}
