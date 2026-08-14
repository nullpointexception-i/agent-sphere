/**
 * Passive reasoning/thinking stream for the selected session.
 *
 * Task-triggered runs (and any run not started by this widget) publish their
 * reasoning to the runtime SSE stream (`/api/v1/runtime/{sessionId}/stream`),
 * which the widget otherwise never receives — CopilotKit only renders runs it
 * itself initiated. This helper bridges that stream so the chatbox can show
 * the live "Thinking" block, mirroring the main UI behavior.
 */

export interface ReasoningStreamOptions {
  url: string;
  token: string;
  onReasoning: (runId: string, delta: string) => void;
  signal: AbortSignal;
}

const REASONING_SYSTEM_TYPE = 'system';
const I18N_PREFIX = '__i18n:';

export async function connectReasoningStream(options: ReasoningStreamOptions): Promise<void> {
  const { url, token, onReasoning, signal } = options;
  try {
    const response = await fetch(url, {
      headers: {
        Authorization: `Bearer ${token}`,
        Accept: 'text/event-stream',
        'Cache-Control': 'no-cache',
      },
      signal,
    });
    if (!response.ok || !response.body) {
      return;
    }
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    while (true) {
      const { done, value } = await reader.read();
      if (done) {
        break;
      }
      buffer += decoder.decode(value, { stream: true });
      const parts = buffer.split('\n\n');
      buffer = parts.pop() || '';
      for (const part of parts) {
        const dataLine = extractDataLine(part);
        if (!dataLine) {
          continue;
        }
        handleLine(dataLine, onReasoning);
      }
    }
  } catch (e) {
    if (e instanceof DOMException && e.name === 'AbortError') {
      return;
    }
    // Stream dropped — the cleanup abort ends the effect; otherwise ignore.
  }
}

function extractDataLine(part: string): string {
  const trimmed = part.trim();
  if (!trimmed || trimmed.startsWith(':')) {
    return '';
  }
  let data = '';
  for (const line of trimmed.split('\n')) {
    if (line.startsWith('data:')) {
      data += (data ? '\n' : '') + line.slice(5).trim();
    }
  }
  return data;
}

function handleLine(dataLine: string, onReasoning: (runId: string, delta: string) => void) {
  try {
    const parsed = JSON.parse(dataLine);
    const evtType = parsed.eventType || parsed.type || '';
    if (evtType !== 'reasoning_token') {
      return;
    }
    const d = parsed.data || parsed;
    if (d?.reasoningType === REASONING_SYSTEM_TYPE) {
      return;
    }
    const runId = d?.runId != null ? String(d.runId) : '';
    const response: unknown = d?.response;
    if (!runId || typeof response !== 'string' || !response) {
      return;
    }
    if (response.startsWith(I18N_PREFIX)) {
      return;
    }
    onReasoning(runId, response);
  } catch {
    // ignore malformed lines
  }
}
