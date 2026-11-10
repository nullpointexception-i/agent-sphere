import type { DriverKind, DriverStatus } from '../../../../shared/types';
import type { ApprovalBroker } from '../../approval';

export type JsonRpcId = number;
export type JsonValue = unknown;

export interface JsonRpcError {
  code: number;
  message: string;
  data?: JsonValue;
}

export interface JsonRpcMessage {
  id?: JsonRpcId;
  method?: string;
  params?: JsonValue;
  result?: JsonValue;
  error?: JsonRpcError;
}

export interface InitializeResult {
  userAgent: string;
  codexHome: string;
  platformFamily: string;
  platformOs: string;
}

export interface CodexDiscovery {
  binary?: string;
  version?: string;
  loggedIn?: boolean;
  status: DriverStatus;
}

export interface CodexRunState {
  taskId: number;
  threadId: string;
  turnId?: string;
  text: string;
  error?: string;
  approvals: ApprovalBroker;
  resolve: () => void;
  reject: (error: Error) => void;
  onChunk: (phase: 'reply' | 'thinking', delta: string) => void;
  onStatus: (status: string) => void;
}

export type { DriverKind };
