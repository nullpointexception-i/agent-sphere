package com.buukle.agent.instance.dtvo.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Timeline 行去重查询条件：同一 run 下按 kind + ref 定位“应该被复用的那一行”。
 *
 * <p>为什么需要它：TimelineRecorder 的进程内 Map 只在单副本有效；PENDING 与 RESPONDED /
 * RUNNING 与 COMPLETED 可能落在不同副本或进程重启之后，必须回 DB 找旧行，而不是新插一行。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TimelineRefQuery implements Serializable {

    private Long runId;

    /** TimelineKind code: clarification / tool / tool_approval / run_status / compaction / assistant。 */
    private String kind;

    /** CLARIFICATION 行使用。 */
    private Long refClarificationId;

    /** TOOL 行使用。 */
    private Long refToolCallId;

    /** TOOL_APPROVAL 行使用。 */
    private Long refApprovalId;

    /** COMPACTION 行使用。 */
    private Long refCompactId;
}
