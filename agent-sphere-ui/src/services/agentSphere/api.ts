import { API_PATHS } from '@/constants/endpoints';
import { request } from '@umijs/max';

import { API_BASE } from '@/constants/api';

const BASE = API_BASE;

/** Workspace document grid row (backend DocumentSummaryVO). */
export interface DocumentSummary {
  id: number;
  title: string;
  contentType?: string;
  version?: number;
  sessionId?: number;
  instanceId?: number;
  createdBy?: string;
  createdAt?: string;
  updatedAt?: string;
  contentLength?: number;
  preview?: string;
}

/**
 * Session cleanup execution report (backend SessionCleanupReportVO).
 * tableStats keys are database table names, directly understandable to operations.
 */
export interface SessionCleanupReport {
  dryRun: boolean;
  enabled: boolean;
  skippedByLock: boolean;
  skipReason?: string;
  retentionDays: number;
  fileRetentionDays: number;
  cutoff?: string;
  fileCutoff?: string;
  sessionCount: number;
  skippedActiveSessions: number;
  tableStats: Record<string, number>;
  batches: number;
  truncated: boolean;
  elapsedMs: number;
  vacuumHint?: string;
  /** Progress denominator: total expired sessions computed before the run */
  totalSessions?: number;
}

/** Session cleanup execution record (backend SessionCleanupRunVO). Both scheduled and manual triggers are recorded. */
export interface SessionCleanupRun {
  id: number;
  triggerType: 'SCHEDULED' | 'MANUAL';
  dryRun: boolean;
  status: 'RUNNING' | 'SUCCESS' | 'FAILED' | 'SKIPPED';
  retentionDays?: number;
  fileRetentionDays?: number;
  cutoff?: string;
  fileCutoff?: string;
  sessionCount?: number;
  /** Progress denominator: total expired sessions counted before the run; progress = sessionCount / totalSessions */
  totalSessions?: number;
  totalRows?: number;
  skippedActiveSessions?: number;
  batches?: number;
  truncated?: boolean;
  elapsedMs?: number;
  tableStats?: Record<string, number>;
  skipReason?: string;
  errorMessage?: string;
  startedAt?: string;
  finishedAt?: string;
  /** Suspected interruption: a crashed process leaves the record stuck in RUNNING */
  stale?: boolean;
  createdBy?: string;
  /** Backend hint for operations (currently the post-hard-delete VACUUM recommendation), displayed as is by the frontend */
  remark?: string;
}

/** One round of Skill auto-sync execution record (backend SkillSyncRunVO). */
export interface SkillSyncRun {
  id: number;
  triggerType: 'SCHEDULED' | 'MANUAL';
  status: 'RUNNING' | 'SUCCESS' | 'FAILED' | 'SKIPPED';
  /** Total number of "replicas with auto-update enabled" scanned this round */
  scannedCount?: number;
  handled?: number;
  updated?: number;
  skipped?: number;
  batchSize?: number;
  /** Replicas not synced and the reason: SOURCE_GONE / NOT_PUBLIC / VERSION_NOT_AHEAD / CONCURRENT_UPDATE / ERROR:… */
  detail?: { copyId: number; originId: number; reason: string }[];
  skipReason?: string;
  errorMessage?: string;
  elapsedMs?: number;
  startedAt?: string;
  finishedAt?: string;
  /** Suspected interruption: a crashed process leaves the record stuck in RUNNING */
  stale?: boolean;
  createdBy?: string;
}

export interface PageResult<T> {
  records: T[];
  total: number;
  current: number;
  size: number;
}

