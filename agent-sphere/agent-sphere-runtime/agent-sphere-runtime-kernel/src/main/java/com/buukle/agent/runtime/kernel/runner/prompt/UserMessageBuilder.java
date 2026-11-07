package com.buukle.agent.runtime.kernel.runner.prompt;

import com.buukle.agent.common.constant.LlmRoles;
import com.buukle.agent.model.dtvo.dto.complete.ChatMessageDTO;
import com.buukle.agent.model.dtvo.dto.complete.ChatMessagePartDTO;
import com.buukle.agent.runtime.kernel.port.ChatAttachmentResolver;
import com.buukle.agent.runtime.kernel.port.vo.PreparedAttachment;
import com.buukle.agent.runtime.kernel.runner.support.SessionInputManager;

import java.util.ArrayList;
import java.util.List;

/** Builds the user message, expanding attachments lazily into OpenAI-compatible content parts. */
public final class UserMessageBuilder {

    private UserMessageBuilder() {
    }

    public static ChatMessageDTO build(SessionInputManager.InputMessage input,
                                       ChatAttachmentResolver attachmentResolver,
                                       int maxAttachments) {
        ChatMessageDTO msg = new ChatMessageDTO().setRole(LlmRoles.USER);
        if (input == null || input.attachments() == null || input.attachments().isEmpty()) {
            String text = input != null && input.text() != null ? input.text() : "";
            return msg.setContent(text);
        }
        List<ChatMessagePartDTO> parts = new ArrayList<>();
        if (input.text() != null && !input.text().isBlank()) {
            parts.add(new ChatMessagePartDTO().setType(ChatMessagePartDTO.TYPE_TEXT).setText(input.text()));
        }
        int count = 0;
        for (PreparedAttachment att : input.attachments()) {
            if (att == null || att.fileKey() == null || att.fileKey().isBlank()) {
                continue;
            }
            if (count >= maxAttachments) {
                break;
            }
            String dataUrl = attachmentResolver.toDataUrl(att.fileKey());
            if (dataUrl == null || dataUrl.isBlank()) {
                continue;
            }
            ChatMessagePartDTO.ImageUrl imageUrl = new ChatMessagePartDTO.ImageUrl().setUrl(dataUrl);
            parts.add(new ChatMessagePartDTO().setType(ChatMessagePartDTO.TYPE_IMAGE_URL).setImageUrl(imageUrl));
            count++;
        }
        return msg.setContent(parts);
    }
}
