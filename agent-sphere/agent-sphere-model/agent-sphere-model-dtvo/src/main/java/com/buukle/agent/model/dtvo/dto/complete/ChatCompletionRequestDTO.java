package com.buukle.agent.model.dtvo.dto.complete;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;
import java.io.Serializable;
import lombok.experimental.Accessors;

import java.util.List;

@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatCompletionRequestDTO implements Serializable {
    private String model;
    private boolean stream;
    private List<ChatMessageDTO> messages;
    private List<ToolDefinitionDTO> tools;
    @JsonProperty("tool_choice")
    @Getter(AccessLevel.NONE)
    private Object toolChoice;
    @JsonProperty("response_format")
    private ResponseFormatDTO responseFormat;
    @JsonProperty("thinking")
    private ThinkingDTO thinking;

    @JsonProperty("tool_choice")
    public Object getToolChoice() {
        if (tools == null || tools.isEmpty()) return null;
        return toolChoice;
    }
}
