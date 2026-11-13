package com.buukle.agent.project.dtvo;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** Input mention supplied with a requirement comment. */
@Data
public class CommentMentionDTO {
    /** USER or AVATAR. */
    @NotNull
    private String mentionType;
    private Long targetUserId;
    private Long targetAgentId;
}
