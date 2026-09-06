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
  /** @param nodeName 子 Agent（skill）thinking 事件带 nodeName=skill:<id>，用于前端内嵌折叠块 */
  onReasoning: (runId: string, delta: string, nodeName?: string) => void;
  /** 任务 run 结束（run_completed/run_failed/run_cancelled）时回调，用于复位流式状态。 */
  onRunEnded?: (runId: string) => void;
  /** 原始 SSE 事件透传：用于子 Agent reply(content_token) / tool_call 等按需路由。 */
  onEvent?: (parsed: Record<string, unknown>) => void;
  /** 流成功建立（拿到响应体）时回调，便于诊断。 */
  onOpen?: () => void;
  signal: AbortSignal;
}

const REASONING_SYSTEM_TYPE = 'system';
const I18N_PREFIX = '__i18n:';
const RUN_ENDED_SUB_TYPES = new Set([
  'run_completed',
  'run_failed',
  'run_cancelled',
]);

export async function connectReasoningStream(options: ReasoningStreamOptions): Promise<void> {
  const { url, token, onReasoning, onRunEnded, onOpen, onEvent, signal } = options;
  try {
    const response = await fetch(url, {
      headers: {
        Authorization: `Bearer ${token}`,
        Accept: 'text/event-stream',
        // 强制 identity，防止代理/服务器对 SSE 做 gzip 压缩导致字节流乱码、JSON 解析静默失败
        'Accept-Encoding': 'identity',
        'Cache-Control': 'no-cache',
      },
      signal,
    });
    if (!response.ok || !response.body) {
      return;
    }
    onOpen?.();
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
        handleLine(dataLine, onReasoning, onRunEnded, onEvent);
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

function handleLine(
  dataLine: string,
  onReasoning: (runId: string, delta: string, nodeName?: string) => void,
  onRunEnded?: (runId: string) => void,
  onEvent?: (parsed: Record<string, unknown>) => void,
) {
  try {
    const parsed = JSON.parse(dataLine);
    const evtType = parsed.eventType || parsed.type || '';
    if (evtType !== 'reasoning_token') {
      onEvent?.(parsed);
      return;
    }
    const d = parsed.data || parsed;
    const runId = d?.runId != null ? String(d.runId) : '';
    if (!runId) {
      return;
    }
    // 任务 run 生命周期终态（system 类型）：先于 model-reasoning 过滤识别，用于复位流式状态
    const subType: unknown = d?.reasoningSubType;
    if (typeof subType === 'string' && RUN_ENDED_SUB_TYPES.has(subType)) {
      onRunEnded?.(runId);
      return;
    }
    if (d?.reasoningType === REASONING_SYSTEM_TYPE) {
      return;
    }
    const response: unknown = d?.response;
    if (typeof response !== 'string' || !response) {
      return;
    }
    if (response.startsWith(I18N_PREFIX)) {
      return;
    }
    const nodeName: unknown = d?.nodeName;
    onReasoning(runId, response, typeof nodeName === 'string' ? nodeName : undefined);
  } catch {
    // ignore malformed lines
  }
}
