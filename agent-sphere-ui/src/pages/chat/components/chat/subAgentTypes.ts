/** 子 Agent 运行卡片（agent_sub_agent_run 的视图）。 */
export interface SubAgentRun {
  id: number;
  sessionId: number;
  runId?: number | null;
  parentToolCallId?: string | null;
  agentType?: string;
  agentRef?: string;
  displayName: string;
  status?: string;
  startedAt?: string;
  finishedAt?: string;
  createdAt?: string;
}

/** 时间线条目（llm_interaction 或 tool_call）。 */
export interface SubAgentTimelineItem {
  activityType: 'llm_interaction' | 'tool_call';
  createdAt?: string;
  interactionId?: number;
  interactionType?: string;
  modelName?: string;
  reasoning?: string | null;
  reply?: string | null;
  success?: boolean;
  stepId?: number;
  toolName?: string;
  displayNameCn?: string;
  displayNameEn?: string;
  argumentsJson?: string | null;
  artifact?: string | null;
  toolStatus?: string;
  toolErrorMessage?: string;
}

/** 实时聚合的单个子 Agent 当前状态（SSE 流式更新）。 */
export interface SubAgentLive {
  key: string;
  parentToolCallId?: string;
  agentType?: string;
  agentRef?: string;
  /** 后端 agent_sub_agent_run 主键（同一 skill 执行全程一致），用于身份与 live/历史去重。 */
  subAgentRunId?: number | null;
  name: string;
  status?: string;
  reasoning: string;
  reply: string;
  toolCalls: {
    callId: string;
    toolName: string;
    args?: string;
    artifact?: string;
    status?: string;
  }[];
}
