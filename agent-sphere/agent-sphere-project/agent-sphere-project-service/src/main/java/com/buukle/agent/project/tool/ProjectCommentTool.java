package com.buukle.agent.project.tool;

import com.buukle.agent.capability.builtin.dtvo.enums.BuiltinToolEnum;
import com.buukle.agent.capability.builtin.tool.spi.constant.BuiltinToolConstants;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteContext;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteResult;
import org.springframework.stereotype.Component;

/** Create a requirement comment, optionally mentioning members or avatars. */
@Component
public class ProjectCommentTool extends AbstractProjectTool {

    private static final String SCHEMA = """
            {"type":"object","properties":{
              "requirementId":{"type":"integer","description":"Requirement to comment on"},
              "content":{"type":"string","description":"Comment body, concrete findings and next steps"},
              "parentCommentId":{"type":"integer","description":"Optional comment id to reply under"},
              "mentions":{"type":"array","description":"Optional @ targets","items":{
                "type":"object","properties":{
                  "mentionType":{"type":"string","enum":["USER","AVATAR"]},
                  "targetUserId":{"type":"integer","description":"Project member id for USER"},
                  "targetAgentId":{"type":"integer","description":"Avatar id for AVATAR"}
                },"required":["mentionType"]}},
              "actionKey":{"type":"string","description":"Optional stable key for retry idempotency"}
            },"required":["requirementId","content"]}
            """;

    public ProjectCommentTool(ProjectToolService toolService) {
        super(toolService);
    }

    @Override
    public BuiltinToolEnum getToolType() {
        return BuiltinToolEnum.PROJECT_COMMENT;
    }

    @Override
    public ExecuteResult execute(ExecuteContext ctx) {
        return toolService.comment(context(ctx));
    }

    @Override
    protected String toolName() {
        return BuiltinToolConstants.NAME_PREFIX + "ProjectCommentTool";
    }

    @Override
    protected String description() {
        return "Create a requirement comment as the project assistant. Use it to report risks, blockers, or next steps in the relevant requirement thread. At most three comments per analysis run; only comment when the finding is actionable.";
    }

    @Override
    protected String displayNameCn() {
        return "需求评论";
    }

    @Override
    protected String displayNameEn() {
        return "Project Comment";
    }

    @Override
    protected String parameterSchema() {
        return SCHEMA;
    }
}
