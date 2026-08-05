import { useEffect, useMemo, useState } from 'react';
import { HttpAgent, type AbstractAgent } from '@ag-ui/client';
import { CopilotChat, CopilotKit } from '@copilotkit/react-core/v2';
import { ApiError, createApi } from '../api';
import type { WidgetConfig } from '../config';
import type { InstanceVO, SessionVO, UserVO } from '../types';

interface CopilotViewProps {
  config: WidgetConfig;
  user: UserVO;
  onLogout: () => void;
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
          <CopilotKit
            runtimeUrl={undefined}
            selfManagedAgents={agents}
            headers={{ Authorization: `Bearer ${user.token}` }}
          >
            <CopilotChat
              agentId={String(selectedAgentId)}
              threadId={String(selectedSession.id)}
            />
          </CopilotKit>
        </div>
      ) : (
        <div className="aw-empty">
          {!loading ? '选择或新建一个会话开始对话' : ''}
        </div>
      )}
    </div>
  );
}