package com.buukle.agent.runtime.kernel.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.io.Serializable;

@Data
public class ToolCallContract implements Serializable {
    private String id;
    private String type;
    @JsonProperty("function")
    private FunctionCallContract function;
}
