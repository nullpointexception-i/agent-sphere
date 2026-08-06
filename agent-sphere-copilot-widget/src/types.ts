export interface UserVO {
  id: number;
  username: string;
  displayName: string;
  email: string;
  avatar: string;
  token: string;
  status: string;
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
