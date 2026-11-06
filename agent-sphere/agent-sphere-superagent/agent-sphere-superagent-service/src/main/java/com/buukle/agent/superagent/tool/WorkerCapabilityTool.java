package com.buukle.agent.superagent.tool;

import com.buukle.agent.capability.builtin.dtvo.enums.BuiltinToolEnum;
import com.buukle.agent.capability.builtin.tool.spi.constant.BuiltinToolConstants;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteContext;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteResult;
import com.buukle.agent.superagent.spi.WorkerCapabilityPort;
import com.buukle.agent.superagent.spi.WorkerToolView;
import com.buukle.agent.util.json.JsonUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lets the Router inspect what the bound Worker can actually do before dispatching
 * non-chat work. The payload intentionally contains only tool names and descriptions.
 */
@Component
public class WorkerCapabilityTool extends AbstractAgentRouterTool {

    private static final String SCHEMA =
            "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}";

    private final ObjectProvider<WorkerCapabilityPort> capabilityPortProvider;

    public WorkerCapabilityTool(AgentRouterToolService toolService,
                                ObjectProvider<WorkerCapabilityPort> capabilityPortProvider) {
        super(toolService);
        this.capabilityPortProvider = capabilityPortProvider;
    }

    @Override
    public BuiltinToolEnum getToolType() {
        return BuiltinToolEnum.WORKER_CAPABILITY;
    }

    /** Auto-included: the Router should not need a DB capability binding to inspect its Worker. */
    @Override
    public boolean needConfig() {
        return false;
    }

    @Override
    public ExecuteResult execute(ExecuteContext ctx) {
        AgentRouterToolContext input = context(ctx);
        WorkerCapabilityPort port = capabilityPortProvider.getIfAvailable();
        if (port == null) {
            return AgentRouterToolResult.failed("worker capability lookup unavailable");
        }
        try {
            List<WorkerToolView> tools = port.describe(toolService.workerInstanceId(input.getSessionId()));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("tools", tools == null ? List.of() : tools);
            return AgentRouterToolResult.ok(JsonUtils.toJson(payload), null);
        } catch (Exception e) {
            return AgentRouterToolResult.failed("worker capability lookup failed: " + e.getMessage());
        }
    }

    @Override
    protected String toolName() {
        return BuiltinToolConstants.NAME_PREFIX + "WorkerCapabilityTool";
    }

    @Override
    protected String description() {
        return "List the tools the bound Worker can use (name + description only). "
                + "Call once before dispatching a non-chat task to decide whether the Worker can do it.";
    }

    @Override
    protected String displayNameCn() {
        return "查询 Worker 能力";
    }

    @Override
    protected String displayNameEn() {
        return "Worker Capabilities";
    }

    @Override
    protected String parameterSchema() {
        return SCHEMA;
    }
}
