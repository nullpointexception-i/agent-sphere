import { request } from '@umijs/max';

import { API_BASE } from '@/constants/api';

const BASE = API_BASE;

export interface ApplicationVO {
  id: number;
  appKey: string;
  name?: string;
  nameKey?: string;
  description?: string;
  descriptionKey?: string;
  defaultLocale?: string;
  icon?: string;
  appType: 'SYSTEM' | 'CUSTOM' | string;
  entryMode?: string;
  entryKey?: string;
  visibility?: string;
  ownerUserId?: number;
  sortOrder?: number;
  status?: string;
  createdAt?: string;
}

export interface ApplicationSceneVO {
  id: number;
  applicationId: number;
  sceneKey: string;
  name?: string;
  nameKey?: string;
  description?: string;
  descriptionKey?: string;
  sceneType: 'QA' | 'PERIODIC' | 'PAGE' | 'ACTION' | string;
  config?: string;
  welcome?: string;
  suggestions?: string[];
  contextScopes?: string[];
  sortOrder?: number;
  status?: string;
}

export interface ApplicationMemberVO {
  id: number;
  applicationId: number;
  userId: number;
  displayName?: string;
  memberRole?: string;
  createdAt?: string;
}

export interface ApplicationPage {
  records: ApplicationVO[];
  total: number;
  page: number;
  pageSize: number;
}

export interface ApplicationRunVO {
  id: number;
  applicationId: number;
  sceneId: number;
  triggerType: string;
  runMode?: string;
  outputLocale?: string;
  status: string;
  attempt?: number;
  workItemId?: number;
  errorMessage?: string;
  createdAt?: string;
  startedAt?: string;
  finishedAt?: string;
}

export interface CreateApplicationInput {
  appKey: string;
  name: string;
  description?: string;
  icon?: string;
  entryMode?: string;
  entryKey?: string;
  visibility?: string;
}

export const applicationApi = {
  page(params: { keyword?: string; page?: number; pageSize?: number } = {}) {
    return request<ApplicationPage>(`${BASE}/application/apps`, {
      method: 'GET',
      params,
    });
  },
  detail(id: number) {
    return request<ApplicationVO>(`${BASE}/application/apps/${id}`, {
      method: 'GET',
    });
  },
  scenes(id: number) {
    return request<ApplicationSceneVO[]>(
      `${BASE}/application/apps/${id}/scenes`,
      { method: 'GET' },
    );
  },
  create(data: CreateApplicationInput) {
    return request<number>(`${BASE}/application/apps`, {
      method: 'POST',
      data,
    });
  },
  disable(id: number) {
    return request<void>(`${BASE}/application/apps/${id}`, {
      method: 'DELETE',
    });
  },
  members(id: number) {
    return request<ApplicationMemberVO[]>(
      `${BASE}/application/apps/${id}/members`,
      { method: 'GET' },
    );
  },
  addMember(id: number, userId: number, memberRole?: string) {
    return request<void>(`${BASE}/application/apps/${id}/members`, {
      method: 'POST',
      data: { userId, memberRole },
    });
  },
  removeMember(id: number, userId: number) {
    return request<void>(
      `${BASE}/application/apps/${id}/members/${userId}`,
      { method: 'DELETE' },
    );
  },
  triggerRun(
    sceneId: number,
    data: {
      triggerType: string;
      triggerKey?: string;
      clientRequestId?: string;
      runMode?: string;
      input?: Record<string, unknown>;
    },
  ) {
    return request<ApplicationRunVO>(
      `${BASE}/application/scenes/${sceneId}/runs`,
      { method: 'POST', data },
    );
  },
  runs(params: {
    applicationId?: number;
    sceneId?: number;
    page?: number;
    pageSize?: number;
  }) {
    return request<{
      records: ApplicationRunVO[];
      total: number;
      page: number;
      pageSize: number;
    }>(`${BASE}/application/runs`, { params });
  },
  resumeRun(id: number) {
    return request<ApplicationRunVO>(`${BASE}/application/runs/${id}/resume`, {
      method: 'POST',
    });
  },
  cancelRun(id: number) {
    return request<void>(`${BASE}/application/runs/${id}/cancel`, {
      method: 'POST',
    });
  },
};
