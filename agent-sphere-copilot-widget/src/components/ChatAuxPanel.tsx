import { useMemo } from 'react';
import { useAgent } from '@copilotkit/react-core/v2';
import type { SessionTodoVO } from '../types';

interface ChatAuxPanelProps {
  agentId: string;
  /** 会话切换时 REST 播种的历史 todos（STATE_SNAPSHOT 事件到达后被覆盖） */
  initialTodos: SessionTodoVO[];
}

interface ToolCallItem {
  id: string;
  name: string;
  status: 'executing' | 'complete';
  result?: string;
}

interface AgentLike {
  state?: Record<string, unknown>;
  messages?: unknown[];
}

function extractToolCalls(messages: unknown[]): ToolCallItem[] {
  const list = messages as {
    role?: string;
    toolCalls?: { id?: string; function?: { name?: string } }[];
    toolCallId?: string;
    content?: unknown;
  }[];
  const out: ToolCallItem[] = [];
  for (const m of list) {
    if (m?.role === 'assistant' && Array.isArray(m.toolCalls)) {
      for (const tc of m.toolCalls) {
        const toolMsg = list.find((x) => x?.role === 'tool' && x?.toolCallId === tc.id);
        const result = typeof toolMsg?.content === 'string' ? toolMsg.content.slice(0, 120) : undefined;
        out.push({
          id: String(tc.id ?? ''),
          name: tc.function?.name ?? 'unknown',
          status: toolMsg ? 'complete' : 'executing',
          result,
        });
      }
    }
  }
  return out;
}

export function ChatAuxPanel({ agentId, initialTodos }: ChatAuxPanelProps) {
  const { agent } = useAgent({ agentId });

  const stateTodos = (agent as AgentLike | undefined)?.state?.todos as
    | SessionTodoVO[]
    | undefined;
  const todos = stateTodos ?? initialTodos ?? [];

  const toolCalls = useMemo(
    () => extractToolCalls((agent as AgentLike | undefined)?.messages ?? []),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [(agent as AgentLike | undefined)?.messages],
  );

  return (
    <>
      {todos.length > 0 && (
        <div className="aw-aux aw-todos">
          <div className="aw-aux-title">任务清单</div>
          <div className="aw-aux-body">
            {todos.map((t, i) => (
              <div
                key={t.id ?? i}
                className={
                  t.status === 'completed' ? 'aw-todo aw-todo-done' : 'aw-todo'
                }
              >
                <span className="aw-todo-content">{t.content}</span>
                {t.priority ? (
                  <span className={`aw-pri aw-pri-${t.priority}`}>{t.priority}</span>
                ) : null}
              </div>
            ))}
          </div>
        </div>
      )}
      {toolCalls.length > 0 && (
        <div className="aw-aux aw-toolcalls">
          <div className="aw-aux-title">工具调用</div>
          <div className="aw-aux-body">
            {toolCalls.map((tc) => (
              <div key={tc.id} className="aw-toolcall">
                <span
                  className={`aw-tool-status aw-tool-status-${tc.status}`}
                />
                <span className="aw-tool-name">{tc.name}</span>
                {tc.result ? (
                  <span className="aw-tool-result">{tc.result}</span>
                ) : null}
              </div>
            ))}
          </div>
        </div>
      )}
    </>
  );
}
