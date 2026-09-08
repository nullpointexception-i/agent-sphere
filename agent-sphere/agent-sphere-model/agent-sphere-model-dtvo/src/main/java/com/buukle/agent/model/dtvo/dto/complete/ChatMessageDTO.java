package com.buukle.agent.model.dtvo.dto.complete;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.List;

@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatMessageDTO implements Serializable {
    private String role;
    /** 纯文本时是 String；含附件（图片）时是 {@link ChatMessagePartDTO} 列表。 */
    private Object content;
    private String name;
    @JsonProperty("tool_call_id")
    private String toolCallId;
    @JsonProperty("tool_calls")
    private List<ToolCallDTO> toolCalls;
    @JsonProperty("reasoning_content")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String reasoningContent;
}
