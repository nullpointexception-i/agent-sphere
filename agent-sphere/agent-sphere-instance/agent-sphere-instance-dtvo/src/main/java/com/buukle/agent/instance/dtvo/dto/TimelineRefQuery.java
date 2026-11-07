package com.buukle.agent.instance.dtvo.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Timeline row deduplication query: locates "the row that should be reused" within the same run by
 * kind + ref.
 *
 * <p>Why it is needed: TimelineRecorder's in-process Map is only valid within a single replica;
 * PENDING vs RESPONDED / RUNNING vs COMPLETED may land on different replicas or after a process
 * restart, so the old row must be looked up in the DB instead of inserting a new one.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TimelineRefQuery implements Serializable {

    private Long runId;

    /** TimelineKind code: clarification / tool / tool_approval / run_status / compaction / assistant。 */
    private String kind;

    /** Used by CLARIFICATION rows. */
    private Long refClarificationId;

    /** Used by TOOL rows. */
    private Long refToolCallId;

    /** Used by TOOL_APPROVAL rows. */
    private Long refApprovalId;

    /** Used by COMPACTION rows. */
    private Long refCompactId;
}
