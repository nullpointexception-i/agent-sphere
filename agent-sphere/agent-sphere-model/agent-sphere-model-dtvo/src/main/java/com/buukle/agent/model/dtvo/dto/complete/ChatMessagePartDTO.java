package com.buukle.agent.model.dtvo.dto.complete;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * OpenAI 兼容消息 content part：文本/图片块。
 * 图片块 image_url.url 为 data URL（base64 内联），各家（OpenAI/DeepSeek/智谱）格式一致。
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
