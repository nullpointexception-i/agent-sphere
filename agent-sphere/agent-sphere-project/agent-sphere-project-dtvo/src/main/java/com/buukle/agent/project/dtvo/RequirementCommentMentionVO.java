package com.buukle.agent.project.dtvo;

import lombok.Data;

/** One structured @ mention on a requirement comment. */
@Data
public class RequirementCommentMentionVO {
    private Long id;
    /** USER or AVATAR. */
    private String mentionType;
    private Long targetUserId;
    private String targetUserName;
    private Long targetAgentId;
    private String targetAgentName;
    private String status;
    private String errorMessage;
}
