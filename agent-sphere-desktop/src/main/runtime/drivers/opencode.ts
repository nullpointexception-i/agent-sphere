import type { DriverStatus } from '../../../shared/types';
import type { DriverRunContext, DriverRunResult, RuntimeDriver } from './types';
import { logger } from '../../logging/logger';

const SESSION_PREFIX = 'opencode:';
const SUPPORTED_MAJOR_MINOR = '2.2';

interface ActiveRun {
  sessionId: string;
  controller: AbortController;
}

type AnyRecord = Record<string, any>;

function parseSessionRef(ref?: string): string | undefined {
  if (!ref) return undefined;
  return ref.startsWith(SESSION_PREFIX) ? ref.slice(SESSION_PREFIX.length) : ref;
}

function textOf(result: unknown): string {
  const value = result as AnyRecord | undefined;
  if (!value) return '';
  if (typeof value.text === 'string') return value.text;
  const parts = value.parts ?? value.data?.parts;
  if (Array.isArray(parts)) {
    return parts
      .filter((part) => part && (part.type === 'text' || typeof part.text === 'string'))
      .map((part) => String(part.text ?? ''))
      .join('');
  }
  return '';
}

function permissionCategory(type: string | undefined): 'read' | 'write' | 'shell' | 'network' {
  const value = (type ?? '').toLowerCase();
  if (value.includes('bash') || value.includes('shell') || value.includes('command')) return 'shell';
  if (value.includes('edit') || value.includes('write') || value.includes('patch')) return 'write';
  if (value.includes('fetch') || value.includes('network') || value.includes('http')) return 'network';
  return 'read';
}

export class OpenCodeDriver implements RuntimeDriver {
  readonly kind = 'OPENCODE' as const;

  private endpoint?: AnyRecord;
  private headers: Record<string, string> = {};
  private client?: AnyRecord;
  private version?: string;
  private readonly active = new Map<number, ActiveRun>();
  private disposed = false;

  constructor(private readonly settings: { get(): { taskTimeoutSec: number } }) {}

  async detect(): Promise<DriverStatus> {
    try {
      const service = (await import('@opencode/client/service')) as AnyRecord;
      const sdk = (await import('@opencode/client')) as AnyRecord;
      const endpoint = await service.Service.discover();
      const client = sdk.OpenCode.make({
        baseUrl: endpoint.url,
        headers: service.Service.headers(endpoint),
      });
      const info = await client.server.info();
      const version = String(info?.version ?? info?.data?.version ?? '');
      if (!version.startsWith(`${SUPPORTED_MAJOR_MINOR}.`)) {
        return {
          kind: 'OPENCODE',
          state: 'VERSION_MISMATCH',
          version,
          message: `opencode ${SUPPORTED_MAJOR_MINOR}.x is required; found ${version || 'unknown'}`,
        };
      }
      this.endpoint = endpoint;
      this.headers = service.Service.headers(endpoint) as Record<string, string>;
      this.client = client;
      this.version = version;
      return {
        kind: 'OPENCODE',
        state: 'READY',
        version,
        path: endpoint.url,
      };
    } catch (error) {
      this.client = undefined;
      this.endpoint = undefined;
      return {
        kind: 'OPENCODE',
        state: 'MISSING',
        message:
          'opencode shared service not found. Install and start opencode Desktop.',
      };
    }
  }

