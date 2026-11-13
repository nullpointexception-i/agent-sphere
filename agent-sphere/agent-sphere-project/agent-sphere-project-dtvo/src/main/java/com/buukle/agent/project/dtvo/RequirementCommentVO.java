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
    private String content;
    private List<RequirementAttachmentVO> attachments;
    private LocalDateTime createdAt;
}
