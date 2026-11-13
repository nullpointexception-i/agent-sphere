import { request } from '@umijs/max';

import { API_BASE } from '@/constants/api';

const BASE = API_BASE;

export interface ProjectVO {
  id: number;
  projectKey: string;
  name: string;
  description?: string;
  ownerUserId?: number;
  ownerName?: string;
  status?: string;
  startDate?: string;
  endDate?: string;
  requirementSeq?: number;
  createdAt?: string;
  updatedAt?: string;
}

export interface ProjectMemberVO {
  id: number;
  projectId: number;
  userId: number;
  displayName?: string;
  memberRole: string;
  joinedAt?: string;
}

export interface RequirementVO {
  id: number;
  projectId: number;
  projectName?: string;
  requirementKey: string;
  title: string;
  description?: string;
  status: string;
  priority?: number;
  ownerUserId?: number;
  ownerName?: string;
  assigneeUserId?: number;
  assigneeName?: string;
  startDate?: string;
  dueDate?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface RequirementAttachmentVO {
  fileKey: string;
  fileName?: string;
  contentType?: string;
  sizeBytes?: number;
}

export interface RequirementCommentVO {
  id: number;
  requirementId: number;
  userId: number;
  userName?: string;
  content: string;
  attachments?: RequirementAttachmentVO[];
  createdAt?: string;
}

export interface RequirementActivityVO {
  id: number;
  requirementId: number;
  actorUserId?: number;
  activityType: string;
  fromValue?: string;
  toValue?: string;
  createdAt?: string;
}

export interface ProjectAssistantSessionVO {
  sessionId: number;
  canonicalSessionId: number;
  threadId: number;
  instanceId: number;
  projectId: number;
}

export interface ProjectInsightVO {
  id: number;
  projectId: number;
  requirementId?: number;
  requirementKey?: string;
  insightType: string;
  severity: string;
  title?: string;
  content: string;
  source?: string;
  sourceRunId?: number;
  createdBy?: string;
  createdAt?: string;
}

export interface ProjectAssistantInboxVO {
  clarificationId: string;
  runId: number;
  sessionId: number;
  projectId: number;
  projectName?: string;
  requirementId?: number;
  requirementKey?: string;
  title: string;
  type: string;
  multiple?: boolean;
  options?: string;
  description?: string;
  requesterUserId?: number;
  requesterName?: string;
  expiresAt?: string;
  createdAt?: string;
}

export interface ProjectAssistantAvatarVO {
  agentId: number;
  name: string;
  ownerUserId?: number;
  ownerName?: string;
  publicVisible?: boolean;
  ownedByRequester?: boolean;
  status?: string;
}

export interface ProjectAssistantSettingsVO {
  projectId: number;
  autoAnalyze?: boolean;
}

export interface ProjectContextEventVO {
  id: number;
  projectId: number;
  requirementId?: number;
  eventType: string;
  summary: string;
  sourceSessionId?: number;
  canonicalSessionId?: number;
  actorUserId?: number;
  createdAt?: string;
}

export interface PageResult<T> {
  records: T[];
  total: number;
  page: number;
  pageSize: number;
}

export interface CreateProjectInput {
  name: string;
  description?: string;
  startDate?: string;
  endDate?: string;
}

export interface CreateRequirementInput {
  title: string;
  description?: string;
  priority?: number;
  assigneeUserId?: number;
  startDate?: string;
  dueDate?: string;
}

export const projectApi = {
  page(params: { keyword?: string; page?: number; pageSize?: number } = {}) {
    return request<PageResult<ProjectVO>>(`${BASE}/project/projects`, {
      method: 'GET',
      params,
    });
  },
  detail(id: number) {
    return request<ProjectVO>(`${BASE}/project/projects/${id}`, {
      method: 'GET',
    });
  },
  create(data: CreateProjectInput) {
    return request<number>(`${BASE}/project/projects`, {
      method: 'POST',
      data,
    });
  },
  update(id: number, data: Partial<CreateProjectInput>) {
    return request<void>(`${BASE}/project/projects/${id}`, {
      method: 'PUT',
      data,
    });
  },
  archive(id: number) {
    return request<void>(`${BASE}/project/projects/${id}/archive`, {
      method: 'POST',
    });
  },
  remove(id: number) {
    return request<void>(`${BASE}/project/projects/${id}`, {
      method: 'DELETE',
    });
  },
  members(id: number) {
    return request<ProjectMemberVO[]>(
      `${BASE}/project/projects/${id}/members`,
      { method: 'GET' },
    );
  },
  addMember(id: number, userId: number, memberRole?: string) {
    return request<void>(`${BASE}/project/projects/${id}/members`, {
      method: 'POST',
      data: { userId, memberRole },
    });
  },
  removeMember(id: number, userId: number) {
    return request<void>(
      `${BASE}/project/projects/${id}/members/${userId}`,
      { method: 'DELETE' },
    );
  },
  updateRequirement(
    id: number,
    data: {
      title?: string;
      description?: string;
      priority?: number;
      assigneeUserId?: number;
      startDate?: string;
      dueDate?: string;
    },
  ) {
    return request<void>(`${BASE}/project/requirements/${id}`, {
      method: 'PUT',
      data,
    });
  },
  requirements(
    projectId: number,
    params: { status?: string; page?: number; pageSize?: number } = {},
  ) {
    return request<PageResult<RequirementVO>>(
      `${BASE}/project/projects/${projectId}/requirements`,
      { method: 'GET', params },
    );
  },
  requirementPage(
    params: {
      projectId?: number;
      status?: string;
      keyword?: string;
      page?: number;
      pageSize?: number;
    } = {},
  ) {
    return request<PageResult<RequirementVO>>(`${BASE}/project/requirements`, {
      method: 'GET',
      params,
    });
  },
  createRequirement(projectId: number, data: CreateRequirementInput) {
    return request<number>(
      `${BASE}/project/projects/${projectId}/requirements`,
      { method: 'POST', data },
    );
  },
  requirement(id: number) {
    return request<RequirementVO>(`${BASE}/project/requirements/${id}`, {
      method: 'GET',
    });
  },
  changeRequirementStatus(id: number, status: string) {
    return request<void>(`${BASE}/project/requirements/${id}/status`, {
      method: 'PATCH',
      data: { status },
    });
  },
  requirementActivities(id: number) {
    return request<RequirementActivityVO[]>(
      `${BASE}/project/requirements/${id}/activities`,
      { method: 'GET' },
    );
  },
  requirementComments(id: number) {
    return request<RequirementCommentVO[]>(
      `${BASE}/project/requirements/${id}/comments`,
      { method: 'GET' },
    );
  },
  addRequirementComment(
    id: number,
    content: string,
    attachments?: RequirementAttachmentVO[],
  ) {
    return request<RequirementCommentVO>(
      `${BASE}/project/requirements/${id}/comments`,
      { method: 'POST', data: { content, attachments } },
    );
  },
};

export const projectAssistantApi = {
  session(projectId: number) {
    return request<ProjectAssistantSessionVO>(
      `${BASE}/project/projects/${projectId}/assistant/session`,
      { method: 'POST' },
    );
  },
  inbox() {
    return request<ProjectAssistantInboxVO[]>(
      `${BASE}/project/assistant/inbox`,
      { method: 'GET' },
    );
  },
  answer(clarificationId: string, response: string) {
    return request<void>(
      `${BASE}/project/assistant/clarifications/${clarificationId}/answer`,
      { method: 'POST', data: { response } },
    );
  },
  settings(projectId: number) {
    return request<ProjectAssistantSettingsVO>(
      `${BASE}/project/projects/${projectId}/assistant/settings`,
      { method: 'GET' },
    );
  },
  updateSettings(projectId: number, autoAnalyze: boolean) {
    return request<void>(
      `${BASE}/project/projects/${projectId}/assistant/settings`,
      { method: 'PUT', data: { autoAnalyze } },
    );
  },
  avatars(projectId: number) {
    return request<ProjectAssistantAvatarVO[]>(
      `${BASE}/project/projects/${projectId}/assistant/avatars`,
      { method: 'GET' },
    );
  },
  analyze(projectId: number, requirementId?: number) {
    return request<{ sessionId: number; runId: number }>(
      `${BASE}/project/projects/${projectId}/assistant/analyze`,
      { method: 'POST', params: { requirementId } },
    );
  },
  insights(projectId: number, requirementId?: number, limit = 20) {
    return request<ProjectInsightVO[]>(
      `${BASE}/project/projects/${projectId}/insights`,
      { method: 'GET', params: { requirementId, limit } },
    );
  },
  context(projectId: number, limit = 30) {
    return request<ProjectContextEventVO[]>(
      `${BASE}/project/projects/${projectId}/assistant/context`,
      { method: 'GET', params: { limit } },
    );
  },
};
