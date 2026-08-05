import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { UIEvent } from 'react';
import { HttpAgent, type AbstractAgent } from '@ag-ui/client';
import { CopilotChat, CopilotKit } from '@copilotkit/react-core/v2';
import { ApiError, createApi } from '../api';
import type { WidgetConfig } from '../config';
import type { InstanceVO, RunVO, SessionVO, UserVO } from '../types';

const HISTORY_PAGE_SIZE = 3;
const SCROLL_TOP_THRESHOLD = 40;
const ACTIVE_SESSION_KEY = 'agent-sphere-widget:active-session';

interface CopilotViewProps {
  config: WidgetConfig;
  user: UserVO;
  onLogout: () => void;
}

type ChatMessage = Parameters<AbstractAgent['setMessages']>[0][number];

function toChatMessages(runs: RunVO[]): ChatMessage[] {
  const messages: ChatMessage[] = [];
  for (const r of runs) {
    if (r.userMessage && r.userMessage !== '{}' && r.userMessage.trim()) {
      messages.push({ id: `u-${r.id}`, role: 'user', content: r.userMessage });
    }
    if (r.assistantReply && r.assistantReply !== '{}' && r.assistantReply.trim()) {
      messages.push({ id: `a-${r.id}`, role: 'assistant', content: r.assistantReply });
    }
  }
  return messages;
}

