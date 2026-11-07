package com.buukle.agent.instance.dtvo.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.Map;

/** Flattened Timeline row (assembled on the read side: row metadata + body already resolved from ref). */
@Data
public class AgentTimelineVO implements Serializable {
    private Long seq;
    private Long runId;
    private String kind;
    private String subtype;
    private String state;
    private Long groupId;
    /** Snapshot title (no body). */
    private String title;
    private Long refRunId;
    private Long refInteractionId;
    private Long refToolCallId;
    private Long refSubAgentRunId;
    private Long refClarificationId;
    /** Compaction record id (COMPACTION row). */
    private Long refCompactId;
    /** Kind-specific body (user/assistant/tool/clarification/subagent/compaction/run_status), resolved from ref on load. */
    private Map<String, Object> content;
}