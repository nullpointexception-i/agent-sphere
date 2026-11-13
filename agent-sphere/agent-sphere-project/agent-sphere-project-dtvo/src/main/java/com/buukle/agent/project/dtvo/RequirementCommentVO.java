package com.buukle.agent.project.dtvo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class RequirementCommentVO {
    private Long id;
    private Long requirementId;
    private Long userId;
    private String userName;
    /** USER / ASSISTANT / AVATAR. */
    private String authorType;
    private String authorName;
    private Long parentCommentId;
    private String content;
    private List<RequirementAttachmentVO> attachments;
    private List<RequirementCommentMentionVO> mentions;
    private LocalDateTime createdAt;
}
