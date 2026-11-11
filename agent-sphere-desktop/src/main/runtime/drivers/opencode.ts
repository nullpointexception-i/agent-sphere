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

    const eventsAbort = new AbortController();
    let accumulated = '';
    let sawText = false;
    let fatal: Error | undefined;

    const eventLoop = (async () => {
      try {
        const events = client.event.subscribe({ signal: eventsAbort.signal });
        for await (const event of events) {
          const type = String(event?.type ?? '');
          const data = (event?.data ?? event?.properties ?? event) as AnyRecord;
          if (String(data?.sessionID ?? data?.sessionId ?? '') !== sessionId) continue;

          if (type === 'session.text.delta') {
            const delta = String(data?.delta ?? data?.text ?? '');
            if (!delta) continue;
            sawText = true;
            accumulated += delta;
            context.onChunk('reply', delta);
          } else if (type === 'session.reasoning.delta') {
            const delta = String(data?.delta ?? data?.text ?? '');
            if (delta) context.onChunk('thinking', delta);
          } else if (type === 'permission.asked') {
            await this.answerPermission(context, sessionId as string, data);
          } else if (type === 'form.created') {
            fatal = new Error('opencode forms are not supported by this client yet');
            controller.abort();
          }
        }
      } catch (error) {
        if (!eventsAbort.signal.aborted) {
          logger.warn(`opencode event stream failed: ${String(error)}`);
        }
      }
    })();
    void eventLoop;

    const timeoutMs = Math.max(1, this.settings.get().taskTimeoutSec * 1000);
    let timedOut = false;
    const timer = setTimeout(() => {
      timedOut = true;
      void this.interrupt(sessionId as string);
      controller.abort();
    }, timeoutMs);

    try {
      const result = (await client.session.generate(
        { sessionID: sessionId, prompt: context.prompt },
        { signal: controller.signal },
      )) as AnyRecord;
      const text = (String(result?.text ?? '') || accumulated).trim();
      if (!text) throw fatal ?? new Error('opencode completed without a reply');
      if (!sawText) context.onChunk('reply', text);
      return {
        text,
        sessionRef: `${SESSION_PREFIX}${sessionId}`,
      };
    } catch (error) {
      if (timedOut) throw new Error('opencode task timed out');
      if (fatal) throw fatal;
      throw error instanceof Error ? error : new Error(String(error));
    } finally {
      clearTimeout(timer);
      eventsAbort.abort();
      controller.abort();
      context.signal.removeEventListener('abort', abort);
      this.active.delete(context.task.taskId);
    }
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
  ): Promise<void> {
    const requestId = String(data?.id ?? data?.requestID ?? '');
    if (!requestId) return;

    const decision = await context.approvals.request({
      taskId: context.task.taskId,
      kind: 'opencode.permission',
      category: permissionCategory(String(data?.action ?? data?.type ?? '')),
      title: 'Local avatar requests permission',
      detail: JSON.stringify(data).slice(0, 2000),
    });

    const reply =
      decision === 'acceptForSession' ? 'always' : decision === 'accept' ? 'once' : 'reject';
    try {
      await (this.client as AnyRecord).permission.reply({
        sessionID: sessionId,
        requestID: requestId,
        decision: reply,
      });
    } catch (error) {
      logger.warn(`opencode permission reply failed: ${String(error)}`);
    }
  }

  private async interrupt(sessionId: string): Promise<void> {
    if (!this.client || !this.endpoint) return;
    try {
      await (this.client as AnyRecord).session.interrupt({ sessionID: sessionId });
    } catch (error) {
      logger.warn(`opencode interrupt failed: ${String(error)}`);
    }
  }
}
