package com.buukle.agent.instance.spi;

import com.buukle.agent.instance.domain.vo.SessionUsageVO;
import com.buukle.agent.instance.domain.vo.SuperAgentUsageVO;
import com.buukle.agent.instance.dtvo.vo.AgentLlmInteractionRecordVO;

import java.util.List;

public interface AgentLlmInteractionRecordSpi {
    /** 落库一次 LLM 调用记录，返回自增主键 id（供 Timeline 按轮次引用）。 */
    Long createRecord(AgentLlmInteractionRecordVO vo);

    /**
     * 主 Agent 轮次开始：为本次调用分配一条独立记录（V117）。
     *
     * <p>同一 {@code (runId, turnNo, attemptNo)} 下已有 RUNNING 行时复用（进程中断重入）；
     * 否则分配新的 {@code retryNo} 插入，保证上一次成功/失败的记录不被覆盖。
     * attemptNo=0 为主路由，1..N 为 fallback。
     */
    Long createOrGetRunning(AgentLlmInteractionRecordVO vo);

    /** 主 Agent 轮次结束：仅更新仍为 RUNNING 的记录，避免旧 attempt 覆盖新 attempt。 */
    void complete(Long id, AgentLlmInteractionRecordVO vo);

    /** 按 (runId, turnNo) 取当前应展示的 attempt id（优先 RUNNING，其次最新）。 */
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

    /** Aggregate one Super-Agent usage by day and session type. */
    List<SuperAgentUsageVO> usageBySuperAgent(Long superAgentId, int days);

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
