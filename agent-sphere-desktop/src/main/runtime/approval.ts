import { randomUUID } from 'node:crypto';
import type {
  ApprovalCategory,
  ApprovalDecision,
  ApprovalView,
} from '../../shared/types';
import type { SettingsStore } from '../config/settings';
import { logger } from '../logging/logger';

interface PendingApproval {
  request: ApprovalView;
  resolve: (decision: ApprovalDecision) => void;
  timer: NodeJS.Timeout;
}

export class ApprovalBroker {
  private readonly pending = new Map<string, PendingApproval>();

  constructor(
    private readonly settings: SettingsStore,
    private readonly emit: (request: ApprovalView) => void,
  ) {}

  list(): ApprovalView[] {
    return [...this.pending.values()].map((item) => item.request);
  }

  async request(input: Omit<ApprovalView, 'id' | 'createdAt'>): Promise<ApprovalDecision> {
    if (this.isAutoApproved(input.category)) {
      return 'accept';
    }

    const request: ApprovalView = {
      ...input,
      id: randomUUID(),
      createdAt: Date.now(),
    };
    this.emit(request);

    return new Promise<ApprovalDecision>((resolve) => {
      const timeoutSec = Math.max(10, this.settings.get().approvalTimeoutSec);
      const timer = setTimeout(() => {
        logger.warn(`approval timeout: ${request.id} ${request.kind}`);
        this.resolve(request.id, 'decline');
      }, timeoutSec * 1000);
      this.pending.set(request.id, { request, resolve, timer });
    });
  }

  respond(id: string, decision: ApprovalDecision): void {
    this.resolve(id, decision);
  }

  private resolve(id: string, decision: ApprovalDecision): void {
    const pending = this.pending.get(id);
    if (!pending) return;
    clearTimeout(pending.timer);
    this.pending.delete(id);
    pending.resolve(decision);
  }

  private isAutoApproved(category: ApprovalCategory): boolean {
    const policy = this.settings.get().autoApprove;
    switch (category) {
      case 'read':
        return policy.readOnly;
      case 'write':
        return policy.fileWrite;
      case 'shell':
        return policy.shell;
      case 'network':
        return policy.network;
      case 'input':
      default:
        return false;
    }
  }
}
