import { request } from '@umijs/max';

import { API_BASE } from '@/constants/api';

const BASE = API_BASE;

export interface SuperAgent {
  id: number;
  ownerUserId?: number;
  agentKey?: string;
  name: string;
  description?: string;
  publicVisible?: boolean;
  routerInstanceId?: number;
  workerInstanceId?: number;
  positionCode?: string;
  positionNameCn?: string;
  positionNameEn?: string;
  config?: string;
  status: string;
  createdAt?: string;
}

export interface SuperAgentInstances {
  superAgentId: number;
  routerInstance?: {
    id: number;
    name: string;
    instanceRole?: string;
    status?: string;
    modelRouteId?: number;
    createdBy?: string;
  };
  workerInstance?: {
    id: number;
    name: string;
    instanceRole?: string;
    status?: string;
    modelRouteId?: number;
    createdBy?: string;
  };
}

export interface PublicSuperAgent {
  id: number;
  ownerUserId: number;
  name: string;
  description?: string;
  positionCode?: string;
  positionNameCn?: string;
  positionNameEn?: string;
  status: string;
}

export interface PendingApproval {
  approvalId: string;
  sessionId: number;
  runId: number;
  sessionType?: string;
  superAgentId?: number;
  agentName?: string;
  workItemId?: number;
  sessionTitle?: string;
  toolCallsJson?: string;
  createdAt?: string;
}

export interface ApprovalDecision {
  callId: string;
  decision: string;
}

export interface TrainingSession {
  sessionId: number;
  superAgentId: number;
  workerInstanceId: number;
  status?: string;
}

export interface TrainingChatResult {
  runId?: number;
  status?: string;
}

export interface WorkItem {
  id: number;
  superAgentId: number;
  threadId?: number;
  workerSessionId?: number;
  triggerEventId?: number;
  title: string;
  goal?: string;
  priority?: number;
  status: string;
  summary?: string;
  lastProgressAt?: string;
  createdAt?: string;
}

export const superAgentApi = {
  list: (limit?: number) =>
    request<SuperAgent[]>(`${BASE}/super-agents`, {
      params: limit ? { limit } : undefined,
    }),
  get: (id: number) => request<SuperAgent>(`${BASE}/super-agents/${id}`),
  instances: (id: number) =>
    request<SuperAgentInstances>(`${BASE}/super-agents/${id}/instances`),
  create: (data: {
    name: string;
    description?: string;
    positionCode: string;
    config?: string;
  }) =>
    request<SuperAgent>(`${BASE}/super-agents`, {
      method: 'POST',
      data,
    }),
  pause: (id: number) =>
    request<SuperAgent>(`${BASE}/super-agents/${id}/pause`, { method: 'POST' }),
  resume: (id: number) =>
    request<SuperAgent>(`${BASE}/super-agents/${id}/resume`, { method: 'POST' }),
  delete: (id: number) =>
    request<void>(`${BASE}/super-agents/${id}`, { method: 'DELETE' }),
  workItems: (id: number, status?: string) =>
    request<WorkItem[]>(`${BASE}/super-agents/${id}/work-items`, {
      params: status ? { status } : undefined,
    }),
  retryWorkItem: (agentId: number, itemId: number) =>
    request<WorkItem>(`${BASE}/super-agents/${agentId}/work-items/${itemId}/retry`, {
      method: 'POST',
    }),
  cancelWorkItem: (agentId: number, itemId: number) =>
    request<WorkItem>(`${BASE}/super-agents/${agentId}/work-items/${itemId}/cancel`, {
      method: 'POST',
    }),
  workerTimeline: (agentId: number, itemId: number, limit = 100) =>
    request<{ rows: any[]; hasMore?: boolean }>(
      `${BASE}/super-agents/${agentId}/work-items/${itemId}/timeline`,
      { params: { limit } },
    ),
  update: (
    id: number,
    data: {
      name: string;
      description?: string;
      positionCode?: string;
      systemPrompt?: string;
      config?: string;
    },
  ) =>
    request<SuperAgent>(`${BASE}/super-agents/${id}`, {
      method: 'PATCH',
      data,
    }),
  updateModelRoute: (id: number, modelRouteId: number) =>
    request<SuperAgent>(`${BASE}/super-agents/${id}/model-route`, {
      method: 'PATCH',
      data: { modelRouteId },
    }),
  usage: (agentId: number, days = 30) =>
    request<any[]>(`${BASE}/super-agents/${agentId}/usage`, {
      params: { days },
    }),
  watchdogRuns: (agentId: number) =>
    request<any[]>(`${BASE}/super-agents/${agentId}/watchdog-runs`),
  events: (agentId: number) =>
    request<any[]>(`${BASE}/super-agents/${agentId}/events`),
  threads: (agentId: number) =>
    request<any[]>(`${BASE}/super-agents/${agentId}/threads`),
  retryEvent: (agentId: number, eventId: number) =>
    request<void>(`${BASE}/super-agents/${agentId}/events/${eventId}/retry`, {
      method: 'POST',
    }),
  groupRouterTemplate: () =>
    request<{ instanceId?: number; template: string; defaultTemplate: string; custom: boolean }>(
      `${BASE}/super-agents/group-router/template`,
    ),
  updateGroupRouterTemplate: (template: string) =>
    request<any>(`${BASE}/super-agents/group-router/template`, {
      method: 'PATCH',
      data: { template },
    }),
  channels: (agentId: number) =>
    request<any[]>(`${BASE}/super-agents/${agentId}/channels`),
  updateChannelStatus: (agentId: number, channelId: number, status: string) =>
    request<any>(
      `${BASE}/super-agents/${agentId}/channels/${channelId}/status?status=${status}`,
      { method: 'PATCH' },
    ),
  updateVisibility: (agentId: number, publicVisible: boolean) =>
    request<SuperAgent>(`${BASE}/super-agents/${agentId}/visibility`, {
      method: 'PATCH',
      data: { publicVisible },
    }),
  publicAvatars: (ownerUserIds?: number[]) =>
    request<PublicSuperAgent[]>(`${BASE}/super-agents/public`, {
      params:
        ownerUserIds && ownerUserIds.length > 0
          ? { ownerUserIds: ownerUserIds.join(',') }
          : undefined,
    }),
  pendingApprovals: () =>
    request<PendingApproval[]>(`${BASE}/super-agents/approvals/pending`),
  resolveApproval: (approvalId: string, decisions: ApprovalDecision[]) =>
    request<void>(`${BASE}/super-agents/approvals/${approvalId}/resolve`, {
      method: 'POST',
      data: { decisions },
    }),
  trainingSession: (agentId: number) =>
    request<TrainingSession>(
      `${BASE}/super-agents/${agentId}/training-sessions`,
      { method: 'POST' },
    ),
  sendTrainingMessage: (
    agentId: number,
    sessionId: number,
    data: { message: string; runMode?: string; modelRouteId?: number },
  ) =>
    request<TrainingChatResult>(
      `${BASE}/super-agents/${agentId}/training-sessions/${sessionId}/messages`,
      { method: 'POST', data },
    ),
  resetTrainingSession: (agentId: number, sessionId: number) =>
    request<void>(
      `${BASE}/super-agents/${agentId}/training-sessions/${sessionId}/reset`,
      { method: 'POST' },
    ),
};
