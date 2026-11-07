package com.buukle.agent.instance.dtvo.dto;

import com.buukle.agent.instance.dtvo.vo.RunAttachment;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class CreateRunDTO implements Serializable {
    @NotNull(message = "sessionId can't be null")
    private Long sessionId;
    private Long threadId;
    @NotBlank(message = "type can't be blank")
    @Size(min = 1, max = 64)
    private String type;
    @NotBlank(message = "userMessage can't be blank")
    @Size(min = 1, max = 5000)
    private String userMessage;
    private Boolean noClarification;
    /** WRITE / READ_ONLY; null falls back to session preference. */
    private String runMode;

    /** Chat attachment references (images): persisted with the run after validation on the send side, and echoed by the Timeline read side. */
    private List<RunAttachment> attachments;
    /** Reasoning effort: low/medium/high/max; when empty the backend computes it dynamically. */
    private String reasoningEffort;
}
