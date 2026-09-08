package com.buukle.agent.runtime.kernel.port;

/**
 * 附件字节回读端口：kernel 组模型消息（content parts）时按 fileKey
 * 惰性读取图片并拼 OpenAI 兼容 data URL。实现由 orchestration 层提供
 * （委托 infrastructure 文件存储），kernel 不依赖 infrastructure。
 */
public interface ChatAttachmentResolver {

    /** 按 fileKey 回读图片字节并拼 data URL；不存在或非图片返回 null。 */
    String toDataUrl(String fileKey);
}