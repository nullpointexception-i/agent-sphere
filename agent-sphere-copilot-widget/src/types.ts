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

/** token 用量（tooltip / 会话吸底条共用）。 */
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
   * 业务域（`agent_instance.business_type`）。非空表示这是**任务专用实例** ——
   * 外部 API 按 businessType 匹配它来跑目标驱动任务，因此不允许在 widget 里手动发起对话。
   */
  businessType?: string | null;
  /**
   * 是否允许从 widget 端发起/继续会话（`agent_instance.allow_widget_session`）。
   * 后端列 `NOT NULL DEFAULT FALSE` —— 缺省/空值按禁止处理。
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
  /** 工具结果中的浏览器截图引用（[{fileKey, contentType}]），子 Agent 卡片回显用。 */
  images?: { fileKey: string; contentType?: string }[];
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
    /** 子 Agent 类型（统一 delegate 后为 AGENT；历史数据可能为 SKILL）。 */
    agentType?: string;
    /** 子 Agent 引用/标识（delegate 的 agentRef，形如 instance:<id>）。 */
    agentRef?: string;
    args?: string;
    artifact?: string;
    /** 后端 ToolMetaResolver 下发的标准化展示字段。 */
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
    /** 用户消息附图（[{fileKey, contentType}]，按 fileKey 拉字节回显）。 */
    images?: { fileKey: string; contentType?: string }[];
    /** 用量（assistant / run_status 行）。 */
    usage?: {
      promptTokens?: number | null;
      completionTokens?: number | null;
      totalTokens?: number | null;
      cacheHitTokens?: number | null;
      cacheMissTokens?: number | null;
    } | null;
    /** 同批次并行子 Agent 共享的分组键（工具调用 id），用于并行 Tab 分组。 */
    parentToolCallId?: string | null;
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
