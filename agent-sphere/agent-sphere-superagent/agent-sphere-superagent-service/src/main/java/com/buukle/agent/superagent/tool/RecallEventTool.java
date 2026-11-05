package com.buukle.agent.superagent.tool;

import com.buukle.agent.capability.builtin.dtvo.enums.BuiltinToolEnum;
import com.buukle.agent.capability.builtin.tool.spi.constant.BuiltinToolConstants;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteContext;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteResult;
import org.springframework.stereotype.Component;

@Component
public class RecallEventTool extends AbstractAgentRouterTool {

    private static final String SCHEMA = """
            {"type":"object","properties":{
              "eventId":{"type":"integer","description":"Event id from the delivery summary"}
            },"required":["eventId"]}
            """;

    public RecallEventTool(AgentRouterToolService toolService) {
        super(toolService);
    }

    @Override
    public BuiltinToolEnum getToolType() {
        return BuiltinToolEnum.RECALL_EVENT;
    }

    @Override
    public ExecuteResult execute(ExecuteContext ctx) {
        AgentRouterToolContext input = context(ctx);
        return toolService.recall(input.getSessionId(), input.getRunId(), input.getEventId());
    }

    @Override
    protected String toolName() {
        return BuiltinToolConstants.NAME_PREFIX + "RecallEventTool";
    }

    @Override
    protected String description() {
        return "Load the full text of an event by id. Delivery messages only carry summaries.";
    }

    @Override
    protected String displayNameCn() {
        return "展开事件";
    }

    @Override
    protected String displayNameEn() {
        return "Recall Event";
    }

    @Override
    protected String parameterSchema() {
        return SCHEMA;
    }
}
