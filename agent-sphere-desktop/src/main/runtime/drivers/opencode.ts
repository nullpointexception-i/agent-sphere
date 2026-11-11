import type { DriverModel, DriverStatus } from '../../../shared/types';
import type { DriverRunContext, DriverRunResult, RuntimeDriver } from './types';
import { logger } from '../../logging/logger';

const SESSION_PREFIX = 'opencode:';
const SUPPORTED_MAJOR_MINOR = '2.2';
/** `session.wait` can resolve a moment before the last text delta is observed. */
const REPLY_POLL_INTERVAL_MS = 100;
const REPLY_POLL_ATTEMPTS = 20;

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

interface ActiveRun {
  sessionId: string;
  controller: AbortController;
}

type AnyRecord = Record<string, any>;

function parseSessionRef(ref?: string): string | undefined {
  if (!ref) return undefined;
  return ref.startsWith(SESSION_PREFIX) ? ref.slice(SESSION_PREFIX.length) : ref;
}

interface ModelRef {
  providerID: string;
  id: string;
}

/** Accepts `provider/model`; anything else keeps the local default. */
function parseModel(value?: string): ModelRef | undefined {
  if (!value) return undefined;
  const separator = value.indexOf('/');
  if (separator <= 0 || separator === value.length - 1) return undefined;
  return {
    providerID: value.slice(0, separator),
    id: value.slice(separator + 1),
  };
}

function permissionCategory(type: string | undefined): 'read' | 'write' | 'shell' | 'network' {
  const value = (type ?? '').toLowerCase();
  if (value.includes('bash') || value.includes('shell') || value.includes('command')) return 'shell';
  if (value.includes('edit') || value.includes('write') || value.includes('patch')) return 'write';
  if (value.includes('fetch') || value.includes('network') || value.includes('http')) return 'network';
  return 'read';
}

