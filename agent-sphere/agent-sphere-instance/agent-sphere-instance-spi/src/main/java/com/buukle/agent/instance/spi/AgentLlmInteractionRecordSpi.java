package com.buukle.agent.instance.spi;

import com.buukle.agent.instance.domain.vo.SessionUsageVO;
import com.buukle.agent.instance.dtvo.vo.AgentLlmInteractionRecordVO;

import java.util.List;

public interface AgentLlmInteractionRecordSpi {
    /** 落库一次 LLM 调用记录，返回自增主键 id（供 Timeline 按轮次引用）。 */
    Long createRecord(AgentLlmInteractionRecordVO vo);

    /**
     * 主 Agent 轮次开始：建或复用 {@code (runId, turnNo)} 的记录。
     *
     * <p>已存在则重置为 RUNNING 并返回原 id（重启恢复复用同一轮），不存在则插入。
     * 并发插入由 {@code uk_llm_interaction_run_turn} 兜底：冲突后回查赢家。
     */
    Long createOrGetRunning(AgentLlmInteractionRecordVO vo);

    /** 主 Agent 轮次结束：仅更新仍为 RUNNING 的记录，避免旧 attempt 覆盖新 attempt。 */
    void complete(Long id, AgentLlmInteractionRecordVO vo);

    /** 按 (runId, turnNo) 取主 Agent 问答轮记录 id。 */
    Long findByRunAndTurn(Long runId, Integer turnNo);

    /** 兜底：超过阈值仍 RUNNING 的记录置 FAILED（进程中断留下的僵死记录）。 */
    int failStaleRunning(int staleMinutes);

    List<AgentLlmInteractionRecordVO> listByRunId(Long runId, int offset, int limit);

    /** 当前 run 的全部 LLM 交互（按 id 升序；resume 恢复逐轮 assistant 正文用）。 */
    List<AgentLlmInteractionRecordVO> listByRunIdAsc(Long runId);

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