  async run(context: DriverRunContext): Promise<DriverRunResult> {
    if (this.disposed) throw new Error('opencode driver disposed');
    if (!this.client || !this.endpoint) {
      const status = await this.detect();
      if (status.state !== 'READY') throw new Error(status.message ?? 'opencode is not ready');
    }

    const client = this.client as AnyRecord;
    const endpoint = this.endpoint as AnyRecord;
    let sessionId = parseSessionRef(context.task.sessionRef);

    if (!sessionId) {
      const session = await client.session.create({
        location: { directory: context.profile.workspace },
      });
      sessionId = String(session.id);
      context.onSessionRef(`${SESSION_PREFIX}${sessionId}`);
    }

    const controller = new AbortController();
    const abort = (): void => controller.abort();
    context.signal.addEventListener('abort', abort, { once: true });
    this.active.set(context.task.taskId, { sessionId, controller });

    let accumulated = '';
    let finalText = '';
    let settled = false;
    let resolveDone!: () => void;
    let rejectDone!: (error: Error) => void;

    const done = new Promise<void>((resolve, reject) => {
      resolveDone = resolve;
      rejectDone = reject;
    });
    void done.catch(() => undefined);

    const settle = (error?: Error): void => {
      if (settled) return;
      settled = true;
      if (error) rejectDone(error);
      else resolveDone();
    };

    const eventLoop = (async () => {
      try {
        const events = client.event.subscribe({ signal: controller.signal });
        for await (const event of events) {
          const type = String(event?.type ?? '');
          const data = (event?.properties ?? event?.data ?? event) as AnyRecord;
          const eventSession = String(data?.sessionID ?? data?.sessionId ?? '');
          if (eventSession && eventSession !== sessionId) continue;

          switch (type) {
            case 'session.text.delta': {
              const delta = String(data?.delta ?? data?.text ?? '');
              if (!delta) break;
              accumulated += delta;
              context.onChunk('reply', delta);
              break;
            }
            case 'session.reasoning.delta': {
              const delta = String(data?.delta ?? data?.text ?? '');
              if (delta) context.onChunk('thinking', delta);
              break;
            }
            case 'session.execution.succeeded': {
              const text = textOf(data?.message ?? data?.result ?? data);
              if (text) finalText = text;
              settle();
              break;
            }
            case 'session.execution.failed': {
              settle(new Error(String(data?.error?.message ?? data?.message ?? 'opencode run failed')));
              break;
            }
            case 'session.execution.interrupted': {
              settle(new Error('Aborted'));
              break;
            }
            case 'session.idle': {
              settle();
              break;
            }
            case 'permission.asked': {
              await this.answerPermission(context, sessionId as string, data, endpoint);
              break;
            }
            case 'form.created': {
              settle(new Error('opencode forms are not supported by this client yet'));
              controller.abort();
              break;
            }
            default:
              break;
          }
        }
      } catch (error) {
        if (!settled && !controller.signal.aborted) {
          settle(error instanceof Error ? error : new Error(String(error)));
        }
      }
    })();

    const timeoutMs = Math.max(1, this.settings.get().taskTimeoutSec * 1000);
    const timer = setTimeout(() => {
      void this.interrupt(sessionId as string);
      settle(new Error('opencode task timed out'));
    }, timeoutMs);

    try {
      const prompt = client.session.prompt({
        sessionID: sessionId,
        text: context.prompt,
        ...(context.profile.model ? { model: context.profile.model } : {}),
      });

      void Promise.resolve(prompt)
        .then((result: unknown) => {
          const text = textOf(result);
          if (text) finalText = text;
          settle();
        })
        .catch((error: unknown) => {
          settle(error instanceof Error ? error : new Error(String(error)));
        });

      await done;
    } finally {
      clearTimeout(timer);
      controller.abort();
      context.signal.removeEventListener('abort', abort);
      this.active.delete(context.task.taskId);
      void eventLoop;
    }

    const text = (finalText || accumulated).trim();
    if (!text) throw new Error('opencode completed without a reply');
    return {
      text,
      sessionRef: `${SESSION_PREFIX}${sessionId}`,
    };
  }

  async cancel(taskId: number): Promise<void> {
    const run = this.active.get(taskId);
    if (!run) return;
    run.controller.abort();
    await this.interrupt(run.sessionId);
  }

  dispose(): void {
    this.disposed = true;
    for (const run of this.active.values()) run.controller.abort();
    this.active.clear();
  }

  private async answerPermission(
    context: DriverRunContext,
    sessionId: string,
    data: AnyRecord,
    endpoint: AnyRecord,
  ): Promise<void> {
    const permissionId = String(
      data.permissionID ?? data.permissionId ?? data.id ?? '',
    );
    if (!permissionId) return;

    const decision = await context.approvals.request({
      taskId: context.task.taskId,
      kind: 'opencode.permission',
      category: permissionCategory(String(data.type ?? data.permission ?? '')),
      title: 'Local avatar requests permission',
      detail: JSON.stringify(data).slice(0, 2000),
    });

    const response =
      decision === 'acceptForSession' ? 'always' : decision === 'accept' ? 'once' : 'reject';
    const url = `${String(endpoint.url).replace(/\/+$/, '')}/api/session/${encodeURIComponent(
      sessionId,
    )}/permission/${encodeURIComponent(permissionId)}/reply`;
    try {
      await fetch(url, {
        method: 'POST',
        headers: { ...this.headers, 'Content-Type': 'application/json' },
        body: JSON.stringify({ response }),
      });
    } catch (error) {
      logger.warn(`opencode permission reply failed: ${String(error)}`);
    }
  }

  private async interrupt(sessionId: string): Promise<void> {
    if (!this.client || !this.endpoint) return;
    const url = `${String(this.endpoint.url).replace(/\/+$/, '')}/api/session/${encodeURIComponent(
      sessionId,
    )}/interrupt`;
    try {
      await fetch(url, {
        method: 'POST',
        headers: { ...this.headers, 'Content-Type': 'application/json' },
        body: '{}',
      });
    } catch (error) {
      logger.warn(`opencode interrupt failed: ${String(error)}`);
    }
  }
}
