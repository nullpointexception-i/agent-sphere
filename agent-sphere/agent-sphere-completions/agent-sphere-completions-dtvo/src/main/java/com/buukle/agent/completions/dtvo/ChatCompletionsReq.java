package com.buukle.agent.completions.dtvo;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;
import java.util.Map;

@Data
public class ChatCompletionsReq implements Serializable {
    @NotNull(message = "completionsId can't be null")
    private Long completionsId;
    private Map<String, Object> input;
}
