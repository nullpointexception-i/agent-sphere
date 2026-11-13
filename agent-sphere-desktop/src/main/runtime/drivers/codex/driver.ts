import { app } from 'electron';
import type {
  AgentProfile,
  ApprovalCategory,
  ApprovalDecision,
  DriverStatus,
} from '../../../../shared/types';
import { isDriverStateUsable } from '../../../../shared/types';
import type { LocalTaskPayload } from '../../../api/contracts';
import type { DriverRunContext, DriverRunResult, RuntimeDriver } from '../types';
import { logger } from '../../../logging/logger';
import { CodexAppServerClient, RpcError } from './client';
import { discoverCodex } from './discovery';
import type { CodexRunState, InitializeResult } from './types';

const SESSION_PREFIX = 'codex:';
const SUPPORTED_PREFIX = '0.167.';
const MAX_THREAD_NAME = 200;

function parseSessionRef(ref?: string): string | undefined {
  if (!ref) return undefined;
  return ref.startsWith(SESSION_PREFIX) ? ref.slice(SESSION_PREFIX.length) : ref;
}

function sandboxPolicy(mode: AgentProfile['sandbox'], workspace: string): unknown {
  switch (mode) {
    case 'read-only':
      return { type: 'readOnly', networkAccess: false };
    case 'danger-full-access':
      return { type: 'dangerFullAccess' };
    case 'workspace-write':
    default:
      return {
        type: 'workspaceWrite',
        writableRoots: [workspace],
        networkAccess: false,
        excludeTmpdirEnvVar: false,
        excludeSlashTmp: false,
      };
  }
}

function toDecision(decision: ApprovalDecision): string {
  switch (decision) {
    case 'acceptForSession':
      return 'acceptForSession';
    case 'decline':
      return 'decline';
    case 'cancel':
      return 'cancel';
    case 'accept':
    default:
      return 'accept';
  }
}

function permissionCategory(params: Record<string, unknown>): ApprovalCategory {
  const permissions = params.permissions as Record<string, unknown> | undefined;
  if (permissions?.network) return 'network';
  if (permissions?.fileSystem) return 'write';
  return 'write';
}

export class CodexDriver implements RuntimeDriver {
  readonly kind = 'CODEX' as const;

  private client?: CodexAppServerClient;
  private initPromise?: Promise<CodexAppServerClient>;
  private readonly runs = new Map<string, CodexRunState>();
  private disposed = false;

  constructor(private readonly settings: {
    get(): { codexPath?: string; taskTimeoutSec: number };
  }) {}

  async detect(): Promise<DriverStatus> {
    const discovery = await discoverCodex(this.settings.get().codexPath);
    return discovery.status;
  }