export const agentApi = {
  system: {
    // Public config read (no login required): currently supports plugin.download-url
    publicConfig: (keys: string[]) =>
      request<Record<string, string>>(`${BASE}/system/config/public?keys=${keys.join(',')}`),
  },
  sso: {
    providers: () =>
      request<{ code: string; name: string }[]>(`${BASE}/auth/sso/providers`),
  },
  instances: {
    listAll: () =>
      request<any[]>(`${BASE}/instance/instances/all`),
    listLatest: (n: number) =>
      request<any[]>(`${BASE}/instance/instances/latest/${n}`),
    list: (params: {
      keyword?: string;
      startTime?: string;
      endTime?: string;
      instanceRole?: string;
      scope?: string;
      page?: number;
      size?: number;
    }) =>
      request<any>(`${BASE}/instance/instances`, {
        params,
      }),
    get: (id: number) => request<any>(`${BASE}/instance/instances/${id}`),
    create: (data: any, scope?: string) =>
      request<any>(`${BASE}/instance/instances`, {
        method: 'POST',
        params: { scope },
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
    list: (offset = 0, limit = 20, keyword?: string, scope?: string, role?: string) =>
      request<any[]>(`${BASE}/instance/sessions`, {
        params: { offset, limit, keyword, scope, role },
      }),
    update: (id: number, title: string) =>
      request<any>(`${BASE}/instance/sessions/${id}`, {
        method: 'PUT',
        data: { title },
      }),
    updateRunMode: (id: number, runMode: string) =>
      request<any>(`${BASE}/instance/sessions/${id}/run-mode`, {
        method: 'PUT',
        data: { runMode },
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
    chat: (
      id: number,
      message: string,
      modelRouteId?: number,
      attachmentKeys?: string[],
      runMode?: string,
      reasoningEffort?: string,
    ) =>
      request<{ runId: number; status: string; assistantReply?: string; queueId?: string }>(
        `${BASE}/runtime/${id}/chat`,
        {
          method: 'POST',
          data: { message, modelRouteId, attachmentKeys, runMode, reasoningEffort },
        },
      ),
    getTodos: (id: number) =>
      request<any>(`${BASE}/instance/sessions/${id}/todos`),
    getLatestToolCalls: (id: number) =>
      request<any>(`${BASE}/instance/sessions/${id}/toolcalls/latest`),
    toolStats: (id: number, runId?: number | null) =>
      request<any>(`${BASE}/instance/sessions/${id}/tool-stats${runId != null ? `?runId=${runId}` : ''}`),
    pendingQueue: (id: number) =>
      request<any[]>(`${BASE}/runtime/${id}/queue`),
    getRuntimeState: (id: number) =>
      request<any>(`${BASE}/runtime/${id}/state`),
    getReasoningCapability: (id: number, modelRouteId?: number) =>
      request<{
        supported: boolean;
        mode: string;
        effectiveMaxOutputTokens?: number;
        answerReserveTokens?: number;
        defaultLevel?: string;
        selectedLevel?: string;
        effectiveLevel?: string;
        running: boolean;
        runId?: number;
        levels: { value: string; minOutputTokens: number; feasible: boolean }[];
      }>(
        `${BASE}/runtime/${id}/reasoning${modelRouteId != null ? `?modelRouteId=${modelRouteId}` : ''}`,
      ),
    withdrawQueue: (id: number, queueId: string) =>
      request<{ result: string }>(`${BASE}/runtime/${id}/queue/${queueId}`, { method: 'DELETE' }),
    feedback: (sessionId: number, interactionId: number, runId: number | null | undefined, value: string) =>
      request<any>(`${BASE}/instance/sessions/${sessionId}/messages/${interactionId}/feedback${runId != null ? `?runId=${runId}` : ''}`, {
        method: 'POST', data: { value },
      }),
    share: (sessionId: number, runId: number, runCount: number) =>
      request<{ shareToken: string }>(`${BASE}/instance/sessions/${sessionId}/share`, {
        method: 'POST', data: { runId, runCount },
      }),
    getSubAgentRuns: (id: number) =>
      request<any[]>(`${BASE}/instance/sessions/${id}/sub-agent-runs`),
    getSubAgentTimeline: (id: number) =>
      request<any[]>(`${BASE}/instance/sub-agent-runs/${id}/timeline`),
    getTimeline: (id: number, params: { beforeSeq?: number; afterSeq?: number; limit?: number } = {}) => {
      const query = [
        params.beforeSeq != null ? `beforeSeq=${params.beforeSeq}` : '',
        params.afterSeq != null ? `afterSeq=${params.afterSeq}` : '',
        `limit=${params.limit ?? 5}`,
      ]
        .filter(Boolean)
        .join('&');
      return request<any>(`${BASE}/instance/sessions/${id}/timeline?${query}`);
    },
    usage: (id: number) =>
      request<any>(`${BASE}/instance/sessions/${id}/usage`),
    getDocuments: (id: number, page = 1, size = 20) =>
      request<any>(`${BASE}/artifacts/documents?sessionId=${id}&page=${page}&size=${size}`),
    clarify: (sessionId: number, runId: number, response: string, clarificationId?: string) =>
      request<any>(`${BASE}/runtime/${sessionId}/run/${runId}/clarify`, {
        method: 'POST', data: { response, clarificationId },
      }),
    pendingClarifications: (sessionId: number) =>
      request<any[]>(`${BASE}/runtime/${sessionId}/clarifications/pending`),
    resolveClarifications: (
      sessionId: number,
      runId: number,
      answers: Array<{ clarificationId: string; selected?: string[]; text?: string }>,
    ) =>
      request<any>(`${BASE}/runtime/${sessionId}/run/${runId}/clarifications/resolve`, {
        method: 'POST', data: { answers },
      }),
  },

  files: {
    upload: (file: File) => {
      const formData = new FormData();
      formData.append('file', file);
      return request<{ fileKey: string; contentType: string; sizeBytes: number }>(
        `${BASE}/files/upload`,
        { method: 'POST', data: formData },
      );
    },
    // Read attachment bytes back by fileKey (chat history image display); returns a Blob, and the view creates its own objectURL
    get: (fileKey: string) =>
      request<Blob>(`${BASE}/files/${fileKey}`, { responseType: 'blob' }),
  },

  runs: {
    create: (data: any) =>
      request<any>(`${BASE}/instance/runs`, { method: 'POST', data }),
    get: (id: number) => request<any>(`${BASE}/instance/runs/${id}`),
    listBySession: (sessionId: number, page = 1, size = 10, keyword?: string) =>
      request<any>(
        `${BASE}/instance/runs?sessionId=${sessionId}&page=${page}&size=${size}${keyword ? `&keyword=${encodeURIComponent(keyword)}` : ''}`,
      ),
    // Fetch reasoning in batches on demand: returns {runId: reasoning} (the list omits it by default)
    reasoning: (runIds: number[]) =>
      request<Record<string, string>>(`${BASE}/instance/runs/reasoning?runIds=${runIds.join(',')}`),
    stop: (sessionId: number, runId: number) =>
      request<void>(`${BASE}/runtime/${sessionId}/run/${runId}/stop`, { method: 'POST' }),
    sessionStop: (sessionId: number) =>
      request<void>(`${BASE}/runtime/${sessionId}/stop`, { method: 'POST' }),
    usage: (runId: number) =>
      request<any>(`${BASE}/instance/runs/${runId}/usage`),
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
    // The list does not return large fields; fetch the full request/response/args/artifact on demand for details
    get: (
      runId: number,
      sessionId: number,
      activityType: string,
      id: number,
    ) =>
      request<any>(
        `${BASE}/instance/runs/${runId}/activities/${activityType}/${id}?sessionId=${sessionId}`,
      ),
    // Token series of the latest 300 main-agent LLM interactions; afterId supports 5s incremental refresh
    usageSeries: (
      runId: number,
      sessionId: number,
      afterId = 0,
      limit = 300,
    ) =>
      request<{ points: any[]; hasMore: boolean }>(
        `${BASE}/instance/runs/${runId}/usage-series?sessionId=${sessionId}&afterId=${afterId}&limit=${limit}`,
      ),
  },

  toolApprovals: {
    pending: (sessionId: number) =>
      request<any>(`${BASE}/runtime/${sessionId}/tool-approvals/pending`),
    resolve: (
      sessionId: number,
      runId: number,
      approvalId: string,
      decisions: Array<{ callId: string; decision: string }>,
    ) =>
      request<void>(
        `${BASE}/runtime/${sessionId}/run/${runId}/tool-approvals/${approvalId}`,
        { method: 'POST', data: { decisions } },
      ),
  },

  modelProviders: {
    count: () =>
      request<number>(`${BASE}/model/providers/count`),
    list: (keyword?: string) =>
      request<any[]>(`${BASE}/model/providers`, {
        params: { keyword },
      }),
    page: (params: {
      page?: number;
      size?: number;
      keyword?: string;
      scope?: string;
    }) =>
      request<any>(`${BASE}/model/providers/page`, {
        params,
      }),
    get: (id: number) => request<any>(`${BASE}/model/providers/${id}`),
    create: (data: any, scope?: string) =>
      request<any>(`${BASE}/model/providers`, {
        method: 'POST',
        params: { scope },
        data,
      }),
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
    listSystem: () => request<any[]>(`${BASE}/model/routes/system`),
    get: (id: number) => request<any>(`${BASE}/model/routes/${id}`),
    getDefault: () => request<any>(`${BASE}/model/routes/default`),
    create: (data: any) =>
      request<any>(`${BASE}/model/routes`, { method: 'POST', data }),
    update: (id: number, data: any) =>
      request<any>(`${BASE}/model/routes/${id}`, { method: 'PUT', data }),
    setDefault: (id: number) =>
      request<any>(`${BASE}/model/routes/${id}/default`, { method: 'PUT' }),
    delete: (id: number) =>
      request<void>(`${BASE}/model/routes/${id}`, { method: 'DELETE' }),
  },

  workspaceContacts: {
    users: (params: { keyword?: string; page?: number; size?: number }) =>
      request<any>(`${BASE}/workspace/contacts/users`, { params }),
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
    test: (id: number) =>
      request<any>(`${BASE}/capability/mcp/${id}/test`, { method: 'POST' }),
    listTools: (id: number) =>
      request<any[]>(`${BASE}/capability/mcp/${id}/tools`),
    callTool: (id: number, toolName: string, args?: any) =>
      request<any>(`${BASE}/capability/mcp/${id}/tools/${toolName}/call`, {
        method: 'POST',
        data: args || {},
      }),
    /** Publish (or re-publish) a local MCP into its tenant hub; version always increments. */
    publishToHub: (id: number) =>
      request<any>(`${BASE}/capability/mcp/${id}/publish`, { method: 'POST' }),
    hub: (params: { keyword?: string; page?: number; size?: number }) =>
      request<any>(`${BASE}/capability/mcp/hub`, { params }),
    hubDetail: (hubId: number) =>
      request<any>(`${BASE}/capability/mcp/hub/${hubId}`),
    followed: () => request<any[]>(`${BASE}/capability/mcp/hub/followed`),
    follow: (hubId: number) =>
      request<any>(`${BASE}/capability/mcp/hub/${hubId}/follow`, {
        method: 'POST',
      }),
    unfollow: (hubId: number) =>
      request<any>(`${BASE}/capability/mcp/hub/${hubId}/follow`, {
        method: 'DELETE',
      }),
    installHub: (hubId: number, autoUpdate: boolean) =>
      request<any>(`${BASE}/capability/mcp/hub/${hubId}/install`, {
        method: 'POST',
        data: { autoUpdate },
      }),
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
    updateStatus: (id: number, status: string) =>
      request<any>(`${BASE}/capability/skill/${id}/status`, { method: 'PUT', data: { status } }),
    batchUpdateStatus: (ids: number[], status: string) =>
      request<any>(`${BASE}/capability/skill/batch/status`, { method: 'POST', data: { ids, status } }),
    hub: (params: { keyword?: string; page?: number; size?: number }) =>
      request<any>(`${BASE}/capability/skill/hub`, {
        params,
      }),
    publish: (id: number, visibility: string) =>
      request<any>(`${BASE}/capability/skill/${id}/visibility`, { method: 'PUT', data: { visibility } }),
    /** Publish (or re-publish) a local skill into its tenant hub; version always increments. */
    publishToHub: (id: number) =>
      request<any>(`${BASE}/capability/skill/${id}/publish`, { method: 'POST' }),
    publishState: (id: number) =>
      request<any>(`${BASE}/capability/skill/${id}/publish-state`),
    hubDetail: (hubId: number) =>
      request<any>(`${BASE}/capability/skill/hub/${hubId}`),
    followed: () => request<any[]>(`${BASE}/capability/skill/hub/followed`),
    follow: (hubId: number) =>
      request<any>(`${BASE}/capability/skill/hub/${hubId}/follow`, { method: 'POST' }),
    unfollow: (hubId: number) =>
      request<any>(`${BASE}/capability/skill/hub/${hubId}/follow`, { method: 'DELETE' }),
    installHub: (hubId: number, autoUpdate: boolean) =>
      request<any>(`${BASE}/capability/skill/hub/${hubId}/install`, {
        method: 'POST',
        data: { autoUpdate },
      }),
    install: (id: number, data?: any) =>
      request<any>(`${BASE}/capability/skill/${id}/install`, { method: 'POST', data: data || {} }),
    setAutoUpdate: (id: number, enabled: boolean) =>
      request<any>(`${BASE}/capability/skill/${id}/auto-update`, {
        method: 'PUT',
        data: { autoUpdate: enabled },
      }),
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
      list: (params?: {
        sessionId?: number;
        page?: number;
        size?: number;
        scope?: 'mine' | 'team';
        keyword?: string;
      }) =>
        request<{
          records: DocumentSummary[];
          total: number;
          page: number;
          pageSize: number;
        }>(`${BASE}/artifacts/documents`, { params }),
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
      request<boolean>(API_PATHS.AUTH_CHECK_USERNAME, { params: { username } }),
    register: (data: { username: string; password: string; repeatPassword: string }) =>
      request<any>(API_PATHS.AUTH_REGISTER, { method: 'POST', data }),
    updateProfile: (data: { displayName?: string; englishName?: string; avatar?: string }) =>
      request<void>(API_PATHS.AUTH_PROFILE, { method: 'PUT', data }),
    updatePassword: (data: { oldPassword: string; newPassword: string }) =>
      request<void>(API_PATHS.AUTH_PASSWORD, { method: 'PUT', data }),
  },

  admin: {
    users: {
      list: (page = 1, size = 20) =>
        request<any>(`${BASE}/admin/users`, { params: { page, size } }),
    },
    tenants: {
      tree: () => request<any[]>(`${BASE}/admin/tenants/tree`),
      list: () => request<any[]>(`${BASE}/admin/tenants`),
      mine: () => request<any[]>(`${BASE}/admin/tenants/mine`),
      userAssignments: (userId: number) =>
        request<any[]>(`${BASE}/admin/tenants/user/${userId}`),
      create: (data: any) =>
        request<any>(`${BASE}/admin/tenants`, { method: 'POST', data }),
      testModel: (data: any) =>
        request<any>(`${BASE}/admin/tenants/test-model`, {
          method: 'POST',
          data,
        }),
      update: (id: number, data: any) =>
        request<any>(`${BASE}/admin/tenants/${id}`, { method: 'PUT', data }),
      updateStatus: (id: number, status: string) =>
        request<void>(`${BASE}/admin/tenants/${id}/status`, {
          method: 'PUT',
          params: { status },
        }),
      remove: (id: number) =>
        request<void>(`${BASE}/admin/tenants/${id}`, { method: 'DELETE' }),
      assign: (data: { userId: number; assignments: any[] }) =>
        request<any[]>(`${BASE}/admin/tenants/assign`, { method: 'POST', data }),
      setCurrentInstance: (id: number, instanceId: number) =>
        request<any>(`${BASE}/admin/tenants/${id}/current-instance`, {
          method: 'PUT',
          data: { instanceId },
        }),
      switch: (tenantId: number) =>
        request<{ currentTenantId: number; currentTenantName?: string }>(
          `${BASE}/admin/tenants/switch`,
          { method: 'POST', params: { tenantId } },
        ),
    },
    listConfigs: () =>
      request<any[]>(`${BASE}/system/config`),
    updateConfig: (key: string, value: string) =>
      request<any>(`${BASE}/system/config/${key}`, { method: 'PUT', data: { value } }),
    regenerateAesKey: () =>
      request<any>(`${BASE}/system/config/crypto.aes-key/regenerate`, { method: 'POST' }),
    uploadPlugin: (file: File) => {
      const form = new FormData();
      form.append('file', file);
      return request<any>(`${BASE}/system/config/plugin/upload`, { method: 'POST', data: form });
    },
    deletePlugin: () =>
      request<any>(`${BASE}/system/config/plugin`, { method: 'DELETE' }),
    /**
     * Submit one cleanup round (async, returns the execution record immediately).
     * Poll {@link getSessionCleanupRun} with the returned record's id for progress and results.
     */
    cleanupSessions: (dryRun: boolean) =>
      request<SessionCleanupRun>(`${BASE}/instance/session-cleanup`, {
        method: 'POST',
        data: { dryRun },
      }),
    /** Poll a single execution record (with live progress). */
    getSessionCleanupRun: (id: number) =>
      request<SessionCleanupRun>(`${BASE}/instance/session-cleanup/runs/${id}`),
    /** Cleanup execution records; passing undefined for dryRun means no dry-run/real-run filtering. */
    listSessionCleanupRuns: (params?: {
      triggerType?: string;
      status?: string;
      dryRun?: boolean;
      page?: number;
      size?: number;
    }) =>
      request<PageResult<SessionCleanupRun>>(`${BASE}/instance/session-cleanup/runs`, {
        params,
      }),
    /**
     * Manually trigger one "sync the latest version from Skill Hub" round (async, returns the
     * execution record immediately).
     * Poll {@link getSkillSyncRun} with the returned record's id for progress and results.
     */
    syncSkillNow: () =>
      request<SkillSyncRun>(`${BASE}/capability/skill/sync-now`, { method: 'POST' }),
    /** Poll a single sync execution record (with live stats and skipped details). */
    getSkillSyncRun: (id: number) =>
      request<SkillSyncRun>(`${BASE}/capability/skill/sync-runs/${id}`),
    /** Sync execution records, paged (scheduled + manual). */
    listSkillSyncRuns: (params?: {
      triggerType?: string;
      status?: string;
      page?: number;
      size?: number;
    }) =>
      request<PageResult<SkillSyncRun>>(`${BASE}/capability/skill/sync-runs`, {
        params,
      }),
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
      getDefaultResourceTemplate: () =>
        request<any>(`${BASE}/admin/identity-providers/resource-template-default`),
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
    taskArtifacts: {
      list: (params: {
        keyword?: string;
        taskId?: number;
        page?: number;
        size?: number;
      }) => request<any>(`${BASE}/admin/task-artifacts`, { params }),
      get: (id: number) => request<any>(`${BASE}/admin/task-artifacts/${id}`),
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
