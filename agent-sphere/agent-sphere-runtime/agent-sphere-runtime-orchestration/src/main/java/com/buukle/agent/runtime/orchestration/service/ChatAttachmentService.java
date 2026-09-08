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
 * 聊天附件：发送侧仅校验并携带 fileKey（+content-type），base64 不进 Redis 队列；
 * 组模型消息时才经 {@link ChatAttachmentResolver#toDataUrl} 惰性回读字节拼 data URL。
 * kernel 只依赖 port 接口，字节解析实现收敛在本层（orchestration → infrastructure）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatAttachmentService implements ChatAttachmentResolver {

    public static final int MAX_ATTACHMENTS = 4;

    private final AttachmentFileService attachmentFileService;
    private final ScreenshotFileService screenshotFileService;

    /** 校验并转换为队列轻量描述；缺失/非图片附件即时 400。 */
    public List<PreparedAttachment> resolve(List<String> attachmentKeys) {
        if (attachmentKeys == null || attachmentKeys.isEmpty()) {
            return Collections.emptyList();
        }
        if (attachmentKeys.size() > MAX_ATTACHMENTS) {
            throw new BizException(com.buukle.agent.common.error.CommonErrorCode.PARAM_INVALID,
                    "单轮最多 " + MAX_ATTACHMENTS + " 个附件");
        }
        List<PreparedAttachment> result = new ArrayList<>(attachmentKeys.size());
        for (String key : attachmentKeys) {
            if (key == null || key.isBlank()) {
                continue;
            }
            String contentType = attachmentFileService.supportedContentTypeOf(key);
            if (contentType == null) {
                throw new BizException(com.buukle.agent.common.error.CommonErrorCode.PARAM_INVALID,
                        "附件不存在或非图片: " + key);
            }
            result.add(new PreparedAttachment(key, contentType));
        }
        return result;
    }

    /** 附件/截图 data URL：聊天附件优先，查不到兜底浏览器截图（同 fileKey 空间不冲突）。 */
    @Override
    public String toDataUrl(String fileKey) {
        String url = attachmentFileService.toDataUrl(fileKey);
        if (url != null) {
            return url;
        }
        // 浏览器截图（browser-screenshot bizKey）：聊天附件查不到时兜底截图
        return screenshotFileService.toDataUrl(fileKey);
    }
}
