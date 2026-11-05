package com.buukle.agent.superagent.tool;

import com.buukle.agent.capability.builtin.dtvo.enums.BuiltinToolEnum;
import com.buukle.agent.capability.builtin.tool.spi.constant.BuiltinToolConstants;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteContext;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteResult;
import org.springframework.stereotype.Component;

@Component
public class DispatchWorkerTool extends AbstractAgentRouterTool {

    private static final String SCHEMA = """
            {"type":"object","properties":{
              "goal":{"type":"string","description":"Concrete task goal for the worker"},
              "priority":{"type":"integer","description":"Lower number means higher priority"}
            },"required":["goal"]}
            """;

    public DispatchWorkerTool(AgentRouterToolService toolService) {
        super(toolService);
    }

    @Override
    public BuiltinToolEnum getToolType() {
        return BuiltinToolEnum.DISPATCH_WORKER;
    }

    @Override
    public ExecuteResult execute(ExecuteContext ctx) {
        AgentRouterToolContext input = context(ctx);
        return toolService.dispatch(input.getSessionId(), input.getRunId(), input.getGoal(), input.getPriority());
    }

    @Override
    protected String toolName() {
        return BuiltinToolConstants.NAME_PREFIX + "DispatchWorkerTool";
    }

    @Override
    protected String description() {
        return "Create a work item and let a Worker execute the goal.";
    }

    @Override
    protected String displayNameCn() {
        return "派发任务";
    }

    @Override
    protected String displayNameEn() {
        return "Dispatch Worker";
    }

    @Override
    protected String parameterSchema() {
        return SCHEMA;
    }
}
