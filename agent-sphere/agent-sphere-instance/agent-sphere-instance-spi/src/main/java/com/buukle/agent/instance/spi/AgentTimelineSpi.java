package com.buukle.agent.instance.spi;

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

    /**
     * 更新行状态/标题（幂等；state 为空则跳过）。
     */
    void update(Long sessionId, Long seq, String state, String subtype, String title);

    /** 回填行引用的 LLM 交互记录 id（每轮 LLM 调用落库后由 recorder 补引用，一轮一行）。 */
    void updateRefInteractionId(Long sessionId, Long seq, Long interactionId);

    /** 按子 Agent 运行 id 更新其头行状态（sub_agent_run 结束封口）。 */
    void updateBySubAgentRun(Long subAgentRunId, String state);

    /**
     * keyset 分页读取：beforeSeq=向上翻（更早）、afterSeq=断线补档（更新）、均空=最近一页；
     * 返回页（含从 ref 解析好的正文）。
     */
    com.buukle.agent.instance.dtvo.vo.SessionTimelinePageVO page(Long sessionId, Long beforeSeq, Long afterSeq, int limit);
}