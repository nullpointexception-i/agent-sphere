package com.buukle.agent.infrastructure.controller;


import com.buukle.agent.common.constant.ApiPrefix;
import com.buukle.agent.common.util.BaseController;
import com.buukle.agent.infrastructure.file.StoredFile;
import com.buukle.agent.infrastructure.service.AttachmentFileService;
import com.buukle.agent.infrastructure.service.CommentAttachmentService;
import com.buukle.agent.infrastructure.service.ScreenshotFileService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Chat attachments (image recognition at this stage):
 * - POST /upload — a logged-in user uploads an image and gets back {fileKey, contentType, sizeBytes};
 *   the frontend sends the fileKey along with the chat message, and the runtime reads the bytes back to
 *   build a data URL for the model.
 * - GET  /{fileKey} — reads the raw bytes back by fileKey (login required); used by chat history to
 *   display images.
 */
@RestController
@RequestMapping(ApiPrefix.V1 + "/files")
@RequiredArgsConstructor
public class FileController extends BaseController {

    private final AttachmentFileService attachmentFileService;
    private final ScreenshotFileService screenshotFileService;
    private final CommentAttachmentService commentAttachmentService;

    @PostMapping("/upload")
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file) {
        return ok(attachmentFileService.upload(file));
    }

    @PostMapping("/comment-attachments")
    public ResponseEntity<?> uploadCommentAttachment(
            @RequestParam("file") MultipartFile file) {
        return ok(commentAttachmentService.upload(file));
    }

    @GetMapping("/{fileKey}")
    public ResponseEntity<?> download(@PathVariable("fileKey") String fileKey) {
        StoredFile stored = attachmentFileService.load(fileKey);
        if (stored == null) {
            // Browser screenshot (browser-screenshot bucket): fallback when the chat attachment is not found
            stored = screenshotFileService.load(fileKey);
        }
        if (stored == null) {
            stored = commentAttachmentService.load(fileKey);
        }
        if (stored == null || stored.content() == null || stored.content().length == 0) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        MediaType mediaType = MediaType.parseMediaType(
                stored.contentType() != null ? stored.contentType() : MediaType.APPLICATION_OCTET_STREAM_VALUE);
        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(stored.content().length)
                .body(stored.content());
    }
}