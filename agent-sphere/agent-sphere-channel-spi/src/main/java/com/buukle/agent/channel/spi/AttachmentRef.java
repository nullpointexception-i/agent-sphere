package com.buukle.agent.channel.spi;

import java.io.Serializable;

/** Attachment reference: carries only fileKey + metadata; the bytes stay in the file store. */
public record AttachmentRef(String fileKey, String contentType, String name, Long size) implements Serializable {
}
