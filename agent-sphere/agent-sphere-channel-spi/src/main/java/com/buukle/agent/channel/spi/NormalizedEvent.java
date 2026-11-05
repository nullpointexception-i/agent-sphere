package com.buukle.agent.channel.spi;

import java.io.Serializable;
import java.util.List;

/**
 * 入站事件的统一形状。Adapter 只做协议转换，不做业务判断。
 */
public record NormalizedEvent(
        String channelType,
        String externalThreadId,
        String externalEventId,
        String kind,
        String author,
        boolean self,
        String text,
        List<AttachmentRef> attachments,
        String rawMeta,
        Long channelBindingId
) implements Serializable {

    public NormalizedEvent {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }
}
