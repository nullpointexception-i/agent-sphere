import { spawn, type ChildProcessWithoutNullStreams } from 'node:child_process';
import readline from 'node:readline';
import type {
  InitializeResult,
  JsonRpcId,
  JsonRpcMessage,
} from './types';
import { logger } from '../../../logging/logger';

interface PendingRequest {
  resolve: (value: unknown) => void;
  reject: (error: Error) => void;
  timer: NodeJS.Timeout;
}

export class RpcError extends Error {
  constructor(
    readonly code: number,
    message: string,
  ) {
    super(message);
    this.name = 'RpcError';
  }
}

type NotificationHandler = (method: string, params: unknown) => void;
type ServerRequestHandler = (method: string, params: unknown) => Promise<unknown>;

/**
 * JSON-RPC 2.0 over newline-delimited JSON on stdio. Codex omits the "jsonrpc"
 * header on the wire; ids are still required for requests/responses.
 */
export class CodexAppServerClient {
  private process?: ChildProcessWithoutNullStreams;
  private nextId = 1;
  private readonly pending = new Map<JsonRpcId, PendingRequest>();
  private notificationHandler: NotificationHandler = () => undefined;
  private serverRequestHandler: ServerRequestHandler = async () => {
    throw new RpcError(-32001, 'no server request handler');
  };
  private stderrTail: string[] = [];

  async start(binary: string): Promise<void> {
    if (this.process) return;
    this.process = spawn(binary, ['app-server'], {
      stdio: ['pipe', 'pipe', 'pipe'],
      windowsHide: true,
    });

    const lines = readline.createInterface({ input: this.process.stdout });
    lines.on('line', (line) => this.handleLine(line));

    this.process.stderr.on('data', (chunk: Buffer) => {
      const text = chunk.toString('utf8').trim();
      if (!text) return;
      this.stderrTail.push(text);
      if (this.stderrTail.length > 20) this.stderrTail.shift();
      logger.debug(`[codex] ${text}`);
    });

    this.process.on('error', (error) => {
      this.failAll(error instanceof Error ? error : new Error(String(error)));
    });
    this.process.on('exit', (code) => {
      this.failAll(new Error(`codex app-server exited (${code ?? 'unknown'}) ${this.stderrTail.join(' | ')}`));
    });
  }

  async initialize(clientInfo: {
    name: string;
    title: string;
    version: string;
  }): Promise<InitializeResult> {
    const result = (await this.request('initialize', {
      clientInfo,
      capabilities: null,
    })) as InitializeResult;
    this.notify('initialized', {});
    return result;
  }

  onNotification(handler: NotificationHandler): void {
    this.notificationHandler = handler;
  }

  onServerRequest(handler: ServerRequestHandler): void {
    this.serverRequestHandler = handler;
  }

  request(method: string, params: unknown, timeoutMs = 30_000): Promise<unknown> {
    if (!this.process?.stdin.writable) {
      return Promise.reject(new Error('codex app-server is not running'));
    }
    const id = this.nextId++;
    return new Promise<unknown>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`codex request timeout: ${method}`));
      }, timeoutMs);
      this.pending.set(id, { resolve, reject, timer });
      this.write({ method, id, params });
    });
  }

  notify(method: string, params: unknown): void {
    this.write({ method, params });
  }

  respond(id: JsonRpcId, result: unknown): void {
    this.write({ id, result });
  }

  respondError(id: JsonRpcId, code: number, message: string): void {
    this.write({ id, error: { code, message } });
  }

  dispose(): void {
    this.failAll(new Error('codex app-server disposed'));
    this.process?.kill();
    this.process = undefined;
  }

  getStderrTail(): string {
    return this.stderrTail.join('\n');
  }

  private write(message: JsonRpcMessage): void {
    try {
      this.process?.stdin.write(`${JSON.stringify(message)}\n`);
    } catch (error) {
      logger.warn(`codex write failed: ${String(error)}`);
    }
  }

  private handleLine(line: string): void {
    let message: JsonRpcMessage;
    try {
      message = JSON.parse(line) as JsonRpcMessage;
    } catch {
      return;
    }

    if (message.id !== undefined && message.method) {
      void this.handleServerRequest(message.id, message.method, message.params);
      return;
    }

    if (message.id !== undefined) {
      const pending = this.pending.get(message.id);
      if (!pending) return;
      this.pending.delete(message.id);
      clearTimeout(pending.timer);
      if (message.error) {
        pending.reject(new RpcError(message.error.code, message.error.message));
      } else {
        pending.resolve(message.result);
      }
      return;
    }

    if (message.method) {
      try {
        this.notificationHandler(message.method, message.params);
      } catch (error) {
        logger.warn(`codex notification handler failed: ${String(error)}`);
      }
    }
  }

  private async handleServerRequest(
    id: JsonRpcId,
    method: string,
    params: unknown,
  ): Promise<void> {
    try {
      const result = await this.serverRequestHandler(method, params);
      this.respond(id, result ?? {});
    } catch (error) {
      if (error instanceof RpcError) {
        this.respondError(id, error.code, error.message);
      } else {
        this.respondError(id, -32000, error instanceof Error ? error.message : String(error));
      }
    }
  }

  private failAll(error: Error): void {
    for (const pending of this.pending.values()) {
      clearTimeout(pending.timer);
      pending.reject(error);
    }
    this.pending.clear();
  }
}