export function CopilotView({ config, user, onLogout }: CopilotViewProps) {
  const api = useMemo(
    () => createApi(config),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [config.apiBase, config.provider],
  );
  const apiBase = config.apiBase ?? '/api/v1';

  const [instances, setInstances] = useState<InstanceVO[]>([]);
  const [sessions, setSessions] = useState<SessionVO[]>([]);
  const [selectedAgentId, setSelectedAgentId] = useState<number | null>(null);
  const [selectedSessionId, setSelectedSessionId] = useState<number | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const historyPageRef = useRef(2);
  const [hasMoreHistory, setHasMoreHistory] = useState(false);
  const [loadingHistory, setLoadingHistory] = useState(false);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      setLoading(true);
      setError(null);
      try {
        const list = await api.listInstances();
        if (cancelled) {
          return;
        }
        const active = list.filter((i) => i.status === 'ENABLED');
        setInstances(active);
        if (active.length > 0) {
          setSelectedAgentId((prev) => prev ?? active[0].id);
        }
      } catch (err) {
        if (!cancelled) {
          setError((err as ApiError).message);
        }
      } finally {
        if (!cancelled) {
          setLoading(false);
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [api]);

  useEffect(() => {
    if (selectedAgentId === null) {
      return;
    }
    let cancelled = false;
    (async () => {
      setLoading(true);
      setError(null);
      try {
        const all = await api.listSessions();
        if (cancelled) {
          return;
        }
        const filtered = all.filter((s) => s.agentInstanceId === selectedAgentId);
        setSessions(filtered);
        setSelectedSessionId((prev) =>
          prev && filtered.some((s) => s.id === prev)
            ? prev
            : filtered.length > 0
              ? filtered[0].id
              : null,
        );
      } catch (err) {
        if (!cancelled) {
          setError((err as ApiError).message);
        }
      } finally {
        if (!cancelled) {
          setLoading(false);
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [api, selectedAgentId]);

  const agents = useMemo(() => {
    const record: Record<string, AbstractAgent> = {};
    for (const inst of instances) {
      record[String(inst.id)] = new HttpAgent({
        url: `${apiBase}/copilot/agent/${inst.id}/services/chat/run`,
        headers: { Authorization: `Bearer ${user.token}` },
      });
    }
    return record;
  }, [instances, apiBase, user.token]);

  const handleCreateSession = async () => {
    if (selectedAgentId === null) {
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const created = await api.createSession(selectedAgentId, '新的会话');
      setSessions((prev) => [...prev, created]);
      setSelectedSessionId(created.id);
    } catch (err) {
      setError((err as ApiError).message);
    } finally {
      setLoading(false);
    }
  };

  // 通知 chrome-extension 当前会话（复用其 1s 轮询的 checkAuth）
  useEffect(() => {
    if (selectedSessionId !== null) {
      try {
        sessionStorage.setItem(ACTIVE_SESSION_KEY, String(selectedSessionId));
      } catch {
        // ignore storage unavailable
      }
    }
    return () => {
      try {
        sessionStorage.removeItem(ACTIVE_SESSION_KEY);
      } catch {
        // ignore
      }
    };
  }, [selectedSessionId]);

  // 加载当前会话历史（第一页=最新），并清掉上个会话残留消息
  useEffect(() => {
    if (selectedAgentId === null || selectedSessionId === null) {
      return;
    }
    const agent = agents[String(selectedAgentId)];
    if (!agent) {
      return;
    }
    let cancelled = false;
    agent.setMessages([]);
    historyPageRef.current = 2;
    setHasMoreHistory(false);
    setLoadingHistory(true);
    (async () => {
      try {
        const res = await api.listRuns(selectedSessionId, 1, HISTORY_PAGE_SIZE);
        if (cancelled) {
          return;
        }
        agent.setMessages(toChatMessages(res.records.slice().reverse()));
        setHasMoreHistory(res.records.length >= HISTORY_PAGE_SIZE);
      } catch {
        // 历史加载失败不影响新对话
      } finally {
        if (!cancelled) {
          setLoadingHistory(false);
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [selectedAgentId, selectedSessionId, agents, api]);

  const loadOlder = useCallback(async () => {
    if (loadingHistory || !hasMoreHistory) {
      return;
    }
    if (selectedAgentId === null || selectedSessionId === null) {
      return;
    }
    const agent = agents[String(selectedAgentId)];
    if (!agent) {
      return;
    }
    setLoadingHistory(true);
    try {
      const res = await api.listRuns(
        selectedSessionId,
        historyPageRef.current,
        HISTORY_PAGE_SIZE,
      );
      const older = toChatMessages(res.records.slice().reverse());
      const current = agent.messages as ChatMessage[];
      agent.setMessages([...older, ...current]);
      historyPageRef.current += 1;
      setHasMoreHistory(res.records.length >= HISTORY_PAGE_SIZE);
    } catch {
      // ignore
    } finally {
      setLoadingHistory(false);
    }
  }, [loadingHistory, hasMoreHistory, selectedAgentId, selectedSessionId, agents, api]);

  const handleScrollUp = (event: UIEvent<HTMLDivElement>) => {
    const el = event.target as HTMLElement;
    if (el.scrollHeight > el.clientHeight && el.scrollTop < SCROLL_TOP_THRESHOLD) {
      void loadOlder();
    }
  };

  const selectedSession = sessions.find((s) => s.id === selectedSessionId) ?? null;

  return (
    <div className="aw-view">
      <div className="aw-header">
        <select
          className="aw-select"
          value={selectedAgentId ?? ''}
          onChange={(e) => {
            setSelectedAgentId(Number(e.target.value));
            setSelectedSessionId(null);
          }}
          disabled={instances.length === 0}
          aria-label="选择助手"
        >
          {instances.length === 0 ? <option value="">暂无可用助手</option> : null}
          {instances.map((i) => (
            <option key={i.id} value={i.id}>
              {i.name}
            </option>
          ))}
        </select>
        <button type="button" className="aw-button" onClick={() => void handleCreateSession()}>
          新会话
        </button>
        <button type="button" className="aw-button aw-button-ghost" onClick={onLogout}>
          退出
        </button>
      </div>

      <select
        className="aw-select aw-session-picker"
        value={selectedSessionId ?? ''}
        onChange={(e) => setSelectedSessionId(Number(e.target.value))}
        disabled={sessions.length === 0}
        aria-label="选择会话"
      >
        {sessions.length === 0 ? <option value="">暂无会话，请新建</option> : null}
        {sessions.map((s) => (
          <option key={s.id} value={s.id}>
            {s.title}
          </option>
        ))}
      </select>

      {error ? <div className="aw-error">{error}</div> : null}
      {loading ? <div className="aw-loading">加载中…</div> : null}

      {selectedSessionId !== null && selectedSession ? (
        <div className="aw-chat">
          {hasMoreHistory ? (
            <button
              type="button"
              className="aw-load-more"
              onClick={() => void loadOlder()}
              disabled={loadingHistory}
            >
              {loadingHistory ? '正在加载更早消息…' : '加载更早消息'}
            </button>
          ) : null}
          <div className="aw-chat-body" onScroll={handleScrollUp}>
            <CopilotKit
              runtimeUrl={undefined}
              selfManagedAgents={agents}
              headers={{ Authorization: `Bearer ${user.token}` }}
            >
              <CopilotChat
                key={String(selectedSession.id)}
                agentId={String(selectedAgentId)}
                threadId={String(selectedSession.id)}
              />
            </CopilotKit>
          </div>
        </div>
      ) : (
        <div className="aw-empty">
          {!loading ? '选择或新建一个会话开始对话' : ''}
        </div>
      )}
    </div>
  );
}
