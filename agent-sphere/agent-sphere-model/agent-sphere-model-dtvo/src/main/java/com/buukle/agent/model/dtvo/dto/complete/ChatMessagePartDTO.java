package com.buukle.agent.model.dtvo.dto.complete;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * OpenAI-compatible message content part: a text or image block.
 * For image blocks, image_url.url is a data URL (inline base64); the format is the same across
 * providers (OpenAI/DeepSeek/Zhipu).
 */
@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatMessagePartDTO implements Serializable {

    public static final String TYPE_TEXT = "text";
    public static final String TYPE_IMAGE_URL = "image_url";

    private String type;
    private String text;
    @JsonProperty("image_url")
    private ImageUrl imageUrl;

    @Data
    @Accessors(chain = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ImageUrl implements Serializable {
        private String url;
    }
}
