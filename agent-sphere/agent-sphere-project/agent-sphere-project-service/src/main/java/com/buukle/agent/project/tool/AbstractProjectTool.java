package com.buukle.agent.project.tool;

import com.buukle.agent.capability.builtin.dtvo.enums.BuiltinToolEnum;
import com.buukle.agent.capability.builtin.tool.spi.CapabilityBuiltinToolSpi;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteContext;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteResult;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.ToolInfoVO;

/** Base for the project-assistant tools. */
public abstract class AbstractProjectTool implements CapabilityBuiltinToolSpi {

    protected final ProjectToolService toolService;

    protected AbstractProjectTool(ProjectToolService toolService) {
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
        return ProjectToolContext.class;
    }

    @Override
    public Class<? extends ExecuteResult> getResultType() {
        return ProjectToolResult.class;
    }

    protected static ProjectToolContext context(ExecuteContext ctx) {
        return (ProjectToolContext) ctx;
    }

    @Override
    public abstract BuiltinToolEnum getToolType();
}
