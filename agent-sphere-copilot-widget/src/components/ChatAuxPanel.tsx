import { useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
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

interface HoverTip {
  text: string;
  x: number;
  y: number;
  maxWidth: number;
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
        const result =
          typeof toolMsg?.content === 'string' ? toolMsg.content : undefined;
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
  const [tip, setTip] = useState<HoverTip | null>(null);
  const auxRef = useRef<HTMLDivElement>(null);
  const layerRef = useRef<HTMLDivElement | null>(null);

  const stateTodos = (agent as AgentLike | undefined)?.state?.todos as
    | SessionTodoVO[]
    | undefined;
  const todos = stateTodos ?? initialTodos ?? [];

  const toolCalls = useMemo(
    () => extractToolCalls((agent as AgentLike | undefined)?.messages ?? []),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [(agent as AgentLike | undefined)?.messages],
  );

  if (todos.length === 0 && toolCalls.length === 0) {
    return null;
  }

  /** 悬浮弹层挂到 shadow root 顶层（相对于该 layer 定位，transform 祖先无关） */
  const ensureLayer = (): HTMLDivElement | null => {
    if (layerRef.current) {
      return layerRef.current;
    }
    const root = auxRef.current?.getRootNode();
    if (root instanceof ShadowRoot) {
      const div = document.createElement('div');
      div.className = 'aw-tooltip-layer';
      root.appendChild(div);
      layerRef.current = div;
    }
    return layerRef.current;
  };

  const showTip = (el: HTMLElement, text: string) => {
    const card = auxRef.current;
    const layer = ensureLayer();
    if (!card || !layer) {
      return;
    }
    const rect = el.getBoundingClientRect();
    const layerRect = layer.getBoundingClientRect();
    const cardRect = card.getBoundingClientRect();
    const maxWidth = Math.max(
      120,
      Math.min(320, cardRect.right - rect.left - 8),
    );
    setTip({
      text,
      x: rect.left - layerRect.left,
      y: rect.top - layerRect.top - 8,
      maxWidth,
    });
  };

  return (
    <div className="aw-aux" ref={auxRef}>
      <div className="aw-aux-cols">
        <div className="aw-aux-col">
          <div className="aw-aux-col-title">
            任务清单{todos.length > 0 ? `（${todos.length}）` : ''}
          </div>
          <div className="aw-aux-body">
            {todos.map((t, i) => (
              <div
                key={t.id ?? i}
                className={
                  t.status === 'completed' ? 'aw-todo aw-todo-done' : 'aw-todo'
                }
                onMouseEnter={(e) => {
                  const el = e.currentTarget.querySelector(
                    '.aw-todo-content',
                  ) as HTMLElement | null;
                  if (el) {
                    showTip(el, t.content);
                  }
                }}
                onMouseLeave={() => setTip(null)}
              >
                <span className="aw-todo-content">{t.content}</span>
                {t.priority ? (
                  <span className={`aw-pri aw-pri-${t.priority}`}>{t.priority}</span>
                ) : null}
              </div>
            ))}
          </div>
        </div>
        <div className="aw-aux-col">
          <div className="aw-aux-col-title">
            工具调用{toolCalls.length > 0 ? `（${toolCalls.length}）` : ''}
          </div>
          <div className="aw-aux-body">
            {toolCalls.map((tc) => (
              <div
                key={tc.id}
                className="aw-toolcall"
                onMouseEnter={(e) => {
                  const row = e.currentTarget;
                  const nameEl = row.querySelector(
                    '.aw-tool-name',
                  ) as HTMLElement | null;
                  if (nameEl) {
                    const full = tc.result ? `${tc.name}：${tc.result}` : tc.name;
                    showTip(nameEl, full);
                  }
                }}
                onMouseLeave={() => setTip(null)}
              >
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
      </div>
      {tip && layerRef.current
        ? createPortal(
            <div
              className="aw-tooltip"
              style={{ left: tip.x, top: tip.y, maxWidth: tip.maxWidth }}
            >
              {tip.text}
            </div>,
            layerRef.current,
          )
        : null}
    </div>
  );
}