/** Returns true when the stored opencode session no longer exists on the server. */
function isInvalidSessionError(error: unknown): boolean {
  const message = error instanceof Error ? error.message : String(error);
  return /invalid session/i.test(message) || /session.*not found/i.test(message);
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

  async listModels(): Promise<DriverModel[]> {
    if (!this.client) {
      const status = await this.detect();
      if (status.state !== 'READY') return [];
    }
    const client = this.client as AnyRecord;
    const result = await client.model.list();
    const list = Array.isArray(result) ? result : (result?.data ?? []);
    const seen = new Set<string>();
    const models: DriverModel[] = [];
    for (const item of list) {
      const providerID = String(item?.providerID ?? '');
      const id = String(item?.modelID ?? item?.id ?? '');
      if (!providerID || !id) continue;
      if (item?.enabled === false || item?.status === 'deprecated') continue;
      const key = `${providerID}/${id}`;
      if (seen.has(key)) continue;
      seen.add(key);
      models.push({
        providerID,
        id,
        name: String(item?.name ?? id),
      });
    }
    return models;
  }

  async run(context: DriverRunContext): Promise<DriverRunResult> {
    if (this.disposed) throw new Error('opencode driver disposed');
    if (!this.client || !this.endpoint) {
      const status = await this.detect();
      if (status.state !== 'READY') throw new Error(status.message ?? 'opencode is not ready');
    }

    const client = this.client as AnyRecord;
    const model = parseModel(context.profile.model);
    let sessionId = parseSessionRef(context.task.sessionRef);

    if (!sessionId) {
      const session = await client.session.create({
        location: { directory: context.profile.workspace },
        ...(model ? { model } : {}),
      });
      sessionId = String(session.id);
      context.onSessionRef(`${SESSION_PREFIX}${sessionId}`);
    } else if (model) {
      try {
        await client.session.switchModel({ sessionID: sessionId, model });
      } catch (error) {
        logger.warn(`opencode model switch failed: ${String(error)}`);
      }
    }

    const controller = new AbortController();
    const eventsAbort = new AbortController();
    let accumulated = '';
    let sawText = false;
    let started = false;
    let settled = false;
    let fatal: Error | undefined;

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

    const abort = (): void => controller.abort();
    context.signal.addEventListener('abort', abort, { once: true });
    controller.signal.addEventListener(
      'abort',
      () => settle(new Error('Aborted')),
      { once: true },
    );
    this.active.set(context.task.taskId, { sessionId, controller });

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
            started = true;
            sawText = true;
            accumulated += delta;
            context.onChunk('reply', delta);
          } else if (type === 'session.reasoning.delta') {
            const delta = String(data?.delta ?? data?.text ?? '');
            if (delta) context.onChunk('thinking', delta);
          } else if (type === 'session.execution.started') {
            started = true;
          } else if (type === 'session.execution.failed') {
            settle(
              new Error(
                String(
                  data?.error?.message ?? data?.message ?? 'opencode run failed',
                ),
              ),
            );
          } else if (type === 'session.execution.interrupted') {
            settle(new Error('Aborted'));
          } else if (type === 'session.idle') {
            if (started) settle();
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

    /**
     * Only this turn's persisted `text` parts count. Reasoning parts also carry
     * a `text` field, and an earlier turn's reply must never be reported as the
     * current answer.
     */
    const lastAssistantText = async (sinceMs: number): Promise<string> => {
      try {
        const result = await client.message.list({ sessionID: sessionId });
        const list = Array.isArray(result) ? result : (result?.data ?? []);
        for (let index = list.length - 1; index >= 0; index -= 1) {
          const message = list[index];
          if (message?.type !== 'assistant') continue;
          const created = Number(message?.time?.created ?? 0);
          if (created && created < sinceMs - 2_000) continue;
          const parts = Array.isArray(message.content) ? message.content : [];
          const text = parts
            .filter((part: AnyRecord) => part?.type === 'text')
            .map((part: AnyRecord) => String(part.text ?? ''))
            .join('')
            .trim();
          if (text) return text;
        }
      } catch (error) {
        logger.warn(`opencode message lookup failed: ${String(error)}`);
      }
      return '';
    };

    const waitForSession = async (sid: string): Promise<void> => {
      const experimentalWait = (client.experimental as AnyRecord | undefined)
        ?.session?.wait;
      if (typeof experimentalWait === 'function') {
        await experimentalWait.call(
          (client.experimental as AnyRecord).session,
          { sessionID: sid },
        );
        return;
      }
      const legacyWait = (client.session as AnyRecord | undefined)?.wait;
      if (typeof legacyWait === 'function') {
        await legacyWait.call(client.session, { sessionID: sid });
        return;
      }
      await done;
    };

    const timeoutMs = Math.max(1, this.settings.get().taskTimeoutSec * 1000);
    let timedOut = false;
    const timer = setTimeout(() => {
      timedOut = true;
      void this.interrupt(sessionId as string);
      settle(new Error('opencode task timed out'));
      controller.abort();
    }, timeoutMs);

    let promptStartedAt = 0;
    try {
      try {
        promptStartedAt = Date.now();
        await client.session.prompt({
          sessionID: sessionId,
          text: context.prompt,
        });
      } catch (error) {
        if (!isInvalidSessionError(error)) throw error;
        // The shared opencode service was restarted or the session was removed;
        // start a fresh session and retry once.
        logger.warn('opencode session is invalid; starting a new session');
        const session = await client.session.create({
          location: { directory: context.profile.workspace },
          ...(model ? { model } : {}),
        });
        sessionId = String(session.id);
        promptStartedAt = Date.now();
        context.onSessionRef(`${SESSION_PREFIX}${sessionId}`);
        this.active.set(context.task.taskId, { sessionId, controller });
        await client.session.prompt({
          sessionID: sessionId,
          text: context.prompt,
        });
      }

      await Promise.race([waitForSession(sessionId), done]);
      let text = accumulated.trim();
      // The wait can resolve just before the last fragment is observed: poll
      // briefly so an empty accumulator never falls back to stale text.
      for (
        let attempt = 0;
        !text && attempt < REPLY_POLL_ATTEMPTS;
        attempt += 1
      ) {
        if (attempt > 0) await delay(REPLY_POLL_INTERVAL_MS);
        text = accumulated.trim() || (await lastAssistantText(promptStartedAt));
      }
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
