package com.buukle.agent.instance.spi;

import com.buukle.agent.instance.dtvo.vo.AgentSubAgentRunVO;
import com.buukle.agent.instance.dtvo.vo.SubAgentTimelineItemVO;

import java.util.List;

public interface AgentSubAgentRunSpi {
    AgentSubAgentRunVO start(Long sessionId, Long runId, Long parentRunId, String parentToolCallId,
                             String agentType, String agentRef, String displayName);

    void finish(Long id, String status);

    AgentSubAgentRunVO getById(Long id);

    List<AgentSubAgentRunVO> listBySession(Long sessionId);

    List<AgentSubAgentRunVO> listByRun(Long runId);

    /** 该子 Agent 运行名下的 interactions + tool_calls 时序流（按 created_at 递增）。 */
    List<SubAgentTimelineItemVO> timeline(Long subAgentRunId);
}