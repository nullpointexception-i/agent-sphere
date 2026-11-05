package com.buukle.agent.superagent.tool;

import com.buukle.agent.capability.builtin.dtvo.enums.BuiltinToolEnum;
import com.buukle.agent.capability.builtin.tool.spi.constant.BuiltinToolConstants;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteContext;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteResult;
import org.springframework.stereotype.Component;

@Component
public class CloseThreadTool extends AbstractAgentRouterTool {

    private static final String SCHEMA = """
            {"type":"object","properties":{
              "reason":{"type":"string","description":"Why the thread can be closed"}
            }}
            """;

    public CloseThreadTool(AgentRouterToolService toolService) {
        super(toolService);
    }

    @Override
    public BuiltinToolEnum getToolType() {
        return BuiltinToolEnum.CLOSE_THREAD;
    }

    @Override
    public ExecuteResult execute(ExecuteContext ctx) {
        AgentRouterToolContext input = context(ctx);
        return toolService.closeThread(input.getSessionId(), input.getRunId(), input.getReason());
    }

    @Override
    protected String toolName() {
        return BuiltinToolConstants.NAME_PREFIX + "CloseThreadTool";
    }

    @Override
    protected String description() {
        return "Close the current thread after the request has been fully handled.";
    }

    @Override
    protected String displayNameCn() {
        return "关闭线程";
    }

    @Override
    protected String displayNameEn() {
        return "Close Thread";
    }

    @Override
    protected String parameterSchema() {
        return SCHEMA;
    }
}
