package com.buukle.agent.project.tool;

import com.buukle.agent.capability.builtin.dtvo.enums.BuiltinToolEnum;
import com.buukle.agent.capability.builtin.tool.spi.constant.BuiltinToolConstants;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteContext;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteResult;
import org.springframework.stereotype.Component;

/** Persists project-level analysis results into the shared context. */
@Component
public class ProjectInsightTool extends AbstractProjectTool {

    private static final String SCHEMA = """
            {"type":"object","properties":{
              "operation":{"type":"string","enum":["create","list"],"description":"Default create"},
              "requirementId":{"type":"integer","description":"Optional related requirement"},
              "insightType":{"type":"string","enum":["SUMMARY","RISK","PROGRESS","SUGGESTION"]},
              "severity":{"type":"string","enum":["INFO","WARN","HIGH"]},
              "title":{"type":"string","description":"Short insight title"},
              "content":{"type":"string","description":"Complete, self-contained insight for project members"},
              "pageSize":{"type":"integer","minimum":1,"maximum":100}
            },"required":["operation"]}
            """;

    public ProjectInsightTool(ProjectToolService toolService) {
        super(toolService);
    }

    @Override
    public BuiltinToolEnum getToolType() {
        return BuiltinToolEnum.PROJECT_INSIGHT;
    }

    @Override
    public ExecuteResult execute(ExecuteContext ctx) {
        return toolService.insight(context(ctx));
    }

    @Override
    protected String toolName() {
        return BuiltinToolConstants.NAME_PREFIX + "ProjectInsightTool";
    }

    @Override
    protected String description() {
        return "Save or list project insights. Analysis-only runs may write insights but must not modify project data.";
    }

    @Override
    protected String displayNameCn() {
        return "项目洞察";
    }

    @Override
    protected String displayNameEn() {
        return "Project Insight";
    }

    @Override
    protected String parameterSchema() {
        return SCHEMA;
    }
}
