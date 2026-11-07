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

/** Third-party identity returned by GET /sso/me (subject is displaySubject). */
export interface SsoIdentityVO {
  providerCode?: string;
  subject?: string;
}

/** Token usage (shared by tooltip / session sticky footer). */
export interface UsageData {
  promptTokens?: number | null;
  completionTokens?: number | null;
  totalTokens?: number | null;
  cacheHitTokens?: number | null;
  cacheMissTokens?: number | null;
}

export interface ToolApprovalCall {
  callId: string;
  toolName: string;
  displayName?: string;
  action?: string;
  arguments?: string;
  approvalScope?: 'ONCE' | 'RUN' | 'SESSION';
}

export interface ToolApprovalBatch {
  approvalId: string;
  runId: number;
  calls: ToolApprovalCall[];
}

export interface ClarificationItem {
  clarificationId: string;
  runId: number;
  title?: string;
  type?: string;
  multiple?: boolean;
  options?: string;
  description?: string;
}

export interface ClarificationBatch {
  runId: number;
  items: ClarificationItem[];
}

export interface InstanceVO {
  id: number;
  name: string;
  description: string;
  systemPrompt: string;
  image: string;
  status: string;
  /**
   * Business domain (`agent_instance.business_type`). Non-empty means a **task-dedicated instance** -
   * the external API matches it by businessType to run goal-driven tasks, so conversations cannot be started manually in the widget.
   */
  businessType?: string | null;
  /**
   * Whether starting/continuing a session from the widget is allowed (`agent_instance.allow_widget_session`).
   * The backend column is `NOT NULL DEFAULT FALSE` - missing/null counts as denied.
   */
  allowWidgetSession?: boolean | null;
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
  /** Browser screenshot references in the tool result ([{fileKey, contentType}]), replayed by the sub-agent card. */
  images?: { fileKey: string; contentType?: string }[];
}

/** One row of GET /instance/sessions/{sid}/timeline (AgentTimelineVO, content already resolved by the backend). */
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
    /** Sub-agent type (AGENT after the unified delegate refactor; historical data may be SKILL). */
    agentType?: string;
    /** Sub-agent reference/identifier (the delegate's agentRef, shaped like instance:<id>). */
    agentRef?: string;
    args?: string;
    artifact?: string;
    /** Normalized display fields delivered by the backend ToolMetaResolver. */
    meta?: Record<string, any>;
    clarificationId?: string;
    approvalId?: string;
    decisions?: string;
    toolCalls?: string;
    options?: string;
    response?: string;
    state?: string;
    startedAt?: string;
    durationMs?: number;
    modelName?: string;
    /** Images attached to a user message ([{fileKey, contentType}], bytes fetched by fileKey for replay). */
    images?: { fileKey: string; contentType?: string }[];
    /** Usage (assistant / run_status rows). */
    usage?: {
      promptTokens?: number | null;
      completionTokens?: number | null;
      totalTokens?: number | null;
      cacheHitTokens?: number | null;
      cacheMissTokens?: number | null;
    } | null;
    /** Group key shared by parallel sub-agents in the same batch (tool call id), used to group them into parallel tabs. */
    parentToolCallId?: string | null;
    [key: string]: unknown;
  };
}

/** Pagination response for GET /instance/sessions/{sid}/timeline. */
export interface SessionTimelinePageVO {
  rows: TimelineRow[];
  hasMore: boolean;
  oldestSeq: number | null;
  newestSeq: number | null;
}
