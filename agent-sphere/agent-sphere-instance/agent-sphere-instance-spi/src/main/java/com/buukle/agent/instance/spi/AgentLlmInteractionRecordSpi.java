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

    /**
     * 回填该 run 最新一条 {@code reply_content} 为空的 CHAT_REPLY 记录（finish 的 summary 作为该轮回复）。
     *
     * <p>finish 的答案在 tool_calls 参数里，LLM 轮本身的 reply 为空；时间线 ASSISTANT 行按
     * interaction.reply_content 渲染，不回填就会一直空白。
     *
     * @return true 表示确实更新了一条空 reply 的记录
     */
    boolean updateLatestBlankReply(Long runId, String reply);
}
