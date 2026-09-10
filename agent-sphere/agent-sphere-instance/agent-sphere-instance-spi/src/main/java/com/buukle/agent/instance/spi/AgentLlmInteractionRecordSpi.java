package com.buukle.agent.instance.spi;

import com.buukle.agent.instance.domain.vo.SessionUsageVO;
import com.buukle.agent.instance.dtvo.vo.AgentLlmInteractionRecordVO;

import java.util.List;

public interface AgentLlmInteractionRecordSpi {
    /** 落库一次 LLM 调用记录，返回自增主键 id（供 Timeline 按轮次引用）。 */
    Long createRecord(AgentLlmInteractionRecordVO vo);

    List<AgentLlmInteractionRecordVO> listByRunId(Long runId, int offset, int limit);

    long countByRunId(Long runId);

    AgentLlmInteractionRecordVO getById(Long id);

    /** 会话级用量聚合（聊天区最下方吸底展示用）。 */
    SessionUsageVO usageSummary(Long sessionId);
}
