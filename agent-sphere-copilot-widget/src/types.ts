export interface UserVO {
  id: number;
  username: string;
  displayName: string;
  englishName?: string;
  email: string;
  avatar: string;
  token: string;
  status: string;
  ssoProviderCode?: string;
  ssoSubject?: string;
}

/** GET /sso/me 返回的第三方身份（subject 为 displaySubject）。 */
export interface SsoIdentityVO {
  providerCode?: string;
  subject?: string;
}

export interface InstanceVO {
  id: number;
  name: string;
  description: string;
  systemPrompt: string;
  image: string;
  status: string;
  createdAt: string;
  createdBy: string;
  updatedAt: string;
}

export interface InstancePageVO {
  records: InstanceVO[];
  total: number;
  current: number;
  pages: number;
}

export interface SessionVO {
  id: number;
  title: string;
  agentInstanceId: number;
  status: string;
  createdAt: string;
  updatedAt: string;
}

export interface SubAgentTimelineItemVO {
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

/** GET /instance/sessions/{sid}/timeline 单行（AgentTimelineVO，content 已在后端 resolve）。 */
export interface TimelineRow {
  seq: number;
  runId?: number | null;
  kind: string;
  subtype?: string | null;
  state?: string | null;
  groupId?: number | null;
  title?: string | null;
  refRunId?: number | null;
  refInteractionId?: number | null;
  refToolCallId?: number | null;
  refSubAgentRunId?: number | null;
  refClarificationId?: number | null;
  content: {
    text?: string;
    thinking?: string;
    reply?: string;
    displayName?: string;
    status?: string;
    args?: string;
    artifact?: string;
    clarificationId?: string;
    options?: string;
    response?: string;
    state?: string;
    startedAt?: string;
    durationMs?: number;
    modelName?: string;
    [key: string]: unknown;
  };
}

/** GET /instance/sessions/{sid}/timeline 分页响应。 */
export interface SessionTimelinePageVO {
  rows: TimelineRow[];
  hasMore: boolean;
  oldestSeq: number | null;
  newestSeq: number | null;
}
