package com.buukle.agent.project.tool;

import com.buukle.agent.capability.builtin.dtvo.enums.BuiltinToolEnum;
import com.buukle.agent.capability.builtin.tool.spi.constant.BuiltinToolConstants;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteContext;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteResult;
import org.springframework.stereotype.Component;

/** Avatar directory, consultation, and task dispatch. */
@Component
public class ProjectAvatarTool extends AbstractProjectTool {

    private static final String SCHEMA = """
            {"type":"object","properties":{
              "operation":{"type":"string","enum":["list","consult","dispatch"]},
              "agentId":{"type":"integer","description":"Avatar id from operation=list"},
              "question":{"type":"string","description":"For consult: the question to ask the avatar"},
              "goal":{"type":"string","description":"For dispatch: the concrete task goal"},
              "taskPriority":{"type":"integer","description":"Lower number means higher priority"},
              "requirementId":{"type":"integer","description":"Optional requirement this consultation/task belongs to"}
            },"required":["operation"]}
            """;

    public ProjectAvatarTool(ProjectToolService toolService) {
        super(toolService);
    }

    @Override
    public BuiltinToolEnum getToolType() {
        return BuiltinToolEnum.PROJECT_AVATAR;
    }

    @Override
    public ExecuteResult execute(ExecuteContext ctx) {
        return toolService.avatar(context(ctx));
    }

    @Override
    protected String toolName() {
        return BuiltinToolConstants.NAME_PREFIX + "ProjectAvatarTool";
    }

    @Override
    protected String description() {
        return "List project avatars, consult one synchronously, or dispatch a task to one. Another member's avatar must be public and the caller must be a project manager.";
    }

    @Override
    protected String displayNameCn() {
        return "项目分身";
    }

    @Override
    protected String displayNameEn() {
        return "Project Avatar";
    }

    @Override
    protected String parameterSchema() {
        return SCHEMA;
    }
}
