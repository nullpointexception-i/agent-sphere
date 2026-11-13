package com.buukle.agent.superagent.dtvo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class EntityAgentBindingVO {
    private Long id;
    private String entityType;
    private Long entityId;
    private Long agentId;
    private String agentName;
    private Long ownerUserId;
    private String bindingRole;
    private String status;
    private LocalDateTime createdAt;
}
