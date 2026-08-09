package com.buukle.agent.instance.spi;

import com.buukle.agent.instance.dtvo.vo.AgentLlmInteractionRecordVO;

import java.util.List;

public interface AgentLlmInteractionRecordSpi {
    void createRecord(AgentLlmInteractionRecordVO vo);

    List<AgentLlmInteractionRecordVO> listByRunId(Long runId, int offset, int limit);

    long countByRunId(Long runId);

    AgentLlmInteractionRecordVO getById(Long id);
}
