export interface LocalTaskMessage {
  seq: number;
  speaker: string;
  speakerType: string;
  text: string;
  at?: string;
}

export interface LocalTaskPayload {
  taskId: number;
  agentId: number;
  agentName: string;
  thread: {
    id: number;
    title?: string;
    summary?: string;
  };
  persona?: {
    position?: string;
    description?: string;
    instructions?: string;
  };
  trigger?: {
    deliveryIds?: number[];
    eventIds?: number[];
    reason?: string;
    seqFrom?: number;
    seqTo?: number;
  };
  messages: LocalTaskMessage[];
  reply?: {
    maxChars?: number;
    format?: string;
  };
  sessionRef?: string;
  deadlineAt?: string;
}

export interface TaskAckResponse {
  task: LocalTaskPayload;
  leaseToken: string;
}

export interface HeartbeatRequest {
  version: string;
  os: string;
  arch: string;
  runningTaskIds: number[];
}

export interface RuntimeInfo {
  id: number;
  name: string;
  status: string;
  lastSeenAt?: string;
}

export interface StreamEvent {
  type?: string;
  taskId?: number;
  [key: string]: unknown;
}
