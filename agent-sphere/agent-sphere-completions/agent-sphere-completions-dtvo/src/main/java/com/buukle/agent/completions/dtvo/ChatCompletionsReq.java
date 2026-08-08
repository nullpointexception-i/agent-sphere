package com.buukle.agent.completions.dtvo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.util.Map;

@Data
public class ChatCompletionsReq implements Serializable {
    @NotBlank(message = "code can't be blank")
    @Size(max = 64)
    private String code;
    @NotBlank(message = "subject can't be blank")
    @Size(max = 512)
    private String subject;
    @NotBlank(message = "businessType can't be blank")
    @Size(max = 64)
    private String businessType;
    private Map<String, Object> input;
}
