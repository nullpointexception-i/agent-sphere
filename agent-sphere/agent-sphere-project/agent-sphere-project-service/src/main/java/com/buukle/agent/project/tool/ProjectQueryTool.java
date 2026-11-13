package com.buukle.agent.project.tool;

import com.buukle.agent.capability.builtin.dtvo.enums.BuiltinToolEnum;
import com.buukle.agent.capability.builtin.tool.spi.constant.BuiltinToolConstants;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteContext;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteResult;
import org.springframework.stereotype.Component;

/** Read-only queries against the current project. */
@Component
public class ProjectQueryTool extends AbstractProjectTool {

    private static final String SCHEMA = """
            {"type":"object","properties":{
              "operation":{"type":"string","enum":["project","requirements","requirement","members","context","avatars"],
                "description":"project=overview; requirements=requirement list; requirement=one requirement with comments/activity; members; context=recent shared context; avatars=available member avatars"},
              "requirementId":{"type":"integer","description":"Requirement id for operation=requirement"},
              "status":{"type":"string","description":"Optional requirement status filter"},
              "keyword":{"type":"string","description":"Optional title/key/description keyword"},
              "page":{"type":"integer","minimum":1},
              "pageSize":{"type":"integer","minimum":1,"maximum":100}
            },"required":["operation"]}
            """;

    public ProjectQueryTool(ProjectToolService toolService) {
        super(toolService);
    }

    @Override
    public BuiltinToolEnum getToolType() {
        return BuiltinToolEnum.PROJECT_QUERY;
    }

    @Override
    public ExecuteResult execute(ExecuteContext ctx) {
        return toolService.query(context(ctx));
    }

    @Override
    protected String toolName() {
        return BuiltinToolConstants.NAME_PREFIX + "ProjectQueryTool";
    }

    @Override
    protected String description() {
        return "Read the current project: overview, members, requirements, comments, activity, shared context, avatars.";
    }

    @Override
    protected String displayNameCn() {
        return "查询项目";
    }

    @Override
    protected String displayNameEn() {
        return "Project Query";
    }

    @Override
    protected String parameterSchema() {
        return SCHEMA;
    }
}
