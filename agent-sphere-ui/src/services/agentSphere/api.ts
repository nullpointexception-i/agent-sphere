import { request } from '@umijs/max';

const BASE = '/api/v1';

export const agentApi = {
  sso: {
    providers: () =>
      request<{ code: string; name: string }[]>(`${BASE}/auth/sso/providers`),
  },
  instances: {
    listAll: () =>
      request<any[]>(`${BASE}/instance/instances/all`),
    listLatest: (n: number) =>
      request<any[]>(`${BASE}/instance/instances/latest/${n}`),
    list: (params: { keyword?: string; startTime?: string; endTime?: string; page?: number; size?: number }) =>
      request<any>(`${BASE}/instance/instances`, {
        params,
      }),
    get: (id: number) => request<any>(`${BASE}/instance/instances/${id}`),
    create: (data: any) =>
      request<any>(`${BASE}/instance/instances`, {
        method: 'POST',
        data,
      }),
    update: (id: number, data: any) =>
      request<any>(`${BASE}/instance/instances/${id}`, {
        method: 'PUT',
        data,
      }),
    delete: (id: number) =>
      request<void>(`${BASE}/instance/instances/${id}`, {
        method: 'DELETE',
      }),
    batchDelete: (ids: number[]) =>
      request<void>(`${BASE}/instance/instances/batch`, {
        method: 'DELETE',
        data: ids,
      }),
    setModelRoute: (id: number, modelRouteId: number | null) =>
      request<any>(`${BASE}/instance/instances/${id}/model-route`, {
        method: 'PUT',
        data: { modelRouteId },
      }),
  },

  sessions: {
    create: (data: any) =>
      request<any>(`${BASE}/instance/sessions`, {
        method: 'POST',
        data,
      }),
    get: (id: number) => request<any>(`${BASE}/instance/sessions/${id}`),
    list: (offset = 0, limit = 20, keyword?: string) =>
      request<any[]>(`${BASE}/instance/sessions`, {
        params: { offset, limit, keyword },
      }),
    update: (id: number, title: string) =>
      request<any>(`${BASE}/instance/sessions/${id}`, {
        method: 'PUT',
        data: { title },
      }),
    updateSummary: (id: number, summary: string) =>
      request<void>(`${BASE}/instance/sessions/${id}/summary`, {
        method: 'PUT',
        data: { summary },
      }),
    messageHistory: (id: number, direction: 'prev' | 'next', runId?: number) =>
      request<{ runId: number; userMessage: string; hasMore: boolean }>(
        `${BASE}/instance/sessions/${id}/history/messages?direction=${direction}${runId != null ? `&runId=${runId}` : ''}`,
      ),
    close: (id: number) =>
      request<void>(`${BASE}/instance/sessions/${id}`, {
        method: 'DELETE',
      }),
    batchClose: (ids: number[]) =>
      request<void>(`${BASE}/instance/sessions/batch`, {
        method: 'DELETE',
        data: ids,
      }),
    chat: (id: number, message: string, modelRouteId?: number) =>
      request<{ runId: number; status: string; assistantReply?: string }>(
        `${BASE}/runtime/${id}/chat`,
        { method: 'POST', data: { message, modelRouteId } },
      ),
    getTodos: (id: number) =>
      request<any>(`${BASE}/instance/sessions/${id}/todos`),
    getLatestToolCalls: (id: number) =>
      request<any>(`${BASE}/instance/sessions/${id}/toolcalls/latest`),
    getDocuments: (id: number, page = 1, size = 20) =>
      request<any>(`${BASE}/artifacts/documents?sessionId=${id}&page=${page}&size=${size}`),
    clarify: (sessionId: number, runId: number, response: string, clarificationId?: string) =>
      request<any>(`${BASE}/runtime/${sessionId}/run/${runId}/clarify`, {
        method: 'POST', data: { response, clarificationId },
      }),
  },

  runs: {
    create: (data: any) =>
      request<any>(`${BASE}/instance/runs`, { method: 'POST', data }),
    get: (id: number) => request<any>(`${BASE}/instance/runs/${id}`),
    listBySession: (sessionId: number, page = 1, size = 10, keyword?: string) =>
      request<any>(
        `${BASE}/instance/runs?sessionId=${sessionId}&page=${page}&size=${size}${keyword ? `&keyword=${encodeURIComponent(keyword)}` : ''}`,
      ),
    stop: (sessionId: number, runId: number) =>
      request<void>(`${BASE}/runtime/${sessionId}/run/${runId}/stop`, { method: 'POST' }),
  },

  interactions: {
    listByRun: (runId: number, offset = 0, limit = 20) =>
      request<any[]>(`${BASE}/instance/interactions?runId=${runId}&offset=${offset}&limit=${limit}`),
    get: (id: number) =>
      request<any>(`${BASE}/instance/interactions/${id}`),
  },

  activities: {
    listByRun: (runId: number, sessionId: number, offset = 0, limit = 20) =>
      request<{ total: number; records: any[] }>(
        `${BASE}/instance/runs/${runId}/activities?sessionId=${sessionId}&offset=${offset}&limit=${limit}`,
      ),
  },

  modelProviders: {
    count: () =>
      request<number>(`${BASE}/model/providers/count`),
    list: (keyword?: string) =>
      request<any[]>(`${BASE}/model/providers`, {
        params: { keyword },
      }),
    get: (id: number) => request<any>(`${BASE}/model/providers/${id}`),
    create: (data: any) =>
      request<any>(`${BASE}/model/providers`, { method: 'POST', data }),
    update: (id: number, data: any) =>
      request<any>(`${BASE}/model/providers/${id}`, { method: 'PUT', data }),
     delete: (id: number) =>
      request<void>(`${BASE}/model/providers/${id}`, { method: 'DELETE' }),
      setActiveKey: (id: number, apiKeyId: number | null) =>
       request<void>(`${BASE}/model/providers/${id}/active-key`, { method: 'PUT', data: { apiKeyId } }),
      getCompanies: () =>
       request<any[]>(`${BASE}/model/providers/companies`),
  },

  apiKeys: {
    listByProvider: (providerId: number, keyword?: string) =>
      request<any[]>(`${BASE}/model/api-keys`, {
        params: { providerId, keyword },
      }),
    get: (id: number) => request<any>(`${BASE}/model/api-keys/${id}`),
    create: (data: any) =>
      request<any>(`${BASE}/model/api-keys`, { method: 'POST', data }),
    update: (id: number, data: any) =>
      request<any>(`${BASE}/model/api-keys/${id}`, { method: 'PUT', data }),
    delete: (id: number) =>
      request<void>(`${BASE}/model/api-keys/${id}`, { method: 'DELETE' }),
  },

  routes: {
    listByProvider: (providerId?: number, keyword?: string) =>
      request<any[]>(`${BASE}/model/routes`, {
        params: { providerId: providerId ?? '', keyword },
      }),
    listAll: (keyword?: string) =>
      request<any[]>(`${BASE}/model/routes`, {
        params: { keyword },
      }),
    get: (id: number) => request<any>(`${BASE}/model/routes/${id}`),
    create: (data: any) =>
      request<any>(`${BASE}/model/routes`, { method: 'POST', data }),
    update: (id: number, data: any) =>
      request<any>(`${BASE}/model/routes/${id}`, { method: 'PUT', data }),
    delete: (id: number) =>
      request<void>(`${BASE}/model/routes/${id}`, { method: 'DELETE' }),
  },

  mcp: {
    list: (params: { keyword?: string; startTime?: string; endTime?: string; page?: number; size?: number }) =>
      request<any>(`${BASE}/capability/mcp`, {
        params,
      }),
    get: (id: number) => request<any>(`${BASE}/capability/mcp/${id}`),
    create: (data: any) =>
      request<any>(`${BASE}/capability/mcp`, { method: 'POST', data }),
    update: (id: number, data: any) =>
      request<any>(`${BASE}/capability/mcp/${id}`, { method: 'PUT', data }),
    delete: (id: number) =>
      request<void>(`${BASE}/capability/mcp/${id}`, { method: 'DELETE' }),
    batchDelete: (ids: number[]) =>
      request<void>(`${BASE}/capability/mcp/batch`, { method: 'DELETE', data: ids }),
  },

  skill: {
    list: (params: { keyword?: string; startTime?: string; endTime?: string; page?: number; size?: number }) =>
      request<any>(`${BASE}/capability/skill`, {
        params,
      }),
    get: (id: number) => request<any>(`${BASE}/capability/skill/${id}`),
    create: (data: any) =>
      request<any>(`${BASE}/capability/skill`, { method: 'POST', data }),
    update: (id: number, data: any) =>
      request<any>(`${BASE}/capability/skill/${id}`, { method: 'PUT', data }),
    delete: (id: number) =>
      request<void>(`${BASE}/capability/skill/${id}`, { method: 'DELETE' }),
    batchDelete: (ids: number[]) =>
      request<void>(`${BASE}/capability/skill/batch`, { method: 'DELETE', data: ids }),
  },

  cli: {
    list: (params: { keyword?: string; startTime?: string; endTime?: string; page?: number; size?: number }) =>
      request<any>(`${BASE}/capability/cli`, {
        params,
      }),
    get: (id: number) => request<any>(`${BASE}/capability/cli/${id}`),
    create: (data: any) =>
      request<any>(`${BASE}/capability/cli`, { method: 'POST', data }),
    update: (id: number, data: any) =>
      request<any>(`${BASE}/capability/cli/${id}`, { method: 'PUT', data }),
    delete: (id: number) =>
      request<void>(`${BASE}/capability/cli/${id}`, { method: 'DELETE' }),
    batchDelete: (ids: number[]) =>
      request<void>(`${BASE}/capability/cli/batch`, { method: 'DELETE', data: ids }),
  },

  builtin: {
    list: () => request<any[]>(`${BASE}/capability/builtin`),
  },

  artifacts: {
    documents: {
      list: (params?: { sessionId?: number; page?: number; size?: number }) =>
        request<any>(`${BASE}/artifacts/documents`, { params }),
      getById: (id: number) => request<any>(`${BASE}/artifacts/documents/${id}`),
      delete: (id: number) =>
        request<void>(`${BASE}/artifacts/documents/${id}`, { method: 'DELETE' }),
      batchDelete: (ids: number[]) =>
        request<void>(`${BASE}/artifacts/documents/batch`, { method: 'DELETE', data: ids }),
      update: (id: number, title: string, content: string) =>
        request<void>(`${BASE}/artifacts/documents/${id}`, { method: 'PUT', data: { title, content } }),
      createShare: (id: number) =>
        request<{ shareToken: string }>(`${BASE}/artifacts/documents/${id}/share`, { method: 'POST' }),
    },
  },

  auth: {
    checkUsername: (username: string) =>
      request<boolean>(`/api/v1/auth/check-username`, { params: { username } }),
    register: (data: { username: string; password: string; repeatPassword: string }) =>
      request<any>(`/api/v1/auth/register`, { method: 'POST', data }),
    updateProfile: (data: { displayName?: string; englishName?: string; avatar?: string }) =>
      request<void>(`/api/v1/auth/profile`, { method: 'PUT', data }),
    updatePassword: (data: { oldPassword: string; newPassword: string }) =>
      request<void>(`/api/v1/auth/password`, { method: 'PUT', data }),
  },

  admin: {
    users: {
      list: (page = 1, size = 20) =>
        request<any>(`${BASE}/admin/users`, { params: { page, size } }),
    },
    listConfigs: () =>
      request<any[]>(`${BASE}/system/config`),
    updateConfig: (key: string, value: string) =>
      request<any>(`${BASE}/system/config/${key}`, { method: 'PUT', data: { value } }),
    regenerateAesKey: () =>
      request<any>(`${BASE}/system/config/crypto.aes-key/regenerate`, { method: 'POST' }),
    roles: {
      list: (page = 1, size = 20) =>
        request<any>(`${BASE}/admin/roles`, { params: { page, size } }),
      listAll: () => request<any[]>(`${BASE}/admin/roles/all`),
      listByUser: (userId: number) => request<any[]>(`${BASE}/admin/roles/user/${userId}`),
      create: (data: any) => request<any>(`${BASE}/admin/roles`, { method: 'POST', data }),
      update: (id: number, data: any) => request<any>(`${BASE}/admin/roles/${id}`, { method: 'PUT', data }),
      delete: (id: number) => request<void>(`${BASE}/admin/roles/${id}`, { method: 'DELETE' }),
      assignPermissions: (roleId: number, permissionIds: number[]) =>
        request<void>(`${BASE}/admin/roles/assign-permissions`, { method: 'POST', data: { roleId, permissionIds } }),
      assignUser: (userId: number, roleIds: number[]) =>
        request<void>(`${BASE}/admin/roles/assign-user`, { method: 'POST', data: { userId, roleIds } }),
    },
    permissions: {
      list: (page = 1, size = 20) =>
        request<any>(`${BASE}/admin/permissions`, { params: { page, size } }),
      tree: () => request<any[]>(`${BASE}/admin/permissions/tree`),
      listByRole: (roleId: number) => request<any[]>(`${BASE}/admin/permissions/role/${roleId}`),
      create: (data: any) => request<any>(`${BASE}/admin/permissions`, { method: 'POST', data }),
      update: (id: number, data: any) => request<any>(`${BASE}/admin/permissions/${id}`, { method: 'PUT', data }),
      delete: (id: number) => request<void>(`${BASE}/admin/permissions/${id}`, { method: 'DELETE' }),
    },
    auditLogs: {
      list: (params: {
        page?: number;
        size?: number;
        username?: string;
        action?: string;
        resourceType?: string;
        startDate?: string;
        endDate?: string;
      }) => request<any>(`${BASE}/admin/audit-logs`, { params }),
    },
    identityProviders: {
      list: (keyword?: string) =>
        request<any[]>(`${BASE}/admin/identity-providers`, { params: { keyword } }),
      get: (id: number) => request<any>(`${BASE}/admin/identity-providers/${id}`),
      create: (data: any) =>
        request<any>(`${BASE}/admin/identity-providers`, { method: 'POST', data }),
      update: (id: number, data: any) =>
        request<any>(`${BASE}/admin/identity-providers/${id}`, { method: 'PUT', data }),
      delete: (id: number) =>
        request<void>(`${BASE}/admin/identity-providers/${id}`, { method: 'DELETE' }),
      setEnabled: (id: number, enabled: boolean) =>
        request<void>(`${BASE}/admin/identity-providers/${id}/enabled`, {
          method: 'PUT',
          data: { enabled },
        }),
      testConnection: (id: number) =>
        request<void>(`${BASE}/admin/identity-providers/${id}/test`, { method: 'POST' }),
    },
    completions: {
      list: (params: {
        keyword?: string;
        startTime?: string;
        endTime?: string;
        page?: number;
        size?: number;
      }) => request<any>(`${BASE}/admin/completions`, { params }),
      get: (id: number) => request<any>(`${BASE}/admin/completions/${id}`),
      create: (data: any) =>
        request<any>(`${BASE}/admin/completions`, { method: 'POST', data }),
      update: (id: number, data: any) =>
        request<any>(`${BASE}/admin/completions/${id}`, { method: 'PUT', data }),
      delete: (id: number) =>
        request<void>(`${BASE}/admin/completions/${id}`, { method: 'DELETE' }),
      addPrompt: (id: number, data: any) =>
        request<any>(`${BASE}/admin/completions/${id}/prompts`, { method: 'POST', data }),
      listPrompts: (id: number) =>
        request<any[]>(`${BASE}/admin/completions/${id}/prompts`),
      activate: (id: number, promptId: number) =>
        request<void>(`${BASE}/admin/completions/${id}/activate`, {
          method: 'PUT',
          data: { promptId },
        }),
      listCalls: (id: number, page = 1, size = 10) =>
        request<any>(`${BASE}/admin/completions/${id}/calls`, { params: { page, size } }),
    },
    tasks: {
      create: (data: any) =>
        request<any>(`${BASE}/admin/tasks`, { method: 'POST', data }),
      list: (params: {
        keyword?: string;
        status?: string;
        startTime?: string;
        endTime?: string;
        page?: number;
        size?: number;
      }) => request<any>(`${BASE}/admin/tasks`, { params }),
      get: (id: number) => request<any>(`${BASE}/admin/tasks/${id}`),
      stop: (id: number) =>
        request<void>(`${BASE}/admin/tasks/${id}/stop`, { method: 'POST' }),
    },
  },

  instanceCapabilities: {
    listByInstance: (instanceId: number) =>
      request<any[]>(
        `${BASE}/instance/instance-capabilities?instanceId=${instanceId}`,
      ),
    listFull: (instanceId: number) =>
      request<any[]>(
        `${BASE}/instance/instance-capabilities/full?instanceId=${instanceId}`,
      ),
    create: (data: any) =>
      request<any>(`${BASE}/instance/instance-capabilities`, {
        method: 'POST',
        data,
      }),
    batchCreate: (data: any) =>
      request<any[]>(`${BASE}/instance/instance-capabilities/batch`, {
        method: 'POST',
        data,
      }),
    delete: (id: number) =>
      request<void>(`${BASE}/instance/instance-capabilities/${id}`, {
        method: 'DELETE',
      }),
  },
};
