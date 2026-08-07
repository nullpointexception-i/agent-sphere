package com.buukle.agent.completions.dtvo;

import lombok.Data;

import java.io.Serializable;
import java.util.Map;

@Data
public class ChatCompletionsResp implements Serializable {
    private String content;
    private String model;
    private Map<String, Object> usage;
}
