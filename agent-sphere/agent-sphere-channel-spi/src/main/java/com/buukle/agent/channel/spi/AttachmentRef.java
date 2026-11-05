package com.buukle.agent.channel.spi;

import java.io.Serializable;

/** 附件引用：只传 fileKey + 元数据，字节留在文件存储。 */
public record AttachmentRef(String fileKey, String contentType, String name, Long size) implements Serializable {
}
