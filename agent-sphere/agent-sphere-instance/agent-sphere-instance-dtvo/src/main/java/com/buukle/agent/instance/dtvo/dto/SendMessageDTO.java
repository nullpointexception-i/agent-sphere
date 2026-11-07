package com.buukle.agent.instance.dtvo.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class SendMessageDTO implements Serializable {
    /** May be empty when sending an image-only message; the service layer validates that it is not empty at the same time as attachmentKeys. */
    @Size(max = 5000)
    private String message;
    private Long modelRouteId;
    private Long threadId;
    /** Reasoning effort: low/medium/high/max; when empty the backend computes it from the output budget. */
    private String reasoningEffort;
    private String delivery;
    private Boolean noClarification;
    /** Optional override for the next run; null uses session.runMode. */
    private String runMode;
    private List<String> attachmentKeys;
}
