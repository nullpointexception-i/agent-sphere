package com.buukle.agent.instance.dtvo.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class ResolveClarificationsDTO implements Serializable {
    @Valid
    @NotEmpty(message = "answers are required")
    private List<Answer> answers;

    @Data
    public static class Answer implements Serializable {
        private String clarificationId;
        private List<String> selected;
        private String text;
    }
}
