package com.buukle.agent.project.dtvo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ProjectMemberVO {
    private Long id;
    private Long projectId;
    private Long userId;
    private String displayName;
    private String memberRole;
    private LocalDateTime joinedAt;
}
