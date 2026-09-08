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
  /** 工具结果中的浏览器截图引用（[{fileKey, contentType}]），子 Agent 卡片回显用。 */
  images?: { fileKey: string; contentType?: string }[];
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

/** 纯 SSE 驱动的子 Agent 实时步骤（按 subAgentRunId 聚合；终态由一次性历史拉取校正）。 */
export type SubAgentLiveStep =
  | {
      type: 'llm';
      reasoning: string;
      reply: string;
      running: boolean;
    }
  | {
      type: 'tool_call';
      publishId: string;
      toolName?: string;
      displayNameCn?: string;
      displayNameEn?: string;
      status: 'pending' | 'in_progress' | 'succeeded' | 'failed';
      argumentsJson?: string;
      artifact?: string;
      /** 工具结果中的浏览器截图引用（[{fileKey, contentType}]），live 步骤回显用。 */
      images?: { fileKey: string; contentType?: string }[];
    };

export type SubAgentLiveMap = Record<number, SubAgentLiveStep[]>;
