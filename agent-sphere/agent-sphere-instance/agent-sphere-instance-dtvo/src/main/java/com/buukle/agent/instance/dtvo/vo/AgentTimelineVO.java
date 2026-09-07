package com.buukle.agent.instance.dtvo.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.Map;

/** 打平 Timeline 行（读取侧组装：行元数据 + 已从 ref 解析好的正文）。 */
@Data
public class AgentTimelineVO implements Serializable {
    private Long seq;
    private Long runId;
    private String kind;
    private String subtype;
    private String state;
    private Long groupId;
    /** 快照标签（无正文）。 */
    private String title;
    private Long refRunId;
    private Long refInteractionId;
    private Long refToolCallId;
    private Long refSubAgentRunId;
    private Long refClarificationId;
    /** kind 专属正文（user/assistant/tool/clarification/subagent/run_status），加载时从 ref 解析。 */
    private Map<String, Object> content;
}