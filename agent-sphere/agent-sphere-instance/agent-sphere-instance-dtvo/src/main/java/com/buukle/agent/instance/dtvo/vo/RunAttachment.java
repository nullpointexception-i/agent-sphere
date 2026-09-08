package com.buukle.agent.instance.dtvo.vo;

import java.io.Serializable;

/**
 * run 关联的聊天附件引用（仅 fileKey + content-type，字节留 agent_file_store）。
 * Timeline USER 行据此回显图片。
 */
public record RunAttachment(String fileKey, String contentType) implements Serializable {
}