package com.buukle.agent.project.tool;

import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteContext;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/** Shared input for the four project-assistant tools. */
@Data
@EqualsAndHashCode(callSuper = true)
public class ProjectToolContext extends ExecuteContext {

    /** Query / mutate / insight / avatar operation name. */
    private String operation;

    private Long requirementId;
    private String title;
    private String description;
    private String status;
    private Integer priority;
    private Long assigneeUserId;
    private String startDate;
    private String dueDate;
    private String keyword;
    private Integer page;
    private Integer pageSize;

    private Long userId;
    private String memberRole;

    private String content;
    private String insightType;
    private String severity;

    private Long agentId;
    private String question;
    private String goal;
    private Integer taskPriority;

    /** Destructive operations: id of the clarification that approved this action. */
    private String confirmationId;
}
