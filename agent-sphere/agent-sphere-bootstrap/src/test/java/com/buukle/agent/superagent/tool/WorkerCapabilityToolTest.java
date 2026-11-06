package com.buukle.agent.superagent.tool;

import com.buukle.agent.superagent.spi.WorkerCapabilityPort;
import com.buukle.agent.superagent.spi.WorkerToolView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkerCapabilityToolTest {

    @Test
    void returnsOnlyToolNameAndDescription() {
        AgentRouterToolService toolService = mock(AgentRouterToolService.class);
        when(toolService.workerInstanceId(5L)).thenReturn(3L);
        WorkerCapabilityPort port = workerInstanceId -> List.of(
                new WorkerToolView("网页搜索", "搜索互联网并返回摘要"));
        @SuppressWarnings("unchecked")
        ObjectProvider<WorkerCapabilityPort> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(port);

        WorkerCapabilityTool tool = new WorkerCapabilityTool(toolService, provider);
        AgentRouterToolContext context = new AgentRouterToolContext();
        context.setSessionId(5L);

        AgentRouterToolResult result = (AgentRouterToolResult) tool.execute(context);

        assertTrue(result.isOk());
        assertTrue(result.getMessage().contains("\"name\":\"网页搜索\""));
        assertTrue(result.getMessage().contains("\"description\":\"搜索互联网并返回摘要\""));
        assertTrue(result.getMessage().startsWith("{\"tools\":"));
    }
}
