export interface DesktopBridgeStatus {
  state: 'UNCONFIGURED' | 'CONNECTING' | 'ONLINE' | 'OFFLINE' | 'PAUSED';
  message?: string;
  runningTasks: number;
}

export interface DesktopEnvironmentBinding {
  runtimeId?: number;
}

export interface DesktopBridgeSettings {
  serverUrl: string;
  webOrigin: string;
  deviceKey: string;
  runtimeId?: number;
  environments?: Record<string, DesktopEnvironmentBinding>;
  recentServers?: string[];
  runtimeName: string;
  language: 'system' | 'zh-CN' | 'en-US';
}

export interface DesktopConnectionTestResult {
  ok: boolean;
  status?: number;
  message?: string;
}

export type DesktopDriverKind = 'OPENCODE' | 'CODEX';
export type DesktopApprovalDecision =
  | 'accept'
  | 'acceptForSession'
  | 'decline'
  | 'cancel';

export interface DesktopBridgeDriver {
  kind: DesktopDriverKind;
  state: string;
  version?: string;
  path?: string;
  message?: string;
}

export interface DesktopBridgeModel {
  providerID: string;
  id: string;
  name: string;
}

export interface DesktopBridgeTask {
  taskId: number;
  agentId?: number;
  agentName?: string;
  threadId?: number;
  threadTitle?: string;
  status: string;
  startedAt?: number;
  finishedAt?: number;
  error?: string;
}

/** One optimistic fragment emitted straight by the desktop runtime. */
export interface DesktopBridgeChunk {
  taskId: number;
  agentId: number;
  threadId: number;
  seq: number;
  phase: 'reply' | 'thinking';
  delta: string;
  at: number;
}

export interface DesktopAgentProfile {
  agentId: number;
  agentName: string;
  driver: DesktopDriverKind;
  workspace: string;
  additionalDirs?: string[];
  model?: string;
  approvalPolicy: 'untrusted' | 'on-request' | 'never';
  sandbox: 'read-only' | 'workspace-write' | 'danger-full-access';
  autoRun: 'always' | 'mention-only' | 'confirm';
  enabled: boolean;
}

export interface DesktopBridgeApproval {
  id: string;
  taskId: number;
  kind: string;
  category: string;
  title: string;
  detail: string;
  command?: string;
  cwd?: string;
  createdAt: number;
}

export interface DesktopBridge {
  settings: {
    get(): Promise<DesktopBridgeSettings>;
    update(
      patch: Partial<DesktopBridgeSettings>,
    ): Promise<DesktopBridgeSettings>;
    test(serverUrl: string): Promise<DesktopConnectionTestResult>;
  };
  runtime: {
    status(): Promise<DesktopBridgeStatus>;
    configure(input: {
      serverUrl: string;
      deviceToken: string;
      runtimeId: number;
      runtimeName?: string;
    }): Promise<DesktopBridgeStatus>;
    pause(): Promise<void>;
    resume(): Promise<void>;
  };
  drivers: {
    detect(): Promise<DesktopBridgeDriver[]>;
    models(kind: DesktopDriverKind): Promise<DesktopBridgeModel[]>;
  };
  profiles: {
    list(): Promise<DesktopAgentProfile[]>;
    save(profile: DesktopAgentProfile): Promise<DesktopAgentProfile[]>;
    remove(agentId: number): Promise<DesktopAgentProfile[]>;
    pickWorkspace(): Promise<string | null>;
  };
  tasks: {
    list(): Promise<DesktopBridgeTask[]>;
    cancel(taskId: number): Promise<void>;
  };
  approvals: {
    list(): Promise<DesktopBridgeApproval[]>;
    respond(id: string, decision: DesktopApprovalDecision): Promise<void>;
    onRequest(callback: (request: DesktopBridgeApproval) => void): () => void;
  };
  events: {
    onRuntime(callback: (status: DesktopBridgeStatus) => void): () => void;
    onTask(callback: (task: DesktopBridgeTask) => void): () => void;
    onChunk(callback: (chunk: DesktopBridgeChunk) => void): () => void;
  };
}

export function getDesktopBridge(): DesktopBridge | undefined {
  return window.agentSphereDesktop as DesktopBridge | undefined;
}
