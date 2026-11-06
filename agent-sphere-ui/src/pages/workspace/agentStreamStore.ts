import { useEffect, useSyncExternalStore } from 'react';
import type { ImStreamPayload } from '@/hooks/useReconnectingSse';

export type AgentStreamPhase =
  | 'waiting'
  | 'thinking'
  | 'tool'
  | 'reply'
  | 'done'
  | 'failed';

export interface AgentStreamState {
  conversationId: number;
  runId?: number;
  agentId?: number;
  phase: AgentStreamPhase;
  thinking: string;
  reply: string;
  toolName?: string;
  displayName?: string;
  streamStatus?: string;
  errorMessage?: string;
  startedAt: number;
  lastStreamSeq: number;
  finalized: boolean;
  finalSeq?: number;
}

interface Store {
  states: Map<number, AgentStreamState>;
  versions: Map<number, number>;
  listeners: Set<() => void>;
}

const store: Store = {
  states: new Map(),
  versions: new Map(),
  listeners: new Set(),
};

function snapshotVersion(conversationId: number) {
  return store.versions.get(conversationId) ?? 0;
}

function notify(conversationId: number) {
  store.versions.set(conversationId, snapshotVersion(conversationId) + 1);
  for (const listener of store.listeners) {
    listener();
  }
}

function currentState(conversationId: number): AgentStreamState | undefined {
  return store.states.get(conversationId);
}

function beginState(conversationId: number): AgentStreamState {
  return {
    conversationId,
    phase: 'waiting',
    thinking: '',
    reply: '',
    startedAt: Date.now(),
    lastStreamSeq: 0,
    finalized: false,
  };
}

/** Called right after the user sends a DM message: shows the immediate waiting bubble. */
export function beginAgentStream(conversationId: number) {
  if (!conversationId) return;
  store.states.set(conversationId, beginState(conversationId));
  notify(conversationId);
}

export function resetAgentStream(conversationId: number) {
  if (!store.states.has(conversationId)) return;
  store.states.delete(conversationId);
  notify(conversationId);
}

/**
 * Marks the stream as superseded by the authoritative agent message. The bubble
 * stays visible (phase=done) until the caller clears it after merging messages.
 */
export function finalizeAgentStream(conversationId: number, finalSeq?: number) {
  const state = currentState(conversationId);
  if (!state || state.finalized) return;
  store.states.set(conversationId, {
    ...state,
    phase: 'done',
    finalized: true,
    finalSeq,
  });
  notify(conversationId);
}

export function failAgentStream(conversationId: number, message: string) {
  const state = currentState(conversationId);
  if (!state) return;
  store.states.set(conversationId, {
    ...state,
    phase: 'failed',
    finalized: true,
    errorMessage: message,
  });
  notify(conversationId);
}

function phaseFromEvent(state: AgentStreamState, event: ImStreamPayload) {
  const status = event.streamStatus;
  if (status === 'failed' || status === 'cancelled')
    return 'failed' as AgentStreamPhase;
  if (status === 'completed') return 'done' as AgentStreamPhase;
  if (event.phase === 'thinking') return 'thinking' as AgentStreamPhase;
  if (event.phase === 'reply') return 'reply' as AgentStreamPhase;
  if (event.phase === 'status' && String(status ?? '').startsWith('tool_')) {
    return 'tool' as AgentStreamPhase;
  }
  return state.phase;
}

/** Consumes one `agent_stream` SSE payload for its conversation. */
export function applyAgentStreamEvent(event: ImStreamPayload) {
  const conversationId = event.conversationId;
  if (!conversationId) return;
  const existing = currentState(conversationId);
  if (!existing) return;
  if (existing.finalized && event.runId && event.runId === existing.runId) {
    return;
  }
  if (event.runId && existing.runId && event.runId !== existing.runId) {
    // A newer run replaced the current one: restart the bubble.
    store.states.set(conversationId, {
      ...beginState(conversationId),
      runId: event.runId,
    });
    notify(conversationId);
    return;
  }

  const seq = event.streamSeq ?? 0;
  const streamSeq = event.phase === 'status' ? existing.lastStreamSeq : seq;
  if (event.phase !== 'status' && seq && seq <= existing.lastStreamSeq) {
    return;
  }

  const phase = phaseFromEvent(existing, event);
  const next: AgentStreamState = {
    ...existing,
    runId: event.runId ?? existing.runId,
    agentId: event.agentId ?? existing.agentId,
    phase,
    streamStatus: event.streamStatus ?? existing.streamStatus,
    lastStreamSeq: streamSeq || existing.lastStreamSeq,
  };

  if (event.phase === 'thinking') {
    next.thinking = existing.thinking + (event.delta ?? '');
  } else if (event.phase === 'reply') {
    next.reply = existing.reply + (event.delta ?? '');
  } else if (event.phase === 'status') {
    const status = String(event.streamStatus ?? '');
    if (status.startsWith('tool_')) {
      next.toolName = event.toolName ?? undefined;
      next.displayName = event.displayName ?? undefined;
      if (status === 'tool_pending') {
        // Tool turn started: the reply draft restarts after the tool completes.
        next.reply = '';
      }
    } else if (status === 'failed' || status === 'cancelled') {
      next.errorMessage = event.errorMessage ?? undefined;
    }
  }

  store.states.set(conversationId, next);
  notify(conversationId);
}

function subscribe(listener: () => void) {
  store.listeners.add(listener);
  return () => {
    store.listeners.delete(listener);
  };
}

/** Read-only snapshot accessor (also used by tests). */
export function peekAgentStream(conversationId: number) {
  return currentState(conversationId);
}

/** Subscribes a chat panel to its conversation's ephemeral agent stream. */
export function useAgentStream(conversationId: number) {
  const state = useSyncExternalStore(
    subscribe,
    () => currentState(conversationId),
    () => currentState(conversationId),
  );

  useEffect(() => {
    // Prune finalized streams once the authoritative message has been merged.
    if (state?.finalized && state.phase === 'done') {
      const timer = window.setTimeout(() => {
        const current = currentState(conversationId);
        if (current?.finalized && current.phase === 'done') {
          store.states.delete(conversationId);
          notify(conversationId);
        }
      }, 600);
      return () => window.clearTimeout(timer);
    }
    return undefined;
  }, [conversationId, state?.finalized, state?.phase, state?.startedAt]);

  return state;
}
