package com.buukle.agent.application.dtvo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ApplicationMemberVO {
    private Long id;
    private Long applicationId;
    private Long userId;
    private String displayName;
    private String memberRole;
    private LocalDateTime createdAt;
}
