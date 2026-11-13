package com.buukle.agent.project.tool;

import com.buukle.agent.capability.builtin.dtvo.enums.BuiltinToolEnum;
import com.buukle.agent.capability.builtin.tool.spi.constant.BuiltinToolConstants;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteContext;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteResult;
import org.springframework.stereotype.Component;

/** Writes against the current project. */
@Component
public class ProjectMutateTool extends AbstractProjectTool {

    private static final String SCHEMA = """
            {"type":"object","properties":{
              "operation":{"type":"string","enum":["createRequirement","updateRequirement","changeRequirementStatus","commentRequirement","updateProject","addMember","removeMember","archiveProject","deleteProject","deleteRequirement"]},
              "requirementId":{"type":"integer"},
              "title":{"type":"string","description":"Requirement or project name"},
              "description":{"type":"string"},
              "status":{"type":"string","enum":["DRAFT","REVIEWING","APPROVED","IN_PROGRESS","DONE","REJECTED"]},
              "priority":{"type":"integer","description":"Lower number means higher priority"},
              "assigneeUserId":{"type":"integer"},
              "startDate":{"type":"string","description":"YYYY-MM-DD"},
              "dueDate":{"type":"string","description":"YYYY-MM-DD; for updateProject this is the project end date"},
              "content":{"type":"string","description":"Comment body"},
              "userId":{"type":"integer","description":"Member user id for addMember/removeMember"},
              "memberRole":{"type":"string","enum":["MANAGER","MEMBER","VIEWER"]},
              "confirmationId":{"type":"string","description":"Required for removeMember/archiveProject/deleteProject/deleteRequirement: the clarificationId returned by ask_clarification after the user approved"}
            },"required":["operation"]}
            """;

    public ProjectMutateTool(ProjectToolService toolService) {
        super(toolService);
    }

    @Override
    public BuiltinToolEnum getToolType() {
        return BuiltinToolEnum.PROJECT_MUTATE;
    }

    @Override
    public ExecuteResult execute(ExecuteContext ctx) {
        return toolService.mutate(context(ctx));
    }

    @Override
    protected String toolName() {
        return BuiltinToolConstants.NAME_PREFIX + "ProjectMutateTool";
    }

    @Override
    protected String description() {
        return "Create/update requirements, change status, comment, update the project, manage members, archive/delete. Destructive operations require a confirmationId.";
    }

    @Override
    protected String displayNameCn() {
        return "修改项目";
    }

    @Override
    protected String displayNameEn() {
        return "Project Update";
    }

    @Override
    protected String parameterSchema() {
        return SCHEMA;
    }
}
