package com.buukle.agent.superagent.tool;

import com.buukle.agent.capability.builtin.dtvo.enums.BuiltinToolEnum;
import com.buukle.agent.capability.builtin.tool.spi.CapabilityBuiltinToolSpi;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteContext;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteResult;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.ToolInfoVO;

/** Base for Super-Agent router tools. */
public abstract class AbstractAgentRouterTool implements CapabilityBuiltinToolSpi {

    protected final AgentRouterToolService toolService;

    protected AbstractAgentRouterTool(AgentRouterToolService toolService) {
        this.toolService = toolService;
    }

    protected abstract String toolName();

    protected abstract String description();

    protected abstract String displayNameCn();

    protected abstract String displayNameEn();

    protected abstract String parameterSchema();

    @Override
    public boolean needConfig() {
        return true;
    }

    @Override
    public ToolInfoVO getInfo() {
        ToolInfoVO info = new ToolInfoVO();
        info.setName(toolName());
        info.setDescription(description());
        info.setDisplayNameCn(displayNameCn());
        info.setDisplayNameEn(displayNameEn());
        info.setParamSchema(parameterSchema());
        info.setResponseSchema("{\"type\":\"object\"}");
        return info;
    }

    @Override
    public Class<? extends ExecuteContext> getContextType() {
        return AgentRouterToolContext.class;
    }

    @Override
    public Class<? extends ExecuteResult> getResultType() {
        return AgentRouterToolResult.class;
    }

    protected static AgentRouterToolContext context(ExecuteContext ctx) {
        return (AgentRouterToolContext) ctx;
    }

    @Override
    public abstract BuiltinToolEnum getToolType();
}
