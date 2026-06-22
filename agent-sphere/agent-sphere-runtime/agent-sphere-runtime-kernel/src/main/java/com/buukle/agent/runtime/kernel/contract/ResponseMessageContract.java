package com.buukle.agent.runtime.kernel.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class ResponseMessageContract implements Serializable {
    private String content;
    @JsonProperty("tool_calls")
    private List<ToolCallContract> toolCalls;
    @JsonProperty("function_call")
    private FunctionCallContract functionCall;
}

