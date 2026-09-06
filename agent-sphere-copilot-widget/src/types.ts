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

export interface ClarificationVO {
  clarificationId: string;
  runId: number;
  sessionId: number;
  messageId: number;
  title: string;
  type: string;
  options: string;
  userResponse: string;
  status: string;
}

export interface RunVO {
  id: number;
  sessionId: number;
  userMessage: string;
  assistantReply: string;
  reasoning?: string;
  createdAt: string;
  clarificationResponse?: boolean;
  clarifications?: ClarificationVO[];
}

export interface SessionTodoVO {
  id: number;
  sessionId: number;
  runId: number;
  content: string;
  status: string;
  priority: string;
  sortOrder: number;
}

export interface SubAgentRunVO {
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
