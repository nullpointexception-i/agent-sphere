package com.buukle.agent.im.service.impl;


import com.buukle.agent.common.constant.ApiPaths;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.buukle.agent.common.constant.FileStoreBizKeys;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.TenantUtil;
import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.common.file.FileStorePort;
import com.buukle.agent.common.file.StoredFile;
import com.buukle.agent.im.domain.ImParticipant;
import com.buukle.agent.im.dtvo.enums.ImParticipantType;
import com.buukle.agent.im.dtvo.vo.ImAttachmentVO;
import com.buukle.agent.im.repository.ImParticipantMapper;
import com.buukle.agent.im.service.ImAttachmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImAttachmentServiceImpl implements ImAttachmentService {

    private static final String URL_PREFIX = ApiPaths.IM_CONVERSATIONS;
    private static final String URL_SEGMENT = "/attachments/";
    private static final String FALLBACK_CONTENT_TYPE = "application/octet-stream";
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp", "image/gif",
            "application/pdf", "text/plain", "text/csv",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/zip", "application/x-zip-compressed");

    private final ImParticipantMapper participantMapper;
    private final FileStorePort fileStorePort;

    @Value("${buukle.agent.im.max-attachment-mb:20}")
    private int maxAttachmentMb;

    @Override
    public ImAttachmentVO upload(Long conversationId, MultipartFile file) {
        Long userId = currentUserId();
        assertMember(conversationId, userId);
        if (file == null || file.isEmpty()) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "attachment is empty");
        }
        long maxBytes = Math.max(1, maxAttachmentMb) * 1024L * 1024L;
        if (file.getSize() > maxBytes) {
            throw new BizException(CommonErrorCode.PARAM_INVALID,
                    "attachment exceeds " + maxAttachmentMb + "MB");
        }
        String contentType = normalizeContentType(file.getContentType());
        String fileKey = UUID.randomUUID().toString();
        String fileName = safeFileName(file.getOriginalFilename(), fileKey);
        try {
            fileStorePort.save(bizKey(conversationId), fileKey, fileName,
                    contentType, file.getBytes());
        } catch (IOException e) {
            throw new IllegalStateException("attachment save failed", e);
        }
        ImAttachmentVO vo = new ImAttachmentVO();
        vo.setFileKey(fileKey);
        vo.setFileName(fileName);
        vo.setContentType(contentType);
        vo.setSizeBytes(file.getSize());
        vo.setUrl(URL_PREFIX + conversationId + URL_SEGMENT + fileKey);
        log.info("IM attachment stored: conversation={}, fileKey={}, size={}",
                conversationId, fileKey, file.getSize());
        return vo;
    }

    @Override
    public StoredFile download(Long conversationId, String fileKey) {
        Long userId = currentUserId();
        assertMember(conversationId, userId);
        if (fileKey == null || fileKey.isBlank()) {
            throw new BizException(CommonErrorCode.PARAM_INVALID, "fileKey is required");
        }
        StoredFile stored = fileStorePort.get(bizKey(conversationId), fileKey);
        if (stored == null || stored.content() == null || stored.content().length == 0) {
            throw new BizException(CommonErrorCode.RESOURCE_NOT_FOUND, "attachment not found");
        }
        return stored;
    }

    private static String bizKey(Long conversationId) {
        return FileStoreBizKeys.IM_ATTACHMENT + ":" + conversationId;
    }

    private static String normalizeContentType(String raw) {
        if (raw == null || raw.isBlank()) {
            return FALLBACK_CONTENT_TYPE;
        }
        String normalized = raw.toLowerCase(Locale.ROOT).split(";")[0].trim();
        return ALLOWED_CONTENT_TYPES.contains(normalized) ? normalized : FALLBACK_CONTENT_TYPE;
    }

    private static String safeFileName(String original, String fallback) {
        if (original == null || original.isBlank()) {
            return fallback;
        }
        String name = original.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        return name.isBlank() ? fallback : name;
    }

    private void assertMember(Long conversationId, Long userId) {
        long count = systemRead(() -> participantMapper.selectCount(
                Wrappers.<ImParticipant>lambdaQuery()
                        .eq(ImParticipant::getConversationId, conversationId)
                        .eq(ImParticipant::getParticipantType, ImParticipantType.USER)
                        .eq(ImParticipant::getParticipantId, userId)));
        if (count == 0) {
            throw new BizException(CommonErrorCode.FORBIDDEN);
        }
    }

    private static Long currentUserId() {
        Long userId = AuthContext.getUserId();
        if (userId == null) {
            throw new BizException(CommonErrorCode.UNAUTHORIZED);
        }
        return userId;
    }

    private <T> T systemRead(Supplier<T> supplier) {
        String tenant = TenantUtil.get();
        boolean admin = AuthContext.isSuperAdmin();
        TenantUtil.stop();
        AuthContext.setSuperAdmin(true);
        try {
            return supplier.get();
        } finally {
            AuthContext.setSuperAdmin(admin);
            if (tenant != null) {
                TenantUtil.start(tenant);
            }
        }
    }
}
