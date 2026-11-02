package com.buukle.agent.instance.spi;

import com.buukle.agent.instance.dtvo.dto.TimelineRefQuery;

/**
 * 统一 Timeline（打平展示索引）契约：
 * 会话级 seq 分配、行记录/封口、keyset 分页读取（正文解析）。
 */
public interface AgentTimelineSpi {

    /** 分配会话级单调 seq（Redis INCR，不重置）。 */
    long nextSeq(Long sessionId);

    /**
     * 记录一行 timeline（返回分配的 seq）。
     */
    long record(Long sessionId, Long runId, String kind, String subtype, String state, String title,
                Long refRunId, Long refInteractionId, Long refToolCallId,
                Long refSubAgentRunId, Long refClarificationId);

    /** 记录工具授权行（refApprovalId 指向 agent_tool_approval.id）。 */
    long recordApproval(Long sessionId, Long runId, String kind, String subtype, String state, String title,
                        Long refRunId, Long refApprovalId);

    /**
     * 更新行状态/标题（幂等；state 为空则跳过）。
     */
    void update(Long sessionId, Long seq, String state, String subtype, String title);

    /** 回填行引用的 LLM 交互记录 id（每轮 LLM 调用落库后由 recorder 补引用，一轮一行）。 */
    void updateRefInteractionId(Long sessionId, Long seq, Long interactionId);

    /** 回填 COMPACTION 行的压缩记录 id（正文从 agent_compact_record.summary_after 解析）。 */
    void updateRefCompactId(Long sessionId, Long seq, Long compactRecordId);

    /**
     * 按 run + kind + ref 查最近一行的 seq；不存在返回 null。
     *
     * <p>供 TimelineRecorder 在进程内 Map 未命中时做 DB 兜底：
     * 跨副本/重启后仍能把 PENDING→ANSWERED、RUNNING→COMPLETED 收敛到同一行。
     */
    Long findLatestSeq(TimelineRefQuery query);

    /** 按 run + kind + ref 查最近一行的 state；不存在返回 null。 */
    String findLatestState(TimelineRefQuery query);

    /** 按子 Agent 运行 id 更新其头行状态（sub_agent_run 结束封口）。 */
    void updateBySubAgentRun(Long subAgentRunId, String state);

    /**
     * 按 LLM 交互记录建/复用 assistant 行。
     *
     * <p>(ref_run_id, ref_interaction_id) 唯一：重启/跨副本后命中同一轮直接复用，
     * 不再新建第二条空气泡。
     */
    long recordAssistantByInteraction(Long sessionId, Long runId, Long interactionId);

    /** 关闭该 run 所有仍 RUNNING 的 assistant 行（终态兜底）。 */
    int closeRunningAssistant(Long sessionId, Long runId, String state);

    /** 按 ref_interaction_id 找 assistant 行 seq；不存在返回 null。 */
    Long findSeqByInteraction(Long runId, Long interactionId);

    /**
     * keyset 分页读取：beforeSeq=向上翻（更早）、afterSeq=断线补档（更新）、均空=最近一页；
     * 返回页（含从 ref 解析好的正文）。
     */
    com.buukle.agent.instance.dtvo.vo.SessionTimelinePageVO page(Long sessionId, Long beforeSeq, Long afterSeq, int limit);
}