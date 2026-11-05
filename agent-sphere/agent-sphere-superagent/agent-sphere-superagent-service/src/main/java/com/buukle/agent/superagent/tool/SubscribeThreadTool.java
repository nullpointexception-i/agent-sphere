package com.buukle.agent.superagent.tool;

import com.buukle.agent.capability.builtin.dtvo.enums.BuiltinToolEnum;
import com.buukle.agent.capability.builtin.tool.spi.constant.BuiltinToolConstants;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteContext;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteResult;
import org.springframework.stereotype.Component;

@Component
public class SubscribeThreadTool extends AbstractAgentRouterTool {

    private static final String SCHEMA = """
            {"type":"object","properties":{
              "mode":{"type":"string","enum":["PASS_THROUGH","ROUTER_ONLY"]}
            }}
            """;

    public SubscribeThreadTool(AgentRouterToolService toolService) {
        super(toolService);
    }

    @Override
    public BuiltinToolEnum getToolType() {
        return BuiltinToolEnum.SUBSCRIBE_THREAD;
    }

    @Override
    public ExecuteResult execute(ExecuteContext ctx) {
        AgentRouterToolContext input = context(ctx);
        return toolService.subscribeThread(input.getSessionId(), input.getRunId(), input.getMode());
    }

    @Override
    protected String toolName() {
        return BuiltinToolConstants.NAME_PREFIX + "SubscribeThreadTool";
    }

    @Override
    protected String description() {
        return "Subscribe to the current thread so future events can pass through the group router.";
    }

    @Override
    protected String displayNameCn() {
        return "订阅线程";
    }

    @Override
    protected String displayNameEn() {
        return "Subscribe Thread";
    }

    @Override
    protected String parameterSchema() {
        return SCHEMA;
    }
}
