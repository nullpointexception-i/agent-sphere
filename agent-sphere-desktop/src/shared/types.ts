export type DriverKind = 'OPENCODE' | 'CODEX';

export type RuntimeState =
  | 'UNCONFIGURED'
  | 'CONNECTING'
  | 'ONLINE'
  | 'OFFLINE'
  | 'PAUSED';

export interface DesktopSettings {
  serverUrl: string;
  webOrigin: string;
  /** Stable per-install key; the backend allows one runtime per device key. */
  deviceKey: string;
  /** Legacy single-runtime id; new builds keep one binding per server origin. */
  runtimeId?: number;
  /** Runtime id per normalized server origin, so local and online keep their own binding. */
  environments?: Record<string, DesktopEnvironment>;
  /** Recently used server origins, newest first. */
  recentServers?: string[];
  runtimeName: string;
  /** Desktop chrome language; the web UI keeps its own locale switcher. */
  language: 'system' | 'zh-CN' | 'en-US';
  startAtLogin: boolean;
  closeToTray: boolean;
  maxConcurrentTasks: number;
  taskTimeoutSec: number;
  approvalTimeoutSec: number;
  autoApprove: {
    readOnly: boolean;
    network: boolean;
    fileWrite: boolean;
    shell: boolean;
  };
  telemetryEnabled: boolean;
  codexPath?: string;
}

export interface DesktopEnvironment {
  runtimeId?: number;
}

export interface AgentProfile {
  agentId: number;
  agentName: string;
  driver: DriverKind;
  workspace: string;
  additionalDirs?: string[];
  /** opencode only: `provider/model`; empty keeps the local default. */
  model?: string;
  approvalPolicy: 'untrusted' | 'on-request' | 'never';
  sandbox: 'read-only' | 'workspace-write' | 'danger-full-access';
  autoRun: 'always' | 'mention-only' | 'confirm';
  enabled: boolean;
}

export type DriverState =
  | 'MISSING'
  | 'DETECTED'
  | 'AUTH_REQUIRED'
  | 'READY'
  /** Outside the tested version range, but still allowed to run. Surface it as a warning only. */
  | 'VERSION_MISMATCH'
  | 'ERROR';

export interface DriverStatus {
  kind: DriverKind;
  state: DriverState;
  version?: string;
  path?: string;
  message?: string;
}

/** Version mismatches are advisory: detection should not block a task from running. */
export function isDriverStateUsable(state: DriverState): boolean {
  return state === 'READY' || state === 'VERSION_MISMATCH';
}

export interface DriverModel {
  providerID: string;
  id: string;
  name: string;
}

export interface RuntimeStatus {
  state: RuntimeState;
  since: number;
  message?: string;
  runningTasks: number;
  driverStatus: DriverStatus[];
}

export type TaskState =
  | 'QUEUED'
  | 'RECEIVED'
  | 'RUNNING'
  | 'WAITING_APPROVAL'
  | 'COMPLETED'
  | 'FAILED'
  | 'CANCELLED';

export interface TaskView {
  taskId: number;
  agentId?: number;
  agentName?: string;
  threadId?: number;
  threadTitle?: string;
  status: TaskState;
  startedAt?: number;
  finishedAt?: number;
  error?: string;
}

/**
 * One optimistic streaming fragment produced by a local driver. It is rendered
 * immediately by the desktop UI; the same delta is uploaded to the backend,
 * whose SSE stream stays authoritative.
 */
export interface TaskChunk {
  taskId: number;
  agentId: number;
  threadId: number;
  seq: number;
  phase: 'reply' | 'thinking';
  delta: string;
  at: number;
}

export type ApprovalCategory = 'read' | 'write' | 'shell' | 'network' | 'input';

export interface ApprovalView {
  id: string;
  taskId: number;
  kind: string;
  category: ApprovalCategory;
  title: string;
  detail: string;
  command?: string;
  cwd?: string;
  createdAt: number;
}

export type ApprovalDecision =
  | 'accept'
  | 'acceptForSession'
  | 'decline'
  | 'cancel';

export interface DesktopInfo {
  appVersion: string;
  electronVersion: string;
  platform: string;
  arch: string;
  serverUrl: string;
  runtimeId?: number;
  configured: boolean;
}

export interface BootstrapConfig {
  desktop: true;
  apiOrigin: string;
  webOrigin: string;
  appVersion: string;
  platform: string;
  configured: boolean;
}

export interface RuntimeConfigureInput {
  serverUrl: string;
  deviceToken: string;
  runtimeId: number;
  runtimeName?: string;
}

export interface ConnectionTestResult {
  ok: boolean;
  status?: number;
  message?: string;
}

export interface DesktopBridge {
  info(): Promise<DesktopInfo>;
  settings: {
    get(): Promise<DesktopSettings>;
    update(patch: Partial<DesktopSettings>): Promise<DesktopSettings>;
    test(serverUrl: string): Promise<ConnectionTestResult>;
  };
  runtime: {
    status(): Promise<RuntimeStatus>;
    configure(input: RuntimeConfigureInput): Promise<RuntimeStatus>;
    pause(): Promise<void>;
    resume(): Promise<void>;
  };
  drivers: {
    detect(): Promise<DriverStatus[]>;
    models(kind: DriverKind): Promise<DriverModel[]>;
  };
  profiles: {
    list(): Promise<AgentProfile[]>;
    save(profile: AgentProfile): Promise<AgentProfile[]>;
    remove(agentId: number): Promise<AgentProfile[]>;
    pickWorkspace(): Promise<string | null>;
  };
  tasks: {
    list(): Promise<TaskView[]>;
    cancel(taskId: number): Promise<void>;
  };
  approvals: {
    list(): Promise<ApprovalView[]>;
    respond(id: string, decision: ApprovalDecision): Promise<void>;
    onRequest(callback: (request: ApprovalView) => void): () => void;
  };
  logs: {
    open(): Promise<void>;
    export(): Promise<string>;
  };
  events: {
    onRuntime(callback: (status: RuntimeStatus) => void): () => void;
    onTask(callback: (task: TaskView) => void): () => void;
    onChunk(callback: (chunk: TaskChunk) => void): () => void;
  };
}
