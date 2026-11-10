import type { SettingsStore } from '../config/settings';
import type {
  HeartbeatRequest,
  LocalTaskPayload,
  RuntimeInfo,
  StreamEvent,
  TaskAckResponse,
} from './contracts';

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly body: string,
  ) {
    super(`AgentSphere API ${status}: ${body.slice(0, 300)}`);
    this.name = 'ApiError';
  }
}

export class LocalApiClient {
  constructor(private readonly settings: SettingsStore) {}

  private baseUrl(): string {
    return this.settings.get().serverUrl.replace(/\/+$/, '');
  }

  private headers(json = false): Record<string, string> {
    const secrets = this.settings.getSecrets();
    const settings = this.settings.get();
    const headers: Record<string, string> = {
      Accept: 'application/json',
    };
    if (secrets.deviceToken) {
      headers.Authorization = `Bearer ${secrets.deviceToken}`;
    }
    if (settings.runtimeId) {
      headers['X-AgentSphere-Runtime'] = String(settings.runtimeId);
    }
    if (json) headers['Content-Type'] = 'application/json';
    return headers;
  }

  async request<T>(
    path: string,
    init: RequestInit = {},
    timeoutMs = 30_000,
  ): Promise<T> {
    const response = await fetch(`${this.baseUrl()}/api/v1${path}`, {
      ...init,
      headers: { ...this.headers(Boolean(init.body)), ...(init.headers ?? {}) },
      signal: init.signal ?? AbortSignal.timeout(timeoutMs),
    });
    if (!response.ok) {
      throw new ApiError(response.status, await response.text());
    }
    if (response.status === 204) return undefined as T;
    return (await response.json()) as T;
  }

  getRuntime(): Promise<RuntimeInfo> {
    return this.request<RuntimeInfo>('/local-runtimes/me', {}, 15_000);
  }

  heartbeat(body: HeartbeatRequest): Promise<void> {
    return this.request<void>('/local-runtimes/me/heartbeat', {
      method: 'POST',
      body: JSON.stringify(body),
    }, 10_000);
  }

  ack(taskId: number): Promise<TaskAckResponse> {
    return this.request<TaskAckResponse>(
      `/local-runtimes/me/tasks/${taskId}/ack`,
      { method: 'POST', body: JSON.stringify({}) },
      20_000,
    );
  }

  chunk(
    taskId: number,
    leaseToken: string,
    seq: number,
    phase: 'reply' | 'thinking',
    delta: string,
  ): Promise<void> {
    return this.request<void>(`/local-runtimes/me/tasks/${taskId}/chunk`, {
      method: 'POST',
      body: JSON.stringify({ leaseToken, seq, phase, delta }),
    }, 15_000);
  }

  complete(
    taskId: number,
    leaseToken: string,
    body: { text: string; sessionRef?: string },
  ): Promise<void> {
    return this.request<void>(`/local-runtimes/me/tasks/${taskId}/complete`, {
      method: 'POST',
      body: JSON.stringify({ ...body, leaseToken }),
    }, 30_000);
  }

  fail(
    taskId: number,
    leaseToken: string,
    errorCode: string,
    errorMessage: string,
  ): Promise<void> {
    return this.request<void>(`/local-runtimes/me/tasks/${taskId}/fail`, {
      method: 'POST',
      body: JSON.stringify({ leaseToken, errorCode, errorMessage }),
    }, 15_000);
  }

  async streamTasks(
    onEvent: (event: StreamEvent) => void,
    signal: AbortSignal,
  ): Promise<void> {
    const response = await fetch(`${this.baseUrl()}/api/v1/local-runtimes/me/stream`, {
      headers: {
        ...this.headers(),
        Accept: 'text/event-stream',
        'Cache-Control': 'no-cache',
      },
      signal,
    });
    if (!response.ok) {
      throw new ApiError(response.status, await response.text());
    }
    const reader = response.body?.getReader();
    if (!reader) throw new Error('SSE stream is not readable');

    const decoder = new TextDecoder();
    let buffer = '';
    for (;;) {
      const { done, value } = await reader.read();
      if (done) return;
      buffer += decoder.decode(value, { stream: true });
      const parts = buffer.split('\n\n');
      buffer = parts.pop() ?? '';
      for (const part of parts) {
        const dataLines: string[] = [];
        for (const line of part.split('\n')) {
          if (line.startsWith('data:')) dataLines.push(line.slice(5).trim());
        }
        if (dataLines.length === 0) continue;
        try {
          onEvent(JSON.parse(dataLines.join('\n')) as StreamEvent);
        } catch {
          // Ignore malformed frames; the next REST catch-up recovers state.
        }
      }
    }
  }
}

export type { LocalTaskPayload };
