package com.buukle.agent.channel.spi;

import java.io.Serializable;
import java.util.List;

/**
 * Unified shape of an inbound event. Adapters only convert protocols; they make no business decisions.
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
