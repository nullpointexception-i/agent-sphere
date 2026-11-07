/** Sub-agent run card (a view of agent_sub_agent_run). */
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

/** Timeline entry (llm_interaction or tool_call). */
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
  /** Browser screenshot references in the tool result ([{fileKey, contentType}]), rendered by the sub-agent card. */
  images?: { fileKey: string; contentType?: string }[];
}

/** Current state of a single live-aggregated sub-agent (updated by SSE streaming). */
export interface SubAgentLive {
  key: string;
  parentToolCallId?: string;
  agentType?: string;
  agentRef?: string;
  /** Backend agent_sub_agent_run primary key (stable for the whole sub-agent execution), used for identity and live/history dedup. */
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

/** Purely SSE-driven sub-agent live steps (aggregated by subAgentRunId; terminal state corrected by a one-off history fetch). */
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
      /** Browser screenshot references in the tool result ([{fileKey, contentType}]), shown by live steps. */
      images?: { fileKey: string; contentType?: string }[];
    };

export type SubAgentLiveMap = Record<number, SubAgentLiveStep[]>;