  async run(context: DriverRunContext): Promise<DriverRunResult> {
    const client = await this.ensureClient();
    if (process.platform === 'win32') {
      const readiness = (await client.request(
        'windowsSandbox/readiness',
        null,
        10_000,
      )) as { status?: string };
      if (readiness?.status && readiness.status !== 'ready') {
        throw new Error(`Windows sandbox is not ready: ${readiness.status}`);
      }
    }

    let threadId = parseSessionRef(context.task.sessionRef);
    if (threadId) {
      try {
        await client.request(
          'thread/resume',
          {
            threadId,
            cwd: context.profile.workspace,
            approvalPolicy: context.profile.approvalPolicy,
            sandbox: context.profile.sandbox,
          },
          30_000,
        );
      } catch (error) {
        logger.warn(`codex resume failed, starting a new thread: ${String(error)}`);
        threadId = undefined;
      }
    }

    if (!threadId) {
      const started = (await client.request(
        'thread/start',
        {
          cwd: context.profile.workspace,
          approvalPolicy: context.profile.approvalPolicy,
          sandbox: context.profile.sandbox,
          serviceName: 'agentsphere',
          threadSource: 'appServer',
        },
        30_000,
      )) as { thread: { id: string } };
      threadId = started.thread.id;
    }

    context.onSessionRef(`${SESSION_PREFIX}${threadId}`);

    const threadName = `AgentSphere · ${context.task.agentName} · ${
      context.task.thread.title ?? `#${context.task.thread.id}`
    }`.slice(0, MAX_THREAD_NAME);
    void client
      .request('thread/name/set', { threadId, name: threadName }, 10_000)
      .catch(() => undefined);

    let resolveRun!: () => void;
    let rejectRun!: (error: Error) => void;
    const completion = new Promise<void>((resolve, reject) => {
      resolveRun = resolve;
      rejectRun = reject;
    });
    void completion.catch(() => undefined);

    const running: CodexRunState = {
      taskId: context.task.taskId,
      threadId,
      text: '',
      approvals: context.approvals,
      resolve: resolveRun,
      reject: rejectRun,
      onChunk: context.onChunk,
      onStatus: context.onStatus,
    };
    this.runs.set(threadId, running);

    const deadline = context.task.deadlineAt
      ? new Date(context.task.deadlineAt).getTime()
      : Number.POSITIVE_INFINITY;
    const timeoutMs = Math.max(
      1,
      Math.min(this.settings.get().taskTimeoutSec * 1000, deadline - Date.now()),
    );
    const onAbort = (): void => {
      void this.interrupt(threadId, running.turnId);
      rejectRun(new Error('Aborted'));
    };
    context.signal.addEventListener('abort', onAbort, { once: true });
    const timer = setTimeout(() => {
      void this.interrupt(threadId, running.turnId);
      rejectRun(new Error('Codex task timed out'));
    }, timeoutMs);

    try {
      const started = (await client.request(
        'turn/start',
        {
          threadId,
          input: [{ type: 'text', text: context.prompt, text_elements: [] }],
          cwd: context.profile.workspace,
          approvalPolicy: context.profile.approvalPolicy,
          sandboxPolicy: sandboxPolicy(context.profile.sandbox, context.profile.workspace),
        },
        30_000,
      )) as { turn: { id: string } };
      running.turnId = running.turnId ?? started.turn.id;
      context.onStatus('RUNNING');
      await completion;
    } finally {
      clearTimeout(timer);
      context.signal.removeEventListener('abort', onAbort);
      this.runs.delete(threadId);
    }

    let text = running.text.trim();
    if (!text) {
      text = (await this.latestAgentText(client, threadId)) ?? '';
    }
    if (!text) {
      throw new Error('Codex completed without an agent message');
    }
    return {
      text,
      sessionRef: `${SESSION_PREFIX}${threadId}`,
    };
  }

  async cancel(taskId: number): Promise<void> {
    for (const [threadId, run] of this.runs) {
      if (run.taskId !== taskId) continue;
      await this.interrupt(threadId, run.turnId);
    }
  }

  dispose(): void {
    this.disposed = true;
    this.client?.dispose();
    this.client = undefined;
    this.runs.clear();
  }

  private async ensureClient(): Promise<CodexAppServerClient> {
    if (this.disposed) throw new Error('codex driver disposed');
    if (this.client) return this.client;
    if (this.initPromise) return this.initPromise;

    this.initPromise = (async () => {
      const discovery = await discoverCodex(this.settings.get().codexPath);
      if (!discovery.binary || !isDriverStateUsable(discovery.status.state)) {
        throw new Error(discovery.status.message ?? 'Codex is not ready');
      }

      const client = new CodexAppServerClient();
      client.onNotification((method, params) => this.handleNotification(method, params));
      client.onServerRequest((method, params) => this.handleServerRequest(method, params));
      await client.start(discovery.binary);
      const info = (await client.initialize({
        name: 'agentsphere',
        title: 'AgentSphere',
        version: app.getVersion(),
      })) as InitializeResult;

      if (!info.userAgent.includes(SUPPORTED_PREFIX)) {
        logger.warn(
          `Codex version ${info.userAgent} is outside the tested ${SUPPORTED_PREFIX}x range; continuing anyway`,
        );
      }
      logger.info(`codex app-server connected: ${info.userAgent}`);
      this.client = client;
      return client;
    })().finally(() => {
      this.initPromise = undefined;
    });

    return this.initPromise;
  }

  private handleNotification(method: string, raw: unknown): void {
    const params = (raw ?? {}) as Record<string, unknown>;
    const threadId =
      (params.threadId as string | undefined) ??
      ((params.thread as Record<string, unknown> | undefined)?.id as string | undefined);
    if (!threadId) return;

    const run = this.runs.get(threadId);
    if (method === 'turn/started') {
      const turn = params.turn as { id?: string } | undefined;
      if (run && turn?.id) run.turnId = turn.id;
      return;
    }
    if (!run) return;

    switch (method) {
      case 'item/agentMessage/delta': {
        const delta = params.delta as string | undefined;
        if (!delta) return;
        run.text += delta;
        run.onChunk('reply', delta);
        break;
      }
      case 'item/reasoning/textDelta':
      case 'item/reasoning/summaryTextDelta': {
        const delta = params.delta as string | undefined;
        if (delta) run.onChunk('thinking', delta);
        break;
      }
      case 'item/completed': {
        const item = params.item as { type?: string; text?: string } | undefined;
        if (item?.type === 'agentMessage' && typeof item.text === 'string') {
          run.text = item.text;
        }
        break;
      }
      case 'turn/completed': {
        const turn = params.turn as
          | { id?: string; status?: string; error?: { message?: string } }
          | undefined;
        if (run.turnId && turn?.id && turn.id !== run.turnId) return;
        if (turn?.status === 'completed') {
          run.resolve();
        } else {
          run.reject(
            new Error(turn?.error?.message ?? `Codex turn ${turn?.status ?? 'failed'}`),
          );
        }
        break;
      }
      case 'error': {
        const error = params.error as { message?: string } | undefined;
        run.error = error?.message;
        if (params.willRetry !== true && error?.message) {
          logger.warn(`codex turn error: ${error.message}`);
        }
        break;
      }
      case 'thread/status/changed': {
        const status = params.status as { activeFlags?: string[] } | undefined;
        const flags = status?.activeFlags ?? [];
        if (flags.includes('waitingOnApproval')) run.onStatus('WAITING_APPROVAL');
        else if (flags.includes('waitingOnUserInput')) run.onStatus('WAITING_INPUT');
        else run.onStatus('RUNNING');
        break;
      }
      default:
        break;
    }
  }

  private async handleServerRequest(method: string, raw: unknown): Promise<unknown> {
    const params = (raw ?? {}) as Record<string, unknown>;
    const threadId = params.threadId as string | undefined;
    const run = threadId ? this.runs.get(threadId) : undefined;
    if (!run) {
      throw new RpcError(-32001, 'no active Codex task for this request');
    }

    switch (method) {
      case 'item/commandExecution/requestApproval': {
        const command = params.command as string | undefined;
        const cwd = params.cwd as string | undefined;
        const reason = params.reason as string | undefined;
        const decision = await run.approvals.request({
          taskId: run.taskId,
          kind: method,
          category: 'shell',
          title: 'Local avatar requests to run a command',
          detail: [command, cwd, reason].filter(Boolean).join('\n'),
          command,
          cwd,
        });
        return { decision: toDecision(decision) };
      }
      case 'item/fileChange/requestApproval': {
        const reason = params.reason as string | undefined;
        const decision = await run.approvals.request({
          taskId: run.taskId,
          kind: method,
          category: 'write',
          title: 'Local avatar requests to modify files',
          detail: reason ?? 'File write outside the sandbox',
        });
        return { decision: toDecision(decision) };
      }
      case 'item/permissions/requestApproval': {
        const decision = await run.approvals.request({
          taskId: run.taskId,
          kind: method,
          category: permissionCategory(params),
          title: 'Local avatar requests extra permissions',
          detail: JSON.stringify(params.permissions ?? {}, null, 2).slice(0, 2000),
        });
        if (decision === 'decline' || decision === 'cancel') {
          throw new RpcError(-32002, 'permission denied by user');
        }
        return {
          permissions: params.permissions ?? {},
          scope: decision === 'acceptForSession' ? 'session' : 'turn',
        };
      }
      case 'item/tool/requestUserInput': {
        const questions = (params.questions as unknown[]) ?? [];
        const decision = await run.approvals.request({
          taskId: run.taskId,
          kind: method,
          category: 'input',
          title: 'Local avatar needs more information',
          detail: JSON.stringify(questions, null, 2).slice(0, 2000),
        });
        if (decision === 'decline' || decision === 'cancel') {
          throw new RpcError(-32003, 'user input declined');
        }
        // v1: structured answers are not wired into the UI yet; ask the agent to continue.
        return { answers: {} };
      }
      case 'mcpServer/elicitation/request':
        throw new RpcError(-32004, 'MCP elicitation is not supported yet');
      default:
        throw new RpcError(-32601, `unsupported Codex request: ${method}`);
    }
  }

  private async interrupt(threadId: string, turnId?: string): Promise<void> {
    if (!this.client || !turnId) return;
    try {
      await this.client.request('turn/interrupt', { threadId, turnId }, 5_000);
    } catch (error) {
      logger.warn(`codex interrupt failed: ${String(error)}`);
    }
  }

  private async latestAgentText(
    client: CodexAppServerClient,
    threadId: string,
  ): Promise<string | undefined> {
    try {
      const result = (await client.request(
        'thread/items/list',
        { threadId, limit: 20, sortDirection: 'desc' },
        10_000,
      )) as { data?: Array<{ item?: { type?: string; text?: string } }> };
      for (const row of result.data ?? []) {
        if (row.item?.type === 'agentMessage' && row.item.text) return row.item.text;
      }
    } catch {
      // best effort
    }
    return undefined;
  }
}
