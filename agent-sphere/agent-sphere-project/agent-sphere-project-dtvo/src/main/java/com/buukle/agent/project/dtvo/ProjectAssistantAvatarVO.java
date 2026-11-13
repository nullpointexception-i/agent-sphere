package com.buukle.agent.project.dtvo;

import lombok.Data;

/** An avatar the current user may consult or task inside the project. */
@Data
public class ProjectAssistantAvatarVO {
    private Long agentId;
    private String name;
    private Long ownerUserId;
    private String ownerName;
    private Boolean publicVisible;
    private Boolean ownedByRequester;
    private String status;
}
