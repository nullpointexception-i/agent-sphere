import type {
  AgentProfile,
  DriverKind,
  DriverModel,
  DriverStatus,
} from '../../../shared/types';
import type { LocalTaskPayload } from '../../api/contracts';
import type { ApprovalBroker } from '../approval';

export interface DriverRunContext {
  task: LocalTaskPayload;
  profile: AgentProfile;
  prompt: string;
  signal: AbortSignal;
  approvals: ApprovalBroker;
  onChunk(phase: 'reply' | 'thinking', delta: string): void;
  onSessionRef(sessionRef: string): void;
  onStatus(status: string): void;
}

export interface DriverRunResult {
  text: string;
  sessionRef?: string;
}

export interface RuntimeDriver {
  readonly kind: DriverKind;
  detect(): Promise<DriverStatus>;
  /** Models the local runtime can run; empty when the driver has no catalog. */
  listModels?(): Promise<DriverModel[]>;
  run(context: DriverRunContext): Promise<DriverRunResult>;
  cancel(taskId: number): Promise<void>;
  dispose(): void;
}
