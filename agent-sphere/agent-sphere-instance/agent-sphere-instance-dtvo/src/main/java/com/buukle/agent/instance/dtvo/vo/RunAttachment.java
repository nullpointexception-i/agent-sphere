package com.buukle.agent.instance.dtvo.vo;

import java.io.Serializable;

/**
 * Chat attachment reference associated with a run (fileKey + content-type only; the bytes stay in agent_file_store).
 * The Timeline USER row uses it to render images.
 */
public record RunAttachment(String fileKey, String contentType) implements Serializable {
}