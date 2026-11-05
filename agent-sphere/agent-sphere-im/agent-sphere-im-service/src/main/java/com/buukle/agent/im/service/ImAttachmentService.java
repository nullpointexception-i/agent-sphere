package com.buukle.agent.im.service;

import com.buukle.agent.common.file.StoredFile;
import com.buukle.agent.im.dtvo.vo.ImAttachmentVO;
import org.springframework.web.multipart.MultipartFile;

/** Conversation-scoped attachment upload and download. */
public interface ImAttachmentService {

    ImAttachmentVO upload(Long conversationId, MultipartFile file);

    StoredFile download(Long conversationId, String fileKey);
}
